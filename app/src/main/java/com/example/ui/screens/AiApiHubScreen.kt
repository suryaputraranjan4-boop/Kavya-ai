package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.memory.okf.OkfCategory
import com.example.memory.okf.OkfKnowledgeUnit
import com.example.memory.okf.OkfMemoryRepository
import com.example.scraper.maps.GoogleMapsScraperClient
import com.example.scraper.maps.ScraperHealthStatus
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.ai.providers.ConnectionStatus
import com.example.ai.providers.RuntimeActivityEvent
import com.example.ai.providers.RuntimeActivityTracker
import com.example.ui.components.GlassCard
import com.example.ui.theme.*
import com.example.utils.AppPreferences
import com.example.utils.CustomApiConfig
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiApiHubScreen(navController: NavController) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Activity tracker events
    val recentEvents by RuntimeActivityTracker.eventsFlow.collectAsState()
    val metrics = remember(recentEvents) { RuntimeActivityTracker.getMetrics(context) }

    // OpenRouter State
    var orKeyInput by remember { mutableStateOf(AppPreferences.getOpenRouterApiKey(context)) }
    var orModel by remember { mutableStateOf(AppPreferences.getOpenRouterModel(context).ifBlank { "openrouter/free" }) }
    var orEnabled by remember { mutableStateOf(AppPreferences.isOpenRouterEnabled(context)) }
    var isOrKeyVisible by remember { mutableStateOf(false) }
    var isOrEditing by remember { mutableStateOf(false) }
    var isOrTesting by remember { mutableStateOf(false) }
    var orTestResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var availableOrModels by remember { mutableStateOf<List<String>>(com.example.ai.providers.OpenRouterProvider.DEFAULT_VERIFIED_MODELS.map { it.id }) }

    LaunchedEffect(orKeyInput) {
        if (orKeyInput.isNotBlank()) {
            try {
                val models = com.example.ai.providers.OpenRouterProvider().getModels(context)
                if (models.isNotEmpty()) {
                    availableOrModels = models.map { it.id }
                }
            } catch (_: Exception) {}
        }
    }

    // Hugging Face State
    var hfKeyInput by remember { mutableStateOf(AppPreferences.getHuggingFaceApiKey(context)) }
    var hfModel by remember { mutableStateOf(AppPreferences.getHuggingFaceModel(context).ifBlank { "distilbert-base-uncased" }) }
    var hfEnabled by remember { mutableStateOf(AppPreferences.isHuggingFaceEnabled(context)) }
    var isHfKeyVisible by remember { mutableStateOf(false) }
    var isHfEditing by remember { mutableStateOf(false) }
    var isHfTesting by remember { mutableStateOf(false) }
    var hfTestResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var availableHfModels by remember { mutableStateOf<List<String>>(com.example.ai.providers.HuggingFaceProvider.DEFAULT_VERIFIED_MODELS.map { it.id }) }

    LaunchedEffect(hfKeyInput) {
        if (hfKeyInput.isNotBlank()) {
            try {
                val models = com.example.ai.providers.HuggingFaceProvider().getModels(context)
                if (models.isNotEmpty()) {
                    availableHfModels = models.map { it.id }
                }
            } catch (_: Exception) {}
        }
    }

    // Gemma 4 E4B State
    val gemmaStatus by com.example.ai.offline.GemmaModelManager.status.collectAsState()
    var gemmaFile by remember { mutableStateOf(com.example.ai.offline.GemmaModelManager.getModelFile(context)) }
    var isGemmaImporting by remember { mutableStateOf(false) }
    var selectedAiProvider by remember { mutableStateOf(AppPreferences.getAiProvider(context)) }

    val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                isGemmaImporting = true
                val success = com.example.ai.offline.GemmaModelManager.importModelFromUri(context, uri)
                if (success) {
                    gemmaFile = com.example.ai.offline.GemmaModelManager.getModelFile(context)
                }
                isGemmaImporting = false
            }
        }
    }
    // Gemini State
    var geminiKeyInput by remember { mutableStateOf(AppPreferences.getCustomApiKey(context)) }
    var geminiModel by remember { mutableStateOf(AppPreferences.getGeminiModel(context)) }
    var isGeminiKeyVisible by remember { mutableStateOf(false) }
    var isGeminiEditing by remember { mutableStateOf(false) }
    var isGeminiTesting by remember { mutableStateOf(false) }
    var geminiTestResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val effectiveGeminiKey = AppPreferences.getEffectiveApiKey(context)
    val geminiPresetModels = remember {
        com.example.ai.providers.GeminiProvider.VERIFIED_MODELS.map { it.id }
    }

    // Custom & Future APIs State
    var customApis by remember { mutableStateOf(AppPreferences.getCustomApis(context)) }
    var showAddApiDialog by remember { mutableStateOf(false) }
    var customApiTestResults by remember { mutableStateOf<Map<String, Pair<Boolean, String>>>(emptyMap()) }
    var testingApiId by remember { mutableStateOf<String?>(null) }

    // Accessibility Service State
    val isAccessibilityEnabled = com.example.services.KavyaAccessibilityService.instance != null

    // Google Maps Scraper State
    var mapsScraperUrlInput by remember { mutableStateOf(AppPreferences.getMapsScraperUrl(context)) }
    var mapsScraperEnabled by remember { mutableStateOf(AppPreferences.isMapsScraperEnabled(context)) }
    var mapsProxyHostInput by remember { mutableStateOf(AppPreferences.getMapsScraperProxyHost(context)) }
    var mapsProxyPortInput by remember { mutableStateOf(if (AppPreferences.getMapsScraperProxyPort(context) > 0) AppPreferences.getMapsScraperProxyPort(context).toString() else "") }
    var isMapsEditing by remember { mutableStateOf(false) }
    var isMapsTesting by remember { mutableStateOf(false) }
    var mapsTestStatus by remember { mutableStateOf<ScraperHealthStatus?>(null) }

    // OKF Memory State
    val okfRepo = remember { OkfMemoryRepository.getInstance(context) }
    val okfUnits: List<OkfKnowledgeUnit> by okfRepo.unitsFlow.collectAsState(initial = emptyList())
    var okfEnabled by remember { mutableStateOf(AppPreferences.isOkfMemoryEnabled(context)) }

    // Background Voice & Gesture State
    var backgroundVoiceEnabled by remember { mutableStateOf(AppPreferences.isBackgroundVoiceEnabled(context)) }
    var micListeningEnabled by remember { mutableStateOf(AppPreferences.isMicListeningEnabled(context)) }
    var wakeGestureEnabled by remember { mutableStateOf(AppPreferences.isWakeGestureEnabled(context)) }
    var sleepGestureEnabled by remember { mutableStateOf(AppPreferences.isSleepGestureEnabled(context)) }
    var wakeSensitivity by remember { mutableStateOf(AppPreferences.getWakeSensitivity(context)) }
    var sleepSensitivity by remember { mutableStateOf(AppPreferences.getSleepSensitivity(context)) }
    var kavyaState by remember { mutableStateOf(AppPreferences.getKavyaState(context)) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Background, BackgroundGradientEnd, Color(0xFF0D0F17))
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top Bar
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "API Dashboard",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.5).sp
                            ),
                            color = TextPrimary
                        )
                        Text(
                            text = "Manage OpenRouter, Hugging Face, Gemini & Custom APIs",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            // Info Banner
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    backgroundColor = SurfaceGlass.copy(alpha = 0.75f),
                    borderColor = AccentPurpleLight.copy(alpha = 0.4f)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.VpnKey,
                            contentDescription = null,
                            tint = AccentPurpleLight,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "Multi-Provider AI & API Hub",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = AccentPurpleLight
                            )
                            Text(
                                "Configure your real API keys below. Kavya connects directly to real official endpoints. You can also add more custom APIs anytime.",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            // Offline Gemma 4 E4B Section
            item {
                Text(
                    text = "Offline Gemma 4 E4B AI Engine",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }

            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    backgroundColor = SurfaceGlass.copy(alpha = 0.85f),
                    borderColor = SuccessGreenGlow.copy(alpha = 0.4f)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Memory,
                                    contentDescription = null,
                                    tint = SuccessGreenGlow,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        "Gemma 4 E4B (Offline)",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = TextPrimary
                                    )
                                    Text(
                                        "On-Device LiteRT-LM Engine (Samsung Galaxy A16 5G Optimized)",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                        color = TextSecondary
                                    )
                                }
                            }

                            RoleBadge(
                                label = if (selectedAiProvider == "GEMMA_OFFLINE") "ACTIVE ENGINE" else "OFFLINE READY",
                                color = if (selectedAiProvider == "GEMMA_OFFLINE") SuccessGreenGlow else AccentCyan
                            )
                        }

                        HorizontalDivider(color = OutlineVariant.copy(alpha = 0.5f))

                        // Model Status & File details
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Status: ${if (gemmaFile != null) "READY" else gemmaStatus.name}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (gemmaFile != null) SuccessGreenGlow else Error
                                )
                                if (gemmaFile != null) {
                                    Text(
                                        text = "File: ${gemmaFile?.name} (${(gemmaFile?.length() ?: 0) / (1024 * 1024)} MB)",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                        color = TextSecondary
                                    )
                                } else {
                                    Text(
                                        text = "No local model found. Import gemma-4-e4b.bin or .task model file.",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                        color = TextTertiary
                                    )
                                }
                            }
                        }

                        // Buttons for importing model and toggling active engine
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { filePickerLauncher.launch("*/*") },
                                enabled = !isGemmaImporting,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                if (isGemmaImporting) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                } else {
                                    Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                Text("Import Model", fontSize = 12.sp, color = AccentCyan)
                            }

                            Button(
                                onClick = {
                                    val newProvider = if (selectedAiProvider == "GEMMA_OFFLINE") "GEMINI" else "GEMMA_OFFLINE"
                                    selectedAiProvider = newProvider
                                    AppPreferences.setAiProvider(context, newProvider)
                                    AppPreferences.setOfflineFallbackEnabled(context, newProvider == "GEMMA_OFFLINE")
                                },
                                enabled = gemmaFile != null,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selectedAiProvider == "GEMMA_OFFLINE") SuccessGreen else Primary
                                )
                            ) {
                                Text(
                                    if (selectedAiProvider == "GEMMA_OFFLINE") "Active (Offline)" else "Use Offline Gemma",
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    backgroundColor = SurfaceGlass.copy(alpha = 0.85f),
                    borderColor = SuccessGreen.copy(alpha = 0.4f)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = SuccessGreen,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    "Voice & Background Service",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = TextPrimary
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = when (kavyaState) {
                                    "ACTIVE" -> SuccessGreen.copy(alpha = 0.2f)
                                    "SLEEP" -> Color.Red.copy(alpha = 0.2f)
                                    else -> AccentPurpleLight.copy(alpha = 0.2f)
                                },
                                modifier = Modifier.padding(4.dp)
                            ) {
                                Text(
                                    text = "State: $kavyaState",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = when (kavyaState) {
                                        "ACTIVE" -> SuccessGreen
                                        "SLEEP" -> Color.Red
                                        else -> AccentPurpleLight
                                    },
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Kavya runs in the background with persistent notification when you leave the app, keeping voice interaction alive.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        // Toggles
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Background Voice Service", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                            Switch(
                                checked = backgroundVoiceEnabled,
                                onCheckedChange = {
                                    backgroundVoiceEnabled = it
                                    AppPreferences.setBackgroundVoiceEnabled(context, it)
                                    if (it) {
                                        val intent = Intent(context, com.example.services.KavyaVoiceService::class.java)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            context.startForegroundService(intent)
                                        } else {
                                            context.startService(intent)
                                        }
                                    } else {
                                        context.stopService(Intent(context, com.example.services.KavyaVoiceService::class.java))
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = SuccessGreen)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Microphone Listening (Mic Active)", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                            Switch(
                                checked = micListeningEnabled,
                                onCheckedChange = {
                                    micListeningEnabled = it
                                    AppPreferences.setMicListeningEnabled(context, it)
                                    val intent = Intent(context, com.example.services.KavyaVoiceService::class.java).apply {
                                        action = "ACTION_TOGGLE_MIC"
                                    }
                                    context.startService(intent)
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = SuccessGreen)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Wake Gesture Enabled", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                            Switch(
                                checked = wakeGestureEnabled,
                                onCheckedChange = {
                                    wakeGestureEnabled = it
                                    AppPreferences.setWakeGestureEnabled(context, it)
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = SuccessGreen)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Sleep Gesture Enabled", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                            Switch(
                                checked = sleepGestureEnabled,
                                onCheckedChange = {
                                    sleepGestureEnabled = it
                                    AppPreferences.setSleepGestureEnabled(context, it)
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = SuccessGreen)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Wake Sensitivity: ${(wakeSensitivity * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                        Slider(
                            value = wakeSensitivity,
                            onValueChange = {
                                wakeSensitivity = it
                                AppPreferences.setWakeSensitivity(context, it)
                            },
                            valueRange = 0.1f..1f
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Sleep Sensitivity: ${(sleepSensitivity * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                        Slider(
                            value = sleepSensitivity,
                            onValueChange = {
                                sleepSensitivity = it
                                AppPreferences.setSleepSensitivity(context, it)
                            },
                            valueRange = 0.1f..1f
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    kavyaState = "ACTIVE"
                                    AppPreferences.setKavyaState(context, "ACTIVE")
                                    val intent = Intent(context, com.example.services.KavyaVoiceService::class.java).apply { action = "ACTION_WAKE" }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
                            ) {
                                Text("Wake Up / Active")
                            }
                            OutlinedButton(
                                onClick = {
                                    kavyaState = "SLEEP"
                                    AppPreferences.setKavyaState(context, "SLEEP")
                                    val intent = Intent(context, com.example.services.KavyaVoiceService::class.java).apply { action = "ACTION_SLEEP" }
                                    context.startService(intent)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Sleep / Idle")
                            }
                        }
                    }
                }
            }

            // Local / No External API Diagnostics Section
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    backgroundColor = SurfaceGlass.copy(alpha = 0.85f),
                    borderColor = SuccessGreenGlow.copy(alpha = 0.35f)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(SuccessGreenGlow.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = SuccessGreenGlow,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Local / No External API Setup",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Zero external credentials or cloud subscriptions required for core local features",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = TextSecondary
                                )
                            }
                        }

                        HorizontalDivider(color = OutlineVariant.copy(alpha = 0.5f))

                        // Diagnostics list
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black.copy(alpha = 0.25f))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "OKF Memory",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = TextPrimary
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "✓ Local",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                        color = SuccessGreenGlow
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Maps Scraper",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = TextPrimary
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "✓ Local",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                        color = SuccessGreenGlow
                                    )
                                }
                            }

                            HorizontalDivider(color = OutlineVariant.copy(alpha = 0.3f))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "External Memory API",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                                Text(
                                    text = "Not required",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = AccentCyan
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Google Maps API Key",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                                Text(
                                    text = "Not required",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = AccentCyan
                                )
                            }
                        }

                        Text(
                            text = "• OKF Agent Memory operates locally with embedded Room + BM25 search without embedding APIs.\n• Google Maps Scraper Kit connects to your local container without requiring Google Cloud keys.",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp, lineHeight = 15.sp),
                            color = TextTertiary
                        )
                    }
                }
            }

            // =========================================================================
            // 1. OPENROUTER API
            // =========================================================================
            item {
                Text(
                    text = "OpenRouter API",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }

            item {
                ApiCard(
                    title = "OpenRouter",
                    badge = "AI PARTNER / MODELS",
                    badgeColor = AccentPurpleLight,
                    icon = Icons.Default.Hub,
                    isEnabled = orEnabled,
                    onToggleEnabled = {
                        orEnabled = it
                        AppPreferences.setOpenRouterEnabled(context, it)
                    },
                    status = if (!orEnabled) {
                        ConnectionStatus.DISABLED
                    } else if (orKeyInput.isBlank()) {
                        ConnectionStatus.NOT_CONFIGURED
                    } else if (isOrTesting) {
                        ConnectionStatus.TESTING
                    } else if (orTestResult?.first == true) {
                        ConnectionStatus.CONNECTED
                    } else if (orTestResult?.first == false) {
                        ConnectionStatus.ERROR
                    } else {
                        ConnectionStatus.CONFIGURED
                    },
                    apiKey = orKeyInput,
                    onApiKeyChange = { orKeyInput = it },
                    isKeyVisible = isOrKeyVisible,
                    onToggleKeyVisibility = { isOrKeyVisible = !isOrKeyVisible },
                    isEditing = isOrEditing,
                    onStartEdit = { isOrEditing = true },
                    onSaveKey = {
                        AppPreferences.setOpenRouterApiKey(context, orKeyInput.trim())
                        isOrEditing = false
                        coroutineScope.launch {
                            isOrTesting = true
                            orTestResult = AppPreferences.validateOpenRouterConnection(orKeyInput, orModel)
                            isOrTesting = false
                        }
                    },
                    onRemoveKey = {
                        orKeyInput = ""
                        AppPreferences.clearOpenRouterApiKey(context)
                        isOrEditing = false
                        orTestResult = null
                    },
                    selectedModel = orModel,
                    onModelChange = {
                        orModel = it
                        AppPreferences.setOpenRouterModel(context, it)
                    },
                    presetModels = availableOrModels,
                    modelPlaceholder = "e.g. openrouter/free or meta-llama/llama-3.3-70b-instruct:free",
                    isTesting = isOrTesting,
                    testResult = orTestResult,
                    onTestConnection = {
                        coroutineScope.launch {
                            isOrTesting = true
                            orTestResult = AppPreferences.validateOpenRouterConnection(orKeyInput, orModel)
                            isOrTesting = false
                        }
                    }
                )
            }

            // =========================================================================
            // 2. HUGGING FACE API
            // =========================================================================
            item {
                Text(
                    text = "Hugging Face API",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }

            item {
                ApiCard(
                    title = "Hugging Face",
                    badge = "SPECIALIZED INFERENCE",
                    badgeColor = AccentYellow,
                    icon = Icons.Default.SmartToy,
                    isEnabled = hfEnabled,
                    onToggleEnabled = {
                        hfEnabled = it
                        AppPreferences.setHuggingFaceEnabled(context, it)
                    },
                    status = if (!hfEnabled) {
                        ConnectionStatus.DISABLED
                    } else if (hfKeyInput.isBlank()) {
                        ConnectionStatus.NOT_CONFIGURED
                    } else if (isHfTesting) {
                        ConnectionStatus.TESTING
                    } else if (hfTestResult?.first == true) {
                        ConnectionStatus.CONNECTED
                    } else if (hfTestResult?.first == false) {
                        ConnectionStatus.ERROR
                    } else {
                        ConnectionStatus.CONFIGURED
                    },
                    apiKey = hfKeyInput,
                    onApiKeyChange = { hfKeyInput = it },
                    isKeyVisible = isHfKeyVisible,
                    onToggleKeyVisibility = { isHfKeyVisible = !isHfKeyVisible },
                    isEditing = isHfEditing,
                    onStartEdit = { isHfEditing = true },
                    onSaveKey = {
                        AppPreferences.setHuggingFaceApiKey(context, hfKeyInput.trim())
                        isHfEditing = false
                        coroutineScope.launch {
                            isHfTesting = true
                            hfTestResult = AppPreferences.validateHuggingFaceConnection(hfKeyInput, hfModel)
                            isHfTesting = false
                        }
                    },
                    onRemoveKey = {
                        hfKeyInput = ""
                        AppPreferences.clearHuggingFaceApiKey(context)
                        isHfEditing = false
                        hfTestResult = null
                    },
                    selectedModel = hfModel,
                    onModelChange = {
                        hfModel = it
                        AppPreferences.setHuggingFaceModel(context, it)
                    },
                    presetModels = availableHfModels,
                    modelPlaceholder = "e.g. distilbert-base-uncased or cardiffnlp/twitter-roberta...",
                    isTesting = isHfTesting,
                    testResult = hfTestResult,
                    onTestConnection = {
                        coroutineScope.launch {
                            isHfTesting = true
                            hfTestResult = AppPreferences.validateHuggingFaceConnection(hfKeyInput, hfModel)
                            isHfTesting = false
                        }
                    }
                )
            }

            // =========================================================================
            // 3. GOOGLE GEMINI API (Main Brain)
            // =========================================================================
            item {
                Text(
                    text = "Google Gemini API (Main Brain)",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }

            item {
                ApiCard(
                    title = "Google Gemini",
                    badge = "MAIN BRAIN / BOSS",
                    badgeColor = AccentCyan,
                    icon = Icons.Default.Psychology,
                    isEnabled = true,
                    onToggleEnabled = null, // Main orchestrator is always on
                    status = if (effectiveGeminiKey.isBlank()) {
                        ConnectionStatus.NOT_CONFIGURED
                    } else if (isGeminiTesting) {
                        ConnectionStatus.TESTING
                    } else if (geminiTestResult?.first == true) {
                        ConnectionStatus.CONNECTED
                    } else if (geminiTestResult?.first == false) {
                        ConnectionStatus.ERROR
                    } else {
                        ConnectionStatus.CONFIGURED
                    },
                    apiKey = geminiKeyInput,
                    onApiKeyChange = { geminiKeyInput = it },
                    isKeyVisible = isGeminiKeyVisible,
                    onToggleKeyVisibility = { isGeminiKeyVisible = !isGeminiKeyVisible },
                    isEditing = isGeminiEditing,
                    onStartEdit = { isGeminiEditing = true },
                    onSaveKey = {
                        AppPreferences.setCustomApiKey(context, geminiKeyInput.trim())
                        isGeminiEditing = false
                        coroutineScope.launch {
                            isGeminiTesting = true
                            geminiTestResult = AppPreferences.validateGeminiApiKey(
                                if (geminiKeyInput.isNotBlank()) geminiKeyInput else effectiveGeminiKey
                            )
                            isGeminiTesting = false
                        }
                    },
                    onRemoveKey = {
                        geminiKeyInput = ""
                        AppPreferences.clearCustomApiKey(context)
                        isGeminiEditing = false
                        geminiTestResult = null
                    },
                    selectedModel = geminiModel,
                    onModelChange = {
                        geminiModel = it
                        AppPreferences.setGeminiModel(context, it)
                    },
                    presetModels = geminiPresetModels,
                    modelPlaceholder = "gemini-2.5-flash",
                    isTesting = isGeminiTesting,
                    testResult = geminiTestResult,
                    onTestConnection = {
                        coroutineScope.launch {
                            isGeminiTesting = true
                            geminiTestResult = AppPreferences.validateGeminiApiKey(
                                if (geminiKeyInput.isNotBlank()) geminiKeyInput else effectiveGeminiKey
                            )
                            isGeminiTesting = false
                        }
                    }
                )
            }

            // =========================================================================
            // 4. CUSTOM & FUTURE APIS (In Future add more APIs)
            // =========================================================================
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Custom & Future APIs",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            text = "Add Groq, Perplexity, Together AI, Ollama or any REST API",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Button(
                        onClick = { showAddApiDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("+ Add API", fontSize = 12.sp)
                    }
                }
            }

            if (customApis.isEmpty()) {
                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        backgroundColor = SurfaceGlass.copy(alpha = 0.6f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.AddCircleOutline,
                                contentDescription = null,
                                tint = TextTertiary,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                "No Custom APIs Added Yet",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = TextSecondary
                            )
                            Text(
                                "You can add any other API provider (Groq, Perplexity, DeepSeek, Together AI, Local Ollama, etc.) anytime by clicking '+ Add API'.",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                color = TextTertiary,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(customApis, key = { it.id }) { api ->
                    CustomApiCard(
                        api = api,
                        isTesting = testingApiId == api.id,
                        testResult = customApiTestResults[api.id],
                        onToggleEnabled = { isEnabled ->
                            AppPreferences.setCustomApiEnabled(context, api.id, isEnabled)
                            customApis = AppPreferences.getCustomApis(context)
                        },
                        onDelete = {
                            AppPreferences.deleteCustomApi(context, api.id)
                            customApis = AppPreferences.getCustomApis(context)
                        },
                        onTest = {
                            coroutineScope.launch {
                                testingApiId = api.id
                                val res = AppPreferences.validateCustomApiConnection(api)
                                customApiTestResults = customApiTestResults + (api.id to res)
                                testingApiId = null
                            }
                        }
                    )
                }
            }

            // =========================================================================
            // 5. GOOGLE MAPS SCRAPER KIT (LOCAL & CONTAINERIZED ENGINE)
            // =========================================================================
            item {
                Text(
                    text = "Google Maps Scraper Kit",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )
            }

            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    backgroundColor = SurfaceGlass.copy(alpha = 0.85f),
                    borderColor = OutlineVariant
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(AccentCyan.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Place,
                                        contentDescription = null,
                                        tint = AccentCyan,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        "Local Scraper Service",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = TextPrimary
                                    )
                                    Text(
                                        "Mahanaicoach Scraper Kit API",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                        color = TextSecondary
                                    )
                                }
                            }

                            Switch(
                                checked = mapsScraperEnabled,
                                onCheckedChange = { isChecked ->
                                    mapsScraperEnabled = isChecked
                                    AppPreferences.setMapsScraperEnabled(context, isChecked)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = AccentCyan
                                )
                            )
                        }

                        // Status Row
                        val mapsStatus = when {
                            !mapsScraperEnabled -> ConnectionStatus.DISABLED
                            isMapsTesting -> ConnectionStatus.TESTING
                            mapsTestStatus != null && mapsTestStatus!!.isHealthy -> ConnectionStatus.CONNECTED
                            mapsTestStatus != null && !mapsTestStatus!!.isHealthy -> ConnectionStatus.ERROR
                            else -> ConnectionStatus.CONFIGURED
                        }
                        StatusIndicatorRow(
                            status = mapsStatus,
                            note = mapsTestStatus?.let { if (it.isHealthy) "${it.latencyMs}ms latency" else "Offline" }
                        )

                        if (isMapsEditing) {
                            OutlinedTextField(
                                value = mapsScraperUrlInput,
                                onValueChange = { mapsScraperUrlInput = it },
                                label = { Text("Service URL (Local or Remote)") },
                                placeholder = { Text("http://localhost:8080") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = mapsProxyHostInput,
                                    onValueChange = { mapsProxyHostInput = it },
                                    label = { Text("Proxy Host (Optional)") },
                                    placeholder = { Text("127.0.0.1") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1.5f)
                                )
                                OutlinedTextField(
                                    value = mapsProxyPortInput,
                                    onValueChange = { mapsProxyPortInput = it },
                                    label = { Text("Port") },
                                    placeholder = { Text("8080") },
                                    singleLine = true,
                                    modifier = Modifier.weight(0.9f)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = { isMapsEditing = false }) {
                                    Text("Cancel", color = TextSecondary)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        AppPreferences.setMapsScraperUrl(context, mapsScraperUrlInput)
                                        AppPreferences.setMapsScraperProxyHost(context, mapsProxyHostInput)
                                        AppPreferences.setMapsScraperProxyPort(context, mapsProxyPortInput.toIntOrNull() ?: 0)
                                        isMapsEditing = false
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                                ) {
                                    Text("Save Configuration", color = Color.Black)
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "URL: $mapsScraperUrlInput",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                    color = TextTertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { isMapsEditing = true }) {
                                    Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit", tint = TextSecondary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }

                        // Test Connection Button
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    isMapsTesting = true
                                    val client = GoogleMapsScraperClient(context)
                                    mapsTestStatus = client.checkHealth()
                                    isMapsTesting = false
                                }
                            },
                            enabled = !isMapsTesting && mapsScraperEnabled,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan.copy(alpha = 0.25f))
                        ) {
                            if (isMapsTesting) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = AccentCyan, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Testing Service...", color = AccentCyan)
                            } else {
                                Icon(imageVector = Icons.Default.NetworkCheck, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Ping Scraper Service", color = AccentCyan)
                            }
                        }

                        mapsTestStatus?.let { status ->
                            Text(
                                text = status.message,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                color = if (status.isHealthy) SuccessGreenGlow else Error
                            )
                        }

                        HorizontalDivider(color = OutlineVariant.copy(alpha = 0.4f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Google Maps API Key",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            Text(
                                text = "Not required (Local Scraping)",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = AccentCyan
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // 6. OKF AGENT MEMORY (GIT-NATIVE & BM25)
            // =========================================================================
            item {
                Text(
                    text = "OKF Agent Memory",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )
            }

            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    backgroundColor = SurfaceGlass.copy(alpha = 0.85f),
                    borderColor = OutlineVariant
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(AccentPurpleLight.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Storage,
                                        contentDescription = null,
                                        tint = AccentPurpleLight,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        "Git-Native Knowledge Units",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = TextPrimary
                                    )
                                    Text(
                                        "BM25 Search-Before-Write Storage",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                        color = TextSecondary
                                    )
                                }
                            }

                            Switch(
                                checked = okfEnabled,
                                onCheckedChange = { isChecked ->
                                    okfEnabled = isChecked
                                    AppPreferences.setOkfMemoryEnabled(context, isChecked)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = AccentPurpleLight
                                )
                            )
                        }

                        StatusIndicatorRow(
                            status = if (okfEnabled) ConnectionStatus.CONNECTED else ConnectionStatus.DISABLED,
                            note = "${okfUnits.size} knowledge units indexed"
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StatusMetricChip(
                                label = "TOTAL UNITS",
                                value = "${okfUnits.size}",
                                color = AccentPurpleLight,
                                modifier = Modifier.weight(1f)
                            )
                            StatusMetricChip(
                                label = "FACTS",
                                value = "${okfUnits.count { it.category.name == "FACT" }}",
                                color = AccentCyan,
                                modifier = Modifier.weight(1f)
                            )
                            StatusMetricChip(
                                label = "DECISIONS",
                                value = "${okfUnits.count { it.category.name == "DECISION" }}",
                                color = SuccessGreenGlow,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        HorizontalDivider(color = OutlineVariant.copy(alpha = 0.4f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "External Memory API",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            Text(
                                text = "Not required (BM25 Local Engine)",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = AccentCyan
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // 7. SYSTEM STATUS & RECENT ACTIVITY
            // =========================================================================
            item {
                Text(
                    text = "System Status & API Activity",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )
            }

            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    backgroundColor = SurfaceGlass.copy(alpha = 0.85f),
                    borderColor = OutlineVariant
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Metric Grid
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StatusMetricChip(
                                label = "TOTAL PROVIDERS",
                                value = "${(metrics["total_providers"] ?: 4) + customApis.size}",
                                color = AccentCyan,
                                modifier = Modifier.weight(1f)
                            )
                            StatusMetricChip(
                                label = "CONNECTED",
                                value = "${metrics["connected_providers"] ?: 0}",
                                color = SuccessGreenGlow,
                                modifier = Modifier.weight(1f)
                            )
                            StatusMetricChip(
                                label = "ACTIVE MODELS",
                                value = "${(metrics["active_ai_models"] ?: 0) + customApis.count { it.isEnabled }}",
                                color = AccentPurpleLight,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        HorizontalDivider(color = OutlineVariant.copy(alpha = 0.5f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Live API Events",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = TextSecondary
                            )
                            Text(
                                if (recentEvents.isEmpty()) "No requests yet" else "${recentEvents.size} events",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary
                            )
                        }

                        if (recentEvents.isEmpty()) {
                            Text(
                                "Real events will appear here as Gemini processes requests or consults OpenRouter, Hugging Face, or Custom APIs.",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                color = TextTertiary,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                recentEvents.take(5).forEach { event ->
                                    RuntimeEventRow(event)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // DIALOG: ADD NEW CUSTOM API
    // =========================================================================
    if (showAddApiDialog) {
        var newApiName by remember { mutableStateOf("") }
        var newApiEndpoint by remember { mutableStateOf("") }
        var newApiKey by remember { mutableStateOf("") }
        var newApiModel by remember { mutableStateOf("") }
        var isNewKeyVisible by remember { mutableStateOf(false) }
        var errorMsg by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showAddApiDialog = false },
            containerColor = Surface,
            title = {
                Text("Add New API Provider", color = TextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Add any future API (Groq, Perplexity, DeepSeek, Together AI, Mistral, Ollama, or custom endpoint).",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    OutlinedTextField(
                        value = newApiName,
                        onValueChange = { newApiName = it },
                        label = { Text("Provider Name (e.g. Groq)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = newApiEndpoint,
                        onValueChange = { newApiEndpoint = it },
                        label = { Text("Endpoint / Base URL") },
                        placeholder = { Text("https://api.groq.com/openai/v1/chat/completions") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = newApiKey,
                        onValueChange = { newApiKey = it },
                        label = { Text("API Key") },
                        visualTransformation = if (isNewKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { isNewKeyVisible = !isNewKeyVisible }) {
                                Icon(
                                    imageVector = if (isNewKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null
                                )
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = newApiModel,
                        onValueChange = { newApiModel = it },
                        label = { Text("Model Name (optional)") },
                        placeholder = { Text("e.g. llama-3.3-70b-versatile") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (errorMsg != null) {
                        Text(errorMsg!!, color = Error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newApiName.isBlank()) {
                            errorMsg = "Please provide a name for this API."
                            return@Button
                        }
                        if (newApiEndpoint.isBlank()) {
                            errorMsg = "Please provide the API endpoint URL."
                            return@Button
                        }
                        val config = CustomApiConfig(
                            name = newApiName.trim(),
                            endpointUrl = newApiEndpoint.trim(),
                            apiKey = newApiKey.trim(),
                            model = newApiModel.trim()
                        )
                        AppPreferences.saveCustomApi(context, config)
                        customApis = AppPreferences.getCustomApis(context)
                        showAddApiDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Text("Save API")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddApiDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

// -----------------------------------------------------------------------------
// REUSABLE API CARD COMPONENT
// -----------------------------------------------------------------------------

@Composable
fun ApiCard(
    title: String,
    badge: String,
    badgeColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isEnabled: Boolean,
    onToggleEnabled: ((Boolean) -> Unit)?,
    status: ConnectionStatus,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    isKeyVisible: Boolean,
    onToggleKeyVisibility: () -> Unit,
    isEditing: Boolean,
    onStartEdit: () -> Unit,
    onSaveKey: () -> Unit,
    onRemoveKey: () -> Unit,
    selectedModel: String,
    onModelChange: (String) -> Unit,
    presetModels: List<String>,
    modelPlaceholder: String,
    isTesting: Boolean,
    testResult: Pair<Boolean, String>?,
    onTestConnection: () -> Unit
) {
    var isCustomModelEditing by remember { mutableStateOf(false) }
    var customModelInput by remember(selectedModel) { mutableStateOf(selectedModel) }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        backgroundColor = SurfaceGlass.copy(alpha = 0.85f),
        borderColor = OutlineVariant
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(badgeColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = badgeColor, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            RoleBadge(label = badge, color = badgeColor)
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        StatusIndicatorRow(status = status)
                    }
                }

                if (onToggleEnabled != null) {
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = onToggleEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Primary
                        )
                    )
                }
            }

            // Model Selection
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Model: $selectedModel",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { isCustomModelEditing = !isCustomModelEditing }) {
                        Text(
                            if (isCustomModelEditing) "Presets" else "Custom Model",
                            color = AccentCyan,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                if (isCustomModelEditing) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customModelInput,
                            onValueChange = { customModelInput = it },
                            placeholder = { Text(modelPlaceholder, fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        )
                        Button(
                            onClick = {
                                onModelChange(customModelInput.trim())
                                isCustomModelEditing = false
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary)
                        ) {
                            Text("Set", fontSize = 12.sp)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        presetModels.take(3).forEach { option ->
                            val isSelected = option == selectedModel
                            val shortName = option.substringAfterLast("/").substringBefore(":")
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Primary.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.05f))
                                    .border(1.dp, if (isSelected) Primary else OutlineVariant, RoundedCornerShape(8.dp))
                                    .clickable { onModelChange(option) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = shortName,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    color = if (isSelected) AccentCyan else TextSecondary
                                )
                            }
                        }
                    }
                }
            }

            // API Key Section
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("API Key:", style = MaterialTheme.typography.labelSmall, color = TextTertiary)

                if (!isEditing && apiKey.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = SuccessGreenGlow, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "••••••••••••••••••••",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = onStartEdit) {
                                Text("Change", color = AccentCyan, style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = onRemoveKey) {
                                Text("Remove", color = Error, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = onApiKeyChange,
                        placeholder = { Text("Enter your API key...", color = TextTertiary) },
                        visualTransformation = if (isKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = onToggleKeyVisibility) {
                                Icon(
                                    imageVector = if (isKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = TextTertiary
                                )
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = onSaveKey,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary)
                        ) {
                            Text("Save Key")
                        }
                    }
                }
            }

            // Test Result Alert
            if (testResult != null) {
                val isPass = testResult.first
                Text(
                    text = testResult.second,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isPass) SuccessGreenGlow else Error
                )
            }

            // Action Bar
            HorizontalDivider(color = OutlineVariant.copy(alpha = 0.5f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onTestConnection,
                    enabled = !isTesting && isEnabled,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("Test Connection", color = AccentCyan)
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// CUSTOM API CARD COMPONENT
// -----------------------------------------------------------------------------

@Composable
fun CustomApiCard(
    api: CustomApiConfig,
    isTesting: Boolean,
    testResult: Pair<Boolean, String>?,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        backgroundColor = SurfaceGlass.copy(alpha = 0.8f),
        borderColor = OutlineVariant
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(AccentCyan.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Extension, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            api.name,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            api.endpointUrl,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = TextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Switch(
                    checked = api.isEnabled,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Primary
                    )
                )
            }

            val status = if (!api.isEnabled) {
                ConnectionStatus.DISABLED
            } else if (api.apiKey.isBlank()) {
                ConnectionStatus.NOT_CONFIGURED
            } else if (testResult?.first == true) {
                ConnectionStatus.CONNECTED
            } else if (testResult?.first == false) {
                ConnectionStatus.ERROR
            } else {
                ConnectionStatus.CONFIGURED
            }

            StatusIndicatorRow(status = status)

            if (api.model.isNotBlank()) {
                Text(
                    "Model: ${api.model}",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = TextSecondary
                )
            }

            if (testResult != null) {
                val isPass = testResult.first
                Text(
                    text = testResult.second,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = if (isPass) SuccessGreenGlow else Error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = Error)
                }

                OutlinedButton(
                    onClick = onTest,
                    enabled = !isTesting && api.isEnabled,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("Test Endpoint", fontSize = 11.sp, color = AccentCyan)
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// HELPER COMPOSABLES
// -----------------------------------------------------------------------------

@Composable
fun StatusMetricChip(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .padding(vertical = 10.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = color
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold),
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun RoleBadge(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold),
            color = color
        )
    }
}

@Composable
fun StatusIndicatorRow(status: ConnectionStatus, note: String? = null) {
    val (dotColor, label) = when (status) {
        ConnectionStatus.CONNECTED -> Pair(SuccessGreenGlow, "Connected")
        ConnectionStatus.CONFIGURED -> Pair(AccentCyan, "Configured")
        ConnectionStatus.NOT_CONFIGURED -> Pair(TextTertiary, "Not Configured")
        ConnectionStatus.TESTING -> Pair(AccentYellow, "Testing...")
        ConnectionStatus.ERROR -> Pair(Error, "Error")
        ConnectionStatus.RATE_LIMITED -> Pair(Error, "Rate Limited")
        ConnectionStatus.DISABLED -> Pair(TextTertiary, "Disabled")
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = dotColor
        )
        if (!note.isNullOrBlank()) {
            Text(
                text = " • $note",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun RuntimeEventRow(event: RuntimeActivityEvent) {
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val formattedTime = timeFormat.format(Date(event.timestamp))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Text(
                text = formattedTime,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = TextTertiary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = event.providerId.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = AccentCyan
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = event.task,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${event.latencyMs}ms",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = TextTertiary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = if (event.success) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                tint = if (event.success) SuccessGreenGlow else Error,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

