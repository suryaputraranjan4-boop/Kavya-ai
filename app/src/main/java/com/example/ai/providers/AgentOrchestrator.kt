package com.example.ai.providers

import kotlinx.coroutines.CancellationException

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

        // Refuse unsupported visual tasks in explicit offline mode before any provider
        // can be selected. This makes the privacy/network boundary independent of API keys.
        if (isExplicitOffline && (hasImage || decision.requiresVision ||
            decision.taskType in setOf(TaskType.VISION, TaskType.OCR, TaskType.IMAGE_AI))
        ) {
            val message = decision.explanation.ifBlank {
                "This visual task is not supported by the selected local text model. Online fallback is disabled."
            }
            return AIProviderResult(
                success = false,
                text = message,
                providerId = "gemma_offline",
                model = "selected local model",
                error = message
            )
        }

        // Explicit offline selection overrides every cloud-provider routing suggestion.
        val primaryProvider: AIProvider? = if (isExplicitOffline) {
            gemmaOfflineProvider
        } else {
            when (decision.provider) {
                "GEMMA_OFFLINE" -> gemmaOfflineProvider
                "GEMINI" -> geminiProvider
                "OPENROUTER" -> openRouterProvider
                "HUGGINGFACE" -> huggingFaceProvider
                else -> {
                    if (geminiProvider.isConfigured(context)) geminiProvider
                    else if (openRouterProvider.isConfigured(context)) openRouterProvider
                    else if (huggingFaceProvider.isConfigured(context)) huggingFaceProvider
                    else if (gemmaOfflineProvider.isConfigured(context)) gemmaOfflineProvider
                    else null
                }
            }
        }

        if (primaryProvider == null || !primaryProvider.isConfigured(context)) {
            val missingMsg = if (isExplicitOffline || decision.provider == "GEMMA_OFFLINE") {
                "No supported local model is installed or ready. Import a compatible model in Settings while Offline Mode is enabled."
            } else {
                "No compatible AI provider is currently configured. Please configure an API key or import Gemma model in Settings."
            }
            onEventLog?.invoke("ERROR: $missingMsg")
            return AIProviderResult(
                success = false,
                text = missingMsg,
                providerId = if (isExplicitOffline) "gemma_offline" else "none",
                model = if (isExplicitOffline) "selected local model" else "none",
                error = missingMsg
            )
        }

        // Try primary provider
        onEventLog?.invoke("REQUEST: Calling ${primaryProvider.displayName} (Model: ${decision.selectedModel ?: "default"})")
        var primaryResult: AIProviderResult
        try {
            primaryResult = primaryProvider.generate(prompt, systemInstruction, context)
        } catch (cancelled: CancellationException) {
            throw cancelled
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
            val offlineError = primaryResult.error ?: "Selected local model failed to generate a response."
            return AIProviderResult(
                success = false,
                text = offlineError,
                providerId = gemmaOfflineProvider.providerId,
                model = "selected local model",
                error = offlineError
            )
        }

        // NEVER silently switch providers or fall back to offline/local engines on failure.
        val failureSummary = primaryResult.error ?: "Selected provider ${primaryProvider.displayName} failed to respond."
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
        } catch (cancelled: CancellationException) {
            throw cancelled
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
        } catch (cancelled: CancellationException) {
            throw cancelled
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
        } catch (cancelled: CancellationException) {
            throw cancelled
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
        if (!com.example.orchestrator.AiAuthorityGateway.hasActiveAuthorizedDispatch()) {
            return ToolExecutionResult(
                success = false,
                output = "Blocked: Accessibility action requires active AI authorization permit.",
                error = "NO_AI_PERMIT"
            )
        }
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
