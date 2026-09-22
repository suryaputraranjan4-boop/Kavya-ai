package com.example.agent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

/**
 * Requirement 14: Strict Task State Machine
 * RECEIVED -> PARSED -> PLANNED -> EXECUTING -> VERIFYING -> SUCCESS / FAILED / BLOCKED / CANCELLED
 * Allowed transitions are strictly enforced. Direct jump from RECEIVED to SUCCESS is invalid.
 */
enum class ExecutionPhase {
    RECEIVED,
    PARSED,
    PLANNED,
    EXECUTING,
    VERIFYING,
    SUCCESS,
    FAILED,
    BLOCKED,
    CANCELLED
}

/**
 * Requirement 19: Command Context Leak Prevention
 * Each task has an isolated, self-contained execution context.
 */
data class TaskExecutionContext(
    val taskId: String = UUID.randomUUID().toString(),
    val rawPrompt: String,
    val category: CommandCategory,
    val targetApp: String? = null,
    val query: String? = null,
    val actionParam: String? = null,
    var currentPhase: ExecutionPhase = ExecutionPhase.RECEIVED,
    var verifiedPackage: String? = null,
    var errorMessage: String? = null,
    var spokenConfirmation: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Requirement 13: Real Execution Event Bus
 * Connects Voice + UI + Automation with real, verified device events.
 * No fake statuses, no ghost narration.
 */
sealed class ActionEvent {
    data class TaskReceived(val context: TaskExecutionContext) : ActionEvent()
    data class TaskParsed(val context: TaskExecutionContext) : ActionEvent()
    data class TaskPlanned(val context: TaskExecutionContext, val stepsCount: Int) : ActionEvent()
    data class ActionStarted(val context: TaskExecutionContext, val stepDescription: String) : ActionEvent()
    data class ActionProgress(val context: TaskExecutionContext, val currentStep: Int, val totalSteps: Int, val statusText: String) : ActionEvent()
    data class ActionVerifying(val context: TaskExecutionContext, val expectedPackage: String) : ActionEvent()
    data class ActionSuccess(val context: TaskExecutionContext, val verifiedPackage: String, val spokenMessage: String) : ActionEvent()
    data class ActionFailed(val context: TaskExecutionContext, val errorReason: String, val spokenMessage: String) : ActionEvent()
    data class ActionBlocked(val context: TaskExecutionContext, val reason: String, val spokenMessage: String) : ActionEvent()
    data class TaskCancelled(val context: TaskExecutionContext, val reason: String) : ActionEvent()
}

object ActionEventBus {
    private val _events = MutableSharedFlow<ActionEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ActionEvent> = _events.asSharedFlow()

    private var activeContext: TaskExecutionContext? = null

    /**
     * Requirement 18: Stop Cascade
     * If a previous task was unfinished, safely cancel it when a new user command arrives.
     */
    @Synchronized
    fun startNewTask(
        rawPrompt: String,
        category: CommandCategory,
        targetApp: String? = null,
        query: String? = null,
        actionParam: String? = null
    ): TaskExecutionContext {
        activeContext?.let { old ->
            if (old.currentPhase in listOf(
                    ExecutionPhase.RECEIVED,
                    ExecutionPhase.PARSED,
                    ExecutionPhase.PLANNED,
                    ExecutionPhase.EXECUTING,
                    ExecutionPhase.VERIFYING
                )
            ) {
                old.currentPhase = ExecutionPhase.CANCELLED
                _events.tryEmit(ActionEvent.TaskCancelled(old, "Superseded by new command: $rawPrompt"))
            }
        }

        val newCtx = TaskExecutionContext(
            rawPrompt = rawPrompt,
            category = category,
            targetApp = targetApp,
            query = query,
            actionParam = actionParam,
            currentPhase = ExecutionPhase.RECEIVED
        )
        activeContext = newCtx
        _events.tryEmit(ActionEvent.TaskReceived(newCtx))
        return newCtx
    }

    @Synchronized
    fun transitionTo(context: TaskExecutionContext, nextPhase: ExecutionPhase, event: ActionEvent? = null) {
        val current = context.currentPhase
        val isValid = when (nextPhase) {
            ExecutionPhase.PARSED -> current == ExecutionPhase.RECEIVED
            ExecutionPhase.PLANNED -> current == ExecutionPhase.PARSED
            ExecutionPhase.EXECUTING -> current == ExecutionPhase.PLANNED || current == ExecutionPhase.PARSED
            ExecutionPhase.VERIFYING -> current == ExecutionPhase.EXECUTING
            ExecutionPhase.SUCCESS -> current == ExecutionPhase.VERIFYING || current == ExecutionPhase.EXECUTING
            ExecutionPhase.FAILED -> current != ExecutionPhase.SUCCESS
            ExecutionPhase.BLOCKED -> current != ExecutionPhase.SUCCESS
            ExecutionPhase.CANCELLED -> current != ExecutionPhase.SUCCESS
            ExecutionPhase.RECEIVED -> false
        }

        if (!isValid) {
            android.util.Log.w("ActionEventBus", "Invalid state transition from $current to $nextPhase for task ${context.taskId}")
            return
        }

        context.currentPhase = nextPhase
        if (event != null) {
            _events.tryEmit(event)
        }
    }

    fun getActiveContext(): TaskExecutionContext? = activeContext
}
