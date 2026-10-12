package com.example.ai.providers

import android.content.Context
import android.util.Log
import com.example.ai.*
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

/**
 * Requirement 1: Gemini AI Provider — The primary orchestrator of Kavya.
 * Implements real network requests, streaming, and error handling.
 */
class GeminiProvider : AIProvider {

    companion object {
        private const val TAG = "KavyaGeminiProvider"
        const val PROVIDER_ID = "gemini"

        val VERIFIED_MODELS = listOf(
            ProviderModelInfo("gemini-2.5-flash", "Gemini 2.5 Flash", ProviderType.GEMINI, listOf("reasoning", "general", "vision", "fast"), 1048576, "Latest low-latency high-throughput Google Gemini model", true),
            ProviderModelInfo("gemini-1.5-flash", "Gemini 1.5 Flash", ProviderType.GEMINI, listOf("reasoning", "general", "vision"), 1048576, "Stable multimodal 1M context model", true),
            ProviderModelInfo("gemini-1.5-pro", "Gemini 1.5 Pro", ProviderType.GEMINI, listOf("reasoning", "coding", "analysis"), 2097152, "State of the art reasoning model", false)
        )
    }

    override val providerId: String = PROVIDER_ID
    override val displayName: String = "Google Gemini"
    override val providerType: ProviderType = ProviderType.GEMINI
    override val isPrimaryOrchestrator: Boolean = true

    override fun isConfigured(context: Context): Boolean {
        val key = getApiKey(context)
        return key.isNotBlank()
    }

    private fun getApiKey(context: Context): String {
        val secureKey = SecureStorage.getSecret(context, "gemini_api_key")
        if (secureKey.isNotBlank()) return secureKey
        return AppPreferences.getEffectiveApiKey(context)
    }

    override suspend fun validateCredentials(context: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val key = getApiKey(context)
        if (key.isBlank()) {
            return@withContext Pair(false, "Gemini API key is not configured. Please enter your API key.")
        }
        return@withContext AppPreferences.validateGeminiApiKey(key)
    }

    override suspend fun getModels(context: Context): List<ProviderModelInfo> {
        return VERIFIED_MODELS
    }

    override suspend fun generate(
        prompt: String,
        systemInstruction: String?,
        context: Context
    ): AIProviderResult = withContext(Dispatchers.IO) {
        val apiKey = getApiKey(context)
        if (apiKey.isBlank()) {
            return@withContext AIProviderResult(
                success = false,
                text = "This provider is not configured. Missing Gemini API key.",
                providerId = providerId,
                model = "gemini-2.5-flash",
                error = "This provider is not configured."
            )
        }

        val models = listOf(
            "gemini-2.5-flash",
            "gemini-1.5-flash"
        )

        val contents = listOf(Content(role = "user", parts = listOf(Part(text = prompt))))
        val request = GenerateContentRequest(
            contents = contents,
            generationConfig = GenerationConfig(temperature = 0.7f),
            systemInstruction = if (!systemInstruction.isNullOrBlank()) {
                Content(parts = listOf(Part(text = systemInstruction)))
            } else null
        )

        var lastError: String? = null
        val startTime = System.currentTimeMillis()

        for (model in models) {
            try {
                val response = RetrofitClient.service.generateContent(
                    model = model,
                    apiKey = apiKey,
                    request = request
                )
                val text = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                val latency = System.currentTimeMillis() - startTime
                if (!text.isNullOrBlank()) {
                    return@withContext AIProviderResult(
                        success = true,
                        text = text.trim(),
                        providerId = providerId,
                        model = model,
                        latencyMs = latency
                    )
                }
            } catch (e: retrofit2.HttpException) {
                val code = e.code()
                val errBody = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
                lastError = handleError(code, errBody)
                Log.w(TAG, "Gemini $model HTTP $code: $lastError")
                if (code == 401 || code == 403) {
                    // Authentication failure shouldn't blindly retry subsequent models
                    break
                }
            } catch (e: Exception) {
                lastError = handleError(-1, e.localizedMessage)
                Log.w(TAG, "Gemini $model failed: ${e.message}")
            }
        }

        val latency = System.currentTimeMillis() - startTime
        return@withContext AIProviderResult(
            success = false,
            text = lastError ?: "Gemini could not generate a response.",
            providerId = providerId,
            model = models.first(),
            error = lastError,
            latencyMs = latency
        )
    }

    /**
     * Requirement 16: Real streaming using Gemini's streamGenerateContent SSE endpoint.
     */
    override fun streamGenerate(
        prompt: String,
        systemInstruction: String?,
        context: Context,
        modelOverride: String?
    ): Flow<String> = flow {
        val apiKey = getApiKey(context)
        if (apiKey.isBlank()) {
            emit("This provider is not configured. Missing Gemini API key.")
            return@flow
        }

        val targetModel = modelOverride?.ifBlank { null } ?: "gemini-2.5-flash"
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$targetModel:streamGenerateContent?key=$apiKey&alt=sse"

        val contentsArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", prompt) })
                })
            })
        }

        val payload = JSONObject().apply {
            put("contents", contentsArray)
            if (!systemInstruction.isNullOrBlank()) {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", systemInstruction) })
                    })
                })
            }
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(mediaType))
            .build()

        try {
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
                            val data = l.removePrefix("data:").trim()
                            if (data.isBlank()) continue
                            try {
                                val json = JSONObject(data)
                                val candidates = json.optJSONArray("candidates")
                                val first = candidates?.optJSONObject(0)
                                val content = first?.optJSONObject("content")
                                val parts = content?.optJSONArray("parts")
                                val text = parts?.optJSONObject(0)?.optString("text", "") ?: ""
                                if (text.isNotEmpty()) {
                                    emit(text)
                                }
                            } catch (ignored: Exception) {}
                        }
                    }
                }
            }
            response.close()
        } catch (e: Exception) {
            emit(handleError(-1, e.localizedMessage))
        }
    }.flowOn(Dispatchers.IO)

    override fun handleError(code: Int, errorBody: String?): String {
        return when (code) {
            400 -> "Invalid API request or unsupported model parameters (400)."
            401, 403 -> "Invalid API credentials. Please verify your Gemini API key (403)."
            404 -> "Requested Gemini model was not found (404)."
            429 -> "Provider rate limit reached. Trying configured fallback if compatible."
            500, 503 -> "Google Gemini server is temporarily overloaded. Please retry."
            else -> {
                if (errorBody != null && (errorBody.contains("timeout", ignoreCase = true) || errorBody.contains("timed out", ignoreCase = true))) {
                    "Provider timed out."
                } else {
                    super.handleError(code, errorBody)
                }
            }
        }
    }
}
