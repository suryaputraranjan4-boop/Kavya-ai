package com.example.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.state.KavyaStateManager
import com.example.ui.components.VoiceState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Authoritative Voice & Microphone Lifecycle States.
 */
enum class VoiceCoordinatorState {
    IDLE,
    STARTING,
    LISTENING,
    PROCESSING,
    SPEAKING,
    PAUSED_FOR_TTS,
    SLEEPING,
    STOPPING,
    ERROR,
    RECOVERING
}

/**
 * Capture Modes supported by the VoiceCaptureCoordinator.
 */
enum class CaptureMode {
    SPEECH_RECOGNIZER,
    RAW_AUDIO_RECORD,
    GEMINI_LIVE_STREAM
}

/**
 * Master Voice Capture Coordinator for Kavya AI.
 *
 * Single Authoritative Owner of Android Physical Microphone Hardware:
 * 1. Guarantees that only ONE capture session (SpeechRecognizer, AudioRecord, or Gemini Live)
 *    accesses hardware at any moment.
 * 2. Provides idempotent start, pause, resume, sleep, and stop semantics.
 * 3. Atomic TTS handoff reference counting to prevent premature microphone restarts.
 * 4. Differentiated SpeechRecognizer error handling with bounded backoff.
 * 5. Exposes audio frame streams to GeminiLiveClient without duplicate AudioRecord instances.
 * 6. Synchronizes real hardware state with KavyaStateManager and the UI.
 */
class VoiceCaptureCoordinator private constructor(private val context: Context) {

    companion object {
        private const val TAG = "VoiceCaptureCoord"
        private const val DEFAULT_SAMPLE_RATE = 16000
        private const val MAX_REARM_RETRIES = 4

        @Volatile
        private var instance: VoiceCaptureCoordinator? = null

        fun getInstance(context: Context): VoiceCaptureCoordinator {
            return instance ?: synchronized(this) {
                instance ?: VoiceCaptureCoordinator(context.applicationContext).also { instance = it }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val sessionMutex = Mutex()

    // Active Capture Components
    private var activeSpeechRecognizer: SpeechRecognizer? = null
    private var activeAudioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var rearmJob: Job? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // Session State
    private val _coordinatorState = MutableStateFlow(VoiceCoordinatorState.IDLE)
    val coordinatorState: StateFlow<VoiceCoordinatorState> = _coordinatorState.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private val _diagnosticState = MutableStateFlow(MicrophoneDiagnosticState())
    val diagnosticState: StateFlow<MicrophoneDiagnosticState> = _diagnosticState.asStateFlow()

    @Volatile
    private var activeCaptureMode = CaptureMode.SPEECH_RECOGNIZER

    @Volatile
    private var isContinuousListeningRequested = false

    @Volatile
    private var isSleeping = false

    private val pauseReferenceCount = AtomicInteger(0)
    private var consecutiveErrorCount = 0

    // Registered Callbacks
    private var onResultCallback: ((String) -> Unit)? = null
    private var onPartialResultCallback: ((String) -> Unit)? = null
    private var onStartedCallback: (() -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    // Gemini Live Frame Subscribers
    private val frameSubscribers = mutableListOf<(ByteArray, Int) -> Unit>()

    init {
        refreshHardwareDiagnostics()
    }

    fun refreshHardwareDiagnostics() {
        val perm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val isRecognizerAvail = try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (_: Exception) {
            false
        }

        val minBuf = AudioRecord.getMinBufferSize(
            DEFAULT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val isHardwareReady = minBuf > 0

        _diagnosticState.value = _diagnosticState.value.copy(
            hasPermission = perm,
            isHardwareAvailable = isHardwareReady,
            isAudioInputReady = isHardwareReady && perm,
            isRecognizerAvailable = isRecognizerAvail,
            isListening = _coordinatorState.value == VoiceCoordinatorState.LISTENING
        )
    }

    // =========================================================================
    // AUTHORITATIVE LIFECYCLE COMMANDS
    // =========================================================================

    /**
     * Idempotently starts continuous hands-free voice capture.
     */
    fun startListening(
        mode: CaptureMode = CaptureMode.SPEECH_RECOGNIZER,
        onStarted: () -> Unit = {},
        onResult: (String) -> Unit,
        onPartialResult: ((String) -> Unit)? = null,
        onError: (String) -> Unit = {}
    ) {
        scope.launch {
            sessionMutex.withLock {
                Log.i(TAG, "CMD: START_LISTENING (Mode=$mode, Current=${_coordinatorState.value})")

                if (!hasAudioPermission()) {
                    Log.e(TAG, "START_LISTENING failed: RECORD_AUDIO permission missing")
                    updateState(VoiceCoordinatorState.ERROR)
                    _diagnosticState.value = _diagnosticState.value.copy(
                        hasPermission = false,
                        isListening = false,
                        lastError = "Microphone permission required"
                    )
                    withContext(Dispatchers.Main) {
                        onError("Microphone permission required. Please grant permission.")
                    }
                    return@withLock
                }

                activeCaptureMode = mode
                isContinuousListeningRequested = true
                isSleeping = false
                pauseReferenceCount.set(0)
                consecutiveErrorCount = 0

                onStartedCallback = onStarted
                onResultCallback = onResult
                onPartialResultCallback = onPartialResult
                onErrorCallback = onError

                rearmJob?.cancel()
                rearmJob = null

                startCaptureSessionInternal()
            }
        }
    }

    /**
     * Temporarily pauses capture during TTS speech playback or audio focus ducking.
     * Thread-safe with atomic reference counting.
     */
    fun pauseListening(reason: String = "TTS_PLAYBACK") {
        val count = pauseReferenceCount.incrementAndGet()
        Log.d(TAG, "CMD: PAUSE_LISTENING (Reason=$reason, PauseRefCount=$count)")

        rearmJob?.cancel()
        rearmJob = null

        if (_coordinatorState.value != VoiceCoordinatorState.SLEEPING) {
            updateState(VoiceCoordinatorState.PAUSED_FOR_TTS)
        }

        stopPhysicalCapture(discard = true)
        _amplitude.value = 0f
    }

    /**
     * Resumes capture after TTS speech playback or audio focus restoration.
     * Only restarts hardware when all pause tokens have been cleared.
     */
    fun resumeListening(reason: String = "TTS_FINISHED", acousticBufferMs: Long = 350L) {
        val remaining = pauseReferenceCount.decrementAndGet()
        Log.d(TAG, "CMD: RESUME_LISTENING (Reason=$reason, RemainingTokens=$remaining)")

        if (remaining > 0) {
            Log.d(TAG, "Resume deferred: $remaining active pause locks remaining.")
            return
        }

        pauseReferenceCount.set(0)

        if (isContinuousListeningRequested && !isSleeping) {
            rearmJob?.cancel()
            rearmJob = scope.launch {
                delay(acousticBufferMs)
                sessionMutex.withLock {
                    if (isContinuousListeningRequested && pauseReferenceCount.get() == 0 && !isSleeping) {
                        Log.d(TAG, "Acoustic buffer elapsed ($acousticBufferMs ms). Re-arming microphone.")
                        startCaptureSessionInternal()
                    }
                }
            }
        } else if (isSleeping) {
            updateState(VoiceCoordinatorState.SLEEPING)
        } else {
            updateState(VoiceCoordinatorState.IDLE)
        }
    }

    /**
     * Completely stops active capture and disables automatic re-arming.
     */
    fun stopListening() {
        Log.i(TAG, "CMD: STOP_LISTENING")
        isContinuousListeningRequested = false
        rearmJob?.cancel()
        rearmJob = null
        pauseReferenceCount.set(0)

        scope.launch {
            sessionMutex.withLock {
                stopPhysicalCapture(discard = true)
                abandonAudioFocus()
                updateState(VoiceCoordinatorState.IDLE)
                _amplitude.value = 0f
                _partialTranscript.value = ""
            }
        }
    }

    /**
     * Enters low-power sleeping state. In sleep mode, normal chatter is rejected.
     */
    fun enterSleep() {
        Log.i(TAG, "CMD: ENTER_SLEEP")
        isSleeping = true
        updateState(VoiceCoordinatorState.SLEEPING)
    }

    /**
     * Exits sleep state into active listening.
     */
    fun exitSleep() {
        Log.i(TAG, "CMD: EXIT_SLEEP")
        isSleeping = false
        updateState(VoiceCoordinatorState.IDLE)
        if (isContinuousListeningRequested) {
            resumeListening("EXIT_SLEEP", acousticBufferMs = 150L)
        }
    }

    /**
     * Immediate barge-in interrupt: instantly cancels TTS pause locks and cuts to microphone.
     */
    fun forceBargeIn() {
        Log.i(TAG, "CMD: FORCE_BARGE_IN (User interrupt)")
        pauseReferenceCount.set(0)
        rearmJob?.cancel()
        rearmJob = null
        scope.launch {
            sessionMutex.withLock {
                startCaptureSessionInternal()
            }
        }
    }

    /**
     * Completely tears down coordinator and releases audio resources.
     */
    fun shutdown() {
        Log.i(TAG, "CMD: SHUTDOWN")
        isContinuousListeningRequested = false
        rearmJob?.cancel()
        rearmJob = null
        pauseReferenceCount.set(0)
        frameSubscribers.clear()

        scope.launch {
            sessionMutex.withLock {
                stopPhysicalCapture(discard = true)
                abandonAudioFocus()
                updateState(VoiceCoordinatorState.IDLE)
            }
        }
    }

    // =========================================================================
    // GEMINI LIVE STREAMING SUBSCRIPTIONS
    // =========================================================================

    fun subscribeAudioFrames(subscriber: (ByteArray, Int) -> Unit) {
        synchronized(frameSubscribers) {
            if (!frameSubscribers.contains(subscriber)) {
                frameSubscribers.add(subscriber)
            }
        }
    }

    fun unsubscribeAudioFrames(subscriber: (ByteArray, Int) -> Unit) {
        synchronized(frameSubscribers) {
            frameSubscribers.remove(subscriber)
        }
    }

    // =========================================================================
    // INTERNAL PHYSICAL CAPTURE IMPLEMENTATION
    // =========================================================================

    private suspend fun startCaptureSessionInternal() {
        if (pauseReferenceCount.get() > 0) {
            Log.d(TAG, "startCaptureSessionInternal suppressed: pause lock active (${pauseReferenceCount.get()})")
            return
        }

        updateState(VoiceCoordinatorState.STARTING)
        stopPhysicalCapture(discard = true)
        requestAudioFocus()

        when (activeCaptureMode) {
            CaptureMode.SPEECH_RECOGNIZER -> {
                val isRecognizerAvailable = try {
                    SpeechRecognizer.isRecognitionAvailable(context)
                } catch (_: Exception) {
                    false
                }

                if (isRecognizerAvailable) {
                    startSpeechRecognizerInternal()
                } else {
                    Log.w(TAG, "Native SpeechRecognizer not available. Falling back to raw AudioRecord capture.")
                    startAudioRecordInternal()
                }
            }
            CaptureMode.RAW_AUDIO_RECORD, CaptureMode.GEMINI_LIVE_STREAM -> {
                startAudioRecordInternal()
            }
        }
    }

    private fun startSpeechRecognizerInternal() {
        mainHandler.post {
            try {
                cleanupSpeechRecognizer()

                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                activeSpeechRecognizer = recognizer

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "SPEECH_RECOGNIZER_READY (Hardware active, privacy indicator ON)")
                        consecutiveErrorCount = 0
                        updateState(VoiceCoordinatorState.LISTENING)
                        _diagnosticState.value = _diagnosticState.value.copy(
                            isListening = true,
                            lastError = "None"
                        )
                        onStartedCallback?.invoke()
                    }

                    override fun onBeginningOfSpeech() {
                        Log.d(TAG, "SPEECH_BEGINNING_DETECTED")
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                        _amplitude.value = normalized
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.d(TAG, "SPEECH_END_DETECTED")
                        updateState(VoiceCoordinatorState.PROCESSING)
                        _amplitude.value = 0f
                    }

                    override fun onError(errorCode: Int) {
                        handleSpeechRecognizerError(errorCode)
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim() ?: ""
                        Log.d(TAG, "SPEECH_RESULTS: \"$text\"")

                        _amplitude.value = 0f
                        _partialTranscript.value = ""
                        cleanupSpeechRecognizer()
                        abandonAudioFocus()

                        if (text.isBlank()) {
                            if (isContinuousListeningRequested && pauseReferenceCount.get() == 0 && !isSleeping) {
                                scheduleSafeRearm(delayMs = 200L)
                            } else {
                                updateState(VoiceCoordinatorState.IDLE)
                                onResultCallback?.invoke("")
                            }
                        } else {
                            consecutiveErrorCount = 0
                            updateState(VoiceCoordinatorState.PROCESSING)
                            _diagnosticState.value = _diagnosticState.value.copy(
                                isListening = false,
                                lastRecognizedText = text,
                                lastCallbackTimestamp = System.currentTimeMillis()
                            )
                            onResultCallback?.invoke(text)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partial = matches?.firstOrNull()?.trim() ?: ""
                        if (partial.isNotBlank()) {
                            _partialTranscript.value = partial
                            onPartialResultCallback?.invoke(partial)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
                    putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-IN", "en-US", "hi-Latn"))
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                }

                recognizer.startListening(intent)

            } catch (e: Exception) {
                Log.e(TAG, "SpeechRecognizer start failed: ${e.message}", e)
                cleanupSpeechRecognizer()
                startAudioRecordInternal()
            }
        }
    }

    private fun handleSpeechRecognizerError(errorCode: Int) {
        val (msg, isSilentTimeout) = when (errorCode) {
            SpeechRecognizer.ERROR_NO_MATCH -> Pair("No speech recognized", true)
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Pair("Speech input timeout", true)
            SpeechRecognizer.ERROR_AUDIO -> Pair("Audio recording error", false)
            SpeechRecognizer.ERROR_CLIENT -> Pair("Speech client error", false)
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Pair("Microphone permission required", false)
            SpeechRecognizer.ERROR_NETWORK -> Pair("Network error during speech recognition", false)
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> Pair("Network timeout", false)
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Pair("Speech recognizer busy", false)
            SpeechRecognizer.ERROR_SERVER -> Pair("Server error during speech recognition", false)
            else -> Pair("Speech recognition error ($errorCode)", false)
        }

        Log.w(TAG, "SPEECH_RECOGNIZER_ERROR: code=$errorCode ($msg), isSilentTimeout=$isSilentTimeout, consecutiveErrors=$consecutiveErrorCount")
        _amplitude.value = 0f
        cleanupSpeechRecognizer()
        abandonAudioFocus()

        if (isSilentTimeout) {
            consecutiveErrorCount = 0
            if (isContinuousListeningRequested && pauseReferenceCount.get() == 0 && !isSleeping) {
                scheduleSafeRearm(delayMs = 180L)
            } else {
                updateState(VoiceCoordinatorState.IDLE)
                onResultCallback?.invoke("")
            }
        } else if (errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || errorCode == SpeechRecognizer.ERROR_CLIENT) {
            consecutiveErrorCount++
            if (consecutiveErrorCount <= MAX_REARM_RETRIES && isContinuousListeningRequested && pauseReferenceCount.get() == 0) {
                val backoff = (300L * consecutiveErrorCount).coerceAtMost(1500L)
                Log.w(TAG, "Recoverable recognizer error ($errorCode). Backoff re-arm in ${backoff}ms")
                scheduleSafeRearm(delayMs = backoff)
            } else {
                Log.w(TAG, "Max recognizer retries exceeded. Falling back to AudioRecord capture.")
                consecutiveErrorCount = 0
                startAudioRecordInternal()
            }
        } else if (errorCode == SpeechRecognizer.ERROR_AUDIO || errorCode == SpeechRecognizer.ERROR_SERVER) {
            Log.w(TAG, "Audio/Server hardware fault. Failing over to real AudioRecord PCM capture.")
            startAudioRecordInternal()
        } else {
            updateState(VoiceCoordinatorState.ERROR)
            _diagnosticState.value = _diagnosticState.value.copy(isListening = false, lastError = msg)
            onErrorCallback?.invoke(msg)
            scope.launch {
                delay(2000)
                if (_coordinatorState.value == VoiceCoordinatorState.ERROR) {
                    updateState(VoiceCoordinatorState.IDLE)
                }
            }
        }
    }

    private fun startAudioRecordInternal() {
        recordingJob?.cancel()
        recordingJob = scope.launch {
            var audioRecord: AudioRecord? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    DEFAULT_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufferSize = (minBuf * 2).coerceAtLeast(4096)

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    DEFAULT_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    audioRecord.release()
                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        DEFAULT_SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                    )
                }

                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("Failed to initialize AudioRecord on any audio source")
                }

                activeAudioRecord = audioRecord
                audioRecord.startRecording()

                updateState(VoiceCoordinatorState.LISTENING)
                withContext(Dispatchers.Main) {
                    onStartedCallback?.invoke()
                }

                val buffer = ByteArray(bufferSize)
                val pcmOutput = ByteArrayOutputStream()
                var speechDetected = false
                var silenceStartTime = 0L
                val sessionStartTime = System.currentTimeMillis()

                while (isActive && _coordinatorState.value == VoiceCoordinatorState.LISTENING && pauseReferenceCount.get() == 0) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val frame = buffer.copyOf(read)

                        // Dispatch to Gemini Live frame subscribers if any
                        val subscribersCopy = synchronized(frameSubscribers) { frameSubscribers.toList() }
                        for (sub in subscribersCopy) {
                            try { sub(frame, DEFAULT_SAMPLE_RATE) } catch (_: Exception) {}
                        }

                        // Calculate RMS amplitude
                        var sum = 0.0
                        var i = 0
                        while (i < read - 1) {
                            val sample = (frame[i + 1].toInt() shl 8) or (frame[i].toInt() and 0xFF)
                            sum += sample * sample
                            i += 2
                        }
                        val rms = kotlin.math.sqrt(sum / (read / 2.0))
                        val normAmp = (rms / 3000.0).coerceIn(0.0, 1.0).toFloat()
                        _amplitude.value = normAmp

                        // VAD checks
                        if (rms > 450.0) {
                            speechDetected = true
                            silenceStartTime = 0L
                            pcmOutput.write(frame)
                        } else if (speechDetected) {
                            pcmOutput.write(frame)
                            if (silenceStartTime == 0L) {
                                silenceStartTime = System.currentTimeMillis()
                            } else if (System.currentTimeMillis() - silenceStartTime > 1600L) {
                                Log.d(TAG, "AudioRecord VAD: Speech utterance ended after silence")
                                break
                            }
                        }

                        // Max duration cap (18 seconds)
                        if (System.currentTimeMillis() - sessionStartTime > 18000L) {
                            break
                        }
                    } else if (read < 0) {
                        Log.w(TAG, "AudioRecord read returned error code: $read")
                        break
                    }
                }

                stopPhysicalCapture(discard = false)
                val capturedPcm = pcmOutput.toByteArray()

                if (capturedPcm.isNotEmpty() && speechDetected) {
                    updateState(VoiceCoordinatorState.PROCESSING)
                    val text = try {
                        val ai = com.example.ai.KavyaAI(context)
                        ai.transcribeAudio(capturedPcm, DEFAULT_SAMPLE_RATE)
                    } catch (e: Exception) {
                        Log.w(TAG, "AudioRecord AI transcription failed: ${e.message}")
                        ""
                    }

                    if (text.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            onResultCallback?.invoke(text)
                        }
                    } else if (isContinuousListeningRequested && pauseReferenceCount.get() == 0 && !isSleeping) {
                        scheduleSafeRearm(delayMs = 200L)
                    } else {
                        updateState(VoiceCoordinatorState.IDLE)
                        withContext(Dispatchers.Main) {
                            onResultCallback?.invoke("")
                        }
                    }
                } else if (isContinuousListeningRequested && pauseReferenceCount.get() == 0 && !isSleeping) {
                    scheduleSafeRearm(delayMs = 200L)
                } else {
                    updateState(VoiceCoordinatorState.IDLE)
                }

            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord capture loop error: ${e.message}", e)
                stopPhysicalCapture(discard = true)
                if (isContinuousListeningRequested && pauseReferenceCount.get() == 0 && !isSleeping) {
                    scheduleSafeRearm(delayMs = 500L)
                } else {
                    updateState(VoiceCoordinatorState.ERROR)
                    withContext(Dispatchers.Main) {
                        onErrorCallback?.invoke(e.message ?: "Audio capture error")
                    }
                }
            }
        }
    }

    private fun scheduleSafeRearm(delayMs: Long) {
        rearmJob?.cancel()
        rearmJob = scope.launch {
            delay(delayMs)
            sessionMutex.withLock {
                if (isContinuousListeningRequested && pauseReferenceCount.get() == 0 && !isSleeping) {
                    startCaptureSessionInternal()
                }
            }
        }
    }

    private fun stopPhysicalCapture(discard: Boolean) {
        try {
            activeAudioRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            }
            activeAudioRecord = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
        }

        cleanupSpeechRecognizer()
    }

    private fun cleanupSpeechRecognizer() {
        mainHandler.post {
            try {
                activeSpeechRecognizer?.let {
                    it.stopListening()
                    it.cancel()
                    it.destroy()
                }
                activeSpeechRecognizer = null
            } catch (e: Exception) {
                Log.w(TAG, "Error cleaning up SpeechRecognizer: ${e.message}")
            }
        }
    }

    private fun updateState(newState: VoiceCoordinatorState) {
        _coordinatorState.value = newState
        val mappedVoiceState = when (newState) {
            VoiceCoordinatorState.IDLE -> VoiceState.IDLE
            VoiceCoordinatorState.STARTING -> VoiceState.LISTENING
            VoiceCoordinatorState.LISTENING -> VoiceState.LISTENING
            VoiceCoordinatorState.PROCESSING -> VoiceState.THINKING
            VoiceCoordinatorState.SPEAKING -> VoiceState.SPEAKING
            VoiceCoordinatorState.PAUSED_FOR_TTS -> VoiceState.SPEAKING
            VoiceCoordinatorState.SLEEPING -> VoiceState.IDLE
            VoiceCoordinatorState.STOPPING -> VoiceState.IDLE
            VoiceCoordinatorState.ERROR -> VoiceState.ERROR
            VoiceCoordinatorState.RECOVERING -> VoiceState.THINKING
        }
        KavyaStateManager.updateVoiceState(mappedVoiceState)
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    // =========================================================================
    // AUDIO FOCUS MANAGEMENT
    // =========================================================================

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { focusChange ->
                        handleAudioFocusChange(focusChange)
                    }
                    .build()
                audioFocusRequest = focusRequest
                am.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    { focusChange -> handleAudioFocusChange(focusChange) },
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request audio focus: ${e.message}")
        }
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "AUDIO_FOCUS_LOSS_TRANSIENT: Pausing microphone capture temporarily")
                pauseListening("AUDIO_FOCUS_TRANSIENT_LOSS")
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(TAG, "AUDIO_FOCUS_LOSS: Permanent audio focus loss")
                pauseListening("AUDIO_FOCUS_PERMANENT_LOSS")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "AUDIO_FOCUS_GAIN: Resuming microphone capture")
                resumeListening("AUDIO_FOCUS_RESTORED", acousticBufferMs = 250L)
            }
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to abandon audio focus: ${e.message}")
        }
    }
}
