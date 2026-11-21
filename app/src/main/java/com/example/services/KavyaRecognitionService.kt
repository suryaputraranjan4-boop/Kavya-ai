package com.example.services

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.agent.MicrophoneEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Standards-compliant Android [RecognitionService] bound by [KavyaAssistantInteractionService]
 * via `voice_interaction_service.xml`. Delegates actual audio capture to the single-owner
 * [MicrophoneEngine] so Android's Default Digital Assistant framework binds cleanly without
 * duplicating physical AudioRecord handles.
 */
class KavyaRecognitionService : RecognitionService() {

    companion object {
        private const val TAG = "KavyaRecognitionService"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var activeListenJob: Job? = null

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        if (listener == null) return
        Log.i(TAG, "RecognitionService.onStartListening invoked")
        activeListenJob?.cancel()
        val engine = MicrophoneEngine.getInstance(applicationContext)
        val started = engine.startListening()
        if (!started) {
            runCatching { listener.error(SpeechRecognizer.ERROR_AUDIO) }
            return
        }
        runCatching { listener.readyForSpeech(Bundle()) }
        val previousTranscript = engine.lastTranscript.value

        activeListenJob = serviceScope.launch {
            val result = withTimeoutOrNull(12_000L) {
                engine.lastTranscript.first { it.isNotBlank() && it != previousTranscript }
            }
            if (!result.isNullOrBlank()) {
                val bundle = Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(result))
                    putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(0.9f))
                }
                runCatching {
                    listener.endOfSpeech()
                    listener.results(bundle)
                }
            } else {
                runCatching { listener.error(SpeechRecognizer.ERROR_NO_MATCH) }
            }
        }
    }

    override fun onCancel(listener: Callback?) {
        activeListenJob?.cancel()
        activeListenJob = null
    }

    override fun onStopListening(listener: Callback?) {
        activeListenJob?.cancel()
        activeListenJob = null
        runCatching { listener?.endOfSpeech() }
    }

    override fun onDestroy() {
        activeListenJob?.cancel()
        super.onDestroy()
    }
}
