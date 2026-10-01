package com.example.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.example.MainActivity
import com.example.agent.AndroidAgent
import com.example.agent.ContextEngine
import com.example.agent.KavyaAssistantPipeline
import com.example.agent.MemoryEngine
import com.example.agent.MicrophoneEngine
import com.example.agent.ScreenInspector
import com.example.agent.VerificationEngine
import com.example.ai.KavyaAI
import com.example.ai.KavyaVoiceEngine
import com.example.state.KavyaStateManager
import com.example.ui.components.VoiceState
import com.example.utils.AppPreferences
import com.example.utils.CommandRouter
import com.example.utils.PermissionsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Production-grade Continuous Background Voice Assistant Service for Kavya AI.
 *
 * Implements Sanna / Hark / Nova voice-first architecture:
 * 1. Persistent Foreground Service with FOREGROUND_SERVICE_TYPE_MICROPHONE.
 * 2. Continuous listening across any Android app, Home Screen, or screen-off state (Partial WakeLock).
 * 3. Unified assistant pipeline: Speech -> Real Text -> Room Database -> IntentGate -> Skill/AI -> TTS.
 * 4. Acoustic echo prevention: pauses microphone capture during TTS, re-arms after speech finishes.
 * 5. Full system compliance: triggers native Android green privacy indicator with real hardware access.
 * 6. Interactive foreground notification with Sleep/Wake, Mute/Unmute, and Open Kavya controls.
 */
class KavyaVoiceService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    companion object {
        const val CHANNEL_ID = "kavya_voice_service_channel"
        const val NOTIFICATION_ID = 101
        private const val TAG = "KavyaVoiceService"

        const val ACTION_WAKE = "ACTION_WAKE"
        const val ACTION_SLEEP = "ACTION_SLEEP"
        const val ACTION_TOGGLE_MIC = "ACTION_TOGGLE_MIC"

        @Volatile
        var isListening = false
            private set
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var router: CommandRouter
    private lateinit var contextEngine: ContextEngine
    private lateinit var screenInspector: ScreenInspector
    private lateinit var verificationEngine: VerificationEngine
    private lateinit var androidAgent: AndroidAgent
    private lateinit var memoryEngine: MemoryEngine
    private lateinit var voiceEngine: KavyaVoiceEngine
    private lateinit var micEngine: MicrophoneEngine
    private lateinit var assistantPipeline: KavyaAssistantPipeline

    private var wakeLock: PowerManager.WakeLock? = null

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "KavyaVoiceService creating...")

        router = CommandRouter(this)
        contextEngine = ContextEngine()
        screenInspector = ScreenInspector()
        verificationEngine = VerificationEngine(screenInspector)
        androidAgent = AndroidAgent(this, router.appResolver, contextEngine, screenInspector, verificationEngine)
        memoryEngine = MemoryEngine(this)
        voiceEngine = KavyaVoiceEngine(this, null, KavyaAI(this))
        micEngine = MicrophoneEngine.getInstance(this)

        assistantPipeline = KavyaAssistantPipeline.getInstance(
            context = this,
            voiceEngine = voiceEngine,
            microphoneEngine = micEngine,
            commandRouter = router,
            androidAgent = androidAgent,
            memoryEngine = memoryEngine
        )

        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        createNotificationChannel()

        val hasRecordAudio = PermissionsManager.hasRecordAudioPermission(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasRecordAudio) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(getNotificationTitle(), getNotificationContent()),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(getNotificationTitle(), getNotificationContent()))
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground with microphone type failed, falling back: ${e.message}")
            try {
                startForeground(NOTIFICATION_ID, buildNotification(getNotificationTitle(), getNotificationContent()))
            } catch (e2: Exception) {
                Log.e(TAG, "startForeground fallback failed: ${e2.message}")
            }
        }

        acquireWakeLock()

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        // Start continuous background listening loop if enabled
        if (AppPreferences.isMicListeningEnabled(this)) {
            startListening()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_WAKE -> {
                Log.i(TAG, "Received ACTION_WAKE from notification or app")
                AppPreferences.setKavyaState(this, "ACTIVE")
                KavyaStateManager.updateVoiceState(VoiceState.IDLE)
                updateNotification(getNotificationTitle(), "Kavya is awake and listening.")
                serviceScope.launch {
                    micEngine.pauseForTts()
                    voiceEngine.speakSuspending("Kavya is awake and listening! How can I help you?")
                    delay(350)
                    micEngine.resumeAfterTts(350L)
                    startListening()
                }
            }
            ACTION_SLEEP -> {
                Log.i(TAG, "Received ACTION_SLEEP from notification or app")
                AppPreferences.setKavyaState(this, "SLEEP")
                KavyaStateManager.updateVoiceState(VoiceState.IDLE)
                updateNotification(getNotificationTitle(), "Kavya is sleeping (Say 'Wake Kavya' to resume)")
                serviceScope.launch {
                    micEngine.pauseForTts()
                    voiceEngine.speakSuspending("Going to sleep. Say 'Wake Kavya' whenever you need me.")
                    delay(350)
                    micEngine.resumeAfterTts(350L)
                    startListening() // Remains listening in SLEEP mode for the wake phrase
                }
            }
            ACTION_TOGGLE_MIC -> {
                val currentMic = AppPreferences.isMicListeningEnabled(this)
                val newMic = !currentMic
                AppPreferences.setMicListeningEnabled(this, newMic)
                Log.i(TAG, "Received ACTION_TOGGLE_MIC -> $newMic")
                if (newMic) {
                    updateNotification(getNotificationTitle(), "Microphone active. Listening...")
                    startListening()
                } else {
                    updateNotification(getNotificationTitle(), "Microphone paused.")
                    stopListening()
                }
            }
            else -> {
                if (AppPreferences.isMicListeningEnabled(this) && !isListening) {
                    startListening()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Continuous background audio capture loop.
     * Uses native SpeechRecognizer / AudioRecord to capture speech across any Android app.
     */
    @Synchronized
    private fun startListening() {
        if (!AppPreferences.isMicListeningEnabled(this)) {
            Log.d(TAG, "Mic listening disabled by user preferences.")
            return
        }

        val hasPerm = PermissionsManager.hasRecordAudioPermission(this)
        if (!hasPerm) {
            Log.w(TAG, "Cannot startListening: RECORD_AUDIO permission missing.")
            updateNotification("Kavya AI Companion", "Microphone permission required.")
            return
        }

        if (isListening) {
            Log.d(TAG, "startListening: Already active in continuous mode.")
            return
        }

        isListening = true
        acquireWakeLock()

        val isSleep = AppPreferences.getKavyaState(this) == "SLEEP"

        micEngine.startContinuousListening(
            onListeningStarted = {
                serviceScope.launch(Dispatchers.Main) {
                    val currentSleep = AppPreferences.getKavyaState(this@KavyaVoiceService) == "SLEEP"
                    val vState = if (currentSleep) VoiceState.IDLE else VoiceState.LISTENING
                    KavyaStateManager.updateVoiceState(vState)
                    val statusMsg = if (currentSleep) {
                        "Kavya is sleeping (Say 'Wake Kavya' to resume)"
                    } else {
                        "Listening... (Say a command or 'Sleep Kavya')"
                    }
                    updateNotification(getNotificationTitle(), statusMsg)
                }
            },
            onPartialResult = { partial ->
                serviceScope.launch(Dispatchers.Main) {
                    if (partial.isNotBlank()) {
                        updateNotification(getNotificationTitle(), partial)
                    }
                }
            },
            onResult = { recognizedText ->
                if (recognizedText.isNotBlank()) {
                    Log.i(TAG, "VOICE_INPUT_RECEIVED: \"$recognizedText\"")
                    serviceScope.launch(Dispatchers.Main) {
                        updateNotification(getNotificationTitle(), "Executing: $recognizedText")
                    }
                    assistantPipeline.processUtterance(
                        rawText = recognizedText,
                        autoSpeak = true,
                        onProgress = { ann ->
                            updateNotification(getNotificationTitle(), ann)
                        },
                        onTurnFinished = { reply ->
                            val currentSleep = AppPreferences.getKavyaState(this@KavyaVoiceService) == "SLEEP"
                            val statusMsg = if (currentSleep) {
                                "Kavya is sleeping (Say 'Wake Kavya' to resume)"
                            } else {
                                "Listening and ready to assist..."
                            }
                            updateNotification(getNotificationTitle(), statusMsg)
                        }
                    )
                }
            },
            onError = { err ->
                Log.w(TAG, "Continuous listening notice: $err")
                serviceScope.launch(Dispatchers.Main) {
                    updateNotification(getNotificationTitle(), "Listening standby...")
                }
            }
        )
    }

    private fun stopListening() {
        if (isListening) {
            isListening = false
            micEngine.setContinuousListening(false)
            micEngine.stopListening()
            KavyaStateManager.updateVoiceState(VoiceState.IDLE)
            releaseWakeLock()
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Kavya::VoiceServiceWakeLock")?.apply {
                    setReferenceCounted(false)
                    acquire(60 * 60 * 1000L) // 60 min safety max
                }
                Log.d(TAG, "WakeLock acquired for background voice service")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                wakeLock = null
                Log.d(TAG, "WakeLock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release WakeLock: ${e.message}")
        }
    }

    private fun getNotificationTitle(): String = "Kavya AI Companion"

    private fun getNotificationContent(): String {
        val isSleep = AppPreferences.getKavyaState(this) == "SLEEP"
        val isMicOn = AppPreferences.isMicListeningEnabled(this)
        return when {
            !isMicOn -> "Microphone paused."
            isSleep -> "Kavya is sleeping (Say 'Wake Kavya' to resume)"
            else -> "Listening and ready to assist..."
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
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPending = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val isSleep = AppPreferences.getKavyaState(this) == "SLEEP"
        val sleepWakeIntent = Intent(this, KavyaVoiceService::class.java).apply {
            action = if (isSleep) ACTION_WAKE else ACTION_SLEEP
        }
        val sleepWakePending = PendingIntent.getService(
            this,
            1,
            sleepWakeIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val sleepWakeLabel = if (isSleep) "Wake Kavya" else "Sleep Kavya"

        val isMicOn = AppPreferences.isMicListeningEnabled(this)
        val micIntent = Intent(this, KavyaVoiceService::class.java).apply {
            action = ACTION_TOGGLE_MIC
        }
        val micPending = PendingIntent.getService(
            this,
            2,
            micIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val micLabel = if (isMicOn) "Mute Mic" else "Unmute Mic"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppPending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_lock_power_off, sleepWakeLabel, sleepWakePending)
            .addAction(android.R.drawable.ic_btn_speak_now, micLabel, micPending)
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(title, content))
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "KavyaVoiceService onDestroy")
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        stopListening()
        voiceEngine.stop()
        releaseWakeLock()
        serviceJob.cancel()
    }
}
