package com.example.agent

import android.util.Log
import com.example.services.KavyaAccessibilityService
import kotlinx.coroutines.delay

/**
 * Centralized Reusable Scroll Engine.
 * Follows the strict mandate:
 * 1. SCAN VISIBLE CHATS / ITEMS
 * 2. MATCH TARGET (if already visible -> select directly, NO unnecessary scrolling)
 * 3. IDENTIFY SCROLLABLE CONTAINER
 * 4. PERFORM CONTROLLED SCROLL
 * 5. RE-SCAN SCREEN
 * 6. STOP IMMEDIATELY WHEN TARGET FOUND OR SAFE RETRY LIMIT REACHED
 */
object ScrollEngine {

    private const val TAG = "KavyaScrollEngine"
    private const val MAX_SCROLL_ATTEMPTS = 5

    suspend fun findAndScrollTo(
        service: KavyaAccessibilityService?,
        currentPackage: String,
        targetQuery: String,
        maxAttempts: Int = MAX_SCROLL_ATTEMPTS
    ): UiElement? {
        if (service == null) return null

        val cleanQuery = targetQuery.trim()
        if (cleanQuery.isEmpty()) return null

        var attempts = 0
        while (attempts < maxAttempts) {
            attempts++
            Log.d(TAG, "Scanning screen for '$cleanQuery' (Pass $attempts/$maxAttempts)")

            // Step 1 & 2: Check if target is already visible on screen
            val root = service.rootInActiveWindow
            val snapshot = ScreenUnderstanding.capture(root, currentPackage)

            val match = ScreenUnderstanding.findElementMatching(snapshot, cleanQuery)
            if (match != null && match.isValidTarget()) {
                Log.d(TAG, "Target '$cleanQuery' found visible at bounds ${match.bounds}. No further scroll required.")
                return match
            }

            if (attempts >= maxAttempts) break

            // Step 3: Find scrollable container or fallback to system scroll
            val scrollContainer = snapshot.scrollableContainers.firstOrNull()
            Log.d(TAG, "Target not yet visible. Scrolling screen downwards...")

            val scrolled = if (scrollContainer?.nodeInfo != null) {
                val node = scrollContainer.nodeInfo
                node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            } else {
                service.scroll("DOWN")
            }

            if (!scrolled) {
                // Fallback to gesture scroll
                service.scroll("DOWN")
            }

            // Allow UI to settle
            delay(700)
        }

        Log.w(TAG, "Target '$cleanQuery' could not be found after $attempts scroll passes.")
        return null
    }
}
