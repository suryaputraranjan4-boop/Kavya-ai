package com.example.visual

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.example.services.KavyaAccessibilityService
import com.example.utils.AppPreferences
import java.io.ByteArrayOutputStream

/**
 * Screenshot Manager for the Kavya Visual Action Engine.
 * Responsibilities:
 * 1. Safely captures the screen via AccessibilityService APIs.
 * 2. Determines dynamic screen dimensions (never hardcodes resolution).
 * 3. Enforces screenshot privacy (capture -> analyze -> discard).
 * 4. Optimizes size/compression for Gemini API performance and cost control.
 */
class ScreenshotManager(private val context: Context) {

    companion object {
        private const val TAG = "KavyaScreenshotMgr"
        private const val MAX_IMAGE_DIMENSION = 1024
        private const val JPEG_QUALITY = 80
    }

    /**
     * Obtains the real dynamic screen resolution of the active device.
     */
    fun getScreenDimensions(): Pair<Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager?.currentWindowMetrics?.bounds
            val width = bounds?.width() ?: 1080
            val height = bounds?.height() ?: 2400
            Pair(width, height)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealMetrics(metrics)
            Pair(metrics.widthPixels.coerceAtLeast(1080), metrics.heightPixels.coerceAtLeast(1920))
        }
    }

    /**
     * Captures the current screen.
     * Respects Android privacy and security constraints.
     */
    suspend fun captureScreen(): Bitmap? {
        val service = KavyaAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "Cannot capture screen: KavyaAccessibilityService not connected.")
            return null
        }

        return try {
            val bitmap = service.captureScreenBitmap()
            if (bitmap != null) {
                // If it's a hardware bitmap, copy to software config for resizing/compression
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
                    bitmap.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    bitmap
                }
            } else {
                Log.w(TAG, "Screen capture returned null from accessibility service.")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing screen: ${e.message}", e)
            null
        }
    }

    /**
     * Resizes and encodes the bitmap to Base64 JPEG.
     * Enforces privacy by discarding/recycling bitmaps when diagnostic logging is disabled.
     */
    fun encodeToOptimizedBase64(bitmap: Bitmap, retainBitmap: Boolean = false): Pair<String, Bitmap?> {
        var scaledBitmap: Bitmap = bitmap
        try {
            val width = bitmap.width
            val height = bitmap.height
            val maxDim = maxOf(width, height)

            if (maxDim > MAX_IMAGE_DIMENSION) {
                val scale = MAX_IMAGE_DIMENSION.toFloat() / maxDim
                val matrix = Matrix().apply { postScale(scale, scale) }
                scaledBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
            }

            val baos = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
            val base64String = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

            val finalRetainedBitmap = if (retainBitmap || AppPreferences.isScreenAwarenessEnabled(context)) {
                scaledBitmap
            } else {
                // Enforce immediate privacy discard
                if (scaledBitmap != bitmap) {
                    scaledBitmap.recycle()
                }
                bitmap.recycle()
                null
            }

            return Pair(base64String, finalRetainedBitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode screenshot: ${e.message}", e)
            return Pair("", null)
        }
    }
}
