package com.example.visual

import android.content.Context
import android.util.Log
import com.example.utils.AppResolver
import java.util.Locale

/**
 * Visual Action Planner for the Kavya Visual Action Engine.
 * Decomposes multi-step voice commands into sequential visual automation milestones.
 */
class VisualActionPlanner(private val context: Context) {

    companion object {
        private const val TAG = "KavyaVisualPlanner"
    }

    /**
     * Initializes a VisualTaskMemory for a new user goal.
     */
    fun initializePlan(userGoal: String): VisualTaskMemory {
        val detectedApp = detectTargetApp(userGoal)
        return VisualTaskMemory(
            goal = userGoal,
            currentApp = detectedApp,
            currentStep = 1,
            completedSteps = mutableListOf(),
            nextStep = "Open $detectedApp",
            retryCount = 0
        )
    }

    /**
     * Identifies the primary application name or package from the user command.
     */
    fun detectTargetApp(goal: String): String {
        val lower = goal.lowercase(Locale.ROOT)
        return when {
            lower.contains("free fire") || lower.contains("freefire") -> "Free Fire"
            lower.contains("youtube") -> "YouTube"
            lower.contains("chrome") || lower.contains("browser") -> "Chrome"
            lower.contains("whatsapp") -> "WhatsApp"
            lower.contains("instagram") -> "Instagram"
            lower.contains("settings") || lower.contains("setting") -> "Settings"
            lower.contains("camera") -> "Camera"
            lower.contains("spotify") -> "Spotify"
            lower.contains("play store") -> "Play Store"
            else -> {
                // Try resolving via AppResolver
                val appResolver = AppResolver(context)
                val resolved = appResolver.resolve(goal)
                resolved.matchedApp?.appName ?: "Current Screen"
            }
        }
    }

    /**
     * Checks if the user command requires opening a new application first.
     */
    fun requiresAppLaunch(goal: String): Boolean {
        val lower = goal.lowercase(Locale.ROOT)
        return lower.contains("open") || lower.contains("launch") || lower.contains("start") ||
                lower.contains("खोलो") || lower.contains("चालू करो") || lower.contains("shuru karo") ||
                lower.contains("kholo")
    }

    /**
     * Extracts clean target UI element name from compound user speech or goal strings.
     */
    fun extractActionTarget(goal: String): String {
        val lower = goal.lowercase(Locale.ROOT).trim()

        val leadingPrefixes = listOf(
            "tap on ", "click on ", "press on ", "select ", "tap ", "click ", "press ",
            "टैप करो ", "क्लिक करो ", "दबाओ "
        )
        for (p in leadingPrefixes) {
            if (lower.startsWith(p)) {
                val candidate = goal.substring(p.length).trim()
                if (candidate.isNotBlank()) return candidate
            }
        }

        val trailingSuffixes = listOf(
            " पर क्लिक करो", " पर टैप करो", " दबाओ", " क्लिक करो", " टैप करो", " खोलो"
        )
        for (s in trailingSuffixes) {
            if (lower.endsWith(s)) {
                val candidate = goal.substring(0, goal.length - s.length).trim()
                if (candidate.isNotBlank()) return candidate
            }
        }

        return goal
    }

    /**
     * Extracts query text for search commands.
     */
    fun extractSearchQuery(goal: String): String? {
        val lower = goal.lowercase(Locale.ROOT)
        val patterns = listOf(
            "search में ", "search me ", "search for ", "search ",
            "ढूंढो ", "खोजो ", "type "
        )
        for (p in patterns) {
            if (lower.contains(p)) {
                val query = lower.substringAfter(p).trim()
                if (query.isNotBlank()) return query
            }
        }
        return null
    }
}
