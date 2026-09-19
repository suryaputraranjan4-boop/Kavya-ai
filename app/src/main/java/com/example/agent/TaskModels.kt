package com.example.agent

import com.example.utils.AppLaunchDiagnostic
import com.example.utils.InstalledApp

/**
 * Universal Action Types supported across all Android apps, websites, and system services.
 */
enum class UniversalActionType {
    OPEN_APP,
    OPEN_FILE,
    READ_FILE,
    CREATE_FILE,
    DELETE_FILE,
    SAVE_FILE,
    SHARE_FILE,
    UPLOAD_FILE,
    DOWNLOAD_FILE,
    OPEN_URL,
    CLOSE_APP,
    BACK,
    HOME,
    SEARCH,
    TAP,
    TYPE,
    CLEAR,
    CLEAR_TEXT,
    SCROLL,
    SWIPE,
    SELECT,
    LONG_PRESS,
    COPY,
    PASTE,
    PLAY,
    PAUSE,
    NEXT,
    PREVIOUS,
    SUBMIT,
    NAVIGATE,
    READ_SCREEN,
    VERIFY,
    SAVE_MEMORY,
    CHESS_MOVE,
    CALL,
    END_CALL,
    SEND_MESSAGE,
    SYSTEM_CONTROL,
    MAKE_PHONE_CALL,
    MAKE_WHATSAPP_CALL,
    SEND_SMS,
    SEND_WHATSAPP_MESSAGE,
    SEND_EMAIL,
    CREATE_FOLDER,
    SELECT_RESULT,
    SCREENSHOT,
    SWITCH_APP,
    ANALYZE_IMAGE,
    RESEARCH_WEB,
    GENERATE_IMAGE,
    SEMANTIC_SEARCH
}

/**
 * Semantic element selector categories to target controls without hardcoded coordinates.
 */
enum class SelectorType {
    EXACT_TEXT,
    SEMANTIC_HINT,
    SEARCH_FIELD,
    ORDINAL_RESULT,    // First result (0), Second (1), Third (2), Last (-1)
    PLAY_BUTTON,
    REELS_CONTROL,
    SUBMIT_BUTTON,
    ANY_CLICKABLE,
    URL_BAR,
    CONTACT_ITEM,
    GENERIC
}

/**
 * Target type representation.
 */
enum class TargetCategory {
    APP,
    WEBSITE,
    FILE,
    CONTEXTUAL_ACTIVE,
    SYSTEM
}

/**
 * Individual atomic action step in a multi-step task plan.
 */
data class TaskStep(
    val id: Int,
    val actionType: UniversalActionType,
    val targetAppOrUrl: String = "",
    val param: String = "",
    val recipient: String = "",
    val messageText: String = "",
    val selectorType: SelectorType = SelectorType.GENERIC,
    val ordinalIndex: Int = 0, // 0 = 1st, 1 = 2nd, 2 = 3rd, -1 = last
    val spokenAnnouncement: String = "",
    val expectedOutcome: String = "",
    val timeoutMs: Long = 2000L
)

/**
 * High-level universal intent model.
 */
data class UniversalIntent(
    val target: String,
    val targetCategory: TargetCategory,
    val action: UniversalActionType,
    val objectSelector: String = "",
    val parameters: String = "",
    val ordinalIndex: Int = 0,
    val sequenceNumber: Int = 1,
    val expectedResult: String = ""
)

/**
 * Multi-step task plan deconstructed from natural user commands.
 */
data class TaskPlan(
    val originalPrompt: String,
    val targetAppName: String = "",
    val steps: List<TaskStep>,
    val isMultiStep: Boolean = false,
    val explanation: String = "",
    val universalIntents: List<UniversalIntent> = emptyList()
)

/**
 * Verified step execution result.
 */
data class StepExecutionResult(
    val stepId: Int,
    val success: Boolean,
    val actionType: UniversalActionType,
    val output: String,
    val verifiedPackage: String? = null,
    val isAmbiguous: Boolean = false,
    val candidateApps: List<InstalledApp> = emptyList(),
    val diagnostic: AppLaunchDiagnostic? = null,
    val screenSummary: String? = null,
    val detectedElements: List<String> = emptyList()
)

/**
 * Complete multi-step task execution outcome.
 */
data class TaskExecutionOutcome(
    val success: Boolean,
    val finalSpokenMessage: String,
    val completedStepsCount: Int,
    val totalStepsCount: Int,
    val failureReason: String? = null,
    val diagnostics: List<AppLaunchDiagnostic> = emptyList(),
    val candidateApps: List<InstalledApp> = emptyList(),
    val isAmbiguous: Boolean = false,
    val debugLogs: List<AutomationDebugLog> = emptyList()
)

/**
 * Requirement 17: Internal developer-only diagnostic log model:
 * USER PROMPT, CLASSIFIED TASK TYPE, SELECTED PROVIDER, SELECTED MODEL,
 * API REQUEST LATENCY, API RESPONSE STATUS, ACCESSIBILITY ACTIONS EXECUTED,
 * SCREEN VERIFICATION RESULTS, ERRORS AND RETRIES.
 */
data class AutomationDebugLog(
    val id: String,
    val command: String,
    val target: String = "",
    val resolvedApp: String = "",
    val foregroundPackage: String = "",
    val currentScreen: String = "",
    val detectedElements: List<String> = emptyList(),
    val action: String = "",
    val result: String = "",
    val verification: String = "",
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val classifiedTaskType: String = "GENERAL_REASONING",
    val selectedProvider: String = "GEMINI",
    val selectedModel: String = "gemini-2.5-flash",
    val requestLatencyMs: Long = 0L,
    val apiResponseStatus: String = "200 OK",
    val retriesCount: Int = 0
)

