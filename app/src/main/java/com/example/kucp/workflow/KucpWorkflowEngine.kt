package com.example.kucp.workflow

import com.example.kucp.model.ToolInvocationResult
import com.example.kucp.model.WorkflowCondition
import com.example.kucp.model.WorkflowDefinition
import com.example.kucp.model.WorkflowExecutionRecord
import com.example.kucp.model.WorkflowStep
import com.example.kucp.model.WorkflowStepResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Dependency-aware DAG Workflow & Cross-App Automation Engine.
 * Supports:
 * - Parallel execution of independent branches within each topological level
 * - Conditional branching (`equals`, `contains`, `not_empty`)
 * - Bounded array looping with strict safety iteration limit (<= 20)
 * - Idempotency keys per `(runId, stepId)` to prevent duplicate side effects on resume
 * - Safe retries strictly restricted to idempotent read operations
 * - Pause / Resume / Cancellation state control
 * - Confirmation gate callback before sensitive steps
 */
class KucpWorkflowEngine(
    private val executeStepTool: suspend (
        connectorId: String,
        toolId: String,
        args: Map<String, String>,
        userConfirmed: Boolean,
        idempotencyKey: String
    ) -> ToolInvocationResult,
    private val requestStepConfirmation: suspend (WorkflowStep, Map<String, String>) -> Boolean = { _, _ -> false }
) {
    private val _activeRun = MutableStateFlow<WorkflowExecutionRecord?>(null)
    val activeRun: StateFlow<WorkflowExecutionRecord?> = _activeRun.asStateFlow()

    private val pausedRuns = ConcurrentHashMap<String, Boolean>()
    private val cancelledRuns = ConcurrentHashMap<String, Boolean>()
    private val completedIdempotencyResults = ConcurrentHashMap<String, WorkflowStepResult>()

    fun pauseRun(runId: String) {
        pausedRuns[runId] = true
    }

    fun resumeRun(runId: String) {
        pausedRuns[runId] = false
    }

    fun cancelRun(runId: String) {
        cancelledRuns[runId] = true
        pausedRuns[runId] = false
    }

    suspend fun executeWorkflow(
        workflow: WorkflowDefinition,
        initialInputs: Map<String, String> = emptyMap(),
        runId: String = UUID.randomUUID().toString()
    ): WorkflowExecutionRecord {
        if (!workflow.enabled) {
            return WorkflowExecutionRecord(
                runId = runId,
                workflowId = workflow.id,
                workflowName = workflow.name,
                status = "FAILED",
                finishedAtMs = System.currentTimeMillis(),
                errorSummary = "Workflow '${workflow.name}' is disabled."
            )
        }
        if (workflow.steps.isEmpty()) {
            return WorkflowExecutionRecord(
                runId = runId,
                workflowId = workflow.id,
                workflowName = workflow.name,
                status = "FAILED",
                finishedAtMs = System.currentTimeMillis(),
                errorSummary = "Workflow contains no steps."
            )
        }

        val stepById = workflow.steps.associateBy { it.stepId }
        val cycleError = detectCycleOrMissingDep(workflow.steps, stepById)
        if (cycleError != null) {
            return WorkflowExecutionRecord(
                runId = runId,
                workflowId = workflow.id,
                workflowName = workflow.name,
                status = "FAILED",
                finishedAtMs = System.currentTimeMillis(),
                errorSummary = cycleError
            )
        }

        cancelledRuns[runId] = false
        pausedRuns[runId] = false

        val resultsByStep = ConcurrentHashMap<String, WorkflowStepResult>()
        val startedAt = System.currentTimeMillis()
        var currentRecord = WorkflowExecutionRecord(
            runId = runId,
            workflowId = workflow.id,
            workflowName = workflow.name,
            status = "RUNNING",
            startedAtMs = startedAt
        )
        _activeRun.value = currentRecord

        val remaining = workflow.steps.map { it.stepId }.toMutableSet()
        var overallFailed = false
        var failSummary: String? = null

        try {
            while (remaining.isNotEmpty()) {
                while (pausedRuns[runId] == true && cancelledRuns[runId] != true) {
                    _activeRun.value = currentRecord.copy(status = "PAUSED", stepResults = resultsByStep.values.toList())
                    delay(150L)
                }
                if (cancelledRuns[runId] == true) {
                    val cancelledRec = currentRecord.copy(
                        status = "CANCELLED",
                        stepResults = resultsByStep.values.toList(),
                        finishedAtMs = System.currentTimeMillis(),
                        errorSummary = "Cancelled by user."
                    )
                    _activeRun.value = cancelledRec
                    return cancelledRec
                }

                // Ready wave: all dependencies satisfied
                val readyWave = remaining
                    .mapNotNull { stepById[it] }
                    .filter { step -> step.dependsOn.all { dep -> dep in resultsByStep.keys } }

                if (readyWave.isEmpty()) {
                    overallFailed = true
                    failSummary = "DAG execution stalled on unresolved dependencies: $remaining"
                    break
                }

                // Execute ready wave branches concurrently
                val waveResults = coroutineScope {
                    readyWave.map { step ->
                        async {
                            executeSingleStep(runId, step, initialInputs, resultsByStep)
                        }
                    }.awaitAll()
                }

                for (sr in waveResults) {
                    resultsByStep[sr.stepId] = sr
                    remaining.remove(sr.stepId)
                    if (sr.status == "FAILED" || sr.status == "CONFIRMATION_REQUIRED") {
                        overallFailed = true
                        failSummary = sr.error ?: "Step '${sr.title}' ended with ${sr.status}."
                    }
                }

                currentRecord = currentRecord.copy(stepResults = resultsByStep.values.toList())
                _activeRun.value = currentRecord

                if (overallFailed) break
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            overallFailed = true
            failSummary = e.message ?: "Workflow execution error"
        }

        val finalStatus = when {
            cancelledRuns[runId] == true -> "CANCELLED"
            resultsByStep.values.any { it.status == "CONFIRMATION_REQUIRED" } -> "CONFIRMATION_REQUIRED"
            overallFailed -> "FAILED"
            else -> "COMPLETED"
        }

        val finalRecord = currentRecord.copy(
            status = finalStatus,
            stepResults = workflow.steps.mapNotNull { resultsByStep[it.stepId] },
            finishedAtMs = System.currentTimeMillis(),
            errorSummary = failSummary
        )
        _activeRun.value = finalRecord
        return finalRecord
    }

    private suspend fun executeSingleStep(
        runId: String,
        step: WorkflowStep,
        initialInputs: Map<String, String>,
        resultsByStep: Map<String, WorkflowStepResult>
    ): WorkflowStepResult {
        val idempKey = "$runId:${step.stepId}"
        completedIdempotencyResults[idempKey]?.let { prev ->
            if (prev.status == "COMPLETED") return prev
        }

        // Check if any upstream dependency failed
        val failedDep = step.dependsOn.firstOrNull { resultsByStep[it]?.status == "FAILED" }
        if (failedDep != null) {
            return WorkflowStepResult(step.stepId, step.title, "SKIPPED", error = "Upstream step '$failedDep' failed.")
        }

        // Evaluate conditional branching
        step.condition?.let { cond ->
            if (!evaluateCondition(cond, resultsByStep)) {
                return WorkflowStepResult(step.stepId, step.title, "SKIPPED", output = "Condition not met (${cond.sourceStepId} ${cond.operator}).")
            }
        }

        val stepStart = System.currentTimeMillis()
        val resolvedArgs = resolveArguments(step.inputMapping, initialInputs, resultsByStep)

        // Bounded loop support over a JSON array string
        if (step.loopArrayJsonPath.isNotBlank()) {
            val rawArrayText = resolveTemplate(step.loopArrayJsonPath, initialInputs, resultsByStep)
            val arr = try {
                JSONArray(rawArrayText)
            } catch (_: Exception) {
                return WorkflowStepResult(step.stepId, step.title, "FAILED", error = "Loop target is not a valid JSON array.")
            }
            val bound = minOf(arr.length(), step.maxLoopIterations.coerceIn(1, 20))
            val collected = JSONArray()
            for (i in 0 until bound) {
                val itemVal = arr.opt(i)?.toString().orEmpty()
                val iterArgs = resolvedArgs.toMutableMap().apply { put("item", itemVal); put("index", i.toString()) }
                val res = invokeWithConfirmationAndSafeRetry(step, iterArgs, "$idempKey:$i")
                if (!res.success) {
                    return WorkflowStepResult(
                        step.stepId,
                        step.title,
                        if (res.requiresUserConfirmation) "CONFIRMATION_REQUIRED" else "FAILED",
                        output = collected.toString(),
                        error = res.errorMessage ?: res.confirmationPrompt,
                        durationMs = System.currentTimeMillis() - stepStart
                    )
                }
                collected.put(res.outputData)
            }
            val okRes = WorkflowStepResult(step.stepId, step.title, "COMPLETED", collected.toString(), durationMs = System.currentTimeMillis() - stepStart)
            completedIdempotencyResults[idempKey] = okRes
            return okRes
        }

        val res = invokeWithConfirmationAndSafeRetry(step, resolvedArgs, idempKey)
        val finalStepRes = WorkflowStepResult(
            stepId = step.stepId,
            title = step.title,
            status = when {
                res.success -> "COMPLETED"
                res.requiresUserConfirmation -> "CONFIRMATION_REQUIRED"
                else -> "FAILED"
            },
            output = res.outputData,
            error = res.errorMessage ?: res.confirmationPrompt,
            durationMs = System.currentTimeMillis() - stepStart
        )
        if (finalStepRes.status == "COMPLETED") {
            completedIdempotencyResults[idempKey] = finalStepRes
        }
        return finalStepRes
    }

    private suspend fun invokeWithConfirmationAndSafeRetry(
        step: WorkflowStep,
        args: Map<String, String>,
        idempotencyKey: String
    ): ToolInvocationResult {
        var confirmed = false
        if (step.requireConfirmation) {
            confirmed = requestStepConfirmation(step, args)
            if (!confirmed) {
                return ToolInvocationResult(
                    requestId = idempotencyKey,
                    connectorId = step.connectorId,
                    toolId = step.toolId,
                    success = false,
                    errorCode = "CONFIRMATION_REQUIRED",
                    errorMessage = "Step '${step.title}' requires explicit user confirmation.",
                    requiresUserConfirmation = true,
                    confirmationPrompt = "Confirm workflow step '${step.title}'?"
                )
            }
        }

        var result = executeStepTool(step.connectorId, step.toolId, args, confirmed, idempotencyKey)

        if (result.requiresUserConfirmation && !confirmed) {
            confirmed = requestStepConfirmation(step, args)
            if (!confirmed) return result
            result = executeStepTool(step.connectorId, step.toolId, args, true, idempotencyKey)
        }

        // Retry only transient errors when explicitly enabled (idempotent-safe)
        if (!result.success && step.retryIdempotentReads && result.errorCode in setOf("HTTP_429", "HTTP_503", "REST_EXCEPTION")) {
            delay(400L)
            result = executeStepTool(step.connectorId, step.toolId, args, confirmed, idempotencyKey)
        }
        return result
    }

    private fun evaluateCondition(cond: WorkflowCondition, resultsByStep: Map<String, WorkflowStepResult>): Boolean {
        val src = resultsByStep[cond.sourceStepId]?.output.orEmpty()
        return when (cond.operator.uppercase()) {
            "EQUALS" -> src.trim() == cond.expectedValue.trim()
            "CONTAINS" -> src.contains(cond.expectedValue, ignoreCase = true)
            "NOT_EMPTY" -> src.isNotBlank()
            else -> src.isNotBlank()
        }
    }

    private fun resolveArguments(
        mapping: Map<String, String>,
        initialInputs: Map<String, String>,
        resultsByStep: Map<String, WorkflowStepResult>
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        mapping.forEach { (k, rawVal) ->
            out[k] = resolveTemplate(rawVal, initialInputs, resultsByStep)
        }
        return out
    }

    private fun resolveTemplate(
        template: String,
        initialInputs: Map<String, String>,
        resultsByStep: Map<String, WorkflowStepResult>
    ): String {
        var resolved = template
        val tokenRegex = Regex("\\{\\{\\s*([^}]+)\\s*\\}\\}")
        resolved = tokenRegex.replace(resolved) { m ->
            val expr = m.groupValues[1].trim()
            when {
                expr.startsWith("input.") -> initialInputs[expr.removePrefix("input.")].orEmpty()
                expr.startsWith("steps.") -> {
                    val parts = expr.removePrefix("steps.").split(".", limit = 3)
                    val sid = parts.getOrNull(0).orEmpty()
                    val field = parts.getOrNull(1).orEmpty()
                    val out = resultsByStep[sid]?.output.orEmpty()
                    if (field == "output" && parts.size == 2) {
                        out
                    } else if (field == "output" && parts.size == 3) {
                        try {
                            JSONObject(out).opt(parts[2])?.toString().orEmpty()
                        } catch (_: Exception) {
                            ""
                        }
                    } else out
                }
                else -> initialInputs[expr].orEmpty()
            }
        }
        return resolved
    }

    private fun detectCycleOrMissingDep(
        steps: List<WorkflowStep>,
        byId: Map<String, WorkflowStep>
    ): String? {
        for (s in steps) {
            for (dep in s.dependsOn) {
                if (dep == s.stepId) return "Step '${s.stepId}' cannot depend on itself."
                if (byId[dep] == null) return "Step '${s.stepId}' depends on unknown step '$dep'."
            }
        }
        val state = HashMap<String, Int>() // 0=unvisited, 1=visiting, 2=visited
        fun hasCycle(id: String): Boolean {
            when (state[id] ?: 0) {
                1 -> return true
                2 -> return false
            }
            state[id] = 1
            val s = byId[id]
            if (s != null) {
                for (dep in s.dependsOn) {
                    if (hasCycle(dep)) return true
                }
            }
            state[id] = 2
            return false
        }
        for (s in steps) {
            if (hasCycle(s.stepId)) return "Workflow contains a cyclic dependency involving '${s.stepId}'."
        }
        return null
    }
}
