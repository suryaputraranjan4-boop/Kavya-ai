package com.example.agent

import java.util.Locale

/**
 * Semantic Entity Extractor for Kavya AI.
 * Extracts intent, target apps, subjects, search queries, media targets, contacts,
 * and pronouns from unstructured natural language (Hindi, English, Hinglish).
 */
object EntityResolver {

    data class ExtractedEntities(
        val rawInput: String,
        val appMention: String?,
        val targetQuery: String?,
        val contactMention: String?,
        val mediaMention: String?,
        val isImageSearch: Boolean = false,
        val isVideoSearch: Boolean = false,
        val pronounReference: String?,
        val actionTypeHint: UniversalActionType? = null
    )

    private val PRONOUN_PATTERNS = listOf(
        "iske baare mein", "iske baare me", "iske bare me", "iske bare mein",
        "uske baare mein", "uske baare me", "uske bare me",
        "iska pata lagao", "iska search nikal", "iska search nikaal",
        "ise", "isse", "isko", "use", "usse", "usko", "iska", "uska",
        "iske", "uske", "usme", "wahi wala", "ye wala", "yeh wala", "vo wala", "woh wala",
        "wahi", "vahi", "this", "that", "it", "him", "her", "there"
    )

    fun extract(input: String, context: ContextEngine? = null): ExtractedEntities {
        val trimmed = input.trim()
            .removeSurrounding("\"", "\"")
            .removeSurrounding("'", "'")
            .trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        // 1. Detect App Mentions
        var appMention: String? = when {
            lower.contains("whatsapp") || lower.contains("व्हाट्सएप") || lower.contains("वाट्सएप") -> "WhatsApp"
            lower.contains("spotify") || lower.contains("स्पॉटिफ़ाई") || lower.contains("स्पॉटीफाई") -> "Spotify"
            lower.contains("youtube") || lower.contains("यूट्यूब") || lower.contains("yt") -> "YouTube"
            lower.contains("chrome") || lower.contains("क्रोम") || lower.contains("browser") || lower.contains("ब्राउज़र") -> "Google Chrome"
            lower.contains("google") || lower.contains("गूगल") -> "Google"
            lower.contains("phone") || lower.contains("dialer") -> "Phone"
            lower.contains("play store") || lower.contains("playstore") || lower.contains("प्ले स्टोर") -> "Google Play Store"
            lower.contains("instagram") || lower.contains("इंस्टाग्राम") -> "Instagram"
            else -> null
        }

        // 2. Detect Image Search Intent
        val isImageSearch = lower.contains("image") || lower.contains("images") || lower.contains("photo") ||
                lower.contains("photos") || lower.contains("तस्वीर") || lower.contains("फोटो") ||
                lower.contains("pics") || lower.contains("pictures")

        // 3. Detect Video Search Intent
        val isVideoSearch = lower.contains("video") || lower.contains("videos") || lower.contains("वीडियो")

        // 4. Detect Pronoun / Anaphoric References
        var detectedPronoun: String? = null
        for (pattern in PRONOUN_PATTERNS) {
            if (lower.contains(pattern)) {
                detectedPronoun = pattern
                break
            }
        }

        // 5. Query / Subject Extraction for Search commands
        var targetQuery: String? = null
        val isSearchLike = lower.contains("search") || lower.contains("dhoondo") || lower.contains("dhundo") ||
                lower.contains("google kar") || lower.contains("google karke") || lower.contains("khojo") ||
                lower.contains("find") || lower.contains("pata lagao") || lower.contains("online dekh") ||
                lower.contains("dekhna hai") || lower.contains("images dekh") || lower.contains("images dikha") ||
                lower.contains("search nikal")

        if (isSearchLike) {
            // Check if referring to prior context
            if (detectedPronoun != null && context != null) {
                targetQuery = context.resolveReference(detectedPronoun) ?: context.activeTargetSubject ?: context.lastSearchQuery
            }

            if (targetQuery == null) {
                // Strip noise phrases
                targetQuery = cleanSearchQuery(trimmed)
            }
        }

        // 6. Contact Detection
        var contactMention: String? = null
        val candidates = listOf(
            "didi", "rohan", "mummy", "mom", "papa", "dad", "bhai", "sister",
            "brother", "rahul", "priya", "amit", "neha", "boss", "friend"
        )
        for (c in candidates) {
            if (lower.contains(c)) {
                contactMention = c.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                break
            }
        }

        // 7. Media Mention
        var mediaMention: String? = null
        if (appMention == "Spotify" || appMention == "YouTube" || lower.contains("song") || lower.contains("chalao") || lower.contains("play")) {
            mediaMention = cleanMediaQuery(trimmed)
        }

        return ExtractedEntities(
            rawInput = trimmed,
            appMention = appMention,
            targetQuery = targetQuery,
            contactMention = contactMention,
            mediaMention = mediaMention,
            isImageSearch = isImageSearch,
            isVideoSearch = isVideoSearch,
            pronounReference = detectedPronoun
        )
    }

    private fun cleanSearchQuery(input: String): String {
        return input
            .replace("(?i)^(?:kavya\\s+)?(?:please\\s+)?(?:hey\\s+kavya\\s+)?(?:hi\\s+kavya\\s+)?".toRegex(), "")
            .replace("(?i)^(?:mujhe\\s+)?(?:google|chrome|youtube|yt|browser)\\s*(?:par|pe|me|mein|on|in|पर|पे|में)?\\s*".toRegex(), "")
            .replace("(?i)(?:google|chrome|youtube|yt|browser)\\s+(?:par|pe|me|mein|on|in|पर|पे|में)?\\s*".toRegex(), "")
            .replace("(?i)\\s+(?:google|chrome|youtube|yt|browser)\\s*(?:par|pe|me|mein|on|in|पर|पे|में)?$".toRegex(), "")
            .replace("(?i)^(?:open\\s+google\\s+and\\s+search(?:\\s+for)?|search(?:\\s+for)?|google\\s+me\\s+jakar|google\\s+me\\s+jaakar)\\s*".toRegex(), "")
            .replace("(?i)^(?:dhoondo|dhundo|search|find|look\\s+for|khojo)\\s+".toRegex(), "")
            .replace("(?i)\\s+(?:google\\s+kar|google\\s+karo|search\\s+kar|search\\s+karo|search\\s+karke\\s+dikhao|search\\s+karke\\s+batao|dhoondo|dhundo|khojo|dekhna\\s+hai|dekh\\s+lo|dekh|dikhao|dikha|करो|कर)$".toRegex(), "")
            .replace("(?i)^(?:ab\\s+)?(?:iske|uske|iske\\s+baare\\s+me|iske\\s+bare\\s+me)\\s+(?:images|photo|photos|video|videos)\\s+(?:dekho|dikhao|dikhaye)?$".toRegex(), "")
            .replace("(?i)\\s+(?:ke\\s+baare\\s+mein|ke\\s+baare\\s+me|ke\\s+bare\\s+me)$".toRegex(), "")
            .trim(' ', ',', '.', ':', ';', '!', '?', '"', '\'')
    }

    private fun cleanMediaQuery(input: String): String {
        return input
            .replace("(?i)^(?:kavya\\s+)?(?:please\\s+)?".toRegex(), "")
            .replace("(?i)(?:spotify|youtube|yt)\\s+(?:par|pe|me|mein|on|in)?\\s*".toRegex(), "")
            .replace("(?i)\\s+(?:spotify|youtube|yt)\\s*(?:par|pe|me|mein|on|in)?$".toRegex(), "")
            .replace("(?i)^(?:play|chalao|bajao|sunao|open)\\s+".toRegex(), "")
            .replace("(?i)\\s+(?:chalao|chala\\s+do|play\\s+karo|play|bajao|baja\\s+do|sunao|laga\\s+do|lagao)$".toRegex(), "")
            .replace("(?i)\\s+(?:ka|ki|ke)?\\s*(?:song|gana|gaana|music|video|track)$".toRegex(), "")
            .trim(' ', ',', '.', ':', ';', '!', '?', '"', '\'')
    }
}
