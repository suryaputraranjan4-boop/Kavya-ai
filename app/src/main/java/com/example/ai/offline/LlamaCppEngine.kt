package com.example.ai.offline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * Thin Kotlin/JNI bridge to the official llama.cpp runtime.
 * Supports GGUF models without hard-coding a model family.
 */
class LlamaCppEngine private constructor() {
    companion object {
        @Volatile private var instance: LlamaCppEngine? = null

        @Volatile private var isNativeLoaded = false

        fun getInstance(): LlamaCppEngine =
            instance ?: synchronized(this) {
                instance ?: LlamaCppEngine().also { instance = it }
            }

        fun isNativeAvailable(): Boolean = isNativeLoaded

        init {
            try {
                System.loadLibrary("kavya_llama")
                isNativeLoaded = true
            } catch (_: Throwable) {
                isNativeLoaded = false
            }
        }
    }

    private var nativeHandle: Long = 0L
    private var loadedPath: String? = null
    private val nativeLock = Any()

    fun isReady(): Boolean = synchronized(nativeLock) { nativeHandle != 0L }

    suspend fun load(modelFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        synchronized(nativeLock) {
            if (!modelFile.exists() || !modelFile.isFile) {
                return@withContext Result.failure(IllegalArgumentException("GGUF model file does not exist."))
            }
            // Detect the actual binary format instead of trusting the filename extension.
            // SAF imports may preserve an unknown filename even when the contents are valid GGUF.
            val hasGgufMagic = try {
                FileInputStream(modelFile).use { input ->
                    val magic = ByteArray(4)
                    input.read(magic) == 4 &&
                        magic[0] == 'G'.code.toByte() &&
                        magic[1] == 'G'.code.toByte() &&
                        magic[2] == 'U'.code.toByte() &&
                        magic[3] == 'F'.code.toByte()
                }
            } catch (e: Exception) {
                return@withContext Result.failure(IllegalArgumentException("Unable to inspect the selected model file.", e))
            }
            if (!hasGgufMagic) {
                return@withContext Result.failure(IllegalArgumentException("Selected file does not contain a valid GGUF header."))
            }

            if (nativeHandle != 0L && loadedPath == modelFile.absolutePath) {
                return@withContext Result.success(Unit)
            }

            releaseLocked()

            val handle = nativeLoad(modelFile.absolutePath)
            if (handle == 0L) {
                return@withContext Result.failure(
                    IllegalStateException("llama.cpp could not load this GGUF model.")
                )
            }

            nativeHandle = handle
            loadedPath = modelFile.absolutePath
            Result.success(Unit)
        }
    }

    suspend fun generate(prompt: String, maxTokens: Int = 256): Result<String> =
        withContext(Dispatchers.Default) {
            synchronized(nativeLock) {
                if (nativeHandle == 0L) {
                    return@withContext Result.failure(
                        IllegalStateException("GGUF runtime is not loaded.")
                    )
                }
                val text = nativeGenerate(nativeHandle, prompt, maxTokens)
                if (text.isBlank()) {
                    Result.failure(IllegalStateException("GGUF inference returned no text."))
                } else {
                    Result.success(text.trim())
                }
            }
        }

    fun release() {
        synchronized(nativeLock) {
            releaseLocked()
        }
    }

    private fun releaseLocked() {
        if (nativeHandle != 0L) {
            nativeFree(nativeHandle)
            nativeHandle = 0L
            loadedPath = null
        }
    }

    private external fun nativeLoad(modelPath: String): Long
    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): String
    private external fun nativeFree(handle: Long)
}
