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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.TouchApp
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
    var accessibilityGranted by remember { mutableStateOf(PermissionsManager.isAccessibilityServiceEnabled(context, KavyaAccessibilityService::class.java)) }
    
    val permissionsToRequest = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    val permissionState = rememberMultiplePermissionsState(permissionsToRequest)
    val canStart = accessibilityGranted && (permissionsToRequest.isEmpty() || permissionState.allPermissionsGranted)
    
    val voiceState by viewModel.voiceState.collectAsState()
    val emotionState by viewModel.emotionState.collectAsState()
    val globalState by com.example.state.KavyaStateManager.state.collectAsState()
    var showScreenShareSheet by remember { mutableStateOf(false) }
    val isScreenSharing by viewModel.isScreenSharing.collectAsState()
    val latestCaption by viewModel.latestKavyaCaption.collectAsState()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsState()
    val agentActionStatus by viewModel.agentActionStatus.collectAsState()
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()
    val micEngineState by com.example.voice.KavyaMicrophoneEngine.engineState.collectAsState()
    val micAmplitude by com.example.voice.KavyaMicrophoneEngine.amplitude.collectAsState()
    
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
                
                // Center: Title & System Status
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "KAVYA",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.W300,
                            letterSpacing = 6.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif
                        ),
                        color = Color.White.copy(alpha = 0.95f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(if (isOfflineMode) Color(0xFF00E5FF) else if (canStart) Color(0xFF10B981) else Color(0xFFFFB020))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isOfflineMode) "OFFLINE • QWEN3-4B" else if (canStart) "SYSTEM READY" else "SETUP REQUIRED",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                letterSpacing = 1.5.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = if (isOfflineMode) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.45f)
                        )
                    }
                }

                // Right side: Visual Action Engine shortcut
                IconButton(onClick = { navController.navigate("visual_action_control") }) {
                    Icon(
                        imageVector = Icons.Default.TouchApp,
                        contentDescription = "Visual Screen Control",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // LOWER CENTER: Kavya AI Orb
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
                        if (micEngineState == com.example.voice.KavyaMicrophoneEngine.EngineState.MIC_OFF ||
                            micEngineState == com.example.voice.KavyaMicrophoneEngine.EngineState.MIC_ERROR
                        ) {
                            com.example.voice.KavyaMicrophoneEngine.startService(context)
                        } else {
                            com.example.voice.KavyaMicrophoneEngine.stopService(context)
                        }
                    },
                    baseSize = 72.dp,
                    amplitude = micAmplitude
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Dynamic, event-driven status text from current task and microphone state
                val statusText: String? = when {
                    agentActionStatus != null -> agentActionStatus
                    globalState.taskState == com.example.state.TaskState.OPENING_APP -> globalState.taskStateDetail ?: "App open ho raha hai..."
                    globalState.taskState == com.example.state.TaskState.LOOKING_AT_SCREEN -> "Screen scan kar rahi hoon..."
                    globalState.taskState == com.example.state.TaskState.FINDING_TARGET -> "Target search: ${globalState.taskStateDetail ?: ""}"
                    globalState.taskState == com.example.state.TaskState.PERFORMING_ACTION -> globalState.taskStateDetail ?: "Action execute ho raha hai..."
                    globalState.taskState == com.example.state.TaskState.EXECUTING -> globalState.taskStateDetail
                    globalState.taskState == com.example.state.TaskState.PLANNING -> globalState.taskStateDetail ?: "Task plan taiyaar kar rahi hoon..."
                    globalState.taskState == com.example.state.TaskState.VERIFYING -> "Result verify ho raha hai..."
                    globalState.taskState == com.example.state.TaskState.COMPLETED -> "Task complete ho gaya."
                    globalState.taskState == com.example.state.TaskState.STOPPED -> "Action rok diya gaya hai."
                    globalState.taskState == com.example.state.TaskState.FAILED -> globalState.taskStateDetail ?: "Task pura nahi ho paya."
                    voiceState == VoiceState.THINKING || voiceState == VoiceState.UNDERSTANDING -> "Request analyze ho rahi hai..."
                    voiceState == VoiceState.SPEAKING -> "Kavya bol rahi hai..."
                    micEngineState == com.example.voice.KavyaMicrophoneEngine.EngineState.MIC_STARTING -> "Starting microphone..."
                    micEngineState == com.example.voice.KavyaMicrophoneEngine.EngineState.MIC_SLEEPING -> "Sleep Mode • Say 'Hey Kavya' to Wake"
                    micEngineState == com.example.voice.KavyaMicrophoneEngine.EngineState.MIC_ACTIVE -> "Mic Active • Say 'Hey Kavya'"
                    micEngineState == com.example.voice.KavyaMicrophoneEngine.EngineState.MIC_OFF -> "Mic Off • Tap to Start"
                    micEngineState == com.example.voice.KavyaMicrophoneEngine.EngineState.MIC_ERROR -> "Microphone unavailable"
                    else -> "Kavya AI Ready"
                }

                AnimatedVisibility(visible = !statusText.isNullOrBlank()) {
                    Text(
                        text = statusText ?: "",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = Color.White.copy(alpha = 0.7f),
                            letterSpacing = 0.5.sp,
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

            // Quick Action Capsules
            val suggestions = listOf("YouTube खोलो", "WhatsApp खोलो", "Google पर सर्च करो", "Torch ON", "Settings")
            androidx.compose.foundation.lazy.LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(suggestions.size) { idx ->
                    val sug = suggestions[idx]
                    Surface(
                        color = Color.White.copy(alpha = 0.05f),
                        shape = RoundedCornerShape(20.dp),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, Color.White.copy(alpha = 0.15f)),
                        modifier = Modifier.clickable { viewModel.sendMessage(sug) }
                    ) {
                        Text(
                            text = sug,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = Color.White.copy(alpha = 0.8f),
                                letterSpacing = 0.4.sp,
                                fontWeight = FontWeight.Normal
                            ),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // BOTTOM: Chat Input
            ChatBar(
                onSend = {
                    viewModel.sendMessage(it)
                },
                onPlusClick = {
                    showScreenShareSheet = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            )
        }

        // Live Action Status Pill with Emergency Stop
        AnimatedVisibility(
            visible = agentActionStatus != null || globalState.taskState in listOf(
                com.example.state.TaskState.OPENING_APP,
                com.example.state.TaskState.LOOKING_AT_SCREEN,
                com.example.state.TaskState.FINDING_TARGET,
                com.example.state.TaskState.PERFORMING_ACTION,
                com.example.state.TaskState.EXECUTING,
                com.example.state.TaskState.VERIFYING
            ),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 80.dp)
        ) {
            val statusDisplay = agentActionStatus ?: globalState.taskStateDetail ?: globalState.taskState.name
            Surface(
                color = SurfaceGlass.copy(alpha = 0.95f),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryLight.copy(alpha = 0.4f)),
                tonalElevation = 8.dp
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = AccentCyan
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = statusDisplay,
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = Color.White,
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(
                        onClick = { viewModel.emergencyStop("User tapped STOP") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4444)),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text("STOP", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
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
