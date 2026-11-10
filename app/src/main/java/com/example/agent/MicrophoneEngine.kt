package com.example.agent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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

    // Audio Engine Components
    private val vad = VoiceActivityDetector(sampleRate = SAMPLE_RATE)
    private val pcmProcessor = PcmFrameProcessor(vad)

    @Volatile private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    @Volatile private var isRecordingActive = false
    @Volatile private var isMuted = false
    @Volatile private var isSleeping = false
    @Volatile private var isAssistantSpeaking = false

    private var commandListener: ((String) -> Unit)? = null

    private val wakeWords = setOf(
        "hey kavya", "hi kavya", "hello kavya",
        "wake kavya", "wake up kavya", "kavya wake up",
        "utho kavya", "kavya utho", "ok kavya", "okay kavya",
        "kavya suno", "suno kavya", "hey kavia"
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
            }
        }

        // Wire VAD utterance completion
        pcmProcessor.onUtteranceCompleted = { pcmData ->
            handleCompletedUtterance(pcmData)
        }
    }

    fun setCommandListener(listener: (String) -> Unit) {
        this.commandListener = listener
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
     * Controls assistant TTS speaking gate.
     * Keeps physical AudioRecord alive while suppressing VAD speech detection.
     */
    fun setAssistantSpeaking(speaking: Boolean) {
        isAssistantSpeaking = speaking
        pcmProcessor.setPlaybackGated(speaking)
        if (speaking) {
            _engineState.value = State.TTS_PLAYBACK
            _amplitude.value = 0.0f
        } else {
            _engineState.value = if (isRecordingActive) {
                if (isSleeping) State.SLEEPING else State.LISTENING
            } else {
                State.IDLE
            }
        }
    }

    fun setSleeping(sleeping: Boolean) {
        isSleeping = sleeping
        AppPreferences.setKavyaState(appContext, if (sleeping) "SLEEP" else "ACTIVE")
        if (isRecordingActive && !isAssistantSpeaking) {
            _engineState.value = if (sleeping) State.SLEEPING else State.LISTENING
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        pcmProcessor.setPlaybackGated(muted)
        if (isRecordingActive) {
            _engineState.value = if (muted) State.MUTED else (if (isSleeping) State.SLEEPING else State.LISTENING)
        }
    }

    /**
     * Starts continuous physical microphone capture. Idempotent & thread-safe.
     */
    fun startListening(): Boolean {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Cannot start MicrophoneEngine: RECORD_AUDIO permission not granted")
            _engineState.value = State.ERROR
            return false
        }

        var started = false
        runBlocking {
            mutex.withLock {
                if (isRecordingActive) {
                    val currentRecord = audioRecord
                    started = currentRecord != null &&
                        currentRecord.state == AudioRecord.STATE_INITIALIZED &&
                        currentRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING
                    if (started) {
                        Log.d(TAG, "MicrophoneEngine already active. Skipping duplicate start.")
                        return@withLock
                    }
                    isRecordingActive = false
                }

                _engineState.value = State.STARTING
                if (!initializeAudioRecord()) {
                    Log.e(TAG, "Failed to initialize physical AudioRecord instance")
                    _engineState.value = State.ERROR
                    isRecordingActive = false
                    return@withLock
                }

                isRecordingActive = true
                isSleeping = AppPreferences.getKavyaState(appContext).equals("SLEEP", ignoreCase = true)
                _engineState.value = if (isSleeping) State.SLEEPING else State.LISTENING
                startRecordingLoop()
                started = true
            }
        }
        return started
    }

    /**
     * Stops continuous physical microphone capture.
     */
    fun stopListening() {
        runBlocking {
            mutex.withLock {
                if (!isRecordingActive) return@runBlocking
                _engineState.value = State.STOPPING
                isRecordingActive = false
                recordingJob?.cancel()
                recordingJob = null

                try {
                    audioRecord?.let { record ->
                        if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            record.stop()
                        }
                        record.release()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping AudioRecord", e)
                } finally {
                    audioRecord = null
                    pcmProcessor.reset()
                    _engineState.value = State.IDLE
                    _amplitude.value = 0.0f
                    Log.i(TAG, "Physical AudioRecord session stopped and released.")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun initializeAudioRecord(): Boolean {
        return try {
            val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minBufSize <= 0) {
                Log.e(TAG, "Unsupported AudioRecord configuration (minBufferSize=$minBufSize)")
                return false
            }
            val bufferSize = maxOf(minBufSize * 2, 8192)

            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize (state=${record.state})")
                record.release()
                false
            } else {
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    Log.e(TAG, "AudioRecord failed to start recording")
                    record.release()
                    false
                } else {
                    audioRecord = record
                    Log.i(TAG, "Physical AudioRecord session successfully started (bufferSize=$bufferSize)")
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception initializing AudioRecord", e)
            false
        }
    }

    private fun startRecordingLoop() {
        recordingJob = engineScope.launch(Dispatchers.IO) {
            val readBuffer = ByteArray(2048)
            var consecutiveReadErrors = 0
            Log.d(TAG, "Entering continuous PCM audio capture loop...")

            while (isActive && isRecordingActive) {
                val record = audioRecord
                if (record == null || record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    Log.w(TAG, "AudioRecord lost recording state. Attempting controlled recovery...")
                    if (!recoverAudioRecord(record)) break
                    consecutiveReadErrors = 0
                    continue
                }

                val bytesRead = record.read(readBuffer, 0, readBuffer.size)
                if (bytesRead > 0) {
                    consecutiveReadErrors = 0
                    if (!isMuted && !isAssistantSpeaking) {
                        pcmProcessor.processRawFrame(readBuffer, bytesRead)
                    }
                } else if (bytesRead < 0) {
                    consecutiveReadErrors++
                    Log.e(TAG, "AudioRecord read error code: $bytesRead ($consecutiveReadErrors consecutive)")
                    if (consecutiveReadErrors >= MAX_CONSECUTIVE_READ_ERRORS) {
                        if (!recoverAudioRecord(record)) break
                        consecutiveReadErrors = 0
                    } else {
                        delay(100)
                    }
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
        return mutex.withLock {
            if (!isRecordingActive) return@withLock false

            // Another lifecycle action may already have replaced or stopped this handle.
            if (failedRecord != null && audioRecord !== failedRecord) {
                val current = audioRecord
                return@withLock current != null &&
                    current.state == AudioRecord.STATE_INITIALIZED &&
                    current.recordingState == AudioRecord.RECORDSTATE_RECORDING
            }

            val oldRecord = audioRecord
            audioRecord = null
            try {
                oldRecord?.let { record ->
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
                    record.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing failed AudioRecord before recovery", e)
            }

            Log.w(TAG, "Reinitializing AudioRecord after persistent capture failure...")
            if (initializeAudioRecord()) {
                _engineState.value = when {
                    isAssistantSpeaking -> State.TTS_PLAYBACK
                    isMuted -> State.MUTED
                    isSleeping -> State.SLEEPING
                    else -> State.LISTENING
                }
                Log.i(TAG, "AudioRecord recovery succeeded; continuous capture resumed.")
                true
            } else {
                isRecordingActive = false
                _engineState.value = State.ERROR
                _amplitude.value = 0.0f
                Log.e(TAG, "AudioRecord recovery failed. Capture stopped safely; explicit restart is required.")
                false
            }
        }
    }

    private fun handleCompletedUtterance(pcmData: ByteArray) {
        engineScope.launch(Dispatchers.IO) {
            if (_engineState.value == State.SPEECH_DETECTED || _engineState.value == State.LISTENING) {
                _engineState.value = State.PROCESSING
            }

            try {
                Log.d(TAG, "Transcribing captured utterance (${pcmData.size} bytes)...")
                val kavyaAI = KavyaAI(appContext)
                val transcript = kavyaAI.transcribeAudio(pcmData, SAMPLE_RATE)
                val cleanText = transcript.trim()

                if (cleanText.isNotBlank()) {
                    Log.i(TAG, "Recognized utterance transcript: \"$cleanText\"")
                    _lastTranscript.value = cleanText

                    val lowerText = cleanText.lowercase(Locale.ROOT)
                    val isWake = wakeWords.any { lowerText.contains(it) }
                    val isSleep = sleepWords.any { lowerText.contains(it) }
                    val isStop = stopWords.any { lowerText.contains(it) }

                    if (isSleeping) {
                        if (isWake) {
                            Log.i(TAG, "Wake word detected in SLEEP mode. Waking up Kavya!")
                            setSleeping(false)
                            val remainingPrompt = stripKeywords(cleanText, wakeWords)
                            if (remainingPrompt.isNotBlank()) {
                                commandListener?.invoke(remainingPrompt)
                            } else {
                                commandListener?.invoke("hey kavya")
                            }
                        } else {
                            Log.d(TAG, "Ignored utterance in SLEEP mode: \"$cleanText\"")
                        }
                    } else {
                        if (isSleep) {
                            Log.i(TAG, "Sleep command detected. Putting Kavya to sleep.")
                            setSleeping(true)
                        } else if (isStop) {
                            Log.i(TAG, "Stop command detected.")
                            commandListener?.invoke("stop")
                        } else {
                            // Forward recognized command to pipeline / ViewModel
                            commandListener?.invoke(cleanText)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing utterance transcript", e)
            } finally {
                if (isRecordingActive && !isAssistantSpeaking) {
                    _engineState.value = if (isSleeping) State.SLEEPING else State.LISTENING
                }
            }
        }
    }

    private fun stripKeywords(text: String, keywords: Set<String>): String {
        var result = text
        for (kw in keywords) {
            result = result.replace(Regex("(?i)\\b${Regex.escape(kw)}\\b"), "")
        }
        return result.trim()
    }
}
