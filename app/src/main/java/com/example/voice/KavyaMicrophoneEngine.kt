package com.example.voice

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
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
 * Backward-compatibility wrapper bridging legacy call sites to the central
 * single-AudioRecord [MicrophoneEngine] and [KavyaVoiceService].
 *
 * Completely eliminates SpeechRecognizer recreation loops and Google-style microphone beeps.
 */
class KavyaMicrophoneEngine : Service() {

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
        private var pendingListener: ((String) -> Unit)? = null
        @Volatile private var isInitialized = false

        fun init(context: Context) {
            if (isInitialized) return
            synchronized(this) {
                if (isInitialized) return
                val appContext = context.applicationContext
                val mic = MicrophoneEngine.getInstance(appContext)

                pendingListener?.let {
                    mic.setCommandListener(it)
                    pendingListener = null
                }

                scope.launch {
                    mic.engineState.collect { state ->
                        stateFlow.value = when (state) {
                            MicrophoneEngine.State.IDLE, MicrophoneEngine.State.STOPPING -> EngineState.MIC_OFF
                            MicrophoneEngine.State.STARTING -> EngineState.MIC_STARTING
                            MicrophoneEngine.State.LISTENING, MicrophoneEngine.State.SPEECH_DETECTED,
                            MicrophoneEngine.State.PROCESSING, MicrophoneEngine.State.TTS_PLAYBACK,
                            MicrophoneEngine.State.MUTED -> EngineState.MIC_ACTIVE
                            MicrophoneEngine.State.SLEEPING -> EngineState.MIC_SLEEPING
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

        fun getInstance(): KavyaMicrophoneEngine? = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        KavyaVoiceService.startService(this)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun stopEngine() {
        MicrophoneEngine.getInstance(this).stopListening()
    }

    fun setSleeping() {
        MicrophoneEngine.getInstance(this).setSleeping(true)
    }

    fun setAwake() {
        MicrophoneEngine.getInstance(this).setSleeping(false)
    }

    fun setSpeaking(isSpeaking: Boolean) {
        MicrophoneEngine.getInstance(this).setAssistantSpeaking(isSpeaking)
    }
}
