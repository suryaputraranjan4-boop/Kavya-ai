package com.example.visual

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import com.example.agent.FreshScreenRecognizer
import com.example.agent.FreshScreenState
import com.example.agent.UIElementResolver
import com.example.ai.offline.Gemma4E4BEngine
import com.example.ai.offline.OnDeviceVisionOrchestrator
import com.example.ai.offline.SmolVlmTaskMode
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

    @Volatile
    private var lastAppContext: Context? = null

    var externalSpeakCallback: ((String) -> Unit)? = null
    var externalStopSpeakCallback: (() -> Unit)? = null

    fun bindContext(context: Context) {
        lastAppContext = context.applicationContext
    }

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
        bindContext(context)
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
     * Verifies a screen transition after a user/agent action via [PostActionVerifier] and ONLY
     * advances the active guidance step sequence when a genuine transition is confirmed.
     */
    fun verifyStepTransitionAndAdvance(
        beforeScreen: FreshScreenState,
        afterScreen: FreshScreenState,
        expectedKind: PreActionVerifier.IntendedActionKind = PreActionVerifier.IntendedActionKind.TAP_TARGET
    ): PostActionVerificationResult {
        val result = PostActionVerifier.verifyTransition(
            beforeScreen = beforeScreen,
            afterScreen = afterScreen,
            expectedKind = expectedKind
        )
        if (result.transitionVerified && ExplanationStepController.steps.value.isNotEmpty()) {
            val idx = ExplanationStepController.currentStepIndex.value
            val total = ExplanationStepController.steps.value.size
            if (idx < total - 1) {
                ExplanationStepController.nextStep()
            }
        }
        return result
    }

    /**
     * Reads and summarizes the visible screen aloud through the AI-authorized screen explanation pipeline.
     */
    fun readCurrentScreenAloud(context: Context) {
        bindContext(context)
        explainActiveScreenAsync(
            context = context,
            query = "Read and summarize the main text on this screen clearly",
            preferredLevel = ScreenSharePresentationLevel.BASIC
        )
    }

    /**
     * Routes synchronous queries into the full AI + perception pipeline when a bound Context exists,
     * otherwise reports truthfully that active observation/AI authorization is required.
     */
    fun executeJarvisQuery(query: String) {
        val ctx = lastAppContext
        if (ctx != null) {
            explainActiveScreenAsync(context = ctx, query = query)
            return
        }
        publishExplanation(
            title = "AI Screen Analysis",
            summary = "Live screen analysis requires active AI provider authorization or local perception models.",
            bulletPoints = listOf("Use Screen Share or ask with an active AI provider / local model ready."),
            confidence = 0.0f
        )
    }

    /**
     * Asynchronous full-pipeline screen explanation triggered from the Floating Orb, Panel, or Voice.
     * Combines live screenshot capture, Accessibility hierarchy, ML Kit Latin+Devanagari pixel OCR,
     * and installed ONNX lightweight vision models, routing reasoning through the selected AI provider
     * (or grounded offline VLM/OCR summary if offline).
     */
    fun explainActiveScreenAsync(
        context: Context,
        query: String = "Explain this screen",
        preferredLevel: ScreenSharePresentationLevel? = null,
        openFullScreenExplainer: Boolean = false
    ) {
        activeAnalysisJob?.cancel()
        val appCtx = context.applicationContext
        lastAppContext = appCtx
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
                val freshState = withContext(Dispatchers.Default) {
                    FreshScreenRecognizer.inspectFreshScreenNow()
                }

                // Capture & validate screenshot (Phase 6)
                val observation = com.example.orchestrator.ScreenObservationCoordinator.captureFreshObservation(appCtx)
                val jpegBytes = observation?.imageBytes ?: VisionPipeline.getCompressedJpegFrame()
                val liveBitmap: Bitmap? = VisionPipeline.getLatestFrame() ?: jpegBytes?.let { bytes ->
                    withContext(Dispatchers.Default) {
                        try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } catch (_: Throwable) { null }
                    }
                }

                val geom = ScreenAnnotationManager.coordinateMapper.getGeometry()
                val screenW = observation?.displayWidthPx?.takeIf { it > 0 } ?: geom.width
                val screenH = observation?.displayHeightPx?.takeIf { it > 0 } ?: geom.height

                // Run real on-device ML Kit OCR (Latin + Devanagari) & installed ONNX vision models
                val ocrSignals = if (liveBitmap != null && !liveBitmap.isRecycled) {
                    OnDeviceOcrExtractor.extractPixelOcrSignals(liveBitmap, screenW, screenH)
                } else emptyList()

                val localVision = if (liveBitmap != null && !liveBitmap.isRecycled) {
                    OnDeviceVisionOrchestrator.getInstance(appCtx).analyzeScreenWithInstalledModels(
                        screenshot = liveBitmap,
                        screenWidth = screenW,
                        screenHeight = screenH
                    )
                } else null

                val fusedElements = MultiSignalFusionEngine.buildUnifiedElements(
                    accessibilityNodes = freshState.nodes,
                    foregroundPackage = observation?.foregroundPackage?.ifBlank { initialMap.packageName }.orEmpty(),
                    ocrSignals = ocrSignals,
                    visionSignals = localVision?.visionSignals.orEmpty(),
                    screenWidth = screenW,
                    screenHeight = screenH
                )

                val activeFingerprint = MultiSignalFusionEngine.computeScreenFingerprint(
                    foregroundPackage = initialMap.packageName,
                    accessibilityNodes = freshState.nodes,
                    ocrSignals = ocrSignals,
                    screenshotWidth = screenW,
                    screenshotHeight = screenH,
                    bitmapSignature = MultiSignalFusionEngine.computeBitmapQuickSignature(liveBitmap)
                )

                if ((jpegBytes == null || jpegBytes.isEmpty()) && fusedElements.isEmpty()) {
                    val errMsg = "Unable to capture a valid screen image or UI hierarchy. Enable Screen Share or Accessibility Service and try again."
                    JarvisOverlayController.showInlineOverlayError("Screen Capture Required", errMsg)
                    com.example.services.ScreenShareManager.setUnifiedStage(
                        com.example.services.ScreenShareManager.UnifiedJarvisSessionState.Error(errMsg)
                    )
                    externalSpeakCallback?.invoke(errMsg)
                    return@launch
                }

                val isStepByStepRequest = query.lowercase(java.util.Locale.ROOT).let { q ->
                    q.contains("step by step") || q.contains("guide me") || q.contains("how do i") ||
                    q.contains("kaise") || q.contains("sikhao") || q.contains("कैसे") || q.contains("dikhao")
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
                    "- ${PromptInjectionGuard.sanitizeScreenText(it.displayLabel)} [${it.type.name}] bounds=${it.bounds}"
                }
                val ocrSummary = ocrSignals.take(24).joinToString("\n") {
                    "- [${it.language}] \"${it.text}\" @ ${it.bounds}"
                }
                val visionSummary = buildString {
                    if (localVision != null && localVision.executedModelIds.isNotEmpty()) {
                        append("Installed ONNX Vision (${localVision.executedModelIds.joinToString()}):\n")
                        if (localVision.sceneTags.isNotEmpty()) {
                            append("Tags: ${localVision.sceneTags.joinToString("; ")}\n")
                        }
                        localVision.visionSignals.take(10).forEach { sig ->
                            append("- ${sig.label} (${sig.roleHint}, conf=${(sig.confidence * 100).toInt()}%) @ ${sig.bounds}\n")
                        }
                    }
                }
                val visionPrompt = """
                    User request: $query
                    $levelInstruction
                    ${if (isStepByStepRequest) "Format your response as clear numbered steps (1., 2., 3.) so the user can follow step by step." else ""}
                    Current foreground app: ${observation?.foregroundPackage?.ifBlank { initialMap.packageName }.orEmpty().ifBlank { "Current Screen" }}
                    UI hierarchy context:
                    ${hierarchySummary.ifBlank { "(none)" }}
                    On-device pixel OCR (Latin + Devanagari):
                    ${ocrSummary.ifBlank { "(none extracted)" }}
                    $visionSummary
                    Analyze the attached live screenshot image and explain only what is genuinely visible on the screen.
                """.trimIndent()

                val aiRes = if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                    com.example.orchestrator.SelectedProviderClient.invokeSelectedProvider(
                        context = appCtx,
                        prompt = visionPrompt,
                        systemInstruction = "You are Kavya, an expert AI visual screen analyst. Describe only what is truly observed in the attached screenshot image and fused signals clearly and accurately.",
                        imageBytes = jpegBytes,
                        requiresVision = true
                    )
                } else {
                    com.example.orchestrator.SelectedProviderClient.invokeSelectedProvider(
                        context = appCtx,
                        prompt = visionPrompt,
                        systemInstruction = "You are Kavya, an expert AI visual screen analyst. Explain the fused accessibility and OCR screen state clearly and accurately.",
                        requiresVision = false
                    )
                }

                when (aiRes) {
                    is com.example.orchestrator.ProviderInvocationResult.Failure -> {
                        // Offline fallback: SmolVLM2 (if loaded) or grounded On-Device OCR + Accessibility + ONNX summary (never fabricated)
                        val offlineSmolRes = if (liveBitmap != null && Gemma4E4BEngine.getInstance(appCtx).isSmolVlm2Loaded()) {
                            Gemma4E4BEngine.getInstance(appCtx).runSmolVlm2GuiGrounding(
                                screenshot = liveBitmap,
                                userGoal = query,
                                taskMode = if (isStepByStepRequest) SmolVlmTaskMode.TARGET_GROUNDING else SmolVlmTaskMode.SCREEN_SUMMARY,
                                uiContextHint = ocrSummary
                            )
                        } else null

                        if (offlineSmolRes != null && offlineSmolRes.isSuccess) {
                            val smolSignals = offlineSmolRes.getOrThrow()
                            val summary = buildString {
                                append("Offline SmolVLM2 analysis: ")
                                append(smolSignals.joinToString("; ") { "${it.label} (${it.roleHint})" })
                                if (ocrSignals.isNotEmpty()) {
                                    append("\nOCR highlights: ${ocrSignals.take(4).joinToString(" | ") { it.text }}")
                                }
                            }
                            publishGroundedScreenPlan(
                                query = query,
                                cardTitle = "Offline Visual Intelligence (SmolVLM2)",
                                summary = summary,
                                bullets = smolSignals.map { "${it.label}: ${it.bounds}" } + ocrSignals.take(3).map { "Text: ${it.text}" },
                                screenMap = initialMap,
                                isStepByStep = isStepByStepRequest,
                                openFullScreenExplainer = openFullScreenExplainer,
                                activeFingerprint = activeFingerprint,
                                instructionsOverride = smolSignals.mapIndexed { idx, sig ->
                                    VisualInstruction(
                                        type = VisualInstructionType.HIGHLIGHT_BOX,
                                        bounds = sig.bounds,
                                        clickPoint = Pair(sig.bounds.centerX(), sig.bounds.centerY()),
                                        label = "Target ${idx + 1}: ${sig.label.take(32)}",
                                        text = summary
                                    )
                                }
                            )
                            externalSpeakCallback?.invoke(summary.take(320))
                        } else if (ocrSignals.isNotEmpty() || (localVision?.executedModelIds?.isNotEmpty() == true) || initialMap.elements.isNotEmpty()) {
                            val ocrBullets = ocrSignals.take(5).map { "[${it.language.uppercase()}] ${it.text.take(48)}" }
                            val detBullets = localVision?.visionSignals?.take(4)?.map { "${it.label} (${(it.confidence * 100).toInt()}%)" }.orEmpty()
                            val a11yBullets = initialMap.actionButtons.take(4).map { "Control: ${it.displayLabel.take(36)}" }
                            val allBullets = (ocrBullets + detBullets + a11yBullets).distinct().take(6)
                            val localSummary = buildString {
                                append("Cloud AI unavailable (${aiRes.message}). Showing verified on-device perception: ")
                                if (ocrSignals.isNotEmpty()) append("${ocrSignals.size} OCR text line(s), ")
                                if (localVision != null && localVision.executedModelIds.isNotEmpty()) {
                                    append("ONNX [${localVision.executedModelIds.joinToString()}] (${localVision.visionSignals.size} detections), ")
                                }
                                append("${initialMap.elements.size} UI node(s).")
                                if (ocrSignals.isNotEmpty()) {
                                    append("\nVisible text: ${ocrSignals.take(4).joinToString(" • ") { it.text }}")
                                }
                            }
                            publishGroundedScreenPlan(
                                query = query,
                                cardTitle = "On-Device Screen Perception",
                                summary = localSummary,
                                bullets = allBullets,
                                screenMap = initialMap,
                                isStepByStep = isStepByStepRequest,
                                openFullScreenExplainer = openFullScreenExplainer,
                                activeFingerprint = activeFingerprint
                            )
                            externalSpeakCallback?.invoke(localSummary.take(320))
                        } else {
                            JarvisOverlayController.showInlineOverlayError(
                                title = "AI Visual Analysis Failed",
                                message = aiRes.message
                            )
                            com.example.services.ScreenShareManager.setUnifiedStage(
                                com.example.services.ScreenShareManager.UnifiedJarvisSessionState.Error(aiRes.message)
                            )
                            externalSpeakCallback?.invoke(aiRes.message)
                        }
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

                        publishGroundedScreenPlan(
                            query = query,
                            cardTitle = cardTitle,
                            summary = summary,
                            bullets = bullets,
                            screenMap = initialMap,
                            isStepByStep = isStepByStepRequest,
                            openFullScreenExplainer = openFullScreenExplainer,
                            activeFingerprint = activeFingerprint
                        )

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

    private fun publishGroundedScreenPlan(
        query: String,
        cardTitle: String,
        summary: String,
        bullets: List<String>,
        screenMap: SemanticScreenMap,
        isStepByStep: Boolean,
        openFullScreenExplainer: Boolean,
        activeFingerprint: String,
        instructionsOverride: List<VisualInstruction>? = null
    ) {
        val structuredPlan = JarvisIntelligencePlanner.createPlan(
            userQuery = query,
            screenMap = screenMap,
            forcedMode = if (isStepByStep) JarvisMode.SHOWS_YOU_HOW else JarvisMode.EXPLAINS_ANYTHING
        )
        val extraVisualCards = structuredPlan.cards.filter {
            it.type == VisualCardType.CANVAS_GRAPH ||
                it.type == VisualCardType.FLOWCHART ||
                it.type == VisualCardType.OBJECT_3D
        }
        val mainCard = VisualCardData(
            type = VisualCardType.DETAIL_TYPEWRITER,
            title = cardTitle,
            summary = summary.take(240),
            bullets = bullets,
            detailText = summary,
            targetAnchorBounds = structuredPlan.instructions.firstOrNull()?.bounds,
            confidence = 0.95f
        )
        val combinedCards = listOf(mainCard) + extraVisualCards

        val interactiveTargets = screenMap.elements
            .filter { it.isClickable && it.bounds.width() > 0 && it.bounds.height() > 0 }
            .take(5)
        val instructions = instructionsOverride ?: if (isStepByStep && interactiveTargets.isNotEmpty()) {
            interactiveTargets.mapIndexed { idx, el ->
                VisualInstruction(
                    type = VisualInstructionType.HIGHLIGHT_BOX,
                    bounds = el.bounds,
                    clickPoint = Pair(el.bounds.centerX(), el.bounds.centerY()),
                    label = "Step ${idx + 1}: ${el.displayLabel.take(36)}",
                    text = bullets.getOrElse(idx) { "Tap ${el.displayLabel}" }
                )
            }
        } else {
            structuredPlan.instructions
        }

        val finalPlan = JarvisVisualPlan(
            userQuery = query,
            mode = if (isStepByStep) JarvisMode.SHOWS_YOU_HOW else JarvisMode.EXPLAINS_ANYTHING,
            title = cardTitle,
            instructions = instructions,
            cards = combinedCards,
            fullScreenExplainerEnabled = openFullScreenExplainer || structuredPlan.fullScreenExplainerEnabled,
            targetPackage = screenMap.packageName
        )
        ScreenAnnotationManager.recordAnnotatedScreen(screenMap.packageName, activeFingerprint)
        if (finalPlan.fullScreenExplainerEnabled) {
            JarvisOverlayController.showFullScreenExplainer(finalPlan)
        } else {
            JarvisOverlayController.presentVisualPlan(finalPlan, autoAdvanceSteps = false)
        }
        _activeExplanation.value = VisualExplanationCard(
            id = mainCard.id,
            title = cardTitle,
            oneLineSummary = summary.take(240),
            bulletChips = bullets,
            detailParagraph = summary,
            confidenceScore = 0.95f
        )
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
        val appCtx = context.applicationContext
        lastAppContext = appCtx
        JarvisOverlayController.setOrbState(JarvisOrbState.THINKING)

        activeAnalysisJob = engineScope.launch {
            try {
                val frameBitmap = VisionPipeline.getLatestFrame()
                    ?: withContext(Dispatchers.IO) { ScreenshotManager(appCtx).captureScreen() }

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

                val regionBitmap = withContext(Dispatchers.Default) {
                    try { BitmapFactory.decodeByteArray(regionBytes, 0, regionBytes.size) } catch (_: Throwable) { null }
                }
                val regionOcr = if (regionBitmap != null) {
                    OnDeviceOcrExtractor.extractPixelOcrSignals(regionBitmap, regionBitmap.width, regionBitmap.height)
                } else emptyList()
                val regionLocalVision = if (regionBitmap != null) {
                    OnDeviceVisionOrchestrator.getInstance(appCtx).analyzeScreenWithInstalledModels(
                        screenshot = regionBitmap,
                        screenWidth = regionBitmap.width,
                        screenHeight = regionBitmap.height,
                        includeRegionCropClassification = true
                    )
                } else null

                val telemetry = com.example.services.ScreenShareManager.telemetry.value
                val hierarchyHint = if (telemetry.activeBrowserUrl.isNotBlank()) {
                    "Active Browser URL: ${telemetry.activeBrowserUrl}"
                } else {
                    "User selected normalized screen region (${(leftNorm * 100).toInt()}%..${(rightNorm * 100).toInt()}%)."
                }
                val regionOcrText = regionOcr.joinToString(" | ") { "[${it.language}] ${it.text}" }
                val regionTags = regionLocalVision?.sceneTags?.joinToString("; ").orEmpty()

                val aiRes = com.example.orchestrator.SelectedProviderClient.invokeSelectedProvider(
                    context = appCtx,
                    prompt = buildString {
                        append("$query\nApp: ${telemetry.foregroundPackage}\n$hierarchyHint")
                        if (regionOcrText.isNotBlank()) append("\nExtracted Region OCR: $regionOcrText")
                        if (regionTags.isNotBlank()) append("\nOn-Device Visual Classification: $regionTags")
                    },
                    systemInstruction = "Explain only the cropped screen region accurately in simple Hinglish/English.",
                    imageBytes = regionBytes,
                    requiresVision = true
                )

                when (aiRes) {
                    is com.example.orchestrator.ProviderInvocationResult.Failure -> {
                        if (regionOcr.isNotEmpty() || regionTags.isNotBlank()) {
                            val localText = buildString {
                                append("Cloud AI unavailable (${aiRes.message}). On-device region perception: ")
                                if (regionOcrText.isNotBlank()) append("Text: $regionOcrText. ")
                                if (regionTags.isNotBlank()) append("Tags: $regionTags.")
                            }
                            publishExplanation(
                                title = "Region Focus (On-Device OCR)",
                                summary = localText,
                                bulletPoints = regionOcr.take(4).map { it.text } + listOfNotNull(regionTags.takeIf { it.isNotBlank() }),
                                details = localText,
                                confidence = 0.80f
                            )
                        } else {
                            publishExplanation(
                                title = "AI Authorization Required",
                                summary = aiRes.message,
                                bulletPoints = listOf("Error code: ${aiRes.code}")
                            )
                        }
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
