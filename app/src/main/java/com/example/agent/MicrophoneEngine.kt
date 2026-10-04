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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
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
 * Centralized Microphone Event stream for decoupled observation by ViewModel,
 * Foreground Service, and Assistant Pipeline without callback collisions.
 */
sealed class MicEvent {
    data object ListeningStarted : MicEvent()
    data class Result(val text: String) : MicEvent()
    data class PartialResult(val text: String) : MicEvent()
    data class Error(val error: String) : MicEvent()
    data object Stopped : MicEvent()
}

/**
 * Listener interface for multi-consumer callback registration.
 */
interface MicListener {
    fun onListeningStarted() {}
    fun onResult(text: String) {}
    fun onPartialResult(text: String) {}
    fun onError(error: String) {}
    fun onStopped() {}
}

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
 * 7. Strictly synchronized session transitions ensuring SpeechRecognizer and AudioRecord are NEVER simultaneously active.
 */
class MicrophoneEngine private constructor(private val context: Context) {

    companion object {
        private const val TAG = "KavyaMicrophoneEngine"
        private const val SAMPLE_RATE = 16000
        private const val MAX_REARM_RETRIES = 3

        @Volatile
        private var instance: MicrophoneEngine? = null

        fun getInstance(context: Context): MicrophoneEngine {
            return instance ?: synchronized(this) {
                instance ?: MicrophoneEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val sessionMutex = Mutex()

    // Hardware capture instances (strictly mutually exclusive)
    @Volatile
    private var activeSpeechRecognizer: SpeechRecognizer? = null
    @Volatile
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

    private val _isListeningActive = MutableStateFlow(false)
    val isListeningActive: StateFlow<Boolean> = _isListeningActive.asStateFlow()

    // Centralized event stream
    private val _micEvents = MutableSharedFlow<MicEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val micEvents: SharedFlow<MicEvent> = _micEvents.asSharedFlow()

    // Registered multi-consumer listeners
    private val registeredListeners = CopyOnWriteArrayList<MicListener>()

    @Volatile
    private var activeCaptureMode = MicrophoneCaptureMode.SPEECH_RECOGNIZER

    @Volatile
    private var isContinuousListeningRequested = false

    @Volatile
    private var isSleeping = false

    @Volatile
    private var currentSessionId: Long = 0L

    private val pauseReferenceCount = AtomicInteger(0)
    private var consecutiveErrorCount = 0

    private var activeOnAudioCaptured: ((ByteArray, Int) -> Unit)? = null

    // Gemini Live Frame Subscribers
    private val frameSubscribers = mutableListOf<(ByteArray, Int) -> Unit>()

    init {
        refreshHardwareDiagnostics()
    }

    fun isListeningOrStarting(): Boolean {
        val s = _micState.value
        return (s == MicrophoneState.LISTENING || s == MicrophoneState.STARTING) && isHardwareActive()
    }

    fun isHardwareActive(): Boolean {
        return (activeSpeechRecognizer != null) ||
               (activeAudioRecord != null && activeAudioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING)
    }

    private fun isHardwareActuallyActive(mode: MicrophoneCaptureMode): Boolean {
        return when (mode) {
            MicrophoneCaptureMode.SPEECH_RECOGNIZER -> activeSpeechRecognizer != null
            MicrophoneCaptureMode.RAW_AUDIO_RECORD, MicrophoneCaptureMode.GEMINI_LIVE_STREAM -> {
                val rec = activeAudioRecord
                rec != null && rec.recordingState == AudioRecord.RECORDSTATE_RECORDING
            }
        }
    }

    fun addListener(listener: MicListener) {
        if (!registeredListeners.contains(listener)) {
            registeredListeners.add(listener)
        }
    }

    fun removeListener(listener: MicListener) {
        registeredListeners.remove(listener)
    }

    private fun notifyListeningStarted() {
        _micEvents.tryEmit(MicEvent.ListeningStarted)
        for (l in registeredListeners) {
            try { l.onListeningStarted() } catch (e: Exception) { Log.w(TAG, "Listener error: ${e.message}") }
        }
    }

    private fun notifyResult(text: String) {
        _micEvents.tryEmit(MicEvent.Result(text))
        for (l in registeredListeners) {
            try { l.onResult(text) } catch (e: Exception) { Log.w(TAG, "Listener error: ${e.message}") }
        }
    }

    private fun notifyPartialResult(text: String) {
        _micEvents.tryEmit(MicEvent.PartialResult(text))
        for (l in registeredListeners) {
            try { l.onPartialResult(text) } catch (e: Exception) { Log.w(TAG, "Listener error: ${e.message}") }
        }
    }

    private fun notifyError(error: String) {
        _micEvents.tryEmit(MicEvent.Error(error))
        for (l in registeredListeners) {
            try { l.onError(error) } catch (e: Exception) { Log.w(TAG, "Listener error: ${e.message}") }
        }
    }

    private fun notifyStopped() {
        _micEvents.tryEmit(MicEvent.Stopped)
        for (l in registeredListeners) {
            try { l.onStopped() } catch (e: Exception) { Log.w(TAG, "Listener error: ${e.message}") }
        }
    }

    /**
     * Inspects device microphone hardware, permissions, AudioRecord buffers, and SpeechRecognizer availability.
     */
    fun refreshHardwareDiagnostics() {
        val permGranted = hasAudioPermission()
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
        onResult: (String) -> Unit = {},
        onPartialResult: ((String) -> Unit)? = null,
        onError: (String) -> Unit = {}
    ) {
        val listener = object : MicListener {
            override fun onListeningStarted() { onListeningStarted() }
            override fun onResult(text: String) { onResult(text) }
            override fun onPartialResult(text: String) { onPartialResult?.invoke(text) }
            override fun onError(error: String) { onError(error) }
        }
        startCaptureInternal(
            continuous = false,
            mode = MicrophoneCaptureMode.SPEECH_RECOGNIZER,
            listener = listener
        )
    }

    /**
     * Idempotently starts continuous hands-free background listening loop.
     */
    fun startContinuousListening(
        onListeningStarted: () -> Unit = {},
        onResult: (String) -> Unit = {},
        onPartialResult: ((String) -> Unit)? = null,
        onError: (String) -> Unit = {}
    ) {
        val listener = object : MicListener {
            override fun onListeningStarted() { onListeningStarted() }
            override fun onResult(text: String) { onResult(text) }
            override fun onPartialResult(text: String) { onPartialResult?.invoke(text) }
            override fun onError(error: String) { onError(error) }
        }
        startCaptureInternal(
            continuous = true,
            mode = MicrophoneCaptureMode.SPEECH_RECOGNIZER,
            listener = listener
        )
    }

    private fun startCaptureInternal(
        continuous: Boolean,
        mode: MicrophoneCaptureMode,
        listener: MicListener? = null
    ) {
        if (listener != null) {
            addListener(listener)
        }

        // Synchronous permission check to fail fast and support tests immediately
        if (!hasAudioPermission()) {
            Log.e(TAG, "MIC_START_FAILURE: RECORD_AUDIO permission missing")
            _isListeningActive.value = false
            updateState(MicrophoneState.ERROR)
            _status.value = _status.value.copy(
                hasPermission = false,
                isListening = false,
                lastError = "Microphone permission required"
            )
            val permError = "Microphone permission required. Please grant permission."
            notifyError(permError)
            return
        }

        if (_isMuted.value) {
            Log.d(TAG, "MIC_START_REQUEST suppressed: microphone is MUTED")
            return
        }

        _isListeningActive.value = true

        scope.launch {
            sessionMutex.withLock {
                Log.i(TAG, "MIC_START_REQUEST (continuous=$continuous, mode=$mode, state=${_micState.value}, activeMode=$activeCaptureMode)")

                // IDEMPOTENT GUARDS:
                // 1. If STARTING and same capture mode: ignore duplicate request if hardware is active/starting
                if (_micState.value == MicrophoneState.STARTING && activeCaptureMode == mode) {
                    if (isHardwareActuallyActive(mode)) {
                        Log.d(TAG, "START_CAPTURE ignored: Already in STARTING state with active hardware for mode $mode")
                        isContinuousListeningRequested = continuous || isContinuousListeningRequested
                        return@withLock
                    }
                }

                // 2. If LISTENING and same capture mode: ignore duplicate request, do NOT restart
                if (_micState.value == MicrophoneState.LISTENING && activeCaptureMode == mode) {
                    if (isHardwareActuallyActive(mode)) {
                        Log.d(TAG, "START_CAPTURE ignored: Already in LISTENING state with active hardware for mode $mode")
                        isContinuousListeningRequested = continuous || isContinuousListeningRequested
                        return@withLock
                    }
                }

                // 3. If active session exists but different mode requested: cleanly stop current session before switching
                if ((_micState.value == MicrophoneState.LISTENING || _micState.value == MicrophoneState.STARTING) && activeCaptureMode != mode) {
                    Log.i(TAG, "START_CAPTURE: Switching mode from $activeCaptureMode to $mode. Cleanly releasing current hardware.")
                    stopPhysicalHardwareSync()
                }

                activeCaptureMode = mode
                isContinuousListeningRequested = continuous
                pauseReferenceCount.set(0)
                consecutiveErrorCount = 0

                rearmJob?.cancel()
                rearmJob = null

                startPhysicalCaptureSession()
            }
        }
    }

    fun isContinuousListeningEnabled(): Boolean {
        return isContinuousListeningRequested
    }

    fun setContinuousListening(enabled: Boolean) {
        isContinuousListeningRequested = enabled
        if (!enabled) {
            stopListening()
        }
    }

    /**
     * Temporarily pauses capture during assistant audio speech playback.
     * Thread-safe with atomic reference counting and bounded recovery.
     */
    fun pauseForTts() {
        val count = pauseReferenceCount.incrementAndGet()
        Log.d(TAG, "PAUSE_FOR_AUDIO_PLAYBACK (RefCount=$count)")

        rearmJob?.cancel()
        rearmJob = null

        scope.launch {
            sessionMutex.withLock {
                stopPhysicalHardwareSync()
            }
        }
        _amplitude.value = 0f
    }

    /**
     * Resumes capture after audio speech playback completes and acoustic echo has dissipated.
     */
    fun resumeAfterTts(acousticDelayMs: Long = 350L) {
        val remaining = pauseReferenceCount.decrementAndGet().coerceAtLeast(0)
        Log.d(TAG, "RESUME_AFTER_AUDIO_PLAYBACK (RemainingTokens=$remaining, Delay=${acousticDelayMs}ms)")

        if (remaining > 0) {
            Log.d(TAG, "Resume deferred: $remaining active pause locks remaining.")
            // Safety auto-recovery: if remaining lock stays stale, clear it so mic is never permanently disabled
            scope.launch {
                delay(5000L)
                if (pauseReferenceCount.get() > 0) {
                    Log.w(TAG, "Safety recovery: Clearing stale pause locks (${pauseReferenceCount.get()})")
                    pauseReferenceCount.set(0)
                    if (isContinuousListeningRequested && !_isMuted.value) {
                        sessionMutex.withLock {
                            startPhysicalCaptureSession()
                        }
                    }
                }
            }
            return
        }

        pauseReferenceCount.set(0)

        if (isContinuousListeningRequested && !_isMuted.value) {
            scheduleSafeRearm(delayMs = acousticDelayMs)
        } else {
            updateState(MicrophoneState.IDLE)
        }
    }

    fun resetPauseLock() {
        pauseReferenceCount.set(0)
    }

    /**
     * Immediate barge-in interrupt: instantly cancels pause locks and cuts to microphone.
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
                    stopPhysicalHardwareSync()
                    abandonAudioFocus()
                    updateState(MicrophoneState.IDLE)
                    _amplitude.value = 0f
                }
            }
        } else {
            Log.i(TAG, "MIC_UNMUTED: Resuming physical microphone capture")
            if (isContinuousListeningRequested) {
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
        if (sleeping) {
            Log.i(TAG, "MIC_SLEEP: Entering sleep mode (physical hardware stays active for wake detection)")
            isSleeping = true
            _isListeningActive.value = false
            KavyaStateManager.updateVoiceState(VoiceState.IDLE)
        } else {
            Log.i(TAG, "MIC_WAKE: Waking up from sleep mode (restoring continuous listening UI and processing)")
            isSleeping = false
            if (isContinuousListeningRequested && !_isMuted.value) {
                _isListeningActive.value = true
                KavyaStateManager.updateVoiceState(VoiceState.LISTENING)
                if (!isHardwareActuallyActive(activeCaptureMode)) {
                    scope.launch {
                        sessionMutex.withLock {
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
        Log.i(TAG, "MIC_STOP_REQUEST: Explicit stop requested")
        isContinuousListeningRequested = false
        _isListeningActive.value = false
        rearmJob?.cancel()
        rearmJob = null
        pauseReferenceCount.set(0)
        activeOnAudioCaptured = null
        updateState(MicrophoneState.IDLE)
        _amplitude.value = 0f
        _partialText.value = ""

        scope.launch {
            sessionMutex.withLock {
                stopPhysicalHardwareSync()
                abandonAudioFocus()
                Log.i(TAG, "MIC_STOP_SUCCESS: Physical capture session completely halted")
                notifyStopped()
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
        onRecordingStarted: () -> Unit = {},
        onAudioCaptured: (ByteArray, Int) -> Unit = { _, _ -> },
        onError: (String) -> Unit = {}
    ) {
        activeOnAudioCaptured = onAudioCaptured
        val listener = object : MicListener {
            override fun onListeningStarted() { onRecordingStarted() }
            override fun onError(error: String) { onError(error) }
        }
        startCaptureInternal(
            continuous = false,
            mode = MicrophoneCaptureMode.RAW_AUDIO_RECORD,
            listener = listener
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
        scope.launch {
            sessionMutex.withLock {
                val isListening = _micState.value == MicrophoneState.LISTENING
                val isStarting = _micState.value == MicrophoneState.STARTING
                if (isListening && activeCaptureMode == MicrophoneCaptureMode.SPEECH_RECOGNIZER) {
                    Log.i(TAG, "Frame subscriber registered: cleanly switching mode to RAW_AUDIO_RECORD")
                    stopPhysicalHardwareSync()
                    activeCaptureMode = MicrophoneCaptureMode.RAW_AUDIO_RECORD
                    startPhysicalCaptureSession()
                } else if (!isListening && !isStarting && !_isMuted.value && pauseReferenceCount.get() == 0) {
                    Log.i(TAG, "Frame subscriber registered while idle: starting RAW_AUDIO_RECORD capture")
                    activeCaptureMode = MicrophoneCaptureMode.RAW_AUDIO_RECORD
                    startPhysicalCaptureSession()
                }
            }
        }
    }

    fun unsubscribeAudioFrames(subscriber: (ByteArray, Int) -> Unit) {
        val remainingSubscribers: Int
        synchronized(frameSubscribers) {
            frameSubscribers.remove(subscriber)
            remainingSubscribers = frameSubscribers.size
        }
        scope.launch {
            sessionMutex.withLock {
                if (remainingSubscribers == 0) {
                    Log.i(TAG, "All frame subscribers detached.")
                    if (isContinuousListeningRequested && !_isMuted.value && pauseReferenceCount.get() == 0) {
                        Log.i(TAG, "Restoring normal SPEECH_RECOGNIZER continuous listening mode")
                        stopPhysicalHardwareSync()
                        activeCaptureMode = MicrophoneCaptureMode.SPEECH_RECOGNIZER
                        startPhysicalCaptureSession()
                    } else if (!isContinuousListeningRequested) {
                        Log.i(TAG, "Continuous listening not active. Cleanly stopping capture hardware.")
                        stopPhysicalHardwareSync()
                        abandonAudioFocus()
                        updateState(MicrophoneState.IDLE)
                        _amplitude.value = 0f
                    }
                }
            }
        }
    }

    // =========================================================================
    // INTERNAL PHYSICAL CAPTURE IMPLEMENTATION
    // =========================================================================

    private suspend fun startPhysicalCaptureSession() {
        if (_isMuted.value || pauseReferenceCount.get() > 0) {
            Log.d(TAG, "Capture session suppressed: muted=${_isMuted.value}, pauseCount=${pauseReferenceCount.get()}")
            return
        }

        val hasFrameSubscribers = synchronized(frameSubscribers) { frameSubscribers.isNotEmpty() }
        val effectiveMode = if (hasFrameSubscribers) {
            MicrophoneCaptureMode.RAW_AUDIO_RECORD
        } else {
            activeCaptureMode
        }

        // Determine if physical capture is ALREADY ACTIVE and HEALTHY in requested mode
        if (effectiveMode == MicrophoneCaptureMode.SPEECH_RECOGNIZER) {
            if (activeSpeechRecognizer != null && (_micState.value == MicrophoneState.LISTENING || _micState.value == MicrophoneState.STARTING)) {
                Log.d(TAG, "startPhysicalCaptureSession: SpeechRecognizer is already active/starting. Preserving session.")
                return
            }
        } else {
            if (activeAudioRecord != null && activeAudioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING && _micState.value == MicrophoneState.LISTENING) {
                Log.d(TAG, "startPhysicalCaptureSession: AudioRecord is already recording. Preserving session.")
                return
            }
        }

        // Cleanly release any existing hardware to avoid duplicate instances
        stopPhysicalHardwareSync()

        Log.i(TAG, "MIC_RECOGNIZER_START (mode=$effectiveMode, activeCaptureMode=$activeCaptureMode)")
        updateState(MicrophoneState.STARTING)
        requestAudioFocus()

        when (effectiveMode) {
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

    private suspend fun startSpeechRecognizerInternal() {
        withContext(Dispatchers.Main) {
            try {
                // Guarantee old recognizer is completely destroyed before creating a new one
                destroySpeechRecognizerDirect()

                val thisSessionId = ++currentSessionId
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                activeSpeechRecognizer = recognizer

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        if (thisSessionId != currentSessionId) return
                        Log.i(TAG, "MIC_START_SUCCESS: Android green privacy indicator ACTIVE (Session $thisSessionId)")
                        consecutiveErrorCount = 0
                        _isListeningActive.value = true
                        updateState(MicrophoneState.LISTENING)
                        _status.value = _status.value.copy(isListening = true, lastError = "None")
                        notifyListeningStarted()
                    }

                    override fun onBeginningOfSpeech() {
                        if (thisSessionId != currentSessionId) return
                        Log.d(TAG, "MIC_RECOGNIZER_START: Speech beginning detected")
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        if (thisSessionId != currentSessionId) return
                        val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                        _amplitude.value = normalized
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        if (thisSessionId != currentSessionId) return
                        Log.d(TAG, "MIC_RECOGNIZER_END: Speech end detected")
                        updateState(MicrophoneState.PROCESSING)
                        _amplitude.value = 0f
                    }

                    override fun onError(errorCode: Int) {
                        if (thisSessionId != currentSessionId) return
                        handleSpeechRecognizerError(errorCode, thisSessionId)
                    }

                    override fun onResults(results: Bundle?) {
                        if (thisSessionId != currentSessionId) return
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim() ?: ""
                        Log.i(TAG, "MIC_RECOGNIZER_RESULT: \"$text\"")

                        _amplitude.value = 0f
                        _partialText.value = ""

                        // Destroy old recognizer session for this turn
                        destroySpeechRecognizerDirect()

                        if (text.isBlank()) {
                            if (isContinuousListeningRequested && pauseReferenceCount.get() == 0 && !_isMuted.value) {
                                Log.d(TAG, "MIC_RESTART: Empty speech result in continuous mode. Auto-recovering session (delay 200ms).")
                                scheduleSafeRearm(delayMs = 200L)
                            } else {
                                abandonAudioFocus()
                                _isListeningActive.value = false
                                updateState(MicrophoneState.IDLE)
                                notifyResult("")
                            }
                        } else {
                            consecutiveErrorCount = 0
                            updateState(MicrophoneState.PROCESSING)
                            _status.value = _status.value.copy(
                                isListening = false,
                                lastRecognizedText = text,
                                lastCallbackTimestamp = System.currentTimeMillis()
                            )
                            notifyResult(text)

                            // Continuous listening re-arm after valid speech result (both ACTIVE and SLEEP modes)
                            if (isContinuousListeningRequested && !_isMuted.value) {
                                if (isSleeping) {
                                    Log.d(TAG, "MIC_RESTART: Re-arming wake detector in sleep mode.")
                                    scheduleSafeRearm(delayMs = 300L)
                                } else {
                                    Log.d(TAG, "MIC_RESTART: Scheduling re-arm window after utterance.")
                                    scheduleContinuousReArmAfterUtterance(delayMs = 2500L)
                                }
                            } else {
                                abandonAudioFocus()
                                _isListeningActive.value = false
                                updateState(MicrophoneState.IDLE)
                            }
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        if (thisSessionId != currentSessionId) return
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partial = matches?.firstOrNull()?.trim() ?: ""
                        if (partial.isNotBlank()) {
                            _partialText.value = partial
                            notifyPartialResult(partial)
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
                destroySpeechRecognizerDirect()
                scope.launch {
                    sessionMutex.withLock {
                        startAudioRecordInternal()
                    }
                }
            }
        }
    }

    private fun handleSpeechRecognizerError(errorCode: Int, sessionId: Long = currentSessionId) {
        if (sessionId != currentSessionId) {
            Log.d(TAG, "Ignoring SpeechRecognizer error from obsolete session $sessionId (current=$currentSessionId)")
            return
        }

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

        Log.w(TAG, "MIC_RECOGNIZER_ERROR: code=$errorCode ($msg), isSilentTimeout=$isSilentTimeout, count=$consecutiveErrorCount")
        _amplitude.value = 0f
        destroySpeechRecognizerDirect()

        // 1. FATAL: Microphone permission revoked
        if (errorCode == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            Log.e(TAG, "MIC_START_FAILURE: Microphone permission revoked ($msg)")
            rearmJob?.cancel()
            rearmJob = null
            isContinuousListeningRequested = false
            _isListeningActive.value = false
            abandonAudioFocus()
            updateState(MicrophoneState.ERROR)
            _status.value = _status.value.copy(hasPermission = false, isListening = false, lastError = msg)
            notifyError(msg)
            scope.launch {
                delay(2000)
                sessionMutex.withLock {
                    if (_micState.value == MicrophoneState.ERROR) {
                        updateState(MicrophoneState.IDLE)
                    }
                }
            }
            return
        }

        // 2. NORMAL / RECOVERABLE: Silent timeout (no speech detected in recognition window)
        if (isSilentTimeout) {
            consecutiveErrorCount = 0
            if (isContinuousListeningRequested && pauseReferenceCount.get() == 0 && !_isMuted.value) {
                Log.d(TAG, "MIC_RESTART: Silence timeout in continuous mode. Auto-recovering session (delay 200ms).")
                scheduleSafeRearm(delayMs = 200L)
            } else {
                abandonAudioFocus()
                _isListeningActive.value = false
                updateState(MicrophoneState.IDLE)
                notifyResult("")
            }
            return
        }

        // 3. RECOVERABLE: Busy / Client / Network / Server transient errors
        val isRecoverable = (errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                errorCode == SpeechRecognizer.ERROR_CLIENT ||
                errorCode == SpeechRecognizer.ERROR_NETWORK ||
                errorCode == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                errorCode == SpeechRecognizer.ERROR_SERVER)

        if (isRecoverable && isContinuousListeningRequested && pauseReferenceCount.get() == 0 && !_isMuted.value) {
            consecutiveErrorCount++
            if (consecutiveErrorCount <= MAX_REARM_RETRIES) {
                val backoff = (300L * consecutiveErrorCount).coerceAtMost(1500L)
                Log.w(TAG, "MIC_RESTART: Recoverable recognizer error ($errorCode: $msg). Auto-recovering in ${backoff}ms (attempt $consecutiveErrorCount)")
                scheduleSafeRearm(delayMs = backoff)
            } else {
                Log.w(TAG, "MIC_RESTART: Max recognizer retries reached. Failing over to native AudioRecord PCM capture.")
                consecutiveErrorCount = 0
                scope.launch {
                    sessionMutex.withLock {
                        startAudioRecordInternal()
                    }
                }
            }
            return
        }

        // 4. HARDWARE FAULT: Audio capture failure
        if (errorCode == SpeechRecognizer.ERROR_AUDIO && isContinuousListeningRequested && pauseReferenceCount.get() == 0 && !_isMuted.value) {
            Log.w(TAG, "MIC_RESTART: Hardware fault ($errorCode). Failing over to native AudioRecord PCM capture.")
            scope.launch {
                sessionMutex.withLock {
                    startAudioRecordInternal()
                }
            }
            return
        }

        // 5. UNRECOVERABLE / NON-CONTINUOUS ERROR
        _isListeningActive.value = false
        updateState(MicrophoneState.ERROR)
        _status.value = _status.value.copy(isListening = false, lastError = msg)
        notifyError(msg)
        abandonAudioFocus()
        scope.launch {
            delay(2000)
            sessionMutex.withLock {
                if (_micState.value == MicrophoneState.ERROR) {
                    updateState(MicrophoneState.IDLE)
                }
            }
        }
    }

    private suspend fun startAudioRecordInternal() {
        // Ensure SpeechRecognizer is completely destroyed before starting AudioRecord
        cleanupSpeechRecognizerSync()

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
                    audioRecord.release()
                    throw IllegalStateException("Failed to initialize AudioRecord on any audio source")
                }

                audioRecord.startRecording()
                if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.release()
                    throw IllegalStateException("AudioRecord failed to start recording (state=${audioRecord.recordingState})")
                }

                activeAudioRecord = audioRecord

                // ONLY update to LISTENING once hardware actually confirms recording!
                updateState(MicrophoneState.LISTENING)
                _status.value = _status.value.copy(isListening = true, lastError = "None")
                withContext(Dispatchers.Main) {
                    notifyListeningStarted()
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
                            Log.w(TAG, "activeOnAudioCaptured error: ${e.message}")
                        }

                        // Dispatch to Gemini Live frame subscribers if any
                        val subscribersCopy = synchronized(frameSubscribers) { frameSubscribers.toList() }
                        for (sub in subscribersCopy) {
                            try { sub(frame, SAMPLE_RATE) } catch (_: Exception) {}
                        }

                        // RMS calculation
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

                        // VAD check
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

                        if (System.currentTimeMillis() - sessionStartTime > 18000L) {
                            break
                        }
                    } else if (read < 0) {
                        Log.w(TAG, "AudioRecord read returned error code: $read")
                        break
                    }
                }

                // Release AudioRecord safely
                try {
                    if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord.stop()
                    }
                    audioRecord.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
                } finally {
                    activeAudioRecord = null
                }

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
                            notifyResult(text)
                        }
                        if (isContinuousListeningRequested && pauseReferenceCount.get() == 0 && !_isMuted.value) {
                            scheduleContinuousReArmAfterUtterance(delayMs = 2500L)
                        } else {
                            abandonAudioFocus()
                            updateState(MicrophoneState.IDLE)
                        }
                    } else if (isContinuousListeningRequested && pauseReferenceCount.get() == 0 && !_isMuted.value) {
                        scheduleSafeRearm(delayMs = 250L)
                    } else {
                        abandonAudioFocus()
                        updateState(MicrophoneState.IDLE)
                        withContext(Dispatchers.Main) {
                            notifyResult("")
                        }
                    }
                } else if (isContinuousListeningRequested && pauseReferenceCount.get() == 0 && !_isMuted.value) {
                    scheduleSafeRearm(delayMs = 250L)
                } else {
                    abandonAudioFocus()
                    updateState(MicrophoneState.IDLE)
                }

            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord capture loop error: ${e.message}", e)
                try {
                    audioRecord?.let {
                        if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            it.stop()
                        }
                        it.release()
                    }
                } catch (_: Exception) {}
                activeAudioRecord = null

                if (isContinuousListeningRequested && pauseReferenceCount.get() == 0 && !_isMuted.value) {
                    scheduleSafeRearm(delayMs = 800L)
                } else {
                    abandonAudioFocus()
                    updateState(MicrophoneState.ERROR)
                    withContext(Dispatchers.Main) {
                        notifyError(e.message ?: "Audio capture error")
                    }
                    delay(2000)
                    sessionMutex.withLock {
                        if (_micState.value == MicrophoneState.ERROR) {
                            updateState(MicrophoneState.IDLE)
                        }
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
                if (isContinuousListeningRequested && pauseReferenceCount.get() == 0 && !_isMuted.value) {
                    if (!isHardwareActuallyActive(activeCaptureMode)) {
                        startPhysicalCaptureSession()
                    }
                }
            }
        }
    }

    private fun scheduleContinuousReArmAfterUtterance(delayMs: Long) {
        rearmJob?.cancel()
        rearmJob = scope.launch {
            delay(delayMs)
            sessionMutex.withLock {
                if (isContinuousListeningRequested && pauseReferenceCount.get() == 0 && !_isMuted.value) {
                    if (!isHardwareActuallyActive(activeCaptureMode)) {
                        Log.d(TAG, "Continuous listening re-arming after user utterance window")
                        startPhysicalCaptureSession()
                    }
                }
            }
        }
    }

    private fun destroySpeechRecognizerDirect() {
        currentSessionId++
        try {
            activeSpeechRecognizer?.let {
                it.setRecognitionListener(null)
                it.stopListening()
                it.cancel()
                it.destroy()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying SpeechRecognizer: ${e.message}")
        } finally {
            activeSpeechRecognizer = null
        }
    }

    private suspend fun cleanupSpeechRecognizerSync() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroySpeechRecognizerDirect()
        } else {
            withContext(Dispatchers.Main) {
                destroySpeechRecognizerDirect()
            }
        }
    }

    private suspend fun stopPhysicalHardwareSync() {
        try {
            activeAudioRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
        } finally {
            activeAudioRecord = null
        }

        cleanupSpeechRecognizerSync()
    }

    private fun updateState(newState: MicrophoneState) {
        _micState.value = newState
        val mappedVoiceState = when (newState) {
            MicrophoneState.IDLE -> VoiceState.IDLE
            MicrophoneState.REQUESTING_PERMISSION -> VoiceState.IDLE
            MicrophoneState.STARTING -> VoiceState.IDLE
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
