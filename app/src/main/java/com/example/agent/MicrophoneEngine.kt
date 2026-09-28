package com.example.agent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
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
 * Exclusively uses native Android [AudioRecord] hardware capture to ensure:
 * 1. Real audio hardware initialization with automatic format fallbacks.
 * 2. Guaranteed triggering of Android 12+ system-level green microphone privacy indicator.
 * 3. Real-time RMS amplitude calculation for genuine voice reactive animations.
 * 4. Automatic Voice Activity Detection (VAD) and silence detection.
 * 5. Clean resource allocation and immediate release when recording halts.
 * 6. Single Source of Truth for microphone state across the entire application.
 */
class MicrophoneEngine private constructor(private val context: Context) {

    companion object {
        private const val TAG = "KavyaMicrophoneEngine"

        // Audio configurations: 16kHz mono 16-bit PCM is standard for speech AI
        private val SAMPLE_RATES = intArrayOf(16000, 44100, 8000)
        private val AUDIO_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
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

    private var activeAudioRecord: AudioRecord? = null
    private var activeSampleRate = 16000
    private var activeBufferSize = 0
    private var recordingJob: Job? = null

    @Volatile
    private var isRecording = false

    private val _micState = MutableStateFlow(MicrophoneState.IDLE)
    val micState: StateFlow<MicrophoneState> = _micState.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _status = MutableStateFlow(MicrophoneDiagnosticState())
    val status: StateFlow<MicrophoneDiagnosticState> = _status.asStateFlow()

    init {
        refreshHardwareDiagnostics()
    }

    /**
     * Inspects device microphone hardware, permissions, and AudioRecord buffer availability.
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
                Log.w(TAG, "Error checking AudioRecord buffer: ${e.message}")
            }
        }

        _status.value = _status.value.copy(
            hasPermission = permGranted,
            isHardwareAvailable = hardwareAvailable,
            isAudioInputReady = audioInputReady,
            isRecognizerAvailable = true
        )

        Log.d(TAG, "Diagnostics refreshed: perm=$permGranted, hw=$hardwareAvailable, audioReady=$audioInputReady")
    }

    /**
     * Starts real hardware audio recording using Android AudioRecord.
     * Android's native green microphone indicator will turn ON as soon as recording starts.
     */
    @Synchronized
    fun startRecording(
        onRecordingStarted: () -> Unit,
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
            _status.value = _status.value.copy(
                hasPermission = false,
                isListening = false,
                lastError = "Microphone permission denied"
            )
            onError("Microphone permission denied. Please grant permission.")
            return
        }

        Log.d(TAG, "MIC_PERMISSION_GRANTED")

        if (isRecording) {
            Log.w(TAG, "Recording already active, stopping previous session first.")
            stopRecordingInternal(discard = true)
        }

        _micState.value = MicrophoneState.STARTING
        Log.d(TAG, "MIC_INITIALIZING")

        // Try initializing AudioRecord with supported sample rates and audio sources
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
            Log.e(TAG, "MIC_ERROR: $err")
            _micState.value = MicrophoneState.ERROR
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
                _micState.value = MicrophoneState.ERROR
                _status.value = _status.value.copy(isListening = false, lastError = err)
                onError(err)
                return
            }

            // Real Android microphone is now actively capturing audio!
            // Android system green privacy indicator is now visible.
            isRecording = true
            _micState.value = MicrophoneState.LISTENING
            _status.value = _status.value.copy(isListening = true, lastError = "None")
            Log.d(TAG, "MIC_STARTED: Real Android microphone active (System privacy indicator visible)")

            mainHandler.post { onRecordingStarted() }

            // Launch high-priority audio processing loop on background IO thread
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
                    _amplitude.value = 0f
                    Log.d(TAG, "MIC_STOPPED: Hardware released, privacy indicator dismissed.")

                    val recordedBytes = audioStream.toByteArray()
                    _micState.value = MicrophoneState.PROCESSING

                    if (recordedBytes.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            onAudioCaptured(recordedBytes, selectedSampleRate)
                        }
                    } else {
                        _micState.value = MicrophoneState.IDLE
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord: ${e.message}", e)
            releaseRecorder()
            _micState.value = MicrophoneState.ERROR
            _status.value = _status.value.copy(isListening = false, lastError = e.message ?: "Recording failure")
            onError(e.message ?: "Failed to activate microphone")
        }
    }

    /**
     * Stops audio capture gracefully and triggers processing of whatever was recorded.
     */
    @Synchronized
    fun stopRecording() {
        stopRecordingInternal(discard = false)
    }

    /**
     * Immediately cancels and releases audio recording resources.
     */
    @Synchronized
    fun cancelRecording() {
        stopRecordingInternal(discard = true)
    }

    private fun stopRecordingInternal(discard: Boolean) {
        if (!isRecording && activeAudioRecord == null) {
            _micState.value = MicrophoneState.IDLE
            return
        }

        Log.d(TAG, "MIC_STOPPING: stopRecordingInternal(discard=$discard)")
        isRecording = false

        if (discard) {
            recordingJob?.cancel()
            recordingJob = null
            releaseRecorder()
            _amplitude.value = 0f
            _micState.value = MicrophoneState.IDLE
            _status.value = _status.value.copy(isListening = false)
            Log.d(TAG, "MIC_STOPPED: Session cancelled and discarded.")
        }
        // If not discard, the recordingJob finally block handles releasing and invokes onAudioCaptured
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

        // Test actual 1-second record probe to verify hardware responds
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

    /**
     * Legacy compatibility method for speech listener caller.
     */
    fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit) {
        startRecording(
            onRecordingStarted = {},
            onAudioCaptured = { _, _ -> onResult("") },
            onError = onError
        )
    }

    /**
     * Legacy compatibility method.
     */
    fun stopListening() {
        stopRecording()
    }
}
