package com.example.evolution

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

object GitHubApiService {
    private const val TAG = "GitHubApiService"
    private val client = OkHttpClient()

    suspend fun searchRepositories(query: String, perPage: Int = 10): List<EvolutionProject> = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/search/repositories?q=${java.net.URLEncoder.encode(query, "UTF-8")}&per_page=$perPage&sort=stars&order=desc"
        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/vnd.github.v3+json")
            .addHeader("User-Agent", "Kavya-Evolution-Engine")
            .build()

        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            response.close()

            if (response.isSuccessful && !body.isNullOrBlank()) {
                val json = JSONObject(body)
                val items = json.optJSONArray("items") ?: JSONArray()
                val projects = mutableListOf<EvolutionProject>()

                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val ownerObj = item.optJSONObject("owner")
                    val licenseObj = item.optJSONObject("license")
                    val licenseStr = licenseObj?.optString("spdx_id", "MIT")?.let { if (it.isBlank()) "MIT" else it } ?: "MIT"

                    projects.add(
                        EvolutionProject(
                            id = item.optLong("id", i.toLong()).toString(),
                            name = item.optString("name", "unknown"),
                            owner = ownerObj?.optString("login", "unknown") ?: "unknown",
                            url = item.optString("html_url", ""),
                            license = licenseStr,
                            language = item.optString("language", "Kotlin"),
                            framework = item.optString("description", "Utility").take(60),
                            stars = item.optInt("stargazers_count", 100),
                            forks = item.optInt("forks_count", 10),
                            lastUpdate = item.optString("updated_at", "Recently"),
                            dependencies = listOf("kotlinx-serialization", "androidx-core"),
                            documentationQuality = "High",
                            architecture = "Modular",
                            requiredPermissions = emptyList(),
                            potentialUsefulness = item.optString("description", "Improves assistant intelligence and capabilities."),
                            maintenanceStatus = "Active"
                        )
                    )
                }
                return@withContext projects
            } else {
                Log.w(TAG, "GitHub search failed with code ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during GitHub search: ${e.message}", e)
        }
        emptyList()
    }
}
