package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.example.services.KavyaAccessibilityService
import com.example.services.ScreenShareManager
import com.example.ui.components.FloatingOrbControlCard
import com.example.ui.components.ScreenSharePreflightDialog
import com.example.ui.components.ScreenSharePreflightSummaryCard
import com.example.ui.components.rememberScreenSharePreflightSnapshot
import com.example.viewmodel.KavyaViewModel
import com.example.vision.VisionWorkspaceContent
import com.example.visual.*

/**
 * JARVIS-Style Screen Intelligence, Visual Explanation & Interactive Guidance Control Screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JarvisScreenIntelligenceScreen(
    navController: NavController,
    viewModel: KavyaViewModel,
    modifier: Modifier = Modifier
) {
    BackHandler {
        navController.popBackStack()
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val orbState by JarvisOverlayController.orbState.collectAsState()
    val orbConfig by JarvisOverlayController.orbConfig.collectAsState()
    val isOverlayRunning by JarvisOverlayController.isOverlayRunning.collectAsState()
    val audioAmplitude by JarvisOverlayController.audioAmplitude.collectAsState()
    val transientMotion by JarvisOverlayController.transientMotion.collectAsState()
    val activePlan by JarvisOverlayController.activePlan.collectAsState()
    val isScreenSharing by viewModel.isScreenSharing.collectAsState()
    val shareState by viewModel.screenShareState.collectAsState()
    val annotationSteps by ExplanationStepController.steps.collectAsState()
    val currentStepIdx by ExplanationStepController.currentStepIndex.collectAsState()

    val activeLevel by JarvisOverlayController.activeExplanationLevel.collectAsState()
    var selectedLevel by remember(activeLevel) { mutableStateOf(activeLevel) }

    val (preflightSnapshot, refreshPreflight) = rememberScreenSharePreflightSnapshot(shareState)
    var showPreflightDialog by remember { mutableStateOf(false) }
    var activeVisionTab by remember { mutableIntStateOf(0) }
    var requestedStudioTechId by remember { mutableStateOf<com.example.vision.VisionTechId?>(null) }
    var testQueryInput by remember { mutableStateOf("Explain this screen") }
    var actionFeedbackBanner by remember { mutableStateOf<String?>(null) }
    var hasOverlayPermission by remember {
        mutableStateOf(JarvisOverlayController.hasOverlayPermission(context))
    }
    var isAccessibilityActive by remember {
        mutableStateOf(KavyaAccessibilityService.instance != null)
    }

    LaunchedEffect(context) {
        JarvisGuidanceEngine.bindContext(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = JarvisOverlayController.hasOverlayPermission(context)
                isAccessibilityActive = KavyaAccessibilityService.instance != null
                refreshPreflight()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onScreenShareConsentResult(context, result.resultCode, result.data)
    }

    fun launchMediaProjectionConsent() {
        val consentIntent = viewModel.startScreenShareConsent(context)
        if (consentIntent != null) {
            mediaProjectionLauncher.launch(consentIntent)
        }
    }

    if (showPreflightDialog) {
        ScreenSharePreflightDialog(
            shareState = shareState,
            onDismiss = {
                refreshPreflight()
                showPreflightDialog = false
            },
            onRequestMediaProjection = {
                showPreflightDialog = false
                launchMediaProjectionConsent()
            },
            onOpenVoiceSettings = {
                showPreflightDialog = false
                navController.navigate("voice_settings")
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(com.example.ui.theme.PrimaryLight)
                        )
                        Text(
                            text = "Vision & JARVIS",
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp),
                            color = com.example.ui.theme.TextPrimary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = com.example.ui.theme.TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = com.example.ui.theme.Background
                )
            )
        },
        containerColor = com.example.ui.theme.Background,
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            com.example.ui.theme.Background,
                            com.example.ui.theme.BackgroundGradientEnd
                        )
                    )
                )
                .padding(paddingValues)
        ) {
            // Sleek 3-tab Apple Liquid Glass segmented control
            com.example.ui.components.AppleGlassSegmentedControl(
                options = listOf("Studio (12)", "Tech Library (12)", "JARVIS Orb"),
                selectedIndex = activeVisionTab,
                onSelect = { activeVisionTab = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            when (activeVisionTab) {
                0 -> {
                    VisionWorkspaceContent(
                        accentColor = com.example.ui.theme.PrimaryLight,
                        reducedMotion = orbConfig.reducedMotion,
                        requestedTechId = requestedStudioTechId,
                        onRequestedTechConsumed = { requestedStudioTechId = null },
                        modifier = Modifier.weight(1f)
                    )
                }
                1 -> {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(top = 6.dp, bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            com.example.vision.VisualizationTechnologyLibrarySection(
                                accentColor = com.example.ui.theme.PrimaryLight,
                                onOpenTechnology = { techId ->
                                    requestedStudioTechId = techId
                                    activeVisionTab = 0
                                }
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(top = 6.dp, bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 1. Live Interactive JARVIS Orb Preview Stage
                        item {
                            com.example.ui.components.GlassCard(
                                shape = RoundedCornerShape(22.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Interactive Orb Preview",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = com.example.ui.theme.TextPrimary
                                    )
                                    Text(
                                        text = "Tap the orb to preview all ${JarvisOrbState.entries.size} live states",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = com.example.ui.theme.TextSecondary
                                    )

                                    Spacer(modifier = Modifier.height(14.dp))

                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(164.dp)
                                            .clip(RoundedCornerShape(18.dp))
                                            .background(Color(0xFF080B12))
                                            .border(1.dp, com.example.ui.theme.OutlineVariant, RoundedCornerShape(18.dp))
                                    ) {
                                        JarvisFloatingOrb(
                                            state = orbState,
                                            config = orbConfig,
                                            amplitude = if (orbState == JarvisOrbState.IDLE) 0.25f else audioAmplitude.coerceAtLeast(0.45f),
                                            transientMotion = transientMotion,
                                            onTap = {
                                                val nextState = JarvisOrbState.entries[(orbState.ordinal + 1) % JarvisOrbState.entries.size]
                                                JarvisOverlayController.setOrbState(nextState)
                                            }
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    com.example.ui.components.StatusBadge(
                                        text = "State: ${orbState.name.replace("_", " ")}",
                                        isActive = true,
                                        activeColor = orbConfig.theme.primaryColor
                                    )
                                }
                            }
                        }

                        // Compact bridge to the 12 Visualization Technologies library
                        item {
                            val techManager = remember(context) {
                                com.example.vision.VisionTechnologyDownloadManager.getInstance(context)
                            }
                            val techSummary by techManager.storageSummary.collectAsState()
                            com.example.ui.components.GlassCard(
                                shape = RoundedCornerShape(18.dp),
                                onClick = { activeVisionTab = 1 },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Visualization Technology Library",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = com.example.ui.theme.TextPrimary
                                        )
                                        Text(
                                            text = "${techSummary.installedCount}/12 installed • ${techSummary.functionalCount}/12 verified for interactive scenes & diagrams",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = com.example.ui.theme.TextSecondary
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "Open Library",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = com.example.ui.theme.PrimaryLight
                                        )
                                        Icon(
                                            imageVector = Icons.Default.ChevronRight,
                                            contentDescription = "Open Visualization Technology Library",
                                            tint = com.example.ui.theme.PrimaryLight,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // 2. Dedicated Floating Orb Control Card
                        item {
                            FloatingOrbControlCard(
                                onPermissionRequiredPrompt = {
                                    refreshPreflight()
                                    showPreflightDialog = true
                                }
                            )
                        }

            // 3. Screen Share Preflight, Accessibility & Quick Controls
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF101724)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {

                        // Screen Share & JARVIS Preflight Summary Card
                        ScreenSharePreflightSummaryCard(
                            snapshot = preflightSnapshot,
                            onOpenPreflight = {
                                refreshPreflight()
                                showPreflightDialog = true
                            }
                        )

                        // Permission & Capture Readiness Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    isAccessibilityActive = KavyaAccessibilityService.instance != null
                                    if (!isAccessibilityActive) {
                                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        })
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                            ) {
                                Text(
                                    text = if (isAccessibilityActive) "✓ UI Tree (Optional)" else "Accessibility (Opt.)",
                                    color = if (isAccessibilityActive) Color(0xFF00E676) else Color(0xFFFFB74D),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    if (isScreenSharing || ScreenShareManager.isScreenSharingActive) {
                                        viewModel.stopScreenShare()
                                    } else {
                                        refreshPreflight()
                                        if (preflightSnapshot.canStartMediaProjection) {
                                            launchMediaProjectionConsent()
                                        } else {
                                            showPreflightDialog = true
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                            ) {
                                Text(
                                    text = if (isScreenSharing || ScreenShareManager.isScreenSharingActive) "● Screen Live (Stop)" else "Start Screen Share",
                                    color = if (isScreenSharing || ScreenShareManager.isScreenSharingActive) Color(0xFF00E5FF) else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        if (isOverlayRunning || annotationSteps.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (annotationSteps.size > 1) {
                                    OutlinedButton(
                                        onClick = {
                                            actionFeedbackBanner = JarvisGuidanceEngine.handleVoiceGuidanceControlCommand(context, "previous")
                                        },
                                        enabled = currentStepIdx > 0,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("◀ Prev", color = Color.White, fontSize = 11.sp)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            actionFeedbackBanner = JarvisGuidanceEngine.handleVoiceGuidanceControlCommand(context, "repeat")
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Repeat", color = Color.White, fontSize = 11.sp)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            actionFeedbackBanner = JarvisGuidanceEngine.handleVoiceGuidanceControlCommand(context, "next")
                                        },
                                        enabled = currentStepIdx < annotationSteps.size - 1,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Next ▶", color = orbConfig.theme.primaryColor, fontSize = 11.sp)
                                    }
                                }

                                Button(
                                    onClick = {
                                        JarvisGuidanceEngine.dismissExplanation()
                                        JarvisOverlayController.emergencyStopJarvis(context)
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD50000)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("✕ Stop Jarvis", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // 3. Theme Presets & Color Customization
            item {
                Text(
                    text = "THEME & COLOR MATRIX",
                    color = orbConfig.theme.primaryColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(JarvisThemePreset.entries) { preset ->
                        val isSelected = orbConfig.theme == preset
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) preset.primaryColor.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f),
                            modifier = Modifier
                                .border(
                                    width = if (isSelected) 1.5.dp else 0.8.dp,
                                    color = if (isSelected) preset.primaryColor else Color.White.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    JarvisOverlayController.updateAndPersistConfig(context) { it.copy(theme = preset) }
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(preset.primaryColor)
                                )
                                Text(
                                    text = preset.displayName,
                                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.7f),
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }

            // 4. Cursor Style Selection
            item {
                Text(
                    text = "ANIMATED CURSOR STYLE",
                    color = orbConfig.theme.primaryColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(CursorStyle.entries) { style ->
                        val isSelected = orbConfig.cursorStyle == style
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) orbConfig.theme.primaryColor.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f),
                            modifier = Modifier
                                .border(
                                    width = if (isSelected) 1.5.dp else 0.8.dp,
                                    color = if (isSelected) orbConfig.theme.primaryColor else Color.White.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    JarvisOverlayController.updateAndPersistConfig(context) { it.copy(cursorStyle = style) }
                                }
                        ) {
                            Text(
                                text = style.name.replace("_", " "),
                                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.7f),
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            // 5. Three Core Modes Interactive Action Hub
            item {
                Text(
                    text = "THREE CORE MODES • INTERACTION HUB",
                    color = orbConfig.theme.primaryColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF101724)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Explanation depth selector
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ScreenSharePresentationLevel.entries.forEach { lvl ->
                                val isLvlSelected = selectedLevel == lvl
                                FilterChip(
                                    selected = isLvlSelected,
                                    onClick = {
                                        selectedLevel = lvl
                                        JarvisOverlayController.setExplanationLevel(lvl)
                                    },
                                    label = {
                                        Text(
                                            text = lvl.title,
                                            fontSize = 11.sp,
                                            fontWeight = if (isLvlSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        OutlinedTextField(
                            value = testQueryInput,
                            onValueChange = { testQueryInput = it },
                            label = { Text("Query or Task (EN / HI / Hinglish)", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = orbConfig.theme.primaryColor,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )

                        // Quick visual query chips
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(
                                listOf(
                                    "Explain this screen",
                                    "Show pie chart breakdown",
                                    "Draw flowchart of screen workflow",
                                    "Show 3D spatial structure",
                                    "Guide me step by step"
                                )
                            ) { chipQuery ->
                                SuggestionChip(
                                    onClick = { testQueryInput = chipQuery },
                                    label = { Text(chipQuery, fontSize = 10.5.sp) }
                                )
                            }
                        }

                        actionFeedbackBanner?.let { feedback ->
                            Text(
                                text = feedback,
                                color = orbConfig.theme.primaryColor,
                                fontSize = 11.5.sp
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Mode A Button (Full Grounded Perception + AI Pipeline)
                            Button(
                                onClick = {
                                    val q = testQueryInput.ifBlank { "Explain this screen" }
                                    actionFeedbackBanner = "Running Mode A visual perception (${selectedLevel.title})..."
                                    JarvisGuidanceEngine.explainActiveScreenAsync(
                                        context = context,
                                        query = q,
                                        preferredLevel = selectedLevel
                                    )
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = orbConfig.theme.primaryColor),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                            ) {
                                Text("Mode A: Explain", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            // Mode B Button (Step-by-Step Interactive Walkthrough)
                            Button(
                                onClick = {
                                    val q = testQueryInput.ifBlank { "Guide me step by step on this screen" }
                                    val stepQuery = if (q.contains("step", true) || q.contains("how", true) || q.contains("kaise", true)) q else "Guide me step by step: $q"
                                    actionFeedbackBanner = "Generating interactive step walkthrough..."
                                    JarvisGuidanceEngine.explainActiveScreenAsync(
                                        context = context,
                                        query = stepQuery,
                                        preferredLevel = selectedLevel
                                    )
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9100)),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                            ) {
                                Text("Mode B: Show How", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            // Mode C Button (Routes through central AI-first autonomous execution pipeline)
                            Button(
                                onClick = {
                                    val q = testQueryInput.ifBlank { "Inspect and perform next requested action" }
                                    val plan = JarvisIntelligencePlanner.createPlan(
                                        userQuery = q,
                                        forcedMode = JarvisMode.DO_IT
                                    )
                                    JarvisOverlayController.presentVisualPlan(plan)
                                    viewModel.sendMessage(q)
                                    actionFeedbackBanner = "Dispatched to AI agent executor: $q"
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                            ) {
                                Text("Mode C: Do It", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Full Screen Explainer Launch
                        OutlinedButton(
                            onClick = {
                                val q = testQueryInput.ifBlank { "Explain the whole screen" }
                                actionFeedbackBanner = "Capturing screen & launching full-screen explainer..."
                                JarvisGuidanceEngine.explainActiveScreenAsync(
                                    context = context,
                                    query = q,
                                    preferredLevel = selectedLevel,
                                    openFullScreenExplainer = true
                                )
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Fullscreen, contentDescription = "Full Screen Explainer", tint = orbConfig.theme.primaryColor)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Launch 'Explain the Whole Screen' Layout", color = orbConfig.theme.primaryColor, fontSize = 12.sp)
                        }
                    }
                }
            }

            // 6. Active Plan Live Cards Demonstration (In-App View)
            if (activePlan != null) {
                item {
                    Text(
                        text = "ACTIVE VISUAL PLAN: ${activePlan!!.title.uppercase()}",
                        color = orbConfig.theme.primaryColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        activePlan!!.cards.forEach { card ->
                            JarvisExplanationCardDispatcher(
                                card = card,
                                theme = orbConfig.theme,
                                modifier = Modifier.fillMaxWidth(),
                                onClose = { JarvisOverlayController.dismissExplanationCard(card.id) }
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
                    }
                }
            }
        }
    }
}
