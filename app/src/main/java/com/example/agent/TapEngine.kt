package com.example.agent

import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.services.KavyaAccessibilityService
import kotlinx.coroutines.delay

/**
 * Result of a deterministic TapEngine operation.
 */
data class TapResult(
    val success: Boolean,
    val targetLabel: String,
    val verified: Boolean,
    val bounds: Rect? = null,
    val diagnostic: String = ""
)

/**
 * Centralized Tap Engine.
 * Follows the strict mandate:
 * 1. SCREEN INSPECTION
 * 2. TARGET DETECTION
 * 3. TARGET VALIDATION (visible, correct, clickable/focusable, enabled, bounds valid)
 * 4. BOUNDS CALCULATION (center-point within realistic bounding box)
 * 5. TAP (Accessibility ACTION_CLICK with gesture fallback)
 * 6. RESULT VERIFICATION (verify UI changed or target interacted)
 * 7. RETRY / RECOVER (limited retry with re-inspection, never blind taps)
 */
object TapEngine {

    private const val TAG = "KavyaTapEngine"
    private const val MAX_RETRIES = 2

    suspend fun tapElement(
        service: KavyaAccessibilityService?,
        currentPackage: String,
        target: String,
        isSearchAction: Boolean = false
    ): TapResult {
        if (service == null) {
            return TapResult(false, target, false, null, "Accessibility service disconnected")
        }

        var attempt = 0
        while (attempt < MAX_RETRIES) {
            attempt++
            Log.d(TAG, "Executing tapElement for '$target' (Attempt $attempt/$MAX_RETRIES)")

            // Step 1: Screen Inspection
            val root = service.rootInActiveWindow
            val snapshot = ScreenUnderstanding.capture(root, currentPackage)

            // Step 2: Target Detection
            val candidate: UiElement? = if (isSearchAction) {
                snapshot.searchButtons.firstOrNull() ?: ScreenUnderstanding.findElementMatching(snapshot, "search")
            } else {
                ScreenUnderstanding.findElementMatching(snapshot, target)
            }

            if (candidate == null) {
                if (attempt < MAX_RETRIES) {
                    delay(500)
                    continue
                }
                return TapResult(false, target, false, null, "Target '$target' not visible on current screen")
            }

            // Step 3: Target Validation
            if (!candidate.isValidTarget()) {
                if (attempt < MAX_RETRIES) {
                    delay(500)
                    continue
                }
                return TapResult(false, target, false, candidate.bounds, "Target '$target' has invalid/giant bounds: ${candidate.bounds}")
            }

            // Step 4: Perform Action
            val tapped = performNodeClick(service, candidate)
            if (!tapped) {
                if (attempt < MAX_RETRIES) {
                    delay(400)
                    continue
                }
                return TapResult(false, target, false, candidate.bounds, "Failed to perform click on '$target'")
            }

            // Step 5: Post-Action State Verification
            delay(400)
            val postRoot = service.rootInActiveWindow
            val postSnapshot = ScreenUnderstanding.capture(postRoot, currentPackage)
            
            // Check if UI changed or target state altered
            val uiChanged = postSnapshot.visibleTexts != snapshot.visibleTexts || 
                            postSnapshot.allElements.size != snapshot.allElements.size ||
                            postSnapshot.editableFields.any { it.isFocused } ||
                            postSnapshot.foregroundPackage != snapshot.foregroundPackage

            Log.d(TAG, "Tap verification for '$target': uiChanged=$uiChanged")
            if (uiChanged) {
                return TapResult(
                    success = true,
                    targetLabel = candidate.primaryLabel,
                    verified = true,
                    bounds = candidate.bounds,
                    diagnostic = "Tapped '${candidate.primaryLabel}' and verified UI change"
                )
            } else if (attempt < MAX_RETRIES) {
                delay(400)
                continue
            } else {
                return TapResult(
                    success = false,
                    targetLabel = candidate.primaryLabel,
                    verified = false,
                    bounds = candidate.bounds,
                    diagnostic = "Tap dispatched on '${candidate.primaryLabel}', but no UI state change was observed"
                )
            }
        }

        return TapResult(false, target, false, null, "Action timed out for '$target'")
    }

    /**
     * Specifically clicks the verified Send button for messaging apps (WhatsApp, Telegram, etc.).
     */
    suspend fun tapSendButton(service: KavyaAccessibilityService?, currentPackage: String): TapResult {
        if (service == null) {
            return TapResult(false, "Send", false, null, "Accessibility service disconnected")
        }

        var attempt = 0
        while (attempt < MAX_RETRIES) {
            attempt++
            Log.d(TAG, "Attempting to find and click Send button (Attempt $attempt)")

            val root = service.rootInActiveWindow
            val snapshot = ScreenUnderstanding.capture(root, currentPackage)

            val sendBtn = snapshot.sendButtons.firstOrNull() ?: snapshot.buttons.find {
                it.text.equals("Send", ignoreCase = true) || 
                it.contentDescription.equals("Send", ignoreCase = true) ||
                it.contentDescription.equals("भेजें", ignoreCase = true) ||
                it.resourceId.contains("send", ignoreCase = true)
            }

            if (sendBtn == null) {
                if (attempt < MAX_RETRIES) {
                    delay(600)
                    continue
                }
                return TapResult(false, "Send", false, null, "Send button not detected on current screen")
            }

            if (!sendBtn.isValidTarget()) {
                return TapResult(false, "Send", false, sendBtn.bounds, "Send button invalid or disabled")
            }

            val clicked = performNodeClick(service, sendBtn)
            if (!clicked) {
                if (attempt < MAX_RETRIES) {
                    delay(400)
                    continue
                }
                return TapResult(false, "Send", false, sendBtn.bounds, "Failed to click Send button")
            }

            // Verify that message input cleared or message appeared
            delay(450)
            val postRoot = service.rootInActiveWindow
            val postSnapshot = ScreenUnderstanding.capture(postRoot, currentPackage)
            val inputCleared = postSnapshot.editableFields.all { it.text.isBlank() } ||
                    postSnapshot.editableFields.none { it.text == snapshot.editableFields.firstOrNull()?.text }
            val sendButtonTransformed = postSnapshot.sendButtons.isEmpty() ||
                    postSnapshot.allElements.any { it.contentDescription.contains("Voice message", ignoreCase = true) || it.contentDescription.contains("voice", ignoreCase = true) }

            if (inputCleared || sendButtonTransformed || postSnapshot.visibleTexts.size != snapshot.visibleTexts.size) {
                return TapResult(
                    success = true,
                    targetLabel = "Send",
                    verified = true,
                    bounds = sendBtn.bounds,
                    diagnostic = "Send button clicked and message dispatch confirmed"
                )
            } else if (attempt < MAX_RETRIES) {
                delay(400)
                continue
            } else {
                return TapResult(
                    success = false,
                    targetLabel = "Send",
                    verified = false,
                    bounds = sendBtn.bounds,
                    diagnostic = "Send button clicked but message composer text remained uncleared"
                )
            }
        }

        return TapResult(false, "Send", false, null, "Send button interaction failed")
    }

    /**
     * Clicks the first search result (video, link, product card).
     */
    suspend fun tapFirstResult(service: KavyaAccessibilityService?, currentPackage: String): TapResult {
        if (service == null) return TapResult(false, "First Result", false, null, "Accessibility disconnected")

        delay(600)
        val root = service.rootInActiveWindow
        val snapshot = ScreenUnderstanding.capture(root, currentPackage)

        val firstItem = snapshot.listItems.firstOrNull { it.isValidTarget() && it.bounds.top > 120 }
        if (firstItem == null) {
            return TapResult(false, "First Result", false, null, "No selectable result cards found")
        }

        val clicked = performNodeClick(service, firstItem)
        delay(600)
        return TapResult(
            success = clicked,
            targetLabel = firstItem.primaryLabel,
            verified = clicked,
            bounds = firstItem.bounds,
            diagnostic = if (clicked) "Selected first result: '${firstItem.primaryLabel}'" else "Failed to click first result"
        )
    }

    private fun performNodeClick(service: KavyaAccessibilityService, elem: UiElement): Boolean {
        val node = elem.nodeInfo
        if (node != null) {
            // First priority: perform accessibility ACTION_CLICK
            val clicked = service.clickNode(node)
            if (clicked) return true
        }

        // Second priority: gesture tap at center of bounds
        val b = elem.bounds
        if (b.width() > 0 && b.height() > 0) {
            val cx = b.centerX().toFloat()
            val cy = b.centerY().toFloat()
            return service.clickByCoordinates(cx, cy)
        }

        return false
    }
}
