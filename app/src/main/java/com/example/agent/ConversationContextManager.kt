package com.example.agent

import com.example.data.MessageEntity
import com.example.data.MemoryEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class UserCorrection(
    val originalAssumption: String,
    val userCorrectionText: String,
    val domain: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ActiveTurnContext(
    val currentPrompt: String,
    val intentCategory: IntentCategory,
    val activeApp: String?,
    val activeTaskDescription: String?,
    val recentTurns: List<MessageEntity>,
    val relevantMemories: List<MemoryEntity>,
    val userCorrections: List<UserCorrection>,
    val activeScreenSummary: String?
)

/**
 * Manages first-class conversation and task context (Requirement 6).
 * Enforces compact context representation so tokens and models are never wasted on full history dumps.
 */
class ConversationContextManager(
    private val sessionSearchEngine: SessionSearchEngine? = null
) {
    private val userCorrections = mutableListOf<UserCorrection>()
    private val taskExecutionHistory = mutableListOf<String>()

    private val _activeContext = MutableStateFlow<ActiveTurnContext?>(null)
    val activeContext: StateFlow<ActiveTurnContext?> = _activeContext.asStateFlow()

    fun recordCorrection(originalAssumption: String, correctionText: String, domain: String = "general") {
        synchronized(userCorrections) {
            userCorrections.add(UserCorrection(originalAssumption, correctionText, domain))
            if (userCorrections.size > 10) userCorrections.removeAt(0)
        }
    }

    fun recordTaskSuccess(taskDesc: String) {
        synchronized(taskExecutionHistory) {
            taskExecutionHistory.add("SUCCESS: $taskDesc")
            if (taskExecutionHistory.size > 10) taskExecutionHistory.removeAt(0)
        }
    }

    fun recordTaskFailure(taskDesc: String, reason: String) {
        synchronized(taskExecutionHistory) {
            taskExecutionHistory.add("FAILED: $taskDesc (Reason: $reason)")
            if (taskExecutionHistory.size > 10) taskExecutionHistory.removeAt(0)
        }
    }

    /**
     * Builds a compact, relevance-filtered context prompt for the turn.
     */
    fun buildCompactPromptContext(
        prompt: String,
        decision: IntentGateDecision,
        recentMessages: List<MessageEntity>,
        memories: List<MemoryEntity>,
        screenContext: String?,
        sessionSearchResults: List<SessionSearchResult> = emptyList()
    ): String {
        val sb = StringBuilder()

        // 1. User corrections (if any)
        val correctionsSnapshot = synchronized(userCorrections) { userCorrections.toList() }
        if (correctionsSnapshot.isNotEmpty()) {
            sb.append("USER PREVIOUS CORRECTIONS:\n")
            correctionsSnapshot.takeLast(3).forEach {
                sb.append("- Instead of '${it.originalAssumption}', user instructed: '${it.userCorrectionText}'\n")
            }
            sb.append("\n")
        }

        // 2. Relevant Persistent Memory
        if (memories.isNotEmpty()) {
            sb.append("RELEVANT MEMORY & PREFERENCES:\n")
            memories.take(5).forEach {
                sb.append("- [${it.category}] ${it.key}: ${it.content}\n")
            }
            sb.append("\n")
        }

        // 3. Historical Session Search Snippets (if user asked about past talks)
        if (sessionSearchResults.isNotEmpty()) {
            sb.append("PREVIOUS CONVERSATION SEARCH SNIPPETS:\n")
            sessionSearchResults.forEach {
                sb.append("- (${it.formattedDate}) ${it.snippet}\n")
            }
            sb.append("\n")
        }

        // 4. Current Device / Screen state
        if (!screenContext.isNullOrBlank()) {
            sb.append("ACTIVE DEVICE SCREEN:\n")
            sb.append(screenContext.take(300))
            sb.append("\n\n")
        }

        // 5. Recent task executions
        val historySnapshot = synchronized(taskExecutionHistory) { taskExecutionHistory.toList() }
        if (historySnapshot.isNotEmpty()) {
            sb.append("RECENT ACTIONS:\n")
            historySnapshot.takeLast(2).forEach { sb.append("- $it\n") }
            sb.append("\n")
        }

        // 6. Intent gate metadata
        sb.append("INTENT: ${decision.category.name}")
        if (decision.targetApp != null) sb.append(" | TARGET: ${decision.targetApp}")

        return sb.toString().trim()
    }
}
