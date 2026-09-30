package com.example

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.example.agent.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ContextAwareRecoveryTest {

    private lateinit var context: Context
    private lateinit var checkpointManager: CheckpointManager
    private lateinit var recoveryManager: RecoveryManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        checkpointManager = CheckpointManager()
        recoveryManager = RecoveryManager()
    }

    // ====================================================
    // TEST 1: SCREEN STATE FINGERPRINT
    // ====================================================
    @Test
    fun testScreenFingerprint_IdentityAndChangeDetection() {
        val fp1 = ScreenFingerprint(
            packageName = "com.spotify.music",
            screenTitle = "Search",
            visibleTextsCount = 10,
            visibleTextsSample = listOf("Search", "Artists", "Songs", "Arijit Singh"),
            textHash = 12345,
            focusedNodeResourceId = "com.spotify.music:id/query",
            focusedNodeIsEditable = true,
            hasDialogOrPopup = false,
            hasKeyboard = true,
            isLoading = false
        )

        // Exact match should NOT be meaningfully different
        val fpIdentical = fp1.copy()
        assertFalse(fp1.isMeaningfullyDifferentFrom(fpIdentical))

        // Package change is meaningfully different
        val fpDifferentPackage = fp1.copy(packageName = "com.android.chrome")
        assertTrue(fp1.isMeaningfullyDifferentFrom(fpDifferentPackage))

        // Dialog appearance is meaningfully different
        val fpWithDialog = fp1.copy(hasDialogOrPopup = true)
        assertTrue(fp1.isMeaningfullyDifferentFrom(fpWithDialog))

        // Keyboard close is meaningfully different
        val fpNoKeyboard = fp1.copy(hasKeyboard = false)
        assertTrue(fp1.isMeaningfullyDifferentFrom(fpNoKeyboard))

        // Substantial text count change (e.g. results loaded)
        val fpMoreTexts = fp1.copy(visibleTextsCount = 20, textHash = 99999)
        assertTrue(fp1.isMeaningfullyDifferentFrom(fpMoreTexts))
    }

    // ====================================================
    // TEST 2: CHECKPOINT MANAGER & DEDUPLICATION GUARDS
    // ====================================================
    @Test
    fun testCheckpointManager_MilestonesAndDeduplication() {
        // Record Checkpoint 1: App Opened
        checkpointManager.recordCheckpoint(
            stepId = 1,
            type = CheckpointType.CHECKPOINT_1_APP_OPENED,
            targetApp = "WhatsApp",
            targetPackage = "com.whatsapp"
        )
        assertTrue(checkpointManager.hasCheckpoint(CheckpointType.CHECKPOINT_1_APP_OPENED))

        // Record Checkpoint 6: Action Completed (WhatsApp message sent)
        checkpointManager.recordCheckpoint(
            stepId = 2,
            type = CheckpointType.CHECKPOINT_6_ACTION_COMPLETED,
            targetApp = "WhatsApp",
            targetPackage = "com.whatsapp",
            data = mapOf(
                "action" to "SEND_MESSAGE",
                "recipient" to "Rohan",
                "message" to "Hello"
            )
        )

        // Verify deduplication guard prevents duplicate message sending
        assertTrue(checkpointManager.isMessageAlreadySent("Rohan", "Hello"))
        assertFalse(checkpointManager.isMessageAlreadySent("Rohan", "Different message"))
        assertFalse(checkpointManager.isMessageAlreadySent("Sohan", "Hello"))

        // Record Checkpoint: Phone call initiated
        checkpointManager.recordCheckpoint(
            stepId = 3,
            type = CheckpointType.CHECKPOINT_6_ACTION_COMPLETED,
            targetApp = "Phone",
            targetPackage = "com.google.android.dialer",
            data = mapOf(
                "action" to "CALL",
                "target" to "Rohan"
            )
        )
        assertTrue(checkpointManager.isCallAlreadyInitiated("Rohan"))
        assertFalse(checkpointManager.isCallAlreadyInitiated("Sohan"))
    }

    // ====================================================
    // TEST 3: RECOVERY BUDGET ENFORCEMENT
    // ====================================================
    @Test
    fun testRecoveryBudget_EnforcesAttemptLimits() {
        val budget = RecoveryBudget(
            maxLocalAttempts = 3,
            maxNavigationAttempts = 2,
            maxAppRestartAttempts = 1,
            maxTotalAttempts = 6
        )

        // Initially all levels allowed
        assertTrue(budget.canAttempt(RecoveryLevel.LEVEL_1_LOCAL_UI))
        assertTrue(budget.canAttempt(RecoveryLevel.LEVEL_2_NAVIGATION))
        assertTrue(budget.canAttempt(RecoveryLevel.LEVEL_3_APP_STATE_RESET))

        // Exhaust local attempts
        budget.recordAttempt(RecoveryLevel.LEVEL_1_LOCAL_UI)
        budget.recordAttempt(RecoveryLevel.LEVEL_1_LOCAL_UI)
        budget.recordAttempt(RecoveryLevel.LEVEL_1_LOCAL_UI)
        assertFalse(budget.canAttempt(RecoveryLevel.LEVEL_1_LOCAL_UI))
        assertTrue(budget.canAttempt(RecoveryLevel.LEVEL_2_NAVIGATION))

        // Exhaust navigation attempts
        budget.recordAttempt(RecoveryLevel.LEVEL_2_NAVIGATION)
        budget.recordAttempt(RecoveryLevel.LEVEL_2_NAVIGATION)
        assertFalse(budget.canAttempt(RecoveryLevel.LEVEL_2_NAVIGATION))
        assertTrue(budget.canAttempt(RecoveryLevel.LEVEL_3_APP_STATE_RESET))

        // Exhaust app restart attempts
        budget.recordAttempt(RecoveryLevel.LEVEL_3_APP_STATE_RESET)
        assertFalse(budget.canAttempt(RecoveryLevel.LEVEL_3_APP_STATE_RESET))

        // Total attempts reached (6) -> Cannot attempt any recovery level except safe stop
        assertEquals(6, budget.currentTotalAttempts)
        assertFalse(budget.canAttempt(RecoveryLevel.LEVEL_1_LOCAL_UI))
        assertFalse(budget.canAttempt(RecoveryLevel.LEVEL_2_NAVIGATION))
        assertFalse(budget.canAttempt(RecoveryLevel.LEVEL_3_APP_STATE_RESET))
    }

    // ====================================================
    // TEST 4: LEVEL 1 RECOVERY — WRONG PACKAGE DETECTION
    // ====================================================
    @Test
    fun testRecoveryPlanner_WrongPackageDetection() {
        val budget = RecoveryBudget()
        val step = TaskStep(id = 2, actionType = UniversalActionType.SEARCH, param = "Arijit Singh")

        // Current package is Chrome, but target is Spotify
        val screen = FreshScreenState(
            root = null,
            foregroundPackage = "com.android.chrome",
            nodes = emptyList(),
            visibleTexts = listOf("Chrome", "New Tab"),
            summaryString = "Chrome browser active",
            isLoading = false,
            searchFields = emptyList(),
            searchButtons = emptyList(),
            organicContentResults = emptyList(),
            adNodes = emptyList()
        )

        val strategy = recoveryManager.planRecovery(
            currentScreen = screen,
            step = step,
            targetApp = "Spotify",
            targetPackage = "com.spotify.music",
            budget = budget,
            history = emptyList(),
            checkpointManager = checkpointManager
        )

        assertEquals(RecoveryLevel.LEVEL_1_LOCAL_UI, strategy.level)
        assertEquals(RecoveryActionType.SWITCH_TO_TARGET_PACKAGE, strategy.actionType)
        assertEquals("com.spotify.music", strategy.param)
    }

    // ====================================================
    // TEST 5: LEVEL 1 RECOVERY — LOADING / DELAY RECOVERY
    // ====================================================
    @Test
    fun testRecoveryPlanner_LoadingStabilization() {
        val budget = RecoveryBudget()
        val step = TaskStep(id = 2, actionType = UniversalActionType.SELECT, param = "Track 1")

        val screen = FreshScreenState(
            root = null,
            foregroundPackage = "com.spotify.music",
            nodes = emptyList(),
            visibleTexts = listOf("Spotify", "Loading..."),
            summaryString = "Spotify loading",
            isLoading = true,
            searchFields = emptyList(),
            searchButtons = emptyList(),
            organicContentResults = emptyList(),
            adNodes = emptyList()
        )

        val strategy = recoveryManager.planRecovery(
            currentScreen = screen,
            step = step,
            targetApp = "Spotify",
            targetPackage = "com.spotify.music",
            budget = budget,
            history = emptyList(),
            checkpointManager = checkpointManager
        )

        assertEquals(RecoveryLevel.LEVEL_1_LOCAL_UI, strategy.level)
        assertEquals(RecoveryActionType.WAIT_FOR_STABILIZATION, strategy.actionType)
    }

    // ====================================================
    // TEST 6: LEVEL 1 RECOVERY — POPUP / DIALOG DISMISSAL
    // ====================================================
    @Test
    fun testRecoveryPlanner_PopupDismissal() {
        val budget = RecoveryBudget()
        val step = TaskStep(id = 2, actionType = UniversalActionType.TAP, param = "Play")

        // Mock analyzed node for "Not now" button
        val dummyNode = AnalyzedNode(
            node = AccessibilityNodeInfo.obtain(),
            text = "Not now",
            contentDescription = "Dismiss dialog",
            resourceId = "com.spotify.music:id/dismiss",
            className = "android.widget.Button",
            bounds = Rect(200, 800, 400, 900),
            isClickable = true,
            isEditable = false,
            isScrollable = false
        )

        val screen = FreshScreenState(
            root = null,
            foregroundPackage = "com.spotify.music",
            nodes = listOf(dummyNode),
            visibleTexts = listOf("Enjoying Spotify Premium?", "Not now", "Upgrade"),
            summaryString = "Modal dialog active",
            isLoading = false,
            searchFields = emptyList(),
            searchButtons = emptyList(),
            organicContentResults = emptyList(),
            adNodes = emptyList()
        )

        val strategy = recoveryManager.planRecovery(
            currentScreen = screen,
            step = step,
            targetApp = "Spotify",
            targetPackage = "com.spotify.music",
            budget = budget,
            history = emptyList(),
            checkpointManager = checkpointManager
        )

        assertEquals(RecoveryLevel.LEVEL_1_LOCAL_UI, strategy.level)
        assertEquals(RecoveryActionType.DISMISS_POPUP_DIALOG, strategy.actionType)
        assertEquals("Not now", strategy.dismissNodeText)
    }

    // ====================================================
    // TEST 7: LEVEL 1 RECOVERY — WRONG INPUT FIELD GUARD
    // Never type message into a search field!
    // ====================================================
    @Test
    fun testRecoveryPlanner_WrongInputFieldGuard() {
        val budget = RecoveryBudget()
        val step = TaskStep(
            id = 2,
            actionType = UniversalActionType.SEND_WHATSAPP_MESSAGE,
            recipient = "Rohan",
            messageText = "Hello"
        )

        val dummySearchNode = AnalyzedNode(
            node = AccessibilityNodeInfo.obtain().apply { isFocused = true },
            text = "",
            contentDescription = "Search…",
            resourceId = "com.whatsapp:id/search_src_text",
            className = "android.widget.EditText",
            bounds = Rect(100, 80, 800, 160),
            isClickable = true,
            isEditable = true,
            isScrollable = false
        )

        val screen = FreshScreenState(
            root = null,
            foregroundPackage = "com.whatsapp",
            nodes = listOf(dummySearchNode),
            visibleTexts = listOf("Chats", "Search…"),
            summaryString = "WhatsApp search mode active",
            isLoading = false,
            searchFields = listOf(dummySearchNode),
            searchButtons = emptyList(),
            organicContentResults = emptyList(),
            adNodes = emptyList()
        )

        val strategy = recoveryManager.planRecovery(
            currentScreen = screen,
            step = step,
            targetApp = "WhatsApp",
            targetPackage = "com.whatsapp",
            budget = budget,
            history = emptyList(),
            checkpointManager = checkpointManager
        )

        assertEquals(RecoveryLevel.LEVEL_1_LOCAL_UI, strategy.level)
        assertEquals(RecoveryActionType.CLEAR_WRONG_SEARCH_FIELD, strategy.actionType)
    }

    // ====================================================
    // TEST 8: LEVEL 3 RECOVERY — APP STATE RESET
    // ====================================================
    @Test
    fun testRecoveryPlanner_AppStateResetOnStuckScreen() {
        val budget = RecoveryBudget(
            currentLocalAttempts = 3,
            currentNavigationAttempts = 2,
            currentAppRestartAttempts = 0
        )
        val step = TaskStep(id = 2, actionType = UniversalActionType.SELECT, param = "Arijit Singh")

        val screen = FreshScreenState(
            root = null,
            foregroundPackage = "com.spotify.music",
            nodes = emptyList(),
            visibleTexts = listOf("Spotify", "Something went wrong"),
            summaryString = "Corrupted UI state",
            isLoading = false,
            searchFields = emptyList(),
            searchButtons = emptyList(),
            organicContentResults = emptyList(),
            adNodes = emptyList()
        )

        val strategy = recoveryManager.planRecovery(
            currentScreen = screen,
            step = step,
            targetApp = "Spotify",
            targetPackage = "com.spotify.music",
            budget = budget,
            history = emptyList(),
            checkpointManager = checkpointManager
        )

        assertEquals(RecoveryLevel.LEVEL_3_APP_STATE_RESET, strategy.level)
        assertEquals(RecoveryActionType.REOPEN_APP_FROM_CHECKPOINT, strategy.actionType)
        assertEquals("com.spotify.music", strategy.param)
    }

    // ====================================================
    // TEST 9: LEVEL 5 — SAFE STOP WHEN BUDGET EXHAUSTED
    // ====================================================
    @Test
    fun testRecoveryPlanner_SafeStopWhenBudgetExhausted() {
        val exhaustedBudget = RecoveryBudget(
            currentLocalAttempts = 3,
            currentNavigationAttempts = 2,
            currentAppRestartAttempts = 1,
            currentTotalAttempts = 6
        )
        val step = TaskStep(id = 2, actionType = UniversalActionType.SELECT, param = "Arijit Singh")

        val screen = FreshScreenState(
            root = null,
            foregroundPackage = "com.spotify.music",
            nodes = emptyList(),
            visibleTexts = listOf("Spotify"),
            summaryString = "Still stuck",
            isLoading = false,
            searchFields = emptyList(),
            searchButtons = emptyList(),
            organicContentResults = emptyList(),
            adNodes = emptyList()
        )

        val strategy = recoveryManager.planRecovery(
            currentScreen = screen,
            step = step,
            targetApp = "Spotify",
            targetPackage = "com.spotify.music",
            budget = exhaustedBudget,
            history = emptyList(),
            checkpointManager = checkpointManager
        )

        assertEquals(RecoveryLevel.LEVEL_5_SAFE_STOP, strategy.level)
        assertEquals(RecoveryActionType.SAFE_STOP, strategy.actionType)
        assertTrue(strategy.description.contains("Recovery budget exceeded"))
    }
}
