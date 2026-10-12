package com.example.visual

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Supported UI action types for human-like mobile interaction.
 */
enum class VisualActionType {
    TAP,
    LONG_PRESS,
    SWIPE,
    SCROLL,
    TYPE,
    BACK,
    HOME,
    WAIT,
    SELECT,
    OPEN,
    CLOSE,
    STOP
}

/**
 * Keyboard action types for software IME interactions.
 */
enum class KeyboardAction {
    ENTER,
    SEARCH,
    SEND,
    DONE,
    HIDE
}

/**
 * Normalized coordinates (0.0 to 1.0) relative to device screen dimensions.
 */
data class NormalizedCoordinates(
    val x: Float,
    val y: Float
) {
    fun isValid(): Boolean = x in 0.001f..0.999f && y in 0.001f..0.999f

    fun toDeviceCoordinates(screenWidth: Int, screenHeight: Int): DeviceCoordinates {
        return DeviceCoordinates(
            x = (x * screenWidth).coerceIn(0f, screenWidth.toFloat()),
            y = (y * screenHeight).coerceIn(0f, screenHeight.toFloat())
        )
    }
}

/**
 * Device pixel coordinates.
 */
data class DeviceCoordinates(
    val x: Float,
    val y: Float
)

/**
 * Screen understanding output produced by visual/hierarchy analysis.
 */
data class ScreenUnderstanding(
    val description: String,
    val targetFound: Boolean,
    val confidence: Float = 1.0f
)

/**
 * Structured action returned by AI reasoning or heuristic planning.
 */
data class VisualAction(
    val type: VisualActionType,
    val target: String,
    val confidence: Float,
    val normalizedX: Float = -1f,
    val normalizedY: Float = -1f,
    val swipeStartX: Float = 0.5f,
    val swipeStartY: Float = 0.7f,
    val swipeEndX: Float = 0.5f,
    val swipeEndY: Float = 0.3f,
    val swipeDurationMs: Long = 400L,
    val textToType: String = "",
    val scrollDirection: String = "DOWN",
    val waitDurationMs: Long = 1000L,
    val expectedResult: String = "",
    val spokenAnnouncement: String = ""
) {
    fun hasValidCoordinates(): Boolean = normalizedX in 0.001f..0.999f && normalizedY in 0.001f..0.999f

    fun getNormalizedCoordinates(): NormalizedCoordinates? {
        return if (hasValidCoordinates()) NormalizedCoordinates(normalizedX, normalizedY) else null
    }
}

/**
 * Complete snapshot of the observed screen state.
 */
data class VisualScreenState(
    val timestamp: Long = System.currentTimeMillis(),
    val foregroundPackage: String,
    val screenshot: Bitmap? = null,
    val hierarchyNodes: List<HierarchyNodeInfo> = emptyList(),
    val visibleTextSummary: String = "",
    val screenWidth: Int = 1080,
    val screenHeight: Int = 2400
)

/**
 * Semantic accessibility node information.
 */
data class HierarchyNodeInfo(
    val text: String? = null,
    val contentDescription: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val bounds: Rect = Rect(),
    val isClickable: Boolean = false,
    val isEnabled: Boolean = true,
    val isScrollable: Boolean = false,
    val isSelected: Boolean = false,
    val isFocused: Boolean = false,
    val isEditable: Boolean = false
) {
    fun getNormalizedCenter(screenWidth: Int, screenHeight: Int): NormalizedCoordinates {
        val cx = bounds.centerX().toFloat() / screenWidth.coerceAtLeast(1)
        val cy = bounds.centerY().toFloat() / screenHeight.coerceAtLeast(1)
        return NormalizedCoordinates(cx.coerceIn(0f, 1f), cy.coerceIn(0f, 1f))
    }
}

/**
 * Result of executing an individual visual action.
 */
data class VisualActionExecutionResult(
    val success: Boolean,
    val actionType: VisualActionType,
    val target: String,
    val executionMethod: String,
    val message: String,
    val confidence: Float,
    val latencyMs: Long = 0L,
    val verificationPassed: Boolean = false,
    val error: String? = null
)

/**
 * Ephemeral task memory maintained during one automation task.
 * Cleared when task completes or is stopped.
 */
data class VisualTaskMemory(
    val goal: String,
    var currentApp: String,
    var currentStep: Int = 1,
    val completedSteps: MutableList<String> = mutableListOf(),
    var nextStep: String = "",
    var retryCount: Int = 0,
    var lastAction: VisualAction? = null,
    var isCancelled: Boolean = false
)

/**
 * Confidence categorization thresholds.
 */
enum class ConfidenceLevel {
    HIGH,     // >= 0.90
    MEDIUM,   // 0.70 - 0.89
    LOW       // < 0.70
}

/**
 * User-configurable visual automation behavior modes.
 */
enum class VisualAutomationMode {
    ASK_BEFORE_ACTION,
    AUTOMATIC_SAFE,
    MANUAL_CONFIRMATION
}

/**
 * Status representation for the visual automation status UI.
 */
data class VisualEngineStatus(
    val isActive: Boolean = false,
    val stepTitle: String = "",
    val detailMessage: String = "",
    val confidence: Float = 1.0f,
    val actionType: VisualActionType? = null,
    val target: String = "",
    val canEmergencyStop: Boolean = true
)
