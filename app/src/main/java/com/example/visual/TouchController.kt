package com.example.visual

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.services.KavyaAccessibilityService
import kotlinx.coroutines.delay

/**
 * Touch Controller for the Kavya Visual Action Engine.
 * Implements human-like touch interactions with:
 * 1. Accessibility node semantic click priority.
 * 2. Center-point tap on node bounding rectangle.
 * 3. Fallback to validated normalized coordinates converted to device pixels.
 * 4. Human-like natural timing (no robotic instant microsecond clicks).
 */
class TouchController(
    private val hierarchyAnalyzer: UIHierarchyAnalyzer = UIHierarchyAnalyzer()
) {

    companion object {
        private const val TAG = "KavyaTouchController"
        private const val NATURAL_PRE_DELAY_MS = 40L
        private const val TAP_STROKE_DURATION_MS = 80L
        private const val LONG_PRESS_DURATION_MS = 600L
    }

    /**
     * Executes a human-like tap on a target with strict precision control.
     * Prevents random taps by:
     * 1. Honoring valid visual coordinates directly with interactive node locking.
     * 2. Rejecting giant/offscreen container taps.
     * 3. Failing safely when a target cannot be confirmed rather than tapping random areas.
     */
    suspend fun performTap(
        target: String,
        normalizedCoordinates: NormalizedCoordinates?,
        screenWidth: Int,
        screenHeight: Int
    ): Pair<Boolean, String> {
        val service = KavyaAccessibilityService.instance
            ?: return Pair(false, "Accessibility service not connected")

        val root = service.rootInActiveWindow

        // 1. Precise Coordinate-Driven Tap (Priority when valid visual coordinates are provided)
        if (normalizedCoordinates != null && normalizedCoordinates.isValid()) {
            val deviceCoords = normalizedCoordinates.toDeviceCoordinates(screenWidth, screenHeight)
            val tapX = deviceCoords.x.coerceIn(1f, (screenWidth - 1).coerceAtLeast(1).toFloat())
            val tapY = deviceCoords.y.coerceIn(1f, (screenHeight - 1).coerceAtLeast(1).toFloat())

            // Check if there is an interactive accessibility node right under this touch point
            val (nodeAtCoords, rawNodeAtCoords) = hierarchyAnalyzer.findInteractiveNodeAt(tapX, tapY, root)
            if (rawNodeAtCoords != null && nodeAtCoords != null) {
                delay(NATURAL_PRE_DELAY_MS)
                val clicked = service.clickNode(rawNodeAtCoords)
                if (clicked) {
                    return Pair(true, "Tapped '$target' via interactive element at (${tapX.toInt()}, ${tapY.toInt()})")
                }
                // Fallback to center of the locked interactive element
                val cx = nodeAtCoords.bounds.centerX().toFloat().coerceIn(1f, (screenWidth - 1).toFloat())
                val cy = nodeAtCoords.bounds.centerY().toFloat().coerceIn(1f, (screenHeight - 1).toFloat())
                val tapped = dispatchTapGesture(service, cx, cy, TAP_STROKE_DURATION_MS)
                if (tapped) {
                    return Pair(true, "Tapped '$target' at element center (${cx.toInt()}, ${cy.toInt()})")
                }
            }

            // Direct precision gesture tap at exact visual coordinates
            delay(NATURAL_PRE_DELAY_MS)
            val tapped = dispatchTapGesture(service, tapX, tapY, TAP_STROKE_DURATION_MS)
            if (tapped) {
                return Pair(true, "Tapped '$target' at visual coordinates (${tapX.toInt()}, ${tapY.toInt()})")
            }
        }

        // 2. High-Precision Scored Hierarchy Target Match
        val (nodeInfo, rawNode) = hierarchyAnalyzer.findMatchingNode(target, root)

        if (rawNode != null && nodeInfo != null) {
            val isGiant = nodeInfo.bounds.width() > screenWidth * 0.85 && nodeInfo.bounds.height() > screenHeight * 0.75
            if (!isGiant && nodeInfo.bounds.width() > 0 && nodeInfo.bounds.height() > 0) {
                delay(NATURAL_PRE_DELAY_MS)

                // Try semantic node click first
                val clicked = service.clickNode(rawNode)
                if (clicked) {
                    return Pair(true, "Tapped '$target' via accessibility semantics")
                }

                // Fallback to center-point tap of verified element
                val cx = nodeInfo.bounds.centerX().toFloat().coerceIn(1f, (screenWidth - 1).coerceAtLeast(1).toFloat())
                val cy = nodeInfo.bounds.centerY().toFloat().coerceIn(1f, (screenHeight - 1).coerceAtLeast(1).toFloat())
                val tapped = dispatchTapGesture(service, cx, cy, TAP_STROKE_DURATION_MS)
                if (tapped) {
                    return Pair(true, "Tapped '$target' at element center (${cx.toInt()}, ${cy.toInt()})")
                }
            }
        }

        // 3. Fallback: only if target is a short clean label, attempt service clickNodeByText
        if (target.isNotBlank() && target.length in 2..35 && !target.contains("\n")) {
            val textClicked = service.clickNodeByText(target)
            if (textClicked) {
                return Pair(true, "Tapped '$target' via text matcher fallback")
            }
        }

        // 4. Never tap random screen coordinates! Fail safely.
        return Pair(false, "Could not locate target '$target' precisely on screen")
    }

    /**
     * Executes a human-like long press on a target with strict precision.
     */
    suspend fun performLongPress(
        target: String,
        normalizedCoordinates: NormalizedCoordinates?,
        screenWidth: Int,
        screenHeight: Int
    ): Pair<Boolean, String> {
        val service = KavyaAccessibilityService.instance
            ?: return Pair(false, "Accessibility service not connected")

        val root = service.rootInActiveWindow

        // 1. Precise Coordinate-Driven Long Press
        if (normalizedCoordinates != null && normalizedCoordinates.isValid()) {
            val deviceCoords = normalizedCoordinates.toDeviceCoordinates(screenWidth, screenHeight)
            val tapX = deviceCoords.x.coerceIn(1f, (screenWidth - 1).coerceAtLeast(1).toFloat())
            val tapY = deviceCoords.y.coerceIn(1f, (screenHeight - 1).coerceAtLeast(1).toFloat())

            val (nodeAtCoords, rawNodeAtCoords) = hierarchyAnalyzer.findInteractiveNodeAt(tapX, tapY, root)
            if (rawNodeAtCoords != null && nodeAtCoords != null) {
                delay(NATURAL_PRE_DELAY_MS)
                val longClicked = service.longClickNode(rawNodeAtCoords)
                if (longClicked) {
                    return Pair(true, "Long-pressed '$target' via element at (${tapX.toInt()}, ${tapY.toInt()})")
                }
                val cx = nodeAtCoords.bounds.centerX().toFloat().coerceIn(1f, (screenWidth - 1).toFloat())
                val cy = nodeAtCoords.bounds.centerY().toFloat().coerceIn(1f, (screenHeight - 1).toFloat())
                val pressed = dispatchTapGesture(service, cx, cy, LONG_PRESS_DURATION_MS)
                if (pressed) {
                    return Pair(true, "Long-pressed '$target' at element center (${cx.toInt()}, ${cy.toInt()})")
                }
            }

            delay(NATURAL_PRE_DELAY_MS)
            val pressed = dispatchTapGesture(service, tapX, tapY, LONG_PRESS_DURATION_MS)
            if (pressed) {
                return Pair(true, "Long-pressed '$target' at visual coords (${tapX.toInt()}, ${tapY.toInt()})")
            }
        }

        // 2. High-Precision Scored Hierarchy Target Match
        val (nodeInfo, rawNode) = hierarchyAnalyzer.findMatchingNode(target, root)

        if (rawNode != null && nodeInfo != null) {
            val isGiant = nodeInfo.bounds.width() > screenWidth * 0.85 && nodeInfo.bounds.height() > screenHeight * 0.75
            if (!isGiant && nodeInfo.bounds.width() > 0 && nodeInfo.bounds.height() > 0) {
                delay(NATURAL_PRE_DELAY_MS)
                val longClicked = service.longClickNode(rawNode)
                if (longClicked) {
                    return Pair(true, "Long-pressed '$target' via accessibility semantics")
                }

                val cx = nodeInfo.bounds.centerX().toFloat().coerceIn(1f, (screenWidth - 1).coerceAtLeast(1).toFloat())
                val cy = nodeInfo.bounds.centerY().toFloat().coerceIn(1f, (screenHeight - 1).coerceAtLeast(1).toFloat())
                val pressed = dispatchTapGesture(service, cx, cy, LONG_PRESS_DURATION_MS)
                if (pressed) {
                    return Pair(true, "Long-pressed '$target' at (${cx.toInt()}, ${cy.toInt()})")
                }
            }
        }

        return Pair(false, "Could not perform long press on '$target' precisely")
    }

    private fun dispatchTapGesture(
        service: KavyaAccessibilityService,
        x: Float,
        y: Float,
        durationMs: Long
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x, y)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return service.dispatchGesture(gesture, null, null)
        }
        return service.clickByCoordinates(x, y)
    }
}
