package com.example.agent

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * Structured semantic representation of an on-screen UI element.
 */
data class UiElement(
    val text: String = "",
    val contentDescription: String = "",
    val resourceId: String = "",
    val className: String = "",
    val bounds: Rect = Rect(),
    val isClickable: Boolean = false,
    val isEnabled: Boolean = true,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val isFocused: Boolean = false,
    val isSelected: Boolean = false,
    val nodeInfo: AccessibilityNodeInfo? = null
) {
    fun isValidTarget(): Boolean {
        return isEnabled && bounds.width() > 0 && bounds.height() > 0 &&
                bounds.width() < 2400 && bounds.height() < 3000
    }

    val primaryLabel: String
        get() = when {
            text.isNotBlank() -> text
            contentDescription.isNotBlank() -> contentDescription
            resourceId.isNotBlank() -> resourceId.substringAfterLast('/')
            else -> className.substringAfterLast('.')
        }
}

/**
 * Structured snapshot of the active screen for deterministic analysis.
 */
data class ScreenSnapshot(
    val foregroundPackage: String,
    val allElements: List<UiElement>,
    val visibleTexts: List<String>,
    val editableFields: List<UiElement>,
    val buttons: List<UiElement>,
    val scrollableContainers: List<UiElement>,
    val sendButtons: List<UiElement>,
    val searchButtons: List<UiElement>,
    val installButtons: List<UiElement>,
    val listItems: List<UiElement>,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Reusable Screen Understanding Layer.
 * Extracts a complete, structured semantic model from the Accessibility Node hierarchy.
 * Ensures zero random guessing or blind taps.
 */
object ScreenUnderstanding {

    private const val TAG = "ScreenUnderstanding"

    /**
     * Inspects the accessibility node tree and builds a structured ScreenSnapshot.
     */
    fun capture(root: AccessibilityNodeInfo?, currentPackage: String): ScreenSnapshot {
        if (root == null) {
            return ScreenSnapshot(
                foregroundPackage = currentPackage,
                allElements = emptyList(),
                visibleTexts = emptyList(),
                editableFields = emptyList(),
                buttons = emptyList(),
                scrollableContainers = emptyList(),
                sendButtons = emptyList(),
                searchButtons = emptyList(),
                installButtons = emptyList(),
                listItems = emptyList()
            )
        }

        val allElements = mutableListOf<UiElement>()
        val visibleTexts = mutableListOf<String>()
        val editableFields = mutableListOf<UiElement>()
        val buttons = mutableListOf<UiElement>()
        val scrollableContainers = mutableListOf<UiElement>()
        val sendButtons = mutableListOf<UiElement>()
        val searchButtons = mutableListOf<UiElement>()
        val installButtons = mutableListOf<UiElement>()
        val listItems = mutableListOf<UiElement>()

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty() && allElements.size < 300) {
            val node = queue.removeFirst()
            try {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                val text = node.text?.toString()?.trim() ?: ""
                val desc = node.contentDescription?.toString()?.trim() ?: ""
                val resId = node.viewIdResourceName?.toString()?.trim() ?: ""
                val cls = node.className?.toString()?.trim() ?: ""

                val element = UiElement(
                    text = text,
                    contentDescription = desc,
                    resourceId = resId,
                    className = cls,
                    bounds = bounds,
                    isClickable = node.isClickable,
                    isEnabled = node.isEnabled,
                    isEditable = node.isEditable,
                    isScrollable = node.isScrollable,
                    isFocused = node.isFocused,
                    isSelected = node.isSelected,
                    nodeInfo = node
                )

                allElements.add(element)

                if (text.isNotBlank()) visibleTexts.add(text)
                if (desc.isNotBlank() && desc != text) visibleTexts.add(desc)

                if (node.isEditable) {
                    editableFields.add(element)
                }

                if (node.isScrollable) {
                    scrollableContainers.add(element)
                }

                // Identify buttons
                val isButtonClass = cls.contains("Button", ignoreCase = true) || cls.contains("ImageButton", ignoreCase = true)
                if (node.isClickable && (isButtonClass || text.isNotBlank() || desc.isNotBlank())) {
                    buttons.add(element)
                }

                // Identify Send buttons (WhatsApp, Telegram, SMS, Chat)
                if (isSendCandidate(element)) {
                    sendButtons.add(element)
                }

                // Identify Search buttons / controls
                if (isSearchCandidate(element)) {
                    searchButtons.add(element)
                }

                // Identify Install / Download buttons (Play Store, etc.)
                if (isInstallCandidate(element)) {
                    installButtons.add(element)
                }

                // Identify List / Result items
                if (node.isClickable && (text.isNotBlank() || desc.isNotBlank()) && !isButtonClass && !node.isEditable) {
                    listItems.add(element)
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error inspecting node: ${e.message}")
            }
        }

        return ScreenSnapshot(
            foregroundPackage = currentPackage,
            allElements = allElements,
            visibleTexts = visibleTexts,
            editableFields = editableFields,
            buttons = buttons,
            scrollableContainers = scrollableContainers,
            sendButtons = sendButtons,
            searchButtons = searchButtons,
            installButtons = installButtons,
            listItems = listItems
        )
    }

    private fun isSendCandidate(elem: UiElement): Boolean {
        if (!elem.isValidTarget()) return false
        val resLower = elem.resourceId.lowercase(Locale.ROOT)
        val descLower = elem.contentDescription.lowercase(Locale.ROOT)
        val textLower = elem.text.lowercase(Locale.ROOT)

        val matchesRes = resLower.contains("send") || resLower.contains("entry_send") || resLower.contains("compose_send")
        val matchesDesc = descLower == "send" || descLower == "भेजें" || descLower.contains("send message") || descLower == "send voice message"
        val matchesText = textLower == "send" || textLower == "भेजें"

        return matchesRes || matchesDesc || matchesText
    }

    private fun isSearchCandidate(elem: UiElement): Boolean {
        if (!elem.isValidTarget()) return false
        val resLower = elem.resourceId.lowercase(Locale.ROOT)
        val descLower = elem.contentDescription.lowercase(Locale.ROOT)
        val textLower = elem.text.lowercase(Locale.ROOT)

        val matchesRes = resLower.contains("search") || resLower.contains("btn_search") || resLower.contains("menu_item_search")
        val matchesDesc = descLower.contains("search") || descLower.contains("सर्च") || descLower.contains("खोज")
        val matchesText = textLower.contains("search") || textLower.contains("सर्च") || textLower.contains("खोज")

        return matchesRes || matchesDesc || matchesText
    }

    private fun isInstallCandidate(elem: UiElement): Boolean {
        if (!elem.isValidTarget()) return false
        val textLower = elem.text.lowercase(Locale.ROOT)
        val descLower = elem.contentDescription.lowercase(Locale.ROOT)
        val resLower = elem.resourceId.lowercase(Locale.ROOT)

        val keywords = listOf("install", "update", "get", "इंस्टॉल", "इंस्टॉल करें", "अपडेट")
        return keywords.any { textLower == it || descLower == it } || resLower.contains("install_button")
    }

    /**
     * Finds the best match for a given target string (contact name, button label, query).
     */
    fun findElementMatching(snapshot: ScreenSnapshot, query: String): UiElement? {
        val cleanQuery = query.trim().lowercase(Locale.ROOT)
        if (cleanQuery.isEmpty()) return null

        // 1. Exact text match on clickable items
        val exact = snapshot.allElements.find {
            it.isClickable && (it.text.equals(cleanQuery, ignoreCase = true) || it.contentDescription.equals(cleanQuery, ignoreCase = true))
        }
        if (exact != null) return exact

        // 2. Case-insensitive contains on clickable items
        val containsClickable = snapshot.allElements.find {
            it.isClickable && (it.text.lowercase(Locale.ROOT).contains(cleanQuery) || it.contentDescription.lowercase(Locale.ROOT).contains(cleanQuery))
        }
        if (containsClickable != null) return containsClickable

        // 3. Any element matching query
        return snapshot.allElements.find {
            it.text.lowercase(Locale.ROOT).contains(cleanQuery) || it.contentDescription.lowercase(Locale.ROOT).contains(cleanQuery)
        }
    }
}
