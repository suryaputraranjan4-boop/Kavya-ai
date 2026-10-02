package com.example.ai.providers

import android.content.Context
import com.example.ai.offline.Gemma4E4BEngine
import com.example.ai.offline.GemmaModelManager
import kotlinx.coroutines.flow.Flow

/**
 * On-Device Gemma 4 E4B Offline AI Provider.
 * Implements AIProvider interface cleanly to integrate into Kavya's multi-provider orchestration.
 *
 * Identity: gemma_offline
 * Display Name: Gemma 4 E4B (Offline)
 */
class GemmaOfflineProvider : AIProvider {

    override val providerId: String = "gemma_offline"
    override val displayName: String = "Gemma 4 E4B (Offline)"
    override val isPrimaryOrchestrator: Boolean = false

    override fun isConfigured(context: Context): Boolean {
        val modelFile = GemmaModelManager.getModelFile(context)
        return modelFile != null && modelFile.exists() && modelFile.length() > 0
    }

    override suspend fun validateCredentials(context: Context): Pair<Boolean, String> {
        val modelFile = GemmaModelManager.getModelFile(context)
        return if (modelFile != null && modelFile.exists()) {
            Pair(true, "Gemma 4 E4B model is installed and ready locally (${modelFile.name}).")
        } else {
            Pair(false, "Local Gemma 4 E4B model file not found. Please import model file in Settings.")
        }
    }

    override suspend fun getModels(context: Context): List<ProviderModelInfo> {
        return listOf(
            ProviderModelInfo(
                id = "gemma-4-e4b",
                name = "Gemma 4 E4B (Offline)",
                provider = ProviderType.GEMMA_OFFLINE,
                supportedTasks = listOf("chat", "reasoning", "phone_control", "offline_reasoning"),
                contextLength = 2048,
                description = "Google AI Edge / LiteRT on-device offline LLM.",
                isFree = true
            )
        )
    }

    override suspend fun generate(
        prompt: String,
        systemInstruction: String?,
        context: Context
    ): AIProviderResult {
        val fullPrompt = if (!systemInstruction.isNullOrBlank()) {
            "SYSTEM: $systemInstruction\n\nUSER: $prompt\n\nASSISTANT:"
        } else {
            prompt
        }

        val engine = Gemma4E4BEngine.getInstance()
        val result = engine.generate(fullPrompt, context)

        return AIProviderResult(
            success = result.success,
            text = result.text,
            rawJson = null,
            providerId = providerId,
            model = "Gemma 4 E4B",
            error = result.error,
            latencyMs = result.latencyMs
        )
    }

    override fun streamGenerate(
        prompt: String,
        systemInstruction: String?,
        context: Context,
        modelOverride: String?
    ): Flow<String> {
        val fullPrompt = if (!systemInstruction.isNullOrBlank()) {
            "SYSTEM: $systemInstruction\n\nUSER: $prompt\n\nASSISTANT:"
        } else {
            prompt
        }

        val engine = Gemma4E4BEngine.getInstance()
        return engine.streamGenerate(fullPrompt, context)
    }
}
