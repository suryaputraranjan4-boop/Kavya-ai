package com.example.ai

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local Assistant Engine provides instant zero-latency responses for common queries,
 * device intents, greetings, math, date/time, and intelligent fallback handling
 * when cloud AI servers experience peak traffic or rate limits.
 */
object LocalAssistantEngine {

    fun handleFastQuery(query: String): String? {
        val clean = query.trim().lowercase(Locale.ROOT)
            .replace("[?!.,]+$".toRegex(), "")
            .trim()

        if (clean.isBlank()) return null

        val isDevanagari = query.any { it in '\u0900'..'\u097F' }
        val isHindiRomanized = clean.let {
            it.contains("kaun") || it.contains("kaise") || it.contains("kya") ||
            it.contains("aap") || it.contains("tum") || it.contains("batao") ||
            it.contains("shukriya") || it.contains("dhanyawad") || it.contains("namaste") ||
            it.contains("karo") || it.contains("kholo") || it.contains("chalao") ||
            it.contains("hain") || it.contains("hai") || it.contains("baje") || it.contains("din")
        }
        val isHindiOrHinglish = isDevanagari || isHindiRomanized

        // 1. Time & Date
        if (isTimeQuery(clean)) {
            val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
            val timeStr = sdf.format(Date())
            return when {
                isDevanagari -> "अभी $timeStr हुए हैं।"
                isHindiRomanized -> "Abhi $timeStr huye hain."
                else -> "It's $timeStr, sir."
            }
        }

        if (isDateQuery(clean)) {
            val sdf = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
            val dateStr = sdf.format(Date())
            return when {
                isDevanagari -> "आज $dateStr है।"
                isHindiRomanized -> "Aaj $dateStr hai."
                else -> "Today is $dateStr."
            }
        }

        // 2. Simple Math evaluation
        val mathResult = evaluateMath(clean)
        if (mathResult != null) {
            return mathResult
        }

        // 3. Greetings & Identity (Butler Persona: Calm, concise, capable, respectful, Hindi/Hinglish first)
        return when {
            clean in listOf("hi", "hello", "hey", "namaste", "hola", "heya", "yo") ||
            clean.startsWith("hi ") || clean.startsWith("hello ") || clean.startsWith("hey ") -> {
                when {
                    isDevanagari -> "नमस्ते। कहिये, क्या काम है?"
                    isHindiRomanized -> "हाँ sir, batayiye kya kaam hai?"
                    else -> "Hello, sir. How can I help?"
                }
            }
            clean in listOf("who are you", "what is your name", "whats your name", "what's your name", "aap kaun ho", "tum kaun ho") -> {
                when {
                    isDevanagari -> "मैं Kavya हूँ, आपकी personal AI assistant।"
                    isHindiRomanized -> "Main Kavya hoon, aapki personal AI assistant."
                    else -> "I'm Kavya, your personal assistant."
                }
            }
            clean in listOf("how are you", "how are you doing", "kaise ho", "kaisa chal raha hai", "how's it going", "what's up") -> {
                when {
                    isDevanagari -> "मैं ठीक हूँ। आप बताइये, क्या करना है?"
                    isHindiRomanized -> "Main theek hoon sir. Batayiye, kya kaam hai?"
                    else -> "I'm doing well, sir. Ready when you are."
                }
            }
            clean in listOf("thank you", "thanks", "thank you so much", "shukriya", "dhanyawad") -> {
                when {
                    isDevanagari -> "कोई बात नहीं।"
                    isHindiRomanized -> "Koi baat nahi sir."
                    else -> "You're welcome, sir."
                }
            }
            clean in listOf("good morning", "shubh prabhat") -> {
                when {
                    isDevanagari -> "शुभ प्रभात।"
                    isHindiRomanized -> "Good morning sir."
                    else -> "Good morning, sir."
                }
            }
            clean in listOf("good night", "shubh ratri") -> {
                when {
                    isDevanagari -> "शुभ रात्रि।"
                    isHindiRomanized -> "Good night sir."
                    else -> "Good night, sir."
                }
            }
            clean in listOf("what can you do", "help", "kya kar sakti ho", "features") -> {
                when {
                    isDevanagari -> "मैं apps open कर सकती हूँ, YouTube या web पर search, phone calls, messages और phone की settings control कर सकती हूँ।"
                    isHindiRomanized -> "Main apps open kar sakti hoon, YouTube ya web search, calls, messages aur device settings control kar sakti hoon."
                    else -> "I can launch apps, search YouTube or the web, manage calls and messages, and control phone settings."
                }
            }
            else -> null
        }
    }

    private fun isTimeQuery(input: String): Boolean {
        return input in listOf(
            "what time is it", "what's the time", "whats the time", "current time",
            "time kya hua hai", "kitne baje hain", "tell me the time", "time"
        )
    }

    private fun isDateQuery(input: String): Boolean {
        return input in listOf(
            "what date is it", "what's the date", "whats the date", "today's date",
            "aaj ki date", "aaj kya din hai", "what day is today", "what day is it",
            "date today", "today date"
        )
    }

    private fun evaluateMath(input: String): String? {
        val mathPattern = Regex("(?:what is|calculate|solve)?\\s*(\\d+(?:\\.\\d+)?)\\s*([+\\-*/x×÷])\\s*(\\d+(?:\\.\\d+)?)\\s*\\??")
        val match = mathPattern.find(input) ?: return null

        val num1 = match.groupValues[1].toDoubleOrNull() ?: return null
        val op = match.groupValues[2]
        val num2 = match.groupValues[3].toDoubleOrNull() ?: return null

        val result = when (op) {
            "+", "plus" -> num1 + num2
            "-", "minus" -> num1 - num2
            "*", "x", "×" -> num1 * num2
            "/", "÷" -> if (num2 != 0.0) num1 / num2 else null
            else -> null
        } ?: return null

        val formattedResult = if (result % 1.0 == 0.0) result.toLong().toString() else "%.2f".format(result)
        val formattedNum1 = if (num1 % 1.0 == 0.0) num1.toLong().toString() else num1.toString()
        val formattedNum2 = if (num2 % 1.0 == 0.0) num2.toLong().toString() else num2.toString()

        return "$formattedNum1 $op $formattedNum2 = $formattedResult"
    }

    fun getHighTrafficFallbackResponse(prompt: String): String {
        val lower = prompt.lowercase(Locale.ROOT)
        return when {
            lower.contains("youtube") || lower.contains("video") || lower.contains("play") -> {
                "YouTube खोल रही हूँ।\n```json\n[{\"action\":\"OPEN_APP\",\"target\":\"YouTube\"}]\n```"
            }
            lower.contains("camera") || lower.contains("photo") || lower.contains("picture") || lower.contains("फोटो") -> {
                "कैमरा खोल रही हूँ।\n```json\n[{\"action\":\"OPEN_APP\",\"target\":\"Camera\"}]\n```"
            }
            lower.contains("setting") || lower.contains("सेटिंग") -> {
                "Settings खोल रही हूँ।\n```json\n[{\"action\":\"OPEN_APP\",\"target\":\"Settings\"}]\n```"
            }
            lower.contains("flashlight") || lower.contains("torch") || lower.contains("टॉर्च") -> {
                "Flashlight toggle कर रही हूँ।\n```json\n[{\"action\":\"GLOBAL_ACTION\",\"target\":\"FLASHLIGHT\"}]\n```"
            }
            lower.contains("screenshot") || lower.contains("स्क्रीनशॉट") -> {
                "Screenshot ले रही हूँ।\n```json\n[{\"action\":\"GLOBAL_ACTION\",\"target\":\"SCREENSHOT\"}]\n```"
            }
            else -> {
                // If it's a general question, never spout a canned app-launch speech
                "AI servers par abhi high traffic hai, kripya ek pal mein dobara try karein. (Network busy, retrying...)"
            }
        }
    }

    /**
     * Resolves user requests when API keys are optional or not configured.
     * Kavya genuinely executes real actions:
     * - Opening apps (YouTube, Camera, Settings, WhatsApp, Chrome, Instagram, Spotify, Maps, etc.)
     * - In-app and web search
     * - Hardware controls (Flashlight, Screenshot, Volume, Home, Back)
     * - Phone calls and SMS
     * - Math, time, date, common greetings
     * - Real public API responses (Weather, Currency, Countries)
     * - Graceful, honest guidance for advanced generative reasoning without faking responses.
     */
    fun resolveLocalCommandOrFallback(prompt: String, memoryOrApiContext: String = ""): String {
        val clean = prompt.trim()
        val lower = clean.lowercase(Locale.ROOT)

        // 1. Check for instant fast query (math, time, date, greeting, identity, capabilities)
        val fastResult = handleFastQuery(clean)
        if (fastResult != null) return fastResult

        // 2. Check if Public API data was already fetched
        if (memoryOrApiContext.contains("API_RESULT:") || memoryOrApiContext.contains("EXTERNAL API DATA:")) {
            val apiData = memoryOrApiContext.substringAfter("API_RESULT:").substringAfter("EXTERNAL API DATA:").trim()
            val formattedData = formatApiResult(apiData)
            if (formattedData.isNotBlank()) {
                return formattedData
            }
        }

        // 3. Search on YouTube or Web
        val ytSearchPattern = Regex("(?i)(?:search|chalao|play|bajao)\\s+(.+?)\\s+(?:on|pe|par|in)\\s+youtube|youtube\\s+(?:pe|par)?\\s*(.+?)\\s*(?:search karo|chalao|play karo)")
        val ytMatch = ytSearchPattern.find(clean)
        if (ytMatch != null) {
            val query = (ytMatch.groupValues[1].ifBlank { ytMatch.groupValues[2] }).trim()
            return "$query YouTube पर search कर रही हूँ।\n```json\n[{\"action\":\"OPEN_APP\",\"target\":\"YouTube\"},{\"action\":\"SEARCH\",\"target\":\"YouTube\",\"query\":\"$query\"}]\n```"
        }

        val webSearchPattern = Regex("(?i)(?:search|google|dhoondo)\\s+(.+?)\\s+(?:on google|on web|pe|par)?|(?:google|web)\\s+(?:pe|par)?\\s*(.+?)\\s*search karo")
        if (lower.contains("google") || lower.startsWith("search ") || lower.contains("search karo")) {
            val query = clean
                .replace("(?i)google\\s*(?:pe|par)?\\s*search\\s*(?:karo)?".toRegex(), "")
                .replace("(?i)search\\s*(?:on google|on web)?".toRegex(), "")
                .replace("(?i)karo|dhoondo".toRegex(), "")
                .trim()
            if (query.isNotBlank()) {
                return "$query search कर रही हूँ।\n```json\n[{\"action\":\"SEARCH_WEB\",\"query\":\"$query\"}]\n```"
            }
        }

        // 4. Phone Calls
        val callPattern = Regex("(?i)(?:call|phone karo|call karo)\\s*(?:to|ko)?\\s*([a-zA-Z0-9\\s]+)|([a-zA-Z0-9\\s]+)\\s*ko\\s*call\\s*karo")
        val callMatch = callPattern.find(clean)
        if (callMatch != null) {
            val recipient = (callMatch.groupValues[1].ifBlank { callMatch.groupValues[2] }).trim()
            if (recipient.isNotBlank() && !recipient.equals("me", ignoreCase = true)) {
                return "$recipient को कॉल मिला रही हूँ।\n```json\n[{\"action\":\"MAKE_PHONE_CALL\",\"recipient\":\"$recipient\"}]\n```"
            }
        }

        // 5. SMS / Messaging
        val smsPattern = Regex("(?i)(?:send\\s+(?:sms|message)\\s+to|message\\s+bhejo)\\s+([a-zA-Z0-9]+)(?:\\s+(?:saying|message|ki)?\\s*(.+))?")
        val smsMatch = smsPattern.find(clean)
        if (smsMatch != null) {
            val recipient = smsMatch.groupValues[1].trim()
            val message = smsMatch.groupValues.getOrNull(2)?.trim() ?: "Hi"
            return "$recipient को मैसेज भेज रही हूँ।\n```json\n[{\"action\":\"SEND_SMS\",\"recipient\":\"$recipient\",\"message\":\"$message\"}]\n```"
        }

        // 6. Device Hardware & System Controls
        if (lower.contains("flashlight") || lower.contains("torch") || lower.contains("टॉर्च")) {
            return "Flashlight toggle कर रही हूँ।\n```json\n[{\"action\":\"GLOBAL_ACTION\",\"target\":\"FLASHLIGHT\"}]\n```"
        }
        if (lower.contains("screenshot") || lower.contains("स्क्रीनशॉट")) {
            return "Screenshot ले रही हूँ।\n```json\n[{\"action\":\"GLOBAL_ACTION\",\"target\":\"SCREENSHOT\"}]\n```"
        }
        if (lower in listOf("go home", "home", "home screen", "home jao")) {
            return "Home screen पर जा रही हूँ।\n```json\n[{\"action\":\"GLOBAL_ACTION\",\"target\":\"HOME\"}]\n```"
        }
        if (lower in listOf("go back", "back", "piche jao", "wapas jao")) {
            return "Back जा रही हूँ।\n```json\n[{\"action\":\"GLOBAL_ACTION\",\"target\":\"BACK\"}]\n```"
        }

        // 7. App Launching Detection
        val knownApps = mapOf(
            "youtube" to "YouTube",
            "whatsapp" to "WhatsApp",
            "camera" to "Camera",
            "settings" to "Settings",
            "chrome" to "Chrome",
            "browser" to "Chrome",
            "instagram" to "Instagram",
            "spotify" to "Spotify",
            "maps" to "Maps",
            "google maps" to "Maps",
            "gmail" to "Gmail",
            "telegram" to "Telegram",
            "phone" to "Phone",
            "dialer" to "Phone",
            "contacts" to "Contacts",
            "calculator" to "Calculator",
            "gallery" to "Gallery",
            "photos" to "Photos",
            "files" to "Files",
            "play store" to "Play Store",
            "clock" to "Clock",
            "calendar" to "Calendar"
        )

        for ((key, appName) in knownApps) {
            if (lower.contains("open $key") || lower.contains("launch $key") || 
                lower.contains("$key kholo") || lower.contains("$key open karo") ||
                lower == key || lower == "open $key" || lower == "$key open") {
                return "$appName खोल रही हूँ।\n```json\n[{\"action\":\"OPEN_APP\",\"target\":\"$appName\"}]\n```"
            }
        }

        // Generic "open [app]" pattern
        val openPattern = Regex("(?i)(?:open|launch|kholo)\\s+([a-zA-Z0-9]+)|([a-zA-Z0-9]+)\\s*(?:kholo|open karo)")
        val openMatch = openPattern.find(clean)
        if (openMatch != null) {
            val target = (openMatch.groupValues[1].ifBlank { openMatch.groupValues[2] }).trim()
            if (target.isNotBlank() && target.length > 2 && target != "the" && target != "please") {
                val capitalized = target.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                return "$capitalized खोल रही हूँ।\n```json\n[{\"action\":\"OPEN_APP\",\"target\":\"$capitalized\"}]\n```"
            }
        }

        // 8. Honest explanation when no key is configured and no local intent matched
        return "मैं अभी Local Assistant Mode में काम कर रही हूँ (कोई Cloud AI API Key सेट नहीं है)।\n\n" +
               "मैं बिना API Key के भी ये काम कर सकती हूँ:\n" +
               "• ऐप्स खोलना (उदा. 'Open YouTube', 'WhatsApp kholo')\n" +
               "• वीडियो व वेब सर्च ('Search songs on YouTube')\n" +
               "• फोन कंट्रोल्स (Torch, Screenshot, Calls, SMS)\n" +
               "• लाइव मौसम व पब्लिक डाटा ('Weather in Delhi')\n" +
               "• गणित और समय-तारीख\n\n" +
               "पूरी conversational AI और creative reasoning के लिए आप Settings > API Dashboard में अपनी मुफ़्त Gemini या OpenRouter API Key जोड़ सकते हैं।"
    }

    private fun formatApiResult(raw: String): String {
        return try {
            val clean = raw.trim()
            if (clean.startsWith("{") && clean.contains("temperature")) {
                val json = org.json.JSONObject(clean)
                val temp = json.optDouble("temperature", Double.NaN)
                val wind = json.optDouble("windspeed", Double.NaN)
                val time = json.optString("time", "")
                if (!temp.isNaN()) {
                    return "वर्तमान मौसम की ताज़ा जानकारी: तापमान ${temp}°C है" +
                           (if (!wind.isNaN()) ", हवा की गति ${wind} km/h" else "") +
                           (if (time.isNotBlank()) " (समय: $time)" else "") + "."
                }
            } else if (clean.startsWith("{") && clean.contains("rates")) {
                val json = org.json.JSONObject(clean)
                val base = json.optString("base", "")
                val date = json.optString("date", "")
                val rates = json.optJSONObject("rates")
                if (rates != null && rates.length() > 0) {
                    val key = rates.keys().next()
                    val rate = rates.getDouble(key)
                    return "लाइव करेंसी एक्सचेंज दर: 1 $base = $rate $key (दिनांक: $date)."
                }
            }
            if (raw.length in 5..300) "प्राप्त जानकारी:\n$raw" else ""
        } catch (_: Exception) {
            ""
        }
    }
}
