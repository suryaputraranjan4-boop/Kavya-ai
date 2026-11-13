package com.example.orchestrator

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Section 10: Single Reliable API State Manager.
 *
 * Enforces exact states:
 * - NOT_CONFIGURED
 * - VALIDATING
 * - CONNECTED
 * - TEMPORARILY_UNAVAILABLE
 * - INVALID_KEY
 * - QUOTA_EXCEEDED
 * - NETWORK_ERROR
 * - PROVIDER_ERROR
 *
 * Critical Rules:
 * 1. Never confuse an API timeout or temporary network error with an absent API key.
 * 2. Never overwrite or delete a saved key when a temporary error occurs.
 * 3. Never show "API not added" when a key is already configured.
 * 4. Redact secrets from all diagnostic messages and logs.
 */
enum class ApiConnectionState {
    NOT_CONFIGURED,
    VALIDATING,
    CONNECTED,
    TEMPORARILY_UNAVAILABLE,
    INVALID_KEY,
    QUOTA_EXCEEDED,
    NETWORK_ERROR,
    PROVIDER_ERROR;

    val isReadyForAiCalls: Boolean
        get() = this == CONNECTED || this == VALIDATING

    val hasConfiguredKey: Boolean
        get() = this != NOT_CONFIGURED
}

data class ApiStateSnapshot(
    val providerId: String = "GEMINI",
    val modelId: String = "gemini-3.5-flash-lite",
    val state: ApiConnectionState = ApiConnectionState.NOT_CONFIGURED,
    val keyConfigured: Boolean = false,
    val redactedKeyPreview: String = "",
    val diagnosticMessage: String = "API key not configured.",
    val lastUpdatedMs: Long = System.currentTimeMillis()
)

object ApiStateManager {
    private val _state = MutableStateFlow(ApiStateSnapshot())
    val state: StateFlow<ApiStateSnapshot> = _state.asStateFlow()

    /**
     * Redacts an API key or secret token so it is never exposed in logs or UI messages.
     */
    fun redactSecret(rawKey: String?): String {
        val clean = rawKey?.trim().orEmpty()
        if (clean.isEmpty()) return ""
        if (clean.length <= 8) return "****"
        return "${clean.take(4)}...${clean.takeLast(3)}"
    }

    /**
     * Synchronizes state from the currently selected provider and stored key without deleting or overwriting keys.
     */
    fun syncFromPreferences(context: Context): ApiStateSnapshot {
        val (providerId, modelId) = SelectedProviderClient.resolveSelectedProviderAndModel(context)
        val rawKey = SelectedProviderClient.resolveApiKey(context, providerId)
        val hasKey = rawKey.isNotBlank() || providerId == "CUSTOM"
        val current = _state.value

        if (!hasKey) {
            val next = ApiStateSnapshot(
                providerId = providerId,
                modelId = modelId,
                state = ApiConnectionState.NOT_CONFIGURED,
                keyConfigured = false,
                redactedKeyPreview = "",
                diagnosticMessage = "No API key configured for $providerId. Offline basic helper mode active."
            )
            _state.value = next
            return next
        }

        // Key IS configured. Preserve transient error states if same provider, or mark CONNECTED if ready.
        val preservedState = if (current.providerId == providerId && current.state != ApiConnectionState.NOT_CONFIGURED) {
            current.state
        } else {
            ApiConnectionState.CONNECTED
        }

        val msg = buildDiagnosticMessage(providerId, modelId, preservedState, true, current.diagnosticMessage)
        val next = ApiStateSnapshot(
            providerId = providerId,
            modelId = modelId,
            state = preservedState,
            keyConfigured = true,
            redactedKeyPreview = redactSecret(rawKey),
            diagnosticMessage = msg
        )
        _state.value = next
        return next
    }

    fun markValidating(providerId: String, modelId: String, rawKey: String) {
        val configured = rawKey.isNotBlank() || providerId == "CUSTOM"
        if (!configured) {
            _state.value = ApiStateSnapshot(
                providerId = providerId,
                modelId = modelId,
                state = ApiConnectionState.NOT_CONFIGURED,
                keyConfigured = false,
                redactedKeyPreview = "",
                diagnosticMessage = "No API key configured for $providerId."
            )
            return
        }
        _state.value = ApiStateSnapshot(
            providerId = providerId,
            modelId = modelId,
            state = ApiConnectionState.VALIDATING,
            keyConfigured = true,
            redactedKeyPreview = redactSecret(rawKey),
            diagnosticMessage = "Validating $providerId ($modelId) connection..."
        )
    }

    fun recordSuccess(providerId: String, modelId: String, rawKey: String) {
        _state.value = ApiStateSnapshot(
            providerId = providerId,
            modelId = modelId,
            state = ApiConnectionState.CONNECTED,
            keyConfigured = true,
            redactedKeyPreview = redactSecret(rawKey),
            diagnosticMessage = "Connected to $providerId ($modelId)."
        )
    }

    /**
     * Classifies an error or failure code into the exact ApiConnectionState.
     * Critically: when `hasSavedKey` is true, this NEVER transitions to NOT_CONFIGURED
     * and NEVER reports "API not added".
     */
    fun recordFailure(
        providerId: String,
        modelId: String,
        rawKey: String,
        errorCode: String?,
        errorMessage: String?
    ): ApiStateSnapshot {
        val hasSavedKey = rawKey.isNotBlank() || providerId == "CUSTOM"
        if (!hasSavedKey) {
            val snap = ApiStateSnapshot(
                providerId = providerId,
                modelId = modelId,
                state = ApiConnectionState.NOT_CONFIGURED,
                keyConfigured = false,
                redactedKeyPreview = "",
                diagnosticMessage = "No API key configured for $providerId."
            )
            _state.value = snap
            return snap
        }

        val combined = "${errorCode.orEmpty()} ${errorMessage.orEmpty()}".lowercase(Locale.ROOT)
        val newState = when {
            combined.contains("no_network") ||
                combined.contains("network") ||
                combined.contains("unable to resolve host") ||
                combined.contains("unknownhost") ||
                combined.contains("no internet") ||
                combined.contains("offline") -> ApiConnectionState.NETWORK_ERROR

            combined.contains("timeout") ||
                combined.contains("timed out") ||
                combined.contains("sockettimeout") ||
                combined.contains("503") ||
                combined.contains("502") ||
                combined.contains("504") ||
                combined.contains("temporarily") ||
                combined.contains("overloaded") -> ApiConnectionState.TEMPORARILY_UNAVAILABLE

            combined.contains("quota") ||
                combined.contains("rate limit") ||
                combined.contains("429") ||
                combined.contains("resource_exhausted") ||
                combined.contains("billing") -> ApiConnectionState.QUOTA_EXCEEDED

            combined.contains("401") ||
                combined.contains("403") ||
                combined.contains("invalid api key") ||
                combined.contains("api_key_invalid") ||
                combined.contains("unauthorized") -> ApiConnectionState.INVALID_KEY

            else -> ApiConnectionState.PROVIDER_ERROR
        }

        val safeMsg = sanitizeAndBuildMessage(providerId, modelId, newState, rawKey, errorMessage)
        val snap = ApiStateSnapshot(
            providerId = providerId,
            modelId = modelId,
            state = newState,
            keyConfigured = true,
            redactedKeyPreview = redactSecret(rawKey),
            diagnosticMessage = safeMsg
        )
        _state.value = snap
        return snap
    }

    private fun sanitizeAndBuildMessage(
        providerId: String,
        modelId: String,
        state: ApiConnectionState,
        rawKey: String,
        rawError: String?
    ): String {
        val redactedError = if (!rawKey.isNullOrBlank() && rawKey.length > 4) {
            rawError.orEmpty().replace(rawKey, redactSecret(rawKey))
        } else {
            rawError.orEmpty()
        }.trim()

        return when (state) {
            ApiConnectionState.NOT_CONFIGURED ->
                "No API key configured for $providerId."
            ApiConnectionState.VALIDATING ->
                "Validating $providerId ($modelId)..."
            ApiConnectionState.CONNECTED ->
                "Connected to $providerId ($modelId)."
            ApiConnectionState.TEMPORARILY_UNAVAILABLE ->
                "API key is saved for $providerId, but the service is temporarily unavailable or timed out. Your saved key is intact."
            ApiConnectionState.NETWORK_ERROR ->
                "API key is saved for $providerId, but a network error prevented reaching the server. Check internet connection."
            ApiConnectionState.QUOTA_EXCEEDED ->
                "API key is configured for $providerId, but rate limit or quota was exceeded (HTTP 429)."
            ApiConnectionState.INVALID_KEY ->
                "Configured API key for $providerId was rejected as invalid or unauthorized. Please verify your key in Settings."
            ApiConnectionState.PROVIDER_ERROR ->
                "API key is saved for $providerId ($modelId), but the provider returned an error: ${redactedError.take(120)}"
        }
    }

    private fun buildDiagnosticMessage(
        providerId: String,
        modelId: String,
        state: ApiConnectionState,
        keyConfigured: Boolean,
        previousMessage: String
    ): String {
        if (!keyConfigured) return "No API key configured for $providerId."
        if (state == ApiConnectionState.CONNECTED) return "Connected to $providerId ($modelId)."
        return previousMessage.ifBlank { "API key configured for $providerId ($modelId) [State: ${state.name}]." }
    }
}
