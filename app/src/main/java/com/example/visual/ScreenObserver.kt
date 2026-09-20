package com.example.visual

import android.content.Context
import android.util.Log
import com.example.services.KavyaAccessibilityService
import kotlinx.coroutines.delay

/**
 * Screen Observer for the Kavya Visual Action Engine.
 * Responsibilities:
 * 1. Event-driven observation (not wasteful continuous polling).
 * 2. Collects current foreground package, accessibility hierarchy, and optional visual capture.
 * 3. Implements non-blocking, adaptive waiting (waitUntilAppReady, waitUntilScreenChanges, waitUntilTargetAppears)
 *    strictly avoiding long fixed sleeps.
 */
class ScreenObserver(
    private val context: Context,
    private val screenshotManager: ScreenshotManager = ScreenshotManager(context),
    private val hierarchyAnalyzer: UIHierarchyAnalyzer = UIHierarchyAnalyzer()
) {

    companion object {
        private const val TAG = "KavyaScreenObserver"
    }

    /**
     * Captures the full screen perception state.
     */
    suspend fun observeScreen(captureVisual: Boolean = false): VisualScreenState {
        val service = KavyaAccessibilityService.instance
        val currentPkg = service?.getForegroundPackage() ?: "unknown"
        val (width, height) = screenshotManager.getScreenDimensions()

        val root = service?.rootInActiveWindow
        val nodes = hierarchyAnalyzer.collectVisibleNodes(root)
        val textSummary = hierarchyAnalyzer.buildHierarchySummary(nodes)

        val screenshotBitmap = if (captureVisual) {
            screenshotManager.captureScreen()
        } else null

        return VisualScreenState(
            timestamp = System.currentTimeMillis(),
            foregroundPackage = currentPkg,
            screenshot = screenshotBitmap,
            hierarchyNodes = nodes,
            visibleTextSummary = textSummary,
            screenWidth = width,
            screenHeight = height
        )
    }

    /**
     * Waits until the requested application is visible in foreground.
     * Replaces long fixed delays with an adaptive check.
     */
    suspend fun waitUntilAppReady(
        expectedPackage: String,
        timeoutMs: Long = 12000L,
        pollIntervalMs: Long = 250L
    ): Boolean {
        val startTime = System.currentTimeMillis()
        val service = KavyaAccessibilityService.instance ?: return false

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val currentPkg = service.getForegroundPackage()
            if (isPackageMatch(currentPkg, expectedPackage)) {
                // Ensure the window has nodes and isn't just a blank launch splash
                val root = service.rootInActiveWindow
                if (root != null && root.childCount > 0) {
                    delay(300) // Brief settle time
                    return true
                }
            }
            delay(pollIntervalMs)
        }
        return false
    }

    /**
     * Waits until the screen hierarchy changes compared to a previous snapshot.
     */
    suspend fun waitUntilScreenChanges(
        previousSummary: String,
        timeoutMs: Long = 6000L,
        pollIntervalMs: Long = 250L
    ): Boolean {
        val startTime = System.currentTimeMillis()
        val service = KavyaAccessibilityService.instance ?: return false

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val root = service.rootInActiveWindow
            val currentNodes = hierarchyAnalyzer.collectVisibleNodes(root)
            val currentSummary = hierarchyAnalyzer.buildHierarchySummary(currentNodes)
            if (currentSummary != previousSummary) {
                delay(200) // Allow UI animation to settle
                return true
            }
            delay(pollIntervalMs)
        }
        return false
    }

    /**
     * Waits until a target label or semantic hint appears in the UI.
     */
    suspend fun waitUntilTargetAppears(
        target: String,
        timeoutMs: Long = 8000L,
        pollIntervalMs: Long = 300L
    ): Boolean {
        val startTime = System.currentTimeMillis()
        val service = KavyaAccessibilityService.instance ?: return false

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val root = service.rootInActiveWindow
            val (nodeInfo, _) = hierarchyAnalyzer.findMatchingNode(target, root)
            if (nodeInfo != null) {
                return true
            }
            delay(pollIntervalMs)
        }
        return false
    }

    private fun isPackageMatch(current: String, expected: String): Boolean {
        val c = current.lowercase()
        val e = expected.lowercase()
        return c == e || c.contains(e) || e.contains(c) ||
                (e.contains("youtube") && c.contains("youtube")) ||
                (e.contains("chrome") && (c.contains("chrome") || c.contains("browser"))) ||
                (e.contains("freefire") && (c.contains("freefire") || c.contains("dts.freefireth")))
    }
}
