package com.example.voice

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
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Single Authoritative File for Kavya AI's Microphone Input System.
 *
 * Serves as both the Android Foreground Service host and the Engine state manager.
 * Guarantees a single SpeechRecognizer instance, background execution, and wake/sleep/stop states.
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
         * Single public entry point to start microphone capture & foreground service.
         */
        fun startService(context: Context) {
            val intent = Intent(context, KavyaMicrophoneEngine::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Single public entry point to stop microphone capture & foreground service.
         */
        fun stopService(context: Context) {
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
    }

    enum class EngineState {
        IDLE,
        AWAKE,
        SLEEP_LISTENING,
        STOPPED,
        ERROR
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var isRecognizerActive = false
    private var isListeningLoopDesired = false
    private var currentSessionId = 0L

    private var backoffDelayMs = BASE_RESTART_DELAY_MS
    private var restartRunnable: Runnable? = null

    private val WAKE_PHRASES = setOf(
        "wake kavya", "wake kavia", "wake kavya ai", "utho kavya", "kavya utho",
        "wake up kavya", "kavya wake up", "wake up", "hey kavya", "hello kavya",
        "hi kavya", "ok kavya", "okay kavya", "kavya suno", "suno kavya"
    )

    private val SLEEP_PHRASES = setOf(
        "sleep kavya", "sleep kavia", "go to sleep kavya", "kavya go to sleep",
        "so jao kavya", "kavya so jao", "stop listening kavya", "kavya stop listening",
        "rest karo kavya", "sleep"
    )

    private val STOP_PHRASES = setOf(
        "stop kavya", "stop kavia", "kavya stop", "kavya chup", "chup ho jao kavya",
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
                _engineState.value = EngineState.ERROR
                return@post
            }

            isListeningLoopDesired = true
            if (_engineState.value == EngineState.STOPPED || _engineState.value == EngineState.IDLE) {
                _engineState.value = EngineState.AWAKE
            }
            backoffDelayMs = BASE_RESTART_DELAY_MS
            startListeningInternal()
        }
    }

    private fun sleepInternal() {
        mainHandler.post {
            Log.i(TAG, "Entering SLEEP_LISTENING state.")
            _engineState.value = EngineState.SLEEP_LISTENING
            _partialText.value = ""
            startListeningInternal()
        }
    }

    private fun wakeInternal() {
        mainHandler.post {
            Log.i(TAG, "Entering AWAKE state.")
            _engineState.value = EngineState.AWAKE
            _partialText.value = ""
            startListeningInternal()
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
        if (!isListeningLoopDesired || _engineState.value == EngineState.STOPPED) {
            return
        }

        if (!hasAudioPermission()) {
            _engineState.value = EngineState.ERROR
            return
        }

        cancelPendingRestart()
        destroyRecognizer()

        try {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                Log.e(TAG, "SpeechRecognizer is NOT available on this device!")
                _engineState.value = EngineState.ERROR
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
        private fun isStale(): Boolean = currentSessionId != sessionId || !isListeningLoopDesired

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

            if (isListeningLoopDesired && _engineState.value != EngineState.STOPPED) {
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

    private fun handleRecognizedText(text: String) {
        val lower = text.lowercase(Locale.ROOT).trim()

        // 1. Stop Command
        if (isMatch(lower, STOP_PHRASES)) {
            Log.i(TAG, "Stop phrase detected: \"$text\"")
            stopService(this)
            return
        }

        // 2. Sleep Command
        if (isMatch(lower, SLEEP_PHRASES)) {
            Log.i(TAG, "Sleep phrase detected: \"$text\"")
            sleepInternal()
            return
        }

        // 3. Wake Command
        if (_engineState.value == EngineState.SLEEP_LISTENING || isMatch(lower, WAKE_PHRASES)) {
            if (isMatch(lower, WAKE_PHRASES)) {
                Log.i(TAG, "Wake phrase detected: \"$text\"")
                wakeInternal()
                return
            } else {
                Log.d(TAG, "Ignoring normal speech during SLEEP_LISTENING: \"$text\"")
                return
            }
        }

        // 4. Normal AWAKE Command Delivery
        if (_engineState.value == EngineState.AWAKE) {
            Log.i(TAG, "Dispatching command to Kavya: \"$text\"")
            commandListener?.invoke(text)
        }
    }

    private fun checkPartialWakeOrStop(partial: String) {
        val lower = partial.lowercase(Locale.ROOT).trim()
        if (isMatch(lower, STOP_PHRASES)) {
            Log.i(TAG, "Partial stop detected: \"$partial\"")
            stopService(this)
        } else if (_engineState.value == EngineState.SLEEP_LISTENING && isMatch(lower, WAKE_PHRASES)) {
            Log.i(TAG, "Partial wake detected: \"$partial\"")
            wakeInternal()
        }
    }

    private fun isMatch(input: String, phrases: Set<String>): Boolean {
        if (phrases.contains(input)) return true
        return phrases.any { phrase ->
            input == phrase || input.startsWith("$phrase ") || input.endsWith(" $phrase")
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        cancelPendingRestart()
        val runnable = Runnable {
            if (isListeningLoopDesired && _engineState.value != EngineState.STOPPED) {
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
                    description = "Kavya AI Active Microphone Listening"
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
            .setContentText("Listening for commands...")
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
