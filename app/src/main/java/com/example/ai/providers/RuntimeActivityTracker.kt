package com.example.ai.providers

import android.content.Context
import com.example.utils.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * RuntimeActivityTracker tracks REAL execution events across all providers.
 * No fake counters or simulated events — only real runtime requests.
 */
object RuntimeActivityTracker {

    private const val MAX_RECENT_EVENTS = 50

    private val _events = CopyOnWriteArrayList<RuntimeActivityEvent>()
    private val _eventsFlow = MutableStateFlow<List<RuntimeActivityEvent>>(emptyList())
    val eventsFlow: StateFlow<List<RuntimeActivityEvent>> = _eventsFlow.asStateFlow()

    fun logEvent(
        context: Context?,
        providerId: String,
        model: String,
        task: String,
        success: Boolean,
        latencyMs: Long,
        errorCategory: String? = null
    ) {
        val event = RuntimeActivityEvent(
            providerId = providerId,
            model = model,
            task = task,
            success = success,
            latencyMs = latencyMs,
            errorCategory = errorCategory
        )

        _events.add(0, event)
        if (_events.size > MAX_RECENT_EVENTS) {
            _events.removeAt(_events.size - 1)
        }
        _eventsFlow.value = _events.toList()

        if (context != null) {
            try {
                AppPreferences.incrementProviderRequestCount(context, providerId)
                if (success) {
                    AppPreferences.setProviderLastSuccessTime(context, providerId, System.currentTimeMillis())
                    AppPreferences.setProviderStatus(context, providerId, ConnectionStatus.CONNECTED.name)
                    AppPreferences.setProviderLastError(context, providerId, null)
                } else if (!errorCategory.isNullOrBlank()) {
                    AppPreferences.setProviderLastError(context, providerId, errorCategory)
                    if (errorCategory.contains("429") || errorCategory.contains("Rate limit", ignoreCase = true)) {
                        AppPreferences.setProviderStatus(context, providerId, ConnectionStatus.RATE_LIMITED.name)
                    } else {
                        AppPreferences.setProviderStatus(context, providerId, ConnectionStatus.ERROR.name)
                    }
                }
            } catch (_: Exception) {
                // Ignore storage logging exceptions
            }
        }
    }

    fun getRecentEvents(): List<RuntimeActivityEvent> = _events.toList()

    fun getMetrics(context: Context): Map<String, Int> {
        val geminiKey = AppPreferences.getEffectiveApiKey(context)
        val orKey = AppPreferences.getOpenRouterApiKey(context)
        val hfKey = AppPreferences.getHuggingFaceApiKey(context)

        val geminiConnected = AppPreferences.getProviderStatus(context, "gemini") == ConnectionStatus.CONNECTED.name || geminiKey.isNotBlank()
        val orConnected = AppPreferences.getProviderStatus(context, "openrouter") == ConnectionStatus.CONNECTED.name && orKey.isNotBlank()
        val hfConnected = AppPreferences.getProviderStatus(context, "huggingface") == ConnectionStatus.CONNECTED.name && hfKey.isNotBlank()
        val publicApisConnected = AppPreferences.isPublicApisEnabled(context)

        var connectedCount = 0
        if (geminiConnected) connectedCount++
        if (orConnected) connectedCount++
        if (hfConnected) connectedCount++
        if (publicApisConnected) connectedCount++

        val totalRequests = _events.size
        val successfulRequests = _events.count { it.success }
        val failedRequests = _events.count { !it.success }

        return mapOf(
            "total_providers" to 4,
            "connected_providers" to connectedCount,
            "active_ai_models" to ModelRegistry.getActiveModelCount(),
            "available_tools" to 6, // Weather, Currency, Geocoding, Accessibility Hands, Vision Inspector, Memory
            "recent_requests" to totalRequests,
            "successful_requests" to successfulRequests,
            "failed_requests" to failedRequests
        )
    }
}
