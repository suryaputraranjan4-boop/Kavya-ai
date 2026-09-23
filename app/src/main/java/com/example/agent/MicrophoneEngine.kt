package com.example.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Diagnostic state model for Microphone & Speech Recognition pipeline.
 */
data class MicrophoneDiagnosticState(
    val hasPermission: Boolean = false,
    val isHardwareAvailable: Boolean = false,
    val isAudioInputReady: Boolean = false,
    val isRecognizerAvailable: Boolean = false,
    val isListening: Boolean = false,
    val lastRecognizedText: String = "",
    val lastCallbackTimestamp: Long = 0,
    val lastError: String = "None"
)

/**
 * Centralized Microphone and Speech Recognition Engine.
 * Manages runtime permission checks, AudioRecord verification, and Android SpeechRecognizer lifecycle.
 */
class MicrophoneEngine(private val context: Context) {

    companion object {
        private const val TAG = "KavyaMicrophoneEngine"

        @Volatile
        private var instance: MicrophoneEngine? = null

        fun getInstance(context: Context): MicrophoneEngine {
            return instance ?: synchronized(this) {
                instance ?: MicrophoneEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null

    private val _status = MutableStateFlow(MicrophoneDiagnosticState())
    val status: StateFlow<MicrophoneDiagnosticState> = _status.asStateFlow()

    init {
        refreshHardwareDiagnostics()
    }

    /**
     * Inspects device microphone hardware, permissions, and SpeechRecognizer availability.
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
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                audioInputReady = minBuffer > 0
            } catch (e: Exception) {
                Log.w(TAG, "Error checking AudioRecord buffer: ${e.message}")
            }
        }

        val recognizerAvailable = SpeechRecognizer.isRecognitionAvailable(context)

        _status.value = _status.value.copy(
            hasPermission = permGranted,
            isHardwareAvailable = hardwareAvailable,
            isAudioInputReady = audioInputReady,
            isRecognizerAvailable = recognizerAvailable
        )

        Log.d(TAG, "Diagnostics refreshed: perm=$permGranted, hw=$hardwareAvailable, audioReady=$audioInputReady, recognizer=$recognizerAvailable")
    }

    /**
     * Starts listening to user voice via native Android SpeechRecognizer.
     */
    fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        mainHandler.post {
            refreshHardwareDiagnostics()
            val state = _status.value

            if (!state.hasPermission) {
                val err = "Microphone permission (RECORD_AUDIO) not granted"
                _status.value = _status.value.copy(lastError = err, isListening = false)
                onError(err)
                return@post
            }

            if (!state.isRecognizerAvailable) {
                val err = "Speech recognition service not available on this device"
                _status.value = _status.value.copy(lastError = err, isListening = false)
                onError(err)
                return@post
            }

            destroyRecognizer()

            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            Log.d(TAG, "SpeechRecognizer: Ready for speech")
                            _status.value = _status.value.copy(isListening = true, lastError = "None")
                        }

                        override fun onBeginningOfSpeech() {
                            Log.d(TAG, "SpeechRecognizer: Speech started")
                        }

                        override fun onRmsChanged(rmsdB: Float) {}

                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {
                            Log.d(TAG, "SpeechRecognizer: End of speech")
                            _status.value = _status.value.copy(isListening = false)
                        }

                        override fun onError(error: Int) {
                            val errorMsg = mapErrorCodeToString(error)
                            Log.w(TAG, "SpeechRecognizer error: $errorMsg ($error)")
                            _status.value = _status.value.copy(
                                isListening = false,
                                lastError = errorMsg,
                                lastCallbackTimestamp = System.currentTimeMillis()
                            )
                            onError(errorMsg)
                        }

                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val recognized = matches?.firstOrNull() ?: ""
                            Log.d(TAG, "SpeechRecognizer results: '$recognized'")
                            _status.value = _status.value.copy(
                                isListening = false,
                                lastRecognizedText = recognized,
                                lastCallbackTimestamp = System.currentTimeMillis(),
                                lastError = "None"
                            )
                            if (recognized.isNotBlank()) {
                                onResult(recognized)
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val partial = matches?.firstOrNull() ?: ""
                            if (partial.isNotBlank()) {
                                _status.value = _status.value.copy(lastRecognizedText = partial)
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }

                speechRecognizer?.startListening(intent)
                _status.value = _status.value.copy(isListening = true)
            } catch (e: Exception) {
                Log.e(TAG, "Exception initializing SpeechRecognizer: ${e.message}")
                _status.value = _status.value.copy(isListening = false, lastError = e.message ?: "Unknown error")
                onError(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Halts speech recognition.
     */
    fun stopListening() {
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping SpeechRecognizer: ${e.message}")
            }
            _status.value = _status.value.copy(isListening = false)
        }
    }

    /**
     * Interactive test function for the preview diagnostic UI.
     */
    fun testMicrophone(onComplete: (Boolean, String) -> Unit) {
        refreshHardwareDiagnostics()
        val s = _status.value
        if (!s.hasPermission) {
            onComplete(false, "Permission DENIED: Grant RECORD_AUDIO in system settings")
            return
        }
        if (!s.isHardwareAvailable) {
            onComplete(false, "Microphone hardware UNAVAILABLE")
            return
        }
        if (!s.isAudioInputReady) {
            onComplete(false, "Audio input buffer initialization FAILED")
            return
        }
        if (!s.isRecognizerAvailable) {
            onComplete(false, "SpeechRecognizer service not available on this Android build")
            return
        }

        startListening(
            onResult = { text ->
                onComplete(true, "Speech recognized: \"$text\"")
            },
            onError = { err ->
                onComplete(false, "Recognition error: $err")
            }
        )
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying speech recognizer: ${e.message}")
        }
        speechRecognizer = null
    }

    private fun mapErrorCodeToString(code: Int): String {
        return when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech match found"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input detected"
            else -> "Speech recognition error code: $code"
        }
    }
}
