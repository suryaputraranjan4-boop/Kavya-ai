package com.example.visual

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
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

/**
 * Android Service managing floating JARVIS Orb and fullscreen overlay canvas.
 * Implements Android Lifecycle & SavedState owners for hosting Jetpack Compose in WindowManager.
 */
class JarvisOverlayService : Service() {

    companion object {
        private const val TAG = "JarvisOverlayService"
        const val ACTION_START_OVERLAY = "com.example.visual.ACTION_START_OVERLAY"
        const val ACTION_STOP_OVERLAY = "com.example.visual.ACTION_STOP_OVERLAY"
    }

    private var windowManager: WindowManager? = null
    private var orbComposeView: ComposeView? = null
    private var canvasComposeView: ComposeView? = null
    private val serviceLifecycleOwner = ServiceLifecycleOwner()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "JarvisOverlayService onCreate")
        serviceLifecycleOwner.onCreate()
        serviceLifecycleOwner.onStart()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted; stopping service")
            stopSelf()
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        setupOrbOverlay()
        setupCanvasOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_OVERLAY -> {
                Log.i(TAG, "Stopping JarvisOverlayService by action")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun setupOrbOverlay() {
        val wm = windowManager ?: return

        orbComposeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(serviceLifecycleOwner)
            setViewTreeSavedStateRegistryOwner(serviceLifecycleOwner)
            setViewTreeViewModelStoreOwner(serviceLifecycleOwner)

            setContent {
                val orbState by JarvisOverlayController.orbState.collectAsState()
                val orbConfig by JarvisOverlayController.orbConfig.collectAsState()
                val amplitude by JarvisOverlayController.audioAmplitude.collectAsState()

                JarvisFloatingOrb(
                    state = orbState,
                    config = orbConfig,
                    amplitude = amplitude,
                    onTap = {
                        // Tap toggles status or cycles to next explanation
                        Log.d(TAG, "Floating orb tapped in state: $orbState")
                    },
                    onDoubleTap = {
                        // Double tap clears highlights or toggles fullscreen explainer
                        val plan = JarvisOverlayController.activePlan.value
                        if (plan != null) {
                            if (JarvisOverlayController.isFullScreenExplainerVisible.value) {
                                JarvisOverlayController.dismissFullScreenExplainer()
                            } else {
                                JarvisOverlayController.showFullScreenExplainer(plan)
                            }
                        }
                    },
                    onLongPress = {
                        // Long press clears active plan
                        JarvisOverlayController.clearAllVisuals()
                    }
                )
            }
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val orbParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 220
        }

        try {
            wm.addView(orbComposeView, orbParams)
            Log.i(TAG, "Floating Orb added to WindowManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add Floating Orb overlay", e)
        }
    }

    private fun setupCanvasOverlay() {
        val wm = windowManager ?: return

        canvasComposeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(serviceLifecycleOwner)
            setViewTreeSavedStateRegistryOwner(serviceLifecycleOwner)
            setViewTreeViewModelStoreOwner(serviceLifecycleOwner)

            setContent {
                val plan by JarvisOverlayController.activePlan.collectAsState()
                val orbState by JarvisOverlayController.orbState.collectAsState()
                val orbConfig by JarvisOverlayController.orbConfig.collectAsState()
                val activeInstruction by JarvisOverlayController.activeInstruction.collectAsState()
                val isFullScreenExplainer by JarvisOverlayController.isFullScreenExplainerVisible.collectAsState()
                val previewBitmap by JarvisOverlayController.screenPreviewBitmap.collectAsState()

                if (isFullScreenExplainer && plan != null) {
                    JarvisFullScreenExplainer(
                        plan = plan!!,
                        screenPreviewBitmap = previewBitmap,
                        theme = orbConfig.theme,
                        onClose = { JarvisOverlayController.dismissFullScreenExplainer() }
                    )
                } else if (plan != null || activeInstruction != null) {
                    JarvisOverlayCanvas(
                        plan = plan,
                        orbState = orbState,
                        orbConfig = orbConfig,
                        activeInstruction = activeInstruction,
                        theme = orbConfig.theme,
                        onDismissCard = { cardId -> JarvisOverlayController.dismissExplanationCard(cardId) },
                        onDismissAll = { JarvisOverlayController.clearAllVisuals() }
                    )
                }
            }
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val canvasParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            wm.addView(canvasComposeView, canvasParams)
            Log.i(TAG, "Fullscreen Canvas Overlay added to WindowManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add Canvas overlay", e)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "JarvisOverlayService onDestroy")
        try {
            orbComposeView?.let { windowManager?.removeView(it) }
            canvasComposeView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay views", e)
        }
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
