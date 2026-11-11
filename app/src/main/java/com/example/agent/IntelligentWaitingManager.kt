package com.example.agent

import android.content.Context
import android.os.Environment
import android.util.Log
import com.example.services.KavyaAccessibilityService
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

/**
 * Intelligent Waiting and Time Management Engine (Section 7).
 *
 * Guarantees that actions requiring variable processing time (opening heavy apps,
 * loading web pages, searching, AI image generation, file downloading, uploading,
 * and media playback) do not rely on fixed sleep delays.
 *
 * Continuously monitors UI indicators, accessibility hierarchy, filesystem status,
 * and media states until the expected condition is fulfilled, or a reasonable
 * timeout is reached.
 */
class IntelligentWaitingManager(
    private val context: Context? = null,
    private val serviceProvider: () -> KavyaAccessibilityService? = { KavyaAccessibilityService.instance }
) {

    companion object {
        private const val TAG = "IntelligentWaitingMgr"

        val LOADING_INDICATOR_KEYWORDS = listOf(
            "loading", "buffering", "generating", "generating image",
            "creating", "downloading", "please wait", "processing",
            "searching", "connecting", "uploading", "saving",
            "लोड हो रहा है", "इंतज़ार करें", "बनाया जा रहा है", "डाउनलोड हो रहा है"
        )
    }

    data class WaitResult(
        val satisfied: Boolean,
        val elapsedMs: Long,
        val reason: String,
        val detectedData: String? = null
    )

    /**
     * Waits adaptively for an operation to complete based on the specified [WaitConditionType].
     */
    suspend fun waitForCondition(
        condition: WaitConditionType,
        targetAppOrPkg: String = "",
        expectedIdentifier: String = "",
        timeoutMs: Long = 15000L,
        pollIntervalMs: Long = 500L,
        onProgress: ((String) -> Unit)? = null
    ): WaitResult {
        val startTime = System.currentTimeMillis()
        var consecutiveStableChecks = 0

        Log.d(TAG, "Entering WAITING state for condition: $condition (timeout: ${timeoutMs}ms)")
        onProgress?.invoke("Waiting for $condition...")

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val elapsed = System.currentTimeMillis() - startTime
            val freshScreen = FreshScreenRecognizer.inspectFreshScreenNow()

            when (condition) {
                WaitConditionType.IMAGE_GENERATED -> {
                    // 1. Detect if loading/generating spinners are still active
                    val stillGenerating = hasActiveLoadingIndicator(freshScreen)
                    // 2. Check if final image container or download button has appeared
                    val hasDownloadAction = freshScreen.nodes.any { node ->
                        val text = "${node.text} ${node.contentDescription} ${node.resourceId}".lowercase(Locale.ROOT)
                        (text.contains("download") || text.contains("save") || text.contains("डाउनलोड") ||
                         text.contains("image") || text.contains("share")) &&
                        (node.isClickable || node.className.contains("button", ignoreCase = true) || node.className.contains("image", ignoreCase = true))
                    }
                    val hasGeneratedImage = freshScreen.nodes.any { node ->
                        val b = node.bounds
                        // High-res visual image container typically spans significant width & height
                        b.width() > 300 && b.height() > 300 && !node.isAd && (node.className.contains("Image", ignoreCase = true) || node.className.contains("View", ignoreCase = true))
                    }

                    if (!stillGenerating && (hasDownloadAction || hasGeneratedImage)) {
                        consecutiveStableChecks++
                        if (consecutiveStableChecks >= 2) {
                            Log.i(TAG, "Image generation verified complete in ${elapsed}ms")
                            return WaitResult(true, elapsed, "Image generated and ready")
                        }
                    } else {
                        consecutiveStableChecks = 0
                        if (elapsed > 4000L && elapsed % 3000L < pollIntervalMs) {
                            onProgress?.invoke("Image generation in progress (${elapsed / 1000}s)...")
                        }
                    }
                }

                WaitConditionType.FILE_DOWNLOADED -> {
                    // Check Downloads directory or app private files
                    val downloadedFile = findDownloadedFile(expectedIdentifier)
                    if (downloadedFile != null && downloadedFile.exists() && downloadedFile.length() > 0) {
                        // Confirm file size is stable (not still being written)
                        val initialSize = downloadedFile.length()
                        delay(300)
                        if (downloadedFile.length() == initialSize) {
                            Log.i(TAG, "File download verified: ${downloadedFile.name} (${downloadedFile.length()} bytes)")
                            return WaitResult(true, elapsed, "File downloaded successfully", downloadedFile.absolutePath)
                        }
                    }
                    // Also check screen for download completed toast or notification
                    val downloadFinishedOnScreen = freshScreen.visibleTexts.any {
                        it.contains("download complete", ignoreCase = true) ||
                        it.contains("downloaded", ignoreCase = true) ||
                        it.contains("file saved", ignoreCase = true)
                    }
                    if (downloadFinishedOnScreen) {
                        return WaitResult(true, elapsed, "Download confirmed on screen")
                    }
                }

                WaitConditionType.PAGE_LOADED -> {
                    val isLoading = freshScreen.isLoading || hasActiveLoadingIndicator(freshScreen)
                    val hasContent = freshScreen.visibleTexts.size >= 3
                    if (!isLoading && hasContent) {
                        consecutiveStableChecks++
                        if (consecutiveStableChecks >= 2) {
                            return WaitResult(true, elapsed, "Web page loaded and content verified")
                        }
                    } else {
                        consecutiveStableChecks = 0
                    }
                }

                WaitConditionType.ELEMENT_VISIBLE -> {
                    val query = expectedIdentifier.lowercase(Locale.ROOT)
                    val elementFound = freshScreen.nodes.any { node ->
                        node.text.lowercase(Locale.ROOT).contains(query) ||
                        node.contentDescription.lowercase(Locale.ROOT).contains(query) ||
                        node.resourceId.lowercase(Locale.ROOT).contains(query)
                    }
                    if (elementFound) {
                        return WaitResult(true, elapsed, "Target element '$expectedIdentifier' became visible")
                    }
                }

                WaitConditionType.ELEMENT_GONE -> {
                    val query = expectedIdentifier.lowercase(Locale.ROOT)
                    val elementFound = freshScreen.nodes.any { node ->
                        node.text.lowercase(Locale.ROOT).contains(query) ||
                        node.contentDescription.lowercase(Locale.ROOT).contains(query)
                    }
                    if (!elementFound) {
                        return WaitResult(true, elapsed, "Element '$expectedIdentifier' dismissed")
                    }
                }

                WaitConditionType.MEDIA_PLAYING -> {
                    // Check for Pause button (indicates media is actively playing)
                    val hasPauseButton = freshScreen.nodes.any { node ->
                        val desc = "${node.text} ${node.contentDescription} ${node.resourceId}".lowercase(Locale.ROOT)
                        desc.contains("pause") || desc.contains("रोकें")
                    }
                    // Check for video player progress bar
                    val hasPlayerControls = freshScreen.nodes.any { node ->
                        node.className.contains("ProgressBar", ignoreCase = true) ||
                        node.resourceId.contains("player", ignoreCase = true) ||
                        node.contentDescription.contains("player", ignoreCase = true)
                    }
                    if (hasPauseButton || hasPlayerControls) {
                        return WaitResult(true, elapsed, "Media playback active")
                    }
                }

                WaitConditionType.APP_FOREGROUND -> {
                    val expected = targetAppOrPkg.lowercase(Locale.ROOT)
                    val current = freshScreen.foregroundPackage.lowercase(Locale.ROOT)
                    if (current.contains(expected) || isMatchingPackageName(current, expected)) {
                        return WaitResult(true, elapsed, "App $targetAppOrPkg in foreground")
                    }
                }

                WaitConditionType.UI_STABLE -> {
                    if (!freshScreen.isLoading && freshScreen.nodes.isNotEmpty()) {
                        consecutiveStableChecks++
                        if (consecutiveStableChecks >= 2) {
                            return WaitResult(true, elapsed, "UI stable")
                        }
                    } else {
                        consecutiveStableChecks = 0
                    }
                }

                WaitConditionType.NONE -> {
                    return WaitResult(true, 0L, "No wait condition")
                }
            }

            delay(pollIntervalMs)
        }

        val totalElapsed = System.currentTimeMillis() - startTime
        Log.w(TAG, "Wait condition $condition timed out after ${totalElapsed}ms")
        return WaitResult(false, totalElapsed, "Timed out waiting for $condition")
    }

    private fun hasActiveLoadingIndicator(screen: FreshScreenState): Boolean {
        if (screen.isLoading) return true
        return screen.nodes.any { node ->
            val label = "${node.text} ${node.contentDescription} ${node.resourceId}".lowercase(Locale.ROOT)
            LOADING_INDICATOR_KEYWORDS.any { label.contains(it) } ||
            node.className.contains("ProgressBar", ignoreCase = true) ||
            node.className.contains("ProgressIndicator", ignoreCase = true)
        }
    }

    private fun findDownloadedFile(pattern: String): File? {
        val targetName = pattern.lowercase(Locale.ROOT).trim()
        val dirs = listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            context?.filesDir,
            context?.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        )

        for (dir in dirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val files = dir.listFiles() ?: continue
            // Prefer newest matching file
            val match = files
                .filter { it.isFile && (targetName.isBlank() || it.name.lowercase(Locale.ROOT).contains(targetName)) }
                .maxByOrNull { it.lastModified() }
            if (match != null) return match
        }
        return null
    }

    private fun isMatchingPackageName(current: String, expected: String): Boolean {
        if (expected.isBlank()) return true
        val c = current.lowercase(Locale.ROOT)
        val e = expected.lowercase(Locale.ROOT)
        return when {
            e.contains("youtube") -> c.contains("youtube")
            e.contains("instagram") -> c.contains("instagram")
            e.contains("whatsapp") -> c.contains("whatsapp")
            e.contains("chrome") -> c.contains("chrome")
            e.contains("gmail") -> c.contains("gm") || c.contains("gmail")
            e.contains("gemini") -> c.contains("gemini") || c.contains("google")
            e.contains("notes") || e.contains("keep") -> c.contains("notes") || c.contains("keep")
            else -> c.contains(e)
        }
    }
}
