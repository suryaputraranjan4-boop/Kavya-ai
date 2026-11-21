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
        val frameSequenceNumber: Long = 0L,
        val lastFrameHash: String = "",
        val hasVisualChanged: Boolean = false,
        val lastVisualDelta: Float = 0f,
        val currentPackage: String = "",
        val timestamp: Long = 0L
    )

    private const val MEAN_DIFF_CHANGE_THRESHOLD = 4.5f
    const val DEFAULT_FRESH_FRAME_MAX_AGE_MS = 5_000L

    private val _perceptionState = MutableStateFlow(VisualPerceptionState())
    val perceptionState: StateFlow<VisualPerceptionState> = _perceptionState.asStateFlow()

    @Volatile
    private var cachedLatestFrame: Bitmap? = null
    private var lastProcessTimestamp = 0L
    private var previousFrameHash = ""
    private var previousPackage = ""
    private var framesProcessedCount = 0
    private val sequenceCounter = java.util.concurrent.atomic.AtomicLong(0L)
    private val hashSampleLock = Any()
    private val hashSampleBuffer = ByteArray(256)
    private var previousLuminanceGrid: IntArray? = null
    @Volatile
    var onMeaningfulScreenChange: ((sequenceNumber: Long, packageName: String, delta: Float) -> Unit)? = null

    /**
     * Ingests a new raw frame from the ScreenCaptureService VirtualDisplay.
     *
     * @param bypassIntervalThrottle Set true when the caller (e.g. ScreenCaptureService)
     *        has already enforced PerformanceProfileManager.minFrameIntervalMs so frames
     *        are not dropped twice by slightly-skewed timestamps.
     */
    fun onNewFrame(bitmap: Bitmap, bypassIntervalThrottle: Boolean = false) {
        if (bitmap.isRecycled) return
        val now = System.currentTimeMillis()
        if (!bypassIntervalThrottle) {
            val minIntervalMs = PerformanceProfileManager.snapshot.value.minFrameIntervalMs
            if (now - lastProcessTimestamp < minIntervalMs) {
                return
            }
        }
        lastProcessTimestamp = now

        cachedLatestFrame = bitmap
        framesProcessedCount++
        val seq = sequenceCounter.incrementAndGet()

        val (frameHash, meanDelta) = computePerceptualSignatureAndDelta(bitmap)
        val hasChanged = previousFrameHash.isEmpty() || meanDelta >= MEAN_DIFF_CHANGE_THRESHOLD
        if (!hasChanged && previousFrameHash.isNotEmpty()) {
            PerformanceProfileManager.recordSkippedUnchangedFrame()
        } else {
            previousFrameHash = frameHash
        }

        val activePkg = KavyaAccessibilityService.instance?.getForegroundPackage() ?: "unknown"
        val packageChanged = previousPackage.isNotBlank() && activePkg != "unknown" && activePkg != previousPackage
        if (packageChanged) {
            ScreenAnnotationManager.onForegroundWindowChanged(activePkg)
        }
        previousPackage = activePkg

        _perceptionState.value = VisualPerceptionState(
            isPipelineActive = true,
            totalFramesProcessed = framesProcessedCount,
            frameSequenceNumber = seq,
            lastFrameHash = frameHash,
            hasVisualChanged = hasChanged || packageChanged,
            lastVisualDelta = meanDelta,
            currentPackage = activePkg,
            timestamp = now
        )

        if (hasChanged || packageChanged) {
            onMeaningfulScreenChange?.invoke(seq, activePkg, meanDelta)
        }
    }

    fun getLatestFrame(): Bitmap? {
        val frame = cachedLatestFrame
        return if (frame != null && !frame.isRecycled) frame else null
    }

    /**
     * Returns the latest captured frame ONLY if it is non-recycled and younger than [maxAgeMs].
     * Prevents stale or privacy-paused frames from being sent to local or cloud vision models.
     */
    fun getFreshFrame(maxAgeMs: Long = DEFAULT_FRESH_FRAME_MAX_AGE_MS): Bitmap? {
        val frame = getLatestFrame() ?: return null
        val ts = lastProcessTimestamp
        if (ts <= 0L) return null
        val ageMs = (System.currentTimeMillis() - ts).coerceAtLeast(0L)
        if (ageMs > maxAgeMs) {
            Log.w(TAG, "Rejected stale cached frame (age=${ageMs}ms > maxAgeMs=${maxAgeMs}ms)")
            return null
        }
        return frame
    }

    /**
     * Compresses the latest captured frame for visual analysis.
     * Uses [getFreshFrame] when [maxAgeMs] > 0 to avoid sending stale screenshots.
     */
    fun getCompressedJpegFrame(
        maxDimension: Int = PerformanceProfileManager.snapshot.value.jpegMaxDimension,
        quality: Int = PerformanceProfileManager.snapshot.value.jpegQuality,
        maxAgeMs: Long = DEFAULT_FRESH_FRAME_MAX_AGE_MS
    ): ByteArray? {
        val frame = if (maxAgeMs > 0L) getFreshFrame(maxAgeMs) else getLatestFrame()
        frame ?: return null
        return compressBitmapToJpeg(frame, maxDimension, quality)
    }

    fun getFreshCompressedJpegFrame(
        maxAgeMs: Long = DEFAULT_FRESH_FRAME_MAX_AGE_MS,
        maxDimension: Int = PerformanceProfileManager.snapshot.value.jpegMaxDimension,
        quality: Int = PerformanceProfileManager.snapshot.value.jpegQuality
    ): ByteArray? = getCompressedJpegFrame(
        maxDimension = maxDimension,
        quality = quality,
        maxAgeMs = maxAgeMs
    )

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
        synchronized(hashSampleLock) {
            previousLuminanceGrid = null
        }
        Log.d(TAG, "Invalidated cached frame after captured content resize to ${width}x${height}")
    }

    fun hasSignificantFrameChange(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled) return false
        val (_, delta) = peekPerceptualDelta(bitmap)
        return previousFrameHash.isEmpty() || delta >= MEAN_DIFF_CHANGE_THRESHOLD
    }

    fun clearCachedFrameForPrivacy(reason: String) {
        cachedLatestFrame = null
        lastProcessTimestamp = 0L
        previousFrameHash = ""
        synchronized(hashSampleLock) {
            previousLuminanceGrid = null
        }
        Log.i(TAG, "Cleared cached sensitive frame ($reason)")
    }

    fun clearFrame() {
        reset()
    }

    fun reset() {
        cachedLatestFrame = null
        lastProcessTimestamp = 0L
        previousFrameHash = ""
        previousPackage = ""
        framesProcessedCount = 0
        sequenceCounter.set(0L)
        synchronized(hashSampleLock) {
            previousLuminanceGrid = null
        }
        analysisInFlight.set(false)
        _perceptionState.value = VisualPerceptionState(isPipelineActive = false)
    }

    /**
     * Computes a 16x16 downsampled luminance grid and returns (md5Hex, meanAbsoluteDifferenceFromPrevious).
     * Updates the stored baseline grid ONLY when delta >= MEAN_DIFF_CHANGE_THRESHOLD (or on first frame),
     * preventing slow drift from hiding gradual changes while ignoring clock ticks / minor JPEG shimmer.
     */
    private fun computePerceptualSignatureAndDelta(bitmap: Bitmap): Pair<String, Float> {
        return try {
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return "" to 0f
            val w = bitmap.width
            val h = bitmap.height
            synchronized(hashSampleLock) {
                val currentGrid = IntArray(256)
                var idx = 0
                for (y in 0 until 16) {
                    val py = ((y * h) / 16).coerceIn(0, h - 1)
                    for (x in 0 until 16) {
                        val px = ((x * w) / 16).coerceIn(0, w - 1)
                        val color = bitmap.getPixel(px, py)
                        val r = (color shr 16) and 0xFF
                        val g = (color shr 8) and 0xFF
                        val b = color and 0xFF
                        val luma = (r * 77 + g * 150 + b * 29) shr 8
                        currentGrid[idx] = luma
                        hashSampleBuffer[idx] = luma.toByte()
                        idx++
                    }
                }
                val prev = previousLuminanceGrid
                val meanDelta = if (prev == null) {
                    255f
                } else {
                    var sumDiff = 0
                    for (i in 0 until 256) {
                        sumDiff += kotlin.math.abs(currentGrid[i] - prev[i])
                    }
                    sumDiff / 256f
                }
                if (prev == null || meanDelta >= MEAN_DIFF_CHANGE_THRESHOLD) {
                    previousLuminanceGrid = currentGrid
                }
                val digest = MessageDigest.getInstance("MD5").digest(hashSampleBuffer)
                digest.joinToString("") { "%02x".format(it) } to meanDelta
            }
        } catch (e: Exception) {
            bitmap.generationId.toString() to 255f
        }
    }

    private fun peekPerceptualDelta(bitmap: Bitmap): Pair<String, Float> {
        return try {
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return "" to 0f
            val w = bitmap.width
            val h = bitmap.height
            synchronized(hashSampleLock) {
                val prev = previousLuminanceGrid
                var sumDiff = 0
                var idx = 0
                for (y in 0 until 16) {
                    val py = ((y * h) / 16).coerceIn(0, h - 1)
                    for (x in 0 until 16) {
                        val px = ((x * w) / 16).coerceIn(0, w - 1)
                        val color = bitmap.getPixel(px, py)
                        val r = (color shr 16) and 0xFF
                        val g = (color shr 8) and 0xFF
                        val b = color and 0xFF
                        val luma = (r * 77 + g * 150 + b * 29) shr 8
                        hashSampleBuffer[idx] = luma.toByte()
                        if (prev != null) {
                            sumDiff += kotlin.math.abs(luma - prev[idx])
                        }
                        idx++
                    }
                }
                val meanDelta = if (prev == null) 255f else sumDiff / 256f
                val digest = MessageDigest.getInstance("MD5").digest(hashSampleBuffer)
                digest.joinToString("") { "%02x".format(it) } to meanDelta
            }
        } catch (_: Exception) {
            bitmap.generationId.toString() to 255f
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
