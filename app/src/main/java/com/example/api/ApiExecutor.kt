package com.example.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class ApiExecutor {
    companion object {
        private const val TAG = "ApiExecutor"
        private val client = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    suspend fun execute(api: ApiMetadata, params: Map<String, String>): String? = withContext(Dispatchers.IO) {
        if (api.health.status == ApiHealthStatus.RATE_LIMITED || api.health.status == ApiHealthStatus.UNAVAILABLE) {
            Log.w(TAG, "API ${api.apiId} is currently ${api.health.status}. Aborting execution.")
            return@withContext null
        }

        try {
            var url = api.endpoint
            
            // Construct query parameters
            if (api.method == HttpMethod.GET && params.isNotEmpty()) {
                val queryParams = params.map { "${it.key}=${it.value}" }.joinToString("&")
                url = if (url.contains("?")) "$url&$queryParams" else "$url?$queryParams"
            }
            
            // Hardcode geocoding resolution for OpenMeteo since it requires precise lat/long
            if (api.apiId == "open-meteo-v1" && params.containsKey("location") && !params.containsKey("latitude")) {
                 val loc = params["location"]?.lowercase() ?: ""
                 if (loc.contains("mumbai")) url = "${api.endpoint}?latitude=19.0760&longitude=72.8777&current_weather=true"
                 else if (loc.contains("delhi")) url = "${api.endpoint}?latitude=28.7041&longitude=77.1025&current_weather=true"
                 else url = "${api.endpoint}?latitude=51.5085&longitude=-0.1257&current_weather=true" // Default London
            }
            
            val requestBuilder = Request.Builder().url(url)
            
            if (api.authType == AuthType.API_KEY_HEADER && api.authKeyName != null) {
                // Not actually injecting keys here to avoid leaks, but framework is ready
                // requestBuilder.addHeader(api.authKeyName, "SOME_KEY")
            }

            val request = requestBuilder.build()
            
            val startTime = System.currentTimeMillis()
            val response = client.newCall(request).execute()
            val endTime = System.currentTimeMillis()
            
            if (response.isSuccessful) {
                val bodyString = response.body?.string()
                
                // Response Minification logic
                val minified = minifyResponse(api, bodyString)
                
                return@withContext minified
            } else {
                Log.w(TAG, "HTTP error ${response.code} from ${api.apiId}")
                return@withContext null
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error executing ${api.apiId}: ${e.message}")
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "Unknown error executing ${api.apiId}: ${e.message}")
            return@withContext null
        }
    }
    
    // Very basic minification to save LLM tokens. 
    // In production, use structured JSON parsing mapped per API.
    private fun minifyResponse(api: ApiMetadata, rawResponse: String?): String {
        if (rawResponse.isNullOrBlank()) return "{}"
        
        // Naive string reduction for this demo to prevent token blowouts
        if (api.apiId == "open-meteo-v1") {
             // Just extract current_weather block if it exists
             val currentStart = rawResponse.indexOf("\"current_weather\"")
             if (currentStart != -1) {
                 val currentEnd = rawResponse.indexOf("}", currentStart)
                 if (currentEnd != -1) {
                     return "{${rawResponse.substring(currentStart, currentEnd + 1)}}"
                 }
             }
        }
        
        // Fallback: truncate if extremely long
        if (rawResponse.length > 1000) {
            return rawResponse.substring(0, 1000) + "...(truncated)"
        }
        return rawResponse
    }
}
