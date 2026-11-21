package com.example.ai.offline

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.example.utils.AppPreferences

enum class VisionTaskType {
    FAST_CLASSIFY,
    UI_DETECTION,
    SCREEN_OCR,
    REGION_SEGMENTATION,
    DEEP_SCREEN_EXPLANATION
}

data class DeviceResourceProfile(
    val availableRamMb: Long,
    val totalRamMb: Long,
    val batteryPercent: Int,
    val isPowerSaveMode: Boolean,
    val isThermalThrottled: Boolean,
    val isOnline: Boolean,
    val hasCloudAiKey: Boolean
)

data class VisionRouteDecision(
    val taskType: VisionTaskType,
    val selectedOnDeviceModelIds: List<String>,
    val useAdvancedLocalVlm: Boolean,
    val useCloudVisionFallback: Boolean,
    val maxConcurrentModels: Int,
    val rationale: String
)

data class ModelHealthSnapshot(
    val totalLightweightModels: Int,
    val readyLightweightCount: Int,
    val readyAdvancedCount: Int,
    val downloadingCount: Int,
    val errorCount: Int,
    val loadedSessionIds: List<String>,
    val installedTotalMb: Long,
    val deviceProfile: DeviceResourceProfile,
    val errorSummaries: List<Pair<String, String>>,
    val excludedCandidateCount: Int
)

/**
 * Memory-, battery-, thermal-, and connectivity-aware vision model router & health inspector.
 * Never schedules all vision models simultaneously: enforces at most 2 lightweight ONNX models
 * on standard memory, 1 on low memory/power-save, and automatic online/offline fallback.
 */
object SmartVisionRouter {

    private const val MIN_RAM_MB_SINGLE_LIGHTWEIGHT = 450L
    private const val MIN_RAM_MB_DUAL_LIGHTWEIGHT = 900L
    private const val MIN_RAM_MB_ADVANCED_VLM = 2600L

    fun inspectDeviceProfile(context: Context): DeviceResourceProfile {
        val appCtx = context.applicationContext
        val (availMb, totalMb) = try {
            val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo()
            if (am != null) {
                am.getMemoryInfo(mi)
                (mi.availMem / (1024L * 1024L)) to (mi.totalMem / (1024L * 1024L))
            } else {
                2048L to 4096L
            }
        } catch (_: Throwable) {
            2048L to 4096L
        }

        val batteryPct = try {
            val intent = appCtx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level >= 0 && scale > 0) (level * 100) / scale else 100
        } catch (_: Throwable) {
            100
        }

        val pm = runCatching { appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager }.getOrNull()
        val isPowerSave = runCatching { pm?.isPowerSaveMode == true }.getOrDefault(false)
        val isThermalThrottled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val status = pm?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
                status >= PowerManager.THERMAL_STATUS_SEVERE
            }.getOrDefault(false)
        } else {
            false
        }

        val isOnline = try {
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork
            val caps = if (net != null) cm.getNetworkCapabilities(net) else null
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (_: Throwable) {
            true
        }

        val hasCloudKey = runCatching {
            AppPreferences.getEffectiveApiKey(appCtx).isNotBlank() ||
                AppPreferences.getActiveApiKey(appCtx).isNotBlank() ||
                AppPreferences.getOpenRouterApiKey(appCtx).isNotBlank() ||
                AppPreferences.getApiKeyForProvider(appCtx, "OPENAI").isNotBlank() ||
                AppPreferences.getApiKeyForProvider(appCtx, "ANTHROPIC").isNotBlank() ||
                AppPreferences.getApiKeyForProvider(appCtx, "GROQ").isNotBlank()
        }.getOrDefault(false)

        return DeviceResourceProfile(
            availableRamMb = availMb,
            totalRamMb = totalMb,
            batteryPercent = batteryPct,
            isPowerSaveMode = isPowerSave,
            isThermalThrottled = isThermalThrottled,
            isOnline = isOnline,
            hasCloudAiKey = hasCloudKey
        )
    }

    /**
     * Pure deterministic routing function (unit-testable with any device profile and ready model set).
     */
    fun route(
        taskType: VisionTaskType,
        readyModelIds: Set<String>,
        profile: DeviceResourceProfile
    ): VisionRouteDecision {
        val canCloud = profile.isOnline && profile.hasCloudAiKey

        if (profile.isThermalThrottled || profile.availableRamMb < MIN_RAM_MB_SINGLE_LIGHTWEIGHT) {
            return VisionRouteDecision(
                taskType = taskType,
                selectedOnDeviceModelIds = emptyList(),
                useAdvancedLocalVlm = false,
                useCloudVisionFallback = canCloud,
                maxConcurrentModels = 0,
                rationale = when {
                    profile.isThermalThrottled ->
                        "Thermal throttling active; skipping local ONNX load and routing to ${if (canCloud) "cloud vision" else "accessibility/OCR fallback"}."
                    else ->
                        "Low available RAM (${profile.availableRamMb} MB); keeping local models unloaded and using ${if (canCloud) "cloud vision" else "lightweight OCR"}."
                }
            )
        }

        val lowPower = profile.isPowerSaveMode || profile.batteryPercent in 1..19
        val maxLightModels = if (lowPower || profile.availableRamMb < MIN_RAM_MB_DUAL_LIGHTWEIGHT) 1 else 2

        val preferredOrder: List<String> = when (taskType) {
            VisionTaskType.FAST_CLASSIFY -> listOf(
                "shufflenet-v2",
                "mobilenet-v3-small",
                "mnasnet-05",
                "mobilenet-v2",
                "efficientnet-b0",
                "mobilenet-v3-large",
                "googlenet"
            )
            VisionTaskType.UI_DETECTION -> listOf(
                "yolov8n-gui-detect",
                "yolo-v7-tiny-detect",
                "mobilenet-v3-small",
                "efficientnet-b0"
            )
            VisionTaskType.SCREEN_OCR -> listOf(
                "paddleocr-v6-det",
                "paddleocr-v6-rec"
            )
            VisionTaskType.REGION_SEGMENTATION -> listOf(
                "deeplabv3-plus-mobilenet",
                "yolov8n-gui-detect",
                "efficientnet-b0"
            )
            VisionTaskType.DEEP_SCREEN_EXPLANATION -> listOf(
                "yolov8n-gui-detect",
                "paddleocr-v6-det",
                "efficientnet-b0",
                "mobilenet-v3-small",
                "deeplabv3-plus-mobilenet",
                "yolo-v7-tiny-detect",
                "mobilenet-v3-large"
            )
        }

        val selectedLight = preferredOrder
            .filter { it in readyModelIds }
            .take(maxLightModels)

        val smolReady = GemmaModelManager.SMOLVLM2_MANIFEST.modelId in readyModelIds
        val florenceReady = GemmaModelManager.FLORENCE2_MANIFEST.modelId in readyModelIds
        val allowHeavyLocal = taskType == VisionTaskType.DEEP_SCREEN_EXPLANATION &&
            !lowPower &&
            profile.availableRamMb >= MIN_RAM_MB_ADVANCED_VLM &&
            (smolReady || florenceReady) &&
            !canCloud // Prefer cloud vision when online to save battery/RAM, switch to heavy local VLM when offline

        val useCloud = when {
            taskType == VisionTaskType.DEEP_SCREEN_EXPLANATION -> canCloud
            selectedLight.isEmpty() -> canCloud
            else -> false
        }

        val rationale = buildString {
            if (selectedLight.isNotEmpty()) {
                append("On-device ONNX: ${selectedLight.joinToString(", ")}")
            } else {
                append("No matching local ONNX model installed")
            }
            if (allowHeavyLocal) append(" + offline advanced VLM")
            if (useCloud) append(" + cloud Vision LLM fusion")
            if (lowPower) append(" (battery-saver cap: 1 model)")
        }

        return VisionRouteDecision(
            taskType = taskType,
            selectedOnDeviceModelIds = selectedLight,
            useAdvancedLocalVlm = allowHeavyLocal,
            useCloudVisionFallback = useCloud,
            maxConcurrentModels = maxLightModels,
            rationale = rationale
        )
    }

    fun routeForContext(context: Context, taskType: VisionTaskType): VisionRouteDecision {
        val coordinator = ModelDownloadCoordinator.getInstance(context)
        val readyIds = coordinator.states.value
            .filterValues { it.state == ModelLifecycleState.READY }
            .keys
        return route(taskType, readyIds, inspectDeviceProfile(context))
    }

    fun inspectModelHealth(context: Context): ModelHealthSnapshot {
        val coordinator = ModelDownloadCoordinator.getInstance(context)
        val orchestrator = OnDeviceVisionOrchestrator.getInstance(context)
        val states = coordinator.states.value
        val lightIds = ModelCatalog.LIGHTWEIGHT_VISION_DESCRIPTORS.map { it.manifest.modelId }.toSet()
        val advIds = ModelCatalog.getAdvancedVisionDescriptors().map { it.manifest.modelId }.toSet()

        val readyLight = lightIds.count { states[it]?.state == ModelLifecycleState.READY }
        val readyAdv = advIds.count { states[it]?.state == ModelLifecycleState.READY }
        val downloading = states.values.count {
            it.state == ModelLifecycleState.DOWNLOADING ||
                it.state == ModelLifecycleState.RESUMING ||
                it.state == ModelLifecycleState.DOWNLOAD_QUEUED ||
                it.state == ModelLifecycleState.VERIFYING
        }
        val errors = states.values
            .filter { it.state == ModelLifecycleState.ERROR }
            .map { st ->
                val name = ModelCatalog.findById(st.modelId)?.displayName ?: st.modelId
                name to (st.errorMessage ?: st.statusMessage)
            }

        return ModelHealthSnapshot(
            totalLightweightModels = ModelCatalog.LIGHTWEIGHT_VISION_DESCRIPTORS.size,
            readyLightweightCount = readyLight,
            readyAdvancedCount = readyAdv,
            downloadingCount = downloading,
            errorCount = errors.size,
            loadedSessionIds = orchestrator.getLoadedSessionIds(),
            installedTotalMb = coordinator.calculateInstalledTotalBytes() / (1024L * 1024L),
            deviceProfile = inspectDeviceProfile(context),
            errorSummaries = errors,
            excludedCandidateCount = ModelCatalog.EXCLUDED_MODELS.size
        )
    }
}
