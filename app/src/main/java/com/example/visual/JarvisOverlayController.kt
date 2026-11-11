package com.example.visual

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central State & Control Interface for the JARVIS Overlay and Screen Intelligence.
 */
object JarvisOverlayController {

    private const val TAG = "JarvisOverlayController"

    private val _isOverlayRunning = MutableStateFlow(false)
    val isOverlayRunning: StateFlow<Boolean> = _isOverlayRunning.asStateFlow()

    private val _isOrbMinimized = MutableStateFlow(false)
    val isOrbMinimized: StateFlow<Boolean> = _isOrbMinimized.asStateFlow()

    private val _activePlan = MutableStateFlow<JarvisVisualPlan?>(null)
    val activePlan: StateFlow<JarvisVisualPlan?> = _activePlan.asStateFlow()

    private val _activeInstruction = MutableStateFlow<VisualInstruction?>(null)
    val activeInstruction: StateFlow<VisualInstruction?> = _activeInstruction.asStateFlow()

    private val _orbState = MutableStateFlow(JarvisOrbState.IDLE)
    val orbState: StateFlow<JarvisOrbState> = _orbState.asStateFlow()

    private val _orbConfig = MutableStateFlow(JarvisOrbConfig())
    val orbConfig: StateFlow<JarvisOrbConfig> = _orbConfig.asStateFlow()

    private val _isFullScreenExplainerVisible = MutableStateFlow(false)
    val isFullScreenExplainerVisible: StateFlow<Boolean> = _isFullScreenExplainerVisible.asStateFlow()

    private val _screenPreviewBitmap = MutableStateFlow<Bitmap?>(null)
    val screenPreviewBitmap: StateFlow<Bitmap?> = _screenPreviewBitmap.asStateFlow()

    private val _audioAmplitude = MutableStateFlow(0f)
    val audioAmplitude: StateFlow<Float> = _audioAmplitude.asStateFlow()

    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun startOverlay(context: Context) {
        if (!hasOverlayPermission(context)) {
            Log.w(TAG, "Cannot start JarvisOverlayService: SYSTEM_ALERT_WINDOW permission missing")
            return
        }
        ScreenAnnotationManager.coordinateMapper.refreshGeometry(context)
        val intent = Intent(context.applicationContext, JarvisOverlayService::class.java).apply {
            action = JarvisOverlayService.ACTION_START_OVERLAY
        }
        try {
            context.applicationContext.startService(intent)
            _isOverlayRunning.value = true
            _isOrbMinimized.value = false
            Log.i(TAG, "JarvisOverlayService start request sent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start JarvisOverlayService", e)
        }
    }

    fun stopOverlay(context: Context) {
        clearAllVisuals()
        val intent = Intent(context.applicationContext, JarvisOverlayService::class.java).apply {
            action = JarvisOverlayService.ACTION_STOP_OVERLAY
        }
        try {
            context.applicationContext.startService(intent)
            context.applicationContext.stopService(Intent(context.applicationContext, JarvisOverlayService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping JarvisOverlayService: ${e.message}")
        }
        _isOverlayRunning.value = false
        _isOrbMinimized.value = false
        Log.i(TAG, "JarvisOverlayService stop request sent")
    }

    /**
     * Emergency exit that immediately clears all overlays, cancels ongoing speech/steps,
     * and stops the overlay service so the user is never stuck.
     */
    fun emergencyStopJarvis(context: Context) {
        Log.w(TAG, "Emergency Stop Jarvis triggered!")
        ScreenAnnotationManager.clearAllAnnotations()
        stopOverlay(context)
    }

    fun setOrbMinimized(minimized: Boolean) {
        _isOrbMinimized.value = minimized
    }

    fun toggleOrbMinimized() {
        _isOrbMinimized.value = !_isOrbMinimized.value
    }

    fun setOrbState(state: JarvisOrbState) {
        _orbState.value = state
    }

    fun setOrbConfig(config: JarvisOrbConfig) {
        _orbConfig.value = config
    }

    fun setAudioAmplitude(amplitude: Float) {
        _audioAmplitude.value = amplitude
    }

    internal fun setActiveStepInstruction(instruction: VisualInstruction) {
        _activeInstruction.value = instruction
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
        _isOverlayRunning.value = false
        _isOrbMinimized.value = false
    }
}
