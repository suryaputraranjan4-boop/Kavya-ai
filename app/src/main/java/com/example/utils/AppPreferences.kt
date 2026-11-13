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
import kotlinx.coroutines.CancellationException
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
    private const val KEY_OFFLINE_FALLBACK = "key_offline_fallback"
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
    private const val KEY_MIC_ENABLED = "key_kavya_mic_enabled"
    private const val PREFIX_PROVIDER_STATUS = "provider_status_"
    private const val PREFIX_PROVIDER_COUNT = "provider_count_"
    private const val PREFIX_PROVIDER_LAST_SUCCESS = "provider_last_success_"
    private const val PREFIX_PROVIDER_LAST_ERROR = "provider_last_error_"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isMicEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_MIC_ENABLED, true)
    }

    fun setMicEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_MIC_ENABLED, enabled).apply()
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

    fun isOfflineFallbackEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_OFFLINE_FALLBACK, false) || getAiProvider(context) == "GEMMA_OFFLINE"
    }

    fun setOfflineFallbackEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_OFFLINE_FALLBACK, enabled).apply()
    }

    fun isOfflineModeEnabled(context: Context): Boolean = isOfflineFallbackEnabled(context)
    fun setOfflineModeEnabled(context: Context, enabled: Boolean) = setOfflineFallbackEnabled(context, enabled)

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

    fun setCustomApiKey(context: Context, key: String): Boolean {
        val trimmed = key.trim()
        val saved = com.example.security.SecureStorage.saveSecret(context, "gemini_api_key", trimmed)
        getPrefs(context).edit().remove(KEY_CUSTOM_API_KEY).apply()
        if (saved && trimmed.isNotBlank() && !isPlaceholderKey(trimmed)) {
            setProviderStatus(context, "gemini", "CONFIGURED")
            setProviderLastError(context, "gemini", null)
        }
        return saved
    }

    fun clearCustomApiKey(context: Context) {
        com.example.security.SecureStorage.clearSecret(context, "gemini_api_key")
        getPrefs(context).edit().remove(KEY_CUSTOM_API_KEY).apply()
    }
    
    fun getOpenRouterApiKey(context: Context): String {
        return com.example.security.SecureStorage.getSecret(context, "openrouter_api_key")
    }

    fun setOpenRouterApiKey(context: Context, key: String): Boolean {
        val trimmed = key.trim()
        val saved = com.example.security.SecureStorage.saveSecret(context, "openrouter_api_key", trimmed)
        getPrefs(context).edit().remove(KEY_OPENROUTER_API_KEY).apply()
        if (saved && trimmed.isNotBlank() && !isPlaceholderKey(trimmed)) {
            setProviderStatus(context, "openrouter", "CONFIGURED")
            setProviderLastError(context, "openrouter", null)
        }
        return saved
    }

    fun clearOpenRouterApiKey(context: Context) {
        com.example.security.SecureStorage.clearSecret(context, "openrouter_api_key")
        getPrefs(context).edit().remove(KEY_OPENROUTER_API_KEY).apply()
    }

    fun getHuggingFaceApiKey(context: Context): String {
        return com.example.security.SecureStorage.getSecret(context, "huggingface_api_key")
    }

    fun setHuggingFaceApiKey(context: Context, key: String): Boolean {
        val trimmed = key.trim()
        val saved = com.example.security.SecureStorage.saveSecret(context, "huggingface_api_key", trimmed)
        getPrefs(context).edit().remove(KEY_HUGGINGFACE_API_KEY).apply()
        if (saved && trimmed.isNotBlank() && !isPlaceholderKey(trimmed)) {
            setProviderStatus(context, "huggingface", "CONFIGURED")
            setProviderLastError(context, "huggingface", null)
        }
        return saved
    }

    fun clearHuggingFaceApiKey(context: Context) {
        com.example.security.SecureStorage.clearSecret(context, "huggingface_api_key")
        getPrefs(context).edit().remove(KEY_HUGGINGFACE_API_KEY).apply()
    }

    fun getHuggingFaceModel(context: Context): String {
        val legacyDefault = "distilbert-base-uncased"
        val chatDefault = "meta-llama/Llama-3.2-1B-Instruct"
        val stored = getPrefs(context).getString(KEY_HUGGINGFACE_MODEL, chatDefault).orEmpty().trim()
        return if (stored.isBlank() || stored.equals(legacyDefault, ignoreCase = true)) {
            chatDefault
        } else {
            stored
        }
    }

    fun setHuggingFaceModel(context: Context, model: String) {
        getPrefs(context).edit().putString(KEY_HUGGINGFACE_MODEL, model.trim()).apply()
    }
    
    fun getAiProvider(context: Context): String {
        return getPrefs(context).getString(KEY_AI_PROVIDER, "GEMINI") ?: "GEMINI"
    }

    fun setAiProvider(context: Context, provider: String) {
        val normalized = provider.trim().uppercase(java.util.Locale.ROOT)
        getPrefs(context).edit().putString(KEY_AI_PROVIDER, normalized).apply()
        com.example.orchestrator.AiAuthorityGateway.onProviderSwitched(normalized)
    }

    fun getApiKeyForProvider(context: Context, providerName: String): String {
        return when (providerName.trim().uppercase(java.util.Locale.ROOT)) {
            "OPENROUTER" -> getOpenRouterApiKey(context)
            "HUGGINGFACE" -> getHuggingFaceApiKey(context)
            "CUSTOM", "CUSTOM_API" -> getCustomApis(context).firstOrNull { it.isEnabled }?.apiKey.orEmpty()
            "GEMMA_OFFLINE", "PUBLIC_APIS" -> ""
            "GEMINI" -> getEffectiveApiKey(context)
            else -> getEffectiveApiKey(context)
        }
    }

    fun getActiveApiKey(context: Context): String {
        return getApiKeyForProvider(context, getAiProvider(context))
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
        return getPrefs(context).getBoolean(KEY_MAPS_SCRAPER_ENABLED, false)
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
        val key1 = try { BuildConfig.GEMINI_API_KEY1.trim() } catch (_: Throwable) { "" }
        if (key1.isNotBlank() && !isPlaceholderKey(key1)) return true
        val key = BuildConfig.GEMINI_API_KEY.trim()
        return key.isNotBlank() && !isPlaceholderKey(key)
    }

    fun isApiSetupCompleted(context: Context): Boolean {
        if (getOpenRouterApiKey(context).isNotBlank() && !isPlaceholderKey(getOpenRouterApiKey(context))) return true
        if (getCustomApiKey(context).isNotBlank() && !isPlaceholderKey(getCustomApiKey(context))) return true
        return getPrefs(context).getBoolean(KEY_API_SETUP_COMPLETED, false)
    }

    fun setApiSetupCompleted(context: Context, completed: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_API_SETUP_COMPLETED, completed).apply()
    }

    fun hasConfiguredApiKey(context: Context): Boolean {
        val provider = getAiProvider(context).trim().uppercase(java.util.Locale.ROOT)
        return when (provider) {
            "OPENROUTER" -> {
                val key = getOpenRouterApiKey(context).trim()
                key.isNotBlank() && !isPlaceholderKey(key)
            }
            "HUGGINGFACE" -> {
                val key = getHuggingFaceApiKey(context).trim()
                key.isNotBlank() && !isPlaceholderKey(key)
            }
            "CUSTOM", "CUSTOM_API" -> {
                getCustomApis(context).any { it.isEnabled && it.apiKey.isNotBlank() && !isPlaceholderKey(it.apiKey) }
            }
            "GEMMA_OFFLINE", "PUBLIC_APIS" -> true
            "GEMINI" -> getEffectiveApiKey(context).isNotBlank()
            else -> getActiveApiKey(context).isNotBlank() || getEffectiveApiKey(context).isNotBlank()
        }
    }

    fun getEffectiveApiKey(context: Context): String {
        val customKey = getCustomApiKey(context).trim()
        if (customKey.isNotBlank() && !isPlaceholderKey(customKey)) {
            return customKey
        }
        // Prefer GEMINI_API_KEY1 if configured
        val buildKey1 = try { BuildConfig.GEMINI_API_KEY1.trim() } catch (_: Throwable) { "" }
        if (buildKey1.isNotBlank() && !isPlaceholderKey(buildKey1)) {
            return buildKey1
        }
        val buildKey = BuildConfig.GEMINI_API_KEY.trim()
        if (buildKey.isNotBlank() && !isPlaceholderKey(buildKey)) {
            return buildKey
        }
        return ""
    }

    fun isPlaceholderKey(key: String): Boolean {
        val lower = key.lowercase(java.util.Locale.ROOT).trim()
        return lower.isBlank() ||
                lower == "my_gemini_api_key" ||
                lower == "your_gemini_api_key" ||
                lower == "your_gemini_api_key1" ||
                lower == "my_gemini_api_key1" ||
                lower == "your_api_key" ||
                lower == "my_api_key" ||
                lower == "placeholder" ||
                lower == "dummy" ||
                lower == "null" ||
                lower == "undefined" ||
                lower == "enter_key_here" ||
                lower == "change_me" ||
                lower.startsWith("your_") ||
                (lower.startsWith("my_") && lower.endsWith("_key"))
    }

    fun hasAnyApiKey(context: Context): Boolean {
        return getEffectiveApiKey(context).isNotBlank() ||
                getOpenRouterApiKey(context).isNotBlank() ||
                getHuggingFaceApiKey(context).isNotBlank() ||
                getCustomApis(context).any { it.apiKey.isNotBlank() }
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
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank() || isPlaceholderKey(cleanKey)) {
            return@withContext Pair(false, "API Key is empty or placeholder.")
        }
        val candidateModels = com.example.ai.GeminiModelRegistry.CHAT_MODEL_TIERS.distinct()
        val testRequest = GenerateContentRequest(
            contents = listOf(
                Content(
                    role = "user",
                    parts = listOf(Part(text = "Hi"))
                )
            )
        )
        var lastHttpCode = 0
        var lastHttpBody = ""
        for (model in candidateModels) {
            try {
                val response = RetrofitClient.service.generateContent(
                    model = model,
                    apiKey = cleanKey,
                    request = testRequest
                )
                val candidate = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (!candidate.isNullOrBlank()) {
                    return@withContext Pair(true, "API Key verified with $model!")
                } else {
                    return@withContext Pair(true, "API Key is valid and active.")
                }
            } catch (e: retrofit2.HttpException) {
                lastHttpCode = e.code()
                lastHttpBody = try { e.response()?.errorBody()?.string().orEmpty() } catch (_: Exception) { "" }
                if (lastHttpCode == 404) {
                    // Try next candidate model
                    continue
                }
                if (lastHttpCode == 429) {
                    return@withContext Pair(true, "Valid API Key (rate limited, but active).")
                }
                break
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (io: java.io.IOException) {
                return@withContext Pair(false, "Network error during validation: ${io.localizedMessage}")
            } catch (e: Exception) {
                return@withContext Pair(false, "Connection error: ${e.localizedMessage ?: "Check network and key"}")
            }
        }

        when (lastHttpCode) {
            401 -> {
                if (lastHttpBody.contains("ACCESS_TOKEN_TYPE_UNSUPPORTED")) {
                    Pair(false, "Key was rejected (401: Unsupported Token Type). If using an AQ key, ensure Generative Language API is enabled on your GCP project or generate a new Gemini API Key in Google AI Studio.")
                } else {
                    Pair(false, "Invalid API key (401 Unauthorized). Check your key in Google AI Studio.")
                }
            }
            403 -> Pair(false, "Permission denied (403). Ensure Generative Language API is enabled on your project.")
            400 -> Pair(false, "Invalid API Key format or request rejected (400).")
            429 -> Pair(true, "Valid API Key (rate limited, but active).")
            404 -> Pair(false, "Candidate models not found on this API tier (404).")
            else -> Pair(false, if (lastHttpCode != 0) "Verification error: HTTP $lastHttpCode" else "Connection failed. Please check network.")
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Pair(false, "OpenRouter connection failed. Please check your API key and internet connection.")
        }
    }

    /**
     * Validates Hugging Face connection by sending an inference request to Hugging Face Inference API.
     */
    suspend fun validateHuggingFaceConnection(
        apiKey: String,
        model: String = com.example.ai.providers.HuggingFaceProvider.MODEL_TEXT_GEN
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Pair(false, "Hugging Face connection failed. Please enter your API key.")
        }
        val targetModel = model.ifBlank { com.example.ai.providers.HuggingFaceProvider.MODEL_TEXT_GEN }
        try {
            val url = "https://router.huggingface.co/hf-inference/models/$targetModel"
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = okhttp3.RequestBody.Companion.create(mediaType, "{\"inputs\": \"Hello world test\"}")
            val request = okhttp3.Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${apiKey.trim()}")
                .post(requestBody)
                .build()

            val code = RetrofitClient.okHttpClient.newCall(request).execute().use { it.code }

            when (code) {
                200 -> Pair(true, "Hugging Face connected successfully ($targetModel).")
                503 -> Pair(true, "Hugging Face connected ($targetModel is loading).")
                401 -> Pair(false, "Invalid Hugging Face API key (401).")
                404 -> Pair(false, "Model '$targetModel' not found on Hugging Face (404).")
                429 -> Pair(true, "Hugging Face connected (Rate limited, but active).")
                else -> Pair(false, "Hugging Face returned status $code.")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Pair(false, "Public API connection test failed: ${e.message ?: "Network error"}")
        }
    }

    // ============================================================================
    // DYNAMIC / CUSTOM APIS (Extensible for future user APIs)
    // ============================================================================
    private const val KEY_CUSTOM_APIS_JSON = "custom_apis_json"

    private fun customApiSecretKey(id: String): String = "custom_api_key_$id"

    private fun customApiMetadata(list: List<CustomApiConfig>): org.json.JSONArray {
        val jsonArray = org.json.JSONArray()
        for (api in list) {
            jsonArray.put(org.json.JSONObject().apply {
                put("id", api.id)
                put("name", api.name)
                put("endpointUrl", api.endpointUrl)
                put("model", api.model)
                put("isEnabled", api.isEnabled)
                put("dateAdded", api.dateAdded)
            })
        }
        return jsonArray
    }

    fun getCustomApis(context: Context): List<CustomApiConfig> {
        val prefs = getPrefs(context)
        val raw = prefs.getString(KEY_CUSTOM_APIS_JSON, "[]") ?: "[]"
        val list = mutableListOf<CustomApiConfig>()
        var containsLegacyPlaintextKeys = false
        try {
            val jsonArray = org.json.JSONArray(raw)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.optString("id", java.util.UUID.randomUUID().toString())
                    .ifBlank { java.util.UUID.randomUUID().toString() }
                val secretKey = customApiSecretKey(id)
                val legacyKey = obj.optString("apiKey", "")
                if (legacyKey.isNotBlank()) {
                    containsLegacyPlaintextKeys = true
                    // One-time migration from the old plaintext JSON field to Keystore AES-GCM.
                    if (com.example.security.SecureStorage.getSecret(context, secretKey).isBlank()) {
                        val migrated = com.example.security.SecureStorage.saveSecret(context, secretKey, legacyKey)
                        if (!migrated) {
                            android.util.Log.w("AppPreferences", "Could not migrate a legacy custom API credential; it was removed from plaintext storage.")
                        }
                    }
                }
                list.add(
                    CustomApiConfig(
                        id = id,
                        name = obj.optString("name", "Custom API"),
                        endpointUrl = obj.optString("endpointUrl", ""),
                        apiKey = com.example.security.SecureStorage.getSecret(context, secretKey),
                        model = obj.optString("model", ""),
                        isEnabled = obj.optBoolean("isEnabled", true),
                        dateAdded = obj.optLong("dateAdded", System.currentTimeMillis())
                    )
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("AppPreferences", "Error reading custom APIs (${e.javaClass.simpleName}).")
        }

        // Always rewrite legacy records without the plaintext apiKey property.
        if (containsLegacyPlaintextKeys) {
            prefs.edit().putString(KEY_CUSTOM_APIS_JSON, customApiMetadata(list).toString()).commit()
        }
        return list
    }

    fun saveCustomApi(context: Context, api: CustomApiConfig): Boolean {
        val keyName = customApiSecretKey(api.id)
        val credentialSaved = if (api.apiKey.isBlank()) {
            com.example.security.SecureStorage.clearSecret(context, keyName)
            true
        } else {
            val saved = com.example.security.SecureStorage.saveSecret(context, keyName, api.apiKey)
            if (!saved) {
                // Never put a secret back into ordinary SharedPreferences when encryption fails.
                com.example.security.SecureStorage.clearSecret(context, keyName)
            }
            saved
        }

        val current = getCustomApis(context).toMutableList()
        val index = current.indexOfFirst { it.id == api.id }
        if (index >= 0) {
            current[index] = api
        } else {
            current.add(api)
        }
        return credentialSaved && persistCustomApiMetadata(context, current)
    }

    fun deleteCustomApi(context: Context, id: String) {
        val current = getCustomApis(context).filterNot { it.id == id }
        com.example.security.SecureStorage.clearSecret(context, customApiSecretKey(id))
        persistCustomApiMetadata(context, current)
    }

    fun setCustomApiEnabled(context: Context, id: String, enabled: Boolean) {
        val current = getCustomApis(context).map {
            if (it.id == id) it.copy(isEnabled = enabled) else it
        }
        // Metadata-only updates must not re-encrypt or clear unrelated secrets.
        persistCustomApiMetadata(context, current)
    }

    private fun persistCustomApiMetadata(context: Context, list: List<CustomApiConfig>): Boolean {
        return getPrefs(context).edit()
            .putString(KEY_CUSTOM_APIS_JSON, customApiMetadata(list).toString())
            .commit()
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
        } catch (cancelled: CancellationException) {
            throw cancelled
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
    private const val KEY_WAKE_GESTURE_ENABLED = "key_wake_gesture_enabled"
    private const val KEY_SLEEP_GESTURE_ENABLED = "key_sleep_gesture_enabled"
    private const val KEY_WAKE_SENSITIVITY = "key_wake_sensitivity"
    private const val KEY_SLEEP_SENSITIVITY = "key_sleep_sensitivity"
    private const val KEY_KAVYA_STATE = "key_kavya_state"

    fun isBackgroundVoiceEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_BACKGROUND_VOICE_ENABLED, true)
    fun setBackgroundVoiceEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_BACKGROUND_VOICE_ENABLED, enabled).apply()

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

    // Obsidian Vault Preferences
    private const val KEY_OBSIDIAN_VAULT_URI = "key_obsidian_vault_uri"
    private const val KEY_OBSIDIAN_LAST_SYNC_TS = "key_obsidian_last_sync_ts"
    private const val KEY_OBSIDIAN_AUTO_SYNC = "key_obsidian_auto_sync"

    fun getObsidianVaultUri(context: Context): String? = getPrefs(context).getString(KEY_OBSIDIAN_VAULT_URI, null)
    fun setObsidianVaultUri(context: Context, uri: String?) = getPrefs(context).edit().putString(KEY_OBSIDIAN_VAULT_URI, uri).apply()

    fun getObsidianLastSyncTs(context: Context): Long = getPrefs(context).getLong(KEY_OBSIDIAN_LAST_SYNC_TS, 0L)
    fun setObsidianLastSyncTs(context: Context, ts: Long) = getPrefs(context).edit().putLong(KEY_OBSIDIAN_LAST_SYNC_TS, ts).apply()

    fun isObsidianAutoSyncEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_OBSIDIAN_AUTO_SYNC, true)
    fun setObsidianAutoSyncEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_OBSIDIAN_AUTO_SYNC, enabled).apply()

    // Chatterbox Hindi & Voice Studio Preferences
    private const val KEY_CHATTERBOX_BACKEND_URL = "key_chatterbox_backend_url"
    private const val KEY_CHATTERBOX_AUTH_TOKEN = "key_chatterbox_auth_token"
    private const val KEY_ACTIVE_VOICE_PROFILE_ID = "key_active_voice_profile_id"
    private const val KEY_SPEECH_ENGINE = "key_speech_engine"

    fun getChatterboxBackendUrl(context: Context): String {
        // 10.0.2.2 is emulator-only and cannot reach a server from a physical phone.
        val saved = getPrefs(context).getString(KEY_CHATTERBOX_BACKEND_URL, "").orEmpty()
        return saved.takeUnless { it.contains("10.0.2.2") } ?: ""
    }

    fun setChatterboxBackendUrl(context: Context, url: String) {
        getPrefs(context).edit().putString(KEY_CHATTERBOX_BACKEND_URL, url.trim()).apply()
    }

    fun getChatterboxAuthToken(context: Context): String {
        return com.example.security.SecureStorage.getSecret(context, "chatterbox_auth_token")
    }

    fun setChatterboxAuthToken(context: Context, token: String) {
        com.example.security.SecureStorage.saveSecret(context, "chatterbox_auth_token", token.trim())
    }

    fun getActiveVoiceProfileId(context: Context): String {
        return getPrefs(context).getString(KEY_ACTIVE_VOICE_PROFILE_ID, "default_kavya_hindi") ?: "default_kavya_hindi"
    }

    fun setActiveVoiceProfileId(context: Context, profileId: String) {
        getPrefs(context).edit().putString(KEY_ACTIVE_VOICE_PROFILE_ID, profileId).apply()
    }

    fun getSpeechEngine(context: Context): String {
        return getPrefs(context).getString(KEY_SPEECH_ENGINE, "INDIC_TINY_TTS") ?: "INDIC_TINY_TTS"
    }

    fun setSpeechEngine(context: Context, engine: String) {
        getPrefs(context).edit().putString(KEY_SPEECH_ENGINE, engine).apply()
    }

    fun getScreenPresentationLevel(context: Context): String {
        return getPrefs(context).getString("key_screen_presentation_level", "STANDARD") ?: "STANDARD"
    }

    fun setScreenPresentationLevel(context: Context, level: String) {
        getPrefs(context).edit().putString("key_screen_presentation_level", level).apply()
    }

    // Floating Orb & Adaptive Motion / Screen Share Quality Preferences
    private const val KEY_FLOATING_ORB_ENABLED = "key_floating_orb_enabled"
    private const val KEY_FLOATING_ORB_X = "key_floating_orb_x"
    private const val KEY_FLOATING_ORB_Y = "key_floating_orb_y"
    private const val KEY_ORB_THEME = "key_orb_theme"
    private const val KEY_ORB_CURSOR_STYLE = "key_orb_cursor_style"
    private const val KEY_REDUCED_MOTION = "key_reduced_motion"
    private const val KEY_CONTINUOUS_LISTENING = "key_continuous_listening"
    private const val KEY_SCREEN_SHARE_QUALITY_MODE = "key_screen_share_quality_mode"

    fun isFloatingOrbEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_FLOATING_ORB_ENABLED, false)
    }

    fun setFloatingOrbEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_FLOATING_ORB_ENABLED, enabled).apply()
    }

    fun getFloatingOrbPosX(context: Context): Int {
        return getPrefs(context).getInt(KEY_FLOATING_ORB_X, 20)
    }

    fun setFloatingOrbPosX(context: Context, x: Int) {
        getPrefs(context).edit().putInt(KEY_FLOATING_ORB_X, x).apply()
    }

    fun getFloatingOrbPosY(context: Context): Int {
        return getPrefs(context).getInt(KEY_FLOATING_ORB_Y, 240)
    }

    fun setFloatingOrbPosY(context: Context, y: Int) {
        getPrefs(context).edit().putInt(KEY_FLOATING_ORB_Y, y).apply()
    }

    fun getOrbThemeName(context: Context): String {
        return getPrefs(context).getString(KEY_ORB_THEME, "NEON_CYAN") ?: "NEON_CYAN"
    }

    fun setOrbThemeName(context: Context, themeName: String) {
        getPrefs(context).edit().putString(KEY_ORB_THEME, themeName).apply()
    }

    fun getOrbCursorStyleName(context: Context): String {
        return getPrefs(context).getString(KEY_ORB_CURSOR_STYLE, "HOLOGRAPHIC_POINTER") ?: "HOLOGRAPHIC_POINTER"
    }

    fun setOrbCursorStyleName(context: Context, cursorStyleName: String) {
        getPrefs(context).edit().putString(KEY_ORB_CURSOR_STYLE, cursorStyleName).apply()
    }

    fun isReducedMotionEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_REDUCED_MOTION, false)
    }

    fun setReducedMotionEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_REDUCED_MOTION, enabled).apply()
    }

    fun isContinuousListeningEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_CONTINUOUS_LISTENING, true)
    }

    fun setContinuousListeningEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_CONTINUOUS_LISTENING, enabled).apply()
    }

    fun getScreenShareQualityMode(context: Context): String {
        return getPrefs(context).getString(KEY_SCREEN_SHARE_QUALITY_MODE, "AUTO") ?: "AUTO"
    }

    fun setScreenShareQualityMode(context: Context, mode: String) {
        getPrefs(context).edit().putString(KEY_SCREEN_SHARE_QUALITY_MODE, mode).apply()
    }
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
