package com.example.agent

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.services.KavyaAccessibilityService
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Analyzed UI node representation carrying accessibility metadata,
 * classification flags (ad, close/clear, avatar), and screen bounds.
 */
data class AnalyzedNode(
    val node: AccessibilityNodeInfo,
    val text: String,
    val contentDescription: String,
    val resourceId: String,
    val className: String,
    val bounds: Rect,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isAd: Boolean = false,
    val isClearOrClose: Boolean = false,
    val isAvatarOrPhoto: Boolean = false
) {
    val displayLabel: String
        get() = when {
            text.isNotBlank() && contentDescription.isNotBlank() && text != contentDescription -> "$text ($contentDescription)"
            text.isNotBlank() -> text
            contentDescription.isNotBlank() -> contentDescription
            resourceId.isNotBlank() -> "id:$resourceId"
            else -> className.substringAfterLast(".")
        }
}

/**
 * Perception snapshot of a freshly inspected active screen.
 * Contains classified nodes, detected search elements, organic content items,
 * and loading status indicators.
 */
data class FreshScreenState(
    val root: AccessibilityNodeInfo?,
    val foregroundPackage: String,
    val nodes: List<AnalyzedNode>,
    val visibleTexts: List<String>,
    val summaryString: String,
    val isLoading: Boolean,
    val searchFields: List<AnalyzedNode>,
    val searchButtons: List<AnalyzedNode>,
    val organicContentResults: List<AnalyzedNode>,
    val adNodes: List<AnalyzedNode>,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Fresh Screen Recognition Engine.
 * Central coordinator for obtaining the CURRENT, UNSTALE screen state immediately
 * before actions, validating screen stability, identifying organic targets, filtering
 * ads/sponsored content, and verifying post-action state transitions.
 *
 * Rules:
 * 1. Fast local recognition first -> More expensive recognition only when necessary.
 * 2. Never reuse stale AccessibilityNodeInfo references after an action.
 * 3. Screen stability check before interacting with dynamic UI.
 * 4. Strict ad/sponsored item filtering.
 * 5. Strict clear/close/cancel/avatar avoidance.
 */
class FreshScreenRecognizer(
    private val serviceProvider: () -> KavyaAccessibilityService? = { KavyaAccessibilityService.instance }
) {

    companion object {
        private const val TAG = "FreshScreenRecognizer"

        // Common loading keywords
        private val LOADING_KEYWORDS = listOf(
            "loading", "buffering", "searching", "please wait",
            "load more", "connecting", "लोड हो रहा है", "इंतज़ार करें"
        )

        // Ad and sponsored keywords across Hindi and English
        private val AD_KEYWORDS = listOf(
            "ad", "sponsored", "promoted", "pr", "advertisement",
            "विज्ञापन", "प्रायोजित", "visit site", "install now", "shop now",
            "learn more", "ad ·", "sponsored ·", "promoted ·"
        )

        // Auxiliary buttons that should never be selected as content/search targets
        private val CLEAR_OR_CLOSE_KEYWORDS = listOf(
            "clear query", "clear search", "clear", "close", "cancel", "dismiss",
            "delete", "cross", "remove", "हटाएं", "बंद करें", "रद्द करें"
        )
    }

    /**
     * Obtains the CURRENT, fresh screen state immediately before an action.
     * If [waitForStability] is true, waits adaptively until the screen stops shifting
     * or loading indicators disappear (with a minimum necessary delay).
     */
    suspend fun acquireFreshScreen(
        expectedPackage: String? = null,
        waitForStability: Boolean = true,
        maxWaitMs: Long = 3000L
    ): FreshScreenState {
        if (waitForStability) {
            return waitForScreenStability(expectedPackage = expectedPackage, maxWaitMs = maxWaitMs)
        }
        return inspectFreshScreenNow(expectedPackage)
    }

    /**
     * Inspects the active window directly without waiting.
     */
    fun inspectFreshScreenNow(expectedPackage: String? = null): FreshScreenState {
        val service = serviceProvider()
        if (service == null) {
            return FreshScreenState(
                root = null,
                foregroundPackage = "none",
                nodes = emptyList(),
                visibleTexts = emptyList(),
                summaryString = "Accessibility Service not bound",
                isLoading = false,
                searchFields = emptyList(),
                searchButtons = emptyList(),
                organicContentResults = emptyList(),
                adNodes = emptyList()
            )
        }

        val root = try {
            service.rootInActiveWindow
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching rootInActiveWindow: ${e.message}")
            null
        }

        val currentPkg = root?.packageName?.toString() ?: service.getForegroundPackage()
        val allNodes = mutableListOf<AnalyzedNode>()
        val visibleTexts = mutableListOf<String>()

        if (root != null) {
            collectAndClassifyNodes(root, allNodes, visibleTexts, depth = 0, maxNodes = 60)
        }

        val isLoading = detectLoadingState(allNodes, visibleTexts)
        val searchFields = allNodes.filter { it.isEditable && !it.isClearOrClose && !it.isAd }
        val searchButtons = allNodes.filter {
            !it.isEditable && !it.isClearOrClose && !it.isAd && (
                it.text.contains("search", ignoreCase = true) ||
                it.contentDescription.contains("search", ignoreCase = true) ||
                it.contentDescription.contains("खोजें", ignoreCase = true) ||
                it.resourceId.contains("search", ignoreCase = true)
            ) && (it.isClickable || it.className.contains("button", ignoreCase = true) || it.className.contains("image", ignoreCase = true))
        }

        val adNodes = allNodes.filter { it.isAd }
        val organicContentResults = allNodes.filter {
            !it.isAd && !it.isClearOrClose && !it.isAvatarOrPhoto &&
            !it.isEditable && (it.isClickable || it.bounds.height() > 40) &&
            it.bounds.top >= 150 && it.bounds.bottom <= 2150 &&
            (it.text.length >= 3 || it.contentDescription.length >= 3)
        }

        val summary = buildSummary(currentPkg, allNodes, isLoading)

        return FreshScreenState(
            root = root,
            foregroundPackage = currentPkg,
            nodes = allNodes,
            visibleTexts = visibleTexts,
            summaryString = summary,
            isLoading = isLoading,
            searchFields = searchFields,
            searchButtons = searchButtons,
            organicContentResults = organicContentResults,
            adNodes = adNodes
        )
    }

    /**
     * Adaptive UI stability mechanism.
     * Observes screen transitions and returns as soon as the UI reaches a stable state,
     * avoiding unnecessary large fixed delays while guaranteeing fresh data.
     */
    suspend fun waitForScreenStability(
        expectedPackage: String? = null,
        minStablePasses: Int = 2,
        checkIntervalMs: Long = 100L,
        maxWaitMs: Long = 2800L
    ): FreshScreenState {
        val startTime = System.currentTimeMillis()
        var lastSignature = ""
        var stableCount = 0
        var latestState = inspectFreshScreenNow(expectedPackage)

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            latestState = inspectFreshScreenNow(expectedPackage)

            // If an expected package was provided, verify it has loaded
            if (expectedPackage != null && !isMatchingPackage(latestState.foregroundPackage, expectedPackage)) {
                stableCount = 0
                delay(checkIntervalMs)
                continue
            }

            // Build signature of active hierarchy
            val currentSignature = "${latestState.foregroundPackage}|${latestState.nodes.size}|" +
                    latestState.nodes.take(8).joinToString { "${it.resourceId}:${it.text.take(15)}" }

            val hasLoadingIndicator = latestState.isLoading

            if (currentSignature == lastSignature && !hasLoadingIndicator && latestState.nodes.isNotEmpty()) {
                stableCount++
                if (stableCount >= minStablePasses) {
                    Log.d(TAG, "Screen stability confirmed in ${System.currentTimeMillis() - startTime}ms")
                    return latestState
                }
            } else {
                stableCount = 0
                lastSignature = currentSignature
            }

            delay(checkIntervalMs)
        }

        Log.d(TAG, "Screen stability wait completed (elapsed: ${System.currentTimeMillis() - startTime}ms)")
        return latestState
    }

    /**
     * Recursively traverses and classifies accessibility nodes.
     */
    private fun collectAndClassifyNodes(
        node: AccessibilityNodeInfo?,
        outputList: MutableList<AnalyzedNode>,
        textList: MutableList<String>,
        depth: Int,
        maxNodes: Int
    ) {
        if (node == null || outputList.size >= maxNodes || depth > 18) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val resId = node.viewIdResourceName?.substringAfterLast("/")?.trim() ?: ""
        val className = node.className?.toString()?.substringAfterLast(".") ?: ""

        val isAd = isAdOrSponsored(node, text, desc, resId)
        val isClearOrClose = isClearOrCloseNode(text, desc, resId)
        val isAvatarOrPhoto = isAvatarOrPhotoNode(desc, resId)

        if (text.isNotBlank() && !textList.contains(text)) textList.add(text)
        if (desc.isNotBlank() && !textList.contains(desc)) textList.add(desc)

        val hasMeaningfulInfo = text.isNotBlank() || desc.isNotBlank() || resId.isNotBlank() || node.isEditable || node.isClickable
        val isValidBounds = bounds.width() > 0 && bounds.height() > 0 && !(bounds.width() > 1000 && bounds.height() > 1900)

        if (hasMeaningfulInfo && isValidBounds) {
            val analyzed = AnalyzedNode(
                node = node,
                text = text,
                contentDescription = desc,
                resourceId = resId,
                className = className,
                bounds = bounds,
                isClickable = node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK },
                isEditable = node.isEditable,
                isScrollable = node.isScrollable,
                isAd = isAd,
                isClearOrClose = isClearOrClose,
                isAvatarOrPhoto = isAvatarOrPhoto
            )
            outputList.add(analyzed)
        }

        for (i in 0 until node.childCount) {
            if (outputList.size >= maxNodes) break
            collectAndClassifyNodes(node.getChild(i), outputList, textList, depth + 1, maxNodes)
        }
    }

    /**
     * Determines whether a node represents an advertisement or sponsored content.
     */
    fun isAdOrSponsored(
        node: AccessibilityNodeInfo,
        text: String,
        desc: String,
        resId: String
    ): Boolean {
        val lowerText = text.lowercase(Locale.ROOT)
        val lowerDesc = desc.lowercase(Locale.ROOT)
        val lowerRes = resId.lowercase(Locale.ROOT)

        // 1. Direct resource ID indicators
        val resAd = lowerRes.contains("ad_badge") || lowerRes.contains("ad_view") ||
                lowerRes.contains("promoted_anchor") || lowerRes.contains("sponsored") ||
                lowerRes.contains("ad_image") || lowerRes.contains("ad_headline") ||
                lowerRes.contains("commercial") || lowerRes.contains("promoted")
        if (resAd) return true

        // 2. Direct exact text/desc badges
        val exactBadge = lowerText == "ad" || lowerText == "ad ·" || lowerText == "sponsored" ||
                lowerText == "promoted" || lowerText == "विज्ञापन" || lowerText == "प्रायोजित" ||
                lowerDesc == "ad" || lowerDesc == "sponsored" || lowerDesc == "advertisement"
        if (exactBadge) return true

        // 3. Prefix matches
        for (adKw in AD_KEYWORDS) {
            if (lowerText.startsWith("$adKw ·") || lowerText.startsWith("$adKw •") ||
                lowerDesc.startsWith("$adKw ·") || lowerDesc.startsWith("$adKw •") ||
                lowerText.startsWith("sponsored") || lowerDesc.startsWith("sponsored")
            ) {
                return true
            }
        }

        // 4. Check immediate child nodes for Ad badge
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val cText = child.text?.toString()?.trim()?.lowercase(Locale.ROOT) ?: ""
            val cDesc = child.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT) ?: ""
            val cRes = child.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
            if (cText == "ad" || cText == "sponsored" || cText == "विज्ञापन" ||
                cDesc == "ad" || cDesc == "sponsored" || cRes.contains("ad_badge") || cRes.contains("promoted")
            ) {
                return true
            }
        }

        return false
    }

    /**
     * Determines whether a node is a close/clear/cancel button.
     */
    fun isClearOrCloseNode(text: String, desc: String, resId: String): Boolean {
        val lowerText = text.lowercase(Locale.ROOT)
        val lowerDesc = desc.lowercase(Locale.ROOT)
        val lowerRes = resId.lowercase(Locale.ROOT)

        if (lowerRes.contains("clear_button") || lowerRes.contains("btn_clear") ||
            lowerRes.contains("clear_query") || lowerRes.contains("delete_query") ||
            lowerRes.contains("close_button") || lowerRes.contains("cancel_button")
        ) {
            return true
        }

        for (kw in CLEAR_OR_CLOSE_KEYWORDS) {
            if (lowerText == kw || lowerDesc == kw || lowerText == "x" || lowerDesc == "x") {
                return true
            }
        }

        return false
    }

    /**
     * Determines whether a node is an avatar / profile picture.
     */
    fun isAvatarOrPhotoNode(desc: String, resId: String): Boolean {
        val lowerDesc = desc.lowercase(Locale.ROOT)
        val lowerRes = resId.lowercase(Locale.ROOT)

        return lowerRes.contains("avatar") || lowerRes.contains("profile_photo") ||
                lowerRes.contains("contact_photo") || lowerRes.contains("user_picture") ||
                lowerDesc.contains("profile photo") || lowerDesc.contains("view profile")
    }

    /**
     * Checks if the screen is currently in a loading state.
     */
    private fun detectLoadingState(nodes: List<AnalyzedNode>, texts: List<String>): Boolean {
        val hasProgressBar = nodes.any { it.className.contains("ProgressBar", ignoreCase = true) || it.resourceId.contains("loading", ignoreCase = true) }
        if (hasProgressBar) return true

        for (t in texts) {
            val lower = t.lowercase(Locale.ROOT)
            if (LOADING_KEYWORDS.any { lower.contains(it) }) return true
        }
        return false
    }

    /**
     * Helper to verify if the foreground package matches an expected package name or alias.
     */
    fun isMatchingPackage(currentPkg: String, expected: String): Boolean {
        val c = currentPkg.lowercase(Locale.ROOT)
        val e = expected.lowercase(Locale.ROOT)

        if (c == e || c.contains(e) || e.contains(c)) return true

        return when {
            e.contains("youtube") && c.contains("youtube") -> true
            e.contains("spotify") && c.contains("spotify") -> true
            e.contains("whatsapp") && c.contains("whatsapp") -> true
            (e.contains("chrome") || e.contains("browser")) && (c.contains("chrome") || c.contains("browser")) -> true
            e.contains("free fire") && (c.contains("freefire") || c.contains("dts")) -> true
            else -> false
        }
    }

    private fun buildSummary(pkg: String, nodes: List<AnalyzedNode>, loading: Boolean): String {
        val sb = StringBuilder("Active App: $pkg | Loading: $loading | Elements: ${nodes.size}\n")
        nodes.take(25).forEach { node ->
            val flags = mutableListOf<String>()
            if (node.isClickable) flags.add("clickable")
            if (node.isEditable) flags.add("editable")
            if (node.isAd) flags.add("AD")
            sb.append("• ${node.displayLabel} [${flags.joinToString(", ")}]\n")
        }
        return sb.toString()
    }
}
