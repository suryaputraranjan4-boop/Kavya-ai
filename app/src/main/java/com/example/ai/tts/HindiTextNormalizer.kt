package com.example.ai.tts

import java.util.Locale

/**
 * Hindi Text Normalizer for Indic Tiny TTS.
 *
 * Converts numbers, symbols, abbreviations, and common English/Hinglish words
 * into readable Devanagari Hindi text for ONNX inference.
 */
object HindiTextNormalizer {

    private val numberMap = mapOf(
        "0" to "शून्य", "1" to "एक", "2" to "दो", "3" to "तीन", "4" to "चार",
        "5" to "पाँच", "6" to "छह", "7" to "सात", "8" to "आठ", "9" to "नौ",
        "10" to "दस", "11" to "ग्यारह", "12" to "बारह", "13" to "तेरह", "14" to "चौदह",
        "15" to "पंद्रह", "16" to "सोलह", "17" to "सत्रह", "18" to "अठारह", "19" to "उन्नीस",
        "20" to "बीस", "25" to "पच्चीस", "30" to "तीस", "50" to "पचास",
        "75" to "पिचहत्तर", "80" to "अस्सी", "90" to "नब्बे", "100" to "सौ",
        "1000" to "हज़ार"
    )

    private val symbolMap = mapOf(
        "%" to " प्रतिशत",
        "₹" to " रुपये ",
        "$" to " डॉलर ",
        "+" to " प्लस ",
        "=" to " बराबर "
    )

    private val englishToDevanagariMap = mapOf(
        "kavya" to "काव्या",
        "ai" to "एआई",
        "hello" to "हेलो",
        "hi" to "हाय",
        "ok" to "ओके",
        "okay" to "ओके",
        "phone" to "फ़ोन",
        "battery" to "बैटरी",
        "status" to "स्टेटस",
        "time" to "टाइम",
        "yes" to "हाँ",
        "no" to "नहीं",
        "model" to "मॉडल",
        "system" to "सिस्टम",
        "chatterbox" to "चैटरबॉक्स",
        "indic" to "इंडिक",
        "tts" to "टीटीएस",
        "google" to "गूगल",
        "app" to "ऐप",
        "update" to "अपडेट"
    )

    /**
     * Normalizes an input string into clean Devanagari script suitable for Indic Tiny TTS.
     */
    fun normalize(text: String): String {
        if (text.isBlank()) return ""

        var result = text.trim()

        // 1. Symbol replacements
        for ((sym, dev) in symbolMap) {
            result = result.replace(sym, dev)
        }

        // 2. Transliterate known Latin/English words
        result = transliterateEnglishWords(result)

        // 3. Convert numbers to Hindi Devanagari words
        result = replaceNumbersWithHindiWords(result)

        // 4. Remove unsupported non-Devanagari punctuation except standard pause markers (।, ,, ?)
        result = cleanPunctuation(result)

        return result.trim()
    }

    private fun transliterateEnglishWords(input: String): String {
        val words = input.split(Regex("\\s+"))
        return words.joinToString(" ") { word ->
            val clean = word.lowercase(Locale.ROOT).replace(Regex("[^a-z]"), "")
            val replacement = englishToDevanagariMap[clean]
            if (replacement != null) {
                // Preserve trailing punctuation
                val punct = word.filter { !it.isLetterOrDigit() }
                replacement + punct
            } else {
                word
            }
        }
    }

    private fun replaceNumbersWithHindiWords(input: String): String {
        return Regex("\\b\\d+\\b").replace(input) { match ->
            val numStr = match.value
            numberMap[numStr] ?: convertNumberToHindi(numStr)
        }
    }

    private fun convertNumberToHindi(numStr: String): String {
        val n = numStr.toIntOrNull() ?: return numStr
        return when {
            n in 0..19 -> numberMap[n.toString()] ?: numStr
            n in 20..99 -> {
                val tens = (n / 10) * 10
                val units = n % 10
                val tensStr = numberMap[tens.toString()] ?: ""
                val unitsStr = if (units > 0) numberMap[units.toString()] ?: "" else ""
                "$tensStr $unitsStr".trim()
            }
            n in 100..999 -> {
                val hundreds = n / 100
                val rem = n % 100
                val hStr = (numberMap[hundreds.toString()] ?: "") + " सौ"
                if (rem > 0) "$hStr ${convertNumberToHindi(rem.toString())}" else hStr
            }
            n in 1000..99999 -> {
                val thousands = n / 1000
                val rem = n % 1000
                val tStr = "${convertNumberToHindi(thousands.toString())} हज़ार"
                if (rem > 0) "$tStr ${convertNumberToHindi(rem.toString())}" else tStr
            }
            else -> numStr
        }
    }

    private fun cleanPunctuation(input: String): String {
        return input
            .replace(".", "। ")
            .replace("!", "। ")
            .replace(Regex("\\s+"), " ")
    }
}
