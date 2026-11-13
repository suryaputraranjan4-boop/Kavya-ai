package com.example.orchestrator

import com.example.agent.TaskPhase
import com.example.agent.TaskStateMachine
import com.example.state.KavyaStateManager
import com.example.state.TaskState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Centralized CEO Task Orchestration State Machine.
 * All automation transitions go exclusively through this state machine and sync read-only HUD status.
 */
enum class OrchestrationLifecycleState {
    IDLE,
    USER_COMMAND_RECEIVED,
    API_AUTHENTICATION,
    AI_REASONING,
    PLAN_GENERATION,
    PLAN_VALIDATION,
    WAITING_FOR_PERMISSION,
    WAITING_FOR_CONFIRMATION,
    ACTION_AUTHORIZATION,
    ACTION_EXECUTION,
    SCREEN_OBSERVATION,
    AI_VERIFICATION,
    REPLANNING,
    TASK_PAUSED,
    TASK_COMPLETED,
    TASK_FAILED,
    TASK_CANCELLED
}

data class StateTransitionEvent(
    val timestampMs: Long = System.currentTimeMillis(),
    val fromState: OrchestrationLifecycleState,
    val toState: OrchestrationLifecycleState,
    val taskId: String,
    val sessionId: String,
    val detail: String
)

data class OrchestrationSnapshot(
    val state: OrchestrationLifecycleState = OrchestrationLifecycleState.IDLE,
    val taskId: String = "",
    val sessionId: String = "",
    val userCommand: String = "",
    val providerId: String = "",
    val modelId: String = "",
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
    val stepDescription: String = "",
    val lastActionSummary: String = "",
    val lastVerificationSummary: String = "",
    val lastError: String? = null,
    val retryCount: Int = 0,
    val isPaused: Boolean = false,
    val suspendedDueToNetwork: Boolean = false,
    val transitions: List<StateTransitionEvent> = emptyList(),
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    val isRunning: Boolean
        get() = state !in setOf(
            OrchestrationLifecycleState.IDLE,
            OrchestrationLifecycleState.TASK_COMPLETED,
            OrchestrationLifecycleState.TASK_FAILED,
            OrchestrationLifecycleState.TASK_CANCELLED
        )
}

object TaskOrchestrationStateMachine {
    private val _snapshot = MutableStateFlow(OrchestrationSnapshot())
    val snapshot: StateFlow<OrchestrationSnapshot> = _snapshot.asStateFlow()

    @Synchronized
    fun startTask(
        taskId: String,
        sessionId: String,
        userCommand: String,
        providerId: String,
        modelId: String
    ) {
        val prev = _snapshot.value.state
        val event = StateTransitionEvent(
            fromState = prev,
            toState = OrchestrationLifecycleState.USER_COMMAND_RECEIVED,
            taskId = taskId,
            sessionId = sessionId,
            detail = "Explicit command received: ${userCommand.take(80)}"
        )
        _snapshot.value = OrchestrationSnapshot(
            state = OrchestrationLifecycleState.USER_COMMAND_RECEIVED,
            taskId = taskId,
            sessionId = sessionId,
            userCommand = userCommand,
            providerId = providerId,
            modelId = modelId,
            currentStep = 0,
            totalSteps = 0,
            stepDescription = "Authenticating selected AI provider ($providerId)",
            transitions = listOf(event)
        )
        syncLegacyStateManagers(OrchestrationLifecycleState.USER_COMMAND_RECEIVED, userCommand)
    }

    @Synchronized
    fun transitionTo(
        newState: OrchestrationLifecycleState,
        detail: String = "",
        currentStep: Int? = null,
        totalSteps: Int? = null,
        stepDescription: String? = null,
        lastActionSummary: String? = null,
        lastVerificationSummary: String? = null,
        lastError: String? = null,
        retryCount: Int? = null,
        isPaused: Boolean? = null,
        suspendedDueToNetwork: Boolean? = null
    ) {
        val current = _snapshot.value
        val event = StateTransitionEvent(
            fromState = current.state,
            toState = newState,
            taskId = current.taskId,
            sessionId = current.sessionId,
            detail = detail.ifBlank { newState.name }
        )
        val trimmedHistory = (current.transitions + event).takeLast(60)
        _snapshot.value = current.copy(
            state = newState,
            currentStep = currentStep ?: current.currentStep,
            totalSteps = totalSteps ?: current.totalSteps,
            stepDescription = stepDescription ?: detail.ifBlank { current.stepDescription },
            lastActionSummary = lastActionSummary ?: current.lastActionSummary,
            lastVerificationSummary = lastVerificationSummary ?: current.lastVerificationSummary,
            lastError = if (newState == OrchestrationLifecycleState.TASK_COMPLETED) null else (lastError ?: current.lastError),
            retryCount = retryCount ?: current.retryCount,
            isPaused = isPaused ?: (newState == OrchestrationLifecycleState.TASK_PAUSED),
            suspendedDueToNetwork = suspendedDueToNetwork ?: current.suspendedDueToNetwork,
            transitions = trimmedHistory,
            updatedAtMs = System.currentTimeMillis()
        )
        syncLegacyStateManagers(newState, detail.ifBlank { current.stepDescription })
    }

    @Synchronized
    fun resetToIdle() {
        _snapshot.value = OrchestrationSnapshot(state = OrchestrationLifecycleState.IDLE)
        syncLegacyStateManagers(OrchestrationLifecycleState.IDLE, "Idle")
    }

    private fun syncLegacyStateManagers(state: OrchestrationLifecycleState, detail: String) {
        val mappedTaskState = when (state) {
            OrchestrationLifecycleState.IDLE -> TaskState.IDLE
            OrchestrationLifecycleState.USER_COMMAND_RECEIVED,
            OrchestrationLifecycleState.API_AUTHENTICATION,
            OrchestrationLifecycleState.AI_REASONING,
            OrchestrationLifecycleState.PLAN_GENERATION,
            OrchestrationLifecycleState.PLAN_VALIDATION,
            OrchestrationLifecycleState.REPLANNING -> TaskState.PLANNING
            OrchestrationLifecycleState.WAITING_FOR_PERMISSION,
            OrchestrationLifecycleState.WAITING_FOR_CONFIRMATION,
            OrchestrationLifecycleState.TASK_PAUSED -> TaskState.NEEDS_USER
            OrchestrationLifecycleState.SCREEN_OBSERVATION -> TaskState.LOOKING_AT_SCREEN
            OrchestrationLifecycleState.ACTION_AUTHORIZATION,
            OrchestrationLifecycleState.ACTION_EXECUTION -> TaskState.EXECUTING
            OrchestrationLifecycleState.AI_VERIFICATION -> TaskState.VERIFYING
            OrchestrationLifecycleState.TASK_COMPLETED -> TaskState.COMPLETED
            OrchestrationLifecycleState.TASK_FAILED -> TaskState.FAILED
            OrchestrationLifecycleState.TASK_CANCELLED -> TaskState.CANCELLED
        }
        KavyaStateManager.updateTaskState(mappedTaskState, detail)

        val mappedPhase = when (state) {
            OrchestrationLifecycleState.IDLE -> TaskPhase.IDLE
            OrchestrationLifecycleState.USER_COMMAND_RECEIVED -> TaskPhase.PARSING
            OrchestrationLifecycleState.API_AUTHENTICATION,
            OrchestrationLifecycleState.AI_REASONING -> TaskPhase.UNDERSTANDING
            OrchestrationLifecycleState.PLAN_GENERATION,
            OrchestrationLifecycleState.PLAN_VALIDATION -> TaskPhase.PLANNING
            OrchestrationLifecycleState.REPLANNING -> TaskPhase.RECOVERING
            OrchestrationLifecycleState.WAITING_FOR_PERMISSION,
            OrchestrationLifecycleState.WAITING_FOR_CONFIRMATION,
            OrchestrationLifecycleState.TASK_PAUSED -> TaskPhase.SAFE_STOP
            OrchestrationLifecycleState.ACTION_AUTHORIZATION,
            OrchestrationLifecycleState.ACTION_EXECUTION -> TaskPhase.ACTION_EXECUTION
            OrchestrationLifecycleState.SCREEN_OBSERVATION -> TaskPhase.OBSERVING
            OrchestrationLifecycleState.AI_VERIFICATION -> TaskPhase.VERIFYING
            OrchestrationLifecycleState.TASK_COMPLETED -> TaskPhase.COMPLETED
            OrchestrationLifecycleState.TASK_FAILED -> TaskPhase.FAILED
            OrchestrationLifecycleState.TASK_CANCELLED -> TaskPhase.INTERRUPTED
        }
        TaskStateMachine.update(
            phase = mappedPhase,
            currentStep = detail,
            actionResult = detail
        )
    }
}
