package com.example.ai.offline

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.orchestrator.ScreenTextSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Kotlin/JNI bridge to the official llama.cpp runtime.
 * Supports both text-only GGUF models and multimodal GGUF + mmproj vision-language models
 * (`HuggingFaceTB/SmolVLM2-2.2B-Instruct-Agentic-GUI`).
 */
class LlamaCppEngine private constructor() {

    data class PreprocessedVlmFrame(
        val rgbBytes: ByteArray,
        val processedWidth: Int,
        val processedHeight: Int,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val patchGridCols: Int,
        val patchGridRows: Int,
        val patchSizePx: Int = SMOLVLM2_PATCH_SIZE
    )

    data class GroundingPrediction(
        val action: String,
        val normalizedX: Float,
        val normalizedY: Float,
        val normalizedBox: FloatArray? = null,
        val deviceX: Int,
        val deviceY: Int,
        val deviceBounds: Rect,
        val confidence: Float,
        val targetLabel: String,
        val rawModelOutput: String
    )

    companion object {
        const val SMOLVLM2_MAX_LONGEST_EDGE = 1152
        const val SMOLVLM2_PATCH_SIZE = 384

        @Volatile private var instance: LlamaCppEngine? = null
        @Volatile private var isNativeLoaded = false

        fun getInstance(): LlamaCppEngine =
            instance ?: synchronized(this) {
                instance ?: LlamaCppEngine().also { instance = it }
            }

        fun isNativeAvailable(): Boolean = isNativeLoaded

        init {
            try {
                System.loadLibrary("kavya_llama")
                isNativeLoaded = true
            } catch (_: Throwable) {
                isNativeLoaded = false
            }
        }

        fun hasValidGgufMagic(file: File): Boolean {
            if (!file.exists() || !file.isFile || file.length() < 4L) return false
            return try {
                FileInputStream(file).use { input ->
                    val magic = ByteArray(4)
                    input.read(magic) == 4 &&
                        magic[0] == 'G'.code.toByte() &&
                        magic[1] == 'G'.code.toByte() &&
                        magic[2] == 'U'.code.toByte() &&
                        magic[3] == 'F'.code.toByte()
                }
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Aspect-ratio-preserving SmolVLM2 image preprocessor.
         * Scales the longest edge to <= 1152 and aligns dimensions to multiples of 384px patches
         * without distorting aspect ratio coordinates when mapping back to physical display pixels.
         */
        fun preprocessScreenshotForSmolVlm2(
            bitmap: Bitmap,
            maxLongestEdge: Int = SMOLVLM2_MAX_LONGEST_EDGE,
            patchSize: Int = SMOLVLM2_PATCH_SIZE
        ): PreprocessedVlmFrame {
            val srcW = bitmap.width.coerceAtLeast(1)
            val srcH = bitmap.height.coerceAtLeast(1)
            val longest = max(srcW, srcH)
            val scale = if (longest > maxLongestEdge) {
                maxLongestEdge.toFloat() / longest.toFloat()
            } else {
                1.0f
            }

            val scaledW = (srcW * scale).roundToInt().coerceAtLeast(1)
            val scaledH = (srcH * scale).roundToInt().coerceAtLeast(1)

            val cols = max(1, ((scaledW + patchSize / 2) / patchSize).coerceAtMost(maxLongestEdge / patchSize))
            val rows = max(1, ((scaledH + patchSize / 2) / patchSize).coerceAtMost(maxLongestEdge / patchSize))

            val resized = if (scaledW == srcW && scaledH == srcH) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
            }

            val pixels = IntArray(scaledW * scaledH)
            resized.getPixels(pixels, 0, scaledW, 0, 0, scaledW, scaledH)
            if (resized !== bitmap) {
                resized.recycle()
            }

            val rgb = ByteArray(scaledW * scaledH * 3)
            var outIdx = 0
            for (px in pixels) {
                rgb[outIdx++] = ((px shr 16) and 0xFF).toByte()
                rgb[outIdx++] = ((px shr 8) and 0xFF).toByte()
                rgb[outIdx++] = (px and 0xFF).toByte()
            }

            return PreprocessedVlmFrame(
                rgbBytes = rgb,
                processedWidth = scaledW,
                processedHeight = scaledH,
                sourceWidth = srcW,
                sourceHeight = srcH,
                patchGridCols = cols,
                patchGridRows = rows,
                patchSizePx = patchSize
            )
        }

        /**
         * Formats a SmolVLM2-2.2B-Instruct-Agentic-GUI prompt using its canonical chat template
         * and sanitizes untrusted screen text before interpolation.
         */
        fun formatSmolVlm2Prompt(
            userGoal: String,
            untrustedScreenSummary: String = "",
            candidateElementsSummary: String = ""
        ): String {
            val safeScreen = ScreenTextSanitizer.sanitizeForPrompt(untrustedScreenSummary, maxChars = 1200)
            val safeCandidates = ScreenTextSanitizer.sanitizeForPrompt(candidateElementsSummary, maxChars = 1200)
            return buildString {
                append("<|im_start|>User:<image>")
                append("You are an Android GUI grounding agent. ")
                append("Return ONLY a single JSON object with protocol_version=\"1.0\", action, target {label, coordinate_space=\"NORMALIZED_0_1\", point:[x,y], bbox:[xMin,yMin,xMax,yMax]}, and confidence.\n")
                append("User goal: ").append(userGoal.trim()).append("\n")
                if (safeScreen.isNotBlank()) {
                    append("Untrusted screen text: ").append(safeScreen).append("\n")
                }
                if (safeCandidates.isNotBlank()) {
                    append("Candidate UI elements: ").append(safeCandidates).append("\n")
                }
                append("<end_of_utterance>\nAssistant:")
            }
        }

        /**
         * Parses SmolVLM2 grounding output (canonical JSON or `click(x, y)` / `[x1, y1, x2, y2]` expressions)
         * into physical screen coordinates without ever inventing fallback center coordinates.
         */
        fun parseSmolVlm2GroundingOutput(
            rawOutput: String,
            screenWidth: Int,
            screenHeight: Int
        ): Result<GroundingPrediction> {
            val trimmed = rawOutput.trim()
            if (trimmed.isEmpty()) {
                return Result.failure(IllegalArgumentException("SmolVLM2 output is empty."))
            }

            // 1. Try canonical JSON object first
            val firstBrace = trimmed.indexOf('{')
            val lastBrace = trimmed.lastIndexOf('}')
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                try {
                    val obj = JSONObject(trimmed.substring(firstBrace, lastBrace + 1))
                    val action = obj.optString("action", "CLICK").uppercase()
                    val conf = obj.optDouble("confidence", 0.82).toFloat().coerceIn(0f, 1f)
                    val targetObj = obj.optJSONObject("target") ?: obj
                    val label = targetObj.optString("label", targetObj.optString("description", "")).trim()
                    val space = targetObj.optString("coordinate_space", "NORMALIZED_0_1").uppercase()
                    val scale = if (space.contains("1000")) 1000f else 1f

                    val pointArr = targetObj.optJSONArray("point")
                    val bboxArr = targetObj.optJSONArray("bbox")

                    var normX: Float? = null
                    var normY: Float? = null
                    var normBox: FloatArray? = null

                    if (bboxArr != null && bboxArr.length() == 4) {
                        val x1 = (bboxArr.getDouble(0).toFloat() / scale).coerceIn(0f, 1f)
                        val y1 = (bboxArr.getDouble(1).toFloat() / scale).coerceIn(0f, 1f)
                        val x2 = (bboxArr.getDouble(2).toFloat() / scale).coerceIn(0f, 1f)
                        val y2 = (bboxArr.getDouble(3).toFloat() / scale).coerceIn(0f, 1f)
                        if (x2 >= x1 && y2 >= y1) {
                            normBox = floatArrayOf(x1, y1, x2, y2)
                            normX = (x1 + x2) / 2f
                            normY = (y1 + y2) / 2f
                        }
                    }

                    if (pointArr != null && pointArr.length() >= 2) {
                        normX = (pointArr.getDouble(0).toFloat() / scale).coerceIn(0f, 1f)
                        normY = (pointArr.getDouble(1).toFloat() / scale).coerceIn(0f, 1f)
                    } else if (targetObj.has("x") && targetObj.has("y")) {
                        normX = (targetObj.getDouble("x").toFloat() / scale).coerceIn(0f, 1f)
                        normY = (targetObj.getDouble("y").toFloat() / scale).coerceIn(0f, 1f)
                    }

                    if (normX != null && normY != null) {
                        return Result.success(
                            buildPrediction(
                                action = action,
                                normX = normX,
                                normY = normY,
                                normBox = normBox,
                                screenWidth = screenWidth,
                                screenHeight = screenHeight,
                                confidence = conf,
                                label = label,
                                rawOutput = trimmed
                            )
                        )
                    }
                } catch (_: Exception) {
                    // Fall through to agentic function-call syntax parser
                }
            }

            // 2. Try agentic GUI call syntax: click(0.45, 0.22) or tap(450, 220)
            val callRegex = Regex("""(?i)\b(click|tap|long_click|double_click)\s*\(\s*([0-9]+(?:\.[0-9]+)?)\s*,\s*([0-9]+(?:\.[0-9]+)?)\s*\)""")
            val callMatch = callRegex.find(trimmed)
            if (callMatch != null) {
                val act = callMatch.groupValues[1].uppercase().let { if (it == "TAP") "CLICK" else it }
                val rawX = callMatch.groupValues[2].toFloat()
                val rawY = callMatch.groupValues[3].toFloat()
                val (nx, ny) = normalizeRawPair(rawX, rawY)
                return Result.success(
                    buildPrediction(
                        action = act,
                        normX = nx,
                        normY = ny,
                        normBox = null,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        confidence = 0.80f,
                        label = "grounded_target",
                        rawOutput = trimmed
                    )
                )
            }

            return Result.failure(IllegalArgumentException("Unable to parse valid GUI coordinates from SmolVLM2 output: $trimmed"))
        }

        private fun normalizeRawPair(rawX: Float, rawY: Float): Pair<Float, Float> {
            return if (rawX > 1.0f || rawY > 1.0f) {
                Pair((rawX / 1000f).coerceIn(0f, 1f), (rawY / 1000f).coerceIn(0f, 1f))
            } else {
                Pair(rawX.coerceIn(0f, 1f), rawY.coerceIn(0f, 1f))
            }
        }

        private fun buildPrediction(
            action: String,
            normX: Float,
            normY: Float,
            normBox: FloatArray?,
            screenWidth: Int,
            screenHeight: Int,
            confidence: Float,
            label: String,
            rawOutput: String
        ): GroundingPrediction {
            val maxW = (screenWidth - 1).coerceAtLeast(1)
            val maxH = (screenHeight - 1).coerceAtLeast(1)
            val devX = (normX * maxW).roundToInt().coerceIn(0, maxW)
            val devY = (normY * maxH).roundToInt().coerceIn(0, maxH)
            val rect = if (normBox != null && normBox.size == 4) {
                Rect(
                    (normBox[0] * maxW).roundToInt().coerceIn(0, maxW),
                    (normBox[1] * maxH).roundToInt().coerceIn(0, maxH),
                    (normBox[2] * maxW).roundToInt().coerceIn(0, maxW),
                    (normBox[3] * maxH).roundToInt().coerceIn(0, maxH)
                )
            } else {
                val half = 24
                Rect(
                    (devX - half).coerceAtLeast(0),
                    (devY - half).coerceAtLeast(0),
                    (devX + half).coerceAtMost(maxW),
                    (devY + half).coerceAtMost(maxH)
                )
            }
            return GroundingPrediction(
                action = action,
                normalizedX = normX,
                normalizedY = normY,
                normalizedBox = normBox,
                deviceX = devX,
                deviceY = devY,
                deviceBounds = rect,
                confidence = confidence,
                targetLabel = label,
                rawModelOutput = rawOutput
            )
        }
    }

    private var nativeHandle: Long = 0L
    private var loadedPath: String? = null
    private var loadedMmprojPath: String? = null
    private val nativeLock = Any()

    fun isReady(): Boolean = synchronized(nativeLock) { nativeHandle != 0L }

    fun isMultimodalLoaded(): Boolean = synchronized(nativeLock) {
        nativeHandle != 0L && !loadedMmprojPath.isNullOrBlank()
    }

    suspend fun load(modelFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        synchronized(nativeLock) {
            if (!modelFile.exists() || !modelFile.isFile) {
                return@withContext Result.failure(IllegalArgumentException("GGUF model file does not exist."))
            }
            if (!hasValidGgufMagic(modelFile)) {
                return@withContext Result.failure(IllegalArgumentException("Selected file does not contain a valid GGUF header."))
            }

            if (nativeHandle != 0L && loadedPath == modelFile.absolutePath && loadedMmprojPath == null) {
                return@withContext Result.success(Unit)
            }

            releaseLocked()

            if (!isNativeLoaded) {
                return@withContext Result.failure(IllegalStateException("Native llama.cpp library is not loaded."))
            }

            val handle = nativeLoad(modelFile.absolutePath)
            if (handle == 0L) {
                return@withContext Result.failure(
                    IllegalStateException("llama.cpp could not load this GGUF model.")
                )
            }

            nativeHandle = handle
            loadedPath = modelFile.absolutePath
            loadedMmprojPath = null
            Result.success(Unit)
        }
    }

    /**
     * Loads a multimodal GGUF model together with its mandatory companion `mmproj` projector GGUF.
     * Fails with `MMPROJ_MISSING` if `mmprojFile` is missing or not a valid GGUF file.
     */
    suspend fun loadMultimodalModel(
        modelFile: File,
        mmprojFile: File,
        contextSize: Int = 4096,
        threads: Int = 4
    ): Result<Unit> = withContext(Dispatchers.IO) {
        synchronized(nativeLock) {
            if (!modelFile.exists() || !modelFile.isFile) {
                return@withContext Result.failure(IllegalArgumentException("MODEL_NOT_INSTALLED: Main GGUF file does not exist."))
            }
            if (!mmprojFile.exists() || !mmprojFile.isFile) {
                return@withContext Result.failure(IllegalStateException("MMPROJ_MISSING: Companion mmproj GGUF file is missing (${mmprojFile.name})."))
            }
            if (!hasValidGgufMagic(modelFile)) {
                return@withContext Result.failure(IllegalArgumentException("MODEL_CORRUPT: Main file does not have a valid GGUF header."))
            }
            if (!hasValidGgufMagic(mmprojFile)) {
                return@withContext Result.failure(IllegalArgumentException("MODEL_CORRUPT: Companion mmproj file does not have a valid GGUF header."))
            }

            if (nativeHandle != 0L &&
                loadedPath == modelFile.absolutePath &&
                loadedMmprojPath == mmprojFile.absolutePath
            ) {
                return@withContext Result.success(Unit)
            }

            releaseLocked()

            if (!isNativeLoaded) {
                return@withContext Result.failure(
                    IllegalStateException("NATIVE_UNAVAILABLE: libkavya_llama.so is not loaded in this runtime.")
                )
            }

            val handle = nativeLoadMultimodal(
                modelFile.absolutePath,
                mmprojFile.absolutePath,
                contextSize,
                threads
            )
            if (handle == 0L) {
                return@withContext Result.failure(
                    IllegalStateException("llama.cpp could not initialize multimodal GGUF + mmproj session.")
                )
            }

            nativeHandle = handle
            loadedPath = modelFile.absolutePath
            loadedMmprojPath = mmprojFile.absolutePath
            Result.success(Unit)
        }
    }

    suspend fun generate(prompt: String, maxTokens: Int = 256): Result<String> =
        withContext(Dispatchers.Default) {
            synchronized(nativeLock) {
                if (nativeHandle == 0L) {
                    return@withContext Result.failure(
                        IllegalStateException("GGUF runtime is not loaded.")
                    )
                }
                val text = nativeGenerate(nativeHandle, prompt, maxTokens)
                if (text.isBlank()) {
                    Result.failure(IllegalStateException("GGUF inference returned no text."))
                } else {
                    Result.success(text.trim())
                }
            }
        }

    suspend fun generateMultimodal(
        frame: PreprocessedVlmFrame,
        prompt: String,
        maxTokens: Int = 192
    ): Result<String> = withContext(Dispatchers.Default) {
        synchronized(nativeLock) {
            if (nativeHandle == 0L || loadedMmprojPath.isNullOrBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("Multimodal GGUF + mmproj runtime is not loaded.")
                )
            }
            val text = nativeGenerateMultimodal(
                nativeHandle,
                frame.rgbBytes,
                frame.processedWidth,
                frame.processedHeight,
                prompt,
                maxTokens
            )
            if (text.isBlank()) {
                Result.failure(IllegalStateException("Multimodal GGUF inference returned empty output."))
            } else {
                Result.success(text.trim())
            }
        }
    }

    fun unloadMultimodalModel() {
        release()
    }

    fun release() {
        synchronized(nativeLock) {
            releaseLocked()
        }
    }

    private fun releaseLocked() {
        if (nativeHandle != 0L) {
            if (isNativeLoaded) {
                try {
                    nativeFree(nativeHandle)
                } catch (_: Throwable) {}
            }
            nativeHandle = 0L
            loadedPath = null
            loadedMmprojPath = null
        }
    }

    private external fun nativeLoad(modelPath: String): Long
    private external fun nativeLoadMultimodal(
        modelPath: String,
        mmprojPath: String,
        contextSize: Int,
        threads: Int
    ): Long
    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): String
    private external fun nativeGenerateMultimodal(
        handle: Long,
        rgbBytes: ByteArray,
        width: Int,
        height: Int,
        prompt: String,
        maxTokens: Int
    ): String
    private external fun nativeFree(handle: Long)
}
