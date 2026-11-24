package com.example.agent

import android.content.Context
import com.example.kucp.KucpGateway
import com.example.kucp.android.AndroidNativeIntegrationProvider
import com.example.kucp.model.ConnectorStatus
import com.example.kucp.model.ToolDescriptor
import com.example.kucp.model.ToolInvocationRequest
import com.example.kucp.model.ToolRiskLevel
import com.example.kucp.security.SecretRedactor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class GatewayOperationStatus(val label: String) {
    PENDING("Pending"),
    RUNNING("Running"),
    COMPLETED("Completed"),
    UNVERIFIED("Unverified"),
    FAILED("Failed"),
    WAITING_FOR_APPROVAL("Waiting for Approval"),
    CANCELLED("Cancelled")
}

data class GatewayToolSpec(
    val toolId: String,
    val name: String,
    val providerId: String,
    val providerName: String,
    val description: String,
    val source: String, // "KUCP" or "KAVYA_NATIVE"
    val riskLevel: ToolRiskLevel,
    val requiresConfirmation: Boolean,
    val parameterNames: List<String>,
    val isConnectedOrReady: Boolean
)

data class GatewayToolResult(
    val requestId: String,
    val toolId: String,
    val providerId: String,
    val operation: String,
    val status: GatewayOperationStatus,
    val structuredOutput: String = "",
    val isolatedOutputForLlm: String = "",
    val errorDetails: String? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    val sideEffectSummary: String = "",
    val verificationEvidence: String = "",
    val sourceRefs: List<String> = emptyList(),
    val idempotencyKey: String = "",
    val durationMs: Long = 0L,
    val pendingApprovalId: String? = null
) {
    val isSuccess: Boolean get() = status == GatewayOperationStatus.COMPLETED
}

data class PendingToolApproval(
    val approvalId: String,
    val taskId: String,
    val agentId: String,
    val toolId: String,
    val providerId: String,
    val operationSummary: String,
    val riskLabel: String,
    val argumentsSummary: String,
    val createdAtMs: Long = System.currentTimeMillis()
)

data class ToolExecutionHistoryEntry(
    val id: String,
    val taskId: String,
    val agentId: String,
    val toolId: String,
    val providerId: String,
    val status: GatewayOperationStatus,
    val outputExcerpt: String,
    val errorMessage: String? = null,
    val durationMs: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Single authoritative Universal Tool Gateway unifying:
 *  1) KUCP external/MCP/REST/GraphQL/OAuth/Webhook/Plugin/Android-Native tools via KucpGateway
 *  2) Kavya on-device tools (AndroidAgent UI automation, OKF memory, alarms, maps) via KavyaToolRegistry
 *
 * Enforces idempotency deduplication, write-action approval queues, secret redaction,
 * and unified execution history across Normal Chat, Dynamic Agents, Workflows, and Scheduler.
 */
class UniversalToolGateway private constructor(
    private val appContext: Context,
    private val kucpGateway: KucpGateway
) {
    companion object {
        @Volatile
        private var instance: UniversalToolGateway? = null

        private val SENSITIVE_NATIVE_TOOLS = setOf(
            "close_app", "download_file", "memory_delete"
        )

        fun getInstance(context: Context): UniversalToolGateway =
            instance ?: synchronized(this) {
                val app = context.applicationContext
                instance ?: UniversalToolGateway(app, KucpGateway.getInstance(app)).also { instance = it }
            }

        internal fun createForTesting(context: Context, kucpGateway: KucpGateway): UniversalToolGateway =
            UniversalToolGateway(context.applicationContext, kucpGateway)
    }

    private val idempotencyCache = ConcurrentHashMap<String, GatewayToolResult>()
    private val approvalWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val externalCallCounter = AtomicInteger(0)

    private val _pendingApprovals = MutableStateFlow<List<PendingToolApproval>>(emptyList())
    val pendingApprovals: StateFlow<List<PendingToolApproval>> = _pendingApprovals.asStateFlow()

    private val _executionHistory = MutableStateFlow<List<ToolExecutionHistoryEntry>>(emptyList())
    val executionHistory: StateFlow<List<ToolExecutionHistoryEntry>> = _executionHistory.asStateFlow()

    private val _totalExternalCalls = MutableStateFlow(0)
    val totalExternalCalls: StateFlow<Int> = _totalExternalCalls.asStateFlow()

    /**
     * Discovers all currently callable tools across KUCP connectors and KavyaToolRegistry.
     * Optionally filters to an agent's least-privilege tool allowlist.
     */
    fun discoverAuthorizedTools(
        allowedTools: Set<String>? = null,
        includeUnconnectedConnectors: Boolean = false
    ): List<GatewayToolSpec> {
        val connectorsById = kucpGateway.connectors.value.associateBy { it.id }
        val kucpSpecs = kucpGateway.tools.value.mapNotNull { tool ->
            val conn = connectorsById[tool.connectorId] ?: return@mapNotNull null
            if (!conn.enabled) return@mapNotNull null
            val ready = conn.status == ConnectorStatus.CONNECTED ||
                conn.status == ConnectorStatus.PARTIALLY_AUTHORIZED ||
                conn.id == AndroidNativeIntegrationProvider.CONNECTOR_ID
            if (!includeUnconnectedConnectors && !ready) return@mapNotNull null

            GatewayToolSpec(
                toolId = tool.toolId,
                name = tool.name,
                providerId = conn.id,
                providerName = conn.name,
                description = tool.description,
                source = "KUCP",
                riskLevel = tool.riskLevel,
                requiresConfirmation = tool.requiresConfirmation || tool.riskLevel == ToolRiskLevel.HIGH || tool.riskLevel == ToolRiskLevel.CRITICAL,
                parameterNames = tool.inputSchema.map { it.name },
                isConnectedOrReady = ready
            )
        }

        val nativeSpecs = KavyaToolRegistry.AVAILABLE_TOOLS.map { def ->
            val sensitive = def.name.lowercase() in SENSITIVE_NATIVE_TOOLS
            GatewayToolSpec(
                toolId = def.name,
                name = def.name,
                providerId = "kavya_native",
                providerName = "Kavya On-Device Runtime",
                description = def.description,
                source = "KAVYA_NATIVE",
                riskLevel = if (sensitive) ToolRiskLevel.HIGH else ToolRiskLevel.LOW,
                requiresConfirmation = sensitive,
                parameterNames = def.parameters.keys.toList(),
                isConnectedOrReady = true
            )
        }

        val combined = kucpSpecs + nativeSpecs
        if (allowedTools.isNullOrEmpty()) return combined
        val normalizedAllow = allowedTools.map { it.trim().lowercase() }.toSet()
        return combined.filter {
            it.toolId.lowercase() in normalizedAllow || it.name.lowercase() in normalizedAllow
        }
    }

    /**
     * Resolves a pending approval request from the Agents Workspace UI or interactive prompt.
     */
    fun resolveApproval(approvalId: String, approved: Boolean): Boolean {
        val waiter = approvalWaiters.remove(approvalId)
        _pendingApprovals.value = _pendingApprovals.value.filterNot { it.approvalId == approvalId }
        return waiter?.complete(approved) ?: false
    }

    fun approve(approvalId: String): Boolean = resolveApproval(approvalId, true)

    fun reject(approvalId: String): Boolean = resolveApproval(approvalId, false)

    /**
     * Unified tool execution path for Normal Chat, Dynamic Agents, Workflows, and Scheduler.
     */
    suspend fun execute(
        toolIdOrName: String,
        arguments: Map<String, String> = emptyMap(),
        context: Context = appContext,
        androidAgent: AndroidAgent? = null,
        taskId: String = "ad_hoc",
        agentId: String = "orchestrator",
        idempotencyKey: String = "",
        userConfirmed: Boolean = false,
        waitForApprovalMs: Long = 0L,
        onRequestApproval: (suspend (PendingToolApproval) -> Boolean)? = null
    ): GatewayToolResult = withContext(Dispatchers.IO) {
        val reqId = UUID.randomUUID().toString()
        val started = System.currentTimeMillis()

        // 1. Idempotency deduplication (prevents duplicate write side effects on recovery/retry)
        if (idempotencyKey.isNotBlank()) {
            idempotencyCache[idempotencyKey]?.let { cached ->
                return@withContext cached.copy(
                    verificationEvidence = "Deduplicated via idempotency key '$idempotencyKey'"
                )
            }
        }

        // 2. Resolve tool in KUCP first, then KavyaToolRegistry
        val kucpTool = resolveKucpTool(toolIdOrName)
        if (kucpTool != null) {
            return@withContext executeKucpTool(
                reqId = reqId,
                tool = kucpTool,
                arguments = arguments,
                taskId = taskId,
                agentId = agentId,
                idempotencyKey = idempotencyKey,
                userConfirmed = userConfirmed,
                waitForApprovalMs = waitForApprovalMs,
                onRequestApproval = onRequestApproval,
                startedAtMs = started
            )
        }

        val nativeDef = KavyaToolRegistry.AVAILABLE_TOOLS.firstOrNull {
            it.name.equals(toolIdOrName.trim(), ignoreCase = true)
        }
        if (nativeDef != null) {
            return@withContext executeNativeTool(
                reqId = reqId,
                toolDef = nativeDef,
                arguments = arguments,
                context = context,
                androidAgent = androidAgent,
                taskId = taskId,
                agentId = agentId,
                idempotencyKey = idempotencyKey,
                userConfirmed = userConfirmed,
                waitForApprovalMs = waitForApprovalMs,
                onRequestApproval = onRequestApproval,
                startedAtMs = started
            )
        }

        val notFound = GatewayToolResult(
            requestId = reqId,
            toolId = toolIdOrName,
            providerId = "unknown",
            operation = toolIdOrName,
            status = GatewayOperationStatus.FAILED,
            errorDetails = "Tool '$toolIdOrName' is not registered in KUCP or KavyaToolRegistry.",
            idempotencyKey = idempotencyKey,
            durationMs = (System.currentTimeMillis() - started).coerceAtLeast(1L)
        )
        recordHistory(taskId, agentId, notFound)
        notFound
    }

    private fun resolveKucpTool(toolIdOrName: String): ToolDescriptor? {
        val clean = toolIdOrName.trim()
        return kucpGateway.toolRegistry.getTool(clean)
            ?: kucpGateway.tools.value.firstOrNull {
                it.name.equals(clean, ignoreCase = true) ||
                    "${it.connectorId}.${it.name}".equals(clean, ignoreCase = true)
            }
    }

    private suspend fun executeKucpTool(
        reqId: String,
        tool: ToolDescriptor,
        arguments: Map<String, String>,
        taskId: String,
        agentId: String,
        idempotencyKey: String,
        userConfirmed: Boolean,
        waitForApprovalMs: Long,
        onRequestApproval: (suspend (PendingToolApproval) -> Boolean)?,
        startedAtMs: Long
    ): GatewayToolResult {
        val connector = kucpGateway.toolRegistry.getConnector(tool.connectorId)
        val providerName = connector?.name ?: tool.connectorId
        var confirmed = userConfirmed

        val needsConfirm = tool.requiresConfirmation ||
            tool.riskLevel == ToolRiskLevel.HIGH ||
            tool.riskLevel == ToolRiskLevel.CRITICAL

        if (needsConfirm && !confirmed) {
            val approvalOutcome = handleApprovalGate(
                taskId = taskId,
                agentId = agentId,
                toolId = tool.toolId,
                providerId = tool.connectorId,
                operationSummary = "$providerName -> ${tool.name} (${tool.methodOrOperation})",
                riskLabel = tool.riskLevel.label,
                arguments = arguments,
                waitForApprovalMs = waitForApprovalMs,
                onRequestApproval = onRequestApproval
            )
            when (approvalOutcome.first) {
                GatewayOperationStatus.COMPLETED -> confirmed = true
                GatewayOperationStatus.WAITING_FOR_APPROVAL,
                GatewayOperationStatus.CANCELLED -> {
                    val gated = GatewayToolResult(
                        requestId = reqId,
                        toolId = tool.toolId,
                        providerId = tool.connectorId,
                        operation = "${tool.methodOrOperation} ${tool.endpointPath}".trim(),
                        status = approvalOutcome.first,
                        errorDetails = approvalOutcome.second,
                        idempotencyKey = idempotencyKey,
                        durationMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1L),
                        pendingApprovalId = approvalOutcome.third
                    )
                    recordHistory(taskId, agentId, gated)
                    return gated
                }
                else -> {}
            }
        }

        if (tool.connectorId != AndroidNativeIntegrationProvider.CONNECTOR_ID) {
            _totalExternalCalls.value = externalCallCounter.incrementAndGet()
        }

        val kucpRes = kucpGateway.executeTool(
            ToolInvocationRequest(
                requestId = reqId,
                connectorId = tool.connectorId,
                toolId = tool.toolId,
                arguments = arguments,
                userConfirmed = confirmed,
                idempotencyKey = idempotencyKey
            )
        )

        val status = when {
            kucpRes.success -> GatewayOperationStatus.COMPLETED
            kucpRes.requiresUserConfirmation -> GatewayOperationStatus.WAITING_FOR_APPROVAL
            else -> GatewayOperationStatus.FAILED
        }

        val gatewayResult = GatewayToolResult(
            requestId = reqId,
            toolId = tool.toolId,
            providerId = tool.connectorId,
            operation = "${tool.methodOrOperation} ${tool.endpointPath}".trim(),
            status = status,
            structuredOutput = SecretRedactor.redact(kucpRes.outputData),
            isolatedOutputForLlm = kucpRes.isolatedDataForLlm.ifBlank { SecretRedactor.redact(kucpRes.outputData) },
            errorDetails = kucpRes.errorMessage?.let { SecretRedactor.redact(it) },
            sideEffectSummary = if (tool.riskLevel == ToolRiskLevel.LOW) "Read-only query" else "External operation on $providerName",
            verificationEvidence = if (kucpRes.success) "HTTP/Protocol status ${kucpRes.statusCode} in ${kucpRes.durationMs}ms" else "",
            sourceRefs = listOfNotNull(connector?.baseUrl?.takeIf { it.isNotBlank() }),
            idempotencyKey = idempotencyKey,
            durationMs = kucpRes.durationMs.coerceAtLeast(1L)
        )

        if (idempotencyKey.isNotBlank() && gatewayResult.isSuccess) {
            idempotencyCache[idempotencyKey] = gatewayResult
        }
        recordHistory(taskId, agentId, gatewayResult)
        return gatewayResult
    }

    private suspend fun executeNativeTool(
        reqId: String,
        toolDef: ToolDefinition,
        arguments: Map<String, String>,
        context: Context,
        androidAgent: AndroidAgent?,
        taskId: String,
        agentId: String,
        idempotencyKey: String,
        userConfirmed: Boolean,
        waitForApprovalMs: Long,
        onRequestApproval: (suspend (PendingToolApproval) -> Boolean)?,
        startedAtMs: Long
    ): GatewayToolResult {
        val sensitive = toolDef.name.lowercase() in SENSITIVE_NATIVE_TOOLS
        if (sensitive && !userConfirmed) {
            val approvalOutcome = handleApprovalGate(
                taskId = taskId,
                agentId = agentId,
                toolId = toolDef.name,
                providerId = "kavya_native",
                operationSummary = "On-Device Action -> ${toolDef.name}",
                riskLabel = ToolRiskLevel.HIGH.label,
                arguments = arguments,
                waitForApprovalMs = waitForApprovalMs,
                onRequestApproval = onRequestApproval
            )
            if (approvalOutcome.first != GatewayOperationStatus.COMPLETED) {
                val gated = GatewayToolResult(
                    requestId = reqId,
                    toolId = toolDef.name,
                    providerId = "kavya_native",
                    operation = toolDef.name,
                    status = approvalOutcome.first,
                    errorDetails = approvalOutcome.second,
                    idempotencyKey = idempotencyKey,
                    durationMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1L),
                    pendingApprovalId = approvalOutcome.third
                )
                recordHistory(taskId, agentId, gated)
                return gated
            }
        }

        val agent = androidAgent ?: run {
            val appCtx = context.applicationContext
            val inspector = ScreenInspector()
            AndroidAgent(
                appCtx,
                com.example.utils.AppResolver(appCtx),
                ContextEngine(),
                inspector,
                VerificationEngine(inspector)
            )
        }
        val exec = KavyaToolRegistry.executeTool(toolDef.name, arguments, context, agent)
        val elapsed = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1L)
        val result = GatewayToolResult(
            requestId = reqId,
            toolId = toolDef.name,
            providerId = "kavya_native",
            operation = toolDef.name,
            status = if (exec.success) GatewayOperationStatus.COMPLETED else GatewayOperationStatus.FAILED,
            structuredOutput = SecretRedactor.redact(exec.data),
            isolatedOutputForLlm = SecretRedactor.redact(exec.data),
            errorDetails = exec.error?.let { SecretRedactor.redact(it) },
            sideEffectSummary = "On-device tool '${toolDef.name}'",
            verificationEvidence = if (exec.success) "Executed natively in ${elapsed}ms" else "",
            idempotencyKey = idempotencyKey,
            durationMs = elapsed
        )
        if (idempotencyKey.isNotBlank() && result.isSuccess) {
            idempotencyCache[idempotencyKey] = result
        }
        recordHistory(taskId, agentId, result)
        return result
    }

    private suspend fun handleApprovalGate(
        taskId: String,
        agentId: String,
        toolId: String,
        providerId: String,
        operationSummary: String,
        riskLabel: String,
        arguments: Map<String, String>,
        waitForApprovalMs: Long,
        onRequestApproval: (suspend (PendingToolApproval) -> Boolean)?
    ): Triple<GatewayOperationStatus, String, String> {
        val approvalId = "apr_${UUID.randomUUID().toString().take(8)}"
        val redactedArgs = arguments.entries.joinToString(", ") { (k, v) ->
            "$k=${SecretRedactor.redact(v).take(80)}"
        }
        val pending = PendingToolApproval(
            approvalId = approvalId,
            taskId = taskId,
            agentId = agentId,
            toolId = toolId,
            providerId = providerId,
            operationSummary = operationSummary,
            riskLabel = riskLabel,
            argumentsSummary = redactedArgs
        )
        val deferred = CompletableDeferred<Boolean>()
        approvalWaiters[approvalId] = deferred
        _pendingApprovals.value = listOf(pending) + _pendingApprovals.value

        if (onRequestApproval != null) {
            val decided = try {
                onRequestApproval(pending)
            } catch (_: Exception) {
                false
            }
            resolveApproval(approvalId, decided)
            return if (decided) {
                Triple(GatewayOperationStatus.COMPLETED, "", approvalId)
            } else {
                Triple(GatewayOperationStatus.CANCELLED, "User rejected approval for $operationSummary.", approvalId)
            }
        }

        if (waitForApprovalMs > 0L) {
            val decision = withTimeoutOrNull(waitForApprovalMs) { deferred.await() }
            if (decision == true) {
                return Triple(GatewayOperationStatus.COMPLETED, "", approvalId)
            } else if (decision == false) {
                return Triple(GatewayOperationStatus.CANCELLED, "User rejected approval for $operationSummary.", approvalId)
            }
        }

        return Triple(
            GatewayOperationStatus.WAITING_FOR_APPROVAL,
            "Action '$operationSummary' queued in Agents -> Approvals (id=$approvalId) awaiting explicit confirmation.",
            approvalId
        )
    }

    private fun recordHistory(taskId: String, agentId: String, res: GatewayToolResult) {
        val entry = ToolExecutionHistoryEntry(
            id = res.requestId,
            taskId = taskId,
            agentId = agentId,
            toolId = res.toolId,
            providerId = res.providerId,
            status = res.status,
            outputExcerpt = res.structuredOutput.take(260),
            errorMessage = res.errorDetails?.take(200),
            durationMs = res.durationMs,
            timestampMs = res.timestampMs
        )
        _executionHistory.value = (listOf(entry) + _executionHistory.value).take(200)
    }

    fun clearIdempotencyCache() {
        idempotencyCache.clear()
    }
}
