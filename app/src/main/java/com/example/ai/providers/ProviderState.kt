package com.example.ai.providers

/**
 * Standard provider connection status reflecting REAL operational state.
 * Never faked based simply on key presence.
 */
enum class ConnectionStatus(val label: String) {
    NOT_CONFIGURED("Not Configured"),
    CONFIGURED("Configured"),
    TESTING("Testing..."),
    CONNECTED("Connected"),
    ERROR("Error"),
    RATE_LIMITED("Rate Limited"),
    DISABLED("Disabled")
}

/**
 * Registered AI model specification.
 */
data class RegisteredModel(
    val providerId: String,
    val modelId: String,
    val displayName: String,
    val task: String,
    val capabilities: List<String>,
    val isFree: Boolean = true
)

/**
 * Comprehensive details for a provider card in the AI & API Hub.
 */
data class ProviderDetails(
    val providerId: String,
    val displayName: String,
    val role: String,
    val status: ConnectionStatus,
    val selectedModel: String,
    val requestCount: Int,
    val lastSuccessfulRequestTime: Long?,
    val lastError: String?,
    val availableFunctions: List<String>,
    val isEnabled: Boolean,
    val hasKey: Boolean,
    val isPrimary: Boolean = false
)

/**
 * Real runtime event log item for AI Activity / System Status dashboard.
 */
data class RuntimeActivityEvent(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val providerId: String,
    val model: String,
    val task: String,
    val success: Boolean,
    val latencyMs: Long,
    val errorCategory: String? = null
)
