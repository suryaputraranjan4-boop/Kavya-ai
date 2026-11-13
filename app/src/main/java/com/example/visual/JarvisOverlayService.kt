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
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.MainActivity
import com.example.orchestrator.TaskOrchestrationStateMachine
import com.example.services.ScreenShareManager
import com.example.utils.AppPreferences
import com.example.voice.KavyaMicrophoneEngine
import kotlin.math.roundToInt

/**
 * Dedicated Lifecycle + SavedState owner for the single Unified JARVIS Live Overlay window.
 */
private class OverlayWindowLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    fun onCreate() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun onStart() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    fun onResume() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onDestroy() {
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.INITIALIZED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }
}

/**
 * Unified JARVIS Live Overlay Service (Phase 3 & Phase 11).
 *
 * Uses a SINGLE authoritative WindowManager attachment (`unifiedOverlayView`) hosting one Compose root
 * (`UnifiedJarvisLiveOverlayRoot`) that seamlessly combines:
 * 1. Draggable Floating Orb (with sound reactivity, minimize pill, edge snap, and quick actions).
 * 2. Live session status badge (Voice state, Screen Share state, CEO Orchestrator task/verification state).
 * 3. Screen Explanation & Step-by-Step Visual Guidance (`JarvisOverlayCanvas` + `ExplanationPanel` + `JarvisFullScreenExplainer`).
 *
 * Window touchability adapts cleanly:
 * - When only the compact Floating Orb is shown, the window is `WRAP_CONTENT` at `(orbX, orbY)` so the rest
 *   of the phone screen passes touches directly to the underlying app.
 * - When an Explanation Panel, Step-by-Step Guidance, or Full-Screen Explainer is active, the single window
 *   expands to `MATCH_PARENT` so annotations, explanation cards, and the orb render together without
 *   multi-window lifecycle collisions or `DisposeOnDetachedFromWindow` crashes.
 */
class JarvisOverlayService : Service() {

    companion object {
        private const val TAG = "JarvisOverlayService"
        private const val CHANNEL_ID = "jarvis_overlay_channel"
        private const val NOTIFICATION_ID = 4049

        const val ACTION_START_OVERLAY = "com.example.visual.action.START_OVERLAY"
        const val ACTION_STOP_OVERLAY = "com.example.visual.action.STOP_OVERLAY"
        const val ACTION_MINIMIZE_ORB = "com.example.visual.action.MINIMIZE_ORB"
        const val ACTION_RESTORE_ORB = "com.example.visual.action.RESTORE_ORB"
        const val ACTION_CLEAR_ANNOTATIONS = "com.example.visual.action.CLEAR_ANNOTATIONS"
        const val ACTION_EXPLAIN_SCREEN = "com.example.visual.action.EXPLAIN_SCREEN"
    }

    private var windowManager: WindowManager? = null
    private var unifiedOverlayView: ComposeView? = null
    private var unifiedLayoutParams: WindowManager.LayoutParams? = null
    private var isUnifiedWindowAttached: Boolean = false
    private var isFullScreenLayoutActive: Boolean = false

    private var snapAnimator: ValueAnimator? = null
    private var orbWindowX: Int = 24
    private var orbWindowY: Int = 260

    private val serviceLifecycleOwner = OverlayWindowLifecycleOwner()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceLifecycleOwner.onCreate()
        serviceLifecycleOwner.onStart()
        serviceLifecycleOwner.onResume()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        ScreenAnnotationManager.coordinateMapper.refreshGeometry(this)
        JarvisOverlayController.loadPersistedConfig(this)
        restoreSavedOrbPosition()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_OVERLAY -> {
                AppPreferences.setFloatingOrbEnabled(this, false)
                stopAndDetachAll()
                return START_NOT_STICKY
            }
            ACTION_MINIMIZE_ORB -> {
                JarvisOverlayController.setOrbMinimized(true)
                return START_STICKY
            }
            ACTION_RESTORE_ORB -> {
                JarvisOverlayController.setOrbMinimized(false)
                return START_STICKY
            }
            ACTION_CLEAR_ANNOTATIONS -> {
                ScreenAnnotationManager.clearAllAnnotations()
                return START_STICKY
            }
            ACTION_EXPLAIN_SCREEN -> {
                JarvisGuidanceEngine.explainActiveScreenAsync(this)
                return START_STICKY
            }
        }

        if (!hasOverlayPermission()) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted; stopping JarvisOverlayService cleanly.")
            JarvisOverlayController.reportOverlayError(
                message = "Display over other apps permission is missing.",
                permissionMissing = true
            )
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startOverlayForegroundNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Foreground notification failed: ${e.message}", e)
            JarvisOverlayController.reportOverlayError(
                message = "Could not start overlay foreground notification: ${e.localizedMessage ?: "error"}"
            )
            stopSelf()
            return START_NOT_STICKY
        }

        val attached = ensureUnifiedOverlayWindowAttached()
        if (attached) {
            JarvisOverlayController.reportOverlayAttached(true)
        } else {
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ScreenAnnotationManager.onConfigurationChanged(this)
        clampOrbPositionToCurrentDisplay()
        if (!isFullScreenLayoutActive) {
            updateCompactOrbWindowPosition()
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
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

    private fun restoreSavedOrbPosition() {
        val geom = ScreenAnnotationManager.coordinateMapper.getGeometry()
        val savedX = AppPreferences.getFloatingOrbPosX(this)
        val savedY = AppPreferences.getFloatingOrbPosY(this)
        val marginPx = dpToPx(12)
        val orbApproxPx = dpToPx(96)
        val minY = geom.statusBarInsetTop + marginPx
        val maxY = (geom.height - geom.navBarInsetBottom - orbApproxPx - marginPx).coerceAtLeast(minY + 40)
        val maxX = (geom.width - orbApproxPx - marginPx).coerceAtLeast(marginPx)

        if (savedX >= 0 && savedY >= 0) {
            orbWindowX = savedX.coerceIn(marginPx, maxX)
            orbWindowY = savedY.coerceIn(minY, maxY)
        } else {
            orbWindowX = maxX
            orbWindowY = (geom.height * 0.28f).roundToInt().coerceIn(minY, maxY)
        }
    }

    private fun clampOrbPositionToCurrentDisplay() {
        val geom = ScreenAnnotationManager.coordinateMapper.refreshGeometry(this)
        val marginPx = dpToPx(8)
        val orbApproxPx = dpToPx(96)
        val minX = geom.navBarInsetLeft + marginPx
        val maxX = (geom.width - geom.navBarInsetRight - orbApproxPx - marginPx).coerceAtLeast(minX)
        val minY = geom.statusBarInsetTop + marginPx
        val maxY = (geom.height - geom.navBarInsetBottom - orbApproxPx - marginPx).coerceAtLeast(minY)
        orbWindowX = orbWindowX.coerceIn(minX, maxX)
        orbWindowY = orbWindowY.coerceIn(minY, maxY)
    }

    private fun attachComposeOwnerTree(view: View) {
        view.setViewTreeLifecycleOwner(serviceLifecycleOwner)
        view.setViewTreeSavedStateRegistryOwner(serviceLifecycleOwner)
    }

    /**
     * Attaches the SINGLE authoritative Unified JARVIS Live Overlay window to WindowManager.
     * Prevents duplicate addView() calls and lifecycle collisions.
     */
    private fun ensureUnifiedOverlayWindowAttached(): Boolean {
        val wm = windowManager ?: return false
        if (isUnifiedWindowAttached && unifiedOverlayView != null && unifiedOverlayView?.parent != null) {
            return true
        }

        return try {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                getOverlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = orbWindowX
                y = orbWindowY
                title = "KavyaUnifiedJarvisOverlay"
            }

            val composeView = ComposeView(this).apply {
                attachComposeOwnerTree(this)
                // Use DisposeOnLifecycleDestroyed so transient layout updates NEVER dispose the composition!
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnLifecycleDestroyed(serviceLifecycleOwner.lifecycle)
                )
                setContent {
                    UnifiedJarvisLiveOverlayRoot()
                }
            }

            wm.addView(composeView, params)
            unifiedOverlayView = composeView
            unifiedLayoutParams = params
            isUnifiedWindowAttached = true
            isFullScreenLayoutActive = false
            JarvisOverlayController.setActiveWindowCount(1)
            Log.i(TAG, "Attached single Unified JARVIS Live Overlay window at ($orbWindowX, $orbWindowY)")
            true
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException attaching Unified JARVIS Overlay window: ${se.message}", se)
            isUnifiedWindowAttached = false
            JarvisOverlayController.reportOverlayError(
                message = "Overlay permission revoked by system.",
                permissionMissing = true
            )
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach Unified JARVIS Overlay window: ${e.message}", e)
            isUnifiedWindowAttached = false
            JarvisOverlayController.reportOverlayError(
                message = "Overlay window failed to attach: ${e.localizedMessage ?: "unknown error"}"
            )
            false
        }
    }

    /**
     * Safely transitions the single WindowManager root between:
     * - Compact pass-through Orb mode (`WRAP_CONTENT` at `(orbWindowX, orbWindowY)`)
     * - Expanded Visual Explanation / Guidance mode (`MATCH_PARENT x MATCH_PARENT`)
     * Never detaches or hides the root view.
     */
    private fun updateUnifiedWindowMode(expandFullScreen: Boolean) {
        val wm = windowManager ?: return
        val view = unifiedOverlayView ?: return
        val params = unifiedLayoutParams ?: return
        if (!isUnifiedWindowAttached || view.parent == null) return
        if (isFullScreenLayoutActive == expandFullScreen) return

        try {
            isFullScreenLayoutActive = expandFullScreen
            if (expandFullScreen) {
                params.width = WindowManager.LayoutParams.MATCH_PARENT
                params.height = WindowManager.LayoutParams.MATCH_PARENT
                params.x = 0
                params.y = 0
            } else {
                params.width = WindowManager.LayoutParams.WRAP_CONTENT
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                params.x = orbWindowX
                params.y = orbWindowY
            }
            wm.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.w(TAG, "Safe window mode update ignored: ${e.message}")
        }
    }

    private fun updateCompactOrbWindowPosition() {
        val wm = windowManager ?: return
        val view = unifiedOverlayView ?: return
        val params = unifiedLayoutParams ?: return
        if (!isUnifiedWindowAttached || view.parent == null || isFullScreenLayoutActive) return

        try {
            params.x = orbWindowX
            params.y = orbWindowY
            wm.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.w(TAG, "Orb window position update ignored: ${e.message}")
        }
    }

    @Composable
    private fun UnifiedJarvisLiveOverlayRoot() {
        val activePlan by JarvisOverlayController.activePlan.collectAsState()
        val activeInstruction by JarvisOverlayController.activeInstruction.collectAsState()
        val orbState by JarvisOverlayController.orbState.collectAsState()
        val orbConfig by JarvisOverlayController.orbConfig.collectAsState()
        val isMinimized by JarvisOverlayController.isOrbMinimized.collectAsState()
        val isExpanded by JarvisOverlayController.isOrbExpanded.collectAsState()
        val transientMotion by JarvisOverlayController.transientMotion.collectAsState()
        val showFullScreen by JarvisOverlayController.isFullScreenExplainerVisible.collectAsState()
        val previewBitmap by JarvisOverlayController.screenPreviewBitmap.collectAsState()
        val amplitude by JarvisOverlayController.audioAmplitude.collectAsState()
        val explanationLevel by JarvisOverlayController.activeExplanationLevel.collectAsState()
        val inlineStatusBanner by JarvisOverlayController.inlineStatusBanner.collectAsState()
        val unifiedSessionState by JarvisOverlayController.unifiedSessionState.collectAsState()

        val allSteps by ExplanationStepController.steps.collectAsState()
        val currentStep by ExplanationStepController.currentStep.collectAsState()
        val isAutoPlaying by ExplanationStepController.isAutoPlaying.collectAsState()
        val isMuted by ExplanationStepController.isMuted.collectAsState()
        val micEngineState by KavyaMicrophoneEngine.engineState.collectAsState()
        val orchestrationSnapshot by TaskOrchestrationStateMachine.snapshot.collectAsState()

        val isMicActive = micEngineState == KavyaMicrophoneEngine.EngineState.MIC_ACTIVE ||
            micEngineState == KavyaMicrophoneEngine.EngineState.MIC_LISTENING ||
            micEngineState == KavyaMicrophoneEngine.EngineState.MIC_SPEECH_DETECTED ||
            micEngineState == KavyaMicrophoneEngine.EngineState.MIC_SLEEPING

        val hasExplanationOrSteps = currentStep != null || activePlan?.cards?.isNotEmpty() == true
        val hasCanvasAnnotations = currentStep != null || activeInstruction != null || activePlan?.instructions?.isNotEmpty() == true
        val needFullScreenCanvas = showFullScreen || hasExplanationOrSteps || hasCanvasAnnotations

        LaunchedEffect(needFullScreenCanvas) {
            updateUnifiedWindowMode(needFullScreenCanvas)
        }

        if (!needFullScreenCanvas) {
            // Compact Pass-Through Mode: Only the Orb + Quick Action Bar + Compact Live Status Pill
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .wrapContentSize()
                    .testTag("unified_jarvis_overlay_compact_root")
            ) {
                JarvisFloatingOrb(
                    state = orbState,
                    config = orbConfig,
                    amplitude = amplitude,
                    isWindowOverlay = true,
                    isMinimized = isMinimized,
                    isExpanded = isExpanded,
                    transientMotion = transientMotion,
                    isMicActive = isMicActive,
                    onTap = {
                        JarvisOverlayController.toggleOrbExpanded()
                    },
                    onDoubleTap = {
                        JarvisOverlayController.setOrbMinimized(!isMinimized)
                    },
                    onLongPress = {
                        JarvisGuidanceEngine.explainActiveScreenAsync(this@JarvisOverlayService)
                    },
                    onDragDelta = { dragAmount ->
                        onOrbDragged(dragAmount.x, dragAmount.y)
                    },
                    onDragFinished = {
                        onOrbDragFinished()
                    },
                    onToggleMinimize = {
                        JarvisOverlayController.setOrbMinimized(!isMinimized)
                    },
                    onExplainScreen = {
                        JarvisGuidanceEngine.explainActiveScreenAsync(this@JarvisOverlayService)
                    },
                    onReadScreenText = {
                        JarvisGuidanceEngine.readCurrentScreenAloud(this@JarvisOverlayService)
                    },
                    onToggleMic = {
                        handleMicToggleFromOrb(micEngineState)
                    },
                    onCloseOrb = {
                        AppPreferences.setFloatingOrbEnabled(this@JarvisOverlayService, false)
                        stopAndDetachAll()
                    }
                )

                val statusLine = inlineStatusBanner
                    ?: orchestrationSnapshot.stepDescription.ifBlank { orchestrationSnapshot.lastActionSummary }
                        .takeIf { orchestrationSnapshot.isRunning && it.isNotBlank() }
                AnimatedVisibility(
                    visible = !isMinimized && !statusLine.isNullOrBlank(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xEE080E18),
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .widthIn(max = 220.dp)
                            .border(1.dp, orbConfig.theme.primaryColor.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    ) {
                        Text(
                            text = statusLine.orEmpty(),
                            color = Color.White,
                            fontSize = 10.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        } else {
            // Expanded Unified Overlay Mode: Canvas Annotations + Explanation/Step Card + Floating Orb
            val focusBounds = currentStep?.bounds
                ?: activeInstruction?.bounds
                ?: activePlan?.instructions?.firstOrNull()?.bounds
            val dockAtTop = ScreenAnnotationManager.coordinateMapper.shouldDockPanelAtTop(focusBounds)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("unified_jarvis_overlay_expanded_root")
            ) {
                // 1. Passive Screen Annotations Canvas (Highlight boxes, step numbers, arrows, pointer)
                JarvisOverlayCanvas(
                    plan = activePlan,
                    orbState = orbState,
                    orbConfig = orbConfig,
                    activeInstruction = activeInstruction,
                    theme = orbConfig.theme,
                    allSteps = allSteps,
                    currentStep = currentStep,
                    showInlinePanel = false,
                    onDismissCard = { cardId ->
                        JarvisOverlayController.dismissExplanationCard(cardId)
                    },
                    onDismissAll = {
                        ScreenAnnotationManager.clearAllAnnotations()
                    },
                    onStepCompleted = {
                        ExplanationStepController.nextStep()
                    }
                )

                // 2. Full-Screen Explainer or Floating Explanation & Step Guidance Card
                val currentFullPlan = activePlan
                if (showFullScreen && currentFullPlan != null) {
                    JarvisFullScreenExplainer(
                        plan = currentFullPlan,
                        screenPreviewBitmap = previewBitmap,
                        theme = orbConfig.theme,
                        onClose = {
                            JarvisOverlayController.dismissFullScreenExplainer()
                        }
                    )
                } else if (hasExplanationOrSteps) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .align(if (dockAtTop) Alignment.TopCenter else Alignment.BottomCenter)
                            .padding(
                                top = if (dockAtTop) 44.dp else 8.dp,
                                bottom = if (dockAtTop) 8.dp else 36.dp,
                                start = 12.dp,
                                end = 12.dp
                            )
                    ) {
                        // Unified Session & Presentation Level Header Pill
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xF0070D17),
                            modifier = Modifier
                                .padding(bottom = 4.dp)
                                .border(1.dp, orbConfig.theme.primaryColor.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(orbConfig.theme.primaryColor)
                                )
                                Text(
                                    text = "JARVIS LIVE • ${explanationLevel.badgeLabel}",
                                    color = orbConfig.theme.primaryColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                if (ScreenShareManager.isScreenSharingActive) {
                                    Text(
                                        text = "• SCREEN SHARE ON",
                                        color = Color(0xFF00E676),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }

                        ExplanationPanel(
                            currentStep = currentStep,
                            activePlan = activePlan,
                            theme = orbConfig.theme,
                            isAutoPlaying = isAutoPlaying,
                            isMuted = isMuted,
                            onPreviousStep = { ExplanationStepController.previousStep() },
                            onNextStep = { ExplanationStepController.nextStep() },
                            onToggleAutoPlay = { ExplanationStepController.toggleAutoPlay() },
                            onToggleMute = { ExplanationStepController.toggleMute() },
                            onToggleDim = { ExplanationStepController.toggleDimming() },
                            onExplainScreenAgain = {
                                JarvisGuidanceEngine.explainActiveScreenAsync(this@JarvisOverlayService)
                            },
                            onDismiss = {
                                ScreenAnnotationManager.clearAllAnnotations()
                                JarvisGuidanceEngine.dismissExplanation()
                            },
                            onEmergencyStopJarvis = {
                                ScreenAnnotationManager.clearAllAnnotations()
                                JarvisGuidanceEngine.dismissExplanation()
                                AppPreferences.setFloatingOrbEnabled(this@JarvisOverlayService, false)
                                stopAndDetachAll()
                            }
                        )
                    }
                }

                // 3. Keep the Floating Orb visible and interactive inside the same Unified Overlay Root!
                Box(
                    modifier = Modifier
                        .offset { IntOffset(orbWindowX.coerceAtLeast(0), orbWindowY.coerceAtLeast(0)) }
                ) {
                    JarvisFloatingOrb(
                        state = orbState,
                        config = orbConfig,
                        amplitude = amplitude,
                        isWindowOverlay = true,
                        isMinimized = isMinimized,
                        isExpanded = isExpanded,
                        transientMotion = transientMotion,
                        isMicActive = isMicActive,
                        onTap = {
                            JarvisOverlayController.toggleOrbExpanded()
                        },
                        onDoubleTap = {
                            ScreenAnnotationManager.clearAllAnnotations()
                        },
                        onLongPress = {
                            JarvisGuidanceEngine.explainActiveScreenAsync(this@JarvisOverlayService)
                        },
                        onDragDelta = { dragAmount ->
                            onOrbDragged(dragAmount.x, dragAmount.y)
                        },
                        onDragFinished = {
                            onOrbDragFinished()
                        },
                        onToggleMinimize = {
                            ScreenAnnotationManager.clearAllAnnotations()
                            JarvisOverlayController.setOrbMinimized(!isMinimized)
                        },
                        onExplainScreen = {
                            JarvisGuidanceEngine.explainActiveScreenAsync(this@JarvisOverlayService)
                        },
                        onReadScreenText = {
                            JarvisGuidanceEngine.readCurrentScreenAloud(this@JarvisOverlayService)
                        },
                        onToggleMic = {
                            handleMicToggleFromOrb(micEngineState)
                        },
                        onCloseOrb = {
                            AppPreferences.setFloatingOrbEnabled(this@JarvisOverlayService, false)
                            stopAndDetachAll()
                        }
                    )
                }
            }
        }
    }

    private fun handleMicToggleFromOrb(micState: KavyaMicrophoneEngine.EngineState) {
        when (micState) {
            KavyaMicrophoneEngine.EngineState.MIC_OFF,
            KavyaMicrophoneEngine.EngineState.MIC_ERROR -> {
                KavyaMicrophoneEngine.startService(this)
            }
            KavyaMicrophoneEngine.EngineState.MIC_SLEEPING -> {
                KavyaMicrophoneEngine.wake(this)
            }
            KavyaMicrophoneEngine.EngineState.MIC_MUTED -> {
                KavyaMicrophoneEngine.setMuted(this, false)
            }
            else -> {
                KavyaMicrophoneEngine.toggleMute(this)
            }
        }
    }

    private fun onOrbDragged(dx: Float, dy: Float) {
        snapAnimator?.cancel()
        JarvisOverlayController.triggerTransientMotion(OrbTransientMotion.DRAGGING)
        val geom = ScreenAnnotationManager.coordinateMapper.getGeometry()
        val marginPx = dpToPx(6)
        val orbApproxPx = dpToPx(96)
        val minX = geom.navBarInsetLeft + marginPx
        val maxX = (geom.width - geom.navBarInsetRight - orbApproxPx - marginPx).coerceAtLeast(minX)
        val minY = geom.statusBarInsetTop + marginPx
        val maxY = (geom.height - geom.navBarInsetBottom - orbApproxPx - marginPx).coerceAtLeast(minY)

        orbWindowX = (orbWindowX + dx.roundToInt()).coerceIn(minX, maxX)
        orbWindowY = (orbWindowY + dy.roundToInt()).coerceIn(minY, maxY)
        updateCompactOrbWindowPosition()
    }

    private fun onOrbDragFinished() {
        val cfg = JarvisOverlayController.orbConfig.value
        val geom = ScreenAnnotationManager.coordinateMapper.getGeometry()
        val marginPx = dpToPx(10)
        val orbApproxPx = dpToPx(96)
        val minX = geom.navBarInsetLeft + marginPx
        val maxX = (geom.width - geom.navBarInsetRight - orbApproxPx - marginPx).coerceAtLeast(minX)

        if (!cfg.snapToEdge) {
            AppPreferences.setFloatingOrbPosX(this, orbWindowX)
            AppPreferences.setFloatingOrbPosY(this, orbWindowY)
            JarvisOverlayController.triggerTransientMotion(OrbTransientMotion.NONE)
            return
        }

        val targetX = if (orbWindowX + (orbApproxPx / 2) < geom.width / 2) minX else maxX
        if (cfg.reducedMotion || kotlin.math.abs(targetX - orbWindowX) < 6) {
            orbWindowX = targetX
            updateCompactOrbWindowPosition()
            AppPreferences.setFloatingOrbPosX(this, orbWindowX)
            AppPreferences.setFloatingOrbPosY(this, orbWindowY)
            JarvisOverlayController.triggerTransientMotion(OrbTransientMotion.NONE)
            return
        }

        JarvisOverlayController.triggerTransientMotion(OrbTransientMotion.DOCKING)
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(orbWindowX, targetX).apply {
            duration = 210L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                orbWindowX = anim.animatedValue as Int
                updateCompactOrbWindowPosition()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    AppPreferences.setFloatingOrbPosX(this@JarvisOverlayService, orbWindowX)
                    AppPreferences.setFloatingOrbPosY(this@JarvisOverlayService, orbWindowY)
                    JarvisOverlayController.triggerTransientMotion(OrbTransientMotion.NONE)
                }
            })
            start()
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density.takeIf { it > 0f } ?: 2.75f
        return (dp * density).roundToInt()
    }

    private fun stopAndDetachAll() {
        snapAnimator?.cancel()
        snapAnimator = null
        ScreenAnnotationManager.clearAllAnnotations()
        removeUnifiedOverlayWindowSafely()
        JarvisOverlayController.onServiceDestroyed()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun removeUnifiedOverlayWindowSafely() {
        val wm = windowManager
        val view = unifiedOverlayView
        unifiedOverlayView = null
        unifiedLayoutParams = null
        isUnifiedWindowAttached = false
        isFullScreenLayoutActive = false
        JarvisOverlayController.setActiveWindowCount(0)

        if (wm != null && view != null) {
            try {
                if (view.parent != null) {
                    wm.removeViewImmediate(view)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Safe removeView ignored: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        snapAnimator?.cancel()
        snapAnimator = null
        removeUnifiedOverlayWindowSafely()
        serviceLifecycleOwner.onDestroy()
        JarvisOverlayController.onServiceDestroyed()
        super.onDestroy()
    }

    private fun startOverlayForegroundNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Live Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Unified JARVIS Floating Orb, Screen Share & Visual Guidance"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val explainIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, JarvisOverlayService::class.java).apply {
                action = ACTION_EXPLAIN_SCREEN
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val clearIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, JarvisOverlayService::class.java).apply {
                action = ACTION_CLEAR_ANNOTATIONS
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, JarvisOverlayService::class.java).apply {
                action = ACTION_STOP_OVERLAY
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("JARVIS Live Overlay Active")
            .setContentText("Tap orb or ask by voice to explain or guide on any screen")
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_search, "Explain Screen", explainIntent)
            .addAction(android.R.drawable.ic_menu_revert, "Clear Visuals", clearIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop JARVIS", stopIntent)
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
    }
}
