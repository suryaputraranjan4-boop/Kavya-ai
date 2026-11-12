package com.example.agent

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * UI Element Resolver & Precise Interaction Engine (Section 5).
 *
 * Implements reliable UI element recognition, semantic matching, and location recovery
 * to eliminate random coordinate tapping.
 *
 * Covers:
 * - Search buttons & magnifying glass icons
 * - Search input fields (EditText with hint/ID/label)
 * - Text-entry boxes & composers
 * - Send buttons (Send, Submit, Post, paper plane icon, arrow)
 * - Download buttons (Download, Save, arrow down, save image)
 * - Menus & navigation icons (3-dots, overflow, drawer, back)
 * - Dialog confirmation buttons (Confirm, Yes, OK, Allow, Agree)
 * - Form input fields & labels
 */
object UIElementResolver {

    private const val TAG = "KavyaUIElementResolver"

    data class ResolvedUIElement(
        val node: AccessibilityNodeInfo?,
        val targetText: String,
        val bounds: Rect,
        val clickPoint: Pair<Int, Int>,
        val resolutionMethod: ResolutionMethod,
        val confidence: Float,
        val isInteractable: Boolean = true
    )

    enum class ResolutionMethod {
        ACCESSIBILITY_NODE_DIRECT,
        SEMANTIC_SEARCH_INPUT,
        SEMANTIC_SEARCH_BUTTON,
        SEMANTIC_SEND_BUTTON,
        SEMANTIC_DOWNLOAD_BUTTON,
        SEMANTIC_MENU_BUTTON,
        SEMANTIC_CONFIRMATION_BUTTON,
        SEMANTIC_FORM_FIELD,
        SEMANTIC_ATTACHMENT_BUTTON,
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
        ORDINAL,
        SEARCH_FIELD,
        SEARCH_BUTTON,
        SEND_BUTTON,
        DOWNLOAD_BUTTON,
        MENU_BUTTON,
        CONFIRMATION_BUTTON,
        FORM_FIELD,
        ATTACHMENT_BUTTON
    }

    // Common search keywords across English and Hindi
    private val SEARCH_TERMS = listOf("search", "find", "query", "look for", "खोजें", "खोज", "ढूंढें", "सर्च")
    // Send terms
    private val SEND_TERMS = listOf("send", "submit", "post", "भेजें", "send message", "send image")
    // Download terms
    private val DOWNLOAD_TERMS = listOf("download", "save", "save image", "save to device", "डाउनलोड", "सहेजें")
    // Confirmation terms
    private val CONFIRMATION_TERMS = listOf("confirm", "yes", "ok", "allow", "agree", "proceed", "accept", "continue", "done", "स्वीकार करें", "हाँ", "ठीक है", "जारी रखें")
    // Menu terms
    private val MENU_TERMS = listOf("more options", "menu", "overflow", "navigation", "drawer", "3 dots", "options")

    /**
     * Resolves the actual search input field in the active screen.
     * Strictly prevents selecting a Chat Composer, Comment Box, or Login Field when searching.
     */
    fun findSearchInput(screen: FreshScreenState): ResolvedUIElement? {
        val nodes = screen.nodes
        val pkg = screen.foregroundPackage

        // 1. Explicit search field already classified by FreshScreenRecognizer & InputFieldClassifier
        val candidate = screen.searchFields.firstOrNull { node ->
            com.example.visual.InputFieldClassifier.isTrueSearchField(node, nodes, pkg) && (
                SEARCH_TERMS.any { "${node.text} ${node.contentDescription} ${node.resourceId}".lowercase(Locale.ROOT).contains(it) } ||
                    try { node.node.isFocused } catch (_: Exception) { false }
            )
        } ?: screen.searchFields.firstOrNull {
            com.example.visual.InputFieldClassifier.isTrueSearchField(it, nodes, pkg)
        }

        if (candidate != null) {
            val b = candidate.bounds
            return ResolvedUIElement(
                node = candidate.node,
                targetText = candidate.displayLabel,
                bounds = b,
                clickPoint = Pair(b.centerX(), b.centerY()),
                resolutionMethod = ResolutionMethod.SEMANTIC_SEARCH_INPUT,
                confidence = 0.95f
            )
        }

        // 2. Check any editable or clickable search bar node whose role is SEARCH_FIELD or BROWSER_ADDRESS_BAR
        val editableSearch = nodes.firstOrNull { node ->
            !node.isClearOrClose && !node.isAd &&
                com.example.visual.InputFieldClassifier.isTrueSearchField(node, nodes, pkg)
        }

        if (editableSearch != null) {
            val b = editableSearch.bounds
            return ResolvedUIElement(
                node = editableSearch.node,
                targetText = editableSearch.displayLabel,
                bounds = b,
                clickPoint = Pair(b.centerX(), b.centerY()),
                resolutionMethod = ResolutionMethod.SEMANTIC_SEARCH_INPUT,
                confidence = 0.92f
            )
        }

        // 3. Only allow a focused top-region editable field if it is NOT a chat composer, comment box, or login field
        val focusedTopEditable = nodes.firstOrNull { node ->
            if (!node.isEditable || node.isClearOrClose || node.isAd) return@firstOrNull false
            val role = com.example.visual.InputFieldClassifier.classifyNode(node, nodes, pkg)
            val isNotWrongField = role !in setOf(
                com.example.visual.InputFieldRole.CHAT_COMPOSER,
                com.example.visual.InputFieldRole.COMMENT_BOX,
                com.example.visual.InputFieldRole.LOGIN_FIELD
            )
            val isFocused = try { node.node.isFocused } catch (_: Exception) { false }
            isNotWrongField && isFocused && node.bounds.centerY() <= 720
        }
        if (focusedTopEditable != null) {
            val b = focusedTopEditable.bounds
            return ResolvedUIElement(
                node = focusedTopEditable.node,
                targetText = focusedTopEditable.displayLabel,
                bounds = b,
                clickPoint = Pair(b.centerX(), b.centerY()),
                resolutionMethod = ResolutionMethod.SEMANTIC_SEARCH_INPUT,
                confidence = 0.82f
            )
        }

        return null
    }

    /**
     * Resolves a Chat Composer / Message Input box while strictly rejecting Search Bars and Address Bars.
     */
    fun findChatComposerInput(screen: FreshScreenState): ResolvedUIElement? {
        val nodes = screen.nodes
        val pkg = screen.foregroundPackage

        val composer = screen.chatComposerFields.firstOrNull()
            ?: nodes.firstOrNull { node ->
                !node.isClearOrClose && !node.isAd &&
                    com.example.visual.InputFieldClassifier.isTrueChatComposer(node, nodes, pkg)
            }
            ?: nodes.firstOrNull { node ->
                node.isEditable && !node.isClearOrClose && !node.isAd &&
                    com.example.visual.InputFieldClassifier.classifyNode(node, nodes, pkg) !in setOf(
                        com.example.visual.InputFieldRole.SEARCH_FIELD,
                        com.example.visual.InputFieldRole.BROWSER_ADDRESS_BAR
                    ) && node.bounds.centerY() > 900
            }

        if (composer != null) {
            val b = composer.bounds
            return ResolvedUIElement(
                node = composer.node,
                targetText = composer.displayLabel,
                bounds = b,
                clickPoint = Pair(b.centerX(), b.centerY()),
                resolutionMethod = ResolutionMethod.SEMANTIC_FORM_FIELD,
                confidence = 0.94f
            )
        }
        return null
    }

    /**
     * Resolves the search action button / magnifying glass icon.
     */
    fun findSearchButton(screen: FreshScreenState): ResolvedUIElement? {
        val nodes = screen.nodes

        // 1. Dedicated search button from fresh screen
        val directBtn = screen.searchButtons.firstOrNull()
        if (directBtn != null) {
            val b = directBtn.bounds
            return ResolvedUIElement(
                node = directBtn.node,
                targetText = directBtn.displayLabel,
                bounds = b,
                clickPoint = Pair(b.centerX(), b.centerY()),
                resolutionMethod = ResolutionMethod.SEMANTIC_SEARCH_BUTTON,
                confidence = 0.95f
            )
        }

        // 2. Look for clickable items with search description or resourceId
        val btnMatch = nodes.firstOrNull { node ->
            !node.isEditable && !node.isClearOrClose && !node.isAd && (node.isClickable || node.node.parent?.isClickable == true) &&
            SEARCH_TERMS.any { term ->
                node.contentDescription.lowercase(Locale.ROOT).contains(term) ||
                node.resourceId.lowercase(Locale.ROOT).contains(term) ||
                node.text.lowercase(Locale.ROOT).contains(term)
            } && !node.contentDescription.lowercase(Locale.ROOT).contains("voice")
        }

        if (btnMatch != null) {
            val b = btnMatch.bounds
            return ResolvedUIElement(
                node = btnMatch.node,
                targetText = btnMatch.displayLabel,
                bounds = b,
                clickPoint = Pair(b.centerX(), b.centerY()),
                resolutionMethod = ResolutionMethod.SEMANTIC_SEARCH_BUTTON,
                confidence = 0.90f
            )
        }

        return null
    }

    /**
     * Resolves Send / Submit button.
     */
    fun findSendButton(screen: FreshScreenState): ResolvedUIElement? {
        val nodes = screen.nodes

        val sendNode = nodes.firstOrNull { node ->
            !node.isEditable && !node.isAd && (node.isClickable || node.node.parent?.isClickable == true) &&
            SEND_TERMS.any { term ->
                val desc = node.contentDescription.lowercase(Locale.ROOT)
                val txt = node.text.lowercase(Locale.ROOT)
                val res = node.resourceId.lowercase(Locale.ROOT)
                desc.contains(term) || txt.contains(term) || res.contains("send") || res.contains("submit")
            }
        }

        if (sendNode != null) {
            val b = sendNode.bounds
            return ResolvedUIElement(
                node = sendNode.node,
                targetText = sendNode.displayLabel,
                bounds = b,
                clickPoint = Pair(b.centerX(), b.centerY()),
                resolutionMethod = ResolutionMethod.SEMANTIC_SEND_BUTTON,
                confidence = 0.95f
            )
        }

        return null
    }

    /**
     * Resolves Download / Save button (e.g. for generated images or attachments).
     */
    fun findDownloadButton(screen: FreshScreenState): ResolvedUIElement? {
        val nodes = screen.nodes

        val downloadNode = nodes.firstOrNull { node ->
            !node.isAd && (node.isClickable || node.node.parent?.isClickable == true) &&
            DOWNLOAD_TERMS.any { term ->
                val desc = node.contentDescription.lowercase(Locale.ROOT)
                val txt = node.text.lowercase(Locale.ROOT)
                val res = node.resourceId.lowercase(Locale.ROOT)
                desc.contains(term) || txt.contains(term) || res.contains("download") || res.contains("save")
            }
        }

        if (downloadNode != null) {
            val b = downloadNode.bounds
            return ResolvedUIElement(
                node = downloadNode.node,
                targetText = downloadNode.displayLabel,
                bounds = b,
                clickPoint = Pair(b.centerX(), b.centerY()),
                resolutionMethod = ResolutionMethod.SEMANTIC_DOWNLOAD_BUTTON,
                confidence = 0.95f
            )
        }

        return null
    }

    /**
     * Resolves dialog confirmation / positive action button.
     */
    fun findConfirmationButton(screen: FreshScreenState): ResolvedUIElement? {
        val nodes = screen.nodes

        val confirmNode = nodes.firstOrNull { node ->
            (node.isClickable || node.node.parent?.isClickable == true) && (
                CONFIRMATION_TERMS.any { term ->
                    node.text.lowercase(Locale.ROOT) == term ||
                    node.contentDescription.lowercase(Locale.ROOT) == term
                } ||
                node.resourceId.contains("button1") ||
                node.resourceId.contains("confirm")
            )
        }

        if (confirmNode != null) {
            val b = confirmNode.bounds
            return ResolvedUIElement(
                node = confirmNode.node,
                targetText = confirmNode.displayLabel,
                bounds = b,
                clickPoint = Pair(b.centerX(), b.centerY()),
                resolutionMethod = ResolutionMethod.SEMANTIC_CONFIRMATION_BUTTON,
                confidence = 0.95f
            )
        }

        return null
    }

    /**
     * Resolves menu / overflow (3 dots) navigation icon.
     */
    fun findMenuOrNavigation(screen: FreshScreenState): ResolvedUIElement? {
        val nodes = screen.nodes

        val menuNode = nodes.firstOrNull { node ->
            (node.isClickable || node.node.parent?.isClickable == true) &&
            MENU_TERMS.any { term ->
                node.contentDescription.lowercase(Locale.ROOT).contains(term) ||
                node.resourceId.lowercase(Locale.ROOT).contains("overflow") ||
                node.resourceId.lowercase(Locale.ROOT).contains("menu")
            }
        }

        if (menuNode != null) {
            val b = menuNode.bounds
            return ResolvedUIElement(
                node = menuNode.node,
                targetText = menuNode.displayLabel,
                bounds = b,
                clickPoint = Pair(b.centerX(), b.centerY()),
                resolutionMethod = ResolutionMethod.SEMANTIC_MENU_BUTTON,
                confidence = 0.90f
            )
        }

        return null
    }

    /**
     * Resolves attachment / paperclip button (e.g. for WhatsApp, Gmail, messaging).
     */
    fun findAttachmentButton(screen: FreshScreenState): ResolvedUIElement? {
        val nodes = screen.nodes

        val attachNode = nodes.firstOrNull { node ->
            (node.isClickable || node.node.parent?.isClickable == true) && (
                node.contentDescription.lowercase(Locale.ROOT).contains("attach") ||
                node.contentDescription.lowercase(Locale.ROOT).contains("अटैच") ||
                node.resourceId.lowercase(Locale.ROOT).contains("attach") ||
                node.resourceId.lowercase(Locale.ROOT).contains("clip") ||
                node.contentDescription.lowercase(Locale.ROOT).contains("paperclip") ||
                node.contentDescription.lowercase(Locale.ROOT).contains("add media")
            )
        }

        if (attachNode != null) {
            val b = attachNode.bounds
            return ResolvedUIElement(
                node = attachNode.node,
                targetText = attachNode.displayLabel,
                bounds = b,
                clickPoint = Pair(b.centerX(), b.centerY()),
                resolutionMethod = ResolutionMethod.SEMANTIC_ATTACHMENT_BUTTON,
                confidence = 0.95f
            )
        }

        return null
    }

    /**
     * Resolves a form field corresponding to a specific label (e.g. "Name", "Email", "Phone", "Date").
     */
    fun findFormField(screen: FreshScreenState, fieldName: String): ResolvedUIElement? {
        val q = fieldName.lowercase(Locale.ROOT).trim()
        val nodes = screen.nodes

        // 1. Direct editable field with hint or text matching label
        val direct = nodes.firstOrNull { node ->
            node.isEditable && (
                node.text.lowercase(Locale.ROOT).contains(q) ||
                node.contentDescription.lowercase(Locale.ROOT).contains(q) ||
                node.resourceId.lowercase(Locale.ROOT).contains(q)
            )
        }
        if (direct != null) {
            val b = direct.bounds
            return ResolvedUIElement(
                node = direct.node,
                targetText = direct.displayLabel,
                bounds = b,
                clickPoint = Pair(b.centerX(), b.centerY()),
                resolutionMethod = ResolutionMethod.SEMANTIC_FORM_FIELD,
                confidence = 0.95f
            )
        }

        // 2. Find label node, then find the closest editable node below or adjacent
        val labelNode = nodes.firstOrNull { node ->
            !node.isEditable && (
                node.text.lowercase(Locale.ROOT).contains(q) ||
                node.contentDescription.lowercase(Locale.ROOT).contains(q)
            )
        }

        if (labelNode != null) {
            val labelBounds = labelNode.bounds
            // Look for editable node immediately below the label
            val nearbyEditable = nodes
                .filter { it.isEditable && it.bounds.top >= labelBounds.top && it.bounds.top <= labelBounds.bottom + 250 }
                .minByOrNull { it.bounds.top - labelBounds.top }

            if (nearbyEditable != null) {
                val b = nearbyEditable.bounds
                return ResolvedUIElement(
                    node = nearbyEditable.node,
                    targetText = "${labelNode.text} -> ${nearbyEditable.displayLabel}",
                    bounds = b,
                    clickPoint = Pair(b.centerX(), b.centerY()),
                    resolutionMethod = ResolutionMethod.SEMANTIC_FORM_FIELD,
                    confidence = 0.90f
                )
            }
        }

        return null
    }

    /**
     * Universal Target Element Resolver supporting all selector categories.
     */
    fun resolveTargetElement(
        screen: FreshScreenState,
        targetQuery: String,
        selectorType: SelectorType = SelectorType.ANY
    ): ResolvedUIElement? {
        val q = targetQuery.lowercase(Locale.ROOT).trim()

        // 1. Specialized selector dispatch
        when (selectorType) {
            SelectorType.SEARCH_FIELD -> {
                val found = findSearchInput(screen)
                if (found != null) return found
            }
            SelectorType.SEARCH_BUTTON -> {
                val found = findSearchButton(screen)
                if (found != null) return found
            }
            SelectorType.SEND_BUTTON -> {
                val found = findSendButton(screen)
                if (found != null) return found
            }
            SelectorType.DOWNLOAD_BUTTON -> {
                val found = findDownloadButton(screen)
                if (found != null) return found
            }
            SelectorType.MENU_BUTTON -> {
                val found = findMenuOrNavigation(screen)
                if (found != null) return found
            }
            SelectorType.CONFIRMATION_BUTTON -> {
                val found = findConfirmationButton(screen)
                if (found != null) return found
            }
            SelectorType.ATTACHMENT_BUTTON -> {
                val found = findAttachmentButton(screen)
                if (found != null) return found
            }
            SelectorType.FORM_FIELD -> {
                val found = findFormField(screen, q)
                if (found != null) return found
            }
            else -> {}
        }

        if (q.isBlank()) return null
        val nodes = screen.nodes

        // 1b. Check spatial / contextual UI navigation references ("ऊपर वाला", "नीचे वाला", "search bar", "send button")
        val unified = screen.unifiedElements.ifEmpty {
            com.example.visual.MultiSignalFusionEngine.buildUnifiedElements(nodes, screen.foregroundPackage)
        }
        val spatialMatch = com.example.visual.ShortTermNavigationMemory.resolveSpatialOrContextualReference(q, unified)
        if (spatialMatch != null && spatialMatch.bounds.width() > 0 && spatialMatch.bounds.height() > 0) {
            return ResolvedUIElement(
                node = spatialMatch.analyzedNode?.node,
                targetText = spatialMatch.primaryLabel,
                bounds = spatialMatch.bounds,
                clickPoint = spatialMatch.centerPoint,
                resolutionMethod = ResolutionMethod.SEMANTIC_HIERARCHY,
                confidence = spatialMatch.confidence
            )
        }

        // 2. Exact Text Match
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

        // 3. Exact Content Description Match
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

        // 4. Substring Text Match
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

        // 5. Substring Content Description Match
        val descSubstring = nodes.firstOrNull { it.contentDescription.lowercase(Locale.ROOT).contains(q) && (it.isClickable || it.node.parent?.isClickable == true) }
        if (descSubstring != null) {
            val b = descSubstring.bounds
            return ResolvedUIElement(
                node = descSubstring.node,
                targetText = descSubstring.contentDescription,
                bounds = b,
                clickPoint = Pair(b.centerX(), b.centerY()),
                resolutionMethod = ResolutionMethod.CONTENT_DESCRIPTION_MATCH,
                confidence = 0.88f
            )
        }

        // 6. View ID Match
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

        // 7. Ordinal selection ("first result", "second result", "1st", "2nd")
        if (q.contains("first") || q.contains("pehla") || q.contains("1st") || q == "0") {
            val organic = screen.organicContentResults.firstOrNull() ?: nodes.firstOrNull { it.isClickable && it.bounds.top > 150 }
            if (organic != null) {
                val b = organic.bounds
                return ResolvedUIElement(
                    node = organic.node,
                    targetText = organic.text.ifBlank { "First Result" },
                    bounds = b,
                    clickPoint = Pair(b.centerX(), b.centerY()),
                    resolutionMethod = ResolutionMethod.SEMANTIC_HIERARCHY,
                    confidence = 0.85f
                )
            }
        } else if (q.contains("second") || q.contains("dusra") || q.contains("doosra") || q.contains("2nd") || q == "1") {
            val organic = screen.organicContentResults.getOrNull(1) ?: nodes.filter { it.isClickable && it.bounds.top > 150 }.getOrNull(1)
            if (organic != null) {
                val b = organic.bounds
                return ResolvedUIElement(
                    node = organic.node,
                    targetText = organic.text.ifBlank { "Second Result" },
                    bounds = b,
                    clickPoint = Pair(b.centerX(), b.centerY()),
                    resolutionMethod = ResolutionMethod.SEMANTIC_HIERARCHY,
                    confidence = 0.85f
                )
            }
        }

        Log.d(TAG, "Target element '$targetQuery' could not be resolved in active UI.")
        return null
    }

    /**
     * Missing UI Element Recovery (Section 5):
     * Analyzes why an element wasn't found and recommends a specific recovery action
     * instead of randomly clicking coordinates.
     */
    fun diagnoseMissingElement(
        screen: FreshScreenState,
        elementQuery: String
    ): ElementRecoveryRecommendation {
        // 1. Is the screen currently loading?
        if (screen.isLoading) {
            return ElementRecoveryRecommendation(
                action = RecoveryAction.WAIT_FOR_LOADING,
                reason = "Screen is still loading, wait for indicators to settle"
            )
        }

        // 2. Is it a search field that might be hidden behind a search icon or tab?
        val q = elementQuery.lowercase(Locale.ROOT)
        if (SEARCH_TERMS.any { q.contains(it) }) {
            val searchIcon = findSearchButton(screen)
            if (searchIcon != null) {
                return ElementRecoveryRecommendation(
                    action = RecoveryAction.TAP_SEARCH_ICON_TO_REVEAL,
                    targetElement = searchIcon,
                    reason = "Search field is collapsed behind search icon/button"
                )
            }
        }

        // 3. Is the action possibly hidden in the 3-dots overflow menu?
        val overflowMenu = findMenuOrNavigation(screen)
        if (overflowMenu != null) {
            return ElementRecoveryRecommendation(
                action = RecoveryAction.OPEN_OVERFLOW_MENU,
                targetElement = overflowMenu,
                reason = "Target action might be inside the application overflow menu"
            )
        }

        // 4. Might it require scrolling down to become visible?
        val hasScrollableList = screen.nodes.any { it.isScrollable }
        if (hasScrollableList) {
            return ElementRecoveryRecommendation(
                action = RecoveryAction.SCROLL_TO_REVEAL,
                reason = "Element is likely below the fold, scroll down to reveal"
            )
        }

        return ElementRecoveryRecommendation(
            action = RecoveryAction.REPLAN_OR_ASK_USER,
            reason = "Element '$elementQuery' could not be located in current state"
        )
    }

    enum class RecoveryAction {
        WAIT_FOR_LOADING,
        TAP_SEARCH_ICON_TO_REVEAL,
        OPEN_OVERFLOW_MENU,
        SCROLL_TO_REVEAL,
        REPLAN_OR_ASK_USER
    }

    data class ElementRecoveryRecommendation(
        val action: RecoveryAction,
        val targetElement: ResolvedUIElement? = null,
        val reason: String = ""
    )
}
