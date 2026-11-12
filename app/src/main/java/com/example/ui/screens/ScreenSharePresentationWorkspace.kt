package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.services.ScreenShareManager
import com.example.utils.AppPreferences
import com.example.viewmodel.KavyaViewModel
import com.example.visual.*

/**
 * Screen Share Presentation Workspace implementing the 3 Presentation Levels:
 * 1. BASIC: Direct on-screen annotations, highlight boxes, pointers, touch-pass-through overlay.
 * 2. STANDARD: Centered upright portrait preview with contextual cards, connecting pointers & 3D diagrams.
 * 3. ADVANCED: Futuristic visual-intelligence split workspace with telemetry, 3D wireframe models,
 *    live architecture flowcharts, and technical step cards.
 */
@Composable
fun ScreenSharePresentationWorkspace(
    viewModel: KavyaViewModel,
    initialLevel: ScreenSharePresentationLevel = ScreenSharePresentationLevel.STANDARD,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentLevel by remember {
        mutableStateOf(
            ScreenSharePresentationLevel.fromId(AppPreferences.getScreenPresentationLevel(context))
        )
    }

    val shareState by viewModel.screenShareState.collectAsState()
    val screenContext by viewModel.screenContextText.collectAsState()
    val activeExplanation by JarvisGuidanceEngine.activeExplanation.collectAsState()
    val guidanceState by JarvisGuidanceEngine.guidanceState.collectAsState()

    val screenBitmap = ScreenShareManager.latestFrameBitmap
    val isSharing = ScreenShareManager.isScreenSharingActive

    val theme = JarvisThemePreset.NEON_CYAN

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070B12))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top HUD Control Bar & Level Selector
            WorkspaceTopHeader(
                currentLevel = currentLevel,
                onSelectLevel = { newLevel ->
                    currentLevel = newLevel
                    AppPreferences.setScreenPresentationLevel(context, newLevel.id)
                },
                onClose = onClose,
                theme = theme
            )

            // Presentation Stage
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (currentLevel) {
                    ScreenSharePresentationLevel.BASIC -> {
                        BasicOverlayPresentation(
                            screenBitmap = screenBitmap,
                            guidanceState = guidanceState,
                            activeExplanation = activeExplanation,
                            theme = theme,
                            onExplainScreen = {
                                JarvisGuidanceEngine.explainActiveScreenAsync(context, "Explain this screen in detail")
                            }
                        )
                    }
                    ScreenSharePresentationLevel.STANDARD -> {
                        StandardStudioPresentation(
                            screenBitmap = screenBitmap,
                            activeExplanation = activeExplanation,
                            guidanceState = guidanceState,
                            theme = theme,
                            viewModel = viewModel
                        )
                    }
                    ScreenSharePresentationLevel.ADVANCED -> {
                        AdvancedWorkspacePresentation(
                            screenBitmap = screenBitmap,
                            shareState = shareState,
                            screenContext = screenContext,
                            activeExplanation = activeExplanation,
                            guidanceState = guidanceState,
                            theme = theme,
                            viewModel = viewModel
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceTopHeader(
    currentLevel: ScreenSharePresentationLevel,
    onSelectLevel: (ScreenSharePresentationLevel) -> Unit,
    onClose: () -> Unit,
    theme: JarvisThemePreset
) {
    Surface(
        color = Color(0xF00A1118),
        border = androidx.compose.foundation.BorderStroke(1.dp, theme.primaryColor.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(theme.primaryColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "SCREEN SHARE • VISUAL INTELLIGENCE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                            fontFamily = FontFamily.Monospace,
                            color = theme.primaryColor
                        )
                    )
                }

                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Workspace",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Level Selector Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ScreenSharePresentationLevel.entries.forEach { level ->
                    val isSelected = currentLevel == level
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .then(
                                if (isSelected)
                                    Modifier.background(Brush.linearGradient(listOf(theme.primaryColor.copy(alpha = 0.85f), theme.secondaryColor.copy(alpha = 0.85f))))
                                else
                                    Modifier
                            )
                            .clickable { onSelectLevel(level) }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = level.title,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Color.Black else Color.White.copy(alpha = 0.7f),
                                fontSize = 11.sp
                            )
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// LEVEL 1: BASIC (DIRECT ON-SCREEN PASS-THROUGH OVERLAY)
// -------------------------------------------------------------
@Composable
private fun BasicOverlayPresentation(
    screenBitmap: Bitmap?,
    guidanceState: JarvisGuidanceState,
    activeExplanation: VisualExplanationCard?,
    theme: JarvisThemePreset,
    onExplainScreen: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("presentation_basic_overlay")
    ) {
        // Full size portrait preview simulating live pass-through screen
        if (screenBitmap != null) {
            Image(
                bitmap = screenBitmap.asImageBitmap(),
                contentDescription = "Screen preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            // Neon mesh wireframe placeholder
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = null,
                        tint = theme.primaryColor.copy(alpha = 0.6f),
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "LIVE PASS-THROUGH ACTIVE",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = theme.primaryColor,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Direct on-screen annotations and bounding reticles enabled",
                        style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.6f))
                    )
                }
            }
        }

        // Live Reticle & Bounding Box Overlay
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 2.5f)
            guidanceState.targetBounds?.let { r ->
                drawRect(
                    color = theme.primaryColor,
                    topLeft = Offset(r.left.toFloat(), r.top.toFloat()),
                    size = Size(r.width().toFloat(), r.height().toFloat()),
                    style = stroke
                )
            }
        }

        // Bottom Banner with Quick Explain Action & Guidance
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            if (guidanceState.isActive) {
                Surface(
                    color = theme.surfaceColor.copy(alpha = 0.95f),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, theme.primaryColor),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(theme.primaryColor.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.TouchApp, contentDescription = null, tint = theme.primaryColor)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "SHOWS YOU HOW • STEP ${guidanceState.stepIndex}/${guidanceState.totalSteps}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = theme.primaryColor,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            )
                            Text(
                                text = guidanceState.instructionText.ifBlank { "Tap on ${guidanceState.targetLabel}" },
                                style = MaterialTheme.typography.bodySmall.copy(color = Color.White)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Button(
                onClick = onExplainScreen,
                colors = ButtonDefaults.buttonColors(containerColor = theme.primaryColor),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(Icons.Default.Psychology, contentDescription = null, tint = Color.Black)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Explain Screen (Basic Overlay Mode)",
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
        }
    }
}

// -------------------------------------------------------------
// LEVEL 2: STANDARD STUDIO (CENTERED PREVIEW + CONTEXTUAL CARDS + 3D)
// -------------------------------------------------------------
@Composable
private fun StandardStudioPresentation(
    screenBitmap: Bitmap?,
    activeExplanation: VisualExplanationCard?,
    guidanceState: JarvisGuidanceState,
    theme: JarvisThemePreset,
    viewModel: KavyaViewModel
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
            .testTag("presentation_standard_studio"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Centered Upright Portrait Screen Preview
        Box(
            modifier = Modifier
                .width(220.dp)
                .height(380.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(1.5.dp, theme.primaryColor.copy(alpha = 0.7f), RoundedCornerShape(18.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (screenBitmap != null) {
                Image(
                    bitmap = screenBitmap.asImageBitmap(),
                    contentDescription = "Standard preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.MobileScreenShare,
                        contentDescription = null,
                        tint = theme.primaryColor.copy(alpha = 0.5f),
                        modifier = Modifier.size(42.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "9:19 Portrait Preview",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Color.White.copy(alpha = 0.6f),
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }

            // Target Pointer Indicator
            if (guidanceState.isActive) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(theme.primaryColor.copy(alpha = 0.85f))
                        .align(Alignment.Center),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.TouchApp,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Contextual Explanation Card with Voice Narration
        Surface(
            color = theme.surfaceColor,
            shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.2.dp, theme.primaryColor.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = activeExplanation?.title ?: "Visual Screen Understanding",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = theme.primaryColor
                        )
                    )

                    IconButton(
                        onClick = {
                            val narration = activeExplanation?.detailParagraph ?: activeExplanation?.oneLineSummary
                            if (!narration.isNullOrBlank()) {
                                viewModel.speakMessage(narration)
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Narrate with Voice Studio",
                            tint = theme.primaryColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = activeExplanation?.oneLineSummary
                        ?: "Observing active screen elements and dynamic layout semantics.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = Color.White, lineHeight = 20.sp)
                )

                if (!activeExplanation?.bulletChips.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        activeExplanation?.bulletChips?.forEach { chip ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(theme.primaryColor)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = chip,
                                    style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.85f))
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 3D Quantum Diagram Card
        Jarvis3DViewerCardView(
            card = VisualCardData(
                title = "Holographic Structural Diagram",
                summary = "Spatial UI topology and layer hierarchy projection",
                mesh3d = WireframeMesh3D.createDiamond(1.3f),
                confidence = 0.98f
            ),
            theme = theme,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// -------------------------------------------------------------
// LEVEL 3: ADVANCED (SPLIT WORKSPACE, TELEMETRY, FLOWCHART & 3D HUD)
// -------------------------------------------------------------
@Composable
private fun AdvancedWorkspacePresentation(
    screenBitmap: Bitmap?,
    shareState: ScreenShareManager.ScreenShareState,
    screenContext: String?,
    activeExplanation: VisualExplanationCard?,
    guidanceState: JarvisGuidanceState,
    theme: JarvisThemePreset,
    viewModel: KavyaViewModel
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(14.dp)
            .testTag("presentation_advanced_workspace"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Horizontal Split Workspace (Upright preview on Left, Telemetry & HUD on Right)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Left Pane: Upright Portrait Screen Preview
            Box(
                modifier = Modifier
                    .weight(0.48f)
                    .height(310.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.2.dp, theme.primaryColor, RoundedCornerShape(16.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (screenBitmap != null) {
                    Image(
                        bitmap = screenBitmap.asImageBitmap(),
                        contentDescription = "Advanced stream",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = null,
                        tint = theme.primaryColor.copy(alpha = 0.4f),
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Live Stream Badge
                Surface(
                    color = Color.Black.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Text(
                        text = "LIVE 60FPS",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = theme.primaryColor,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Right Pane: Telemetry and Real-Time Perception Metrics
            Column(
                modifier = Modifier
                    .weight(0.52f)
                    .height(310.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Telemetry Card
                Surface(
                    color = theme.surfaceColor,
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, theme.primaryColor.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "STREAM TELEMETRY",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = theme.primaryColor,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        when (val s = shareState) {
                            is ScreenShareManager.ScreenShareState.Active -> {
                                Text("Res: ${s.width}x${s.height}", style = MaterialTheme.typography.bodySmall.copy(color = Color.White, fontFamily = FontFamily.Monospace))
                                Text("FPS: ${s.fps}", style = MaterialTheme.typography.bodySmall.copy(color = Color.White, fontFamily = FontFamily.Monospace))
                                Text("Frames: ${s.framesCaptured}", style = MaterialTheme.typography.bodySmall.copy(color = Color.White, fontFamily = FontFamily.Monospace))
                            }
                            else -> {
                                Text("Status: Standby", style = MaterialTheme.typography.bodySmall.copy(color = Color.White, fontFamily = FontFamily.Monospace))
                                Text("Frames: 0", style = MaterialTheme.typography.bodySmall.copy(color = Color.White, fontFamily = FontFamily.Monospace))
                            }
                        }
                    }
                }

                // Voice Studio Narration Trigger Card
                Surface(
                    color = theme.surfaceColor,
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, theme.secondaryColor.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "VOICE STUDIO NARRATION",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = theme.secondaryColor,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Button(
                            onClick = {
                                val text = activeExplanation?.detailParagraph ?: screenContext ?: "Screen is active"
                                viewModel.speakMessage(text)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = theme.secondaryColor),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Narrate Screen", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Visual Architecture Flowchart
        Surface(
            color = theme.surfaceColor,
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.2.dp, theme.primaryColor.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "VISUAL INTELLIGENCE PIPELINE FLOWCHART",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = theme.primaryColor,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                )
                Spacer(modifier = Modifier.height(10.dp))

                val stages = listOf(
                    "VirtualDisplay Capture" to true,
                    "Frame Segmentation" to true,
                    "OCR & Accessibility Trees" to true,
                    "Vision Reasoning" to true,
                    "Voice Studio Narration" to true
                )

                stages.forEachIndexed { index, (label, active) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(if (active) theme.primaryColor else Color.Gray),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = Color.Black,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = if (active) Color.White else Color.White.copy(alpha = 0.5f),
                                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = theme.primaryColor,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        // Interactive 3D Wireframe Mesh Card (Quantum Prism)
        Jarvis3DViewerCardView(
            card = VisualCardData(
                title = "Spatial Interface Topology (Level 3)",
                summary = "Full-dimension projection of interactive surfaces and semantic hierarchy",
                mesh3d = WireframeMesh3D.createCube(1.2f),
                confidence = 0.99f
            ),
            theme = theme,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
