package com.example.ai

import android.content.Context
import android.util.Log
import com.example.utils.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thrown whenever an outbound HTTP, WebSocket, or model-download request is attempted while
 * Strict Offline Mode is enabled.
 */
class StrictOfflineBlockedException(
    val operation: String,
    val host: String = "",
    message: String = "Strict Offline Mode blocked outbound request [$operation${if (host.isNotBlank()) " -> $host" else ""}]."
) : IOException(message)

data class OfflinePolicyEvent(
    val operation: String,
    val host: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val reason: String
)

/**
 * Single authoritative privacy guard enforcing Strict Offline Mode across ALL OkHttp clients,
 * HttpURLConnection model downloaders, WebSockets (Gemini Live), and cloud AI/TTS/ASR providers.
 */
object OfflinePrivacyGuard {
    private const val TAG = "OfflinePrivacyGuard"

    @Volatile
    private var appContextRef: Context? = null

    private val registeredClients = CopyOnWriteArrayList<WeakReference<OkHttpClient>>()
    private val activeWebSockets = CopyOnWriteArrayList<WeakReference<WebSocket>>()

    private val _blockedRequestCount = AtomicInteger(0)
    val blockedRequestCount: Int
        get() = _blockedRequestCount.get()

    private val _lastBlockedEvent = MutableStateFlow<OfflinePolicyEvent?>(null)
    val lastBlockedEvent: StateFlow<OfflinePolicyEvent?> = _lastBlockedEvent.asStateFlow()

    private val _lastEngineAttribution = MutableStateFlow("UNASSIGNED")
    val lastEngineAttribution: StateFlow<String> = _lastEngineAttribution.asStateFlow()

    fun bindApplicationContext(context: Context?) {
        if (context != null) {
            appContextRef = context.applicationContext ?: context
        }
    }

    fun isStrictOfflineMode(context: Context? = null): Boolean {
        val ctx = context ?: appContextRef ?: return false
        appContextRef = ctx.applicationContext ?: ctx
        return AppPreferences.isOfflineFallbackEnabled(ctx) ||
            AppPreferences.getAiProvider(ctx).equals("GEMMA_OFFLINE", ignoreCase = true)
    }

    fun recordEngineAttribution(engineLabel: String) {
        _lastEngineAttribution.value = engineLabel.trim().ifBlank { "UNKNOWN" }
    }

    fun registerCloudClient(client: OkHttpClient): OkHttpClient {
        if (registeredClients.none { it.get() === client }) {
            registeredClients.add(WeakReference(client))
        }
        return client
    }

    fun registerActiveWebSocket(webSocket: WebSocket) {
        activeWebSockets.removeAll { it.get() == null || it.get() === webSocket }
        activeWebSockets.add(WeakReference(webSocket))
    }

    fun unregisterWebSocket(webSocket: WebSocket?) {
        if (webSocket == null) return
        activeWebSockets.removeAll { it.get() == null || it.get() === webSocket }
    }

    /**
     * Immediately cancels all in-flight cloud HTTP calls and closes active cloud WebSockets
     * (such as Gemini Live) when the user activates Strict Offline Mode.
     */
    fun cancelAllActiveCloudRequests(reason: String = "Strict Offline Mode enabled") {
        var cancelledClients = 0
        val staleClients = mutableListOf<WeakReference<OkHttpClient>>()
        for (ref in registeredClients) {
            val client = ref.get()
            if (client == null) {
                staleClients.add(ref)
            } else {
                runCatching {
                    client.dispatcher.cancelAll()
                    cancelledClients++
                }
            }
        }
        if (staleClients.isNotEmpty()) {
            registeredClients.removeAll(staleClients.toSet())
        }

        var closedSockets = 0
        val staleSockets = mutableListOf<WeakReference<WebSocket>>()
        for (ref in activeWebSockets) {
            val ws = ref.get()
            if (ws == null) {
                staleSockets.add(ref)
            } else {
                runCatching {
                    ws.close(1000, reason.take(60))
                    ws.cancel()
                    closedSockets++
                }
            }
        }
        activeWebSockets.clear()
        Log.i(TAG, "Cancelled in-flight cloud traffic ($reason): clients=$cancelledClients, sockets=$closedSockets")
    }

    fun checkOutboundAllowed(
        context: Context? = null,
        operation: String,
        host: String = ""
    ) {
        if (isStrictOfflineMode(context)) {
            val redactedHost = redactForLog(host)
            _blockedRequestCount.incrementAndGet()
            val event = OfflinePolicyEvent(
                operation = operation,
                host = redactedHost,
                reason = "Strict Offline Mode is active; outbound traffic is forbidden."
            )
            _lastBlockedEvent.value = event
            Log.w(TAG, "BLOCKED outbound [$operation] host=$redactedHost (Strict Offline Mode)")
            throw StrictOfflineBlockedException(operation = operation, host = redactedHost)
        }
    }

    fun checkModelDownloadAllowed(
        context: Context? = null,
        modelId: String,
        url: String = ""
    ): Result<Unit> {
        return try {
            val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
            checkOutboundAllowed(
                context = context,
                operation = "model_download:$modelId",
                host = host
            )
            Result.success(Unit)
        } catch (blocked: StrictOfflineBlockedException) {
            Result.failure(blocked)
        }
    }

    fun resetMetricsForTest() {
        _blockedRequestCount.set(0)
        _lastBlockedEvent.value = null
    }

    fun redactForLog(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw
            .replace(Regex("(?i)(api[_-]?key|key|token|authorization|bearer)=([^&\\s]+)"), "$1=[REDACTED]")
            .replace(Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+"), "Bearer [REDACTED]")
            .replace(Regex("AIza[0-9A-Za-z\\-_]{20,}"), "[REDACTED_GEMINI_KEY]")
            .replace(Regex("sk-or-v1-[0-9A-Za-z]{16,}"), "[REDACTED_OR_KEY]")
            .replace(Regex("hf_[0-9A-Za-z]{16,}"), "[REDACTED_HF_KEY]")
    }
}

/**
 * OkHttp interceptor attached to every cloud OkHttpClient in the app.
 * Blocks any outbound HTTP call immediately before DNS/socket creation when Strict Offline Mode is on.
 */
class StrictOfflineInterceptor(
    private val contextProvider: () -> Context? = { null },
    private val operationTag: String = "http_client"
) : Interceptor {

    constructor(context: Context, operationTag: String = "http_client") : this(
        contextProvider = { context.applicationContext ?: context },
        operationTag = operationTag
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val ctx = contextProvider()
        OfflinePrivacyGuard.checkOutboundAllowed(
            context = ctx,
            operation = "$operationTag:${request.method}",
            host = request.url.host
        )
        return chain.proceed(request)
    }
}
