package com.example.ai.providers

import com.example.api.ApiExecutionResult
import com.example.api.ApiSystem

/**
 * Public API Tool Provider — External Data & Tool Resource.
 * Supplies real-time weather (Open-Meteo), currency conversion (Frankfurter),
 * country facts (REST Countries), and other external live data.
 */
class PublicApiToolProvider(
    private val apiSystem: ApiSystem = ApiSystem()
) : ToolProvider {

    companion object {
        const val TOOL_ID = "public_api"
    }

    override val toolId: String = TOOL_ID
    override val displayName: String = "Public APIs (External Data & Tools)"

    override suspend fun execute(action: String, params: Map<String, String>): ToolExecutionResult {
        val query = params["query"] ?: action
        val startTime = System.currentTimeMillis()
        val result = apiSystem.processRequest(query)
        val latency = System.currentTimeMillis() - startTime

        return when (result) {
            is ApiExecutionResult.Success -> {
                RuntimeActivityTracker.logEvent(
                    context = null,
                    providerId = "public_apis",
                    model = "live-tools",
                    task = "Data Query: $query",
                    success = true,
                    latencyMs = latency
                )
                ToolExecutionResult(
                    success = true,
                    output = result.data,
                    rawData = result.data
                )
            }
            is ApiExecutionResult.Failure -> {
                RuntimeActivityTracker.logEvent(
                    context = null,
                    providerId = "public_apis",
                    model = "live-tools",
                    task = "Data Query: $query",
                    success = false,
                    latencyMs = latency,
                    errorCategory = result.reason
                )
                ToolExecutionResult(
                    success = false,
                    output = "API execution failed: ${result.reason}",
                    error = result.reason
                )
            }
            is ApiExecutionResult.ToolRedirect -> {
                RuntimeActivityTracker.logEvent(
                    context = null,
                    providerId = "public_apis",
                    model = "live-tools",
                    task = "Tool Redirect: ${result.toolName}",
                    success = true,
                    latencyMs = latency
                )
                ToolExecutionResult(
                    success = true,
                    output = "Tool redirect: ${result.toolName}",
                    rawData = result.toolName
                )
            }
            null -> {
                RuntimeActivityTracker.logEvent(
                    context = null,
                    providerId = "public_apis",
                    model = "live-tools",
                    task = "Data Query: $query",
                    success = false,
                    latencyMs = latency,
                    errorCategory = "NO_API_HANDLER"
                )
                ToolExecutionResult(
                    success = false,
                    output = "No public API handler available for query: $query",
                    error = "NO_API_HANDLER"
                )
            }
        }
    }
}
