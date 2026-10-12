package com.example.ai.providers

import android.content.Context
import com.example.ai.offline.Gemma4E4BEngine
import com.example.ai.offline.GemmaModelManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll

/**
 * On-Device Local AI Offline AI Provider.
 * Implements AIProvider interface cleanly to integrate into Kavya's multi-provider orchestration.
 *
 * Identity: gemma_offline
 * Display Name: Local AI (Offline)
 */
class GemmaOfflineProvider : AIProvider {

    override val providerId: String = "gemma_offline"
    override val displayName: String = "Local AI (Offline)"
    override val isPrimaryOrchestrator: Boolean = false

    override fun isConfigured(context: Context): Boolean {
        val modelFile = GemmaModelManager.getModelFile(context)
        if (modelFile == null || !modelFile.exists()) return false
        val info = GemmaModelManager.inspectModel(modelFile)
        return info.isSupported && Gemma4E4BEngine.getInstance().isReady()
    }

    override suspend fun validateCredentials(context: Context): Pair<Boolean, String> {
        val modelFile = GemmaModelManager.getModelFile(context)
        if (modelFile == null || !modelFile.exists()) {
            return Pair(false, "Local selected local model model file not found. Please import a compatible model in Settings.")
        }

        val info = GemmaModelManager.inspectModel(modelFile)
        if (!info.isSupported) {
            return Pair(false, "Model file found but unsupported: ${info.validationMessage}")
        }

        val engine = Gemma4E4BEngine.getInstance()
        if (!engine.isReady()) {
            val initRes = engine.initialize(context)
            if (initRes.isFailure) {
                val err = initRes.exceptionOrNull()?.localizedMessage ?: "Runtime initialization failed."
                return Pair(false, "Gemma model is installed but runtime initialization failed: $err")
            }
        }

        return Pair(true, "selected local model is loaded and responding offline.")
    }

    override suspend fun getModels(context: Context): List<ProviderModelInfo> {
        return listOf(
            ProviderModelInfo(
                id = "local-offline",
                name = "Local AI (Offline)",
                provider = ProviderType.GEMMA_OFFLINE,
                supportedTasks = listOf("chat", "reasoning", "phone_control", "offline_reasoning"),
                contextLength = 2048,
                description = "On-device offline LLM runtime supporting GGUF, LiteRT-LM and MediaPipe Task models.",
                isFree = true
            )
        )
    }

    override suspend fun generate(
        prompt: String,
        systemInstruction: String?,
        context: Context
    ): AIProviderResult {
        val engine = Gemma4E4BEngine.getInstance()
        if (!engine.isReady()) {
            val initRes = engine.initialize(context)
            if (initRes.isFailure) {
                val err = initRes.exceptionOrNull()?.localizedMessage ?: "selected local model engine is not ready."
                return AIProviderResult(
                    success = false,
                    text = "selected local model is not ready: $err",
                    providerId = providerId,
                    model = "selected local model",
                    error = err
                )
            }
        }

        val startTime = System.currentTimeMillis()
        val result = engine.generate(prompt, systemInstruction)
        val latency = System.currentTimeMillis() - startTime

        return if (result.isSuccess) {
            AIProviderResult(
                success = true,
                text = result.getOrDefault(""),
                rawJson = null,
                providerId = providerId,
                model = "selected local model",
                latencyMs = latency
            )
        } else {
            val err = result.exceptionOrNull()?.localizedMessage ?: "Generation failed."
            AIProviderResult(
                success = false,
                text = "Gemma Offline Generation Error: $err",
                rawJson = null,
                providerId = providerId,
                model = "selected local model",
                error = err,
                latencyMs = latency
            )
        }
    }

    override fun streamGenerate(
        prompt: String,
        systemInstruction: String?,
        context: Context,
        modelOverride: String?
    ): Flow<String> = flow {
        val engine = Gemma4E4BEngine.getInstance()
        if (!engine.isReady()) {
            val initRes = engine.initialize(context)
            if (initRes.isFailure) {
                emit("LOCAL OFFLINE ERROR: ${initRes.exceptionOrNull()?.localizedMessage ?: "Runtime initialization failed."}")
                return@flow
            }
        }
        emitAll(engine.streamGenerate(prompt, systemInstruction, context))
    }
}
