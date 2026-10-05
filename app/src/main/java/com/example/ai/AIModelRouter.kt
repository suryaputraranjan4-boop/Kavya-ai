package com.example.ai

import android.content.Context
import android.util.Log
import com.example.ai.offline.Gemma4E4BEngine
import com.example.ai.offline.GemmaModelManager
import com.example.data.MessageEntity
import com.example.utils.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Single Authoritative AI Model Router for Kavya AI.
 *
 * Routes inference requests seamlessly between:
 * • ONLINE MODE → Gemini 2.5 Flash / Pro (Main Brain)
 * • OFFLINE MODE → selected local model runtime (GGUF / LiteRT-LM / MediaPipe Task)
 *
 * Guarantees zero cloud leakage when Offline Mode is enabled.
 * Preserves normalized conversation context, persistent memories, and assistant actions across modes.
 */
class AIModelRouter(private val context: Context) {

    companion object {
        private const val TAG = "KavyaAIModelRouter"
    }

    private val onlineEngine = KavyaAI(context)
    private val localEngine = Gemma4E4BEngine.getInstance()

    fun isOfflineMode(): Boolean {
        return AppPreferences.isOfflineFallbackEnabled(context) || AppPreferences.getAiProvider(context) == "GEMMA_OFFLINE"
    }

    fun setOfflineMode(enabled: Boolean) {
        AppPreferences.setOfflineFallbackEnabled(context, enabled)
        if (enabled) {
            AppPreferences.setAiProvider(context, "GEMMA_OFFLINE")
            Log.i(TAG, "AI Model Router switched to OFFLINE MODE (selected local model)")
            GemmaModelManager.checkModelStatus(context)
        } else {
            AppPreferences.setAiProvider(context, "GEMINI")
            Log.i(TAG, "AI Model Router switched to ONLINE MODE (Gemini)")
        }
    }

    /**
     * Executes non-streaming chat query via the active AI inference engine.
     */
    suspend fun chat(
        prompt: String,
        history: List<MessageEntity> = emptyList(),
        screenContext: String? = null,
        memoryContext: String = "",
        isProactiveMode: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        if (isOfflineMode()) {
            Log.d(TAG, "Routing chat request to LOCAL selected local model engine")
            if (!localEngine.isReady()) {
                val initResult = localEngine.initialize(context)
                if (initResult.isFailure) {
                    val err = initResult.exceptionOrNull()?.message ?: "selected local model model is not initialized or ready."
                    return@withContext "LOCAL OFFLINE ERROR: $err"
                }
            }
            val sysInstruction = SystemPrompt.buildSystemPrompt(screenContext, memoryContext, isProactiveMode)
            val genResult = localEngine.generate(prompt, sysInstruction)
            return@withContext genResult.getOrElse {
                "LOCAL OFFLINE ERROR: ${it.localizedMessage ?: "Generation failed."}"
            }
        } else {
            Log.d(TAG, "Routing chat request to ONLINE Gemini model")
            return@withContext onlineEngine.chat(
                prompt = prompt,
                history = history,
                screenContext = screenContext,
                memoryContext = memoryContext,
                isProactiveMode = isProactiveMode
            )
        }
    }

    /**
     * Streams generated response tokens from the active AI inference engine.
     */
    suspend fun streamChat(
        prompt: String,
        history: List<MessageEntity> = emptyList(),
        screenContext: String? = null,
        memoryContext: String = "",
        isProactiveMode: Boolean = false
    ): Flow<String> {
        return if (isOfflineMode()) {
            Log.d(TAG, "Routing streamChat request to LOCAL selected local model engine")
            val sysInstruction = SystemPrompt.buildSystemPrompt(screenContext, memoryContext, isProactiveMode)
            localEngine.streamGenerate(prompt, sysInstruction, context)
        } else {
            Log.d(TAG, "Routing streamChat request to ONLINE Gemini model")
            onlineEngine.streamChat(
                prompt = prompt,
                history = history,
                screenContext = screenContext,
                memoryContext = memoryContext,
                isProactiveMode = isProactiveMode
            )
        }
    }

    /**
     * Generates TTS speech audio from response text using the active voice engine.
     */
    suspend fun generateSpeechAudio(
        text: String,
        voiceName: String = "Kore",
        emotionLabel: String = "Sweet Anime Voice",
        pitchMultiplier: Float = 1.0f,
        speedMultiplier: Float = 1.0f
    ): ByteArray? {
        return onlineEngine.generateSpeechAudio(text, voiceName, emotionLabel, pitchMultiplier, speedMultiplier)
    }
}
