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
     * Universal synchronous entry point: executes a user's natural language request through JARVIS Intelligence.
     */
    fun executeJarvisQuery(query: String) {
        val screenMap = SemanticScreenAnalyzer.analyzeScreen()
        val plan = JarvisIntelligencePlanner.createPlan(query, screenMap)
        applyPlanToState(plan)
    }

    /**
     * Asynchronous full-pipeline screen explanation triggered from the Floating Orb, Panel, or Voice.
     * Combines Accessibility hierarchy with live MediaProjection or Accessibility screenshot fallback
     * and Gemini Vision multimodal bounding-box analysis on Dispatchers.IO.
     */
    fun explainActiveScreenAsync(context: Context, query: String = "Explain this screen") {
        activeAnalysisJob?.cancel()
        ScreenAnnotationManager.coordinateMapper.refreshGeometry(context)
        JarvisOverlayController.setOrbState(JarvisOrbState.THINKING)

        activeAnalysisJob = engineScope.launch {
            try {
                // 1. Fast local pass using Accessibility hierarchy if available
                val initialMap = withContext(Dispatchers.Default) {
                    SemanticScreenAnalyzer.analyzeScreen()
                }

                // 2. Ensure we have a live visual frame (MediaProjection or Accessibility takeScreenshot fallback)
                var frameBitmap = VisionPipeline.getLatestFrame()
                if (frameBitmap == null) {
                    frameBitmap = withContext(Dispatchers.IO) {
                        ScreenshotManager(context).captureScreen()
                    }
                    if (frameBitmap != null) {
                        VisionPipeline.onNewFrame(frameBitmap)
                    }
                }

                // Present immediate local plan while Vision AI enriches (or as final if offline/no key)
                val localPlan = withContext(Dispatchers.Default) {
                    JarvisIntelligencePlanner.createPlan(
                        userQuery = query,
                        screenMap = initialMap,
                        forcedMode = JarvisIntelligencePlanner.classifyMode(query).let {
                            if (it == JarvisMode.IDLE) JarvisMode.EXPLAINS_ANYTHING else it
                        }
                    )
                }
                applyPlanToState(localPlan, autoAdvance = localPlan.instructions.size > 1)

                // 3. If a visual frame is available, invoke GeminiVisionAnalyzer on background thread
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

                if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                    val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
                    val hierarchySummary = initialMap.elements.take(20).joinToString("\n") {
                        "- ${it.displayLabel} [${it.type.name}] bounds=${it.bounds}"
                    }
                    val (visionSummary, visionSteps) = GeminiVisionAnalyzer(context)
                        .analyzeScreenForVisualExplanation(
                            base64Image = base64,
                            userQuery = query,
                            currentApp = initialMap.packageName.ifBlank { "Current App" },
                            hierarchySummary = hierarchySummary
                        )

                    if (visionSteps.isNotEmpty()) {
                        // Snap vision bounding boxes to exact Accessibility node bounds when a matching element exists
                        val refinedSteps = visionSteps.map { step ->
                            val matchedNode = initialMap.findElementMatching(step.title)
                                ?: initialMap.findElementNear(step.bounds.centerX(), step.bounds.centerY(), threshold = 140)
                            if (matchedNode != null && matchedNode.bounds.width() > 0 && matchedNode.bounds.height() > 0) {
                                step.copy(
                                    bounds = matchedNode.bounds,
                                    clickPoint = Pair(matchedNode.bounds.centerX(), matchedNode.bounds.centerY())
                                )
                            } else {
                                step
                            }
                        }

                        val refinedInstructions = refinedSteps.map { step ->
                            VisualInstruction(
                                type = VisualInstructionType.HIGHLIGHT_BOX,
                                bounds = step.bounds,
                                clickPoint = step.clickPoint,
                                label = step.label,
                                text = step.spokenExplanation,
                                isPassThroughTouch = true
                            )
                        }

                        val finalSummary = visionSummary.ifBlank { refinedSteps.first().spokenExplanation }
                        val enrichedCard = VisualCardData(
                            type = VisualCardType.SUMMARY,
                            title = "Vision Screen Analysis",
                            summary = finalSummary,
                            bullets = refinedSteps.map { "Step ${it.stepNumber}: ${it.title} — ${it.label}" },
                            detailText = visionSummary,
                            targetAnchorBounds = refinedSteps.first().bounds,
                            confidence = 0.96f
                        )

                        val enrichedPlan = localPlan.copy(
                            instructions = refinedInstructions,
                            cards = listOf(enrichedCard) + localPlan.cards.drop(1)
                        )
                        applyPlanToState(enrichedPlan, autoAdvance = refinedSteps.size > 1)
                        com.example.services.ScreenShareManager.saveSnapshotToVault(
                            title = "Screen Explanation",
                            summary = finalSummary,
                            appPackage = initialMap.packageName
                        )
                    } else if (visionSummary.isNotBlank()) {
                        com.example.services.ScreenShareManager.saveSnapshotToVault(
                            title = "Screen Summary",
                            summary = visionSummary,
                            appPackage = initialMap.packageName
                        )
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
     * Circle / Region Select to Explain (inspired by Circle-to-Share / CircleToSearch).
     * Crops the user-selected region from the live screen frame and runs targeted Vision AI analysis.
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
                val base64 = Base64.encodeToString(regionBytes, Base64.NO_WRAP)
                val hierarchyHint = if (telemetry.activeBrowserUrl.isNotBlank()) {
                    "Active Browser URL: ${telemetry.activeBrowserUrl}"
                } else {
                    "User selected normalized screen region (${(leftNorm * 100).toInt()}%..${(rightNorm * 100).toInt()}%)."
                }

                val (visionSummary, visionSteps) = GeminiVisionAnalyzer(context)
                    .analyzeScreenForVisualExplanation(
                        base64Image = base64,
                        userQuery = query,
                        currentApp = telemetry.foregroundPackage.ifBlank { "Selected Screen Region" },
                        hierarchySummary = hierarchyHint
                    )

                val summaryText = visionSummary.ifBlank {
                    visionSteps.firstOrNull()?.spokenExplanation ?: "Highlighted region analyzed."
                }
                val bullets = visionSteps.take(4).map { "${it.title}: ${it.label}" }.ifEmpty {
                    listOf("Focused crop analyzed (${regionBytes.size / 1024} KB)")
                }

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

                if (frameBytes != null && frameBytes.isNotEmpty()) {
                    val base64 = Base64.encodeToString(frameBytes, Base64.NO_WRAP)
                    val (visionSummary, visionSteps) = GeminiVisionAnalyzer(context)
                        .analyzeScreenForVisualExplanation(
                            base64Image = base64,
                            userQuery = prompt,
                            currentApp = telemetry.foregroundPackage.ifBlank { screenMap.packageName.ifBlank { "Chrome" } },
                            hierarchySummary = "$urlLine$textContext"
                        )
                    val cleanSummary = visionSummary.ifBlank {
                        visionSteps.firstOrNull()?.spokenExplanation ?: textContext.lines().take(3).joinToString(" • ")
                    }.ifBlank { "No text or visual content found yet on this page." }

                    val bullets = visionSteps.take(4).map { "${it.title}: ${it.label}" }
                        .ifEmpty { cleanSummary.split(". ").filter { it.isNotBlank() }.take(3) }

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
                } else {
                    val fallbackText = textContext.lines().take(5).joinToString(" • ")
                        .ifBlank { "Switch to Chrome with Screen Share active to analyze the live page." }
                    publishExplanation(
                        title = actionTitle,
                        summary = fallbackText,
                        bulletPoints = listOfNotNull(telemetry.activeBrowserUrl.takeIf { it.isNotBlank() }),
                        details = fallbackText
                    )
                }
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
