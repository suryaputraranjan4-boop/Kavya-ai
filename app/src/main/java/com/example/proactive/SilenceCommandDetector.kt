package com.example.proactive

import java.util.Locale
import java.util.regex.Pattern

enum class SilenceAction {
    NONE,
    TURN_OFF_PROACTIVE,
    TURN_ON_PROACTIVE,
    TEMPORARY_SILENCE,
    DECREASE_FREQUENCY,
    INCREASE_FREQUENCY
}

data class SilenceDetectionResult(
    val detected: Boolean,
    val action: SilenceAction,
    val silenceDurationMs: Long = 0L,
    val confirmationText: String = ""
)

object SilenceCommandDetector {

    // Regex patterns for permanent silence / shut down proactive mode
    private val PERMANENT_SILENCE_PATTERNS = listOf(
        Pattern.compile("(?i)\\b(चुप\\s*हो\\s*जाओ|चुप\\s*रहो|शांत\\s*रहो|बात\\s*मत\\s*करो|बोलना\\s*बंद\\s*करो|रुक\\s*जाओ|बस\\s*करो)\\b"),
        Pattern.compile("(?i)\\b(chup\\s*ho\\s*jao|chup\\s*raho|shant\\s*raho|baat\\s*mat\\s*karo|bolna\\s*band\\s*karo|chup|shutup|shut\\s*up|ruk\\s*jao|bas\\s*karo)\\b"),
        Pattern.compile("(?i)\\b(don'?t\\s*talk|be\\s*quiet|stop\\s*talking|silence|mute\\s*yourself|proactive\\s*mode\\s*off|stop)\\b")
    )

    // Regex patterns for resume proactive mode
    private val RESUME_PATTERNS = listOf(
        Pattern.compile("(?i)\\b(अब\\s*बात\\s*कर\\s*सकती\\s*हो|बात\\s*करो|बोलो\\s*काव्या|मुझसे\\s*बात\\s*करो)\\b"),
        Pattern.compile("(?i)\\b(ab\\s*baat\\s*kar\\s*sakti\\s*ho|baat\\s*karo|bolo\\s*kavya|mujhse\\s*baat\\s*karo|bol\\s*sakti\\s*ho)\\b"),
        Pattern.compile("(?i)\\b(talk\\s*to\\s*me|start\\s*talking|you\\s*can\\s*talk|resume\\s*talking|proactive\\s*mode\\s*on)\\b")
    )

    // Regex patterns for temporary silence (e.g. 5 min, 10 min)
    private val TIMED_SILENCE_PATTERN = Pattern.compile("(?i)(\\d+)\\s*(मिनट|min|minute|minutes|mins)\\s*(चुप|शांत|mat\\s*bolo|quiet|silence)")
    private val TIMED_SILENCE_PATTERN_ALT = Pattern.compile("(?i)(चुप|शांत|quiet|silence)\\s*(for)?\\s*(\\d+)\\s*(मिनट|min|minute|minutes|mins)")
    private val SHORT_TEMPORARY_SILENCE = Pattern.compile("(?i)\\b(अभी\\s*थोड़ी\\s*देर\\s*मत\\s*बोलना|थोड़ी\\s*देर\\s*चुप\\s*रहो|abhi\\s*thodi\\s*der\\s*mat\\s*bolna|thodi\\s*der\\s*chup\\s*raho|quiet\\s*for\\s*a\\s*bit)\\b")

    // Frequency reduction: "कम बोला करो", "speak less", "kam bolo"
    private val REDUCE_FREQ_PATTERNS = listOf(
        Pattern.compile("(?i)\\b(कम\\s*बोला\\s*करो|कम\\s*बात\\s*करो|इतना\\s*मत\\s*बोलो|kam\\s*bola\\s*karo|kam\\s*baat\\s*karo|speak\\s*less|talk\\s*less)\\b")
    )

    // Frequency increase: "मुझसे बात करती रहा करो", "keep talking", "zyada baat karo"
    private val INCREASE_FREQ_PATTERNS = listOf(
        Pattern.compile("(?i)\\b(मुझसे\\s*बात\\s*करती\\s*रहा\\s*करो|ज़्यादा\\s*बात\\s*करो|keep\\s*talking\\s*to\\s*me|talk\\s*more|chat\\s*more|baat\\s*karti\\s*raha\\s*karo)\\b")
    )

    fun evaluate(query: String): SilenceDetectionResult {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return SilenceDetectionResult(false, SilenceAction.NONE)

        // 1. Check for timed silence first
        var matcher = TIMED_SILENCE_PATTERN.matcher(trimmed)
        if (matcher.find()) {
            val minutes = matcher.group(1)?.toIntOrNull() ?: 10
            val durationMs = minutes * 60 * 1000L
            return SilenceDetectionResult(
                detected = true,
                action = SilenceAction.TEMPORARY_SILENCE,
                silenceDurationMs = durationMs,
                confirmationText = "[calm] Theek hai, main agle $minutes minute ke liye bilkul shant rahungi. Agar koi kaam ho toh bas mujhe bata dena."
            )
        }

        matcher = TIMED_SILENCE_PATTERN_ALT.matcher(trimmed)
        if (matcher.find()) {
            val minutes = matcher.group(3)?.toIntOrNull() ?: 10
            val durationMs = minutes * 60 * 1000L
            return SilenceDetectionResult(
                detected = true,
                action = SilenceAction.TEMPORARY_SILENCE,
                silenceDurationMs = durationMs,
                confirmationText = "[calm] Theek hai, agle $minutes minute tak main proactive interact nahi karungi. Jab bhi zarurat ho, bula lena."
            )
        }

        if (SHORT_TEMPORARY_SILENCE.matcher(trimmed).find()) {
            val durationMs = 10 * 60 * 1000L // Default 10 minutes
            return SilenceDetectionResult(
                detected = true,
                action = SilenceAction.TEMPORARY_SILENCE,
                silenceDurationMs = durationMs,
                confirmationText = "[calm] Samajh gayi yaar, main abhi thodi der ke liye shant rehti hoon."
            )
        }

        // 2. Check for permanent silence commands
        for (pat in PERMANENT_SILENCE_PATTERNS) {
            if (pat.matcher(trimmed).find()) {
                return SilenceDetectionResult(
                    detected = true,
                    action = SilenceAction.TURN_OFF_PROACTIVE,
                    confirmationText = "[calm] Theek hai, main abhi shant ho jati hoon. Proactive mode off kar diya hai. Jab baat karni ho toh bol dena."
                )
            }
        }

        // 3. Check for resume commands
        for (pat in RESUME_PATTERNS) {
            if (pat.matcher(trimmed).find()) {
                return SilenceDetectionResult(
                    detected = true,
                    action = SilenceAction.TURN_ON_PROACTIVE,
                    confirmationText = "[happy] Main wapas active hoon! Ab hum natural conversation continue kar sakte hain."
                )
            }
        }

        // 4. Check for reduce frequency commands
        for (pat in REDUCE_FREQ_PATTERNS) {
            if (pat.matcher(trimmed).find()) {
                return SilenceDetectionResult(
                    detected = true,
                    action = SilenceAction.DECREASE_FREQUENCY,
                    confirmationText = "[warm] Theek hai, maine note kar liya hai. Ab main kam aur sirf zaruri moments par bolungi."
                )
            }
        }

        // 5. Check for increase frequency commands
        for (pat in INCREASE_FREQ_PATTERNS) {
            if (pat.matcher(trimmed).find()) {
                return SilenceDetectionResult(
                    detected = true,
                    action = SilenceAction.INCREASE_FREQUENCY,
                    confirmationText = "[excited] Yeh hui na baat! Main aapse regular baat karti rahungi."
                )
            }
        }

        return SilenceDetectionResult(false, SilenceAction.NONE)
    }
}
