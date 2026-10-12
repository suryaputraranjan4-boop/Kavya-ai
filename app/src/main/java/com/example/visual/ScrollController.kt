package com.example.visual

import android.util.Log
import com.example.services.KavyaAccessibilityService

/**
 * Scroll Controller for the Kavya Visual Action Engine.
 * Supports both accessibility node scrolling and gesture scroll fallbacks.
 */
class ScrollController(
    private val gestureController: GestureController = GestureController()
) {

    companion object {
        private const val TAG = "KavyaScrollController"
    }

    suspend fun performScroll(
        direction: String,
        screenWidth: Int,
        screenHeight: Int
    ): Pair<Boolean, String> {
        val service = KavyaAccessibilityService.instance
            ?: return Pair(false, "Accessibility service not connected")

        val isDown = direction.equals("DOWN", true) || direction.equals("FORWARD", true)

        // 1. Accessibility Semantic Scroll
        val semanticScrolled = service.scroll(if (isDown) "FORWARD" else "BACKWARD")
        if (semanticScrolled) {
            return Pair(true, "Scrolled $direction via accessibility semantics")
        }

        // 2. Gesture Scroll Fallback
        val startY = if (isDown) 0.75f else 0.25f
        val endY = if (isDown) 0.25f else 0.75f
        val (gestureSuccess, msg) = gestureController.performSwipe(
            startXNorm = 0.5f,
            startYNorm = startY,
            endXNorm = 0.5f,
            endYNorm = endY,
            durationMs = 450L,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )

        return if (gestureSuccess) {
            Pair(true, "Scrolled $direction via gesture swipe")
        } else {
            Pair(false, "Scroll failed: $msg")
        }
    }
}
