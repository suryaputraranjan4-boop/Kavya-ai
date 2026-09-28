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
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.example.agent.MemoryEngine
import com.example.agent.MicrophoneEngine
import com.example.agent.MicrophoneState
import com.example.agent.ScreenInspector
import com.example.agent.SleepWakeDetector
import com.example.agent.TaskPlanner
import com.example.agent.VerificationEngine
import com.example.ai.GeminiLiveClient
import com.example.ai.KavyaAI
import com.example.ai.KavyaVoiceEngine
import com.example.api.ApiSystem
import com.example.state.KavyaStateManager
import com.example.state.TaskState
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
import java.util.Locale

/**
 * Production-grade background voice assistant service for Kavya AI.
 *
 * Responsibilities:
 * 1. Persistent Foreground Service with FOREGROUND_SERVICE_TYPE_MICROPHONE.
 * 2. Continuously listens for voice commands while other apps (YouTube, WhatsApp, Instagram, Spotify, etc.) are in the foreground.
 * 3. Understands compound user intents and executes real multi-step tasks via [AndroidAgent] and [KavyaAccessibilityService].
 * 4. Explicit two-state Sleep / Wake engine (ACTIVE vs SLEEPING) to conserve battery and avoid false executions.
 * 5. Full system compliance: visible Android microphone indicator, interactive foreground notification.
 * 6. Prevents acoustic feedback loops by pausing microphone during assistant speech playback.
 */
class KavyaVoiceService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var router: CommandRouter
    private lateinit var contextEngine: ContextEngine
    private lateinit var screenInspector: ScreenInspector
    private lateinit var verificationEngine: VerificationEngine
    private lateinit var androidAgent: AndroidAgent
    private lateinit var taskPlanner: TaskPlanner
    private lateinit var memoryEngine: MemoryEngine
    private lateinit var apiSystem: ApiSystem
    private lateinit var voiceEngine: KavyaVoiceEngine

    private var geminiLiveClient: GeminiLiveClient? = null
    private var voiceState by mutableStateOf(VoiceState.IDLE)

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store

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

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "KavyaVoiceService creating...")

        router = CommandRouter(this)
        contextEngine = ContextEngine()
        screenInspector = ScreenInspector()
        verificationEngine = VerificationEngine(screenInspector)
        androidAgent = AndroidAgent(this, router.appResolver, contextEngine, screenInspector, verificationEngine)
        taskPlanner = TaskPlanner(router.appResolver)
        memoryEngine = MemoryEngine(this)
        apiSystem = ApiSystem()
        voiceEngine = KavyaVoiceEngine(this, null, KavyaAI(this))

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

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        // Start continuous background listening loop
        if (AppPreferences.isMicListeningEnabled(this)) {
            startListening()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_WAKE -> {
                Log.i(TAG, "Received ACTION_WAKE")
                AppPreferences.setKavyaState(this, "ACTIVE")
                voiceState = VoiceState.IDLE
                KavyaStateManager.updateVoiceState(VoiceState.IDLE)
                updateNotification(getNotificationTitle(), "Kavya is awake and listening.")
                serviceScope.launch {
                    voiceEngine.speakSuspending("Kavya is awake and listening! How can I help you?")
                    delay(300)
                    startListening()
                }
            }
            ACTION_SLEEP -> {
                Log.i(TAG, "Received ACTION_SLEEP")
                AppPreferences.setKavyaState(this, "SLEEP")
                voiceState = VoiceState.IDLE
                KavyaStateManager.updateVoiceState(VoiceState.IDLE)
                updateNotification(getNotificationTitle(), "Kavya is sleeping (Say 'Wake Kavya' to resume)")
                serviceScope.launch {
                    voiceEngine.speakSuspending("Kavya is now sleeping. Say 'Wake Kavya' whenever you need me.")
                    delay(300)
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
     * Keeps listening across apps until user sleeps or mutes Kavya.
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
            Log.d(TAG, "startListening: Already listening.")
            return
        }

        serviceScope.launch {
            // Guard: wait if Kavya TTS is currently speaking to prevent acoustic feedback loop
            var waitCount = 0
            while (voiceEngine.isSpeaking && waitCount < 50) {
                delay(200)
                waitCount++
            }
            delay(350) // Acoustic dissipating delay

            if (isListening) return@launch
            isListening = true

            val micEngine = MicrophoneEngine.getInstance(this@KavyaVoiceService)
            val isSleep = AppPreferences.getKavyaState(this@KavyaVoiceService) == "SLEEP"

            if (micEngine.micState.value == MicrophoneState.IDLE) {
                micEngine.startRecording(
                    onRecordingStarted = {
                        voiceState = if (isSleep) VoiceState.IDLE else VoiceState.LISTENING
                        KavyaStateManager.updateVoiceState(if (isSleep) VoiceState.IDLE else VoiceState.LISTENING)
                        if (!isSleep) {
                            updateNotification("Kavya AI Companion", "Listening... (Say a command or 'Sleep Kavya')")
                        } else {
                            updateNotification("Kavya AI Companion", "Kavya is sleeping (Say 'Wake Kavya' to resume)")
                        }
                    },
                    onAudioCaptured = { pcmBytes, sampleRate ->
                        handleCapturedAudio(pcmBytes, sampleRate)
                    },
                    onError = { err ->
                        Log.w(TAG, "Service microphone error: $err")
                        isListening = false
                        voiceState = VoiceState.ERROR
                        KavyaStateManager.updateVoiceState(VoiceState.ERROR)
                        updateNotification("Kavya AI Companion", "Microphone error: $err")

                        // Bounded auto-recover after 2.5s cooldown
                        serviceScope.launch {
                            delay(2500)
                            if (AppPreferences.isMicListeningEnabled(this@KavyaVoiceService)) {
                                startListening()
                            }
                        }
                    }
                )
            } else {
                isListening = false
            }
        }
    }

    private fun stopListening() {
        if (isListening) {
            isListening = false
            MicrophoneEngine.getInstance(this).stopRecording()
            voiceState = VoiceState.IDLE
            KavyaStateManager.updateVoiceState(VoiceState.IDLE)
        }
    }

    /**
     * Core Voice Command Engine:
     * Transcribes audio, checks Sleep/Wake state, parses multi-step tasks, and executes via real Android mechanisms.
     */
    private fun handleCapturedAudio(pcmBytes: ByteArray, sampleRate: Int) {
        val isSleep = AppPreferences.getKavyaState(this) == "SLEEP"
        if (!isSleep) {
            voiceState = VoiceState.THINKING
            KavyaStateManager.updateVoiceState(VoiceState.THINKING)
            updateNotification("Kavya AI Companion", "Understanding voice command...")
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                val ai = KavyaAI(this@KavyaVoiceService)
                val text = ai.transcribeAudio(pcmBytes, sampleRate)
                Log.d(TAG, "Audio transcribed: \"$text\" (State=$isSleep)")

                if (text.isBlank()) {
                    isListening = false
                    voiceState = VoiceState.IDLE
                    KavyaStateManager.updateVoiceState(VoiceState.IDLE)
                    delay(300)
                    startListening()
                    return@launch
                }

                val currentSleepState = AppPreferences.getKavyaState(this@KavyaVoiceService) == "SLEEP"

                // ==========================================
                // 1. SLEEPING STATE LOGIC
                // ==========================================
                if (currentSleepState) {
                    if (SleepWakeDetector.isWakeCommand(text)) {
                        Log.i(TAG, "Wake command recognized: \"$text\"")
                        AppPreferences.setKavyaState(this@KavyaVoiceService, "ACTIVE")
                        voiceState = VoiceState.IDLE
                        KavyaStateManager.updateVoiceState(VoiceState.IDLE)
                        updateNotification("Kavya AI Companion", "Listening and ready to assist...")
                        voiceEngine.speakSuspending("Kavya is awake and listening! What can I do for you?")
                        delay(400)
                        isListening = false
                        startListening()
                    } else {
                        // Sleeping: Ignore regular chatter/noise silently
                        Log.d(TAG, "Sleeping: Ignored non-wake phrase: \"$text\"")
                        isListening = false
                        delay(250)
                        startListening()
                    }
                    return@launch
                }

                // ==========================================
                // 2. ACTIVE STATE: SLEEP COMMAND CHECK
                // ==========================================
                if (SleepWakeDetector.isSleepCommand(text)) {
                    Log.i(TAG, "Sleep command recognized: \"$text\"")
                    AppPreferences.setKavyaState(this@KavyaVoiceService, "SLEEP")
                    voiceState = VoiceState.IDLE
                    KavyaStateManager.updateVoiceState(VoiceState.IDLE)
                    updateNotification("Kavya AI Companion", "Kavya is sleeping (Say 'Wake Kavya' to resume)")
                    voiceEngine.speakSuspending("Going to sleep. Say 'Wake Kavya' whenever you need me.")
                    delay(400)
                    isListening = false
                    startListening() // Now listens in SLEEP mode
                    return@launch
                }

                // ==========================================
                // 3. EMERGENCY STOP CHECK
                // ==========================================
                val lower = text.lowercase(Locale.ROOT)
                if (lower == "stop" || lower == "ruko" || lower == "cancel" || lower == "bas" ||
                    lower == "stop kavya" || lower == "kavya stop") {
                    Log.i(TAG, "Emergency stop recognized: \"$text\"")
                    androidAgent.stopExecution("Voice stop command: $text")
                    voiceEngine.speakSuspending("Action stopped.")
                    isListening = false
                    voiceState = VoiceState.IDLE
                    KavyaStateManager.updateVoiceState(VoiceState.IDLE)
                    delay(400)
                    startListening()
                    return@launch
                }

                // ==========================================
                // 4. ACTIVE COMMAND EXECUTION
                // ==========================================
                updateNotification("Kavya AI Companion", "Executing: $text")
                KavyaStateManager.updateTaskState(TaskState.EXECUTING, text)

                // Check for multi-step structured task plan (e.g. "YouTube खोलो, इस गाने को search करो और video खोलो")
                val plan = taskPlanner.createPlan(text, contextEngine)
                if (plan != null && plan.steps.isNotEmpty()) {
                    Log.i(TAG, "Executing planned multi-step task (${plan.steps.size} steps): \"$text\"")
                    val outcome = androidAgent.executeTaskPlan(
                        plan,
                        onSpeakProgress = { spokenAnnouncement ->
                            voiceEngine.speak(spokenAnnouncement)
                        }
                    )
                    if (outcome.finalSpokenMessage.isNotBlank()) {
                        voiceEngine.speakSuspending(outcome.finalSpokenMessage)
                    }
                } else if (router.isDirectDeviceCommand(text)) {
                    Log.i(TAG, "Executing direct device action: \"$text\"")
                    val directResult = router.executeDirectUserCommand(
                        text,
                        onBeforeExecute = { spokenAnnouncement ->
                            voiceEngine.speak(spokenAnnouncement)
                        }
                    )
                    if (directResult.output.isNotBlank()) {
                        voiceEngine.speakSuspending(directResult.output)
                    }
                } else {
                    Log.i(TAG, "Executing conversational AI request: \"$text\"")
                    try {
                        val relevant = memoryEngine.retrieveRelevantMemories(text, screenInspector.getCurrentForegroundPackage())
                        val memoryBlock = if (relevant.isNotEmpty()) relevant.joinToString("\n") { "- ${it.key}: ${it.content}" } else ""
                        val aiResponse = ai.chat(
                            prompt = text,
                            history = emptyList(),
                            screenContext = screenInspector.getCurrentForegroundPackage(),
                            memoryContext = memoryBlock,
                            isProactiveMode = false
                        )
                        val clean = voiceEngine.cleanText(aiResponse)
                        if (clean.isNotBlank()) {
                            voiceEngine.speakSuspending(clean)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Conversational AI failed: ${e.message}")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error handling captured audio: ${e.message}", e)
            } finally {
                voiceState = VoiceState.IDLE
                KavyaStateManager.updateVoiceState(VoiceState.IDLE)
                KavyaStateManager.updateTaskState(TaskState.IDLE)
                isListening = false

                val isSleepNow = AppPreferences.getKavyaState(this@KavyaVoiceService) == "SLEEP"
                if (!isSleepNow) {
                    updateNotification("Kavya AI Companion", "Listening and ready to assist...")
                } else {
                    updateNotification("Kavya AI Companion", "Kavya is sleeping (Say 'Wake Kavya' to resume)")
                }

                // Resume continuous listening for the next voice command
                delay(400)
                if (AppPreferences.isMicListeningEnabled(this@KavyaVoiceService)) {
                    startListening()
                }
            }
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
        MicrophoneEngine.getInstance(this).cancelRecording()
        geminiLiveClient?.stopSession()
        voiceEngine.stop()
        serviceJob.cancel()
    }
}
