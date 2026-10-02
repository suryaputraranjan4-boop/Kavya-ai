package com.example.ai.local

import android.content.Context
import com.example.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Main Kotlin Facade for Qwen3-4B-Q4_K_M.gguf Offline AI Inference on Android.
 * Delegates 100% of native model loading, ChatML prompt construction, persistent memory injection,
 * and token streaming to [NativeQwenInferenceBridge].
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

    private val nativeBridge = NativeQwenInferenceBridge.getInstance(context)

    /**
     * Initializes and verifies the local Qwen3-4B GGUF model file.
     */
    suspend fun initialize(filePath: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext nativeBridge.loadModel(filePath)
    }

    fun isLoaded(): Boolean = nativeBridge.isLoaded()

    /**
     * Unloads the model and releases local tensor buffers.
     */
    suspend fun unload() = withContext(Dispatchers.IO) {
        nativeBridge.unloadModel()
    }

    /**
     * Cancels active token generation.
     */
    fun cancelGeneration() {
        nativeBridge.cancelGeneration()
    }

    /**
     * Generates a non-streaming response from native Qwen3-4B inference backend.
     */
    suspend fun chat(
        prompt: String,
        history: List<MessageEntity> = emptyList(),
        screenContext: String? = null,
        memoryContext: String = ""
    ): String = withContext(Dispatchers.IO) {
        return@withContext nativeBridge.chat(
            prompt = prompt,
            history = history,
            screenContext = screenContext,
            memoryContext = memoryContext
        )
    }

    /**
     * Streams generated tokens progressively from native Qwen3-4B inference backend.
     */
    suspend fun streamChat(
        prompt: String,
        history: List<MessageEntity> = emptyList(),
        screenContext: String? = null,
        memoryContext: String = ""
    ): Flow<String> {
        return nativeBridge.streamChat(
            prompt = prompt,
            history = history,
            screenContext = screenContext,
            memoryContext = memoryContext
        )
    }
}
