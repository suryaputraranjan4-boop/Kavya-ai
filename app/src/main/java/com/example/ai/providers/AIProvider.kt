package com.example.ai.providers

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

enum class ProviderType {
    GEMINI,
    OPENROUTER,
    HUGGINGFACE,
    CUSTOM
}

data class ProviderModelInfo(
    val id: String,
    val name: String,
    val provider: ProviderType,
    val supportedTasks: List<String> = emptyList(),
    val contextLength: Int = 0,
    val description: String = "",
    val isFree: Boolean = false
)

/**
 * Result returned by an AI Provider.
 */
data class AIProviderResult(
    val success: Boolean,
    val text: String,
    val rawJson: String? = null,
    val providerId: String,
    val model: String,
    val error: String? = null,
    val latencyMs: Long = 0L
)

/**
 * Requirement 1: Clean provider abstraction interface for AI intelligence providers.
 * Follows real network communications across:
 * - GeminiProvider
 * - OpenRouterProvider
 * - HuggingFaceProvider
 */
interface AIProvider {
    val providerId: String
    val displayName: String
    val providerType: ProviderType
        get() = when (providerId.lowercase()) {
            "gemini" -> ProviderType.GEMINI
            "openrouter" -> ProviderType.OPENROUTER
            "huggingface" -> ProviderType.HUGGINGFACE
            else -> ProviderType.CUSTOM
        }
    val isPrimaryOrchestrator: Boolean

    fun isConfigured(context: Context): Boolean

    suspend fun testConnection(context: Context): Pair<Boolean, String> = validateCredentials(context)

    suspend fun validateCredentials(context: Context): Pair<Boolean, String>

    suspend fun getModels(context: Context): List<ProviderModelInfo> = emptyList()

    suspend fun generate(
        prompt: String,
        systemInstruction: String? = null,
        context: Context
    ): AIProviderResult

    fun streamGenerate(
        prompt: String,
        systemInstruction: String? = null,
        context: Context,
        modelOverride: String? = null
    ): Flow<String> = flowOf()

    fun handleError(code: Int, errorBody: String?): String {
        return when (code) {
            401 -> "Invalid API credentials."
            402 -> "Payment required or credit limit exceeded."
            403 -> "Access forbidden / invalid scope permissions."
            404 -> "Model or endpoint not found."
            429 -> "Provider rate limit reached. Trying configured fallback if compatible."
            500, 502 -> "Provider server encountered an internal error."
            503 -> "Provider or model is temporarily unavailable or loading."
            504 -> "Provider timed out."
            else -> "HTTP $code: ${errorBody?.take(100) ?: "Unknown error"}"
        }
    }
}
