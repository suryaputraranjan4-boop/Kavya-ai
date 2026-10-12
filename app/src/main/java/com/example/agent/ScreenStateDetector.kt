package com.example.agent

import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * Screen State Detector (Requirements 9, 13, 14, 25).
 * Inspects Accessibility Tree and classifies the active screen layout and input fields.
 * Prevents typing messages into search bars or clicking wrong result entities.
 */
object ScreenStateDetector {

    enum class ScreenType {
        MESSAGE_CHAT_VIEW,
        MESSAGE_COMPOSER_READY,
        SEARCH_INTERFACE,
        SEARCH_FIELD_ACTIVE,
        SEARCH_RESULTS_VIEW,
        MEDIA_PLAYER_VIEW,
        POPUP_OR_DIALOG,
        UNKNOWN_SCREEN
    }

    data class ScreenInspection(
        val screenType: ScreenType,
        val isMessageComposerFocused: Boolean,
        val isSearchFieldFocused: Boolean,
        val focusedNodeHint: String?,
        val hasPopupOrDialog: Boolean,
        val isKeyboardLikelyOpen: Boolean,
        val primaryEntityTitle: String?
    )

    fun inspect(screen: FreshScreenState): ScreenInspection {
        var isComposerFocused = false
        var isSearchFocused = false
        var focusedHint: String? = null

        val focusedNode = screen.nodes.firstOrNull { it.node.isFocused }
        if (focusedNode != null) {
            val text = focusedNode.text.lowercase(Locale.ROOT)
            val desc = focusedNode.contentDescription.lowercase(Locale.ROOT)
            val viewId = focusedNode.resourceId.lowercase(Locale.ROOT)
            focusedHint = focusedNode.text.ifBlank { focusedNode.contentDescription }

            val isSearchIndicator = text.contains("search") || desc.contains("search") || viewId.contains("search") ||
                    text.contains("dhoonde") || text.contains("khoje") || text.contains("find")
            val isMessageIndicator = text.contains("message") || desc.contains("message") || viewId.contains("entry") ||
                    viewId.contains("compose") || viewId.contains("input") || viewId.contains("msg")

            if (isSearchIndicator && !isMessageIndicator) {
                isSearchFocused = true
            } else if (isMessageIndicator) {
                isComposerFocused = true
            } else if (focusedNode.isEditable) {
                // If in WhatsApp and editable but not search, it's typically the message composer
                if (screen.foregroundPackage.contains("whatsapp", ignoreCase = true)) {
                    isComposerFocused = true
                }
            }
        }

        val hasDialog = screen.nodes.any { it.className.contains("Dialog", ignoreCase = true) || it.className.contains("AlertDialog", ignoreCase = true) }
        val isKeyboardOpen = screen.nodes.any { it.className.contains("InputMethod", ignoreCase = true) || it.className.contains("KeyboardView", ignoreCase = true) }

        val screenType = when {
            hasDialog -> ScreenType.POPUP_OR_DIALOG
            isComposerFocused -> ScreenType.MESSAGE_COMPOSER_READY
            isSearchFocused -> ScreenType.SEARCH_FIELD_ACTIVE
            screen.visibleTexts.any { it.contains("message", ignoreCase = true) || it.contains("type a message", ignoreCase = true) } -> ScreenType.MESSAGE_CHAT_VIEW
            screen.visibleTexts.any { it.contains("search", ignoreCase = true) } -> ScreenType.SEARCH_INTERFACE
            else -> ScreenType.UNKNOWN_SCREEN
        }

        return ScreenInspection(
            screenType = screenType,
            isMessageComposerFocused = isComposerFocused,
            isSearchFieldFocused = isSearchFocused,
            focusedNodeHint = focusedHint,
            hasPopupOrDialog = hasDialog,
            isKeyboardLikelyOpen = isKeyboardOpen,
            primaryEntityTitle = screen.visibleTexts.firstOrNull() ?: ""
        )
    }

    /**
     * Requirement 13: Wrong Input Field Guard.
     * Prevents message text from ever being typed into a search input!
     */
    fun isWrongFieldForMessage(screen: FreshScreenState): Boolean {
        val inspection = inspect(screen)
        // If a search field is currently focused, it's strictly the wrong field for typing a message!
        return inspection.isSearchFieldFocused
    }
}
