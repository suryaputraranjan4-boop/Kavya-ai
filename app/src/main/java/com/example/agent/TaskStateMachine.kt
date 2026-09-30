package com.example.agent

import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Task Execution State Machine (Requirement 27).
 * Transitions:
 * IDLE -> PARSING -> PLANNING -> OBSERVING -> NAVIGATING -> EXECUTING -> VERIFYING -> COMPLETED
 * On failure: VERIFYING -> RECOVERING -> (EXECUTING or SAFE_STOP)
 */
enum class TaskPhase {
    IDLE,
    PARSING,
    UNDERSTANDING,
    APP_RESOLUTION,
    APP_OPENING,
    PLANNING,
    OBSERVING,
    NAVIGATING,
    EXECUTING,
    ACTION_EXECUTION,
    VERIFYING,
    RECOVERING,
    COMPLETED,
    FAILED,
    INTERRUPTED,
    SLEEP,
    SAFE_STOP
}

typealias AgentTaskPhase = TaskPhase

data class TaskState(
    val phase: TaskPhase = TaskPhase.IDLE,
    val currentTask: String = "",
    val currentApp: String = "None",
    val currentStep: String = "Idle",
    val stepIndex: Int = 0,
    val totalSteps: Int = 0,
    val detectedTarget: String = "None",
    val targetBounds: Rect? = null,
    val lastAction: String = "None",
    val actionResult: String = "None",
    val lastError: String = "",
    val detailMessage: String = "",
    val recoveryAttempt: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

typealias TaskMachineState = TaskState

object TaskStateMachine {

    private const val TAG = "KavyaTaskStateMachine"

    private val _state = MutableStateFlow(TaskState())
    val state: StateFlow<TaskState> = _state.asStateFlow()
    val currentState: StateFlow<TaskState> get() = state

    fun update(
        phase: TaskPhase = _state.value.phase,
        currentTask: String = _state.value.currentTask,
        currentApp: String = _state.value.currentApp,
        currentStep: String = _state.value.currentStep,
        stepIndex: Int = _state.value.stepIndex,
        totalSteps: Int = _state.value.totalSteps,
        detectedTarget: String = _state.value.detectedTarget,
        targetBounds: Rect? = _state.value.targetBounds,
        lastAction: String = _state.value.lastAction,
        actionResult: String = _state.value.actionResult,
        lastError: String = _state.value.lastError,
        detail: String = _state.value.detailMessage,
        recoveryCount: Int = _state.value.recoveryAttempt
    ) {
        val updated = _state.value.copy(
            phase = phase,
            currentTask = currentTask,
            currentApp = currentApp,
            currentStep = currentStep,
            stepIndex = stepIndex,
            totalSteps = totalSteps,
            detectedTarget = detectedTarget,
            targetBounds = targetBounds,
            lastAction = lastAction,
            actionResult = actionResult,
            lastError = lastError,
            detailMessage = detail,
            recoveryAttempt = recoveryCount,
            timestamp = System.currentTimeMillis()
        )
        _state.value = updated
        Log.i(TAG, "Update: Phase=${phase.name} | App=$currentApp | Step=$stepIndex/$totalSteps | Action=$lastAction | Result=$actionResult")
    }

    fun transitionTo(
        phase: TaskPhase,
        prompt: String = _state.value.currentTask,
        activeApp: String = _state.value.currentApp,
        stepNumber: Int = _state.value.stepIndex,
        totalSteps: Int = _state.value.totalSteps,
        detail: String = "",
        recoveryCount: Int = _state.value.recoveryAttempt
    ) {
        val newState = _state.value.copy(
            phase = phase,
            currentTask = prompt,
            currentApp = activeApp,
            stepIndex = stepNumber,
            totalSteps = totalSteps,
            detailMessage = detail,
            recoveryAttempt = recoveryCount,
            timestamp = System.currentTimeMillis()
        )
        _state.value = newState
        Log.i(TAG, "Phase: ${phase.name} | App: $activeApp | Step: $stepNumber/$totalSteps | $detail")
    }

    fun reset() {
        _state.value = TaskState()
    }
}
