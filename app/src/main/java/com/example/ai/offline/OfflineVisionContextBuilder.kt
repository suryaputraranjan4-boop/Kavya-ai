package com.example.ai.offline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.ai.OfflinePrivacyGuard
import com.example.services.KavyaAccessibilityService
import com.example.visual.OnDeviceOcrExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class OfflineVisionAnalysis(
    val succeeded: Boolean,
    val contextSummary: String,
    val enginesUsed: List<String>,
    val errorReason: String? = null,
    val ocrSnippets: List<String> = emptyList(),
    val detectedRegions: List<String> = emptyList(),
    val directVlmAnswer: String? = null
)

/**
 * Integrates genuine local vision technologies into the offline conversation and visual-UI
 * pipeline without ever sending screenshots or images to cloud endpoints:
 *
 * 1. SmolVLM2 2.2B GGUF + mmproj multimodal VLM (when installed & native runtime available)
 * 2. Florence-2-base-ft ONNX bundle (`<OD>` / `<CAPTION>` / `<OCR>`) (when installed)
 * 3. ML Kit on-device OCR (Latin + Devanagari/Hindi) via [OnDeviceOcrExtractor]
 * 4. Android Accessibility hierarchy context (when available)
 *
 * Returns structured visual context for local LLM reasoning or direct VLM answers, and reports
 * clear diagnostics when no local vision signal or model is available rather than silently ignoring images.
 */
object OfflineVisionContextBuilder {

    private const val TAG = "OfflineVisionContext"

    /**
     * Test hook for deterministic JVM/Robolectric verification of offline image routing.
     */
    @Volatile
    internal var testCustomVisionAnalyzer: (suspend (Bitmap, String, String?) -> OfflineVisionAnalysis)? = null

    suspend fun analyzeImageBytes(
        context: Context,
        imageBytes: ByteArray,
        userPrompt: String,
        accessibilityContext: String? = null
    ): OfflineVisionAnalysis = withContext(Dispatchers.Default) {
        if (imageBytes.isEmpty()) {
            return@withContext OfflineVisionAnalysis(
                succeeded = false,
                contextSummary = "",
                enginesUsed = emptyList(),
                errorReason = "Offline vision received an empty image payload."
            )
        }
        val bitmap = try {
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to decode image bytes for offline vision: ${t.message}")
            null
        }
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
            return@withContext OfflineVisionAnalysis(
                succeeded = false,
                contextSummary = "",
                enginesUsed = emptyList(),
                errorReason = "Could not decode screenshot/image bytes for local offline vision analysis."
            )
        }
        try {
            analyzeBitmap(
                context = context,
                bitmap = bitmap,
                userPrompt = userPrompt,
                accessibilityContext = accessibilityContext
            )
        } finally {
            // Bitmap was decoded locally inside this method; safe to recycle after inference.
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    /**
     * Analyzes a caller-owned [Bitmap] strictly on-device. Never recycles the caller's bitmap.
     */
    suspend fun analyzeBitmap(
        context: Context,
        bitmap: Bitmap,
        userPrompt: String,
        accessibilityContext: String? = null
    ): OfflineVisionAnalysis = withContext(Dispatchers.Default) {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return@withContext OfflineVisionAnalysis(
                succeeded = false,
                contextSummary = "",
                enginesUsed = emptyList(),
                errorReason = "Screenshot bitmap is invalid or recycled."
            )
        }

        val customHook = testCustomVisionAnalyzer
        if (customHook != null) {
            val res = customHook.invoke(bitmap, userPrompt, accessibilityContext)
            if (res.succeeded) {
                OfflinePrivacyGuard.recordEngineAttribution("LOCAL_VISION:${res.enginesUsed.joinToString("+")}")
            }
            return@withContext res
        }

        val appCtx = context.applicationContext ?: context
        val enginesUsed = mutableListOf<String>()
        val diagnostics = mutableListOf<String>()
        val ocrLines = mutableListOf<String>()
        val regionSummaries = mutableListOf<String>()
        var directVlmAnswer: String? = null

        val liveA11y = accessibilityContext?.takeIf { it.isNotBlank() }
            ?: runCatching { KavyaAccessibilityService.instance?.getScreenContext() }.getOrNull()?.takeIf { it.isNotBlank() }

        // 1. On-device ML Kit OCR (Latin + Devanagari/Hindi)
        val ocrSignals = try {
            OnDeviceOcrExtractor.extractPixelOcrSignals(bitmap, bitmap.width, bitmap.height)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            diagnostics.add("ML Kit OCR unavailable (${t.javaClass.simpleName})")
            emptyList()
        }
        if (ocrSignals.isNotEmpty()) {
            enginesUsed.add("MLKit-OnDevice-OCR")
            ocrSignals.take(60).forEach { sig ->
                val clean = sig.text.trim()
                if (clean.isNotBlank()) {
                    ocrLines.add("\"$clean\" @(${sig.bounds.centerX()},${sig.bounds.centerY()})")
                }
            }
        }

        val engine = Gemma4E4BEngine.getInstance()
        val baseDir = GemmaModelManager.getModelDirectory(appCtx)

        // 2. SmolVLM2 2.2B GGUF + mmproj multimodal VLM (if installed)
        val smolReport = GemmaModelManager.verifySmolVlm2Package(baseDir, verifyChecksums = false)
        if (smolReport.isInstalledAndValid) {
            val loaded = if (engine.isSmolVlm2Ready()) {
                Result.success("READY")
            } else {
                engine.loadSmolVlm2GuiEngine(context = appCtx, baseDir = baseDir, verifyChecksums = false)
            }
            if (loaded.isSuccess) {
                val frame = LlamaCppEngine.preprocessScreenshotForSmolVlm2(bitmap)
                val vlmPrompt = LlamaCppEngine.formatSmolVlm2Prompt(
                    userGoal = userPrompt,
                    untrustedScreenSummary = buildString {
                        if (ocrLines.isNotEmpty()) appendLine("OCR: ${ocrLines.take(20).joinToString("; ")}")
                        if (!liveA11y.isNullOrBlank()) appendLine("UI: ${liveA11y.take(1200)}")
                    }.ifBlank { "Analyze the provided screenshot accurately." }
                )
                val vlmRes = LlamaCppEngine.getInstance().generateMultimodal(frame, vlmPrompt, maxTokens = 256)
                val answer = vlmRes.getOrNull()?.trim()
                if (!answer.isNullOrBlank()) {
                    enginesUsed.add("SmolVLM2-2.2B-GGUF")
                    directVlmAnswer = answer
                } else {
                    diagnostics.add("SmolVLM2 multimodal inference failed: ${vlmRes.exceptionOrNull()?.message ?: "empty output"}")
                }
            } else {
                diagnostics.add("SmolVLM2 installed but could not load: ${loaded.exceptionOrNull()?.message}")
            }
        } else {
            diagnostics.add("SmolVLM2 not installed (${smolReport.statusCode})")
        }

        // 3. Florence-2-base-ft ONNX bundle (if installed and SmolVLM2 didn't produce a direct VLM answer)
        if (directVlmAnswer.isNullOrBlank()) {
            val florenceReport = GemmaModelManager.verifyFlorence2Package(baseDir, verifyChecksums = false)
            if (florenceReport.isInstalledAndValid) {
                val loaded = if (engine.isFlorence2Ready()) {
                    Result.success("READY")
                } else {
                    engine.loadFlorence2AuxEngine(context = appCtx, baseDir = baseDir)
                }
                if (loaded.isSuccess) {
                    val pass = engine.runFlorence2AuxiliaryPass(bitmap, taskPrompt = "<OD>")
                    val signals = pass.getOrNull().orEmpty()
                    if (signals.isNotEmpty()) {
                        enginesUsed.add("Florence-2-ONNX")
                        signals.take(30).forEach { elem ->
                            val lbl = elem.label.ifBlank { elem.roleHint }.ifBlank { "element" }
                            regionSummaries.add("$lbl @(${elem.bounds.centerX()},${elem.bounds.centerY()})")
                        }
                    } else {
                        diagnostics.add("Florence-2 pass returned no regions: ${pass.exceptionOrNull()?.message ?: "none"}")
                    }
                } else {
                    diagnostics.add("Florence-2 installed but could not load: ${loaded.exceptionOrNull()?.message}")
                }
            } else {
                diagnostics.add("Florence-2 not installed (${florenceReport.statusCode})")
            }
        }

        if (!liveA11y.isNullOrBlank()) {
            enginesUsed.add("Android-Accessibility-Tree")
        }

        val hasAnyVisualSignal = !directVlmAnswer.isNullOrBlank() ||
            ocrLines.isNotEmpty() ||
            regionSummaries.isNotEmpty() ||
            !liveA11y.isNullOrBlank()

        if (!hasAnyVisualSignal) {
            val msg = "LOCAL OFFLINE VISION UNAVAILABLE: No local vision model (SmolVLM2 or Florence-2) is installed and on-device OCR/Accessibility extracted no readable content from this ${bitmap.width}x${bitmap.height} image. Download SmolVLM2 or Florence-2 in Offline Models while online, or disable Strict Offline Mode for cloud vision. (${diagnostics.joinToString("; ")})"
            return@withContext OfflineVisionAnalysis(
                succeeded = false,
                contextSummary = "",
                enginesUsed = emptyList(),
                errorReason = msg
            )
        }

        OfflinePrivacyGuard.recordEngineAttribution("LOCAL_VISION:${enginesUsed.joinToString("+")}")

        val contextBlock = buildString {
            appendLine("[LOCAL OFFLINE VISUAL EVIDENCE — ${bitmap.width}x${bitmap.height} — Engines: ${enginesUsed.joinToString(", ")}]")
            if (!directVlmAnswer.isNullOrBlank()) {
                appendLine("LOCAL VLM ANALYSIS (SmolVLM2):")
                appendLine(directVlmAnswer)
            }
            if (regionSummaries.isNotEmpty()) {
                appendLine("DETECTED VISUAL REGIONS (Florence-2):")
                appendLine(regionSummaries.joinToString(" | "))
            }
            if (ocrLines.isNotEmpty()) {
                appendLine("EXTRACTED ON-DEVICE OCR TEXT + COORDINATES:")
                appendLine(ocrLines.joinToString("\n"))
            }
            if (!liveA11y.isNullOrBlank()) {
                appendLine("ACCESSIBILITY UI HIERARCHY:")
                appendLine(liveA11y.take(2000))
            }
        }.trim()

        OfflineVisionAnalysis(
            succeeded = true,
            contextSummary = contextBlock,
            enginesUsed = enginesUsed,
            ocrSnippets = ocrLines,
            detectedRegions = regionSummaries,
            directVlmAnswer = directVlmAnswer
        )
    }
}
