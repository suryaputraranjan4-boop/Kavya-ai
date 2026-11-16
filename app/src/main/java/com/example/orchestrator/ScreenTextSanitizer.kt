package com.example.orchestrator

/**
 * Untrusted Screen-Text Prompt-Injection Sanitizer for Kavya AI.
 *
 * All OCR text, Accessibility node labels, notification text, web page titles/DOM text,
 * and Florence-2 / SmolVLM2 extracted text are untrusted data. Before interpolating any
 * screen text into an LLM or VLM prompt, it must pass through [ScreenTextSanitizer].
 */
object ScreenTextSanitizer {

    data class SanitizationResult(
        val originalLength: Int,
        val sanitizedText: String,
        val containsInjectionAttempt: Boolean,
        val matchedPatterns: List<String>
    )

    private val namedPatterns: List<Pair<String, Regex>> = listOf(
        "IGNORE_PREVIOUS_INSTRUCTIONS" to Regex(
            """(?i)\b(ignore|disregard|forget|override)\s+(all\s+)?(previous|prior|above|system|earlier)(\s+\w+)?\s+(instructions|prompts|rules|directives)\b"""
        ),
        "SYSTEM_PROMPT_OVERRIDE" to Regex(
            """(?i)\b(system\s*prompt|system\s*instruction|developer\s*mode|jailbreak|you\s+are\s+now\s+in\s+developer\s+mode)\s*:?"""
        ),
        "FAKE_ACTION_TAG" to Regex(
            """(?i)\[\s*ACTION\s*:[^\]]*\]"""
        ),
        "EMBEDDED_PROTOCOL_ACTION_JSON" to Regex(
            """(?i)"(action|action_type|protocol_version)"\s*:\s*"(DELETE_FILE|SEND_SMS|MAKE_PHONE_CALL|SEND_WHATSAPP_MESSAGE|CLICK|TYPE_TEXT|OPEN_APP|COMPLETE|FAIL|1\.0)""""
        ),
        "EXFILTRATION_OR_PAYMENT_INJECTION" to Regex(
            """(?i)\b(send\s+all\s+(passwords|contacts|otp|tokens)|transfer\s+money\s+to|wire\s+funds\s+to|approve\s+upi\s+collect)\b"""
        ),
        "CHATML_DELIMITER_SPOOF" to Regex(
            """(?i)(<\|im_start\|>|<\|im_end\|>|<end_of_utterance>|<start_of_turn>|<end_of_turn>)"""
        )
    )

    fun containsPromptInjectionAttempt(rawText: String): Boolean {
        if (rawText.isBlank()) return false
        return namedPatterns.any { (_, regex) -> regex.containsMatchIn(rawText) }
    }

    fun inspectAndSanitize(rawText: String, maxChars: Int = 2000): SanitizationResult {
        if (rawText.isBlank()) {
            return SanitizationResult(
                originalLength = rawText.length,
                sanitizedText = "",
                containsInjectionAttempt = false,
                matchedPatterns = emptyList()
            )
        }

        var working = rawText
        val matched = mutableListOf<String>()
        for ((name, regex) in namedPatterns) {
            if (regex.containsMatchIn(working)) {
                matched.add(name)
                working = regex.replace(working, "[UNTRUSTED_SCREEN_TEXT][REDACTED_UNTRUSTED_SCREEN_DIRECTIVE]")
            }
        }

        val bounded = if (working.length > maxChars) {
            working.take(maxChars) + "..."
        } else {
            working
        }

        return SanitizationResult(
            originalLength = rawText.length,
            sanitizedText = bounded.trim(),
            containsInjectionAttempt = matched.isNotEmpty(),
            matchedPatterns = matched
        )
    }

    fun sanitizeForPrompt(rawText: String, maxChars: Int = 2000): String {
        return inspectAndSanitize(rawText, maxChars).sanitizedText
    }

    fun sanitizeUntrustedScreenText(rawText: String, maxLength: Int = 2200): String {
        return sanitizeForPrompt(rawText, maxLength)
    }

    fun wrapUntrustedScreenBlock(sourceLabel: String, rawText: String, maxChars: Int = 2000): String {
        val result = inspectAndSanitize(rawText, maxChars)
        return buildString {
            append("<untrusted_screen_data source=\"")
            append(sourceLabel.replace("\"", "'"))
            append("\" injection_detected=\"")
            append(result.containsInjectionAttempt)
            append("\">\n")
            append(result.sanitizedText)
            append("\n</untrusted_screen_data>")
        }
    }
}
