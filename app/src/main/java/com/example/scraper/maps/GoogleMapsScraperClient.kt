package com.example.scraper.maps

import android.content.Context
import android.util.Log
import com.example.utils.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

data class ScraperHealthStatus(
    val isHealthy: Boolean,
    val latencyMs: Long = 0L,
    val serviceUrl: String,
    val message: String
)

sealed class ScrapeResult {
    data class Success(val businesses: List<ScrapedBusiness>, val sourceUrl: String, val latencyMs: Long) : ScrapeResult()
    data class ServiceUnavailable(val serviceUrl: String, val error: String) : ScrapeResult()
    data class EmptyResult(val query: String) : ScrapeResult()
    data class Error(val message: String) : ScrapeResult()
}

/**
 * Client for Mahanaicoach/google-maps-scraper-kit local Docker service.
 * Supports configurable service URL, health checks, query execution, proxying, and normalized parsing.
 */
class GoogleMapsScraperClient(private val context: Context) {

    companion object {
        private const val TAG = "MapsScraperClient"
        const val DEFAULT_SCRAPER_URL = "http://localhost:8080"
    }

    private fun getClient(connectTimeoutSec: Long = 10, readTimeoutSec: Long = 45): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        // Configure optional proxy if set in preferences
        val proxyHost = AppPreferences.getMapsScraperProxyHost(context)
        val proxyPort = AppPreferences.getMapsScraperProxyPort(context)
        if (proxyHost.isNotBlank() && proxyPort > 0) {
            try {
                val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort))
                builder.proxy(proxy)
            } catch (e: Exception) {
                Log.w(TAG, "Invalid proxy config: ${e.message}")
            }
        }

        return builder.build()
    }

    fun getServiceUrl(): String {
        val custom = AppPreferences.getMapsScraperUrl(context)
        return if (custom.isNotBlank()) custom.trim().removeSuffix("/") else DEFAULT_SCRAPER_URL
    }

    /**
     * Checks if the local Google Maps Scraper service is running and responsive.
     */
    suspend fun checkHealth(): ScraperHealthStatus = withContext(Dispatchers.IO) {
        val serviceUrl = getServiceUrl()
        val startTime = System.currentTimeMillis()

        val endpointsToTry = listOf("$serviceUrl/health", "$serviceUrl/status", serviceUrl)
        val client = getClient(connectTimeoutSec = 3, readTimeoutSec = 3)

        for (endpoint in endpointsToTry) {
            try {
                val request = Request.Builder()
                    .url(endpoint)
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    val latency = System.currentTimeMillis() - startTime
                    if (response.isSuccessful || response.code == 200 || response.code == 404) {
                        return@withContext ScraperHealthStatus(
                            isHealthy = true,
                            latencyMs = latency,
                            serviceUrl = serviceUrl,
                            message = "Connected to Google Maps Scraper service (${latency}ms)"
                        )
                    }
                }
            } catch (ignored: Exception) {
                // Try next endpoint candidate
            }
        }

        return@withContext ScraperHealthStatus(
            isHealthy = false,
            serviceUrl = serviceUrl,
            message = "Google Maps search service is currently unavailable. Ensure the local scraper container is running at $serviceUrl"
        )
    }

    /**
     * Executes Google Maps business search using the local scraper.
     */
    suspend fun searchBusinesses(
        query: GoogleMapsScraperQuery
    ): ScrapeResult = withContext(Dispatchers.IO) {
        val serviceUrl = getServiceUrl()
        val startTime = System.currentTimeMillis()
        val searchTerm = query.toSearchTerm()

        Log.d(TAG, "Executing Google Maps search: '$searchTerm' (limit: ${query.limit}) at $serviceUrl")

        val client = getClient(connectTimeoutSec = 10, readTimeoutSec = 45)
        val payload = JSONObject().apply {
            put("query", searchTerm)
            put("limit", query.limit)
            put("depth", query.limit)
            put("extract_email", query.extractEmail)
            put("extract_social", query.extractSocial)
            val fieldsArray = JSONArray()
            query.requestedFields.forEach { fieldsArray.put(it) }
            put("fields", fieldsArray)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = payload.toString().toRequestBody(mediaType)

        // Try standard scrape endpoints
        val candidateEndpoints = listOf(
            "$serviceUrl/api/scrape",
            "$serviceUrl/scrape",
            "$serviceUrl/search"
        )

        for (endpoint in candidateEndpoints) {
            try {
                val request = Request.Builder()
                    .url(endpoint)
                    .post(body)
                    .addHeader("Accept", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string().orEmpty()
                        val businesses = parseResultsJson(responseBody)
                        val latency = System.currentTimeMillis() - startTime
                        return@withContext if (businesses.isNotEmpty()) {
                            ScrapeResult.Success(businesses, serviceUrl, latency)
                        } else {
                            ScrapeResult.EmptyResult(searchTerm)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Attempt failed for $endpoint: ${e.message}")
            }
        }

        // Also try GET /search?query=...
        try {
            val encoded = java.net.URLEncoder.encode(searchTerm, "UTF-8")
            val getUrl = "$serviceUrl/search?query=$encoded&limit=${query.limit}"
            val getRequest = Request.Builder()
                .url(getUrl)
                .get()
                .build()

            client.newCall(getRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string().orEmpty()
                    val businesses = parseResultsJson(responseBody)
                    val latency = System.currentTimeMillis() - startTime
                    if (businesses.isNotEmpty()) {
                        return@withContext ScrapeResult.Success(businesses, serviceUrl, latency)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "GET search failed: ${e.message}")
        }

        val latency = System.currentTimeMillis() - startTime
        return@withContext ScrapeResult.ServiceUnavailable(
            serviceUrl = serviceUrl,
            error = "Google Maps search service is currently unavailable. Ensure the local scraper container is running at $serviceUrl"
        )
    }

    private fun parseResultsJson(jsonString: String): List<ScrapedBusiness> {
        val list = mutableListOf<ScrapedBusiness>()
        val trimmed = jsonString.trim()
        try {
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i)
                    if (item != null) {
                        list.add(ScrapedBusiness.fromFlexibleJson(item))
                    }
                }
            } else if (trimmed.startsWith("{")) {
                val root = JSONObject(trimmed)
                val candidates = listOf("results", "data", "businesses", "places", "items")
                for (key in candidates) {
                    val arr = root.optJSONArray(key)
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i)
                            if (item != null) {
                                list.add(ScrapedBusiness.fromFlexibleJson(item))
                            }
                        }
                        if (list.isNotEmpty()) break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing scraped JSON: ${e.message}", e)
        }
        return list
    }
}
