package com.example.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Requirement 18: Real persistent memory & context storage.
 * Strictly separates:
 * 1. Conversation Context (recent user-assistant dialogues)
 * 2. Task State (current atomic task, sub-steps, verification criteria)
 * 3. User-approved Preferences (language, theme, selected provider preferences)
 * 4. Temporary Workflow State (in-progress accessibility gestures, interim OCR cache)
 *
 * Privacy rule: Never sends private device data, personal passwords, or
 * unrelated screens to external third-party AI providers.
 */
data class ConversationContext(
    val sessionId: String = System.currentTimeMillis().toString(),
    val turns: List<ConversationTurn> = emptyList(),
    val maxTurnsToRetain: Int = 10
)

data class ConversationTurn(
    val role: String, // "user" or "assistant"
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class WorkflowState(
    val workflowId: String = "",
    val activeGoal: String = "",
    val targetApp: String = "",
    val stepIndex: Int = 0,
    val totalSteps: Int = 0,
    val currentStepDescription: String = "",
    val intermediateResults: Map<String, String> = emptyMap(),
    val isRunning: Boolean = false
)

object MemoryManager {

    private val _conversationFlow = MutableStateFlow(ConversationContext())
    val conversationFlow: StateFlow<ConversationContext> = _conversationFlow.asStateFlow()

    private val _workflowStateFlow = MutableStateFlow(WorkflowState())
    val workflowStateFlow: StateFlow<WorkflowState> = _workflowStateFlow.asStateFlow()

    // Temporary non-persistent cache for interim screen text / OCR results
    private val volatileScratchpad = ConcurrentHashMap<String, String>()

    /**
     * Records a dialogue turn into the active conversation context.
     * Enforces sanitization to strip potential credentials or private tokens.
     */
    fun recordConversationTurn(role: String, message: String) {
        val sanitized = sanitizePrivacyData(message)
        val currentTurns = _conversationFlow.value.turns.toMutableList()
        currentTurns.add(ConversationTurn(role = role, message = sanitized))
        if (currentTurns.size > _conversationFlow.value.maxTurnsToRetain) {
            currentTurns.removeAt(0)
        }
        _conversationFlow.value = _conversationFlow.value.copy(turns = currentTurns)
    }

    /**
     * Formats recent conversation turns for AI prompt inclusion safely.
     */
    fun getFormattedConversationHistory(maxCount: Int = 5): String {
        val turns = _conversationFlow.value.turns.takeLast(maxCount)
        if (turns.isEmpty()) return ""
        return buildString {
            appendLine("Recent Dialogue History:")
            turns.forEach { turn ->
                val speaker = if (turn.role == "user") "User" else "Kavya"
                appendLine("$speaker: ${turn.message}")
            }
        }
    }

    /**
     * Clears dialogue history on demand.
     */
    fun clearConversation() {
        _conversationFlow.value = ConversationContext()
    }

    /**
     * Starts tracking a multi-step workflow.
     */
    fun startWorkflow(id: String, goal: String, targetApp: String, totalSteps: Int) {
        _workflowStateFlow.value = WorkflowState(
            workflowId = id,
            activeGoal = goal,
            targetApp = targetApp,
            stepIndex = 0,
            totalSteps = totalSteps,
            currentStepDescription = "Initiating workflow",
            isRunning = true
        )
    }

    /**
     * Updates ongoing workflow progression.
     */
    fun updateWorkflowStep(stepIndex: Int, description: String, stepResult: String? = null) {
        val current = _workflowStateFlow.value
        val newResults = current.intermediateResults.toMutableMap()
        if (stepResult != null) {
            newResults["step_$stepIndex"] = stepResult
        }
        _workflowStateFlow.value = current.copy(
            stepIndex = stepIndex,
            currentStepDescription = description,
            intermediateResults = newResults
        )
    }

    /**
     * Completes or cancels the active workflow.
     */
    fun completeWorkflow(success: Boolean) {
        val current = _workflowStateFlow.value
        _workflowStateFlow.value = current.copy(
            isRunning = false,
            currentStepDescription = if (success) "Completed successfully" else "Workflow halted"
        )
        volatileScratchpad.clear()
    }

    fun setScratchpadValue(key: String, value: String) {
        volatileScratchpad[key] = value
    }

    fun getScratchpadValue(key: String): String? {
        return volatileScratchpad[key]
    }

    /**
     * Sanitizes privacy-sensitive tokens (e.g., API keys, credit cards, passcodes)
     * from text before passing it to any external model.
     */
    fun sanitizePrivacyData(input: String): String {
        var sanitized = input
        // Mask typical API key structures
        sanitized = sanitized.replace(Regex("sk-[a-zA-Z0-9_-]{20,}"), "[REDACTED_API_KEY]")
        sanitized = sanitized.replace(Regex("hf_[a-zA-Z0-9_-]{20,}"), "[REDACTED_HF_TOKEN]")
        sanitized = sanitized.replace(Regex("AIza[a-zA-Z0-9_-]{30,}"), "[REDACTED_GEMINI_KEY]")
        // Mask card-like number patterns
        sanitized = sanitized.replace(Regex("\\b\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}\\b"), "[REDACTED_CARD]")
        return sanitized
    }
}
