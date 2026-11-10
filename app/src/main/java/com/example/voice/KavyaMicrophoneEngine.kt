package com.example.voice

import android.content.Context
import com.example.agent.MicrophoneEngine
import com.example.services.KavyaVoiceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Compatibility facade for existing UI/ViewModel call sites.
 *
 * This is deliberately NOT an Android Service. KavyaVoiceService owns the
 * foreground-service lifecycle, while MicrophoneEngine is the sole physical
 * AudioRecord owner. This facade only mirrors state and forwards commands.
 */
class KavyaMicrophoneEngine private constructor(context: Context) {

    enum class EngineState {
        MIC_OFF,
        MIC_STARTING,
        MIC_ACTIVE,
        MIC_SLEEPING,
        MIC_ERROR
    }

    companion object {
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        private val stateFlow = MutableStateFlow(EngineState.MIC_OFF)
        private val partialFlow = MutableStateFlow("")
        private val amplitudeFlow = MutableStateFlow(0f)

        val engineState: StateFlow<EngineState> = stateFlow.asStateFlow()
        val partialText: StateFlow<String> = partialFlow.asStateFlow()
        val amplitude: StateFlow<Float> = amplitudeFlow.asStateFlow()

        @Volatile private var instance: KavyaMicrophoneEngine? = null
        @Volatile private var pendingListener: ((String) -> Unit)? = null
        @Volatile private var pendingSpeakingState = false

        private fun ensureInitialized(context: Context): KavyaMicrophoneEngine {
            return instance ?: synchronized(this) {
                instance ?: KavyaMicrophoneEngine(context.applicationContext).also {
                    instance = it
                }
            }
        }

        fun setCommandListener(listener: (String) -> Unit) {
            pendingListener = listener
            instance?.micEngine?.setCommandListener(listener)
        }

        fun startService(context: Context) {
            ensureInitialized(context)
            KavyaVoiceService.startService(context)
        }

        fun stopService(context: Context) {
            ensureInitialized(context)
            KavyaVoiceService.stopService(context)
        }

        fun sleep(context: Context) {
            ensureInitialized(context)
            KavyaVoiceService.sleep(context)
        }

        fun wake(context: Context) {
            ensureInitialized(context)
            KavyaVoiceService.wake(context)
        }

        fun onKavyaSpeakingChanged(isSpeaking: Boolean) {
            pendingSpeakingState = isSpeaking
            instance?.setSpeaking(isSpeaking)
        }

        fun getInstance(): KavyaMicrophoneEngine? = instance
    }

    private val micEngine = MicrophoneEngine.getInstance(context.applicationContext)

    init {
        pendingListener?.let(micEngine::setCommandListener)
        micEngine.setAssistantSpeaking(pendingSpeakingState)

        // These collectors are started as soon as the facade is initialized,
        // so UI state works even though the foreground service is separate.
        scope.launch {
            micEngine.engineState.collect { state ->
                stateFlow.value = when (state) {
                    MicrophoneEngine.State.IDLE,
                    MicrophoneEngine.State.STOPPING -> EngineState.MIC_OFF

                    MicrophoneEngine.State.STARTING -> EngineState.MIC_STARTING

                    MicrophoneEngine.State.LISTENING,
                    MicrophoneEngine.State.SPEECH_DETECTED,
                    MicrophoneEngine.State.PROCESSING,
                    MicrophoneEngine.State.TTS_PLAYBACK -> {
                        if (micEngine.isRecording) EngineState.MIC_ACTIVE else EngineState.MIC_OFF
                    }

                    MicrophoneEngine.State.MUTED -> EngineState.MIC_ACTIVE
                    MicrophoneEngine.State.SLEEPING -> EngineState.MIC_SLEEPING
                    MicrophoneEngine.State.ERROR -> EngineState.MIC_ERROR
                }
            }
        }

        scope.launch {
            micEngine.amplitude.collect { amplitude ->
                amplitudeFlow.value = amplitude
            }
        }

        scope.launch {
            micEngine.lastTranscript.collect { transcript ->
                partialFlow.value = transcript
            }
        }
    }

    fun stopEngine() {
        micEngine.stopListening()
    }

    fun setSleeping() {
        micEngine.setSleeping(true)
    }

    fun setAwake() {
        micEngine.setSleeping(false)
    }

    fun setSpeaking(isSpeaking: Boolean) {
        micEngine.setAssistantSpeaking(isSpeaking)
    }
}
