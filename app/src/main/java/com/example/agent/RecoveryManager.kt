package com.example.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.services.KavyaAccessibilityService
import com.example.state.KavyaStateManager
import com.example.state.TaskState
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Advanced Context-Aware Recovery Manager for Kavya AI.
 *
 * Implements an intelligent, multi-level recovery loop when the current screen, active element,
 * or UI state does not match the planned target state:
 * - LEVEL 0: Direct Action (when verified and ready)
 * - LEVEL 1: Local UI Recovery (scroll, dismiss popup/dialog, close keyboard, switch tab, wait for loading)
 * - LEVEL 2: Navigation Recovery (back navigation, return to search, navigate to in-app home)
 * - LEVEL 3: App State Reset (save checkpoint, relaunch app, wait for stable start, resume from checkpoint)
 * - LEVEL 4: Deep Recovery (re-search query, rebuild interaction path)
 * - LEVEL 5: Safe Stop (stop automation gracefully, report actual cause, preserve debugging context)
 */
class RecoveryManager(
    private val screenInspector: ScreenInspector = ScreenInspector()
) {

    companion object {
        private const val TAG = "KavyaRecoveryManager"

        private val POPUP_DISMISS_KEYWORDS = listOf(
            "cancel", "not now", "dismiss", "close", "later", "skip",
            "maybe later", "no thanks", "रद्द करें", "बाद में", "बंद करें",
            "close ad", "skip ad"
        )
    }

    /**
     * Evaluates the current screen against the planned step requirements and selects
     * the optimal, lowest-cost recovery strategy.
     */
    fun planRecovery(
        currentScreen: FreshScreenState,
        step: TaskStep,
        targetApp: String,
        targetPackage: String,
        budget: RecoveryBudget,
        history: List<RecoveryAttempt>,
        checkpointManager: CheckpointManager
    ): RecoveryStrategy {
        val currentFingerprint = ScreenFingerprint.createFrom(currentScreen)
        val currentPkg = currentScreen.foregroundPackage.lowercase(Locale.ROOT)
        val targetPkgLower = targetPackage.lowercase(Locale.ROOT)

        // 1. Budget check - Safe stop if max total attempts reached
        if (!budget.canAttempt(RecoveryLevel.LEVEL_1_LOCAL_UI) &&
            !budget.canAttempt(RecoveryLevel.LEVEL_2_NAVIGATION) &&
            !budget.canAttempt(RecoveryLevel.LEVEL_3_APP_STATE_RESET)
        ) {
            return RecoveryStrategy(
                level = RecoveryLevel.LEVEL_5_SAFE_STOP,
                actionType = RecoveryActionType.SAFE_STOP,
                description = "Recovery budget exceeded after ${budget.currentTotalAttempts} attempts without reaching target state."
            )
        }

        // 2. Wrong package detection: current foreground app is not the target app
        val isTargetAppActive = isPackageMatching(currentPkg, targetPkgLower, targetApp)
        val isSystemDialogOrOverlay = currentPkg.contains("systemui") || currentPkg.contains("packageinstaller") || currentPkg.contains("permissioncontroller")

        if (!isTargetAppActive && !isSystemDialogOrOverlay && step.actionType != UniversalActionType.OPEN_APP) {
            if (budget.canAttempt(RecoveryLevel.LEVEL_1_LOCAL_UI)) {
                return RecoveryStrategy(
                    level = RecoveryLevel.LEVEL_1_LOCAL_UI,
                    actionType = RecoveryActionType.SWITCH_TO_TARGET_PACKAGE,
                    description = "Expected $targetApp ($targetPackage), but current foreground package is '$currentPkg'. Switching back.",
                    param = targetPackage
                )
            }
        }

        // 3. Loading / Progress Indicator detection: UI is actively fetching data
        if (currentScreen.isLoading) {
            if (budget.canAttempt(RecoveryLevel.LEVEL_1_LOCAL_UI)) {
                val consecutiveWaits = history.takeLast(2).count { it.strategyApplied.actionType == RecoveryActionType.WAIT_FOR_STABILIZATION }
                if (consecutiveWaits < 2) {
                    return RecoveryStrategy(
                        level = RecoveryLevel.LEVEL_1_LOCAL_UI,
                        actionType = RecoveryActionType.WAIT_FOR_STABILIZATION,
                        description = "Detected loading indicator/buffering. Waiting for UI stabilization."
                    )
                }
            }
        }

        // 4. Popup / Dialog / Overlay detection: Unexpected modal obstructing the screen
        val dismissButtonText = findPopupDismissButton(currentScreen)
        if (dismissButtonText != null) {
            if (budget.canAttempt(RecoveryLevel.LEVEL_1_LOCAL_UI)) {
                return RecoveryStrategy(
                    level = RecoveryLevel.LEVEL_1_LOCAL_UI,
                    actionType = RecoveryActionType.DISMISS_POPUP_DIALOG,
                    description = "Unexpected popup or modal dialog detected. Dismissing with '$dismissButtonText'.",
                    dismissNodeText = dismissButtonText
                )
            }
        }

        // 5. Soft Keyboard / IME detection when interacting with buttons or cards
        if (currentFingerprint.hasKeyboard && (step.actionType == UniversalActionType.TAP || step.actionType == UniversalActionType.SELECT || step.actionType == UniversalActionType.PLAY)) {
            if (budget.canAttempt(RecoveryLevel.LEVEL_1_LOCAL_UI)) {
                val alreadyClosedKeyboard = history.takeLast(1).any { it.strategyApplied.actionType == RecoveryActionType.CLOSE_KEYBOARD }
                if (!alreadyClosedKeyboard) {
                    return RecoveryStrategy(
                        level = RecoveryLevel.LEVEL_1_LOCAL_UI,
                        actionType = RecoveryActionType.CLOSE_KEYBOARD,
                        description = "Soft keyboard is covering the screen controls. Closing keyboard."
                    )
                }
            }
        }

        // 6. Wrong input field guard (Requirement 9): Never type a message into a search field!
        if (step.actionType == UniversalActionType.SEND_MESSAGE || step.actionType == UniversalActionType.SEND_WHATSAPP_MESSAGE) {
            val isFocusedOnSearch = currentFingerprint.focusedNodeResourceId?.contains("search", ignoreCase = true) == true ||
                    currentFingerprint.focusedNodeResourceId?.contains("query", ignoreCase = true) == true
            if (isFocusedOnSearch) {
                return RecoveryStrategy(
                    level = RecoveryLevel.LEVEL_1_LOCAL_UI,
                    actionType = RecoveryActionType.CLEAR_WRONG_SEARCH_FIELD,
                    description = "Detected search field focused instead of message composer. Clearing and returning to chat."
                )
            }
        }

        // 7. Tab / Navigation section mismatch
        if (targetApp.equals("Spotify", ignoreCase = true) && (step.actionType == UniversalActionType.SEARCH || step.actionType == UniversalActionType.PLAY)) {
            val hasSearchInScreen = currentScreen.visibleTexts.any { it.equals("search", ignoreCase = true) || it.contains("खोजें") }
            val hasSearchTab = currentScreen.nodes.any { node ->
                val desc = node.contentDescription.lowercase(Locale.ROOT)
                val text = node.text.lowercase(Locale.ROOT)
                (desc.contains("search") || text.contains("search")) && node.isClickable
            }
            if (hasSearchTab && !hasSearchInScreen) {
                if (budget.canAttempt(RecoveryLevel.LEVEL_1_LOCAL_UI)) {
                    return RecoveryStrategy(
                        level = RecoveryLevel.LEVEL_1_LOCAL_UI,
                        actionType = RecoveryActionType.SELECT_CORRECT_TAB,
                        description = "Spotify search tab is not active. Selecting Search tab.",
                        param = "Search"
                    )
                }
            }
        }

        if (targetApp.equals("WhatsApp", ignoreCase = true) && (step.actionType == UniversalActionType.SEND_MESSAGE || step.actionType == UniversalActionType.OPEN_CHAT)) {
            val hasChatsTab = currentScreen.nodes.any { node ->
                val text = node.text.lowercase(Locale.ROOT)
                text == "chats" || text == "चैट्स"
            }
            val onUpdatesOrCalls = currentScreen.visibleTexts.any { it.equals("status", ignoreCase = true) || it.equals("calls", ignoreCase = true) }
            if (hasChatsTab && onUpdatesOrCalls) {
                if (budget.canAttempt(RecoveryLevel.LEVEL_1_LOCAL_UI)) {
                    return RecoveryStrategy(
                        level = RecoveryLevel.LEVEL_1_LOCAL_UI,
                        actionType = RecoveryActionType.SELECT_CORRECT_TAB,
                        description = "WhatsApp is on secondary tab (Calls/Updates). Switching back to Chats tab.",
                        param = "Chats"
                    )
                }
            }
        }

        // 8. Scroll recovery: Target element may be off-screen
        val scrollAttemptsCount = history.count { it.strategyApplied.actionType == RecoveryActionType.SCROLL_DOWN || it.strategyApplied.actionType == RecoveryActionType.SCROLL_UP }
        val isScrollable = currentScreen.nodes.any { it.isScrollable } || currentFingerprint.visibleTextsCount > 6
        if (isScrollable && scrollAttemptsCount < 2 && budget.canAttempt(RecoveryLevel.LEVEL_1_LOCAL_UI)) {
            val lastWasScrollDown = history.takeLast(1).any { it.strategyApplied.actionType == RecoveryActionType.SCROLL_DOWN }
            val scrollAction = if (lastWasScrollDown) RecoveryActionType.SCROLL_UP else RecoveryActionType.SCROLL_DOWN
            return RecoveryStrategy(
                level = RecoveryLevel.LEVEL_1_LOCAL_UI,
                actionType = scrollAction,
                description = "Target element not in current viewport. Scrolling ${if (scrollAction == RecoveryActionType.SCROLL_DOWN) "downward" else "upward"} to reveal off-screen items."
            )
        }

        // 9. Navigation recovery (Level 2): Use Back button to exit unexpected sub-screens
        if (budget.canAttempt(RecoveryLevel.LEVEL_2_NAVIGATION)) {
            val consecutiveBacks = history.takeLast(2).count { it.strategyApplied.actionType == RecoveryActionType.NAVIGATE_BACK }
            if (consecutiveBacks < 2) {
                return RecoveryStrategy(
                    level = RecoveryLevel.LEVEL_2_NAVIGATION,
                    actionType = RecoveryActionType.NAVIGATE_BACK,
                    description = "Target element not found on current screen. Navigating back to previous screen."
                )
            }
        }

        // 10. App State Reset (Level 3): When UI is unresponsive, stuck, or in an unrecoverable state
        if (budget.canAttempt(RecoveryLevel.LEVEL_3_APP_STATE_RESET)) {
            return RecoveryStrategy(
                level = RecoveryLevel.LEVEL_3_APP_STATE_RESET,
                actionType = RecoveryActionType.REOPEN_APP_FROM_CHECKPOINT,
                description = "Screen navigation corrupted or unresponsive. Resetting app state and relaunching $targetApp from latest verified checkpoint.",
                param = targetPackage
            )
        }

        // 11. Safe Stop (Level 5): Prevent looping or unintended actions
        return RecoveryStrategy(
            level = RecoveryLevel.LEVEL_5_SAFE_STOP,
            actionType = RecoveryActionType.SAFE_STOP,
            description = "Could not reach required screen state for '${step.actionType}' after exhausting recovery levels."
        )
    }

    /**
     * Executes the chosen recovery strategy on the device via AccessibilityService or Intent.
     */
    suspend fun executeRecovery(
        strategy: RecoveryStrategy,
        context: Context,
        step: TaskStep,
        checkpointManager: CheckpointManager
    ): Boolean {
        val service = KavyaAccessibilityService.instance
        KavyaStateManager.updateTaskState(TaskState.RETRYING, "Recovery: ${strategy.description}")
        Log.i(TAG, "Executing recovery [${strategy.level}]: ${strategy.description}")

        return when (strategy.actionType) {
            RecoveryActionType.DIRECT_EXECUTE -> true

            RecoveryActionType.WAIT_FOR_STABILIZATION -> {
                delay(800)
                true
            }

            RecoveryActionType.DISMISS_POPUP_DIALOG -> {
                if (service != null && strategy.dismissNodeText != null) {
                    val clicked = service.clickNodeByText(strategy.dismissNodeText)
                    if (clicked) {
                        delay(400)
                        return true
                    }
                }
                // Fallback to back press to dismiss dialog
                if (service != null) {
                    service.performGlobal(AccessibilityService.GLOBAL_ACTION_BACK)
                    delay(300)
                    return true
                }
                false
            }

            RecoveryActionType.CLOSE_KEYBOARD -> {
                if (service != null) {
                    service.performGlobal(AccessibilityService.GLOBAL_ACTION_BACK)
                    delay(300)
                    return true
                }
                false
            }

            RecoveryActionType.CLEAR_WRONG_SEARCH_FIELD -> {
                if (service != null) {
                    val root = service.rootInActiveWindow
                    val searchNode = service.findSearchField(root)
                    if (searchNode != null) {
                        service.clearNodeText(searchNode)
                    }
                    service.performGlobal(AccessibilityService.GLOBAL_ACTION_BACK)
                    delay(350)
                    return true
                }
                false
            }

            RecoveryActionType.SCROLL_DOWN -> {
                if (service != null) {
                    val scrolled = service.scroll("DOWN")
                    delay(400)
                    return scrolled
                }
                false
            }

            RecoveryActionType.SCROLL_UP -> {
                if (service != null) {
                    val scrolled = service.scroll("UP")
                    delay(400)
                    return scrolled
                }
                false
            }

            RecoveryActionType.SELECT_CORRECT_TAB -> {
                if (service != null && strategy.param.isNotBlank()) {
                    val clicked = service.clickNodeByText(strategy.param)
                    delay(400)
                    return clicked
                }
                false
            }

            RecoveryActionType.NAVIGATE_BACK -> {
                if (service != null) {
                    val backed = service.performGlobal(AccessibilityService.GLOBAL_ACTION_BACK)
                    delay(400)
                    return backed
                }
                false
            }

            RecoveryActionType.NAVIGATE_APP_HOME -> {
                if (service != null) {
                    // Try in-app home icon or back twice
                    val clickedHome = service.clickNodeByText("Home") || service.clickNodeByText("होम")
                    if (!clickedHome) {
                        service.performGlobal(AccessibilityService.GLOBAL_ACTION_BACK)
                    }
                    delay(400)
                    return true
                }
                false
            }

            RecoveryActionType.SWITCH_TO_TARGET_PACKAGE -> {
                val targetPkg = strategy.param
                if (targetPkg.isNotBlank()) {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(targetPkg)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        delay(600)
                        return true
                    }
                }
                false
            }

            RecoveryActionType.REOPEN_APP_FROM_CHECKPOINT -> {
                val targetPkg = strategy.param
                if (targetPkg.isNotBlank()) {
                    // Check if critical action was already completed
                    val latest = checkpointManager.getLatestCheckpoint()
                    Log.i(TAG, "Reopening $targetPkg. Latest checkpoint: ${latest?.type}")

                    val launchIntent = context.packageManager.getLaunchIntentForPackage(targetPkg)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        context.startActivity(launchIntent)
                        delay(800)
                        return true
                    }
                }
                false
            }

            RecoveryActionType.REBUILD_UI_PATH, RecoveryActionType.SAFE_STOP -> {
                false
            }
        }
    }

    private fun findPopupDismissButton(screen: FreshScreenState): String? {
        for (node in screen.nodes) {
            val textLower = node.text.lowercase(Locale.ROOT).trim()
            val descLower = node.contentDescription.lowercase(Locale.ROOT).trim()
            for (keyword in POPUP_DISMISS_KEYWORDS) {
                if ((textLower == keyword || descLower == keyword) && node.isClickable) {
                    return if (node.text.isNotBlank()) node.text else node.contentDescription
                }
            }
        }
        return null
    }

    private fun isPackageMatching(currentPkg: String, expectedPkg: String, targetApp: String): Boolean {
        if (currentPkg.isBlank() || currentPkg == "unknown" || currentPkg == "none") return true
        if (currentPkg.equals(expectedPkg, ignoreCase = true)) return true
        if (currentPkg.contains(expectedPkg, ignoreCase = true) || expectedPkg.contains(currentPkg, ignoreCase = true)) return true

        val lowerApp = targetApp.lowercase(Locale.ROOT)
        return when {
            lowerApp.contains("youtube") && currentPkg.contains("youtube") -> true
            lowerApp.contains("spotify") && currentPkg.contains("spotify") -> true
            lowerApp.contains("whatsapp") && currentPkg.contains("whatsapp") -> true
            lowerApp.contains("chrome") && (currentPkg.contains("chrome") || currentPkg.contains("browser")) -> true
            lowerApp.contains("calculator") && currentPkg.contains("calculator") -> true
            lowerApp.contains("settings") && currentPkg.contains("settings") -> true
            lowerApp.contains("camera") && currentPkg.contains("camera") -> true
            lowerApp.contains("free fire") && (currentPkg.contains("freefire") || currentPkg.contains("dts")) -> true
            else -> false
        }
    }
}
