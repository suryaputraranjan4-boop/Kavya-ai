package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
                    SettingRow("Permissions & Service Status", "Overlay, Accessibility & Audio", Icons.Default.Security, SuccessGreen) {
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
                    SettingRow("Advanced", "Offline fallback & system tweaks", Icons.Default.SettingsApplications, TextSecondary) {
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
// 3. VOICE SETTINGS SCREEN
// -------------------------------------------------------------
@Composable
fun VoiceSettingsScreen(navController: NavController, viewModel: KavyaViewModel? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var geminiVoice by remember { mutableStateOf(com.example.utils.AppPreferences.getGeminiVoice(context)) }
    var pitch by remember { mutableStateOf(com.example.utils.AppPreferences.getVoicePitch(context)) }
    var speed by remember { mutableStateOf(com.example.utils.AppPreferences.getVoiceSpeed(context)) }
    var selectedPersona by remember { mutableStateOf(com.example.utils.AppPreferences.getVoicePersona(context)) }

    SimpleScreen("Voice & Persona", navController) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    backgroundColor = SurfaceGlass.copy(alpha = 0.85f),
                    borderColor = Primary.copy(alpha = 0.3f)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🌸", fontSize = 28.sp)
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                "Kavya Sweet Anime Voice",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "Powered by Gemini Neural Speech API. Sweet, adorable anime female voice with natural Hindi accent and genuine emotional inflections.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            item {
                Text("Gemini Neural Voice Model", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = geminiVoice == "Kore",
                        onClick = {
                            geminiVoice = "Kore"
                            com.example.utils.AppPreferences.setGeminiVoice(context, geminiVoice)
                            viewModel?.speakMessage("Hello! I am Kore, ready to assist you!")
                        },
                        label = { Text("🌸 Kore (Sweet Anime)") }
                    )
                        FilterChip(
                            selected = geminiVoice == "Aoede",
                            onClick = {
                                geminiVoice = "Aoede"
                                com.example.utils.AppPreferences.setGeminiVoice(context, geminiVoice)
                                viewModel?.speakMessage("Hello! I am Aoede. I have a melodious voice!")
                            },
                            label = { Text("✨ Aoede (Melodious)") }
                        )
                        FilterChip(
                            selected = geminiVoice == "Leda",
                            onClick = {
                                geminiVoice = "Leda"
                                com.example.utils.AppPreferences.setGeminiVoice(context, geminiVoice)
                                viewModel?.speakMessage("Hello there! I am Leda, speaking with a bright and clear voice.")
                            },
                            label = { Text("⚡ Leda (Bright)") }
                        )
                    }
                }

            item {
                Text("Companion Anime Voice Presets", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedPersona.contains("Sweet") || selectedPersona.contains("Anime"),
                        onClick = {
                            selectedPersona = "Sweet Anime Hindi Female"
                            pitch = 1.14f
                            speed = 1.02f
                            com.example.utils.AppPreferences.setVoicePersona(context, selectedPersona)
                            viewModel?.setVoiceSettings(pitch, speed)
                        },
                        label = { Text("🌸 Sweet Anime") }
                    )
                    FilterChip(
                        selected = selectedPersona.contains("Caring") || selectedPersona.contains("Soft"),
                        onClick = {
                            selectedPersona = "Soft & Caring Hindi"
                            pitch = 1.08f
                            speed = 0.95f
                            com.example.utils.AppPreferences.setVoicePersona(context, selectedPersona)
                            viewModel?.setVoiceSettings(pitch, speed)
                        },
                        label = { Text("💖 Soft & Caring") }
                    )
                    FilterChip(
                        selected = selectedPersona.contains("Lively") || selectedPersona.contains("Energetic"),
                        onClick = {
                            selectedPersona = "Lively & Cheerful"
                            pitch = 1.18f
                            speed = 1.06f
                            com.example.utils.AppPreferences.setVoicePersona(context, selectedPersona)
                            viewModel?.setVoiceSettings(pitch, speed)
                        },
                        label = { Text("⚡ Lively") }
                    )
                }
            }

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
                            viewModel?.setVoiceSettings(pitch, speed)
                        },
                        valueRange = 0.95f..1.25f,
                        colors = SliderDefaults.colors(thumbColor = Primary, activeTrackColor = Primary)
                    )
                    Text("Pitch Multiplier: ${"%.2f".format(pitch)}x (Sweet Natural Anime Feminine Tone)", style = MaterialTheme.typography.labelSmall, color = TextSecondary)

                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Speaking Cadence & Rate", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = TextPrimary)
                    Slider(
                        value = speed,
                        onValueChange = {
                            speed = it
                            viewModel?.setVoiceSettings(pitch, speed)
                        },
                        valueRange = 0.80f..1.25f,
                        colors = SliderDefaults.colors(thumbColor = AccentPurple, activeTrackColor = AccentPurple)
                    )
                    Text("Speed Multiplier: ${"%.2f".format(speed)}x", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
            }

            item {
                Text("Emotion & Slang Spoken Audio Tests", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel?.setVoiceSettings(pitch, speed)
                            viewModel?.speakMessage("Hi! I am Kavya. This is my new sweet voice powered by Gemini Neural Speech.")
                        },
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Text("🔊 Preview Voice")
                    }
                    
                    Button(
                        onClick = {
                            viewModel?.setVoiceSettings(pitch, speed)
                            viewModel?.speakMessage("[happy] Arre waah yaar! Main ekdum mast hoon. Aap bataiye aaj ka kya scene hai?")
                        },
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Icon(Icons.Default.Celebration, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Test [happy] (Cheerful Hindi Slang)", fontWeight = FontWeight.SemiBold, color = Color.White)
                    }

                    Button(
                        onClick = {
                            viewModel?.setVoiceSettings(pitch, speed)
                            viewModel?.speakMessage("[sad] Arre yaar, sunkar bohot bura laga... Tension mat lo, main hamesha aapke sath hoon.")
                        },
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Secondary)
                    ) {
                        Icon(Icons.Default.Favorite, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Test [sad] (Soft & Caring Tone)", fontWeight = FontWeight.SemiBold, color = Color.White)
                    }

                    Button(
                        onClick = {
                            viewModel?.setVoiceSettings(pitch, speed)
                            viewModel?.speakMessage("[excited] Yay! Arre waah yaar! Yeh toh ekdum bawaal khabar hai! Maza aa gaya!")
                        },
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPurple)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Test [excited] (Sparkling Anime Celebration)", fontWeight = FontWeight.SemiBold, color = Color.White)
                    }

                    Button(
                        onClick = {
                            viewModel?.setVoiceSettings(pitch, speed)
                            viewModel?.speakMessage("[warm] Scene sorted hai yaar! Bindass chill karo, batao aur kya chal raha hai?")
                        },
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceGlass)
                    ) {
                        Icon(Icons.Default.VolumeUp, contentDescription = null, tint = PrimaryLight)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Test [warm] (Chill Hinglish Vibe)", fontWeight = FontWeight.SemiBold, color = PrimaryLight)
                    }
                }
            }
        }
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
    val isMicMuted by viewModel.isMicMuted.collectAsState()
    val screenContext by viewModel.screenContextText.collectAsState()

    val micPermissionState = com.google.accompanist.permissions.rememberPermissionState(android.Manifest.permission.RECORD_AUDIO)

    val mediaProjectionManager = remember {
        context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as? android.media.projection.MediaProjectionManager
    }

    val screenCaptureConsentLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.toggleScreenSharing(true)
            viewModel.setMicMuted(false)
        }
    }

    fun startScreenShare() {
        if (!micPermissionState.status.isGranted) {
            micPermissionState.launchPermissionRequest()
            return
        }

        if (mediaProjectionManager != null) {
            try {
                val intent = mediaProjectionManager.createScreenCaptureIntent()
                screenCaptureConsentLauncher.launch(intent)
            } catch (e: Exception) {
                viewModel.toggleScreenSharing(true)
                viewModel.setMicMuted(false)
            }
        } else {
            viewModel.toggleScreenSharing(true)
            viewModel.setMicMuted(false)
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
                            text = if (isSharing) "Kavya is observing your screen and listening to your voice." else "Kavya observes your screen to guide tasks hands-free.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // LIVE MICROPHONE STATUS & CONTROLS
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                backgroundColor = if (!isMicMuted && isSharing) SuccessGreen.copy(alpha = 0.12f) else SurfaceGlass.copy(alpha = 0.6f),
                borderColor = if (!isMicMuted && isSharing) SuccessGreen.copy(alpha = 0.35f) else GlassBorder
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (!isMicMuted) SuccessGreen.copy(alpha = 0.2f) else Error.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (!isMicMuted) Icons.Default.Mic else Icons.Default.MicOff,
                                contentDescription = if (!isMicMuted) "Mic On" else "Mic Muted",
                                tint = if (!isMicMuted) SuccessGreen else Error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (!isMicMuted) "Mic On (Listening) 🎙️" else "Microphone Muted 🔇",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = if (!isMicMuted) SuccessGreenGlow else Error
                            )
                            Text(
                                text = if (!isMicMuted) "Kavya hears your commands" else "Tap Unmute to speak",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = TextSecondary
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = { viewModel.toggleMicMute() },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isMicMuted) SuccessGreen.copy(alpha = 0.15f) else Error.copy(alpha = 0.15f),
                            contentColor = if (isMicMuted) SuccessGreen else Error
                        )
                    ) {
                        Text(
                            text = if (isMicMuted) "Unmute" else "Mute",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
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
    var selectedCategory by remember { mutableStateOf("ALL") }
    var searchQuery by remember { mutableStateOf("") }
    
    val allMemories by viewModel.memoryEngine.getAllMemoriesFlow().collectAsState(initial = emptyList())
    
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
            
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search memories, facts, preferences...", fontSize = 13.sp, color = TextTertiary) },
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
                listOf("ALL", "PREFERENCE", "IMPORTANT", "CRITICAL", "CONTEXT").forEach { cat ->
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
    val context = androidx.compose.ui.platform.LocalContext.current
    var offlineMode by remember { mutableStateOf(com.example.utils.AppPreferences.isOfflineFallbackEnabled(context)) }

    SimpleScreen("Advanced", navController) {
        Column(modifier = Modifier.padding(20.dp)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Offline Fallback Mode", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = TextPrimary)
                        Text("Prefer deterministic local execution when offline", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = TextSecondary)
                    }
                    Switch(
                        checked = offlineMode,
                        onCheckedChange = {
                            offlineMode = it
                            com.example.utils.AppPreferences.setOfflineFallbackEnabled(context, it)
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Primary, checkedTrackColor = PrimaryContainer)
                    )
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
fun AiProviderSettingsScreen(navController: NavController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var aiProvider by remember { mutableStateOf(com.example.utils.AppPreferences.getAiProvider(context)) }
    var geminiApiKey by remember { mutableStateOf(com.example.utils.AppPreferences.getCustomApiKey(context)) }
    var openRouterApiKey by remember { mutableStateOf(com.example.utils.AppPreferences.getOpenRouterApiKey(context)) }
    var openRouterModel by remember { mutableStateOf(com.example.utils.AppPreferences.getOpenRouterModel(context)) }
    
    var showGeminiKey by remember { mutableStateOf(false) }
    var showOpenRouterKey by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    val modelPresets = listOf(
        Triple("openrouter/free", "OpenRouter Free Auto", true),
        Triple("google/gemini-2.0-flash-exp:free", "Gemini 2.0 Flash", true),
        Triple("meta-llama/llama-3.3-70b-instruct:free", "Llama 3.3 70B", true),
        Triple("mistralai/mistral-7b-instruct:free", "Mistral 7B", true),
        Triple("anthropic/claude-3.5-sonnet", "Claude 3.5 Sonnet", false),
        Triple("openai/gpt-4o", "GPT-4o", false)
    )

    SimpleScreen("AI Provider & Models", navController) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsSectionHeader("Select AI Provider")
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    backgroundColor = SurfaceGlass.copy(alpha = 0.75f)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                aiProvider = "GEMINI"
                                com.example.utils.AppPreferences.setAiProvider(context, "GEMINI")
                                testStatus = null
                            }.padding(vertical = 8.dp)
                        ) {
                            RadioButton(
                                selected = aiProvider == "GEMINI",
                                onClick = {
                                    aiProvider = "GEMINI"
                                    com.example.utils.AppPreferences.setAiProvider(context, "GEMINI")
                                    testStatus = null
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Gemini (Google AI Studio)", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                Text("Native multimodal & fast responses", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                            }
                        }
                        HorizontalDivider(color = OutlineVariant, modifier = Modifier.padding(vertical = 4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                aiProvider = "OPENROUTER"
                                com.example.utils.AppPreferences.setAiProvider(context, "OPENROUTER")
                                testStatus = null
                            }.padding(vertical = 8.dp)
                        ) {
                            RadioButton(
                                selected = aiProvider == "OPENROUTER",
                                onClick = {
                                    aiProvider = "OPENROUTER"
                                    com.example.utils.AppPreferences.setAiProvider(context, "OPENROUTER")
                                    testStatus = null
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("OpenRouter", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                Text("Access Llama, Claude, Mistral & Open models", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                            }
                        }
                    }
                }
            }

            if (aiProvider == "GEMINI") {
                item {
                    SettingsSectionHeader("Gemini Configuration")
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        backgroundColor = SurfaceGlass.copy(alpha = 0.75f)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Gemini API Key", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            OutlinedTextField(
                                value = geminiApiKey,
                                onValueChange = { 
                                    geminiApiKey = it 
                                    testStatus = null
                                },
                                placeholder = { Text("AIzaSy... (leave blank for default)", color = TextTertiary) },
                                visualTransformation = if (showGeminiKey) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { showGeminiKey = !showGeminiKey }) {
                                        Icon(
                                            imageVector = if (showGeminiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = "Toggle Key Visibility",
                                            tint = TextTertiary
                                        )
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (testStatus != null) {
                                val isPass = testStatus!!.first
                                Text(
                                    text = testStatus!!.second,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isPass) SuccessGreenGlow else Error
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (geminiApiKey.isNotBlank()) {
                                    TextButton(
                                        onClick = {
                                            geminiApiKey = ""
                                            com.example.utils.AppPreferences.clearCustomApiKey(context)
                                            testStatus = Pair(true, "Gemini key removed. Using default credentials.")
                                        }
                                    ) {
                                        Text("Remove Key", color = Error)
                                    }
                                } else {
                                    Spacer(modifier = Modifier.width(1.dp))
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                isTesting = true
                                                val res = com.example.utils.AppPreferences.validateGeminiApiKey(
                                                    if (geminiApiKey.isNotBlank()) geminiApiKey else com.example.BuildConfig.GEMINI_API_KEY
                                                )
                                                testStatus = res
                                                isTesting = false
                                            }
                                        }
                                    ) {
                                        Text(if (isTesting) "Testing..." else "Test Key", color = AccentCyan)
                                    }

                                    Button(
                                        onClick = {
                                            if (geminiApiKey.isNotBlank()) {
                                                com.example.utils.AppPreferences.setCustomApiKey(context, geminiApiKey.trim())
                                                testStatus = Pair(true, "Gemini Key saved securely.")
                                            } else {
                                                com.example.utils.AppPreferences.clearCustomApiKey(context)
                                                testStatus = Pair(true, "Reset to default credentials.")
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                                    ) {
                                        Text("Save Key")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (aiProvider == "OPENROUTER") {
                item {
                    SettingsSectionHeader("OpenRouter API Key")
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        backgroundColor = SurfaceGlass.copy(alpha = 0.75f)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                "Enter your OpenRouter API key. It will be stored securely on your device and never exposed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            OutlinedTextField(
                                value = openRouterApiKey,
                                onValueChange = { 
                                    openRouterApiKey = it
                                    testStatus = null
                                },
                                placeholder = { Text("sk-or-v1-...", color = TextTertiary) },
                                visualTransformation = if (showOpenRouterKey) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { showOpenRouterKey = !showOpenRouterKey }) {
                                        Icon(
                                            imageVector = if (showOpenRouterKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = "Toggle Key Visibility",
                                            tint = TextTertiary
                                        )
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (testStatus != null) {
                                val isPass = testStatus!!.first
                                Text(
                                    text = testStatus!!.second,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isPass) SuccessGreenGlow else Error
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (openRouterApiKey.isNotBlank()) {
                                    TextButton(
                                        onClick = {
                                            openRouterApiKey = ""
                                            com.example.utils.AppPreferences.clearOpenRouterApiKey(context)
                                            testStatus = Pair(true, "OpenRouter API Key removed.")
                                        }
                                    ) {
                                        Text("Remove Key", color = Error)
                                    }
                                } else {
                                    Spacer(modifier = Modifier.width(1.dp))
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                isTesting = true
                                                testStatus = com.example.utils.AppPreferences.validateOpenRouterConnection(
                                                    apiKey = openRouterApiKey.trim(),
                                                    model = openRouterModel.ifBlank { "openrouter/free" }
                                                )
                                                isTesting = false
                                            }
                                        }
                                    ) {
                                        Text(if (isTesting) "Testing..." else "Test Connection", color = AccentCyan)
                                    }

                                    Button(
                                        onClick = {
                                            if (openRouterApiKey.isNotBlank()) {
                                                com.example.utils.AppPreferences.setOpenRouterApiKey(context, openRouterApiKey.trim())
                                                testStatus = Pair(true, "OpenRouter API Key saved securely.")
                                            } else {
                                                com.example.utils.AppPreferences.clearOpenRouterApiKey(context)
                                                testStatus = Pair(true, "OpenRouter key cleared.")
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                                    ) {
                                        Text("Save Key")
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    SettingsSectionHeader("Model Selection")
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        backgroundColor = SurfaceGlass.copy(alpha = 0.75f)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "Choose a preset model or enter any custom model ID from OpenRouter. Free models are clearly tagged below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )

                            modelPresets.forEach { (presetModelId, label, isFree) ->
                                val isSelected = openRouterModel == presetModelId
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (isSelected) Primary.copy(alpha = 0.15f) else Color.Transparent)
                                        .clickable {
                                            openRouterModel = presetModelId
                                            com.example.utils.AppPreferences.setOpenRouterModel(context, presetModelId)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = {
                                            openRouterModel = presetModelId
                                            com.example.utils.AppPreferences.setOpenRouterModel(context, presetModelId)
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(label, color = TextPrimary, fontWeight = FontWeight.Medium)
                                        Text(presetModelId, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = TextTertiary)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isFree) SuccessGreenGlow.copy(alpha = 0.2f) else AccentPurpleLight.copy(alpha = 0.2f))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = if (isFree) "FREE" else "PAID",
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                            color = if (isFree) SuccessGreenGlow else AccentPurpleLight
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(color = OutlineVariant, modifier = Modifier.padding(vertical = 4.dp))

                            Text("Custom Model ID", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            OutlinedTextField(
                                value = openRouterModel,
                                onValueChange = { 
                                    openRouterModel = it
                                },
                                placeholder = { Text("openrouter/free or custom/model-id", color = TextTertiary) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Button(
                                onClick = {
                                    if (openRouterModel.isNotBlank()) {
                                        com.example.utils.AppPreferences.setOpenRouterModel(context, openRouterModel.trim())
                                        testStatus = Pair(true, "Model updated to: $openRouterModel")
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Save Model")
                            }
                        }
                    }
                }

                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        backgroundColor = SurfaceGlass.copy(alpha = 0.5f)
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("About OpenRouter Tier & Voice", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = TextPrimary)
                            }
                            Text(
                                "• Free models may experience rate limits during peak usage.\n• Paid models require account credits on OpenRouter.\n• Kavya's responses are spoken using Gemini Cloud Voice for 100% natural, lifelike expressiveness with zero robotic Android TTS.",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}
