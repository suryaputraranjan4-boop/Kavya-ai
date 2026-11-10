package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.ui.components.GlassCard
import com.example.ui.components.StatusBadge
import com.example.ui.components.TimelineItem
import com.example.ui.components.glassPanel
import com.example.ui.theme.*
import com.example.viewmodel.KavyaViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleScreen(
    title: String,
    navController: NavController,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit
) {
    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Background, BackgroundGradientEnd)
                    )
                )
        ) {
            content()
        }
    }
}

// -------------------------------------------------------------
// 1. ACTIVITY & TIMELINE SCREEN
// -------------------------------------------------------------
@Composable
fun ActivityScreen(navController: NavController, viewModel: KavyaViewModel) {
    val history by viewModel.commandHistory.collectAsState()
    val isSharing by viewModel.isScreenSharing.collectAsState()
    var selectedFilter by remember { mutableStateOf("All") }

    SimpleScreen("Activity Timeline", navController) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Interaction History & System Events",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Filter Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("All", "Voice", "Screen Vision", "Actions").forEach { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = { Text(filter, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Primary.copy(alpha = 0.25f),
                                selectedLabelColor = PrimaryLight,
                                containerColor = SurfaceGlass,
                                labelColor = TextSecondary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selectedFilter == filter,
                                borderColor = if (selectedFilter == filter) Primary else GlassBorder
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Live Screen Share Session Indicator if active
            if (isSharing && (selectedFilter == "All" || selectedFilter == "Screen Vision")) {
                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = SurfaceGlass.copy(alpha = 0.9f),
                        borderColor = SuccessGreen.copy(alpha = 0.4f),
                        onClick = { navController.navigate("screenshare") }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(SuccessGreen.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.ScreenShare, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Live Screen Session Active", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = TextPrimary)
                                Text("Observing foreground apps & elements", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = SuccessGreenGlow)
                            }
                            StatusBadge("Live", isActive = true, activeColor = SuccessGreen)
                        }
                    }
                }
            }

            if (history.isEmpty() && (!isSharing || selectedFilter == "Actions")) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(SurfaceGlass)
                                .border(1.dp, GlassBorder, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Timeline, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(32.dp))
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("No recent interactions", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = TextPrimary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Execute voice commands or share screen to see activity log.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }
            } else {
                items(history.size) { index ->
                    val item = history[index]
                    val diag = item.diagnostic
                    val isPass = diag?.verification?.startsWith("PASS") != false

                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        backgroundColor = SurfaceGlass.copy(alpha = 0.8f)
                    ) {
                        TimelineItem(
                            icon = if (isPass) Icons.Default.CheckCircle else Icons.Default.Error,
                            iconTint = if (isPass) SuccessGreen else Error,
                            iconBgColor = (if (isPass) SuccessGreen else Error).copy(alpha = 0.12f),
                            time = "Result: ${item.result}",
                            action = item.command,
                            subText = if (diag != null) "Target: ${diag.resolvedApp}" else null
                        )

                        if (diag != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            HorizontalDivider(color = OutlineVariant)
                            Spacer(modifier = Modifier.height(10.dp))

                            Text("Diagnostic Trace:", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = PrimaryLight))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("• Package: ${diag.packageName}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextSecondary))
                            Text("• Verification: ${diag.verification}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = if (isPass) SuccessGreenGlow else Error))
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// 2. SETTINGS SCREEN (Reorganized into clean glassmorphic sections)
// -------------------------------------------------------------
@Composable
fun SettingsScreen(navController: NavController) {
    val context = androidx.compose.ui.platform.LocalContext.current

    SimpleScreen("Settings", navController) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Group 1: Companion & Voice
            item {
                SettingsSectionHeader("Companion & Voice ✨")
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    backgroundColor = SurfaceGlass.copy(alpha = 0.75f)
                ) {
                    SettingRow("Voice & AI Persona ✨", "Natural female voice, sentiment tones & pacing", Icons.Default.RecordVoiceOver, Primary) {
                        navController.navigate("voice_settings")
                    }
                    HorizontalDivider(color = OutlineVariant, modifier = Modifier.padding(vertical = 4.dp))
                    SettingRow("Proactive Conversation 💬", "Natural follow-ups, screen context & silence rules", Icons.Default.ChatBubble, AccentCyan) {
                        navController.navigate("proactive_settings")
                    }
                }
            }

            // Group 2: Vision & Setup
            item {
                SettingsSectionHeader("Vision & Setup 🛡️")
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    backgroundColor = SurfaceGlass.copy(alpha = 0.75f)
                ) {
                    SettingRow("API Dashboard", "OpenRouter, Hugging Face, Gemini & Custom APIs", Icons.Default.VpnKey, AccentPurpleLight) {
                        navController.navigate("ai_api_hub")
                    }
                    HorizontalDivider(color = OutlineVariant, modifier = Modifier.padding(vertical = 4.dp))
                    SettingRow("Permissions & Service Status", "Accessibility & Audio", Icons.Default.Security, SuccessGreen) {
                        navController.navigate("permissions")
                    }
                    HorizontalDivider(color = OutlineVariant, modifier = Modifier.padding(vertical = 4.dp))
                    SettingRow("Run Setup Flow Again", "First-launch configuration & permissions wizard", Icons.Default.RestartAlt, PrimaryLight) {
                        navController.navigate("onboarding")
                    }
                    HorizontalDivider(color = OutlineVariant, modifier = Modifier.padding(vertical = 4.dp))
                    SettingRow("Share Screen & Vision", "Live perception feed & screen controls", Icons.Default.ScreenShare, AccentCyan) {
                        navController.navigate("screenshare")
                    }
                    HorizontalDivider(color = OutlineVariant, modifier = Modifier.padding(vertical = 4.dp))
                    SettingRow("Automations & Routines", "Multi-step shortcuts & actions", Icons.Default.AutoMode, AccentPurpleLight) {
                        navController.navigate("routines")
                    }
                    HorizontalDivider(color = OutlineVariant, modifier = Modifier.padding(vertical = 4.dp))
                    SettingRow("Visual Action Engine 🎮", "Human-like screen control, games & emergency stop", Icons.Default.TouchApp, AccentCyan) {
                        navController.navigate("visual_action_control")
                    }
                }
            }

            // Group 3: Preferences & Diagnostics
            item {
                SettingsSectionHeader("System & Preferences ⚙️")
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    backgroundColor = SurfaceGlass.copy(alpha = 0.75f)
                ) {
                    SettingRow("Automation Diagnostics", "Live state, task traces & self-test", Icons.Default.BugReport, PrimaryLight) {
                        navController.navigate("automation_debug")
                    }
                    HorizontalDivider(color = OutlineVariant, modifier = Modifier.padding(vertical = 4.dp))
                    SettingRow("Appearance", "Dark futuristic themes", Icons.Default.Palette, Secondary) {
                        navController.navigate("appearance")
                    }
                    HorizontalDivider(color = OutlineVariant, modifier = Modifier.padding(vertical = 4.dp))
                    SettingRow("Language", "Voice model language settings", Icons.Default.Language, TextSecondary) {
                        navController.navigate("language")
                    }
                    HorizontalDivider(color = OutlineVariant, modifier = Modifier.padding(vertical = 4.dp))
                    SettingRow("Assistant Memory", "Learned app aliases & preferences", Icons.Default.Memory, TextSecondary) {
                        navController.navigate("memory")
                    }
                    HorizontalDivider(color = OutlineVariant, modifier = Modifier.padding(vertical = 4.dp))
                    SettingRow("Advanced", "System configuration & tweaks", Icons.Default.SettingsApplications, TextSecondary) {
                        navController.navigate("advanced")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = TextSecondary,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = TextTertiary)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(20.dp))
    }
}

// -------------------------------------------------------------
// 3. VOICE STUDIO SCREEN (Chatterbox Hindi & Custom Voice Conditioning)
// -------------------------------------------------------------
@Composable
fun VoiceSettingsScreen(navController: NavController, viewModel: KavyaViewModel? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val voiceEngineManager = remember { com.example.ai.tts.VoiceEngineManager.getInstance(context) }
    val indicModelManager = remember { com.example.ai.tts.IndicTinyTtsModelManager.getInstance(context) }
    val chatterboxModelManager = remember { com.example.ai.ChatterboxModelManager.getInstance(context) }
    val profileManager = remember { com.example.ai.VoiceProfileManager.getInstance(context) }
    val ttsClient = remember { com.example.ai.ChatterboxTtsClient(context) }

    val activeEngineState = remember { mutableStateOf(voiceEngineManager.getActiveEngine()) }

    val indicStatus by indicModelManager.status.collectAsState()
    val indicProgress by indicModelManager.downloadProgress.collectAsState()
    val indicProgressText by indicModelManager.progressText.collectAsState()

    val chatterboxFileMap by chatterboxModelManager.fileProgressMap.collectAsState()
    val profiles by profileManager.profiles.collectAsState()
    val activeProfileId = com.example.utils.AppPreferences.getActiveVoiceProfileId(context)

    var backendUrl by remember { mutableStateOf(com.example.utils.AppPreferences.getChatterboxBackendUrl(context)) }
    var backendToken by remember { mutableStateOf(com.example.utils.AppPreferences.getChatterboxAuthToken(context)) }
    var backendHealthStatus by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var isCheckingHealth by remember { mutableStateOf(false) }

    var pitch by remember { mutableStateOf(com.example.utils.AppPreferences.getVoicePitch(context)) }
    var speed by remember { mutableStateOf(com.example.utils.AppPreferences.getVoiceSpeed(context)) }

    // Recording state
    var isRecording by remember { mutableStateOf(false) }
    var recordTimeSeconds by remember { mutableStateOf(0) }
    var recordedFile by remember { mutableStateOf<java.io.File?>(null) }
    var showNameDialog by remember { mutableStateOf(false) }
    var newProfileName by remember { mutableStateOf("") }
    var renameTargetId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    // Audio upload launcher
    val audioPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val profile = profileManager.importVoiceSample("Uploaded Voice", stream)
                    if (profile != null) {
                        android.widget.Toast.makeText(context, "Voice profile imported!", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Failed to import audio: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Permission launcher for recording
    val recordAudioPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            try {
                profileManager.startRecording()
                isRecording = true
                recordTimeSeconds = 0
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Recording failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            android.widget.Toast.makeText(context, "Microphone permission required for voice cloning", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            while (isRecording) {
                kotlinx.coroutines.delay(1000)
                recordTimeSeconds++
            }
        }
    }

    SimpleScreen("Kavya AI Voice Studio", navController) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header: Indic Tiny TTS Hindi Primary Engine
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    backgroundColor = SurfaceGlass.copy(alpha = 0.85f),
                    borderColor = Primary.copy(alpha = 0.4f)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🎙️", fontSize = 28.sp)
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                "Indic Tiny TTS Hindi Engine",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "Primary offline 24 kHz Hindi female voice engine. Fast ONNX CPU inference with lightweight model weights.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            // SECTION 1: VOICE ENGINE SELECTOR (PRIMARY & ALTERNATIVE)
            item {
                Text("Voice Engine Selection", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        com.example.ai.tts.VoiceEngine.entries.forEach { engine ->
                            val isSelected = activeEngineState.value == engine
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) Primary.copy(alpha = 0.15f) else SurfaceElevated.copy(alpha = 0.4f))
                                    .clickable {
                                        activeEngineState.value = engine
                                        voiceEngineManager.setActiveEngine(engine)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        activeEngineState.value = engine
                                        voiceEngineManager.setActiveEngine(engine)
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = PrimaryLight)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            engine.displayName,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium),
                                            color = if (isSelected) PrimaryLight else TextPrimary
                                        )
                                        if (engine == com.example.ai.tts.VoiceEngine.INDIC_TINY_TTS) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            StatusBadge(text = "DEFAULT • PRIMARY", isActive = true)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        if (engine.isOffline) "Offline ONNX Model • 24 kHz Female Voice • Low Latency" else "Cloud/Server Voice Conditioning • Multilingual Hindi",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Live Sample Voice Preview
                        Button(
                            onClick = {
                                voiceEngineManager.previewEngine(activeEngineState.value)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("🔊 Play Voice Sample (\"नमस्ते! मैं काव्या हूँ।\")")
                        }
                    }
                }
            }

            // SECTION 2: INDIC TINY TTS HINDI MODEL MANAGEMENT (HUGGING FACE)
            item {
                Text("Indic Tiny TTS Hindi Model (Hugging Face)", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("sulabhkatiyar/indic-tiny-tts-hindi", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = TextPrimary)
                                Text(
                                    indicProgressText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when (indicStatus) {
                                        com.example.ai.tts.ModelDownloadStatus.READY -> SuccessGreen
                                        com.example.ai.tts.ModelDownloadStatus.DOWNLOADING -> PrimaryLight
                                        com.example.ai.tts.ModelDownloadStatus.ERROR -> Error
                                        else -> AccentYellow
                                    }
                                )
                            }
                            StatusBadge(
                                text = when (indicStatus) {
                                    com.example.ai.tts.ModelDownloadStatus.READY -> "Installed"
                                    com.example.ai.tts.ModelDownloadStatus.DOWNLOADING -> "Downloading"
                                    com.example.ai.tts.ModelDownloadStatus.PAUSED -> "Paused"
                                    com.example.ai.tts.ModelDownloadStatus.VERIFYING -> "Verifying"
                                    com.example.ai.tts.ModelDownloadStatus.ERROR -> "Error"
                                    else -> "Ready"
                                },
                                isActive = indicStatus == com.example.ai.tts.ModelDownloadStatus.READY
                            )
                        }

                        if (indicStatus == com.example.ai.tts.ModelDownloadStatus.DOWNLOADING || indicStatus == com.example.ai.tts.ModelDownloadStatus.PAUSED) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { indicProgress },
                                modifier = Modifier.fillMaxWidth(),
                                color = PrimaryLight,
                                trackColor = SurfaceElevated
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // ONNX File Specs list
                        com.example.ai.tts.IndicTinyTtsModelManager.MODEL_SPECS.forEach { spec ->
                            val file = java.io.File(indicModelManager.modelDir, spec.fileName)
                            val exists = file.exists() && file.length() > 0
                            val sizeMb = if (exists) file.length() / (1024.0 * 1024.0) else spec.estimatedSizeBytes / (1024.0 * 1024.0)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(spec.fileName, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = TextPrimary)
                                    Text(
                                        "${"%.2f".format(sizeMb)} MB • ${if (exists) "Available on disk" else "Pending download"}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = TextSecondary
                                    )
                                }
                                Icon(
                                    if (exists) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (exists) SuccessGreen else TextTertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Model Management Action Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            when (indicStatus) {
                                com.example.ai.tts.ModelDownloadStatus.DOWNLOADING -> {
                                    Button(
                                        onClick = { indicModelManager.pauseDownload() },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated)
                                    ) {
                                        Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Pause")
                                    }
                                    Button(
                                        onClick = { indicModelManager.cancelDownload() },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Error.copy(alpha = 0.8f))
                                    ) {
                                        Text("Cancel")
                                    }
                                }
                                com.example.ai.tts.ModelDownloadStatus.PAUSED -> {
                                    Button(
                                        onClick = { indicModelManager.resumeDownload() },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Resume")
                                    }
                                    Button(
                                        onClick = { indicModelManager.cancelDownload() },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated)
                                    ) {
                                        Text("Cancel")
                                    }
                                }
                                com.example.ai.tts.ModelDownloadStatus.READY -> {
                                    Button(
                                        onClick = { indicModelManager.verifyModelAsync() },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated)
                                    ) {
                                        Icon(Icons.Default.Verified, contentDescription = null, modifier = Modifier.size(16.dp), tint = PrimaryLight)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Verify", color = PrimaryLight)
                                    }
                                    OutlinedButton(
                                        onClick = { indicModelManager.deleteModel() },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = Error)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Delete", color = Error)
                                    }
                                    Button(
                                        onClick = { indicModelManager.redownloadModel() },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Re-fetch")
                                    }
                                }
                                else -> {
                                    Button(
                                        onClick = { indicModelManager.downloadAll() },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Download Model")
                                    }
                                    Button(
                                        onClick = { indicModelManager.verifyModelAsync() },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated)
                                    ) {
                                        Icon(Icons.Default.Verified, contentDescription = null, modifier = Modifier.size(16.dp), tint = PrimaryLight)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Verify", color = PrimaryLight)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // SECTION 3: CHATTERBOX BACKEND / INFERENCE RUNNER
            item {
                Text("Chatterbox Inference Runner & GPU Backend", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(
                        "Speech generation for Chatterbox runs on a separate inference server. Enter its reachable LAN or HTTPS URL here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = backendUrl,
                        onValueChange = {
                            backendUrl = it
                            com.example.utils.AppPreferences.setChatterboxBackendUrl(context, it)
                        },
                        label = { Text("Chatterbox Backend URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = GlassBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = backendToken,
                        onValueChange = {
                            backendToken = it
                            com.example.utils.AppPreferences.setChatterboxAuthToken(context, it)
                        },
                        label = { Text("Auth Token (Optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = GlassBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                isCheckingHealth = true
                                coroutineScope.launch {
                                    val health = ttsClient.checkHealth()
                                    backendHealthStatus = health
                                    isCheckingHealth = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (isCheckingHealth) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = PrimaryLight, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Connecting...")
                            } else {
                                Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp), tint = PrimaryLight)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Test Connection", color = PrimaryLight)
                            }
                        }
                    }

                    backendHealthStatus?.let { (ok, msg) ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ok) SuccessGreen else Error
                        )
                    }
                }
            }

            // SECTION 4: VOICE PROFILES & ZERO-SHOT CLONING
            item {
                Text("Voice Profiles & Custom Cloning", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(
                        "Condition voice synthesis on your own voice. Uses recorded audio samples.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Record or Upload Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (isRecording) {
                                    val file = profileManager.stopRecording()
                                    isRecording = false
                                    if (file != null && file.exists() && file.length() > 0) {
                                        recordedFile = file
                                        showNameDialog = true
                                    }
                                } else {
                                    recordAudioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = if (isRecording) Error else Primary),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(if (isRecording) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isRecording) "Stop (${recordTimeSeconds}s)" else "Record Voice")
                        }

                        Button(
                            onClick = { audioPickerLauncher.launch("audio/*") },
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = null, tint = PrimaryLight)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Upload Sample", color = PrimaryLight)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text("Saved Voice Profiles", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = TextPrimary)
                    Spacer(modifier = Modifier.height(8.dp))

                    profiles.forEach { profile ->
                        val isSelected = profile.id == activeProfileId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) Primary.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable {
                                    com.example.utils.AppPreferences.setActiveVoiceProfileId(context, profile.id)
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(profile.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium), color = if (isSelected) PrimaryLight else TextPrimary)
                                Text(profile.description, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isSelected) {
                                    Icon(Icons.Default.Check, contentDescription = "Active", tint = PrimaryLight, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                if (!profile.isSystemPreset) {
                                    IconButton(
                                        onClick = {
                                            renameTargetId = profile.id
                                            renameText = profile.name
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Rename", tint = TextSecondary, modifier = Modifier.size(16.dp))
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    IconButton(
                                        onClick = { profileManager.deleteProfile(profile.id) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Error, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // SECTION 5: ACOUSTIC SLIDERS & SPEECH PREVIEW
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("Voice Pitch Multiplier", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = TextPrimary)
                    Slider(
                        value = pitch,
                        onValueChange = {
                            pitch = it
                            com.example.utils.AppPreferences.setVoicePitch(context, pitch)
                            viewModel?.setVoiceSettings(pitch, speed)
                        },
                        valueRange = 0.95f..1.25f,
                        colors = SliderDefaults.colors(thumbColor = Primary, activeTrackColor = Primary)
                    )
                    Text("Pitch: ${"%.2f".format(pitch)}x", style = MaterialTheme.typography.labelSmall, color = TextSecondary)

                    Spacer(modifier = Modifier.height(14.dp))

                    Text("Voice Speech Pace", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = TextPrimary)
                    Slider(
                        value = speed,
                        onValueChange = {
                            speed = it
                            com.example.utils.AppPreferences.setVoiceSpeed(context, speed)
                            viewModel?.setVoiceSettings(pitch, speed)
                        },
                        valueRange = 0.80f..1.25f,
                        colors = SliderDefaults.colors(thumbColor = AccentPurple, activeTrackColor = AccentPurple)
                    )
                    Text("Speed: ${"%.2f".format(speed)}x", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
            }

            // SECTION 6: REAL HINDI SPEECH TESTS
            item {
                Text("Hindi Speech Generation Tests", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            voiceEngineManager.speak("नमस्ते! मैं काव्या हूँ। आपकी क्या सहायता कर सकती हूँ?")
                        },
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Icon(Icons.Default.VolumeUp, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("🔊 Test Indic Tiny TTS Hindi Speech")
                    }

                    Button(
                        onClick = {
                            voiceEngineManager.speak("अरे वाह! आपका दिन बहुत अच्छा रहे। मैं हमेशा आपके साथ हूँ।")
                        },
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary.copy(alpha = 0.85f))
                    ) {
                        Icon(Icons.Default.Celebration, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Test Cheerful Hindi Speech")
                    }
                }
            }
        }
    }

    // Dialog for naming recorded profile
    if (showNameDialog && recordedFile != null) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("Save Voice Profile") },
            text = {
                Column {
                    Text("Give a name to your new voice profile:")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newProfileName,
                        onValueChange = { newProfileName = it },
                        placeholder = { Text("e.g. My Warm Voice") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newProfileName.isNotBlank() && recordedFile != null) {
                        profileManager.saveVoiceProfile(newProfileName, recordedFile!!, recordTimeSeconds)
                        showNameDialog = false
                        newProfileName = ""
                        recordedFile = null
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    showNameDialog = false
                    recordedFile?.delete()
                    recordedFile = null
                }) { Text("Discard") }
            }
        )
    }

    // Dialog for renaming profile
    if (renameTargetId != null) {
        AlertDialog(
            onDismissRequest = { renameTargetId = null },
            title = { Text("Rename Voice Profile") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    val id = renameTargetId
                    if (id != null && renameText.isNotBlank()) {
                        profileManager.renameProfile(id, renameText)
                        renameTargetId = null
                    }
                }) { Text("Rename") }
            },
            dismissButton = {
                OutlinedButton(onClick = { renameTargetId = null }) { Text("Cancel") }
            }
        )
    }
}


// -------------------------------------------------------------
// 4. SCREEN SHARE & VISION SCREEN (Dedicated Screen)
// -------------------------------------------------------------
@OptIn(com.google.accompanist.permissions.ExperimentalPermissionsApi::class)
@Composable
fun ScreenShareScreen(navController: NavController, viewModel: KavyaViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isSharing by viewModel.isScreenSharing.collectAsState()
    val screenContext by viewModel.screenContextText.collectAsState()

    val mediaProjectionManager = remember {
        context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as? android.media.projection.MediaProjectionManager
    }

    val screenCaptureConsentLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onScreenShareConsentResult(context, result.resultCode, result.data)
    }

    fun startScreenShare() {
        val captureIntent = viewModel.startScreenShareConsent(context)
        if (captureIntent != null) {
            screenCaptureConsentLauncher.launch(captureIntent)
        }
    }

    SimpleScreen("Screen Share & Vision", navController) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                backgroundColor = if (isSharing) SurfaceGlass.copy(alpha = 0.9f) else SurfaceGlass.copy(alpha = 0.7f),
                borderColor = if (isSharing) SuccessGreen.copy(alpha = 0.4f) else GlassBorder
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(if (isSharing) SuccessGreen.copy(alpha = 0.15f) else Primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isSharing) Icons.Default.ScreenShare else Icons.Default.MobileScreenShare,
                            contentDescription = null,
                            tint = if (isSharing) SuccessGreen else PrimaryLight
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isSharing) "Screen Sharing is Active" else "Screen Sharing is Inactive",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            text = if (isSharing) "Kavya is observing your screen in real time." else "Kavya observes your screen to guide tasks hands-free.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = {
                    if (isSharing) {
                        viewModel.toggleScreenSharing(false)
                    } else {
                        startScreenShare()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSharing) Error else Primary
                )
            ) {
                Icon(
                    imageVector = if (isSharing) Icons.Default.StopScreenShare else Icons.Default.ScreenShare,
                    contentDescription = null,
                    tint = Color.White
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (isSharing) "Stop Screen Sharing" else "Start Sharing Screen with Kavya",
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("Live Perception Feed:", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = TextSecondary)
            Spacer(modifier = Modifier.height(8.dp))

            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(16.dp),
                backgroundColor = Background.copy(alpha = 0.85f),
                borderColor = Outline
            ) {
                LazyColumn {
                    item {
                        Text(
                            text = screenContext ?: "No active screen data. Tap 'Start Sharing Screen' or enable Accessibility in Settings.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedButton(
                onClick = {
                    viewModel.analyzeScreenWithKavya("What's on my screen right now? Please guide me.")
                    navController.navigate("chat")
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Analyze Screen in Chat ✨", color = PrimaryLight)
            }
        }
    }
}

// -------------------------------------------------------------
// 5. OTHER SUB-SCREENS (Favourites, Routines, Diagnostics, etc.)
// -------------------------------------------------------------
@Composable
fun FavouritesScreen(navController: NavController, viewModel: KavyaViewModel) {
    var favourites by remember { mutableStateOf(listOf("Open YouTube", "Open Spotify", "Open Camera", "Open Settings", "Open Chrome")) }
    var showDialog by remember { mutableStateOf(false) }
    var newCmd by remember { mutableStateOf("") }

    SimpleScreen("Favourites", navController, actions = {
        IconButton(onClick = { showDialog = true }) {
            Icon(Icons.Default.Add, contentDescription = "Add Favourite", tint = PrimaryLight)
        }
    }) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(favourites.size) { index ->
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        onClick = {
                            viewModel.sendMessage(favourites[index])
                            navController.navigate("chat")
                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFFFC107), modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(favourites[index], style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                            }
                            IconButton(onClick = { favourites = favourites.filterIndexed { i, _ -> i != index } }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = TextTertiary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            if (showDialog) {
                AlertDialog(
                    onDismissRequest = { showDialog = false },
                    containerColor = Surface,
                    title = { Text("Add Favourite Command", color = TextPrimary) },
                    text = {
                        OutlinedTextField(
                            value = newCmd,
                            onValueChange = { newCmd = it },
                            label = { Text("Command (e.g. Open YouTube)") }
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (newCmd.isNotBlank()) {
                                favourites = favourites + newCmd
                                newCmd = ""
                            }
                            showDialog = false
                        }) {
                            Text("Add", color = PrimaryLight)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDialog = false }) { Text("Cancel", color = TextTertiary) }
                    }
                )
            }
        }
    }
}

@Composable
fun RoutinesScreen(navController: NavController, viewModel: KavyaViewModel? = null) {
    val coroutineScope = rememberCoroutineScope()
    val schedules = viewModel?.schedulerEngine?.allSchedulesFlow?.collectAsStateWithLifecycle(initialValue = emptyList())?.value ?: emptyList()
    SimpleScreen("Quick Automations", navController) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text("Automate multi-step device actions with a single tap or voice command.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                Spacer(modifier = Modifier.height(6.dp))
            }
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        viewModel?.sendMessage("Open YouTube")
                        navController.navigate("chat")
                    }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(38.dp).clip(CircleShape).background(Primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.PlayCircle, contentDescription = null, tint = Primary, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Open YouTube", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = TextPrimary)
                            Text("Launch YouTube app deterministically", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = TextTertiary)
                        }
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = PrimaryLight)
                    }
                }
            }
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        viewModel?.sendMessage("Open Spotify")
                        navController.navigate("chat")
                    }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(38.dp).clip(CircleShape).background(SuccessGreen.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.MusicNote, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Open Spotify", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = TextPrimary)
                            Text("Launch music player", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = TextTertiary)
                        }
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = PrimaryLight)
                    }
                }
            }
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        viewModel?.sendMessage("Open Camera")
                        navController.navigate("chat")
                    }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(38.dp).clip(CircleShape).background(AccentCyan.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Open Camera", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = TextPrimary)
                            Text("Launch device camera", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = TextTertiary)
                        }
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = PrimaryLight)
                    }
                }
            }

            // --- Section: Offline Alarms & Reminders ---
            item {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    "Offline Alarms & Reminders ⏰",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "Backed by native AlarmManager & Room. Works 100% offline across reboots.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = TextSecondary
                )
            }

            if (schedules.isEmpty()) {
                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = SurfaceGlass.copy(alpha = 0.5f)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Alarm, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(28.dp))
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("No active alarms or reminders", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            viewModel?.schedulerEngine?.createSchedule("Morning Alarm", "Wake up call", "DAILY", 7, 0)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Primary.copy(alpha = 0.8f))
                                ) {
                                    Text("+ 7:00 AM Daily", style = MaterialTheme.typography.labelSmall)
                                }
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            viewModel?.schedulerEngine?.createSchedule("Reminder", "Check tasks", "ONE_TIME", 9, 30)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan.copy(alpha = 0.8f))
                                ) {
                                    Text("+ 9:30 AM Once", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            } else {
                items(items = schedules, key = { it.id }) { schedule ->
                    val timeStr = String.format(java.util.Locale.ROOT, "%02d:%02d", schedule.hour, schedule.minute)
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = SurfaceGlass.copy(alpha = if (schedule.isActive) 0.85f else 0.45f)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(40.dp).clip(CircleShape).background(
                                    if (schedule.isActive) Primary.copy(alpha = 0.16f) else TextTertiary.copy(alpha = 0.1f)
                                ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Alarm,
                                    contentDescription = null,
                                    tint = if (schedule.isActive) Primary else TextTertiary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    timeStr,
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = if (schedule.isActive) TextPrimary else TextTertiary
                                )
                                Text(
                                    "${schedule.scheduleType} • ${schedule.title}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = if (schedule.isActive) TextSecondary else TextTertiary
                                )
                            }
                            Switch(
                                checked = schedule.isActive,
                                onCheckedChange = { active ->
                                    coroutineScope.launch {
                                        viewModel?.schedulerEngine?.toggleActive(schedule.id, active)
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        viewModel?.schedulerEngine?.deleteSchedule(schedule.id)
                                    }
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete alarm", tint = Error.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MusicScreen(navController: NavController, viewModel: KavyaViewModel? = null) {
    SimpleScreen("Media Control", navController) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.size(72.dp).clip(CircleShape).background(Primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(36.dp), tint = Primary)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("Music & Audio Control", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = TextPrimary)
            Spacer(modifier = Modifier.height(6.dp))
            Text("Control playback and volume via voice or chat commands.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Spacer(modifier = Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = {
                        viewModel?.sendMessage("Open Spotify")
                        navController.navigate("chat")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Text("Launch Spotify")
                }
                OutlinedButton(
                    onClick = {
                        viewModel?.sendMessage("Open YouTube Music")
                        navController.navigate("chat")
                    }
                ) {
                    Text("Launch YT Music", color = PrimaryLight)
                }
            }
        }
    }
}

@Composable
fun MemoryScreen(navController: NavController, viewModel: KavyaViewModel) {
    val coroutineScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedCategory by remember { mutableStateOf("ALL") }
    var searchQuery by remember { mutableStateOf("") }
    
    val allMemories by viewModel.memoryEngine.getAllMemoriesFlow().collectAsState(initial = emptyList())
    val obsidianSyncState by viewModel.obsidianVaultManager.syncState.collectAsState()

    val obsidianVaultLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.obsidianVaultManager.connectVault(uri)
            viewModel.syncMemoriesToObsidian()
        }
    }
    
    var showAddDialog by remember { mutableStateOf(false) }
    var editingMemory by remember { mutableStateOf<com.example.data.MemoryEntity?>(null) }
    var newKey by remember { mutableStateOf("") }
    var newContent by remember { mutableStateOf("") }
    var newCategory by remember { mutableStateOf("PREFERENCE") }
    var newImportance by remember { mutableStateOf(3) }

    val filteredMemories = allMemories.filter { mem ->
        val catMatches = selectedCategory == "ALL" || mem.category.equals(selectedCategory, ignoreCase = true)
        val queryMatches = searchQuery.isBlank() ||
                mem.key.contains(searchQuery, ignoreCase = true) ||
                mem.content.contains(searchQuery, ignoreCase = true)
        catMatches && queryMatches
    }

    SimpleScreen(
        title = "Assistant Memory & Recall",
        navController = navController,
        actions = {
            IconButton(onClick = {
                newKey = ""
                newContent = ""
                newCategory = "PREFERENCE"
                newImportance = 3
                showAddDialog = true
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add Memory", tint = PrimaryLight)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))
            
            // Aesthetic Neural Core & Personality Hub Card
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Primary.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Memory, contentDescription = null, tint = PrimaryLight, modifier = Modifier.size(18.dp))
                            }
                            Text(
                                text = "Kavya Neural Core",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp),
                                color = TextPrimary
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Primary.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Primary.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = "Active 4-Tier Hub",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                color = PrimaryLight
                            )
                        }
                    }

                    // Tech Stack Badges
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("Room DB", "OKF Git Sync", "BM25 Index").forEach { badge ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = SurfaceGlass,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder)
                                ) {
                                    Text(
                                        text = badge,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("Auto-Learn", "ChatGPT Personality").forEach { badge ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = SurfaceGlass,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder)
                                ) {
                                    Text(
                                        text = badge,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }

                    Divider(color = GlassBorder, thickness = 1.dp)

                    // Personality Presets
                    Text(
                        text = "ChatGPT-Style Personality & Tone Presets",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                        color = TextPrimary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    viewModel.memoryEngine.insertCustomMemory(
                                        "Personality & Tone",
                                        "User prefers a friendly, casual, and warm tone in all responses.",
                                        "PERSONALITY",
                                        5
                                    )
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Primary),
                            modifier = Modifier.weight(1f).height(34.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Friendly Tone", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = androidx.compose.ui.graphics.Color.White)
                        }
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    viewModel.memoryEngine.insertCustomMemory(
                                        "Language & Style",
                                        "User prefers fluent Hinglish responses with professional insight.",
                                        "PREFERENCE",
                                        4
                                    )
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceGlass),
                            modifier = Modifier.weight(1f).height(34.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder)
                        ) {
                            Text("Fluent Hinglish", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        }
                    }
                }
            }

            // Obsidian Vault Sync Integration Card
            val isVaultConnected = viewModel.obsidianVaultManager.isVaultConnected()
            val vaultName = viewModel.obsidianVaultManager.getVaultName()

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(AccentCyan.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                            }
                            Column {
                                Text(
                                    text = "Obsidian Vault Sync",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
                                    color = TextPrimary
                                )
                                Text(
                                    text = if (isVaultConnected) (vaultName ?: "Connected") else "Markdown Notes Sync",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = TextSecondary
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (isVaultConnected) SuccessGreen.copy(alpha = 0.15f) else SurfaceGlass,
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (isVaultConnected) SuccessGreen.copy(alpha = 0.4f) else GlassBorder)
                        ) {
                            Text(
                                text = if (isVaultConnected) "Connected" else "SAF Offline",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                color = if (isVaultConnected) SuccessGreenGlow else TextSecondary
                            )
                        }
                    }

                    Text(
                        text = "Store your AI assistant's memories as structured Markdown (.md) notes directly in your authorized Obsidian Vault. Full two-way offline synchronization.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                        color = TextSecondary
                    )

                    when (obsidianSyncState) {
                        is com.example.memory.obsidian.ObsidianVaultManager.SyncState.Syncing -> {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = PrimaryLight)
                                Text("Synchronizing with Obsidian Vault...", fontSize = 11.sp, color = PrimaryLight)
                            }
                        }
                        is com.example.memory.obsidian.ObsidianVaultManager.SyncState.Success -> {
                            val msg = (obsidianSyncState as com.example.memory.obsidian.ObsidianVaultManager.SyncState.Success).message
                            Text("✓ $msg", fontSize = 11.sp, color = SuccessGreenGlow)
                        }
                        is com.example.memory.obsidian.ObsidianVaultManager.SyncState.Error -> {
                            val err = (obsidianSyncState as com.example.memory.obsidian.ObsidianVaultManager.SyncState.Error).error
                            Text("⚠️ $err", fontSize = 11.sp, color = Error)
                        }
                        else -> {}
                    }

                    if (!isVaultConnected) {
                        Button(
                            onClick = { obsidianVaultLauncher.launch(null) },
                            modifier = Modifier.fillMaxWidth().height(36.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary)
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp), tint = androidx.compose.ui.graphics.Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Select Obsidian Vault Folder", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = androidx.compose.ui.graphics.Color.White)
                        }
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.syncMemoriesToObsidian() },
                                modifier = Modifier.weight(1f).height(34.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Primary)
                            ) {
                                Text("Sync to Vault", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = androidx.compose.ui.graphics.Color.White)
                            }
                            Button(
                                onClick = { viewModel.importMemoriesFromObsidian() },
                                modifier = Modifier.weight(1f).height(34.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = SurfaceGlass),
                                border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder)
                            ) {
                                Text("Import Notes", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            }
                            OutlinedButton(
                                onClick = { viewModel.obsidianVaultManager.disconnectVault() },
                                modifier = Modifier.height(34.dp),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) {
                                Text("Disconnect", fontSize = 11.sp, color = TextTertiary)
                            }
                        }
                    }
                }
            }
            
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search memories, personality, facts...", fontSize = 13.sp, color = TextTertiary) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            // Category Filter Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("ALL", "PERSONALITY", "PREFERENCE", "IMPORTANT", "CRITICAL", "CONTEXT").forEach { cat ->
                    val isSelected = selectedCategory == cat
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCategory = cat },
                        label = {
                            Text(
                                text = if (cat == "ALL") "All (${allMemories.size})" else cat.lowercase().replaceFirstChar { it.uppercase() },
                                fontSize = 11.sp
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Primary.copy(alpha = 0.25f),
                            selectedLabelColor = PrimaryLight,
                            containerColor = SurfaceGlass,
                            labelColor = TextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = if (isSelected) Primary else GlassBorder
                        )
                    )
                }
            }

            if (filteredMemories.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(SurfaceGlass)
                                .border(1.dp, GlassBorder, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Memory, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(28.dp))
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (searchQuery.isNotBlank()) "No matching memories found" else "No persistent memories saved yet",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tell Kavya 'Remember that...' or tap + to add one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(filteredMemories.size, key = { filteredMemories[it].id }) { index ->
                        val mem = filteredMemories[index]
                        val categoryBadgeColor = when (mem.category.uppercase()) {
                            "CRITICAL" -> Error
                            "PREFERENCE" -> Primary
                            "IMPORTANT" -> AccentPurpleLight
                            else -> AccentCyan
                        }

                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(categoryBadgeColor.copy(alpha = 0.15f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = mem.category,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 9.sp,
                                                    color = categoryBadgeColor
                                                )
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = mem.key,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = TextPrimary
                                        )
                                    }

                                    Row {
                                        IconButton(
                                            onClick = {
                                                editingMemory = mem
                                                newKey = mem.key
                                                newContent = mem.content
                                                newCategory = mem.category
                                                newImportance = mem.importance
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.Edit, contentDescription = "Edit", tint = TextSecondary, modifier = Modifier.size(16.dp))
                                        }
                                        IconButton(
                                            onClick = {
                                                coroutineScope.launch {
                                                    viewModel.memoryEngine.deleteMemoryById(mem.id)
                                                }
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Forget", tint = TextTertiary, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }

                                if (mem.content.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = mem.content,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Importance: " + "★".repeat(mem.importance) + "☆".repeat(5 - mem.importance),
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = PrimaryLight
                                    )
                                    if (mem.confidence < 1.0f) {
                                        Text("Auto-learned", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = TextTertiary)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Clear All Button
            if (allMemories.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            viewModel.memoryEngine.clearAll()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 80.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Error)
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clear All Saved Memories")
                }
            }
        }

        // Add / Edit Dialog
        if (showAddDialog || editingMemory != null) {
            val isEdit = editingMemory != null
            AlertDialog(
                onDismissRequest = {
                    showAddDialog = false
                    editingMemory = null
                },
                containerColor = SurfaceVariant,
                title = { Text(if (isEdit) "Edit Memory" else "Add Custom Memory", color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = newKey,
                            onValueChange = { newKey = it },
                            label = { Text("Topic / Title (e.g. Language, Project)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newContent,
                            onValueChange = { newContent = it },
                            label = { Text("Content / Details") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Category:", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            listOf("PREFERENCE", "IMPORTANT", "CRITICAL").forEach { c ->
                                FilterChip(
                                    selected = newCategory == c,
                                    onClick = { newCategory = c },
                                    label = { Text(c.take(4), fontSize = 10.sp) }
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (newKey.isNotBlank()) {
                                coroutineScope.launch {
                                    if (isEdit && editingMemory != null) {
                                        viewModel.memoryEngine.updateMemory(
                                            editingMemory!!.copy(
                                                key = newKey.trim(),
                                                content = newContent.trim(),
                                                category = newCategory,
                                                importance = newImportance,
                                                lastUsedAt = System.currentTimeMillis()
                                            )
                                        )
                                    } else {
                                        viewModel.memoryEngine.insertCustomMemory(
                                            key = newKey.trim(),
                                            content = newContent.trim(),
                                            category = newCategory,
                                            importance = newImportance
                                        )
                                    }
                                    showAddDialog = false
                                    editingMemory = null
                                }
                            }
                        }
                    ) {
                        Text("Save", color = PrimaryLight, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showAddDialog = false
                        editingMemory = null
                    }) {
                        Text("Cancel", color = TextTertiary)
                    }
                }
            )
        }
    }
}


@Composable
fun AppearanceScreen(navController: NavController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedTheme by remember { mutableStateOf(com.example.utils.AppPreferences.getSelectedTheme(context)) }

    SimpleScreen("Appearance", navController) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Theme Selection", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = TextSecondary)
            
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedTheme = "Futuristic Dark"
                                com.example.utils.AppPreferences.setSelectedTheme(context, selectedTheme)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Futuristic Dark", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            Text("Deep Navy/Black with Pink & Purple Glow", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                        RadioButton(
                            selected = selectedTheme == "Futuristic Dark",
                            onClick = {
                                selectedTheme = "Futuristic Dark"
                                com.example.utils.AppPreferences.setSelectedTheme(context, selectedTheme)
                            }
                        )
                    }

                    HorizontalDivider(color = OutlineVariant)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedTheme = "Cyber Anime"
                                com.example.utils.AppPreferences.setSelectedTheme(context, selectedTheme)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Cyber Anime", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            Text("Vibrant Neon & Soft Glow Accents", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                        RadioButton(
                            selected = selectedTheme == "Cyber Anime",
                            onClick = {
                                selectedTheme = "Cyber Anime"
                                com.example.utils.AppPreferences.setSelectedTheme(context, selectedTheme)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LanguageScreen(navController: NavController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedLang by remember { mutableStateOf(com.example.utils.AppPreferences.getLanguage(context)) }

    SimpleScreen("Language", navController) {
        LazyColumn(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Voice & Interface Language", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = TextSecondary)
            }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedLang = "hi"
                                    com.example.utils.AppPreferences.setLanguage(context, "hi")
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Hindi & Hinglish (हिंदी / Hinglish)", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                Text("Natural native conversational tone (Default)", style = MaterialTheme.typography.bodySmall, color = SuccessGreenGlow)
                            }
                            RadioButton(
                                selected = selectedLang == "hi",
                                onClick = {
                                    selectedLang = "hi"
                                    com.example.utils.AppPreferences.setLanguage(context, "hi")
                                }
                            )
                        }

                        HorizontalDivider(color = OutlineVariant)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedLang = "en"
                                    com.example.utils.AppPreferences.setLanguage(context, "en")
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("English (Indian Accent)", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                Text("English responses with Indian prosody", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            }
                            RadioButton(
                                selected = selectedLang == "en",
                                onClick = {
                                    selectedLang = "en"
                                    com.example.utils.AppPreferences.setLanguage(context, "en")
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PrivacyScreen(navController: NavController, viewModel: KavyaViewModel) {
    val diag by viewModel.latestDiagnostic.collectAsState()
    val agentDiagnostics by com.example.agent.DiagnosticEngine.recentDiagnostics.collectAsState()
    var testQuery by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<String?>(null) }

    SimpleScreen("Developer Diagnostics", navController) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("Universal Agent & Resolver Diagnostics", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = TextPrimary)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Deterministic index, multi-step execution traces, and verification logs.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }

            // Universal Agent Multi-Step Execution Logs
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Agent Execution Traces", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = TextPrimary)
                        if (agentDiagnostics.isNotEmpty()) {
                            TextButton(onClick = { com.example.agent.DiagnosticEngine.clearDiagnostics() }) {
                                Text("Clear", color = TextTertiary, fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))

                    if (agentDiagnostics.isEmpty()) {
                        Text("No multi-step automation traces recorded yet.", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            agentDiagnostics.take(5).forEach { record ->
                                val isPass = record.success
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Surface.copy(alpha = 0.6f))
                                        .padding(10.dp)
                                ) {
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "Target: ${record.targetApp}",
                                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                                color = TextPrimary
                                            )
                                            Text(
                                                text = if (isPass) "SUCCESS (${record.completedSteps}/${record.totalSteps})" else "FAILED",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isPass) SuccessGreen else Error
                                                )
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Prompt: \"${record.prompt}\"",
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                            color = TextSecondary
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        record.stepsSummary.forEach { step ->
                                            Text(
                                                text = "• $step",
                                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextTertiary)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text("Interactive App Resolver Test", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = TextPrimary)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = testQuery,
                        onValueChange = {
                            testQuery = it
                            if (it.isNotBlank()) {
                                val res = viewModel.commandRouter.appResolver.resolve(it)
                                testResult = "• Target: ${viewModel.commandRouter.appResolver.extractAppNameFromNaturalLanguage(it)}\n• Matched: ${res.matchedApp?.appName ?: "None"}\n• Package: ${res.matchedApp?.packageName ?: "None"}\n• Confidence: ${res.confidence}\n• Reason: ${res.reason}"
                            } else {
                                testResult = null
                            }
                        },
                        label = { Text("Enter prompt (e.g. YT kholo, Open YouTube)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (testResult != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(testResult!!, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextSecondary))
                    }
                }
            }

            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text("Latest Launch Diagnostic", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = TextPrimary)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (diag != null) {
                        Text("Requested: ${diag!!.requestedApp}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = TextSecondary))
                        Text("Resolved: ${diag!!.resolvedApp}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = TextSecondary))
                        Text("Package: ${diag!!.packageName}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = TextSecondary))
                        Text("Verification: ${diag!!.verification}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = if (diag!!.verification.startsWith("PASS")) SuccessGreen else Error))
                    } else {
                        Text("No app launch performed yet.", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                    }
                }
            }

            item {
                val count = viewModel.commandRouter.appResolver.getInstalledApps().size
                Button(
                    onClick = { viewModel.commandRouter.appResolver.refreshIndex() },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Re-index Installed Apps ($count)", color = Color.White)
                }
            }
        }
    }
}

@Composable
fun AdvancedScreen(navController: NavController) {
    SimpleScreen("Advanced", navController) {
        Column(modifier = Modifier.padding(20.dp)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("System Status", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = TextPrimary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Kavya Assistant is configured for multi-provider online execution with Gemini and OpenRouter.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
        }
    }
}

@Composable
fun ProactiveSettingsScreen(navController: NavController, viewModel: KavyaViewModel) {
    val isProactive by viewModel.proactiveController.isProactiveMode.collectAsState()
    val isScreenAware by viewModel.proactiveController.isScreenAwareness.collectAsState()
    val frequency by viewModel.proactiveController.frequency.collectAsState()
    val silenceUntil by viewModel.proactiveController.silenceUntil.collectAsState()
    val engagementScore by viewModel.proactiveController.engagementScore.collectAsState()
    
    val now = System.currentTimeMillis()
    val isCurrentlySilenced = silenceUntil > now
    val silenceRemainingMin = if (isCurrentlySilenced) ((silenceUntil - now) / 60000L) + 1 else 0

    SimpleScreen("Proactive Conversation", navController) {
        LazyColumn(
            modifier = Modifier.padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Master Switch
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = if (isProactive) SurfaceGlass.copy(alpha = 0.9f) else SurfaceGlass.copy(alpha = 0.5f),
                    borderColor = if (isProactive) PrimaryLight.copy(alpha = 0.4f) else OutlineVariant
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Proactive Mode", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = TextPrimary)
                                Spacer(modifier = Modifier.width(8.dp))
                                if (isProactive) {
                                    StatusBadge(text = "ACTIVE", isActive = true, activeColor = SuccessGreen)
                                } else {
                                    StatusBadge(text = "MUTED", isActive = false, inactiveColor = TextTertiary)
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "When enabled, Kavya naturally follows up, engages in warm conversations, and reacts without rigid Q&A stopping.",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = isProactive,
                            onCheckedChange = { viewModel.proactiveController.setProactiveMode(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Primary, checkedTrackColor = PrimaryContainer)
                        )
                    }
                }
            }

            // Privacy: Screen Awareness
            item {
                SettingsSectionHeader("Privacy & Perception 🛡️")
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Screen Context Awareness", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = TextPrimary)
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                "Allow Kavya to offer helpful tips or summaries based on active apps. Off by default. Kavya never reads passwords or private fields.",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = isScreenAware,
                            onCheckedChange = { viewModel.proactiveController.setScreenAwareness(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = AccentCyan, checkedTrackColor = PrimaryContainer)
                        )
                    }
                }
            }

            // Conversation Frequency
            item {
                SettingsSectionHeader("Conversation Frequency ⏱️")
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        com.example.proactive.ProactiveFrequency.entries.forEach { freq ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.proactiveController.setFrequency(freq) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(freq.displayName, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        when (freq) {
                                            com.example.proactive.ProactiveFrequency.QUIET -> "Speaks less, high patience, only high-relevance moments."
                                            com.example.proactive.ProactiveFrequency.BALANCED -> "Standard natural conversation pacing."
                                            com.example.proactive.ProactiveFrequency.CHATTY -> "Lively, frequent follow-ups and active participation."
                                        },
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                        color = TextSecondary
                                    )
                                }
                                RadioButton(
                                    selected = frequency == freq,
                                    onClick = { viewModel.proactiveController.setFrequency(freq) }
                                )
                            }
                            if (freq != com.example.proactive.ProactiveFrequency.entries.last()) {
                                HorizontalDivider(color = OutlineVariant)
                            }
                        }
                    }
                }
            }

            // Silence Controls
            item {
                SettingsSectionHeader("Temporary Silence 🤫")
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (isCurrentlySilenced) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(AccentPurple.copy(alpha = 0.2f))
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "Kavya is silenced for ~$silenceRemainingMin min",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = AccentPurpleLight)
                                )
                                TextButton(onClick = { viewModel.proactiveController.clearSilence() }) {
                                    Text("Resume", color = SuccessGreenGlow, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Text("Quick Silence Presets:", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { viewModel.proactiveController.setTemporarySilence(5 * 60 * 1000L) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                            ) {
                                Text("5 Min", fontSize = 11.sp, color = TextPrimary)
                            }
                            OutlinedButton(
                                onClick = { viewModel.proactiveController.setTemporarySilence(15 * 60 * 1000L) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                            ) {
                                Text("15 Min", fontSize = 11.sp, color = TextPrimary)
                            }
                            OutlinedButton(
                                onClick = { viewModel.proactiveController.setTemporarySilence(30 * 60 * 1000L) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                            ) {
                                Text("30 Min", fontSize = 11.sp, color = TextPrimary)
                            }
                        }
                    }
                }
            }

            // Natural Voice Commands Cheat-sheet
            item {
                SettingsSectionHeader("Supported Voice Commands 🗣️")
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("• \"चुप हो जाओ\" / \"Stop talking\" / \"Chup raho\": Mutes Kavya & disables proactive mode.", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, color = TextSecondary))
                        Text("• \"अब बात कर सकती हो\" / \"Talk to me\" / \"Bolo Kavya\": Resumes proactive conversations.", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, color = TextSecondary))
                        Text("• \"10 मिनट चुप रहो\" / \"Quiet for 5 mins\": Sets temporary silence timer.", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, color = TextSecondary))
                        Text("• \"कम बोला करो\" / \"Speak less\": Switches to quiet mode.", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, color = TextSecondary))
                        Text("• \"मुझसे बात करती रहा करो\": Switches to chatty mode.", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, color = TextSecondary))
                    }
                }
            }
        }
    }
}

@Composable
fun HelpSupportScreen(navController: NavController) {
    val scrollState = androidx.compose.foundation.rememberScrollState()

    SimpleScreen(
        title = "Help & Support",
        navController = navController
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(modifier = Modifier.height(2.dp))

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Kavya AI Assistant • Quick Guide",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Text(
                        text = "Kavya is an intelligent, voice-enabled, multimodal Android assistant designed with Apple-inspired White Liquid Glass architecture.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            SettingsSectionHeader("Supported Voice Commands 🗣️")
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("• \"Hey Kavya\" / \"Wake up\": Wakes microphone listening.", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                    Text("• \"Sleep Kavya\" / \"So jao\": Puts voice assistant to sleep.", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                    Text("• \"Stop Kavya\" / \"Ruko\": Emergency voice stop.", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                    Text("• \"Chup raho\": Temporarily silences proactive conversations.", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                }
            }

            SettingsSectionHeader("Chat & Attachments 📎")
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("• Plus (+) Popup: Compact White Liquid Glass floating menu.", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                    Text("• Camera: Take a live photo and analyze it with AI.", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                    Text("• PDF Scanner: Scans document images and produces real PDF files.", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                    Text("• Live Screen: Grants real-time MediaProjection screen awareness.", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                    Text("• Document Picker: Extracts text from TXT, MD, CSV, JSON and sends to AI.", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                }
            }

            SettingsSectionHeader("Memory & Obsidian Vault 🧠")
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("• Room DB: All learned facts and preferences are persistently stored.", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                    Text("• Obsidian Sync: Select any Obsidian Vault folder via Storage Access Framework to sync memories as structured Markdown notes.", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                }
            }

            SettingsSectionHeader("System & Version")
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Kavya AI • Version 2.5.0", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = TextPrimary)
                    Text("Engine: White Liquid Glass + Jetpack Compose", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text("Branch: main (Clean Architecture)", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}


