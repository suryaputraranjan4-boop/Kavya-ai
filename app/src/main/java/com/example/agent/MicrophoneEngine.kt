package com.example.agent

import android.Manifest
import android.annotation.SuppressLint
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
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.sqrt

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
 * Production-grade Real Android Microphone Engine.
 *
 * Exclusively accesses native Android audio capture hardware via:
 * 1. Native [SpeechRecognizer] with [RecognitionListener] for direct, latency-free on-device/system STT.
 * 2. Native [AudioRecord] hardware PCM capture for streaming/multimodal audio fallback.
 * 3. Guaranteed triggering of Android 12+ system-level green microphone privacy indicator.
 * 4. Real-time RMS amplitude calculation for authentic voice-reactive animations.
 * 5. Audio Focus management to pause external media and avoid acoustic feedback.
 * 6. Single Source of Truth for microphone state across the entire application.
 */
class MicrophoneEngine private constructor(private val context: Context) {

    companion object {
        private const val TAG = "KavyaMicrophoneEngine"

        // Audio configurations: 16kHz mono 16-bit PCM is standard for speech AI
        private val SAMPLE_RATES = intArrayOf(16000, 44100, 8000)
        private val AUDIO_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.DEFAULT
        )

        private const val SILENCE_THRESHOLD_RMS = 450.0
        private const val SILENCE_TIMEOUT_MS = 1800L
        private const val MIN_SPEECH_DURATION_MS = 800L
        private const val MAX_RECORDING_DURATION_MS = 18000L

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

    private var activeAudioRecord: AudioRecord? = null
    private var activeSpeechRecognizer: SpeechRecognizer? = null
    private var activeSampleRate = 16000
    private var activeBufferSize = 0
    private var recordingJob: Job? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    @Volatile
    private var isRecording = false

    @Volatile
    private var isRecognizing = false

    private val _micState = MutableStateFlow(MicrophoneState.IDLE)
    val micState: StateFlow<MicrophoneState> = _micState.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _status = MutableStateFlow(MicrophoneDiagnosticState())
    val status: StateFlow<MicrophoneDiagnosticState> = _status.asStateFlow()

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
                for (rate in SAMPLE_RATES) {
                    val minBuffer = AudioRecord.getMinBufferSize(
                        rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    if (minBuffer > 0) {
                        audioInputReady = true
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "MIC_DIAGNOSTIC: Error checking AudioRecord buffer: ${e.message}")
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
            isRecognizerAvailable = recognizerAvailable
        )

        Log.d(TAG, "MIC_DIAGNOSTIC: perm=$permGranted, hw=$hardwareAvailable, audioReady=$audioInputReady, recognizerReady=$recognizerAvailable")
    }

    // =========================================================================
    // 1. PRIMARY NATIVE SPEECH RECOGNITION (SpeechRecognizer + RecognitionListener)
    // =========================================================================

    /**
     * Starts native Android SpeechRecognizer.
     * Uses real microphone hardware, turns on the Android system green privacy indicator,
     * computes live RMS amplitude, delivers partial recognition, and outputs final text.
     */
    @Synchronized
    fun startListening(
        onListeningStarted: () -> Unit = {},
        onResult: (String) -> Unit,
        onPartialResult: ((String) -> Unit)? = null,
        onError: (String) -> Unit = {}
    ) {
        refreshHardwareDiagnostics()

        val permGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!permGranted) {
            Log.e(TAG, "MIC_PERMISSION_DENIED: RECORD_AUDIO permission missing")
            _micState.value = MicrophoneState.ERROR
            KavyaStateManager.updateVoiceState(VoiceState.ERROR)
            _status.value = _status.value.copy(
                hasPermission = false,
                isListening = false,
                lastError = "Microphone permission required"
            )
            onError("Microphone permission denied. Please grant permission.")
            return
        }

        Log.d(TAG, "MIC_PERMISSION_GRANTED")

        // Stop any currently running recording or recognition session cleanly
        stopAllInternal(discard = true)

        _micState.value = MicrophoneState.STARTING
        Log.d(TAG, "MIC_INITIALIZING")

        requestAudioFocus()

        val isSpeechAvailable = try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (_: Exception) {
            false
        }

        if (!isSpeechAvailable) {
            Log.w(TAG, "STT_FALLBACK: Native SpeechRecognizer unavailable, falling back to AudioRecord capture.")
            startRawAudioRecording(
                onStarted = {
                    mainHandler.post { onListeningStarted() }
                },
                onAudioCaptured = { pcmBytes, sampleRate ->
                    scope.launch {
                        _micState.value = MicrophoneState.PROCESSING
                        KavyaStateManager.updateVoiceState(VoiceState.THINKING)
                        val text = try {
                            val ai = com.example.ai.KavyaAI(context)
                            ai.transcribeAudio(pcmBytes, sampleRate)
                        } catch (e: Exception) {
                            Log.w(TAG, "Fallback transcription failed: ${e.message}")
                            ""
                        }
                        _micState.value = MicrophoneState.IDLE
                        KavyaStateManager.updateVoiceState(VoiceState.IDLE)
                        abandonAudioFocus()
                        withContext(Dispatchers.Main) {
                            onResult(text)
                        }
                    }
                },
                onError = { err ->
                    abandonAudioFocus()
                    onError(err)
                }
            )
            return
        }

        // Initialize SpeechRecognizer strictly on Main Looper
        mainHandler.post {
            try {
                cleanupSpeechRecognizer()

                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                activeSpeechRecognizer = recognizer

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "MIC_STARTED: Native SpeechRecognizer active (System privacy indicator visible)")
                        isRecognizing = true
                        _micState.value = MicrophoneState.LISTENING
                        KavyaStateManager.updateVoiceState(VoiceState.LISTENING)
                        _status.value = _status.value.copy(isListening = true, lastError = "None")
                        onListeningStarted()
                    }

                    override fun onBeginningOfSpeech() {
                        Log.d(TAG, "MIC_VOICE_STARTED: Beginning of user speech detected")
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        // Native Android rmsdB ranges from -2dB (silence) to ~10dB (loud speech)
                        val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                        _amplitude.value = normalized
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {
                        // Raw buffer delivered by recognition engine if supported
                    }

                    override fun onEndOfSpeech() {
                        Log.d(TAG, "MIC_VOICE_ENDED: User finished speaking, processing speech")
                        _micState.value = MicrophoneState.PROCESSING
                        _amplitude.value = 0f
                        KavyaStateManager.updateVoiceState(VoiceState.THINKING)
                    }

                    override fun onError(errorCode: Int) {
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

                        Log.w(TAG, "STT_ERROR: code=$errorCode, reason=$msg, isTimeout=$isSilentTimeout")
                        isRecognizing = false
                        abandonAudioFocus()
                        cleanupSpeechRecognizer()
                        _amplitude.value = 0f

                        if (isSilentTimeout) {
                            _micState.value = MicrophoneState.IDLE
                            KavyaStateManager.updateVoiceState(VoiceState.IDLE)
                            _status.value = _status.value.copy(isListening = false)
                            onResult("")
                        } else if (errorCode == SpeechRecognizer.ERROR_AUDIO ||
                            errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                            errorCode == SpeechRecognizer.ERROR_SERVER ||
                            errorCode == SpeechRecognizer.ERROR_CLIENT
                        ) {
                            Log.w(TAG, "STT_FALLBACK_ON_ERROR: SpeechRecognizer error $errorCode, failing over to real hardware AudioRecord")
                            startRawAudioRecording(
                                onStarted = { mainHandler.post { onListeningStarted() } },
                                onAudioCaptured = { pcmBytes, sampleRate ->
                                    scope.launch {
                                        _micState.value = MicrophoneState.PROCESSING
                                        KavyaStateManager.updateVoiceState(VoiceState.THINKING)
                                        val text = try {
                                            val ai = com.example.ai.KavyaAI(context)
                                            ai.transcribeAudio(pcmBytes, sampleRate)
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Fallback transcription failed: ${e.message}")
                                            ""
                                        }
                                        _micState.value = MicrophoneState.IDLE
                                        KavyaStateManager.updateVoiceState(VoiceState.IDLE)
                                        abandonAudioFocus()
                                        withContext(Dispatchers.Main) {
                                            onResult(text)
                                        }
                                    }
                                },
                                onError = { fallbackErr ->
                                    _micState.value = MicrophoneState.ERROR
                                    KavyaStateManager.updateVoiceState(VoiceState.ERROR)
                                    _status.value = _status.value.copy(isListening = false, lastError = fallbackErr)
                                    onError(fallbackErr)
                                }
                            )
                        } else {
                            _micState.value = MicrophoneState.ERROR
                            KavyaStateManager.updateVoiceState(VoiceState.ERROR)
                            _status.value = _status.value.copy(isListening = false, lastError = msg)
                            onError(msg)
                            // Auto-recover back to IDLE after cooldown
                            scope.launch {
                                delay(2000)
                                if (_micState.value == MicrophoneState.ERROR) {
                                    _micState.value = MicrophoneState.IDLE
                                    KavyaStateManager.updateVoiceState(VoiceState.IDLE)
                                }
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim() ?: ""
                        Log.d(TAG, "STT_RESULT: Candidates=${matches?.size ?: 0}, Text=\"$text\"")

                        isRecognizing = false
                        _micState.value = MicrophoneState.IDLE
                        _amplitude.value = 0f
                        KavyaStateManager.updateVoiceState(VoiceState.IDLE)
                        _status.value = _status.value.copy(
                            isListening = false,
                            lastRecognizedText = text,
                            lastCallbackTimestamp = System.currentTimeMillis()
                        )

                        abandonAudioFocus()
                        cleanupSpeechRecognizer()
                        onResult(text)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partial = matches?.firstOrNull()?.trim() ?: ""
                        if (partial.isNotBlank()) {
                            _partialText.value = partial
                            onPartialResult?.invoke(partial)
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

                Log.d(TAG, "STT_STARTED: Requesting native recognizer to listen")
                recognizer.startListening(intent)

            } catch (e: Exception) {
                Log.w(TAG, "STT_FALLBACK: SpeechRecognizer initialization failed (${e.message}), falling back to AudioRecord capture.")
                cleanupSpeechRecognizer()
                startRawAudioRecording(
                    onStarted = { mainHandler.post { onListeningStarted() } },
                    onAudioCaptured = { pcmBytes, sampleRate ->
                        scope.launch {
                            _micState.value = MicrophoneState.PROCESSING
                            KavyaStateManager.updateVoiceState(VoiceState.THINKING)
                            val text = try {
                                val ai = com.example.ai.KavyaAI(context)
                                ai.transcribeAudio(pcmBytes, sampleRate)
                            } catch (err: Exception) {
                                Log.w(TAG, "Fallback transcription failed: ${err.message}")
                                ""
                            }
                            _micState.value = MicrophoneState.IDLE
                            KavyaStateManager.updateVoiceState(VoiceState.IDLE)
                            abandonAudioFocus()
                            withContext(Dispatchers.Main) {
                                onResult(text)
                            }
                        }
                    },
                    onError = { err ->
                        abandonAudioFocus()
                        _micState.value = MicrophoneState.ERROR
                        KavyaStateManager.updateVoiceState(VoiceState.ERROR)
                        onError(err)
                    }
                )
            }
        }
    }

    // =========================================================================
    // 2. HARDWARE AUDIO CAPTURE (AudioRecord PCM)
    // =========================================================================

    /**
     * Starts real hardware audio recording using Android AudioRecord.
     * Guaranteed to turn on the Android 12+ green microphone indicator.
     */
    @Synchronized
    fun startRecording(
        onRecordingStarted: () -> Unit,
        onAudioCaptured: (ByteArray, Int) -> Unit,
        onError: (String) -> Unit
    ) {
        startRawAudioRecording(onRecordingStarted, onAudioCaptured, onError)
    }

    private fun startRawAudioRecording(
        onStarted: () -> Unit,
        onAudioCaptured: (ByteArray, Int) -> Unit,
        onError: (String) -> Unit
    ) {
        refreshHardwareDiagnostics()

        val permGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!permGranted) {
            Log.e(TAG, "MIC_PERMISSION_DENIED: RECORD_AUDIO permission not granted")
            _micState.value = MicrophoneState.ERROR
            KavyaStateManager.updateVoiceState(VoiceState.ERROR)
            _status.value = _status.value.copy(
                hasPermission = false,
                isListening = false,
                lastError = "Microphone permission denied"
            )
            onError("Microphone permission denied. Please grant permission.")
            return
        }

        Log.d(TAG, "MIC_PERMISSION_GRANTED")

        stopAllInternal(discard = true)

        _micState.value = MicrophoneState.STARTING
        Log.d(TAG, "MIC_INITIALIZING: AudioRecord hardware configuration selection")

        requestAudioFocus()

        var recorder: AudioRecord? = null
        var selectedSampleRate = 16000
        var selectedBufferSize = 0

        for (source in AUDIO_SOURCES) {
            for (rate in SAMPLE_RATES) {
                try {
                    val minBuf = AudioRecord.getMinBufferSize(
                        rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    if (minBuf <= 0) continue

                    val bufferSize = minBuf * 2
                    @SuppressLint("MissingPermission")
                    val rec = AudioRecord(
                        source,
                        rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                    )

                    if (rec.state == AudioRecord.STATE_INITIALIZED) {
                        recorder = rec
                        selectedSampleRate = rate
                        selectedBufferSize = bufferSize
                        Log.d(TAG, "MIC_INITIALIZED: source=$source, rate=$rate, bufferSize=$bufferSize")
                        break
                    } else {
                        rec.release()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed config source=$source, rate=$rate: ${e.message}")
                }
            }
            if (recorder != null) break
        }

        if (recorder == null) {
            val err = "Microphone hardware initialization failed on all configurations"
            Log.e(TAG, "MIC_INIT_FAILED: $err")
            abandonAudioFocus()
            _micState.value = MicrophoneState.ERROR
            KavyaStateManager.updateVoiceState(VoiceState.ERROR)
            _status.value = _status.value.copy(isListening = false, lastError = err)
            onError(err)
            return
        }

        activeAudioRecord = recorder
        activeSampleRate = selectedSampleRate
        activeBufferSize = selectedBufferSize

        try {
            Log.d(TAG, "MIC_STARTING")
            recorder.startRecording()

            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                val err = "Microphone unavailable or occupied by another app"
                Log.e(TAG, "MIC_ERROR: $err")
                releaseRecorder()
                abandonAudioFocus()
                _micState.value = MicrophoneState.ERROR
                KavyaStateManager.updateVoiceState(VoiceState.ERROR)
                _status.value = _status.value.copy(isListening = false, lastError = err)
                onError(err)
                return
            }

            isRecording = true
            _micState.value = MicrophoneState.LISTENING
            KavyaStateManager.updateVoiceState(VoiceState.LISTENING)
            _status.value = _status.value.copy(isListening = true, lastError = "None")
            Log.d(TAG, "MIC_STARTED: Real Android microphone active (System privacy indicator visible)")

            mainHandler.post { onStarted() }

            recordingJob = scope.launch {
                val audioStream = ByteArrayOutputStream()
                val readBuffer = ByteArray(selectedBufferSize)
                var hasSpeech = false
                var speechStartTime = 0L
                var lastVoiceTime = System.currentTimeMillis()
                val recordingStartTime = System.currentTimeMillis()
                var firstBuffer = true

                try {
                    while (isRecording && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val bytesRead = recorder.read(readBuffer, 0, readBuffer.size)
                        if (bytesRead > 0) {
                            if (firstBuffer) {
                                Log.d(TAG, "AUDIO_BUFFER_RECEIVED: $bytesRead bytes")
                                firstBuffer = false
                            }

                            audioStream.write(readBuffer, 0, bytesRead)

                            // Compute real-time RMS amplitude for authentic visualization
                            var sumSquares = 0.0
                            val sampleCount = bytesRead / 2
                            for (i in 0 until bytesRead step 2) {
                                val sample = (readBuffer[i].toInt() and 0xFF) or (readBuffer[i + 1].toInt() shl 8)
                                val shortVal = sample.toShort()
                                sumSquares += shortVal * shortVal
                            }

                            val rms = if (sampleCount > 0) sqrt(sumSquares / sampleCount) else 0.0
                            val normalizedAmplitude = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
                            _amplitude.value = normalizedAmplitude

                            val now = System.currentTimeMillis()

                            // Voice Activity Detection
                            if (rms > SILENCE_THRESHOLD_RMS) {
                                if (!hasSpeech) {
                                    hasSpeech = true
                                    speechStartTime = now
                                    Log.d(TAG, "SPEECH_PIPELINE_STARTED: Voice detected, rms=$rms")
                                }
                                lastVoiceTime = now
                            }

                            // Automatic silence detection (stop capture after 1.8s silence following speech)
                            if (hasSpeech && (now - speechStartTime > MIN_SPEECH_DURATION_MS)) {
                                if (now - lastVoiceTime > SILENCE_TIMEOUT_MS) {
                                    Log.d(TAG, "SPEECH_PIPELINE_STOPPED: Silence detected after speech, finishing capture.")
                                    break
                                }
                            }

                            // Maximum recording duration safeguard
                            if (now - recordingStartTime > MAX_RECORDING_DURATION_MS) {
                                Log.d(TAG, "Maximum recording duration reached (${MAX_RECORDING_DURATION_MS / 1000}s)")
                                break
                            }
                        } else if (bytesRead < 0) {
                            Log.w(TAG, "AudioRecord read returned error code: $bytesRead")
                            if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION || bytesRead == AudioRecord.ERROR_DEAD_OBJECT) {
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception during audio reading loop: ${e.message}", e)
                } finally {
                    Log.d(TAG, "MIC_STOPPING")
                    _micState.value = MicrophoneState.STOPPING
                    releaseRecorder()
                    abandonAudioFocus()
                    _amplitude.value = 0f
                    Log.d(TAG, "MIC_STOPPED: Hardware released, privacy indicator dismissed.")

                    val recordedBytes = audioStream.toByteArray()
                    _micState.value = MicrophoneState.PROCESSING
                    KavyaStateManager.updateVoiceState(VoiceState.THINKING)

                    if (recordedBytes.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            onAudioCaptured(recordedBytes, selectedSampleRate)
                        }
                    } else {
                        _micState.value = MicrophoneState.IDLE
                        KavyaStateManager.updateVoiceState(VoiceState.IDLE)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord: ${e.message}", e)
            releaseRecorder()
            abandonAudioFocus()
            _micState.value = MicrophoneState.ERROR
            KavyaStateManager.updateVoiceState(VoiceState.ERROR)
            _status.value = _status.value.copy(isListening = false, lastError = e.message ?: "Recording failure")
            onError(e.message ?: "Failed to activate microphone")
        }
    }

    // =========================================================================
    // 3. STOP & RESOURCE CLEANUP
    // =========================================================================

    /**
     * Gracefully stops whichever microphone session is active.
     */
    @Synchronized
    fun stopListening() {
        stopAllInternal(discard = false)
    }

    /**
     * Legacy alias for stopListening.
     */
    @Synchronized
    fun stopRecording() {
        stopAllInternal(discard = false)
    }

    /**
     * Immediately cancels audio capture and releases all resources.
     */
    @Synchronized
    fun cancelListening() {
        stopAllInternal(discard = true)
    }

    /**
     * Legacy alias for cancelListening.
     */
    @Synchronized
    fun cancelRecording() {
        stopAllInternal(discard = true)
    }

    private fun stopAllInternal(discard: Boolean) {
        Log.d(TAG, "MIC_STOPPING: stopAllInternal(discard=$discard)")

        // Stop SpeechRecognizer
        if (isRecognizing || activeSpeechRecognizer != null) {
            mainHandler.post {
                try {
                    if (discard) {
                        activeSpeechRecognizer?.cancel()
                    } else {
                        activeSpeechRecognizer?.stopListening()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping SpeechRecognizer: ${e.message}")
                } finally {
                    cleanupSpeechRecognizer()
                }
            }
            isRecognizing = false
        }

        // Stop AudioRecord
        if (isRecording || activeAudioRecord != null) {
            isRecording = false
            if (discard) {
                recordingJob?.cancel()
                recordingJob = null
                releaseRecorder()
            }
        }

        abandonAudioFocus()
        _amplitude.value = 0f
        _micState.value = MicrophoneState.IDLE
        KavyaStateManager.updateVoiceState(VoiceState.IDLE)
        _status.value = _status.value.copy(isListening = false)
        Log.d(TAG, "MIC_STOPPED: Session ended, resources cleanly released.")
    }

    private fun cleanupSpeechRecognizer() {
        try {
            activeSpeechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying SpeechRecognizer: ${e.message}")
        } finally {
            activeSpeechRecognizer = null
            isRecognizing = false
        }
    }

    private fun releaseRecorder() {
        try {
            val rec = activeAudioRecord
            if (rec != null) {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop()
                }
                rec.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord: ${e.message}")
        } finally {
            activeAudioRecord = null
            isRecording = false
        }
    }

    // =========================================================================
    // 4. AUDIO FOCUS MANAGEMENT
    // =========================================================================

    private fun requestAudioFocus(): Boolean {
        val am = audioManager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { focusChange ->
                        Log.d(TAG, "AUDIO_FOCUS_CHANGED: $focusChange")
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            stopListening()
                        }
                    }
                    .build()
                audioFocusRequest = request
                val res = am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                if (res) Log.d(TAG, "AUDIO_FOCUS_GAINED")
                res
            } else {
                @Suppress("DEPRECATION")
                val res = am.requestAudioFocus(
                    { focusChange ->
                        Log.d(TAG, "AUDIO_FOCUS_CHANGED: $focusChange")
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            stopListening()
                        }
                    },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                if (res) Log.d(TAG, "AUDIO_FOCUS_GAINED")
                res
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request audio focus: ${e.message}")
            false
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let {
                    am.abandonAudioFocusRequest(it)
                    audioFocusRequest = null
                    Log.d(TAG, "AUDIO_FOCUS_ABANDONED")
                }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
                Log.d(TAG, "AUDIO_FOCUS_ABANDONED")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to abandon audio focus: ${e.message}")
        }
    }

    // =========================================================================
    // 5. HARDWARE SELF-TEST & VALIDATION
    // =========================================================================

    /**
     * Self-test function verifying hardware, permission, and live AudioRecord buffer creation.
     */
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

        // Test actual 1-second record probe to verify real device hardware response
        startRecording(
            onRecordingStarted = {
                Log.d(TAG, "testMicrophone: probe recording started")
                scope.launch {
                    delay(1200)
                    stopRecording()
                }
            },
            onAudioCaptured = { pcmBytes, rate ->
                val success = pcmBytes.isNotEmpty()
                val msg = if (success) "Real microphone verified: captured ${pcmBytes.size} bytes at ${rate}Hz" else "Microphone captured 0 bytes"
                onComplete(success, msg)
            },
            onError = { err ->
                onComplete(false, "Microphone test failed: $err")
            }
        )
    }
}
