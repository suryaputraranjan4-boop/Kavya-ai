package com.example.ui.components

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.utils.AppPreferences
import com.example.utils.PermissionsManager
import com.example.visual.JarvisOrbState
import com.example.visual.JarvisOverlayController
import com.example.visual.OverlayAttachmentState

/**
 * Dedicated Floating Orb Control Card with explicit Enable / Disable switches,
 * honest WindowManager visibility status, Display-over-other-apps permission status & explanation,
 * Start / Stop / Test Orb action buttons, Reduced Motion toggle, and ON_RESUME auto-refresh.
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
        OverlayAttachmentState.ATTACHED_VISIBLE -> "VISIBLE OVER APPS (ATTACHED)"
        OverlayAttachmentState.STARTING -> "STARTING WINDOW OVERLAY..."
        OverlayAttachmentState.PERMISSION_MISSING -> "HIDDEN • PERMISSION MISSING"
        OverlayAttachmentState.ERROR -> "ERROR • NOT VISIBLE"
        OverlayAttachmentState.STOPPED -> "STOPPED • HIDDEN"
    }

    val visibilityColor = when (attachmentState) {
        OverlayAttachmentState.ATTACHED_VISIBLE -> Color(0xFF00E676)
        OverlayAttachmentState.STARTING -> Color(0xFF40C4FF)
        OverlayAttachmentState.PERMISSION_MISSING -> Color(0xFFFFB74D)
        OverlayAttachmentState.ERROR -> Color(0xFFFF5252)
        OverlayAttachmentState.STOPPED -> Color(0xFF94A3B8)
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101724)),
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.2.dp,
                color = if (isOverlayRunning) orbConfig.theme.primaryColor.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.14f),
                shape = RoundedCornerShape(18.dp)
            )
            .testTag("floating_orb_control_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Card Header
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
                            text = "Floating Orb Controls",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "State: ${orbState.name} • $visibilityLabel",
                            color = visibilityColor,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.testTag("floating_orb_visibility_status")
                        )
                    }
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            // Enable Floating Orb Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Enable Floating Orb",
                        color = Color.White,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Shows interactive JARVIS orb across apps & home screen",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 11.sp
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

            // Disable Floating Orb Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Disable Floating Orb",
                        color = Color.White,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Detaches overlay windows and stops foreground orb service",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = !isOrbPrefEnabled && !isOverlayRunning,
                    onCheckedChange = { checked ->
                        if (checked) requestDisableOrb() else requestEnableOrb()
                    },
                    modifier = Modifier.testTag("disable_floating_orb_switch")
                )
            }

            // Reduced Motion Switch (Low-end friendly)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Reduced Motion Mode",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Lightweight static halo & instant docking for battery saving",
                        color = Color.White.copy(alpha = 0.62f),
                        fontSize = 11.sp
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

            // Display Over Other Apps Permission Status & Explanation
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (hasOverlayPermission) Color(0x1800E676) else Color(0x22FFB74D),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = if (hasOverlayPermission) Color(0x5500E676) else Color(0x66FFB74D),
                        shape = RoundedCornerShape(12.dp)
                    )
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
                            text = if (hasOverlayPermission) "Permission: Display over other apps GRANTED" else "Permission: Display over other apps MISSING",
                            color = if (hasOverlayPermission) Color(0xFF00E676) else Color(0xFFFFB74D),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
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
                                    tint = Color(0xFFFFB74D),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Grant", color = Color(0xFFFFB74D), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Text(
                        text = "Android's 'Display over other apps' permission lets Kavya render the Floating Orb and touch-through step highlights while you use other apps like YouTube, WhatsApp, or Settings.",
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }

            if (!lastOverlayError.isNullOrBlank()) {
                Text(
                    text = lastOverlayError ?: "",
                    color = Color(0xFFFF6B6B),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.testTag("floating_orb_error_text")
                )
            }

            // Action Buttons: Start Orb | Stop Orb | Test Orb
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { requestEnableOrb() },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853)),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("start_orb_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Start Orb",
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Start Orb", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = { requestDisableOrb() },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD50000)),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("stop_orb_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop Orb",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Stop Orb", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("test_orb_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Animation,
                        contentDescription = "Test Orb",
                        tint = orbConfig.theme.primaryColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isTestRunning) "Testing..." else "Test Orb",
                        color = orbConfig.theme.primaryColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
