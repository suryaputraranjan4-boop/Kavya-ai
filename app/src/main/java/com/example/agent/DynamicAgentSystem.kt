package com.example.agent

import android.content.Context
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.DurableTaskEntity
import com.example.data.DurableTaskStepEntity
import com.example.services.KavyaAccessibilityService
import com.example.utils.AppResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

/**
 * Dynamic Agent Model.
 * Represents an autonomous executor designated as "Agent 1", "Agent 2", etc.
 * Agents are dynamic, task-based (never hardcoded to a specific app),
 * and allocated on-demand based on the user's goal complexity.
 */
data class DynamicAgent(
    val agentId: String, // e.g. "Agent 1", "Agent 2"
    val assignedTaskId: String,
    val taskObjective: String,
    val requiredInputs: Map<String, String>,
    val availableTools: List<String>,
    val dependencies: List<String> = emptyList(),
    val expectedOutput: String,
    var executionStatus: AgentStatus = AgentStatus.PENDING,
    var errorStatus: String? = null,
    var verificationResult: String? = null
)

enum class AgentStatus {
    PENDING,
    READY,
    RUNNING,
    WAITING_DEPENDENCY,
    COMPLETED,
    FAILED,
    RETRYING,
    CANCELLED
}

enum class GoalStatus {
    PLANNING,
    READY,
    WAITING_APPROVAL,
    EXECUTING,
    COMPLETED,
    FAILED,
    RECOVERY,
    CANCELLED,
    PAUSED
}

enum class TaskNodeStatus {
    PENDING,
    READY,
    RUNNING,
    WAITING,
    COMPLETED,
    FAILED,
    BLOCKED,
    CANCELLED,
    NEEDS_USER_INPUT
}

/**
 * Directed Acyclic Graph (DAG) Task Node representing an atomic verifiable unit of work.
 */
data class GoalTaskNode(
    val taskId: String,
    val parentGoalId: String,
    val assignedAgentId: String,
    val description: String,
    val toolName: String,
    val parameters: Map<String, String>,
    val dependencies: List<String> = emptyList(), // Task IDs that must complete first
    var status: TaskNodeStatus = TaskNodeStatus.PENDING,
    val expectedOutput: String = "",
    var output: String? = null,
    var error: String? = null,
    var retryCount: Int = 0,
    val maxRetries: Int = 2,
    var verificationStatus: String? = null,
    val requiresApproval: Boolean = false
)

/**
 * Complete Goal Definition structure holding the entire orchestration lifecycle.
 */
data class GoalDefinition(
    val goalId: String = UUID.randomUUID().toString(),
    val finalGoal: String,
    val userIntent: String,
    val requiredOutcomes: List<String>,
    val tasks: List<GoalTaskNode>,
    val dependencies: Map<String, List<String>>, // taskId -> parent taskIds
    val agentAssignments: Map<String, String>, // taskId -> agentId
    val availableTools: List<String>,
    val permissionRequirements: List<String>,
    val expectedResults: List<String>,
    val verificationConditions: List<String>,
    val failureRecoveryPlan: String,
    val requiresUserApproval: Boolean,
    var status: GoalStatus = GoalStatus.READY,
    val createdAt: Long = System.currentTimeMillis(),
    var completedAt: Long? = null
)

/**
 * Result of an orchestrator goal run.
 */
data class GoalExecutionResult(
    val goalId: String,
    val success: Boolean,
    val finalGoal: String,
    val summary: String,
    val completedTasks: Int,
    val totalTasks: Int,
    val outputs: Map<String, String>,
    val failedTask: GoalTaskNode? = null,
    val needsApprovalFor: GoalTaskNode? = null
)

/**
 * Decomposes natural language user commands into structured goals, tasks, and dynamic agents.
 */
object DynamicGoalDecomposer {

    private const val TAG = "GoalDecomposer"

    fun decompose(
        userInput: String,
        contextEngine: ContextEngine,
        appResolver: AppResolver? = null
    ): GoalDefinition {
        val trimmed = userInput.trim()
        val lower = trimmed.lowercase(java.util.Locale.ROOT)
        val goalId = "goal_${UUID.randomUUID().toString().take(8)}"

        val isImageAndShare = (lower.contains("photo") || lower.contains("image") || lower.contains("तस्वीर") || lower.contains("फोटो") || lower.contains("इमेज")) &&
                (lower.contains("whatsapp") || lower.contains("व्हाट्सएप") || lower.contains("bhej") || lower.contains("भेज") || lower.contains("send")) &&
                (lower.contains("generate") || lower.contains("banao") || lower.contains("बनाओ") || lower.contains("gemini") || lower.contains("जेमिनी"))

        if (isImageAndShare) {
            val recipient = extractRecipient(trimmed)
            val promptQuery = extractImagePrompt(trimmed)
            val tasks = listOf(
                GoalTaskNode(
                    taskId = "${goalId}_t1",
                    parentGoalId = goalId,
                    assignedAgentId = "Agent 1",
                    description = "Generate requested image: '$promptQuery'",
                    toolName = "generate_image",
                    parameters = mapOf("prompt" to promptQuery),
                    dependencies = emptyList(),
                    status = TaskNodeStatus.READY,
                    expectedOutput = "Generated image file or media resource"
                ),
                GoalTaskNode(
                    taskId = "${goalId}_t2",
                    parentGoalId = goalId,
                    assignedAgentId = "Agent 2",
                    description = "Resolve WhatsApp contact for '$recipient'",
                    toolName = "open_app",
                    parameters = mapOf("app_name" to "WhatsApp", "target" to recipient),
                    dependencies = listOf("${goalId}_t1"),
                    status = TaskNodeStatus.WAITING,
                    expectedOutput = "WhatsApp chat with $recipient identified"
                ),
                GoalTaskNode(
                    taskId = "${goalId}_t3",
                    parentGoalId = goalId,
                    assignedAgentId = "Agent 3",
                    description = "Prepare attachment and confirm sending to $recipient",
                    toolName = "type_text",
                    parameters = mapOf("target" to recipient, "action" to "SEND_IMAGE_CONFIRMATION"),
                    dependencies = listOf("${goalId}_t2"),
                    status = TaskNodeStatus.WAITING,
                    expectedOutput = "User confirmation received and image sent",
                    requiresApproval = true
                )
            )

            return GoalDefinition(
                goalId = goalId,
                finalGoal = "Generate image and send to $recipient via WhatsApp",
                userIntent = "IMAGE_GENERATION_AND_SHARE",
                requiredOutcomes = listOf("Image generated", "Recipient chat resolved", "Image shared with confirmation"),
                tasks = tasks,
                dependencies = mapOf(
                    "${goalId}_t2" to listOf("${goalId}_t1"),
                    "${goalId}_t3" to listOf("${goalId}_t2")
                ),
                agentAssignments = mapOf(
                    "${goalId}_t1" to "Agent 1",
                    "${goalId}_t2" to "Agent 2",
                    "${goalId}_t3" to "Agent 3"
                ),
                availableTools = listOf("generate_image", "open_app", "type_text"),
                permissionRequirements = listOf("android.permission.INTERNET"),
                expectedResults = listOf("Generated image path", "Message delivery verified"),
                verificationConditions = listOf("Image output non-empty", "WhatsApp chat verified"),
                failureRecoveryPlan = "If image generation fails, prompt for alternate description. If contact resolution fails, ask user for exact contact.",
                requiresUserApproval = true
            )
        }

        // 2. Multi-App Chained Actions (Example 8: YouTube -> Video -> Instagram -> Back)
        val isMultiAppChained = (lower.contains("youtube") && lower.contains("instagram")) ||
                (lower.contains("whatsapp") && lower.contains("youtube")) ||
                (lower.contains("chrome") && lower.contains("whatsapp"))

        if (isMultiAppChained) {
            val tasks = mutableListOf<GoalTaskNode>()
            var agentIndex = 1

            if (lower.contains("youtube")) {
                val ytTask = GoalTaskNode(
                    taskId = "${goalId}_t1",
                    parentGoalId = goalId,
                    assignedAgentId = "Agent $agentIndex",
                    description = "Open YouTube and play video",
                    toolName = "open_app",
                    parameters = mapOf("app_name" to "YouTube"),
                    dependencies = emptyList(),
                    status = TaskNodeStatus.READY,
                    expectedOutput = "YouTube opened"
                )
                tasks.add(ytTask)
                agentIndex++
            }

            if (lower.contains("instagram")) {
                val prevId = tasks.lastOrNull()?.taskId
                val instaTask = GoalTaskNode(
                    taskId = "${goalId}_t2",
                    parentGoalId = goalId,
                    assignedAgentId = "Agent $agentIndex",
                    description = "Open Instagram and prepare message",
                    toolName = "open_app",
                    parameters = mapOf("app_name" to "Instagram"),
                    dependencies = if (prevId != null) listOf(prevId) else emptyList(),
                    status = TaskNodeStatus.WAITING,
                    expectedOutput = "Instagram opened",
                    requiresApproval = true
                )
                tasks.add(instaTask)
            }

            return GoalDefinition(
                goalId = goalId,
                finalGoal = "Execute sequential multi-app workflow across applications",
                userIntent = "MULTI_APP_CHAIN",
                requiredOutcomes = tasks.map { it.description },
                tasks = tasks,
                dependencies = tasks.associate { it.taskId to it.dependencies },
                agentAssignments = tasks.associate { it.taskId to it.assignedAgentId },
                availableTools = listOf("open_app", "tap", "type_text"),
                permissionRequirements = emptyList(),
                expectedResults = listOf("All apps navigated and verified"),
                verificationConditions = listOf("Target apps brought to foreground sequentially"),
                failureRecoveryPlan = "If secondary app fails to open, return to primary app and inform user.",
                requiresUserApproval = true
            )
        }

        // 3. Web Research and Document/Summary Generation (Example 9, 14, 22)
        val isResearchAndDocument = (lower.contains("research") || lower.contains("summary") || lower.contains("document") || lower.contains("तुलना")) &&
                (lower.contains("topic") || lower.contains("internet") || lower.contains("sources") || lower.contains("compare"))

        if (isResearchAndDocument) {
            val query = trimmed.replace("(?i)^(?:internet par|topic|research|karo|batao|mujhe|ek|acha|accha)\\s*".toRegex(), "").trim()
            val tasks = listOf(
                GoalTaskNode(
                    taskId = "${goalId}_t1",
                    parentGoalId = goalId,
                    assignedAgentId = "Agent 1",
                    description = "Search multiple authoritative sources on '$query'",
                    toolName = "research_web",
                    parameters = mapOf("query" to query),
                    dependencies = emptyList(),
                    status = TaskNodeStatus.READY,
                    expectedOutput = "Curated multi-source research findings"
                ),
                GoalTaskNode(
                    taskId = "${goalId}_t2",
                    parentGoalId = goalId,
                    assignedAgentId = "Agent 2",
                    description = "Synthesize and verify information consistency across sources",
                    toolName = "semantic_search",
                    parameters = mapOf("query" to query, "content" to "Synthesize research evidence"),
                    dependencies = listOf("${goalId}_t1"),
                    status = TaskNodeStatus.WAITING,
                    expectedOutput = "Verified factual points with citations"
                ),
                GoalTaskNode(
                    taskId = "${goalId}_t3",
                    parentGoalId = goalId,
                    assignedAgentId = "Agent 3",
                    description = "Generate structured document summary and key points",
                    toolName = "memory_create",
                    parameters = mapOf("key" to "Research: $query", "category" to "RESEARCH"),
                    dependencies = listOf("${goalId}_t2"),
                    status = TaskNodeStatus.WAITING,
                    expectedOutput = "Structured document created and persisted"
                )
            )

            return GoalDefinition(
                goalId = goalId,
                finalGoal = "Deep research, comparative analysis, and document summary for: $query",
                userIntent = "RESEARCH_AND_DOCUMENT",
                requiredOutcomes = listOf("Source discovery", "Cross-verification", "Document creation"),
                tasks = tasks,
                dependencies = mapOf(
                    "${goalId}_t2" to listOf("${goalId}_t1"),
                    "${goalId}_t3" to listOf("${goalId}_t2")
                ),
                agentAssignments = mapOf(
                    "${goalId}_t1" to "Agent 1",
                    "${goalId}_t2" to "Agent 2",
                    "${goalId}_t3" to "Agent 3"
                ),
                availableTools = listOf("research_web", "semantic_search", "memory_create"),
                permissionRequirements = listOf("android.permission.INTERNET"),
                expectedResults = listOf("Web search data", "Cross-verified report"),
                verificationConditions = listOf("Non-empty research data", "Logical citations"),
                failureRecoveryPlan = "If web search returns low-confidence data, widen query terms and query alternate sources.",
                requiresUserApproval = false
            )
        }

        // 4. Default: Standard Single or Dual Agent Goal (e.g. Simple App Action, Search, or Message)
        val targetApp = appResolver?.resolve(trimmed)?.matchedApp?.appName ?: "Kavya"
        val singleTask = GoalTaskNode(
            taskId = "${goalId}_t1",
            parentGoalId = goalId,
            assignedAgentId = "Agent 1",
            description = "Execute user goal: '$trimmed'",
            toolName = if (lower.contains("search")) "search_web" else "open_app",
            parameters = mapOf("target" to trimmed, "app_name" to targetApp),
            dependencies = emptyList(),
            status = TaskNodeStatus.READY,
            expectedOutput = "Goal completed"
        )

        return GoalDefinition(
            goalId = goalId,
            finalGoal = trimmed,
            userIntent = "DIRECT_EXECUTION",
            requiredOutcomes = listOf("Execute requested action"),
            tasks = listOf(singleTask),
            dependencies = emptyMap(),
            agentAssignments = mapOf("${goalId}_t1" to "Agent 1"),
            availableTools = listOf("open_app", "search_web", "tap"),
            permissionRequirements = emptyList(),
            expectedResults = listOf("Target action completed"),
            verificationConditions = listOf("Target state reached"),
            failureRecoveryPlan = "If target action fails, analyze error and retry with fallback tool.",
            requiresUserApproval = lower.contains("send") || lower.contains("delete") || lower.contains("bhej")
        )
    }

    private fun extractRecipient(input: String): String {
        val lower = input.lowercase(java.util.Locale.ROOT)
        return when {
            lower.contains("पापा") || lower.contains("papa") -> "Papa"
            lower.contains("मम्मी") || lower.contains("mummy") || lower.contains("mom") -> "Mummy"
            lower.contains("दीदी") || lower.contains("didi") -> "Didi"
            lower.contains("bhai") || lower.contains("भाई") -> "Bhai"
            lower.contains("rohan") || lower.contains("रोहन") -> "Rohan"
            else -> "Contact"
        }
    }

    private fun extractImagePrompt(input: String): String {
        return input.replace("(?i)^(?:gemini se|ek|acchi|photo|generate karo|banao|aur|whatsapp par|papa ko|bhej do)\\s*".toRegex(), "")
            .trim()
            .ifBlank { "Beautiful sunset landscape" }
    }
}

/**
 * Orchestrates the execution of a dynamic GoalDefinition across dynamic agents,
 * executing tools, validating dependencies, verifying outputs, and persisting state.
 */
class DynamicGoalOrchestrator(
    private val context: Context,
    private val androidAgent: AndroidAgent,
    private val appDatabase: AppDatabase = AppDatabase.getDatabase(context),
    private val toolGateway: UniversalToolGateway = UniversalToolGateway.getInstance(context)
) {
    companion object {
        private const val TAG = "GoalOrchestrator"

        private val EXCLUSIVE_UI_TOOLS = setOf(
            "open_app", "close_app", "tap", "type_text", "scroll",
            "swipe", "press_back", "read_screen", "screenshot", "switch_app"
        )
    }

    private val maxConcurrency: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    private val concurrencySemaphore = kotlinx.coroutines.sync.Semaphore(maxConcurrency)
    private val exclusiveUiMutex = kotlinx.coroutines.sync.Mutex()
    private val stateMutex = kotlinx.coroutines.sync.Mutex()

    private val _activeGoal = MutableStateFlow<GoalDefinition?>(null)
    val activeGoal: StateFlow<GoalDefinition?> = _activeGoal.asStateFlow()

    private val _liveAgents = MutableStateFlow<List<DynamicAgent>>(emptyList())
    val liveAgents: StateFlow<List<DynamicAgent>> = _liveAgents.asStateFlow()

    private val _executionLogs = MutableStateFlow<List<String>>(emptyList())
    val executionLogs: StateFlow<List<String>> = _executionLogs.asStateFlow()

    private var activeJob: Job? = null

    /**
     * Starts DAG-aware execution of a GoalDefinition.
     * Independent tasks whose dependencies are satisfied execute concurrently in bounded parallel waves,
     * while exclusive Android UI/accessibility tools are automatically serialized via exclusiveUiMutex.
     * Temporary agents are created on demand and released when the goal finishes.
     */
    suspend fun executeGoal(
        goal: GoalDefinition,
        onMilestone: (String) -> Unit
    ): GoalExecutionResult = withContext(Dispatchers.IO) {
        _activeGoal.value = goal
        goal.status = GoalStatus.EXECUTING
        log("Starting Goal [${goal.goalId}]: '${goal.finalGoal}' with ${goal.tasks.size} dynamic tasks (maxParallel=$maxConcurrency)")
        onMilestone("Goal started: ${goal.finalGoal}")

        // Spawn temporary dynamic agents for this goal
        val tempAgents = goal.tasks.map { task ->
            DynamicAgent(
                agentId = task.assignedAgentId,
                assignedTaskId = task.taskId,
                taskObjective = task.description,
                requiredInputs = task.parameters,
                availableTools = listOf(task.toolName),
                dependencies = task.dependencies,
                expectedOutput = task.expectedOutput,
                executionStatus = if (task.dependencies.isEmpty()) AgentStatus.READY else AgentStatus.WAITING_DEPENDENCY
            )
        }
        _liveAgents.value = tempAgents

        persistGoalToDb(goal)

        val taskOutputs = java.util.concurrent.ConcurrentHashMap<String, String>()
        val tasksById = goal.tasks.associateBy { it.taskId }
        val completedIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        var approvalNeededTask: GoalTaskNode? = null
        var failedTaskNode: GoalTaskNode? = null

        try {
            while (completedIds.size < goal.tasks.size) {
                if (goal.status == GoalStatus.CANCELLED || goal.status == GoalStatus.PAUSED || !isActive) {
                    log("Goal execution stopped (Status: ${goal.status})")
                    return@withContext GoalExecutionResult(
                        goalId = goal.goalId,
                        success = false,
                        finalGoal = goal.finalGoal,
                        summary = "Execution stopped by user",
                        completedTasks = completedIds.size,
                        totalTasks = goal.tasks.size,
                        outputs = taskOutputs.toMap()
                    )
                }

                val remaining = goal.tasks.filter { it.taskId !in completedIds && it.status != TaskNodeStatus.FAILED && it.status != TaskNodeStatus.BLOCKED }
                if (remaining.isEmpty()) break

                val readyWave = remaining.filter { task ->
                    task.dependencies.all { depId -> tasksById[depId]?.status == TaskNodeStatus.COMPLETED }
                }

                if (readyWave.isEmpty()) {
                    remaining.forEach { t ->
                        t.status = TaskNodeStatus.BLOCKED
                        updateAgentStatus(t.taskId, AgentStatus.CANCELLED, "Blocked by unmet dependencies")
                        log("Task [${t.taskId}] blocked: Dependencies not satisfied: ${t.dependencies}")
                    }
                    break
                }

                // Check approval gate before dispatching wave
                val gated = readyWave.firstOrNull { it.requiresApproval }
                if (gated != null) {
                    goal.status = GoalStatus.WAITING_APPROVAL
                    gated.status = TaskNodeStatus.NEEDS_USER_INPUT
                    updateAgentStatus(gated.taskId, AgentStatus.WAITING_DEPENDENCY, "Awaiting user approval")
                    log("Task [${gated.taskId}] requires user confirmation before proceeding with external action.")
                    onMilestone("Awaiting your confirmation for: ${gated.description}")
                    approvalNeededTask = gated
                    break
                }

                if (readyWave.size > 1) {
                    log("Executing parallel wave of ${readyWave.size} independent tasks: ${readyWave.joinToString { it.taskId }}")
                }

                // Execute all ready tasks in this wave concurrently (bounded by Semaphore)
                val waveOutcomes = coroutineScope {
                    readyWave.map { task ->
                        async(Dispatchers.IO) {
                            concurrencySemaphore.withPermit {
                                executeSingleTaskNode(goal, task, completedIds, taskOutputs, onMilestone)
                            }
                        }
                    }.awaitAll()
                }

                val firstFailure = waveOutcomes.firstOrNull { !it.second }?.first
                if (firstFailure != null) {
                    failedTaskNode = firstFailure
                    goal.status = GoalStatus.FAILED
                    break
                }
            }

            if (approvalNeededTask != null) {
                return@withContext GoalExecutionResult(
                    goalId = goal.goalId,
                    success = false,
                    finalGoal = goal.finalGoal,
                    summary = "Awaiting user approval for ${approvalNeededTask!!.description}",
                    completedTasks = completedIds.size,
                    totalTasks = goal.tasks.size,
                    outputs = taskOutputs.toMap(),
                    needsApprovalFor = approvalNeededTask
                )
            }

            if (failedTaskNode != null) {
                return@withContext GoalExecutionResult(
                    goalId = goal.goalId,
                    success = false,
                    finalGoal = goal.finalGoal,
                    summary = "Task '${failedTaskNode!!.description}' failed: ${failedTaskNode!!.error}",
                    completedTasks = completedIds.size,
                    totalTasks = goal.tasks.size,
                    outputs = taskOutputs.toMap(),
                    failedTask = failedTaskNode
                )
            }

            goal.status = GoalStatus.COMPLETED
            goal.completedAt = System.currentTimeMillis()
            persistGoalFinalStatus(goal, completedIds.size, "All ${goal.tasks.size} tasks verified and executed successfully.", null)
            log("Goal [${goal.goalId}] successfully completed all ${goal.tasks.size} tasks!")
            onMilestone("Goal completed successfully.")

            GoalExecutionResult(
                goalId = goal.goalId,
                success = true,
                finalGoal = goal.finalGoal,
                summary = "All ${goal.tasks.size} tasks verified and executed successfully.",
                completedTasks = completedIds.size,
                totalTasks = goal.tasks.size,
                outputs = taskOutputs.toMap()
            )
        } finally {
            // Temporary agents are always released once the goal finishes or pauses
            if (goal.status != GoalStatus.EXECUTING) {
                _liveAgents.value = emptyList()
            }
        }
    }

    private suspend fun executeSingleTaskNode(
        goal: GoalDefinition,
        task: GoalTaskNode,
        completedIds: MutableSet<String>,
        taskOutputs: MutableMap<String, String>,
        onMilestone: (String) -> Unit
    ): Pair<GoalTaskNode, Boolean> {
        task.status = TaskNodeStatus.RUNNING
        updateAgentStatus(task.taskId, AgentStatus.RUNNING)
        log("${task.assignedAgentId} starting '${task.description}' using tool '${task.toolName}'")
        onMilestone("${task.assignedAgentId}: ${task.description}")

        val isExclusiveUi = task.toolName.lowercase() in EXCLUSIVE_UI_TOOLS

        suspend fun runViaGateway(attempt: Int): GatewayToolResult {
            val idempKey = "${goal.goalId}_${task.taskId}"
            return if (isExclusiveUi) {
                exclusiveUiMutex.withLock {
                    toolGateway.execute(
                        toolIdOrName = task.toolName,
                        arguments = task.parameters,
                        context = context,
                        androidAgent = androidAgent,
                        taskId = goal.goalId,
                        agentId = task.assignedAgentId,
                        idempotencyKey = idempKey,
                        userConfirmed = !task.requiresApproval
                    )
                }
            } else {
                toolGateway.execute(
                    toolIdOrName = task.toolName,
                    arguments = task.parameters,
                    context = context,
                    androidAgent = androidAgent,
                    taskId = goal.goalId,
                    agentId = task.assignedAgentId,
                    idempotencyKey = idempKey,
                    userConfirmed = !task.requiresApproval
                )
            }
        }

        var result = runViaGateway(0)
        if (!result.isSuccess && task.retryCount < task.maxRetries) {
            task.retryCount++
            task.status = TaskNodeStatus.READY
            updateAgentStatus(task.taskId, AgentStatus.RETRYING, result.errorDetails)
            log("Retrying task [${task.taskId}] (Attempt ${task.retryCount} of ${task.maxRetries})...")
            result = runViaGateway(task.retryCount)
        }

        if (result.isSuccess) {
            task.status = TaskNodeStatus.COMPLETED
            task.output = result.structuredOutput
            task.verificationStatus = if (task.retryCount > 0) "VERIFIED_PASS_AFTER_RETRY" else "VERIFIED_PASS"
            taskOutputs[task.taskId] = result.structuredOutput
            completedIds.add(task.taskId)
            updateAgentStatus(task.taskId, AgentStatus.COMPLETED, verification = task.verificationStatus)
            log("${task.assignedAgentId} COMPLETED [${task.taskId}]: ${result.structuredOutput.take(80)}")
            updateTaskStepInDb(goal.goalId, task, completedIds.size, goal.tasks.indexOf(task) + 1)
            return task to true
        } else {
            task.status = TaskNodeStatus.FAILED
            task.error = result.errorDetails ?: "Tool execution failed"
            updateAgentStatus(task.taskId, AgentStatus.FAILED, task.error)
            log("${task.assignedAgentId} FAILED [${task.taskId}]: ${task.error}")
            updateTaskStepInDb(goal.goalId, task, completedIds.size, goal.tasks.indexOf(task) + 1)
            return task to false
        }
    }

    private suspend fun updateAgentStatus(
        taskId: String,
        status: AgentStatus,
        error: String? = null,
        verification: String? = null
    ) = stateMutex.withLock {
        _liveAgents.value = _liveAgents.value.map { agent ->
            if (agent.assignedTaskId == taskId) {
                agent.copy(
                    executionStatus = status,
                    errorStatus = error ?: agent.errorStatus,
                    verificationResult = verification ?: agent.verificationResult
                )
            } else agent
        }
    }

    fun stopCurrentExecution(reason: String = "User requested stop") {
        log("Stopping current execution: $reason")
        _activeGoal.value?.status = GoalStatus.CANCELLED
        _liveAgents.value = emptyList()
        activeJob?.cancel()
    }

    fun pauseCurrentExecution() {
        log("Pausing current execution")
        _activeGoal.value?.status = GoalStatus.PAUSED
        activeJob?.cancel()
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        _executionLogs.value = (_executionLogs.value + message).takeLast(100)
    }

    private suspend fun persistGoalToDb(goal: GoalDefinition) = withContext(Dispatchers.IO) {
        try {
            val taskEntity = DurableTaskEntity(
                id = goal.goalId,
                title = goal.finalGoal,
                intentCategory = goal.userIntent,
                status = goal.status.name,
                totalSteps = goal.tasks.size,
                currentStepIndex = 0,
                createdAt = goal.createdAt,
                updatedAt = System.currentTimeMillis()
            )
            appDatabase.taskDao().insertTask(taskEntity)

            val stepEntities = goal.tasks.mapIndexed { index, taskNode ->
                DurableTaskStepEntity(
                    id = taskNode.taskId,
                    taskId = goal.goalId,
                    stepIndex = index + 1,
                    title = taskNode.description,
                    actionType = taskNode.toolName,
                    targetApp = taskNode.parameters["app_name"] ?: taskNode.parameters["target"],
                    param = taskNode.parameters.toString(),
                    status = taskNode.status.name,
                    expectedState = taskNode.expectedOutput,
                    actualState = taskNode.output,
                    result = taskNode.output,
                    failureReason = taskNode.error,
                    timestamp = System.currentTimeMillis()
                )
            }
            appDatabase.taskDao().insertSteps(stepEntities)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist goal: ${e.message}")
        }
    }

    private suspend fun updateTaskStepInDb(
        goalId: String,
        taskNode: GoalTaskNode,
        completedCount: Int,
        stepIndex: Int
    ) = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            appDatabase.taskDao().updateStep(
                DurableTaskStepEntity(
                    id = taskNode.taskId,
                    taskId = goalId,
                    stepIndex = stepIndex.coerceAtLeast(1),
                    title = taskNode.description,
                    actionType = taskNode.toolName,
                    targetApp = taskNode.parameters["app_name"] ?: taskNode.parameters["target"],
                    param = taskNode.parameters.toString(),
                    status = taskNode.status.name,
                    expectedState = taskNode.expectedOutput,
                    actualState = taskNode.output,
                    result = taskNode.output,
                    failureReason = taskNode.error,
                    timestamp = now
                )
            )
            appDatabase.taskDao().updateTaskProgress(
                taskId = goalId,
                status = if (taskNode.status == TaskNodeStatus.FAILED) GoalStatus.FAILED.name else GoalStatus.EXECUTING.name,
                currentStepIndex = completedCount,
                updatedAt = now,
                result = taskNode.output,
                error = taskNode.error
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update step: ${e.message}")
        }
    }

    private suspend fun persistGoalFinalStatus(
        goal: GoalDefinition,
        completedCount: Int,
        summary: String,
        error: String?
    ) = withContext(Dispatchers.IO) {
        try {
            appDatabase.taskDao().updateTaskProgress(
                taskId = goal.goalId,
                status = goal.status.name,
                currentStepIndex = completedCount,
                updatedAt = System.currentTimeMillis(),
                result = summary,
                error = error
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to finalize goal in DB: ${e.message}")
        }
    }
}
