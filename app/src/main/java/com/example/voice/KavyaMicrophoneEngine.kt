package com.example.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import com.example.utils.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Android port of the Open.Jarvis wake-listener model:
 * continuous recognition sessions, wake-word gating, active-session handling,
 * retry/recovery and microphone state reporting.
 *
 * Open.Jarvis is MIT licensed (Copyright (c) 2026 dmrr35).
 * This Android implementation is independently written for Kavya.
 */
class KavyaMicrophoneEngine : Service() {

    companion object {
        private const val TAG = "KavyaMicEngine"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "kavya_mic_channel"

        private const val INITIAL_RETRY_MS = 350L
        private const val NO_MATCH_RETRY_MS = 450L
        private const val BUSY_RETRY_MS = 1000L
        private const val MAX_RETRY_MS = 5000L

        private val stateFlow = MutableStateFlow(EngineState.MIC_OFF)
        private val partialFlow = MutableStateFlow("")
        private val amplitudeFlow = MutableStateFlow(0f)

        val engineState: StateFlow<EngineState> = stateFlow.asStateFlow()
        val partialText: StateFlow<String> = partialFlow.asStateFlow()
        val amplitude: StateFlow<Float> = amplitudeFlow.asStateFlow()

        @Volatile private var instance: KavyaMicrophoneEngine? = null
        private var commandListener: ((String) -> Unit)? = null

        fun setCommandListener(listener: (String) -> Unit) {
            commandListener = listener
        }

        fun startService(context: Context) {
            AppPreferences.setMicEnabled(context, true)
            AppPreferences.setKavyaState(context, "ACTIVE")
            val intent = Intent(context, KavyaMicrophoneEngine::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            AppPreferences.setMicEnabled(context, false)
            AppPreferences.setKavyaState(context, "OFF")
            instance?.stopEngine()
            context.stopService(Intent(context, KavyaMicrophoneEngine::class.java))
        }

        fun sleep(context: Context) {
            instance?.setSleeping()
        }

        fun wake(context: Context) {
            instance?.setAwake()
        }

        fun onKavyaSpeakingChanged(isSpeaking: Boolean) {
            instance?.setSpeaking(isSpeaking)
        }

        fun getInstance(): KavyaMicrophoneEngine? = instance
    }

    enum class EngineState {
        MIC_OFF,
        MIC_STARTING,
        MIC_ACTIVE,
        MIC_SLEEPING,
        MIC_ERROR
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var sessionRunning = false
    private var desired = false
    private var sleeping = true
    private var assistantSpeaking = false
    private var retryMs = INITIAL_RETRY_MS
    private var retryTask: Runnable? = null

    private val wakeWords = setOf(
        "hey kavya", "hi kavya", "hello kavya",
        "wake kavya", "wake up kavya", "kavya wake up",
        "utho kavya", "kavya utho", "ok kavya", "okay kavya",
        "kavya suno", "suno kavya", "hey kavia",
        "kavya", "kavia", "cavia"
    )

    private val sleepWords = setOf(
        "sleep kavya", "kavya sleep", "go to sleep kavya",
        "kavya go to sleep", "sleep kavia",
        "so jao kavya", "kavya so jao"
    )

    private val stopWords = setOf(
        "stop kavya", "kavya stop", "stop kavia",
        "kavya chup", "chup ho jao kavya",
        "ruko kavya", "kavya ruko"
    )

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Restore the last explicit voice state if Android recreates the service.
        sleeping = AppPreferences.getKavyaState(this).equals("SLEEP", ignoreCase = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            createChannel()
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            startEngine()
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to promote microphone service", t)
            stateFlow.value = EngineState.MIC_ERROR
            stopSelf()
        }
        return START_STICKY
    }

    private fun startEngine() {
        handler.post {
            if (!hasAudioPermission()) {
                stateFlow.value = EngineState.MIC_ERROR
                Log.e(TAG, "RECORD_AUDIO permission is missing")
                return@post
            }

            desired = true
            retryMs = INITIAL_RETRY_MS
            if (stateFlow.value == EngineState.MIC_OFF ||
                stateFlow.value == EngineState.MIC_ERROR) {
                // Starting from the UI is an explicit start/wake action.
                sleeping = false
                AppPreferences.setKavyaState(this@KavyaMicrophoneEngine, "ACTIVE")
            }
            stateFlow.value = if (sleeping) EngineState.MIC_SLEEPING else EngineState.MIC_STARTING
            startRecognitionSession()
        }
    }

    private fun startRecognitionSession() {
        if (!desired || assistantSpeaking || !hasAudioPermission()) return
        if (sessionRunning) return

        cancelRetry()

        try {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                stateFlow.value = EngineState.MIC_ERROR
                scheduleRetry()
                return
            }

            recognizer?.destroy()
            recognizer = createBestRecognizer().also {
                it.setRecognitionListener(listener)
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)

                // Open.Jarvis-style local-first preference where Android supports it.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
            }

            recognizer?.startListening(intent)
            sessionRunning = true
            stateFlow.value = if (sleeping) EngineState.MIC_SLEEPING else EngineState.MIC_ACTIVE
            Log.i(TAG, "Microphone recognition session started")
        } catch (t: Throwable) {
            Log.e(TAG, "Recognition start failed", t)
            sessionRunning = false
            scheduleRetry()
        }
    }

    private fun createBestRecognizer(): SpeechRecognizer {
        return if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            retryMs = INITIAL_RETRY_MS
            if (!sleeping) stateFlow.value = EngineState.MIC_ACTIVE
        }

        override fun onBeginningOfSpeech() {
            if (!sleeping) stateFlow.value = EngineState.MIC_ACTIVE
        }

        override fun onRmsChanged(rmsdB: Float) {
            amplitudeFlow.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            sessionRunning = false
        }

        override fun onError(error: Int) {
            sessionRunning = false
            partialFlow.value = ""

            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> schedule(NO_MATCH_RETRY_MS)

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> schedule(BUSY_RETRY_MS)

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    stateFlow.value = EngineState.MIC_ERROR
                    Log.e(TAG, "Microphone permission was revoked")
                }

                else -> scheduleRetry()
            }
        }

        override fun onResults(results: Bundle?) {
            sessionRunning = false
            partialFlow.value = ""

            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()

            if (text.isNotBlank()) handleRecognizedText(text)

            if (desired && !assistantSpeaking) schedule(NO_MATCH_RETRY_MS)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()

            if (text.isBlank()) return
            partialFlow.value = text
            handlePartial(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun handlePartial(text: String) {
        val normalized = normalize(text)

        if (containsPhrase(normalized, stopWords)) {
            stopService(this)
            return
        }

        // Do not restart recognition from a partial wake-word match.
        // Waiting for final results preserves commands such as:
        // "Hey Kavya, open YouTube".
    }

    private fun handleRecognizedText(text: String) {
        val normalized = normalize(text)

        if (containsPhrase(normalized, stopWords)) {
            stopService(this)
            return
        }

        if (containsPhrase(normalized, sleepWords)) {
            setSleeping()
            return
        }

        val wake = findWake(normalized)

        if (sleeping) {
            if (wake == null) return
            sleeping = false
            AppPreferences.setKavyaState(this, "ACTIVE")
            stateFlow.value = EngineState.MIC_ACTIVE
            val command = commandAfterWake(text, wake)
            if (command.isNotBlank()) dispatch(command)
            return
        }

        val command = if (wake != null) commandAfterWake(text, wake) else text.trim()
        if (command.isNotBlank()) dispatch(command)
    }

    private fun dispatch(command: String) {
        Log.i(TAG, "Dispatching voice command: $command")
        commandListener?.invoke(command)
    }

    private fun setSleeping() {
        handler.post {
            sleeping = true
            AppPreferences.setKavyaState(this, "SLEEP")
            partialFlow.value = ""
            stateFlow.value = EngineState.MIC_SLEEPING
            restartRecognition()
        }
    }

    private fun setAwake() {
        handler.post {
            sleeping = false
            AppPreferences.setKavyaState(this, "ACTIVE")
            partialFlow.value = ""
            stateFlow.value = EngineState.MIC_ACTIVE
            restartRecognition()
        }
    }

    private fun setSpeaking(isSpeaking: Boolean) {
        handler.post {
            assistantSpeaking = isSpeaking
            if (isSpeaking) {
                cancelRetry()
                cancelRecognizer()
                amplitudeFlow.value = 0f
            } else if (desired) {
                schedule(250L)
            }
        }
    }

    private fun restartRecognition() {
        cancelRecognizer()
        if (desired && !assistantSpeaking) schedule(120L)
    }

    private fun stopEngine() {
        handler.post {
            desired = false
            cancelRetry()
            cancelRecognizer()
            stateFlow.value = EngineState.MIC_OFF
            partialFlow.value = ""
            amplitudeFlow.value = 0f
        }
    }

    private fun cancelRecognizer() {
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Throwable) {
        } finally {
            recognizer = null
            sessionRunning = false
        }
    }

    private fun schedule(delay: Long) {
        cancelRetry()
        retryTask = Runnable {
            retryTask = null
            if (desired && !assistantSpeaking) startRecognitionSession()
        }
        handler.postDelayed(retryTask!!, delay)
    }

    private fun scheduleRetry() {
        val delay = retryMs
        retryMs = (retryMs * 1.5f).toLong().coerceAtMost(MAX_RETRY_MS)
        schedule(delay)
    }

    private fun cancelRetry() {
        retryTask?.let(handler::removeCallbacks)
        retryTask = null
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

    private fun findWake(input: String): String? =
        wakeWords.firstOrNull { phrase ->
            input == phrase ||
                input.startsWith("$phrase ") ||
                input.endsWith(" $phrase") ||
                input.contains(" $phrase ")
        }

    private fun containsPhrase(input: String, phrases: Set<String>): Boolean =
        phrases.any { phrase ->
            input == phrase ||
                input.startsWith("$phrase ") ||
                input.endsWith(" $phrase") ||
                input.contains(" $phrase ")
        }

    private fun commandAfterWake(original: String, wake: String): String {
        val normalized = original.lowercase(Locale.ROOT)
        val index = normalized.indexOf(wake)
        if (index < 0) return ""
        return original.substring((index + wake.length).coerceAtMost(original.length))
            .trim()
            .trimStart(',', '.', '!', '?', ';', ':')
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Kavya Voice Input",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Kavya microphone and wake-word listener"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Kavya microphone active")
            .setContentText("Listening for Hey Kavya / Wake Up Kavya")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    override fun onDestroy() {
        stopEngine()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
