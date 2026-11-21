package com.example.kucp.registry

import com.example.kucp.model.ConnectorDefinition
import com.example.kucp.model.ConnectorHealth
import com.example.kucp.model.ConnectorStatus
import com.example.kucp.model.ToolDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln

data class ValidationIssue(val field: String, val message: String)

/**
 * Centralized Dynamic Tool Registry & Health Monitor.
 * Automatically indexes tools from active external connectors, MCP servers, plugins, and Android native services,
 * providing BM25/keyword contextual Top-K retrieval so adding new integrations never requires hardcoding tools
 * into SystemPrompt.
 */
class DynamicToolRegistry {

    private val connectorsMap = ConcurrentHashMap<String, ConnectorDefinition>()
    private val toolsById = ConcurrentHashMap<String, ToolDescriptor>()
    private val healthByConnector = ConcurrentHashMap<String, ConnectorHealth>()

    private val _connectors = MutableStateFlow<List<ConnectorDefinition>>(emptyList())
    val connectors: StateFlow<List<ConnectorDefinition>> = _connectors.asStateFlow()

    private val _tools = MutableStateFlow<List<ToolDescriptor>>(emptyList())
    val tools: StateFlow<List<ToolDescriptor>> = _tools.asStateFlow()

    private val _health = MutableStateFlow<Map<String, ConnectorHealth>>(emptyMap())
    val health: StateFlow<Map<String, ConnectorHealth>> = _health.asStateFlow()

    @Synchronized
    fun registerConnectorAndTools(connector: ConnectorDefinition, tools: List<ToolDescriptor>) {
        connectorsMap[connector.id] = connector
        // Remove previous tools belonging to this connector to prevent stale ghosts
        toolsById.keys.filter { toolsById[it]?.connectorId == connector.id }.forEach { toolsById.remove(it) }
        tools.forEach { t -> toolsById[t.toolId] = t }
        healthByConnector.putIfAbsent(
            connector.id,
            ConnectorHealth(connectorId = connector.id, status = connector.status)
        )
        publishFlows()
    }

    @Synchronized
    fun replaceConnectorTools(connectorId: String, tools: List<ToolDescriptor>) {
        toolsById.keys.filter { toolsById[it]?.connectorId == connectorId }.forEach { toolsById.remove(it) }
        tools.forEach { t -> toolsById[t.toolId] = t }
        connectorsMap[connectorId]?.let { existing ->
            connectorsMap[connectorId] = existing.copy(
                capabilities = tools.map { it.name },
                updatedAtMs = System.currentTimeMillis()
            )
        }
        publishFlows()
    }

    @Synchronized
    fun removeConnector(connectorId: String) {
        connectorsMap.remove(connectorId)
        toolsById.keys.filter { toolsById[it]?.connectorId == connectorId }.forEach { toolsById.remove(it) }
        healthByConnector.remove(connectorId)
        publishFlows()
    }

    fun getConnector(connectorId: String): ConnectorDefinition? = connectorsMap[connectorId]
    fun getTool(toolId: String): ToolDescriptor? = toolsById[toolId] ?: toolsById.values.firstOrNull { it.name == toolId }

    fun getToolsForConnector(connectorId: String): List<ToolDescriptor> =
        toolsById.values.filter { it.connectorId == connectorId }.sortedBy { it.name }

    @Synchronized
    fun updateHealth(
        connectorId: String,
        success: Boolean,
        latencyMs: Long,
        statusOverride: ConnectorStatus? = null,
        error: String? = null
    ) {
        val prev = healthByConnector[connectorId] ?: ConnectorHealth(connectorId)
        val consecFail = if (success) 0 else prev.consecutiveFailures + 1
        val newStatus = statusOverride ?: when {
            success -> ConnectorStatus.CONNECTED
            consecFail >= 3 -> ConnectorStatus.ERROR
            else -> ConnectorStatus.DEGRADED
        }
        healthByConnector[connectorId] = prev.copy(
            status = newStatus,
            latencyMs = if (latencyMs > 0L) latencyMs else prev.latencyMs,
            successCount = prev.successCount + if (success) 1 else 0,
            failureCount = prev.failureCount + if (success) 0 else 1,
            consecutiveFailures = consecFail,
            lastCheckedAtMs = System.currentTimeMillis(),
            lastError = if (success) null else error
        )
        connectorsMap[connectorId]?.let { c ->
            connectorsMap[connectorId] = c.copy(status = newStatus, updatedAtMs = System.currentTimeMillis())
        }
        publishFlows()
    }

    /**
     * BM25-inspired contextual Top-K retrieval.
     * Excludes disabled, revoked, and unconnected TEMPLATE connectors unless specifically requested.
     */
    fun retrieveRelevantTools(
        query: String,
        maxResults: Int = 8,
        includeDisconnected: Boolean = false
    ): List<ToolDescriptor> {
        val candidates = toolsById.values.filter { tool ->
            val conn = connectorsMap[tool.connectorId] ?: return@filter false
            if (!conn.enabled) return@filter false
            if (!includeDisconnected && conn.status in setOf(ConnectorStatus.TEMPLATE, ConnectorStatus.REVOKED, ConnectorStatus.DISCONNECTED)) {
                return@filter false
            }
            true
        }
        if (candidates.isEmpty()) return emptyList()

        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) {
            return candidates.take(maxResults)
        }

        // Document frequency across active tools
        val docs = candidates.associateWith { tool ->
            val conn = connectorsMap[tool.connectorId]
            tokenize(
                listOfNotNull(
                    tool.name.replace('_', ' '),
                    tool.description,
                    tool.tags.joinToString(" "),
                    conn?.name,
                    conn?.provider,
                    conn?.category?.label,
                    tool.inputSchema.joinToString(" ") { "${it.name} ${it.description}" }
                ).joinToString(" ")
            )
        }
        val avgLen = docs.values.map { it.size }.average().coerceAtLeast(1.0)
        val nDocs = docs.size.toDouble()
        val k1 = 1.5
        val b = 0.75

        val scored = candidates.mapNotNull { tool ->
            val docTokens = docs[tool].orEmpty()
            var score = 0.0
            for (qt in queryTokens) {
                val tf = docTokens.count { it == qt || it.contains(qt) || qt.contains(it) }.toDouble()
                if (tf <= 0.0) continue
                val df = docs.values.count { dt -> dt.any { it == qt || it.contains(qt) } }.toDouble()
                val idf = ln((nDocs - df + 0.5) / (df + 0.5) + 1.0)
                val denom = tf + k1 * (1.0 - b + b * (docTokens.size / avgLen))
                score += idf * ((tf * (k1 + 1.0)) / denom)
            }
            // Slight boost for exact tool/connector name match and healthy connectors
            val lowerQ = query.lowercase()
            if (lowerQ.contains(tool.name.lowercase().replace('_', ' ')) ||
                lowerQ.contains(tool.name.lowercase())
            ) {
                score += 4.0
            }
            val conn = connectorsMap[tool.connectorId]
            if (conn != null && lowerQ.contains(conn.name.lowercase())) {
                score += 2.5
            }
            if (healthByConnector[tool.connectorId]?.status == ConnectorStatus.CONNECTED) {
                score += 0.35
            }
            if (score > 0.0) tool to score else null
        }

        return scored.sortedByDescending { it.second }.take(maxResults).map { it.first }
    }

    fun validateArguments(tool: ToolDescriptor, args: Map<String, String>): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        for (param in tool.inputSchema) {
            val valStr = args[param.name]?.trim().orEmpty()
            if (param.required && valStr.isBlank() && param.defaultValue == null) {
                issues += ValidationIssue(param.name, "Required parameter '${param.name}' is missing.")
                continue
            }
            if (valStr.isNotBlank()) {
                if (param.enumValues.isNotEmpty() && valStr !in param.enumValues) {
                    issues += ValidationIssue(param.name, "Value '$valStr' must be one of ${param.enumValues}.")
                }
                when (param.type.lowercase()) {
                    "integer", "int" -> if (valStr.toLongOrNull() == null) {
                        issues += ValidationIssue(param.name, "Expected integer value for '${param.name}'.")
                    }
                    "number", "float", "double" -> if (valStr.toDoubleOrNull() == null) {
                        issues += ValidationIssue(param.name, "Expected numeric value for '${param.name}'.")
                    }
                    "boolean", "bool" -> if (valStr.lowercase() !in setOf("true", "false")) {
                        issues += ValidationIssue(param.name, "Expected boolean ('true'/'false') for '${param.name}'.")
                    }
                }
            }
        }
        return issues
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }

    private fun publishFlows() {
        _connectors.value = connectorsMap.values.sortedByDescending { it.updatedAtMs }
        _tools.value = toolsById.values.sortedBy { it.name }
        _health.value = healthByConnector.toMap()
    }
}
