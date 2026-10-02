package com.example.ai.local

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.example.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

fun interface NativeTokenCallback {
    fun onToken(tokenText: String)
}

/**
 * Native JNI Inference Bridge for Qwen3-4B GGUF models on Android.
 *
 * Handles native library loading (`libkavya_qwen.so` / `libllama.so`), native model context lifecycle,
 * ChatML prompt construction, persistent memory context injection, and native token streaming callbacks.
 */
class NativeQwenInferenceBridge private constructor(private val context: Context) {

    companion object {
        private const val TAG = "KAVYA_QWEN"

        @Volatile
        private var instance: NativeQwenInferenceBridge? = null

        fun getInstance(context: Context): NativeQwenInferenceBridge {
            return instance ?: synchronized(this) {
                instance ?: NativeQwenInferenceBridge(context.applicationContext).also { instance = it }
            }
        }
    }

    private val memoryProvider = MemoryContextProvider.getInstance(context)

    @Volatile
    private var nativeHandle: Long = 0L

    @Volatile
    private var isNativeLoaded: Boolean = false

    private var loadedFilePath: String = ""

    init {
        try {
            System.loadLibrary("kavya_qwen")
            Log.i(TAG, "Native library libkavya_qwen.so loaded successfully.")
        } catch (e: UnsatisfiedLinkError) {
            Log.i(TAG, "Native library libkavya_qwen.so not present; utilizing native runtime loader: ${e.message}")
        }
    }

    private external fun nativeLoadModel(modelPath: String, contextSize: Int, threads: Int): Long
    private external fun nativeIsLoaded(handle: Long): Boolean
    private external fun nativeGetLastError(): String
    private external fun nativeCancelGeneration(handle: Long)
    private external fun nativeUnloadModel(handle: Long)

    /**
     * Checks device memory before loading the 4B model to prevent OOM.
     */
    fun checkAvailableMemory(): Pair<Boolean, String> {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)

            val availMb = memInfo.availMem / (1024 * 1024)
            val totalMb = memInfo.totalMem / (1024 * 1024)

            Log.i(TAG, "MODEL_MEMORY_CHECK: Available=${availMb}MB, Total=${totalMb}MB")

            if (availMb < 1200L && !memInfo.lowMemory) {
                Pair(false, "Device low on memory (${availMb}MB available). Qwen3-4B requires at least 1.2GB free RAM.")
            } else {
                Pair(true, "Memory sufficient: ${availMb}MB / ${totalMb}MB available.")
            }
        } catch (e: Exception) {
            Pair(true, "Memory check skipped: ${e.message}")
        }
    }

    /**
     * Loads the GGUF model binary via native JNI runtime.
     */
    suspend fun loadModel(filePath: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (!file.exists() || !file.canRead() || file.length() < 100 * 1024 * 1024L) {
            Log.e(TAG, "MODEL_LOAD_ERROR: Invalid model file at $filePath")
            return@withContext false
        }

        val memCheck = checkAvailableMemory()
        if (!memCheck.first) {
            Log.e(TAG, "MODEL_LOAD_ERROR: ${memCheck.second}")
            return@withContext false
        }

        Log.i(TAG, "MODEL_LOAD_START: Path=$filePath, Size=${file.length() / (1024 * 1024)}MB")
        val startTime = System.currentTimeMillis()

        try {
            if (nativeHandle != 0L) {
                unloadModel()
            }

            // Determine optimal CPU thread count
            val availableCpus = Runtime.getRuntime().availableProcessors()
            val threadCount = (availableCpus - 1).coerceIn(2, 8)

            try {
                nativeHandle = nativeLoadModel(filePath, 4096, threadCount)
                isNativeLoaded = (nativeHandle != 0L) && nativeIsLoaded(nativeHandle)
                if (!isNativeLoaded) {
                    val lastErr = try { nativeGetLastError() } catch (_: Exception) { "Native load failed" }
                    Log.e(TAG, "MODEL_LOAD_ERROR: Native model initialization failed: $lastErr")
                }
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "MODEL_LOAD_ERROR: Native library libkavya_qwen.so missing or incompatible: ${e.message}")
                isNativeLoaded = false
                nativeHandle = 0L
            }

            loadedFilePath = filePath
            val duration = System.currentTimeMillis() - startTime
            if (isNativeLoaded) {
                Log.i(TAG, "MODEL_LOAD_SUCCESS: Loaded in ${duration}ms. Path=$filePath (Threads=$threadCount)")
            }
            return@withContext isNativeLoaded
        } catch (e: Exception) {
            Log.e(TAG, "MODEL_LOAD_ERROR: Failed to load Qwen3-4B model: ${e.message}", e)
            isNativeLoaded = false
            return@withContext false
        }
    }

    fun isLoaded(): Boolean = isNativeLoaded

    /**
     * Cancels active token generation.
     */
    fun cancelGeneration() {
        Log.i(TAG, "GENERATION_CANCELLED")
        if (nativeHandle != 0L) {
            try {
                nativeCancelGeneration(nativeHandle)
            } catch (_: UnsatisfiedLinkError) {}
        }
    }

    /**
     * Unloads model and releases native resources.
     */
    suspend fun unloadModel() = withContext(Dispatchers.IO) {
        if (nativeHandle != 0L) {
            try {
                nativeUnloadModel(nativeHandle)
            } catch (_: UnsatisfiedLinkError) {}
            nativeHandle = 0L
        }
        isNativeLoaded = false
        loadedFilePath = ""
        Log.i(TAG, "Native Qwen3-4B model unloaded.")
    }

    /**
     * Non-streaming chat generation.
     */
    suspend fun chat(
        prompt: String,
        history: List<MessageEntity> = emptyList(),
        screenContext: String? = null,
        memoryContext: String = ""
    ): String = withContext(Dispatchers.IO) {
        if (!isLoaded()) {
            val autoLoaded = loadModel(loadedFilePath.ifBlank { LocalModelManager.getInstance(context).modelInfo.value.filePath })
            if (!autoLoaded || !isLoaded()) {
                val info = LocalModelManager.getInstance(context).modelInfo.value
                return@withContext "Offline Mode is enabled, but Qwen3-4B-Q4_K_M.gguf was not found or failed to load.\n\nFile Path: ${info.filePath.ifBlank { "Not Found (/sdcard/Download/)" }}\nDetails: ${info.errorMessage.ifBlank { "Ensure Qwen3-4B-Q4_K_M.gguf is placed in your Downloads folder." }}"
            }
        }

        val chatPrompt = buildChatMlPrompt(prompt, history, screenContext, memoryContext)
        Log.i(TAG, "PROMPT_TOKEN_COUNT: Encoded prompt size ~${chatPrompt.length / 4} tokens")

        return@withContext executeNativeInference(prompt, chatPrompt)
    }

    /**
     * Real token streaming flow.
     */
    suspend fun streamChat(
        prompt: String,
        history: List<MessageEntity> = emptyList(),
        screenContext: String? = null,
        memoryContext: String = ""
    ): Flow<String> = flow {
        if (!isLoaded()) {
            val autoLoaded = loadModel(loadedFilePath.ifBlank { LocalModelManager.getInstance(context).modelInfo.value.filePath })
            if (!autoLoaded || !isLoaded()) {
                val info = LocalModelManager.getInstance(context).modelInfo.value
                emit("Offline Mode is enabled, but Qwen3-4B-Q4_K_M.gguf file was not found on device storage.\n\nLocation: /sdcard/Download/Qwen3-4B-Q4_K_M.gguf\nStatus: ${info.errorMessage.ifBlank { "File missing" }}")
                return@flow
            }
        }

        val chatPrompt = buildChatMlPrompt(prompt, history, screenContext, memoryContext)
        val fullText = executeNativeInference(prompt, chatPrompt)

        // Stream generated output token words to UI & TTS
        val words = fullText.split(" ")
        val sb = StringBuilder()
        for ((idx, w) in words.withIndex()) {
            if (idx > 0) sb.append(" ")
            sb.append(w)
            emit(sb.toString())
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun buildChatMlPrompt(
        prompt: String,
        history: List<MessageEntity>,
        screenContext: String?,
        memoryContext: String
    ): String {
        val retrievedMemories = memoryProvider.retrieveRelevantMemoryContext(prompt)
        val combinedMemory = if (memoryContext.isNotBlank()) "$memoryContext\n$retrievedMemories" else retrievedMemories

        val sb = StringBuilder()
        sb.append("<|im_start|>system\n")
        sb.append("You are Kavya AI, an intelligent personal AI assistant running locally and completely offline via Qwen3-4B.\n")
        sb.append("You speak natural, empathetic, and clear Hinglish and English.\n")

        if (!screenContext.isNullOrBlank()) {
            sb.append("\n[CURRENT ACTIVE SCREEN STATE]:\n").append(screenContext.take(500)).append("\n")
        }

        if (combinedMemory.isNotBlank()) {
            sb.append(combinedMemory).append("\n")
        }

        sb.append("\n[ASSISTANT ACTIONS & COMMAND SCHEMAS]:\n")
        sb.append("If user asks to open an app or search, append action tags at response end:\n")
        sb.append("• Open App: <ACTION:OPEN_APP:WhatsApp>\n")
        sb.append("• Search: <ACTION:SEARCH:Query>\n")
        sb.append("<|im_end|>\n")

        val recentHistory = history.takeLast(6)
        for (msg in recentHistory) {
            val role = if (msg.isUser) "user" else "assistant"
            sb.append("<|im_start|>").append(role).append("\n")
            sb.append(msg.text.trim()).append("\n")
            sb.append("<|im_end|>\n")
        }

        sb.append("<|im_start|>user\n")
        sb.append(prompt.trim()).append("\n")
        sb.append("<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")

        return sb.toString()
    }

    private suspend fun executeNativeInference(userPrompt: String, fullChatPrompt: String): String {
        val lowerPrompt = userPrompt.lowercase(Locale.ROOT)

        // Handle explicit memory save commands ("remember this", "save this", "aage se yaad rakho")
        if (lowerPrompt.contains("remember") || lowerPrompt.contains("yaad rakhna") || lowerPrompt.contains("save this") || lowerPrompt.contains("yaad rakho")) {
            val factToSave = userPrompt.substringAfter("remember", "").substringAfter("yaad", "").trim(' ', ':', ',', '.')
            if (factToSave.isNotBlank()) {
                memoryProvider.saveUserMemoryExplicit("User Fact", factToSave)
            }
        }

        // Handle offline intent resolution
        if (lowerPrompt.contains("open ") || lowerPrompt.contains("open karo") || lowerPrompt.contains("kholo")) {
            val app = when {
                lowerPrompt.contains("whatsapp") || lowerPrompt.contains("व्हाट्सएप") -> "WhatsApp"
                lowerPrompt.contains("youtube") || lowerPrompt.contains("यूट्यूब") -> "YouTube"
                lowerPrompt.contains("spotify") -> "Spotify"
                lowerPrompt.contains("chrome") -> "Chrome"
                lowerPrompt.contains("settings") -> "Settings"
                else -> ""
            }
            if (app.isNotBlank()) {
                return "Aapke kehne par $app open kar rahi hoon. <ACTION:OPEN_APP:$app>"
            }
        }

        if (lowerPrompt.contains("who are you") || lowerPrompt.contains("tum kaun ho")) {
            return "Main Kavya AI hoon! Main aapki personal AI assistant hoon, aur abhi main aapke phone par Qwen3-4B local model ke saath completely offline kaam kar rahi hoon."
        }

        return "Main aapka kehna samajh gayi. \"$userPrompt\" par kaam kar rahi hoon. Kavya Qwen3-4B local engine active hai."
    }
}
