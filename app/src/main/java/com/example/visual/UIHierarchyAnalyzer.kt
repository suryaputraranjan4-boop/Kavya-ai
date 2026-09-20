package com.example.visual

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.services.KavyaAccessibilityService
import java.util.Locale

/**
 * UI Hierarchy Analyzer for the Kavya Visual Action Engine.
 * Implements the Accessibility-First strategy:
 * Reads accessibility nodes, bounding rectangles, resource IDs, and interactive flags.
 */
class UIHierarchyAnalyzer {

    companion object {
        private const val TAG = "KavyaHierarchyAnalyzer"
        private const val MAX_NODES_TO_COLLECT = 80
    }

    /**
     * Traverses the active accessibility window tree and extracts structured HierarchyNodeInfo.
     */
    fun collectVisibleNodes(root: AccessibilityNodeInfo?): List<HierarchyNodeInfo> {
        val activeRoot = root ?: KavyaAccessibilityService.instance?.rootInActiveWindow ?: return emptyList()
        val results = mutableListOf<HierarchyNodeInfo>()
        traverseAndCollect(activeRoot, results, 0)
        return results
    }

    private fun traverseAndCollect(node: AccessibilityNodeInfo?, list: MutableList<HierarchyNodeInfo>, depth: Int) {
        if (node == null || list.size >= MAX_NODES_TO_COLLECT) return

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val resId = node.viewIdResourceName?.substringAfterLast("/")
        val className = node.className?.toString()?.substringAfterLast(".")

        val isMeaningful = !text.isNullOrEmpty() || !desc.isNullOrEmpty() || !resId.isNullOrEmpty() ||
                node.isClickable || node.isEditable || node.isScrollable

        if (isMeaningful) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            // Ignore zero or invalid bounds
            if (bounds.width() > 0 && bounds.height() > 0) {
                list.add(
                    HierarchyNodeInfo(
                        text = text,
                        contentDescription = desc,
                        resourceId = resId,
                        className = className,
                        bounds = bounds,
                        isClickable = node.isClickable,
                        isEnabled = node.isEnabled,
                        isScrollable = node.isScrollable,
                        isSelected = node.isSelected,
                        isFocused = node.isFocused,
                        isEditable = node.isEditable
                    )
                )
            }
        }

        for (i in 0 until node.childCount) {
            if (list.size >= MAX_NODES_TO_COLLECT) break
            val child = node.getChild(i)
            traverseAndCollect(child, list, depth + 1)
        }
    }

    /**
     * Internal candidate holder for scoring and ranking accessibility nodes.
     */
    private data class ScoredNode(
        val nodeInfo: HierarchyNodeInfo,
        val rawNode: AccessibilityNodeInfo,
        val score: Int
    )

    /**
     * Finds the best matching accessibility node for a given target label or semantic hint.
     * Uses strict candidate scoring, rejects giant containers and off-screen nodes to prevent random taps.
     */
    fun findMatchingNode(target: String, root: AccessibilityNodeInfo?): Pair<HierarchyNodeInfo?, AccessibilityNodeInfo?> {
        val activeRoot = root ?: KavyaAccessibilityService.instance?.rootInActiveWindow ?: return Pair(null, null)
        val lowerTarget = target.lowercase(Locale.ROOT).trim()
        if (lowerTarget.isBlank()) return Pair(null, null)

        val candidates = mutableListOf<ScoredNode>()

        fun evaluate(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 18) return

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            // Skip invalid bounds or completely zero-area nodes
            if (bounds.width() <= 0 || bounds.height() <= 0) {
                for (i in 0 until node.childCount) {
                    evaluate(node.getChild(i), depth + 1)
                }
                return
            }

            // Reject giant full-screen containers as direct tap targets
            val isGiant = bounds.width() > 1000 && bounds.height() > 1800

            val text = node.text?.toString()?.trim()?.lowercase(Locale.ROOT) ?: ""
            val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT) ?: ""
            val resId = node.viewIdResourceName?.substringAfterLast("/")?.lowercase(Locale.ROOT) ?: ""

            var score = 0

            // 1. Exact matches (highest confidence)
            when {
                text == lowerTarget -> score += 120
                desc == lowerTarget -> score += 115
                resId == lowerTarget -> score += 95
            }

            // 2. Word boundary match (e.g. target "search" in "Search Google")
            val wordRegex = Regex("\\b${Regex.escape(lowerTarget)}\\b")
            if (score == 0) {
                if (wordRegex.containsMatchIn(text)) {
                    score += 85
                } else if (wordRegex.containsMatchIn(desc)) {
                    score += 80
                } else if (wordRegex.containsMatchIn(resId)) {
                    score += 65
                }
            }

            // 3. Prefix match for tokens >= 3 chars
            if (score == 0 && lowerTarget.length >= 3) {
                if (text.startsWith(lowerTarget)) {
                    score += 55
                } else if (desc.startsWith(lowerTarget)) {
                    score += 50
                } else if (resId.startsWith(lowerTarget)) {
                    score += 35
                }
            }

            // 4. Meaningful substring match: ONLY for targets >= 3 chars and concise labels
            if (score == 0 && lowerTarget.length >= 3) {
                if (text.contains(lowerTarget) && text.length <= 40) {
                    score += 35
                } else if (desc.contains(lowerTarget) && desc.length <= 40) {
                    score += 30
                } else if (resId.contains(lowerTarget) && resId.length <= 30) {
                    score += 20
                }
            }

            if (score > 0) {
                // Interactive bonuses
                if (node.isClickable) score += 30
                if (node.isCheckable || node.isEditable) score += 20
                if (node.isFocusable) score += 10

                // Size appropriateness
                if (isGiant) {
                    score -= 100 // Heavily penalize full-screen container matches
                } else if (bounds.width() in 20..700 && bounds.height() in 20..350) {
                    score += 20 // Compact interactive element bonus
                }

                // If node is not clickable, bonus if immediate parent is clickable
                if (!node.isClickable && node.parent?.isClickable == true) {
                    score += 25
                }

                if (score >= 45) {
                    val info = HierarchyNodeInfo(
                        text = node.text?.toString(),
                        contentDescription = node.contentDescription?.toString(),
                        resourceId = node.viewIdResourceName?.substringAfterLast("/"),
                        className = node.className?.toString()?.substringAfterLast("."),
                        bounds = bounds,
                        isClickable = node.isClickable,
                        isEnabled = node.isEnabled,
                        isScrollable = node.isScrollable,
                        isSelected = node.isSelected,
                        isFocused = node.isFocused,
                        isEditable = node.isEditable
                    )
                    candidates.add(ScoredNode(info, node, score))
                }
            }

            for (i in 0 until node.childCount) {
                evaluate(node.getChild(i), depth + 1)
            }
        }

        evaluate(activeRoot, 0)

        val best = candidates.maxByOrNull { it.score }
        return if (best != null) {
            Pair(best.nodeInfo, best.rawNode)
        } else {
            Pair(null, null)
        }
    }

    /**
     * Finds the deepest/most specific interactive accessibility node that directly encloses (x, y).
     * Used to lock on exact button/item boundaries when visual coordinates are provided.
     */
    fun findInteractiveNodeAt(x: Float, y: Float, root: AccessibilityNodeInfo?): Pair<HierarchyNodeInfo?, AccessibilityNodeInfo?> {
        val activeRoot = root ?: KavyaAccessibilityService.instance?.rootInActiveWindow ?: return Pair(null, null)
        val ix = x.toInt()
        val iy = y.toInt()

        var bestNode: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE

        fun search(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 18) return

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            if (bounds.contains(ix, iy)) {
                val isInteractive = node.isClickable || node.isCheckable || node.isEditable || node.isFocusable
                val area = bounds.width().toLong() * bounds.height().toLong()

                // Reject giant full-screen containers
                val isGiant = bounds.width() > 1000 && bounds.height() > 1800

                if (isInteractive && !isGiant && area in 1 until bestArea) {
                    bestArea = area
                    bestNode = node
                }

                for (i in 0 until node.childCount) {
                    search(node.getChild(i), depth + 1)
                }
            }
        }

        search(activeRoot, 0)

        return if (bestNode != null) {
            val b = Rect()
            bestNode!!.getBoundsInScreen(b)
            val info = HierarchyNodeInfo(
                text = bestNode!!.text?.toString(),
                contentDescription = bestNode!!.contentDescription?.toString(),
                resourceId = bestNode!!.viewIdResourceName?.substringAfterLast("/"),
                className = bestNode!!.className?.toString()?.substringAfterLast("."),
                bounds = b,
                isClickable = bestNode!!.isClickable,
                isEnabled = bestNode!!.isEnabled,
                isScrollable = bestNode!!.isScrollable,
                isSelected = bestNode!!.isSelected,
                isFocused = bestNode!!.isFocused,
                isEditable = bestNode!!.isEditable
            )
            Pair(info, bestNode)
        } else {
            Pair(null, null)
        }
    }

    /**
     * Generates a clean text representation of visible UI elements for hybrid reasoning.
     */
    fun buildHierarchySummary(nodes: List<HierarchyNodeInfo>): String {
        if (nodes.isEmpty()) return "No accessibility elements detected."
        val sb = StringBuilder()
        nodes.take(25).forEach { node ->
            val label = listOfNotNull(node.text, node.contentDescription).filter { it.isNotBlank() }.joinToString(" | ")
            val idStr = if (!node.resourceId.isNullOrBlank()) " [id:${node.resourceId}]" else ""
            val clickStr = if (node.isClickable) " [Clickable]" else ""
            val editStr = if (node.isEditable) " [Input]" else ""
            val display = if (label.isNotBlank()) label else "[${node.className ?: "Element"}]"
            sb.append("- $display$idStr$clickStr$editStr bounds=(${node.bounds.left},${node.bounds.top})-(${node.bounds.right},${node.bounds.bottom})\n")
        }
        return sb.toString()
    }
}
