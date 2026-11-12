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
    OPEN_CHAT,
    CREATE_FOLDER,
    SELECT_RESULT,
    SCREENSHOT,
    SWITCH_APP,
    ANALYZE_IMAGE,
    RESEARCH_WEB,
    GENERATE_IMAGE,
    SEMANTIC_SEARCH,
    GOOGLE_MAPS_SEARCH,
    MEMORY_TOOL,
    WAIT_FOR_CONDITION,
    CONFIRM_ACTION,
    DOWNLOAD_IMAGE,
    ATTACH_FILE,
    CREATE_REPORT,
    SUMMARIZE_TEXT,
    EXTRACT_DATA,
    FILL_FORM,
    VALIDATE_FORM,
    ORGANIZE_FILES,
    REPLAN_TASK
}

/**
 * Execution Status of a Step (Section 2 & 9).
 */
enum class StepStatus {
    PENDING,
    READY,
    RUNNING,
    WAITING,
    VERIFYING,
    COMPLETED,
    FAILED,
    BLOCKED,
    NEEDS_USER_INPUT,
    CANCELLED
}

/**
 * Wait condition types for intelligent waiting (Section 7).
 */
enum class WaitConditionType {
    NONE,
    UI_STABLE,
    ELEMENT_VISIBLE,
    ELEMENT_GONE,
    PAGE_LOADED,
    IMAGE_GENERATED,
    FILE_DOWNLOADED,
    MEDIA_PLAYING,
    APP_FOREGROUND
}

/**
 * Retry strategy for atomic steps (Section 8).
 */
data class StepRetryStrategy(
    val maxRetries: Int = 2,
    val retryDelayMs: Long = 500L,
    val currentRetries: Int = 0,
    val alternativeStrategy: String? = null,
    val allowReplanOnFailure: Boolean = true
)

/**
 * Waiting and timing strategy for operations that take time (Section 7).
 */
data class StepWaitingStrategy(
    val timeoutMs: Long = 10000L,
    val pollIntervalMs: Long = 500L,
    val waitCondition: WaitConditionType = WaitConditionType.NONE,
    val customConditionDesc: String = "",
    val notifyUserIfSlowAfterMs: Long = 8000L
)

/**
 * Semantic element selector categories to target controls without hardcoded coordinates.
 */
enum class SelectorType {
    EXACT_TEXT,
    SEMANTIC_HINT,
    SEARCH_FIELD,
    SEARCH_BUTTON,
    SEND_BUTTON,
    DOWNLOAD_BUTTON,
    ATTACHMENT_BUTTON,
    MENU_BUTTON,
    CONFIRMATION_BUTTON,
    FORM_FIELD,
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
 * Individual atomic action step in a multi-step task plan (Section 2).
 *
 * Each step contains:
 * - Step ID
 * - Action description
 * - Target application
 * - Expected screen or UI state
 * - Required input
 * - Expected output
 * - Dependency on previous steps
 * - Execution status
 * - Success verification condition
 * - Retry strategy
 * - Timeout or waiting strategy
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
    val timeoutMs: Long = 2000L,
    // Enhanced architectural attributes for Universal Task Decomposition (Section 2 & 3)
    val stepId: String = "step_$id",
    val actionDescription: String = spokenAnnouncement.ifBlank { "$actionType on $targetAppOrUrl" },
    val targetApplication: String = targetAppOrUrl,
    val expectedScreenOrUiState: String = expectedOutcome,
    val requiredInput: String = param,
    val expectedOutput: String = expectedOutcome,
    val dependsOnStepIds: List<String> = emptyList(),
    var status: StepStatus = StepStatus.PENDING,
    val successVerificationCondition: String = expectedOutcome.ifBlank { "Action $actionType completed" },
    val retryStrategy: StepRetryStrategy = StepRetryStrategy(),
    val waitingStrategy: StepWaitingStrategy = StepWaitingStrategy(timeoutMs = timeoutMs),
    val requiresUserConfirmation: Boolean = false,
    val isConsequentialOrSensitive: Boolean = false
) {
    fun isExecutable(completedStepIds: Set<String>): Boolean {
        if (status == StepStatus.COMPLETED || status == StepStatus.CANCELLED || status == StepStatus.FAILED) return false
        return dependsOnStepIds.all { it in completedStepIds }
    }
}

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
 * Real-time state container for active plan execution (Section 9 & 10).
 */
data class TaskPlanExecutionState(
    var isPaused: Boolean = false,
    var isCancelled: Boolean = false,
    var currentStepId: String? = null,
    val completedStepIds: MutableSet<String> = mutableSetOf(),
    val skippedStepIds: MutableSet<String> = mutableSetOf(),
    val failedStepIds: MutableSet<String> = mutableSetOf(),
    val stepOutputs: MutableMap<String, String> = mutableMapOf()
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
    val universalIntents: List<UniversalIntent> = emptyList(),
    val appliedMemory: com.example.data.MemoryEntity? = null,
    val rememberedWorkflowUsed: Boolean = false,
    val planId: String = java.util.UUID.randomUUID().toString(),
    val finalExpectedOutcome: String = "",
    val executionState: TaskPlanExecutionState = TaskPlanExecutionState()
) {
    fun getPendingSteps(): List<TaskStep> = steps.filter { it.status == StepStatus.PENDING || it.status == StepStatus.READY }
    fun getCompletedSteps(): List<TaskStep> = steps.filter { it.status == StepStatus.COMPLETED }
    fun getFailedSteps(): List<TaskStep> = steps.filter { it.status == StepStatus.FAILED }
}

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
    val detectedElements: List<String> = emptyList(),
    val stepStatus: StepStatus = if (success) StepStatus.COMPLETED else StepStatus.FAILED,
    val generatedData: String? = null,
    val needsUserConfirmation: Boolean = false
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
    val debugLogs: List<AutomationDebugLog> = emptyList(),
    val stepResults: List<StepExecutionResult> = emptyList()
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
    val selectedModel: String = "gemini-3.5-flash-lite",
    val requestLatencyMs: Long = 0L,
    val apiResponseStatus: String = "200 OK",
    val retriesCount: Int = 0
)
