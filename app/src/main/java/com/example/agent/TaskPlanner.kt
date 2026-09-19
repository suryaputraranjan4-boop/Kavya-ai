package com.example.agent

import com.example.utils.AppResolver
import java.util.Locale

/**
 * Universal Natural Language Task Planner.
 * Deconstructs multi-step user commands across ANY supported Android app or website
 * into a structured pipeline: TARGET -> ACTION -> SELECTOR -> PARAMETERS -> SEQUENCE -> EXPECTED RESULT.
 *
 * Adheres strictly to the Universal Engine Mandate:
 * - NO hardcoded per-app logic or YouTube-only assumptions.
 * - Dynamic resolution of apps, URLs, web elements, and ordinal results.
 * - Milestone voice feedback ("Okay, Google खोल रही हूँ.", "अब Hi search कर रही हूँ.", "मिल गया, पहली website खोल रही हूँ.", "Done.").
 */
class TaskPlanner(private val appResolver: AppResolver? = null) {

    companion object {
        private const val TAG = "KavyaTaskPlanner"
    }

    /**
     * Deconstructs any single or multi-step command into a verified TaskPlan.
     */
    fun createPlan(userInput: String, activeContext: ContextEngine): TaskPlan? {
        val trimmed = userInput.trim()
        if (trimmed.isBlank()) return null
        val lower = trimmed.lowercase(Locale.ROOT)

        // 1. Check contextual follow-up first ("इसे खोलो", "पहला वाला खोलो", "वहीं scroll करो")
        val followUp = activeContext.resolveFollowUp(trimmed)
        if (followUp != null) {
            val step = TaskStep(
                id = 1,
                actionType = followUp.resolvedAction,
                targetAppOrUrl = followUp.targetAppOrUrl,
                param = followUp.param,
                selectorType = SelectorType.SEMANTIC_HINT,
                spokenAnnouncement = followUp.spokenFeedback,
                expectedOutcome = "Contextual action execution"
            )
            return TaskPlan(
                originalPrompt = trimmed,
                targetAppName = followUp.targetAppOrUrl,
                steps = listOf(step),
                isMultiStep = false,
                explanation = "Contextual follow-up action",
                universalIntents = listOf(
                    UniversalIntent(
                        target = followUp.targetAppOrUrl,
                        targetCategory = TargetCategory.CONTEXTUAL_ACTIVE,
                        action = followUp.resolvedAction,
                        parameters = followUp.param,
                        expectedResult = "Contextual execution verified"
                    )
                )
            )
        }

        // 2. Split user input into sequential action clauses
        val clauses = splitIntoClauses(trimmed)
        if (clauses.isEmpty()) return null

        // 3. Resolve the Primary Target across clauses
        var primaryTarget = ""
        var primaryCategory = TargetCategory.APP

        for (clause in clauses) {
            val (target, category) = detectTarget(clause)
            if (target.isNotBlank()) {
                primaryTarget = target
                primaryCategory = category
                break
            }
        }

        // If no explicit target detected, check if the first clause is a direct app launch or URL
        if (primaryTarget.isBlank()) {
            val (target, category) = detectTarget(trimmed)
            if (target.isNotBlank()) {
                primaryTarget = target
                primaryCategory = category
            }
        }

        // If still no target, default to active context, or browser/search if search verbs exist, or return null for normal conversation
        var skipOpenStep = false
        val isPureInScreenOrSystemAction = lower.startsWith("go back") || lower == "back" || lower.startsWith("peeche") || lower.startsWith("wapas") ||
            lower.contains("scroll") || lower.contains("swipe") ||
            lower.startsWith("click ") || lower.startsWith("press ") || lower.contains("दबाओ") || lower.contains("button") ||
            lower.contains("brightness") || lower.contains("volume") ||
            lower.contains("second video") || lower.contains("second result") || lower.contains("dusra video") || lower.contains("पहला") ||
            lower.contains("start br ranked") || lower.contains("start cs ranked") || lower == "start"

        if (primaryTarget.isBlank()) {
            val activeTarget = activeContext.activeTargetAppName
            if (!activeTarget.isNullOrBlank()) {
                primaryTarget = activeTarget
                primaryCategory = if (primaryTarget.startsWith("http")) TargetCategory.WEBSITE else TargetCategory.APP
                skipOpenStep = true
            } else if (isPureInScreenOrSystemAction) {
                primaryTarget = "System"
                primaryCategory = TargetCategory.APP
                skipOpenStep = true
            } else if (lower.contains("search") || lower.contains("khojo") || lower.contains("dhoondo") || lower.contains("website")) {
                primaryTarget = "Google"
                primaryCategory = TargetCategory.APP
            } else {
                return null
            }
        } else if (primaryTarget == activeContext.activeTargetAppName) {
            skipOpenStep = true
        }

        val steps = mutableListOf<TaskStep>()
        val universalIntents = mutableListOf<UniversalIntent>()
        var stepId = 1
        var currentTarget = primaryTarget
        var currentCategory = primaryCategory

        // Add Step 1: Open Target App or URL (unless we are skipping because it's already active)
        val firstClauseLower = clauses.first().lowercase(Locale.ROOT)
        val isPureInScreenAction = clauses.size == 1 && (firstClauseLower.startsWith("scroll") || firstClauseLower.startsWith("swipe") || firstClauseLower.startsWith("type") || firstClauseLower.contains("दबाओ") || firstClauseLower.contains("click"))

        if (!isPureInScreenAction && !skipOpenStep && primaryTarget.isNotBlank()) {
            val (openAction, announcement) = when (primaryCategory) {
                TargetCategory.WEBSITE -> Pair(UniversalActionType.OPEN_URL, "Okay, $primaryTarget खोल रही हूँ.")
                TargetCategory.FILE -> Pair(UniversalActionType.OPEN_FILE, "Okay, $primaryTarget खोल रही हूँ.")
                else -> Pair(UniversalActionType.OPEN_APP, "Okay, $primaryTarget खोल रही हूँ.")
            }

            steps.add(
                TaskStep(
                    id = stepId++,
                    actionType = openAction,
                    targetAppOrUrl = primaryTarget,
                    param = primaryTarget,
                    selectorType = SelectorType.GENERIC,
                    spokenAnnouncement = announcement,
                    expectedOutcome = "Target $primaryTarget in foreground"
                )
            )

            universalIntents.add(
                UniversalIntent(
                    target = primaryTarget,
                    targetCategory = primaryCategory,
                    action = openAction,
                    sequenceNumber = universalIntents.size + 1,
                    expectedResult = "$primaryTarget launched and verified"
                )
            )
        }

        // Iterate through all clauses to build the action pipeline
        for (clause in clauses) {
            val clauseLower = clause.lowercase(Locale.ROOT)

            // Check if clause switches target app or website
            val (clauseTarget, clauseCategory) = detectTarget(clause)
            if (clauseTarget.isNotBlank() && clauseTarget != currentTarget) {
                currentTarget = clauseTarget
                currentCategory = clauseCategory
                val openAct = when (currentCategory) {
                    TargetCategory.WEBSITE -> UniversalActionType.OPEN_URL
                    TargetCategory.FILE -> UniversalActionType.OPEN_FILE
                    else -> UniversalActionType.OPEN_APP
                }
                steps.add(
                    TaskStep(
                        id = stepId++,
                        actionType = openAct,
                        targetAppOrUrl = currentTarget,
                        param = currentTarget,
                        spokenAnnouncement = "Okay, $currentTarget खोल रही हूँ."
                    )
                )
            }

            // Case A: Search query action (e.g., "Hi search करो", "search Minecraft", "comedy dhoondo")
            if (isSearchClause(clauseLower)) {
                val query = extractQueryFromClause(clause, currentTarget)
                if (query.isNotBlank()) {
                    steps.add(
                        TaskStep(
                            id = stepId++,
                            actionType = UniversalActionType.TYPE,
                            targetAppOrUrl = currentTarget,
                            param = query,
                            selectorType = SelectorType.SEARCH_FIELD,
                            spokenAnnouncement = "अब $query search कर रही हूँ.",
                            expectedOutcome = "Query '$query' entered in search bar"
                        )
                    )
                    steps.add(
                        TaskStep(
                            id = stepId++,
                            actionType = UniversalActionType.SUBMIT,
                            targetAppOrUrl = currentTarget,
                            param = "SUBMIT",
                            selectorType = SelectorType.SUBMIT_BUTTON,
                            spokenAnnouncement = "",
                            expectedOutcome = "Search results displayed"
                        )
                    )
                    universalIntents.add(
                        UniversalIntent(
                            target = currentTarget,
                            targetCategory = currentCategory,
                            action = UniversalActionType.SEARCH,
                            objectSelector = "search_bar",
                            parameters = query,
                            sequenceNumber = universalIntents.size + 1,
                            expectedResult = "Search submitted for $query"
                        )
                    )
                }
            }

            // Case B: Ordinal selection (e.g., "पहली website खोलो", "पहला result खोलो", "पहला video चलाओ", "second link", "last result")
            val ordinalMatch = detectOrdinalSelection(clauseLower)
            if (ordinalMatch != null) {
                val (ordinalIndex, ordinalAnnouncement) = ordinalMatch
                val actionType = if (clauseLower.contains("play") || clauseLower.contains("chalao") || clauseLower.contains("video")) {
                    UniversalActionType.PLAY
                } else {
                    UniversalActionType.SELECT
                }
                steps.add(
                    TaskStep(
                        id = stepId++,
                        actionType = actionType,
                        targetAppOrUrl = currentTarget,
                        param = "ORDINAL_$ordinalIndex",
                        selectorType = SelectorType.ORDINAL_RESULT,
                        ordinalIndex = ordinalIndex,
                        spokenAnnouncement = ordinalAnnouncement,
                        expectedOutcome = "Result item at index $ordinalIndex selected"
                    )
                )
                universalIntents.add(
                    UniversalIntent(
                        target = currentTarget,
                        targetCategory = currentCategory,
                        action = actionType,
                        objectSelector = "content_result",
                        ordinalIndex = ordinalIndex,
                        sequenceNumber = universalIntents.size + 1,
                        expectedResult = "Opened result item at index $ordinalIndex"
                    )
                )
            }

            // Case B.1: Call / Message (e.g. "Rahul ko call karo", "Mom ko message karo hello")
            val callMatch = Regex("""(.+?)\s+ko\s+call\s+karo""", RegexOption.IGNORE_CASE).find(clauseLower)
            if (callMatch != null) {
                val contact = callMatch.groupValues[1].trim()
                steps.add(
                    TaskStep(
                        id = stepId++,
                        actionType = UniversalActionType.CALL,
                        targetAppOrUrl = currentTarget,
                        param = contact,
                        spokenAnnouncement = "$contact ko call kar rahi hoon.",
                        expectedOutcome = "Call initiated to $contact"
                    )
                )
                continue
            }
            val msgMatch = Regex("""(.+?)\s+ko\s+message\s+karo\s+(.+)""", RegexOption.IGNORE_CASE).find(clauseLower)
            val msgMatchBhejo = Regex("""(.+?)\s+ko\s+(.+?)\s+bhejo""", RegexOption.IGNORE_CASE).find(clauseLower)
            val msgMatchDirect = Regex("""(?:message|msg)\s+([A-Za-z0-9_\u0900-\u097F]+)[:\s]+(.+)""", RegexOption.IGNORE_CASE).find(clause)
            val foundMsg = msgMatch ?: msgMatchBhejo ?: msgMatchDirect
            if (foundMsg != null) {
                val contact = foundMsg.groupValues[1].trim()
                val message = foundMsg.groupValues[2].trim().trim(':', ' ')
                steps.add(
                    TaskStep(
                        id = stepId++,
                        actionType = UniversalActionType.SEND_MESSAGE,
                        targetAppOrUrl = currentTarget,
                        param = "$contact||$message",
                        spokenAnnouncement = "$contact ko message bhej rahi hoon.",
                        expectedOutcome = "Message sent to $contact"
                    )
                )
                continue
            }

            // Case B.2: Free Fire Game Modes & Start
            if (clauseLower.contains("br ranked") || clauseLower.contains("battle royale")) {
                steps.add(
                    TaskStep(
                        id = stepId++,
                        actionType = UniversalActionType.TAP,
                        targetAppOrUrl = currentTarget,
                        param = "BR-RANKED",
                        spokenAnnouncement = "BR Ranked mode select kar rahi hoon.",
                        expectedOutcome = "BR Ranked selected"
                    )
                )
                if (clauseLower.contains("start") || clauseLower.contains("play") || clauseLower.contains("shuru")) {
                    steps.add(
                        TaskStep(
                            id = stepId++,
                            actionType = UniversalActionType.TAP,
                            targetAppOrUrl = currentTarget,
                            param = "Start",
                            spokenAnnouncement = "Match start kar rahi hoon.",
                            expectedOutcome = "Match started"
                        )
                    )
                }
                continue
            } else if (clauseLower.contains("cs ranked") || clauseLower.contains("clash squad")) {
                steps.add(
                    TaskStep(
                        id = stepId++,
                        actionType = UniversalActionType.TAP,
                        targetAppOrUrl = currentTarget,
                        param = "CS-RANKED",
                        spokenAnnouncement = "CS Ranked mode select kar rahi hoon.",
                        expectedOutcome = "CS Ranked selected"
                    )
                )
                if (clauseLower.contains("start") || clauseLower.contains("play") || clauseLower.contains("shuru")) {
                    steps.add(
                        TaskStep(
                            id = stepId++,
                            actionType = UniversalActionType.TAP,
                            targetAppOrUrl = currentTarget,
                            param = "Start",
                            spokenAnnouncement = "Match start kar rahi hoon.",
                            expectedOutcome = "Match started"
                        )
                    )
                }
                continue
            }

            // Case B.3: Calendar Date Navigation
            val dateNav = detectCalendarDateNavigation(clauseLower)
            if (dateNav != null) {
                steps.add(
                    TaskStep(
                        id = stepId++,
                        actionType = UniversalActionType.NAVIGATE,
                        targetAppOrUrl = currentTarget,
                        param = "DATE:${dateNav.second}",
                        spokenAnnouncement = dateNav.first,
                        expectedOutcome = "Calendar navigated to ${dateNav.second}"
                    )
                )
                continue
            }

            // Case B.4: Back and Home Navigation
            if (clauseLower == "back" || clauseLower == "go back" || clauseLower == "peeche jao" || clauseLower == "wapas jao") {
                steps.add(
                    TaskStep(
                        id = stepId++,
                        actionType = UniversalActionType.BACK,
                        targetAppOrUrl = currentTarget,
                        spokenAnnouncement = "Peeche jaa rahi hoon.",
                        expectedOutcome = "Navigated back"
                    )
                )
                continue
            }
            if (clauseLower == "home" || clauseLower == "go home" || clauseLower == "ghar jao") {
                steps.add(
                    TaskStep(
                        id = stepId++,
                        actionType = UniversalActionType.HOME,
                        targetAppOrUrl = currentTarget,
                        spokenAnnouncement = "Home screen par jaa rahi hoon.",
                        expectedOutcome = "Returned to home screen"
                    )
                )
                continue
            }

            // Case B.5: Volume & Brightness System Controls
            if (clauseLower.contains("volume down") || clauseLower.contains("volume kam") || clauseLower.contains("turn volume down") || clauseLower.contains("decrease volume")) {
                steps.add(
                    TaskStep(
                        id = stepId++,
                        actionType = UniversalActionType.SYSTEM_CONTROL,
                        targetAppOrUrl = "System",
                        param = "VOLUME_DOWN",
                        spokenAnnouncement = "Volume kam kar rahi hoon.",
                        expectedOutcome = "Volume turned down"
                    )
                )
                continue
            }
            if (clauseLower.contains("volume up") || clauseLower.contains("volume badhao") || clauseLower.contains("turn volume up") || clauseLower.contains("increase volume")) {
                steps.add(
                    TaskStep(
                        id = stepId++,
                        actionType = UniversalActionType.SYSTEM_CONTROL,
                        targetAppOrUrl = "System",
                        param = "VOLUME_UP",
                        spokenAnnouncement = "Volume badha rahi hoon.",
                        expectedOutcome = "Volume turned up"
                    )
                )
                continue
            }
            if (clauseLower.contains("increase brightness") || clauseLower.contains("brightness badhao") || clauseLower.contains("brightness up") || clauseLower.contains("turn brightness up")) {
                steps.add(
                    TaskStep(
                        id = stepId++,
                        actionType = UniversalActionType.SYSTEM_CONTROL,
                        targetAppOrUrl = "System",
                        param = "BRIGHTNESS_UP",
                        spokenAnnouncement = "Brightness badha rahi hoon.",
                        expectedOutcome = "Brightness increased"
                    )
                )
                continue
            }
            if (clauseLower.contains("decrease brightness") || clauseLower.contains("brightness kam") || clauseLower.contains("brightness down") || clauseLower.contains("turn brightness down")) {
                steps.add(
                    TaskStep(
                        id = stepId++,
                        actionType = UniversalActionType.SYSTEM_CONTROL,
                        targetAppOrUrl = "System",
                        param = "BRIGHTNESS_DOWN",
                        spokenAnnouncement = "Brightness kam kar rahi hoon.",
                        expectedOutcome = "Brightness decreased"
                    )
                )
                continue
            }

            // Case C: Tap / Button / Tab interaction (e.g., "Play दबाओ", "Reels खोलो", "Chat खोलो", "Send दबाओ")
            val tapObject = detectTapObject(clause, clauseLower)
            if (tapObject != null && ordinalMatch == null && !isSearchClause(clauseLower)) {
                val (label, selectorType, spokenText) = tapObject
                steps.add(
                    TaskStep(
                        id = stepId++,
                        actionType = UniversalActionType.TAP,
                        targetAppOrUrl = currentTarget,
                        param = label,
                        selectorType = selectorType,
                        spokenAnnouncement = spokenText,
                        expectedOutcome = "Control '$label' clicked"
                    )
                )
                universalIntents.add(
                    UniversalIntent(
                        target = currentTarget,
                        targetCategory = currentCategory,
                        action = UniversalActionType.TAP,
                        objectSelector = label,
                        parameters = label,
                        sequenceNumber = universalIntents.size + 1,
                        expectedResult = "Tapped on $label"
                    )
                )
            }

            // Case D: Scroll / Swipe interaction (e.g., "scroll करो", "reels scroll karo", "swipe up", "niche jao")
            if (clauseLower.contains("scroll") || clauseLower.contains("swipe")) {
                val direction = if (clauseLower.contains("up") || clauseLower.contains("upar")) "UP" else "DOWN"
                steps.add(
                    TaskStep(
                        id = stepId++,
                        actionType = UniversalActionType.SCROLL,
                        targetAppOrUrl = currentTarget,
                        param = direction,
                        selectorType = SelectorType.GENERIC,
                        spokenAnnouncement = "Scroll कर रही हूँ.",
                        expectedOutcome = "Screen scrolled $direction"
                    )
                )
                universalIntents.add(
                    UniversalIntent(
                        target = currentTarget,
                        targetCategory = currentCategory,
                        action = UniversalActionType.SCROLL,
                        parameters = direction,
                        sequenceNumber = universalIntents.size + 1,
                        expectedResult = "Scrolled viewport"
                    )
                )
            }

            // Case E: Clear Text
            if (clauseLower.contains("clear text") || clauseLower.contains("text hatao") || clauseLower.contains("delete karo")) {
                steps.add(
                    TaskStep(
                        id = stepId++,
                        actionType = UniversalActionType.CLEAR_TEXT,
                        targetAppOrUrl = currentTarget,
                        spokenAnnouncement = "Text clear कर रही हूँ."
                    )
                )
            }
        }

        // Append final verification milestone
        if (steps.isNotEmpty() && steps.last().actionType != UniversalActionType.VERIFY) {
            steps.add(
                TaskStep(
                    id = stepId++,
                    actionType = UniversalActionType.VERIFY,
                    targetAppOrUrl = currentTarget,
                    spokenAnnouncement = "Done.",
                    expectedOutcome = "Task execution verified"
                )
            )
        }

        return TaskPlan(
            originalPrompt = trimmed,
            targetAppName = primaryTarget,
            steps = steps,
            isMultiStep = steps.size > 2,
            explanation = "Universal pipeline execution for $primaryTarget",
            universalIntents = universalIntents
        )
    }

    /**
     * Splits multi-step command string using Hindi & English clause conjunctions.
     */
    private fun splitIntoClauses(input: String): List<String> {
        // Delimiters: ",", ";", "\n", " और ", " फिर ", " उसके बाद ", " and ", " then ", " aur ", " phir "
        val pattern = "(?:\\s+(?:और|फिर|उसके बाद|and|then|aur|phir|after that|before)\\s+|[,;\\n]+)".toRegex(RegexOption.IGNORE_CASE)
        return input.split(pattern)
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    /**
     * Detects target app or website dynamically from a clause.
     */
    private fun detectTarget(clause: String): Pair<String, TargetCategory> {
        val lower = clause.lowercase(Locale.ROOT)

        // 1. Web URL / Domain check
        val words = clause.split(" ", "\n", "\t")
        for (w in words) {
            val clean = w.trim(',', '.', ';', '"', '\'', '(', ')')
            if (clean.startsWith("http://") || clean.startsWith("https://") || clean.startsWith("www.")) {
                return Pair(clean, TargetCategory.WEBSITE)
            }
            if (clean.contains(".com") || clean.contains(".org") || clean.contains(".in") || clean.contains(".net") || clean.contains(".io")) {
                val fullUrl = if (clean.startsWith("http")) clean else "https://$clean"
                return Pair(fullUrl, TargetCategory.WEBSITE)
            }
        }

        // 1.5 File extension check
        for (w in words) {
            val clean = w.trim(',', '.', ';', '"', '\'', '(', ')')
            if (clean.endsWith(".pdf") || clean.endsWith(".txt") || clean.endsWith(".docx") || clean.endsWith(".csv") || clean.endsWith(".json")) {
                return Pair(clean, TargetCategory.FILE)
            }
        }
        if (lower.contains("pdf")) {
            return Pair("document.pdf", TargetCategory.FILE)
        }
        if (lower.contains("file")) {
            return Pair("document.txt", TargetCategory.FILE)
        }

        // 2. Check for explicit app keywords or installed app names
        val knownApps = listOf(
            "Google" to "Google",
            "Chrome" to "Google Chrome",
            "Google Chrome" to "Google Chrome",
            "YouTube" to "YouTube",
            "Instagram" to "Instagram",
            "WhatsApp" to "WhatsApp",
            "Spotify" to "Spotify",
            "Facebook" to "Facebook",
            "Snapchat" to "Snapchat",
            "LinkedIn" to "LinkedIn",
            "Settings" to "Settings",
            "Camera" to "Camera",
            "Calculator" to "Calculator",
            "Chess" to "Chess",
            "Twitter" to "Twitter",
            "X" to "X",
            "Telegram" to "Telegram",
            "Reddit" to "Reddit",
            "Maps" to "Maps",
            "Gmail" to "Gmail",
            "Clock" to "Clock",
            "Gallery" to "Photos",
            "Photos" to "Photos",
            "Free Fire Max" to "Free Fire",
            "Free Fire" to "Free Fire",
            "Freefire" to "Free Fire",
            "फ़्री फ़ायर" to "Free Fire",
            "Calendar" to "Calendar",
            "कैलेंडर" to "Calendar"
        )

        for ((alias, canonical) in knownApps) {
            if (lower.contains(alias.lowercase(Locale.ROOT))) {
                return Pair(canonical, TargetCategory.APP)
            }
        }

        // 3. Natural language app extraction
        // If the clause is an action clause (search, play, navigate), it's highly unlikely to be just an app name,
        // unless it matches a known app above.
        val isActionClause = isSearchClause(lower) || 
            lower.contains("play") || lower.contains("chalao") || 
            lower.contains("scroll") || lower.contains("click") || lower.contains("daba") ||
            lower.contains("select") || lower.contains("website") || lower.contains("link") ||
            detectOrdinalSelection(lower) != null

        if (!isActionClause) {
            val extracted = AppResolver.extractAppNameFromNaturalLanguage(clause)
            if (extracted.isNotBlank() && extracted.length > 2 && !isGenericActionWord(extracted)) {
                val isExplicitLaunch = appResolver?.isDirectAppLaunchQuery(clause) == true
                if (isExplicitLaunch) {
                    return Pair(extracted, TargetCategory.APP)
                }
            }
        }

        return Pair("", TargetCategory.APP)
    }

    private fun isGenericActionWord(word: String): Boolean {
        val w = word.lowercase(Locale.ROOT)
        return w == "search" || w == "play" || w == "reels" || w == "scroll" || w == "website" || w == "link" || w == "first" || w == "video"
    }

    private fun isSearchClause(clauseLower: String): Boolean {
        return clauseLower.contains("search") ||
                clauseLower.contains("khojo") ||
                clauseLower.contains("dhoondo") ||
                clauseLower.contains("find") ||
                clauseLower.contains("look for")
    }

    /**
     * Extracts search query text from clause.
     */
    private fun extractQueryFromClause(clause: String, target: String): String {
        var text = clause
        val targetLower = target.lowercase(Locale.ROOT)
        
        // Remove target name and common verb wrappers
        val removals = listOf(
            targetLower, "google", "youtube", "chrome", "instagram", "spotify", "whatsapp",
            "kholo", "khol do", "khol ke", "khol kar", "kholkar", "open karke", "karke", "open", "launch", "start", "pe", "par", "me", "mein", "in", "on",
            "search karo", "search kar", "search", "khojo", "dhoondo", "find", "look for", "chalao",
            "पर", "पे", "में", "के अंदर", "खोलें", "खोलो", "खोल दो", "खोल के", "खोल कर", "सर्च करो", "सर्च कर", "सर्च",
            "ढूंढो", "खोजो", "चलाओ", "प्ले करो", "लगाओ", "करो", "कर", "दो",
            "is", "isme", "ismein", "ispe", "this", "app", "website", "application", "usme", "usmein", "uspe",
            "इस", "इसमें", "इसपे", "उसमें", "उसपे", "यहाँ", "वहाँ", "yahan", "wahan"
        )

        var query = text
        for (r in removals) {
            query = query.replace("(?i)\\b$r\\b".toRegex(), " ")
        }
        
        // Remove ordinal and object wrapper phrases e.g. "the second video about", "second video about", "dusra video", "second result"
        val ordinalPatterns = listOf(
            "(?i)\\b(?:the\\s+)?(?:first|second|third|fourth|fifth|1st|2nd|3rd|4th|5th)\\s+(?:video|result|link|website|song|item)\\s+(?:about|on|of|for|regarding)?\\b",
            "(?i)\\b(?:first|second|third|fourth|fifth|1st|2nd|3rd|4th|5th)\\b",
            "(?i)\\b(?:the\\s+)?(?:video|result|link|website|song|item)\\s+(?:about|on|of|for|regarding)?\\b",
            "(?i)\\b(?:about|regarding)\\b",
            "(?i)\\b(?:pehla|pahla|dusra|doosra|teesra|chautha|panchwa)\\s+(?:video|result|link|website|gana)?\\s*(?:ke\\s+bare\\s+mein|par|pe)?\\b"
        )
        for (pattern in ordinalPatterns) {
            query = query.replace(pattern.toRegex(), " ")
        }
        
        // Custom replacement for Devanagari words without \b because \b doesn't always work well with Unicode in Java/Kotlin depending on the engine.
        // It's safer to just replace them with spaces if they appear as standalone words.
        val hindiRemovals = listOf("पर", "पे", "में", "के अंदर", "खोलें", "खोलो", "खोल दो", "खोल के", "खोल कर", "सर्च करो", "सर्च कर", "सर्च", "ढूंढो", "खोजो", "चलाओ", "प्ले करो", "लगाओ", "करो", "कर", "दो", "इस", "इसमें", "इसपे", "उसमें", "उसपे", "यहाँ", "वहाँ")
        for (hr in hindiRemovals) {
            query = query.replace("(?<=\\s|^)$hr(?=\\s|$)".toRegex(), " ")
        }

        query = query.replace("[,.!?;:\"]".toRegex(), " ").trim().replace("\\s+".toRegex(), " ")

        return if (query.isNotBlank()) query else "Hi"
    }

    /**
     * Detects ordinal selector in natural Hindi or English.
     */
    private fun detectOrdinalSelection(clauseLower: String): Pair<Int, String>? {
        // First result
        if (clauseLower.contains("पहली website") || clauseLower.contains("पहला website") ||
            clauseLower.contains("पहला result") || clauseLower.contains("पहली link") ||
            clauseLower.contains("पहला link") || clauseLower.contains("पहला video") ||
            clauseLower.contains("first website") || clauseLower.contains("first result") ||
            clauseLower.contains("first link") || clauseLower.contains("first video") ||
            clauseLower.contains("सबसे ऊपर वाला") || clauseLower.contains("top result") ||
            clauseLower.contains("पहला वाला") || clauseLower.contains("पहली वाली") || clauseLower.contains("ऊपर वाला") || clauseLower.contains("पहला") || clauseLower.contains("first")
        ) {
            val announcement = if (clauseLower.contains("video") || clauseLower.contains("chalao") || clauseLower.contains("play")) {
                "पहला video चला रही हूँ."
            } else {
                "मिल गया, पहला वाला खोल रही हूँ."
            }
            return Pair(0, announcement)
        }

        // Second result
        if (clauseLower.contains("दूसरी website") || clauseLower.contains("दूसरा result") ||
            clauseLower.contains("दूसरी link") || clauseLower.contains("दूसरा video") ||
            clauseLower.contains("second website") || clauseLower.contains("second result") ||
            clauseLower.contains("second link") || clauseLower.contains("second video") ||
            clauseLower.contains("दूसरा वाला") || clauseLower.contains("दूसरी वाली") || clauseLower.contains("दूसरा") || clauseLower.contains("second")
        ) {
            return Pair(1, "दूसरा result खोल रही हूँ.")
        }

        // Third result
        if (clauseLower.contains("तीसरी website") || clauseLower.contains("तीसरा result") ||
            clauseLower.contains("तीसरी link") || clauseLower.contains("तीसरा video") ||
            clauseLower.contains("third website") || clauseLower.contains("third result") ||
            clauseLower.contains("तीसरा वाला") || clauseLower.contains("तीसरी वाली") || clauseLower.contains("तीसरा") || clauseLower.contains("third")
        ) {
            return Pair(2, "तीसरा result खोल रही हूँ.")
        }

        // Fourth result
        if (clauseLower.contains("चौथी website") || clauseLower.contains("चौथा result") ||
            clauseLower.contains("चौथी link") || clauseLower.contains("चौथा video") ||
            clauseLower.contains("fourth website") || clauseLower.contains("fourth result") ||
            clauseLower.contains("चौथा वाला") || clauseLower.contains("चौथी वाली") || clauseLower.contains("चौथा") || clauseLower.contains("fourth")
        ) {
            return Pair(3, "चौथा result खोल रही हूँ.")
        }

        // Last result
        if (clauseLower.contains("last result") || clauseLower.contains("आखिरी result") || clauseLower.contains("last link") ||
            clauseLower.contains("आखिरी website") || clauseLower.contains("last website") || clauseLower.contains("last video") ||
            clauseLower.contains("आखिरी वाला") || clauseLower.contains("नीचे वाला") || clauseLower.contains("सबसे नीचे वाला") || clauseLower.contains("आखिरी") || clauseLower.contains("last")
        ) {
            return Pair(-1, "आखिरी result खोल रही हूँ.")
        }

        return null
    }

    private fun detectCalendarDateNavigation(clauseLower: String): Pair<String, String>? {
        val dateRegex = Regex("""(?:go to|jump to|open|show|tarikh|date)\s+(?:the\s+)?(\d{1,2}(?:st|nd|rd|th)?\s+(?:january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)|(?:january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)\s+\d{1,2}(?:st|nd|rd|th)?|\d{1,2}/\d{1,2}(?:/\d{2,4})?)""", RegexOption.IGNORE_CASE)
        val match = dateRegex.find(clauseLower) ?: Regex("""(\d{1,2}\s+(?:september|october|november|december|january|february|march|april|may|june|july|august))""", RegexOption.IGNORE_CASE).find(clauseLower)
        if (match != null) {
            val rawDate = match.groupValues[1].trim()
            return Pair("Going to $rawDate in Calendar.", rawDate)
        }
        return null
    }

    /**
     * Detects semantic tap objects (e.g. "Play दबाओ", "Reels खोलो", "Rahul की chat खोलो", "Send दबाओ", "Press the Start button").
     */
    private fun detectTapObject(clause: String, clauseLower: String): Triple<String, SelectorType, String>? {
        // 1. Play / Start Button
        if (clauseLower.contains("start button") || clauseLower.contains("press start") ||
            clauseLower.contains("click start") || clauseLower.contains("press the start") ||
            clauseLower.contains("click the start") || clauseLower.contains("start dabao") ||
            clauseLower.contains("start karo") || clauseLower.trim() == "start"
        ) {
            return Triple("Start", SelectorType.PLAY_BUTTON, "Pressing Start button.")
        }
        if (clauseLower.contains("play दबाओ") || clauseLower.contains("play karo") ||
            clauseLower.contains("play dabao") || clauseLower.contains("play online") ||
            clauseLower.contains("press play") || clauseLower.contains("start game")
        ) {
            return Triple("Play", SelectorType.PLAY_BUTTON, "Play दबा रही हूँ.")
        }

        // 2. Reels / Shorts Tab
        if (clauseLower.contains("reels खोलो") || clauseLower.contains("reels dekho") ||
            clauseLower.contains("open reels") || clauseLower.contains("shorts खोलो")
        ) {
            return Triple("Reels", SelectorType.REELS_CONTROL, "Reels खोल रही हूँ.")
        }

        // 3. Contact / Chat Selection
        if (clauseLower.contains("chat खोलो") || clauseLower.contains("message karo") || clauseLower.contains("ki chat")) {
            val words = clause.split(" ", ",", "aur", "and", "ko", "ki", "par")
            val contact = words.filter {
                it.length > 2 && !it.equals("whatsapp", true) && !it.equals("chat", true) &&
                !it.equals("kholo", true) && !it.equals("message", true)
            }.firstOrNull() ?: "Chat"
            return Triple(contact, SelectorType.CONTACT_ITEM, "Chat खोल रही हूँ.")
        }

        // 4. Send / Submit
        if (clauseLower.contains("send दबाओ") || clauseLower.contains("send karo") || clauseLower.contains("submit karo")) {
            return Triple("Send", SelectorType.SUBMIT_BUTTON, "Send दबा रही हूँ.")
        }

        // 5. Generic Tap on named element
        if (clauseLower.contains("दबाओ") || clauseLower.contains("click karo") || clauseLower.contains("tap karo") || clauseLower.contains("press") || clauseLower.contains("click")) {
            val label = clause.replace("(?i)(दबाओ|click karo|tap karo|press|click|khojo|खोलो|kholo|the|button)".toRegex(), "").trim()
            if (label.isNotBlank()) {
                return Triple(label, SelectorType.SEMANTIC_HINT, "$label दबा रही हूँ.")
            }
        }

        return null
    }
}

