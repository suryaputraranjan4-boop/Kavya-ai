package com.example.visual

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import com.example.services.KavyaAccessibilityService
import kotlinx.coroutines.delay

/**
 * Gesture Controller for the Kavya Visual Action Engine.
 * Handles swipe and directional gestures with human-like motion and timing.
 */
class GestureController {

    companion object {
        private const val TAG = "KavyaGestureController"
        private const val NATURAL_PRE_DELAY_MS = 120L
    }

    /**
     * Executes a smooth swipe between normalized coordinates.
     */
    suspend fun performSwipe(
        startXNorm: Float,
        startYNorm: Float,
        endXNorm: Float,
        endYNorm: Float,
        durationMs: Long,
        screenWidth: Int,
        screenHeight: Int
    ): Pair<Boolean, String> {
        val service = KavyaAccessibilityService.instance
            ?: return Pair(false, "Accessibility service not connected")

        val startX = (startXNorm * screenWidth).coerceIn(0f, screenWidth.toFloat())
        val startY = (startYNorm * screenHeight).coerceIn(0f, screenHeight.toFloat())
        val endX = (endXNorm * screenWidth).coerceIn(0f, screenWidth.toFloat())
        val endY = (endYNorm * screenHeight).coerceIn(0f, screenHeight.toFloat())

        delay(NATURAL_PRE_DELAY_MS)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(150L, 1000L))
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val dispatched = service.dispatchGesture(gesture, null, null)
            return if (dispatched) {
                Pair(true, "Swiped from (${startX.toInt()}, ${startY.toInt()}) to (${endX.toInt()}, ${endY.toInt()})")
            } else {
                Pair(false, "Gesture dispatch returned false")
            }
        }

        return Pair(false, "Gestures require Android N or higher")
    }
}
