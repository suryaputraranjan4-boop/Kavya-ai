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
        val intent = Intent(context, JarvisOverlayService::class.java).apply {
            action = JarvisOverlayService.ACTION_START_OVERLAY
        }
        context.startService(intent)
        _isOverlayRunning.value = true
        Log.i(TAG, "JarvisOverlayService start request sent")
    }

    fun stopOverlay(context: Context) {
        val intent = Intent(context, JarvisOverlayService::class.java).apply {
            action = JarvisOverlayService.ACTION_STOP_OVERLAY
        }
        context.startService(intent)
        _isOverlayRunning.value = false
        Log.i(TAG, "JarvisOverlayService stop request sent")
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

    fun presentVisualPlan(plan: JarvisVisualPlan) {
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
        Log.i(TAG, "Presented visual plan: '${plan.title}' (${plan.mode})")
    }

    fun highlightElement(
        bounds: Rect,
        label: String,
        clickPoint: Pair<Int, Int>? = null,
        prompt: String = ""
    ) {
        val instruction = VisualInstruction(
            type = VisualInstructionType.HIGHLIGHT_BOX,
            bounds = bounds,
            clickPoint = clickPoint ?: Pair(bounds.centerX(), bounds.centerY()),
            label = label,
            text = prompt.ifBlank { "Tap on $label" }
        )
        _activeInstruction.value = instruction
        _orbState.value = JarvisOrbState.GUIDING
        Log.i(TAG, "Highlighted element: '$label' at bounds: $bounds")
    }

    fun dismissExplanationCard(cardId: String) {
        val current = _activePlan.value ?: return
        val updated = current.cards.filter { it.id != cardId }
        _activePlan.value = current.copy(cards = updated)
        if (updated.isEmpty() && _activeInstruction.value == null) {
            _orbState.value = JarvisOrbState.IDLE
        }
    }

    fun clearAllVisuals() {
        _activePlan.value = null
        _activeInstruction.value = null
        _isFullScreenExplainerVisible.value = false
        _orbState.value = JarvisOrbState.IDLE
    }

    fun showFullScreenExplainer(plan: JarvisVisualPlan, previewBitmap: Bitmap? = null) {
        _activePlan.value = plan
        _screenPreviewBitmap.value = previewBitmap
        _isFullScreenExplainerVisible.value = true
        _orbState.value = JarvisOrbState.EXPLAINING
    }

    fun dismissFullScreenExplainer() {
        _isFullScreenExplainerVisible.value = false
        if (_activeInstruction.value == null) {
            _orbState.value = JarvisOrbState.IDLE
        }
    }

    internal fun onServiceDestroyed() {
        _isOverlayRunning.value = false
    }
}
