package com.example.utils

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.BuildConfig
import com.example.ai.GenerateContentRequest
import com.example.ai.Part
import com.example.ai.Content
import com.example.ai.RetrofitClient
import com.example.services.KavyaAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

object AppPreferences {
    private const val PREFS_NAME = "kavya_app_preferences"
    private const val KEY_ONBOARDING_COMPLETED = "key_onboarding_completed"
    private const val KEY_API_SETUP_COMPLETED = "key_api_setup_completed"
    private const val KEY_CUSTOM_API_KEY = "key_custom_gemini_api_key"
    private const val KEY_OPENROUTER_API_KEY = "key_openrouter_api_key"
    private const val KEY_HUGGINGFACE_API_KEY = "key_huggingface_api_key"
    private const val KEY_HUGGINGFACE_MODEL = "key_huggingface_model"
    private const val KEY_AI_PROVIDER = "key_ai_provider"
    private const val KEY_OPENROUTER_MODEL = "key_openrouter_model"
    private const val KEY_VOICE_PITCH = "key_voice_pitch"
    private const val KEY_VOICE_SPEED = "key_voice_speed"
    private const val KEY_VOICE_PERSONA = "key_voice_persona"
    private const val KEY_THEME = "key_app_theme"
    private const val KEY_LANGUAGE = "key_app_language"
    private const val KEY_GEMINI_VOICE = "key_gemini_voice"
    private const val KEY_PROACTIVE_MODE = "key_proactive_mode"
    private const val KEY_SCREEN_AWARENESS = "key_screen_awareness"
    private const val KEY_PROACTIVE_FREQUENCY = "key_proactive_frequency"
    private const val KEY_SILENCE_UNTIL_TS = "key_silence_until_ts"
    private const val KEY_PROACTIVE_ENGAGEMENT_SCORE = "key_proactive_engagement_score"
    private const val KEY_GEMINI_MODEL = "key_gemini_model"
    private const val KEY_OPENROUTER_ENABLED = "key_openrouter_enabled"
    private const val KEY_HUGGINGFACE_ENABLED = "key_huggingface_enabled"
    private const val KEY_PUBLIC_APIS_ENABLED = "key_public_apis_enabled"
    private const val KEY_MAPS_SCRAPER_URL = "key_maps_scraper_url"
    private const val KEY_MAPS_SCRAPER_ENABLED = "key_maps_scraper_enabled"
    private const val KEY_MAPS_SCRAPER_PROXY_HOST = "key_maps_scraper_proxy_host"
    private const val KEY_MAPS_SCRAPER_PROXY_PORT = "key_maps_scraper_proxy_port"
    private const val KEY_OKF_MEMORY_ENABLED = "key_okf_memory_enabled"
    private const val PREFIX_PROVIDER_STATUS = "provider_status_"
    private const val PREFIX_PROVIDER_COUNT = "provider_count_"
    private const val PREFIX_PROVIDER_LAST_SUCCESS = "provider_last_success_"
    private const val PREFIX_PROVIDER_LAST_ERROR = "provider_last_error_"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isProactiveModeEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PROACTIVE_MODE, true)
    }

    fun setProactiveModeEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_PROACTIVE_MODE, enabled).apply()
    }

    fun isScreenAwarenessEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SCREEN_AWARENESS, false)
    }

    fun setScreenAwarenessEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SCREEN_AWARENESS, enabled).apply()
    }

    fun getProactiveFrequency(context: Context): String {
        return getPrefs(context).getString(KEY_PROACTIVE_FREQUENCY, "BALANCED") ?: "BALANCED"
    }

    fun setProactiveFrequency(context: Context, frequency: String) {
        getPrefs(context).edit().putString(KEY_PROACTIVE_FREQUENCY, frequency).apply()
    }

    fun getSilenceUntilTimestamp(context: Context): Long {
        return getPrefs(context).getLong(KEY_SILENCE_UNTIL_TS, 0L)
    }

    fun setSilenceUntilTimestamp(context: Context, timestamp: Long) {
        getPrefs(context).edit().putLong(KEY_SILENCE_UNTIL_TS, timestamp).apply()
    }

    fun getProactiveEngagementScore(context: Context): Float {
        return getPrefs(context).getFloat(KEY_PROACTIVE_ENGAGEMENT_SCORE, 1.0f)
    }

    fun setProactiveEngagementScore(context: Context, score: Float) {
        getPrefs(context).edit().putFloat(KEY_PROACTIVE_ENGAGEMENT_SCORE, score.coerceIn(0.2f, 2.5f)).apply()
    }

    fun getGeminiVoice(context: Context): String {
        return getPrefs(context).getString(KEY_GEMINI_VOICE, "Kore") ?: "Kore"
    }

    fun setGeminiVoice(context: Context, voiceName: String) {
        getPrefs(context).edit().putString(KEY_GEMINI_VOICE, voiceName).apply()
    }

    fun getVoicePitch(context: Context): Float {
        return getPrefs(context).getFloat(KEY_VOICE_PITCH, 1.14f).coerceIn(0.95f, 1.25f)
    }

    fun setVoicePitch(context: Context, pitch: Float) {
        getPrefs(context).edit().putFloat(KEY_VOICE_PITCH, pitch.coerceIn(0.95f, 1.25f)).apply()
    }

    fun getVoiceSpeed(context: Context): Float {
        return getPrefs(context).getFloat(KEY_VOICE_SPEED, 1.02f).coerceIn(0.80f, 1.25f)
    }

    fun setVoiceSpeed(context: Context, speed: Float) {
        getPrefs(context).edit().putFloat(KEY_VOICE_SPEED, speed.coerceIn(0.80f, 1.25f)).apply()
    }

    fun getVoicePersona(context: Context): String {
        return getPrefs(context).getString(KEY_VOICE_PERSONA, "Sweet Anime Hindi Female") ?: "Sweet Anime Hindi Female"
    }

    fun setVoicePersona(context: Context, persona: String) {
        getPrefs(context).edit().putString(KEY_VOICE_PERSONA, persona).apply()
    }

    fun getSelectedTheme(context: Context): String {
        return getPrefs(context).getString(KEY_THEME, "Futuristic Dark") ?: "Futuristic Dark"
    }

    fun setSelectedTheme(context: Context, theme: String) {
        getPrefs(context).edit().putString(KEY_THEME, theme).apply()
    }

    fun getLanguage(context: Context): String {
        return getPrefs(context).getString(KEY_LANGUAGE, "hi") ?: "hi"
    }

    fun setLanguage(context: Context, languageCode: String) {
        getPrefs(context).edit().putString(KEY_LANGUAGE, languageCode).apply()
    }

    fun clearAllLearnedPreferences(context: Context) {
        val aliasPrefs = context.getSharedPreferences("kavya_app_aliases", Context.MODE_PRIVATE)
        aliasPrefs.edit().clear().apply()
    }

    fun isOnboardingCompleted(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }

    fun setOnboardingCompleted(context: Context, completed: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ONBOARDING_COMPLETED, completed).apply()
    }

    fun getCustomApiKey(context: Context): String {
        return com.example.security.SecureStorage.getSecret(context, "gemini_api_key")
    }

    fun setCustomApiKey(context: Context, key: String) {
        val trimmed = key.trim()
        com.example.security.SecureStorage.saveSecret(context, "gemini_api_key", trimmed)
        getPrefs(context).edit().remove(KEY_CUSTOM_API_KEY).apply()
    }

    fun clearCustomApiKey(context: Context) {
        com.example.security.SecureStorage.clearSecret(context, "gemini_api_key")
        getPrefs(context).edit().remove(KEY_CUSTOM_API_KEY).apply()
    }
    
    fun getOpenRouterApiKey(context: Context): String {
        return com.example.security.SecureStorage.getSecret(context, "openrouter_api_key")
    }

    fun setOpenRouterApiKey(context: Context, key: String) {
        val trimmed = key.trim()
        com.example.security.SecureStorage.saveSecret(context, "openrouter_api_key", trimmed)
        getPrefs(context).edit().remove(KEY_OPENROUTER_API_KEY).apply()
    }

    fun clearOpenRouterApiKey(context: Context) {
        com.example.security.SecureStorage.clearSecret(context, "openrouter_api_key")
        getPrefs(context).edit().remove(KEY_OPENROUTER_API_KEY).apply()
    }

    fun getHuggingFaceApiKey(context: Context): String {
        return com.example.security.SecureStorage.getSecret(context, "huggingface_api_key")
    }

    fun setHuggingFaceApiKey(context: Context, key: String) {
        val trimmed = key.trim()
        com.example.security.SecureStorage.saveSecret(context, "huggingface_api_key", trimmed)
        getPrefs(context).edit().remove(KEY_HUGGINGFACE_API_KEY).apply()
    }

    fun clearHuggingFaceApiKey(context: Context) {
        com.example.security.SecureStorage.clearSecret(context, "huggingface_api_key")
        getPrefs(context).edit().remove(KEY_HUGGINGFACE_API_KEY).apply()
    }

    fun getHuggingFaceModel(context: Context): String {
        return getPrefs(context).getString(KEY_HUGGINGFACE_MODEL, "distilbert-base-uncased") ?: "distilbert-base-uncased"
    }

    fun setHuggingFaceModel(context: Context, model: String) {
        getPrefs(context).edit().putString(KEY_HUGGINGFACE_MODEL, model.trim()).apply()
    }
    
    fun getAiProvider(context: Context): String {
        return getPrefs(context).getString(KEY_AI_PROVIDER, "GEMINI") ?: "GEMINI"
    }

    fun setAiProvider(context: Context, provider: String) {
        getPrefs(context).edit().putString(KEY_AI_PROVIDER, provider.trim().uppercase()).apply()
    }
    
    fun getOpenRouterModel(context: Context): String {
        return getPrefs(context).getString(KEY_OPENROUTER_MODEL, "openrouter/free") ?: "openrouter/free"
    }

    fun setOpenRouterModel(context: Context, model: String) {
        getPrefs(context).edit().putString(KEY_OPENROUTER_MODEL, model.trim()).apply()
    }

    fun getGeminiModel(context: Context): String {
        return getPrefs(context).getString(KEY_GEMINI_MODEL, com.example.ai.GeminiModelRegistry.DEFAULT_AGENT_MODEL) ?: com.example.ai.GeminiModelRegistry.DEFAULT_AGENT_MODEL
    }

    fun setGeminiModel(context: Context, model: String) {
        getPrefs(context).edit().putString(KEY_GEMINI_MODEL, model.trim()).apply()
    }

    fun isOpenRouterEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_OPENROUTER_ENABLED, true)
    }

    fun setOpenRouterEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_OPENROUTER_ENABLED, enabled).apply()
    }

    fun isHuggingFaceEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_HUGGINGFACE_ENABLED, true)
    }

    fun setHuggingFaceEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_HUGGINGFACE_ENABLED, enabled).apply()
    }

    fun isPublicApisEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PUBLIC_APIS_ENABLED, true)
    }

    fun setPublicApisEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_PUBLIC_APIS_ENABLED, enabled).apply()
    }

    fun getMapsScraperUrl(context: Context): String {
        return getPrefs(context).getString(KEY_MAPS_SCRAPER_URL, "http://localhost:8080") ?: "http://localhost:8080"
    }

    fun setMapsScraperUrl(context: Context, url: String) {
        getPrefs(context).edit().putString(KEY_MAPS_SCRAPER_URL, url.trim()).apply()
    }

    fun isMapsScraperEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_MAPS_SCRAPER_ENABLED, true)
    }

    fun setMapsScraperEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_MAPS_SCRAPER_ENABLED, enabled).apply()
    }

    fun getMapsScraperProxyHost(context: Context): String {
        return getPrefs(context).getString(KEY_MAPS_SCRAPER_PROXY_HOST, "") ?: ""
    }

    fun setMapsScraperProxyHost(context: Context, host: String) {
        getPrefs(context).edit().putString(KEY_MAPS_SCRAPER_PROXY_HOST, host.trim()).apply()
    }

    fun getMapsScraperProxyPort(context: Context): Int {
        return getPrefs(context).getInt(KEY_MAPS_SCRAPER_PROXY_PORT, 0)
    }

    fun setMapsScraperProxyPort(context: Context, port: Int) {
        getPrefs(context).edit().putInt(KEY_MAPS_SCRAPER_PROXY_PORT, port).apply()
    }

    fun isOkfMemoryEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_OKF_MEMORY_ENABLED, true)
    }

    fun setOkfMemoryEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_OKF_MEMORY_ENABLED, enabled).apply()
    }

    fun getProviderStatus(context: Context, providerId: String): String {
        val defaultStatus = if (providerId.equals("gemini", ignoreCase = true) && getEffectiveApiKey(context).isNotBlank()) {
            "CONNECTED"
        } else if (providerId.equals("public_apis", ignoreCase = true)) {
            "CONNECTED"
        } else if (providerId.equals("openrouter", ignoreCase = true) && getOpenRouterApiKey(context).isNotBlank()) {
            "CONFIGURED"
        } else if (providerId.equals("huggingface", ignoreCase = true) && getHuggingFaceApiKey(context).isNotBlank()) {
            "CONFIGURED"
        } else {
            "NOT_CONFIGURED"
        }
        return getPrefs(context).getString(PREFIX_PROVIDER_STATUS + providerId.lowercase(), defaultStatus) ?: defaultStatus
    }

    fun setProviderStatus(context: Context, providerId: String, status: String) {
        getPrefs(context).edit().putString(PREFIX_PROVIDER_STATUS + providerId.lowercase(), status).apply()
    }

    fun getProviderRequestCount(context: Context, providerId: String): Int {
        return getPrefs(context).getInt(PREFIX_PROVIDER_COUNT + providerId.lowercase(), 0)
    }

    fun incrementProviderRequestCount(context: Context, providerId: String) {
        val current = getProviderRequestCount(context, providerId)
        getPrefs(context).edit().putInt(PREFIX_PROVIDER_COUNT + providerId.lowercase(), current + 1).apply()
    }

    fun getProviderLastSuccessTime(context: Context, providerId: String): Long {
        return getPrefs(context).getLong(PREFIX_PROVIDER_LAST_SUCCESS + providerId.lowercase(), 0L)
    }

    fun setProviderLastSuccessTime(context: Context, providerId: String, timestamp: Long) {
        getPrefs(context).edit().putLong(PREFIX_PROVIDER_LAST_SUCCESS + providerId.lowercase(), timestamp).apply()
    }

    fun getProviderLastError(context: Context, providerId: String): String? {
        return getPrefs(context).getString(PREFIX_PROVIDER_LAST_ERROR + providerId.lowercase(), null)
    }

    fun setProviderLastError(context: Context, providerId: String, error: String?) {
        getPrefs(context).edit().putString(PREFIX_PROVIDER_LAST_ERROR + providerId.lowercase(), error).apply()
    }

    fun isBuiltInApiKeyAvailable(): Boolean {
        return BuildConfig.GEMINI_API_KEY.isNotBlank()
    }

    fun isApiSetupCompleted(context: Context): Boolean {
        if (getOpenRouterApiKey(context).isNotBlank()) return true
        if (getCustomApiKey(context).isNotBlank()) return true
        return getPrefs(context).getBoolean(KEY_API_SETUP_COMPLETED, false)
    }

    fun setApiSetupCompleted(context: Context, completed: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_API_SETUP_COMPLETED, completed).apply()
    }

    fun hasConfiguredApiKey(context: Context): Boolean {
        val provider = getAiProvider(context)
        return if (provider == "OPENROUTER") {
            getOpenRouterApiKey(context).isNotBlank()
        } else {
            getEffectiveApiKey(context).isNotBlank()
        }
    }

    fun getEffectiveApiKey(context: Context): String {
        val customKey = getCustomApiKey(context)
        if (customKey.isNotBlank()) {
            return customKey
        }
        return BuildConfig.GEMINI_API_KEY
    }

    fun hasAnyApiKey(context: Context): Boolean {
        return getEffectiveApiKey(context).isNotBlank() || getOpenRouterApiKey(context).isNotBlank()
    }

    fun hasMicrophonePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasAccessibilityPermission(context: Context): Boolean {
        return PermissionsManager.isAccessibilityServiceEnabled(
            context,
            KavyaAccessibilityService::class.java
        )
    }

    /**
     * Validates API key against Gemini API with a lightweight prompt
     */
    suspend fun validateGeminiApiKey(apiKey: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Pair(false, "API Key is empty.")
        }
        try {
            val testRequest = GenerateContentRequest(
                contents = listOf(
                    Content(
                        role = "user",
                        parts = listOf(Part(text = "Hi"))
                    )
                )
            )
            val response = RetrofitClient.service.generateContent(
                model = "gemini-3.6-flash",
                apiKey = apiKey.trim(),
                request = testRequest
            )
            val candidate = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            if (!candidate.isNullOrBlank()) {
                Pair(true, "API Key verified successfully!")
            } else {
                Pair(true, "API Key is valid and active.")
            }
        } catch (e: retrofit2.HttpException) {
            when (e.code()) {
                400 -> Pair(false, "Invalid API Key format or request rejected (400).")
                403 -> Pair(false, "Permission denied. Please verify your Gemini API key (403).")
                429 -> Pair(true, "Valid API Key (rate limited, but active).")
                else -> Pair(false, "Verification error: HTTP ${e.code()}")
            }
        } catch (e: Exception) {
            Pair(false, "Connection failed: ${e.localizedMessage ?: "Please check your network and API key"}")
        }
    }

    /**
     * Validates OpenRouter connection by sending a real API request to OpenRouter.
     * Prompt: "Reply only with: Kavya connected."
     * If successful: "OpenRouter connected successfully."
     * If unsuccessful: "OpenRouter connection failed. Please check your API key and internet connection."
     */
    suspend fun validateOpenRouterConnection(apiKey: String, model: String = "openrouter/free"): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Pair(false, "OpenRouter connection failed. Please check your API key and internet connection.")
        }
        try {
            val testReq = com.example.ai.OpenRouterRequest(
                model = model.ifBlank { "openrouter/free" },
                messages = listOf(
                    com.example.ai.OpenRouterMessage(role = "user", content = "Reply only with: Kavya connected.")
                )
            )
            val response = RetrofitClient.openRouterService.generateContent(
                authorization = "Bearer ${apiKey.trim()}",
                request = testReq
            )
            if (response.error != null) {
                return@withContext Pair(false, "OpenRouter connection failed: ${response.error.message ?: "Authentication error"}")
            }
            val reply = response.choices?.firstOrNull()?.message?.content
            if (!reply.isNullOrBlank()) {
                Pair(true, "OpenRouter connected successfully.")
            } else {
                Pair(true, "OpenRouter connected successfully.")
            }
        } catch (e: retrofit2.HttpException) {
            val errorBody = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
            val parsedMsg = if (!errorBody.isNullOrBlank()) {
                try {
                    org.json.JSONObject(errorBody).optJSONObject("error")?.optString("message")
                } catch (_: Exception) { null }
            } else null

            val reason = when (e.code()) {
                401 -> "Invalid OpenRouter API Key (401). Please check your key."
                402 -> "Payment required or credit limit reached (402)."
                403 -> "Access forbidden (403)."
                429 -> "Rate limit reached (429). Please try again shortly."
                else -> parsedMsg ?: "HTTP ${e.code()}"
            }
            Pair(false, "OpenRouter connection failed: $reason")
        } catch (e: Exception) {
            Pair(false, "OpenRouter connection failed. Please check your API key and internet connection.")
        }
    }

    /**
     * Validates Hugging Face connection by sending an inference request to Hugging Face Inference API.
     */
    suspend fun validateHuggingFaceConnection(apiKey: String, model: String = "distilbert-base-uncased"): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Pair(false, "Hugging Face connection failed. Please enter your API key.")
        }
        val targetModel = model.ifBlank { "distilbert-base-uncased" }
        try {
            val url = "https://api-inference.huggingface.co/models/$targetModel"
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = okhttp3.RequestBody.Companion.create(mediaType, "{\"inputs\": \"Hello world test\"}")
            val request = okhttp3.Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${apiKey.trim()}")
                .post(requestBody)
                .build()

            val response = RetrofitClient.okHttpClient.newCall(request).execute()
            val code = response.code
            response.close()

            when (code) {
                200 -> Pair(true, "Hugging Face connected successfully ($targetModel).")
                503 -> Pair(true, "Hugging Face connected ($targetModel is loading).")
                401 -> Pair(false, "Invalid Hugging Face API key (401).")
                404 -> Pair(false, "Model '$targetModel' not found on Hugging Face (404).")
                429 -> Pair(true, "Hugging Face connected (Rate limited, but active).")
                else -> Pair(false, "Hugging Face returned status $code.")
            }
        } catch (e: Exception) {
            Pair(false, "Hugging Face connection failed: ${e.message ?: "Network error"}")
        }
    }

    /**
     * Validates Public API connectivity (Open-Meteo weather endpoint test).
     */
    suspend fun validatePublicApiConnection(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=28.7041&longitude=77.1025&current_weather=true"
            val request = okhttp3.Request.Builder().url(url).get().build()
            val response = RetrofitClient.okHttpClient.newCall(request).execute()
            val isSuccess = response.isSuccessful
            val code = response.code
            response.close()
            if (isSuccess) {
                Pair(true, "Public APIs connected successfully (Open-Meteo active).")
            } else {
                Pair(false, "Public API test returned HTTP $code.")
            }
        } catch (e: Exception) {
            Pair(false, "Public API connection test failed: ${e.message ?: "Network error"}")
        }
    }

    // ============================================================================
    // DYNAMIC / CUSTOM APIS (Extensible for future user APIs)
    // ============================================================================
    private const val KEY_CUSTOM_APIS_JSON = "custom_apis_json"

    fun getCustomApis(context: Context): List<CustomApiConfig> {
        val raw = getPrefs(context).getString(KEY_CUSTOM_APIS_JSON, "[]") ?: "[]"
        val list = mutableListOf<CustomApiConfig>()
        try {
            val jsonArray = org.json.JSONArray(raw)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    CustomApiConfig(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        name = obj.optString("name", "Custom API"),
                        endpointUrl = obj.optString("endpointUrl", ""),
                        apiKey = obj.optString("apiKey", ""),
                        model = obj.optString("model", ""),
                        isEnabled = obj.optBoolean("isEnabled", true),
                        dateAdded = obj.optLong("dateAdded", System.currentTimeMillis())
                    )
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("AppPreferences", "Error reading custom APIs", e)
        }
        return list
    }

    fun saveCustomApi(context: Context, api: CustomApiConfig) {
        val current = getCustomApis(context).toMutableList()
        val index = current.indexOfFirst { it.id == api.id }
        if (index >= 0) {
            current[index] = api
        } else {
            current.add(api)
        }
        persistCustomApis(context, current)
    }

    fun deleteCustomApi(context: Context, id: String) {
        val current = getCustomApis(context).filterNot { it.id == id }
        persistCustomApis(context, current)
    }

    fun setCustomApiEnabled(context: Context, id: String, enabled: Boolean) {
        val current = getCustomApis(context).map {
            if (it.id == id) it.copy(isEnabled = enabled) else it
        }
        persistCustomApis(context, current)
    }

    private fun persistCustomApis(context: Context, list: List<CustomApiConfig>) {
        val jsonArray = org.json.JSONArray()
        for (api in list) {
            val obj = org.json.JSONObject().apply {
                put("id", api.id)
                put("name", api.name)
                put("endpointUrl", api.endpointUrl)
                put("apiKey", api.apiKey)
                put("model", api.model)
                put("isEnabled", api.isEnabled)
                put("dateAdded", api.dateAdded)
            }
            jsonArray.put(obj)
        }
        getPrefs(context).edit().putString(KEY_CUSTOM_APIS_JSON, jsonArray.toString()).apply()
    }

    /**
     * Real connection test for user-added custom APIs.
     */
    suspend fun validateCustomApiConnection(api: CustomApiConfig): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (api.endpointUrl.isBlank()) {
            return@withContext Pair(false, "Endpoint URL cannot be empty.")
        }
        try {
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBuilder = okhttp3.Request.Builder().url(api.endpointUrl.trim())

            if (api.apiKey.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer ${api.apiKey.trim()}")
                requestBuilder.addHeader("api-key", api.apiKey.trim())
            }

            // If it's an OpenAI-style endpoint, send a lightweight test payload
            if (api.endpointUrl.contains("chat") || api.endpointUrl.contains("completions") || api.endpointUrl.contains("generate")) {
                val jsonBody = org.json.JSONObject().apply {
                    if (api.model.isNotBlank()) put("model", api.model.trim())
                    put("messages", org.json.JSONArray().put(org.json.JSONObject().apply {
                        put("role", "user")
                        put("content", "ping")
                    }))
                    put("max_tokens", 5)
                }
                val body = okhttp3.RequestBody.Companion.create(mediaType, jsonBody.toString())
                requestBuilder.post(body)
            } else {
                requestBuilder.get()
            }

            val startTime = System.currentTimeMillis()
            val response = RetrofitClient.okHttpClient.newCall(requestBuilder.build()).execute()
            val latency = System.currentTimeMillis() - startTime
            val code = response.code
            val isOk = response.isSuccessful
            response.close()

            if (isOk) {
                Pair(true, "Connected successfully! (HTTP $code, ${latency}ms)")
            } else if (code in 400..499 && code != 404) {
                // 401/403/422/429 means server reached and responding to request
                Pair(true, "Endpoint reachable (HTTP $code: ${response.message}, ${latency}ms)")
            } else {
                Pair(false, "Server returned HTTP $code (${latency}ms)")
            }
        } catch (e: Exception) {
            Pair(false, "Connection failed: ${e.localizedMessage ?: e.message}")
        }
    }

    private const val KEY_VISUAL_AUTOMATION_ENABLED = "key_visual_automation_enabled"
    private const val KEY_VISUAL_AUTOMATION_MODE = "key_visual_automation_mode"
    private const val KEY_CONFIDENCE_THRESHOLD = "key_confidence_threshold"

    fun isVisualAutomationEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_VISUAL_AUTOMATION_ENABLED, true)
    }

    fun setVisualAutomationEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_VISUAL_AUTOMATION_ENABLED, enabled).apply()
    }

    fun getVisualAutomationMode(context: Context): String {
        return getPrefs(context).getString(KEY_VISUAL_AUTOMATION_MODE, "AUTOMATIC_SAFE") ?: "AUTOMATIC_SAFE"
    }

    fun setVisualAutomationMode(context: Context, mode: String) {
        getPrefs(context).edit().putString(KEY_VISUAL_AUTOMATION_MODE, mode).apply()
    }

    fun getConfidenceThreshold(context: Context): Float {
        return getPrefs(context).getFloat(KEY_CONFIDENCE_THRESHOLD, 0.70f)
    }

    fun setConfidenceThreshold(context: Context, threshold: Float) {
        getPrefs(context).edit().putFloat(KEY_CONFIDENCE_THRESHOLD, threshold).apply()
    }

    private const val KEY_BACKGROUND_VOICE_ENABLED = "key_background_voice_enabled"
    private const val KEY_MIC_LISTENING_ENABLED = "key_mic_listening_enabled"
    private const val KEY_WAKE_GESTURE_ENABLED = "key_wake_gesture_enabled"
    private const val KEY_SLEEP_GESTURE_ENABLED = "key_sleep_gesture_enabled"
    private const val KEY_WAKE_SENSITIVITY = "key_wake_sensitivity"
    private const val KEY_SLEEP_SENSITIVITY = "key_sleep_sensitivity"
    private const val KEY_KAVYA_STATE = "key_kavya_state"

    fun isBackgroundVoiceEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_BACKGROUND_VOICE_ENABLED, true)
    fun setBackgroundVoiceEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_BACKGROUND_VOICE_ENABLED, enabled).apply()

    fun isMicListeningEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_MIC_LISTENING_ENABLED, true)
    fun setMicListeningEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_MIC_LISTENING_ENABLED, enabled).apply()

    fun isWakeGestureEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_WAKE_GESTURE_ENABLED, true)
    fun setWakeGestureEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_WAKE_GESTURE_ENABLED, enabled).apply()

    fun isSleepGestureEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_SLEEP_GESTURE_ENABLED, true)
    fun setSleepGestureEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_SLEEP_GESTURE_ENABLED, enabled).apply()

    fun getWakeSensitivity(context: Context): Float = getPrefs(context).getFloat(KEY_WAKE_SENSITIVITY, 0.75f)
    fun setWakeSensitivity(context: Context, value: Float) = getPrefs(context).edit().putFloat(KEY_WAKE_SENSITIVITY, value.coerceIn(0.1f, 1.0f)).apply()

    fun getSleepSensitivity(context: Context): Float = getPrefs(context).getFloat(KEY_SLEEP_SENSITIVITY, 0.75f)
    fun setSleepSensitivity(context: Context, value: Float) = getPrefs(context).edit().putFloat(KEY_SLEEP_SENSITIVITY, value.coerceIn(0.1f, 1.0f)).apply()

    fun getKavyaState(context: Context): String = getPrefs(context).getString(KEY_KAVYA_STATE, "ACTIVE") ?: "ACTIVE"
    fun setKavyaState(context: Context, state: String) = getPrefs(context).edit().putString(KEY_KAVYA_STATE, state).apply()
}

/**
 * Data class representing any user-defined or future API provider.
 */
data class CustomApiConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val endpointUrl: String,
    val apiKey: String,
    val model: String = "",
    val isEnabled: Boolean = true,
    val dateAdded: Long = System.currentTimeMillis()
)
