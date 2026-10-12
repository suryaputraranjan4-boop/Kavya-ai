package com.example.agent

import java.util.Locale

/**
 * High-Level Semantic Intent Resolver for Kavya AI.
 * Implements "Command Meaning First, Action Second" (Requirements 1, 2, 3, 4, 7, 8, 15, 16):
 * - Meaning and semantic intent prior to execution.
 * - Robust handling of natural, mixed, and inverted word orders without rigid prefix matching.
 * - Deep context and pronoun/anaphora resolution ("ise", "usko", "iske baare me", "wahi").
 * - Prevention of conversational words ("hi", "call", "search", "open") being treated as apps.
 */
object IntentResolver {

    /**
     * Resolves user input into a definitive [CommandIntent] using contextual entity resolution.
     */
    fun resolve(userInput: String, context: ContextEngine): CommandIntent {
        val trimmed = userInput.trim()
            .removeSurrounding("\"", "\"")
            .removeSurrounding("'", "'")
            .trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        if (trimmed.isEmpty()) {
            return CommandIntent.Conversation("")
        }

        // 1. Explicit Memory Commands ("Ye yaad rakhna", "Isko remember karo", "Aage se aise karna")
        if (UserCommandClassifier.isExplicitMemoryCommand(trimmed)) {
            return CommandInterpreter.interpret(trimmed)
        }

        // 2. Stop / Sleep / Wake System Commands
        val baseInterpreted = CommandInterpreter.interpret(trimmed)
        if (baseInterpreted is CommandIntent.Stop || baseInterpreted is CommandIntent.Sleep || baseInterpreted is CommandIntent.Wake) {
            return baseInterpreted
        }

        // 3. Conversational Disambiguation Guard:
        // "Hi Kavya, search GTA 5 in Google" -> strip conversational prefix!
        var cleanedInput = trimmed
        val greetingPrefixes = listOf(
            "(?i)^(?:hi\\s+kavya|hello\\s+kavya|hey\\s+kavya|kavya\\s+suno|suno\\s+kavya|kavya\\s+ji|kavya|namaste\\s+kavya)[,:]?\\s*",
            "(?i)^(?:hi|hello|hey|namaste|suno|please)[,:]?\\s*"
        )
        for (gp in greetingPrefixes) {
            val stripped = cleanedInput.replace(gp.toRegex(), "").trim()
            if (stripped.isNotBlank()) {
                cleanedInput = stripped
                break
            }
        }

        val cleanedLower = cleanedInput.lowercase(Locale.ROOT)

        // 4. Image search follow-up on active context
        // Example: "Google me Kavya AI search karo" -> "Ab iske baare me images dekho"
        val isImageFollowUp = (cleanedLower.contains("image") || cleanedLower.contains("images") || cleanedLower.contains("photo") || cleanedLower.contains("तस्वीर")) &&
                (cleanedLower.contains("iske") || cleanedLower.contains("uske") || cleanedLower.contains("iska") || cleanedLower.contains("iske baare me") || cleanedLower.contains("this"))

        if (isImageFollowUp && context.activeTargetSubject != null) {
            val queryWithImages = "${context.activeTargetSubject} images"
            context.updateContext(appName = "Google", searchQuery = queryWithImages)
            return CommandIntent.WebSearch(
                query = queryWithImages,
                engineOrApp = "Google"
            )
        }

        // 5. Inverted and Natural Google Search Queries (Requirement 1, 3, 15)
        // “Open Google and search GTA 5”, “Search GTA 5 in Google”, “Google pe GTA 5 ढूंढो”,
        // “GTA 5 Google kar”, “Google में जाकर GTA 5 search कर”, “मुझे Google पर GTA 5 देखना है”,
        // “GTA 5 के बारे में Google पर search करके दिखाओ”, “Google में इसे ढूंढ”, “इसे Google कर”
        val isGoogleExplicit = cleanedLower.contains("google") || cleanedLower.contains("गूगल")
        val isSearchVerb = cleanedLower.contains("search") || cleanedLower.contains("dhoondo") || cleanedLower.contains("dhundo") ||
                cleanedLower.contains("google kar") || cleanedLower.contains("google karo") || cleanedLower.contains("khojo") ||
                cleanedLower.contains("pata lagao") || cleanedLower.contains("search nikal") || cleanedLower.contains("search nikaal") ||
                cleanedLower.contains("online dekh") || cleanedLower.contains("dekhna hai") || cleanedLower.contains("find ")

        if (isGoogleExplicit || isSearchVerb) {
            val isOtherDomain = (cleanedLower.contains("whatsapp") && !cleanedLower.contains("search")) ||
                    (cleanedLower.contains("spotify") && !cleanedLower.contains("search") && !cleanedLower.contains("dhoondo")) ||
                    ((cleanedLower.contains("call") || cleanedLower.contains("phone")) && !cleanedLower.contains("search")) ||
                    (cleanedLower.contains("message") && !cleanedLower.contains("search"))

            if (!isOtherDomain) {
                val entities = EntityResolver.extract(cleanedInput, context)
                var query = entities.targetQuery

                // Resolve pronoun references
                if (query.isNullOrBlank() || query.lowercase(Locale.ROOT) in listOf("ise", "usko", "isko", "this", "that", "it")) {
                    query = context.resolveReference("ise") ?: context.activeTargetSubject ?: context.lastSearchQuery
                }

                if (!query.isNullOrBlank()) {
                    val finalQuery = if (entities.isImageSearch && !query.lowercase(Locale.ROOT).contains("image")) {
                        "$query images"
                    } else query

                    val targetApp = entities.appMention ?: "Google"
                    context.updateContext(appName = targetApp, searchQuery = finalQuery, subject = query)
                    return CommandIntent.WebSearch(
                        query = finalQuery,
                        engineOrApp = targetApp
                    )
                }
            }
        }

        // 6. Direct UI Navigation / In-App Follow up
        val contextualFollowUp = context.resolveFollowUp(cleanedInput)
        if (contextualFollowUp != null) {
            return CommandIntent.InteractInApp(
                action = contextualFollowUp.resolvedAction.name,
                param = cleanedInput
            )
        }

        // 7. Fallback to CommandInterpreter with updated cleaned context
        val interpreted = CommandInterpreter.interpret(cleanedInput)

        // Keep context engine updated with newly detected target entities
        when (interpreted) {
            is CommandIntent.WebSearch -> {
                context.updateContext(
                    appName = if (interpreted.engineOrApp.isNotBlank()) interpreted.engineOrApp else "Google",
                    searchQuery = interpreted.query,
                    subject = interpreted.query
                )
            }
            is CommandIntent.SendMessage -> {
                context.updateContext(
                    appName = interpreted.app,
                    contact = interpreted.recipient,
                    subject = interpreted.recipient
                )
            }
            is CommandIntent.CallContact -> {
                context.updateContext(
                    appName = if (interpreted.isWhatsApp) "WhatsApp" else "Phone",
                    contact = interpreted.contactName,
                    subject = interpreted.contactName
                )
            }
            is CommandIntent.PlayMedia -> {
                context.updateContext(
                    appName = interpreted.app,
                    media = interpreted.mediaQuery,
                    subject = interpreted.mediaQuery
                )
            }
            is CommandIntent.OpenApp -> {
                context.updateContext(
                    appName = interpreted.appName
                )
            }
            else -> {}
        }

        return interpreted
    }
}
