package com.example.visual

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.utils.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Central State & Control Interface for the JARVIS Overlay and Screen Intelligence.
 *
 * Truthfulness Guarantee:
 * - [isOverlayRunning] / [attachmentState] ONLY transitions to [OverlayAttachmentState.ATTACHED_VISIBLE]
 *   after [JarvisOverlayService] confirms the Floating Orb View has genuinely attached to WindowManager.
 * - No optimistic/fake ON states when SYSTEM_ALERT_WINDOW is missing or addView fails.
 */
object JarvisOverlayController {

    private const val TAG = "JarvisOverlayController"

    private val controllerScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var testShowcaseJob: Job? = null
    private var transientResetJob: Job? = null

    private val _isOverlayRunning = MutableStateFlow(false)
    val isOverlayRunning: StateFlow<Boolean> = _isOverlayRunning.asStateFlow()

    private val _attachmentState = MutableStateFlow(OverlayAttachmentState.STOPPED)
    val attachmentState: StateFlow<OverlayAttachmentState> = _attachmentState.asStateFlow()

    private val _lastOverlayError = MutableStateFlow<String?>(null)
    val lastOverlayError: StateFlow<String?> = _lastOverlayError.asStateFlow()

    private val _isOrbExpanded = MutableStateFlow(false)
    val isOrbExpanded: StateFlow<Boolean> = _isOrbExpanded.asStateFlow()

    private val _isOrbMinimized = MutableStateFlow(false)
    val isOrbMinimized: StateFlow<Boolean> = _isOrbMinimized.asStateFlow()

    private val _activePlan = MutableStateFlow<JarvisVisualPlan?>(null)
    val activePlan: StateFlow<JarvisVisualPlan?> = _activePlan.asStateFlow()

    private val _activeInstruction = MutableStateFlow<VisualInstruction?>(null)
    val activeInstruction: StateFlow<VisualInstruction?> = _activeInstruction.asStateFlow()

    private val _orbState = MutableStateFlow(JarvisOrbState.IDLE)
    val orbState: StateFlow<JarvisOrbState> = _orbState.asStateFlow()

    private val _transientMotion = MutableStateFlow(OrbTransientMotion.NONE)
    val transientMotion: StateFlow<OrbTransientMotion> = _transientMotion.asStateFlow()

    private val _isTestShowcaseRunning = MutableStateFlow(false)
    val isTestShowcaseRunning: StateFlow<Boolean> = _isTestShowcaseRunning.asStateFlow()

    private val _orbConfig = MutableStateFlow(JarvisOrbConfig())
    val orbConfig: StateFlow<JarvisOrbConfig> = _orbConfig.asStateFlow()

    private val _isFullScreenExplainerVisible = MutableStateFlow(false)
    val isFullScreenExplainerVisible: StateFlow<Boolean> = _isFullScreenExplainerVisible.asStateFlow()

    private val _screenPreviewBitmap = MutableStateFlow<Bitmap?>(null)
    val screenPreviewBitmap: StateFlow<Bitmap?> = _screenPreviewBitmap.asStateFlow()

    private val _audioAmplitude = MutableStateFlow(0f)
    val audioAmplitude: StateFlow<Float> = _audioAmplitude.asStateFlow()

    private val _unifiedSessionState = MutableStateFlow<com.example.services.ScreenShareManager.UnifiedJarvisSessionState>(
        com.example.services.ScreenShareManager.UnifiedJarvisSessionState.Idle
    )
    val unifiedSessionState: StateFlow<com.example.services.ScreenShareManager.UnifiedJarvisSessionState> =
        _unifiedSessionState.asStateFlow()

    fun syncWithUnifiedSessionState(state: com.example.services.ScreenShareManager.UnifiedJarvisSessionState) {
        _unifiedSessionState.value = state
        when (state) {
            is com.example.services.ScreenShareManager.UnifiedJarvisSessionState.PermissionPending -> {
                if (_activePlan.value == null && _activeInstruction.value == null) {
                    _orbState.value = JarvisOrbState.CONNECTING
                }
            }
            is com.example.services.ScreenShareManager.UnifiedJarvisSessionState.Starting -> {
                if (_activePlan.value == null && _activeInstruction.value == null) {
                    _orbState.value = JarvisOrbState.LOADING
                }
            }
            is com.example.services.ScreenShareManager.UnifiedJarvisSessionState.Active -> {
                // Only update orb badge to SCREEN_SHARE_ACTIVE if not actively speaking/listening/thinking/guiding.
                // Never restart/recreate the overlay on frame ticks.
                val cur = _orbState.value
                if (_activePlan.value == null &&
                    _activeInstruction.value == null &&
                    cur != JarvisOrbState.LISTENING &&
                    cur != JarvisOrbState.SPEAKING &&
                    cur != JarvisOrbState.THINKING &&
                    cur != JarvisOrbState.PROCESSING
                ) {
                    _orbState.value = JarvisOrbState.SCREEN_SHARE_ACTIVE
                }
            }
            is com.example.services.ScreenShareManager.UnifiedJarvisSessionState.Error -> {
                _orbState.value = JarvisOrbState.ERROR
            }
            is com.example.services.ScreenShareManager.UnifiedJarvisSessionState.Stopping,
            is com.example.services.ScreenShareManager.UnifiedJarvisSessionState.Stopped,
            is com.example.services.ScreenShareManager.UnifiedJarvisSessionState.Idle -> {
                if (_activePlan.value == null && _activeInstruction.value == null && _orbState.value == JarvisOrbState.SCREEN_SHARE_ACTIVE) {
                    _orbState.value = JarvisOrbState.IDLE
                } else if ((state is com.example.services.ScreenShareManager.UnifiedJarvisSessionState.Stopped ||
                            state is com.example.services.ScreenShareManager.UnifiedJarvisSessionState.Stopping) &&
                    _activePlan.value == null && _activeInstruction.value == null
                ) {
                    _orbState.value = JarvisOrbState.IDLE
                }
            }
            is com.example.services.ScreenShareManager.UnifiedJarvisSessionState.Paused -> {
                if (_activePlan.value == null && _activeInstruction.value == null) {
                    _orbState.value = JarvisOrbState.IDLE
                }
            }
        }
    }

    /**
     * Synchronizes Orb animation state cleanly from the single-AudioRecord microphone state.
     */
    fun syncWithMicrophoneState(
        micState: com.example.voice.KavyaMicrophoneEngine.EngineState,
        voiceState: com.example.ui.components.VoiceState,
        isScreenSharingActive: Boolean
    ) {
        if (_isTestShowcaseRunning.value) return
        val currentOrb = _orbState.value
        if (currentOrb == JarvisOrbState.EXPLAINING || currentOrb == JarvisOrbState.GUIDING) return

        val resolved = when {
            voiceState == com.example.ui.components.VoiceState.SPEAKING -> JarvisOrbState.SPEAKING
            voiceState == com.example.ui.components.VoiceState.THINKING -> JarvisOrbState.THINKING
            voiceState == com.example.ui.components.VoiceState.EXECUTING -> JarvisOrbState.WORKING
            voiceState == com.example.ui.components.VoiceState.ERROR -> JarvisOrbState.ERROR
            micState == com.example.voice.KavyaMicrophoneEngine.EngineState.MIC_STARTING -> JarvisOrbState.CONNECTING
            micState == com.example.voice.KavyaMicrophoneEngine.EngineState.MIC_PROCESSING -> JarvisOrbState.PROCESSING
            micState == com.example.voice.KavyaMicrophoneEngine.EngineState.MIC_SPEECH_DETECTED ||
                micState == com.example.voice.KavyaMicrophoneEngine.EngineState.MIC_ACTIVE ||
                micState == com.example.voice.KavyaMicrophoneEngine.EngineState.MIC_LISTENING ||
                voiceState == com.example.ui.components.VoiceState.UNDERSTANDING -> JarvisOrbState.LISTENING
            micState == com.example.voice.KavyaMicrophoneEngine.EngineState.MIC_ERROR -> JarvisOrbState.ERROR
            isScreenSharingActive -> JarvisOrbState.SCREEN_SHARE_ACTIVE
            else -> JarvisOrbState.IDLE
        }
        if (_orbState.value != resolved) {
            _orbState.value = resolved
        }
    }

    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun loadPersistedConfig(context: Context) {
        val themeName = AppPreferences.getOrbThemeName(context)
        val cursorName = AppPreferences.getOrbCursorStyleName(context)
        val reducedMotion = AppPreferences.isReducedMotionEnabled(context)
        val resolvedTheme = JarvisThemePreset.entries.firstOrNull { it.name == themeName } ?: JarvisThemePreset.NEON_CYAN
        val resolvedCursor = CursorStyle.entries.firstOrNull { it.name == cursorName } ?: CursorStyle.HOLOGRAPHIC_POINTER
        _orbConfig.value = _orbConfig.value.copy(
            theme = resolvedTheme,
            cursorStyle = resolvedCursor,
            reducedMotion = reducedMotion
        )
    }

    fun updateAndPersistConfig(context: Context, transform: (JarvisOrbConfig) -> JarvisOrbConfig) {
        val updated = transform(_orbConfig.value)
        _orbConfig.value = updated
        AppPreferences.setOrbThemeName(context, updated.theme.name)
        AppPreferences.setOrbCursorStyleName(context, updated.cursorStyle.name)
        AppPreferences.setReducedMotionEnabled(context, updated.reducedMotion)
    }

    /**
     * Enables Floating Orb in preferences and attempts to attach overlay service.
     * Returns true if service launch was initiated (attachment still reported honestly via [attachmentState]).
     */
    fun enableFloatingOrb(context: Context): Boolean {
        AppPreferences.setFloatingOrbEnabled(context, true)
        return startOverlay(context)
    }

    /**
     * Disables Floating Orb in preferences and stops/detaches overlay service cleanly.
     */
    fun disableFloatingOrb(context: Context) {
        AppPreferences.setFloatingOrbEnabled(context, false)
        stopOverlay(context)
    }

    /**
     * Restores Floating Orb on app resume or config change if the user previously enabled it.
     */
    fun syncFloatingOrbFromPreferences(context: Context) {
        loadPersistedConfig(context)
        val enabled = AppPreferences.isFloatingOrbEnabled(context)
        if (!enabled) {
            if (_attachmentState.value == OverlayAttachmentState.PERMISSION_MISSING) {
                _attachmentState.value = OverlayAttachmentState.STOPPED
            }
            return
        }
        if (!hasOverlayPermission(context)) {
            _isOverlayRunning.value = false
            _attachmentState.value = OverlayAttachmentState.PERMISSION_MISSING
            _lastOverlayError.value = "Display over other apps permission is missing."
            return
        }
        if (_attachmentState.value != OverlayAttachmentState.ATTACHED_VISIBLE &&
            _attachmentState.value != OverlayAttachmentState.STARTING
        ) {
            startOverlay(context)
        }
    }

    fun startOverlay(context: Context): Boolean {
        loadPersistedConfig(context)
        if (!hasOverlayPermission(context)) {
            Log.w(TAG, "Cannot start JarvisOverlayService: SYSTEM_ALERT_WINDOW permission missing")
            _isOverlayRunning.value = false
            _attachmentState.value = OverlayAttachmentState.PERMISSION_MISSING
            _lastOverlayError.value = "Display over other apps permission is required to show the Floating Orb."
            return false
        }

        // Idempotent guard: if already genuinely attached, just ensure visible/restored without recreating
        if (_attachmentState.value == OverlayAttachmentState.ATTACHED_VISIBLE && _isOverlayRunning.value) {
            _isOrbMinimized.value = false
            return true
        }

        _lastOverlayError.value = null
        _attachmentState.value = OverlayAttachmentState.STARTING
        ScreenAnnotationManager.coordinateMapper.refreshGeometry(context)

        val appCtx = context.applicationContext
        val intent = Intent(appCtx, JarvisOverlayService::class.java).apply {
            action = JarvisOverlayService.ACTION_START_OVERLAY
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appCtx.startForegroundService(intent)
            } else {
                appCtx.startService(intent)
            }
            _isOrbMinimized.value = false
            Log.i(TAG, "JarvisOverlayService start request sent (awaiting WindowManager attach confirmation)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start JarvisOverlayService", e)
            _isOverlayRunning.value = false
            _attachmentState.value = OverlayAttachmentState.ERROR
            _lastOverlayError.value = "Failed to start Floating Orb service: ${e.localizedMessage ?: e.javaClass.simpleName}"
            false
        }
    }

    fun stopOverlay(context: Context) {
        testShowcaseJob?.cancel()
        testShowcaseJob = null
        _isTestShowcaseRunning.value = false
        clearAllVisuals()
        _isOrbExpanded.value = false
        _isOrbMinimized.value = false
        _transientMotion.value = OrbTransientMotion.NONE

        val appCtx = context.applicationContext
        val intent = Intent(appCtx, JarvisOverlayService::class.java).apply {
            action = JarvisOverlayService.ACTION_STOP_OVERLAY
        }
        try {
            appCtx.stopService(Intent(appCtx, JarvisOverlayService::class.java))
            appCtx.startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping JarvisOverlayService: ${e.message}")
        }
        _isOverlayRunning.value = false
        _attachmentState.value = OverlayAttachmentState.STOPPED
        _lastOverlayError.value = null
        Log.i(TAG, "JarvisOverlayService stop request sent")
    }

    /**
     * Emergency exit that immediately clears all overlays, cancels ongoing speech/steps,
     * disables overlay preference if requested, and stops the overlay service so the user is never stuck.
     */
    fun emergencyStopJarvis(context: Context) {
        Log.w(TAG, "Emergency Stop Jarvis triggered!")
        AppPreferences.setFloatingOrbEnabled(context, false)
        ScreenAnnotationManager.clearAllAnnotations()
        stopOverlay(context)
    }

    /**
     * Runs a safe showcase cycle across key Floating Orb states so users can visually test animations.
     */
    fun runTestOrbShowcase(context: Context, stepDelayMs: Long = 650L): Boolean {
        val started = if (hasOverlayPermission(context)) {
            enableFloatingOrb(context)
        } else {
            false
        }
        testShowcaseJob?.cancel()
        _isTestShowcaseRunning.value = true
        setOrbExpanded(true)

        val sequence = listOf(
            JarvisOrbState.CONNECTING,
            JarvisOrbState.LISTENING,
            JarvisOrbState.THINKING,
            JarvisOrbState.PROCESSING,
            JarvisOrbState.SPEAKING,
            JarvisOrbState.SCREEN_SHARE_ACTIVE,
            JarvisOrbState.GUIDING,
            JarvisOrbState.SUCCESS,
            JarvisOrbState.IDLE
        )
        testShowcaseJob = controllerScope.launch {
            try {
                for (state in sequence) {
                    _orbState.value = state
                    _audioAmplitude.value = when (state) {
                        JarvisOrbState.LISTENING -> 0.55f
                        JarvisOrbState.SPEAKING -> 0.78f
                        else -> 0f
                    }
                    delay(stepDelayMs)
                }
            } finally {
                _audioAmplitude.value = 0f
                _isTestShowcaseRunning.value = false
                setOrbExpanded(false)
            }
        }
        return started
    }

    fun setOrbExpanded(expanded: Boolean) {
        if (_isOrbExpanded.value == expanded) return
        _isOrbExpanded.value = expanded
        triggerTransientMotion(if (expanded) OrbTransientMotion.EXPANDING else OrbTransientMotion.COLLAPSING, 280L)
    }

    fun toggleOrbExpanded() {
        setOrbExpanded(!_isOrbExpanded.value)
    }

    fun setOrbMinimized(minimized: Boolean) {
        if (_isOrbMinimized.value == minimized) return
        _isOrbMinimized.value = minimized
        if (minimized) _isOrbExpanded.value = false
        triggerTransientMotion(if (minimized) OrbTransientMotion.COLLAPSING else OrbTransientMotion.EXPANDING, 280L)
    }

    fun toggleOrbMinimized() {
        setOrbMinimized(!_isOrbMinimized.value)
    }

    fun triggerTransientMotion(motion: OrbTransientMotion, autoClearMs: Long = 0L) {
        transientResetJob?.cancel()
        _transientMotion.value = motion
        if (autoClearMs > 0L && motion != OrbTransientMotion.NONE) {
            transientResetJob = controllerScope.launch {
                delay(autoClearMs)
                if (_transientMotion.value == motion) {
                    _transientMotion.value = OrbTransientMotion.NONE
                }
            }
        }
    }

    fun setOrbState(state: JarvisOrbState) {
        _orbState.value = state
    }

    fun setOrbConfig(config: JarvisOrbConfig) {
        _orbConfig.value = config
    }

    fun setAudioAmplitude(amplitude: Float) {
        val clamped = amplitude.coerceIn(0f, 1f)
        if (kotlin.math.abs(_audioAmplitude.value - clamped) >= 0.015f || clamped == 0f) {
            _audioAmplitude.value = clamped
        }
    }

    internal fun setActiveStepInstruction(instruction: VisualInstruction) {
        _activeInstruction.value = instruction
    }

    internal fun reportOverlayAttached(attached: Boolean, errorMessage: String? = null) {
        if (attached) {
            _isOverlayRunning.value = true
            _attachmentState.value = OverlayAttachmentState.ATTACHED_VISIBLE
            _lastOverlayError.value = null
        } else {
            _isOverlayRunning.value = false
            if (!errorMessage.isNullOrBlank()) {
                _attachmentState.value = OverlayAttachmentState.ERROR
                _lastOverlayError.value = errorMessage
            } else if (_attachmentState.value != OverlayAttachmentState.PERMISSION_MISSING) {
                _attachmentState.value = OverlayAttachmentState.STOPPED
            }
        }
    }

    internal fun reportOverlayError(message: String, permissionMissing: Boolean = false) {
        _isOverlayRunning.value = false
        _lastOverlayError.value = message
        _attachmentState.value = if (permissionMissing) {
            OverlayAttachmentState.PERMISSION_MISSING
        } else {
            OverlayAttachmentState.ERROR
        }
    }

    fun presentVisualPlan(plan: JarvisVisualPlan, autoAdvanceSteps: Boolean = false) {
        _activePlan.value = plan
        _activeInstruction.value = plan.instructions.firstOrNull()
        if (plan.fullScreenExplainerEnabled) {
            _isFullScreenExplainerVisible.value = true
        }
        _orbState.value = when (plan.mode) {
            JarvisMode.EXPLAINS_ANYTHING -> JarvisOrbState.EXPLAINING
            JarvisMode.SHOWS_YOU_HOW -> JarvisOrbState.GUIDING
            JarvisMode.DO_IT -> JarvisOrbState.WORKING
            JarvisMode.IDLE -> JarvisOrbState.IDLE
        }

        // Populate ExplanationStepController from plan instructions when valid bounds exist
        val validInstructions = plan.instructions.filter { it.bounds != null && it.bounds.width() > 0 && it.bounds.height() > 0 }
        if (validInstructions.isNotEmpty()) {
            val steps = validInstructions.mapIndexed { idx, inst ->
                val b = inst.bounds!!
                val stepTitle = inst.label.ifBlank { plan.title.ifBlank { "Step ${idx + 1}" } }
                val calloutLabel = when (plan.mode) {
                    JarvisMode.SHOWS_YOU_HOW -> if (inst.label.lowercase().startsWith("tap")) inst.label else "Tap here: ${inst.label}"
                    else -> inst.label.ifBlank { stepTitle }
                }
                val spoken = inst.text.ifBlank {
                    plan.cards.getOrNull(idx)?.summary
                        ?: plan.cards.firstOrNull()?.summary
                        ?: calloutLabel
                }
                ScreenAnnotationStep(
                    stepNumber = idx + 1,
                    totalSteps = validInstructions.size,
                    title = stepTitle,
                    label = calloutLabel,
                    spokenExplanation = spoken,
                    bounds = b,
                    clickPoint = inst.clickPoint ?: Pair(b.centerX(), b.centerY()),
                    showArrow = true,
                    showPointer = true,
                    dimUnrelatedAreas = true
                )
            }
            ExplanationStepController.startSequence(steps, autoAdvance = autoAdvanceSteps)
        } else {
            ExplanationStepController.clear()
        }

        if (plan.targetPackage.isNotBlank()) {
            ScreenAnnotationManager.recordAnnotatedScreen(plan.targetPackage, plan.title)
        }
        Log.i(TAG, "Presented visual plan: '${plan.title}' (${plan.mode}) with ${plan.instructions.size} instructions")
    }

    fun highlightElement(
        bounds: Rect,
        label: String,
        clickPoint: Pair<Int, Int>? = null,
        prompt: String = ""
    ) {
        val actionLabel = if (label.lowercase().startsWith("tap")) label else "Tap here to open $label"
        val spokenText = prompt.ifBlank { "Tap here to open $label." }
        val resolvedPoint = clickPoint ?: Pair(bounds.centerX(), bounds.centerY())

        val instruction = VisualInstruction(
            type = VisualInstructionType.HIGHLIGHT_BOX,
            bounds = bounds,
            clickPoint = resolvedPoint,
            label = actionLabel,
            text = spokenText,
            isPassThroughTouch = true
        )
        _activeInstruction.value = instruction
        _orbState.value = JarvisOrbState.GUIDING

        val singleStep = ScreenAnnotationStep(
            stepNumber = 1,
            totalSteps = 1,
            title = label,
            label = actionLabel,
            spokenExplanation = spokenText,
            bounds = bounds,
            clickPoint = resolvedPoint,
            showArrow = true,
            showPointer = true,
            dimUnrelatedAreas = true
        )
        ExplanationStepController.startSequence(listOf(singleStep), autoAdvance = false)
        Log.i(TAG, "Highlighted element: '$label' at bounds: $bounds")
    }

    fun dismissExplanationCard(cardId: String) {
        val current = _activePlan.value ?: return
        val updated = current.cards.filter { it.id != cardId }
        if (updated.isEmpty()) {
            clearAllVisuals()
        } else {
            _activePlan.value = current.copy(cards = updated)
        }
    }

    fun clearAllVisuals() {
        _activePlan.value = null
        _activeInstruction.value = null
        _isFullScreenExplainerVisible.value = false
        ExplanationStepController.clear()
        _orbState.value = JarvisOrbState.IDLE
    }

    fun showFullScreenExplainer(plan: JarvisVisualPlan, previewBitmap: Bitmap? = null) {
        _activePlan.value = plan
        _screenPreviewBitmap.value = previewBitmap ?: VisionPipeline.getLatestFrame()
        _isFullScreenExplainerVisible.value = true
        _orbState.value = JarvisOrbState.EXPLAINING
    }

    fun dismissFullScreenExplainer() {
        _isFullScreenExplainerVisible.value = false
        if (_activeInstruction.value == null && ExplanationStepController.currentStep.value == null) {
            _orbState.value = JarvisOrbState.IDLE
        }
    }

    internal fun onServiceDestroyed() {
        testShowcaseJob?.cancel()
        testShowcaseJob = null
        _isTestShowcaseRunning.value = false
        _isOverlayRunning.value = false
        _isOrbMinimized.value = false
        _isOrbExpanded.value = false
        _transientMotion.value = OrbTransientMotion.NONE
        if (_attachmentState.value != OverlayAttachmentState.PERMISSION_MISSING &&
            _attachmentState.value != OverlayAttachmentState.ERROR
        ) {
            _attachmentState.value = OverlayAttachmentState.STOPPED
        }
    }
}
