package com.example.ai

import kotlinx.coroutines.flow.flowOn

import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Header
import retrofit2.http.Headers
import java.io.IOException

@Serializable
data class GenerateContentRequest(
    val contents: List<Content>,
    val generationConfig: GenerationConfig? = null,
    val systemInstruction: Content? = null
)

@Serializable
data class Content(
    val role: String? = null,
    val parts: List<Part>
)

@Serializable
data class Part(
    val text: String? = null,
    val inlineData: InlineData? = null
)

@Serializable
data class InlineData(
    val mimeType: String? = null,
    val data: String? = null
)

@Serializable
data class ThinkingConfig(
    val thinkingBudget: Int = 0
)

@Serializable
data class GenerationConfig(
    val temperature: Float? = 0.7f,
    val topP: Float? = 0.95f,
    val topK: Int? = 40,
    val maxOutputTokens: Int? = 512,
    val thinkingConfig: ThinkingConfig? = null,
    val responseModalities: List<String>? = null,
    val speechConfig: SpeechConfig? = null
)

@Serializable
data class SpeechConfig(
    val voiceConfig: VoiceConfig
)

@Serializable
data class VoiceConfig(
    val prebuiltVoiceConfig: PrebuiltVoiceConfig
)

@Serializable
data class PrebuiltVoiceConfig(
    val voiceName: String
)

@Serializable
data class GenerateContentResponse(
    val candidates: List<Candidate>? = null
)

@Serializable
data class Candidate(
    val content: Content? = null
)

interface GeminiApiService {
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body request: GenerateContentRequest
    ): GenerateContentResponse

    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateAudioContent(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body request: GenerateContentRequest
    ): GenerateContentResponse

    @POST("v1alpha/models/{model}:generateContent")
    suspend fun generateAudioContentAlpha(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body request: GenerateContentRequest
    ): GenerateContentResponse
}

interface OpenRouterApiService {
    @POST("api/v1/chat/completions")
    @Headers("HTTP-Referer: https://aistudio.google.com", "X-Title: Kavya AI")
    suspend fun generateContent(
        @Header("Authorization") authorization: String,
        @Body request: OpenRouterRequest
    ): OpenRouterResponse
}

@Serializable
data class OpenRouterRequest(
    val model: String,
    val messages: List<OpenRouterMessage>
)

@Serializable
data class OpenRouterMessage(
    val role: String,
    val content: String
)

@Serializable
data class OpenRouterResponse(
    val choices: List<OpenRouterChoice>? = null,
    val error: OpenRouterError? = null
)

@Serializable
data class OpenRouterChoice(
    val message: OpenRouterMessage
)

@Serializable
data class OpenRouterError(
    val message: String
)

object RetrofitClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"
    private const val OPENROUTER_BASE_URL = "https://openrouter.ai/"
    
    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val url = original.url
            val key = url.queryParameter("key")
            val builder = original.newBuilder()
            if (!key.isNullOrBlank() && original.header("x-goog-api-key").isNullOrBlank()) {
                builder.header("x-goog-api-key", key)
            }
            chain.proceed(builder.build())
        }
        .build()

    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
        encodeDefaults = false
    }

    val service: GeminiApiService by lazy {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        retrofit.create(GeminiApiService::class.java)
    }

    val openRouterService: OpenRouterApiService by lazy {
        val retrofit = Retrofit.Builder()
            .baseUrl(OPENROUTER_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        retrofit.create(OpenRouterApiService::class.java)
    }
}

class KavyaAI(private val context: android.content.Context? = null) {
    fun isApiKeyConfigured(): Boolean {
        val key = if (context != null) {
            com.example.utils.AppPreferences.getEffectiveApiKey(context)
        } else {
            BuildConfig.GEMINI_API_KEY
        }
        return key.isNotBlank()
    }

    companion object {
        private const val TAG = "KavyaAI"
        // Ordered list of verified working, high-availability Gemini models
        private val MODEL_TIERS = GeminiModelRegistry.CHAT_MODEL_TIERS

        // Official Gemini models supporting audio modality generation
        private val AUDIO_MODEL_TIERS = GeminiModelRegistry.AUDIO_MODEL_TIERS

        private val modelCooldowns = java.util.concurrent.ConcurrentHashMap<String, Long>()

        fun markModelThrottled(model: String, cooldownDurationMs: Long = 30000L) {
            modelCooldowns[model] = System.currentTimeMillis() + cooldownDurationMs
            Log.w(TAG, "Model $model temporarily throttled (cooldown for ${cooldownDurationMs}ms)")
        }

        fun getAvailableModels(): List<String> {
            val now = System.currentTimeMillis()
            val available = MODEL_TIERS.filter { (modelCooldowns[it] ?: 0L) <= now }
            return if (available.isNotEmpty()) available else MODEL_TIERS
        }
    }

    fun isStrictOfflineMode(): Boolean {
        val ctx = context ?: return false
        return com.example.utils.AppPreferences.getAiProvider(ctx) == "GEMMA_OFFLINE" ||
            com.example.utils.AppPreferences.isOfflineFallbackEnabled(ctx)
    }

    suspend fun analyzeVisualUI(bitmap: android.graphics.Bitmap, prompt: String): String {
        if (isStrictOfflineMode()) {
            return "Offline Mode is active: Cloud visual analysis is disabled to guarantee zero network usage."
        }
        try {
            val key = if (context != null) {
                val secureKey = com.example.security.SecureStorage.getSecret(context, "gemini_api_key")
                if (secureKey.isNotBlank()) secureKey else com.example.utils.AppPreferences.getEffectiveApiKey(context)
            } else BuildConfig.GEMINI_API_KEY
            if (key.isBlank()) return "Error: Gemini API key missing."
            
            val byteArrayOutputStream = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, byteArrayOutputStream)
            val base64Image = android.util.Base64.encodeToString(byteArrayOutputStream.toByteArray(), android.util.Base64.NO_WRAP)
            
            val request = GenerateContentRequest(
                contents = listOf(
                    Content(
                        role = "user",
                        parts = listOf(
                            Part(text = prompt),
                            Part(inlineData = InlineData(mimeType = "image/jpeg", data = base64Image))
                        )
                    )
                ),
                generationConfig = null
            )
            
            val activeModel = getAvailableModels().firstOrNull() ?: MODEL_TIERS.first()
            val response = RetrofitClient.service.generateContent(
                model = activeModel,
                apiKey = key,
                request = request
            )
            
            return response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: "No visual result found."
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Gemini visual analysis failed", e)
            return "Visual parsing failed: ${e.message}"
        }
    }

    /**
     * Consults OpenRouter as Gemini's secondary AI partner when useful.
     * Gemini remains the boss and final decision maker.
     */
    suspend fun consultOpenRouterPartner(
        prompt: String,
        systemInstruction: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext null
        val openRouterKey = com.example.utils.AppPreferences.getOpenRouterApiKey(ctx)
        if (openRouterKey.isBlank() || !com.example.utils.AppPreferences.isOpenRouterEnabled(ctx)) return@withContext null

        val modelId = com.example.utils.AppPreferences.getOpenRouterModel(ctx).ifBlank { "openrouter/free" }
        val messages = mutableListOf<OpenRouterMessage>()
        if (!systemInstruction.isNullOrBlank()) {
            messages.add(OpenRouterMessage(role = "system", content = systemInstruction))
        }
        messages.add(OpenRouterMessage(role = "user", content = prompt))

        val startTime = System.currentTimeMillis()
        return@withContext try {
            val response = RetrofitClient.openRouterService.generateContent(
                authorization = "Bearer ${openRouterKey.trim()}",
                request = OpenRouterRequest(model = modelId, messages = messages)
            )
            val reply = response.choices?.firstOrNull()?.message?.content?.trim()
            val latency = System.currentTimeMillis() - startTime
            com.example.ai.providers.RuntimeActivityTracker.logEvent(
                context = ctx,
                providerId = "openrouter",
                model = modelId,
                task = "Secondary AI Partner",
                success = !reply.isNullOrBlank(),
                latencyMs = latency
            )
            reply
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            com.example.ai.providers.RuntimeActivityTracker.logEvent(
                context = ctx,
                providerId = "openrouter",
                model = modelId,
                task = "Secondary AI Partner",
                success = false,
                latencyMs = latency,
                errorCategory = e.message
            )
            Log.w(TAG, "OpenRouter partner consultation failed: ${e.message}")
            null
        }
    }

    /**
     * Consults Hugging Face specialized models when classification, sentiment,
     * or specialized domain inference is needed.
     * Gemini remains the boss and evaluates the result.
     */
    suspend fun consultHuggingFaceSpecialist(
        prompt: String
    ): String? = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext null
        val hfKey = com.example.utils.AppPreferences.getHuggingFaceApiKey(ctx)
        if (hfKey.isBlank() || !com.example.utils.AppPreferences.isHuggingFaceEnabled(ctx)) return@withContext null

        val model = com.example.utils.AppPreferences.getHuggingFaceModel(ctx).ifBlank { "distilbert-base-uncased" }
        val startTime = System.currentTimeMillis()
        return@withContext try {
            val url = "https://router.huggingface.co/hf-inference/models/$model"
            val jsonInput = org.json.JSONObject().apply { put("inputs", prompt) }
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = jsonInput.toString().toRequestBody(mediaType)
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${hfKey.trim()}")
                .post(body)
                .build()

            val response = RetrofitClient.okHttpClient.newCall(request).execute()
            val latency = System.currentTimeMillis() - startTime
            val raw = response.body?.string() ?: ""
            val code = response.code
            response.close()

            if (code in 200..299) {
                com.example.ai.providers.RuntimeActivityTracker.logEvent(
                    context = ctx,
                    providerId = "huggingface",
                    model = model,
                    task = "Specialized Inference",
                    success = true,
                    latencyMs = latency
                )
                raw.take(350)
            } else {
                com.example.ai.providers.RuntimeActivityTracker.logEvent(
                    context = ctx,
                    providerId = "huggingface",
                    model = model,
                    task = "Specialized Inference",
                    success = false,
                    latencyMs = latency,
                    errorCategory = "HTTP $code"
                )
                null
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            com.example.ai.providers.RuntimeActivityTracker.logEvent(
                context = ctx,
                providerId = "huggingface",
                model = model,
                task = "Specialized Inference",
                success = false,
                latencyMs = latency,
                errorCategory = e.message
            )
            null
        }
    }

    /**
     * Consults user-defined custom APIs if enabled.
     * Gemini remains the boss and synthesizes all results.
     */
    suspend fun consultCustomApis(
        prompt: String
    ): String? = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext null
        val customApis = com.example.utils.AppPreferences.getCustomApis(ctx).filter { it.isEnabled && it.endpointUrl.isNotBlank() }
        if (customApis.isEmpty()) return@withContext null

        val api = customApis.firstOrNull() ?: return@withContext null
        val startTime = System.currentTimeMillis()
        try {
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val rawUrl = api.endpointUrl.trim().trimEnd('/')
            val resolvedUrl = if (api.model.isNotBlank() && !rawUrl.endsWith("/chat/completions") && !rawUrl.contains("generateContent")) {
                "$rawUrl/chat/completions"
            } else {
                api.endpointUrl.trim()
            }
            val requestBuilder = Request.Builder().url(resolvedUrl)

            if (api.apiKey.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer ${api.apiKey.trim()}")
                requestBuilder.addHeader("api-key", api.apiKey.trim())
            }

            val jsonBody = org.json.JSONObject().apply {
                if (api.model.isNotBlank()) put("model", api.model.trim())
                put("messages", org.json.JSONArray().put(org.json.JSONObject().apply {
                    put("role", "user")
                    put("content", prompt.take(1000))
                }))
                put("max_tokens", 400)
            }
            val body = jsonBody.toString().toRequestBody(mediaType)
            requestBuilder.post(body)

            val response = RetrofitClient.okHttpClient.newCall(requestBuilder.build()).execute()
            val latency = System.currentTimeMillis() - startTime
            val raw = response.body?.string() ?: ""
            val code = response.code
            response.close()

            if (code in 200..299) {
                val parsed = try {
                    org.json.JSONObject(raw)
                        .optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("message")?.optString("content")
                } catch (_: Exception) { null }

                val text = parsed?.trim() ?: raw.take(300)
                com.example.ai.providers.RuntimeActivityTracker.logEvent(
                    context = ctx,
                    providerId = api.name.lowercase().replace(" ", "_"),
                    model = api.model.ifBlank { "custom" },
                    task = "Custom API: ${api.name}",
                    success = true,
                    latencyMs = latency
                )
                text
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun consultOpenRouterDirect(
        prompt: String,
        history: List<MessageEntity>,
        screenContext: String?,
        memoryContext: String,
        isProactiveMode: Boolean
    ): String? = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext null
        val openRouterKey = com.example.utils.AppPreferences.getOpenRouterApiKey(ctx)
        if (openRouterKey.isBlank()) return@withContext null

        val modelId = com.example.utils.AppPreferences.getOpenRouterModel(ctx).ifBlank { "openrouter/free" }
        val systemInstructionText = com.example.ai.SystemPrompt.buildSystemPrompt(screenContext, memoryContext, isProactiveMode)

        val orMessages = mutableListOf<OpenRouterMessage>()
        orMessages.add(OpenRouterMessage(role = "system", content = systemInstructionText))

        val recentHistory = history.takeLast(12)
        var lastRole = ""
        for (msg in recentHistory) {
            val role = if (msg.isUser) "user" else "assistant"
            if (msg.text.isNotBlank() && role != lastRole) {
                orMessages.add(OpenRouterMessage(role = role, content = msg.text.trim()))
                lastRole = role
            }
        }
        if (lastRole == "user") {
            orMessages.removeAt(orMessages.size - 1)
        }
        orMessages.add(OpenRouterMessage(role = "user", content = prompt.trim()))

        return@withContext try {
            val response = RetrofitClient.openRouterService.generateContent(
                authorization = "Bearer ${openRouterKey.trim()}",
                request = OpenRouterRequest(model = modelId, messages = orMessages)
            )
            response.choices?.firstOrNull()?.message?.content?.trim()
        } catch (e: Exception) {
            Log.w(TAG, "OpenRouter direct call failed: ${e.message}")
            null
        }
    }

    suspend fun chat(
        prompt: String,
        history: List<MessageEntity> = emptyList(),
        screenContext: String? = null, 
        memoryContext: String = "",
        isProactiveMode: Boolean = false,
        imageBytes: ByteArray? = null
    ): String = withContext(Dispatchers.IO) {
        val requestId = (prompt.hashCode() * 31 + history.size).toString()
        val priority = if (isProactiveMode) com.example.api.RequestPriority.P3_PROACTIVE_CONVERSATION else com.example.api.RequestPriority.P0_ACTIVE_USER_REQUEST
        
        if (!com.example.api.QuotaManager.instance.acquireQuota(requestId, priority)) {
            val localGreeting = LocalAssistantEngine.handleFastQuery(prompt)
            if (localGreeting != null) return@withContext localGreeting
            return@withContext LocalAssistantEngine.getHighTrafficFallbackResponse(prompt)
        }
        
        var lastError: Exception? = null
        var lastClassifiedError: AiClassifiedError? = null
        try {
            if (context != null) {
                val selectedProvider = com.example.utils.AppPreferences.getAiProvider(context)
                val isExplicitOffline = isStrictOfflineMode()
                val gemmaProvider = com.example.ai.providers.GemmaOfflineProvider()

                if (isExplicitOffline) {
                    if (gemmaProvider.isConfigured(context)) {
                        val sysPrompt = com.example.ai.SystemPrompt.buildSystemPrompt(screenContext, memoryContext, isProactiveMode)
                        val gemmaResult = gemmaProvider.generate(prompt, sysPrompt, context)
                        if (gemmaResult.success && gemmaResult.text.isNotBlank()) {
                            return@withContext gemmaResult.text
                        }
                        return@withContext gemmaResult.error ?: "Offline local model failed to generate response."
                    } else {
                        val localFast = LocalAssistantEngine.handleFastQuery(prompt)
                        if (localFast != null) return@withContext localFast
                        return@withContext "Offline Mode is active (zero network calls), but no local GGUF/LiteRT-LM model is installed. Please import a local model in AI API Hub or disable Offline Mode."
                    }
                }

                if (selectedProvider == "CUSTOM_API") {
                    val customReply = consultCustomApis(prompt)
                    if (!customReply.isNullOrBlank()) return@withContext customReply
                    return@withContext "Custom API provider is selected, but no enabled Custom API returned a valid response. Check your endpoint and key in AI API Hub."
                }

                if (selectedProvider == "OPENROUTER") {
                    val openRouterReply = consultOpenRouterDirect(prompt, history, screenContext, memoryContext, isProactiveMode)
                    if (!openRouterReply.isNullOrBlank()) return@withContext openRouterReply
                }

                if (!isApiKeyConfigured() && gemmaProvider.isConfigured(context)) {
                    val sysPrompt = com.example.ai.SystemPrompt.buildSystemPrompt(screenContext, memoryContext, isProactiveMode)
                    val gemmaResult = gemmaProvider.generate(prompt, sysPrompt, context)
                    if (gemmaResult.success && gemmaResult.text.isNotBlank()) {
                        return@withContext gemmaResult.text
                    }
                }
            }

            val apiKey = if (context != null) {
                com.example.utils.AppPreferences.getEffectiveApiKey(context)
            } else {
                BuildConfig.GEMINI_API_KEY
            }

            // If Gemini API key is missing, check if OpenRouter or Custom API is configured as fallback
            if (apiKey.isBlank()) {
                val openRouterBackup = consultOpenRouterDirect(prompt, history, screenContext, memoryContext, isProactiveMode)
                if (openRouterBackup != null) return@withContext openRouterBackup

                val customBackup = consultCustomApis(prompt)
                if (!customBackup.isNullOrBlank()) return@withContext customBackup

                val missingKeyError = AiErrorClassifier.recordError(
                    AiClassifiedError(category = AiErrorCategory.MISSING_API_KEY)
                )
                return@withContext missingKeyError.toErrorPrefixString()
            }

            // Consult OpenRouter as Gemini's secondary AI partner when task is complex
            val isComplexTask = prompt.length > 70 || prompt.contains("compare", ignoreCase = true) || prompt.contains("reason", ignoreCase = true)
            var partnerPerspective = memoryContext
            if (isComplexTask && context != null && com.example.utils.AppPreferences.getOpenRouterApiKey(context).isNotBlank()) {
                val partnerResult = consultOpenRouterPartner(prompt)
                if (!partnerResult.isNullOrBlank()) {
                    partnerPerspective += "\n\n[SECONDARY AI PARTNER (OpenRouter) SUGGESTION]:\n$partnerResult\n(Evaluate this suggestion as the boss orchestrator, refine it, and produce your final decision.)"
                }
            }

            // Consult Hugging Face specialized model when specialized inference is needed
            val isSpecializedTask = prompt.contains("sentiment", ignoreCase = true) ||
                prompt.contains("classify", ignoreCase = true) ||
                prompt.contains("hugging face", ignoreCase = true) ||
                prompt.contains("specialized", ignoreCase = true)
            if (isSpecializedTask && context != null && com.example.utils.AppPreferences.getHuggingFaceApiKey(context).isNotBlank()) {
                val hfResult = consultHuggingFaceSpecialist(prompt)
                if (!hfResult.isNullOrBlank()) {
                    partnerPerspective += "\n\n[SPECIALIZED AI RESOURCE (Hugging Face) INFERENCE]:\n$hfResult\n(Incorporate this specialized model output into your overall orchestration and final response.)"
                }
            }

            // Consult custom APIs if configured
            if (context != null) {
                val customResult = consultCustomApis(prompt)
                if (!customResult.isNullOrBlank()) {
                    partnerPerspective += "\n\n[CUSTOM API RESPONSE]:\n$customResult\n(Evaluate and integrate this external output as boss orchestrator.)"
                }
            }

            val systemInstructionText = com.example.ai.SystemPrompt.buildSystemPrompt(screenContext, partnerPerspective, isProactiveMode)

        // Build conversation contents (pass last 12 turns to ensure high context continuity)
        val contents = mutableListOf<Content>()
        var lastRole = ""
        val recentHistory = history.takeLast(12)
        for (msg in recentHistory) {
            val role = if (msg.isUser) "user" else "model"
            if (msg.text.isNotBlank() && role != lastRole) {
                contents.add(Content(role = role, parts = listOf(Part(text = msg.text.trim()))))
                lastRole = role
            }
        }
        if (lastRole == "user") {
            // Can't have two users in a row. Drop the previous one if the current prompt is also user.
            contents.removeAt(contents.size - 1)
        }
        val userParts = mutableListOf<Part>()
        if (imageBytes != null && imageBytes.isNotEmpty()) {
            val base64Data = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            userParts.add(Part(inlineData = InlineData(mimeType = "image/jpeg", data = base64Data)))
        }
        userParts.add(Part(text = prompt.trim()))
        contents.add(Content(role = "user", parts = userParts))

        val request = GenerateContentRequest(
            contents = contents,
            generationConfig = GenerationConfig(temperature = 0.7f),
            systemInstruction = Content(parts = listOf(Part(text = systemInstructionText)))
        )

        // Try supported model tiers with bounded retry, Retry-After support, and non-retryable fast-fail
        val candidateModels = getAvailableModels()

        modelLoop@ for (model in candidateModels) {
            var attempt = 0
            val maxRetries = 2

            while (attempt <= maxRetries) {
                val startTime = System.currentTimeMillis()
                try {
                    Log.d(TAG, "Sending request to Gemini model: $model (attempt $attempt)")
                    val response = RetrofitClient.service.generateContent(
                        model = model,
                        apiKey = apiKey,
                        request = request
                    )

                    val text = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    if (!text.isNullOrBlank()) {
                        val latency = System.currentTimeMillis() - startTime
                        AiErrorClassifier.clearLastError()
                        com.example.ai.providers.RuntimeActivityTracker.logEvent(
                            context = context,
                            providerId = "gemini",
                            model = model,
                            task = "Main Brain Reasoning",
                            success = true,
                            latencyMs = latency
                        )
                        return@withContext text.trim()
                    } else {
                        Log.w(TAG, "Empty candidate response from $model")
                        lastClassifiedError = AiErrorClassifier.recordError(
                            AiClassifiedError(
                                category = AiErrorCategory.EMPTY_RESPONSE,
                                redactedLogSummary = "Empty candidate response from $model"
                            )
                        )
                        break
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    AiErrorClassifier.classifyThrowable(cancelled)
                    throw cancelled
                } catch (e: HttpException) {
                    val latency = System.currentTimeMillis() - startTime
                    lastError = e
                    val code = e.code()
                    val errorBodyString = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
                    val retryAfterHeader = try { e.response()?.headers()?.get("Retry-After") } catch (_: Exception) { null }
                    val classified = AiErrorClassifier.classifyHttpResponse(code, errorBodyString, retryAfterHeader)
                    lastClassifiedError = classified

                    com.example.ai.providers.RuntimeActivityTracker.logEvent(
                        context = context,
                        providerId = "gemini",
                        model = model,
                        task = "Main Brain Reasoning",
                        success = false,
                        latencyMs = latency,
                        errorCategory = classified.category.name
                    )
                    com.example.api.QuotaManager.instance.apply {
                        lastErrorType = classified.category.name
                        lastErrorTime = System.currentTimeMillis()
                        lastErrorRequestType = "TEXT"
                        if (code == 429) rateLimitErrorsCount++
                    }
                    Log.e(TAG, "Gemini API error on model $model (attempt $attempt): ${classified.redactedLogSummary}")

                    // Fast-fail across all models on non-retryable credential/account/quota errors
                    if (classified.category in listOf(
                            AiErrorCategory.INVALID_API_KEY,
                            AiErrorCategory.MISSING_API_KEY,
                            AiErrorCategory.AUTHENTICATION_FAILURE,
                            AiErrorCategory.QUOTA_EXHAUSTED
                        )
                    ) {
                        break@modelLoop
                    }

                    if (classified.isRetryable) {
                        markModelThrottled(model)
                        attempt++
                        if (attempt <= maxRetries) {
                            val baseWait = classified.retryAfterMs ?: (600L * (1 shl attempt))
                            val backoffMs = kotlin.math.min(baseWait + (50..200).random(), 5000L)
                            Log.d(TAG, "Retryable ${classified.category} on $model. Backing off ${backoffMs}ms before retry...")
                            delay(backoffMs)
                        } else {
                            Log.w(TAG, "Model $model exhausted retries on ${classified.category}. Trying next model tier...")
                            break
                        }
                    } else {
                        Log.w(TAG, "Non-retryable ${classified.category} (HTTP $code) on model $model, trying next model tier...")
                        break
                    }
                } catch (e: IOException) {
                    lastError = e
                    val classified = AiErrorClassifier.classifyThrowable(e)
                    lastClassifiedError = classified
                    com.example.api.QuotaManager.instance.apply {
                        lastErrorType = classified.category.name
                        lastErrorTime = System.currentTimeMillis()
                        lastErrorRequestType = "TEXT"
                    }
                    Log.w(TAG, "Network/IO error on model $model: ${classified.redactedLogSummary}")
                    if (classified.category == AiErrorCategory.NETWORK_UNAVAILABLE ||
                        classified.category == AiErrorCategory.DNS_FAILURE
                    ) {
                        attempt++
                        if (attempt <= 1) {
                            delay(500L)
                        } else {
                            break@modelLoop
                        }
                    } else {
                        attempt++
                        if (attempt <= maxRetries) {
                            delay(500L * attempt)
                        } else {
                            break
                        }
                    }
                } catch (e: Exception) {
                    lastError = e
                    val classified = AiErrorClassifier.classifyThrowable(e)
                    lastClassifiedError = classified
                    com.example.api.QuotaManager.instance.apply {
                        lastErrorType = classified.category.name
                        lastErrorTime = System.currentTimeMillis()
                        lastErrorRequestType = "TEXT"
                    }
                    Log.e(TAG, "Unexpected error on model $model: ${classified.redactedLogSummary}")
                    break
                }
            }
        }

        } finally {
            com.example.api.QuotaManager.instance.releaseQuota(requestId)
        }
        val finalClassified = lastClassifiedError
            ?: lastError?.let { AiErrorClassifier.classifyThrowable(it) }
            ?: AiErrorClassifier.recordError(AiClassifiedError(AiErrorCategory.SERVICE_UNAVAILABLE))
        return@withContext finalClassified.toErrorPrefixString()
    }

    suspend fun generateSpeechAudio(
        text: String, 
        voiceName: String = "Kore",
        emotionLabel: String = "Sweet Anime Voice",
        pitchMultiplier: Float = 1.0f,
        speedMultiplier: Float = 1.0f
    ): ByteArray? = withContext(Dispatchers.IO) {
        // Gemini TTS/audio generation has been removed from the speech generation pipeline.
        // All speech generation is routed through Chatterbox Hindi via ChatterboxTtsClient.
        if (text.isBlank() || context == null) return@withContext null
        val client = ChatterboxTtsClient(context)
        val activeProfile = VoiceProfileManager.getInstance(context).getActiveProfile()
        when (val result = client.synthesize(
            text = text,
            referenceAudioPath = activeProfile.audioFilePath,
            pitchMultiplier = pitchMultiplier,
            speedMultiplier = speedMultiplier,
            emotion = emotionLabel
        )) {
            is ChatterboxResult.Success -> result.audioBytes
            is ChatterboxResult.Error -> {
                Log.w(TAG, "Chatterbox speech generation failed: ${result.message}")
                null
            }
        }
    }

    private fun splitTextForTts(text: String, maxLength: Int = 160): List<String> {
        if (text.length <= maxLength) return listOf(text)
        val result = mutableListOf<String>()
        val sentences = text.split(Regex("(?<=[.!?,\n।])\\s+"))
        var current = StringBuilder()
        for (s in sentences) {
            if (current.length + s.length + 1 > maxLength) {
                if (current.isNotBlank()) {
                    result.add(current.toString().trim())
                    current = StringBuilder()
                }
                if (s.length > maxLength) {
                    val words = s.split(" ")
                    for (w in words) {
                        if (current.length + w.length + 1 > maxLength) {
                            if (current.isNotBlank()) {
                                result.add(current.toString().trim())
                                current = StringBuilder()
                            }
                        }
                        current.append(w).append(" ")
                    }
                } else {
                    current.append(s).append(" ")
                }
            } else {
                current.append(s).append(" ")
            }
        }
        if (current.isNotBlank()) {
            result.add(current.toString().trim())
        }
        return if (result.isEmpty()) listOf(text.take(maxLength)) else result
    }

    private fun addWavHeaderToPcm(pcmData: ByteArray, sampleRate: Int = 24000, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val header = ByteArray(44)
        
        // RIFF chunk
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        
        // fmt chunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // PCM format
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte()
        header[33] = 0
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        
        // data chunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (pcmData.size and 0xff).toByte()
        header[41] = ((pcmData.size shr 8) and 0xff).toByte()
        header[42] = ((pcmData.size shr 16) and 0xff).toByte()
        header[43] = ((pcmData.size shr 24) and 0xff).toByte()
        
        return header + pcmData
    }


    suspend fun streamChat(
        prompt: String, 
        history: List<com.example.data.MessageEntity> = emptyList(), 
        screenContext: String? = null, 
        memoryContext: String = "",
        isProactiveMode: Boolean = true,
        imageBytes: ByteArray? = null
    ): kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.flow {
        val requestId = (prompt.hashCode() * 31 + history.size).toString()
        val priority = if (isProactiveMode) com.example.api.RequestPriority.P3_PROACTIVE_CONVERSATION else com.example.api.RequestPriority.P0_ACTIVE_USER_REQUEST
        
        if (!com.example.api.QuotaManager.instance.acquireQuota(requestId, priority)) {
            emit("I am a bit busy right now. Please try again in a moment.")
            return@flow
        }
        
        if (context != null) {
            val selectedProvider = com.example.utils.AppPreferences.getAiProvider(context)
            val isExplicitOffline = isStrictOfflineMode()
            val gemmaProvider = com.example.ai.providers.GemmaOfflineProvider()

            if (isExplicitOffline) {
                if (gemmaProvider.isConfigured(context)) {
                    val sysPrompt = com.example.ai.SystemPrompt.buildSystemPrompt(screenContext, memoryContext, isProactiveMode)
                    gemmaProvider.streamGenerate(prompt, sysPrompt, context).collect { chunk ->
                        emit(chunk)
                    }
                } else {
                    val localFast = LocalAssistantEngine.handleFastQuery(prompt)
                    emit(localFast ?: "Offline Mode is active (zero network calls), but no local GGUF/LiteRT-LM model is installed. Please import a local model in AI API Hub or disable Offline Mode.")
                }
                return@flow
            }

            if (selectedProvider == "CUSTOM_API" || selectedProvider == "OPENROUTER") {
                val responseText = chat(prompt, history, screenContext, memoryContext, isProactiveMode, imageBytes)
                emit(responseText)
                return@flow
            }

            if (!isApiKeyConfigured() && gemmaProvider.isConfigured(context)) {
                val sysPrompt = com.example.ai.SystemPrompt.buildSystemPrompt(screenContext, memoryContext, isProactiveMode)
                gemmaProvider.streamGenerate(prompt, sysPrompt, context).collect { chunk ->
                    emit(chunk)
                }
                return@flow
            }
        }

        val contents = mutableListOf<Content>()
        var lastRole = ""
        for (msg in history.takeLast(10)) {
            val role = if (msg.isUser) "user" else "model"
            if (msg.text.isNotBlank() && role != lastRole) {
                contents.add(Content(role = role, parts = listOf(Part(text = msg.text.trim()))))
                lastRole = role
            }
        }
        if (lastRole == "user") {
            contents.removeAt(contents.size - 1)
        }
        val userParts = mutableListOf<Part>()
        if (imageBytes != null && imageBytes.isNotEmpty()) {
            val base64Data = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            userParts.add(Part(inlineData = InlineData(mimeType = "image/jpeg", data = base64Data)))
        }
        userParts.add(Part(text = prompt.trim()))
        contents.add(Content(role = "user", parts = userParts))
        
        val sysText = com.example.ai.SystemPrompt.buildSystemPrompt(screenContext, memoryContext, isProactiveMode)

        val requestObj = GenerateContentRequest(
            contents = contents,
            generationConfig = GenerationConfig(temperature = 0.7f),
            systemInstruction = Content(parts = listOf(Part(text = sysText)))
        )

        val apiKey = if (context != null) com.example.utils.AppPreferences.getEffectiveApiKey(context) else BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank()) {
            val openRouterBackup = consultOpenRouterDirect(prompt, history, screenContext, memoryContext, isProactiveMode)
            if (openRouterBackup != null) {
                emit(openRouterBackup)
                return@flow
            }
            val missingKey = AiErrorClassifier.recordError(AiClassifiedError(AiErrorCategory.MISSING_API_KEY))
            emit(missingKey.toErrorPrefixString())
            return@flow
        }

        val json = kotlinx.serialization.json.Json { encodeDefaults = false; ignoreUnknownKeys = true }
        val requestJson = json.encodeToString(GenerateContentRequest.serializer(), requestObj)
        
        val okHttpClient = okhttp3.OkHttpClient.Builder()
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val candidateModels = getAvailableModels()
        var hasEmittedTokens = false
        var lastStreamClassifiedError: AiClassifiedError? = null

        try {
            modelLoop@ for (model in candidateModels) {
                if (hasEmittedTokens) break
                val request = okhttp3.Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse&key=$apiKey")
                    .addHeader("x-goog-api-key", apiKey)
                    .addHeader("Accept", "text/event-stream")
                    .post(okhttp3.RequestBody.create("application/json".toMediaType(), requestJson))
                    .build()

                try {
                    val response = okHttpClient.newCall(request).execute()
                    var abortAllModels = false
                    response.use { res ->
                        if (!res.isSuccessful) {
                            val code = res.code
                            val errBody = try { res.body?.string() } catch (_: Exception) { null }
                            val retryAfter = res.header("Retry-After")
                            val classified = AiErrorClassifier.classifyHttpResponse(code, errBody, retryAfter)
                            lastStreamClassifiedError = classified
                            Log.w(TAG, "Stream HTTP error on $model: ${classified.redactedLogSummary}")

                            if (code == 429 || code == 503 || code == 500) {
                                markModelThrottled(model)
                            }
                            if (classified.category in listOf(
                                    AiErrorCategory.INVALID_API_KEY,
                                    AiErrorCategory.MISSING_API_KEY,
                                    AiErrorCategory.AUTHENTICATION_FAILURE,
                                    AiErrorCategory.QUOTA_EXHAUSTED
                                )
                            ) {
                                abortAllModels = true
                            }
                            return@use
                        }

                        var sawValidChunk = false
                        var parseFailures = 0
                        res.body?.source()?.let { source ->
                            while (true) {
                                // A clean SSE EOF is normal; readUtf8LineStrict() throws when the
                                // final line has no trailing newline and falsely reports interruption.
                                val line = source.readUtf8Line() ?: break
                                if (line.startsWith("data:")) {
                                    val dataJson = line.removePrefix("data:").trim()
                                    if (dataJson.isNotBlank() && dataJson != "[DONE]") {
                                        try {
                                            val chunk = json.decodeFromString(GenerateContentResponse.serializer(), dataJson)
                                            sawValidChunk = true
                                            val textChunk = chunk.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: ""
                                            if (textChunk.isNotEmpty()) {
                                                hasEmittedTokens = true
                                                AiErrorClassifier.clearLastError()
                                                emit(textChunk)
                                            }
                                        } catch (e: Exception) {
                                            parseFailures++
                                            Log.w(TAG, "Malformed SSE chunk on $model: ${SecretRedactor.redact(e.message)}")
                                        }
                                    }
                                }
                            }
                        }
                        if (!hasEmittedTokens) {
                            lastStreamClassifiedError = AiErrorClassifier.recordError(
                                AiClassifiedError(
                                    category = if (parseFailures > 0 && !sawValidChunk) {
                                        AiErrorCategory.MALFORMED_RESPONSE
                                    } else {
                                        AiErrorCategory.EMPTY_RESPONSE
                                    },
                                    redactedLogSummary = "Stream completed without tokens on $model (parseFailures=$parseFailures)"
                                )
                            )
                        }
                    }

                    if (abortAllModels || hasEmittedTokens) {
                        break@modelLoop
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    AiErrorClassifier.classifyThrowable(cancelled)
                    throw cancelled
                } catch (e: Exception) {
                    val classified = if (hasEmittedTokens) {
                        AiErrorClassifier.recordError(
                            AiClassifiedError(
                                category = AiErrorCategory.STREAMING_INTERRUPTION,
                                redactedLogSummary = "Stream interrupted mid-response on $model: ${SecretRedactor.redact(e.message)}"
                            )
                        )
                    } else {
                        AiErrorClassifier.classifyThrowable(e)
                    }
                    lastStreamClassifiedError = classified
                    if (hasEmittedTokens && classified.category == AiErrorCategory.STREAMING_INTERRUPTION) {
                        // Never silently present a truncated streamed answer as complete.
                        emit("\\n\\n${classified.toErrorPrefixString()}")
                    }
                    Log.w(TAG, "Stream error on $model: ${classified.redactedLogSummary}")
                    if (classified.category == AiErrorCategory.NETWORK_UNAVAILABLE ||
                        classified.category == AiErrorCategory.DNS_FAILURE
                    ) {
                        break@modelLoop
                    }
                }
            }

            if (!hasEmittedTokens) {
                val err = lastStreamClassifiedError
                    ?: AiErrorClassifier.recordError(AiClassifiedError(AiErrorCategory.SERVICE_UNAVAILABLE))
                emit(err.toErrorPrefixString())
            }
        } finally {
            com.example.api.QuotaManager.instance.releaseQuota(requestId)
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)

    suspend fun transcribeAudio(pcmData: ByteArray, sampleRate: Int = 16000): String = withContext(Dispatchers.IO) {
        if (pcmData.isEmpty()) {
            AiErrorClassifier.recordVoiceError(VoiceInputErrorCategory.NO_SPEECH_RECOGNIZED)
            return@withContext ""
        }
        if (isStrictOfflineMode()) {
            Log.i(TAG, "Strict Offline Mode active: skipping cloud audio transcription.")
            AiErrorClassifier.recordVoiceError(VoiceInputErrorCategory.RECOGNITION_SERVICE_UNAVAILABLE)
            return@withContext ""
        }
        val wavBytes = createWavBytes(pcmData, sampleRate)
        val base64Wav = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        val request = GenerateContentRequest(
            contents = listOf(
                Content(
                    role = "user",
                    parts = listOf(
                        Part(inlineData = InlineData(mimeType = "audio/wav", data = base64Wav)),
                        Part(text = "Transcribe the spoken audio accurately. Output only the verbatim transcribed speech text in its spoken language (Hindi, English, or Hinglish) without any commentary.")
                    )
                )
            )
        )
        val apiKey = if (context != null) com.example.utils.AppPreferences.getEffectiveApiKey(context) else BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank()) {
            AiErrorClassifier.recordError(AiClassifiedError(AiErrorCategory.MISSING_API_KEY))
            AiErrorClassifier.recordVoiceError(VoiceInputErrorCategory.TRANSCRIPTION_SERVICE_ERROR)
            return@withContext ""
        }
        for (model in getAvailableModels()) {
            try {
                val response = RetrofitClient.service.generateContent(
                    model = model,
                    apiKey = apiKey,
                    request = request
                )
                val transcript = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim()
                if (!transcript.isNullOrBlank()) {
                    return@withContext transcript
                } else {
                    AiErrorClassifier.recordVoiceError(VoiceInputErrorCategory.EMPTY_TRANSCRIPTION)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                val classified = AiErrorClassifier.classifyThrowable(e)
                when (classified.category) {
                    AiErrorCategory.NETWORK_UNAVAILABLE, AiErrorCategory.DNS_FAILURE ->
                        AiErrorClassifier.recordVoiceError(VoiceInputErrorCategory.RECOGNITION_NETWORK_FAILURE)
                    AiErrorCategory.CONNECTION_TIMEOUT ->
                        AiErrorClassifier.recordVoiceError(VoiceInputErrorCategory.RECOGNITION_TIMEOUT)
                    else ->
                        AiErrorClassifier.recordVoiceError(VoiceInputErrorCategory.TRANSCRIPTION_SERVICE_ERROR)
                }
                Log.w("KavyaAI", "Error transcribing audio on $model: ${classified.redactedLogSummary}")
                if (!classified.isRetryable) break
            }
        }
        ""
    }

    private fun createWavBytes(pcmData: ByteArray, sampleRate: Int = 16000): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * 2
        val header = ByteArray(44)
        val bb = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put('R'.code.toByte()).put('I'.code.toByte()).put('F'.code.toByte()).put('F'.code.toByte())
        bb.putInt(totalDataLen)
        bb.put('W'.code.toByte()).put('A'.code.toByte()).put('V'.code.toByte()).put('E'.code.toByte())
        bb.put('f'.code.toByte()).put('m'.code.toByte()).put('t'.code.toByte()).put(' '.code.toByte())
        bb.putInt(16)
        bb.putShort(1.toShort())
        bb.putShort(1.toShort())
        bb.putInt(sampleRate)
        bb.putInt(byteRate)
        bb.putShort(2.toShort())
        bb.putShort(16.toShort())
        bb.put('d'.code.toByte()).put('a'.code.toByte()).put('t'.code.toByte()).put('a'.code.toByte())
        bb.putInt(pcmData.size)

        val wav = ByteArray(44 + pcmData.size)
        System.arraycopy(header, 0, wav, 0, 44)
        System.arraycopy(pcmData, 0, wav, 44, pcmData.size)
        return wav
    }
}
