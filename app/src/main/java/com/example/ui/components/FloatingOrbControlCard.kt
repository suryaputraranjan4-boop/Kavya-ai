package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.ui.theme.*
import com.example.utils.AppPreferences
import com.example.utils.PermissionsManager
import com.example.visual.JarvisOverlayController
import com.example.visual.OverlayAttachmentState

/**
 * Clean Apple Liquid Glass Floating Orb Control Card with a single master toggle,
 * honest WindowManager visibility status, overlay permission check, and Start / Stop / Test actions.
 */
@Composable
fun FloatingOrbControlCard(
    modifier: Modifier = Modifier,
    onPermissionRequiredPrompt: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val isOverlayRunning by JarvisOverlayController.isOverlayRunning.collectAsState()
    val attachmentState by JarvisOverlayController.attachmentState.collectAsState()
    val lastOverlayError by JarvisOverlayController.lastOverlayError.collectAsState()
    val orbState by JarvisOverlayController.orbState.collectAsState()
    val orbConfig by JarvisOverlayController.orbConfig.collectAsState()
    val isTestRunning by JarvisOverlayController.isTestShowcaseRunning.collectAsState()

    var hasOverlayPermission by remember {
        mutableStateOf(JarvisOverlayController.hasOverlayPermission(context))
    }
    var isOrbPrefEnabled by remember {
        mutableStateOf(AppPreferences.isFloatingOrbEnabled(context))
    }
    var pendingEnableAfterSettings by remember { mutableStateOf(false) }

    fun refreshState() {
        hasOverlayPermission = JarvisOverlayController.hasOverlayPermission(context)
        isOrbPrefEnabled = AppPreferences.isFloatingOrbEnabled(context)
        if (pendingEnableAfterSettings && hasOverlayPermission) {
            pendingEnableAfterSettings = false
            AppPreferences.setFloatingOrbEnabled(context, true)
            isOrbPrefEnabled = true
            JarvisOverlayController.startOverlay(context)
        } else {
            JarvisOverlayController.syncFloatingOrbFromPreferences(context)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun requestEnableOrb() {
        hasOverlayPermission = JarvisOverlayController.hasOverlayPermission(context)
        if (!hasOverlayPermission) {
            pendingEnableAfterSettings = true
            AppPreferences.setFloatingOrbEnabled(context, false)
            isOrbPrefEnabled = false
            JarvisOverlayController.reportOverlayError(
                "Grant 'Display over other apps' permission to show the Floating Orb over all apps.",
                permissionMissing = true
            )
            onPermissionRequiredPrompt?.invoke()
            PermissionsManager.requestOverlayPermission(context)
            return
        }
        AppPreferences.setFloatingOrbEnabled(context, true)
        isOrbPrefEnabled = true
        JarvisOverlayController.enableFloatingOrb(context)
    }

    fun requestDisableOrb() {
        pendingEnableAfterSettings = false
        AppPreferences.setFloatingOrbEnabled(context, false)
        isOrbPrefEnabled = false
        JarvisOverlayController.disableFloatingOrb(context)
    }

    val visibilityLabel = when (attachmentState) {
        OverlayAttachmentState.ATTACHED_VISIBLE -> "Visible over apps"
        OverlayAttachmentState.STARTING -> "Starting overlay..."
        OverlayAttachmentState.PERMISSION_MISSING -> "Permission required"
        OverlayAttachmentState.ERROR -> "Unavailable"
        OverlayAttachmentState.STOPPED -> "Hidden"
    }

    val visibilityColor = when (attachmentState) {
        OverlayAttachmentState.ATTACHED_VISIBLE -> SuccessGreen
        OverlayAttachmentState.STARTING -> PrimaryLight
        OverlayAttachmentState.PERMISSION_MISSING -> AccentYellow
        OverlayAttachmentState.ERROR -> Error
        OverlayAttachmentState.STOPPED -> TextSecondary
    }

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("floating_orb_control_card"),
        shape = RoundedCornerShape(22.dp),
        borderColor = if (isOverlayRunning) PrimaryLight.copy(alpha = 0.55f) else GlassBorder
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Header
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
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(visibilityColor)
                    )
                    Column {
                        Text(
                            text = "Floating Orb Overlay",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "${orbState.name} • $visibilityLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = visibilityColor,
                            modifier = Modifier.testTag("floating_orb_visibility_status")
                        )
                    }
                }
                StatusBadge(
                    text = if (isOverlayRunning) "Live" else "Off",
                    isActive = isOverlayRunning,
                    activeColor = SuccessGreen,
                    inactiveColor = TextTertiary
                )
            }

            HorizontalDivider(color = OutlineVariant)

            // Single clean master toggle switch (no duplicate conflicting disable switch)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "Show Floating Orb",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = TextPrimary
                    )
                    Text(
                        text = "Keep interactive JARVIS orb accessible over any app",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = isOrbPrefEnabled && isOverlayRunning,
                    onCheckedChange = { checked ->
                        if (checked) requestEnableOrb() else requestDisableOrb()
                    },
                    modifier = Modifier.testTag("enable_floating_orb_switch")
                )
            }

            // Reduced Motion Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "Reduced Motion",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = TextPrimary
                    )
                    Text(
                        text = "Calm static halo and instant edge docking",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = orbConfig.reducedMotion,
                    onCheckedChange = { reduced ->
                        JarvisOverlayController.updateAndPersistConfig(context) {
                            it.copy(reducedMotion = reduced)
                        }
                    },
                    modifier = Modifier.testTag("orb_reduced_motion_switch")
                )
            }

            // Overlay Permission Banner
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (hasOverlayPermission) SuccessGreen.copy(alpha = 0.12f) else AccentYellow.copy(alpha = 0.14f),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (hasOverlayPermission) SuccessGreen.copy(alpha = 0.35f) else AccentYellow.copy(alpha = 0.45f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (hasOverlayPermission) "Overlay Permission Granted" else "Overlay Permission Required",
                            color = if (hasOverlayPermission) SuccessGreen else AccentYellow,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("floating_orb_permission_status")
                        )
                        if (!hasOverlayPermission) {
                            TextButton(
                                onClick = {
                                    pendingEnableAfterSettings = true
                                    PermissionsManager.requestOverlayPermission(context)
                                },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("grant_overlay_permission_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = "Open Overlay Settings",
                                    tint = AccentYellow,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Grant", color = AccentYellow, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Text(
                        text = "Lets Kavya display the Floating Orb and touch-through step highlights while you use other apps.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (!lastOverlayError.isNullOrBlank()) {
                Text(
                    text = lastOverlayError ?: "",
                    color = Error,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    modifier = Modifier.testTag("floating_orb_error_text")
                )
            }

            // Action Buttons: Start | Stop | Test
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { requestEnableOrb() },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("start_orb_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Start Orb",
                        tint = Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Start", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = { requestDisableOrb() },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Error.copy(alpha = 0.88f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("stop_orb_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop Orb",
                        tint = Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Stop", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = {
                        hasOverlayPermission = JarvisOverlayController.hasOverlayPermission(context)
                        if (!hasOverlayPermission) {
                            pendingEnableAfterSettings = true
                            JarvisOverlayController.reportOverlayError(
                                "Please grant 'Display over other apps' first, then Test Orb will display over apps.",
                                permissionMissing = true
                            )
                            PermissionsManager.requestOverlayPermission(context)
                        } else {
                            isOrbPrefEnabled = true
                            JarvisOverlayController.runTestOrbShowcase(context)
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, GlassBorder),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("test_orb_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Animation,
                        contentDescription = "Test Orb",
                        tint = PrimaryLight,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isTestRunning) "Testing" else "Test",
                        color = PrimaryLight,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
