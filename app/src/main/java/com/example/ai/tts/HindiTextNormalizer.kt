package com.example.ai.tts

import java.text.Normalizer
import java.util.Locale

/**
 * Hindi character-path normalizer for the Indic Tiny TTS Hindi vocabulary.
 * The released model reads Devanagari characters; Latin words and digits must
 * be converted before tokenizer lookup or they become unknown/blank tokens.
 */
object HindiTextNormalizer {
    private val units = listOf(
        "शून्य", "एक", "दो", "तीन", "चार", "पाँच", "छह", "सात", "आठ", "नौ",
        "दस", "ग्यारह", "बारह", "तेरह", "चौदह", "पंद्रह", "सोलह", "सत्रह", "अठारह", "उन्नीस",
        "बीस", "इक्कीस", "बाईस", "तेईस", "चौबीस", "पच्चीस", "छब्बीस", "सत्ताईस", "अट्ठाईस", "उनतीस",
        "तीस", "इकतीस", "बत्तीस", "तैंतीस", "चौंतीस", "पैंतीस", "छत्तीस", "सैंतीस", "अड़तीस", "उनतालीस",
        "चालीस", "इकतालीस", "बयालीस", "तैंतालीस", "चवालीस", "पैंतालीस", "छियालीस", "सैंतालीस", "अड़तालीस", "उनचास",
        "पचास", "इक्यावन", "बावन", "तिरेपन", "चौवन", "पचपन", "छप्पन", "सत्तावन", "अट्ठावन", "उनसठ",
        "साठ", "इकसठ", "बासठ", "तिरेसठ", "चौंसठ", "पैंसठ", "छियासठ", "सरसठ", "अड़सठ", "उनहत्तर",
        "सत्तर", "इकहत्तर", "बहत्तर", "तिहत्तर", "चौहत्तर", "पचहत्तर", "छिहत्तर", "सतहत्तर", "अठहत्तर", "उन्यासी",
        "अस्सी", "इक्यासी", "बयासी", "तिरासी", "चौरासी", "पचासी", "छियासी", "सत्तासी", "अठासी", "नवासी",
        "नब्बे", "इक्यानवे", "बानवे", "तिरानवे", "चौरानवे", "पचानवे", "छियानवे", "सत्तानवे", "अट्ठानवे", "निन्यानवे"
    )

    private val commonWords = mapOf(
        "kavya" to "काव्या", "ai" to "एआई", "assistant" to "असिस्टेंट",
        "hello" to "हेलो", "hi" to "हाय", "ok" to "ओके", "okay" to "ओके",
        "phone" to "फ़ोन", "battery" to "बैटरी", "status" to "स्टेटस",
        "time" to "टाइम", "yes" to "हाँ", "no" to "नहीं", "model" to "मॉडल",
        "system" to "सिस्टम", "chatterbox" to "चैटरबॉक्स", "indic" to "इंडिक",
        "tts" to "टीटीएस", "onnx" to "ओएनएनएक्स", "google" to "गूगल",
        "gemini" to "जेमिनी", "android" to "एंड्रॉइड", "app" to "ऐप",
        "update" to "अपडेट", "voice" to "वॉइस", "studio" to "स्टूडियो",
        "download" to "डाउनलोड", "install" to "इंस्टॉल", "internet" to "इंटरनेट",
        "online" to "ऑनलाइन", "offline" to "ऑफलाइन", "bluetooth" to "ब्लूटूथ",
        "wifi" to "वाईफाई", "camera" to "कैमरा", "message" to "मैसेज",
        "notification" to "नोटिफिकेशन", "settings" to "सेटिंग्स", "help" to "हेल्प",
        "start" to "स्टार्ट", "stop" to "स्टॉप", "play" to "प्ले", "pause" to "पॉज़",
        "resume" to "रिज़्यूम", "volume" to "वॉल्यूम", "music" to "म्यूज़िक",
        "song" to "सॉन्ग", "video" to "वीडियो", "photo" to "फोटो",
        "file" to "फ़ाइल", "folder" to "फ़ोल्डर", "upload" to "अपलोड",
        "delete" to "डिलीट", "remove" to "रिमूव", "change" to "चेंज",
        "select" to "सिलेक्ट", "default" to "डिफ़ॉल्ट", "female" to "फीमेल",
        "male" to "मेल", "speech" to "स्पीच", "text" to "टेक्स्ट",
        "language" to "लैंग्वेज", "hindi" to "हिंदी", "english" to "इंग्लिश",
        "test" to "टेस्ट", "good" to "गुड", "morning" to "मॉर्निंग",
        "evening" to "ईवनिंग", "night" to "नाइट", "today" to "आज",
        "tomorrow" to "कल", "please" to "प्लीज़", "thanks" to "थैंक्स",
        "thank" to "थैंक", "sorry" to "सॉरी", "weather" to "वेदर",
        "search" to "सर्च", "cpu" to "सीपीयू", "ram" to "रैम",
        "api" to "एपीआई", "percent" to "प्रतिशत", "rupees" to "रुपये",
        "dollar" to "डॉलर", "usd" to "डॉलर", "inr" to "रुपये", "internet" to "इंटरनेट", "call" to "कॉल",
        "open" to "ओपन", "close" to "क्लोज़", "smart" to "स्मार्ट",
        "fast" to "फास्ट", "slow" to "स्लो", "natural" to "नेचुरल",
        "km" to "किलोमीटर", "kg" to "किलोग्राम", "cm" to "सेंटीमीटर",
        "mm" to "मिलीमीटर", "ml" to "मिलीलीटर", "mg" to "मिलीग्राम",
        "gb" to "जीबी", "mb" to "एमबी", "mbps" to "एमबीपीएस", "mhz" to "मेगाहर्ट्ज"
    )

    private val letterNames = mapOf(
        'a' to "ए", 'b' to "बी", 'c' to "सी", 'd' to "डी", 'e' to "ई",
        'f' to "एफ", 'g' to "जी", 'h' to "एच", 'i' to "आई", 'j' to "जे",
        'k' to "के", 'l' to "एल", 'm' to "एम", 'n' to "एन", 'o' to "ओ",
        'p' to "पी", 'q' to "क्यू", 'r' to "आर", 's' to "एस", 't' to "टी",
        'u' to "यू", 'v' to "वी", 'w' to "डब्ल्यू", 'x' to "एक्स",
        'y' to "वाई", 'z' to "ज़ेड"
    )

    private val latinWord = Regex("[A-Za-z]+")
    private val numberToken = Regex("₹\\s*\\d[\\d,]*(?:\\.\\d+)?%?|\\d[\\d,]*(?:\\.\\d+)?%?")

    fun normalize(text: String): String {
        if (text.isBlank()) return ""
        var value = Normalizer.normalize(text, Normalizer.Form.NFC)
            .replace("[\\u200B\\uFEFF\\u2060\\u00AD\\u200C\\u200D]".toRegex(), "")
            .map { ch ->
                if (ch in '०'..'९') ('0'.code + (ch.code - '०'.code)).toChar() else ch
            }.joinToString("")
            .replace("$", " डॉलर ")
            .replace(Regex("\\s+"), " ")
            .trim()

        value = normalizeNumbers(value)
        value = latinWord.replace(value) { match ->
            val word = match.value
            commonWords[word.lowercase(Locale.ROOT)] ?: word.lowercase(Locale.ROOT)
                .mapNotNull { letterNames[it] }
                .joinToString(" ")
        }

        // Apply the Hindi model's common nukta/matra repairs.
        value = value
            .replace("बडे़", "बड़े")
            .replace("ाॅ", "ॉ")
            .replace("ॅा", "ॉ")
            .replace("ेा", "ो")
            .replace("ाे", "ो")
            .replace("ैा", "ौ")
            .replace("ाै", "ौ")
            .replace("ीे", "ी")
            .replace("ेै", "े")

        value = value
            .replace(";", ",")
            .replace(":", ",")
            .replace("…", ".")
            .replace("–", ",")
            .replace("—", ",")
            .replace("!", "।")
            .replace(".", "।")
            .replace(Regex("[()\\[\\]{}<>]"), " ")
            .replace(Regex("[^\\u0900-\\u097F\\s,?।\\-\\\"'“”‘’]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return value
    }

    private fun normalizeNumbers(input: String): String {
        return numberToken.replace(input) { match ->
            val token = match.value.trim()
            val prefixSpace = if (match.range.first > 0 && input[match.range.first - 1].isLetter()) " " else ""
            val suffixSpace = if (match.range.last + 1 < input.length && input[match.range.last + 1].isLetter()) " " else ""
            val hasRupee = token.startsWith("₹")
            val hasPercent = token.endsWith("%")
            var digits = token.removePrefix("₹").removeSuffix("%").replace(",", "").trim()
            val spoken = if (digits.contains(".")) {
                val parts = digits.split(".", limit = 2)
                val whole = parts[0].toLongOrNull()?.let { cardinal(it) } ?: digits
                val fraction = parts[1].mapNotNull { it.digitToIntOrNull() }.joinToString(" ") { units[it] }
                if (fraction.isBlank()) whole else whole + " दशमलव " + fraction
            } else {
                val n = digits.toLongOrNull()
                if (n == null) digits else if (digits.length >= 10 || (digits.length > 1 && digits.startsWith("0"))) {
                    digits.mapNotNull { it.digitToIntOrNull() }.joinToString(" ") { units[it] }
                } else if (n in 1100L..1999L) {
                    cardinal(n / 100) + " सौ " + cardinal(n % 100)
                } else {
                    cardinal(n)
                }
            }
            prefixSpace + spoken + when {
                hasRupee -> " रुपये"
                hasPercent -> " प्रतिशत"
                else -> ""
            } + suffixSpace
        }
    }

    private fun cardinal(number: Long): String {
        if (number < 0L) return "माइनस " + cardinal(-number)
        if (number < 100L) return units[number.toInt()]
        require(number <= 999_999_999L) { "Number exceeds Hindi model normalizer range" }
        var remaining = number
        val parts = ArrayList<String>()
        val crore = (remaining / 10_000_000L).toInt()
        remaining %= 10_000_000L
        val lakh = (remaining / 100_000L).toInt()
        remaining %= 100_000L
        val thousand = (remaining / 1_000L).toInt()
        remaining %= 1_000L
        val hundred = (remaining / 100L).toInt()
        remaining %= 100L
        if (crore > 0) parts += units[crore] + " करोड़"
        if (lakh > 0) parts += units[lakh] + " लाख"
        if (thousand > 0) parts += units[thousand] + " हज़ार"
        if (hundred > 0) parts += units[hundred] + " सौ"
        if (remaining > 0) parts += units[remaining.toInt()]
        return parts.joinToString(" ")
    }
}
