package com.example.ai.tts

import java.util.Locale

data class KokoroVoiceOption(
    val id: String,
    val displayName: String,
    val languageLabel: String,
    val locale: String,
    val isHindi: Boolean
)

enum class KokoroLanguageMode(val id: String, val displayName: String) {
    AUTO_HINDI_ENGLISH("AUTO_HINDI_ENGLISH", "Auto (Hindi + English)"),
    HINDI_ONLY("HINDI_ONLY", "Hindi"),
    ENGLISH_ONLY("ENGLISH_ONLY", "English");

    companion object {
        fun fromId(id: String): KokoroLanguageMode =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO_HINDI_ENGLISH
    }
}

enum class KokoroSpeechScript {
    HINDI_DEVANAGARI,
    HINGLISH_ROMAN,
    ENGLISH_LATIN
}

data class KokoroSpeechSegment(
    val text: String,
    val voiceId: String,
    val locale: String,
    val script: KokoroSpeechScript
)

/**
 * Bilingual Hindi + Hinglish + English segmenter and strict female-voice registry for Kokoro-82M.
 * Filters out all male voice IDs (hm_*, am_*, bm_*) so only female voices are surfaced or used.
 */
object KokoroLanguageSegmenter {

    val FEMALE_HINDI_VOICES: List<KokoroVoiceOption> = listOf(
        KokoroVoiceOption("hf_alpha", "Ananya (hf_alpha)", "Hindi Female", "hi", isHindi = true),
        KokoroVoiceOption("hf_beta", "Kavya Warm (hf_beta)", "Hindi Female", "hi", isHindi = true)
    )

    val FEMALE_ENGLISH_VOICES: List<KokoroVoiceOption> = listOf(
        KokoroVoiceOption("af_heart", "Heart (af_heart)", "English US Female", "en-US", isHindi = false),
        KokoroVoiceOption("af_bella", "Bella (af_bella)", "English US Female", "en-US", isHindi = false),
        KokoroVoiceOption("af_nova", "Nova (af_nova)", "English US Female", "en-US", isHindi = false),
        KokoroVoiceOption("af_aoede", "Aoede (af_aoede)", "English US Female", "en-US", isHindi = false),
        KokoroVoiceOption("af_alloy", "Alloy (af_alloy)", "English US Female", "en-US", isHindi = false),
        KokoroVoiceOption("af_jessica", "Jessica (af_jessica)", "English US Female", "en-US", isHindi = false),
        KokoroVoiceOption("af_kore", "Kore (af_kore)", "English US Female", "en-US", isHindi = false),
        KokoroVoiceOption("af_nicole", "Nicole (af_nicole)", "English US Female", "en-US", isHindi = false),
        KokoroVoiceOption("af_river", "River (af_river)", "English US Female", "en-US", isHindi = false),
        KokoroVoiceOption("af_sarah", "Sarah (af_sarah)", "English US Female", "en-US", isHindi = false),
        KokoroVoiceOption("af_sky", "Sky (af_sky)", "English US Female", "en-US", isHindi = false),
        KokoroVoiceOption("bf_emma", "Emma (bf_emma)", "English UK Female", "en-GB", isHindi = false),
        KokoroVoiceOption("bf_alice", "Alice (bf_alice)", "English UK Female", "en-GB", isHindi = false),
        KokoroVoiceOption("bf_isabella", "Isabella (bf_isabella)", "English UK Female", "en-GB", isHindi = false),
        KokoroVoiceOption("bf_lily", "Lily (bf_lily)", "English UK Female", "en-GB", isHindi = false)
    )

    val ALL_ALLOWED_FEMALE_VOICES: List<KokoroVoiceOption> = FEMALE_HINDI_VOICES + FEMALE_ENGLISH_VOICES

    private val ALLOWED_VOICE_IDS: Set<String> = ALL_ALLOWED_FEMALE_VOICES.map { it.id }.toSet()

    private val HINGLISH_LEXICON: Set<String> = setOf(
        "namaste", "namaskar", "haan", "nahi", "nahin", "kya", "kaise", "kahan", "kaha",
        "kaun", "kab", "kyun", "kyon", "aap", "aapka", "aapki", "main", "mai", "hum",
        "tum", "yeh", "ye", "woh", "wo", "hai", "hain", "hoon", "ho", "tha", "thi",
        "the", "karo", "kardo", "karna", "kariye", "kholo", "khol", "band", "batao",
        "bataiye", "samjhao", "samjha", "samajh", "dekho", "dikhao", "chalo", "chalao",
        "bhejo", "ruko", "abhi", "yahan", "wahan", "pehle", "aage", "piche", "dobara",
        "phir", "fir", "madad", "kaam", "theek", "thik", "accha", "acha", "bahut",
        "mera", "meri", "mere", "apna", "apni", "shukriya", "dhanyawad", "kripya",
        "zara", "par", "pe", "se", "ko", "mein", "aur", "lekin", "kyunki", "agar",
        "isliye", "sab", "kuch", "bhi", "hi", "toh", "waala", "wala", "wali", "upar",
        "neeche", "agla", "pichla", "baad", "chahiye", "sakta", "sakti", "raha", "rahi"
    )

    fun isAllowedFemaleVoice(voiceId: String): Boolean =
        voiceId in ALLOWED_VOICE_IDS && (voiceId.startsWith("hf_") || voiceId.startsWith("af_") || voiceId.startsWith("bf_"))

    fun filterFemaleVoices(rawIds: Collection<String>): List<KokoroVoiceOption> {
        val allowed = ALL_ALLOWED_FEMALE_VOICES.filter { it.id in rawIds }
        return allowed.ifEmpty { ALL_ALLOWED_FEMALE_VOICES }
    }

    fun sanitizeHindiVoice(voiceId: String): String =
        if (voiceId == "hf_alpha" || voiceId == "hf_beta") voiceId else "hf_alpha"

    fun sanitizeEnglishVoice(voiceId: String): String =
        if (voiceId in ALLOWED_VOICE_IDS && !voiceId.startsWith("hf_")) voiceId else "af_heart"

    fun localeForVoice(voiceId: String): String =
        ALL_ALLOWED_FEMALE_VOICES.firstOrNull { it.id == voiceId }?.locale ?: "en-US"

    /**
     * Segments bilingual Hindi (Devanagari), Hinglish, and English text into ordered speech clauses
     * assigned to the selected female voices so one Kokoro model switches voice IDs per clause.
     */
    fun segmentText(
        rawText: String,
        languageMode: KokoroLanguageMode = KokoroLanguageMode.AUTO_HINDI_ENGLISH,
        hindiVoiceId: String = "hf_alpha",
        englishVoiceId: String = "af_heart"
    ): List<KokoroSpeechSegment> {
        val cleaned = rawText.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isEmpty()) return emptyList()

        val safeHindiVoice = sanitizeHindiVoice(hindiVoiceId)
        val safeEnglishVoice = sanitizeEnglishVoice(englishVoiceId)
        val englishLocale = localeForVoice(safeEnglishVoice)

        if (languageMode == KokoroLanguageMode.HINDI_ONLY) {
            return splitByClauseLength(cleaned).map { clause ->
                KokoroSpeechSegment(clause, safeHindiVoice, "hi", KokoroSpeechScript.HINDI_DEVANAGARI)
            }
        }
        if (languageMode == KokoroLanguageMode.ENGLISH_ONLY) {
            return splitByClauseLength(cleaned).map { clause ->
                KokoroSpeechSegment(clause, safeEnglishVoice, englishLocale, KokoroSpeechScript.ENGLISH_LATIN)
            }
        }

        // AUTO_HINDI_ENGLISH: split by script transitions first (Devanagari runs vs Latin/punctuation runs)
        val scriptRuns = splitByScriptTransitions(cleaned)
        val segments = mutableListOf<KokoroSpeechSegment>()

        for ((chunk, containsDevanagari) in scriptRuns) {
            for (clause in splitByClauseLength(chunk)) {
                if (clause.isBlank()) continue
                if (containsDevanagari) {
                    segments.add(
                        KokoroSpeechSegment(
                            text = clause,
                            voiceId = safeHindiVoice,
                            locale = "hi",
                            script = KokoroSpeechScript.HINDI_DEVANAGARI
                        )
                    )
                } else {
                    val isHinglishClause = isLikelyHinglishClause(clause)
                    if (isHinglishClause) {
                        segments.add(
                            KokoroSpeechSegment(
                                text = clause,
                                voiceId = safeHindiVoice,
                                locale = "hi",
                                script = KokoroSpeechScript.HINGLISH_ROMAN
                            )
                        )
                    } else {
                        segments.add(
                            KokoroSpeechSegment(
                                text = clause,
                                voiceId = safeEnglishVoice,
                                locale = englishLocale,
                                script = KokoroSpeechScript.ENGLISH_LATIN
                            )
                        )
                    }
                }
            }
        }

        // Coalesce tiny adjacent segments sharing same voiceId to avoid choppy prosody
        val merged = mutableListOf<KokoroSpeechSegment>()
        for (seg in segments) {
            val last = merged.lastOrNull()
            if (last != null && last.voiceId == seg.voiceId && last.text.length + seg.text.length < 180) {
                merged[merged.lastIndex] = last.copy(text = "${last.text} ${seg.text}".trim())
            } else {
                merged.add(seg)
            }
        }
        return merged
    }

    fun isLikelyHinglishClause(clause: String): Boolean {
        val tokens = clause.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false
        val hits = tokens.count { it in HINGLISH_LEXICON }
        return hits >= 2 || (tokens.size <= 3 && hits >= 1) || (hits.toFloat() / tokens.size.toFloat() >= 0.30f)
    }

    private fun isDevanagariChar(c: Char): Boolean = c in '\u0900'..'\u097F'

    private fun splitByScriptTransitions(text: String): List<Pair<String, Boolean>> {
        val result = mutableListOf<Pair<String, Boolean>>()
        val current = StringBuilder()
        var currentIsDeva: Boolean? = null

        for (ch in text) {
            val isDeva = when {
                isDevanagariChar(ch) -> true
                ch.isLetter() -> false
                else -> currentIsDeva // attach punctuation/spaces/numbers to current script run
            }
            if (currentIsDeva == null) {
                currentIsDeva = isDeva
                current.append(ch)
            } else if (isDeva == null || isDeva == currentIsDeva) {
                current.append(ch)
            } else {
                val flushed = current.toString().trim()
                if (flushed.isNotEmpty()) {
                    result.add(flushed to currentIsDeva)
                }
                current.clear()
                current.append(ch)
                currentIsDeva = isDeva
            }
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) {
            result.add(tail to (currentIsDeva == true))
        }
        return result
    }

    private fun splitByClauseLength(text: String, maxChars: Int = 180): List<String> {
        val rawClauses = text.split(Regex("(?<=[।.!?\\n])\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (rawClauses.isEmpty()) return listOf(text)

        val out = mutableListOf<String>()
        for (clause in rawClauses) {
            if (clause.length <= maxChars) {
                out.add(clause)
            } else {
                val subParts = clause.split(Regex("(?<=[,;:])\\s+"))
                val buf = StringBuilder()
                for (part in subParts) {
                    if (buf.isNotEmpty() && buf.length + part.length + 1 > maxChars) {
                        out.add(buf.toString().trim())
                        buf.clear()
                    }
                    if (buf.isNotEmpty()) buf.append(' ')
                    buf.append(part)
                }
                if (buf.isNotEmpty()) out.add(buf.toString().trim())
            }
        }
        return out
    }
}
