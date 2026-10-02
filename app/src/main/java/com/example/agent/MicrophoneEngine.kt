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
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verified lifecycle states for the real Android Microphone capture engine.
 */
enum class MicrophoneState {
    IDLE,
    REQUESTING_PERMISSION,
    STARTING,
    LISTENING,
    PROCESSING,
    STOPPING,
    ERROR
}

/**
 * Capture modes for MicrophoneEngine.
 */
enum class MicrophoneCaptureMode {
    SPEECH_RECOGNIZER,
    RAW_AUDIO_RECORD,
    GEMINI_LIVE_STREAM
}

/**
 * Diagnostic state model for Microphone & Audio Input pipeline.
 */
data class MicrophoneDiagnosticState(
    val hasPermission: Boolean = false,
    val isHardwareAvailable: Boolean = false,
    val isAudioInputReady: Boolean = false,
    val isRecognizerAvailable: Boolean = true,
    val isListening: Boolean = false,
    val lastRecognizedText: String = "",
    val lastCallbackTimestamp: Long = 0,
    val lastError: String = "None"
)

/**
 * Single Authoritative Physical Android Microphone Engine for Kavya AI.
 *
 * Sole owner of device audio capture hardware across the entire Android process:
 * 1. Native [SpeechRecognizer] with [RecognitionListener] for high-accuracy STT with Android green privacy indicator.
 * 2. Native [AudioRecord] hardware PCM capture for raw audio streaming and Gemini multimodal audio.
 * 3. Gemini Live audio frame subscriber coordinator (shares physical capture without duplicate AudioRecord instances).
 * 4. Atomic reference-counted TTS speech handoff preventing premature microphone re-arming and acoustic echo.
 * 5. Dynamic AudioFocus management (handles transient loss, ducking, and restoration).
 * 6. Differentiated SpeechRecognizer error recovery with bounded exponential backoff.
 */
class MicrophoneEngine private constructor(private val context: Context) {

    companion object {
        private const val TAG = "KavyaMicrophoneEngine"
        private const val SAMPLE_RATE = 16000
        private const val MAX_REARM_RETRIES = 4

        @Volatile
        private var instance: MicrophoneEngine? = null

        fun getInstance(context: Context): MicrophoneEngine {
            return instance ?: synchronized(this) {
                instance ?: MicrophoneEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val sessionMutex = Mutex()

    // Hardware capture instances
    private var activeSpeechRecognizer: SpeechRecognizer? = null
    private var activeAudioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var rearmJob: Job? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // State flows
    private val _micState = MutableStateFlow(MicrophoneState.IDLE)
    val micState: StateFlow<MicrophoneState> = _micState.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _status = MutableStateFlow(MicrophoneDiagnosticState())
    val status: StateFlow<MicrophoneDiagnosticState> = _status.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    @Volatile
    private var activeCaptureMode = MicrophoneCaptureMode.SPEECH_RECOGNIZER

    @Volatile
    private var isContinuousListeningRequested = false

    @Volatile
    private var isSleeping = false

    private val pauseReferenceCount = AtomicInteger(0)
    private var consecutiveErrorCount = 0

    // Registered Callbacks
    private var activeOnResult: ((String) -> Unit)? = null
    private var activeOnPartialResult: ((String) -> Unit)? = null
    private var activeOnListeningStarted: (() -> Unit)? = null
    private var activeOnError: ((String) -> Unit)? = null
    private var activeOnAudioCaptured: ((ByteArray, Int) -> Unit)? = null

    // Gemini Live Frame Subscribers
    private val frameSubscribers = mutableListOf<(ByteArray, Int) -> Unit>()

    init {
        refreshHardwareDiagnostics()
    }

    /**
     * Inspects device microphone hardware, permissions, AudioRecord buffers, and SpeechRecognizer availability.
     */
    fun refreshHardwareDiagnostics() {
        val permGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val hardwareAvailable = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)

        var audioInputReady = false
        if (permGranted && hardwareAvailable) {
            try {
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuffer > 0) {
                    audioInputReady = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking AudioRecord buffer: ${e.message}")
            }
        }

        val recognizerAvailable = try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (_: Exception) {
            false
        }

        _status.value = _status.value.copy(
            hasPermission = permGranted,
            isHardwareAvailable = hardwareAvailable,
            isAudioInputReady = audioInputReady,
            isRecognizerAvailable = recognizerAvailable,
            isListening = _micState.value == MicrophoneState.LISTENING
        )
    }

    // =========================================================================
    // AUTHORITATIVE LIFECYCLE API
    // =========================================================================

    /**
     * Idempotently starts a single-turn speech recognition session.
     */
    fun startListening(
        onListeningStarted: () -> Unit = {},
        onResult: (String) -> Unit,
        onPartialResult: ((String) -> Unit)? = null,
        onError: (String) -> Unit = {}
    ) {
        startCaptureInternal(
            continuous = false,
            mode = MicrophoneCaptureMode.SPEECH_RECOGNIZER,
            onStarted = onListeningStarted,
            onResult = onResult,
            onPartialResult = onPartialResult,
            onError = onError
        )
    }

    /**
     * Idempotently starts continuous hands-free background listening loop.
     */
    fun startContinuousListening(
        onListeningStarted: () -> Unit = {},
        onResult: (String) -> Unit,
        onPartialResult: ((String) -> Unit)? = null,
        onError: (String) -> Unit = {}
    ) {
        startCaptureInternal(
            continuous = true,
            mode = MicrophoneCaptureMode.SPEECH_RECOGNIZER,
            onStarted = onListeningStarted,
            onResult = onResult,
            onPartialResult = onPartialResult,
            onError = onError
        )
    }

    private fun startCaptureInternal(
        continuous: Boolean,
        mode: MicrophoneCaptureMode,
        onStarted: () -> Unit,
        onResult: (String) -> Unit,
        onPartialResult: ((String) -> Unit)?,
        onError: (String) -> Unit
    ) {
        scope.launch {
            sessionMutex.withLock {
                Log.i(TAG, "START_CAPTURE_REQUEST (Continuous=$continuous, Mode=$mode, Current=${_micState.value})")

                if (!hasAudioPermission()) {
                    Log.e(TAG, "START_CAPTURE failed: RECORD_AUDIO permission missing")
                    updateState(MicrophoneState.ERROR)
                    _status.value = _status.value.copy(
                        hasPermission = false,
                        isListening = false,
                        lastError = "Microphone permission required"
                    )
                    withContext(Dispatchers.Main) {
                        onError("Microphone permission required. Please grant permission.")
                    }
                    return@withLock
                }

                if (_isMuted.value) {
                    Log.d(TAG, "START_CAPTURE suppressed: microphone is MUTED")
                    return@withLock
                }

                activeCaptureMode = mode
                isContinuousListeningRequested = continuous
                isSleeping = false
                pauseReferenceCount.set(0)
                consecutiveErrorCount = 0

                activeOnListeningStarted = onStarted
                activeOnResult = onResult
                activeOnPartialResult = onPartialResult
                activeOnError = onError

                rearmJob?.cancel()
                rearmJob = null

                startPhysicalCaptureSession()
            }
        }
    }

    fun isContinuousListeningEnabled(): Boolean {
        return isContinuousListeningRequested && !isSleeping
    }

    fun setContinuousListening(enabled: Boolean) {
        isContinuousListeningRequested = enabled
        if (!enabled) {
            stopListening()
        }
    }

    /**
     * Temporarily pauses capture during assistant TTS speech playback.
     * Thread-safe with atomic reference counting.
     */
    fun pauseForTts() {
        val count = pauseReferenceCount.incrementAndGet()
        Log.d(TAG, "PAUSE_FOR_TTS (RefCount=$count)")

        rearmJob?.cancel()
        rearmJob = null

        stopPhysicalHardware(discard = true)
        _amplitude.value = 0f
    }

    /**
     * Resumes capture after TTS speech playback completes and acoustic echo has dissipated.
     */
    fun resumeAfterTts(acousticDelayMs: Long = 350L) {
        val remaining = pauseReferenceCount.decrementAndGet()
        Log.d(TAG, "RESUME_AFTER_TTS (RemainingTokens=$remaining, Delay=${acousticDelayMs}ms)")

        if (remaining > 0) {
            Log.d(TAG, "Resume deferred: $remaining active pause locks remaining.")
            return
        }

        pauseReferenceCount.set(0)

        if (isContinuousListeningRequested && !isSleeping) {
            rearmJob?.cancel()
            rearmJob = scope.launch {
                delay(acousticDelayMs)
                sessionMutex.withLock {
                    if (isContinuousListeningRequested && pauseReferenceCount.get() == 0 && !isSleeping) {
                        startPhysicalCaptureSession()
                    }
                }
            }
        } else {
            updateState(MicrophoneState.IDLE)
        }
    }

    /**
     * Immediate barge-in interrupt: instantly cancels TTS pause locks and cuts to microphone.
     */
    fun forceBargeIn() {
        Log.i(TAG, "FORCE_BARGE_IN (User interrupt)")
        pauseReferenceCount.set(0)
        rearmJob?.cancel()
        rearmJob = null
        scope.launch {
            sessionMutex.withLock {
                startPhysicalCaptureSession()
            }
        }
    }

    fun setMuted(muted: Boolean) {
        if (_isMuted.value == muted) return
        _isMuted.value = muted
        com.example.utils.AppPreferences.setMicListeningEnabled(context, !muted)
        if (muted) {
            Log.i(TAG, "MIC_MUTED: Halting physical microphone capture")
            rearmJob?.cancel()
            rearmJob = null
            scope.launch {
                sessionMutex.withLock {
                    stopPhysicalHardware(discard = true)
                    abandonAudioFocus()
                    updateState(MicrophoneState.IDLE)
                    _amplitude.value = 0f
                }
            }
        } else {
            Log.i(TAG, "MIC_UNMUTED: Resuming physical microphone capture")
            if (isContinuousListeningRequested && !isSleeping) {
                scope.launch {
                    sessionMutex.withLock {
                        if (!_isMuted.value && pauseReferenceCount.get() == 0) {
                            startPhysicalCaptureSession()
                        }
                    }
                }
            }
        }
    }

    fun toggleMute() {
        setMuted(!_isMuted.value)
    }

    fun setSleeping(sleeping: Boolean) {
        isSleeping = sleeping
        if (sleeping) {
            Log.i(TAG, "MIC_SLEEP: Halting active command processing")
            rearmJob?.cancel()
            rearmJob = null
            scope.launch {
                sessionMutex.withLock {
                    stopPhysicalHardware(discard = true)
                    abandonAudioFocus()
                    updateState(MicrophoneState.IDLE)
                }
            }
        } else {
            Log.i(TAG, "MIC_WAKE: Resuming active listening")
            if (isContinuousListeningRequested && !_isMuted.value) {
                scope.launch {
                    sessionMutex.withLock {
                        if (pauseReferenceCount.get() == 0) {
                            startPhysicalCaptureSession()
                        }
                    }
                }
            }
        }
    }

    fun isSleeping(): Boolean = isSleeping

    /**
     * Completely stops active capture and disables automatic re-arming.
     */
    fun stopListening() {
        Log.i(TAG, "STOP_LISTENING")
        isContinuousListeningRequested = false
        rearmJob?.cancel()
        rearmJob = null
        pauseReferenceCount.set(0)
        activeOnAudioCaptured = null

        scope.launch {
            sessionMutex.withLock {
                stopPhysicalHardware(discard = true)
                abandonAudioFocus()
                updateState(MicrophoneState.IDLE)
                _amplitude.value = 0f
                _partialText.value = ""
            }
        }
    }

    fun cancelListening() {
        stopListening()
    }

    fun stopRecording() {
        stopListening()
    }

    fun cancelRecording() {
        stopListening()
    }

    fun startRecording(
        onRecordingStarted: () -> Unit,
        onAudioCaptured: (ByteArray, Int) -> Unit,
        onError: (String) -> Unit
    ) {
        activeOnAudioCaptured = onAudioCaptured
        startCaptureInternal(
            continuous = false,
            mode = MicrophoneCaptureMode.RAW_AUDIO_RECORD,
            onStarted = onRecordingStarted,
            onResult = {},
            onPartialResult = null,
            onError = onError
        )
    }

    fun testMicrophone(onComplete: (Boolean, String) -> Unit) {
        refreshHardwareDiagnostics()
        val s = _status.value
        if (!s.hasPermission) {
            onComplete(false, "Permission DENIED: Grant RECORD_AUDIO permission in Settings")
            return
        }
        if (!s.isHardwareAvailable) {
            onComplete(false, "Microphone hardware UNAVAILABLE on this device")
            return
        }
        if (!s.isAudioInputReady) {
            onComplete(false, "Audio input buffer initialization FAILED")
            return
        }
        onComplete(true, "Microphone hardware active and speech input ready")
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

    private suspend fun startPhysicalCaptureSession() {
        if (_isMuted.value || pauseReferenceCount.get() > 0 || isSleeping) {
            Log.d(TAG, "Capture session suppressed: muted=${_isMuted.value}, pauseCount=${pauseReferenceCount.get()}, sleeping=$isSleeping")
            return
        }

        updateState(MicrophoneState.STARTING)
        stopPhysicalHardware(discard = true)
        requestAudioFocus()

        when (activeCaptureMode) {
            MicrophoneCaptureMode.SPEECH_RECOGNIZER -> {
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
            MicrophoneCaptureMode.RAW_AUDIO_RECORD, MicrophoneCaptureMode.GEMINI_LIVE_STREAM -> {
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
                        Log.d(TAG, "SPEECH_RECOGNIZER_READY (Android green privacy indicator ACTIVE)")
                        consecutiveErrorCount = 0
                        updateState(MicrophoneState.LISTENING)
                        _status.value = _status.value.copy(isListening = true, lastError = "None")
                        activeOnListeningStarted?.invoke()
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
                        updateState(MicrophoneState.PROCESSING)
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
                        _partialText.value = ""
                        cleanupSpeechRecognizer()
                        abandonAudioFocus()

                        if (text.isBlank()) {
                            if (isContinuousListeningRequested && pauseReferenceCount.get() == 0 && !isSleeping) {
                                scheduleSafeRearm(delayMs = 200L)
                            } else {
                                updateState(MicrophoneState.IDLE)
                                activeOnResult?.invoke("")
                            }
                        } else {
                            consecutiveErrorCount = 0
                            updateState(MicrophoneState.PROCESSING)
                            _status.value = _status.value.copy(
                                isListening = false,
                                lastRecognizedText = text,
                                lastCallbackTimestamp = System.currentTimeMillis()
                            )
                            activeOnResult?.invoke(text)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partial = matches?.firstOrNull()?.trim() ?: ""
                        if (partial.isNotBlank()) {
                            _partialText.value = partial
                            activeOnPartialResult?.invoke(partial)
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

        Log.w(TAG, "SPEECH_RECOGNIZER_ERROR: code=$errorCode ($msg), isSilentTimeout=$isSilentTimeout, count=$consecutiveErrorCount")
        _amplitude.value = 0f
        cleanupSpeechRecognizer()
        abandonAudioFocus()

        if (isSilentTimeout) {
            consecutiveErrorCount = 0
            if (isContinuousListeningRequested && pauseReferenceCount.get() == 0 && !isSleeping) {
                scheduleSafeRearm(delayMs = 180L)
            } else {
                updateState(MicrophoneState.IDLE)
                activeOnResult?.invoke("")
            }
        } else if (errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || errorCode == SpeechRecognizer.ERROR_CLIENT) {
            consecutiveErrorCount++
            if (consecutiveErrorCount <= MAX_REARM_RETRIES && isContinuousListeningRequested && pauseReferenceCount.get() == 0) {
                val backoff = (300L * consecutiveErrorCount).coerceAtMost(1500L)
                Log.w(TAG, "Recoverable recognizer error ($errorCode). Backoff re-arm in ${backoff}ms")
                scheduleSafeRearm(delayMs = backoff)
            } else {
                Log.w(TAG, "Max recognizer retries reached. Falling back to raw AudioRecord capture.")
                consecutiveErrorCount = 0
                startAudioRecordInternal()
            }
        } else if (errorCode == SpeechRecognizer.ERROR_AUDIO || errorCode == SpeechRecognizer.ERROR_SERVER) {
            Log.w(TAG, "Hardware/Server fault. Failing over to native AudioRecord PCM capture.")
            startAudioRecordInternal()
        } else {
            updateState(MicrophoneState.ERROR)
            _status.value = _status.value.copy(isListening = false, lastError = msg)
            activeOnError?.invoke(msg)
            scope.launch {
                delay(2000)
                if (_micState.value == MicrophoneState.ERROR) {
                    updateState(MicrophoneState.IDLE)
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
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufferSize = (minBuf * 2).coerceAtLeast(4096)

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    audioRecord.release()
                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
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

                updateState(MicrophoneState.LISTENING)
                withContext(Dispatchers.Main) {
                    activeOnListeningStarted?.invoke()
                }

                val buffer = ByteArray(bufferSize)
                val pcmOutput = ByteArrayOutputStream()
                var speechDetected = false
                var silenceStartTime = 0L
                val sessionStartTime = System.currentTimeMillis()

                while (isActive && _micState.value == MicrophoneState.LISTENING && pauseReferenceCount.get() == 0) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val frame = buffer.copyOf(read)
                        try {
                            activeOnAudioCaptured?.invoke(frame, read)
                        } catch (e: Exception) {
                            Log.w(TAG, "activeOnAudioCaptured callback error: ${e.message}")
                        }

                        // Dispatch to Gemini Live frame subscribers if any
                        val subscribersCopy = synchronized(frameSubscribers) { frameSubscribers.toList() }
                        for (sub in subscribersCopy) {
                            try { sub(frame, SAMPLE_RATE) } catch (_: Exception) {}
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
                                Log.d(TAG, "AudioRecord VAD: Utterance completed")
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

                stopPhysicalHardware(discard = false)
                val capturedPcm = pcmOutput.toByteArray()

                if (capturedPcm.isNotEmpty() && speechDetected) {
                    updateState(MicrophoneState.PROCESSING)
                    val text = try {
                        val ai = com.example.ai.KavyaAI(context)
                        ai.transcribeAudio(capturedPcm, SAMPLE_RATE)
                    } catch (e: Exception) {
                        Log.w(TAG, "AudioRecord transcription failed: ${e.message}")
                        ""
                    }

                    if (text.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            activeOnResult?.invoke(text)
                        }
                    } else if (isContinuousListeningRequested && pauseReferenceCount.get() == 0 && !isSleeping) {
                        scheduleSafeRearm(delayMs = 200L)
                    } else {
                        updateState(MicrophoneState.IDLE)
                        withContext(Dispatchers.Main) {
                            activeOnResult?.invoke("")
                        }
                    }
                } else if (isContinuousListeningRequested && pauseReferenceCount.get() == 0 && !isSleeping) {
                    scheduleSafeRearm(delayMs = 200L)
                } else {
                    updateState(MicrophoneState.IDLE)
                }

            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord capture loop error: ${e.message}", e)
                stopPhysicalHardware(discard = true)
                if (isContinuousListeningRequested && pauseReferenceCount.get() == 0 && !isSleeping) {
                    scheduleSafeRearm(delayMs = 500L)
                } else {
                    updateState(MicrophoneState.ERROR)
                    withContext(Dispatchers.Main) {
                        activeOnError?.invoke(e.message ?: "Audio capture error")
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
                    startPhysicalCaptureSession()
                }
            }
        }
    }

    private fun stopPhysicalHardware(discard: Boolean) {
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

    private fun updateState(newState: MicrophoneState) {
        _micState.value = newState
        val mappedVoiceState = when (newState) {
            MicrophoneState.IDLE -> VoiceState.IDLE
            MicrophoneState.REQUESTING_PERMISSION -> VoiceState.IDLE
            MicrophoneState.STARTING -> VoiceState.LISTENING
            MicrophoneState.LISTENING -> VoiceState.LISTENING
            MicrophoneState.PROCESSING -> VoiceState.THINKING
            MicrophoneState.STOPPING -> VoiceState.IDLE
            MicrophoneState.ERROR -> VoiceState.ERROR
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
                pauseForTts()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(TAG, "AUDIO_FOCUS_LOSS: Permanent audio focus loss")
                pauseForTts()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "AUDIO_FOCUS_GAIN: Resuming microphone capture")
                resumeAfterTts(acousticDelayMs = 250L)
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
