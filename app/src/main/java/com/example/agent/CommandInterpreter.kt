package com.example.agent

import java.util.Locale

/**
 * Robust Command Interpreter for Kavya AI.
 * Translates speech-to-text / typed user prompt into a structured [CommandIntent].
 *
 * Implements strict contextual disambiguation:
 * - Conversational words ("hi", "hello", "hey") are strictly [CommandIntent.Conversation] and NEVER launch an app.
 * - Call commands resolve to [CommandIntent.CallContact], NEVER to dial-pad digit typing.
 * - WhatsApp messages separate chat opening, message composer, and send verification.
 * - Spotify commands separate search from track/result selection and playback verification.
 * - Chrome commands separate opening the browser, opening websites, and searching the web.
 * - Fallback defaults to conversational AI, NEVER blind Google search.
 */
object CommandInterpreter {

    private val CONVERSATIONAL_GREETINGS = setOf(
        "hi", "hello", "hey", "hie", "namaste", "pranam", "kavya",
        "hi kavya", "hello kavya", "hey kavya", "namaste kavya",
        "suno", "suno kavya", "kavya suno", "kavya ji",
        "kaise ho", "kya haal hai", "kya kar rahi ho", "kya chal raha hai",
        "how are you", "whats up", "what's up", "good morning", "good evening",
        "good night", "good afternoon", "bye", "alvida", "thank you", "thanks",
        "dhanyawad", "shukriya", "who are you", "kavya kaun ho", "tum kaun ho",
        "tell me a joke", "kuch sunao", "help", "madad"
    )

    private val STOP_WORDS = setOf(
        "stop", "kavya stop", "stop kavya", "ruko", "ruk jao", "रुको", "रुक जाओ",
        "bas karo", "cancel", "band karo"
    )

    private val SLEEP_WORDS = setOf(
        "sleep", "kavya sleep", "sleep kavya", "go to sleep", "so jao", "सो जाओ",
        "stop listening", "stop listening kavya"
    )

    private val WAKE_WORDS = setOf(
        "wake", "wake up", "wake kavya", "wake up kavya", "kavya wake up",
        "utho", "utho kavya", "jag jao"
    )

    private val KNOWN_WEBSITES = mapOf(
        "amazon" to "https://www.amazon.in",
        "flipkart" to "https://www.flipkart.com",
        "youtube" to "https://www.youtube.com",
        "google" to "https://www.google.com",
        "facebook" to "https://www.facebook.com",
        "instagram" to "https://www.instagram.com",
        "twitter" to "https://www.x.com",
        "x" to "https://www.x.com",
        "wikipedia" to "https://www.wikipedia.org",
        "netflix" to "https://www.netflix.com",
        "reddit" to "https://www.reddit.com",
        "github" to "https://www.github.com",
        "spotify" to "https://open.spotify.com"
    )

    fun interpret(input: String): CommandIntent {
        val trimmed = input.trim()
            .removeSurrounding("\"", "\"")
            .removeSurrounding("'", "'")
            .trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        if (trimmed.isEmpty()) {
            return CommandIntent.Conversation("")
        }

        // ====================================================
        // 1. STOP / SYSTEM CONTROLS
        // ====================================================
        if (STOP_WORDS.contains(lower)) {
            return CommandIntent.Stop(trimmed)
        }

        // ====================================================
        // 1b. EXPLICIT MEMORY INSTRUCTION (Requirement 1 & 8)
        // "Ye yaad rakhna", "Isko remember karo", "Remember this", "Aage se aise karna"
        // ====================================================
        if (UserCommandClassifier.isExplicitMemoryCommand(trimmed)) {
            val (cleanInstruction, appHint, entityHint, key) = parseExplicitMemoryInstruction(trimmed)
            return CommandIntent.RememberInstruction(
                memoryKey = key,
                memoryContent = cleanInstruction,
                category = "TASK_WORKFLOW",
                targetApp = appHint,
                targetEntity = entityHint,
                rawCommand = trimmed
            )
        }

        // ====================================================
        // 2. CONVERSATIONAL DISAMBIGUATION (Rule 3)
        // Words like "hi", "hello", "kaise ho" must NEVER launch an app!
        // ====================================================
        val cleanPunct = lower.replace("[.,!?;:]".toRegex(), "").trim()
        if (CONVERSATIONAL_GREETINGS.contains(cleanPunct) ||
            cleanPunct.startsWith("hi ") && cleanPunct.length <= 12 ||
            cleanPunct.startsWith("hello ") && cleanPunct.length <= 15 ||
            cleanPunct.startsWith("hey ") && cleanPunct.length <= 14
        ) {
            // Guard: If user explicitly said "open Hi app" or "Hi app kholo", allow app launch
            val isExplicitAppMention = lower.contains("app kholo") || lower.contains("open ") || lower.contains("launch ")
            if (!isExplicitAppMention) {
                return CommandIntent.Conversation(trimmed)
            }
        }

        // ====================================================
        // 3. HARDWARE & SYSTEM ACTION CONTROLS
        // ====================================================
        if (lower.contains("torch") || lower.contains("flashlight") || lower.contains("टॉर्च")) {
            val turnOn = !lower.contains("off") && !lower.contains("band")
            return CommandIntent.SystemControl(if (turnOn) "TORCH_ON" else "TORCH_OFF", trimmed)
        }
        if (lower.contains("screenshot") || lower.contains("स्क्रीनशॉट")) {
            return CommandIntent.SystemControl("SCREENSHOT", trimmed)
        }
        if (lower.contains("volume up") || lower.contains("volume down") || lower.startsWith("volume ") || lower.contains("mute")) {
            return CommandIntent.SystemControl("VOLUME", trimmed)
        }

        // Navigation & UI controls
        if (lower in listOf("go home", "home", "home screen", "home jao", "होम")) {
            return CommandIntent.InteractInApp("HOME", trimmed)
        }
        if (lower in listOf("go back", "back", "piche jao", "wapas jao", "peeche jao", "बैक")) {
            return CommandIntent.InteractInApp("BACK", trimmed)
        }
        if (lower.startsWith("scroll ") || lower == "scroll down" || lower == "scroll up") {
            return CommandIntent.InteractInApp("SCROLL", trimmed)
        }
        if (lower.startsWith("tap ") || lower.startsWith("click ") || lower.startsWith("press ") ||
            lower.contains("first result") || lower.contains("second result") || lower.contains("2nd result") ||
            lower.contains("third result") || lower.contains("second video") || lower.contains("2nd video") ||
            lower.contains("pehla") || lower.contains("dusra")
        ) {
            return CommandIntent.InteractInApp("TAP", trimmed)
        }

        // ====================================================
        // 4. PHONE CALL INTENT (Rule 7)
        // "Rohan ko call karo", "call Rohan", "Mom ko call karo"
        // ====================================================
        val isWhatsAppCall = (lower.contains("whatsapp") || lower.contains("व्हाट्सएप")) &&
                (lower.contains("call") || lower.contains("फोन") || lower.contains("कॉल"))
        val callPattern = Regex("(?i)^(?:call|phone karo|phone mila|dial)\\s+(?:to\\s+)?(.+)$|^(.+?)\\s+ko\\s+(?:call|phone)\\s*(?:karo|lagao|milao|karna)?$")
        callPattern.find(trimmed)?.let { match ->
            var recipient = (match.groupValues[1].ifBlank { match.groupValues[2] }).trim()
            recipient = recipient.replace("(?i)^(?:on\\s+whatsapp|whatsapp\\s+par|whatsapp\\s+pe)\\s+".toRegex(), "")
                .replace("(?i)\\s+(?:on\\s+whatsapp|whatsapp\\s+par|whatsapp\\s+pe)$".toRegex(), "")
                .trim()
            if (recipient.isNotBlank() && !recipient.equals("me", ignoreCase = true)) {
                return CommandIntent.CallContact(
                    contactName = recipient,
                    isWhatsApp = isWhatsAppCall
                )
            }
        }
        if ((lower.contains("call karo") || lower.contains("call lagao") || lower.endsWith(" ko call")) &&
            !lower.contains("message") && !lower.contains("msg")
        ) {
            val contact = trimmed
                .replace("(?i)^(?:kavya\\s+)?(?:please\\s+)?".toRegex(), "")
                .replace("(?i)^(?:call|phone)\\s+".toRegex(), "")
                .replace("(?i)\\s+(?:ko\\s+)?(?:call|phone)\\s*(?:karo|lagao|milao)?$".toRegex(), "")
                .replace("(?i)^(?:whatsapp\\s+par|on\\s+whatsapp)\\s+".toRegex(), "")
                .replace("(?i)\\s+(?:whatsapp\\s+par|on\\s+whatsapp)$".toRegex(), "")
                .trim()
            if (contact.isNotBlank()) {
                return CommandIntent.CallContact(
                    contactName = contact,
                    isWhatsApp = isWhatsAppCall
                )
            }
        }

        // ====================================================
        // 5. WHATSAPP OPEN CHAT ONLY (Rule 19, Test 10)
        // "WhatsApp par Rohan ka chat kholo" -> Open chat, DO NOT type anything!
        // ====================================================
        val isChatOnlyMatch = Regex("(?i)(?:whatsapp|वाट्सएप|व्हाट्सएप)\\s+(?:par|pe|me|mein|on)?\\s*(.+?)\\s+(?:ka|ki|ke)?\\s*(?:chat|conversation)\\s+(?:kholo|open karo|open|khol do|khol)")
            .find(trimmed)
            ?: Regex("(?i)open\\s+(.+?)(?:'s)?\\s+chat\\s+(?:on|in)\\s+whatsapp").find(trimmed)

        if (isChatOnlyMatch != null) {
            val contact = isChatOnlyMatch.groupValues[1].trim()
                .replace("(?i)\\s+(?:ka|ki|ke)$".toRegex(), "")
                .trim()
            if (contact.isNotBlank()) {
                return CommandIntent.OpenChat(
                    app = "WhatsApp",
                    contactName = contact
                )
            }
        }

        // ====================================================
        // 6. WHATSAPP / SMS SEND MESSAGE (Rule 6)
        // "WhatsApp par Rohan ko hello message karo"
        // "WhatsApp par Rohan ko hello bhejo"
        // "Rohan ko WhatsApp pe hi likh do"
        // "Send hello to Rohan on WhatsApp"
        // ====================================================
        val cleanTrimmed = trimmed.trimEnd('?', '!', '.')
        val cleanLower = cleanTrimmed.lowercase(Locale.ROOT)
        val isWhatsAppMsg = cleanLower.contains("whatsapp") || cleanLower.contains("व्हाट्सएप") || cleanLower.contains("वाट्सएप")
        val isExplicitMsg = cleanLower.contains("message") || cleanLower.contains("msg") || cleanLower.contains("मैसेज") ||
                cleanLower.contains("bhejo") || cleanLower.contains("bhej do") || cleanLower.contains("send karo") ||
                cleanLower.contains("likh do") || cleanLower.contains("likho") || cleanLower.contains("text karo") ||
                cleanLower.startsWith("send ") || cleanLower.contains(" send ") || cleanLower.contains("भेजो")

        if (isExplicitMsg && (isWhatsAppMsg || cleanLower.contains("sms") || cleanLower.contains("ko ") || cleanLower.contains("to "))) {
            val app = if (isWhatsAppMsg) "WhatsApp" else "SMS"

            // Pattern C2: "Send hello to Rohan on WhatsApp"
            val patternC2 = Regex("(?i)^(?:kavya\\s+)?(?:please\\s+)?send\\s+(.+?)\\s+to\\s+(.+?)(?:\\s+(?:on|in|via)\\s+(?:whatsapp|sms))?$")
            patternC2.find(cleanTrimmed)?.let { m ->
                val rawMsg = m.groupValues[1]
                    .replace("(?i)^(?:a\\s+)?(?:whatsapp\\s+|sms\\s+)?(?:message|msg)\\s*".toRegex(), "")
                    .trim()
                val rawRecipient = m.groupValues[2]
                    .replace("(?i)\\s+(?:on|in|via)\\s+(?:whatsapp|sms)$".toRegex(), "")
                    .trim()
                if (rawMsg.isNotBlank() && rawRecipient.isNotBlank() &&
                    !rawMsg.equals("message", ignoreCase = true) &&
                    !rawMsg.equals("a message", ignoreCase = true) &&
                    !rawRecipient.contains(" saying ", ignoreCase = true)
                ) {
                    return CommandIntent.SendMessage(app, rawRecipient, rawMsg)
                }
            }

            // Pattern B2: "WhatsApp par Rohan ko hello bhejo" / "Rohan ko WhatsApp pe hi likh do"
            val patternB2 = Regex("(?i)^(?:kavya\\s+)?(?:please\\s+)?(?:(?:whatsapp|sms|व्हाट्सएप|वाट्सएप)\\s+(?:par|pe|me|mein|on)\\s+)?(.+?)\\s+(?:ko|को)\\s+(?:(?:whatsapp|sms|व्हाट्सएप|वाट्सएप)\\s+(?:par|pe|me|mein|on)\\s+)?(.+?)\\s+(?:message\\s+karo|message\\s+bhejo|msg\\s+karo|msg\\s+bhejo|message|msg|मैसेज|संदेश|bhejo|bhej\\s+do|likh\\s+do|likho|send\\s+karo|भेजो|लिख\\s+दो)(?:\\s+(?:karo|bhejo|bhej\\s+do|send\\s+karo|भेजो|करो))?$")
            patternB2.find(cleanTrimmed)?.let { m ->
                val recipient = m.groupValues[1]
                    .replace("(?i)^(?:whatsapp|sms|व्हाट्सएप|वाट्सएप)\\s+(?:par|pe|me|mein|on|kholo\\s+aur)\\s*".toRegex(), "")
                    .trim()
                val message = m.groupValues[2]
                    .replace("(?i)^(?:whatsapp|sms|व्हाट्सएप|वाट्सएप)\\s+(?:par|pe|me|mein|on)\\s*".toRegex(), "")
                    .replace("(?i)\\s+(?:message|msg|मैसेज|संदेश)$".toRegex(), "")
                    .trim()
                if (recipient.isNotBlank() && message.isNotBlank()) {
                    return CommandIntent.SendMessage(app, recipient, message)
                }
            }

            // Pattern 0: "[Recipient] ko [App] (par|pe|me) message karo: [Message]" or "[Recipient] ko message karo: [Message]"
            val pattern0 = Regex("(?i)^(.+?)\\s+(?:ko|को)\\s+(?:(?:whatsapp|sms)\\s+(?:par|pe|me|mein)?\\s*)?(?:yeh\\s+|ye\\s+)?(?:message|msg|मैसेज)?\\s*(?:bhejo|karo|bhej do|send karo|भेजो|करो)?\\s*[:\\s]+(.+)$")
            pattern0.find(cleanTrimmed)?.let { m ->
                val recipient = m.groupValues[1]
                    .replace("(?i)^(?:kavya\\s+)?(?:please\\s+)?".toRegex(), "")
                    .replace("(?i)^(?:whatsapp\\s+(?:par|pe|me|mein|kholo\\s+aur)?\\s*)".toRegex(), "")
                    .trim()
                val message = m.groupValues[2].trim()
                if (recipient.isNotBlank() && message.isNotBlank()) {
                    return CommandIntent.SendMessage(app, recipient, message)
                }
            }

            // Pattern A: "WhatsApp par Rohan ko hello message karo" / "WhatsApp par Rohan ko hello bhejo"
            val patternA = Regex("(?i)(?:whatsapp\\s+(?:par|pe|me|mein|kholo\\s+aur)?\\s*)?(.+?)\\s+(?:ko|को)\\s+(?:yeh\\s+|ye\\s+)?(?:message|msg|मैसेज)?\\s*(?:bhejo|karo|bhej do|send karo|भेजो|करो)?\\s*[:\\s]+(.+)$")
            patternA.find(cleanTrimmed)?.let { m ->
                val recipient = m.groupValues[1].replace("(?i)^(?:whatsapp\\s+(?:par|pe|me|mein|kholo\\s+aur)?\\s*)".toRegex(), "").trim()
                val message = m.groupValues[2].trim()
                if (recipient.isNotBlank() && message.isNotBlank()) {
                    return CommandIntent.SendMessage(app, recipient, message)
                }
            }

            // Pattern B: "WhatsApp par Rohan ko [msg] message karo"
            val patternB = Regex("(?i)(?:whatsapp\\s+(?:par|pe|me|mein)?\\s*)?(.+?)\\s+ko\\s+(.+?)\\s+(?:message|msg|मैसेज|संदेश)\\s*(?:karo|bhejo|send karo|भेजो|करो)?$")
            patternB.find(cleanTrimmed)?.let { m ->
                val recipient = m.groupValues[1].replace("(?i)^(?:whatsapp\\s+(?:par|pe|me|mein)?\\s*)".toRegex(), "").trim()
                val message = m.groupValues[2].trim()
                if (recipient.isNotBlank() && message.isNotBlank()) {
                    return CommandIntent.SendMessage(app, recipient, message)
                }
            }

            // Pattern C: "send message to [recipient] [message]"
            val patternC = Regex("(?i)send\\s+(?:a\\s+)?(?:whatsapp\\s+)?message\\s+to\\s+(.+?)\\s+(?:saying|that|with\\s+text|ki)?\\s*(.+)$")
            patternC.find(cleanTrimmed)?.let { m ->
                val recipient = m.groupValues[1].trim()
                val message = m.groupValues[2].trim()
                if (recipient.isNotBlank() && message.isNotBlank()) {
                    return CommandIntent.SendMessage(app, recipient, message)
                }
            }

            // Pattern D: "Open WhatsApp and message [recipient] [message]" / "message [recipient] [message]"
            val patternD1 = Regex("(?i)(?:open\\s+(?:whatsapp|sms)\\s+and\\s+)?message\\s+(.+?)\\s+(hello|hi|hey|how\\s+are\\s+you|good\\s+morning|good\\s+night)$")
            patternD1.find(cleanTrimmed)?.let { m ->
                val recipient = m.groupValues[1].trim()
                val message = m.groupValues[2].trim()
                if (recipient.isNotBlank() && message.isNotBlank()) {
                    return CommandIntent.SendMessage(app, recipient, message)
                }
            }

            val patternD = Regex("(?i)(?:open\\s+(?:whatsapp|sms)\\s+and\\s+)?message\\s+(.+?)\\s+(.+)$")
            patternD.find(cleanTrimmed)?.let { m ->
                val recipient = m.groupValues[1].trim()
                val message = m.groupValues[2].trim()
                if (recipient.isNotBlank() && message.isNotBlank()) {
                    return CommandIntent.SendMessage(app, recipient, message)
                }
            }
        }

        // ====================================================
        // 7. PLAY MEDIA (SPOTIFY / YOUTUBE) (Rule 8)
        // "Spotify par Arijit Singh ka song chalao"
        // "Spotify pe Believer play karo"
        // "Arijit ke gaane baja do Spotify par"
        // "Play something on YouTube"
        // ====================================================
        val isSpotifyMention = cleanLower.contains("spotify") || cleanLower.contains("स्पॉटिफ़ाई") || cleanLower.contains("स्पॉटीफाई")
        val isYouTubeMention = cleanLower.contains("youtube") || cleanLower.contains("यूट्यूब") || cleanLower.contains("yt")
        val isPlayVerb = cleanLower.contains("chalao") || cleanLower.contains("chala do") || cleanLower.contains("play") ||
                cleanLower.contains("bajao") || cleanLower.contains("baja do") ||
                cleanLower.contains("sunao") || cleanLower.contains("चलाओ") || cleanLower.contains("बजाओ") ||
                cleanLower.contains("song") || cleanLower.contains("songs") ||
                cleanLower.contains("gana") || cleanLower.contains("gaana") || cleanLower.contains("gaane") ||
                cleanLower.contains("music") || cleanLower.contains("video") || cleanLower.contains("वीडियो") ||
                cleanLower.contains("open karke do")

        if (isPlayVerb && (isSpotifyMention || isYouTubeMention || cleanLower.startsWith("play "))) {
            val app = if (isSpotifyMention) "Spotify" else "YouTube"

            var query = cleanTrimmed
                // Remove polite prefixes & app mentions
                .replace("(?i)^(?:kavya\\s+)?(?:please\\s+|zara\\s+)?".toRegex(), "")
                .replace("(?i)(?:open\\s+)?(?:spotify|youtube|yt|स्पॉटिफ़ाई|यूट्यूब)\\s+(?:par|pe|me|mein|on|in|and)?\\s*".toRegex(), "")
                .replace("(?i)\\s+(?:on|in)?\\s*(?:spotify|youtube|yt|स्पॉटिफ़ाई|यूट्यूब)\\s*(?:par|pe|me|mein|on|in)?$".toRegex(), "")
                // Remove action verbs
                .replace("(?i)^(?:play|chalao|chala do|bajao|baja do|sunao|open|and|and\\s+play)\\s+".toRegex(), "")
                .replace("(?i)\\s+(?:chalao|chala do|play karo|play kar do|play kar|play|bajao|baja do|sunao|open karke do|karke do|karo|do|लगाओ|चलाओ|बजाओ)$".toRegex(), "")
                // Remove wrappers like "ka song", "ke gaane", "ka video", "ka gana"
                .replace("(?i)\\s+(?:ka|ki|ke)?\\s*(?:song|songs|gana|gaana|gaane|music|video|videos|track|tracks)$".toRegex(), "")
                .trim()

            val effectiveQuery = if (query.isBlank() || query.equals("koi", ignoreCase = true) || query.equals("something", ignoreCase = true)) "video" else query
            val targetType = when {
                cleanLower.contains("artist") -> "ARTIST"
                cleanLower.contains("album") -> "ALBUM"
                cleanLower.contains("playlist") -> "PLAYLIST"
                else -> "TRACK"
            }
            return CommandIntent.PlayMedia(
                app = app,
                mediaQuery = effectiveQuery,
                targetType = targetType
            )
        }

        // ====================================================
        // 8. OPEN WEBSITE (CHROME / BROWSER) (Rule 9)
        // "Chrome par Amazon kholo", "Chrome par YouTube kholo", "open Amazon in Chrome"
        // ====================================================
        val isChromeMention = cleanLower.contains("chrome") || cleanLower.contains("क्रोम") || cleanLower.contains("browser") || cleanLower.contains("ब्राउज़र")
        val isWebsiteTrigger = isChromeMention || cleanLower.contains("website") || cleanLower.contains(".com") || cleanLower.contains("site") || cleanLower.startsWith("www.")

        if (isWebsiteTrigger && !cleanLower.contains("search") && !cleanLower.contains("dhoondo") && !cleanLower.contains("khojo")) {
            // Check for known website names
            for ((name, url) in KNOWN_WEBSITES) {
                if (cleanLower.contains(name) && !cleanLower.equals("chrome", ignoreCase = true) && !cleanLower.equals("chrome kholo", ignoreCase = true)) {
                    // Make sure user is not asking to open the standalone app if not mentioning Chrome
                    if (isChromeMention || cleanLower.contains("website") || cleanLower.contains("site") || cleanLower.contains(".com")) {
                        return CommandIntent.OpenWebsite(
                            app = "Google Chrome",
                            destinationUrl = url,
                            destinationName = name.replaceFirstChar { it.uppercase() }
                        )
                    }
                }
            }

            // Direct URL (e.g. "google.com kholo", "https://xyz.com")
            val words = cleanTrimmed.split("\\s+".toRegex())
            for (w in words) {
                val clean = w.trim(',', '.', ';', '"', '\'', '(', ')')
                if (clean.contains(".com") || clean.contains(".org") || clean.contains(".in") || clean.contains(".net") || clean.startsWith("http")) {
                    val fullUrl = if (clean.startsWith("http")) clean else "https://$clean"
                    return CommandIntent.OpenWebsite(
                        app = "Google Chrome",
                        destinationUrl = fullUrl,
                        destinationName = clean
                    )
                }
            }
        }

        // ====================================================
        // 9. WEB SEARCH INTENT (Rule 9)
        // "Google pe GTA 5 search karo", "search GTA 5 on Google"
        // Extract ONLY query, NOT entire sentence!
        // ====================================================
        val isExplicitSearch = cleanLower.contains("search") || cleanLower.contains("सर्च") ||
                cleanLower.contains("dhoondo") || cleanLower.contains("dhundo") || cleanLower.contains("ढूँढो") || cleanLower.contains("ढूंढो") ||
                cleanLower.contains("khojo") || cleanLower.contains("खोजो") || cleanLower.startsWith("find ") || cleanLower.startsWith("look for ")

        if (isExplicitSearch) {
            val detectedEngineOrApp = when {
                cleanLower.contains("youtube") || cleanLower.contains("यूट्यूब") || cleanLower.startsWith("yt ") || cleanLower.contains(" yt") -> "YouTube"
                cleanLower.contains("chrome") || cleanLower.contains("क्रोम") -> "Google Chrome"
                cleanLower.contains("play store") || cleanLower.contains("playstore") || cleanLower.contains("प्ले स्टोर") -> "Google Play Store"
                cleanLower.contains("spotify") || cleanLower.contains("स्पॉटिफ़ाई") -> "Spotify"
                cleanLower.contains("instagram") || cleanLower.contains("इंस्टाग्राम") -> "Instagram"
                cleanLower.contains("google") || cleanLower.contains("गूगल") -> "Google"
                else -> ""
            }

            var query = cleanTrimmed
                .replace("(?i)^(?:kavya\\s+)?(?:please\\s+)?".toRegex(), "")
                .replace("(?i)(?:open\\s+)?(?:google|chrome|browser|youtube|yt|play\\s*store|spotify|instagram|गूगल|यूट्यूब|क्रोम)\\s+(?:pe|par|me|mein|on|in|and)?\\s*".toRegex(), "")
                .replace("(?i)\\s+(?:google|chrome|browser|youtube|yt|play\\s*store|spotify|instagram|गूगल|यूट्यूब|क्रोम)\\s*(?:pe|par|me|mein|on|in)?$".toRegex(), "")
                .replace("(?i)^(?:search(?:\\s+for)?|find|look\\s+for|and\\s+search(?:\\s+for)?|and\\s+search|सर्च\\s*करो|ढूंढो|खोजो)\\s+".toRegex(), "")
                .replace("(?i)\\s+(?:search\\s+karo|search\\s+kar|search|dhoondo|khojo|सर्च\\s*करो|ढूंढो|खोजो)$".toRegex(), "")
                .trim()

            if (query.isNotBlank()) {
                return CommandIntent.WebSearch(
                    query = query,
                    engineOrApp = detectedEngineOrApp
                )
            }
        }

        // ====================================================
        // 10. EXPLICIT APP LAUNCH (Rule 3 & 10)
        // "Chrome kholo", "Zara YouTube open kar do", "Can you launch YouTube please?",
        // "Mujhe YouTube dikhao", "WhatsApp kholo"
        // MUST have explicit opening evidence!
        // ====================================================
        val strippedPolite = cleanTrimmed
            .replace("(?i)^(?:kavya\\s+)?(?:please\\s+|zara\\s+|mujhe\\s+|kripya\\s+|can\\s+you\\s+|could\\s+you\\s+|would\\s+you\\s+)+".toRegex(), "")
            .replace("(?i)\\s+(?:please|plz|zara|na)$".toRegex(), "")
            .trim()
        val strippedLower = strippedPolite.lowercase(Locale.ROOT)

        val naturalExtracted = com.example.utils.AppResolver.extractAppNameFromNaturalLanguage(strippedPolite)

        val isExplicitAppOpen = strippedLower.startsWith("open ") || strippedLower.startsWith("launch ") ||
                strippedLower.startsWith("start ") || strippedLower.startsWith("run ") ||
                strippedLower.startsWith("go to ") || strippedLower.startsWith("show me ") ||
                strippedLower.endsWith(" kholo") || strippedLower.endsWith(" khol do") ||
                strippedLower.endsWith(" open karo") || strippedLower.endsWith(" open kar do") ||
                strippedLower.endsWith(" open kar") || strippedLower.endsWith(" dikhao") ||
                strippedLower.endsWith(" dikha do") || strippedLower.endsWith(" chalu karo") ||
                strippedLower.endsWith(" खोलो") || strippedLower.endsWith(" चालू करो") ||
                strippedLower.endsWith(" दिखाओ") ||
                strippedLower.endsWith(" app") || strippedLower.endsWith(" ऐप") ||
                (naturalExtracted.isNotBlank() && naturalExtracted.lowercase(java.util.Locale.ROOT) != strippedLower)

        val knownCanonicalApps = listOf(
            "youtube create" to "YouTube Create",
            "yt create" to "YouTube Create",
            "youtube music" to "YouTube Music",
            "yt music" to "YouTube Music",
            "google chrome" to "Google Chrome",
            "chrome" to "Google Chrome",
            "youtube" to "YouTube",
            "whatsapp" to "WhatsApp",
            "spotify" to "Spotify",
            "instagram" to "Instagram",
            "facebook" to "Facebook",
            "camera" to "Camera",
            "calculator" to "Calculator",
            "settings" to "Settings",
            "contacts" to "Contacts",
            "clock" to "Clock",
            "gallery" to "Photos",
            "photos" to "Photos",
            "play store" to "Play Store",
            "playstore" to "Play Store",
            "gmail" to "Gmail",
            "maps" to "Google Maps",
            "google maps" to "Google Maps",
            "free fire" to "Free Fire",
            "telegram" to "Telegram",
            "snapchat" to "Snapchat",
            "twitter" to "Twitter",
            "x" to "Twitter"
        )

        val candidateFromPolite = strippedPolite
            .replace("(?i)^(?:open|launch|start|run|take\\s+me\\s+to|show\\s+me|go\\s+to)\\s+".toRegex(), "")
            .replace("(?i)\\s+(?:kholo|khol do|open karo|open kar do|open kar|dikhao|dikha do|chalu karo|खोलो|चालू करो|दिखाओ|app|ऐप)$".toRegex(), "")
            .trim()
        val candidateLower = naturalExtracted.lowercase(java.util.Locale.ROOT).ifBlank { candidateFromPolite.lowercase(java.util.Locale.ROOT) }

        for ((alias, canonical) in knownCanonicalApps) {
            val matchesAlias = isExplicitAppOpen && (
                    candidateLower == alias ||
                    candidateFromPolite.equals(alias, ignoreCase = true) ||
                    strippedLower == "open $alias" ||
                    strippedLower == "launch $alias" ||
                    strippedLower == "start $alias" ||
                    strippedLower == "$alias kholo" ||
                    strippedLower == "$alias open karo" ||
                    strippedLower == "$alias open kar do" ||
                    strippedLower == "$alias khol do" ||
                    strippedLower == "$alias dikhao" ||
                    strippedLower == "$alias chalao" ||
                    strippedLower == "$alias chalaye" ||
                    strippedLower == "खोलो $alias" ||
                    strippedLower == "$alias खोलो"
            )

            if (matchesAlias) {
                return CommandIntent.OpenApp(
                    appName = canonical
                )
            }
        }

        if (isExplicitAppOpen) {
            val extracted = candidateFromPolite
            if (extracted.isNotBlank() && !CONVERSATIONAL_GREETINGS.contains(extracted.lowercase(Locale.ROOT))) {
                return CommandIntent.OpenApp(appName = extracted)
            }
        }

        // ====================================================
        // 11. CONVERSATIONAL FALLBACK (Rule 16)
        // General query or chat -> NEVER fallback blindly to Google Search!
        // ====================================================
        return CommandIntent.Conversation(trimmed)
    }

    private data class MemoryParseResult(
        val cleanInstruction: String,
        val appHint: String?,
        val entityHint: String?,
        val key: String
    )

    private fun parseExplicitMemoryInstruction(input: String): MemoryParseResult {
        var clean = input.trim()

        val suffixPatterns = listOf(
            "(?i)\\s*[,.]?\\s*(?:ye\\s+|yeh\\s+|isko\\s+|ise\\s+)?(?:yaad\\s+rakhna|yaad\\s+rakho|remember\\s+karo|remember\\s+rakhna|remember\\s+this|remember\\s+that|aage\\s+se\\s+aise\\s+karna|aage\\s+se\\s+aisa\\s+karna|aage\\s+se\\s+aise\\s+hi\\s+karna|याद\\s*रखना|याद\\s*रखो)[.!]?$",
            "(?i)\\s*[,.]?\\s*(?:next\\s+time\\s+do\\s+it\\s+like\\s+this|from\\s+now\\s+on\\s+do\\s+this)[.!]?$"
        )

        for (pattern in suffixPatterns) {
            clean = clean.replace(pattern.toRegex(), "").trim()
        }

        val prefixPatterns = listOf(
            "(?i)^(?:aage\\s+se\\s+aise\\s+karna\\s*[:,-]?|aage\\s+se\\s*[:,-]?|remember\\s+this\\s*[:,-]?|remember\\s+that\\s*[:,-]?|remember\\s+to\\s*|yaad\\s+rakhna\\s+ki\\s*|yaad\\s+rakhna\\s*[:,-]?|yaad\\s+rakho\\s+ki\\s*|isko\\s+remember\\s+karo\\s*[:,-]?|ise\\s+remember\\s+karo\\s*[:,-]?)\\s*"
        )

        for (pattern in prefixPatterns) {
            clean = clean.replace(pattern.toRegex(), "").trim()
        }

        clean = clean.trim(' ', ',', '.', ':', ';', '!', '\"', '\'')

        val lowerClean = clean.lowercase(Locale.ROOT)
        var appHint: String? = null
        when {
            lowerClean.contains("whatsapp") || lowerClean.contains("वाट्सएप") || lowerClean.contains("व्हाट्सएप") -> appHint = "WhatsApp"
            lowerClean.contains("spotify") || lowerClean.contains("स्पॉटिफाई") -> appHint = "Spotify"
            lowerClean.contains("youtube") || lowerClean.contains("यूट्यूब") -> appHint = "YouTube"
            lowerClean.contains("chrome") || lowerClean.contains("browser") -> appHint = "Google Chrome"
            lowerClean.contains("phone") || lowerClean.contains("call") -> appHint = "Phone"
        }

        var entityHint: String? = null
        val entityCandidates = listOf(
            "didi", "rohan", "mummy", "mom", "papa", "dad", "bhai", "sister",
            "brother", "rahul", "priya", "amit", "neha", "arijit singh", "boss"
        )
        for (candidate in entityCandidates) {
            if (lowerClean.contains(candidate)) {
                entityHint = candidate.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                break
            }
        }

        val key = when {
            appHint != null && entityHint != null -> "Workflow:$appHint:$entityHint"
            appHint != null -> "Workflow:$appHint:Preference"
            entityHint != null -> "Workflow:Contact:$entityHint"
            else -> "Preference:${clean.take(24).trim().replace(" ", "_")}"
        }

        return MemoryParseResult(
            cleanInstruction = clean.ifBlank { input },
            appHint = appHint,
            entityHint = entityHint,
            key = key
        )
    }
}
