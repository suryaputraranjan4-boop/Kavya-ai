package com.example.agent

import android.content.Context
import com.example.ai.OfflinePrivacyGuard
import com.example.ai.StrictOfflineInterceptor
import com.example.kucp.security.SecretRedactor
import com.example.kucp.security.SsrfPolicyValidator
import com.example.kucp.security.UntrustedContentIsolator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ResearchSourceEvidence(
    val title: String,
    val url: String,
    val publisher: String,
    val excerpt: String,
    val isolatedExcerpt: String,
    val retrievedAtMs: Long = System.currentTimeMillis(),
    val query: String = "",
    val isVerifiedFetch: Boolean = true
)

enum class BrowserSessionStatus(val label: String) {
    ACTIVE("Active"),
    COMPLETED("Completed"),
    BLOCKED_OFFLINE("Blocked (Offline Mode)"),
    FAILED("Failed"),
    CANCELLED("Cancelled")
}

data class BrowserResearchSession(
    val sessionId: String,
    val taskId: String,
    val agentId: String,
    val objective: String,
    val status: BrowserSessionStatus = BrowserSessionStatus.ACTIVE,
    val urlsVisited: List<String> = emptyList(),
    val evidenceCount: Int = 0,
    val startedAtMs: Long = System.currentTimeMillis(),
    val endedAtMs: Long? = null,
    val lastNote: String = ""
)

/**
 * Shared Browser & Web Research Resource Manager for Kavya AI dynamic agents.
 * - Uses one shared OkHttpClient guarded by OfflinePrivacyGuard + StrictOfflineInterceptor.
 * - Tracks live task-scoped BrowserResearchSession entries surfaced in Agents Workspace.
 * - Performs real multi-source retrieval (Wikipedia, arXiv, DuckDuckGo Instant Answer, direct HTTPS pages).
 * - Never fabricates URLs, quotes, or statistics; isolates all untrusted web payloads via UntrustedContentIsolator.
 */
class AgentResearchAndBrowserManager private constructor(
    private val appContext: Context,
    private val httpClient: OkHttpClient,
    private val allowLocalUrlsForTesting: Boolean = false
) {
    companion object {
        @Volatile
        private var instance: AgentResearchAndBrowserManager? = null

        private const val USER_AGENT = "KavyaAI-ResearchAgent/1.0 (Android; Citation-Verified)"

        fun getInstance(context: Context): AgentResearchAndBrowserManager =
            instance ?: synchronized(this) {
                val app = context.applicationContext
                val client = OfflinePrivacyGuard.registerCloudClient(
                    OkHttpClient.Builder()
                        .connectTimeout(12, TimeUnit.SECONDS)
                        .readTimeout(18, TimeUnit.SECONDS)
                        .followRedirects(true)
                        .addInterceptor(StrictOfflineInterceptor(operationTag = "AgentResearchBrowser"))
                        .build()
                )
                instance ?: AgentResearchAndBrowserManager(app, client).also { instance = it }
            }

        internal fun createForTesting(
            context: Context,
            httpClient: OkHttpClient,
            allowLocalUrls: Boolean = true
        ): AgentResearchAndBrowserManager =
            AgentResearchAndBrowserManager(context.applicationContext, httpClient, allowLocalUrls)
    }

    private val _sessions = MutableStateFlow<List<BrowserResearchSession>>(emptyList())
    val sessions: StateFlow<List<BrowserResearchSession>> = _sessions.asStateFlow()

    fun openSession(taskId: String, agentId: String, objective: String): BrowserResearchSession {
        val session = BrowserResearchSession(
            sessionId = "brs_${UUID.randomUUID().toString().take(8)}",
            taskId = taskId,
            agentId = agentId,
            objective = objective,
            status = BrowserSessionStatus.ACTIVE,
            lastNote = "Session initialized"
        )
        _sessions.value = (listOf(session) + _sessions.value).take(60)
        return session
    }

    fun closeSession(
        sessionId: String,
        status: BrowserSessionStatus = BrowserSessionStatus.COMPLETED,
        note: String = ""
    ) {
        val now = System.currentTimeMillis()
        _sessions.value = _sessions.value.map { s ->
            if (s.sessionId == sessionId) {
                s.copy(
                    status = status,
                    endedAtMs = now,
                    lastNote = note.ifBlank { s.lastNote }
                )
            } else s
        }
    }

    fun cancelSession(sessionId: String) {
        closeSession(sessionId, BrowserSessionStatus.CANCELLED, "Cancelled by user or orchestrator")
    }

    fun clearFinishedSessions() {
        _sessions.value = _sessions.value.filter { it.status == BrowserSessionStatus.ACTIVE }
    }

    private fun recordVisit(sessionId: String, url: String, addedEvidence: Int, note: String) {
        _sessions.value = _sessions.value.map { s ->
            if (s.sessionId == sessionId) {
                s.copy(
                    urlsVisited = (s.urlsVisited + url).distinct().takeLast(30),
                    evidenceCount = s.evidenceCount + addedEvidence,
                    lastNote = note.take(180)
                )
            } else s
        }
    }

    /**
     * Runs real parallel multi-source research (Wikipedia + DuckDuckGo Instant Answer + optional arXiv).
     * Honors Strict Offline Mode honestly without fabricating results.
     */
    suspend fun runMultiSourceResearch(
        sessionId: String,
        query: String,
        includeAcademic: Boolean = true
    ): List<ResearchSourceEvidence> = coroutineScope {
        if (OfflinePrivacyGuard.isStrictOfflineMode(appContext)) {
            closeSession(sessionId, BrowserSessionStatus.BLOCKED_OFFLINE, "Strict Offline Mode enabled: external web research blocked.")
            return@coroutineScope emptyList()
        }

        val tasks = mutableListOf(
            async(Dispatchers.IO) { searchWikipedia(sessionId, query, limit = 3) },
            async(Dispatchers.IO) { searchDuckDuckGoInstant(sessionId, query) }
        )
        if (includeAcademic) {
            tasks += async(Dispatchers.IO) { searchArxiv(sessionId, query, limit = 2) }
        }

        val results = tasks.awaitAll().flatten()
            .distinctBy { it.url.trim().lowercase() }

        results
    }

    suspend fun searchWikipedia(
        sessionId: String,
        query: String,
        limit: Int = 3
    ): List<ResearchSourceEvidence> = withContext(Dispatchers.IO) {
        if (query.isBlank() || OfflinePrivacyGuard.isStrictOfflineMode(appContext)) return@withContext emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&srlimit=${limit.coerceIn(1, 5)}&format=json&utf8=1"
        val check = SsrfPolicyValidator.validateUrl(url, allowLocalDev = allowLocalUrlsForTesting)
        if (!check.allowed) return@withContext emptyList()

        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    recordVisit(sessionId, url, 0, "Wikipedia HTTP ${resp.code}")
                    return@withContext emptyList()
                }
                val raw = resp.body?.string().orEmpty()
                val arr = JSONObject(raw).optJSONObject("query")?.optJSONArray("search") ?: return@withContext emptyList()
                val out = mutableListOf<ResearchSourceEvidence>()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val title = item.optString("title").trim()
                    val snippet = stripHtml(item.optString("snippet"))
                    if (title.isBlank()) continue
                    val pageUrl = "https://en.wikipedia.org/wiki/${URLEncoder.encode(title.replace(' ', '_'), "UTF-8")}"
                    out += ResearchSourceEvidence(
                        title = title,
                        url = pageUrl,
                        publisher = "Wikipedia",
                        excerpt = snippet,
                        isolatedExcerpt = UntrustedContentIsolator.isolate("Wikipedia:$title", snippet, 1500),
                        query = query
                    )
                }
                recordVisit(sessionId, url, out.size, "Wikipedia returned ${out.size} citations")
                out
            }
        } catch (e: Exception) {
            recordVisit(sessionId, url, 0, "Wikipedia failed: ${SecretRedactor.redact(e.message)}")
            emptyList()
        }
    }

    suspend fun searchArxiv(
        sessionId: String,
        query: String,
        limit: Int = 2
    ): List<ResearchSourceEvidence> = withContext(Dispatchers.IO) {
        if (query.isBlank() || OfflinePrivacyGuard.isStrictOfflineMode(appContext)) return@withContext emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "https://export.arxiv.org/api/query?search_query=all:$encoded&start=0&max_results=${limit.coerceIn(1, 5)}"
        val check = SsrfPolicyValidator.validateUrl(url, allowLocalDev = allowLocalUrlsForTesting)
        if (!check.allowed) return@withContext emptyList()

        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/atom+xml")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    recordVisit(sessionId, url, 0, "arXiv HTTP ${resp.code}")
                    return@withContext emptyList()
                }
                val xml = resp.body?.string().orEmpty()
                val entries = Regex("(?s)<entry>(.*?)</entry>").findAll(xml).toList()
                val out = mutableListOf<ResearchSourceEvidence>()
                for (match in entries) {
                    val block = match.groupValues[1]
                    val title = extractXmlTag(block, "title").replace(Regex("\\s+"), " ").trim()
                    val idUrl = extractXmlTag(block, "id").trim()
                    val summary = extractXmlTag(block, "summary").replace(Regex("\\s+"), " ").trim().take(650)
                    if (title.isBlank() || idUrl.isBlank()) continue
                    out += ResearchSourceEvidence(
                        title = title,
                        url = idUrl,
                        publisher = "arXiv",
                        excerpt = summary,
                        isolatedExcerpt = UntrustedContentIsolator.isolate("arXiv:$title", summary, 1500),
                        query = query
                    )
                }
                recordVisit(sessionId, url, out.size, "arXiv returned ${out.size} papers")
                out
            }
        } catch (e: Exception) {
            recordVisit(sessionId, url, 0, "arXiv failed: ${SecretRedactor.redact(e.message)}")
            emptyList()
        }
    }

    suspend fun searchDuckDuckGoInstant(
        sessionId: String,
        query: String
    ): List<ResearchSourceEvidence> = withContext(Dispatchers.IO) {
        if (query.isBlank() || OfflinePrivacyGuard.isStrictOfflineMode(appContext)) return@withContext emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
        val check = SsrfPolicyValidator.validateUrl(url, allowLocalDev = allowLocalUrlsForTesting)
        if (!check.allowed) return@withContext emptyList()

        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    recordVisit(sessionId, url, 0, "DuckDuckGo HTTP ${resp.code}")
                    return@withContext emptyList()
                }
                val raw = resp.body?.string().orEmpty()
                val json = JSONObject(raw)
                val out = mutableListOf<ResearchSourceEvidence>()

                val abstractText = json.optString("AbstractText").trim()
                val abstractUrl = json.optString("AbstractURL").trim()
                val heading = json.optString("Heading").trim().ifBlank { query }
                val source = json.optString("AbstractSource").trim().ifBlank { "DuckDuckGo Instant Answer" }
                if (abstractText.isNotBlank() && abstractUrl.isNotBlank()) {
                    out += ResearchSourceEvidence(
                        title = heading,
                        url = abstractUrl,
                        publisher = source,
                        excerpt = abstractText.take(650),
                        isolatedExcerpt = UntrustedContentIsolator.isolate("$source:$heading", abstractText, 1500),
                        query = query
                    )
                }

                val related = json.optJSONArray("RelatedTopics")
                if (related != null) {
                    for (i in 0 until minOf(related.length(), 3)) {
                        val item = related.optJSONObject(i) ?: continue
                        val text = item.optString("Text").trim()
                        val firstUrl = item.optString("FirstURL").trim()
                        if (text.isNotBlank() && firstUrl.isNotBlank()) {
                            out += ResearchSourceEvidence(
                                title = text.substringBefore(" - ").take(90),
                                url = firstUrl,
                                publisher = "DuckDuckGo",
                                excerpt = text.take(400),
                                isolatedExcerpt = UntrustedContentIsolator.isolate("DuckDuckGo", text, 1000),
                                query = query
                            )
                        }
                    }
                }
                recordVisit(sessionId, url, out.size, "DuckDuckGo returned ${out.size} citations")
                out
            }
        } catch (e: Exception) {
            recordVisit(sessionId, url, 0, "DuckDuckGo failed: ${SecretRedactor.redact(e.message)}")
            emptyList()
        }
    }

    /**
     * Directly reads a verified HTTPS web page with SSRF validation, CAPTCHA/403 honesty,
     * HTML stripping, and UntrustedContentIsolator wrapping.
     */
    suspend fun fetchWebPageExcerpt(
        sessionId: String,
        rawUrl: String,
        maxChars: Int = 2500
    ): Result<ResearchSourceEvidence> = withContext(Dispatchers.IO) {
        if (OfflinePrivacyGuard.isStrictOfflineMode(appContext)) {
            return@withContext Result.failure(IllegalStateException("Strict Offline Mode is enabled; external web fetch blocked."))
        }
        val check = SsrfPolicyValidator.validateUrl(rawUrl, allowLocalDev = allowLocalUrlsForTesting)
        if (!check.allowed) {
            return@withContext Result.failure(SecurityException(check.reason))
        }
        try {
            val req = Request.Builder()
                .url(rawUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,text/plain,application/json")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 403 || resp.code == 429) {
                    val msg = "Site returned HTTP ${resp.code} (rate-limited or bot-protected)."
                    recordVisit(sessionId, rawUrl, 0, msg)
                    return@withContext Result.failure(IllegalStateException(msg))
                }
                if (!resp.isSuccessful) {
                    val msg = "Web page fetch failed with HTTP ${resp.code}"
                    recordVisit(sessionId, rawUrl, 0, msg)
                    return@withContext Result.failure(IllegalStateException(msg))
                }
                val body = resp.body?.string().orEmpty()
                if (body.contains("cf-challenge", ignoreCase = true) || body.contains("captcha", ignoreCase = true) && body.length < 4000) {
                    val msg = "Page requires interactive CAPTCHA / human verification."
                    recordVisit(sessionId, rawUrl, 0, msg)
                    return@withContext Result.failure(IllegalStateException(msg))
                }
                val title = extractXmlTag(body, "title").ifBlank { rawUrl }.take(140)
                val cleanText = stripHtml(body).take(maxChars)
                val host = try { java.net.URI(rawUrl).host.orEmpty() } catch (_: Exception) { "web" }
                val evidence = ResearchSourceEvidence(
                    title = title,
                    url = rawUrl,
                    publisher = host,
                    excerpt = cleanText.take(650),
                    isolatedExcerpt = UntrustedContentIsolator.isolate("Web:$host", cleanText, maxChars),
                    query = rawUrl
                )
                recordVisit(sessionId, rawUrl, 1, "Fetched '$title' (${cleanText.length} chars)")
                Result.success(evidence)
            }
        } catch (e: Exception) {
            val msg = SecretRedactor.redact(e.message)
            recordVisit(sessionId, rawUrl, 0, "Fetch error: $msg")
            Result.failure(e)
        }
    }

    private fun extractXmlTag(source: String, tag: String): String {
        val m = Regex("(?is)<$tag[^>]*>(.*?)</$tag>").find(source)
        return m?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun stripHtml(raw: String): String {
        return raw
            .replace(Regex("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?is)<[^>]+>"), " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
