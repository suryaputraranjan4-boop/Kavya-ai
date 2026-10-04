package com.example.ui.components

import android.Manifest
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
import com.example.ui.theme.*
import com.example.viewmodel.KavyaViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenShareSheet(
    viewModel: KavyaViewModel,
    onDismiss: () -> Unit,
    onNavigateToChat: () -> Unit
) {
    val context = LocalContext.current
    val isSharing by viewModel.isScreenSharing.collectAsState()
    val screenContext by viewModel.screenContextText.collectAsState()
    val voiceState by viewModel.voiceState.collectAsState()
    val scrollState = rememberScrollState()

    // Android Official Screen Capture Consent Launcher (MediaProjection)
    val mediaProjectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
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
                            imageVector = Icons.AutoMirrored.Filled.ScreenShare,
                            contentDescription = "Screen Share",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "Live Screen Sharing",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextPrimary)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (isSharing) SuccessGreen else TextTertiary)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isSharing) "Active • Kavya is observing & listening" else "Inactive • Ready to share",
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

            // Companion Prompt Glass Bubble
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                backgroundColor = SurfaceGlass.copy(alpha = 0.8f),
                borderColor = GlassBorder
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "✨",
                        fontSize = 22.sp,
                        modifier = Modifier.padding(end = 10.dp)
                    )
                    Text(
                        text = if (isSharing)
                            "\"I can see your screen now! I will observe and guide you in real time ✨\""
                        else
                            "\"Share your screen so I can follow along and guide you in real time ✨\"",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 19.sp),
                        color = PrimaryLight
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

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
                    imageVector = if (isSharing) Icons.Default.Stop else Icons.AutoMirrored.Filled.ScreenShare,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (isSharing) "Stop Screen Sharing" else "Start Sharing Screen with Kavya",
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    fontSize = 15.sp
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Quick Inquiries
            Text(
                text = "Screen Inquiries (Live Guidance):",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, color = TextSecondary)
            )
            Spacer(modifier = Modifier.height(10.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        viewModel.analyzeScreenWithKavya("What's on my screen right now? Please guide me.")
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
                    Icon(Icons.Default.Psychology, contentDescription = null, tint = PrimaryLight, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Explain what's on screen ✨", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp))
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
                    Text("Find clickable buttons and actions 🔍", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp))
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

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

