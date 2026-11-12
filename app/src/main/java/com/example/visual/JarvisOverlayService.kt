package com.example.visual

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.view.animation.DecelerateInterpolator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.MainActivity
import com.example.agent.MicrophoneEngine
import com.example.services.KavyaVoiceService
import com.example.utils.AppPreferences
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Android Foreground Service managing the JARVIS Floating Orb, Passive Screen Annotation Canvas,
 * and Compact Floating Explanation Panel across apps.
 *
 * CRITICAL TOUCH & WINDOW ARCHITECTURE:
 * 1. [annotationCanvasView]: MATCH_PARENT x MATCH_PARENT with permanent FLAG_NOT_TOUCHABLE |
 *    FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN. Never intercepts touches under any circumstance.
 * 2. [panelComposeView]: WRAP_CONTENT x WRAP_CONTENT with FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL
 *    when an explanation is active, and View.GONE + FLAG_NOT_TOUCHABLE when idle.
 *    Automatically docks at TOP when the highlighted element is in the bottom half of the screen.
 * 3. [orbComposeView]: WRAP_CONTENT x WRAP_CONTENT with FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL.
 *    Updates WindowManager.LayoutParams (x, y) directly on drag with smooth ValueAnimator edge docking
 *    and persists position across orientation changes and app restarts.
 */
class JarvisOverlayService : Service() {

    companion object {
        private const val TAG = "JarvisOverlayService"
        const val ACTION_START_OVERLAY = "com.example.visual.ACTION_START_OVERLAY"
        const val ACTION_STOP_OVERLAY = "com.example.visual.ACTION_STOP_OVERLAY"
        const val ACTION_EXPLAIN_SCREEN = "com.example.visual.ACTION_EXPLAIN_SCREEN"

        private const val NOTIFICATION_CHANNEL_ID = "jarvis_floating_orb_channel"
        private const val NOTIFICATION_ID = 8802
    }

    private var windowManager: WindowManager? = null
    private var annotationCanvasView: ComposeView? = null
    private var panelComposeView: ComposeView? = null
    private var orbComposeView: ComposeView? = null

    private var annotationParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var orbParams: WindowManager.LayoutParams? = null

    private var dockAnimator: ValueAnimator? = null
    private val serviceLifecycleOwner = ServiceLifecycleOwner()
    private var fallbackTts: TextToSpeech? = null
    private var isFallbackTtsReady = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "JarvisOverlayService onCreate")
        serviceLifecycleOwner.onCreate()
        serviceLifecycleOwner.onStart()
        createNotificationChannel()
        ensureForegroundNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted; stopping service")
            JarvisOverlayController.reportOverlayError(
                "Display over other apps permission is missing.",
                permissionMissing = true
            )
            stopSelfCleanly()
            return
        }

        JarvisOverlayController.loadPersistedConfig(this)
        ScreenAnnotationManager.coordinateMapper.refreshGeometry(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager

        initSpeechCallbacks()
        ensureOverlaysAttached()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "JARVIS Floating Orb",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the Kavya JARVIS Floating Orb visible and responsive over other apps."
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun ensureForegroundNotification() {
        try {
            val openAppIntent = Intent(this, MainActivity::class.java)
            val openAppPendingIntent = PendingIntent.getActivity(
                this,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val stopIntent = Intent(this, JarvisOverlayService::class.java).apply {
                action = ACTION_STOP_OVERLAY
            }
            val stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("JARVIS Floating Orb Active")
                .setContentText("Tap orb for quick voice & screen intelligence actions")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(openAppPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Orb", stopPendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to promote JarvisOverlayService to foreground: ${e.message}")
        }
    }

    private fun stopSelfCleanly() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {}
        stopSelf()
    }

    private fun ensureOverlaysAttached() {
        if (windowManager == null) {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        }
        ScreenAnnotationManager.coordinateMapper.refreshGeometry(this)
        if (annotationCanvasView == null || !annotationCanvasView!!.isAttachedToWindow) {
            setupAnnotationCanvasOverlay()
        }
        if (panelComposeView == null || !panelComposeView!!.isAttachedToWindow) {
            setupPanelOverlay()
        }
        if (orbComposeView == null || !orbComposeView!!.isAttachedToWindow) {
            setupOrbOverlay()
        } else {
            // Confirm genuine attachment without recreating view
            JarvisOverlayController.reportOverlayAttached(true)
        }
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
            } else {
                try {
                    com.example.ai.tts.VoiceEngineManager.getInstance(applicationContext).speak(text)
                } catch (e: Exception) {
                    if (isFallbackTtsReady) {
                        try {
                            fallbackTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_step_tts")
                        } catch (ttsErr: Exception) {
                            Log.w(TAG, "Fallback TTS speak error: ${ttsErr.message}")
                        }
                    }
                }
            }
        }

        ExplanationStepController.onStopSpeaking = {
            JarvisGuidanceEngine.externalStopSpeakCallback?.invoke()
            try {
                com.example.ai.tts.VoiceEngineManager.getInstance(applicationContext).stop()
            } catch (_: Exception) {}
            try {
                fallbackTts?.stop()
            } catch (_: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForegroundNotification()
        when (intent?.action) {
            ACTION_STOP_OVERLAY -> {
                Log.i(TAG, "Stopping JarvisOverlayService by action")
                removeAllOverlayViews()
                JarvisOverlayController.reportOverlayAttached(false)
                stopSelfCleanly()
                return START_NOT_STICKY
            }
            ACTION_EXPLAIN_SCREEN -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    JarvisOverlayController.reportOverlayError(
                        "Display over other apps permission is missing.",
                        permissionMissing = true
                    )
                    stopSelfCleanly()
                    return START_NOT_STICKY
                }
                ensureOverlaysAttached()
                JarvisGuidanceEngine.explainActiveScreenAsync(applicationContext, "Explain this screen")
            }
            ACTION_START_OVERLAY, null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    JarvisOverlayController.reportOverlayError(
                        "Display over other apps permission is missing.",
                        permissionMissing = true
                    )
                    stopSelfCleanly()
                    return START_NOT_STICKY
                }
                ensureOverlaysAttached()
            }
        }
        return if (AppPreferences.isFloatingOrbEnabled(this)) START_STICKY else START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        dockAnimator?.cancel()
        ScreenAnnotationManager.onConfigurationChanged(this)
        val geom = ScreenAnnotationManager.coordinateMapper.getGeometry()
        val params = orbParams
        val view = orbComposeView
        val wm = windowManager
        if (params != null && view != null && wm != null && view.isAttachedToWindow) {
            val viewWidth = view.width.takeIf { it > 0 } ?: 140
            val viewHeight = view.height.takeIf { it > 0 } ?: 180
            val maxX = (geom.width - viewWidth - 8).coerceAtLeast(12)
            val maxY = (geom.height - viewHeight - 16).coerceAtLeast(48)
            params.x = params.x.coerceIn(12, maxX)
            params.y = params.y.coerceIn(48, maxY)
            if (JarvisOverlayController.orbConfig.value.snapToEdge) {
                params.x = if (params.x + viewWidth / 2 < geom.width / 2) 12 else maxX
            }
            AppPreferences.setFloatingOrbPosX(this, params.x)
            AppPreferences.setFloatingOrbPosY(this, params.y)
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

    private fun setupOrbOverlay() {
        val wm = windowManager ?: return
        orbComposeView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }

        val geom = ScreenAnnotationManager.coordinateMapper.getGeometry()
        val maxInitialX = (geom.width - 140).coerceAtLeast(12)
        val maxInitialY = (geom.height - 180).coerceAtLeast(48)
        val savedX = AppPreferences.getFloatingOrbPosX(this).coerceIn(12, maxInitialX)
        val savedY = AppPreferences.getFloatingOrbPosY(this).coerceIn(48, maxInitialY)

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
            x = savedX
            y = savedY
        }
        orbParams = params

        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(serviceLifecycleOwner)
            setViewTreeSavedStateRegistryOwner(serviceLifecycleOwner)
            setViewTreeViewModelStoreOwner(serviceLifecycleOwner)

            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    Log.i(TAG, "Floating Orb verified attached to WindowManager")
                    JarvisOverlayController.reportOverlayAttached(true)
                }

                override fun onViewDetachedFromWindow(v: View) {
                    Log.i(TAG, "Floating Orb detached from WindowManager")
                    if (orbComposeView === v) {
                        JarvisOverlayController.reportOverlayAttached(false)
                    }
                }
            })

            setContent {
                val orbState by JarvisOverlayController.orbState.collectAsState()
                val orbConfig by JarvisOverlayController.orbConfig.collectAsState()
                val amplitude by JarvisOverlayController.audioAmplitude.collectAsState()
                val isMinimized by JarvisOverlayController.isOrbMinimized.collectAsState()
                val isExpanded by JarvisOverlayController.isOrbExpanded.collectAsState()
                val transientMotion by JarvisOverlayController.transientMotion.collectAsState()
                val micState by com.example.voice.KavyaMicrophoneEngine.engineState.collectAsState()
                val isMicMuted = micState == com.example.voice.KavyaMicrophoneEngine.EngineState.MIC_OFF ||
                    micState == com.example.voice.KavyaMicrophoneEngine.EngineState.MIC_MUTED ||
                    MicrophoneEngine.getInstance(applicationContext).engineState.value == MicrophoneEngine.State.MUTED

                JarvisFloatingOrb(
                    state = orbState,
                    config = orbConfig,
                    amplitude = amplitude,
                    isWindowOverlay = true,
                    isMinimized = isMinimized,
                    isExpanded = isExpanded,
                    transientMotion = transientMotion,
                    isMicActive = micState.isListening && !isMicMuted,
                    onTap = {
                        Log.d(TAG, "Floating orb tapped in state: $orbState")
                        JarvisOverlayController.toggleOrbExpanded()
                    },
                    onDoubleTap = {
                        JarvisOverlayController.setOrbExpanded(false)
                        JarvisGuidanceEngine.explainActiveScreenAsync(applicationContext, "Explain this screen")
                    },
                    onLongPress = {
                        if (JarvisOverlayController.activePlan.value != null ||
                            JarvisOverlayController.activeInstruction.value != null
                        ) {
                            ScreenAnnotationManager.clearAllAnnotations()
                        } else {
                            JarvisOverlayController.toggleOrbMinimized()
                        }
                    },
                    onDragDelta = { delta ->
                        dockAnimator?.cancel()
                        JarvisOverlayController.triggerTransientMotion(OrbTransientMotion.DRAGGING)
                        val p = orbParams ?: return@JarvisFloatingOrb
                        val currentGeom = ScreenAnnotationManager.coordinateMapper.getGeometry()
                        val viewW = this@apply.width.takeIf { it > 0 } ?: 140
                        val viewH = this@apply.height.takeIf { it > 0 } ?: 180
                        val maxX = (currentGeom.width - viewW).coerceAtLeast(0)
                        val maxY = (currentGeom.height - viewH).coerceAtLeast(36)
                        p.x = (p.x + delta.x.roundToInt()).coerceIn(0, maxX)
                        p.y = (p.y + delta.y.roundToInt()).coerceIn(36, maxY)
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
                        val currentGeom = ScreenAnnotationManager.coordinateMapper.getGeometry()
                        val viewW = this@apply.width.takeIf { it > 0 } ?: 140
                        val maxX = (currentGeom.width - viewW - 12).coerceAtLeast(12)
                        val targetX = if (orbConfig.snapToEdge) {
                            if (p.x + viewW / 2 < currentGeom.width / 2) 12 else maxX
                        } else {
                            p.x.coerceIn(12, maxX)
                        }
                        AppPreferences.setFloatingOrbPosY(applicationContext, p.y)

                        if (orbConfig.reducedMotion || targetX == p.x) {
                            p.x = targetX
                            AppPreferences.setFloatingOrbPosX(applicationContext, targetX)
                            JarvisOverlayController.triggerTransientMotion(OrbTransientMotion.NONE)
                            if (this@apply.isAttachedToWindow) {
                                try { wm.updateViewLayout(this@apply, p) } catch (_: Exception) {}
                            }
                        } else {
                            JarvisOverlayController.triggerTransientMotion(OrbTransientMotion.DOCKING, 260L)
                            dockAnimator?.cancel()
                            val orbView = this@apply
                            dockAnimator = ValueAnimator.ofInt(p.x, targetX).apply {
                                duration = 220L
                                interpolator = DecelerateInterpolator()
                                addUpdateListener { anim ->
                                    val animatedX = anim.animatedValue as Int
                                    p.x = animatedX
                                    if (orbView.isAttachedToWindow) {
                                        try {
                                            wm.updateViewLayout(orbView, p)
                                        } catch (_: Exception) {}
                                    }
                                    if (animatedX == targetX) {
                                        AppPreferences.setFloatingOrbPosX(applicationContext, targetX)
                                    }
                                }
                                start()
                            }
                        }
                    },
                    onToggleMinimize = {
                        JarvisOverlayController.toggleOrbMinimized()
                    },
                    onExplainScreen = {
                        JarvisGuidanceEngine.explainActiveScreenAsync(applicationContext, "Explain this screen")
                    },
                    onReadScreenText = {
                        JarvisGuidanceEngine.explainActiveScreenAsync(applicationContext, "Read all visible text on this screen clearly")
                    },
                    onToggleMic = {
                        val mic = MicrophoneEngine.getInstance(applicationContext)
                        val currentMicState = mic.engineState.value
                        if (currentMicState == MicrophoneEngine.State.IDLE ||
                            currentMicState == MicrophoneEngine.State.ERROR ||
                            currentMicState == MicrophoneEngine.State.STOPPING
                        ) {
                            KavyaVoiceService.startService(applicationContext)
                        } else {
                            mic.setMuted(currentMicState != MicrophoneEngine.State.MUTED)
                        }
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
            if (view.isAttachedToWindow) {
                JarvisOverlayController.reportOverlayAttached(true)
            }
            Log.i(TAG, "Floating Orb addView dispatched to WindowManager at (${params.x}, ${params.y})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add Floating Orb overlay", e)
            JarvisOverlayController.reportOverlayError(
                "Could not display Floating Orb: ${e.localizedMessage ?: e.javaClass.simpleName}",
                permissionMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)
            )
            stopSelfCleanly()
        }
    }

    private fun removeAllOverlayViews() {
        dockAnimator?.cancel()
        dockAnimator = null
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
