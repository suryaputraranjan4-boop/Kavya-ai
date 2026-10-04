package com.example.agent

import com.example.utils.AppResolver
import com.example.utils.InstalledApp
import com.example.utils.MatchConfidence
import java.util.Locale

/**
 * Fundamental intent categories prescribed by Kavya's master architecture.
 */
enum class IntentCategory {
    CHAT,
    QUESTION,
    EXPLANATION,
    RESEARCH,
    ACTION,
    MULTI_STEP_TASK,
    SCHEDULE,
    MEMORY_SAVE,
    MEMORY_RECALL,
    CANCEL,
    STOP,
    SLEEP,
    UNKNOWN_OR_AMBIGUOUS
}

data class GateStep(
    val category: IntentCategory,
    val targetApp: String? = null,
    val actionType: UniversalActionType = UniversalActionType.SYSTEM_CONTROL,
    val query: String? = null,
    val param: String? = null,
    val recipient: String? = null,
    val messageText: String? = null
)

data class IntentGateDecision(
    val category: IntentCategory,
    val targetApp: String? = null,
    val query: String? = null,
    val param: String? = null,
    val steps: List<GateStep> = emptyList(),
    val reasoning: String = "",
    val isExplicitAction: Boolean = false,
    val isAmbiguous: Boolean = false,
    val candidateApps: List<InstalledApp> = emptyList(),
    val rawPrompt: String
)

/**
 * Central Intent/Action Gate for Kavya AI.
 *
 * Implements strict intent gating:
 * - Mentioning an app name is NEVER enough to launch an app.
 * - Mentioning Google is NEVER enough to search Google.
 * - Mentioning YouTube is NEVER enough to open YouTube.
 * - Mentioning WhatsApp is NEVER enough to open WhatsApp.
 * - Chat, questions, explanations NEVER trigger device side-effects or external web searches.
 * - Research only triggers when explicitly requested.
 * - Multi-step workflows are structured into ordered sequential steps.
 */
object IntentGate {

    private val STOP_KEYWORDS = listOf(
        "stop", "kavya stop", "stop kavya", "ruko", "ruk jao", "रुको", "रुक जाओ",
        "bas karo", "cancel", "band karo", "halt", "quit", "abort"
    )

    private val SLEEP_KEYWORDS = listOf(
        "sleep", "kavya sleep", "sleep kavya", "go to sleep", "so jao", "सो जाओ",
        "stop listening", "stop listening kavya", "shubh ratri", "good night kavya"
    )

    private val QUESTION_WORDS = listOf(
        "what", "why", "how", "who", "which", "where", "when", "whom", "whose",
        "kya", "kyun", "kaise", "kaun", "kahan", "kab", "kisko", "kiska", "kitna",
        "क्या", "क्यों", "कैसे", "कौन", "कहाँ", "कब", "किसका", "कितना"
    )

    private val EXPLANATION_WORDS = listOf(
        "explain", "tell me about", "describe", "elaborate", "teach me",
        "batao", "samjhao", "ke baare me batao", "ke bare me batao",
        "बताओ", "समझाओ", "के बारे में बताओ"
    )

    private val RESEARCH_KEYWORDS = listOf(
        "search the latest", "research", "look up on github", "search github",
        "find online", "google search for", "latest news on", "search web for",
        "internet par search karo", "online research karo", "github par research karo",
        "browse for", "fetch documentation for"
    )

    private val APP_ACTION_VERBS = listOf(
        "open", "launch", "start", "run", "kholo", "khol do", "open karo", "open kar", "khol",
        "chalao", "chalaye", "jao", "par jao", "pe jao", "me jao", "mein jao", "go to",
        "take me to", "le chalo", "खोलो", "खोलिए", "खोल", "चालू करो", "चालू कर", "जाओ", "ले चलो"
    )

    fun evaluate(input: String, appResolver: AppResolver): IntentGateDecision {
        val trimmed = input.trim()
            .removeSurrounding("\"", "\"")
            .removeSurrounding("'", "'")
            .trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        if (trimmed.isEmpty()) {
            return IntentGateDecision(
                category = IntentCategory.CHAT,
                reasoning = "Empty input",
                rawPrompt = trimmed
            )
        }

        // 1. STOP & CANCEL (Highest Priority Interruption)
        if (STOP_KEYWORDS.any { lower == it || lower.startsWith("$it ") || lower.endsWith(" $it") }) {
            return IntentGateDecision(
                category = IntentCategory.STOP,
                param = "STOP",
                reasoning = "User issued immediate stop/cancel directive",
                isExplicitAction = true,
                rawPrompt = trimmed
            )
        }

        // 2. SLEEP
        if (SLEEP_KEYWORDS.any { lower == it || lower.startsWith("$it ") || lower.endsWith(" $it") }) {
            return IntentGateDecision(
                category = IntentCategory.SLEEP,
                param = "SLEEP",
                reasoning = "User requested sleep mode",
                isExplicitAction = true,
                rawPrompt = trimmed
            )
        }

        // 3. SCHEDULE (Natural language scheduling)
        if (isSchedulingInstruction(lower)) {
            return IntentGateDecision(
                category = IntentCategory.SCHEDULE,
                query = trimmed,
                param = trimmed,
                reasoning = "User requested scheduled/timed reminder or recurring task",
                isExplicitAction = true,
                rawPrompt = trimmed
            )
        }

        // 4. MEMORY_SAVE ("Remember this", "Ye yaad rakhna", etc.)
        if (UserCommandClassifier.isExplicitMemoryCommand(trimmed)) {
            return IntentGateDecision(
                category = IntentCategory.MEMORY_SAVE,
                param = trimmed,
                reasoning = "User explicitly instructed Kavya to store persistent memory",
                isExplicitAction = true,
                rawPrompt = trimmed
            )
        }

        // 5. MEMORY_RECALL & SESSION SEARCH ("What did we talk about...", "Humne last week kya...", etc.)
        if (isSessionOrMemoryRecallQuery(lower)) {
            return IntentGateDecision(
                category = IntentCategory.MEMORY_RECALL,
                query = trimmed,
                param = trimmed,
                reasoning = "User is querying previous sessions, discussions, or saved preferences",
                rawPrompt = trimmed
            )
        }

        // 6. QUESTION & EXPLANATION GUARDS
        // "What is WhatsApp?", "Explain Spotify algorithms", "I was talking about WhatsApp yesterday"
        val isExplicitQuestion = lower.endsWith("?") ||
                QUESTION_WORDS.any { lower.startsWith("$it ") || lower.startsWith("$it?") }
        val isExplicitExplanation = EXPLANATION_WORDS.any { lower.startsWith("$it ") || lower.contains(" $it") }
        val isConversationalMention = isConversationalAppMention(lower)

        if ((isExplicitQuestion || isExplicitExplanation || isConversationalMention) && !hasExplicitAppActionVerb(lower)) {
            val cat = if (isExplicitExplanation) IntentCategory.EXPLANATION else if (isExplicitQuestion) IntentCategory.QUESTION else IntentCategory.CHAT
            return IntentGateDecision(
                category = cat,
                query = trimmed,
                reasoning = "Conversational inquiry or mention without explicit app opening verb",
                rawPrompt = trimmed
            )
        }

        // 7. EXPLICIT RESEARCH ("Search the latest AI projects on GitHub", "Research on...", etc.)
        if (isExplicitResearchQuery(lower)) {
            val queryClean = extractResearchQuery(trimmed)
            return IntentGateDecision(
                category = IntentCategory.RESEARCH,
                query = queryClean,
                reasoning = "User explicitly requested online/external research",
                isExplicitAction = true,
                rawPrompt = trimmed
            )
        }

        // 8. MULTI-STEP COMPOUND WORKFLOWS
        // "Open WhatsApp and send hello to Rahul", "YouTube kholo aur billiyan search karo"
        val compoundMatch = parseCompoundTask(trimmed, appResolver)
        if (compoundMatch != null) {
            return compoundMatch
        }

        // 9. SINGLE EXPLICIT ACTION:
        // Must contain an explicit Action Verb ("kholo", "open", "launch", "chalao", "send", "call", "tap", "scroll")
        val explicitActionDecision = parseExplicitAction(trimmed, lower, appResolver)
        if (explicitActionDecision != null) {
            return explicitActionDecision
        }

        // 10. DEFAULT CONVERSATIONAL / CHAT FALLBACK
        // Standard chat or knowledge conversation. Never opens an app or blindly searches web.
        return IntentGateDecision(
            category = IntentCategory.CHAT,
            query = trimmed,
            reasoning = "General conversational turn; no device action requested",
            rawPrompt = trimmed
        )
    }

    private fun hasExplicitAppActionVerb(lower: String): Boolean {
        return APP_ACTION_VERBS.any { lower.startsWith("$it ") || lower.endsWith(" $it") || lower.contains(" $it ") }
    }

    private fun isConversationalAppMention(lower: String): Boolean {
        val mentionPhrases = listOf(
            "was talking about", "talked about", "heard about", "told me about",
            "talking about", "yesterday", "last week", "kal maine", "baat kar raha tha",
            "baat ho rahi thi", "ke baare me suna", "kya hai", "what is", "kaise kaam karta hai",
            "meaning of", "matlab kya hai", "kaisi app hai", "accha app hai"
        )
        return mentionPhrases.any { lower.contains(it) }
    }

    private fun isSchedulingInstruction(lower: String): Boolean {
        val scheduleTriggers = listOf(
            "remind me to", "remind me at", "every day at", "every morning at", "every night at",
            "every weekday at", "every sunday", "every monday", "every tuesday", "every wednesday",
            "every thursday", "every friday", "every saturday", "har subah", "har din",
            "har roz", "roj subah", "roz subah", "kal subah", "tomorrow at", "today at",
            "alarm lagao", "schedule a task", "schedule task", "schedule a report",
            "याद दिलाना", "अलार्म लगाओ", "हर दिन", "हर सुबह"
        )
        return scheduleTriggers.any { lower.contains(it) }
    }

    private fun isSessionOrMemoryRecallQuery(lower: String): Boolean {
        val recallTriggers = listOf(
            "what did we talk about", "what did we discuss", "humne kya baat ki",
            "humne kya discuss kiya", "last week kya", "kal kya baat", "yesterday what",
            "do you remember", "kya tumhe yaad hai", "tumhe yaad hai", "maine kya bataya tha",
            "kavya recall", "search previous chat", "search past conversation", "purani baat",
            "pichli conversation", "last time kya", "what did we do yesterday", "humne last week"
        )
        return recallTriggers.any { lower.contains(it) }
    }

    private fun isExplicitResearchQuery(lower: String): Boolean {
        if (RESEARCH_KEYWORDS.any { lower.contains(it) }) return true
        val hasSearch = lower.startsWith("search ") || lower.contains("search for ") || lower.contains("research ")
        val hasOnlineTarget = lower.contains("github") || lower.contains("online") || lower.contains("internet") || lower.contains("web")
        return hasSearch && hasOnlineTarget
    }

    private fun extractResearchQuery(input: String): String {
        return input.replace("(?i)^(?:search(?:\\s+the)?(?:\\s+latest)?|research(?:\\s+on)?|look\\s+up\\s+on\\s+github|search\\s+github\\s+for|find\\s+online)\\s+".toRegex(), "")
            .replace("(?i)\\s+(?:on\\s+github|online|on\\s+the\\s+internet|par\\s+search\\s+karo)$".toRegex(), "")
            .trim()
    }

    private fun parseCompoundTask(trimmed: String, appResolver: AppResolver): IntentGateDecision? {
        val compoundRegex = Regex("(?i)^(?:open\\s+|launch\\s+|start\\s+)?(.+?)\\s+(?:and\\s+|then\\s+|aur\\s+|kholo\\s+aur\\s+|open\\s+karo\\s+aur\\s+|kholke\\s+|khol\\s+ke\\s+|khol\\s+kar\\s+|खोलो\\s+और\\s+|और\\s+|फिर\\s+)(.+)$")
        val match = compoundRegex.find(trimmed) ?: return null

        val firstPart = match.groupValues[1].trim()
        val secondPart = match.groupValues[2].trim()

        val appTarget = appResolver.extractAppNameFromNaturalLanguage(firstPart)
        if (appTarget.isBlank()) return null

        val secondLower = secondPart.lowercase(Locale.ROOT)
        val isMsgInSecond = secondLower.contains("message") || secondLower.contains("msg") || secondLower.contains("bhejo") || secondLower.contains("send") || secondLower.contains("भेजो")
        val isCallInSecond = secondLower.contains("call") || secondLower.contains("phone") || secondLower.contains("dial") || secondLower.contains("कॉल")
        val isSearchInSecond = UserCommandClassifier.hasExplicitSearchKeyword(secondPart)
        val isPlayInSecond = secondLower.contains("play") || secondLower.contains("chalao") || secondLower.contains("bajao") || secondLower.contains("sunao")

        val cleanQuery = secondPart
            .replace("(?i)^(?:search(?:\\s+for)?|find|look\\s+for|chalao|play|bajao|sunao|dhoondo|सर्च\\s*करो|चलाओ|ढूंढो)\\s*".toRegex(), "")
            .trim()

        val step1 = GateStep(
            category = IntentCategory.ACTION,
            targetApp = appTarget,
            actionType = UniversalActionType.OPEN_APP
        )

        val step2 = when {
            isMsgInSecond -> {
                val (recipient, message) = UserCommandClassifier.parseMessageParam(secondPart)
                GateStep(
                    category = IntentCategory.ACTION,
                    targetApp = appTarget,
                    actionType = if (appTarget.contains("WhatsApp", ignoreCase = true)) UniversalActionType.SEND_WHATSAPP_MESSAGE else UniversalActionType.SEND_SMS,
                    recipient = recipient,
                    messageText = message,
                    param = "$recipient||$message"
                )
            }
            isCallInSecond -> {
                val recipient = secondPart.replace("(?i)call|phone|dial|karo|lagao|कॉल|करो".toRegex(), "").trim()
                GateStep(
                    category = IntentCategory.ACTION,
                    targetApp = appTarget,
                    actionType = if (appTarget.contains("WhatsApp", ignoreCase = true)) UniversalActionType.MAKE_WHATSAPP_CALL else UniversalActionType.MAKE_PHONE_CALL,
                    recipient = recipient,
                    param = recipient
                )
            }
            isPlayInSecond -> {
                GateStep(
                    category = IntentCategory.ACTION,
                    targetApp = appTarget,
                    actionType = UniversalActionType.PLAY,
                    query = cleanQuery,
                    param = cleanQuery
                )
            }
            isSearchInSecond -> {
                GateStep(
                    category = IntentCategory.ACTION,
                    targetApp = appTarget,
                    actionType = UniversalActionType.SEARCH,
                    query = cleanQuery,
                    param = cleanQuery
                )
            }
            else -> {
                GateStep(
                    category = IntentCategory.ACTION,
                    targetApp = appTarget,
                    actionType = UniversalActionType.TAP,
                    param = secondPart
                )
            }
        }

        return IntentGateDecision(
            category = IntentCategory.MULTI_STEP_TASK,
            targetApp = appTarget,
            steps = listOf(step1, step2),
            reasoning = "Multi-step sequential compound workflow detected",
            isExplicitAction = true,
            rawPrompt = trimmed
        )
    }

    private fun parseExplicitAction(
        trimmed: String,
        lower: String,
        appResolver: AppResolver
    ): IntentGateDecision? {
        // UI Navigation: Home, Back, Recents, Scroll
        if (lower in listOf("go home", "home", "home screen", "home jao", "होम")) {
            return IntentGateDecision(
                category = IntentCategory.ACTION,
                param = "HOME",
                steps = listOf(GateStep(category = IntentCategory.ACTION, actionType = UniversalActionType.HOME, param = "HOME")),
                reasoning = "System navigation Home",
                isExplicitAction = true,
                rawPrompt = trimmed
            )
        }
        if (lower in listOf("go back", "back", "piche jao", "wapas jao", "peeche jao", "बैक")) {
            return IntentGateDecision(
                category = IntentCategory.ACTION,
                param = "BACK",
                steps = listOf(GateStep(category = IntentCategory.ACTION, actionType = UniversalActionType.BACK, param = "BACK")),
                reasoning = "System navigation Back",
                isExplicitAction = true,
                rawPrompt = trimmed
            )
        }
        if (lower.startsWith("scroll down") || lower == "scroll down" || lower.startsWith("scroll up") || lower == "scroll up") {
            return IntentGateDecision(
                category = IntentCategory.ACTION,
                param = trimmed,
                steps = listOf(GateStep(category = IntentCategory.ACTION, actionType = UniversalActionType.SCROLL, param = trimmed)),
                reasoning = "Directional scroll action",
                isExplicitAction = true,
                rawPrompt = trimmed
            )
        }

        // Explicit App Open: MUST have an explicit launching verb or suffix
        // "open WhatsApp", "WhatsApp kholo", "launch YouTube Create", "YT Create khol do"
        val isExplicitOpenSyntax = lower.startsWith("open ") || lower.startsWith("launch ") ||
                lower.startsWith("start ") || lower.startsWith("run ") ||
                lower.endsWith(" kholo") || lower.endsWith(" khol do") ||
                lower.endsWith(" open karo") || lower.endsWith(" open kar") ||
                lower.endsWith(" chalao") || lower.endsWith(" खोलो") || lower.endsWith(" चालू करो")

        if (isExplicitOpenSyntax) {
            val candidateName = AppResolver.extractAppNameFromNaturalLanguage(trimmed)
            if (candidateName.isNotBlank()) {
                val resolution = appResolver.resolve(candidateName)
                if (resolution.confidence == MatchConfidence.AMBIGUOUS) {
                    return IntentGateDecision(
                        category = IntentCategory.UNKNOWN_OR_AMBIGUOUS,
                        targetApp = candidateName,
                        isAmbiguous = true,
                        candidateApps = resolution.candidateApps,
                        reasoning = "Ambiguous app selection between multiple installed apps",
                        isExplicitAction = true,
                        rawPrompt = trimmed
                    )
                }
                val resolvedTarget = resolution.matchedApp?.appName ?: candidateName
                return IntentGateDecision(
                    category = IntentCategory.ACTION,
                    targetApp = resolvedTarget,
                    param = resolution.matchedApp?.packageName,
                    steps = listOf(
                        GateStep(
                            category = IntentCategory.ACTION,
                            targetApp = resolvedTarget,
                            actionType = UniversalActionType.OPEN_APP,
                            param = resolution.matchedApp?.packageName
                        )
                    ),
                    reasoning = "Explicit app open command resolved: $resolvedTarget",
                    isExplicitAction = true,
                    rawPrompt = trimmed
                )
            }
        }

        // Explicit Media Playback: "play Arijit Singh on Spotify", "Spotify par gana chalao"
        val isPlayCommand = (lower.contains("play ") || lower.contains("chalao") || lower.contains("bajao") || lower.contains("sunao")) &&
                (lower.contains("spotify") || lower.contains("youtube") || lower.contains("song") || lower.contains("video") || lower.contains("music"))
        if (isPlayCommand) {
            val app = if (lower.contains("spotify")) "Spotify" else "YouTube"
            val queryClean = trimmed.replace("(?i)^(?:play|chalao|bajao|sunao)\\s*".toRegex(), "")
                .replace("(?i)\\s+(?:on\\s+spotify|on\\s+youtube|par\\s+chalao|chalao|bajao)$".toRegex(), "")
                .trim()
            return IntentGateDecision(
                category = IntentCategory.ACTION,
                targetApp = app,
                query = queryClean,
                param = queryClean,
                steps = listOf(
                    GateStep(category = IntentCategory.ACTION, targetApp = app, actionType = UniversalActionType.PLAY, query = queryClean)
                ),
                reasoning = "Explicit media playback command",
                isExplicitAction = true,
                rawPrompt = trimmed
            )
        }

        return null
    }
}
