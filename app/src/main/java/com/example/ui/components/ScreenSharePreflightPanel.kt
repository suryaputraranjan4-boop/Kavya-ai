package com.example.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.ai.tts.IndicTinyTtsModelManager
import com.example.ai.tts.ModelDownloadStatus
import com.example.services.KavyaAccessibilityService
import com.example.services.ScreenShareManager
import com.example.ui.theme.*
import com.example.utils.AppPreferences
import com.example.utils.PermissionsManager

enum class PreflightCheckId {
    OVERLAY,
    MICROPHONE,
    NOTIFICATION,
    VOICE_MODEL,
    MEDIA_PROJECTION,
    ACCESSIBILITY,
    USAGE_ACCESS,
    NOTIFICATION_LISTENER
}

enum class PreflightCheckStatus {
    GRANTED,
    MISSING,
    IN_PROGRESS,
    OPTIONAL_GRANTED,
    OPTIONAL_MISSING
}

data class PreflightCheckItem(
    val id: PreflightCheckId,
    val title: String,
    val purpose: String,
    val isRequired: Boolean,
    val status: PreflightCheckStatus,
    val statusLabel: String,
    val actionLabel: String
) {
    val isSatisfied: Boolean
        get() = status == PreflightCheckStatus.GRANTED || status == PreflightCheckStatus.OPTIONAL_GRANTED
}

data class PreflightSnapshot(
    val items: List<PreflightCheckItem>
) {
    val allRequiredChecks: List<PreflightCheckItem> = items.filter { it.isRequired }
    val optionalChecks: List<PreflightCheckItem> = items.filter { !it.isRequired }

    /** The 4 prerequisites that should be ready before prompting MediaProjection. */
    val setupPrerequisiteChecks: List<PreflightCheckItem> =
        allRequiredChecks.filter { it.id != PreflightCheckId.MEDIA_PROJECTION }

    val mediaProjectionCheck: PreflightCheckItem? =
        allRequiredChecks.firstOrNull { it.id == PreflightCheckId.MEDIA_PROJECTION }

    val remainingSetupItems: List<PreflightCheckItem> =
        setupPrerequisiteChecks.filter { it.status != PreflightCheckStatus.GRANTED }

    val remainingAllRequiredItems: List<PreflightCheckItem> =
        allRequiredChecks.filter { it.status != PreflightCheckStatus.GRANTED }

    val readySetupCount: Int = setupPrerequisiteChecks.count { it.status == PreflightCheckStatus.GRANTED }
    val totalSetupCount: Int = setupPrerequisiteChecks.size

    val readyRequiredCount: Int = allRequiredChecks.count { it.status == PreflightCheckStatus.GRANTED }
    val totalRequiredCount: Int = allRequiredChecks.size

    val requiredProgress: Float =
        if (totalRequiredCount == 0) 1f else readyRequiredCount.toFloat() / totalRequiredCount.toFloat()

    val setupProgress: Float =
        if (totalSetupCount == 0) 1f else readySetupCount.toFloat() / totalSetupCount.toFloat()

    val canStartMediaProjection: Boolean = remainingSetupItems.isEmpty()
    val allReadyIncludingProjection: Boolean = remainingAllRequiredItems.isEmpty()
}

object ScreenSharePreflightEvaluator {

    fun inspect(
        context: Context,
        shareState: ScreenShareManager.ScreenShareState,
        modelStatus: ModelDownloadStatus,
        modelReadyOnDisk: Boolean,
        modelProgress: Float = 0f,
        modelProgressText: String = "",
        androidSdkInt: Int = Build.VERSION.SDK_INT
    ): PreflightSnapshot {
        val overlayGranted = PermissionsManager.canDrawOverlays(context)
        val micGranted = PermissionsManager.hasRecordAudioPermission(context)
        val notificationGranted = if (androidSdkInt >= Build.VERSION_CODES.TIRAMISU) {
            AppPreferences.hasNotificationPermission(context)
        } else {
            true
        }
        val voiceModelReady = modelStatus == ModelDownloadStatus.READY || modelReadyOnDisk
        val projectionActive = shareState is ScreenShareManager.ScreenShareState.Active ||
            shareState is ScreenShareManager.ScreenShareState.StaleFrame ||
            shareState is ScreenShareManager.ScreenShareState.Paused ||
            shareState is ScreenShareManager.ScreenShareState.WaitingForFirstFrame ||
            shareState is ScreenShareManager.ScreenShareState.StartingService

        val accessibilityGranted = PermissionsManager.isAccessibilityServiceEnabled(
            context,
            KavyaAccessibilityService::class.java
        )
        val usageAccessGranted = PermissionsManager.hasUsageStatsPermission(context)
        val notifListenerGranted = PermissionsManager.hasNotificationListenerPermission(context)

        return evaluate(
            overlayGranted = overlayGranted,
            micGranted = micGranted,
            notificationGranted = notificationGranted,
            voiceModelReady = voiceModelReady,
            voiceModelStatus = modelStatus,
            voiceModelProgress = modelProgress,
            voiceModelProgressText = modelProgressText,
            mediaProjectionGranted = projectionActive,
            accessibilityGranted = accessibilityGranted,
            usageAccessGranted = usageAccessGranted,
            notificationListenerGranted = notifListenerGranted,
            androidSdkInt = androidSdkInt
        )
    }

    fun evaluate(
        overlayGranted: Boolean,
        micGranted: Boolean,
        notificationGranted: Boolean,
        voiceModelReady: Boolean,
        voiceModelStatus: ModelDownloadStatus = if (voiceModelReady) ModelDownloadStatus.READY else ModelDownloadStatus.NOT_DOWNLOADED,
        voiceModelProgress: Float = if (voiceModelReady) 1f else 0f,
        voiceModelProgressText: String = "",
        mediaProjectionGranted: Boolean,
        accessibilityGranted: Boolean,
        usageAccessGranted: Boolean,
        notificationListenerGranted: Boolean,
        androidSdkInt: Int = Build.VERSION.SDK_INT
    ): PreflightSnapshot {
        val notifGrantedEffective = if (androidSdkInt < Build.VERSION_CODES.TIRAMISU) true else notificationGranted
        val voiceCheckStatus = when {
            voiceModelReady -> PreflightCheckStatus.GRANTED
            voiceModelStatus == ModelDownloadStatus.DOWNLOADING ||
                voiceModelStatus == ModelDownloadStatus.VERIFYING -> PreflightCheckStatus.IN_PROGRESS
            else -> PreflightCheckStatus.MISSING
        }
        val voiceStatusLabel = when {
            voiceModelReady -> "Verified"
            voiceModelStatus == ModelDownloadStatus.DOWNLOADING ->
                "Downloading ${(voiceModelProgress * 100).toInt().coerceIn(0, 99)}%"
            voiceModelStatus == ModelDownloadStatus.VERIFYING -> "Verifying..."
            voiceModelStatus == ModelDownloadStatus.PAUSED -> "Paused"
            voiceModelStatus == ModelDownloadStatus.ERROR -> "Retry needed"
            else -> "Not downloaded"
        }
        val voiceActionLabel = when {
            voiceModelReady -> "Verified"
            voiceModelStatus == ModelDownloadStatus.DOWNLOADING -> "Pause"
            voiceModelStatus == ModelDownloadStatus.VERIFYING -> "Checking..."
            voiceModelStatus == ModelDownloadStatus.PAUSED -> "Resume Download"
            voiceModelStatus == ModelDownloadStatus.ERROR -> "Re-download"
            else -> "Download Model"
        }

        val items = listOf(
            PreflightCheckItem(
                id = PreflightCheckId.OVERLAY,
                title = "Display over other apps",
                purpose = "Shows the floating JARVIS orb, highlight boxes, and step guidance on top of other apps.",
                isRequired = true,
                status = if (overlayGranted) PreflightCheckStatus.GRANTED else PreflightCheckStatus.MISSING,
                statusLabel = if (overlayGranted) "Granted" else "Missing",
                actionLabel = if (overlayGranted) "Granted" else "Open Settings"
            ),
            PreflightCheckItem(
                id = PreflightCheckId.MICROPHONE,
                title = "Microphone permission",
                purpose = "Enables hands-free voice questions and continuous voice conversation while screen sharing.",
                isRequired = true,
                status = if (micGranted) PreflightCheckStatus.GRANTED else PreflightCheckStatus.MISSING,
                statusLabel = if (micGranted) "Granted" else "Missing",
                actionLabel = if (micGranted) "Granted" else "Allow Mic"
            ),
            PreflightCheckItem(
                id = PreflightCheckId.NOTIFICATION,
                title = "Notification permission",
                purpose = if (androidSdkInt >= Build.VERSION_CODES.TIRAMISU) {
                    "Required on Android 13+ for foreground screen capture and live voice service notifications."
                } else {
                    "Automatically granted below Android 13 for screen capture service controls."
                },
                isRequired = true,
                status = if (notifGrantedEffective) PreflightCheckStatus.GRANTED else PreflightCheckStatus.MISSING,
                statusLabel = if (notifGrantedEffective) "Granted" else "Missing",
                actionLabel = if (notifGrantedEffective) "Granted" else "Allow Alerts"
            ),
            PreflightCheckItem(
                id = PreflightCheckId.VOICE_MODEL,
                title = "Local voice model (Indic Tiny TTS)",
                purpose = voiceModelProgressText.ifBlank {
                    "Verified offline Hindi ONNX voice model (~91 MB) for crisp, low-latency spoken screen guidance."
                },
                isRequired = true,
                status = voiceCheckStatus,
                statusLabel = voiceStatusLabel,
                actionLabel = voiceActionLabel
            ),
            PreflightCheckItem(
                id = PreflightCheckId.MEDIA_PROJECTION,
                title = "Screen-capture consent",
                purpose = "System MediaProjection approval allowing live frame analysis and AR target framing.",
                isRequired = true,
                status = if (mediaProjectionGranted) PreflightCheckStatus.GRANTED else PreflightCheckStatus.MISSING,
                statusLabel = if (mediaProjectionGranted) "Granted" else "Missing",
                actionLabel = if (mediaProjectionGranted) "Active" else "Grant Capture"
            ),
            PreflightCheckItem(
                id = PreflightCheckId.ACCESSIBILITY,
                title = "Accessibility service",
                purpose = "Optional: reads live UI node trees and performs verified in-app taps and form steps.",
                isRequired = false,
                status = if (accessibilityGranted) PreflightCheckStatus.OPTIONAL_GRANTED else PreflightCheckStatus.OPTIONAL_MISSING,
                statusLabel = if (accessibilityGranted) "Optional • Enabled" else "Optional",
                actionLabel = if (accessibilityGranted) "Enabled" else "Enable"
            ),
            PreflightCheckItem(
                id = PreflightCheckId.USAGE_ACCESS,
                title = "Usage access",
                purpose = "Optional: improves foreground app context detection across app switches.",
                isRequired = false,
                status = if (usageAccessGranted) PreflightCheckStatus.OPTIONAL_GRANTED else PreflightCheckStatus.OPTIONAL_MISSING,
                statusLabel = if (usageAccessGranted) "Optional • Granted" else "Optional",
                actionLabel = if (usageAccessGranted) "Granted" else "Open Settings"
            ),
            PreflightCheckItem(
                id = PreflightCheckId.NOTIFICATION_LISTENER,
                title = "Notification listener",
                purpose = "Optional: lets Kavya summarize incoming alerts while you share your screen.",
                isRequired = false,
                status = if (notificationListenerGranted) PreflightCheckStatus.OPTIONAL_GRANTED else PreflightCheckStatus.OPTIONAL_MISSING,
                statusLabel = if (notificationListenerGranted) "Optional • Enabled" else "Optional",
                actionLabel = if (notificationListenerGranted) "Enabled" else "Open Settings"
            )
        )

        return PreflightSnapshot(items)
    }
}

private fun openNotificationPermissionOrSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    } else {
        null
    }
    try {
        if (intent != null) {
            context.startActivity(intent)
        } else {
            PermissionsManager.openAppSettings(context)
        }
    } catch (_: Exception) {
        PermissionsManager.openAppSettings(context)
    }
}

private fun shouldOpenSettingsForPermission(context: Context, permission: String, alreadyRequestedOnce: Boolean): Boolean {
    if (!alreadyRequestedOnce) return false
    val activity = context as? Activity ?: return true
    return !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
}

@Composable
fun rememberScreenSharePreflightSnapshot(
    shareState: ScreenShareManager.ScreenShareState
): Pair<PreflightSnapshot, () -> Unit> {
    val context = LocalContext.current
    val indicModelManager = remember { IndicTinyTtsModelManager.getInstance(context) }
    val modelStatus by indicModelManager.status.collectAsState()
    val modelProgress by indicModelManager.downloadProgress.collectAsState()
    val modelProgressText by indicModelManager.progressText.collectAsState()

    var refreshTick by remember { mutableIntStateOf(0) }

    val refresh: () -> Unit = remember {
        {
            indicModelManager.checkInstalledState()
            refreshTick++
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refresh()
    }

    val snapshot = remember(
        refreshTick,
        shareState,
        modelStatus,
        modelProgress,
        modelProgressText
    ) {
        ScreenSharePreflightEvaluator.inspect(
            context = context,
            shareState = shareState,
            modelStatus = modelStatus,
            modelReadyOnDisk = indicModelManager.isModelReady(),
            modelProgress = modelProgress,
            modelProgressText = modelProgressText
        )
    }
    return snapshot to refresh
}

/**
 * Inline compact summary card embedded inside ScreenShareSheet and ScreenShareScreen.
 */
@Composable
fun ScreenSharePreflightSummaryCard(
    snapshot: PreflightSnapshot,
    onOpenPreflight: () -> Unit,
    modifier: Modifier = Modifier
) {
    val allSetupReady = snapshot.canStartMediaProjection
    val accentColor = when {
        snapshot.allReadyIncludingProjection -> SuccessGreen
        allSetupReady -> AccentCyan
        else -> AccentYellow
    }

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("screen_share_preflight_summary"),
        shape = RoundedCornerShape(16.dp),
        backgroundColor = SurfaceGlass.copy(alpha = 0.78f),
        borderColor = accentColor.copy(alpha = 0.45f),
        onClick = onOpenPreflight
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (allSetupReady) Icons.Default.VerifiedUser else Icons.Default.Checklist,
                            contentDescription = "Permission Readiness",
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column {
                        Text(
                            text = if (snapshot.allReadyIncludingProjection) {
                                "All Screen Share checks ready"
                            } else if (allSetupReady) {
                                "Setup ready • Screen-capture consent pending"
                            } else {
                                "Preflight: ${snapshot.readySetupCount}/${snapshot.totalSetupCount} setup checks ready"
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = TextPrimary
                        )
                        val missingNames = snapshot.remainingAllRequiredItems.joinToString(", ") { it.title }
                        Text(
                            text = if (missingNames.isBlank()) {
                                "Required permissions and verified voice model are ready."
                            } else {
                                "Remaining: $missingNames"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                TextButton(
                    onClick = onOpenPreflight,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("open_preflight_button")
                ) {
                    Text(
                        text = if (allSetupReady) "Review" else "Setup",
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                }
            }

            LinearProgressIndicator(
                progress = { snapshot.requiredProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = accentColor,
                trackColor = SurfaceElevated
            )
        }
    }
}

/**
 * Full-screen dialog wrapping the polished Screen Share & JARVIS Permission Preflight experience.
 */
@Composable
fun ScreenSharePreflightDialog(
    shareState: ScreenShareManager.ScreenShareState,
    onDismiss: () -> Unit,
    onRequestMediaProjection: () -> Unit,
    onOpenVoiceSettings: (() -> Unit)? = null
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        ScreenSharePreflightView(
            shareState = shareState,
            onClose = onDismiss,
            onRequestMediaProjection = onRequestMediaProjection,
            onOpenVoiceSettings = onOpenVoiceSettings,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * Complete Screen Share & JARVIS Permission Preflight readiness screen / panel.
 */
@Composable
fun ScreenSharePreflightView(
    shareState: ScreenShareManager.ScreenShareState,
    onClose: () -> Unit,
    onRequestMediaProjection: () -> Unit,
    onOpenVoiceSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val indicModelManager = remember { IndicTinyTtsModelManager.getInstance(context) }
    val modelStatus by indicModelManager.status.collectAsState()
    val modelProgress by indicModelManager.downloadProgress.collectAsState()

    val (snapshot, refreshAll) = rememberScreenSharePreflightSnapshot(shareState)

    var requestedMicOnce by remember { mutableStateOf(false) }
    var requestedNotifOnce by remember { mutableStateOf(false) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        requestedMicOnce = true
        if (!granted && shouldOpenSettingsForPermission(context, Manifest.permission.RECORD_AUDIO, true)) {
            PermissionsManager.openAppSettings(context)
        }
        refreshAll()
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        requestedNotifOnce = true
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            shouldOpenSettingsForPermission(context, Manifest.permission.POST_NOTIFICATIONS, true)
        ) {
            openNotificationPermissionOrSettings(context)
        }
        refreshAll()
    }

    fun handleItemAction(item: PreflightCheckItem) {
        when (item.id) {
            PreflightCheckId.OVERLAY -> PermissionsManager.requestOverlayPermission(context)
            PreflightCheckId.MICROPHONE -> {
                if (PermissionsManager.hasRecordAudioPermission(context)) {
                    refreshAll()
                } else if (shouldOpenSettingsForPermission(context, Manifest.permission.RECORD_AUDIO, requestedMicOnce)) {
                    PermissionsManager.openAppSettings(context)
                } else {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            PreflightCheckId.NOTIFICATION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !AppPreferences.hasNotificationPermission(context)
                ) {
                    if (shouldOpenSettingsForPermission(context, Manifest.permission.POST_NOTIFICATIONS, requestedNotifOnce)) {
                        openNotificationPermissionOrSettings(context)
                    } else {
                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                } else {
                    refreshAll()
                }
            }
            PreflightCheckId.VOICE_MODEL -> {
                when (modelStatus) {
                    ModelDownloadStatus.READY -> refreshAll()
                    ModelDownloadStatus.DOWNLOADING -> indicModelManager.pauseDownload()
                    ModelDownloadStatus.PAUSED -> indicModelManager.resumeDownload()
                    ModelDownloadStatus.ERROR -> indicModelManager.redownloadModel()
                    ModelDownloadStatus.VERIFYING -> Unit
                    ModelDownloadStatus.NOT_DOWNLOADED -> indicModelManager.downloadAll()
                }
            }
            PreflightCheckId.MEDIA_PROJECTION -> {
                if (snapshot.canStartMediaProjection) {
                    onRequestMediaProjection()
                }
            }
            PreflightCheckId.ACCESSIBILITY -> PermissionsManager.requestAccessibilityPermission(context)
            PreflightCheckId.USAGE_ACCESS -> PermissionsManager.requestUsageStatsPermission(context)
            PreflightCheckId.NOTIFICATION_LISTENER -> PermissionsManager.requestNotificationListenerPermission(context)
        }
    }

    val scrollState = rememberScrollState()

    Surface(
        color = Background,
        contentColor = TextPrimary,
        modifier = modifier
            .systemBarsPadding()
            .testTag("screen_share_preflight_screen")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("preflight_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Permission Preflight",
                            tint = TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "Screen Share & JARVIS Preflight",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            text = "Verify system access before live visual sharing",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }

                IconButton(
                    onClick = refreshAll,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("preflight_refresh_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh permission status",
                        tint = AccentCyan
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Progress Overview Card
                val remainingSetupCount = snapshot.remainingSetupItems.size
                val remainingAllCount = snapshot.remainingAllRequiredItems.size
                val headerAccent = when {
                    snapshot.allReadyIncludingProjection -> SuccessGreen
                    snapshot.canStartMediaProjection -> AccentCyan
                    else -> PrimaryLight
                }

                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("preflight_progress_card"),
                    shape = RoundedCornerShape(20.dp),
                    backgroundColor = SurfaceGlass.copy(alpha = 0.9f),
                    borderColor = headerAccent.copy(alpha = 0.5f)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${snapshot.readyRequiredCount} of ${snapshot.totalRequiredCount} required ready",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = headerAccent
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = headerAccent.copy(alpha = 0.16f)
                            ) {
                                Text(
                                    text = when {
                                        snapshot.allReadyIncludingProjection -> "ALL READY"
                                        snapshot.canStartMediaProjection -> "CONSENT NEXT"
                                        else -> "$remainingSetupCount SETUP LEFT"
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    ),
                                    color = headerAccent,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        LinearProgressIndicator(
                            progress = { snapshot.requiredProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            color = headerAccent,
                            trackColor = SurfaceElevated
                        )

                        Text(
                            text = when {
                                snapshot.allReadyIncludingProjection ->
                                    "All required permissions, verified voice model, and screen capture consent are active."
                                snapshot.canStartMediaProjection ->
                                    "Setup prerequisites complete. Tap Grant Screen Capture below to start MediaProjection."
                                else ->
                                    "Remaining required ($remainingAllCount): ${snapshot.remainingAllRequiredItems.joinToString(", ") { it.title }}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                // Required Section Header
                Text(
                    text = "REQUIRED FOR SCREEN SHARE & JARVIS",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = AccentCyan,
                    modifier = Modifier.padding(top = 4.dp)
                )

                snapshot.allRequiredChecks.forEach { item ->
                    val isProjectionBlocked = item.id == PreflightCheckId.MEDIA_PROJECTION && !snapshot.canStartMediaProjection
                    PreflightPermissionCard(
                        item = item,
                        showProgressBar = item.id == PreflightCheckId.VOICE_MODEL &&
                            (modelStatus == ModelDownloadStatus.DOWNLOADING ||
                                modelStatus == ModelDownloadStatus.VERIFYING ||
                                modelStatus == ModelDownloadStatus.PAUSED),
                        progress = modelProgress,
                        actionEnabled = !item.isSatisfied && !isProjectionBlocked &&
                            !(item.id == PreflightCheckId.VOICE_MODEL && modelStatus == ModelDownloadStatus.VERIFYING),
                        secondaryActionLabel = if (item.id == PreflightCheckId.VOICE_MODEL && !item.isSatisfied && onOpenVoiceSettings != null) {
                            "Voice Studio"
                        } else null,
                        onSecondaryAction = onOpenVoiceSettings,
                        onAction = { handleItemAction(item) }
                    )
                }

                HorizontalDivider(
                    color = GlassBorder,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                // Optional Section Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "OPTIONAL SYSTEM ENHANCEMENTS",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        ),
                        color = TextSecondary
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = SurfaceGlass
                    ) {
                        Text(
                            text = "NOT REQUIRED",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = TextTertiary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                snapshot.optionalChecks.forEach { item ->
                    PreflightPermissionCard(
                        item = item,
                        showProgressBar = false,
                        progress = 0f,
                        actionEnabled = true,
                        onAction = { handleItemAction(item) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            // Bottom Primary Action Bar
            Surface(
                color = Surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val nextMissing = snapshot.remainingSetupItems.firstOrNull()
                    Button(
                        onClick = {
                            when {
                                snapshot.allReadyIncludingProjection -> onClose()
                                snapshot.canStartMediaProjection -> {
                                    onClose()
                                    onRequestMediaProjection()
                                }
                                nextMissing != null -> handleItemAction(nextMissing)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("preflight_primary_action_button"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = when {
                                snapshot.allReadyIncludingProjection -> SuccessGreen
                                snapshot.canStartMediaProjection -> Primary
                                else -> AccentCyan
                            }
                        )
                    ) {
                        Icon(
                            imageVector = when {
                                snapshot.allReadyIncludingProjection -> Icons.Default.CheckCircle
                                snapshot.canStartMediaProjection -> Icons.Default.ScreenShare
                                else -> Icons.AutoMirrored.Filled.OpenInNew
                            },
                            contentDescription = null,
                            tint = if (snapshot.canStartMediaProjection && !snapshot.allReadyIncludingProjection) Color.White else Color.Black,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                snapshot.allReadyIncludingProjection -> "Screen Share Ready • Continue"
                                snapshot.canStartMediaProjection -> "Grant Screen Capture & Start"
                                else -> "Next Step: ${nextMissing?.title ?: "Complete Setup"}"
                            },
                            fontWeight = FontWeight.Bold,
                            color = if (snapshot.canStartMediaProjection && !snapshot.allReadyIncludingProjection) Color.White else Color.Black
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreflightPermissionCard(
    item: PreflightCheckItem,
    showProgressBar: Boolean,
    progress: Float,
    actionEnabled: Boolean,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    onAction: () -> Unit
) {
    val icon: ImageVector = when (item.id) {
        PreflightCheckId.OVERLAY -> Icons.Default.Layers
        PreflightCheckId.MICROPHONE -> Icons.Default.Mic
        PreflightCheckId.NOTIFICATION -> Icons.Default.Notifications
        PreflightCheckId.VOICE_MODEL -> Icons.Default.RecordVoiceOver
        PreflightCheckId.MEDIA_PROJECTION -> Icons.Default.ScreenShare
        PreflightCheckId.ACCESSIBILITY -> Icons.Default.Accessibility
        PreflightCheckId.USAGE_ACCESS -> Icons.Default.QueryStats
        PreflightCheckId.NOTIFICATION_LISTENER -> Icons.Default.NotificationsActive
    }

    val badgeColor = when (item.status) {
        PreflightCheckStatus.GRANTED -> SuccessGreen
        PreflightCheckStatus.MISSING -> Color(0xFFFFB300)
        PreflightCheckStatus.IN_PROGRESS -> AccentCyan
        PreflightCheckStatus.OPTIONAL_GRANTED -> SuccessGreen
        PreflightCheckStatus.OPTIONAL_MISSING -> TextSecondary
    }

    val badgeBackground = badgeColor.copy(alpha = 0.16f)

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("preflight_card_${item.id.name.lowercase()}"),
        shape = RoundedCornerShape(16.dp),
        backgroundColor = SurfaceGlass.copy(alpha = 0.72f),
        borderColor = when (item.status) {
            PreflightCheckStatus.GRANTED -> SuccessGreen.copy(alpha = 0.35f)
            PreflightCheckStatus.MISSING -> Color(0xFFFFB300).copy(alpha = 0.38f)
            PreflightCheckStatus.IN_PROGRESS -> AccentCyan.copy(alpha = 0.45f)
            else -> GlassBorder
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(badgeBackground),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = TextPrimary,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = badgeBackground
                        ) {
                            Text(
                                text = item.statusLabel,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                ),
                                color = badgeColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = item.purpose,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = TextSecondary
                    )
                }
            }

            AnimatedVisibility(visible = showProgressBar) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = AccentCyan,
                    trackColor = SurfaceElevated
                )
            }

            if (!item.isSatisfied || !item.isRequired) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!secondaryActionLabel.isNullOrBlank() && onSecondaryAction != null) {
                        TextButton(
                            onClick = onSecondaryAction,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text(secondaryActionLabel, color = TextSecondary, fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    if (item.id == PreflightCheckId.MEDIA_PROJECTION && !actionEnabled && !item.isSatisfied) {
                        Text(
                            text = "Complete required setup checks above first",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary
                        )
                    } else if (!item.isSatisfied || item.id != PreflightCheckId.MEDIA_PROJECTION) {
                        OutlinedButton(
                            onClick = onAction,
                            enabled = actionEnabled,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor.copy(alpha = 0.55f)),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("preflight_action_${item.id.name.lowercase()}")
                        ) {
                            Text(
                                text = item.actionLabel,
                                color = badgeColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}
