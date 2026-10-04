package com.example.agent

import java.util.Locale

/**
 * Intelligent detector for explicit Assistant Sleep and Wake voice commands.
 * Distinguishes true assistant state change commands from normal conversation
 * (e.g. "play sleep music" or "wake me up at 7am" are NOT assistant sleep/wake commands).
 */
object SleepWakeDetector {

    private val SLEEP_EXACT_MATCHES = setOf(
        "sleep kavya",
        "kavya sleep",
        "go to sleep kavya",
        "kavya go to sleep",
        "stop listening kavya",
        "kavya stop listening",
        "so jao kavya",
        "kavya so jao",
        "chup ho jao kavya",
        "kavya chup ho jao",
        "sleep",
        "so jao",
        "stop listening",
        "kavya rest karo",
        "rest karo kavya",
        "सो जाओ काव्या",
        "काव्या सो जाओ",
        "सो जाओ"
    )

    private val WAKE_EXACT_MATCHES = setOf(
        "kavya",
        "wake kavya",
        "wake up kavya",
        "kavya wake up",
        "kavya wake",
        "wake up",
        "wake",
        "utho kavya",
        "kavya utho",
        "jag jao kavya",
        "kavya jag jao",
        "hello kavya",
        "hey kavya",
        "hi kavya",
        "ok kavya",
        "okay kavya",
        "kavya suno",
        "suno kavya",
        "kavya ji utho",
        "काव्या",
        "उठो काव्या",
        "काव्या उठो",
        "जाग जाओ काव्या",
        "काव्या सुनो",
        "सुनो काव्या",
        "नमस्ते काव्या"
    )

    fun isSleepCommand(input: String): Boolean {
        val cleaned = cleanInput(input)
        if (cleaned.isBlank()) return false

        // Guard: If the utterance contains an action intent (play, search, alarm, etc.), it's NOT a sleep state command!
        if (cleaned.contains("play") || cleaned.contains("search") || cleaned.contains("baja") ||
            cleaned.contains("chalao") || cleaned.contains("gaana") || cleaned.contains("gana") ||
            cleaned.contains("music") || cleaned.contains("song") || cleaned.contains("video") ||
            cleaned.contains("open") || cleaned.contains("kholo") || cleaned.contains("timer") ||
            cleaned.contains("alarm") || cleaned.contains("what is") || cleaned.contains("kya hai") ||
            cleaned.contains("tell me") || cleaned.contains("batao") || cleaned.contains("message") ||
            cleaned.contains("call") || cleaned.contains("google") || cleaned.contains("youtube")
        ) {
            return false
        }

        if (SLEEP_EXACT_MATCHES.contains(cleaned)) {
            return true
        }

        // Regex patterns for variations like "Kavya, please sleep now"
        val sleepRegex = Regex("^(?:please\\s+)?(?:kavya\\s+)?(?:go\\s+to\\s+)?sleep(?:\\s+now)?(?:\\s+kavya)?$", RegexOption.IGNORE_CASE)
        val hindiSleepRegex = Regex("^(?:kavya\\s+)?(?:ab\\s+)?so\\s+jao(?:\\s+kavya)?$", RegexOption.IGNORE_CASE)
        val stopListenRegex = Regex("^(?:kavya\\s+)?stop\\s+listening(?:\\s+kavya)?$", RegexOption.IGNORE_CASE)

        return sleepRegex.matches(cleaned) || hindiSleepRegex.matches(cleaned) || stopListenRegex.matches(cleaned)
    }

    fun isWakeCommand(input: String): Boolean {
        val cleaned = cleanInput(input)
        if (cleaned.isBlank()) return false

        if (WAKE_EXACT_MATCHES.contains(cleaned)) {
            return true
        }

        val wakeRegex = Regex("^(?:hey|hello|hi|ok)?\\s*(?:kavya\\s+)?wake(?:\\s+up)?(?:\\s+kavya)?$", RegexOption.IGNORE_CASE)
        val hindiWakeRegex = Regex("^(?:kavya\\s+)?(?:ab\\s+)?(?:utho|jag\\s+jao)(?:\\s+kavya)?$", RegexOption.IGNORE_CASE)
        val greetingRegex = Regex("^(?:hey|hello|hi|namaste|pranam)\\s+kavya$", RegexOption.IGNORE_CASE)

        return wakeRegex.matches(cleaned) || hindiWakeRegex.matches(cleaned) || greetingRegex.matches(cleaned)
    }

    fun isStopCommand(input: String): Boolean {
        val cleaned = cleanInput(input)
        if (cleaned.isBlank()) return false
        val stopSet = setOf(
            "stop", "ruko", "cancel", "bas", "kavya stop", "stop kavya",
            "kavya chup", "chup ho jao", "chup ho jao kavya", "kavya chup ho jao",
            "ruk jao", "ruk jao kavya", "kavya ruk jao"
        )
        if (stopSet.contains(cleaned)) return true
        val stopRegex = Regex("^(?:kavya\\s+)?(?:please\\s+)?(?:stop|cancel|ruko|bas)(?:\\s+kavya)?$", RegexOption.IGNORE_CASE)
        return stopRegex.matches(cleaned)
    }

    private fun cleanInput(raw: String): String {
        return raw.trim().lowercase(Locale.ROOT)
            .replace(Regex("[.,!?;:\"'’‘“”\\-_]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
