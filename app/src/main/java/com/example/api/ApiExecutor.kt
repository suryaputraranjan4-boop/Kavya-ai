package com.example.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
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
                    Log.w(TAG, "Could not resolve weather location '$location'.")
                    return@withContext null
                }
                requestParams["latitude"] = coordinates.first.toString()
                requestParams["longitude"] = coordinates.second.toString()
                requestParams.putIfAbsent("current_weather", "true")
            }

            val missingRequired = api.requiredParams.filter { parameter ->
                requestParams[parameter.name].isNullOrBlank()
            }
            if (missingRequired.isNotEmpty()) {
                Log.w(TAG, "Missing required parameters for ${api.apiId}: ${missingRequired.joinToString { it.name }}")
                return@withContext null
            }

            // The current public API registry models parameters as strings. It cannot safely
            // turn local audio/image URIs into the binary request bodies required by these models.
            if ((api.apiId == "hf-whisper" || api.apiId == "hf-vit") &&
                requestParams["inputs"].orEmpty().let {
                    it.startsWith("content://", true) || it.startsWith("file://", true)
                }
            ) {
                Log.w(TAG, "${api.apiId} requires a binary audio/image upload; a URI string is not a valid payload.")
                return@withContext null
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

            val requestBody = when (api.method) {
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
                    Log.w(TAG, "HTTP ${response.code} from ${api.apiId}: ${responseText.take(300)}")
                    return@withContext null
                }
                minifyResponse(api, responseText)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error executing ${api.apiId}: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error executing ${api.apiId}: ${e.message}", e)
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
            val current = json?.optJSONObject("current_weather")
            if (current != null) {
                return JSONObject().put("current_weather", current).toString()
            }
        }

        return if (rawResponse.length > 1000) {
            rawResponse.substring(0, 1000) + "...(truncated)"
        } else {
            rawResponse
        }
    }
}
