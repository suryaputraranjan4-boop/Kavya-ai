package com.example.agent

import com.example.utils.AppResolver
import java.util.Locale

/**
 * Requirement 1: User Command = Primary Source
 * Classifies spoken or typed user commands into strictly isolated categories.
 */
enum class CommandCategory {
    OPEN_APP,
    SEARCH_IN_APP,
    INTERACT_IN_APP,
    SEND_MESSAGE,
    CALL,
    PLAY_MEDIA,
    SYSTEM_ACTION,
    STOP_AUTOMATION,
    SLEEP,
    MEMORY_REMEMBER,
    CONVERSATION
}

data class ClassifiedCommand(
    val category: CommandCategory,
    val targetApp: String? = null,
    val query: String? = null,
    val actionParam: String? = null,
    val rawPrompt: String,
    val isMultiStep: Boolean = false,
    val steps: List<ClassifiedStep> = emptyList()
)

data class ClassifiedStep(
    val category: CommandCategory,
    val targetApp: String? = null,
    val query: String? = null,
    val param: String? = null
)

object UserCommandClassifier {

    private val SEARCH_KEYWORDS = listOf(
        "search", "सर्च", "ढूँढो", "ढूंढो", "find", "खोजो", "look for"
    )

    private val APP_OPEN_KEYWORDS = listOf(
        "open", "launch", "start", "run",
        "kholo", "khol do", "open karo", "open kar", "khol",
        "jao", "par jao", "pe jao", "me jao", "mein jao", "go to", "take me to", "le chalo",
        "खोलो", "खोलिए", "खोल", "चालू करो", "चालू कर", "जाओ", "पर जाओ", "पे जाओ", "ले चलो"
    )

    fun hasExplicitSearchKeyword(input: String): Boolean {
        val lower = input.lowercase(Locale.ROOT)
        return SEARCH_KEYWORDS.any { lower.contains(it) }
    }

    fun parseMessageParam(input: String): Pair<String, String> {
        val clean = input.trim()

        // Pattern 1a: explicit delimiter words (saying, that, with text, ki)
        val patternExplicit = Regex("(?i)^(?:send\\s+(?:a\\s+)?message\\s+to|send\\s+message\\s+|message\\s+to|message|msg)\\s+(.+?)\\s+(?:saying|that|with\\s+text|ki)\\s+(.+)$")
        patternExplicit.find(clean)?.let { match ->
            val recipient = match.groupValues[1].replace("(?i)^to\\s+".toRegex(), "").trim()
            val msg = match.groupValues[2].trim()
            if (recipient.isNotBlank() && msg.isNotBlank()) {
                return Pair(recipient, msg)
            }
        }

        // Pattern 1b: standard greetings at the end: e.g. "message my friend hello"
        val patternGreeting = Regex("(?i)^(?:send\\s+(?:a\\s+)?message\\s+to|send\\s+message\\s+|message\\s+to|message|msg)\\s+(.+?)\\s+(hello|hi|hey|how are you|kya haal hai|kal milte hain)$")
        patternGreeting.find(clean)?.let { match ->
            val recipient = match.groupValues[1].replace("(?i)^to\\s+".toRegex(), "").trim()
            val msg = match.groupValues[2].trim()
            if (recipient.isNotBlank() && msg.isNotBlank()) {
                return Pair(recipient, msg)
            }
        }

        // Pattern 2: "मेरे दोस्त को यह message भेजो hello" or "rahul ko message bhejo hello"
        val pattern2 = Regex("(?i)^(.+?)\\s+(?:ko|को)\\s+(?:yeh\\s+|ye\\s+)?(?:message|msg|मैसेज|संदेश)\\s+(?:bhejo|karo|भेजो|करो)\\s*(.+)?$")
        pattern2.find(clean)?.let { match ->
            val recipient = match.groupValues[1].trim()
            val msg = match.groupValues[2].trim().ifBlank { "Hello" }
            return Pair(recipient, msg)
        }

        // Pattern 3: "my friend hello" -> recipient = "my friend", msg = "hello"
        val words = clean.split("\\s+".toRegex())
        if (words.size >= 2) {
            val lastWord = words.last()
            val leading = words.dropLast(1).joinToString(" ")
            return Pair(leading, lastWord)
        }

        return Pair(clean, "Hello")
    }

    /**
     * Requirement 1 & 2: Primary Classification Engine
     * Prioritizes structured CommandIntent and ensures search is NEVER initiated without explicit search keywords.
     */
    fun classify(rawInput: String, appResolver: AppResolver): ClassifiedCommand {
        val trimmed = rawInput.trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        // 0. STOP and SLEEP Interruption Commands (Requirement 20)
        if (lower in listOf("stop kavya", "kavya stop", "stop", "ruko", "ruk jao", "रुको", "रुक जाओ", "बस करो", "cancel")) {
            return ClassifiedCommand(
                category = CommandCategory.STOP_AUTOMATION,
                actionParam = "STOP",
                rawPrompt = trimmed
            )
        }
        if (lower in listOf("sleep kavya", "kavya sleep", "sleep", "go to sleep", "so jao", "सो जाओ", "स्लीप मोड")) {
            return ClassifiedCommand(
                category = CommandCategory.SLEEP,
                actionParam = "SLEEP",
                rawPrompt = trimmed
            )
        }

        // 0b. Explicit Memory Storage Commands ("Ye yaad rakhna", "Isko remember karo", "Aage se aise karna")
        if (isExplicitMemoryCommand(trimmed)) {
            return ClassifiedCommand(
                category = CommandCategory.MEMORY_REMEMBER,
                actionParam = trimmed,
                rawPrompt = trimmed
            )
        }

        // 1. On-Screen Navigation & Direct UI Interaction (Home, Back, Scroll, Tap, Click, Press, Ordinals)
        if (lower in listOf("go home", "home", "home screen", "home jao", "होम")) {
            return ClassifiedCommand(category = CommandCategory.INTERACT_IN_APP, actionParam = "HOME", rawPrompt = trimmed)
        }
        if (lower in listOf("go back", "back", "piche jao", "wapas jao", "peeche jao", "बैक")) {
            return ClassifiedCommand(category = CommandCategory.INTERACT_IN_APP, actionParam = "BACK", rawPrompt = trimmed)
        }
        if (lower.startsWith("scroll ") || lower == "scroll down" || lower == "scroll up") {
            return ClassifiedCommand(category = CommandCategory.INTERACT_IN_APP, actionParam = trimmed, rawPrompt = trimmed)
        }
        if (lower.startsWith("tap ") || lower.startsWith("click ") || lower.startsWith("press ")) {
            return ClassifiedCommand(category = CommandCategory.INTERACT_IN_APP, actionParam = trimmed, rawPrompt = trimmed)
        }
        // Ordinal selectors like "dusra video kholo", "open second video", "open 2nd website", "first result", "second result"
        val isOrdinal = lower.contains("second video") || lower.contains("2nd video") ||
                lower.contains("third video") || lower.contains("3rd video") ||
                lower.contains("dusra video") || lower.contains("doosra video") ||
                lower.contains("teesra video") || lower.contains("first video") ||
                lower.contains("second website") || lower.contains("2nd website") ||
                lower.contains("first result") || lower.contains("second result") || lower.contains("2nd result") ||
                lower.contains("pehli website") || lower.contains("pahla video")
        if (isOrdinal) {
            return ClassifiedCommand(
                category = CommandCategory.INTERACT_IN_APP,
                actionParam = trimmed,
                rawPrompt = trimmed
            )
        }

        // 2. Multi-step compound commands (e.g. "Open WhatsApp and message my friend hello", "Open YouTube and search Minecraft")
        val compoundRegex = Regex("(?i)^(?:open\\s+|launch\\s+|start\\s+)?(.+?)\\s+(?:and\\s+|then\\s+|aur\\s+|kholo\\s+aur\\s+|open\\s+karo\\s+aur\\s+|kholke\\s+|khol\\s+ke\\s+|khol\\s+kar\\s+|खोलो\\s+और\\s+|और\\s+|फिर\\s+)(.+)$")
        compoundRegex.find(trimmed)?.let { match ->
            val firstPart = match.groupValues[1].trim()
            val secondPart = match.groupValues[2].trim()
            val app = appResolver.extractAppNameFromNaturalLanguage(firstPart)
            if (app.isNotBlank()) {
                val secondLower = secondPart.lowercase(Locale.ROOT)
                val hasSearchInSecond = hasExplicitSearchKeyword(secondPart)
                val isPlayInSecond = secondLower.contains("chalao") || secondLower.contains("play") || secondLower.contains("bajao") || secondLower.contains("sunao") || secondLower.contains("चलाओ") || secondLower.contains("बजाओ")
                val isMsgInSecond = secondLower.contains("message") || secondLower.contains("msg") || secondLower.contains("bhejo") || secondLower.contains("send") || secondLower.contains("भेजो")
                val isCallInSecond = secondLower.contains("call") || secondLower.contains("phone") || secondLower.contains("dial") || secondLower.contains("कॉल")

                val cleanQuery = secondPart
                    .replace("(?i)^(?:search(?:\\s+for)?|find|look\\s+for|chalao|play|bajao|sunao|dhoondo|सर्च\\s*करो|चलाओ|ढूंढो)\\s*".toRegex(), "")
                    .trim()

                val (step2Category, step2Param, step2Query) = when {
                    isMsgInSecond -> {
                        val (recipient, message) = parseMessageParam(secondPart)
                        Triple(CommandCategory.SEND_MESSAGE, "$recipient||$message", null)
                    }
                    isCallInSecond -> {
                        val recipient = secondPart.replace("(?i)call|phone|dial|karo|lagao|कॉल|करो".toRegex(), "").trim()
                        Triple(CommandCategory.CALL, recipient, null)
                    }
                    isPlayInSecond -> {
                        Triple(CommandCategory.PLAY_MEDIA, cleanQuery, cleanQuery)
                    }
                    hasSearchInSecond -> {
                        Triple(CommandCategory.SEARCH_IN_APP, cleanQuery, cleanQuery)
                    }
                    else -> {
                        Triple(CommandCategory.INTERACT_IN_APP, secondPart, cleanQuery.ifBlank { null })
                    }
                }

                return ClassifiedCommand(
                    category = CommandCategory.OPEN_APP,
                    targetApp = app,
                    query = null, // First step has strictly NO query
                    rawPrompt = trimmed,
                    isMultiStep = true,
                    steps = listOf(
                        ClassifiedStep(category = CommandCategory.OPEN_APP, targetApp = app, query = null),
                        ClassifiedStep(category = step2Category, targetApp = app, query = step2Query, param = step2Param)
                    )
                )
            }
        }

        // 3. Structured CommandIntent Layer (Single-step commands)
        val interpreted = CommandInterpreter.interpret(rawInput)
        return when (interpreted) {
            is CommandIntent.RememberInstruction -> ClassifiedCommand(CommandCategory.MEMORY_REMEMBER, actionParam = interpreted.rawCommand, rawPrompt = trimmed)
            is CommandIntent.Stop -> ClassifiedCommand(CommandCategory.STOP_AUTOMATION, actionParam = "STOP", rawPrompt = trimmed)
            is CommandIntent.Sleep -> ClassifiedCommand(CommandCategory.SLEEP, actionParam = "SLEEP", rawPrompt = trimmed)
            is CommandIntent.CallContact -> ClassifiedCommand(CommandCategory.CALL, targetApp = if (interpreted.isWhatsApp) "WhatsApp" else "Phone", actionParam = interpreted.contactName, rawPrompt = trimmed)
            is CommandIntent.OpenChat -> ClassifiedCommand(CommandCategory.INTERACT_IN_APP, targetApp = interpreted.app, actionParam = "OPEN_CHAT:${interpreted.contactName}", rawPrompt = trimmed)
            is CommandIntent.SendMessage -> ClassifiedCommand(CommandCategory.SEND_MESSAGE, targetApp = interpreted.app, actionParam = "${interpreted.recipient}:${interpreted.messageText}", rawPrompt = trimmed)
            is CommandIntent.PlayMedia -> ClassifiedCommand(CommandCategory.PLAY_MEDIA, targetApp = interpreted.app, query = interpreted.mediaQuery, rawPrompt = trimmed)
            is CommandIntent.OpenWebsite -> ClassifiedCommand(CommandCategory.INTERACT_IN_APP, targetApp = interpreted.app, actionParam = "OPEN_URL:${interpreted.destinationUrl}", rawPrompt = trimmed)
            is CommandIntent.WebSearch -> ClassifiedCommand(CommandCategory.SEARCH_IN_APP, targetApp = interpreted.engineOrApp.ifBlank { null }, query = interpreted.query, rawPrompt = trimmed)
            is CommandIntent.OpenApp -> ClassifiedCommand(CommandCategory.OPEN_APP, targetApp = interpreted.appName, rawPrompt = trimmed)
            is CommandIntent.SystemControl -> ClassifiedCommand(CommandCategory.SYSTEM_ACTION, actionParam = interpreted.controlType, rawPrompt = trimmed)
            is CommandIntent.InteractInApp -> ClassifiedCommand(CommandCategory.INTERACT_IN_APP, actionParam = interpreted.action, rawPrompt = trimmed)
            is CommandIntent.Wake -> ClassifiedCommand(CommandCategory.CONVERSATION, rawPrompt = trimmed)
            is CommandIntent.Conversation -> {
                // If user asked to search with explicit search keyword:
                if (hasExplicitSearchKeyword(trimmed)) {
                    val compound = appResolver.parseCompoundCommand(trimmed)
                    if (compound != null) {
                        ClassifiedCommand(
                            category = CommandCategory.SEARCH_IN_APP,
                            targetApp = compound.appName,
                            query = compound.searchQuery,
                            rawPrompt = trimmed
                        )
                    } else {
                        val cleanQuery = trimmed
                            .replace("(?i)^(?:search(?:\\s+for)?|look\\s+for|find|google|सर्च\\s*करो|ढूंढो|खोजो)\\s+".toRegex(), "")
                            .replace("(?i)\\s+(?:search\\s+karo|dhoondo|khojo|सर्च\\s*करो|ढूंढो|खोजो)$".toRegex(), "")
                            .trim()
                        ClassifiedCommand(
                            category = CommandCategory.SEARCH_IN_APP,
                            targetApp = if (trimmed.contains("google", ignoreCase = true)) "Google" else null,
                            query = cleanQuery.ifBlank { trimmed },
                            rawPrompt = trimmed
                        )
                    }
                } else {
                    // Check if user explicitly asked to launch an installed app via AppResolver
                    val extracted = appResolver.extractAppNameFromNaturalLanguage(trimmed)
                    if (extracted.isNotBlank() && appResolver.isDirectAppLaunchQuery(trimmed)) {
                        val res = appResolver.resolve(extracted)
                        if (res.confidence != com.example.utils.MatchConfidence.NONE && res.matchedApp != null) {
                            ClassifiedCommand(
                                category = CommandCategory.OPEN_APP,
                                targetApp = res.matchedApp?.appName ?: extracted,
                                query = null,
                                rawPrompt = trimmed
                            )
                        } else {
                            ClassifiedCommand(CommandCategory.CONVERSATION, rawPrompt = trimmed)
                        }
                    } else {
                        ClassifiedCommand(CommandCategory.CONVERSATION, rawPrompt = trimmed)
                    }
                }
            }
        }
    }

    /**
     * Determines whether input is an explicit instruction to store persistent memory.
     */
    fun isExplicitMemoryCommand(input: String): Boolean {
        val lower = input.lowercase(Locale.ROOT)
            .replace("[.,!?;:]".toRegex(), "")
            .trim()
        val memorySuffixes = listOf(
            "ye yaad rakhna", "yeh yaad rakhna", "ye yaad rakho", "yeh yaad rakho",
            "isko yaad rakhna", "ise yaad rakhna", "isko yaad rakho", "ise yaad rakho",
            "isko remember karo", "ise remember karo", "isko remember rakhna",
            "remember this", "remember that", "please remember this",
            "aage se aise karna", "aage se aisa karna", "aage se aise hi karna", "aage se dhyan rakhna",
            "next time do it like this", "from now on do this", "next time do this",
            "yaad rakhna", "yaad rakho", "याद रखना", "याद रखो"
        )
        val memoryPrefixes = listOf(
            "aage se ", "remember that ", "remember this ", "remember to ",
            "yaad rakhna ki ", "yaad rakhna ", "yaad rakho ki ",
            "isko remember karo ", "ise remember karo ", "isko yaad rakho "
        )
        return memorySuffixes.any { lower.endsWith(it) } || memoryPrefixes.any { lower.startsWith(it) }
    }
}
