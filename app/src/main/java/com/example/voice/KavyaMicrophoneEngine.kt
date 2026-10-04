package com.example.voice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single Source of Truth for Kavya AI's Microphone & Voice Input System.
 *
 * Responsibilities:
 * - Single-instance SpeechRecognizer lifecycle & collision protection
 * - Continuous audio capture & speech recognition with automatic recovery
 * - Single-threaded Main-thread execution for Android SpeechRecognizer compliance
 * - Wake / Sleep / Stop state machine ("Wake Kavya", "Sleep Kavya", "Stop Kavya")
 * - Partial results, final results, & audio amplitude metering
 * - Dispatches clean commands to existing Kavya AI pipeline
 */
class KavyaMicrophoneEngine private constructor(context: Context) {

    companion object {
        private const val TAG = "KavyaMicEngine"
        private const val BASE_RESTART_DELAY_MS = 300L
        private const val MAX_BACKOFF_DELAY_MS = 5000L

        @Volatile
        private var instance: KavyaMicrophoneEngine? = null

        fun getInstance(context: Context): KavyaMicrophoneEngine {
            return instance ?: synchronized(this) {
                instance ?: KavyaMicrophoneEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    enum class EngineState {
        IDLE,
        AWAKE,
        SLEEP_LISTENING,
        STOPPED,
        ERROR
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _engineState = MutableStateFlow(EngineState.IDLE)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var isRecognizerActive = false
    private var isListeningLoopDesired = false

    private var backoffDelayMs = BASE_RESTART_DELAY_MS
    private var restartRunnable: Runnable? = null

    private var commandListener: ((String) -> Unit)? = null
    private var stateChangeListener: ((EngineState) -> Unit)? = null

    private val WAKE_PHRASES = setOf(
        "wake kavya", "wake kavia", "wake kavya ai", "utho kavya", "kavya utho",
        "wake up kavya", "kavya wake up", "wake up", "hey kavya", "hello kavya",
        "hi kavya", "ok kavya", "okay kavya", "kavya suno", "suno kavya", " wake kavya"
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

    fun setCommandListener(listener: (String) -> Unit) {
        this.commandListener = listener
    }

    fun setStateListener(listener: (EngineState) -> Unit) {
        this.stateChangeListener = listener
    }

    /**
     * Starts or resumes continuous microphone listening.
     */
    fun start() {
        mainHandler.post {
            if (!hasAudioPermission()) {
                Log.w(TAG, "RECORD_AUDIO permission missing. Engine cannot start.")
                updateState(EngineState.ERROR)
                return@post
            }

            Log.i(TAG, "Starting KavyaMicrophoneEngine...")
            isListeningLoopDesired = true
            if (_engineState.value == EngineState.STOPPED || _engineState.value == EngineState.IDLE) {
                updateState(EngineState.AWAKE)
            }
            backoffDelayMs = BASE_RESTART_DELAY_MS
            startListeningInternal()
        }
    }

    /**
     * Enters Sleep Listening mode ("Sleep Kavya").
     * Minimum wake-word listening mechanism active to detect "Wake Kavya".
     */
    fun sleep() {
        mainHandler.post {
            Log.i(TAG, "KavyaMicrophoneEngine entering SLEEP_LISTENING mode.")
            updateState(EngineState.SLEEP_LISTENING)
            _partialText.value = ""
            startListeningInternal()
        }
    }

    /**
     * Awakens Kavya from Sleep mode ("Wake Kavya").
     */
    fun wake() {
        mainHandler.post {
            Log.i(TAG, "KavyaMicrophoneEngine AWAKENED.")
            updateState(EngineState.AWAKE)
            _partialText.value = ""
            startListeningInternal()
        }
    }

    /**
     * Completely stops microphone capture and releases resources ("Stop Kavya").
     */
    fun stop() {
        mainHandler.post {
            Log.i(TAG, "Stopping KavyaMicrophoneEngine and releasing microphone.")
            isListeningLoopDesired = false
            cancelPendingRestart()
            destroyRecognizer()
            updateState(EngineState.STOPPED)
            _partialText.value = ""
            _amplitude.value = 0f
        }
    }

    fun isListening(): Boolean = isRecognizerActive && isListeningLoopDesired

    fun isSleeping(): Boolean = _engineState.value == EngineState.SLEEP_LISTENING

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun updateState(newState: EngineState) {
        if (_engineState.value != newState) {
            _engineState.value = newState
            stateChangeListener?.invoke(newState)
            Log.d(TAG, "Engine state updated to: $newState")
        }
    }

    private fun startListeningInternal() {
        if (!isListeningLoopDesired || _engineState.value == EngineState.STOPPED) {
            return
        }

        if (!hasAudioPermission()) {
            Log.w(TAG, "Cannot start listening: RECORD_AUDIO permission missing.")
            updateState(EngineState.ERROR)
            return
        }

        cancelPendingRestart()
        destroyRecognizer()

        try {
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                Log.e(TAG, "SpeechRecognizer is NOT available on this device!")
                updateState(EngineState.ERROR)
                scheduleRestartWithBackoff()
                return
            }

            val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            this.speechRecognizer = recognizer

            recognizer.setRecognitionListener(createRecognitionListener())

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES, arrayListOf("en-IN", "hi-IN", "en-US"))
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            }

            recognizer.startListening(intent)
            isRecognizerActive = true
            Log.d(TAG, "SpeechRecognizer listening started in state: ${_engineState.value}")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start SpeechRecognizer: ${e.message}", e)
            isRecognizerActive = false
            scheduleRestartWithBackoff()
        }
    }

    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "SpeechRecognizer: Ready for speech.")
            backoffDelayMs = BASE_RESTART_DELAY_MS
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "SpeechRecognizer: Beginning of speech detected.")
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Convert dB (-2 to 10 typical) into 0.0 - 1.0 amplitude
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            _amplitude.value = normalized
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "SpeechRecognizer: End of speech detected.")
            _amplitude.value = 0f
        }

        override fun onError(error: Int) {
            val errorMsg = getErrorMessage(error)
            Log.w(TAG, "SpeechRecognizer error: $errorMsg ($error)")
            _amplitude.value = 0f
            isRecognizerActive = false

            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // Normal silence/no-match during continuous listening loop
                    scheduleRestart(BASE_RESTART_DELAY_MS)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER,
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    scheduleRestartWithBackoff()
                }
                else -> {
                    scheduleRestartWithBackoff()
                }
            }
        }

        override fun onResults(results: Bundle?) {
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
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = matches?.firstOrNull()?.trim() ?: ""
            if (partial.isNotBlank()) {
                _partialText.value = partial
                Log.d(TAG, "SpeechRecognizer Partial Result: \"$partial\"")
                checkPartialWakeOrStop(partial)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun handleRecognizedText(text: String) {
        val lower = text.lowercase(Locale.ROOT).trim()

        // 1. Check Stop Command
        if (isMatch(lower, STOP_PHRASES)) {
            Log.i(TAG, "Stop phrase detected in recognized text: \"$text\"")
            stop()
            return
        }

        // 2. Check Sleep Command
        if (isMatch(lower, SLEEP_PHRASES)) {
            Log.i(TAG, "Sleep phrase detected in recognized text: \"$text\"")
            sleep()
            return
        }

        // 3. Check Wake Command
        if (_engineState.value == EngineState.SLEEP_LISTENING || isMatch(lower, WAKE_PHRASES)) {
            if (isMatch(lower, WAKE_PHRASES)) {
                Log.i(TAG, "Wake phrase detected in recognized text: \"$text\"")
                wake()
                return
            } else {
                Log.d(TAG, "Sleeping mode active. Ignoring normal speech: \"$text\"")
                return
            }
        }

        // 4. Normal AWAKE Command Delivery
        if (_engineState.value == EngineState.AWAKE) {
            Log.i(TAG, "Delivering recognized command to Kavya Pipeline: \"$text\"")
            commandListener?.invoke(text)
        }
    }

    private fun checkPartialWakeOrStop(partial: String) {
        val lower = partial.lowercase(Locale.ROOT).trim()
        if (isMatch(lower, STOP_PHRASES)) {
            Log.i(TAG, "Partial stop phrase detected: \"$partial\"")
            stop()
        } else if (_engineState.value == EngineState.SLEEP_LISTENING && isMatch(lower, WAKE_PHRASES)) {
            Log.i(TAG, "Partial wake phrase detected: \"$partial\"")
            wake()
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
