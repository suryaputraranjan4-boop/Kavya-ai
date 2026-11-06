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
    private const val MIN_FRAME_INTERVAL_MS = 250L // Cap visual frame processing rate

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
    private var framesProcessedCount = 0

    /**
     * Ingests a new raw frame from the ScreenCaptureService VirtualDisplay.
     */
    @Synchronized
    fun onNewFrame(bitmap: Bitmap) {
        val now = System.currentTimeMillis()
        if (now - lastProcessTimestamp < MIN_FRAME_INTERVAL_MS) {
            // Drop intermediate frames to protect CPU & memory
            return
        }
        lastProcessTimestamp = now

        // Keep an owned snapshot: the capture/share consumer may recycle its bitmap.
        val ownedFrame = try { bitmap.copy(Bitmap.Config.ARGB_8888, false) } catch (e: Exception) {
            Log.w(TAG, "Unable to retain screen frame: ${e.message}")
            return
        }
        cachedLatestFrame?.takeIf { !it.isRecycled }?.recycle()
        cachedLatestFrame = ownedFrame
        framesProcessedCount++

        val frameHash = computeFrameHash(ownedFrame)
        val hasChanged = frameHash != previousFrameHash
        previousFrameHash = frameHash

        val activePkg = KavyaAccessibilityService.instance?.getForegroundPackage() ?: "unknown"

        _perceptionState.value = VisualPerceptionState(
            isPipelineActive = true,
            totalFramesProcessed = framesProcessedCount,
            lastFrameHash = frameHash,
            hasVisualChanged = hasChanged,
            currentPackage = activePkg,
            timestamp = now
        )
    }

    fun getLatestFrame(): Bitmap? = cachedLatestFrame

    /** Returns an independent frame snapshot safe for asynchronous AI analysis. */
    @Synchronized
    fun getLatestFrameCopy(maxAgeMs: Long = 2500L): Bitmap? {
        val frame = cachedLatestFrame ?: return null
        if (frame.isRecycled) return null
        val frameTimestamp = _perceptionState.value.timestamp
        val ageMs = if (frameTimestamp > 0L) System.currentTimeMillis() - frameTimestamp else Long.MAX_VALUE
        if (ageMs > maxAgeMs) {
            Log.w(TAG, "Refusing stale screen frame (age=$ageMs ms)")
            return null
        }
        return try { frame.copy(Bitmap.Config.ARGB_8888, false) } catch (e: Exception) {
            Log.w(TAG, "Unable to copy current screen frame: ${e.message}")
            null
        }
    }

    /**
     * Compresses the latest captured frame for Gemini Vision multimodal analysis
     * only when semantic visual reasoning is genuinely required.
     */
    fun getCompressedJpegFrame(maxDimension: Int = 720, quality: Int = 75): ByteArray? {
        val frame = cachedLatestFrame ?: return null
        return try {
            val width = frame.width
            val height = frame.height
            val scale = if (width > maxDimension || height > maxDimension) {
                val max = maxOf(width, height)
                maxDimension.toFloat() / max
            } else 1.0f

            val scaledBitmap = if (scale < 1.0f) {
                Bitmap.createScaledBitmap(frame, (width * scale).toInt(), (height * scale).toInt(), true)
            } else {
                frame
            }

            try {
                val stream = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                stream.toByteArray()
            } finally {
                if (scaledBitmap !== frame && !scaledBitmap.isRecycled) scaledBitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error compressing visual frame: ${e.message}")
            null
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
        val hasVisualFrame = cachedLatestFrame?.isRecycled == false &&
            System.currentTimeMillis() - _perceptionState.value.timestamp <= 2500L

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

    @Synchronized
    fun reset() {
        cachedLatestFrame?.takeIf { !it.isRecycled }?.recycle()
        cachedLatestFrame = null
        lastProcessTimestamp = 0L
        previousFrameHash = ""
        framesProcessedCount = 0
        _perceptionState.value = VisualPerceptionState(isPipelineActive = false)
    }

    private fun computeFrameHash(bitmap: Bitmap): String {
        return try {
            // Compute quick downsampled hash (16x16)
            val small = Bitmap.createScaledBitmap(bitmap, 16, 16, false)
            try {
                val bytes = ByteArray(256)
                var idx = 0
                for (y in 0 until 16) {
                    for (x in 0 until 16) {
                        val color = small.getPixel(x, y)
                        val r = (color shr 16) and 0xFF
                        val g = (color shr 8) and 0xFF
                        val b = color and 0xFF
                        bytes[idx++] = ((r + g + b) / 3).toByte()
                    }
                }
                val md = MessageDigest.getInstance("MD5")
                val digest = md.digest(bytes)
                digest.joinToString("") { "%02x".format(it) }
            } finally {
                if (small !== bitmap && !small.isRecycled) small.recycle()
            }
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
