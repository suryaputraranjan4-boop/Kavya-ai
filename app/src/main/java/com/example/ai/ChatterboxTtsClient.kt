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
            if (!refBase64.isNullOrBlank()) {
                put("reference_audio", refBase64)
                put("audio_prompt", refBase64)
            }
        }

        val endpointsToTry = listOf(
            "$baseUrl/v1/audio/speech",
            "$baseUrl/synthesize",
            "$baseUrl/generate",
            "$baseUrl/tts"
        )

        var lastError: String = "Failed to reach Chatterbox backend at $baseUrl."

        for (url in endpointsToTry) {
            try {
                val reqBuilder = Request.Builder()
                    .url(url)
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))

                if (token.isNotBlank()) {
                    reqBuilder.addHeader("Authorization", "Bearer $token")
                }

                httpClient.newCall(reqBuilder.build()).execute().use { response ->
                    val code = response.code
                    if (response.isSuccessful) {
                        val body = response.body ?: return@use
                        val contentType = response.header("Content-Type") ?: ""
                        
                        // If JSON response containing base64 audio or direct WAV binary
                        if (contentType.contains("application/json", ignoreCase = true)) {
                            val jsonStr = body.string()
                            val json = JSONObject(jsonStr)
                            val b64Audio = json.optString("audio")
                                .ifEmpty { json.optString("audio_base64") }
                                .ifEmpty { json.optString("audio_content") }
                            if (b64Audio.isNotBlank()) {
                                val bytes = android.util.Base64.decode(b64Audio, android.util.Base64.DEFAULT)
                                val sampleRate = json.optInt("sample_rate", 24000)
                                return@withContext ChatterboxResult.Success(bytes, sampleRate)
                            }
                        } else {
                            // Direct binary audio (e.g., audio/wav, audio/mpeg, audio/x-wav)
                            val bytes = body.bytes()
                            if (bytes.isNotEmpty()) {
                                return@withContext ChatterboxResult.Success(bytes, 24000)
                            }
                        }
                    } else {
                        val errorBody = response.body?.string() ?: ""
                        lastError = "Chatterbox server returned $code: $errorBody"
                        Log.w(TAG, "Endpoint $url returned $code: $errorBody")
                    }
                }
            } catch (e: Exception) {
                lastError = "Connection error to $url: ${e.message}"
                Log.w(TAG, "Attempt to $url failed: ${e.message}")
            }
        }

        return@withContext ChatterboxResult.Error(lastError, isBackendUnavailable = true)
    }
}
