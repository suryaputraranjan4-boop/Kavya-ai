package com.example.agent

import com.example.data.MemoryEntity
import com.example.data.MessageEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

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

enum class TaskControlCommandType {
    NONE,
    PAUSE,
    RESUME,
    CANCEL,
    REPRIORITIZE,
    MODIFY_PREVIOUS_STEP,
    CONTRADICTION_CORRECTION
}

data class TaskControlDirective(
    val type: TaskControlCommandType,
    val replacementTargetApp: String? = null,
    val rejectedTargetApp: String? = null,
    val newPriorityPrompt: String? = null,
    val explanationHindi: String = ""
)

/**
 * Manages first-class conversation and task context (Upgrade 4).
 * Handles Hindi/English/Hinglish pronoun & reference resolution, mid-task pause/resume/reprioritize
 * directives, contradiction/correction tracking, and compact context building.
 */
class ConversationContextManager(
    private val sessionSearchEngine: SessionSearchEngine? = null
) {
    private val userCorrections = mutableListOf<UserCorrection>()
    private val taskExecutionHistory = mutableListOf<String>()

    private val _activeContext = MutableStateFlow<ActiveTurnContext?>(null)
    val activeContext: StateFlow<ActiveTurnContext?> = _activeContext.asStateFlow()

    @Volatile
    var lastOpenedApp: String? = null
        private set

    @Volatile
    var previousOpenedApp: String? = null
        private set

    @Volatile
    var lastSearchQuery: String? = null
        private set

    @Volatile
    var lastRecipient: String? = null
        private set

    @Volatile
    var lastSelectedItem: String? = null
        private set

    @Volatile
    var activePausedCheckpoint: TaskCheckpoint? = null

    fun updateConversationalEntities(
        openedApp: String? = null,
        searchQuery: String? = null,
        recipient: String? = null,
        selectedItem: String? = null
    ) {
        if (!openedApp.isNullOrBlank()) {
            if (lastOpenedApp != null && !lastOpenedApp.equals(openedApp, ignoreCase = true)) {
                previousOpenedApp = lastOpenedApp
            }
            lastOpenedApp = openedApp
        }
        if (!searchQuery.isNullOrBlank()) {
            lastSearchQuery = searchQuery
        }
        if (!recipient.isNullOrBlank()) {
            lastRecipient = recipient
        }
        if (!selectedItem.isNullOrBlank()) {
            lastSelectedItem = selectedItem
        }
    }

    /**
     * Resolves Hindi, English, and Hinglish pronouns & contextual references ("वहाँ", "उसमें",
     * "उसी में", "वही वाला", "अभी जो खुला है", "पिछले screen पर") using tracked conversation state.
     */
    fun resolveContextualReferences(
        rawPrompt: String,
        currentForegroundApp: String? = null
    ): String {
        var resolved = rawPrompt.trim()
        if (resolved.isBlank()) return resolved

        val activeAppName = currentForegroundApp?.takeIf { it.isNotBlank() && it != "none" && it != "unknown" }
            ?: lastOpenedApp

        // 1. "अभी जो खुला है" / "abhi jo khula hai" / "currently open app"
        if (!activeAppName.isNullOrBlank()) {
            resolved = resolved.replace(
                Regex("""(?i)(अभी जो खुला है|abhi jo khula hai|currently open app)"""),
                activeAppName
            )
        }

        // 2. "उसमें" / "उसी में" / "वहाँ" / "usme" / "usi me" / "wahan" -> lastOpenedApp or activeAppName
        val appRef = lastOpenedApp ?: activeAppName
        if (!appRef.isNullOrBlank()) {
            resolved = resolved.replace(
                Regex("""(?i)(?:^|(?<=\s))(उसी में|उसमें|वहाँ पर|वहाँ|usi me|usme|wahan par|wahan|in the same app|in that app)(?=\s|$|[.,!?।])"""),
                "$appRef में"
            )
        }

        // 3. "वही वाला" / "wahi wala" / "the same one" -> lastSearchQuery or lastSelectedItem
        val itemRef = lastSelectedItem ?: lastSearchQuery
        if (!itemRef.isNullOrBlank()) {
            resolved = resolved.replace(
                Regex("""(?i)(वही वाला|वही वाली|wahi wala|wahi wali|that same one|the same one)"""),
                itemRef
            )
        }

        // 4. "पिछले screen पर" / "pichhle screen par" / "previous screen"
        val prevApp = previousOpenedApp
        if (!prevApp.isNullOrBlank() &&
            (resolved.contains("पिछले app", ignoreCase = true) || resolved.contains("pichhle app", ignoreCase = true))
        ) {
            resolved = resolved.replace(Regex("""(?i)(पिछले app|pichhle app)"""), prevApp)
        }

        return resolved
    }

    /**
     * Detects mid-task execution control commands (Pause, Resume, Cancel, Reprioritize, Modify Step,
     * or Contradiction/Correction like "YouTube नहीं, Spotify खोलो").
     */
    fun detectTaskControlDirective(prompt: String): TaskControlDirective {
        val trimmed = prompt.trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        // 1. Contradiction / Correction: "<App A> नहीं, <App B> खोलो" or "गलत app खुल गया, <App B> खोलो"
        val notThisButThat = Regex("""(?i)([a-zA-Z0-9\u0900-\u097F]+)\s+(?:नहीं|nahi|not)\s*[,،]?\s*(?:बल्कि\s+|balki\s+|instead\s+)?([a-zA-Z0-9\u0900-\u097F]+)\s*(?:खोलो|खोल|चलाओ|open|launch)?""")
        val contradictionMatch = notThisButThat.find(trimmed)
        if (contradictionMatch != null) {
            val rejected = contradictionMatch.groupValues[1].trim()
            val replacement = contradictionMatch.groupValues[2].trim()
            if (rejected.isNotBlank() && replacement.isNotBlank() && !rejected.equals("कुछ", ignoreCase = true)) {
                recordCorrection(rejected, replacement, "app_selection")
                updateConversationalEntities(openedApp = replacement)
                return TaskControlDirective(
                    type = TaskControlCommandType.CONTRADICTION_CORRECTION,
                    replacementTargetApp = replacement,
                    rejectedTargetApp = rejected,
                    explanationHindi = "ठीक है, $rejected के बजाय $replacement खोल रही हूँ।"
                )
            }
        }

        // 2. Modify previous step ("अपना पिछला step बदलो" / "गलत app खुल गया")
        if (lower.contains("पिछला step बदलो") || lower.contains("pichhla step badlo") ||
            lower.contains("गलत app खुल गया") || lower.contains("galat app khul gaya") ||
            lower.contains("change previous step")
        ) {
            return TaskControlDirective(
                type = TaskControlCommandType.MODIFY_PREVIOUS_STEP,
                explanationHindi = "ठीक है, पिछला step बदल रही हूँ। बताइए अब क्या करना है?"
            )
        }

        // 3. Reprioritize ("अब यह काम पहले करो" / "पहले यह करो" / "do this first")
        if (lower.contains("अब यह काम पहले करो") || lower.contains("ab yeh kaam pehle karo") ||
            lower.contains("do this first") || lower.startsWith("पहले यह करो")
        ) {
            val remainder = trimmed
                .replace(Regex("""(?i)(अब यह काम पहले करो|ab yeh kaam pehle karo|do this first|पहले यह करो)\s*[:,-]?\s*"""), "")
                .trim()
            return TaskControlDirective(
                type = TaskControlCommandType.REPRIORITIZE,
                newPriorityPrompt = remainder.ifBlank { trimmed },
                explanationHindi = "ठीक है, पहले यह नया काम पूरा कर रही हूँ और पिछला कार्य सुरक्षित रखा गया है।"
            )
        }

        // 4. Pause ("pause करो" / "थोड़ी देर रुको" / "रुक जाओ")
        if (lower == "pause" || lower.contains("pause करो") || lower.contains("pause karo") ||
            lower.contains("थोड़ी देर रुको") || lower.contains("thodi der ruko") ||
            lower == "रुक जाओ" || lower == "ruk jao"
        ) {
            return TaskControlDirective(
                type = TaskControlCommandType.PAUSE,
                explanationHindi = "कार्य को अस्थायी रूप से रोक (Pause) दिया गया है। जब आप कहेंगे, वहीं से फिर शुरू करूँगी।"
            )
        }

        // 5. Resume ("resume करो" / "आगे बढ़ो" / "फिर से शुरू करो" / "continue करो")
        if (lower == "resume" || lower.contains("resume करो") || lower.contains("resume karo") ||
            lower.contains("फिर से शुरू करो") || lower.contains("phir se shuru karo") ||
            lower == "आगे बढ़ो" || lower == "aage badho" || lower == "continue task"
        ) {
            return TaskControlDirective(
                type = TaskControlCommandType.RESUME,
                explanationHindi = "रोका गया कार्य वहीं से फिर शुरू कर रही हूँ।"
            )
        }

        // 6. Cancel ("cancel करो" / "रहने दो" / "काम बंद करो")
        if (lower == "cancel" || lower.contains("cancel करो") || lower.contains("cancel karo") ||
            lower == "रहने दो" || lower == "rehne do"
        ) {
            return TaskControlDirective(
                type = TaskControlCommandType.CANCEL,
                explanationHindi = "ठीक है, वर्तमान कार्य रद्द कर दिया गया है।"
            )
        }

        return TaskControlDirective(type = TaskControlCommandType.NONE)
    }

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

        // 4. Current Device / Screen state (sanitized against prompt injection)
        if (!screenContext.isNullOrBlank()) {
            sb.append("ACTIVE DEVICE SCREEN:\n")
            sb.append(com.example.visual.PromptInjectionGuard.sanitizeScreenText(screenContext).take(300))
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
