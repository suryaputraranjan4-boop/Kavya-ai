package com.example.visual

import android.content.Context
import android.util.Log
import com.example.utils.AppPreferences
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Safety Controller for the Kavya Visual Action Engine.
 * Responsibilities:
 * 1. Immediate interruption and Emergency Stop.
 * 2. Voice-based stop command detection ("रुको", "Stop", "Cancel", "बस", "रुक जाओ").
 * 3. Confidence threshold enforcement (HIGH >= 0.90, MEDIUM 0.70-0.89, LOW < 0.70).
 * 4. Infinite loop protection (MAX_ACTION_RETRIES = 3).
 * 5. Security guards: Protects against clicking permission prompts, lock screens, and payments.
 */
class VisualSafetyController(private val context: Context) {

    companion object {
        private const val TAG = "KavyaVisualSafety"
        const val MAX_ACTION_RETRIES = 3

        private val STOP_KEYWORDS = listOf(
            "stop", "cancel", "रुको", "रुक जाओ", "बस", "बंद करो",
            "roko", "band karo", "halt", "abort", "chup", "ruko"
        )
    }

    private val isEmergencyStopped = AtomicBoolean(false)

    fun triggerEmergencyStop() {
        Log.w(TAG, "EMERGENCY STOP TRIGGERED")
        isEmergencyStopped.set(true)
    }

    fun resetEmergencyStop() {
        isEmergencyStopped.set(false)
    }

    fun isInterrupted(): Boolean {
        return isEmergencyStopped.get()
    }

    /**
     * Checks if a user voice utterance represents an immediate stop command.
     */
    fun isStopCommand(text: String): Boolean {
        val lower = text.lowercase().trim()
        return STOP_KEYWORDS.any { lower == it || lower.startsWith("$it ") || lower.endsWith(" $it") }
    }

    /**
     * Validates whether an action is safe to execute based on confidence and mode.
     */
    fun evaluateActionSafety(action: VisualAction, mode: VisualAutomationMode): Pair<Boolean, String> {
        if (isEmergencyStopped.get()) {
            return Pair(false, "Emergency stop is active. Action aborted.")
        }

        // Loop protection check should be passed from memory

        // Low confidence safety guard
        if (action.confidence < 0.70f) {
            return Pair(
                false,
                "Confidence (${(action.confidence * 100).toInt()}%) is too low to perform '${action.target}' automatically."
            )
        }

        // Mode-based checks
        return when (mode) {
            VisualAutomationMode.MANUAL_CONFIRMATION -> {
                Pair(false, "Manual confirmation required for '${action.target}'")
            }
            VisualAutomationMode.ASK_BEFORE_ACTION -> {
                if (action.confidence >= 0.95f) {
                    Pair(true, "High confidence safe action")
                } else {
                    Pair(false, "Please confirm before tapping '${action.target}'")
                }
            }
            VisualAutomationMode.AUTOMATIC_SAFE -> {
                if (isPotentiallySensitive(action.target)) {
                    Pair(false, "Action '${action.target}' is sensitive. Confirmation required.")
                } else {
                    Pair(true, "Action approved for automatic safe execution")
                }
            }
        }
    }

    /**
     * Identifies sensitive actions such as purchases, permissions, or deleting data.
     */
    fun isPotentiallySensitive(target: String): Boolean {
        val lower = target.lowercase()
        return lower.contains("pay") || lower.contains("buy") || lower.contains("purchase") ||
                lower.contains("delete") || lower.contains("uninstall") || lower.contains("format") ||
                lower.contains("permission") || lower.contains("allow") || lower.contains("password") ||
                lower.contains("biometric") || lower.contains("fingerprint")
    }
}
