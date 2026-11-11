package com.example.visual

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.*
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Android Service managing the JARVIS Floating Orb, Passive Screen Annotation Canvas,
 * and Compact Floating Explanation Panel.
 *
 * CRITICAL TOUCH & WINDOW ARCHITECTURE:
 * 1. [annotationCanvasView]: MATCH_PARENT x MATCH_PARENT with permanent FLAG_NOT_TOUCHABLE |
 *    FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN. Never intercepts touches under any circumstance.
 * 2. [panelComposeView]: WRAP_CONTENT x WRAP_CONTENT with FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL
 *    when an explanation is active, and View.GONE + FLAG_NOT_TOUCHABLE when idle.
 *    Automatically docks at TOP when the highlighted element is in the bottom half of the screen.
 * 3. [orbComposeView]: WRAP_CONTENT x WRAP_CONTENT with FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL.
 *    Updates WindowManager.LayoutParams (x, y) directly on drag and supports 1-tap close & minimize.
 */
class JarvisOverlayService : Service() {

    companion object {
        private const val TAG = "JarvisOverlayService"
        const val ACTION_START_OVERLAY = "com.example.visual.ACTION_START_OVERLAY"
        const val ACTION_STOP_OVERLAY = "com.example.visual.ACTION_STOP_OVERLAY"
        const val ACTION_EXPLAIN_SCREEN = "com.example.visual.ACTION_EXPLAIN_SCREEN"
    }

    private var windowManager: WindowManager? = null
    private var annotationCanvasView: ComposeView? = null
    private var panelComposeView: ComposeView? = null
    private var orbComposeView: ComposeView? = null

    private var annotationParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var orbParams: WindowManager.LayoutParams? = null

    private val serviceLifecycleOwner = ServiceLifecycleOwner()
    private var fallbackTts: TextToSpeech? = null
    private var isFallbackTtsReady = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "JarvisOverlayService onCreate")
        serviceLifecycleOwner.onCreate()
        serviceLifecycleOwner.onStart()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted; stopping service")
            JarvisOverlayController.onServiceDestroyed()
            stopSelf()
            return
        }

        ScreenAnnotationManager.coordinateMapper.refreshGeometry(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager

        initSpeechCallbacks()
        setupAnnotationCanvasOverlay()
        setupPanelOverlay()
        setupOrbOverlay()
    }

    private fun initSpeechCallbacks() {
        try {
            fallbackTts = TextToSpeech(applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    fallbackTts?.language = Locale.getDefault()
                    isFallbackTtsReady = true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fallback TTS initialization skipped: ${e.message}")
        }

        ExplanationStepController.onSpeakStep = { text ->
            val externalSpeaker = JarvisGuidanceEngine.externalSpeakCallback
            if (externalSpeaker != null) {
                externalSpeaker.invoke(text)
            } else if (isFallbackTtsReady) {
                try {
                    fallbackTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_step_tts")
                } catch (e: Exception) {
                    Log.w(TAG, "Fallback TTS speak error: ${e.message}")
                }
            }
        }

        ExplanationStepController.onStopSpeaking = {
            JarvisGuidanceEngine.externalStopSpeakCallback?.invoke()
            try {
                fallbackTts?.stop()
            } catch (_: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_OVERLAY -> {
                Log.i(TAG, "Stopping JarvisOverlayService by action")
                removeAllOverlayViews()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_EXPLAIN_SCREEN -> {
                JarvisGuidanceEngine.explainActiveScreenAsync(applicationContext, "Explain this screen")
            }
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ScreenAnnotationManager.onConfigurationChanged(this)
        val geom = ScreenAnnotationManager.coordinateMapper.getGeometry()
        val params = orbParams
        val view = orbComposeView
        val wm = windowManager
        if (params != null && view != null && wm != null && view.isAttachedToWindow) {
            params.x = params.x.coerceIn(8, (geom.width - 140).coerceAtLeast(8))
            params.y = params.y.coerceIn(48, (geom.height - 180).coerceAtLeast(48))
            try {
                wm.updateViewLayout(view, params)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update orb position on rotation: ${e.message}")
            }
        }
    }

    private fun getOverlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    /**
     * Window 1: 100% Passive Non-Touchable Annotation Canvas.
     * Permanently uses FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN.
     * Can NEVER block or intercept user touch events on the mobile screen.
     */
    private fun setupAnnotationCanvasOverlay() {
        val wm = windowManager ?: return
        annotationCanvasView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }

        val overlayType = getOverlayWindowType()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        annotationParams = params

        val view = ComposeView(this).apply {
            visibility = View.GONE
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(serviceLifecycleOwner)
            setViewTreeSavedStateRegistryOwner(serviceLifecycleOwner)
            setViewTreeViewModelStoreOwner(serviceLifecycleOwner)

            setContent {
                val plan by JarvisOverlayController.activePlan.collectAsState()
                val orbState by JarvisOverlayController.orbState.collectAsState()
                val orbConfig by JarvisOverlayController.orbConfig.collectAsState()
                val activeInstruction by JarvisOverlayController.activeInstruction.collectAsState()
                val allSteps by ExplanationStepController.steps.collectAsState()
                val currentStep by ExplanationStepController.currentStep.collectAsState()

                val hasVisualAnnotations = currentStep != null ||
                        activeInstruction != null ||
                        (plan != null && plan!!.instructions.isNotEmpty())

                LaunchedEffect(hasVisualAnnotations) {
                    this@apply.visibility = if (hasVisualAnnotations) View.VISIBLE else View.GONE
                }

                if (hasVisualAnnotations) {
                    JarvisOverlayCanvas(
                        plan = plan,
                        orbState = orbState,
                        orbConfig = orbConfig,
                        activeInstruction = activeInstruction,
                        theme = orbConfig.theme,
                        allSteps = allSteps,
                        currentStep = currentStep,
                        showInlinePanel = false
                    )
                }
            }
        }
        annotationCanvasView = view

        try {
            wm.addView(view, params)
            Log.i(TAG, "Passive Annotation Canvas added (FLAG_NOT_TOUCHABLE enforced)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add Passive Annotation Canvas", e)
        }
    }

    /**
     * Window 2: Compact Floating Explanation & Step Control Panel.
     * Uses WRAP_CONTENT x WRAP_CONTENT so the rest of the mobile screen remains 100% interactive.
     * Automatically docks at the TOP when the highlighted UI element is in the bottom half of the screen.
     */
    private fun setupPanelOverlay() {
        val wm = windowManager ?: return
        panelComposeView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }

        val overlayType = getOverlayWindowType()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 100
        }
        panelParams = params

        val view = ComposeView(this).apply {
            visibility = View.GONE
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(serviceLifecycleOwner)
            setViewTreeSavedStateRegistryOwner(serviceLifecycleOwner)
            setViewTreeViewModelStoreOwner(serviceLifecycleOwner)

            setContent {
                val plan by JarvisOverlayController.activePlan.collectAsState()
                val orbConfig by JarvisOverlayController.orbConfig.collectAsState()
                val currentStep by ExplanationStepController.currentStep.collectAsState()
                val isAutoPlaying by ExplanationStepController.isAutoPlaying.collectAsState()
                val isMuted by ExplanationStepController.isMuted.collectAsState()
                val isFullScreenExplainer by JarvisOverlayController.isFullScreenExplainerVisible.collectAsState()
                val previewBitmap by JarvisOverlayController.screenPreviewBitmap.collectAsState()

                val showPanel = isFullScreenExplainer || currentStep != null || (plan != null && plan!!.cards.isNotEmpty())
                val dockAtTop = ScreenAnnotationManager.coordinateMapper.shouldDockPanelAtTop(currentStep?.bounds)

                LaunchedEffect(showPanel, isFullScreenExplainer, dockAtTop) {
                    val p = panelParams ?: return@LaunchedEffect
                    if (!showPanel) {
                        this@apply.visibility = View.GONE
                        p.width = WindowManager.LayoutParams.WRAP_CONTENT
                        p.height = WindowManager.LayoutParams.WRAP_CONTENT
                        p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    } else if (isFullScreenExplainer) {
                        this@apply.visibility = View.VISIBLE
                        p.width = WindowManager.LayoutParams.MATCH_PARENT
                        p.height = WindowManager.LayoutParams.MATCH_PARENT
                        p.gravity = Gravity.CENTER
                        p.y = 0
                        p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    } else {
                        this@apply.visibility = View.VISIBLE
                        p.width = WindowManager.LayoutParams.WRAP_CONTENT
                        p.height = WindowManager.LayoutParams.WRAP_CONTENT
                        p.gravity = (if (dockAtTop) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
                        p.y = if (dockAtTop) 84 else 96
                        p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    }
                    if (this@apply.isAttachedToWindow) {
                        try {
                            wm.updateViewLayout(this@apply, p)
                        } catch (e: Exception) {
                            Log.w(TAG, "Error updating panel layout: ${e.message}")
                        }
                    }
                }

                if (isFullScreenExplainer && plan != null) {
                    JarvisFullScreenExplainer(
                        plan = plan!!,
                        screenPreviewBitmap = previewBitmap,
                        theme = orbConfig.theme,
                        onClose = { JarvisOverlayController.dismissFullScreenExplainer() }
                    )
                } else if (showPanel) {
                    ExplanationPanel(
                        currentStep = currentStep,
                        activePlan = plan,
                        theme = orbConfig.theme,
                        isAutoPlaying = isAutoPlaying,
                        isMuted = isMuted,
                        onPreviousStep = { ExplanationStepController.previousStep() },
                        onNextStep = { ExplanationStepController.nextStep() },
                        onToggleAutoPlay = { ExplanationStepController.toggleAutoPlay() },
                        onToggleMute = { ExplanationStepController.toggleMute() },
                        onToggleDim = { ExplanationStepController.toggleDimming() },
                        onExplainScreenAgain = {
                            JarvisGuidanceEngine.explainActiveScreenAsync(applicationContext, "Explain this screen")
                        },
                        onDismiss = {
                            ScreenAnnotationManager.clearAllAnnotations()
                        },
                        onEmergencyStopJarvis = {
                            JarvisOverlayController.emergencyStopJarvis(applicationContext)
                        }
                    )
                }
            }
        }
        panelComposeView = view

        try {
            wm.addView(view, params)
            Log.i(TAG, "Compact Explanation Panel added to WindowManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add Explanation Panel overlay", e)
        }
    }

    /**
     * Window 3: Lightweight Draggable Floating Orb.
     */
    private fun setupOrbOverlay() {
        val wm = windowManager ?: return
        orbComposeView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }

        val overlayType = getOverlayWindowType()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 240
        }
        orbParams = params

        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(serviceLifecycleOwner)
            setViewTreeSavedStateRegistryOwner(serviceLifecycleOwner)
            setViewTreeViewModelStoreOwner(serviceLifecycleOwner)

            setContent {
                val orbState by JarvisOverlayController.orbState.collectAsState()
                val orbConfig by JarvisOverlayController.orbConfig.collectAsState()
                val amplitude by JarvisOverlayController.audioAmplitude.collectAsState()
                val isMinimized by JarvisOverlayController.isOrbMinimized.collectAsState()

                JarvisFloatingOrb(
                    state = orbState,
                    config = orbConfig,
                    amplitude = amplitude,
                    isWindowOverlay = true,
                    isMinimized = isMinimized,
                    onTap = {
                        Log.d(TAG, "Floating orb tapped in state: $orbState")
                    },
                    onDoubleTap = {
                        // Double tap triggers immediate visual screen explanation
                        JarvisGuidanceEngine.explainActiveScreenAsync(applicationContext, "Explain this screen")
                    },
                    onLongPress = {
                        // Long press clears active annotations
                        ScreenAnnotationManager.clearAllAnnotations()
                    },
                    onDragDelta = { delta ->
                        val p = orbParams ?: return@JarvisFloatingOrb
                        val geom = ScreenAnnotationManager.coordinateMapper.getGeometry()
                        p.x = (p.x + delta.x.roundToInt()).coerceIn(0, (geom.width - 120).coerceAtLeast(0))
                        p.y = (p.y + delta.y.roundToInt()).coerceIn(36, (geom.height - 160).coerceAtLeast(36))
                        if (this@apply.isAttachedToWindow) {
                            try {
                                wm.updateViewLayout(this@apply, p)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed updating orb position: ${e.message}")
                            }
                        }
                    },
                    onDragFinished = {
                        val p = orbParams ?: return@JarvisFloatingOrb
                        if (orbConfig.snapToEdge) {
                            val geom = ScreenAnnotationManager.coordinateMapper.getGeometry()
                            p.x = if (p.x < geom.width / 2) 12 else (geom.width - 130).coerceAtLeast(12)
                            if (this@apply.isAttachedToWindow) {
                                try {
                                    wm.updateViewLayout(this@apply, p)
                                } catch (_: Exception) {}
                            }
                        }
                    },
                    onToggleMinimize = {
                        JarvisOverlayController.toggleOrbMinimized()
                    },
                    onExplainScreen = {
                        JarvisGuidanceEngine.explainActiveScreenAsync(applicationContext, "Explain this screen")
                    },
                    onCloseOrb = {
                        JarvisOverlayController.emergencyStopJarvis(applicationContext)
                    }
                )
            }
        }
        orbComposeView = view

        try {
            wm.addView(view, params)
            Log.i(TAG, "Floating Orb added to WindowManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add Floating Orb overlay", e)
        }
    }

    private fun removeAllOverlayViews() {
        val wm = windowManager
        orbComposeView?.let {
            try { wm?.removeViewImmediate(it) } catch (_: Exception) {
                try { wm?.removeView(it) } catch (_: Exception) {}
            }
        }
        orbComposeView = null

        panelComposeView?.let {
            try { wm?.removeViewImmediate(it) } catch (_: Exception) {
                try { wm?.removeView(it) } catch (_: Exception) {}
            }
        }
        panelComposeView = null

        annotationCanvasView?.let {
            try { wm?.removeViewImmediate(it) } catch (_: Exception) {
                try { wm?.removeView(it) } catch (_: Exception) {}
            }
        }
        annotationCanvasView = null
    }

    override fun onDestroy() {
        Log.i(TAG, "JarvisOverlayService onDestroy")
        ExplanationStepController.clear()
        ExplanationStepController.onSpeakStep = null
        ExplanationStepController.onStopSpeaking = null
        try {
            fallbackTts?.stop()
            fallbackTts?.shutdown()
        } catch (_: Exception) {}
        fallbackTts = null

        removeAllOverlayViews()
        serviceLifecycleOwner.onDestroy()
        JarvisOverlayController.onServiceDestroyed()
        super.onDestroy()
    }
}

/**
 * Minimal Service Lifecycle & SavedState Owner enabling Jetpack Compose in Android Service.
 */
class ServiceLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun onCreate() {
        savedStateRegistryController.performRestore(Bundle())
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun onStart() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}
