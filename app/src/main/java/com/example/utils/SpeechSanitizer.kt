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
        Regex("(?i)\\[\\s*hiss\\s*\\]")
    )

    /**
     * Cleans raw speech transcript by stripping noise tags, sound annotations, and non-verbal artifacts.
     * Returns a clean, trimmed spoken string, or empty string if only noise was captured.
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

        // Trim whitespace and punctuation
        result = result.replace(Regex("\\s+"), " ").trim()

        // If the remaining string has no letters or digits (e.g. only dots, symbols, noise tokens), return empty
        val hasActualWords = result.any { it.isLetterOrDigit() || it in '\u0900'..'\u097F' }
        if (!hasActualWords) {
            Log.d(TAG, "Suppressed pure noise transcript: '$raw'")
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
}
