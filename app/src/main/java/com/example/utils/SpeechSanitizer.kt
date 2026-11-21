package com.example.utils

import android.util.Log

/**
 * Universal Speech & STT Transcript Sanitizer.
 * Completely eliminates noise artifacts, bracketed sound tags (<noise>, [noise], [music], etc.),
 * and non-speech tokens before transcripts enter the command pipeline, chat DB, or UI.
 */
object SpeechSanitizer {

    private const val TAG = "SpeechSanitizer"

    private val NOISE_PATTERNS = listOf(
        Regex("(?i)<\\s*noise\\s*>"),
        Regex("(?i)\\[\\s*noise\\s*\\]"),
        Regex("(?i)\\(\\s*noise\\s*\\)"),
        Regex("(?i)<\\s*music\\s*>"),
        Regex("(?i)\\[\\s*music\\s*\\]"),
        Regex("(?i)\\(\\s*music\\s*\\)"),
        Regex("(?i)<\\s*speech\\s*>"),
        Regex("(?i)\\[\\s*speech\\s*\\]"),
        Regex("(?i)<\\s*unk\\s*>"),
        Regex("(?i)\\[\\s*unk\\s*\\]"),
        Regex("(?i)<\\s*silence\\s*>"),
        Regex("(?i)\\[\\s*silence\\s*\\]"),
        Regex("(?i)\\[\\s*blank_audio\\s*\\]"),
        Regex("(?i)<\\s*static\\s*>"),
        Regex("(?i)\\[\\s*static\\s*\\]"),
        Regex("(?i)<\\s*cough\\s*>"),
        Regex("(?i)\\[\\s*cough\\s*\\]"),
        Regex("(?i)\\[\\s*coughing\\s*\\]"),
        Regex("(?i)\\[\\s*applause\\s*\\]"),
        Regex("(?i)\\[\\s*laughter\\s*\\]"),
        Regex("(?i)\\[\\s*snicker\\s*\\]"),
        Regex("(?i)\\[\\s*sigh\\s*\\]"),
        Regex("(?i)\\[\\s*gasp\\s*\\]"),
        Regex("(?i)\\[\\s*throat clearing\\s*\\]"),
        Regex("(?i)\\[\\s*clears throat\\s*\\]"),
        Regex("(?i)\\[\\s*humming\\s*\\]"),
        Regex("(?i)\\[\\s*whispering\\s*\\]"),
        Regex("(?i)\\[\\s*singing\\s*\\]"),
        Regex("(?i)\\[\\s*bell\\s*\\]"),
        Regex("(?i)\\[\\s*ring\\s*\\]"),
        Regex("(?i)\\[\\s*beep\\s*\\]"),
        Regex("(?i)\\[\\s*beeps\\s*\\]"),
        Regex("(?i)\\[\\s*click\\s*\\]"),
        Regex("(?i)\\[\\s*clicks\\s*\\]"),
        Regex("(?i)\\[\\s*pop\\s*\\]"),
        Regex("(?i)\\[\\s*crackle\\s*\\]"),
        Regex("(?i)\\[\\s*thud\\s*\\]"),
        Regex("(?i)\\[\\s*rustle\\s*\\]"),
        Regex("(?i)\\[\\s*clatter\\s*\\]"),
        Regex("(?i)\\[\\s*thump\\s*\\]"),
        Regex("(?i)\\[\\s*slam\\s*\\]"),
        Regex("(?i)\\[\\s*bang\\s*\\]"),
        Regex("(?i)\\[\\s*crash\\s*\\]"),
        Regex("(?i)\\[\\s*buzz\\s*\\]"),
        Regex("(?i)\\[\\s*hum\\s*\\]"),
        Regex("(?i)\\[\\s*hiss\\s*\\]"),
        Regex("(?i)\\[\\s*wind\\s*\\]"),
        Regex("(?i)\\[\\s*typing\\s*\\]"),
        Regex("(?i)\\[\\s*keyboard\\s*\\]"),
        Regex("(?i)\\[\\s*breathing\\s*\\]"),
        Regex("(?i)\\[\\s*inhale\\s*\\]"),
        Regex("(?i)\\[\\s*exhale\\s*\\]"),
        Regex("(?i)\\[\\s*mic pop\\s*\\]"),
        Regex("(?i)<\\s*inaudible\\s*>"),
        Regex("(?i)\\[\\s*inaudible\\s*\\]")
    )

    private val PURE_FILLER_TOKENS = setOf(
        "uh", "um", "umm", "uhh", "hmm", "hm", "ah", "ahh", "eh", "er", "shh", "psst", "mm", "mhm"
    )

    // Phrases commonly hallucinated by neural STT models (Gemini/Whisper) on pure silence or fan noise
    private val ALWAYS_HALLUCINATION_PHRASES = setOf(
        "no speech",
        "no human speech",
        "no intelligible speech",
        "no intelligible human speech",
        "no speech detected",
        "empty string",
        "silence",
        "inaudible",
        "unintelligible",
        "background noise",
        "ambient noise",
        "static noise",
        "music playing",
        "thanks for watching",
        "thank you for watching",
        "please subscribe",
        "like and subscribe",
        "subscribe to my channel",
        "subtitles by",
        "captions by",
        "i didn't hear anything",
        "nothing was said"
    )

    // Short phantom words that neural STT frequently outputs on low-SNR silence/noise bursts
    private val LOW_CONFIDENCE_HALLUCINATION_PHRASES = setOf(
        "thank you",
        "thanks",
        "you",
        "bye",
        "bye bye",
        "okay",
        "ok",
        "yeah",
        "yes",
        "no",
        "so",
        "the",
        "and"
    )

    /**
     * Cleans raw speech transcript by stripping noise tags, sound annotations, and non-verbal artifacts.
     * Returns a clean, trimmed spoken string, or empty string if only noise/hallucination was captured.
     */
    fun sanitize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var result = raw.trim()

        // Strip known noise tokens
        for (pattern in NOISE_PATTERNS) {
            result = result.replace(pattern, " ")
        }

        // Strip residual angle brackets or square brackets containing pure non-alphanumeric noise descriptions
        result = result.replace(Regex("<[^>]*>"), " ")
        result = result.replace(Regex("\\[[^\\]]*\\]"), " ")
        result = result.replace(Regex("\\([^\\)]*\\)"), " ")

        // Trim whitespace and leading/trailing punctuation noise
        result = result.replace(Regex("\\s+"), " ").trim()
        result = result.trim { it in setOf('.', ',', '!', '?', '-', '_', ';', ':', '\'', '"', '~', '`') }.trim()

        // If the remaining string has no letters or digits (e.g. only dots, symbols, noise tokens), return empty
        val meaningfulChars = result.filter { it.isLetterOrDigit() || it in '\u0900'..'\u097F' }
        if (meaningfulChars.isEmpty()) {
            Log.d(TAG, "Suppressed pure noise transcript: '$raw'")
            return ""
        }

        // Reject single-character ASCII hallucinations (e.g. "a", "e", "t", "s", "k") unless it is a valid digit
        if (meaningfulChars.length == 1 && meaningfulChars[0].isLetter() && meaningfulChars[0].code < 128) {
            Log.d(TAG, "Suppressed single-character hallucination transcript: '$raw'")
            return ""
        }

        val normalizedLower = result.lowercase()
        // Reject pure non-semantic filler sounds ("uh", "um", "hmm")
        if (normalizedLower in PURE_FILLER_TOKENS) {
            Log.d(TAG, "Suppressed filler sound transcript: '$raw'")
            return ""
        }

        // Reject confirmed neural STT silence hallucination phrases
        if (normalizedLower in ALWAYS_HALLUCINATION_PHRASES ||
            normalizedLower.startsWith("there is no ") && normalizedLower.contains("speech")
        ) {
            Log.d(TAG, "Suppressed STT silence hallucination transcript: '$raw'")
            return ""
        }

        return result
    }

    /**
     * Checks if a string consists exclusively of noise artifacts, tags, or non-verbal sounds.
     */
    fun isNoiseOnly(text: String?): Boolean {
        if (text.isNullOrBlank()) return true
        val sanitized = sanitize(text)
        return sanitized.isBlank()
    }

    /**
     * Returns true when a transcript is likely an STT hallucination produced from marginal/noisy audio.
     */
    fun isLikelySilenceHallucination(raw: String?, confidence: Float): Boolean {
        val clean = sanitize(raw)
        if (clean.isBlank()) return true
        val lower = clean.lowercase()
        if (lower in ALWAYS_HALLUCINATION_PHRASES) return true
        if (confidence < 0.65f && lower in LOW_CONFIDENCE_HALLUCINATION_PHRASES) return true
        return false
    }

    /**
     * Validates that a transcript passes content sanitization, anti-hallucination check, and minimum confidence threshold.
     */
    fun isValidCommandTranscript(
        raw: String?,
        confidence: Float = 1.0f,
        minConfidence: Float = 0.35f
    ): Boolean {
        if (!confidence.isFinite() || confidence < minConfidence) return false
        val clean = sanitize(raw)
        if (clean.isBlank()) return false
        if (isLikelySilenceHallucination(clean, confidence)) {
            Log.d(TAG, "Rejected low-confidence hallucination '$clean' (conf=$confidence)")
            return false
        }
        return true
    }

    /**
     * Returns true if [newTranscript] is an accidental duplicate of [previousTranscript] within [windowMs].
     */
    fun isDuplicateTranscript(
        newTranscript: String?,
        previousTranscript: String?,
        elapsedMs: Long,
        windowMs: Long = 2200L
    ): Boolean {
        if (elapsedMs < 0L || elapsedMs > windowMs) return false
        val a = sanitize(newTranscript).lowercase()
        val b = sanitize(previousTranscript).lowercase()
        if (a.isBlank() || b.isBlank()) return false
        return a == b
    }
}
