package com.example.visual

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.example.utils.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ScreenShareQualityMode(
    val id: String,
    val title: String,
    val description: String
) {
    AUTO("AUTO", "Auto", "Adapts to RAM, thermal state & device speed"),
    LOW_END("LOW_END", "Low-End", "480p • ~2 FPS • Lowest RAM & battery"),
    BALANCED("BALANCED", "Balanced", "600p • ~4 FPS • Smooth & efficient"),
    HIGH_QUALITY("HIGH_QUALITY", "High", "720p • ~8 FPS • Sharp text & fast updates");

    companion object {
        fun fromId(id: String?): ScreenShareQualityMode {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO
        }
    }
}

enum class EffectivePerformanceTier(
    val shortLabel: String,
    val maxWidthPx: Int,
    val minFrameIntervalMs: Long,
    val jpegQuality: Int,
    val jpegMaxDimension: Int,
    val bitmapConfigName: String
) {
    LOW_END(
        shortLabel = "LOW",
        maxWidthPx = 480,
        minFrameIntervalMs = 500L,
        jpegQuality = 60,
        jpegMaxDimension = 512,
        bitmapConfigName = "ARGB_8888_480P"
    ),
    BALANCED(
        shortLabel = "BAL",
        maxWidthPx = 600,
        minFrameIntervalMs = 260L,
        jpegQuality = 72,
        jpegMaxDimension = 640,
        bitmapConfigName = "ARGB_8888_600P"
    ),
    HIGH_QUALITY(
        shortLabel = "HIGH",
        maxWidthPx = 720,
        minFrameIntervalMs = 140L,
        jpegQuality = 82,
        jpegMaxDimension = 768,
        bitmapConfigName = "ARGB_8888_720P"
    )
}

data class PerformanceSnapshot(
    val selectedMode: ScreenShareQualityMode = ScreenShareQualityMode.AUTO,
    val effectiveTier: EffectivePerformanceTier = EffectivePerformanceTier.BALANCED,
    val isLowRamDevice: Boolean = false,
    val totalRamMb: Long = 0L,
    val availableRamMb: Long = 0L,
    val isMemoryLow: Boolean = false,
    val isThermalThrottled: Boolean = false,
    val avgFrameLatencyMs: Float = 0f,
    val skippedUnchangedFrames: Long = 0L
) {
    val maxWidthPx: Int get() = effectiveTier.maxWidthPx
    val minFrameIntervalMs: Long get() = effectiveTier.minFrameIntervalMs
    val jpegQuality: Int get() = effectiveTier.jpegQuality
    val jpegMaxDimension: Int get() = effectiveTier.jpegMaxDimension
}

/**
 * Adaptive Screen Share Performance Engine.
 * Dynamically adjusts capture scale, frame interval, JPEG compression, and hash gating
 * for low-end / low-RAM / thermally throttled Android devices.
 */
object PerformanceProfileManager {

    private const val TAG = "PerformanceProfileMgr"
    private const val LOW_RAM_THRESHOLD_MB = 3200L
    private const val CRITICAL_AVAIL_RAM_MB = 420L
    private const val HIGH_LATENCY_STEPDOWN_MS = 85f

    private val _snapshot = MutableStateFlow(PerformanceSnapshot())
    val snapshot: StateFlow<PerformanceSnapshot> = _snapshot.asStateFlow()

    @Volatile
    private var ewmaLatencyMs: Float = 0f

    @Volatile
    private var skippedFramesCount: Long = 0L

    fun loadAndRefresh(context: Context): PerformanceSnapshot {
        val mode = ScreenShareQualityMode.fromId(AppPreferences.getScreenShareQualityMode(context))
        return refresh(context, mode)
    }

    fun setQualityMode(context: Context, mode: ScreenShareQualityMode): PerformanceSnapshot {
        AppPreferences.setScreenShareQualityMode(context, mode.id)
        return refresh(context, mode)
    }

    fun refresh(
        context: Context,
        modeOverride: ScreenShareQualityMode? = null
    ): PerformanceSnapshot {
        val mode = modeOverride ?: ScreenShareQualityMode.fromId(AppPreferences.getScreenShareQualityMode(context))
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        val hasMemInfo = try {
            am?.getMemoryInfo(memInfo)
            am != null
        } catch (_: Exception) {
            false
        }

        val isLowRam = try {
            am?.isLowRamDevice == true
        } catch (_: Exception) {
            false
        }
        val totalRamMb = if (hasMemInfo) memInfo.totalMem / (1024L * 1024L) else 4096L
        val availRamMb = if (hasMemInfo) memInfo.availMem / (1024L * 1024L) else 1536L
        val isMemoryLow = if (hasMemInfo) (memInfo.lowMemory || availRamMb < CRITICAL_AVAIL_RAM_MB) else false

        val isThermalThrottled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val status = pm?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
                status >= PowerManager.THERMAL_STATUS_MODERATE
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }

        val resolvedTier = resolveEffectiveTier(
            mode = mode,
            isLowRamDevice = isLowRam,
            totalRamMb = totalRamMb,
            isMemoryLow = isMemoryLow,
            isThermalThrottled = isThermalThrottled,
            avgLatencyMs = ewmaLatencyMs
        )

        val updated = PerformanceSnapshot(
            selectedMode = mode,
            effectiveTier = resolvedTier,
            isLowRamDevice = isLowRam,
            totalRamMb = totalRamMb,
            availableRamMb = availRamMb,
            isMemoryLow = isMemoryLow,
            isThermalThrottled = isThermalThrottled,
            avgFrameLatencyMs = ewmaLatencyMs,
            skippedUnchangedFrames = skippedFramesCount
        )
        _snapshot.value = updated
        return updated
    }

    /**
     * Pure resolution helper so unit tests can verify every adaptive branch deterministically.
     */
    fun resolveEffectiveTier(
        mode: ScreenShareQualityMode,
        isLowRamDevice: Boolean,
        totalRamMb: Long,
        isMemoryLow: Boolean,
        isThermalThrottled: Boolean,
        avgLatencyMs: Float
    ): EffectivePerformanceTier {
        return when (mode) {
            ScreenShareQualityMode.LOW_END -> EffectivePerformanceTier.LOW_END
            ScreenShareQualityMode.BALANCED -> {
                if (isThermalThrottled || isMemoryLow) EffectivePerformanceTier.LOW_END
                else EffectivePerformanceTier.BALANCED
            }
            ScreenShareQualityMode.HIGH_QUALITY -> {
                when {
                    isLowRamDevice || isMemoryLow || isThermalThrottled -> EffectivePerformanceTier.BALANCED
                    else -> EffectivePerformanceTier.HIGH_QUALITY
                }
            }
            ScreenShareQualityMode.AUTO -> {
                when {
                    isLowRamDevice || totalRamMb in 1..LOW_RAM_THRESHOLD_MB || isMemoryLow || isThermalThrottled ->
                        EffectivePerformanceTier.LOW_END
                    avgLatencyMs >= HIGH_LATENCY_STEPDOWN_MS ->
                        EffectivePerformanceTier.LOW_END
                    avgLatencyMs >= 45f || totalRamMb < 5500L ->
                        EffectivePerformanceTier.BALANCED
                    else ->
                        EffectivePerformanceTier.HIGH_QUALITY
                }
            }
        }
    }

    fun recordFrameProcessingLatencyMs(latencyMs: Long) {
        val sample = latencyMs.coerceAtLeast(0L).toFloat()
        ewmaLatencyMs = if (ewmaLatencyMs <= 0f) sample else (ewmaLatencyMs * 0.75f + sample * 0.25f)
        val cur = _snapshot.value
        val nextTier = resolveEffectiveTier(
            mode = cur.selectedMode,
            isLowRamDevice = cur.isLowRamDevice,
            totalRamMb = cur.totalRamMb,
            isMemoryLow = cur.isMemoryLow,
            isThermalThrottled = cur.isThermalThrottled,
            avgLatencyMs = ewmaLatencyMs
        )
        if (nextTier != cur.effectiveTier) {
            Log.i(TAG, "Adaptive screen share tier shifted ${cur.effectiveTier} -> $nextTier (avgLatency=${ewmaLatencyMs.toInt()}ms)")
        }
        _snapshot.value = cur.copy(
            effectiveTier = nextTier,
            avgFrameLatencyMs = ewmaLatencyMs
        )
    }

    fun recordSkippedUnchangedFrame() {
        skippedFramesCount++
        val cur = _snapshot.value
        _snapshot.value = cur.copy(skippedUnchangedFrames = skippedFramesCount)
    }

    fun resetMetrics() {
        ewmaLatencyMs = 0f
        skippedFramesCount = 0L
        val cur = _snapshot.value
        _snapshot.value = cur.copy(avgFrameLatencyMs = 0f, skippedUnchangedFrames = 0L)
    }
}
