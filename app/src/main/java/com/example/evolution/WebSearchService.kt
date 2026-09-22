package com.example.evolution

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

object WebSearchService {
    private const val TAG = "WebSearchService"
    private val client = OkHttpClient()

    suspend fun searchWeb(query: String): String = withContext(Dispatchers.IO) {
        // Using DuckDuckGo instant answer / search API or fallback public search endpoint
        val url = "https://api.duckduckgo.com/?q=${java.net.URLEncoder.encode(query, "UTF-8")}&format=json&no_html=1"
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "Kavya-AI-Assistant")
            .build()

        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            response.close()

            if (response.isSuccessful && !body.isNullOrBlank()) {
                val json = JSONObject(body)
                val abstractText = json.optString("AbstractText", "")
                val relatedTopics = json.optJSONArray("RelatedTopics")

                val sb = StringBuilder()
                if (abstractText.isNotBlank()) {
                    sb.append("Summary: $abstractText\n")
                }
                if (relatedTopics != null) {
                    for (i in 0 until minOf(relatedTopics.length(), 3)) {
                        val topic = relatedTopics.optJSONObject(i)
                        val text = topic?.optString("Text", "")
                        if (!text.isNullOrBlank()) {
                            sb.append("• $text\n")
                        }
                    }
                }
                val result = sb.toString().trim()
                if (result.isNotBlank()) {
                    return@withContext result
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Web search error: ${e.message}", e)
        }
        "Search could not be completed or no live results found for '$query'."
    }
}
