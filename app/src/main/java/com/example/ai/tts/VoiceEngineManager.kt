package com.example.ai.tts

import android.content.Context
import android.util.Log
import com.example.agent.MicrophoneEngine
import com.example.ai.ChatterboxResult
import com.example.ai.ChatterboxTtsClient
import com.example.ai.GeminiAudioPlayer
import com.example.ai.VoiceProfileManager
import com.example.utils.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class VoiceEngine(val id: String, val displayName: String, val isOffline: Boolean) {
    INDIC_TINY_TTS("INDIC_TINY_TTS", "Indic Tiny TTS Hindi (Female Voice)", true),
    CHATTERBOX("CHATTERBOX_HINDI", "Chatterbox Multilingual Hindi", false);

    companion object {
        fun fromId(id: String): VoiceEngine =
            entries.find { it.id.equals(id, ignoreCase = true) } ?: INDIC_TINY_TTS
    }
}

/**
 * Centralized speech routing. The selected engine is never silently replaced.
 * Previewing an engine does not mutate the user's saved engine preference.
 */
class VoiceEngineManager private constructor(private val appContext: Context) {
    companion object {
        private const val TAG = "VoiceEngineManager"
        const val PREVIEW_SAMPLE_HINDI =
            "नमस्ते! मैं काव्या हूँ, आपकी एआई सहायिका। मैं आपकी सहायता करने के लिए तैयार हूँ।"

        @Volatile private var instance: VoiceEngineManager? = null

        fun getInstance(context: Context): VoiceEngineManager =
            instance ?: synchronized(this) {
                instance ?: VoiceEngineManager(context.applicationContext).also { instance = it }
            }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val indicTinyEngine = IndicTinyTtsEngine(appContext)
    private val chatterboxClient = ChatterboxTtsClient(appContext)
    private val voiceProfileManager = VoiceProfileManager.getInstance(appContext)
    private val player = GeminiAudioPlayer(appContext, onSpeakingStateChanged = { speaking ->
        updateSpeakingState(speaking)
    })
    private val synthesisMutex = Mutex()
    private var activeSpeechJob: Job? = null
    private val activeSpeechJobs = java.util.Collections.synchronizedSet(mutableSetOf<Job>())

    @Volatile var isSpeaking: Boolean = false
        private set

    var onSpeakingStateChanged: ((Boolean) -> Unit)? = null
    var onSpeechError: ((String) -> Unit)? = null

    fun getActiveEngine(): VoiceEngine =
        VoiceEngine.fromId(AppPreferences.getSpeechEngine(appContext))

    fun setActiveEngine(engine: VoiceEngine) {
        AppPreferences.setSpeechEngine(appContext, engine.id)
        Log.i(TAG, "Active voice engine set to: " + engine.displayName)
    }

    fun speak(
        text: String,
        enqueue: Boolean = false,
        onComplete: (() -> Unit)? = null,
        speed: Float = 1.0f,
        pitch: Float = 1.0f,
        emotion: String = "natural"
    ) {
        speakUsingEngine(text, getActiveEngine(), enqueue, onComplete, speed, pitch, emotion)
    }

    private fun speakUsingEngine(
        text: String,
        engine: VoiceEngine,
        enqueue: Boolean,
        onComplete: (() -> Unit)?,
        speed: Float = 1.0f,
        pitch: Float = 1.0f,
        emotion: String = "natural"
    ) {
        if (text.isBlank()) {
            onComplete?.invoke()
            return
        }
        if (!enqueue) stop()

        Log.d(TAG, "Routing speech through " + engine.displayName)
        val speechJob = scope.launch(Dispatchers.IO) {
            var playbackQueued = false
            try {
                synthesisMutex.withLock {
                    updateSpeakingState(true)
                    when (engine) {
                        VoiceEngine.INDIC_TINY_TTS -> {
                            val pcm = indicTinyEngine.synthesizeSpeech(text, speed = speed.coerceIn(0.25f, 4.0f))
                            if (pcm.isEmpty()) {
                                throw IllegalStateException("Indic Tiny TTS returned empty audio.")
                            }
                            player.playPcm(
                                pcmBytes = pcm,
                                sampleRate = IndicTinyTtsEngine.SAMPLE_RATE,
                                enqueue = enqueue,
                                onComplete = onComplete
                            )
                            playbackQueued = true
                        }
                        VoiceEngine.CHATTERBOX -> {
                            val activeProfile = voiceProfileManager.getActiveProfile()
                            when (val result = chatterboxClient.synthesize(
                                text = text,
                                referenceAudioPath = activeProfile.audioFilePath,
                                pitchMultiplier = pitch,
                                speedMultiplier = speed,
                                emotion = emotion
                            )) {
                                is ChatterboxResult.Success -> {
                                    player.playWav(
                                        wavBytes = result.audioBytes,
                                        enqueue = enqueue,
                                        onComplete = onComplete
                                    )
                                    playbackQueued = true
                                }
                                is ChatterboxResult.Error -> {
                                    throw IllegalStateException(result.message)
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                updateSpeakingState(false)
                if (!playbackQueued) withContext(Dispatchers.Main) { onComplete?.invoke() }
                throw cancelled
            } catch (e: Exception) {
                Log.e(TAG, "Speech synthesis failed with " + engine.displayName, e)
                if (!player.isSpeaking()) updateSpeakingState(false)
                withContext(Dispatchers.Main) {
                    onSpeechError?.invoke(e.message ?: "Speech generation failed.")
                    if (!playbackQueued) onComplete?.invoke()
                }
            }
        }
        activeSpeechJob = speechJob
        activeSpeechJobs.add(speechJob)
        speechJob.invokeOnCompletion { activeSpeechJobs.remove(speechJob) }
    }

    fun previewEngine(
        engine: VoiceEngine,
        sampleText: String = PREVIEW_SAMPLE_HINDI,
        onComplete: (() -> Unit)? = null
    ) {
        speakUsingEngine(sampleText, engine, enqueue = false, onComplete = onComplete, speed = 1.0f, pitch = 1.0f, emotion = "natural")
    }

    fun stop() {
        val jobs = synchronized(activeSpeechJobs) { activeSpeechJobs.toList().also { activeSpeechJobs.clear() } }
        jobs.forEach { it.cancel() }
        activeSpeechJob = null
        player.stop()
        updateSpeakingState(false)
    }

    private fun updateSpeakingState(speaking: Boolean) {
        if (isSpeaking == speaking) return
        isSpeaking = speaking
        MicrophoneEngine.getInstance(appContext).setAssistantSpeaking(speaking)
        scope.launch(Dispatchers.Main) {
            onSpeakingStateChanged?.invoke(speaking)
        }
    }
}
