package com.example.ai.providers

import android.content.Context
import android.util.Log
import com.example.ai.ClassifiedTaskCategory
import com.example.ai.TaskClassifier
import com.example.ai.TaskRouter
import com.example.ai.TaskType
import com.example.utils.AppPreferences

/**
 * Requirement 7 & 8: AgentOrchestrator.
 * Implements real multi-provider orchestration with real fallback logic:
 * Primary provider -> API failure? -> Check another configured provider -> Compatible provider available? -> Use it.
 * Never fallback to a fake response.
 * If no provider is available: "No compatible AI provider is currently configured."
 */
class AgentOrchestrator(
    val geminiProvider: GeminiProvider = GeminiProvider(),
    val openRouterProvider: OpenRouterProvider = OpenRouterProvider(),
    val huggingFaceProvider: HuggingFaceProvider = HuggingFaceProvider(),
    val gemmaOfflineProvider: GemmaOfflineProvider = GemmaOfflineProvider(),
    val publicApiProvider: PublicApiToolProvider = PublicApiToolProvider(),
    var accessibilityProvider: AndroidAccessibilityToolProvider? = null
) {

    companion object {
        private const val TAG = "KavyaAgentOrchestrator"
    }

    /**
     * Returns list of all known AI providers.
     */
    fun getAllProviders(): List<AIProvider> {
        return listOf(geminiProvider, openRouterProvider, huggingFaceProvider, gemmaOfflineProvider)
    }

    /**
     * Requirement 8: Executes prompt with automatic real fallback between configured providers.
     */
    suspend fun executeWithFallback(
        prompt: String,
        systemInstruction: String? = null,
        context: Context,
        hasImage: Boolean = false,
        onEventLog: ((String) -> Unit)? = null
    ): AIProviderResult {
        val decision = TaskRouter.routeTask(prompt, hasImage, context)
        onEventLog?.invoke("ROUTER: Decision=${decision.taskType}, Provider=${decision.provider}, Model=${decision.selectedModel}")

        val preferredProvider = AppPreferences.getAiProvider(context)
        val isExplicitOffline = preferredProvider == "GEMMA_OFFLINE" || AppPreferences.isOfflineFallbackEnabled(context)

        // 1. Determine primary provider based on routing decision
        val primaryProvider: AIProvider? = when (decision.provider) {
            "GEMMA_OFFLINE" -> gemmaOfflineProvider
            "GEMINI" -> geminiProvider
            "OPENROUTER" -> openRouterProvider
            "HUGGINGFACE" -> huggingFaceProvider
            else -> {
                if (isExplicitOffline && gemmaOfflineProvider.isConfigured(context)) gemmaOfflineProvider
                else if (geminiProvider.isConfigured(context)) geminiProvider
                else if (openRouterProvider.isConfigured(context)) openRouterProvider
                else if (huggingFaceProvider.isConfigured(context)) huggingFaceProvider
                else if (gemmaOfflineProvider.isConfigured(context)) gemmaOfflineProvider
                else null
            }
        }

        if (primaryProvider == null || !primaryProvider.isConfigured(context)) {
            val missingMsg = if (isExplicitOffline || decision.provider == "GEMMA_OFFLINE") {
                "Local Gemma 4 E4B model is not installed or ready. Please import model in Settings."
            } else {
                "No compatible AI provider is currently configured. Please configure an API key or import Gemma model in Settings."
            }
            onEventLog?.invoke("ERROR: $missingMsg")
            return AIProviderResult(
                success = false,
                text = missingMsg,
                providerId = if (isExplicitOffline) "gemma_offline" else "none",
                model = if (isExplicitOffline) "Gemma 4 E4B" else "none",
                error = missingMsg
            )
        }

        // Try primary provider
        onEventLog?.invoke("REQUEST: Calling ${primaryProvider.displayName} (Model: ${decision.selectedModel ?: "default"})")
        var primaryResult: AIProviderResult
        try {
            primaryResult = primaryProvider.generate(prompt, systemInstruction, context)
        } catch (e: Exception) {
            primaryResult = AIProviderResult(
                success = false,
                text = primaryProvider.handleError(-1, e.localizedMessage),
                providerId = primaryProvider.providerId,
                model = decision.selectedModel ?: "default",
                error = e.message
            )
        }

        if (primaryResult.success) {
            onEventLog?.invoke("RESPONSE: ${primaryProvider.displayName} succeeded in ${primaryResult.latencyMs}ms")
            return primaryResult
        }

        onEventLog?.invoke("WARNING: Primary provider ${primaryProvider.displayName} failed: ${primaryResult.error}")

        // In explicit offline mode, do NOT attempt network fallbacks
        if (isExplicitOffline || primaryProvider == gemmaOfflineProvider) {
            val offlineError = primaryResult.error ?: "Gemma 4 E4B local model failed to generate response."
            return AIProviderResult(
                success = false,
                text = offlineError,
                providerId = gemmaOfflineProvider.providerId,
                model = "Gemma 4 E4B",
                error = offlineError
            )
        }

        // 2. Identify candidate fallback providers (online mode)
        val fallbackProviders = mutableListOf<AIProvider>()
        if (primaryProvider != geminiProvider && geminiProvider.isConfigured(context)) {
            fallbackProviders.add(geminiProvider)
        }
        if (primaryProvider != openRouterProvider && openRouterProvider.isConfigured(context)) {
            fallbackProviders.add(openRouterProvider)
        }
        if (primaryProvider != huggingFaceProvider && huggingFaceProvider.isConfigured(context)) {
            fallbackProviders.add(huggingFaceProvider)
        }
        if (primaryProvider != gemmaOfflineProvider && gemmaOfflineProvider.isConfigured(context)) {
            fallbackProviders.add(gemmaOfflineProvider)
        }

        for (fallback in fallbackProviders) {
            onEventLog?.invoke("FALLBACK: Attempting fallback with ${fallback.displayName}...")
            try {
                val fallbackResult = fallback.generate(prompt, systemInstruction, context)
                if (fallbackResult.success) {
                    onEventLog?.invoke("FALLBACK SUCCESS: ${fallback.displayName} responded in ${fallbackResult.latencyMs}ms")
                    return fallbackResult
                } else {
                    onEventLog?.invoke("FALLBACK FAILED: ${fallback.displayName} failed: ${fallbackResult.error}")
                }
            } catch (e: Exception) {
                onEventLog?.invoke("FALLBACK EXCEPTION: ${fallback.displayName}: ${e.message}")
            }
        }

        // If all fallbacks failed, return truthful error message
        val failureSummary = primaryResult.error ?: "All configured providers failed to respond."
        return AIProviderResult(
            success = false,
            text = failureSummary,
            providerId = primaryProvider.providerId,
            model = primaryResult.model,
            error = failureSummary
        )
    }

    /**
     * Consults OpenRouter as secondary AI partner when useful.
     */
    suspend fun consultOpenRouterPartner(
        prompt: String,
        context: Context,
        systemInstruction: String? = null
    ): String? {
        if (!openRouterProvider.isConfigured(context)) {
            return null
        }
        return try {
            val result = openRouterProvider.generate(prompt, systemInstruction, context)
            if (result.success && result.text.isNotBlank()) {
                result.text
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "OpenRouter partner consultation failed: ${e.message}")
            null
        }
    }

    /**
     * Consults Hugging Face specialized AI models when specialized analysis is needed.
     */
    suspend fun consultHuggingFaceSpecialist(
        prompt: String,
        context: Context
    ): String? {
        if (!huggingFaceProvider.isConfigured(context)) {
            return null
        }
        return try {
            val result = huggingFaceProvider.generate(prompt, null, context)
            if (result.success && result.text.isNotBlank()) {
                result.text
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Hugging Face specialist failed: ${e.message}")
            null
        }
    }

    /**
     * Queries Public APIs for real-time external data (weather, currency, etc.).
     */
    suspend fun fetchPublicApiData(query: String): String? {
        return try {
            val result = publicApiProvider.execute("fetch", mapOf("query" to query))
            if (result.success && result.output.isNotBlank()) {
                result.output
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Public API fetch failed: ${e.message}")
            null
        }
    }

    /**
     * Executes a physical device action using Android Accessibility (Hands).
     */
    suspend fun executeHandsAction(
        actionType: String,
        params: Map<String, String>
    ): ToolExecutionResult {
        val hands = accessibilityProvider
        if (hands == null) {
            return ToolExecutionResult(
                success = false,
                output = "Accessibility hands provider not attached.",
                error = "NO_HANDS"
            )
        }
        return hands.execute(actionType, params)
    }
}
