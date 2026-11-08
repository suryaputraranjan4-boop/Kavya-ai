package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import com.example.ui.components.AttachmentOptionItem
import com.example.ui.components.ChatBar
import com.example.ui.components.GlassCard
import com.example.ui.components.ScreenShareSheet
import com.example.ui.components.StatusBadge
import com.example.ui.components.VoiceState
import com.example.ui.components.glassPanel
import com.example.ui.theme.*
import com.example.utils.InstalledApp
import com.example.viewmodel.ChatMessage
import com.example.viewmodel.KavyaViewModel

import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import android.provider.OpenableColumns

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(navController: NavController, viewModel: KavyaViewModel) {
    val messages by viewModel.messages.collectAsState()
    val allChats by viewModel.allChats.collectAsState(initial = emptyList())
    val currentChatId by viewModel.currentChatId.collectAsState()
    val isScreenSharing by viewModel.isScreenSharing.collectAsState()
    val voiceState by viewModel.voiceState.collectAsState()

    val context = LocalContext.current
    var showHistory by remember { mutableStateOf(false) }
    var showScreenShareSheet by remember { mutableStateOf(false) }
    var showAttachmentPopup by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    // Option 1: Camera
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            viewModel.sendMessage("[Photo Attachment: ${bitmap.width}x${bitmap.height} px] I have captured this photo with my camera. Please inspect and analyze it.")
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
            try {
                val pdfDocument = PdfDocument()
                val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create()
                val page = pdfDocument.startPage(pageInfo)
                page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                pdfDocument.finishPage(page)

                val fileName = "scanned_doc_${System.currentTimeMillis()}.pdf"
                val file = File(context.cacheDir, fileName)
                FileOutputStream(file).use { out ->
                    pdfDocument.writeTo(out)
                }
                pdfDocument.close()
                viewModel.sendMessage("[Scanned PDF Document: $fileName (${file.length()} bytes)] The scanned PDF document is ready and attached.")
                Toast.makeText(context, "Document scanned: $fileName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                viewModel.sendMessage("PDF scan failed: ${e.localizedMessage}")
            }
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
                viewModel.sendMessage("[Gallery Image Attached: $displayName (${fileSize / 1024} KB)] Please analyze this image from my gallery.")
            } catch (e: Exception) {
                viewModel.sendMessage("[Gallery Image Attached: ${uri.lastPathSegment ?: "image"}]")
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
                viewModel.sendMessage("[PDF Document Attached: $displayName (${fileSize / 1024} KB)] Please inspect and extract key insights from this PDF.")
            } catch (e: Exception) {
                viewModel.sendMessage("[PDF Document Attached: ${uri.lastPathSegment ?: "document.pdf"}]")
            }
        }
    }

    // Option 6: Documents Picker with real text extraction
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
                    val rawText = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    val snippet = if (rawText.length > 2500) rawText.take(2500) + "\n...[truncated for length]" else rawText
                    viewModel.sendMessage("[Document Attached: $displayName]\n\nDocument Contents:\n$snippet\n\nPlease review and explain this document.")
                } else {
                    viewModel.sendMessage("[Document Attached: $displayName ($mimeType)] Please review this document.")
                }
            } catch (e: Exception) {
                viewModel.sendMessage("[Document Attached: ${uri.lastPathSegment ?: "document"}]")
            }
        }
    }

    val micEngineState by com.example.voice.KavyaMicrophoneEngine.engineState.collectAsState()
    val isMicListening = micEngineState == com.example.voice.KavyaMicrophoneEngine.EngineState.MIC_ACTIVE ||
            micEngineState == com.example.voice.KavyaMicrophoneEngine.EngineState.MIC_SLEEPING

    val currentChatTitle = allChats.find { it.id == currentChatId }?.title ?: "Conversation"

    Scaffold(
        containerColor = Background,
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = currentChatTitle,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (isScreenSharing) SuccessGreen else Primary)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (isScreenSharing) "Screen Share Active" else "Ready",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = if (isScreenSharing) SuccessGreenGlow else TextSecondary
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showScreenShareSheet = true }) {
                        Icon(
                            imageVector = if (isScreenSharing) Icons.Default.ScreenShare else Icons.Default.MobileScreenShare,
                            contentDescription = "Share Screen",
                            tint = if (isScreenSharing) SuccessGreen else TextSecondary
                        )
                    }
                    IconButton(onClick = { viewModel.startNewChat() }) {
                        Icon(Icons.Default.Add, contentDescription = "New Chat", tint = TextSecondary)
                    }
                    IconButton(onClick = { showHistory = true }) {
                        Icon(Icons.Default.History, contentDescription = "History", tint = TextSecondary)
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = TextSecondary)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier
                            .background(SurfaceGlass)
                            .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                    ) {
                        DropdownMenuItem(
                            text = { Text("Screen Share & Vision ✨", color = TextPrimary) },
                            onClick = {
                                showScreenShareSheet = true
                                menuExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete Conversation", color = Error) },
                            onClick = {
                                viewModel.deleteCurrentChat()
                                menuExpanded = false
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Background
                )
            )
        },
        bottomBar = {
            ChatBar(
                onSend = { viewModel.sendMessage(it) },
                onPlusClick = { showAttachmentPopup = !showAttachmentPopup },
                onMicClick = {
                    if (isMicListening) {
                        viewModel.stopListening()
                    } else {
                        viewModel.startListening()
                    }
                },
                isMicListening = isMicListening,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .navigationBarsPadding()
                    .imePadding()
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Screen Share Active Ribbon Banner
                AnimatedVisibility(visible = isScreenSharing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .glassPanel(shape = RoundedCornerShape(12.dp), backgroundColor = SurfaceGlass.copy(alpha = 0.9f), borderColor = SuccessGreen.copy(alpha = 0.3f))
                        .clickable { showScreenShareSheet = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(SuccessGreen)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Screen Share Active • Observing",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = SuccessGreenGlow
                            )
                        }
                        Text(
                            "Manage",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = PrimaryLight
                            )
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                reverseLayout = true
            ) {
                item { Spacer(modifier = Modifier.height(16.dp)) }
                items(messages.reversed(), key = { it.id }) { msg ->
                    MessageBubble(
                        message = msg,
                        onSpeak = { viewModel.speakMessage(it) },
                        onStopSpeak = { viewModel.stopSpeaking() },
                        onSelectCandidate = { app, orig -> viewModel.selectCandidateApp(app, orig) }
                    )
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }

        // Floating White Liquid Glass Popup anchored near Plus button
        if (showAttachmentPopup) {
            Popup(
                alignment = Alignment.BottomStart,
                offset = IntOffset(
                    x = with(LocalDensity.current) { 16.dp.roundToPx() },
                    y = with(LocalDensity.current) { (-6).dp.roundToPx() }
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
                                        Color(0xCCFFFFFF),
                                        Color(0x80FFFFFF),
                                        Color.White
                                    )
                                )
                            ),
                            shape = RoundedCornerShape(22.dp)
                        ),
                    color = Color(0xF9FFFFFF),
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
                                    color = Color(0xFF64748B)
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
                                    tint = Color(0xFF64748B),
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
    }

        if (showScreenShareSheet) {
            ScreenShareSheet(
                viewModel = viewModel,
                onDismiss = { showScreenShareSheet = false },
                onNavigateToChat = { showScreenShareSheet = false }
            )
        }

        if (showHistory) {
            ModalBottomSheet(
                onDismissRequest = { showHistory = false },
                containerColor = Surface,
                contentColor = TextPrimary,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                        .fillMaxWidth()
                ) {
                    Text(
                        "Chat History",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextPrimary)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(allChats) { chat ->
                            GlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                backgroundColor = SurfaceGlass,
                                onClick = {
                                    viewModel.loadChat(chat.id)
                                    showHistory = false
                                }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = chat.title,
                                        style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { viewModel.deleteChat(chat.id) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = TextTertiary, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MessageBubble(
    message: ChatMessage,
    onSpeak: (String) -> Unit,
    onStopSpeak: () -> Unit,
    onSelectCandidate: (InstalledApp, String) -> Unit
) {
    val isUser = message.isUser
    val isLoading = message.isLoading
    val isError = message.isError

    val bubbleShape = if (isUser) {
        RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
    } else {
        RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
    }

    var isSpeaking by remember { mutableStateOf(false) }
    var showDiagnosticDetails by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(bubbleShape)
                .background(
                    if (isUser) {
                        Brush.linearGradient(
                            listOf(
                                BrandGradientStart.copy(alpha = 0.85f),
                                BrandGradientEnd.copy(alpha = 0.85f)
                            )
                        )
                    } else if (isError) {
                        Brush.linearGradient(
                            listOf(
                                ErrorContainer,
                                SurfaceGlass
                            )
                        )
                    } else {
                        Brush.linearGradient(
                            listOf(
                                SurfaceGlass.copy(alpha = 0.95f),
                                SurfaceVariant.copy(alpha = 0.95f)
                            )
                        )
                    }
                )
                .border(
                    1.dp,
                    if (isUser) Color.White.copy(alpha = 0.25f) else GlassBorder,
                    bubbleShape
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Column {
                if (isLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = PrimaryLight,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = message.text,
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    Text(
                        text = message.text,
                        color = if (isUser) Color.White else TextPrimary,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 14.5.sp,
                            lineHeight = 21.sp
                        )
                    )

                    // Multiple app disambiguation
                    if (message.isAmbiguous && message.candidateApps.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            "Which one did you mean?",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = PrimaryLight
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            message.candidateApps.forEach { candidate ->
                                OutlinedButton(
                                    onClick = { onSelectCandidate(candidate, message.actionParam ?: message.text) },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = SurfaceElevated.copy(alpha = 0.8f),
                                        contentColor = TextPrimary
                                    ),
                                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = Brush.linearGradient(listOf(Outline, GlassBorder))),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(16.dp), tint = PrimaryLight)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                                        Text(candidate.appName, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
                                        Text(candidate.packageName, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = TextTertiary)
                                    }
                                }
                            }
                        }
                    }

                    // Action outcome badge
                    if (message.actionType != null && message.actionParam != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(SurfaceElevated.copy(alpha = 0.9f))
                                .border(1.dp, GlassBorder, RoundedCornerShape(10.dp))
                                .padding(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(message.actionType, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, color = PrimaryLight))
                                    Text(message.actionParam, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, color = TextSecondary))
                                }
                            }
                        }
                    }

                    // Diagnostic info details
                    if (message.diagnostic != null) {
                        val diag = message.diagnostic
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Background.copy(alpha = 0.8f))
                                .border(1.dp, Outline, RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showDiagnosticDetails = !showDiagnosticDetails },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            if (diag.verification.startsWith("PASS")) Icons.Default.CheckCircle else Icons.Default.Error,
                                            contentDescription = null,
                                            tint = if (diag.verification.startsWith("PASS")) SuccessGreen else Error,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            "Verification: ${diag.verification.substringBefore(" -")}",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = TextSecondary)
                                        )
                                    }
                                    Text(
                                        if (showDiagnosticDetails) "Hide" else "Trace",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = PrimaryLight)
                                    )
                                }

                                if (showDiagnosticDetails) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    HorizontalDivider(color = OutlineVariant)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text("• Target: ${diag.requestedApp}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextSecondary))
                                    Text("• Resolved: ${diag.resolvedApp}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextSecondary))
                                    Text("• Package: ${diag.packageName}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextSecondary))
                                    Text("• Intent: ${diag.launchIntent}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextSecondary))
                                }
                            }
                        }
                    }

                    // Voice Speaker
                    if (!isUser && !isError) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            IconButton(
                                onClick = {
                                    if (isSpeaking) {
                                        onStopSpeak()
                                        isSpeaking = false
                                    } else {
                                        onSpeak(message.text)
                                        isSpeaking = true
                                    }
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = if (isSpeaking) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = "Speak message",
                                    tint = PrimaryLight.copy(alpha = 0.8f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


