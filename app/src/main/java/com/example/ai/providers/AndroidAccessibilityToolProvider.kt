package com.example.ai.providers

import com.example.agent.AndroidAgent
import com.example.agent.TaskPlan
import com.example.agent.TaskStep
import com.example.agent.UniversalActionType

/**
 * Android Accessibility Tool Provider — The "Hands" of Kavya.
 * Responsible for physical Android execution (tapping, typing, scrolling, launching apps)
 * strictly as directed by the Gemini main orchestrator.
 * Never acts as the AI brain.
 */
class AndroidAccessibilityToolProvider(
    private val androidAgent: AndroidAgent
) : ToolProvider {

    companion object {
        const val TOOL_ID = "accessibility_hands"
    }

    override val toolId: String = TOOL_ID
    override val displayName: String = "Android Accessibility (Execution Layer / Hands)"

    override suspend fun execute(action: String, params: Map<String, String>): ToolExecutionResult {
        val actionType = try {
            UniversalActionType.valueOf(action.uppercase())
        } catch (_: Exception) {
            UniversalActionType.SYSTEM_CONTROL
        }

        val step = TaskStep(
            id = 1,
            actionType = actionType,
            targetAppOrUrl = params["target"] ?: "",
            param = params["param"] ?: params["query"] ?: params["text"] ?: "",
            recipient = params["recipient"] ?: "",
            messageText = params["message"] ?: "",
            ordinalIndex = params["index"]?.toIntOrNull() ?: 0,
            spokenAnnouncement = params["spoken_message"] ?: ""
        )

        val plan = TaskPlan(
            originalPrompt = params["prompt"] ?: "Automated Action",
            targetAppName = step.targetAppOrUrl,
            steps = listOf(step),
            isMultiStep = false
        )

        val startTime = System.currentTimeMillis()
        val outcome = androidAgent.executeTaskPlan(plan)
        val latency = System.currentTimeMillis() - startTime
        val failureReason = outcome.failureReason ?: "Action failed"

        RuntimeActivityTracker.logEvent(
            context = null,
            providerId = "accessibility_hands",
            model = "android-accessibility",
            task = "Physical Action: ${step.actionType} (${step.targetAppOrUrl})",
            success = outcome.success,
            latencyMs = latency,
            errorCategory = if (!outcome.success) failureReason else null
        )

        return ToolExecutionResult(
            success = outcome.success,
            output = outcome.finalSpokenMessage.ifBlank { if (outcome.success) "Action succeeded." else failureReason },
            rawData = outcome,
            error = if (!outcome.success) failureReason else null
        )
    }
}
