package com.example.agent

/**
 * Recovery levels defining escalating remediation strategies.
 */
enum class RecoveryLevel {
    LEVEL_0_DIRECT_ACTION,       // Target verified and actionable
    LEVEL_1_LOCAL_UI,            // Scroll, dismiss dialog, close keyboard, tab switch, wait for loading
    LEVEL_2_NAVIGATION,          // In-app Back, return to search tab, navigate to app home screen
    LEVEL_3_APP_STATE_RESET,     // Save checkpoint, relaunch app, verify package, resume from checkpoint
    LEVEL_4_DEEP_RECOVERY,       // Return to root, re-search query, rebuild entire UI interaction path
    LEVEL_5_SAFE_STOP            // Recovery budget exceeded; stop safely and report actionable reason
}

/**
 * Concrete action to be executed during a recovery attempt.
 */
enum class RecoveryActionType {
    DIRECT_EXECUTE,
    WAIT_FOR_STABILIZATION,
    DISMISS_POPUP_DIALOG,
    CLOSE_KEYBOARD,
    SCROLL_DOWN,
    SCROLL_UP,
    SELECT_CORRECT_TAB,
    CLEAR_WRONG_SEARCH_FIELD,
    NAVIGATE_BACK,
    NAVIGATE_APP_HOME,
    SWITCH_TO_TARGET_PACKAGE,
    REOPEN_APP_FROM_CHECKPOINT,
    REBUILD_UI_PATH,
    SAFE_STOP
}

/**
 * Strategy planned by RecoveryManager to transition from current mismatched screen to required state.
 */
data class RecoveryStrategy(
    val level: RecoveryLevel,
    val actionType: RecoveryActionType,
    val description: String,
    val param: String = "",
    val dismissNodeText: String? = null
)

/**
 * Historic record of an applied recovery attempt to prevent cycling or repeating failed strategies.
 */
data class RecoveryAttempt(
    val attemptNumber: Int,
    val screenFingerprint: ScreenFingerprint,
    val actionAttempted: String,
    val failureReason: String,
    val strategyApplied: RecoveryStrategy,
    val resultSuccessful: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Configurable budget to bound automated recovery attempts and prevent infinite loops.
 */
data class RecoveryBudget(
    val maxLocalAttempts: Int = 3,
    val maxNavigationAttempts: Int = 2,
    val maxAppRestartAttempts: Int = 1,
    val maxTotalAttempts: Int = 6,
    var currentLocalAttempts: Int = 0,
    var currentNavigationAttempts: Int = 0,
    var currentAppRestartAttempts: Int = 0,
    var currentTotalAttempts: Int = 0
) {
    fun canAttempt(level: RecoveryLevel): Boolean {
        if (currentTotalAttempts >= maxTotalAttempts) return false
        return when (level) {
            RecoveryLevel.LEVEL_0_DIRECT_ACTION -> true
            RecoveryLevel.LEVEL_1_LOCAL_UI -> currentLocalAttempts < maxLocalAttempts
            RecoveryLevel.LEVEL_2_NAVIGATION -> currentNavigationAttempts < maxNavigationAttempts
            RecoveryLevel.LEVEL_3_APP_STATE_RESET -> currentAppRestartAttempts < maxAppRestartAttempts
            RecoveryLevel.LEVEL_4_DEEP_RECOVERY -> currentNavigationAttempts < maxNavigationAttempts
            RecoveryLevel.LEVEL_5_SAFE_STOP -> true
        }
    }

    fun recordAttempt(level: RecoveryLevel) {
        currentTotalAttempts++
        when (level) {
            RecoveryLevel.LEVEL_1_LOCAL_UI -> currentLocalAttempts++
            RecoveryLevel.LEVEL_2_NAVIGATION, RecoveryLevel.LEVEL_4_DEEP_RECOVERY -> currentNavigationAttempts++
            RecoveryLevel.LEVEL_3_APP_STATE_RESET -> currentAppRestartAttempts++
            else -> {}
        }
    }
}
