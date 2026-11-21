package com.example.orchestrator

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import android.util.Log
import com.example.ai.AiErrorClassifier
import com.example.ai.Content
import com.example.ai.GeminiModelRegistry
import com.example.ai.GenerateContentRequest
import com.example.ai.GenerationConfig
import com.example.ai.InlineData
import com.example.ai.Part
import com.example.ai.RetrofitClient
import com.example.ai.StructuredRoutingStatus
import com.example.ai.TaskRouter
import com.example.ai.offline.Gemma4E4BEngine
import com.example.ai.offline.GemmaModelManager
import com.example.utils.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class ProviderInvocationRequest(
    val providerId: String,
    val modelId: String,
    val apiKey: String,
    val customEndpointUrl: String = "",
    val prompt: String,
    val systemInstruction: String,
    val imageBytes: ByteArray? = null,
    val temperature: Double = 0.2
)

sealed class ProviderInvocationResult {
    data class Success(
        val text: String,
        val providerId: String,
        val modelId: String
    ) : ProviderInvocationResult()

    data class Failure(
        val code: String,
        val message: String,
        val isRateLimitOrQuota: Boolean = false,
        val isAuthFailure: Boolean = false,
        val isNetworkError: Boolean = false
    ) : ProviderInvocationResult()
}

interface ProviderTransport {
    suspend fun invoke(context: Context, request: ProviderInvocationRequest): ProviderInvocationResult
}

/**
 * Unified AI provider client that routes to the user's selected online provider when online,
 * enforces a hard zero-network boundary in strict privacy/offline mode, and automatically
 * falls back to the verified local `SmolVLM2` (`Q4_K_M` + `mmproj-f16`) planning adapter ONLY
 * when temporary network loss occurs and the local multimodal package is installed and `READY`.
 */
object SelectedProviderClient {
    private const val TAG = "SelectedProviderClient"

    @Volatile
    var testTransport: ProviderTransport? = null

    @Volatile
    var testNetworkConnectedOverride: Boolean? = null

    fun isNetworkConnected(context: Context): Boolean {
        testNetworkConnectedOverride?.let { return it }
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    fun resolveSelectedProviderAndModel(context: Context): Pair<String, String> {
        val rawProvider = AppPreferences.getAiProvider(context).trim().uppercase(Locale.ROOT)
        val providerId = if (rawProvider == "CUSTOM_API") "CUSTOM" else rawProvider
        val modelId = when (providerId) {
            "GEMINI" -> AppPreferences.getGeminiModel(context).ifBlank { GeminiModelRegistry.DEFAULT_AGENT_MODEL }
            "OPENROUTER" -> AppPreferences.getOpenRouterModel(context).ifBlank { "openrouter/free" }
            "HUGGINGFACE" -> AppPreferences.getHuggingFaceModel(context)
            "CUSTOM" -> AppPreferences.getCustomApis(context).firstOrNull { it.isEnabled }?.model.orEmpty()
                .ifBlank { "custom-model" }
            "LOCAL_MULTIMODAL", "GEMMA_OFFLINE" -> {
                if (Gemma4E4BEngine.getInstance(context).isSmolVlm2Ready()) {
                    GemmaModelManager.SMOLVLM2_MAIN_GGUF_FILENAME
                } else {
                    providerId
                }
            }
            else -> providerId
        }
        return Pair(providerId, modelId)
    }

    fun resolveApiKey(context: Context, providerId: String): String {
        val rawNorm = providerId.trim().uppercase(Locale.ROOT)
        val normalized = if (rawNorm == "CUSTOM_API") "CUSTOM" else rawNorm
        val raw = when (normalized) {
            "GEMINI" -> AppPreferences.getEffectiveApiKey(context)
            "OPENROUTER" -> AppPreferences.getOpenRouterApiKey(context)
            "HUGGINGFACE" -> AppPreferences.getHuggingFaceApiKey(context)
            "CUSTOM" -> AppPreferences.getCustomApis(context).firstOrNull { it.isEnabled }?.apiKey.orEmpty()
            else -> ""
        }.trim()
        return if (AppPreferences.isPlaceholderKey(raw)) "" else raw
    }

    fun resolveCapabilities(providerId: String, modelId: String): AiProviderCapabilities {
        val p = providerId.trim().uppercase(Locale.ROOT)
        val m = modelId.trim().lowercase(Locale.ROOT)
        val isOnline = p in setOf("GEMINI", "OPENROUTER", "HUGGINGFACE", "CUSTOM", "OPENAI")
        val isLocalMultimodal = p == "LOCAL_MULTIMODAL" || m.contains("smolvlm2")

        val supportsVision = when {
            p == "GEMINI" -> true
            isLocalMultimodal -> true
            p == "OPENROUTER" || p == "OPENAI" -> {
                m.contains("vision") || m.contains("-vl") || m.contains("vl-") ||
                    m.contains("gpt-4o") || m.contains("gpt-4.1") || m.contains("gpt-5") ||
                    m.contains("gemini") || m.contains("claude-3") || m.contains("claude-sonnet-4") ||
                    m.contains("claude-opus-4") || m.contains("gemma-3") || m.contains("pixtral") ||
                    m.contains("llava") || m.contains("llama-3.2-11b") || m.contains("llama-3.2-90b") ||
                    m == "openrouter/free"
            }
            p == "HUGGINGFACE" -> m.contains("vision") || m.contains("-vl") || m.contains("llava") || m.contains("qwen2.5-vl")
            p == "CUSTOM" -> m.contains("vision") || m.contains("-vl") || m.contains("gpt-4o") || m.contains("gemini") || m.contains("claude-3")
            else -> false
        }

        return AiProviderCapabilities(
            providerId = p,
            modelId = modelId,
            isOnlineProvider = isOnline,
            supportsVision = supportsVision,
            supportsStructuredJson = isOnline || isLocalMultimodal
        )
    }

    data class ConfiguredVisionProvider(
        val providerId: String,
        val modelId: String,
        val apiKey: String,
        val customEndpointUrl: String = ""
    )

    /**
     * Dynamically discovers ALL configured, enabled, vision-capable cloud providers without hardcoding
     * any single provider. Orders the user's selected provider first (if vision-capable & enabled),
     * followed by any other configured, enabled vision-capable cloud providers.
     */
    fun discoverConfiguredVisionProviders(context: Context): List<ConfiguredVisionProvider> {
        val candidates = mutableListOf<ConfiguredVisionProvider>()
        val (selectedProv, selectedModel) = resolveSelectedProviderAndModel(context)

        fun tryAdd(providerId: String, modelId: String, apiKey: String, customUrl: String = "", enabled: Boolean = true) {
            if (!enabled) return
            val cleanKey = apiKey.trim()
            if (providerId != "CUSTOM" && (cleanKey.isBlank() || AppPreferences.isPlaceholderKey(cleanKey))) return
            if (providerId == "CUSTOM" && customUrl.isBlank()) return
            val caps = resolveCapabilities(providerId, modelId)
            if (!caps.isOnlineProvider || !caps.supportsVision) return
            val entry = ConfiguredVisionProvider(providerId, modelId, cleanKey, customUrl)
            if (candidates.none { it.providerId == entry.providerId && it.modelId == entry.modelId && it.customEndpointUrl == entry.customEndpointUrl }) {
                candidates.add(entry)
            }
        }

        // 1. Selected provider priority
        when (selectedProv) {
            "GEMINI" -> tryAdd("GEMINI", selectedModel, resolveApiKey(context, "GEMINI"), enabled = true)
            "OPENROUTER" -> tryAdd("OPENROUTER", selectedModel, AppPreferences.getOpenRouterApiKey(context), enabled = AppPreferences.isOpenRouterEnabled(context))
            "HUGGINGFACE" -> tryAdd("HUGGINGFACE", selectedModel, AppPreferences.getHuggingFaceApiKey(context), enabled = AppPreferences.isHuggingFaceEnabled(context))
            "CUSTOM" -> AppPreferences.getCustomApis(context).filter { it.isEnabled }.forEach { custom ->
                tryAdd("CUSTOM", custom.model, custom.apiKey, custom.endpointUrl, enabled = true)
            }
        }

        // 2. Other configured & enabled vision-capable cloud providers
        tryAdd("GEMINI", AppPreferences.getGeminiModel(context).ifBlank { GeminiModelRegistry.DEFAULT_AGENT_MODEL }, resolveApiKey(context, "GEMINI"), enabled = true)
        tryAdd("OPENROUTER", AppPreferences.getOpenRouterModel(context).ifBlank { "openrouter/free" }, AppPreferences.getOpenRouterApiKey(context), enabled = AppPreferences.isOpenRouterEnabled(context))
        tryAdd("HUGGINGFACE", AppPreferences.getHuggingFaceModel(context), AppPreferences.getHuggingFaceApiKey(context), enabled = AppPreferences.isHuggingFaceEnabled(context))
        AppPreferences.getCustomApis(context).filter { it.isEnabled }.forEach { custom ->
            tryAdd("CUSTOM", custom.model, custom.apiKey, custom.endpointUrl, enabled = true)
        }

        return candidates
    }

    /**
     * Escalates visual ambiguity / low-confidence screenshots to an already-configured & enabled
     * cloud vision provider. Never hardcodes a provider, never leaks into network in strict offline mode,
     * and returns `CLOUD_PROVIDER_UNAVAILABLE` when no vision provider is available.
     */
    suspend fun invokeVisionEscalation(
        context: Context,
        prompt: String,
        systemInstruction: String,
        imageBytes: ByteArray?,
        preferredProviderId: String? = null
    ): ProviderInvocationResult = withContext(Dispatchers.IO) {
        if (AppPreferences.isOfflineFallbackEnabled(context)) {
            return@withContext ProviderInvocationResult.Failure(
                code = "CLOUD_PROVIDER_UNAVAILABLE",
                message = "Strict offline mode is enabled; cloud vision escalation disabled."
            )
        }
        if (!isNetworkConnected(context)) {
            return@withContext ProviderInvocationResult.Failure(
                code = "CLOUD_PROVIDER_UNAVAILABLE",
                message = "Network unavailable; cannot reach cloud vision provider.",
                isNetworkError = true
            )
        }
        val discovered = discoverConfiguredVisionProviders(context)
        val ordered = if (!preferredProviderId.isNullOrBlank()) {
            val match = discovered.filter { it.providerId.equals(preferredProviderId, ignoreCase = true) }
            val rest = discovered.filterNot { it.providerId.equals(preferredProviderId, ignoreCase = true) }
            match + rest
        } else {
            discovered
        }
        if (ordered.isEmpty()) {
            return@withContext ProviderInvocationResult.Failure(
                code = "CLOUD_PROVIDER_UNAVAILABLE",
                message = "No configured and enabled cloud vision provider available for escalation."
            )
        }

        var lastFailure: ProviderInvocationResult.Failure? = null
        for (provider in ordered) {
            val request = ProviderInvocationRequest(
                providerId = provider.providerId,
                modelId = provider.modelId,
                apiKey = provider.apiKey,
                customEndpointUrl = provider.customEndpointUrl,
                prompt = prompt,
                systemInstruction = systemInstruction,
                imageBytes = imageBytes
            )
            val outcome = testTransport?.invoke(context, request) ?: when (provider.providerId) {
                "GEMINI" -> invokeGemini(context, request)
                "OPENROUTER" -> invokeOpenRouter(request)
                "HUGGINGFACE" -> invokeHuggingFace(request)
                "CUSTOM" -> invokeCustom(request)
                else -> ProviderInvocationResult.Failure(
                    code = "CLOUD_PROVIDER_UNAVAILABLE",
                    message = "Unsupported cloud vision provider '${provider.providerId}'."
                )
            }
            if (outcome is ProviderInvocationResult.Success) {
                ApiStateManager.recordSuccess(provider.providerId, provider.modelId, provider.apiKey)
                return@withContext outcome
            }
            val fail = outcome as ProviderInvocationResult.Failure
            ApiStateManager.recordFailure(provider.providerId, provider.modelId, provider.apiKey, fail.code, fail.message)
            lastFailure = fail
        }

        lastFailure ?: ProviderInvocationResult.Failure(
            code = "CLOUD_PROVIDER_UNAVAILABLE",
            message = "All configured cloud vision providers failed."
        )
    }

    suspend fun invokeSelectedProvider(
        context: Context,
        prompt: String,
        systemInstruction: String,
        imageBytes: ByteArray? = null,
        requiresVision: Boolean = false
    ): ProviderInvocationResult = withContext(Dispatchers.IO) {
        val (providerId, modelId) = resolveSelectedProviderAndModel(context)
        val capabilities = resolveCapabilities(providerId, modelId)
        val apiKey = resolveApiKey(context, providerId)
        val online = isNetworkConnected(context)
        val strictOffline = AppPreferences.isOfflineFallbackEnabled(context)
        val engine = Gemma4E4BEngine.getInstance(context)

        // 1. Strict Privacy / Offline Mode: NEVER touch the network (Section 3.3)
        if (strictOffline || !capabilities.isOnlineProvider) {
            if (engine.isSmolVlm2Ready()) {
                val localReq = ProviderInvocationRequest(
                    providerId = providerId,
                    modelId = modelId,
                    apiKey = "",
                    prompt = prompt,
                    systemInstruction = systemInstruction,
                    imageBytes = imageBytes
                )
                return@withContext LocalGuiPlanningAdapter.invokeLocalPlanningOrVerification(context, localReq)
            }
            if (strictOffline && providerId != "GEMMA_OFFLINE") {
                val (localStatus, _) = TaskRouter.inspectLocalMultimodalHealth(context)
                val failCode = if (localStatus == StructuredRoutingStatus.READY) "MODEL_NOT_INSTALLED" else localStatus.name
                return@withContext ProviderInvocationResult.Failure(
                    code = failCode,
                    message = "Strict offline mode is enabled and local multimodal model status is $failCode. Zero network requests made."
                )
            }
            ApiStateManager.recordFailure(providerId, modelId, apiKey, "OFFLINE_PROVIDER_FORBIDDEN", "Selected provider is offline/local.")
            return@withContext ProviderInvocationResult.Failure(
                code = "OFFLINE_PROVIDER_FORBIDDEN",
                message = "Selected provider '$providerId' is offline/local and no verified SmolVLM2 + mmproj model is loaded."
            )
        }

        if (apiKey.isBlank() && providerId != "CUSTOM") {
            ApiStateManager.recordFailure(providerId, modelId, "", "MISSING_API_KEY", "No API key configured for '$providerId'.")
            return@withContext ProviderInvocationResult.Failure(
                code = "MISSING_API_KEY",
                message = "No API key configured for selected provider '$providerId'.",
                isAuthFailure = true
            )
        }

        // 2. Temporary Network Loss while Cloud Use is Allowed -> Fall back to verified local SmolVLM2 if READY
        if (!online) {
            if (engine.isSmolVlm2Ready()) {
                Log.i(TAG, "Network unavailable; falling back automatically to verified local SmolVLM2 GUI grounding adapter.")
                val localReq = ProviderInvocationRequest(
                    providerId = providerId,
                    modelId = modelId,
                    apiKey = "",
                    prompt = prompt,
                    systemInstruction = systemInstruction,
                    imageBytes = imageBytes
                )
                return@withContext LocalGuiPlanningAdapter.invokeLocalPlanningOrVerification(context, localReq)
            }
            val snap = ApiStateManager.recordFailure(providerId, modelId, apiKey, "NETWORK_UNAVAILABLE", "No active internet connection.")
            return@withContext ProviderInvocationResult.Failure(
                code = "NETWORK_UNAVAILABLE",
                message = snap.diagnosticMessage,
                isNetworkError = true
            )
        }

        if (requiresVision && !capabilities.supportsVision) {
            ApiStateManager.recordFailure(providerId, modelId, apiKey, "CAPABILITY_MISMATCH_NO_VISION", "Model does not support vision.")
            return@withContext ProviderInvocationResult.Failure(
                code = "CAPABILITY_MISMATCH_NO_VISION",
                message = "Selected model '$modelId' on '$providerId' does not support vision analysis."
            )
        }

        ApiStateManager.markValidating(providerId, modelId, apiKey)

        val customEndpoint = if (providerId == "CUSTOM") {
            AppPreferences.getCustomApis(context).firstOrNull { it.isEnabled }?.endpointUrl.orEmpty()
        } else ""

        val request = ProviderInvocationRequest(
            providerId = providerId,
            modelId = modelId,
            apiKey = apiKey,
            customEndpointUrl = customEndpoint,
            prompt = prompt,
            systemInstruction = systemInstruction,
            imageBytes = if (capabilities.supportsVision) imageBytes else null
        )

        val outcome = testTransport?.invoke(context, request) ?: when (providerId) {
            "GEMINI" -> invokeGemini(context, request)
            "OPENROUTER" -> invokeOpenRouter(request)
            "HUGGINGFACE" -> invokeHuggingFace(request)
            "CUSTOM" -> invokeCustom(request)
            else -> ProviderInvocationResult.Failure(
                code = "UNSUPPORTED_PROVIDER",
                message = "Unsupported selected provider '$providerId'."
            )
        }

        // If online transport failed due to transient network error and local SmolVLM2 is READY, fall back locally
        if (outcome is ProviderInvocationResult.Failure && outcome.isNetworkError && engine.isSmolVlm2Ready()) {
            Log.i(TAG, "Online request failed with network error (${outcome.code}); falling back to local SmolVLM2 adapter.")
            return@withContext LocalGuiPlanningAdapter.invokeLocalPlanningOrVerification(context, request)
        }

        when (outcome) {
            is ProviderInvocationResult.Success -> ApiStateManager.recordSuccess(providerId, modelId, apiKey)
            is ProviderInvocationResult.Failure -> ApiStateManager.recordFailure(providerId, modelId, apiKey, outcome.code, outcome.message)
        }

        outcome
    }

    private suspend fun invokeGemini(
        context: Context,
        request: ProviderInvocationRequest
    ): ProviderInvocationResult {
        val parts = mutableListOf<Part>()
        val img = request.imageBytes
        if (img != null && img.isNotEmpty()) {
            parts.add(
                Part(
                    inlineData = InlineData(
                        mimeType = "image/jpeg",
                        data = Base64.encodeToString(img, Base64.NO_WRAP)
                    )
                )
            )
        }
        parts.add(Part(text = request.prompt))

        val apiReq = GenerateContentRequest(
            systemInstruction = Content(role = "user", parts = listOf(Part(text = request.systemInstruction))),
            contents = listOf(Content(role = "user", parts = parts)),
            generationConfig = GenerationConfig(temperature = 0.15f, maxOutputTokens = 2048)
        )

        val candidateModels = GeminiModelRegistry.resolveApiModelFallbackChain(
            request.modelId.ifBlank { GeminiModelRegistry.DEFAULT_AGENT_MODEL }
        )

        var lastThrowable: Throwable? = null
        for (candidateModel in candidateModels) {
            try {
                val response = RetrofitClient.service.generateContent(
                    model = candidateModel,
                    apiKey = request.apiKey,
                    request = apiReq
                )
                val text = response.candidates?.firstOrNull()?.content?.parts
                    ?.mapNotNull { part -> part.text }
                    ?.joinToString("\n")
                    ?.trim()
                    .orEmpty()
                return if (text.isBlank()) {
                    ProviderInvocationResult.Failure("EMPTY_RESPONSE", "Gemini returned an empty response.")
                } else {
                    ProviderInvocationResult.Success(text, "GEMINI", request.modelId.ifBlank { candidateModel })
                }
            } catch (c: CancellationException) {
                throw c
            } catch (http: retrofit2.HttpException) {
                lastThrowable = http
                if (http.code() == 404) {
                    Log.w(TAG, "Gemini model '$candidateModel' returned HTTP 404; trying next official fallback model.")
                    continue
                }
                break
            } catch (t: Throwable) {
                lastThrowable = t
                break
            }
        }

        val classified = AiErrorClassifier.classifyThrowable(
            lastThrowable ?: IllegalStateException("Gemini invocation failed")
        )
        return ProviderInvocationResult.Failure(
            code = classified.category.name,
            message = classified.userFacingText,
            isRateLimitOrQuota = classified.category == com.example.ai.AiErrorCategory.QUOTA_EXHAUSTED ||
                classified.category == com.example.ai.AiErrorCategory.RATE_LIMIT,
            isAuthFailure = classified.category in setOf(
                com.example.ai.AiErrorCategory.INVALID_API_KEY,
                com.example.ai.AiErrorCategory.AUTHENTICATION_FAILURE,
                com.example.ai.AiErrorCategory.MISSING_API_KEY,
                com.example.ai.AiErrorCategory.PERMISSION_DENIED
            ),
            isNetworkError = classified.category in setOf(
                com.example.ai.AiErrorCategory.NETWORK_UNAVAILABLE,
                com.example.ai.AiErrorCategory.DNS_FAILURE,
                com.example.ai.AiErrorCategory.CONNECTION_TIMEOUT
            )
        )
    }

    private suspend fun invokeOpenRouter(request: ProviderInvocationRequest): ProviderInvocationResult {
        return try {
            val payload = JSONObject().apply {
                put("model", request.modelId.ifBlank { "openrouter/free" })
                put("temperature", 0.15)
                val messages = JSONArray()
                messages.put(JSONObject().apply {
                    put("role", "system")
                    put("content", request.systemInstruction)
                })
                val img = request.imageBytes
                if (img != null && img.isNotEmpty()) {
                    val b64 = Base64.encodeToString(img, Base64.NO_WRAP)
                    val contentArr = JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", request.prompt)
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$b64")
                            })
                        })
                    }
                    messages.put(JSONObject().apply {
                        put("role", "user")
                        put("content", contentArr)
                    })
                } else {
                    messages.put(JSONObject().apply {
                        put("role", "user")
                        put("content", request.prompt)
                    })
                }
                put("messages", messages)
            }

            val req = Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .addHeader("Authorization", "Bearer ${request.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            RetrofitClient.okHttpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return ProviderInvocationResult.Failure(
                        code = "OPENROUTER_HTTP_${resp.code}",
                        message = "OpenRouter error (${resp.code}): ${raw.take(180)}",
                        isRateLimitOrQuota = resp.code == 429 || resp.code == 402,
                        isAuthFailure = resp.code == 401 || resp.code == 403
                    )
                }
                val json = JSONObject(raw)
                val content = json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    ?.trim()
                    .orEmpty()
                if (content.isBlank()) {
                    ProviderInvocationResult.Failure("EMPTY_RESPONSE", "OpenRouter returned an empty message.")
                } else {
                    ProviderInvocationResult.Success(content, "OPENROUTER", request.modelId)
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w(TAG, "OpenRouter invocation failed: ${e.javaClass.simpleName}")
            ProviderInvocationResult.Failure(
                code = "OPENROUTER_NETWORK_ERROR",
                message = "OpenRouter network failure: ${e.message}",
                isNetworkError = true
            )
        }
    }

    private suspend fun invokeHuggingFace(request: ProviderInvocationRequest): ProviderInvocationResult {
        return try {
            val messages = JSONArray()
            if (request.systemInstruction.isNotBlank()) {
                messages.put(JSONObject().apply {
                    put("role", "system")
                    put("content", request.systemInstruction)
                })
            }
            val img = request.imageBytes
            if (img != null && img.isNotEmpty()) {
                val b64 = Base64.encodeToString(img, Base64.NO_WRAP)
                val contentArr = JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", request.prompt)
                    })
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply {
                            put("url", "data:image/jpeg;base64,$b64")
                        })
                    })
                }
                messages.put(JSONObject().apply {
                    put("role", "user")
                    put("content", contentArr)
                })
            } else {
                messages.put(JSONObject().apply {
                    put("role", "user")
                    put("content", request.prompt)
                })
            }
            val payload = JSONObject().apply {
                put("model", request.modelId)
                put("messages", messages)
                put("max_tokens", 800)
                put("temperature", 0.2)
            }
            val url = "https://router.huggingface.co/v1/chat/completions"
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${request.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            RetrofitClient.okHttpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return ProviderInvocationResult.Failure(
                        code = "HF_HTTP_${resp.code}",
                        message = "HuggingFace error (${resp.code}): ${raw.take(180)}",
                        isRateLimitOrQuota = resp.code == 429,
                        isAuthFailure = resp.code == 401 || resp.code == 403
                    )
                }
                val text = JSONObject(raw)
                    .optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content", "")
                    ?.trim().orEmpty()
                if (text.isBlank()) {
                    ProviderInvocationResult.Failure("EMPTY_RESPONSE", "HuggingFace returned empty text.")
                } else {
                    ProviderInvocationResult.Success(text, "HUGGINGFACE", request.modelId)
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            ProviderInvocationResult.Failure(
                code = "HF_NETWORK_ERROR",
                message = "HuggingFace network failure: ${e.message}",
                isNetworkError = true
            )
        }
    }

    private suspend fun invokeCustom(request: ProviderInvocationRequest): ProviderInvocationResult {
        if (request.customEndpointUrl.isBlank()) {
            return ProviderInvocationResult.Failure(
                code = "MISSING_CUSTOM_ENDPOINT",
                message = "No active Custom API endpoint configured.",
                isAuthFailure = true
            )
        }
        return try {
            val payload = JSONObject().apply {
                if (request.modelId.isNotBlank()) put("model", request.modelId)
                put("temperature", 0.15)
                val messages = JSONArray()
                messages.put(JSONObject().apply {
                    put("role", "system")
                    put("content", request.systemInstruction)
                })
                val img = request.imageBytes
                if (img != null && img.isNotEmpty()) {
                    val b64 = Base64.encodeToString(img, Base64.NO_WRAP)
                    val contentArr = JSONArray().apply {
                        put(JSONObject().apply { put("type", "text"); put("text", request.prompt) })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply { put("url", "data:image/jpeg;base64,$b64") })
                        })
                    }
                    messages.put(JSONObject().apply { put("role", "user"); put("content", contentArr) })
                } else {
                    messages.put(JSONObject().apply { put("role", "user"); put("content", request.prompt) })
                }
                put("messages", messages)
            }

            val reqBuilder = Request.Builder()
                .url(request.customEndpointUrl.trim())
                .addHeader("Content-Type", "application/json")
            if (request.apiKey.isNotBlank()) {
                reqBuilder.addHeader("Authorization", "Bearer ${request.apiKey}")
                reqBuilder.addHeader("api-key", request.apiKey)
            }
            reqBuilder.post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

            RetrofitClient.okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return ProviderInvocationResult.Failure(
                        code = "CUSTOM_HTTP_${resp.code}",
                        message = "Custom API error (${resp.code}): ${raw.take(180)}",
                        isRateLimitOrQuota = resp.code == 429 || resp.code == 402,
                        isAuthFailure = resp.code == 401 || resp.code == 403
                    )
                }
                val json = JSONObject(raw)
                val content = json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    ?.trim()
                    ?: json.optString("text", "").trim()
                if (content.isBlank()) {
                    ProviderInvocationResult.Failure("EMPTY_RESPONSE", "Custom API returned empty content.")
                } else {
                    ProviderInvocationResult.Success(content, "CUSTOM", request.modelId)
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            ProviderInvocationResult.Failure(
                code = "CUSTOM_NETWORK_ERROR",
                message = "Custom API network failure: ${e.message}",
                isNetworkError = true
            )
        }
    }
}
