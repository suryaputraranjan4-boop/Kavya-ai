package com.example.api

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiExecutor {
    companion object {
        private const val TAG = "ApiExecutor"
        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    suspend fun execute(api: ApiMetadata, params: Map<String, String>): String? = withContext(Dispatchers.IO) {
        if (api.health.status == ApiHealthStatus.RATE_LIMITED || api.health.status == ApiHealthStatus.UNAVAILABLE) {
            Log.w(TAG, "API ${api.apiId} is currently ${api.health.status}. Aborting execution.")
            return@withContext null
        }

        try {
            val requestParams = params.toMutableMap()
            val credentialKeys = setOf("api_key", "apiKey", "token", "access_token")
            val credential = requestParams.entries.firstOrNull { it.key in credentialKeys }?.value
                ?: api.authKeyName?.let { requestParams[it] }

            credentialKeys.forEach(requestParams::remove)
            api.authKeyName?.let(requestParams::remove)

            if (api.authType != AuthType.NONE && credential.isNullOrBlank()) {
                Log.w(TAG, "API ${api.apiId} requires credentials, but no credential was supplied.")
                return@withContext null
            }

            // Open-Meteo needs coordinates. Resolve a supplied place name through its free geocoding API;
            // never silently substitute a different city or fixed coordinates.
            if (api.apiId == "open-meteo-v1" &&
                (!requestParams.containsKey("latitude") || !requestParams.containsKey("longitude"))
            ) {
                val location = requestParams.remove("location")?.trim().orEmpty()
                if (location.isBlank()) {
                    Log.w(TAG, "Open-Meteo requires latitude/longitude or a location name.")
                    return@withContext null
                }
                val coordinates = geocode(location) ?: run {
                    Log.w(TAG, "Could not resolve supplied weather location.")
                    return@withContext null
                }
                requestParams["latitude"] = coordinates.first.toString()
                requestParams["longitude"] = coordinates.second.toString()
                requestParams.putIfAbsent("current", "temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code,wind_speed_10m")
                requestParams.putIfAbsent("timezone", "auto")
            }

            if (api.apiId == "open-meteo-v1") {
                requestParams.remove("current_weather")
                requestParams.putIfAbsent(
                    "current",
                    "temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code,wind_speed_10m"
                )
                requestParams.putIfAbsent("timezone", "auto")
            }

            val missingRequired = api.requiredParams.filter { parameter ->
                requestParams[parameter.name].isNullOrBlank()
            }
            if (missingRequired.isNotEmpty()) {
                Log.w(TAG, "Missing required parameters for ${api.apiId}: ${missingRequired.joinToString { it.name }}")
                return@withContext null
            }

            // Hugging Face inference expects raw bytes, not a JSON string. Accept base64
            // payloads (including data URIs); a local URI needs a SAF/content resolver and
            // is rejected rather than being sent as meaningless text.
            val binaryRequestBody = if (api.apiId == "hf-whisper" || api.apiId == "hf-vit") {
                val input = requestParams.remove("inputs").orEmpty()
                if (input.isBlank() || input.startsWith("content://", true) || input.startsWith("file://", true)) {
                    Log.w(TAG, "${api.apiId} requires base64 audio/image bytes; local URI strings are not valid request bodies.")
                    return@withContext null
                }
                val dataUriMime = input.takeIf { it.startsWith("data:", true) }
                    ?.substringAfter("data:", "")
                    ?.substringBefore(";")
                    ?.takeIf { it.contains("/") }
                val encoded = if (input.startsWith("data:", true)) input.substringAfter("base64,", "") else input
                val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
                if (bytes == null || bytes.isEmpty()) {
                    Log.w(TAG, "${api.apiId} received an invalid or empty base64 payload.")
                    return@withContext null
                }
                val defaultMime = if (api.apiId == "hf-whisper") "audio/wav" else "image/jpeg"
                val contentType = dataUriMime ?: requestParams.remove("content_type") ?: defaultMime
                bytes.toRequestBody(contentType.toMediaType())
            } else {
                null
            }

            val baseUrl = api.endpoint.toHttpUrlOrNull()
                ?: run {
                    Log.e(TAG, "Invalid endpoint URL for ${api.apiId}.")
                    return@withContext null
                }
            val urlBuilder = baseUrl.newBuilder()

            if (api.method == HttpMethod.GET) {
                requestParams.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }
            } else if (api.authType == AuthType.API_KEY_QUERY) {
                // API-key query auth is added below, independently of the request body.
            }

            if (api.authType == AuthType.API_KEY_QUERY) {
                urlBuilder.addQueryParameter(api.authKeyName?.takeIf { it.isNotBlank() } ?: "api_key", credential)
            }

            val requestBuilder = Request.Builder().url(urlBuilder.build())
                .header("Accept", "application/json")

            when (api.authType) {
                AuthType.NONE, AuthType.API_KEY_QUERY -> Unit
                AuthType.API_KEY_HEADER -> requestBuilder.header(
                    api.authKeyName?.takeIf { it.isNotBlank() } ?: "X-API-Key",
                    credential!!
                )
                AuthType.BEARER_TOKEN, AuthType.OAUTH -> requestBuilder.header("Authorization", "Bearer $credential")
            }

            if (api.apiId == "github-api-v3") {
                requestBuilder.header("User-Agent", "Kavya-Android")
                requestBuilder.header("Accept", "application/vnd.github+json")
            }

            val requestBody = binaryRequestBody ?: when (api.method) {
                HttpMethod.GET -> null
                HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE -> {
                    if (requestParams.isEmpty() && api.method == HttpMethod.DELETE) {
                        null
                    } else {
                        JSONObject(requestParams as Map<*, *>).toString()
                            .toRequestBody("application/json; charset=utf-8".toMediaType())
                    }
                }
            }

            val request = requestBuilder.method(api.method.name, requestBody).build()
            client.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} from ${api.apiId}; response body omitted.")
                    return@withContext null
                }
                minifyResponse(api, responseText)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error executing ${api.apiId} (${e.javaClass.simpleName}).")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error executing ${api.apiId} (${e.javaClass.simpleName}).")
            null
        }
    }

    private fun geocode(location: String): Pair<Double, Double>? {
        val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("name", location)
            ?.addQueryParameter("count", "1")
            ?.addQueryParameter("language", "en")
            ?.addQueryParameter("format", "json")
            ?.build()
            ?: return null

        return client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string().orEmpty()
            val results = JSONObject(body).optJSONArray("results") ?: return@use null
            if (results.length() == 0) return@use null
            val first = results.optJSONObject(0) ?: return@use null
            if (!first.has("latitude") || !first.has("longitude")) return@use null
            first.getDouble("latitude") to first.getDouble("longitude")
        }
    }

    private fun minifyResponse(api: ApiMetadata, rawResponse: String): String {
        if (rawResponse.isBlank()) return "{}"

        if (api.apiId == "open-meteo-v1") {
            val json = runCatching { JSONObject(rawResponse) }.getOrNull()
            val current = json?.optJSONObject("current")
            if (current != null) {
                return JSONObject().put("current", current).toString()
            }
        }

        return if (rawResponse.length > 1000) {
            rawResponse.substring(0, 1000) + "...(truncated)"
        } else {
            rawResponse
        }
    }
}
