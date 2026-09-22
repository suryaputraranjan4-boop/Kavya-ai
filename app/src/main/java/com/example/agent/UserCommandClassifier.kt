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

    /**
     * Requirement 1 & 2: Primary Classification Engine
     * Prioritizes OPEN_APP and ensures search is NEVER initiated without explicit search keywords.
     */
    fun classify(rawInput: String, appResolver: AppResolver): ClassifiedCommand {
        val trimmed = rawInput.trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        // 1. Phone Call
        val callPattern = Regex("(?i)^(?:call|phone karo|phone mila|dial)\\s+(?:to\\s+)?(.+)$|^(.+?)\\s+ko\\s+(?:call|phone)\\s*(?:karo|lagao|milao)?$")
        callPattern.find(trimmed)?.let { match ->
            val recipient = (match.groupValues[1].ifBlank { match.groupValues[2] }).trim()
            if (recipient.isNotBlank() && !recipient.equals("me", ignoreCase = true)) {
                return ClassifiedCommand(
                    category = CommandCategory.CALL,
                    actionParam = recipient,
                    rawPrompt = trimmed
                )
            }
        }

        // 2. Messaging / SMS / WhatsApp
        val msgPattern = Regex("(?i)^(?:send\\s+(?:sms|message)\\s+to|message\\s+bhejo)\\s+([^:]+?)(?:\\s+(?:saying|message|ki)?\\s*(.+))?$|^(.+?)\\s+ko\\s+(?:whatsapp|sms|message)\\s+(?:bhejo|karo)(?:\\s*(.+))?$")
        msgPattern.find(trimmed)?.let { match ->
            val recipient = (match.groupValues[1].ifBlank { match.groupValues[3] }).trim()
            val message = (match.groupValues[2].ifBlank { match.groupValues[4] }).trim().ifBlank { "Hello" }
            if (recipient.isNotBlank()) {
                return ClassifiedCommand(
                    category = CommandCategory.SEND_MESSAGE,
                    actionParam = "$recipient:$message",
                    rawPrompt = trimmed
                )
            }
        }

        // 3. System Hardware & Controls
        if (lower.contains("flashlight") || lower.contains("torch") || lower.contains("टॉर्च")) {
            val turnOn = !lower.contains("off") && !lower.contains("band")
            return ClassifiedCommand(
                category = CommandCategory.SYSTEM_ACTION,
                actionParam = if (turnOn) "TORCH_ON" else "TORCH_OFF",
                rawPrompt = trimmed
            )
        }
        if (lower.contains("screenshot") || lower.contains("स्क्रीनशॉट")) {
            return ClassifiedCommand(
                category = CommandCategory.SYSTEM_ACTION,
                actionParam = "SCREENSHOT",
                rawPrompt = trimmed
            )
        }
        if (lower.contains("volume up") || lower.contains("volume down") || lower.startsWith("volume ") || lower.contains("mute")) {
            return ClassifiedCommand(
                category = CommandCategory.SYSTEM_ACTION,
                actionParam = "VOLUME:$trimmed",
                rawPrompt = trimmed
            )
        }
        if (lower.contains("wifi") || lower.contains("wi-fi") || lower.contains("bluetooth") || lower.contains("auto rotate") || lower.contains("dnd")) {
            return ClassifiedCommand(
                category = CommandCategory.SYSTEM_ACTION,
                actionParam = "SETTINGS:$trimmed",
                rawPrompt = trimmed
            )
        }

        // 4. On-Screen Navigation & Interaction
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
        // Ordinal selectors like "dusra video kholo", "open second video", "open 2nd website"
        val isOrdinal = lower.contains("second video") || lower.contains("2nd video") ||
                lower.contains("third video") || lower.contains("3rd video") ||
                lower.contains("dusra video") || lower.contains("doosra video") ||
                lower.contains("teesra video") || lower.contains("first video") ||
                lower.contains("second website") || lower.contains("2nd website") ||
                lower.contains("second result") || lower.contains("2nd result") ||
                lower.contains("pehli website") || lower.contains("pahla video")
        if (isOrdinal) {
            return ClassifiedCommand(
                category = CommandCategory.INTERACT_IN_APP,
                actionParam = trimmed,
                rawPrompt = trimmed
            )
        }

        // 5. Multi-step commands (e.g. "YouTube kholo aur Believer chalao", "WhatsApp खोलो और मेरे दोस्त को यह message भेजो")
        val compoundKholoAur = Regex("(?i)^(.+?)\\s+(?:kholo\\s+aur|open\\s+karo\\s+aur|kholke|khol\\s+ke|khol\\s+kar|open\\s+and|खोलो\\s+और|खोलकर|खोल\\s+के)\\s+(.+)$")
        compoundKholoAur.find(trimmed)?.let { match ->
            val firstPart = match.groupValues[1].trim()
            val secondPart = match.groupValues[2].trim()
            val app = appResolver.extractAppNameFromNaturalLanguage(firstPart)
            if (app.isNotBlank()) {
                val hasSearchInSecond = hasExplicitSearchKeyword(secondPart)
                val secondLower = secondPart.lowercase(Locale.ROOT)
                val isPlayInSecond = secondLower.contains("chalao") || secondLower.contains("play") || secondLower.contains("bajao") || secondLower.contains("sunao") || secondLower.contains("चलाओ") || secondLower.contains("बजाओ")
                val isMsgInSecond = secondLower.contains("message") || secondLower.contains("msg") || secondLower.contains("bhejo") || secondLower.contains("send") || secondLower.contains("भेजो")
                val isCallInSecond = secondLower.contains("call") || secondLower.contains("phone") || secondLower.contains("dial") || secondLower.contains("कॉल")

                val cleanQuery = secondPart
                    .replace("(?i)search\\s*(?:karo)?|chalao|play|bajao|sunao|dhoondo|सर्च\\s*करो|चलाओ|ढूंढो".toRegex(), "")
                    .trim()

                val step2Category = when {
                    isMsgInSecond -> CommandCategory.SEND_MESSAGE
                    isCallInSecond -> CommandCategory.CALL
                    isPlayInSecond -> CommandCategory.PLAY_MEDIA
                    hasSearchInSecond -> CommandCategory.SEARCH_IN_APP
                    else -> CommandCategory.INTERACT_IN_APP
                }

                return ClassifiedCommand(
                    category = CommandCategory.OPEN_APP,
                    targetApp = app,
                    query = null, // First step has strictly NO query
                    rawPrompt = trimmed,
                    isMultiStep = true,
                    steps = listOf(
                        ClassifiedStep(category = CommandCategory.OPEN_APP, targetApp = app, query = null),
                        ClassifiedStep(category = step2Category, targetApp = app, query = cleanQuery.ifBlank { null }, param = secondPart)
                    )
                )
            }
        }

        // 6. REQUIREMENT 2 & 3: OPEN_APP Command has HIGHEST PRIORITY
        // If the user does NOT explicitly say search words (search, dhoondo, find, etc.),
        // and uses app-opening indicators or direct app name:
        val hasSearchKeyword = hasExplicitSearchKeyword(trimmed)

        // Check if direct app launch query
        val isDirectLaunch = appResolver.isDirectAppLaunchQuery(trimmed)
        if (!hasSearchKeyword && isDirectLaunch) {
            val app = appResolver.extractAppNameFromNaturalLanguage(trimmed)
            if (app.isNotBlank()) {
                return ClassifiedCommand(
                    category = CommandCategory.OPEN_APP,
                    targetApp = app,
                    query = null, // Strictly null
                    rawPrompt = trimmed
                )
            }
        }

        // Check for "open [app]" or "[app] kholo" or "[app] chalao" (when referring directly to app)
        val extractedAppName = appResolver.extractAppNameFromNaturalLanguage(trimmed)
        if (!hasSearchKeyword && extractedAppName.isNotBlank()) {
            val res = appResolver.resolve(extractedAppName)
            if (res.confidence != com.example.utils.MatchConfidence.NONE) {
                // If it matches an installed app or official alias and no search keyword exists:
                // Check if user said "xyz chalao" where xyz is an app -> OPEN_APP
                return ClassifiedCommand(
                    category = CommandCategory.OPEN_APP,
                    targetApp = res.matchedApp?.appName ?: extractedAppName,
                    query = null, // Strictly null
                    rawPrompt = trimmed
                )
            }
        }

        // 7. REQUIREMENT 4: SEARCH_IN_APP ONLY when user explicitly said SEARCH
        // e.g. "YouTube par Arijit Singh search karo", "search cats on Google"
        if (hasSearchKeyword) {
            val compound = appResolver.parseCompoundCommand(trimmed)
            if (compound != null) {
                return ClassifiedCommand(
                    category = CommandCategory.SEARCH_IN_APP,
                    targetApp = compound.appName,
                    query = compound.searchQuery,
                    rawPrompt = trimmed
                )
            }

            // Direct search without compound
            val cleanQuery = trimmed
                .replace("(?i)^(?:search(?:\\s+for)?|look\\s+for|find|google|सर्च\\s*करो|ढूंढो|खोजो)\\s+".toRegex(), "")
                .replace("(?i)\\s+(?:search\\s+karo|dhoondo|khojo|सर्च\\s*करो|ढूंढो|खोजो)$".toRegex(), "")
                .trim()
            return ClassifiedCommand(
                category = CommandCategory.SEARCH_IN_APP,
                targetApp = "Google",
                query = cleanQuery.ifBlank { trimmed },
                rawPrompt = trimmed
            )
        }

        // 8. PLAY_MEDIA: "YouTube par Believer chalao", "Spotify par gana bajao"
        val isPlayMediaIntent = lower.contains("gana chalao") || lower.contains("video chalao") ||
                lower.contains("song play") || (lower.contains("play ") && (lower.contains("spotify") || lower.contains("youtube") || lower.contains("music"))) ||
                (lower.contains("chalao") && (lower.contains("youtube") || lower.contains("spotify") || lower.contains("gana") || lower.contains("video")))
        if (isPlayMediaIntent) {
            val compound = appResolver.parseCompoundCommand(trimmed)
            if (compound != null) {
                return ClassifiedCommand(
                    category = CommandCategory.PLAY_MEDIA,
                    targetApp = compound.appName,
                    query = compound.searchQuery,
                    rawPrompt = trimmed
                )
            }
        }

        // 9. Fallback to conversational / informational AI
        return ClassifiedCommand(
            category = CommandCategory.CONVERSATION,
            rawPrompt = trimmed
        )
    }
}
