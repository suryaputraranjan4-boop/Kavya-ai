package com.example.ai

import java.util.Locale

/**
 * Requirement 3: TaskClassifier.
 * Real classification of user input into semantic operational intent.
 * Considers English, Hindi, and Hinglish natural language prompts.
 */
enum class ClassifiedTaskCategory {
    PHONE_CONTROL,
    PHONE_AUTOMATION,
    RESEARCH,
    CODING,
    VISION,
    OCR,
    REASONING,
    FAST_GENERAL
}

data class ClassificationResult(
    val category: ClassifiedTaskCategory,
    val confidence: Float,
    val requiresVision: Boolean = false,
    val targetApp: String? = null,
    val detectedKeywords: List<String> = emptyList()
)

object TaskClassifier {

    private val PHONE_CONTROL_KEYWORDS = listOf(
        "open", "launch", "kholo", "chalao", "start",
        "scroll", "swipe", "tap", "click", "dabao",
        "type", "search on", "likho", "dhoondo",
        "volume", "awaz", "brightness", "bluetooth", "wifi",
        "call", "dial", "phone lagao", "message", "sms",
        "settings", "calendar", "alarm", "clock", "free fire", "game"
    )

    private val RESEARCH_KEYWORDS = listOf(
        "research", "investigate", "sources", "citations",
        "who won", "latest news", "find articles", "compare",
        "deep analysis", "background of", "history of"
    )

    private val CODING_KEYWORDS = listOf(
        "code", "kotlin", "java", "python", "javascript",
        "function", "class", "bug", "debug", "refactor",
        "algorithm", "regex", "api endpoint", "gradle", "sql"
    )

    private val VISION_KEYWORDS = listOf(
        "image", "photo", "picture", "screenshot", "screen pe kya hai",
        "look at this", "see this", "identify object", "what is on screen",
        "read text from image", "ocr", "detect"
    )

    private val REASONING_KEYWORDS = listOf(
        "why", "how does", "explain step by step", "solve", "math",
        "logic", "riddle", "pros and cons", "evaluate", "derivation"
    )

    /**
     * Classifies a user prompt into a task category with keyword attribution.
     */
    fun classify(prompt: String, hasAttachedImage: Boolean = false): ClassificationResult {
        val lower = prompt.lowercase(Locale.ROOT).trim()

        if (hasAttachedImage) {
            val isOcr = lower.contains("read") || lower.contains("ocr") || lower.contains("text")
            return ClassificationResult(
                category = if (isOcr) ClassifiedTaskCategory.OCR else ClassifiedTaskCategory.VISION,
                confidence = 0.95f,
                requiresVision = true,
                detectedKeywords = listOf("attached_image")
            )
        }

        // Check Phone Control first (Android execution layer)
        val matchedPhone = PHONE_CONTROL_KEYWORDS.filter { lower.contains(it) }
        if (matchedPhone.isNotEmpty()) {
            val isAutomation = lower.contains("and") || lower.contains("aur") || lower.contains("then") || lower.contains("phir")
            val targetApp = extractTargetApp(lower)
            return ClassificationResult(
                category = if (isAutomation) ClassifiedTaskCategory.PHONE_AUTOMATION else ClassifiedTaskCategory.PHONE_CONTROL,
                confidence = 0.90f,
                targetApp = targetApp,
                detectedKeywords = matchedPhone
            )
        }

        // Check Vision / OCR
        val matchedVision = VISION_KEYWORDS.filter { lower.contains(it) }
        if (matchedVision.isNotEmpty()) {
            return ClassificationResult(
                category = if (lower.contains("ocr") || lower.contains("read text")) ClassifiedTaskCategory.OCR else ClassifiedTaskCategory.VISION,
                confidence = 0.85f,
                requiresVision = true,
                detectedKeywords = matchedVision
            )
        }

        // Check Coding
        val matchedCode = CODING_KEYWORDS.filter { lower.contains(it) }
        if (matchedCode.isNotEmpty()) {
            return ClassificationResult(
                category = ClassifiedTaskCategory.CODING,
                confidence = 0.88f,
                detectedKeywords = matchedCode
            )
        }

        // Check Research
        val matchedResearch = RESEARCH_KEYWORDS.filter { lower.contains(it) }
        if (matchedResearch.isNotEmpty()) {
            return ClassificationResult(
                category = ClassifiedTaskCategory.RESEARCH,
                confidence = 0.85f,
                detectedKeywords = matchedResearch
            )
        }

        // Check Reasoning
        val matchedReasoning = REASONING_KEYWORDS.filter { lower.contains(it) }
        if (matchedReasoning.isNotEmpty()) {
            return ClassificationResult(
                category = ClassifiedTaskCategory.REASONING,
                confidence = 0.80f,
                detectedKeywords = matchedReasoning
            )
        }

        return ClassificationResult(
            category = ClassifiedTaskCategory.FAST_GENERAL,
            confidence = 0.70f,
            detectedKeywords = emptyList()
        )
    }

    private fun extractTargetApp(prompt: String): String? {
        val appMap = mapOf(
            "youtube" to "YouTube",
            "whatsapp" to "WhatsApp",
            "chrome" to "Chrome",
            "browser" to "Chrome",
            "camera" to "Camera",
            "calendar" to "Calendar",
            "settings" to "Settings",
            "free fire" to "Free Fire",
            "maps" to "Google Maps",
            "spotify" to "Spotify",
            "gmail" to "Gmail"
        )
        for ((key, app) in appMap) {
            if (prompt.contains(key)) return app
        }
        return null
    }
}
