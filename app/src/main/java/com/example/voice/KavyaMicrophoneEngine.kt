package com.example.voice

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.utils.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Authoritative Single-File Jarvis-Style Microphone Engine & Foreground Service Host.
 *
 * Guarantees Single Microphone Ownership, Persistent Foreground Execution across apps,
 * Task-Removed Recovery (when enabled), State Machine (WAKE_LISTENING, COMMAND_LISTENING,
 * PROCESSING, SPEAKING, SLEEP, STOPPED), Variation-Tolerant Wake Word Matching,
 * Partial Recognition Interrupts, and Speech-Output Collision Protection.
 */
class KavyaMicrophoneEngine : Service() {

    companion object {
        private const val TAG = "KavyaMicEngine"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "kavya_mic_channel"
        private const val CHANNEL_NAME = "Kavya Voice Input"

        private const val BASE_RESTART_DELAY_MS = 350L
        private const val MAX_BACKOFF_DELAY_MS = 5000L

        @Volatile
        private var instance: KavyaMicrophoneEngine? = null

        fun getInstance(): KavyaMicrophoneEngine? = instance

        private val _engineState = MutableStateFlow(EngineState.IDLE)
        val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

        private val _partialText = MutableStateFlow("")
        val partialText: StateFlow<String> = _partialText.asStateFlow()

        private val _amplitude = MutableStateFlow(0f)
        val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

        private var commandListener: ((String) -> Unit)? = null

        fun setCommandListener(listener: (String) -> Unit) {
            commandListener = listener
        }

        /**
         * Starts the single microphone foreground service and updates user preference.
         */
        fun startService(context: Context) {
            AppPreferences.setMicEnabled(context, true)
            val intent = Intent(context, KavyaMicrophoneEngine::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stops the single microphone foreground service and updates user preference.
         */
        fun stopService(context: Context) {
            AppPreferences.setMicEnabled(context, false)
            val intent = Intent(context, KavyaMicrophoneEngine::class.java)
            context.stopService(intent)
            instance?.stopListeningLoop()
        }

        fun sleep(context: Context) {
            instance?.sleepInternal()
        }

        fun wake(context: Context) {
            instance?.wakeInternal()
        }

        fun onKavyaSpeakingChanged(isSpeaking: Boolean) {
            instance?.handleSpeakingStateChanged(isSpeaking)
        }
    }

    enum class EngineState {
        STOPPED,
        IDLE,
        WAKE_LISTENING,
        COMMAND_LISTENING,
        PROCESSING,
        SPEAKING,
        SLEEP
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var isRecognizerActive = false
    private var isListeningLoopDesired = false
    private var currentSessionId = 0L

    private var backoffDelayMs = BASE_RESTART_DELAY_MS
    private var restartRunnable: Runnable? = null

    private val WAKE_WORDS = setOf(
        "hey kavya", "hi kavya", "hello kavya", "wake kavya", "wake up kavya",
        "kavya wake up", "utho kavya", "kavya utho", "ok kavya", "okay kavya",
        "kavya suno", "suno kavya", "hey kavia", "kavya", "kavia", "cavia"
    )

    private val SLEEP_WORDS = setOf(
        "sleep kavya", "kavya sleep", "go to sleep kavya", "kavya go to sleep",
        "sleep kavia", "so jao kavya", "kavya so jao"
    )

    private val STOP_WORDS = setOf(
        "stop kavya", "kavya stop", "stop kavia", "kavya chup", "chup ho jao kavya",
        "ruko kavya", "kavya ruko"
    )

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "KavyaMicrophoneEngine service created.")
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "KavyaMicrophoneEngine onStartCommand: promoting to foreground...")
        createNotificationChannel()
        val notification = buildForegroundNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startListeningLoop()

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "Kavya task removed from Recents.")
        if (AppPreferences.isMicEnabled(this)) {
            Log.i(TAG, "Mic is user-enabled. Scheduling recovery restart...")
            val restartIntent = Intent(applicationContext, KavyaMicrophoneEngine::class.java)
            val pendingIntent = PendingIntent.getService(
                this,
                1,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1000,
                pendingIntent
            )
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "KavyaMicrophoneEngine service destroyed.")
        stopListeningLoop()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startListeningLoop() {
        mainHandler.post {
            if (!hasAudioPermission()) {
                Log.w(TAG, "RECORD_AUDIO permission missing.")
                _engineState.value = EngineState.IDLE
                return@post
            }

            isListeningLoopDesired = true
            if (_engineState.value == EngineState.STOPPED || _engineState.value == EngineState.IDLE) {
                _engineState.value = EngineState.WAKE_LISTENING
            }
            backoffDelayMs = BASE_RESTART_DELAY_MS
            startListeningInternal()
        }
    }

    private fun sleepInternal() {
        mainHandler.post {
            Log.i(TAG, "Entering SLEEP state.")
            _engineState.value = EngineState.SLEEP
            _partialText.value = ""
            startListeningInternal()
        }
    }

    private fun wakeInternal() {
        mainHandler.post {
            Log.i(TAG, "Entering COMMAND_LISTENING state.")
            _engineState.value = EngineState.COMMAND_LISTENING
            _partialText.value = ""
            startListeningInternal()
        }
    }

    private fun handleSpeakingStateChanged(isSpeaking: Boolean) {
        mainHandler.post {
            if (isSpeaking) {
                Log.i(TAG, "Kavya is SPEAKING. Pausing microphone capture to prevent speech self-collision...")
                _engineState.value = EngineState.SPEAKING
                cancelPendingRestart()
                destroyRecognizer()
            } else {
                Log.i(TAG, "Kavya finished SPEAKING. Returning microphone to WAKE_LISTENING...")
                if (isListeningLoopDesired && _engineState.value != EngineState.STOPPED) {
                    _engineState.value = EngineState.WAKE_LISTENING
                    scheduleRestart(300L)
                }
            }
        }
    }

    private fun stopListeningLoop() {
        mainHandler.post {
            Log.i(TAG, "Stopping microphone engine.")
            isListeningLoopDesired = false
            cancelPendingRestart()
            destroyRecognizer()
            _engineState.value = EngineState.STOPPED
            _partialText.value = ""
            _amplitude.value = 0f
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startListeningInternal() {
        if (!isListeningLoopDesired || _engineState.value == EngineState.STOPPED || _engineState.value == EngineState.SPEAKING) {
            return
        }

        if (!hasAudioPermission()) {
            _engineState.value = EngineState.IDLE
            return
        }

        cancelPendingRestart()
        destroyRecognizer()

        try {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                Log.e(TAG, "SpeechRecognizer is NOT available on this device!")
                _engineState.value = EngineState.IDLE
                scheduleRestartWithBackoff()
                return
            }

            val session = System.currentTimeMillis()
            this.currentSessionId = session

            val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            this.speechRecognizer = recognizer

            recognizer.setRecognitionListener(createRecognitionListener(session))

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES, arrayListOf("en-IN", "hi-IN", "en-US"))
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }

            recognizer.startListening(intent)
            isRecognizerActive = true
            Log.d(TAG, "SpeechRecognizer started in state ${_engineState.value} (session=$session)")
        } catch (e: Throwable) {
            Log.e(TAG, "Error starting SpeechRecognizer: ${e.message}", e)
            isRecognizerActive = false
            scheduleRestartWithBackoff()
        }
    }

    private fun createRecognitionListener(sessionId: Long) = object : RecognitionListener {
        private fun isStale(): Boolean = currentSessionId != sessionId || !isListeningLoopDesired || _engineState.value == EngineState.SPEAKING

        override fun onReadyForSpeech(params: Bundle?) {
            if (isStale()) return
            Log.d(TAG, "SpeechRecognizer ready for speech.")
            backoffDelayMs = BASE_RESTART_DELAY_MS
        }

        override fun onBeginningOfSpeech() {
            if (isStale()) return
            Log.d(TAG, "SpeechRecognizer beginning of speech.")
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (isStale()) return
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            _amplitude.value = normalized
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            if (isStale()) return
            Log.d(TAG, "SpeechRecognizer end of speech.")
            _amplitude.value = 0f
        }

        override fun onError(error: Int) {
            if (isStale()) return
            val errorMsg = getErrorMessage(error)
            Log.w(TAG, "SpeechRecognizer error: $errorMsg ($error)")
            _amplitude.value = 0f
            isRecognizerActive = false

            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    scheduleRestart(BASE_RESTART_DELAY_MS)
                }
                else -> {
                    scheduleRestartWithBackoff()
                }
            }
        }

        override fun onResults(results: Bundle?) {
            if (isStale()) return
            _amplitude.value = 0f
            isRecognizerActive = false

            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val bestResult = matches?.firstOrNull()?.trim() ?: ""
            Log.i(TAG, "SpeechRecognizer Final Result: \"$bestResult\"")

            _partialText.value = ""

            if (bestResult.isNotBlank()) {
                handleRecognizedText(bestResult)
            }

            if (isListeningLoopDesired && _engineState.value != EngineState.STOPPED && _engineState.value != EngineState.SPEAKING) {
                scheduleRestart(BASE_RESTART_DELAY_MS)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (isStale()) return
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = matches?.firstOrNull()?.trim() ?: ""
            if (partial.isNotBlank()) {
                _partialText.value = partial
                checkPartialWakeOrStop(partial)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun handleRecognizedText(rawText: String) {
        val lower = rawText.lowercase(Locale.ROOT).trim()

        // 1. Stop Command
        if (containsMatch(lower, STOP_WORDS)) {
            Log.i(TAG, "Stop phrase detected: \"$rawText\"")
            stopService(this)
            return
        }

        // 2. Sleep Command
        if (containsMatch(lower, SLEEP_WORDS)) {
            Log.i(TAG, "Sleep phrase detected: \"$rawText\"")
            sleepInternal()
            return
        }

        // 3. Wake Phase / Mode Handling
        val wakeMatch = findWakeMatch(lower)

        if (_engineState.value == EngineState.SLEEP) {
            if (wakeMatch != null) {
                Log.i(TAG, "Wake phrase detected while sleeping: \"$rawText\"")
                val cleanCommand = extractCommandAfterWake(lower, rawText, wakeMatch)
                _engineState.value = EngineState.COMMAND_LISTENING
                if (cleanCommand.isNotBlank()) {
                    dispatchCommand(cleanCommand)
                }
            } else {
                Log.d(TAG, "Ignoring speech during SLEEP: \"$rawText\"")
            }
            return
        }

        if (_engineState.value == EngineState.WAKE_LISTENING) {
            if (wakeMatch != null) {
                Log.i(TAG, "Wake phrase detected: \"$rawText\"")
                val cleanCommand = extractCommandAfterWake(lower, rawText, wakeMatch)
                if (cleanCommand.isNotBlank()) {
                    _engineState.value = EngineState.PROCESSING
                    dispatchCommand(cleanCommand)
                } else {
                    _engineState.value = EngineState.COMMAND_LISTENING
                }
            } else {
                Log.i(TAG, "Delivering prompt in active mode: \"$rawText\"")
                _engineState.value = EngineState.PROCESSING
                dispatchCommand(rawText)
            }
            return
        }

        if (_engineState.value == EngineState.COMMAND_LISTENING) {
            val cleanCommand = if (wakeMatch != null) extractCommandAfterWake(lower, rawText, wakeMatch) else rawText
            val targetCommand = if (cleanCommand.isNotBlank()) cleanCommand else rawText
            Log.i(TAG, "Dispatching command to Kavya AI: \"$targetCommand\"")
            _engineState.value = EngineState.PROCESSING
            dispatchCommand(targetCommand)
        }
    }

    private fun checkPartialWakeOrStop(partial: String) {
        val lower = partial.lowercase(Locale.ROOT).trim()
        if (containsMatch(lower, STOP_WORDS)) {
            Log.i(TAG, "Partial stop detected: \"$partial\"")
            stopService(this)
        } else if (_engineState.value == EngineState.SLEEP && findWakeMatch(lower) != null) {
            Log.i(TAG, "Partial wake detected: \"$partial\"")
            wakeInternal()
        }
    }

    private fun dispatchCommand(commandText: String) {
        commandListener?.invoke(commandText)
    }

    private fun findWakeMatch(input: String): String? {
        for (wakeWord in WAKE_WORDS) {
            if (input == wakeWord || input.startsWith("$wakeWord ") || input.contains(" $wakeWord ") || input.endsWith(" $wakeWord")) {
                return wakeWord
            }
        }
        return null
    }

    private fun extractCommandAfterWake(lower: String, originalText: String, wakeMatch: String): String {
        val index = lower.indexOf(wakeMatch)
        if (index != -1) {
            val after = originalText.substring(index + wakeMatch.length).trim()
            return after.replace(Regex("^[.,!?;:]+"), "").trim()
        }
        return originalText.trim()
    }

    private fun containsMatch(input: String, phrases: Set<String>): Boolean {
        if (phrases.contains(input)) return true
        return phrases.any { phrase ->
            input == phrase || input.startsWith("$phrase ") || input.endsWith(" $phrase")
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        cancelPendingRestart()
        val runnable = Runnable {
            if (isListeningLoopDesired && _engineState.value != EngineState.STOPPED && _engineState.value != EngineState.SPEAKING) {
                startListeningInternal()
            }
        }
        this.restartRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun scheduleRestartWithBackoff() {
        scheduleRestart(backoffDelayMs)
        backoffDelayMs = (backoffDelayMs * 1.5f).toLong().coerceAtMost(MAX_BACKOFF_DELAY_MS)
    }

    private fun cancelPendingRestart() {
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        restartRunnable = null
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.apply {
                stopListening()
                cancel()
                destroy()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error destroying SpeechRecognizer: ${e.message}")
        } finally {
            speechRecognizer = null
            isRecognizerActive = false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Kavya AI Active Voice Listening"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildForegroundNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kavya AI Voice Active")
            .setContentText("Listening for 'Hey Kavya'...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun getErrorMessage(errorCode: Int): String = when (errorCode) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Client side error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "No match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RecognitionService busy"
        SpeechRecognizer.ERROR_SERVER -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
        else -> "Unknown error ($errorCode)"
    }
}
