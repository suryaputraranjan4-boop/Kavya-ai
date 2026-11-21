package com.example.agent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.ai.KavyaAI
import com.example.utils.AppPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/**
 * Microphone Engine for Kavya AI.
 *
 * GUARANTEE: This class is the SOLE physical owner of [AudioRecord] in the process.
 * Exactly ONE AudioRecord instance exists at any time. Continuous PCM capture runs
 * without stopping or recreating sessions between sentences or TTS playback.
 */
class MicrophoneEngine private constructor(private val appContext: Context) {

    enum class State {
        IDLE,
        STARTING,
        LISTENING,
        SPEECH_DETECTED,
        PROCESSING,
        TTS_PLAYBACK,
        MUTED,
        SLEEPING,
        STOPPING,
        ERROR
    }

    /**
     * Explicit Google Assistant-style wake-word & command state machine (Phase 8).
     */
    enum class WakeWordAssistantState {
        OFF,
        SLEEPING_WAKE_WORD_LISTENING,
        WAKE_WORD_DETECTED,
        ASSISTANT_ACTIVATED,
        COMMAND_LISTENING,
        PROCESSING,
        SPEAKING,
        ERROR_RECOVERY
    }

    companion object {
        private const val TAG = "KavyaMicEngine"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_CONSECUTIVE_READ_ERRORS = 3

        @Volatile private var instance: MicrophoneEngine? = null

        fun getInstance(context: Context): MicrophoneEngine {
            return instance ?: synchronized(this) {
                instance ?: MicrophoneEngine(context.applicationContext).also { instance = it }
            }
        }

        fun getExistingInstance(): MicrophoneEngine? = instance
    }

    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mutex = Mutex()

    // State Flows
    private val _engineState = MutableStateFlow(State.IDLE)
    val engineState: StateFlow<State> = _engineState.asStateFlow()

    private val _amplitude = MutableStateFlow(0.0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _lastTranscript = MutableStateFlow("")
    val lastTranscript: StateFlow<String> = _lastTranscript.asStateFlow()

    private val _lastVoiceError = MutableStateFlow<com.example.ai.VoiceInputErrorCategory?>(null)
    val lastVoiceError: StateFlow<com.example.ai.VoiceInputErrorCategory?> = _lastVoiceError.asStateFlow()

    private val _lastAsrDiagnosticMessage = MutableStateFlow<String?>(null)
    val lastAsrDiagnosticMessage: StateFlow<String?> = _lastAsrDiagnosticMessage.asStateFlow()

    @Volatile
    private var sessionLanguageHint: String? = null

    private val _assistantState = MutableStateFlow(WakeWordAssistantState.OFF)
    val assistantState: StateFlow<WakeWordAssistantState> = _assistantState.asStateFlow()

    private val _isAssistantPopupVisible = MutableStateFlow(false)
    val isAssistantPopupVisible: StateFlow<Boolean> = _isAssistantPopupVisible.asStateFlow()

    // Audio Engine Components
    private val vad = VoiceActivityDetector(sampleRate = SAMPLE_RATE)
    private val pcmProcessor = PcmFrameProcessor(vad)

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var acousticEchoCanceler: AcousticEchoCanceler? = null
    @Volatile private var noiseSuppressor: NoiseSuppressor? = null
    private val kavyaAI by lazy { KavyaAI(appContext) }
    private var recordingJob: Job? = null
    @Volatile private var isRecordingActive = false
    @Volatile private var isMuted = false
    @Volatile private var isSleeping = false
    @Volatile private var isAssistantSpeaking = false
    @Volatile private var isSilencedByOs = false
    @Volatile private var audioRecordingCallbackRegistered = false
    private var audioRecordingCallback: AudioManager.AudioRecordingCallback? = null

    @Volatile private var lastAcceptedTranscript: String = ""
    @Volatile private var lastAcceptedTranscriptTimeMs: Long = 0L

    private var commandListener: ((String) -> Unit)? = null
    private var userInterruptionListener: (() -> Unit)? = null
    private var voiceErrorListener: ((com.example.ai.VoiceInputErrorCategory) -> Unit)? = null
    private var wakeWordActivationListener: ((String, String?) -> Unit)? = null

    val wakeWords = setOf(
        "hey kavya", "hi kavya", "hello kavya",
        "wake kavya", "wake up kavya", "kavya wake up",
        "utho kavya", "kavya utho", "ok kavya", "okay kavya",
        "kavya suno", "suno kavya", "hey kavia", "namaste kavya",
        "हे काव्या", "हाय काव्या", "हेलो काव्या", "ओके काव्या",
        "काव्या सुनो", "सुनो काव्या", "नमस्ते काव्या", "उठो काव्या", "काव्या उठो"
    )

    val sleepWords = setOf(
        "sleep kavya", "kavya sleep", "go to sleep kavya", "go to sleep",
        "kavya go to sleep", "sleep kavia",
        "so jao kavya", "kavya so jao",
        "सो जाओ काव्या", "काव्या सो जाओ"
    )

    val stopWords = setOf(
        "stop", "stop kavya", "kavya stop", "stop kavia",
        "kavya chup", "chup ho jao kavya",
        "ruko kavya", "kavya ruko",
        "रुको काव्या", "काव्या रुको", "चुप हो जाओ काव्या"
    )

    init {
        // Collect amplitude for UI
        engineScope.launch {
            pcmProcessor.amplitudeFlow.collect { amp ->
                _amplitude.value = amp
            }
        }

        // Wire VAD speech start event
        vad.onSpeechStart = {
            if (_engineState.value == State.LISTENING) {
                _engineState.value = State.SPEECH_DETECTED
                _assistantState.value = WakeWordAssistantState.COMMAND_LISTENING
            }
        }

        // Wire VAD user interruption event (full-duplex playback cancellation)
        vad.onUserInterruption = {
            if (isAssistantSpeaking) {
                Log.i(TAG, "User speech interruption detected during assistant playback. Stopping TTS!")
                setAssistantSpeaking(false)
                _engineState.value = State.SPEECH_DETECTED
                _assistantState.value = WakeWordAssistantState.COMMAND_LISTENING
                userInterruptionListener?.invoke()
            }
        }

        // Wire VAD utterance completion
        pcmProcessor.onUtteranceCompleted = { pcmData ->
            handleCompletedUtterance(pcmData)
        }
    }

    fun setWakeWordActivationListener(listener: ((wakePhrase: String, inlineCommand: String?) -> Unit)?) {
        this.wakeWordActivationListener = listener
    }

    fun activateAssistantPopup() {
        _isAssistantPopupVisible.value = true
        setSleeping(false)
        _assistantState.value = WakeWordAssistantState.ASSISTANT_ACTIVATED
    }

    fun dismissAssistantPopup() {
        _isAssistantPopupVisible.value = false
    }

    fun setCommandListener(listener: (String) -> Unit) {
        this.commandListener = listener
    }

    fun setUserInterruptionListener(listener: (() -> Unit)?) {
        this.userInterruptionListener = listener
    }

    fun setVoiceErrorListener(listener: ((com.example.ai.VoiceInputErrorCategory) -> Unit)?) {
        this.voiceErrorListener = listener
    }

    fun setSessionLanguageHint(languageTag: String?) {
        sessionLanguageHint = languageTag?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun reportVoiceError(category: com.example.ai.VoiceInputErrorCategory) {
        com.example.ai.AiErrorClassifier.recordVoiceError(category)
        _lastVoiceError.value = category
        voiceErrorListener?.invoke(category)
    }

    /**
     * Registers a consumer for live raw PCM frames (e.g. Gemini Live).
     */
    fun registerFrameSubscriber(id: String, subscriber: (ByteArray) -> Unit) {
        pcmProcessor.registerSubscriber(id, subscriber)
    }

    /**
     * Unregisters a live PCM frame consumer.
     */
    fun unregisterFrameSubscriber(id: String) {
        pcmProcessor.unregisterSubscriber(id)
    }

    /**
     * Controls assistant TTS speaking state for full-duplex barge-in.
     * Keeps physical AudioRecord alive and elevates VAD threshold instead of gating frames,
     * allowing user speech to interrupt TTS playback immediately.
     */
    fun setAssistantSpeaking(speaking: Boolean) {
        isAssistantSpeaking = speaking
        pcmProcessor.setAssistantSpeaking(speaking)
        if (speaking) {
            _engineState.value = State.TTS_PLAYBACK
            _assistantState.value = WakeWordAssistantState.SPEAKING
        } else {
            _engineState.value = if (isRecordingActive) {
                if (isSleeping) State.SLEEPING else State.LISTENING
            } else {
                State.IDLE
            }
            _assistantState.value = if (isRecordingActive) {
                if (isSleeping) WakeWordAssistantState.SLEEPING_WAKE_WORD_LISTENING else WakeWordAssistantState.COMMAND_LISTENING
            } else {
                WakeWordAssistantState.OFF
            }
        }
    }

    /**
     * Deterministically feeds a raw 16kHz 16-bit mono PCM frame into the engine pipeline.
     * Used by unit/Robolectric tests to validate full-duplex barge-in and utterance segmentation
     * even when physical AudioRecord is unavailable on JVM.
     */
    fun injectPcmFrameForTest(pcmData: ByteArray, length: Int = pcmData.size) {
        if (!isMuted) {
            pcmProcessor.processRawFrame(pcmData, length)
        }
    }

    fun setSleeping(sleeping: Boolean) {
        isSleeping = sleeping
        AppPreferences.setKavyaState(appContext, if (sleeping) "SLEEP" else "ACTIVE")
        if (sleeping) {
            _isAssistantPopupVisible.value = false
            _assistantState.value = WakeWordAssistantState.SLEEPING_WAKE_WORD_LISTENING
        } else {
            _assistantState.value = WakeWordAssistantState.COMMAND_LISTENING
        }
        if (isRecordingActive && !isAssistantSpeaking) {
            _engineState.value = if (sleeping) State.SLEEPING else State.LISTENING
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        pcmProcessor.setPlaybackGated(muted)
        if (muted) {
            _assistantState.value = WakeWordAssistantState.OFF
        } else if (isRecordingActive) {
            _assistantState.value = if (isSleeping) {
                WakeWordAssistantState.SLEEPING_WAKE_WORD_LISTENING
            } else {
                WakeWordAssistantState.COMMAND_LISTENING
            }
        }
        if (isRecordingActive) {
            _engineState.value = if (muted) State.MUTED else (if (isSleeping) State.SLEEPING else State.LISTENING)
        }
    }

    private val stateLock = Any()

    /**
     * Starts continuous physical microphone capture. Idempotent & thread-safe.
     */
    fun startListening(): Boolean {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Cannot start MicrophoneEngine: RECORD_AUDIO permission not granted")
            reportVoiceError(com.example.ai.VoiceInputErrorCategory.MIC_PERMISSION_DENIED)
            _engineState.value = State.ERROR
            return false
        }

        synchronized(stateLock) {
            if (isRecordingActive) {
                val currentRecord = audioRecord
                val alreadyRecording = currentRecord != null &&
                    currentRecord.state == AudioRecord.STATE_INITIALIZED &&
                    currentRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING
                if (alreadyRecording) {
                    Log.d(TAG, "MicrophoneEngine already active. Skipping duplicate start.")
                    return true
                }
                isRecordingActive = false
                recordingJob?.cancel()
                recordingJob = null
                // Release a stale handle before attempting a fresh initialization.
                releaseAudioEffects()
                audioRecord?.let { stale ->
                    try {
                        if (stale.recordingState == AudioRecord.RECORDSTATE_RECORDING) stale.stop()
                        stale.release()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error releasing stale AudioRecord before restart", e)
                    }
                }
                audioRecord = null
            }

            _engineState.value = State.STARTING
            if (!initializeAudioRecord()) {
                Log.e(TAG, "Failed to initialize physical AudioRecord instance")
                _engineState.value = State.ERROR
                isRecordingActive = false
                return false
            }

            isRecordingActive = true
            isSleeping = AppPreferences.getKavyaState(appContext).equals("SLEEP", ignoreCase = true)
            _engineState.value = if (isSleeping) State.SLEEPING else State.LISTENING
            _assistantState.value = if (isSleeping) {
                WakeWordAssistantState.SLEEPING_WAKE_WORD_LISTENING
            } else {
                WakeWordAssistantState.COMMAND_LISTENING
            }
            startRecordingLoop()
            return true
        }
    }

    /**
     * Stops continuous physical microphone capture safely without blocking caller threads.
     */
    fun stopListening() {
        synchronized(stateLock) {
            if (!isRecordingActive && audioRecord == null) {
                isAssistantSpeaking = false
                pcmProcessor.setAssistantSpeaking(false)
                pcmProcessor.reset()
                _engineState.value = State.IDLE
                _assistantState.value = WakeWordAssistantState.OFF
                _isAssistantPopupVisible.value = false
                _amplitude.value = 0.0f
                return
            }
            _engineState.value = State.STOPPING
            isRecordingActive = false
            val currentJob = recordingJob
            recordingJob = null
            currentJob?.cancel()

            val recordToRelease = audioRecord
            audioRecord = null
            releaseAudioEffects()

            try {
                recordToRelease?.let { record ->
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        record.stop()
                    }
                    record.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping AudioRecord", e)
            } finally {
                pcmProcessor.reset()
                _engineState.value = State.IDLE
                _assistantState.value = WakeWordAssistantState.OFF
                _isAssistantPopupVisible.value = false
                _amplitude.value = 0.0f
                Log.i(TAG, "Physical AudioRecord session stopped and released.")
            }
        }
    }

    private fun releaseAudioEffects() {
        try {
            acousticEchoCanceler?.release()
        } catch (_: Exception) {}
        acousticEchoCanceler = null
        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {}
        noiseSuppressor = null
    }

    @SuppressLint("MissingPermission")
    private fun initializeAudioRecord(): Boolean {
        var candidate: AudioRecord? = null
        return try {
            val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minBufSize <= 0) {
                Log.e(TAG, "Unsupported AudioRecord configuration (minBufferSize=$minBufSize)")
                reportVoiceError(com.example.ai.VoiceInputErrorCategory.UNSUPPORTED_CONFIGURATION)
                return false
            }
            val bufferSize = maxOf(minBufSize * 2, 8192)

            candidate = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (candidate.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize (state=${candidate.state})")
                reportVoiceError(com.example.ai.VoiceInputErrorCategory.AUDIO_INIT_FAILURE)
                candidate.release()
                false
            } else {
                releaseAudioEffects()
                try {
                    if (AcousticEchoCanceler.isAvailable()) {
                        acousticEchoCanceler = AcousticEchoCanceler.create(candidate.audioSessionId)?.apply {
                            enabled = true
                        }
                        Log.d(TAG, "AcousticEchoCanceler enabled on session ${candidate.audioSessionId}")
                    }
                    if (NoiseSuppressor.isAvailable()) {
                        noiseSuppressor = NoiseSuppressor.create(candidate.audioSessionId)?.apply {
                            enabled = true
                        }
                        Log.d(TAG, "NoiseSuppressor enabled on session ${candidate.audioSessionId}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not enable audio effects on session ${candidate.audioSessionId}", e)
                }

                candidate.startRecording()
                if (candidate.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    Log.e(TAG, "AudioRecord failed to start recording")
                    reportVoiceError(com.example.ai.VoiceInputErrorCategory.MIC_UNAVAILABLE)
                    releaseAudioEffects()
                    candidate.release()
                    false
                } else {
                    audioRecord = candidate
                    isSilencedByOs = false
                    registerAudioRecordingCallbackIfNeeded()
                    _lastVoiceError.value = null
                    Log.i(TAG, "Physical AudioRecord session successfully started (bufferSize=$bufferSize)")
                    true
                }
            }
        } catch (e: Exception) {
            try {
                candidate?.release()
            } catch (releaseError: Exception) {
                Log.w(TAG, "Failed to release partially initialized AudioRecord", releaseError)
            }
            reportVoiceError(com.example.ai.VoiceInputErrorCategory.AUDIO_INIT_FAILURE)
            Log.e(TAG, "Exception initializing AudioRecord", e)
            false
        }
    }

    private fun registerAudioRecordingCallbackIfNeeded() {
        if (audioRecordingCallbackRegistered || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        try {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val cb = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                    val activeSessionId = audioRecord?.audioSessionId ?: return
                    val mine = configs?.firstOrNull { it.clientAudioSessionId == activeSessionId }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mine != null) {
                        val silenced = mine.isClientSilenced
                        if (silenced != isSilencedByOs) {
                            isSilencedByOs = silenced
                            Log.w(TAG, "Android OS mic silenced state changed: silenced=$silenced (session=$activeSessionId)")
                            if (!silenced && isRecordingActive) {
                                // OS returned mic focus (e.g. call or other app finished) — reset VAD baseline cleanly
                                vad.reset()
                            }
                        }
                    }
                }
            }
            am.registerAudioRecordingCallback(cb, null)
            audioRecordingCallback = cb
            audioRecordingCallbackRegistered = true
        } catch (t: Throwable) {
            Log.w(TAG, "Could not register AudioRecordingCallback: ${t.message}")
        }
    }

    /**
     * Called from MainActivity.onResume() when the user switches back from another app or reopens Kavya.
     * Recovers cleanly if Android silenced or preempted the microphone while backgrounded.
     */
    fun ensureHealthyOnForeground(): Boolean {
        if (!isRecordingActive && _engineState.value != State.ERROR) return false
        val currentRecord = audioRecord
        val needsRecovery = isSilencedByOs ||
            _engineState.value == State.ERROR ||
            currentRecord == null ||
            currentRecord.state != AudioRecord.STATE_INITIALIZED ||
            currentRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING ||
            recordingJob?.isActive != true
        if (!needsRecovery) return true

        Log.i(TAG, "Foreground mic health check detected silenced/stale AudioRecord — recovering cleanly...")
        isSilencedByOs = false
        vad.reset()
        return if (isRecordingActive) {
            engineScope.launch {
                val ok = recoverAudioRecord(currentRecord)
                if (ok && recordingJob?.isActive != true) {
                    startRecordingLoop()
                }
            }
            true
        } else if (AppPreferences.isMicEnabled(appContext)) {
            startListening()
        } else {
            false
        }
    }

    private fun startRecordingLoop() {
        recordingJob = engineScope.launch(Dispatchers.IO) {
            val readBuffer = ByteArray(2048)
            var consecutiveReadErrors = 0
            var consecutiveAllZeroFrames = 0
            Log.d(TAG, "Entering continuous PCM audio capture loop...")

            while (isActive && isRecordingActive) {
                val record = audioRecord
                if (record == null || record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    if (!isRecordingActive) break
                    Log.w(TAG, "AudioRecord lost recording state. Attempting controlled recovery...")
                    if (!recoverAudioRecord(record)) break
                    consecutiveReadErrors = 0
                    consecutiveAllZeroFrames = 0
                    continue
                }

                try {
                    val bytesRead = record.read(readBuffer, 0, readBuffer.size)
                    if (!isRecordingActive) break

                    if (bytesRead > 0) {
                        consecutiveReadErrors = 0
                        // Detect Android 11+ OS background mic silencing (all-zero PCM frames)
                        var nonZeroFound = false
                        var i = 0
                        while (i < bytesRead) {
                            if (readBuffer[i].toInt() != 0) {
                                nonZeroFound = true
                                break
                            }
                            i += 4
                        }
                        if (!nonZeroFound) {
                            consecutiveAllZeroFrames++
                            if (consecutiveAllZeroFrames == 45) { // ~2.8s of pure digital zero
                                isSilencedByOs = true
                                Log.w(TAG, "Detected sustained all-zero PCM frames (OS mic silencing/preemption).")
                            }
                        } else {
                            if (isSilencedByOs) {
                                isSilencedByOs = false
                                vad.reset()
                            }
                            consecutiveAllZeroFrames = 0
                        }

                        if (!isMuted && nonZeroFound) {
                            pcmProcessor.processRawFrame(readBuffer, bytesRead)
                        }
                    } else if (bytesRead == 0) {
                        delay(15)
                    } else {
                        consecutiveReadErrors++
                        Log.e(TAG, "AudioRecord read error code: $bytesRead ($consecutiveReadErrors consecutive)")
                        if (consecutiveReadErrors >= MAX_CONSECUTIVE_READ_ERRORS) {
                            if (!recoverAudioRecord(record)) break
                            consecutiveReadErrors = 0
                            consecutiveAllZeroFrames = 0
                        } else {
                            delay(100)
                        }
                    }
                } catch (e: Exception) {
                    if (!isRecordingActive) break
                    Log.w(TAG, "Safe unblock during AudioRecord read: ${e.message}")
                    delay(50)
                }
            }
            Log.d(TAG, "Exited PCM audio capture loop.")
        }
    }

    /**
     * Recreates the hardware capture handle only after a persistent read/state failure.
     * Normal utterance boundaries and TTS playback never stop or recreate AudioRecord.
     */
    private suspend fun recoverAudioRecord(failedRecord: AudioRecord?): Boolean {
        return withContext(Dispatchers.Default) {
            synchronized(stateLock) {
                if (!isRecordingActive) return@synchronized false

                // Another lifecycle action may already have replaced or stopped this handle.
                if (failedRecord != null && audioRecord !== failedRecord) {
                    val current = audioRecord
                    return@synchronized current != null &&
                        current.state == AudioRecord.STATE_INITIALIZED &&
                        current.recordingState == AudioRecord.RECORDSTATE_RECORDING
                }

                val oldRecord = audioRecord
                audioRecord = null
                releaseAudioEffects()
                try {
                    oldRecord?.let { record ->
                        if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
                        record.release()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing failed AudioRecord before recovery", e)
                }

                Log.w(TAG, "Reinitializing AudioRecord after persistent capture failure...")
                _assistantState.value = WakeWordAssistantState.ERROR_RECOVERY
                if (initializeAudioRecord()) {
                    _engineState.value = when {
                        isAssistantSpeaking -> State.TTS_PLAYBACK
                        isMuted -> State.MUTED
                        isSleeping -> State.SLEEPING
                        else -> State.LISTENING
                    }
                    _assistantState.value = when {
                        isAssistantSpeaking -> WakeWordAssistantState.SPEAKING
                        isMuted -> WakeWordAssistantState.OFF
                        isSleeping -> WakeWordAssistantState.SLEEPING_WAKE_WORD_LISTENING
                        else -> WakeWordAssistantState.COMMAND_LISTENING
                    }
                    Log.i(TAG, "AudioRecord recovery succeeded; continuous capture resumed.")
                    true
                } else {
                    isRecordingActive = false
                    _engineState.value = State.ERROR
                    _assistantState.value = WakeWordAssistantState.ERROR_RECOVERY
                    _amplitude.value = 0.0f
                    Log.e(TAG, "AudioRecord recovery failed. Capture stopped safely; explicit restart is required.")
                    false
                }
            }
        }
    }

    private fun handleCompletedUtterance(pcmData: ByteArray) {
        engineScope.launch(Dispatchers.IO) {
            val acousticConfidence = vad.computeUtteranceConfidence(pcmData)
            if (acousticConfidence < 0.30f) {
                Log.d(TAG, "Discarded silent/ambient utterance before STT (acousticConfidence=${"%.2f".format(acousticConfidence)})")
                if (isRecordingActive && !isAssistantSpeaking) {
                    _engineState.value = if (isSleeping) State.SLEEPING else State.LISTENING
                }
                return@launch
            }

            if (_engineState.value == State.SPEECH_DETECTED || _engineState.value == State.LISTENING) {
                _engineState.value = State.PROCESSING
                _assistantState.value = WakeWordAssistantState.PROCESSING
            }

            try {
                Log.d(TAG, "Transcribing captured utterance (${pcmData.size} bytes, conf=${"%.2f".format(acousticConfidence)})...")
                val transcript = kavyaAI.transcribeAudio(
                    pcmData = pcmData,
                    sampleRate = SAMPLE_RATE,
                    languageHint = sessionLanguageHint
                )
                _lastAsrDiagnosticMessage.value = kavyaAI.lastTranscriptionStatusMessage
                processRecognizedTranscript(transcript, confidence = acousticConfidence)
            } catch (e: Exception) {
                _assistantState.value = WakeWordAssistantState.ERROR_RECOVERY
                _lastAsrDiagnosticMessage.value = e.localizedMessage ?: "Speech recognition error"
                reportVoiceError(com.example.ai.VoiceInputErrorCategory.TRANSCRIPTION_SERVICE_ERROR)
                Log.e(TAG, "Error processing utterance transcript", e)
            } finally {
                if (isRecordingActive && !isAssistantSpeaking) {
                    _engineState.value = if (isSleeping) State.SLEEPING else State.LISTENING
                    if (_assistantState.value != WakeWordAssistantState.ASSISTANT_ACTIVATED) {
                        _assistantState.value = if (isSleeping) {
                            WakeWordAssistantState.SLEEPING_WAKE_WORD_LISTENING
                        } else {
                            WakeWordAssistantState.COMMAND_LISTENING
                        }
                    }
                }
            }
        }
    }

    /**
     * Deterministic transcript & wake-word state machine processor.
     * Supports English, Hinglish, and Hindi wake words, inline wake+command execution,
     * sleep/stop commands, confidence gating, and duplicate transcript suppression.
     */
    fun processRecognizedTranscript(
        rawTranscript: String?,
        confidence: Float = 1.0f,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!com.example.utils.SpeechSanitizer.isValidCommandTranscript(rawTranscript, confidence)) {
            val recordedErr = com.example.ai.AiErrorClassifier.lastVoiceInputError
                ?: com.example.ai.VoiceInputErrorCategory.EMPTY_TRANSCRIPTION
            reportVoiceError(recordedErr)
            return false
        }

        val cleanText = com.example.utils.SpeechSanitizer.sanitize(rawTranscript)
        if (cleanText.isBlank()) return false

        // Suppress duplicate consecutive transcripts within 2.2s window
        val elapsedSinceLast = nowMs - lastAcceptedTranscriptTimeMs
        if (com.example.utils.SpeechSanitizer.isDuplicateTranscript(cleanText, lastAcceptedTranscript, elapsedSinceLast)) {
            Log.d(TAG, "Suppressed duplicate consecutive transcript: \"$cleanText\" (${elapsedSinceLast}ms)")
            return false
        }
        lastAcceptedTranscript = cleanText
        lastAcceptedTranscriptTimeMs = nowMs

        _lastVoiceError.value = null
        Log.i(TAG, "Recognized utterance transcript: \"$cleanText\"")
        _lastTranscript.value = cleanText

        val lowerText = cleanText.lowercase(Locale.ROOT)
        val matchedWake = wakeWords.firstOrNull { lowerText.contains(it) }
        val isWake = matchedWake != null
        val isSleep = sleepWords.any { lowerText.contains(it) }
        val isStop = stopWords.any { lowerText == it || lowerText.contains(it) }

        if (isSleeping || _assistantState.value == WakeWordAssistantState.SLEEPING_WAKE_WORD_LISTENING) {
            if (isWake) {
                Log.i(TAG, "Wake word detected in SLEEP mode (\"$matchedWake\"). Waking up Kavya!")
                _assistantState.value = WakeWordAssistantState.WAKE_WORD_DETECTED
                isSleeping = false
                AppPreferences.setKavyaState(appContext, "ACTIVE")
                _isAssistantPopupVisible.value = true
                _assistantState.value = WakeWordAssistantState.ASSISTANT_ACTIVATED

                val remainingPrompt = stripKeywords(cleanText, wakeWords)
                wakeWordActivationListener?.invoke(matchedWake ?: "hey kavya", remainingPrompt.takeIf { it.isNotBlank() })

                if (remainingPrompt.isNotBlank()) {
                    _assistantState.value = WakeWordAssistantState.PROCESSING
                    commandListener?.invoke(remainingPrompt)
                } else {
                    _assistantState.value = WakeWordAssistantState.COMMAND_LISTENING
                    commandListener?.invoke("hey kavya")
                }
                if (isRecordingActive && !isAssistantSpeaking) {
                    _engineState.value = State.LISTENING
                }
                return true
            } else {
                Log.d(TAG, "Ignored non-wake utterance in SLEEP mode: \"$cleanText\"")
                _assistantState.value = WakeWordAssistantState.SLEEPING_WAKE_WORD_LISTENING
                return false
            }
        } else {
            if (isSleep) {
                Log.i(TAG, "Sleep command detected. Putting Kavya to sleep.")
                setSleeping(true)
                return true
            } else if (isStop && !isWake) {
                Log.i(TAG, "Stop command detected.")
                setAssistantSpeaking(false)
                _assistantState.value = WakeWordAssistantState.COMMAND_LISTENING
                commandListener?.invoke("stop")
                return true
            } else if (isWake) {
                // User said "Hey Kavya" or "Hey Kavya <command>" while already awake
                _assistantState.value = WakeWordAssistantState.WAKE_WORD_DETECTED
                _isAssistantPopupVisible.value = true
                _assistantState.value = WakeWordAssistantState.ASSISTANT_ACTIVATED
                val remainingPrompt = stripKeywords(cleanText, wakeWords)
                wakeWordActivationListener?.invoke(matchedWake ?: "hey kavya", remainingPrompt.takeIf { it.isNotBlank() })
                if (remainingPrompt.isNotBlank()) {
                    _assistantState.value = WakeWordAssistantState.PROCESSING
                    commandListener?.invoke(remainingPrompt)
                } else {
                    _assistantState.value = WakeWordAssistantState.COMMAND_LISTENING
                    commandListener?.invoke("hey kavya")
                }
                return true
            } else {
                _assistantState.value = WakeWordAssistantState.PROCESSING
                commandListener?.invoke(cleanText)
                return true
            }
        }
    }

    fun resetTranscriptDeduplicationForTesting() {
        lastAcceptedTranscript = ""
        lastAcceptedTranscriptTimeMs = 0L
    }

    private fun stripKeywords(text: String, keywords: Set<String>): String {
        var result = text
        // Sort longest first so multi-word phrases ("hey kavya") strip cleanly before shorter substrings
        for (kw in keywords.sortedByDescending { it.length }) {
            result = result.replace(Regex("(?i)${Regex.escape(kw)}"), " ")
        }
        return result.replace(Regex("\\s+"), " ")
            .trim { it.isWhitespace() || it in setOf(',', '.', '!', '?', '-', ':') }
    }
}
