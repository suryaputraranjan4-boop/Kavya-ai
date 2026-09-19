package com.example.agent

import android.view.accessibility.AccessibilityNodeInfo
import com.example.services.KavyaAccessibilityService
import java.util.Locale

/**
 * Screen perception snapshot capturing the full accessible state of the active foreground window.
 */
data class ScreenPerception(
    val foregroundPackage: String,
    val elementSummaries: List<String>,
    val searchFieldAvailable: Boolean,
    val contentResultsCount: Int,
    val isScrollable: Boolean,
    val rawContextString: String
)

/**
 * Screen and UI perception engine.
 * Inspects real visible accessibility nodes, clickable buttons, editable fields, and web elements.
 * Never relies on hardcoded coordinates as the primary automation strategy.
 */
class ScreenInspector {

    data class NodeSummary(
        val text: String?,
        val contentDescription: String?,
        val resourceId: String?,
        val className: String?,
        val isClickable: Boolean,
        val isEditable: Boolean,
        val isScrollable: Boolean
    )

    fun isAccessibilityAvailable(): Boolean {
        return KavyaAccessibilityService.instance != null
    }

    fun getCurrentForegroundPackage(): String {
        val service = KavyaAccessibilityService.instance ?: return "unknown"
        return service.getForegroundPackage()
    }

    fun getScreenContextString(): String {
        val service = KavyaAccessibilityService.instance ?: return "Accessibility Service not connected."
        return service.getScreenContext()
    }

    fun getVisibleElementSummaries(): List<String> {
        val service = KavyaAccessibilityService.instance ?: return emptyList()
        return service.getVisibleElementSummaries()
    }

    /**
     * Inspects the current active window and produces a comprehensive perception snapshot.
     */
    fun inspectScreen(): ScreenPerception {
        val service = KavyaAccessibilityService.instance
        if (service == null) {
            return ScreenPerception(
                foregroundPackage = "none",
                elementSummaries = emptyList(),
                searchFieldAvailable = false,
                contentResultsCount = 0,
                isScrollable = false,
                rawContextString = "Accessibility Service not bound."
            )
        }

        val root = service.rootInActiveWindow
        val summaries = service.getVisibleElementSummaries()
        val searchNode = service.findSearchField(root)
        val firstResult = service.findOrdinalContentNode(root, 0)
        val rawContext = service.getScreenContext()

        return ScreenPerception(
            foregroundPackage = service.getForegroundPackage(),
            elementSummaries = summaries,
            searchFieldAvailable = searchNode != null,
            contentResultsCount = if (firstResult != null) 1 else 0,
            isScrollable = rawContext.contains("[Scrollable]"),
            rawContextString = rawContext
        )
    }

    /**
     * Finds the corresponding accessible node for a task step using semantic UI analysis.
     */
    fun findTargetNodeForStep(step: TaskStep): AccessibilityNodeInfo? {
        val service = KavyaAccessibilityService.instance ?: return null
        val root = service.rootInActiveWindow ?: return null

        return when (step.selectorType) {
            SelectorType.SEARCH_FIELD, SelectorType.URL_BAR -> {
                service.findSearchField(root) ?: findNodeByTextOrDesc(root, "search")
            }
            SelectorType.ORDINAL_RESULT -> {
                service.findOrdinalContentNode(root, step.ordinalIndex) ?: findFirstSearchResultNode(root)
            }
            SelectorType.PLAY_BUTTON -> {
                findPlayControlNode(root)
            }
            SelectorType.REELS_CONTROL -> {
                findReelsControlNode(root)
            }
            SelectorType.SUBMIT_BUTTON -> {
                findSubmitControlNode(root)
            }
            SelectorType.CONTACT_ITEM -> {
                findContactNode(root, step.param)
            }
            SelectorType.EXACT_TEXT, SelectorType.SEMANTIC_HINT, SelectorType.ANY_CLICKABLE, SelectorType.GENERIC -> {
                findTargetNode(step.param, preferredType = null)
            }
        }
    }

    /**
     * Inspects active root node to find a node matching target criteria.
     */
    fun findTargetNode(
        targetQuery: String,
        preferredType: String? = null
    ): AccessibilityNodeInfo? {
        val service = KavyaAccessibilityService.instance ?: return null
        val root = service.rootInActiveWindow ?: return null

        val lowerQuery = targetQuery.lowercase(Locale.ROOT).trim()

        if (preferredType == "EDITABLE" || lowerQuery == "search" || lowerQuery == "searchbar" || lowerQuery == "search bar") {
            return service.findSearchField(root) ?: findNodeByTextOrDesc(root, "search")
        }

        if (preferredType == "FIRST_RESULT" || lowerQuery == "first_result" || lowerQuery == "first result" || lowerQuery == "pehli website" || lowerQuery == "pehla video") {
            return service.findOrdinalContentNode(root, 0) ?: findFirstSearchResultNode(root)
        }

        if (preferredType == "PLAY_BUTTON" || lowerQuery == "play" || lowerQuery == "play online") {
            return findPlayControlNode(root)
        }

        if (lowerQuery.contains("reel") || lowerQuery.contains("reels")) {
            return findReelsControlNode(root)
        }

        return findNodeByTextOrDesc(root, targetQuery)
    }

    private fun findPlayControlNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findNodeByTextOrDesc(root, "play online")
            ?: findNodeByTextOrDesc(root, "play vs computer")
            ?: findNodeByTextOrDesc(root, "play with a friend")
            ?: findNodeByTextOrDesc(root, "play")
            ?: findNodeByTextOrDesc(root, "start game")
            ?: findNodeByTextOrDesc(root, "start")
            ?: findNodeByTextOrDesc(root, "resume")
    }

    private fun findReelsControlNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findNodeByTextOrDesc(root, "Reels")
            ?: findNodeByTextOrDesc(root, "reels")
            ?: findNodeByTextOrDesc(root, "Shorts")
            ?: findNodeByTextOrDesc(root, "shorts")
            ?: findNodeByTextOrDesc(root, "video tab")
    }

    private fun findSubmitControlNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findNodeByTextOrDesc(root, "Search")
            ?: findNodeByTextOrDesc(root, "Go")
            ?: findNodeByTextOrDesc(root, "Submit")
            ?: findNodeByTextOrDesc(root, "Send")
    }

    private fun findContactNode(root: AccessibilityNodeInfo, contactName: String): AccessibilityNodeInfo? {
        if (contactName.isNotBlank()) {
            val direct = findNodeByTextOrDesc(root, contactName)
            if (direct != null) return direct
        }
        return findFirstSearchResultNode(root)
    }

    private fun findNodeByTextOrDesc(node: AccessibilityNodeInfo?, target: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val lowerTarget = target.lowercase(Locale.ROOT).trim()

        val text = node.text?.toString()?.lowercase(Locale.ROOT)?.trim()
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT)?.trim()
        val resId = node.viewIdResourceName?.substringAfterLast("/")?.lowercase(Locale.ROOT)

        if (text?.contains(lowerTarget) == true ||
            desc?.contains(lowerTarget) == true ||
            resId?.contains(lowerTarget) == true) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = findNodeByTextOrDesc(node.getChild(i), target)
            if (child != null) return child
        }
        return null
    }

    private fun findFirstSearchResultNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        
        val clickableNodes = mutableListOf<AccessibilityNodeInfo>()
        collectClickableContentNodes(node, clickableNodes, 0)
        
        for (candidate in clickableNodes) {
            val txt = candidate.text?.toString() ?: candidate.contentDescription?.toString() ?: ""
            val lowerTxt = txt.lowercase(Locale.ROOT)
            if (txt.length > 3 && !lowerTxt.contains("search") && !lowerTxt.contains("filter") && !lowerTxt.contains("menu")) {
                return candidate
            }
        }

        return clickableNodes.firstOrNull()
    }

    private fun collectClickableContentNodes(node: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (node == null || list.size >= 12) return
        if (node.isClickable && depth > 0) {
            list.add(node)
        }
        for (i in 0 until node.childCount) {
            collectClickableContentNodes(node.getChild(i), list, depth + 1)
        }
    }
}

