package com.example.ai.providers

import android.content.Context
import android.util.Log
import com.example.ai.OpenRouterMessage
import com.example.ai.OpenRouterRequest
import com.example.ai.RetrofitClient
import com.example.security.SecureStorage
import com.example.utils.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Result of a deep research or complex multi-source investigation performed by OpenRouter.
 */
data class DeepResearchResult(
    val success: Boolean,
    val report: String,
    val sources: List<String> = emptyList(),
    val modelUsed: String = "",
    val error: String? = null
)

/**
 * Requirement 2 & 3: Real OpenRouter AI Provider.
 * Real network communication, validation, model discovery, chat, streaming, and structured generation.
 */
class OpenRouterProvider : AIProvider {

    companion object {
        private const val TAG = "KavyaOpenRouterProvider"
        const val PROVIDER_ID = "openrouter"
        private const val BASE_URL = "https://openrouter.ai/api/v1"

        // Default catalogue of verified models for fast initialization
        val DEFAULT_VERIFIED_MODELS = listOf(
            ProviderModelInfo(
                id = "openrouter/free",
                name = "OpenRouter Auto Free",
                provider = ProviderType.OPENROUTER,
                supportedTasks = listOf("reasoning", "general", "fast"),
                contextLength = 16384,
                description = "Automatic routing across currently available free high-throughput models",
                isFree = true
            ),
            ProviderModelInfo(
                id = "meta-llama/llama-3.3-70b-instruct:free",
                name = "Meta Llama 3.3 70B Instruct (Free)",
                provider = ProviderType.OPENROUTER,
                supportedTasks = listOf("reasoning", "coding", "general"),
                contextLength = 131072,
                description = "State of the art 70B open weights model for complex reasoning and coding",
                isFree = true
            ),
            ProviderModelInfo(
                id = "google/gemini-2.0-flash-exp:free",
                name = "Google Gemini 2.0 Flash Experimental (Free)",
                provider = ProviderType.OPENROUTER,
                supportedTasks = listOf("reasoning", "multimodal", "fast", "coding"),
                contextLength = 1048576,
                description = "Ultra-fast multimodal reasoning model from Google DeepMind",
                isFree = true
            ),
            ProviderModelInfo(
                id = "mistralai/mistral-7b-instruct:free",
                name = "Mistral 7B Instruct (Free)",
                provider = ProviderType.OPENROUTER,
                supportedTasks = listOf("fast", "general"),
                contextLength = 32768,
                description = "Fast, efficient instruction-tuned general assistant",
                isFree = true
            ),
            ProviderModelInfo(
                id = "deepseek/deepseek-r1:free",
                name = "DeepSeek R1 (Free)",
                provider = ProviderType.OPENROUTER,
                supportedTasks = listOf("reasoning", "math", "coding"),
                contextLength = 64000,
                description = "Advanced chain-of-thought reasoning and problem solving",
                isFree = true
            ),
            ProviderModelInfo(
                id = "perplexity/sonar",
                name = "Perplexity Sonar",
                provider = ProviderType.OPENROUTER,
                supportedTasks = listOf("research", "web_search", "synthesis"),
                contextLength = 127000,
                description = "Real-time internet search and citation engine",
                isFree = false
            )
        )
    }

    override val providerId: String = PROVIDER_ID
    override val displayName: String = "OpenRouter"
    override val providerType: ProviderType = ProviderType.OPENROUTER
    override val isPrimaryOrchestrator: Boolean = false

    // In-memory cache of discovered real models
    private val cachedModels = CopyOnWriteArrayList<ProviderModelInfo>(DEFAULT_VERIFIED_MODELS)

    override fun isConfigured(context: Context): Boolean {
        val key = getApiKey(context)
        return key.isNotBlank()
    }

    private fun getApiKey(context: Context): String {
        val secureKey = SecureStorage.getSecret(context, "openrouter_api_key")
        if (secureKey.isNotBlank()) return secureKey
        return AppPreferences.getOpenRouterApiKey(context)
    }

    /**
     * Requirement 2: validateCredentials()
     * Sends a real HTTPS request to OpenRouter's auth key endpoint or chat completions endpoint.
     */
    override suspend fun validateCredentials(context: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey(context)
        return@withContext validateCredentialsWithKey(apiKey)
    }

    suspend fun validateCredentialsWithKey(apiKey: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Pair(false, "OpenRouter API Key is empty. Please enter your key.")
        }

        try {
            // First attempt: Check auth key endpoint
            val authUrl = "$BASE_URL/auth/key"
            val request = Request.Builder()
                .url(authUrl)
                .addHeader("Authorization", "Bearer ${apiKey.trim()}")
                .addHeader("HTTP-Referer", "https://aistudio.google.com")
                .addHeader("X-Title", "Kavya AI")
                .get()
                .build()

            val response = RetrofitClient.okHttpClient.newCall(request).execute()
            val code = response.code
            val responseBody = response.body?.string() ?: ""
            response.close()

            if (code in 200..299) {
                var keyLabel = "Active"
                var usageInfo = ""
                try {
                    val json = JSONObject(responseBody)
                    val data = json.optJSONObject("data")
                    if (data != null) {
                        keyLabel = data.optString("label", "Active")
                        val usage = data.optDouble("usage", 0.0)
                        val limit = if (data.has("limit") && !data.isNull("limit")) "$${data.optDouble("limit")}" else "unlimited"
                        usageInfo = " (Usage: $${String.format("%.2f", usage)} / $limit)"
                    }
                } catch (ignored: Exception) {}
                return@withContext Pair(true, "OpenRouter connected successfully: $keyLabel$usageInfo")
            } else if (code == 401) {
                return@withContext Pair(false, "Invalid OpenRouter API credentials (401). Please check your key.")
            } else if (code == 402) {
                return@withContext Pair(false, "Payment required or credit limit reached (402).")
            } else if (code == 429) {
                return@withContext Pair(false, "Provider rate limit reached. Trying configured fallback if compatible.")
            }

            // Fallback check: test tiny chat completion
            val fallbackReq = OpenRouterRequest(
                model = "openrouter/free",
                messages = listOf(OpenRouterMessage(role = "user", content = "Reply with: OK"))
            )
            val fallbackResp = RetrofitClient.openRouterService.generateContent(
                authorization = "Bearer ${apiKey.trim()}",
                request = fallbackReq
            )
            if (fallbackResp.error != null) {
                return@withContext Pair(false, "OpenRouter validation failed: ${fallbackResp.error.message}")
            }
            return@withContext Pair(true, "OpenRouter connected successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "OpenRouter validation network error", e)
            val msg = handleError(-1, e.localizedMessage)
            return@withContext Pair(false, "OpenRouter connection failed: $msg")
        }
    }

    /**
     * Requirement 2: getModels()
     * Retrieves real model catalogue from OpenRouter /api/v1/models.
     */
    override suspend fun getModels(context: Context): List<ProviderModelInfo> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey(context)
        val url = "$BASE_URL/models"
        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("HTTP-Referer", "https://aistudio.google.com")
            .addHeader("X-Title", "Kavya AI")

        if (apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer ${apiKey.trim()}")
        }

        try {
            val response = RetrofitClient.okHttpClient.newCall(requestBuilder.build()).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                response.close()
                val json = JSONObject(body)
                val data = json.optJSONArray("data")
                if (data != null && data.length() > 0) {
                    val list = mutableListOf<ProviderModelInfo>()
                    for (i in 0 until data.length()) {
                        val item = data.optJSONObject(i) ?: continue
                        val id = item.optString("id")
                        if (id.isBlank()) continue
                        val name = item.optString("name", id)
                        val desc = item.optString("description", "")
                        val ctxLen = item.optInt("context_length", 8192)
                        val pricing = item.optJSONObject("pricing")
                        val isFree = id.endsWith(":free") || (pricing != null && pricing.optDouble("prompt", 0.0) == 0.0)

                        val tasks = mutableListOf<String>()
                        if (id.contains("vision") || id.contains("vl")) tasks.add("vision")
                        if (id.contains("code") || id.contains("coder")) tasks.add("coding")
                        if (id.contains("sonar") || id.contains("search")) tasks.add("research")
                        tasks.add("reasoning")
                        tasks.add("general")

                        list.add(
                            ProviderModelInfo(
                                id = id,
                                name = name,
                                provider = ProviderType.OPENROUTER,
                                supportedTasks = tasks,
                                contextLength = ctxLen,
                                description = desc,
                                isFree = isFree
                            )
                        )
                    }
                    if (list.isNotEmpty()) {
                        cachedModels.clear()
                        cachedModels.addAll(list)
                        return@withContext list
                    }
                }
            } else {
                response.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed fetching real OpenRouter models, using verified catalogue: ${e.message}")
        }
        return@withContext cachedModels.toList()
    }

    /**
     * Requirement 2: chat()
     * Sends real chat completions request to OpenRouter.
     */
    suspend fun chat(
        messages: List<OpenRouterMessage>,
        model: String,
        context: Context,
        temperature: Float = 0.7f
    ): AIProviderResult = withContext(Dispatchers.IO) {
        val apiKey = getApiKey(context)
        if (apiKey.isBlank()) {
            return@withContext AIProviderResult(
                success = false,
                text = "This provider is not configured. Missing OpenRouter API key.",
                providerId = providerId,
                model = model,
                error = "This provider is not configured."
            )
        }

        val startTime = System.currentTimeMillis()
        try {
            val messagesArray = JSONArray()
            messages.forEach { msg ->
                messagesArray.put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                })
            }

            val payload = JSONObject().apply {
                put("model", model)
                put("messages", messagesArray)
                put("temperature", temperature)
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = payload.toString().toRequestBody(mediaType)
            val request = Request.Builder()
                .url("$BASE_URL/chat/completions")
                .addHeader("Authorization", "Bearer ${apiKey.trim()}")
                .addHeader("HTTP-Referer", "https://aistudio.google.com")
                .addHeader("X-Title", "Kavya AI")
                .post(requestBody)
                .build()

            val response = RetrofitClient.okHttpClient.newCall(request).execute()
            val code = response.code
            val rawBody = response.body?.string() ?: ""
            response.close()
            val latency = System.currentTimeMillis() - startTime

            if (code in 200..299) {
                val json = JSONObject(rawBody)
                val choices = json.optJSONArray("choices")
                val firstChoice = choices?.optJSONObject(0)
                val messageObj = firstChoice?.optJSONObject("message")
                val content = messageObj?.optString("content") ?: ""

                if (content.isNotBlank()) {
                    return@withContext AIProviderResult(
                        success = true,
                        text = content.trim(),
                        rawJson = rawBody,
                        providerId = providerId,
                        model = model,
                        latencyMs = latency
                    )
                } else {
                    return@withContext AIProviderResult(
                        success = false,
                        text = "OpenRouter returned an empty response.",
                        providerId = providerId,
                        model = model,
                        error = "Empty response content"
                    )
                }
            } else {
                val errorMsg = handleError(code, rawBody)
                return@withContext AIProviderResult(
                    success = false,
                    text = errorMsg,
                    providerId = providerId,
                    model = model,
                    error = errorMsg
                )
            }
        } catch (e: Exception) {
            val errorMsg = handleError(-1, e.localizedMessage)
            return@withContext AIProviderResult(
                success = false,
                text = errorMsg,
                providerId = providerId,
                model = model,
                error = e.message
            )
        }
    }

    /**
     * Requirement 2 & 16: streamChat()
     * Streams real response chunks over Server-Sent Events (SSE).
     */
    fun streamChat(
        messages: List<OpenRouterMessage>,
        model: String,
        context: Context
    ): Flow<String> = flow {
        val apiKey = getApiKey(context)
        if (apiKey.isBlank()) {
            emit("This provider is not configured. Missing OpenRouter API key.")
            return@flow
        }

        val messagesArray = JSONArray()
        messages.forEach { msg ->
            messagesArray.put(JSONObject().apply {
                put("role", msg.role)
                put("content", msg.content)
            })
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", messagesArray)
            put("stream", true)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url("$BASE_URL/chat/completions")
            .addHeader("Authorization", "Bearer ${apiKey.trim()}")
            .addHeader("HTTP-Referer", "https://aistudio.google.com")
            .addHeader("X-Title", "Kavya AI")
            .post(payload.toString().toRequestBody(mediaType))
            .build()

        val response = RetrofitClient.okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val err = handleError(response.code, response.body?.string())
            response.close()
            emit(err)
            return@flow
        }

        val stream = response.body?.byteStream()
        if (stream != null) {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line?.trim() ?: continue
                    if (l.startsWith("data:")) {
                        val dataContent = l.removePrefix("data:").trim()
                        if (dataContent == "[DONE]") {
                            break
                        }
                        try {
                            val json = JSONObject(dataContent)
                            val choices = json.optJSONArray("choices")
                            val first = choices?.optJSONObject(0)
                            val delta = first?.optJSONObject("delta")
                            val chunk = delta?.optString("content", "") ?: ""
                            if (chunk.isNotEmpty()) {
                                emit(chunk)
                            }
                        } catch (ignored: Exception) {}
                    }
                }
            }
        }
        response.close()
    }.flowOn(Dispatchers.IO)

    /**
     * Requirement 2: structuredGeneration()
     * Enforces structured JSON output schema for task planning and action execution.
     */
    suspend fun structuredGeneration(
        prompt: String,
        schemaInstruction: String,
        model: String,
        context: Context
    ): AIProviderResult = withContext(Dispatchers.IO) {
        val messages = listOf(
            OpenRouterMessage(
                role = "system",
                content = "You are an action execution assistant. Return your response ONLY as valid JSON. Schema instruction: $schemaInstruction"
            ),
            OpenRouterMessage(role = "user", content = prompt)
        )
        return@withContext chat(messages = messages, model = model, context = context, temperature = 0.2f)
    }

    override suspend fun generate(
        prompt: String,
        systemInstruction: String?,
        context: Context
    ): AIProviderResult = withContext(Dispatchers.IO) {
        val configuredModel = AppPreferences.getOpenRouterModel(context).ifBlank { "openrouter/free" }
        val messages = mutableListOf<OpenRouterMessage>()
        if (!systemInstruction.isNullOrBlank()) {
            messages.add(OpenRouterMessage(role = "system", content = systemInstruction))
        }
        messages.add(OpenRouterMessage(role = "user", content = prompt))
        return@withContext chat(messages = messages, model = configuredModel, context = context)
    }

    override fun streamGenerate(
        prompt: String,
        systemInstruction: String?,
        context: Context,
        modelOverride: String?
    ): Flow<String> {
        val configuredModel = modelOverride?.ifBlank { null }
            ?: AppPreferences.getOpenRouterModel(context).ifBlank { "openrouter/free" }
        val messages = mutableListOf<OpenRouterMessage>()
        if (!systemInstruction.isNullOrBlank()) {
            messages.add(OpenRouterMessage(role = "system", content = systemInstruction))
        }
        messages.add(OpenRouterMessage(role = "user", content = prompt))
        return streamChat(messages, configuredModel, context)
    }

    /**
     * Deep multi-source research using OpenRouter search-capable models.
     */
    suspend fun deepResearch(
        query: String,
        context: Context
    ): DeepResearchResult = withContext(Dispatchers.IO) {
        val model = "perplexity/sonar"
        val prompt = "Perform a thorough, multi-source investigation on: $query. Include factual findings and references."
        val result = chat(
            messages = listOf(
                OpenRouterMessage(role = "system", content = "You are an objective research synthesizer. Cite verifiable sources."),
                OpenRouterMessage(role = "user", content = prompt)
            ),
            model = model,
            context = context
        )

        if (result.success) {
            return@withContext DeepResearchResult(
                success = true,
                report = result.text,
                sources = listOf("Web search via OpenRouter Perplexity Sonar"),
                modelUsed = model
            )
        } else {
            // Fallback to openrouter/free
            val fallbackResult = generate(prompt, "Research synthesizer", context)
            return@withContext DeepResearchResult(
                success = fallbackResult.success,
                report = fallbackResult.text,
                modelUsed = fallbackResult.model,
                error = fallbackResult.error
            )
        }
    }

    override fun handleError(code: Int, errorBody: String?): String {
        val parsedMsg = if (!errorBody.isNullOrBlank()) {
            try {
                JSONObject(errorBody).optJSONObject("error")?.optString("message")
            } catch (e: Exception) {
                null
            }
        } else null

        return when (code) {
            401 -> "Invalid API credentials. Please check your OpenRouter API key."
            402 -> "Provider credit limit reached or insufficient credits (402)."
            403 -> "Access forbidden for this model or key (403)."
            404 -> "Selected model was not found on OpenRouter (404)."
            429 -> "Provider rate limit reached. Trying configured fallback if compatible."
            500, 502 -> "OpenRouter server error: ${parsedMsg ?: "internal failure"}"
            503 -> "OpenRouter model is temporarily unavailable (503)."
            504 -> "Provider timed out."
            else -> {
                if (errorBody != null && (errorBody.contains("timeout", ignoreCase = true) || errorBody.contains("timed out", ignoreCase = true))) {
                    "Provider timed out."
                } else {
                    parsedMsg ?: "OpenRouter network request failed: ${errorBody?.take(120) ?: "Unknown network error"}"
                }
            }
        }
    }
}
