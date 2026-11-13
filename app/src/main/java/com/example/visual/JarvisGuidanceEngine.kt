package com.example.visual

import android.content.Context
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import com.example.agent.FreshScreenRecognizer
import com.example.agent.UIElementResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

/**
 * State for JARVIS "Shows You How" interactive visual guidance.
 */
data class JarvisGuidanceState(
    val isActive: Boolean = false,
    val targetQuery: String = "",
    val targetLabel: String = "",
    val targetBounds: Rect? = null,
    val clickPoint: Pair<Int, Int>? = null,
    val instructionText: String = "",
    val stepIndex: Int = 1,
    val totalSteps: Int = 1,
    val isUserActionDetected: Boolean = false,
    val confidence: Float = 1.0f
)

/**
 * Rich Visual Explanation Card model for "JARVIS Explains Anything".
 */
data class VisualExplanationCard(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val oneLineSummary: String,
    val bulletChips: List<String>,
    val detailParagraph: String,
    val comparisonTable: List<Pair<String, String>> = emptyList(),
    val diagramFlow: List<String> = emptyList(),
    val sourceCitations: List<String> = emptyList(),
    val confidenceScore: Float = 0.95f,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * High-precision guidance and visual explanation engine for JARVIS UI.
 */
object JarvisGuidanceEngine {

    private const val TAG = "JarvisGuidanceEngine"

    private val _guidanceState = MutableStateFlow(JarvisGuidanceState())
    val guidanceState: StateFlow<JarvisGuidanceState> = _guidanceState.asStateFlow()

    private val _activeExplanation = MutableStateFlow<VisualExplanationCard?>(null)
    val activeExplanation: StateFlow<VisualExplanationCard?> = _activeExplanation.asStateFlow()

    private val engineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var activeAnalysisJob: Job? = null

    var externalSpeakCallback: ((String) -> Unit)? = null
    var externalStopSpeakCallback: (() -> Unit)? = null

    /**
     * Resolves and highlights an element on the active screen for step-by-step guidance.
     */
    fun showHowToTap(targetDescription: String, customPrompt: String? = null): Boolean {
        Log.i(TAG, "Locating element for 'Shows You How': '$targetDescription'")
        val freshScreen = FreshScreenRecognizer.inspectFreshScreenNow()
        val resolved = UIElementResolver.resolveTargetElement(freshScreen, targetDescription)

        if (resolved != null && resolved.bounds.width() > 0 && resolved.bounds.height() > 0) {
            val spokenPrompt = customPrompt ?: "Tap here to open ${resolved.targetText}"
            _guidanceState.value = JarvisGuidanceState(
                isActive = true,
                targetQuery = targetDescription,
                targetLabel = resolved.targetText,
                targetBounds = resolved.bounds,
                clickPoint = resolved.clickPoint,
                instructionText = spokenPrompt,
                confidence = resolved.confidence
            )
            JarvisOverlayController.highlightElement(
                bounds = resolved.bounds,
                label = resolved.targetText,
                clickPoint = resolved.clickPoint,
                prompt = spokenPrompt
            )
            Log.i(TAG, "Highlighted '${resolved.targetText}' at bounds: ${resolved.bounds}")
            return true
        }

        Log.w(TAG, "Could not resolve element for '$targetDescription' on active screen.")
        return false
    }

    /**
     * Highlights an element while talking ("Circle While Talking").
     */
    fun circleWhileTalking(targetText: String, speechText: String) {
        showHowToTap(targetText, speechText)
    }

    /**
     * Dismisses active reticle / highlight guidance.
     */
    fun clearGuidance() {
        activeAnalysisJob?.cancel()
        _guidanceState.value = JarvisGuidanceState(isActive = false)
        ScreenAnnotationManager.clearAllAnnotations()
    }

    /**
     * Creates and publishes a structured visual explanation card for "JARVIS Explains Anything"
     * while preserving any active on-screen highlight instructions and step sequences.
     */
    fun publishExplanation(
        title: String,
        summary: String,
        bulletPoints: List<String>,
        details: String = "",
        comparisons: List<Pair<String, String>> = emptyList(),
        diagramFlow: List<String> = emptyList(),
        citations: List<String> = emptyList(),
        confidence: Float = 0.95f
    ): VisualExplanationCard {
        val card = VisualExplanationCard(
            title = title,
            oneLineSummary = summary,
            bulletChips = bulletPoints,
            detailParagraph = details,
            comparisonTable = comparisons,
            diagramFlow = diagramFlow,
            sourceCitations = citations,
            confidenceScore = confidence
        )
        _activeExplanation.value = card

        val existingPlan = JarvisOverlayController.activePlan.value
        val existingInstructions = existingPlan?.instructions ?: emptyList()
        val anchorBounds = existingInstructions.firstOrNull()?.bounds
            ?: existingPlan?.cards?.firstOrNull()?.targetAnchorBounds

        val overlayCard = VisualCardData(
            id = card.id,
            type = if (comparisons.isNotEmpty()) VisualCardType.COMPARISON else if (details.isNotBlank()) VisualCardType.DETAIL_TYPEWRITER else VisualCardType.SUMMARY,
            title = title,
            summary = summary,
            bullets = bulletPoints,
            detailText = details,
            comparisonItems = comparisons.map { ComparisonItem(it.first, it.second, "", "") },
            citations = citations,
            targetAnchorBounds = anchorBounds,
            confidence = confidence
        )
        val plan = JarvisVisualPlan(
            userQuery = existingPlan?.userQuery ?: title,
            mode = JarvisMode.EXPLAINS_ANYTHING,
            title = title,
            instructions = existingInstructions,
            cards = listOf(overlayCard),
            targetPackage = existingPlan?.targetPackage ?: ""
        )
        JarvisOverlayController.presentVisualPlan(plan)

        return card
    }

    /**
     * Synchronous entry point no longer fabricates local/offline plans without AI authorization.
     */
    fun executeJarvisQuery(query: String) {
        publishExplanation(
            title = "AI Screen Analysis",
            summary = "Live screen analysis requires active AI provider authorization.",
            bulletPoints = listOf("Use Screen Share or ask with an active online AI provider configured."),
            confidence = 0.0f
        )
    }

    /**
     * Asynchronous full-pipeline screen explanation triggered from the Floating Orb, Panel, or Voice.
     * Routes strictly through the user's selected online AI provider (Gemini, OpenRouter, HuggingFace, Custom).
     * Never presents synthetic local plans when API authorization fails.
     */
    fun explainActiveScreenAsync(context: Context, query: String = "Explain this screen") {
        activeAnalysisJob?.cancel()
        ScreenAnnotationManager.coordinateMapper.refreshGeometry(context)
        JarvisOverlayController.setOrbState(JarvisOrbState.THINKING)

        activeAnalysisJob = engineScope.launch {
            try {
                val initialMap = withContext(Dispatchers.Default) {
                    SemanticScreenAnalyzer.analyzeScreen()
                }
                var frameBitmap = VisionPipeline.getLatestFrame()
                if (frameBitmap == null) {
                    frameBitmap = withContext(Dispatchers.IO) {
                        ScreenshotManager(context).captureScreen()
                    }
                    if (frameBitmap != null) {
                        VisionPipeline.onNewFrame(frameBitmap)
                    }
                }

                val jpegBytes = VisionPipeline.getCompressedJpegFrame()
                    ?: frameBitmap?.let { bmp ->
                        withContext(Dispatchers.IO) {
                            try {
                                val q = PerformanceProfileManager.snapshot.value.jpegQuality
                                val baos = ByteArrayOutputStream()
                                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, baos)
                                baos.toByteArray()
                            } catch (_: Exception) { null }
                        }
                    }

                if (jpegBytes == null || jpegBytes.isEmpty()) {
                    publishExplanation(
                        title = "Fresh Screen Required",
                        summary = "No live screen frame available. Start Screen Share and switch to the target app.",
                        bulletPoints = listOf("Missing fresh screenshot for AI vision analysis"),
                        confidence = 0.0f
                    )
                    JarvisOverlayController.setOrbState(JarvisOrbState.IDLE)
                    return@launch
                }

                val hierarchySummary = initialMap.elements.take(20).joinToString("\n") {
                    "- ${it.displayLabel} [${it.type.name}] bounds=${it.bounds}"
                }
                val visionPrompt = """
                    User question: $query
                    Current app: ${initialMap.packageName.ifBlank { "Current App" }}
                    UI hierarchy context:
                    $hierarchySummary

                    Analyze the live screenshot accurately and explain what is visible and helpful next steps.
                """.trimIndent()

                val aiRes = com.example.orchestrator.SelectedProviderClient.invokeSelectedProvider(
                    context = context,
                    prompt = visionPrompt,
                    systemInstruction = "You are Kavya, an expert AI visual screen analyst. Describe only what is truly observed on the screen clearly and concisely.",
                    imageBytes = jpegBytes,
                    requiresVision = true
                )

                when (aiRes) {
                    is com.example.orchestrator.ProviderInvocationResult.Failure -> {
                        publishExplanation(
                            title = "AI Authorization Required",
                            summary = aiRes.message,
                            bulletPoints = listOf("Error code: ${aiRes.code}", "Configure an online vision-capable AI provider in API Hub."),
                            confidence = 0.0f
                        )
                        JarvisOverlayController.setOrbState(JarvisOrbState.IDLE)
                    }
                    is com.example.orchestrator.ProviderInvocationResult.Success -> {
                        val summary = aiRes.text.trim()
                        val bullets = summary.lines()
                            .map { it.trim().trimStart('•', '-', '*') }
                            .filter { it.length > 6 }
                            .take(4)
                            .ifEmpty { listOf("Analyzed by ${aiRes.providerId} (${aiRes.modelId})") }
                        publishExplanation(
                            title = "AI Screen Analysis",
                            summary = summary.take(220),
                            bulletPoints = bullets,
                            details = summary,
                            confidence = 0.95f
                        )
                        com.example.services.ScreenShareManager.saveSnapshotToVault(
                            title = "Screen Explanation",
                            summary = summary,
                            appPackage = initialMap.packageName
                        )
                        JarvisOverlayController.setOrbState(JarvisOrbState.IDLE)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w(TAG, "explainActiveScreenAsync failed: ${e.message}")
                JarvisOverlayController.setOrbState(JarvisOrbState.IDLE)
            }
        }
    }

    /**
     * Circle / Region Select to Explain.
     * Crops the user-selected region from the live screen frame and runs targeted Vision AI analysis via SelectedProviderClient.
     */
    fun explainRegionAsync(
        context: Context,
        leftNorm: Float,
        topNorm: Float,
        rightNorm: Float,
        bottomNorm: Float,
        query: String = "Explain only the highlighted/selected area concisely in simple Hinglish/English."
    ) {
        activeAnalysisJob?.cancel()
        JarvisOverlayController.setOrbState(JarvisOrbState.THINKING)

        activeAnalysisJob = engineScope.launch {
            try {
                val frameBitmap = VisionPipeline.getLatestFrame()
                    ?: withContext(Dispatchers.IO) { ScreenshotManager(context).captureScreen() }

                val regionBytes = withContext(Dispatchers.IO) {
                    VisionPipeline.cropNormalizedRegionCompressed(
                        leftNorm = leftNorm,
                        topNorm = topNorm,
                        rightNorm = rightNorm,
                        bottomNorm = bottomNorm,
                        sourceBitmap = frameBitmap
                    )
                }

                if (regionBytes == null || regionBytes.isEmpty()) {
                    publishExplanation(
                        title = "Region Select",
                        summary = "No live frame available yet. Start Screen Share and switch to the target app first.",
                        bulletPoints = listOf("Tip: Select 'Entire Screen' when prompted for MediaProjection consent")
                    )
                    JarvisOverlayController.setOrbState(JarvisOrbState.IDLE)
                    return@launch
                }

                val telemetry = com.example.services.ScreenShareManager.telemetry.value
                val hierarchyHint = if (telemetry.activeBrowserUrl.isNotBlank()) {
                    "Active Browser URL: ${telemetry.activeBrowserUrl}"
                } else {
                    "User selected normalized screen region (${(leftNorm * 100).toInt()}%..${(rightNorm * 100).toInt()}%)."
                }

                val aiRes = com.example.orchestrator.SelectedProviderClient.invokeSelectedProvider(
                    context = context,
                    prompt = "$query\nApp: ${telemetry.foregroundPackage}\n$hierarchyHint",
                    systemInstruction = "Explain only the cropped screen region accurately in simple Hinglish/English.",
                    imageBytes = regionBytes,
                    requiresVision = true
                )

                when (aiRes) {
                    is com.example.orchestrator.ProviderInvocationResult.Failure -> {
                        publishExplanation(
                            title = "AI Authorization Required",
                            summary = aiRes.message,
                            bulletPoints = listOf("Error code: ${aiRes.code}")
                        )
                    }
                    is com.example.orchestrator.ProviderInvocationResult.Success -> {
                        val summaryText = aiRes.text.trim()
                        val bullets = summaryText.lines()
                            .map { it.trim().trimStart('•', '-', '*') }
                            .filter { it.length > 6 }
                            .take(4)
                            .ifEmpty { listOf("Focused crop analyzed (${regionBytes.size / 1024} KB)") }

                        publishExplanation(
                            title = "Region Focus Analysis",
                            summary = summaryText,
                            bulletPoints = bullets,
                            details = summaryText,
                            citations = listOfNotNull(telemetry.activeBrowserUrl.takeIf { it.isNotBlank() }),
                            confidence = 0.96f
                        )
                        com.example.services.ScreenShareManager.saveSnapshotToVault(
                            title = "Region Focus",
                            summary = summaryText,
                            appPackage = telemetry.foregroundPackage,
                            urlOrContext = telemetry.activeBrowserUrl
                        )
                    }
                }
                JarvisOverlayController.setOrbState(JarvisOrbState.IDLE)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w(TAG, "explainRegionAsync failed: ${e.message}")
                JarvisOverlayController.setOrbState(JarvisOrbState.IDLE)
            }
        }
    }

    /**
     * One-tap Chrome / Webpage quick action (Summarize, Translate HI/EN, Key Takeaways).
     */
    fun analyzeBrowserPageAsync(
        context: Context,
        actionTitle: String,
        prompt: String
    ) {
        activeAnalysisJob?.cancel()
        JarvisOverlayController.setOrbState(JarvisOrbState.THINKING)

        activeAnalysisJob = engineScope.launch {
            try {
                val screenMap = withContext(Dispatchers.Default) {
                    SemanticScreenAnalyzer.analyzeScreen()
                }
                val telemetry = com.example.services.ScreenShareManager.telemetry.value
                val urlLine = telemetry.activeBrowserUrl.takeIf { it.isNotBlank() }?.let { "URL: $it\n" }.orEmpty()
                val textContext = screenMap.elements.take(25).joinToString("\n") { it.displayLabel }

                val frameBytes = withContext(Dispatchers.IO) {
                    VisionPipeline.getCompressedJpegFrame()
                        ?: ScreenshotManager(context).captureScreen()?.let { bmp ->
                            try {
                                val q = PerformanceProfileManager.snapshot.value.jpegQuality
                                val baos = ByteArrayOutputStream()
                                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, baos)
                                baos.toByteArray()
                            } catch (_: Exception) { null }
                        }
                }

                if (frameBytes == null || frameBytes.isEmpty()) {
                    publishExplanation(
                        title = actionTitle,
                        summary = "Switch to Chrome with Screen Share active to capture a fresh frame for AI analysis.",
                        bulletPoints = listOfNotNull(telemetry.activeBrowserUrl.takeIf { it.isNotBlank() })
                    )
                    JarvisOverlayController.setOrbState(JarvisOrbState.IDLE)
                    return@launch
                }

                val aiRes = com.example.orchestrator.SelectedProviderClient.invokeSelectedProvider(
                    context = context,
                    prompt = "$prompt\nApp: ${telemetry.foregroundPackage.ifBlank { screenMap.packageName }}\n$urlLine\nVisible text:\n$textContext",
                    systemInstruction = "Analyze the active browser screenshot accurately and concisely.",
                    imageBytes = frameBytes,
                    requiresVision = true
                )

                when (aiRes) {
                    is com.example.orchestrator.ProviderInvocationResult.Failure -> {
                        publishExplanation(
                            title = "AI Authorization Required",
                            summary = aiRes.message,
                            bulletPoints = listOf("Error code: ${aiRes.code}")
                        )
                    }
                    is com.example.orchestrator.ProviderInvocationResult.Success -> {
                        val cleanSummary = aiRes.text.trim()
                        val bullets = cleanSummary.lines()
                            .map { it.trim().trimStart('•', '-', '*') }
                            .filter { it.length > 6 }
                            .take(4)
                        publishExplanation(
                            title = actionTitle,
                            summary = cleanSummary,
                            bulletPoints = bullets,
                            details = cleanSummary,
                            citations = listOfNotNull(telemetry.activeBrowserUrl.takeIf { it.isNotBlank() }),
                            confidence = 0.95f
                        )
                        com.example.services.ScreenShareManager.saveSnapshotToVault(
                            title = actionTitle,
                            summary = cleanSummary,
                            appPackage = telemetry.foregroundPackage.ifBlank { screenMap.packageName },
                            urlOrContext = telemetry.activeBrowserUrl
                        )
                    }
                }
                JarvisOverlayController.setOrbState(JarvisOrbState.IDLE)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w(TAG, "analyzeBrowserPageAsync failed: ${e.message}")
                JarvisOverlayController.setOrbState(JarvisOrbState.IDLE)
            }
        }
    }

    private fun applyPlanToState(plan: JarvisVisualPlan, autoAdvance: Boolean = false) {
        JarvisOverlayController.presentVisualPlan(plan, autoAdvanceSteps = autoAdvance)
        if (plan.cards.isNotEmpty()) {
            val firstCard = plan.cards.first()
            _activeExplanation.value = VisualExplanationCard(
                id = firstCard.id,
                title = firstCard.title,
                oneLineSummary = firstCard.summary,
                bulletChips = firstCard.bullets,
                detailParagraph = firstCard.detailText,
                confidenceScore = firstCard.confidence
            )
        }
        if (plan.instructions.isNotEmpty()) {
            val firstInst = plan.instructions.first()
            _guidanceState.value = JarvisGuidanceState(
                isActive = true,
                targetQuery = firstInst.label,
                targetLabel = firstInst.label,
                targetBounds = firstInst.bounds,
                clickPoint = firstInst.clickPoint,
                instructionText = firstInst.text,
                stepIndex = 1,
                totalSteps = plan.instructions.size
            )
        } else {
            _guidanceState.value = JarvisGuidanceState(isActive = false)
        }
    }

    fun dismissExplanation() {
        activeAnalysisJob?.cancel()
        _activeExplanation.value = null
        ScreenAnnotationManager.clearAllAnnotations()
    }
}
