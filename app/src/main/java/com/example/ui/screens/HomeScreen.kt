package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
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

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.ScreenShare
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream

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
    var showAttachmentPopup by remember { mutableStateOf(false) }
    var scannedPages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var showScanReview by remember { mutableStateOf(false) }
    val isScreenSharing by viewModel.isScreenSharing.collectAsState()
    val latestCaption by viewModel.latestKavyaCaption.collectAsState()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsState()
    val agentActionStatus by viewModel.agentActionStatus.collectAsState()
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()
    val micEngineState by com.example.voice.KavyaMicrophoneEngine.engineState.collectAsState()
    val micAmplitude by com.example.voice.KavyaMicrophoneEngine.amplitude.collectAsState()
    val guidanceState by com.example.visual.JarvisGuidanceEngine.guidanceState.collectAsState()
    val activeExplanation by com.example.visual.JarvisGuidanceEngine.activeExplanation.collectAsState()

    // Option 1: Camera
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            viewModel.analyzeImage(bitmap, "Camera photo")
            navController.navigate("chat")
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            cameraLauncher.launch(null)
        } else {
            Toast.makeText(context, "Camera permission required to capture photos", Toast.LENGTH_SHORT).show()
        }
    }

    // Option 2: PDF Scanner (Real document scanner creating PDF)
    val pdfScanLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            scannedPages = scannedPages + bitmap
            showScanReview = true
        }
    }
    val pdfScanPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            pdfScanLauncher.launch(null)
        } else {
            Toast.makeText(context, "Camera permission required for PDF scanning", Toast.LENGTH_SHORT).show()
        }
    }

    // Option 4: Gallery Photo Picker
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            try {
                var displayName = "image.jpg"
                var fileSize = 0L
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0) displayName = cursor.getString(nameIdx)
                        if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx)
                    }
                }
                val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    ?: throw IllegalArgumentException("Unable to read the selected image.")
                val maxDimension = 1600
                val largest = maxOf(decoded.width, decoded.height)
                val analysisBitmap = if (largest > maxDimension) {
                    val scale = maxDimension.toFloat() / largest.toFloat()
                    Bitmap.createScaledBitmap(
                        decoded,
                        (decoded.width * scale).toInt().coerceAtLeast(1),
                        (decoded.height * scale).toInt().coerceAtLeast(1),
                        true
                    ).also { if (it !== decoded) decoded.recycle() }
                } else decoded
                viewModel.analyzeImage(analysisBitmap, displayName)
                navController.navigate("chat")
            } catch (e: Exception) {
                Toast.makeText(context, "Unable to read the selected image. Please try again.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Option 5: PDF Files Picker
    val pdfFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                var displayName = "document.pdf"
                var fileSize = 0L
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0) displayName = cursor.getString(nameIdx)
                        if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx)
                    }
                }
                val pdfCopy = File(context.cacheDir, "selected_pdf_${System.currentTimeMillis()}.pdf")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(pdfCopy).use { output -> input.copyTo(output) }
                } ?: throw IllegalArgumentException("Unable to open selected PDF.")
                if (pdfCopy.length() <= 0L) throw IllegalArgumentException("Selected PDF is empty.")
                viewModel.analyzePdf(pdfCopy, displayName)
                navController.navigate("chat")
            } catch (e: Exception) {
                Toast.makeText(context, "Unable to read the selected PDF. Please try again.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Option 6: Documents Picker with text extraction
    val docFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                var displayName = "document"
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0) displayName = cursor.getString(nameIdx)
                    }
                }
                val mimeType = context.contentResolver.getType(uri) ?: ""
                val isText = mimeType.startsWith("text/") || 
                        displayName.endsWith(".txt", true) || 
                        displayName.endsWith(".md", true) || 
                        displayName.endsWith(".csv", true) || 
                        displayName.endsWith(".json", true) || 
                        displayName.endsWith(".xml", true)

                if (isText) {
                    val rawText = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                        val buffer = CharArray(20000)
                        val output = StringBuilder()
                        while (output.length < 100000) {
                            val remaining = 100000 - output.length
                            val count = reader.read(buffer, 0, minOf(buffer.size, remaining))
                            if (count <= 0) break
                            output.append(buffer, 0, count)
                        }
                        output.toString()
                    } ?: throw IllegalArgumentException("Unable to read selected document.")
                    if (rawText.isBlank()) throw IllegalArgumentException("Selected document is empty.")
                    val snippet = if (rawText.length >= 100000) rawText + "\n[Content limit reached: first 100,000 characters included.]" else rawText
                    viewModel.sendMessage("[Document: $displayName]\n\nDocument Contents:\n$snippet\n\nPlease review and explain this document.")
                } else {
                    viewModel.sendMessage("I can identify this file ($displayName, $mimeType), but this file format is not yet supported for content extraction. Please choose a TXT, MD, CSV, JSON, or XML file, or use PDF Files for a PDF.")
                }
                navController.navigate("chat")
            } catch (e: Exception) {
                Toast.makeText(context, "Unable to read the selected document. Please try again.", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    val drawerState = LocalDrawerState.current
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black) // Extreme minimalist dark background
            .graphicsLayer {
                renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && showAttachmentPopup) {
                    android.graphics.RenderEffect.createBlurEffect(
                        16f, 16f, android.graphics.Shader.TileMode.CLAMP
                    ).asComposeRenderEffect()
                } else null
            }
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

            // JARVIS "Shows You How" Guidance Banner
            AnimatedVisibility(visible = guidanceState.isActive) {
                JarvisGuidanceBanner(
                    guidance = guidanceState,
                    onDismiss = { com.example.visual.JarvisGuidanceEngine.clearGuidance() },
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }

            // JARVIS "Explains Anything" Card
            AnimatedVisibility(visible = activeExplanation != null) {
                activeExplanation?.let { card ->
                    JarvisExplanationCardView(
                        card = card,
                        onDismiss = { com.example.visual.JarvisGuidanceEngine.dismissExplanation() },
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            }

            // Quick Action Capsules
            val suggestions = listOf("Explain Screen", "JARVIS Guide", "YouTube खोलो", "WhatsApp खोलो", "Google पर सर्च करो", "Torch ON", "Settings")
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
                    showAttachmentPopup = !showAttachmentPopup
                },
                onMicClick = {
                    if (
                        micEngineState == com.example.voice.KavyaMicrophoneEngine.EngineState.MIC_OFF ||
                        micEngineState == com.example.voice.KavyaMicrophoneEngine.EngineState.MIC_ERROR
                    ) {
                        viewModel.startListening()
                    } else {
                        viewModel.stopListening()
                    }
                },
                isMicListening =
                    micEngineState == com.example.voice.KavyaMicrophoneEngine.EngineState.MIC_ACTIVE ||
                    micEngineState == com.example.voice.KavyaMicrophoneEngine.EngineState.MIC_SLEEPING ||
                    micEngineState == com.example.voice.KavyaMicrophoneEngine.EngineState.MIC_STARTING,
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

    // Floating White Liquid Glass Popup anchored near Plus button
    if (showAttachmentPopup) {
        Popup(
            alignment = Alignment.BottomStart,
            offset = IntOffset(
                x = with(LocalDensity.current) { 24.dp.roundToPx() },
                y = with(LocalDensity.current) { (-80).dp.roundToPx() }
            ),
            onDismissRequest = { showAttachmentPopup = false },
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            Surface(
                modifier = Modifier
                    .width(285.dp)
                    .shadow(
                        elevation = 18.dp,
                        shape = RoundedCornerShape(22.dp),
                        spotColor = Color(0x38000000),
                        ambientColor = Color(0x1A000000)
                    )
                    .border(
                        border = BorderStroke(
                            1.2.dp,
                            Brush.linearGradient(
                                listOf(
                                    Color.White,
                                    Color(0xB3FFFFFF),
                                    Color(0x80FFFFFF),
                                    Color.White
                                )
                            )
                        ),
                        shape = RoundedCornerShape(22.dp)
                    ),
                color = Color(0xB3FFFFFF),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "ATTACH & SHARE",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.2.sp,
                                color = Color.White,
                                shadow = Shadow(Color(0xF0000000), Offset(0f, 1.5f), 4.5f)
                            ),
                            modifier = Modifier.padding(start = 4.dp)
                        )
                        IconButton(
                            onClick = { showAttachmentPopup = false },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close attachments",
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Row 1: Camera, PDF Scanner, Live Screen
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        AttachmentOptionItem(
                            icon = Icons.Default.PhotoCamera,
                            label = "Camera",
                            onClick = {
                                showAttachmentPopup = false
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                    cameraLauncher.launch(null)
                                } else {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            }
                        )
                        AttachmentOptionItem(
                            icon = Icons.Default.DocumentScanner,
                            label = "PDF Scanner",
                            onClick = {
                                showAttachmentPopup = false
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                    pdfScanLauncher.launch(null)
                                } else {
                                    pdfScanPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            }
                        )
                        AttachmentOptionItem(
                            icon = Icons.Default.ScreenShare,
                            label = "Live Screen",
                            onClick = {
                                showAttachmentPopup = false
                                showScreenShareSheet = true
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Row 2: Gallery, PDF Files, Documents
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        AttachmentOptionItem(
                            icon = Icons.Default.PhotoLibrary,
                            label = "Gallery",
                            onClick = {
                                showAttachmentPopup = false
                                galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        )
                        AttachmentOptionItem(
                            icon = Icons.Default.PictureAsPdf,
                            label = "PDF Files",
                            onClick = {
                                showAttachmentPopup = false
                                pdfFileLauncher.launch(arrayOf("application/pdf"))
                            }
                        )
                        AttachmentOptionItem(
                            icon = Icons.Default.FolderOpen,
                            label = "Documents",
                            onClick = {
                                showAttachmentPopup = false
                                docFileLauncher.launch(arrayOf("*/*"))
                            }
                        )
                    }
                }
            }
        }
    }

    if (showScanReview) {
        AlertDialog(
            onDismissRequest = { showScanReview = false },
            title = { Text("PDF Scanner • ${scannedPages.size} page(s)") },
            text = { Text("Page captured. Add another page or finish to create and analyze a multi-page PDF.") },
            confirmButton = {
                TextButton(onClick = {
                    showScanReview = false
                    pdfScanLauncher.launch(null)
                }) { Text("Add page") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        scannedPages.forEach { it.recycle() }
                        scannedPages = emptyList()
                        showScanReview = false
                    }) { Text("Discard") }
                    TextButton(onClick = {
                        val pages = scannedPages.toList()
                        showScanReview = false
                        scannedPages = emptyList()
                        try {
                            val document = PdfDocument()
                            pages.forEachIndexed { index, bitmap ->
                                val info = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, index + 1).create()
                                val page = document.startPage(info)
                                page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                                document.finishPage(page)
                            }
                            val file = File(context.cacheDir, "scanned_doc_${System.currentTimeMillis()}.pdf")
                            FileOutputStream(file).use { document.writeTo(it) }
                            document.close()
                            pages.forEach { it.recycle() }
                            viewModel.analyzePdf(file, file.name)
                            Toast.makeText(context, "Created ${pages.size}-page PDF", Toast.LENGTH_SHORT).show()
                            navController.navigate("chat")
                        } catch (e: Exception) {
                            pages.forEach { if (!it.isRecycled) it.recycle() }
                            Toast.makeText(context, "PDF scan failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                        }
                    }) { Text("Finish") }
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

