package com.example.ai

import android.content.Context
import android.util.Log
import com.example.utils.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Result returned by the Chatterbox Hindi speech engine.
 */
sealed class ChatterboxResult {
    data class Success(val audioBytes: ByteArray, val sampleRate: Int = 24000) : ChatterboxResult()
    data class Error(val message: String, val isBackendUnavailable: Boolean = false) : ChatterboxResult()
}

/**
 * ChatterboxTtsClient connects Kavya to the Chatterbox Hindi TTS & voice conditioning service.
 * Supports configurable endpoints (local network, remote GPU server, or cloud deployment),
 * reference audio conditioning for custom voice profiles, and health checks.
 */
class ChatterboxTtsClient(private val context: Context) {

    companion object {
        private const val TAG = "ChatterboxTtsClient"
        private const val DEFAULT_TIMEOUT_SECONDS = 45L
        val AUDIO_WAV_MEDIA_TYPE = "audio/wav".toMediaType()
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Checks the health and readiness of the configured Chatterbox backend.
     */
    suspend fun checkHealth(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val baseUrl = AppPreferences.getChatterboxBackendUrl(context).trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            return@withContext Pair(false, "Chatterbox backend URL is not configured.")
        }

        val healthEndpoints = listOf("$baseUrl/health", "$baseUrl/status", "$baseUrl/v1/models", baseUrl)
        for (endpoint in healthEndpoints) {
            try {
                val requestBuilder = Request.Builder().url(endpoint).get()
                val token = AppPreferences.getChatterboxAuthToken(context).trim()
                if (token.isNotBlank()) {
                    requestBuilder.addHeader("Authorization", "Bearer $token")
                }
                httpClient.newCall(requestBuilder.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        return@withContext Pair(true, "Chatterbox backend is online and reachable (${response.code}).")
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Health check attempt failed for $endpoint: ${e.message}")
            }
        }
        return@withContext Pair(
            false,
            "Cannot connect to Chatterbox server at $baseUrl. Ensure your GPU backend or inference runner is running."
        )
    }

    /**
     * Synthesizes Hindi text to speech using Chatterbox Hindi.
     * Optionally conditions speech on reference audio bytes or file from a voice profile.
     */
    suspend fun synthesize(
        text: String,
        referenceAudioBytes: ByteArray? = null,
        referenceAudioPath: String? = null,
        pitchMultiplier: Float = 1.0f,
        speedMultiplier: Float = 1.0f,
        emotion: String = "natural"
    ): ChatterboxResult = withContext(Dispatchers.IO) {
        if (text.isBlank()) {
            return@withContext ChatterboxResult.Error("Text cannot be empty.")
        }

        val baseUrl = AppPreferences.getChatterboxBackendUrl(context).trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            return@withContext ChatterboxResult.Error(
                "Chatterbox backend URL is not set. Open Voice Studio to configure or test your backend.",
                isBackendUnavailable = true
            )
        }

        val token = AppPreferences.getChatterboxAuthToken(context).trim()

        // Read reference audio if available
        val refBytes = referenceAudioBytes ?: run {
            if (!referenceAudioPath.isNullOrBlank()) {
                val f = File(referenceAudioPath)
                if (f.exists() && f.canRead()) {
                    try { f.readBytes() } catch (_: Exception) { null }
                } else null
            } else null
        }

        val refBase64 = if (refBytes != null && refBytes.isNotEmpty()) {
            android.util.Base64.encodeToString(refBytes, android.util.Base64.NO_WRAP)
        } else null

        // 1. Try standard OpenAI-compatible audio speech endpoint (/v1/audio/speech)
        // or dedicated Chatterbox Hindi endpoint (/synthesize or /generate)
        val payload = JSONObject().apply {
            put("text", text)
            put("input", text) // OpenAI format compatibility
            put("language", "hi")
            put("model", "chatterbox-multilingual-hi")
            put("voice", AppPreferences.getActiveVoiceProfileId(context))
            put("speed", speedMultiplier)
            put("pitch", pitchMultiplier)
            put("emotion", emotion)
            put("response_format", "wav")
            put("format", "wav")
            if (!refBase64.isNullOrBlank()) {
                put("reference_audio", refBase64)
                put("audio_prompt", refBase64)
                val extension = referenceAudioPath?.let { File(it).extension.lowercase() }.orEmpty()
                put("reference_audio_format", extension.ifBlank { "auto" })
            }
        }

        val endpointsToTry = listOf(
            "$baseUrl/v1/audio/speech",
            "$baseUrl/synthesize",
            "$baseUrl/generate",
            "$baseUrl/tts"
        )

        var lastError = "Failed to reach Chatterbox backend at $baseUrl."
        var backendReached = false

        for (url in endpointsToTry) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "audio/wav, application/json, application/octet-stream")
                    .apply {
                        if (token.isNotBlank()) addHeader("Authorization", "Bearer $token")
                    }
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    backendReached = true
                    val contentType = response.header("Content-Type").orEmpty()
                    if (!response.isSuccessful) {
                        val detail = response.body?.string()?.take(500).orEmpty()
                        lastError = "Chatterbox server returned HTTP ${response.code}${if (detail.isBlank()) "" else ": $detail"}"
                        Log.w(TAG, "Endpoint $url returned HTTP ${response.code}")
                        if (response.code == 401 || response.code == 403 || response.code == 429) {
                            return@withContext ChatterboxResult.Error(lastError, isBackendUnavailable = false)
                        }
                        continue
                    }

                    val body = response.body ?: run {
                        lastError = "Chatterbox returned an empty response."
                        continue
                    }

                    if (contentType.contains("application/json", ignoreCase = true)) {
                        val jsonText = body.string()
                        val json = JSONObject(jsonText)
                        val encoded = json.optString("audio")
                            .ifEmpty { json.optString("audio_base64") }
                            .ifEmpty { json.optString("audio_content") }
                            .substringAfter("base64,", json.optString("audio"))
                        if (encoded.isNotBlank()) {
                            val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                            if (bytes.isNotEmpty()) {
                                return@withContext ChatterboxResult.Success(bytes, json.optInt("sample_rate", 24000))
                            }
                        }
                        lastError = json.optString("detail")
                            .ifBlank { json.optString("message", "Chatterbox response did not contain audio data.") }
                    } else if (contentType.startsWith("audio/", ignoreCase = true) ||
                        contentType.contains("octet-stream", ignoreCase = true)) {
                        val bytes = body.bytes()
                        if (bytes.isNotEmpty()) return@withContext ChatterboxResult.Success(bytes, 24000)
                        lastError = "Chatterbox returned an empty audio file."
                    } else {
                        lastError = "Unexpected Chatterbox response type: ${contentType.ifBlank { "unknown" }}."
                    }
                }
            } catch (e: Exception) {
                lastError = "Chatterbox request failed: ${e.message ?: e.javaClass.simpleName}"
                Log.w(TAG, "Attempt to $url failed: ${e.javaClass.simpleName}")
            }
        }

        return@withContext ChatterboxResult.Error(lastError, isBackendUnavailable = !backendReached)
    }
}
