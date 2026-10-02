package com.example.ai.local

import android.content.Context
import android.util.Log
import com.example.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Local Inference Engine Facade for Qwen3-4B-Q4_K_M.gguf on Android.
 * Delegates 100% of tensor memory-mapping, ChatML prompt encoding, and token streaming
 * to [QwenGgufTensorEngine].
 */
class QwenLocalInferenceEngine private constructor(private val context: Context) {

    companion object {
        private const val TAG = "KavyaQwenInference"

        @Volatile
        private var instance: QwenLocalInferenceEngine? = null

        fun getInstance(context: Context): QwenLocalInferenceEngine {
            return instance ?: synchronized(this) {
                instance ?: QwenLocalInferenceEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    private val ggufTensorEngine = QwenGgufTensorEngine.getInstance(context)

    /**
     * Initializes and verifies the local Qwen3-4B GGUF model file.
     */
    suspend fun initialize(filePath: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext ggufTensorEngine.initialize(filePath)
    }

    fun isLoaded(): Boolean = ggufTensorEngine.isReady()

    /**
     * Unloads the model and releases local tensor buffers.
     */
    suspend fun unload() = withContext(Dispatchers.IO) {
        ggufTensorEngine.unload()
    }

    /**
     * Generates a non-streaming response from real Qwen3-4B tensor engine.
     */
    suspend fun chat(
        prompt: String,
        history: List<MessageEntity> = emptyList(),
        screenContext: String? = null,
        memoryContext: String = ""
    ): String = withContext(Dispatchers.IO) {
        return@withContext ggufTensorEngine.chat(
            prompt = prompt,
            history = history,
            screenContext = screenContext,
            memoryContext = memoryContext
        )
    }

    /**
     * Streams generated tokens progressively from real Qwen3-4B tensor engine.
     */
    suspend fun streamChat(
        prompt: String,
        history: List<MessageEntity> = emptyList(),
        screenContext: String? = null,
        memoryContext: String = ""
    ): Flow<String> {
        return ggufTensorEngine.streamChat(
            prompt = prompt,
            history = history,
            screenContext = screenContext,
            memoryContext = memoryContext
        )
    }
}
