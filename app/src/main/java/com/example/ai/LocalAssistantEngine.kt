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

    fun getConnectionFallbackResponse(prompt: String): String {
        return "Selected AI API is currently unavailable or rate-limited. No local/offline device automation can run without an active AI API connection."
    }

    fun getHighTrafficFallbackResponse(prompt: String): String = getConnectionFallbackResponse(prompt)

    /**
     * Non-automation fast query helper ONLY (time, date, simple math, greetings).
     * Never plans or emits device-automation commands when the AI API is unavailable.
     */
    fun resolveLocalCommandOrFallback(prompt: String, memoryOrApiContext: String = ""): String {
        val clean = prompt.trim()
        val fastResult = handleFastQuery(clean)
        if (fastResult != null) return fastResult

        if (memoryOrApiContext.contains("API_RESULT:") || memoryOrApiContext.contains("EXTERNAL API DATA:")) {
            val apiData = memoryOrApiContext.substringAfter("API_RESULT:").substringAfter("EXTERNAL API DATA:").trim()
            val formattedData = formatApiResult(apiData)
            if (formattedData.isNotBlank()) {
                return formattedData
            }
        }

        return "AI API authorization is required to process this request or perform device automation. Please verify your active API key and internet connection in API Dashboard."
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
