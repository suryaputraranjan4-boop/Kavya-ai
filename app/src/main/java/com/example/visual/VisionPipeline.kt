package com.example.visual

import android.graphics.Bitmap
import android.util.Log
import com.example.agent.FreshScreenRecognizer
import com.example.agent.ScreenFingerprint
import com.example.services.KavyaAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Visual Perception Pipeline for Kavya AI.
 * Connects the live MediaProjection screen capture frames with
 * local accessibility hierarchy and multi-modal intelligence.
 *
 * Adopts Mark-LV architectural insights:
 * 1. Visual change detection / frame hashing (only analyzes when screen actually changes).
 * 2. Downsampling & budget-aware compression (avoids burning quota on every frame).
 * 3. Local-First reasoning: combines Accessibility Tree with visual confirmation before
 *    making any external AI calls.
 */
object VisionPipeline {

    private const val TAG = "KavyaVisionPipeline"

    data class VisualPerceptionState(
        val isPipelineActive: Boolean = false,
        val totalFramesProcessed: Int = 0,
        val lastFrameHash: String = "",
        val hasVisualChanged: Boolean = false,
        val currentPackage: String = "",
        val timestamp: Long = 0L
    )

    private val _perceptionState = MutableStateFlow(VisualPerceptionState())
    val perceptionState: StateFlow<VisualPerceptionState> = _perceptionState.asStateFlow()

    @Volatile
    private var cachedLatestFrame: Bitmap? = null
    private var lastProcessTimestamp = 0L
    private var previousFrameHash = ""
    private var previousPackage = ""
    private var framesProcessedCount = 0
    private val hashSampleLock = Any()
    private val hashSampleBuffer = ByteArray(256)

    /**
     * Ingests a new raw frame from the ScreenCaptureService VirtualDisplay.
     */
    fun onNewFrame(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        val now = System.currentTimeMillis()
        val minIntervalMs = PerformanceProfileManager.snapshot.value.minFrameIntervalMs
        if (now - lastProcessTimestamp < minIntervalMs) {
            return
        }
        lastProcessTimestamp = now

        cachedLatestFrame = bitmap
        framesProcessedCount++

        val frameHash = computeFrameHash(bitmap)
        val hasChanged = frameHash != previousFrameHash
        if (!hasChanged && previousFrameHash.isNotEmpty()) {
            PerformanceProfileManager.recordSkippedUnchangedFrame()
        }
        previousFrameHash = frameHash

        val activePkg = KavyaAccessibilityService.instance?.getForegroundPackage() ?: "unknown"
        if (previousPackage.isNotBlank() && activePkg != "unknown" && activePkg != previousPackage) {
            ScreenAnnotationManager.onForegroundWindowChanged(activePkg)
        }
        previousPackage = activePkg

        _perceptionState.value = VisualPerceptionState(
            isPipelineActive = true,
            totalFramesProcessed = framesProcessedCount,
            lastFrameHash = frameHash,
            hasVisualChanged = hasChanged,
            currentPackage = activePkg,
            timestamp = now
        )
    }

    fun getLatestFrame(): Bitmap? {
        val frame = cachedLatestFrame
        return if (frame != null && !frame.isRecycled) frame else null
    }

    /**
     * Compresses the latest captured frame for Gemini Vision multimodal analysis
     * only when semantic visual reasoning is genuinely required.
     */
    fun getCompressedJpegFrame(
        maxDimension: Int = PerformanceProfileManager.snapshot.value.jpegMaxDimension,
        quality: Int = PerformanceProfileManager.snapshot.value.jpegQuality
    ): ByteArray? {
        val frame = getLatestFrame() ?: return null
        return compressBitmapToJpeg(frame, maxDimension, quality)
    }

    /**
     * Crops a normalized region [0f..1f] from the captured frame (inspired by Circle-to-Share
     * / Circle-to-Search) and compresses it to JPEG without leaking intermediate bitmaps.
     */
    fun cropNormalizedRegionCompressed(
        leftNorm: Float,
        topNorm: Float,
        rightNorm: Float,
        bottomNorm: Float,
        sourceBitmap: Bitmap? = getLatestFrame(),
        maxDimension: Int = PerformanceProfileManager.snapshot.value.jpegMaxDimension,
        quality: Int = PerformanceProfileManager.snapshot.value.jpegQuality
    ): ByteArray? {
        val frame = sourceBitmap ?: return null
        if (frame.isRecycled || frame.width <= 0 || frame.height <= 0) return null

        val normL = minOf(leftNorm, rightNorm).coerceIn(0f, 1f)
        val normR = maxOf(leftNorm, rightNorm).coerceIn(0f, 1f)
        val normT = minOf(topNorm, bottomNorm).coerceIn(0f, 1f)
        val normB = maxOf(topNorm, bottomNorm).coerceIn(0f, 1f)

        val x = (normL * frame.width).toInt().coerceIn(0, (frame.width - 1).coerceAtLeast(0))
        val y = (normT * frame.height).toInt().coerceIn(0, (frame.height - 1).coerceAtLeast(0))
        val rawRight = (normR * frame.width).toInt().coerceIn(x + 1, frame.width)
        val rawBottom = (normB * frame.height).toInt().coerceIn(y + 1, frame.height)
        val cropW = (rawRight - x).coerceAtLeast(minOf(16, frame.width))
            .coerceAtMost(frame.width - x)
        val cropH = (rawBottom - y).coerceAtLeast(minOf(16, frame.height))
            .coerceAtMost(frame.height - y)
        if (cropW <= 0 || cropH <= 0) return null

        var croppedBitmap: Bitmap? = null
        return try {
            croppedBitmap = Bitmap.createBitmap(frame, x, y, cropW, cropH)
            compressBitmapToJpeg(croppedBitmap, maxDimension, quality)
        } catch (e: Exception) {
            Log.e(TAG, "Error cropping region for visual explanation: ${e.message}")
            null
        } finally {
            val crop = croppedBitmap
            if (crop != null && crop !== frame && !crop.isRecycled) {
                crop.recycle()
            }
        }
    }

    private fun compressBitmapToJpeg(
        source: Bitmap,
        maxDimension: Int,
        quality: Int
    ): ByteArray? {
        if (source.isRecycled) return null
        var scaledBitmap: Bitmap? = null
        return try {
            val width = source.width
            val height = source.height
            if (width <= 0 || height <= 0) return null

            val safeMaxDim = maxDimension.coerceAtLeast(64)
            val safeQuality = quality.coerceIn(30, 100)
            val scale = if (width > safeMaxDim || height > safeMaxDim) {
                val max = maxOf(width, height)
                safeMaxDim.toFloat() / max
            } else 1.0f

            val targetBitmap = if (scale < 1.0f) {
                val targetW = (width * scale).toInt().coerceAtLeast(1)
                val targetH = (height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(source, targetW, targetH, true).also {
                    scaledBitmap = it
                }
            } else {
                source
            }

            val stream = ByteArrayOutputStream()
            targetBitmap.compress(Bitmap.CompressFormat.JPEG, safeQuality, stream)
            stream.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Error compressing visual frame: ${e.message}")
            null
        } finally {
            val scaled = scaledBitmap
            if (scaled != null && scaled !== source && !scaled.isRecycled) {
                scaled.recycle()
            }
        }
    }

    /**
     * Produces a unified perception snapshot combining live Accessibility hierarchy
     * and live visual capture state.
     */
    fun getUnifiedPerceptionSnapshot(): UnifiedScreenPerception {
        val a11y = KavyaAccessibilityService.instance
        val freshScreen = FreshScreenRecognizer.inspectFreshScreenNow()
        val fingerprint = ScreenFingerprint.createFrom(freshScreen)
        val hasVisualFrame = getLatestFrame() != null

        return UnifiedScreenPerception(
            packageName = freshScreen.foregroundPackage,
            screenTitle = freshScreen.screenTitle,
            fingerprint = fingerprint,
            visibleTexts = freshScreen.visibleTexts,
            isAccessibilityActive = a11y != null,
            isVisualCaptureActive = hasVisualFrame,
            visualFrameAvailable = hasVisualFrame,
            timestamp = System.currentTimeMillis()
        )
    }

    fun getFrameAgeMs(): Long {
        return if (lastProcessTimestamp > 0L) {
            (System.currentTimeMillis() - lastProcessTimestamp).coerceAtLeast(0L)
        } else {
            0L
        }
    }

    private val analysisInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    fun tryAcquireAnalysisLock(): Boolean = analysisInFlight.compareAndSet(false, true)

    fun releaseAnalysisLock() {
        analysisInFlight.set(false)
    }

    fun invalidateStaleFrameOnResize(width: Int, height: Int) {
        cachedLatestFrame = null
        previousFrameHash = ""
        Log.d(TAG, "Invalidated cached frame after captured content resize to ${width}x${height}")
    }

    fun hasSignificantFrameChange(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled) return false
        val newHash = computeFrameHash(bitmap)
        return newHash.isNotBlank() && newHash != previousFrameHash
    }

    fun clearFrame() {
        reset()
    }

    fun reset() {
        cachedLatestFrame = null
        previousFrameHash = ""
        previousPackage = ""
        framesProcessedCount = 0
        analysisInFlight.set(false)
        _perceptionState.value = VisualPerceptionState(isPipelineActive = false)
    }

    private fun computeFrameHash(bitmap: Bitmap): String {
        return try {
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return ""
            // Sample 16x16 grid into synchronized reusable 256-byte buffer without Bitmap or ByteArray churn
            val w = bitmap.width
            val h = bitmap.height
            val digest = synchronized(hashSampleLock) {
                var idx = 0
                for (y in 0 until 16) {
                    val py = ((y * h) / 16).coerceIn(0, h - 1)
                    for (x in 0 until 16) {
                        val px = ((x * w) / 16).coerceIn(0, w - 1)
                        val color = bitmap.getPixel(px, py)
                        val r = (color shr 16) and 0xFF
                        val g = (color shr 8) and 0xFF
                        val b = color and 0xFF
                        hashSampleBuffer[idx++] = ((r + g + b) / 3).toByte()
                    }
                }
                MessageDigest.getInstance("MD5").digest(hashSampleBuffer)
            }
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            bitmap.generationId.toString()
        }
    }
}

data class UnifiedScreenPerception(
    val packageName: String,
    val screenTitle: String,
    val fingerprint: ScreenFingerprint,
    val visibleTexts: List<String>,
    val isAccessibilityActive: Boolean,
    val isVisualCaptureActive: Boolean,
    val visualFrameAvailable: Boolean,
    val timestamp: Long
)
