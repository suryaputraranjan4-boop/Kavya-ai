package com.example.agent

import android.graphics.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TaskPhase {
    IDLE,
    PARSING,
    APP_RESOLUTION,
    APP_OPENING,
    SCREEN_ANALYSIS,
    ACTION_EXECUTION,
    ACTION_VERIFICATION,
    NEXT_STEP,
    COMPLETED,
    RECOVERY,
    FAILED,
    INTERRUPTED,
    SLEEP
}

data class LiveTaskState(
    val phase: TaskPhase = TaskPhase.IDLE,
    val currentApp: String = "None",
    val currentScreen: String = "Home",
    val currentTask: String = "None",
    val currentStep: String = "None",
    val stepIndex: Int = 0,
    val totalSteps: Int = 0,
    val detectedTarget: String = "None",
    val targetBounds: Rect? = null,
    val lastAction: String = "None",
    val actionResult: String = "None",
    val lastError: String? = null
)

/**
 * Observable Task State Machine providing real-time transparency into Kavya's execution lifecycle.
 */
object TaskStateMachine {

    private val _state = MutableStateFlow(LiveTaskState())
    val state: StateFlow<LiveTaskState> = _state.asStateFlow()

    fun update(
        phase: TaskPhase? = null,
        currentApp: String? = null,
        currentScreen: String? = null,
        currentTask: String? = null,
        currentStep: String? = null,
        stepIndex: Int? = null,
        totalSteps: Int? = null,
        detectedTarget: String? = null,
        targetBounds: Rect? = null,
        lastAction: String? = null,
        actionResult: String? = null,
        lastError: String? = null
    ) {
        val current = _state.value
        _state.value = current.copy(
            phase = phase ?: current.phase,
            currentApp = currentApp ?: current.currentApp,
            currentScreen = currentScreen ?: current.currentScreen,
            currentTask = currentTask ?: current.currentTask,
            currentStep = currentStep ?: current.currentStep,
            stepIndex = stepIndex ?: current.stepIndex,
            totalSteps = totalSteps ?: current.totalSteps,
            detectedTarget = detectedTarget ?: current.detectedTarget,
            targetBounds = targetBounds ?: current.targetBounds,
            lastAction = lastAction ?: current.lastAction,
            actionResult = actionResult ?: current.actionResult,
            lastError = lastError
        )
    }

    fun reset() {
        _state.value = LiveTaskState()
    }
}
