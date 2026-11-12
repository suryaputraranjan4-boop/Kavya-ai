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
 * Authoritative facade and backward-compatibility bridge to the central
 * single-AudioRecord [MicrophoneEngine] and [KavyaVoiceService].
 *
 * Guarantees a single-owner AudioRecord lifecycle, eliminating duplicate service
 * registrations, speech recognizer recreation loops, and mic beep sounds.
 */
class KavyaMicrophoneEngine private constructor(private val appContext: Context) {

    enum class EngineState {
        MIC_OFF,
        MIC_STARTING,
        MIC_ACTIVE,
        MIC_LISTENING,
        MIC_SPEECH_DETECTED,
        MIC_PROCESSING,
        MIC_MUTED,
        MIC_SLEEPING,
        MIC_STOPPING,
        MIC_ERROR;

        val isListening: Boolean
            get() = this == MIC_ACTIVE || this == MIC_LISTENING ||
                this == MIC_SPEECH_DETECTED || this == MIC_PROCESSING
    }

    companion object {
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        private val stateFlow = MutableStateFlow(EngineState.MIC_OFF)
        private val partialFlow = MutableStateFlow("")
        private val amplitudeFlow = MutableStateFlow(0f)
        private val mutedFlow = MutableStateFlow(false)

        val engineState: StateFlow<EngineState> = stateFlow.asStateFlow()
        val partialText: StateFlow<String> = partialFlow.asStateFlow()
        val amplitude: StateFlow<Float> = amplitudeFlow.asStateFlow()
        val isMuted: StateFlow<Boolean> = mutedFlow.asStateFlow()

        @Volatile private var instance: KavyaMicrophoneEngine? = null
        private var pendingListener: ((String) -> Unit)? = null
        @Volatile private var isInitialized = false

        fun init(context: Context) {
            if (isInitialized && instance != null) return
            synchronized(this) {
                if (isInitialized && instance != null) return
                val appContext = context.applicationContext
                instance = KavyaMicrophoneEngine(appContext)
                val mic = MicrophoneEngine.getInstance(appContext)

                pendingListener?.let {
                    mic.setCommandListener(it)
                    pendingListener = null
                }

                scope.launch {
                    mic.engineState.collect { state ->
                        mutedFlow.value = state == MicrophoneEngine.State.MUTED
                        stateFlow.value = when (state) {
                            MicrophoneEngine.State.IDLE -> EngineState.MIC_OFF
                            MicrophoneEngine.State.STARTING -> EngineState.MIC_STARTING
                            MicrophoneEngine.State.LISTENING -> EngineState.MIC_ACTIVE
                            MicrophoneEngine.State.SPEECH_DETECTED -> EngineState.MIC_SPEECH_DETECTED
                            MicrophoneEngine.State.PROCESSING -> EngineState.MIC_PROCESSING
                            MicrophoneEngine.State.TTS_PLAYBACK -> EngineState.MIC_ACTIVE
                            MicrophoneEngine.State.MUTED -> EngineState.MIC_MUTED
                            MicrophoneEngine.State.SLEEPING -> EngineState.MIC_SLEEPING
                            MicrophoneEngine.State.STOPPING -> EngineState.MIC_STOPPING
                            MicrophoneEngine.State.ERROR -> EngineState.MIC_ERROR
                        }
                    }
                }

                scope.launch {
                    mic.amplitude.collect { amp ->
                        amplitudeFlow.value = amp
                    }
                }

                scope.launch {
                    mic.lastTranscript.collect { txt ->
                        partialFlow.value = txt
                    }
                }

                isInitialized = true
            }
        }

        fun setCommandListener(listener: (String) -> Unit) {
            val mic = MicrophoneEngine.getExistingInstance()
            if (mic != null) {
                mic.setCommandListener(listener)
            } else {
                pendingListener = listener
            }
        }

        fun startService(context: Context) {
            init(context)
            KavyaVoiceService.startService(context)
        }

        fun stopService(context: Context) {
            init(context)
            KavyaVoiceService.stopService(context)
        }

        fun setMuted(context: Context, muted: Boolean) {
            init(context)
            MicrophoneEngine.getInstance(context).setMuted(muted)
            mutedFlow.value = muted
        }

        fun toggleMute(context: Context): Boolean {
            init(context)
            val mic = MicrophoneEngine.getInstance(context)
            val next = mic.engineState.value != MicrophoneEngine.State.MUTED
            mic.setMuted(next)
            mutedFlow.value = next
            return next
        }

        fun sleep(context: Context) {
            init(context)
            KavyaVoiceService.sleep(context)
        }

        fun wake(context: Context) {
            init(context)
            KavyaVoiceService.wake(context)
        }

        fun onKavyaSpeakingChanged(isSpeaking: Boolean) {
            MicrophoneEngine.getExistingInstance()?.setAssistantSpeaking(isSpeaking)
        }

        fun setUserInterruptionListener(listener: (() -> Unit)?) {
            MicrophoneEngine.getExistingInstance()?.setUserInterruptionListener(listener)
        }

        fun injectPcmFrameForTest(pcmData: ByteArray, length: Int = pcmData.size) {
            MicrophoneEngine.getExistingInstance()?.injectPcmFrameForTest(pcmData, length)
        }

        fun getInstance(): KavyaMicrophoneEngine? = instance
    }

    fun stopEngine() {
        MicrophoneEngine.getInstance(appContext).stopListening()
    }

    fun setSleeping() {
        MicrophoneEngine.getInstance(appContext).setSleeping(true)
    }

    fun setAwake() {
        MicrophoneEngine.getInstance(appContext).setSleeping(false)
    }

    fun setSpeaking(isSpeaking: Boolean) {
        MicrophoneEngine.getInstance(appContext).setAssistantSpeaking(isSpeaking)
    }
}
