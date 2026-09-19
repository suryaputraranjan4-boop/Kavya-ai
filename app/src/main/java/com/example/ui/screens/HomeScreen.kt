package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material3.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.services.KavyaAccessibilityService
import com.example.services.KavyaVoiceService
import com.example.ui.components.*
import com.example.ui.navigation.LocalDrawerState
import com.example.ui.theme.*
import com.example.utils.PermissionsManager
import com.example.viewmodel.KavyaViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.launch

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HomeScreen(navController: NavController, viewModel: KavyaViewModel) {
    val context = LocalContext.current
    var overlayGranted by remember { mutableStateOf(PermissionsManager.hasOverlayPermission(context)) }
    var accessibilityGranted by remember { mutableStateOf(PermissionsManager.isAccessibilityServiceEnabled(context, KavyaAccessibilityService::class.java)) }
    
    val permissionsToRequest = mutableListOf(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    val permissionState = rememberMultiplePermissionsState(permissionsToRequest)
    val canStart = overlayGranted && accessibilityGranted && permissionState.allPermissionsGranted
    
    val voiceState by viewModel.voiceState.collectAsState()
    val emotionState by viewModel.emotionState.collectAsState()
    val globalState by com.example.state.KavyaStateManager.state.collectAsState()
    var showScreenShareSheet by remember { mutableStateOf(false) }
    val isScreenSharing by viewModel.isScreenSharing.collectAsState()
    val latestCaption by viewModel.latestKavyaCaption.collectAsState()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsState()
    val agentActionStatus by viewModel.agentActionStatus.collectAsState()
    
    val drawerState = LocalDrawerState.current
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black) // Extreme minimalist dark background
    ) {
        // Very subtle background atmosphere
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF1A1A2E).copy(alpha = 0.6f),
                            Color.Black
                        ),
                        radius = 800f
                    )
                )
        )

        // CENTER: 2D Holographic Kavya
        HolographicKavya(
            voiceState = voiceState,
            emotionState = emotionState,
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp, bottom = 24.dp)
        ) {
            // TOP BAR: Menu, Screen Share, and Kavya Title
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left side: Hamburger + Screen Share
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Menu",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    val shareInteractionSource = remember { MutableInteractionSource() }
                    val sharePressed by shareInteractionSource.collectIsPressedAsState()
                    val shareScale by animateFloatAsState(if (sharePressed) 0.9f else 1f, label="shareScale")
                    
                    Box(
                        modifier = Modifier
                            .scale(shareScale)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = shareInteractionSource,
                                indication = null,
                                onClick = { showScreenShareSheet = true }
                            )
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = if (isScreenSharing) Icons.Filled.StopScreenShare else Icons.AutoMirrored.Filled.ScreenShare,
                            contentDescription = "Screen Share",
                            tint = if (isScreenSharing) Color(0xFFFF4444) else Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                
                // Center: Title
                Text(
                    text = "Kavya",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.W300,
                        letterSpacing = 4.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif
                    ),
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.weight(1f).padding(end = 84.dp), // Balance the left icons
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // LOWER CENTER: Microphone
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                AnimatedVisibility(visible = !latestCaption.isNullOrBlank()) {
                    TypewriterText(
                        text = latestCaption ?: "",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.W400,
                            letterSpacing = 0.5.sp,
                            color = Color.White.copy(alpha = 0.9f)
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(horizontal = 32.dp, vertical = 16.dp)
                            .fillMaxWidth()
                    )
                }
                KavyaVoiceOrb(
                    state = voiceState,
                    onClick = {
                        if (canStart) {
                            if (voiceState == VoiceState.SPEAKING) {
                                viewModel.setVoiceState(VoiceState.IDLE)
                            } else {
                                try {
                                    val intent = Intent(context, KavyaVoiceService::class.java)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(intent)
                                    } else {
                                        context.startService(intent)
                                    }
                                    viewModel.setVoiceState(VoiceState.LISTENING)
                                } catch (e: Exception) {
                                    // ignored
                                }
                            }
                        } else {
                            navController.navigate("permissions")
                        }
                    },
                    baseSize = 72.dp // Refined size
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Real Task Machine & Voice status text
                AnimatedVisibility(visible = true) {
                    val statusText = when {
                        globalState.taskState == com.example.state.TaskState.EXECUTING -> globalState.taskStateDetail ?: "Executing task..."
                        globalState.taskState == com.example.state.TaskState.PLANNING -> "Planning steps..."
                        globalState.taskState == com.example.state.TaskState.VERIFYING -> "Verifying outcome..."
                        globalState.taskState == com.example.state.TaskState.COMPLETED -> "Completed"
                        globalState.taskState == com.example.state.TaskState.FAILED -> globalState.taskStateDetail ?: "Task failed"
                        voiceState == VoiceState.IDLE -> "Tap to talk with Kavya"
                        voiceState == VoiceState.LISTENING -> "Listening..."
                        voiceState == VoiceState.UNDERSTANDING -> "Understanding..."
                        voiceState == VoiceState.THINKING -> "Thinking..."
                        voiceState == VoiceState.EXECUTING -> "Executing..."
                        voiceState == VoiceState.SPEAKING -> "Speaking..."
                        voiceState == VoiceState.ERROR -> "Error. Tap to retry."
                        else -> "Ready"
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = Color.White.copy(alpha = 0.6f),
                            letterSpacing = 1.sp,
                            fontWeight = FontWeight.W400
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
                
                if (!canStart) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Permissions required",
                        style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFFFF4444)),
                        modifier = Modifier.clickable { navController.navigate("permissions") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // BOTTOM: Chat Input
            ChatBar(
                onSend = {
                    viewModel.sendMessage(it)
                },
                onMicClick = {
                    if (canStart) {
                        try {
                            val intent = Intent(context, KavyaVoiceService::class.java)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(intent)
                            } else {
                                context.startService(intent)
                            }
                            viewModel.setVoiceState(VoiceState.LISTENING)
                        } catch (e: Exception) {
                            // ignored
                        }
                    } else {
                        navController.navigate("permissions")
                    }
                },
                onPlusClick = {
                    showScreenShareSheet = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            )
        }

        // Live Action Status Pill
        AnimatedVisibility(
            visible = agentActionStatus != null,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 90.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                shape = CircleShape,
                tonalElevation = 6.dp
            ) {
                Text(
                    text = agentActionStatus ?: "",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Medium
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }

    // Security Confirmation Dialog for Sensitive Actions
    if (pendingConfirmation != null) {
        val conf = pendingConfirmation!!
        AlertDialog(
            onDismissRequest = { viewModel.cancelPendingAction() },
            title = {
                Text(
                    text = conf.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Text(
                    text = conf.description,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmPendingAction() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Allow")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.cancelPendingAction() }) {
                    Text("Deny")
                }
            }
        )
    }

    if (showScreenShareSheet) {
        ScreenShareSheet(
            viewModel = viewModel,
            onDismiss = { showScreenShareSheet = false },
            onNavigateToChat = { navController.navigate("chat") }
        )
    }
}
