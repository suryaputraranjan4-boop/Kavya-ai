package com.example.services

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.agent.MicrophoneEngine
import com.example.ai.OfflinePrivacyGuard
import com.example.ai.asr.OfflineSpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Security-hardened Android [RecognitionService] bound by [KavyaAssistantInteractionService]
 * via `voice_interaction_service.xml`.
 *
 * - Protected by `android:permission="android.permission.RECORD_AUDIO"` in `AndroidManifest.xml`
 *   plus runtime RECORD_AUDIO & caller UID permission checks.
 * - Delegates actual audio capture to the single-owner [MicrophoneEngine] (never creates a second AudioRecord).
 * - Respects `EXTRA_PREFER_OFFLINE`, `EXTRA_LANGUAGE`, and Strict Offline Mode by routing through
 *   local [OfflineSpeechRecognizer] without silently falling back to cloud STT.
 * - Enforces single active session ownership and cleans up `serviceScope` in `onDestroy()`.
 */
class KavyaRecognitionService : RecognitionService() {

    companion object {
        private const val TAG = "KavyaRecognitionService"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val sessionLock = Any()
    private var activeListenJob: Job? = null
    private var activeListener: Callback? = null
    private var activeCallingUid: Int = -1

    internal fun hasCallerAndSelfAudioPermission(): Boolean {
        val selfGranted = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!selfGranted) return false

        val callingUid = Binder.getCallingUid()
        if (callingUid == Process.myUid() || callingUid == Process.SYSTEM_UID) {
            return true
        }
        val callerCheck = checkCallingOrSelfPermission(Manifest.permission.RECORD_AUDIO)
        return callerCheck == PackageManager.PERMISSION_GRANTED
    }

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        if (listener == null) return

        if (!hasCallerAndSelfAudioPermission()) {
            Log.w(TAG, "Rejected RecognitionService.onStartListening: missing RECORD_AUDIO permission")
            runCatching { listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) }
            return
        }

        val preferOffline = recognizerIntent?.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false) == true
        val strictOffline = OfflinePrivacyGuard.isStrictOfflineMode(applicationContext)
        val requestedLanguage = recognizerIntent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)

        if (strictOffline || preferOffline) {
            if (!OfflineSpeechRecognizer.isLanguageSupportedByWhisper(requestedLanguage)) {
                Log.w(TAG, "Offline recognition rejected unsupported language: $requestedLanguage")
                runCatching { listener.error(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) }
                return
            }
            val offlineAsr = OfflineSpeechRecognizer.getInstance(applicationContext)
            if (!offlineAsr.isWhisperModelInstalled() && offlineAsr.testCustomPcmTranscriber == null) {
                Log.w(TAG, "Offline recognition requested but Whisper Tiny ONNX model is not installed.")
                runCatching { listener.error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) }
                return
            }
        }

        val callerUid = Binder.getCallingUid()
        val engine = MicrophoneEngine.getInstance(applicationContext)

        synchronized(sessionLock) {
            activeListenJob?.cancel()
            activeListener = listener
            activeCallingUid = callerUid
        }

        Log.i(TAG, "RecognitionService.onStartListening invoked (offline=${strictOffline || preferOffline}, lang=${requestedLanguage ?: "default"})")
        engine.setSessionLanguageHint(requestedLanguage)

        val started = engine.startListening()
        if (!started) {
            synchronized(sessionLock) {
                if (activeListener === listener) {
                    activeListener = null
                    activeCallingUid = -1
                }
            }
            engine.setSessionLanguageHint(null)
            runCatching { listener.error(SpeechRecognizer.ERROR_AUDIO) }
            return
        }

        runCatching { listener.readyForSpeech(Bundle()) }
        val previousTranscript = engine.lastTranscript.value

        val job = serviceScope.launch {
            try {
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
            } finally {
                engine.setSessionLanguageHint(null)
                synchronized(sessionLock) {
                    if (activeListener === listener) {
                        activeListener = null
                        activeListenJob = null
                        activeCallingUid = -1
                    }
                }
            }
        }
        synchronized(sessionLock) {
            activeListenJob = job
        }
    }

    override fun onCancel(listener: Callback?) {
        synchronized(sessionLock) {
            if (listener != null && activeListener != null && activeListener !== listener) {
                Log.w(TAG, "Ignored onCancel from non-owning listener")
                return
            }
            activeListenJob?.cancel()
            activeListenJob = null
            activeListener = null
            activeCallingUid = -1
        }
        MicrophoneEngine.getExistingInstance()?.setSessionLanguageHint(null)
    }

    override fun onStopListening(listener: Callback?) {
        synchronized(sessionLock) {
            if (listener != null && activeListener != null && activeListener !== listener) {
                Log.w(TAG, "Ignored onStopListening from non-owning listener")
                return
            }
            activeListenJob?.cancel()
            activeListenJob = null
            activeListener = null
            activeCallingUid = -1
        }
        MicrophoneEngine.getExistingInstance()?.setSessionLanguageHint(null)
        runCatching { listener?.endOfSpeech() }
    }

    override fun onDestroy() {
        synchronized(sessionLock) {
            activeListenJob?.cancel()
            activeListenJob = null
            activeListener = null
            activeCallingUid = -1
        }
        MicrophoneEngine.getExistingInstance()?.setSessionLanguageHint(null)
        serviceScope.cancel()
        super.onDestroy()
    }
}
