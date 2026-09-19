package com.example.services

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class KavyaAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "KavyaAccessibility"
        var instance: KavyaAccessibilityService? = null
            private set
        @Volatile
        var currentForegroundPackage: String = "com.example"
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Kavya Accessibility Service Connected")
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (!pkg.isNullOrBlank()) {
                currentForegroundPackage = pkg
                Log.d(TAG, "Foreground window changed to: $pkg")
            }
        }
    }

    suspend fun captureScreenBitmap(): android.graphics.Bitmap? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                        try {
                            val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                            cont.resumeWith(Result.success(bitmap))
                        } catch(e: Exception) {
                            cont.resumeWith(Result.success(null))
                        }
                    }
                    override fun onFailure(errorCode: Int) {
                        cont.resumeWith(Result.success(null))
                    }
                }
            )
        } else {
            cont.resumeWith(Result.success(null))
        }
    }

    fun getForegroundPackage(): String {
        val root = rootInActiveWindow
        val rootPkg = root?.packageName?.toString()
        if (!rootPkg.isNullOrBlank()) {
            currentForegroundPackage = rootPkg
            return rootPkg
        }
        return currentForegroundPackage
    }

    fun isForegroundPackage(expectedPackage: String): Boolean {
        val current = getForegroundPackage()
        return current.equals(expectedPackage, ignoreCase = true) ||
               current.contains(expectedPackage, ignoreCase = true) ||
               expectedPackage.contains(current, ignoreCase = true)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Kavya Accessibility Service Interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    fun getScreenContext(): String {
        val root = rootInActiveWindow ?: return "Cannot read screen. Root node is null (Screen off or accessibility not bound)."
        val sb = StringBuilder()
        val pkg = root.packageName ?: currentForegroundPackage
        sb.append("Active Foreground App: $pkg\n")
        sb.append("Visible UI Elements:\n")
        traverseNode(root, sb, 0, 0, 40)
        return sb.toString()
    }

    /**
     * Extracts readable summary list of detected UI elements for automation debugging.
     */
    fun getVisibleElementSummaries(): List<String> {
        val root = rootInActiveWindow ?: return listOf("Accessibility root node is null")
        val summaries = mutableListOf<String>()
        collectElementSummaries(root, summaries, 0, 35)
        return summaries
    }

    private fun collectElementSummaries(node: AccessibilityNodeInfo?, list: MutableList<String>, depth: Int, max: Int) {
        if (node == null || list.size >= max) return
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val resId = node.viewIdResourceName?.substringAfterLast("/")
        val className = node.className?.toString()?.substringAfterLast(".")

        if (!text.isNullOrEmpty() || !desc.isNullOrEmpty() || !resId.isNullOrEmpty() || node.isEditable) {
            val label = listOfNotNull(text, desc).filter { it.isNotBlank() }.joinToString(" | ")
            val display = if (label.isNotBlank()) label else "[$className]"
            val tags = mutableListOf<String>()
            if (node.isClickable) tags.add("clickable")
            if (node.isEditable) tags.add("editable")
            if (node.isScrollable) tags.add("scrollable")
            if (resId != null) tags.add("id:$resId")
            
            val entry = "$display (${tags.joinToString(", ")})"
            if (!list.contains(entry)) {
                list.add(entry)
            }
        }

        for (i in 0 until node.childCount) {
            if (list.size >= max) break
            collectElementSummaries(node.getChild(i), list, depth + 1, max)
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int, count: Int, maxNodes: Int): Int {
        if (node == null || count >= maxNodes) return count
        var currentCount = count
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val resId = node.viewIdResourceName?.substringAfterLast("/")
        val className = node.className?.toString()?.substringAfterLast(".")

        if (!text.isNullOrEmpty() || !desc.isNullOrEmpty() || !resId.isNullOrEmpty() || node.isEditable) {
            val indent = "  ".repeat(depth)
            val flags = mutableListOf<String>()
            if (node.isClickable) flags.add("[Clickable]")
            if (node.isEditable) flags.add("[Editable Input]")
            if (node.isFocused) flags.add("[Focused]")
            if (node.isScrollable) flags.add("[Scrollable]")
            if (resId != null) flags.add("[ID:$resId]")
            if (className != null && (className.contains("Button", true) || className.contains("Edit", true) || className.contains("Search", true))) {
                flags.add("[$className]")
            }

            val label = listOfNotNull(text, desc).filter { it.isNotBlank() }.joinToString(" | ")
            val displayLabel = if (label.isNotBlank()) label else "Element ($className)"
            sb.append("$indent- $displayLabel ${flags.joinToString(" ")}\n")
            currentCount++
        }

        for (i in 0 until node.childCount) {
            if (currentCount >= maxNodes) break
            currentCount = traverseNode(node.getChild(i), sb, depth + 1, currentCount, maxNodes)
        }
        return currentCount
    }

    fun findNodeRecursively(node: AccessibilityNodeInfo?, targetText: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val lowerTarget = targetText.lowercase(Locale.ROOT).trim()

        val text = node.text?.toString()?.trim()?.lowercase(Locale.ROOT)
        val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT)
        val resId = node.viewIdResourceName?.substringAfterLast("/")?.lowercase(Locale.ROOT)

        if (text?.contains(lowerTarget) == true ||
            desc?.contains(lowerTarget) == true ||
            resId?.contains(lowerTarget) == true) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = findNodeRecursively(node.getChild(i), targetText)
            if (child != null) return child
        }
        return null
    }

    /**
     * Finds the primary search bar or input field dynamically on screen.
     */
    fun findSearchField(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val rootNode = root ?: rootInActiveWindow ?: return null

        // 1. Check currently focused input
        val focused = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused

        // 2. Scan for editable fields or nodes with search keywords
        return findFirstMatchingSearchNode(rootNode)
    }

    private fun findFirstMatchingSearchNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val resId = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
        val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
        val className = node.className?.toString()?.lowercase(Locale.ROOT) ?: ""

        val isSearchHint = text.contains("search") || text.contains("khoje") || text.contains("type url") || text.contains("find")
        val isSearchRes = resId.contains("search") || resId.contains("query") || resId.contains("input") || resId.contains("url_bar")
        val isSearchDesc = desc.contains("search") || desc.contains("find") || desc.contains("magnify")

        if (node.isEditable && (isSearchRes || isSearchDesc || isSearchHint || resId.isNotBlank())) {
            return node
        }

        if (node.isClickable && (isSearchRes || isSearchDesc || isSearchHint) && !className.contains("button")) {
            return node
        }

        if (node.isEditable) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = findFirstMatchingSearchNode(node.getChild(i))
            if (child != null) return child
        }
        return null
    }

    /**
     * Identifies content results (e.g. search result links, video cards, news cards)
     * and selects by ordinal index (0 = 1st, 1 = 2nd, 2 = 3rd, -1 = last).
     */
    fun findOrdinalContentNode(root: AccessibilityNodeInfo?, ordinal: Int): AccessibilityNodeInfo? {
        val rootNode = root ?: rootInActiveWindow ?: return null
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectContentCandidates(rootNode, candidates, 0)

        if (candidates.isEmpty()) return null

        return when {
            ordinal == -1 -> candidates.lastOrNull()
            ordinal in 0 until candidates.size -> candidates[ordinal]
            else -> candidates.firstOrNull()
        }
    }

    private fun collectContentCandidates(node: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (node == null || list.size >= 15) return

        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val resId = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
        val combined = "$text $desc".lowercase(Locale.ROOT)

        // Filter out header, search bar, back button, nav tabs
        val isHeaderOrNav = resId.contains("toolbar") || resId.contains("search") || resId.contains("nav") ||
                resId.contains("tab") || resId.contains("header") || combined.contains("search") ||
                combined.contains("menu") || combined.contains("filter") || combined.contains("navigate up")

        if (node.isClickable && !isHeaderOrNav && (text.length > 3 || desc.length > 5)) {
            list.add(node)
        }

        for (i in 0 until node.childCount) {
            collectContentCandidates(node.getChild(i), list, depth + 1)
        }
    }

    fun clickByCoordinates(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = android.graphics.Path()
            path.moveTo(x, y)
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            return dispatchGesture(gesture, null, null)
        }
        return false
    }

    fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun longClickNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isLongClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            }
            current = current.parent
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    }

    fun typeInNode(node: AccessibilityNodeInfo?, textToType: String): Boolean {
        if (node == null) return false
        var current: AccessibilityNodeInfo? = node
        var typed = false
        while (current != null) {
            if (current.isEditable) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
                }
                val setSuccess = current.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (setSuccess) {
                    typed = true
                    current.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    // Attempt IME enter if supported
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        current.performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    } else {
                        // Fallback: try pressing ENTER via keyevent if it somehow works
                        try {
                            Runtime.getRuntime().exec("input keyevent 66")
                        } catch (e: Exception) {}
                    }
                }
                return setSuccess
            }
            if (current.isClickable) {
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return false
    }

    fun clearNodeText(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isEditable) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                }
                return current.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            current = current.parent
        }
        return false
    }

    fun clickNodeByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val targetNode = findNodeRecursively(root, text) ?: return false
        return clickNode(targetNode)
    }

    fun typeInNodeByText(textToFind: String, textToType: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val targetNode = if (textToFind.isBlank() || textToFind.equals("search", true)) {
            findSearchField(root) ?: findNodeRecursively(root, "search")
        } else {
            findNodeRecursively(root, textToFind) ?: findSearchField(root)
        }
        return typeInNode(targetNode, textToType)
    }

    fun typeInFocusedNode(textToType: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = findFocusedEditableNode(root)
        return typeInNode(focused, textToType)
    }

    private fun findFocusedEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = findFocusedEditableNode(node.getChild(i))
            if (child != null) return child
        }
        return null
    }

    fun clickSearchOrSubmitButton(): Boolean {
        val root = rootInActiveWindow ?: return false
        val searchNode = findSubmitButton(root, "Search")
            ?: findSubmitButton(root, "Go")
            ?: findSubmitButton(root, "Submit")
            ?: findSubmitButton(root, "Send")
            ?: findSubmitButton(root, "खोजें")
        return clickNode(searchNode)
    }

    /**
     * Performs an in-app search by finding the search bar or search button,
     * typing the query, and submitting.
     */
    suspend fun performAppSearch(query: String): Boolean {
        var root = rootInActiveWindow
        var targetField = findSearchField(root)

        // 1. If no editable field is immediately visible, look for a search icon or button to tap
        if (targetField == null || !targetField.isEditable) {
            val searchBtn = findSearchButtonOrIcon(root)
            if (searchBtn != null) {
                clickNode(searchBtn)
                kotlinx.coroutines.delay(350)
                root = rootInActiveWindow
                targetField = findSearchField(root)
            }
        }

        // 2. Type into the detected search field
        if (targetField != null) {
            val typed = typeInNode(targetField, query)
            if (typed) {
                kotlinx.coroutines.delay(200)
                clickSearchOrSubmitButton()
                return true
            }
        }

        // 3. Try typing in currently focused input
        if (typeInFocusedNode(query)) {
            kotlinx.coroutines.delay(200)
            clickSearchOrSubmitButton()
            return true
        }

        // 4. Try generic search text resolution
        if (typeInNodeByText("search", query)) {
            kotlinx.coroutines.delay(200)
            clickSearchOrSubmitButton()
            return true
        }

        return false
    }

    private fun findSearchButtonOrIcon(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
        val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
        val resId = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""

        val isSearchHint = desc.contains("search") || desc.contains("खोजें") || desc.contains("find") ||
                text.contains("search") || text.contains("खोजें") || text.contains("find") ||
                resId.contains("search") || resId.contains("action_search") || resId.contains("menu_search") || resId.contains("btn_search")

        if (node.isClickable && isSearchHint && !node.isEditable) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = findSearchButtonOrIcon(node.getChild(i))
            if (child != null) return child
        }
        return null
    }

    private fun findSubmitButton(node: AccessibilityNodeInfo?, targetText: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val lowerTarget = targetText.lowercase(Locale.ROOT).trim()

        val text = node.text?.toString()?.trim()?.lowercase(Locale.ROOT)
        val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT)
        val resId = node.viewIdResourceName?.substringAfterLast("/")?.lowercase(Locale.ROOT)
        val className = node.className?.toString()?.lowercase(Locale.ROOT) ?: ""

        val matchesTarget = text?.contains(lowerTarget) == true || desc?.contains(lowerTarget) == true || resId?.contains(lowerTarget) == true
        
        // We want a button or imageview, not the editable text box
        if (matchesTarget && !node.isEditable) {
            // Further heuristic: usually clickable
            if (node.isClickable || className.contains("button") || className.contains("imageview")) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val child = findSubmitButton(node.getChild(i), targetText)
            if (child != null) return child
        }
        return null
    }

    fun scroll(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollableNode = findScrollableNode(root) ?: return false

        return if (direction.equals("FORWARD", true) || direction.equals("DOWN", true) || direction.equals("BOTTOM", true)) {
            scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        } else {
            scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        }
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = findScrollableNode(node.getChild(i))
            if (child != null) return child
        }
        return null
    }

    fun takeScreenshot(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        } else {
            false
        }
    }

    fun performGlobal(action: Int): Boolean {
        return performGlobalAction(action)
    }
}

