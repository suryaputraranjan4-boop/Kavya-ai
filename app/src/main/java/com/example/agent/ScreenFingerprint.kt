package com.example.agent

import android.view.accessibility.AccessibilityNodeInfo
import com.example.services.KavyaAccessibilityService
import java.util.Locale

/**
 * Lightweight screen fingerprint capturing the essential structural and contextual identity
 * of the currently displayed Android UI window.
 *
 * Used before and after actions to verify whether a screen state transition actually occurred,
 * preventing blind repeated tapping on stuck screens.
 */
data class ScreenFingerprint(
    val packageName: String,
    val screenTitle: String,
    val visibleTextsCount: Int,
    val visibleTextsSample: List<String>,
    val textHash: Int,
    val focusedNodeResourceId: String?,
    val focusedNodeIsEditable: Boolean,
    val hasDialogOrPopup: Boolean,
    val hasKeyboard: Boolean,
    val isLoading: Boolean,
    val timestamp: Long = System.currentTimeMillis()
) {

    /**
     * Determines whether the screen state is meaningfully different from another fingerprint.
     * Prevents false-positive changes (like clock or battery percentage updates) while reliably
     * detecting actual UI navigation, dialog appearance, content change, or text injection.
     */
    fun isMeaningfullyDifferentFrom(other: ScreenFingerprint?): Boolean {
        if (other == null) return true
        if (this.packageName != other.packageName) return true
        if (this.hasDialogOrPopup != other.hasDialogOrPopup) return true
        if (this.hasKeyboard != other.hasKeyboard) return true
        if (this.isLoading != other.isLoading) return true

        // Title or screen header change
        if (this.screenTitle.isNotBlank() && other.screenTitle.isNotBlank() &&
            !this.screenTitle.equals(other.screenTitle, ignoreCase = true)
        ) {
            return true
        }

        // Substantial change in visible texts
        if (Math.abs(this.visibleTextsCount - other.visibleTextsCount) > 2) return true
        if (this.textHash != other.textHash) return true

        // Focus shift between editable and non-editable
        if (this.focusedNodeIsEditable != other.focusedNodeIsEditable) return true
        if (this.focusedNodeResourceId != other.focusedNodeResourceId && this.focusedNodeResourceId != null) return true

        return false
    }

    companion object {

        private val DIALOG_KEYWORDS = listOf(
            "alertdialog", "dialog", "popup", "bottomsheet",
            "cancel", "not now", "dismiss", "close", "later", "skip",
            "रद्द करें", "बाद में", "बंद करें"
        )

        private val LOADING_KEYWORDS = listOf(
            "loading", "buffering", "searching", "please wait",
            "load more", "connecting", "लोड हो रहा है", "प्रतीक्षा करें"
        )

        fun createFrom(screenState: FreshScreenState): ScreenFingerprint {
            val pkg = screenState.foregroundPackage
            val texts = screenState.visibleTexts
            val sample = texts.take(8)
            val hash = sample.joinToString("|").hashCode()

            var title = ""
            var focusedResId: String? = null
            var focusedIsEditable = false
            var dialogDetected = false
            var keyboardDetected = false
            var loadingDetected = screenState.isLoading

            for (node in screenState.nodes) {
                if (title.isBlank() && node.bounds.top < 250 && node.text.isNotBlank() && node.text.length in 3..40) {
                    title = node.text
                }
                if (node.node.isFocused) {
                    focusedResId = node.resourceId
                    focusedIsEditable = node.isEditable
                }
                val className = node.className.lowercase(Locale.ROOT)
                val textLower = node.text.lowercase(Locale.ROOT)
                val descLower = node.contentDescription.lowercase(Locale.ROOT)

                if (className.contains("dialog") || className.contains("popup") || className.contains("bottomsheet")) {
                    dialogDetected = true
                }
                if (DIALOG_KEYWORDS.any { textLower.contains(it) || descLower.contains(it) }) {
                    if (node.isClickable && (textLower == "cancel" || textLower == "not now" || textLower == "dismiss" || textLower == "close")) {
                        dialogDetected = true
                    }
                }
                if (LOADING_KEYWORDS.any { textLower.contains(it) || descLower.contains(it) }) {
                    loadingDetected = true
                }
                if (className.contains("inputmethod") || className.contains("keyboard") || node.resourceId.contains("keyboard")) {
                    keyboardDetected = true
                }
            }

            return ScreenFingerprint(
                packageName = pkg,
                screenTitle = title,
                visibleTextsCount = texts.size,
                visibleTextsSample = sample,
                textHash = hash,
                focusedNodeResourceId = focusedResId,
                focusedNodeIsEditable = focusedIsEditable,
                hasDialogOrPopup = dialogDetected,
                hasKeyboard = keyboardDetected,
                isLoading = loadingDetected
            )
        }

        fun captureCurrent(): ScreenFingerprint {
            val service = KavyaAccessibilityService.instance
            if (service == null) {
                return ScreenFingerprint(
                    packageName = "unknown",
                    screenTitle = "",
                    visibleTextsCount = 0,
                    visibleTextsSample = emptyList(),
                    textHash = 0,
                    focusedNodeResourceId = null,
                    focusedNodeIsEditable = false,
                    hasDialogOrPopup = false,
                    hasKeyboard = false,
                    isLoading = false
                )
            }

            val pkg = service.getForegroundPackage()
            val summaries = service.getVisibleElementSummaries()
            val sample = summaries.take(8)
            val hash = sample.joinToString("|").hashCode()

            val root = service.rootInActiveWindow
            var title = ""
            var focusedResId: String? = null
            var focusedIsEditable = false
            var dialogDetected = false
            var keyboardDetected = false
            var loadingDetected = false

            fun inspectNode(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 10) return
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                val resId = node.viewIdResourceName ?: ""
                val cls = node.className?.toString() ?: ""
                val lower = (text + " " + desc + " " + cls).lowercase(Locale.ROOT)

                if (title.isBlank() && text.isNotBlank() && text.length in 3..40) {
                    val b = android.graphics.Rect()
                    node.getBoundsInScreen(b)
                    if (b.top < 250) title = text
                }
                if (node.isFocused) {
                    focusedResId = resId
                    focusedIsEditable = node.isEditable
                }
                if (lower.contains("dialog") || lower.contains("bottomsheet") || lower.contains("popup")) {
                    dialogDetected = true
                }
                if (DIALOG_KEYWORDS.any { lower.contains(it) } && node.isClickable) {
                    dialogDetected = true
                }
                if (LOADING_KEYWORDS.any { lower.contains(it) }) {
                    loadingDetected = true
                }
                if (lower.contains("keyboard") || lower.contains("inputmethod")) {
                    keyboardDetected = true
                }

                for (i in 0 until node.childCount) {
                    inspectNode(node.getChild(i), depth + 1)
                }
            }

            inspectNode(root, 0)

            return ScreenFingerprint(
                packageName = pkg,
                screenTitle = title,
                visibleTextsCount = summaries.size,
                visibleTextsSample = sample,
                textHash = hash,
                focusedNodeResourceId = focusedResId,
                focusedNodeIsEditable = focusedIsEditable,
                hasDialogOrPopup = dialogDetected,
                hasKeyboard = keyboardDetected,
                isLoading = loadingDetected
            )
        }
    }
}
