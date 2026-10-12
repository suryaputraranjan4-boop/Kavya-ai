package com.example.agent

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * UI Element Resolver (Requirement 26).
 * Priority for interacting with UI:
 * 1. Accessibility Node exact match
 * 2. Text match (case-insensitive substring)
 * 3. Content Description match
 * 4. View ID resource name match
 * 5. Semantic Parent / Child relation
 * 6. Verified Bounds (centerX, centerY of matched node)
 * 7. Coordinate fallback only when Accessibility tree is obscured.
 */
object UIElementResolver {

    private const val TAG = "KavyaUIElementResolver"

    data class ResolvedUIElement(
        val node: AccessibilityNodeInfo?,
        val targetText: String,
        val bounds: Rect,
        val clickPoint: Pair<Int, Int>,
        val resolutionMethod: ResolutionMethod,
        val confidence: Float
    )

    enum class ResolutionMethod {
        ACCESSIBILITY_NODE_DIRECT,
        TEXT_MATCH,
        CONTENT_DESCRIPTION_MATCH,
        VIEW_ID_MATCH,
        SEMANTIC_HIERARCHY,
        BOUNDS_FALLBACK
    }

    enum class SelectorType {
        ANY,
        TEXT,
        CONTENT_DESCRIPTION,
        VIEW_ID,
        ORDINAL
    }

    fun resolveTargetElement(
        screen: FreshScreenState,
        targetQuery: String,
        selectorType: SelectorType = SelectorType.ANY
    ): ResolvedUIElement? {
        val q = targetQuery.lowercase(Locale.ROOT).trim()
        if (q.isBlank()) return null

        val nodes = screen.nodes

        // 1. Exact Text Match
        val textExact = nodes.firstOrNull { it.text.equals(q, ignoreCase = true) && (it.isClickable || it.node.parent?.isClickable == true) }
        if (textExact != null) {
            val b = textExact.bounds
            return ResolvedUIElement(
                node = textExact.node,
                targetText = textExact.text,
                bounds = b,
                clickPoint = Pair(b.centerX(), b.centerY()),
                resolutionMethod = ResolutionMethod.TEXT_MATCH,
                confidence = 1.0f
            )
        }

        // 2. Exact Content Description Match
        val descExact = nodes.firstOrNull { it.contentDescription.equals(q, ignoreCase = true) && (it.isClickable || it.node.parent?.isClickable == true) }
        if (descExact != null) {
            val b = descExact.bounds
            return ResolvedUIElement(
                node = descExact.node,
                targetText = descExact.contentDescription,
                bounds = b,
                clickPoint = Pair(b.centerX(), b.centerY()),
                resolutionMethod = ResolutionMethod.CONTENT_DESCRIPTION_MATCH,
                confidence = 0.95f
            )
        }

        // 3. Substring Text Match
        val textSubstring = nodes.firstOrNull { it.text.lowercase(Locale.ROOT).contains(q) && (it.isClickable || it.node.parent?.isClickable == true) }
        if (textSubstring != null) {
            val b = textSubstring.bounds
            return ResolvedUIElement(
                node = textSubstring.node,
                targetText = textSubstring.text,
                bounds = b,
                clickPoint = Pair(b.centerX(), b.centerY()),
                resolutionMethod = ResolutionMethod.TEXT_MATCH,
                confidence = 0.9f
            )
        }

        // 4. View ID Match
        val viewIdMatch = nodes.firstOrNull { it.resourceId.lowercase(Locale.ROOT).contains(q) }
        if (viewIdMatch != null) {
            val b = viewIdMatch.bounds
            return ResolvedUIElement(
                node = viewIdMatch.node,
                targetText = viewIdMatch.resourceId,
                bounds = b,
                clickPoint = Pair(b.centerX(), b.centerY()),
                resolutionMethod = ResolutionMethod.VIEW_ID_MATCH,
                confidence = 0.85f
            )
        }

        // 5. Ordinal / Index selection ("first result", "second video", "1st result")
        if (q.contains("first") || q.contains("pehla") || q.contains("1st")) {
            val clickableItem = nodes.firstOrNull { it.isClickable && it.bounds.top > 150 }
            if (clickableItem != null) {
                val b = clickableItem.bounds
                return ResolvedUIElement(
                    node = clickableItem.node,
                    targetText = clickableItem.text.ifBlank { "First Result" },
                    bounds = b,
                    clickPoint = Pair(b.centerX(), b.centerY()),
                    resolutionMethod = ResolutionMethod.SEMANTIC_HIERARCHY,
                    confidence = 0.8f
                )
            }
        }

        Log.d(TAG, "Target element '$targetQuery' could not be resolved in active UI.")
        return null
    }
}
