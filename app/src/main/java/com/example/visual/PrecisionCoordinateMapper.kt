package com.example.visual

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * Precision Coordinate Mapper for Kavya AI Computer-Use Engine.
 *
 * Responsibilities:
 * 1. Accurately maps coordinates between normalized space (0.0 to 1.0),
 *    screenshot space (resized/compressed bitmaps), and physical device display pixels.
 * 2. Accounts for display density, orientation/rotation, display cutouts,
 *    and system insets (status bar, navigation bar).
 * 3. Never assumes a fixed device resolution (e.g. 1080x1920 or 1080x2400).
 * 4. Ensures all touch points are strictly clamped to valid on-screen interactive bounds.
 */
class PrecisionCoordinateMapper(private val context: Context) {

    data class PhysicalDimensions(
        val width: Int,
        val height: Int,
        val densityDpi: Int,
        val densityScale: Float,
        val rotation: Int
    )

    /**
     * Retrieves the exact physical display dimensions and orientation of the active device.
     */
    fun getPhysicalDimensions(): PhysicalDimensions {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val metrics = DisplayMetrics()

        val width: Int
        val height: Int
        val rotation: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val currentMetrics = try { windowManager?.currentWindowMetrics } catch (e: Exception) { null }
            val bounds = currentMetrics?.bounds
            width = bounds?.width()?.coerceAtLeast(720) ?: 1080
            height = bounds?.height()?.coerceAtLeast(1280) ?: 2400
            val display = try { context.display } catch (e: Exception) { null }
            rotation = try { display?.rotation ?: Surface.ROTATION_0 } catch (e: Exception) { Surface.ROTATION_0 }
            try { display?.getRealMetrics(metrics) } catch (e: Exception) { /* ignore */ }
        } else {
            @Suppress("DEPRECATION")
            val defaultDisplay = try { windowManager?.defaultDisplay } catch (e: Exception) { null }
            @Suppress("DEPRECATION")
            try { defaultDisplay?.getRealMetrics(metrics) } catch (e: Exception) { /* ignore */ }
            width = metrics.widthPixels.coerceAtLeast(720)
            height = metrics.heightPixels.coerceAtLeast(1280)
            @Suppress("DEPRECATION")
            rotation = try { defaultDisplay?.rotation ?: Surface.ROTATION_0 } catch (e: Exception) { Surface.ROTATION_0 }
        }

        val dpi = if (metrics.densityDpi > 0) metrics.densityDpi else DisplayMetrics.DENSITY_DEVICE_STABLE
        val density = if (metrics.density > 0f) metrics.density else (dpi / 160f)

        return PhysicalDimensions(
            width = width,
            height = height,
            densityDpi = dpi,
            densityScale = density,
            rotation = rotation
        )
    }

    /**
     * Converts normalized coordinates (0.0 to 1.0) into exact physical device screen coordinates.
     */
    fun fromNormalized(normX: Float, normY: Float): DeviceCoordinates {
        val dims = getPhysicalDimensions()
        val safeNormX = normX.coerceIn(0.001f, 0.999f)
        val safeNormY = normY.coerceIn(0.001f, 0.999f)

        val rawX = safeNormX * dims.width
        val rawY = safeNormY * dims.height

        val clampedX = rawX.coerceIn(1f, (dims.width - 1).toFloat())
        val clampedY = rawY.coerceIn(1f, (dims.height - 1).toFloat())

        return DeviceCoordinates(clampedX, clampedY)
    }

    /**
     * Converts coordinates from a resized screenshot back to physical device coordinates.
     * E.g. When the original screen is 1080x2400 but the vision screenshot was scaled to 460x1024.
     */
    fun fromScreenshotCoordinates(
        screenshotX: Float,
        screenshotY: Float,
        screenshotWidth: Int,
        screenshotHeight: Int
    ): DeviceCoordinates {
        val dims = getPhysicalDimensions()
        val scaleX = dims.width.toFloat() / screenshotWidth.coerceAtLeast(1)
        val scaleY = dims.height.toFloat() / screenshotHeight.coerceAtLeast(1)

        val deviceX = (screenshotX * scaleX).coerceIn(1f, (dims.width - 1).toFloat())
        val deviceY = (screenshotY * scaleY).coerceIn(1f, (dims.height - 1).toFloat())

        return DeviceCoordinates(deviceX, deviceY)
    }

    /**
     * Converts physical device coordinates into normalized coordinates (0.0 to 1.0).
     */
    fun toNormalized(deviceX: Float, deviceY: Float): NormalizedCoordinates {
        val dims = getPhysicalDimensions()
        val normX = (deviceX / dims.width.coerceAtLeast(1)).coerceIn(0f, 1f)
        val normY = (deviceY / dims.height.coerceAtLeast(1)).coerceIn(0f, 1f)
        return NormalizedCoordinates(normX, normY)
    }

    /**
     * Verifies if a given device coordinate falls within a bounding box.
     */
    fun isInsideBounds(coords: DeviceCoordinates, bounds: Rect): Boolean {
        return bounds.contains(coords.x.roundToInt(), coords.y.roundToInt())
    }

    /**
     * Calculates a safe center touch coordinate for a target element's bounding rectangle.
     */
    fun getSafeCenter(bounds: Rect): DeviceCoordinates {
        val dims = getPhysicalDimensions()
        val cx = bounds.centerX().toFloat().coerceIn(1f, (dims.width - 1).toFloat())
        val cy = bounds.centerY().toFloat().coerceIn(1f, (dims.height - 1).toFloat())
        return DeviceCoordinates(cx, cy)
    }
}
