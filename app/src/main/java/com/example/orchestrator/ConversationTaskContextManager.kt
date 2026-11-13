package com.example.orchestrator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Sections 3, 15 & 16: Coordinated Conversation & Task Execution Context Manager.
 *
 * Maintains coordinated state for:
 * - Conversation context & recent turns
 * - Current user request, current task, and current step
 * - Previous screen observations, tool results, and verification records
 * - User corrections & pending confirmations
 * - Pronoun / follow-up reference resolution ("wahan", "usko", "phir se", "next", "ye wala")
 * - Truthful, concise user-visible progress updates
 */
data class StepVerificationRecord(
    val stepNumber: Int,
    val actionType: OrchestratorActionType,
    val expectedOutcome: String,
    val observedOutcome: String,
    val confidenceScore: Double,
    val observationMode: String,
    val status: OrchestratorVerificationStatus,
    val diagnosticExplanation: String,
    val timestampMs: Long = System.currentTimeMillis()
)

data class CoordinatedTaskContext(
    val activeTaskId: String = "",
    val currentUserRequest: String = "",
    val resolvedUserRequest: String = "",
    val currentStepNumber: Int = 0,
    val totalPlannedSteps: Int = 0,
    val currentProgressMessage: String = "",
    val lastTargetApp: String = "",
    val lastForegroundPackage: String = "",
    val lastSearchQuery: String = "",
    val lastTargetDescription: String = "",
    val lastCommand: String = "",
    val isLastTaskCompleted: Boolean = false,
    val verificationHistory: List<StepVerificationRecord> = emptyList(),
    val userCorrections: List<String> = emptyList()
)

object ConversationTaskContextManager {
    private val _context = MutableStateFlow(CoordinatedTaskContext())
    val context: StateFlow<CoordinatedTaskContext> = _context.asStateFlow()

    private val followUpPronouns = listOf(
        "wahan", "wahaan", "usko", "use", "usme", "usmein", "us par", "us pe",
        "phir se", "fir se", "dobara", "repeat", "again",
        "next", "agla", "agle", "ye wala", "yeh wala", "is wala", "isko",
        "there", "that one", "this one", "do it again", "same thing"
    )

    /**
     * Checks whether a user utterance contains contextual follow-up references
     * ("wahan", "usko", "phir se", "next", "ye wala").
     */
    fun isContextualFollowUp(rawInput: String): Boolean {
        val lower = rawInput.trim().lowercase(Locale.ROOT)
        if (lower.isEmpty()) return false
        return followUpPronouns.any { token ->
            lower == token ||
                lower.startsWith("$token ") ||
                lower.endsWith(" $token") ||
                lower.contains(" $token ")
        }
    }

    /**
     * Resolves follow-up references against recent task/conversation state without
     * confusing a completed old task with an unrelated new explicit request.
     */
    fun resolveContextualRequest(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return trimmed
        val lower = trimmed.lowercase(Locale.ROOT)
        val snap = _context.value

        // If the user gives a brand-new standalone explicit request with no follow-up pronouns, return as-is.
        if (!isContextualFollowUp(trimmed)) {
            return trimmed
        }

        // "phir se" / "dobara" / "repeat" / "do it again"
        if (lower in setOf("phir se", "fir se", "dobara", "repeat", "do it again", "phir se karo", "dobara karo") &&
            snap.lastCommand.isNotBlank()
        ) {
            return snap.lastCommand
        }

        // "wahan <query> search karo" or "usme <query> search karo"
        if ((lower.contains("wahan") || lower.contains("usme") || lower.contains("usmein") || lower.contains("there")) &&
            snap.lastTargetApp.isNotBlank()
        ) {
            return "$trimmed [Context target_app=${snap.lastTargetApp}]"
        }

        // "usko kholo" / "ye wala kholo" / "next"
        if ((lower.contains("usko") || lower.contains("isko") || lower.contains("ye wala") || lower.contains("yeh wala") || lower.contains("next") || lower.contains("agla")) &&
            (snap.lastTargetDescription.isNotBlank() || snap.lastTargetApp.isNotBlank())
        ) {
            val targetCtx = snap.lastTargetDescription.ifBlank { snap.lastTargetApp }
            return "$trimmed [Context reference=$targetCtx in app=${snap.lastTargetApp.ifBlank { snap.lastForegroundPackage }}]"
        }

        return trimmed
    }

    /**
     * Produces truthful, concise user-visible progress messages for each action stage (Section 16).
     */
    fun truthfulProgressMessageForAction(envelope: AiActionEnvelope): String {
        return when (envelope.action) {
            OrchestratorActionType.OPEN_APP -> "Opening application..."
            OrchestratorActionType.SPLIT_SCREEN -> "Entering split-screen mode..."
            OrchestratorActionType.SEARCH_WEB -> "Searching for the requested content..."
            OrchestratorActionType.TYPE -> {
                if (envelope.target.description.contains("search", ignoreCase = true) ||
                    envelope.expectedResult.contains("search", ignoreCase = true)
                ) {
                    "Searching for the requested content..."
                } else {
                    "Typing into ${envelope.target.description.ifBlank { "input field" }}..."
                }
            }
            OrchestratorActionType.TAP,
            OrchestratorActionType.DOUBLE_TAP,
            OrchestratorActionType.LONG_PRESS -> "Interacting with ${envelope.target.description.ifBlank { "screen element" }}..."
            OrchestratorActionType.SCROLL,
            OrchestratorActionType.SWIPE -> "Scrolling screen..."
            OrchestratorActionType.BACK,
            OrchestratorActionType.HOME,
            OrchestratorActionType.RECENTS -> "Navigating system screen..."
            OrchestratorActionType.VERIFY -> "Verifying the result..."
            OrchestratorActionType.WAIT -> "Checking current screen..."
            else -> "Processing step ${envelope.sequence}..."
        }
    }

    fun onTaskStarted(taskId: String, rawRequest: String, resolvedRequest: String, totalSteps: Int = 1) {
        val prev = _context.value
        _context.value = prev.copy(
            activeTaskId = taskId,
            currentUserRequest = rawRequest,
            resolvedUserRequest = resolvedRequest,
            currentStepNumber = 1,
            totalPlannedSteps = totalSteps,
            currentProgressMessage = "Checking current screen...",
            isLastTaskCompleted = false
        )
    }

    fun onActionDispatched(envelope: AiActionEnvelope, foregroundPackage: String) {
        val prev = _context.value
        val progress = truthfulProgressMessageForAction(envelope)
        _context.value = prev.copy(
            currentStepNumber = envelope.sequence,
            currentProgressMessage = progress,
            lastTargetApp = envelope.appName.ifBlank { prev.lastTargetApp },
            lastForegroundPackage = foregroundPackage.ifBlank { prev.lastForegroundPackage },
            lastSearchQuery = if (envelope.action == OrchestratorActionType.TYPE || envelope.action == OrchestratorActionType.SEARCH_WEB) {
                envelope.text.ifBlank { prev.lastSearchQuery }
            } else prev.lastSearchQuery,
            lastTargetDescription = envelope.target.description.ifBlank { prev.lastTargetDescription },
            lastCommand = prev.currentUserRequest.ifBlank { prev.lastCommand }
        )
    }

    fun recordVerification(record: StepVerificationRecord) {
        val prev = _context.value
        val updatedHistory = (prev.verificationHistory + record).takeLast(25)
        _context.value = prev.copy(
            currentProgressMessage = "Verifying the result...",
            verificationHistory = updatedHistory
        )
    }

    fun onTaskFinished(completed: Boolean, summaryMessage: String) {
        val prev = _context.value
        _context.value = prev.copy(
            activeTaskId = "",
            currentProgressMessage = summaryMessage,
            isLastTaskCompleted = completed,
            lastCommand = prev.currentUserRequest.ifBlank { prev.lastCommand }
        )
    }

    fun recordUserCorrection(correction: String) {
        val prev = _context.value
        _context.value = prev.copy(
            userCorrections = (prev.userCorrections + correction).takeLast(10)
        )
    }

    fun resetForTest() {
        _context.value = CoordinatedTaskContext()
    }
}
