package com.example.agent

import android.content.Context
import android.util.Log
import com.example.data.MemoryManager
import com.example.services.KavyaAccessibilityService
import com.example.state.KavyaStateManager
import com.example.state.TaskState
import kotlinx.coroutines.delay
import java.util.UUID

data class WorkflowStep(
    val stepId: String = UUID.randomUUID().toString(),
    val stepNumber: Int,
    val description: String,
    val actionType: UniversalActionType,
    val target: String = "",
    val query: String = "",
    val text: String = "",
    val ordinalIndex: Int = 0,
    val expectedPackage: String? = null,
    val verificationCriteria: String = "",
    val maxRetries: Int = 2
)

data class WorkflowStepExecutionResult(
    val success: Boolean,
    val message: String,
    val perceptionAfterStep: ScreenPerception? = null
)

data class WorkflowPlan(
    val workflowId: String = UUID.randomUUID().toString(),
    val userGoal: String,
    val targetApp: String,
    val steps: List<WorkflowStep>
)

data class WorkflowResult(
    val success: Boolean,
    val goal: String,
    val completedSteps: Int,
    val totalSteps: Int,
    val finalMessage: String,
    val error: String? = null
)

/**
 * Requirement 12: Real Workflow Engine.
 * Implements:
 * - plan()
 * - observe()
 * - execute()
 * - verify()
 * - recover()
 * - complete()
 *
 * Example workflow:
 * Open YouTube -> Verify YouTube opened -> Find Search -> Click Search ->
 * Verify search field -> Type query -> Submit -> Verify results ->
 * Find second result -> Click -> Verify destination.
 */
class WorkflowEngine(
    private val screenInspector: ScreenInspector = ScreenInspector(),
    private val verificationEngine: VerificationEngine = VerificationEngine(screenInspector),
    private val taskPlanner: TaskPlanner = TaskPlanner()
) {

    private val recoveryManager: RecoveryManager = RecoveryManager(screenInspector)
    private val checkpointManager = CheckpointManager()
    private val recoveryBudget = RecoveryBudget()

    companion object {
        private const val TAG = "KavyaWorkflowEngine"
    }

    /**
     * 1. PLAN: Deconstructs natural language goal into atomic verifiable steps.
     */
    fun plan(userGoal: String, context: Context): WorkflowPlan {
        KavyaStateManager.updateTaskState(TaskState.PLANNING, "Planning workflow for: $userGoal")
        val contextEngine = ContextEngine()
        val taskPlan = taskPlanner.createPlan(userGoal, contextEngine)
        if (taskPlan != null && taskPlan.steps.isNotEmpty()) {
            val workflowSteps = taskPlan.steps.mapIndexed { idx, step ->
                WorkflowStep(
                    stepNumber = idx + 1,
                    description = step.spokenAnnouncement.ifBlank { "${step.actionType} on ${step.targetAppOrUrl.ifBlank { step.param }}" },
                    actionType = step.actionType,
                    target = step.targetAppOrUrl.ifBlank { step.param },
                    text = step.param,
                    query = step.param,
                    ordinalIndex = step.ordinalIndex,
                    expectedPackage = if (step.actionType == UniversalActionType.OPEN_APP) {
                        val resolved = com.example.utils.AppResolver(context).resolve(step.targetAppOrUrl)
                        resolved.matchedApp?.packageName
                    } else null,
                    verificationCriteria = step.expectedOutcome.ifBlank { "Action ${step.actionType} completed" }
                )
            }
            return WorkflowPlan(
                userGoal = userGoal,
                targetApp = taskPlan.targetAppName.ifBlank { "System" },
                steps = workflowSteps
            )
        }

        // Generic fallback: resolve target app and create atomic step
        val lower = userGoal.lowercase(java.util.Locale.ROOT).trim()
        val app = extractAppFromGoal(lower) ?: "App"
        val steps = listOf(
            WorkflowStep(
                stepNumber = 1,
                description = "Open $app",
                actionType = UniversalActionType.OPEN_APP,
                target = app,
                verificationCriteria = "$app UI presented to user"
            )
        )
        return WorkflowPlan(userGoal = userGoal, targetApp = app, steps = steps)
    }

    /**
     * 2. OBSERVE: Inspects real active screen perception.
     */
    fun observe(expectedPackage: String? = null): ScreenPerception {
        val perception = screenInspector.inspectScreen()
        Log.d(TAG, "OBSERVE: Foreground=${perception.foregroundPackage}, Elements=${perception.elementSummaries.size}")
        return perception
    }

    /**
     * 3. EXECUTE: Dispatches physical accessibility interaction.
     */
    suspend fun execute(step: WorkflowStep, context: Context): WorkflowStepExecutionResult {
        KavyaStateManager.updateTaskState(TaskState.EXECUTING, "Executing: ${step.description}")
        val service = KavyaAccessibilityService.instance

        if (step.actionType == UniversalActionType.OPEN_APP) {
            val appResolver = com.example.utils.AppResolver(context)
            val res = appResolver.resolve(step.target)
            val targetPkg = res.matchedApp?.packageName ?: step.expectedPackage ?: step.target
            val intent = context.packageManager.getLaunchIntentForPackage(targetPkg)
            val launched = if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else false
            delay(300)
            val perception = observe(step.expectedPackage ?: targetPkg)
            return WorkflowStepExecutionResult(
                success = launched,
                message = if (launched) "Launched ${step.target}" else "Failed to find or launch ${step.target}",
                perceptionAfterStep = perception
            )
        }

        if (service == null) {
            return WorkflowStepExecutionResult(
                success = false,
                message = "Accessibility Service is not enabled in Android Settings.",
                perceptionAfterStep = null
            )
        }

        val success = when (step.actionType) {
            UniversalActionType.TAP -> {
                service.clickNodeByText(step.target)
            }
            UniversalActionType.SELECT, UniversalActionType.SELECT_RESULT -> {
                val node = service.findOrdinalContentNode(null, step.ordinalIndex)
                if (node != null) {
                    service.clickNode(node)
                } else {
                    service.clickNodeByText(step.target)
                }
            }
            UniversalActionType.TYPE -> {
                if (step.target.isNotBlank()) {
                    service.typeInNodeByText(step.target, step.text)
                } else {
                    service.typeInFocusedNode(step.text)
                }
            }
            UniversalActionType.SEARCH -> {
                service.performAppSearch(step.text.ifBlank { step.query })
            }
            UniversalActionType.SUBMIT -> {
                service.clickSearchOrSubmitButton()
            }
            UniversalActionType.SCROLL -> {
                service.scroll("forward")
            }
            UniversalActionType.BACK -> {
                service.performGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            }
            UniversalActionType.HOME -> {
                service.performGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            }
            UniversalActionType.PLAY -> {
                val node = service.findOrdinalContentNode(null, step.ordinalIndex)
                if (node != null) {
                    service.clickNode(node)
                } else {
                    val root = service.rootInActiveWindow
                    val playBtn = if (root != null) screenInspector.findTargetNode("play", "PLAY_BUTTON") else null
                    if (playBtn != null) service.clickNode(playBtn) else service.clickNodeByText("play")
                }
            }
            UniversalActionType.CALL, UniversalActionType.MAKE_PHONE_CALL, UniversalActionType.MAKE_WHATSAPP_CALL -> {
                val root = service.rootInActiveWindow
                val callBtn = if (root != null) service.findAudioCallButton(root) else null
                if (callBtn != null) service.clickNode(callBtn) else (service.clickNodeByText("Call") || service.clickNodeByText("Voice call"))
            }
            UniversalActionType.SEND_MESSAGE, UniversalActionType.SEND_SMS, UniversalActionType.SEND_WHATSAPP_MESSAGE -> {
                val root = service.rootInActiveWindow
                val sendBtn = if (root != null) service.findSubmitButton(root, "Send") else null
                if (sendBtn != null) service.clickNode(sendBtn) else (service.clickNodeByText("Send") || service.clickNodeByText("भेजें"))
            }
            else -> false
        }

        delay(200)
        val perception = observe(step.expectedPackage)
        return WorkflowStepExecutionResult(
            success = success,
            message = if (success) "Executed ${step.description}" else "Action execution failed for ${step.description}",
            perceptionAfterStep = perception
        )
    }

    /**
     * 4. VERIFY: Evaluates whether expected post-condition was achieved.
     */
    suspend fun verify(step: WorkflowStep, perception: ScreenPerception): Boolean {
        KavyaStateManager.updateTaskState(TaskState.VERIFYING, "Verifying: ${step.verificationCriteria}")
        val criteriaLower = step.verificationCriteria.lowercase()

        // Verify package using package alias matching
        if (step.expectedPackage != null && perception.foregroundPackage != "none" && perception.foregroundPackage != "unknown") {
            if (screenInspector.freshRecognizer.isMatchingPackage(perception.foregroundPackage, step.expectedPackage)) {
                return true
            }
        }

        // Verify keywords in element summaries
        val matchingElements = perception.elementSummaries.filter { summary ->
            criteriaLower.split(" ").any { kw -> kw.length > 3 && summary.lowercase().contains(kw) }
        }

        if (matchingElements.isNotEmpty()) {
            return true
        }

        // Check search field availability
        if (criteriaLower.contains("search field") || criteriaLower.contains("edit text")) {
            if (perception.searchFieldAvailable) return true
        }

        // Check content results
        if (criteriaLower.contains("result") || criteriaLower.contains("feed")) {
            if (perception.contentResultsCount > 0) return true
        }

        return false
    }

    /**
     * 5. RECOVER: Executes fallback or retry recovery action using RecoveryManager.
     */
    suspend fun recover(step: WorkflowStep, failureReason: String, context: Context): Boolean {
        KavyaStateManager.updateTaskState(TaskState.RETRYING, "Recovering step ${step.stepNumber}: $failureReason")
        Log.w(TAG, "RECOVER: Attempting recovery for step '${step.description}'. Reason: $failureReason")
        val service = KavyaAccessibilityService.instance ?: return false

        val freshScreen = screenInspector.freshRecognizer.acquireFreshScreen(waitForStability = true)
        val taskStep = TaskStep(
            id = step.stepNumber,
            actionType = step.actionType,
            targetAppOrUrl = step.target,
            param = step.text.ifBlank { step.query },
            expectedOutcome = step.verificationCriteria
        )

        val strategy = recoveryManager.planRecovery(
            currentScreen = freshScreen,
            step = taskStep,
            targetApp = step.target,
            targetPackage = step.expectedPackage ?: step.target,
            budget = recoveryBudget,
            history = emptyList(),
            checkpointManager = checkpointManager
        )

        if (strategy.level != RecoveryLevel.LEVEL_5_SAFE_STOP) {
            recoveryBudget.recordAttempt(strategy.level)
            val dispatched = recoveryManager.executeRecovery(strategy, context, taskStep, checkpointManager)
            delay(300)
            if (dispatched) return true
        }

        // Fallback strategies:
        service.scroll("forward")
        delay(200)

        val targetQuery = step.target
        if (targetQuery.isNotBlank()) {
            val retried = service.clickNodeByText(targetQuery)
            if (retried) return true
        }

        service.performGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        delay(150)
        return false
    }

    /**
     * 6. COMPLETE: Concludes workflow and updates persistent memory.
     */
    fun complete(result: WorkflowResult, context: Context): WorkflowResult {
        KavyaStateManager.updateTaskState(
            if (result.success) TaskState.COMPLETED else TaskState.FAILED,
            result.finalMessage
        )
        MemoryManager.completeWorkflow(result.success)
        Log.i(TAG, "WORKFLOW COMPLETE: Success=${result.success}, Goal='${result.goal}', Steps=${result.completedSteps}/${result.totalSteps}")
        return result
    }

    /**
     * Executes end-to-end multi-step workflow.
     */
    suspend fun runWorkflow(
        userGoal: String,
        context: Context,
        onProgress: ((String) -> Unit)? = null
    ): WorkflowResult {
        val plan = plan(userGoal, context)
        MemoryManager.startWorkflow(plan.workflowId, userGoal, plan.targetApp, plan.steps.size)
        onProgress?.invoke("Created plan with ${plan.steps.size} steps for '${plan.userGoal}'")

        var completed = 0
        for ((index, step) in plan.steps.withIndex()) {
            onProgress?.invoke("Step ${step.stepNumber}/${plan.steps.size}: ${step.description}")
            MemoryManager.updateWorkflowStep(index + 1, step.description)

            var stepPassed = false
            for (attempt in 1..step.maxRetries) {
                val execResult = execute(step, context)
                val currentPerception = execResult.perceptionAfterStep ?: observe(step.expectedPackage)
                val verified = verify(step, currentPerception)

                if (execResult.success && verified) {
                    stepPassed = true
                    onProgress?.invoke("✓ Verified: ${step.description}")
                    break
                } else {
                    onProgress?.invoke("⚠ Step ${step.stepNumber} attempt $attempt unverified. Trying recovery...")
                    val recovered = recover(step, execResult.message, context)
                    if (recovered) {
                        stepPassed = true
                        break
                    }
                }
            }

            if (stepPassed) {
                completed++
            } else {
                val errorMsg = "Workflow halted at step ${step.stepNumber}: ${step.description}"
                return complete(
                    WorkflowResult(
                        success = false,
                        goal = userGoal,
                        completedSteps = completed,
                        totalSteps = plan.steps.size,
                        finalMessage = errorMsg,
                        error = errorMsg
                    ),
                    context
                )
            }
        }

        val successMsg = "Workflow successfully completed: '${plan.userGoal}' ($completed/${plan.steps.size} steps)"
        return complete(
            WorkflowResult(
                success = true,
                goal = userGoal,
                completedSteps = completed,
                totalSteps = plan.steps.size,
                finalMessage = successMsg
            ),
            context
        )
    }

    private fun extractQueryAfterKeyword(text: String, keywords: List<String>): String? {
        for (kw in keywords) {
            val idx = text.indexOf(kw)
            if (idx != -1) {
                val remainder = text.substring(idx + kw.length).trim()
                val clean = remainder.removePrefix("for").removePrefix("about").removePrefix("on").trim()
                if (clean.isNotBlank()) return clean
            }
        }
        return null
    }

    private fun extractAppFromGoal(text: String): String? {
        val candidates = listOf("YouTube", "WhatsApp", "Chrome", "Camera", "Settings", "Calendar", "Spotify", "Maps", "Free Fire")
        for (app in candidates) {
            if (text.contains(app, ignoreCase = true)) return app
        }
        return null
    }
}
