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
     * Infers the presentation/explanation depth (`BASIC`, `STANDARD`, `ADVANCED`) from natural
     * Hindi, Hinglish, or English voice/text commands (Phase 5).
     * Defaults to `STANDARD` if the user does not specify a level.
     */
    fun inferExplanationLevelFromQuery(
        query: String,
        fallback: ScreenSharePresentationLevel = ScreenSharePresentationLevel.STANDARD
    ): ScreenSharePresentationLevel {
        val lower = query.lowercase(java.util.Locale.ROOT).trim()
        val basicKeywords = listOf(
            "basic", "simple", "short", "quick", "brief", "beginner", "easy",
            "aasan", "saral", "chhota", "sankshipt", "आसान", "सरल", "संक्षेप", "छोटा"
        )
        val advancedKeywords = listOf(
            "advanced", "detailed", "in detail", "deep", "technical", "complete detail",
            "full detail", "expert", "vistar", "poori jankari", "gहराई", "विस्तार", "टेक्निकल", "पूरी जानकारी"
        )
        val standardKeywords = listOf(
            "standard", "normal", "medium", "regular", "balanced"
        )
        return when {
            advancedKeywords.any { lower.contains(it) } -> ScreenSharePresentationLevel.ADVANCED
            basicKeywords.any { lower.contains(it) } -> ScreenSharePresentationLevel.BASIC
            standardKeywords.any { lower.contains(it) } -> ScreenSharePresentationLevel.STANDARD
            else -> fallback
        }
    }

    /**
     * Handles voice-first step-by-step navigation and explanation-level follow-up commands (Phase 5).
     * Returns a non-null spoken/chat response when handled as an active JARVIS guidance control command.
     */
    fun handleVoiceGuidanceControlCommand(context: Context, command: String): String? {
        val lower = command.lowercase(java.util.Locale.ROOT).trim()
        val hasActiveSteps = ExplanationStepController.steps.value.isNotEmpty()
        val hasActivePlan = JarvisOverlayController.activePlan.value != null

        if (hasActiveSteps) {
            if (lower in listOf("next", "next step", "continue", "aage", "aage badho", "agla", "agla step", "आगे", "अगला")) {
                val step = ExplanationStepController.nextStep()
                return step?.let { "Step ${it.stepNumber} of ${it.totalSteps}: ${it.spokenExplanation}" }
            }
            if (lower in listOf("previous", "previous step", "prev", "back step", "piche", "pichla step", "पीछे", "पिछला")) {
                val step = ExplanationStepController.previousStep()
                return step?.let { "Step ${it.stepNumber} of ${it.totalSteps}: ${it.spokenExplanation}" }
            }
            if (lower in listOf("repeat", "repeat step", "say again", "phir se", "phir se batao", "dobara", "dobara bolo", "फिर से बताओ")) {
                val step = ExplanationStepController.currentStep.value
                if (step != null) {
                    externalSpeakCallback?.invoke(step.spokenExplanation)
                    return "Step ${step.stepNumber} of ${step.totalSteps}: ${step.spokenExplanation}"
                }
            }
        }

        val isLevelSwitchFollowUp = hasActivePlan && (
            lower.contains("basic") || lower.contains("simple") || lower.contains("aasan") ||
            lower.contains("standard") ||
            lower.contains("advanced") || lower.contains("detailed") || lower.contains("technical") || lower.contains("vistar")
        ) && (lower.contains("explain") || lower.contains("samjhao") || lower.contains("batao") || lower.contains("level") || lower.contains("detail") || lower.contains("mode"))

        if (isLevelSwitchFollowUp) {
            val newLevel = inferExplanationLevelFromQuery(command, JarvisOverlayController.activeExplanationLevel.value)
            JarvisOverlayController.setExplanationLevel(newLevel)
            explainActiveScreenAsync(context, command, preferredLevel = newLevel)
            return "Switching screen explanation to ${newLevel.title} (${newLevel.id})..."
        }

        return null
    }

    /**
     * Reads and summarizes the visible screen aloud through the AI-authorized screen explanation pipeline.
     */
    fun readCurrentScreenAloud(context: Context) {
        explainActiveScreenAsync(
            context = context,
            query = "Read and summarize the main text on this screen clearly",
            preferredLevel = ScreenSharePresentationLevel.BASIC
        )
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
     * Routes strictly through the user's selected online AI provider (Gemini, OpenRouter, Custom).
     * Never destroys or hides the Unified JARVIS Live Overlay on error (Phase 11).
     */
    fun explainActiveScreenAsync(
        context: Context,
        query: String = "Explain this screen",
        preferredLevel: ScreenSharePresentationLevel? = null
    ) {
        activeAnalysisJob?.cancel()
        val appCtx = context.applicationContext
        ScreenAnnotationManager.coordinateMapper.refreshGeometry(appCtx)

        val resolvedLevel = preferredLevel ?: inferExplanationLevelFromQuery(query, ScreenSharePresentationLevel.STANDARD)
        JarvisOverlayController.setExplanationLevel(resolvedLevel)
        com.example.services.ScreenShareManager.setUnifiedStage(
            com.example.services.ScreenShareManager.UnifiedJarvisSessionState.Analyzing(query = query)
        )
        JarvisOverlayController.setOrbState(JarvisOrbState.THINKING)

        // Ensure the Unified JARVIS Live Overlay is visible if overlay permission is granted
        if (JarvisOverlayController.hasOverlayPermission(appCtx) && !JarvisOverlayController.isOverlayRunning.value) {
            JarvisOverlayController.startOverlay(appCtx)
        }

        activeAnalysisJob = engineScope.launch {
            try {
                val initialMap = withContext(Dispatchers.Default) {
                    SemanticScreenAnalyzer.analyzeScreen()
                }

                // Capture & validate screenshot (Phase 6)
                val observation = com.example.orchestrator.ScreenObservationCoordinator.captureFreshObservation(appCtx)
                val jpegBytes = observation?.imageBytes ?: VisionPipeline.getCompressedJpegFrame()

                if (jpegBytes == null || jpegBytes.isEmpty()) {
                    val errMsg = "Unable to capture a valid screen image. Enable Screen Share or Accessibility Service and try again."
                    JarvisOverlayController.showInlineOverlayError("Screen Capture Required", errMsg)
                    com.example.services.ScreenShareManager.setUnifiedStage(
                        com.example.services.ScreenShareManager.UnifiedJarvisSessionState.Error(errMsg)
                    )
                    externalSpeakCallback?.invoke(errMsg)
                    return@launch
                }

                val isStepByStepRequest = query.lowercase(java.util.Locale.ROOT).let { q ->
                    q.contains("step by step") || q.contains("guide me") || q.contains("how do i") ||
                    q.contains("kaise") || q.contains("sikhao") || q.contains("कैसे")
                }

                val levelInstruction = when (resolvedLevel) {
                    ScreenSharePresentationLevel.BASIC ->
                        "EXPLANATION LEVEL: BASIC. Explain in 2-3 very short, simple, beginner-friendly sentences without technical jargon."
                    ScreenSharePresentationLevel.STANDARD ->
                        "EXPLANATION LEVEL: STANDARD. Provide a clear, balanced explanation of the current screen, main visible options, and practical next steps."
                    ScreenSharePresentationLevel.ADVANCED ->
                        "EXPLANATION LEVEL: ADVANCED. Provide a comprehensive, detailed technical & functional breakdown of all visible sections, controls, settings, and workflows."
                }

                val hierarchySummary = initialMap.elements.take(20).joinToString("\n") {
                    "- ${it.displayLabel} [${it.type.name}] bounds=${it.bounds}"
                }
                val visionPrompt = """
                    User request: $query
                    $levelInstruction
                    ${if (isStepByStepRequest) "Format your response as clear numbered steps (1., 2., 3.) so the user can follow step by step." else ""}
                    Current foreground app: ${observation?.foregroundPackage?.ifBlank { initialMap.packageName }.orEmpty().ifBlank { "Current Screen" }}
                    UI hierarchy context:
                    $hierarchySummary

                    Analyze the attached live screenshot image and explain only what is genuinely visible on the screen.
                """.trimIndent()

                val aiRes = com.example.orchestrator.SelectedProviderClient.invokeSelectedProvider(
                    context = appCtx,
                    prompt = visionPrompt,
                    systemInstruction = "You are Kavya, an expert AI visual screen analyst. Describe only what is truly observed in the attached screenshot image clearly and accurately.",
                    imageBytes = jpegBytes,
                    requiresVision = true
                )

                when (aiRes) {
                    is com.example.orchestrator.ProviderInvocationResult.Failure -> {
                        JarvisOverlayController.showInlineOverlayError(
                            title = "AI Visual Analysis Failed",
                            message = aiRes.message
                        )
                        com.example.services.ScreenShareManager.setUnifiedStage(
                            com.example.services.ScreenShareManager.UnifiedJarvisSessionState.Error(aiRes.message)
                        )
                        externalSpeakCallback?.invoke(aiRes.message)
                    }
                    is com.example.orchestrator.ProviderInvocationResult.Success -> {
                        val summary = aiRes.text.trim()
                        val bullets = summary.lines()
                            .map { it.trim().trimStart('•', '-', '*') }
                            .filter { it.length > 6 }
                            .take(4)
                            .ifEmpty { listOf("Analyzed by ${aiRes.providerId} (${aiRes.modelId}) • ${resolvedLevel.title}") }

                        val cardTitle = when (resolvedLevel) {
                            ScreenSharePresentationLevel.BASIC -> "Basic Screen Summary"
                            ScreenSharePresentationLevel.STANDARD -> "Screen Explanation"
                            ScreenSharePresentationLevel.ADVANCED -> "Advanced Screen Analysis"
                        }

                        // Highlight interactive targets if step-by-step guidance was requested
                        if (isStepByStepRequest && initialMap.elements.isNotEmpty()) {
                            val interactiveTargets = initialMap.elements.filter { it.isClickable && it.bounds.width() > 0 && it.bounds.height() > 0 }.take(4)
                            if (interactiveTargets.isNotEmpty()) {
                                val instructions = interactiveTargets.mapIndexed { idx, el ->
                                    VisualInstruction(
                                        type = VisualInstructionType.HIGHLIGHT_BOX,
                                        bounds = el.bounds,
                                        clickPoint = Pair(el.bounds.centerX(), el.bounds.centerY()),
                                        label = "Step ${idx + 1}: ${el.displayLabel.take(36)}",
                                        text = bullets.getOrElse(idx) { "Tap ${el.displayLabel}" }
                                    )
                                }
                                val overlayCard = VisualCardData(
                                    type = VisualCardType.SUMMARY,
                                    title = cardTitle,
                                    summary = summary.take(240),
                                    bullets = bullets,
                                    detailText = summary,
                                    confidence = 0.95f
                                )
                                val plan = JarvisVisualPlan(
                                    userQuery = query,
                                    mode = JarvisMode.SHOWS_YOU_HOW,
                                    title = cardTitle,
                                    instructions = instructions,
                                    cards = listOf(overlayCard),
                                    targetPackage = initialMap.packageName
                                )
                                JarvisOverlayController.presentVisualPlan(plan, autoAdvanceSteps = false)
                            } else {
                                publishExplanation(
                                    title = cardTitle,
                                    summary = summary.take(240),
                                    bulletPoints = bullets,
                                    details = summary,
                                    confidence = 0.95f
                                )
                            }
                        } else {
                            publishExplanation(
                                title = cardTitle,
                                summary = summary.take(240),
                                bulletPoints = bullets,
                                details = summary,
                                confidence = 0.95f
                            )
                        }

                        com.example.services.ScreenShareManager.saveSnapshotToVault(
                            title = cardTitle,
                            summary = summary,
                            appPackage = initialMap.packageName
                        )
                        externalSpeakCallback?.invoke(summary.take(320))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w(TAG, "explainActiveScreenAsync failed: ${e.message}")
                val errMsg = "Screen explanation encountered an error: ${e.localizedMessage ?: "Unknown error"}"
                JarvisOverlayController.showInlineOverlayError("Screen Analysis Error", errMsg)
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
