package com.example.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import android.util.Log

import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.components.KavyaVoiceOrb
import com.example.ui.components.VoiceState
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import com.example.ai.GeminiLiveClient
import com.example.api.ApiSystem
import com.example.agent.AndroidAgent
import com.example.agent.ContextEngine
import com.example.agent.ScreenInspector
import com.example.agent.VerificationEngine
import com.example.utils.CommandRouter
import com.example.agent.MemoryEngine

class KavyaVoiceService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private lateinit var windowManager: WindowManager
    private var floatingView: ComposeView? = null
    
    private var geminiLiveClient: GeminiLiveClient? = null
    
    private lateinit var router: CommandRouter
    private lateinit var contextEngine: ContextEngine
    private lateinit var screenInspector: ScreenInspector
    private lateinit var verificationEngine: VerificationEngine
    private lateinit var androidAgent: AndroidAgent
    private lateinit var memoryEngine: MemoryEngine
    private lateinit var apiSystem: ApiSystem

    private var voiceState by mutableStateOf(VoiceState.IDLE)
    
    // Lifecycle components for ComposeView
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    
    companion object {
        const val CHANNEL_ID = "kavya_voice_service_channel"
        const val NOTIFICATION_ID = 101
        private const val TAG = "KavyaVoice"
        
        var isListening = false
    }

    override fun onCreate() {
        super.onCreate()
        
        router = CommandRouter(this)
        contextEngine = ContextEngine()
        screenInspector = ScreenInspector()
        verificationEngine = VerificationEngine(screenInspector)
        androidAgent = AndroidAgent(this, router.appResolver, contextEngine, screenInspector, verificationEngine)
        memoryEngine = MemoryEngine(this)
        apiSystem = ApiSystem()

        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        
        createNotificationChannel()
        val hasRecordAudio = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasRecordAudio) {
                startForeground(NOTIFICATION_ID, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground with microphone type failed, falling back: ${e.message}")
            try {
                startForeground(NOTIFICATION_ID, createNotification())
            } catch (e2: Exception) {
                Log.e(TAG, "startForeground fallback failed: ${e2.message}")
            }
        }
        
        geminiLiveClient = GeminiLiveClient(
            context = this,
            apiSystem = apiSystem,
            androidAgent = androidAgent,
            onStateChange = { state ->
                when (state) {
                    "IDLE" -> voiceState = VoiceState.IDLE
                    "LISTENING" -> voiceState = VoiceState.LISTENING
                    "SPEAKING" -> voiceState = VoiceState.SPEAKING
                    "THINKING" -> voiceState = VoiceState.THINKING
                    "ERROR" -> voiceState = VoiceState.ERROR
                }
            },
            onCaption = { caption ->
                // Handle captions if needed
            }
        )
        
        setupFloatingWindow()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startListening() {
        if (!isListening) {
            isListening = true
            geminiLiveClient?.startSession()
        }
    }
    
    private fun stopListening() {
        if (isListening) {
            isListening = false
            geminiLiveClient?.stopSession()
            voiceState = VoiceState.IDLE
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Kavya Voice Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Running Kavya AI companion in background"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kavya AI Companion")
            .setContentText("Listening and ready to assist...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun setupFloatingWindow() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission not granted, skipping floating window setup")
            return
        }

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            
            val density = resources.displayMetrics.density
            val sizePx = (96 * density).toInt()

            val composeView = ComposeView(this).apply {
                setContent {
                    MyApplicationTheme {
                        KavyaVoiceOrb(
                            state = voiceState,
                            onClick = {
                                if (isListening) {
                                    stopListening()
                                } else {
                                    startListening()
                                }
                            },
                            modifier = Modifier.padding(4.dp),
                            baseSize = 80.dp
                        )
                    }
                }
            }
            
            composeView.setViewTreeLifecycleOwner(this)
            composeView.setViewTreeSavedStateRegistryOwner(this)
            composeView.setViewTreeViewModelStoreOwner(this)
            
            floatingView = composeView

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                sizePx,
                sizePx,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            )

            params.gravity = Gravity.BOTTOM or Gravity.END
            params.x = (16 * density).toInt()
            params.y = (100 * density).toInt()

            windowManager.addView(floatingView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup floating window: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        geminiLiveClient?.stopSession()
        floatingView?.let {
            try {
                if (::windowManager.isInitialized) {
                    windowManager.removeView(it)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing floating view: ${e.message}")
            }
        }
    }
}
