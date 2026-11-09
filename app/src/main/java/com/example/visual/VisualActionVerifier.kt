package com.example.visual

import android.util.Log
import com.example.services.KavyaAccessibilityService
import kotlinx.coroutines.delay

/**
 * Action Verifier for the Kavya Visual Action Engine.
 * Responsibilities:
 * 1. Post-action verification.
 * 2. Confirms that UI state actually changed before marking any action as completed.
 * 3. Never reports "Done" unless the action is verified.
 */
class VisualActionVerifier(
    private val hierarchyAnalyzer: UIHierarchyAnalyzer = UIHierarchyAnalyzer()
) {

    companion object {
        private const val TAG = "KavyaVisualVerifier"
    }

    /**
     * Verifies that the executed action led to an observable UI change or expected result.
     */
    suspend fun verifyAction(
        action: VisualAction,
        screenSummaryBefore: String,
        settleDelayMs: Long = 350L
    ): Pair<Boolean, String> {
        delay(settleDelayMs)

        val service = KavyaAccessibilityService.instance
            ?: return Pair(false, "Accessibility service disconnected during verification")

        val root = service.rootInActiveWindow
        val currentNodes = hierarchyAnalyzer.collectVisibleNodes(root)
        val screenSummaryAfter = hierarchyAnalyzer.buildHierarchySummary(currentNodes)
        val currentPkg = service.getForegroundPackage()

        val screenTransitioned = screenSummaryBefore != screenSummaryAfter

        return when (action.type) {
            VisualActionType.TAP, VisualActionType.SELECT -> {
                if (screenTransitioned) {
                    Pair(true, "Verified: UI screen transitioned after tap on '${action.target}'")
                } else {
                    // Check if node is still present or if its state changed (e.g. checked, selected)
                    val (nodeAfter, _) = hierarchyAnalyzer.findMatchingNode(action.target, root)
                    if (nodeAfter == null) {
                        Pair(true, "Verified: Target '${action.target}' is no longer displayed (navigated forward)")
                    } else {
                        Pair(false, "Unverified: Tap dispatched on '${action.target}' but no UI transition or state change detected")
                    }
                }
            }
            VisualActionType.TYPE -> {
                if (screenSummaryAfter.contains(action.textToType, ignoreCase = true)) {
                    Pair(true, "Verified: Text '${action.textToType}' is present in active input field")
                } else if (screenTransitioned) {
                    Pair(true, "Verified: Screen transitioned after entering text")
                } else {
                    Pair(false, "Unverified: Typed text '${action.textToType}' could not be confirmed in target field")
                }
            }
            VisualActionType.SWIPE, VisualActionType.SCROLL -> {
                if (screenTransitioned) {
                    Pair(true, "Verified: Screen content scrolled / shifted")
                } else {
                    Pair(false, "Unverified: Scroll gesture executed but viewport content did not change")
                }
            }
            VisualActionType.OPEN -> {
                if (currentPkg.contains(action.target, ignoreCase = true) || screenTransitioned) {
                    Pair(true, "Verified: Foreground application or visible screen changed to '$currentPkg'")
                } else {
                    Pair(false, "Verification failed: foreground app/screen did not change from '$currentPkg'")
                }
            }
            VisualActionType.BACK, VisualActionType.HOME -> {
                if (screenTransitioned) {
                    Pair(true, "Verified: System navigation changed the visible screen")
                } else {
                    Pair(false, "Unverified: System navigation was dispatched but the visible screen did not change")
                }
            }
            VisualActionType.WAIT -> {
                Pair(true, "Verified: Wait duration elapsed")
            }
            VisualActionType.LONG_PRESS, VisualActionType.CLOSE -> {
                if (screenTransitioned) {
                    Pair(true, "Verified: The visible UI changed after the action")
                } else {
                    Pair(false, "Unverified: No visible UI change followed the action")
                }
            }
            VisualActionType.STOP -> {
                Pair(true, "Verified: Automation stop command was applied")
            }
        }
    }
}
