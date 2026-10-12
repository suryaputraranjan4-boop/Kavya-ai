package com.example.visual

import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.services.KavyaAccessibilityService
import kotlinx.coroutines.delay

/**
 * Text Input Controller for the Kavya Visual Action Engine.
 * Handles typing, focusing, clearing, and submitting text in editable fields.
 */
class TextInputController(
    private val hierarchyAnalyzer: UIHierarchyAnalyzer = UIHierarchyAnalyzer()
) {

    companion object {
        private const val TAG = "KavyaTextInputController"
        private const val FOCUS_SETTLE_DELAY_MS = 150L
    }

    /**
     * Injects text into a target or currently focused input field.
     */
    suspend fun performType(
        target: String,
        textToType: String,
        submitAfter: Boolean = true
    ): Pair<Boolean, String> {
        val service = KavyaAccessibilityService.instance
            ?: return Pair(false, "Accessibility service not connected")

        val root = service.rootInActiveWindow

        // 1. Try finding target node by label
        var editableNode: AccessibilityNodeInfo? = null
        if (target.isNotBlank()) {
            val (_, rawNode) = hierarchyAnalyzer.findMatchingNode(target, root)
            if (rawNode != null && (rawNode.isEditable || rawNode.isClickable)) {
                editableNode = rawNode
            }
        }

        // 2. Try primary search field if not yet found
        if (editableNode == null) {
            editableNode = service.findSearchField(root)
        }

        // 3. Perform type
        if (editableNode != null) {
            editableNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            editableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(FOCUS_SETTLE_DELAY_MS)

            val success = service.typeInNode(editableNode, textToType)
            if (success) {
                if (submitAfter) {
                    delay(200)
                    service.clickSearchOrSubmitButton()
                }
                return Pair(true, "Entered '$textToType' into input field")
            }
        }

        // 4. Try focused input fallback
        if (service.typeInFocusedNode(textToType)) {
            if (submitAfter) {
                delay(200)
                service.clickSearchOrSubmitButton()
            }
            return Pair(true, "Entered '$textToType' into focused field")
        }

        // 5. Try high-level performAppSearch
        val searchHandled = service.performAppSearch(textToType)
        if (searchHandled) {
            return Pair(true, "Searched for '$textToType' via app search engine")
        }

        return Pair(false, "Could not find an editable input field to type '$textToType'")
    }
}
