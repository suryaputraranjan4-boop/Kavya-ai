package com.example.ai.tts

import android.content.Context
import android.util.Log
import com.example.agent.MicrophoneEngine
import com.example.ai.ChatterboxTtsClient
import com.example.ai.GeminiAudioPlayer
import com.example.utils.AppPreferences
import kotlinx.coroutines.*
import java.util.Locale

enum class VoiceEngine(val id: String, val displayName: String, val isOffline: Boolean) {
    INDIC_TINY_TTS("INDIC_TINY_TTS", "Indic Tiny TTS Hindi (Female Voice)", true),
    CHATTERBOX("CHATTERBOX_HINDI", "Chatterbox Multilingual Hindi", false);

    companion object {
        fun fromId(id: String): VoiceEngine {
            return entries.find { it.id.equals(id, ignoreCase = true) } ?: INDIC_TINY_TTS
        }
    }
}

/**
 * Centralized Voice Engine Manager for Kavya AI.
 *
 * Guarantees:
 * 1. Indic Tiny TTS Hindi Female Voice is selected by default.
 * 2. ALL assistant speech across app features routes through the active engine.
 * 3. Chatterbox remains fully supported as an alternative selectable engine.
 * 4. Microphones are gated during playback to eliminate echo feedback.
 */
class VoiceEngineManager private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "VoiceEngineManager"
        const val PREVIEW_SAMPLE_HINDI = "नमस्ते! मैं काव्या हूँ, आपकी एआई सहायिका। मैं आपकी सहायता करने के लिए तैयार हूँ।"

        @Volatile
        private var instance: VoiceEngineManager? = null

        fun getInstance(context: Context): VoiceEngineManager {
            return instance ?: synchronized(this) {
                instance ?: VoiceEngineManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val indicTinyEngine = IndicTinyTtsEngine(appContext)
    private val chatterboxClient = ChatterboxTtsClient(appContext)
    private val player = GeminiAudioPlayer(appContext)

    private var activeSpeechJob: Job? = null

    @Volatile
    var isSpeaking: Boolean = false
        private set

    var onSpeakingStateChanged: ((Boolean) -> Unit)? = null

    fun getActiveEngine(): VoiceEngine {
        val savedId = AppPreferences.getSpeechEngine(appContext)
        return VoiceEngine.fromId(savedId)
    }

    fun setActiveEngine(engine: VoiceEngine) {
        AppPreferences.setSpeechEngine(appContext, engine.id)
        Log.i(TAG, "Active voice engine set to: ${engine.displayName}")
    }

    fun speak(
        text: String,
        enqueue: Boolean = false,
        onComplete: (() -> Unit)? = null
    ) {
        if (text.isBlank()) {
            onComplete?.invoke()
            return
        }

        if (!enqueue) {
            stop()
        }

        val engine = getActiveEngine()
        Log.d(TAG, "Routing speech request through engine: ${engine.displayName} (\"${text.take(30)}...\")")

        activeSpeechJob = scope.launch(Dispatchers.IO) {
            try {
                updateSpeakingState(true)

                when (engine) {
                    VoiceEngine.INDIC_TINY_TTS -> {
                        val pcmBytes = indicTinyEngine.synthesizeSpeech(text)
                        if (pcmBytes.isNotEmpty()) {
                            // Play 24kHz 16-bit mono PCM audio
                            player.playPcm(pcmBytes, sampleRate = IndicTinyTtsEngine.SAMPLE_RATE)
                        } else {
                            Log.w(TAG, "Indic Tiny TTS returned empty audio bytes")
                        }
                    }
                    VoiceEngine.CHATTERBOX -> {
                        val result = chatterboxClient.synthesize(text)
                        if (result is com.example.ai.ChatterboxResult.Success) {
                            player.playWav(result.audioBytes)
                        } else {
                            Log.w(TAG, "Chatterbox synthesis failed; fallback to Indic Tiny TTS")
                            val fallbackPcm = indicTinyEngine.synthesizeSpeech(text)
                            if (fallbackPcm.isNotEmpty()) {
                                player.playPcm(fallbackPcm, sampleRate = IndicTinyTtsEngine.SAMPLE_RATE)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error synthesizing speech with ${engine.displayName}", e)
            } finally {
                updateSpeakingState(false)
                withContext(Dispatchers.Main) {
                    onComplete?.invoke()
                }
            }
        }
    }

    fun previewEngine(engine: VoiceEngine, sampleText: String = PREVIEW_SAMPLE_HINDI, onComplete: (() -> Unit)? = null) {
        val previousEngine = getActiveEngine()
        setActiveEngine(engine)
        speak(sampleText, enqueue = false) {
            setActiveEngine(previousEngine)
            onComplete?.invoke()
        }
    }

    fun stop() {
        activeSpeechJob?.cancel()
        activeSpeechJob = null
        player.stop()
        updateSpeakingState(false)
    }

    private fun updateSpeakingState(speaking: Boolean) {
        if (isSpeaking != speaking) {
            isSpeaking = speaking
            // Gate physical microphone to prevent echo
            MicrophoneEngine.getInstance(appContext).setAssistantSpeaking(speaking)
            scope.launch(Dispatchers.Main) {
                onSpeakingStateChanged?.invoke(speaking)
            }
        }
    }
}
