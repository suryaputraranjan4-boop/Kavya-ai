package com.example.visual

import android.graphics.Rect
import java.util.Locale

/**
 * Structured target information identified by visual/semantic perception.
 */
data class VisualTargetInfo(
    val target: String,
    val x: Int,
    val y: Int,
    val confidence: Float,
    val targetType: String, // icon, button, search_field, profile_row, card, tab, text, menu_item
    val action: String, // tap, double_tap, long_press, type, scroll, select
    val bounds: Rect? = null,
    val surroundingContext: String? = null
)

sealed class DisambiguationResult {
    data class Resolved(val targetInfo: VisualTargetInfo) : DisambiguationResult()
    data class Ambiguous(val candidateLabels: List<String>, val questionForUser: String) : DisambiguationResult()
    data class NotFound(val reason: String) : DisambiguationResult()
}

/**
 * Target Disambiguator for Kavya AI.
 *
 * Solves the problem of ambiguous UI elements (multiple identical labels, repeated names,
 * icons that look alike) using:
 * 1. Screen position (header vs body vs bottom bar)
 * 2. Visual and semantic hierarchy (is inside a list row, card, tab bar)
 * 3. Surrounding sibling labels and icons (e.g. avatar, timestamp, badge)
 * 4. Application context (e.g. WhatsApp chat list vs contact search)
 * 5. Asking short clarification instead of guessing when confidence is low.
 */
class TargetDisambiguator {

    companion object {
        private const val CONFIDENCE_THRESHOLD_RESOLVE = 0.82f
    }

    /**
     * Disambiguates candidates matching a target description.
     */
    fun disambiguate(
        targetQuery: String,
        candidates: List<HierarchyNodeInfo>,
        screenWidth: Int,
        screenHeight: Int,
        activeApp: String
    ): DisambiguationResult {
        if (candidates.isEmpty()) {
            return DisambiguationResult.NotFound("No elements found matching '$targetQuery'")
        }

        if (candidates.size == 1) {
            val single = candidates[0]
            val center = single.getNormalizedCenter(screenWidth, screenHeight)
            val devCoords = center.toDeviceCoordinates(screenWidth, screenHeight)
            val targetType = inferTargetType(single)

            return DisambiguationResult.Resolved(
                VisualTargetInfo(
                    target = single.text ?: single.contentDescription ?: targetQuery,
                    x = devCoords.x.toInt(),
                    y = devCoords.y.toInt(),
                    confidence = 0.95f,
                    targetType = targetType,
                    action = if (single.isEditable) "type" else "tap",
                    bounds = single.bounds,
                    surroundingContext = single.className
                )
            )
        }

        // Multiple candidates detected: evaluate positional & context signals
        val lowerTarget = targetQuery.lowercase(Locale.ROOT)
        val scoredCandidates = candidates.map { node ->
            var score = 0f
            val nodeText = (node.text ?: "").lowercase(Locale.ROOT)
            val nodeDesc = (node.contentDescription ?: "").lowercase(Locale.ROOT)

            // Exact text match bonus
            if (nodeText == lowerTarget || nodeDesc == lowerTarget) score += 30f
            else if (nodeText.startsWith(lowerTarget) || nodeDesc.startsWith(lowerTarget)) score += 20f

            // Clickable bonus
            if (node.isClickable) score += 15f
            if (node.isFocused) score += 10f

            // Position heuristics:
            // 1. Header elements (top 15% of screen) - typical for Search, Back, Title
            val isHeader = node.bounds.centerY() < screenHeight * 0.15f
            val isBottomBar = node.bounds.centerY() > screenHeight * 0.85f
            val isBody = !isHeader && !isBottomBar

            if (lowerTarget.contains("search") || lowerTarget.contains("back") || lowerTarget.contains("menu")) {
                if (isHeader) score += 25f
            } else if (lowerTarget.contains("tab") || lowerTarget.contains("home") || lowerTarget.contains("chats") || lowerTarget.contains("reels")) {
                if (isBottomBar || isHeader) score += 20f
            } else {
                // Regular content / contacts / video items should be in the body area
                if (isBody) score += 25f
            }

            // Size sanity: reject giant backdrop containers
            val area = node.bounds.width() * node.bounds.height()
            val totalArea = screenWidth * screenHeight
            if (area > totalArea * 0.70f) {
                score -= 40f
            }

            Pair(node, score)
        }.sortedByDescending { it.second }

        val best = scoredCandidates[0]
        val secondBest = scoredCandidates.getOrNull(1)

        val scoreDiff = if (secondBest != null) best.second - secondBest.second else 100f

        // High confidence winner
        if (scoreDiff >= 20f || best.second >= 60f) {
            val node = best.first
            val center = node.getNormalizedCenter(screenWidth, screenHeight)
            val devCoords = center.toDeviceCoordinates(screenWidth, screenHeight)

            return DisambiguationResult.Resolved(
                VisualTargetInfo(
                    target = node.text ?: node.contentDescription ?: targetQuery,
                    x = devCoords.x.toInt(),
                    y = devCoords.y.toInt(),
                    confidence = 0.90f,
                    targetType = inferTargetType(node),
                    action = if (node.isEditable) "type" else "tap",
                    bounds = node.bounds,
                    surroundingContext = "Resolved with score ${best.second}"
                )
            )
        }

        // Genuine ambiguity: multiple equally probable candidates (e.g. multiple "Didi" in contact list)
        val candidateNames = candidates.mapNotNull { it.text ?: it.contentDescription }.distinct().take(4)
        val question = if (activeApp.contains("WhatsApp", ignoreCase = true) || activeApp.contains("Contact", ignoreCase = true)) {
            "मुझे कई '$targetQuery' मिले हैं। कौन-से वाले को चुनना है?"
        } else {
            "There are multiple items matching '$targetQuery'. Which one would you like to select?"
        }

        return DisambiguationResult.Ambiguous(candidateNames, question)
    }

    private fun inferTargetType(node: HierarchyNodeInfo): String {
        val cls = (node.className ?: "").lowercase(Locale.ROOT)
        val desc = (node.contentDescription ?: "").lowercase(Locale.ROOT)
        return when {
            node.isEditable || cls.contains("edit") || desc.contains("search") -> "search_field"
            cls.contains("button") || cls.contains("imagebutton") -> "button"
            cls.contains("image") || desc.contains("icon") -> "icon"
            cls.contains("tab") -> "tab"
            cls.contains("card") -> "card"
            cls.contains("viewgroup") || cls.contains("layout") -> "profile_row"
            else -> "element"
        }
    }
}
