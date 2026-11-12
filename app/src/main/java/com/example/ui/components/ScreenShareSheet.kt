package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.services.ScreenShareManager
import com.example.ui.screens.ScreenSharePresentationWorkspace
import com.example.ui.theme.*
import com.example.utils.AppPreferences
import com.example.viewmodel.KavyaViewModel
import com.example.visual.JarvisGuidanceEngine
import com.example.visual.ScreenSharePresentationLevel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenShareSheet(
    viewModel: KavyaViewModel,
    onDismiss: () -> Unit,
    onNavigateToChat: () -> Unit
) {
    val context = LocalContext.current
    val shareState by viewModel.screenShareState.collectAsState()
    val isSharing = ScreenShareManager.isScreenSharingActive
    val screenContext by viewModel.screenContextText.collectAsState()
    val scrollState = rememberScrollState()

    var showWorkspaceDialog by remember { mutableStateOf(false) }

    var selectedPresentationLevel by remember {
        mutableStateOf(
            ScreenSharePresentationLevel.fromId(AppPreferences.getScreenPresentationLevel(context))
        )
    }

    // Status description from actual state
    val statusText = when (val s = shareState) {
        is ScreenShareManager.ScreenShareState.Active -> "Active • ${s.width}x${s.height} @ ${s.fps} FPS (${s.framesCaptured} frames)"
        is ScreenShareManager.ScreenShareState.StaleFrame -> "Warning • Screen frame stream is stale"
        is ScreenShareManager.ScreenShareState.WaitingForFirstFrame -> "Connecting • Waiting for first frame..."
        is ScreenShareManager.ScreenShareState.StartingService -> "Starting foreground capture service..."
        is ScreenShareManager.ScreenShareState.RequestingPermission -> "Requesting system consent..."
        is ScreenShareManager.ScreenShareState.PermissionDenied -> "Permission Denied: ${s.reason}"
        is ScreenShareManager.ScreenShareState.Error -> "Error: ${s.message}"
        else -> "Inactive • Ready to share screen"
    }

    val statusDotColor = when (shareState) {
        is ScreenShareManager.ScreenShareState.Active -> SuccessGreen
        is ScreenShareManager.ScreenShareState.StaleFrame -> Color(0xFFFFB300)
        is ScreenShareManager.ScreenShareState.StartingService,
        is ScreenShareManager.ScreenShareState.WaitingForFirstFrame -> AccentCyan
        is ScreenShareManager.ScreenShareState.PermissionDenied,
        is ScreenShareManager.ScreenShareState.Error -> Error
        else -> TextTertiary
    }

    val screenCaptureConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onScreenShareConsentResult(context, result.resultCode, result.data)
    }

    fun startScreenShareWithConsent() {
        val captureIntent = viewModel.startScreenShareConsent(context)
        if (captureIntent != null) {
            screenCaptureConsentLauncher.launch(captureIntent)
        }
    }

    if (showWorkspaceDialog) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showWorkspaceDialog = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            ScreenSharePresentationWorkspace(
                viewModel = viewModel,
                initialLevel = selectedPresentationLevel,
                onClose = { showWorkspaceDialog = false },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle(color = OutlineVariant) },
        containerColor = Surface,
        contentColor = TextPrimary,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier.testTag("screen_share_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 36.dp)
                .verticalScroll(scrollState)
        ) {
            // Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSharing)
                                    Brush.linearGradient(listOf(SuccessGreen, AccentCyan))
                                else
                                    Brush.linearGradient(listOf(BrandGradientStart, BrandGradientEnd))
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ScreenShare,
                            contentDescription = "Screen Share",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "Screen Share",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextPrimary)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(statusDotColor)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = if (isSharing) SuccessGreenGlow else TextSecondary
                            )
                        }
                    }
                }

                IconButton(
                    onClick = { viewModel.refreshScreenContext() },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(SurfaceGlass)
                        .border(1.dp, GlassBorder, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh screen context",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Presentation Level Selection (Basic, Standard, Advanced)
            Text(
                text = "Explain Screen Presentation Level:",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, color = TextSecondary)
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceGlass)
                    .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ScreenSharePresentationLevel.entries.forEach { level ->
                    val isSelected = selectedPresentationLevel == level
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .then(
                                if (isSelected)
                                    Modifier.background(Brush.linearGradient(listOf(Primary, AccentCyan)))
                                else
                                    Modifier
                            )
                            .clickable {
                                selectedPresentationLevel = level
                                AppPreferences.setScreenPresentationLevel(context, level.id)
                            }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = level.title,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Color.White else TextSecondary,
                                fontSize = 11.sp
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Main Toggle Action Button (With Screen Capture Consent Flow)
            val toggleInteraction = remember { MutableInteractionSource() }
            val isTogglePressed by toggleInteraction.collectIsPressedAsState()
            val toggleScale by animateFloatAsState(
                targetValue = if (isTogglePressed) 0.95f else 1f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "ToggleScale"
            )

            Button(
                onClick = {
                    if (isSharing) {
                        viewModel.toggleScreenSharing(false)
                    } else {
                        startScreenShareWithConsent()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .scale(toggleScale)
                    .testTag("toggle_screen_share_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSharing) Error else Primary
                ),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                Icon(
                    imageVector = if (isSharing) Icons.Default.Stop else Icons.Default.ScreenShare,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (isSharing) "Stop Screen Sharing" else "Start Screen Share with Kavya",
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    fontSize = 15.sp
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Open Full Screen Share Workspace Button
            OutlinedButton(
                onClick = { showWorkspaceDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = SurfaceGlass.copy(alpha = 0.6f),
                    contentColor = AccentCyan
                ),
                border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                    brush = Brush.linearGradient(listOf(AccentCyan.copy(alpha = 0.5f), GlassBorder))
                )
            ) {
                Icon(Icons.Default.OpenInFull, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open Visual Workspace (${selectedPresentationLevel.title})", fontWeight = FontWeight.Medium, fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Quick Inquiries & Visual Intelligence Actions
            Text(
                text = "Visual Intelligence & Actions:",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, color = TextSecondary)
            )
            Spacer(modifier = Modifier.height(10.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        JarvisGuidanceEngine.explainActiveScreenAsync(context, "Explain this screen in detail")
                        showWorkspaceDialog = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = SurfaceGlass.copy(alpha = 0.5f),
                        contentColor = TextPrimary
                    ),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(brush = Brush.linearGradient(listOf(Outline, GlassBorder)))
                ) {
                    Icon(Icons.Default.Psychology, contentDescription = null, tint = PrimaryLight, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Explain Screen (Visual Intelligence) ✨", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp))
                }

                OutlinedButton(
                    onClick = {
                        viewModel.analyzeScreenWithKavya("What clickable buttons or action items are available on this screen?")
                        onDismiss()
                        onNavigateToChat()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = SurfaceGlass.copy(alpha = 0.5f),
                        contentColor = TextPrimary
                    ),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(brush = Brush.linearGradient(listOf(Outline, GlassBorder)))
                ) {
                    Icon(Icons.Default.TouchApp, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Shows You How (Guidance) 🔍", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp))
                }

                OutlinedButton(
                    onClick = {
                        viewModel.analyzeScreenWithKavya("Read aloud all visible text on the screen.")
                        onDismiss()
                        onNavigateToChat()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = SurfaceGlass.copy(alpha = 0.5f),
                        contentColor = TextPrimary
                    ),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(brush = Brush.linearGradient(listOf(Outline, GlassBorder)))
                ) {
                    Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = AccentPurpleLight, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Read screen text aloud 🎙️", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp))
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Perception Inspector Feed
            Text(
                text = "Live Perception Feed:",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, color = TextSecondary)
            )
            Spacer(modifier = Modifier.height(8.dp))

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                backgroundColor = Background.copy(alpha = 0.8f),
                borderColor = Outline
            ) {
                Text(
                    text = screenContext ?: "Screen context will appear here in real-time when active.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                )
            }
        }
    }
}
