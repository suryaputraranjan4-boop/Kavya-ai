package com.example.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.ui.components.VoiceState

/**
 * Requirement 18: Real Task State Machine
 * IDLE -> LISTENING -> UNDERSTANDING -> PLANNING -> EXECUTING -> VERIFYING -> COMPLETED
 * Failure / Recovery States: WAITING, RETRYING, NEEDS_USER, FAILED, CANCELLED
 */
enum class TaskState {
    IDLE,
    LISTENING,
    UNDERSTANDING,
    PLANNING,
    EXECUTING,
    VERIFYING,
    COMPLETED,
    WAITING,
    RETRYING,
    NEEDS_USER,
    FAILED,
    CANCELLED
}

data class KavyaGlobalState(
    val conversationState: String = "IDLE",
    val voiceState: VoiceState = VoiceState.IDLE,
    val taskState: TaskState = TaskState.IDLE,
    val taskStateDetail: String? = null,
    val cgiState: String = "IDLE",
    val currentApp: String? = null,
    val currentScreen: String? = null,
    val currentWebsite: String? = null,
    val currentFile: String? = null,
    val currentTask: String? = null,
    val currentStep: String? = null,
    val activeTool: String? = null,
    val activeAPI: String? = null,
    val memoryContext: String = "",
    val lastAction: String? = null,
    val lastResult: String? = null,
    val errorState: String? = null,
    val geminiRequestState: String = "INACTIVE"
)

object KavyaStateManager {
    private val _state = MutableStateFlow(KavyaGlobalState())
    val state = _state.asStateFlow()

    fun updateTaskState(ts: TaskState, detail: String? = null) {
        _state.value = _state.value.copy(
            taskState = ts,
            taskStateDetail = detail,
            currentStep = detail ?: _state.value.currentStep
        )
    }

    fun updateVoiceState(vs: VoiceState) {
        val mappedTaskState = when (vs) {
            VoiceState.LISTENING -> TaskState.LISTENING
            VoiceState.THINKING -> TaskState.UNDERSTANDING
            VoiceState.SPEAKING -> if (_state.value.taskState == TaskState.EXECUTING) TaskState.EXECUTING else TaskState.COMPLETED
            VoiceState.IDLE -> if (_state.value.taskState == TaskState.COMPLETED || _state.value.taskState == TaskState.FAILED) _state.value.taskState else TaskState.IDLE
            else -> _state.value.taskState
        }
        _state.value = _state.value.copy(
            voiceState = vs,
            taskState = mappedTaskState,
            cgiState = vs.name,
            conversationState = if (vs == VoiceState.SPEAKING || vs == VoiceState.THINKING) "ACTIVE" else "IDLE"
        )
    }

    fun updateContext(app: String? = null, screen: String? = null, task: String? = null) {
        _state.value = _state.value.copy(
            currentApp = app ?: _state.value.currentApp,
            currentScreen = screen ?: _state.value.currentScreen,
            currentTask = task ?: _state.value.currentTask
        )
    }

    fun updateAction(tool: String? = null, action: String? = null, result: String? = null) {
        _state.value = _state.value.copy(
            activeTool = tool,
            lastAction = action ?: _state.value.lastAction,
            lastResult = result ?: _state.value.lastResult
        )
    }
    
    fun setGeminiState(requestState: String, error: String? = null) {
        _state.value = _state.value.copy(
            geminiRequestState = requestState,
            errorState = error ?: _state.value.errorState
        )
    }
}
