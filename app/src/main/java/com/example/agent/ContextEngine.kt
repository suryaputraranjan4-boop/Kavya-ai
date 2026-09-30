package com.example.agent

import android.util.Log
import java.util.Locale

/**
 * Tracks active task context across multi-step conversational interactions.
 * Kept strictly distinct from permanent long-term memory.
 */
class ContextEngine {

    companion object {
        private const val TAG = "KavyaContextEngine"
        private const val CONTEXT_EXPIRATION_MS = 5 * 60 * 1000L // 5 minutes
    }

    var activeTargetAppName: String? = null
        private set

    var activePackageName: String? = null
        private set

    var activeWebsiteUrl: String? = null
        private set

    var activeSubContext: String? = null // e.g. "reels", "chess_game", "search_results", "chat_window"
        private set

    var activeTargetSubject: String? = null // e.g. "Rahul", "Minecraft", "Kavya AI"
        private set

    var lastSearchQuery: String? = null
        private set

    var lastContactName: String? = null
        private set

    var lastPlayedMedia: String? = null
        private set

    var lastActionTimestamp: Long = 0
        private set

    fun updateContext(
        appName: String? = null,
        packageName: String? = null,
        url: String? = null,
        subContext: String? = null,
        subject: String? = null,
        searchQuery: String? = null,
        contact: String? = null,
        media: String? = null
    ) {
        if (!appName.isNullOrBlank()) activeTargetAppName = appName
        if (!packageName.isNullOrBlank()) activePackageName = packageName
        if (!url.isNullOrBlank()) activeWebsiteUrl = url
        if (!subContext.isNullOrBlank()) activeSubContext = subContext
        if (!subject.isNullOrBlank()) activeTargetSubject = subject
        if (!searchQuery.isNullOrBlank()) {
            lastSearchQuery = searchQuery
            if (activeTargetSubject.isNullOrBlank()) activeTargetSubject = searchQuery
        }
        if (!contact.isNullOrBlank()) lastContactName = contact
        if (!media.isNullOrBlank()) lastPlayedMedia = media
        lastActionTimestamp = System.currentTimeMillis()
        Log.d(TAG, "Context updated: app=$activeTargetAppName, pkg=$activePackageName, url=$activeWebsiteUrl, sub=$activeSubContext, subj=$activeTargetSubject, q=$lastSearchQuery")
    }

    /**
     * Resolves anaphoric pronouns and references ("ise", "usko", "iske baare me", "wahi wala", "this", "that").
     */
    fun resolveReference(reference: String): String? {
        val lower = reference.lowercase(Locale.ROOT).trim()
        val isPronoun = lower in listOf(
            "ise", "isse", "isko", "use", "usse", "usko", "iske", "iske bare me", "iske baare mein", "iske baare me",
            "uske", "uske baare me", "uske baare mein", "iska", "uska", "wahi", "wahi wala", "ye wala", "yeh wala",
            "vo wala", "vahi", "this", "that", "it", "him", "her", "there", "these", "those"
        )
        if (!isPronoun) return null

        return activeTargetSubject ?: lastSearchQuery ?: lastContactName ?: lastPlayedMedia
    }

    fun isContextActive(): Boolean {
        if (activeTargetAppName == null && activeWebsiteUrl == null) return false
        val elapsed = System.currentTimeMillis() - lastActionTimestamp
        return elapsed < CONTEXT_EXPIRATION_MS
    }

    fun clearTaskContext() {
        activeTargetAppName = null
        activePackageName = null
        activeWebsiteUrl = null
        activeSubContext = null
        activeTargetSubject = null
        lastActionTimestamp = 0
    }

    /**
     * Resolves short follow-up commands in the context of the active session.
     * Examples:
     * - "Reels kholo" when Instagram is active -> Target: Instagram, Action: Reels
     * - "Scroll karo" when Reels or Web is active -> Target: Active app, Action: Scroll
     * - "E5 chalo" when chess.com is active -> Target: chess.com, Action: Chess move
     * - "Pehli website kholo" when Google search results are active -> Target: Google / Chrome, Action: Select first result
     */
    fun resolveFollowUp(userInput: String): ContextualFollowUpResult? {
        if (!isContextActive()) return null

        val lower = userInput.trim().lowercase(Locale.ROOT)

        // 1. Chess moves in context of active chess game
        if (activeWebsiteUrl?.contains("chess", true) == true || activeTargetAppName?.contains("chess", true) == true) {
            val chessMoveRegex = "^([a-h][1-8]|[nbrqk][a-h]?[1-8]?x?[a-h][1-8]|o-o|o-o-o|e4|e5|d4|d5|c4|c5|nf3|nc6|bc4|bb5)(\\s*(chalo|khelo|move|play))?$".toRegex()
            val cleanMove = lower.replace("chalo", "").replace("khelo", "").replace("move", "").replace("play", "").trim()
            if (chessMoveRegex.matches(lower) || cleanMove.matches("^[a-h][1-8]$".toRegex()) || cleanMove.matches("^[nbrqk][a-h1-8]{2,4}$".toRegex())) {
                return ContextualFollowUpResult(
                    resolvedAction = UniversalActionType.CHESS_MOVE,
                    targetAppOrUrl = activeWebsiteUrl ?: "https://chess.com",
                    param = cleanMove.uppercase(Locale.ROOT),
                    spokenFeedback = "Playing $cleanMove."
                )
            }
        }

        // 2. Reels in context of Instagram
        if (activeTargetAppName?.contains("instagram", true) == true || activePackageName?.contains("instagram", true) == true) {
            if (lower.contains("reel") || lower.contains("reels")) {
                updateContext(subContext = "reels")
                return ContextualFollowUpResult(
                    resolvedAction = UniversalActionType.TAP,
                    targetAppOrUrl = "Instagram",
                    param = "Reels",
                    spokenFeedback = "Reels khol rahi hoon."
                )
            }
        }

        // 3. Scroll in context of active app/reels/webpage
        if (lower == "scroll" || lower == "scroll karo" || lower == "next" || lower == "aage badhao" || lower == "scroll down") {
            return ContextualFollowUpResult(
                resolvedAction = UniversalActionType.SCROLL,
                targetAppOrUrl = activeTargetAppName ?: "Current App",
                param = "DOWN",
                spokenFeedback = "Scrolling."
            )
        }
        if (lower == "scroll up" || lower == "upar karo" || lower == "previous" || lower == "peeche") {
            return ContextualFollowUpResult(
                resolvedAction = UniversalActionType.SCROLL,
                targetAppOrUrl = activeTargetAppName ?: "Current App",
                param = "UP",
                spokenFeedback = "Scrolling up."
            )
        }

        // 4. Click first result / Play first video
        if (lower.contains("pehla") || lower.contains("first") || lower.contains("1st")) {
            if (lower.contains("video") || activeTargetAppName?.contains("youtube", true) == true) {
                return ContextualFollowUpResult(
                    resolvedAction = UniversalActionType.SELECT,
                    targetAppOrUrl = "YouTube",
                    param = "FIRST_RESULT",
                    spokenFeedback = "Pehla video play kar rahi hoon."
                )
            }
            if (lower.contains("website") || lower.contains("link") || activeTargetAppName?.contains("chrome", true) == true || activeTargetAppName?.contains("google", true) == true) {
                return ContextualFollowUpResult(
                    resolvedAction = UniversalActionType.SELECT,
                    targetAppOrUrl = activeTargetAppName ?: "Browser",
                    param = "FIRST_RESULT",
                    spokenFeedback = "Pehli website khol rahi hoon."
                )
            }
        }

        return null
    }
}

data class ContextualFollowUpResult(
    val resolvedAction: UniversalActionType,
    val targetAppOrUrl: String,
    val param: String,
    val spokenFeedback: String
)
