package com.example.ai.offline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Thin Kotlin/JNI bridge to the official llama.cpp runtime.
 * Supports GGUF models without hard-coding a model family.
 */
class LlamaCppEngine private constructor() {
    companion object {
        @Volatile private var instance: LlamaCppEngine? = null

        fun getInstance(): LlamaCppEngine =
            instance ?: synchronized(this) {
                instance ?: LlamaCppEngine().also { instance = it }
            }

        init {
            System.loadLibrary("kavya_llama")
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
