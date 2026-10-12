package com.example.ai.providers

/**
 * Result returned from a tool execution.
 */
data class ToolExecutionResult(
    val success: Boolean,
    val output: String,
    val rawData: Any? = null,
    val error: String? = null
)

/**
 * Interface for Kavya's execution and data tools.
 */
interface ToolProvider {
    val toolId: String
    val displayName: String

    suspend fun execute(action: String, params: Map<String, String>): ToolExecutionResult
}
