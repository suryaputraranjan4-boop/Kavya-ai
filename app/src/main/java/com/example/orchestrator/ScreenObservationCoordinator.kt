package com.example.orchestrator

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import com.example.services.KavyaAccessibilityService
import com.example.visual.ScreenshotManager
import com.example.visual.VisionPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

data class ScreenObservationRecord(
    val screenshotId: String,
    val capturedAtMs: Long,
    val ttlMs: Long = AiProtocolConstants.DEFAULT_SCREENSHOT_TTL_MS,
    val displayWidthPx: Int,
    val displayHeightPx: Int,
    val bitmapWidth: Int,
    val bitmapHeight: Int,
    val orientation: Int,
    val densityDpi: Int,
    val insetTop: Int = 0,
    val insetBottom: Int = 0,
    val insetLeft: Int = 0,
    val insetRight: Int = 0,
    val foregroundPackage: String = "",
    val browserUrl: String = "",
    val uiTreeSummary: String = "",
    val imageBytes: ByteArray? = null,
    val source: String = "NONE"
) {
    val hasScreenshotVisualEvidence: Boolean
        get() = imageBytes != null && imageBytes.isNotEmpty()

    val observationModeDescription: String
        get() = if (hasScreenshotVisualEvidence) {
            "SCREENSHOT_AND_UI_HIERARCHY ($source)"
        } else {
            "ACCESSIBILITY_UI_HIERARCHY_ONLY"
        }

    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean {
        return nowMs - capturedAtMs > ttlMs
    }

    fun matchesGeometry(
        currentWidth: Int,
        currentHeight: Int,
        currentOrientation: Int
    ): Boolean {
        if (currentWidth <= 0 || currentHeight <= 0) return false
        return displayWidthPx == currentWidth &&
            displayHeightPx == currentHeight &&
            orientation == currentOrientation
    }

    fun toBase64DataUrl(): String? {
        val bytes = imageBytes ?: return null
        if (bytes.isEmpty()) return null
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:image/jpeg;base64,$encoded"
    }
}

data class DisplayGeometrySnapshot(
    val widthPx: Int,
    val heightPx: Int,
    val orientation: Int,
    val densityDpi: Int,
    val insetTop: Int = 0,
    val insetBottom: Int = 0,
    val insetLeft: Int = 0,
    val insetRight: Int = 0
)

/**
 * Captures ONE fresh screen observation per AI reasoning / verification cycle.
 * Never reuses stale cached frames as current truth after an action, pause, reconnect, or resize.
 */
object ScreenObservationCoordinator {
    private const val TAG = "ScreenObservationCoord"

    @Volatile
    private var testObserverProvider: (suspend (Context) -> ScreenObservationRecord?)? = null

    @Volatile
    private var testGeometryProvider: ((Context) -> DisplayGeometrySnapshot)? = null

    @Volatile
    var latestObservation: ScreenObservationRecord? = null
        private set

    fun setTestProviders(
        observer: (suspend (Context) -> ScreenObservationRecord?)?,
        geometry: ((Context) -> DisplayGeometrySnapshot)? = null
    ) {
        testObserverProvider = observer
        testGeometryProvider = geometry
    }

    fun clearLatestObservation() {
        latestObservation = null
    }

    fun readCurrentDisplayGeometry(context: Context): DisplayGeometrySnapshot {
        testGeometryProvider?.let { return it(context) }

        val resConfig = context.resources.configuration
        val orientation = resConfig.orientation.takeIf {
            it == Configuration.ORIENTATION_PORTRAIT || it == Configuration.ORIENTATION_LANDSCAPE
        } ?: Configuration.ORIENTATION_PORTRAIT
        val densityDpi = resConfig.densityDpi.takeIf { it > 0 } ?: DisplayMetrics.DENSITY_DEFAULT

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val metrics = wm.currentWindowMetrics
                val bounds: Rect = metrics.bounds
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                return DisplayGeometrySnapshot(
                    widthPx = bounds.width().coerceAtLeast(1),
                    heightPx = bounds.height().coerceAtLeast(1),
                    orientation = orientation,
                    densityDpi = densityDpi,
                    insetTop = insets.top,
                    insetBottom = insets.bottom,
                    insetLeft = insets.left,
                    insetRight = insets.right
                )
            } catch (e: Exception) {
                Log.w(TAG, "WindowMetrics lookup failed: ${e.message}")
            }
        }

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm?.defaultDisplay?.getRealMetrics(dm)
        val width = if (dm.widthPixels > 0) dm.widthPixels else context.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = if (dm.heightPixels > 0) dm.heightPixels else context.resources.displayMetrics.heightPixels.coerceAtLeast(1)

        return DisplayGeometrySnapshot(
            widthPx = width,
            heightPx = height,
            orientation = orientation,
            densityDpi = densityDpi
        )
    }

    suspend fun captureFreshObservation(
        context: Context,
        minTimestampMs: Long = System.currentTimeMillis() - 100L
    ): ScreenObservationRecord? = withContext(Dispatchers.IO) {
        testObserverProvider?.let { custom ->
            val obs = custom(context)
            latestObservation = obs
            return@withContext obs
        }

        val geometry = readCurrentDisplayGeometry(context)
        val a11y = KavyaAccessibilityService.instance
        val foregroundPkg = a11y?.getForegroundPackage() ?: KavyaAccessibilityService.currentForegroundPackage
        val browserUrl = try {
            a11y?.extractBrowserUrlAndTitle()?.first.orEmpty()
        } catch (_: Exception) {
            ""
        }
        val uiSummary = try {
            a11y?.getScreenContext().orEmpty().take(3500)
        } catch (_: Exception) {
            ""
        }

        var capturedBitmap: Bitmap? = null
        var source = "ACCESSIBILITY_TREE_ONLY"

        // 1. Prefer live MediaProjection frame only if captured after minTimestampMs
        val pipelineFrame = VisionPipeline.getLatestFrame()
        val pipelineTs = VisionPipeline.perceptionState.value.timestamp
        if (pipelineFrame != null && !pipelineFrame.isRecycled && pipelineTs >= minTimestampMs) {
            capturedBitmap = pipelineFrame
            source = "MEDIA_PROJECTION_FRESH"
        }

        // 2. Otherwise actively capture fresh screenshot via ScreenshotManager / AccessibilityService
        if (capturedBitmap == null) {
            capturedBitmap = try {
                ScreenshotManager(context).captureScreen()
            } catch (e: Exception) {
                Log.w(TAG, "Fresh screenshot capture failed: ${e.message}")
                null
            }
            if (capturedBitmap != null) {
                source = "ACCESSIBILITY_SCREENSHOT"
            }
        }

        val bitmapW = capturedBitmap?.width ?: geometry.widthPx
        val bitmapH = capturedBitmap?.height ?: geometry.heightPx
        val jpegBytes = capturedBitmap?.let { encodeScaledJpeg(it) }
        if (capturedBitmap != null && source == "ACCESSIBILITY_SCREENSHOT" && !capturedBitmap.isRecycled) {
            capturedBitmap.recycle()
        }

        val record = ScreenObservationRecord(
            screenshotId = "scr_${UUID.randomUUID().toString().take(12)}",
            capturedAtMs = System.currentTimeMillis(),
            ttlMs = AiProtocolConstants.DEFAULT_SCREENSHOT_TTL_MS,
            displayWidthPx = geometry.widthPx,
            displayHeightPx = geometry.heightPx,
            bitmapWidth = bitmapW,
            bitmapHeight = bitmapH,
            orientation = geometry.orientation,
            densityDpi = geometry.densityDpi,
            insetTop = geometry.insetTop,
            insetBottom = geometry.insetBottom,
            insetLeft = geometry.insetLeft,
            insetRight = geometry.insetRight,
            foregroundPackage = foregroundPkg,
            browserUrl = browserUrl,
            uiTreeSummary = uiSummary,
            imageBytes = jpegBytes,
            source = source
        )
        latestObservation = record
        AiAuthorityGateway.registerScreenObservation(record)
        record
    }

    private fun encodeScaledJpeg(bitmap: Bitmap): ByteArray? {
        return try {
            val maxDim = 1280
            val largest = maxOf(bitmap.width, bitmap.height)
            val scaled = if (largest > maxDim) {
                val ratio = maxDim.toFloat() / largest.toFloat()
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 78, out)
            if (scaled !== bitmap && !scaled.isRecycled) {
                scaled.recycle()
            }
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }
}
