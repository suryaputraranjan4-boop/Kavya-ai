package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.services.ScreenShareManager
import com.example.utils.AppPreferences
import com.example.viewmodel.KavyaViewModel
import com.example.visual.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure coordinate transformer mapping physical screen/bitmap rects into Compose preview
 * canvas coordinates for ContentScale.Fit (Basic) and ContentScale.Crop (Standard/Advanced).
 */
object ScreenPreviewCoordinateScaler {
    data class MappedBounds(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float
    ) {
        val centerX: Float get() = left + width / 2f
        val centerY: Float get() = top + height / 2f
    }

    fun mapRect(
        sourceRect: Rect,
        sourceWidth: Int,
        sourceHeight: Int,
        canvasWidth: Float,
        canvasHeight: Float,
        isCrop: Boolean
    ): MappedBounds? {
        if (sourceWidth <= 0 || sourceHeight <= 0 || canvasWidth <= 0f || canvasHeight <= 0f) return null
        val sw = sourceWidth.toFloat()
        val sh = sourceHeight.toFloat()
        val scale = if (isCrop) {
            max(canvasWidth / sw, canvasHeight / sh)
        } else {
            min(canvasWidth / sw, canvasHeight / sh)
        }
        val renderedW = sw * scale
        val renderedH = sh * scale
        val offsetX = (canvasWidth - renderedW) / 2f
        val offsetY = (canvasHeight - renderedH) / 2f

        val rawLeft = offsetX + sourceRect.left * scale
        val rawTop = offsetY + sourceRect.top * scale
        val rawRight = offsetX + sourceRect.right * scale
        val rawBottom = offsetY + sourceRect.bottom * scale

        val clampedLeft = rawLeft.coerceIn(0f, canvasWidth)
        val clampedTop = rawTop.coerceIn(0f, canvasHeight)
        val clampedRight = rawRight.coerceIn(clampedLeft, canvasWidth)
        val clampedBottom = rawBottom.coerceIn(clampedTop, canvasHeight)
        return MappedBounds(
            left = clampedLeft,
            top = clampedTop,
            width = (clampedRight - clampedLeft).coerceAtLeast(8f),
            height = (clampedBottom - clampedTop).coerceAtLeast(8f)
        )
    }
}

private data class NormalizedSelectionBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val isValid: Boolean
        get() = abs(right - left) >= 0.05f && abs(bottom - top) >= 0.05f
}

/**
 * Screen Share Presentation Workspace implementing the 3 Presentation Levels + Advanced Tools:
 * 1. BASIC: Direct on-screen annotations, highlight boxes, pointers, touch-pass-through overlay.
 * 2. STANDARD: Upright preview with Interactive Circle/Region Select, Browser Quick Actions, Contextual Cards & Snapshot Vault.
 * 3. ADVANCED: Split workspace with Live Chrome/Health Telemetry HUD, Pipeline Flowchart, 3D Mesh & Snapshot Vault.
 */
@Composable
fun ScreenSharePresentationWorkspace(
    viewModel: KavyaViewModel,
    initialLevel: ScreenSharePresentationLevel = ScreenSharePresentationLevel.STANDARD,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onClose()
    }

    val context = LocalContext.current
    var currentLevel by remember(initialLevel) {
        mutableStateOf(initialLevel)
    }

    val shareState by viewModel.screenShareState.collectAsState()
    val telemetry by ScreenShareManager.telemetry.collectAsState()
    val snapshotVault by ScreenShareManager.snapshotVault.collectAsState()
    val screenContext by viewModel.screenContextText.collectAsState()
    val activeExplanation by JarvisGuidanceEngine.activeExplanation.collectAsState()
    val guidanceState by JarvisGuidanceEngine.guidanceState.collectAsState()

    val screenBitmap by ScreenShareManager.latestFrame.collectAsState()
    val perfSnapshot by PerformanceProfileManager.snapshot.collectAsState()
    val isSharing = shareState is ScreenShareManager.ScreenShareState.Active ||
        shareState is ScreenShareManager.ScreenShareState.StaleFrame ||
        shareState is ScreenShareManager.ScreenShareState.Paused

    LaunchedEffect(Unit) {
        PerformanceProfileManager.loadAndRefresh(context)
    }

    val theme = JarvisThemePreset.NEON_CYAN

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070B12))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top HUD Control Bar, Chrome Health Pill, Privacy Pause & Level Selector
            WorkspaceTopHeader(
                currentLevel = currentLevel,
                shareState = shareState,
                telemetry = telemetry,
                isSharing = isSharing,
                onTogglePrivacyPause = { ScreenShareManager.toggleUserPause() },
                onSelectLevel = { newLevel ->
                    currentLevel = newLevel
                    AppPreferences.setScreenPresentationLevel(context, newLevel.id)
                },
                onClose = onClose,
                theme = theme
            )

            // Diagnostic Alert Strip (Single-app fallback or Incognito FLAG_SECURE notice)
            if (telemetry.recoveryHint.isNotBlank()) {
                Surface(
                    color = if (telemetry.isSystemVisibilityPaused) Color(0x33FF5252) else Color(0x28FFB300),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (telemetry.isSystemVisibilityPaused) Color(0x88FF5252) else Color(0x66FFB300)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (telemetry.isProtectedSurface) Icons.Default.Shield else Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = if (telemetry.isSystemVisibilityPaused) Color(0xFFFF8A80) else Color(0xFFFFCA28),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = telemetry.recoveryHint,
                            style = MaterialTheme.typography.bodySmall.copy(color = Color.White, fontSize = 11.sp),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

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
                            telemetry = telemetry,
                            snapshotVault = snapshotVault,
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
                            telemetry = telemetry,
                            snapshotVault = snapshotVault,
                            perfSnapshot = perfSnapshot,
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
    shareState: ScreenShareManager.ScreenShareState,
    telemetry: ScreenShareManager.ScreenCaptureTelemetry,
    isSharing: Boolean,
    onTogglePrivacyPause: () -> Unit,
    onSelectLevel: (ScreenSharePresentationLevel) -> Unit,
    onClose: () -> Unit,
    theme: JarvisThemePreset
) {
    val healthLabel = when {
        telemetry.isUserPaused -> "PRIVACY SHIELD ON"
        telemetry.isSystemVisibilityPaused -> "SELECT ENTIRE SCREEN"
        telemetry.isProtectedSurface -> "PROTECTED SURFACE"
        shareState is ScreenShareManager.ScreenShareState.Active && telemetry.isStaticScreen -> "STATIC PAGE READY"
        shareState is ScreenShareManager.ScreenShareState.Active -> "LIVE • ${telemetry.fps} FPS"
        else -> "STANDBY"
    }
    val healthColor = when {
        telemetry.isUserPaused -> Color(0xFFFFCA28)
        telemetry.isSystemVisibilityPaused -> Color(0xFFFF6E6E)
        telemetry.isProtectedSurface -> Color(0xFFCE93D8)
        shareState is ScreenShareManager.ScreenShareState.Active -> theme.primaryColor
        else -> Color.White.copy(alpha = 0.65f)
    }

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
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(healthColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "SCREEN SHARE • $healthLabel",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                fontFamily = FontFamily.Monospace,
                                color = healthColor
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (telemetry.activeBrowserUrl.isNotBlank() || telemetry.foregroundPackage.isNotBlank()) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (telemetry.activeBrowserUrl.isNotBlank()) Icons.Default.Language else Icons.Default.Apps,
                                contentDescription = null,
                                tint = theme.primaryColor.copy(alpha = 0.8f),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = telemetry.activeBrowserUrl.ifBlank { telemetry.foregroundPackage },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.White.copy(alpha = 0.72f),
                                    fontSize = 10.sp
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSharing) {
                        IconButton(
                            onClick = onTogglePrivacyPause,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("workspace_privacy_pause_button")
                        ) {
                            Icon(
                                imageVector = if (telemetry.isUserPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (telemetry.isUserPaused) "Resume Screen Share" else "Privacy Pause Screen Share",
                                tint = if (telemetry.isUserPaused) Color(0xFFFFCA28) else theme.primaryColor,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Workspace",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

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
                            .heightIn(min = 48.dp)
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
        if (screenBitmap != null) {
            Image(
                bitmap = screenBitmap.asImageBitmap(),
                contentDescription = "Screen preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
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

        val geometry = remember { ScreenAnnotationManager.coordinateMapper.getGeometry() }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 4f)
            guidanceState.targetBounds?.let { r ->
                val srcW = screenBitmap?.width ?: geometry.width
                val srcH = screenBitmap?.height ?: geometry.height
                val mapped = ScreenPreviewCoordinateScaler.mapRect(
                    sourceRect = r,
                    sourceWidth = srcW,
                    sourceHeight = srcH,
                    canvasWidth = size.width,
                    canvasHeight = size.height,
                    isCrop = false
                )
                if (mapped != null) {
                    drawRoundRect(
                        color = theme.primaryColor,
                        topLeft = Offset(mapped.left, mapped.top),
                        size = Size(mapped.width, mapped.height),
                        cornerRadius = CornerRadius(12f, 12f),
                        style = stroke
                    )
                    drawCircle(
                        color = theme.primaryColor.copy(alpha = 0.25f),
                        radius = 18f,
                        center = Offset(mapped.centerX, mapped.centerY)
                    )
                    drawCircle(
                        color = theme.primaryColor,
                        radius = 6f,
                        center = Offset(mapped.centerX, mapped.centerY)
                    )
                }
            }
        }

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
// LEVEL 2: STANDARD STUDIO (PREVIEW + CIRCLE/REGION SELECT + QUICK ACTIONS + VAULT)
// -------------------------------------------------------------
@Composable
private fun StandardStudioPresentation(
    screenBitmap: Bitmap?,
    telemetry: ScreenShareManager.ScreenCaptureTelemetry,
    snapshotVault: List<ScreenShareManager.ScreenInsightSnapshot>,
    activeExplanation: VisualExplanationCard?,
    guidanceState: JarvisGuidanceState,
    theme: JarvisThemePreset,
    viewModel: KavyaViewModel
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var regionSelectEnabled by remember { mutableStateOf(true) }
    var selectionBox by remember { mutableStateOf<NormalizedSelectionBox?>(null) }
    var previewSize by remember { mutableStateOf(IntSize(1, 1)) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
            .testTag("presentation_standard_studio"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Region Select Mode Toggle Banner
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CropFree,
                    contentDescription = null,
                    tint = theme.primaryColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (regionSelectEnabled) "Drag on preview to Circle/Explain Area" else "Preview locked for scrolling",
                    style = MaterialTheme.typography.labelSmall.copy(color = Color.White.copy(alpha = 0.78f))
                )
            }
            TextButton(
                onClick = { regionSelectEnabled = !regionSelectEnabled },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(
                    text = if (regionSelectEnabled) "Lock Drag" else "Select Area",
                    color = theme.primaryColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Centered Upright Portrait Screen Preview with Interactive Region Drag Overlay
        Box(
            modifier = Modifier
                .width(235.dp)
                .height(385.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(1.5.dp, theme.primaryColor.copy(alpha = 0.7f), RoundedCornerShape(18.dp))
                .background(Color.Black)
                .onSizeChanged { previewSize = it }
                .then(
                    if (regionSelectEnabled) {
                        Modifier.pointerInput(screenBitmap) {
                            detectDragGestures(
                                onDragStart = { startOffset ->
                                    dragStart = startOffset
                                    val w = size.width.coerceAtLeast(1).toFloat()
                                    val h = size.height.coerceAtLeast(1).toFloat()
                                    val nx = (startOffset.x / w).coerceIn(0f, 1f)
                                    val ny = (startOffset.y / h).coerceIn(0f, 1f)
                                    selectionBox = NormalizedSelectionBox(nx, ny, nx, ny)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val start = dragStart ?: return@detectDragGestures
                                    val w = size.width.coerceAtLeast(1).toFloat()
                                    val h = size.height.coerceAtLeast(1).toFloat()
                                    val x1 = (minOf(start.x, change.position.x) / w).coerceIn(0f, 1f)
                                    val y1 = (minOf(start.y, change.position.y) / h).coerceIn(0f, 1f)
                                    val x2 = (maxOf(start.x, change.position.x) / w).coerceIn(0f, 1f)
                                    val y2 = (maxOf(start.y, change.position.y) / h).coerceIn(0f, 1f)
                                    selectionBox = NormalizedSelectionBox(x1, y1, x2, y2)
                                }
                            )
                        }
                    } else {
                        Modifier
                    }
                ),
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
                        text = if (telemetry.isUserPaused) "Privacy Shield Paused" else "9:19 Portrait Preview",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Color.White.copy(alpha = 0.6f),
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }

            // Scaled Target Box & Pointer Indicator + User Circle/Region Box
            val previewGeometry = remember { ScreenAnnotationManager.coordinateMapper.getGeometry() }
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (guidanceState.isActive && guidanceState.targetBounds != null) {
                    val srcW = screenBitmap?.width ?: previewGeometry.width
                    val srcH = screenBitmap?.height ?: previewGeometry.height
                    val mapped = ScreenPreviewCoordinateScaler.mapRect(
                        sourceRect = guidanceState.targetBounds!!,
                        sourceWidth = srcW,
                        sourceHeight = srcH,
                        canvasWidth = size.width,
                        canvasHeight = size.height,
                        isCrop = true
                    )
                    if (mapped != null) {
                        drawRoundRect(
                            color = theme.primaryColor,
                            topLeft = Offset(mapped.left, mapped.top),
                            size = Size(mapped.width, mapped.height),
                            cornerRadius = CornerRadius(10f, 10f),
                            style = Stroke(width = 3.5f)
                        )
                        drawCircle(
                            color = theme.primaryColor.copy(alpha = 0.85f),
                            radius = 14f,
                            center = Offset(mapped.centerX, mapped.centerY)
                        )
                        drawCircle(
                            color = Color.Black,
                            radius = 5f,
                            center = Offset(mapped.centerX, mapped.centerY)
                        )
                    }
                }

                selectionBox?.takeIf { it.isValid }?.let { box ->
                    val rectLeft = box.left * size.width
                    val rectTop = box.top * size.height
                    val rectW = (box.right - box.left) * size.width
                    val rectH = (box.bottom - box.top) * size.height
                    drawRoundRect(
                        color = theme.primaryColor.copy(alpha = 0.20f),
                        topLeft = Offset(rectLeft, rectTop),
                        size = Size(rectW, rectH),
                        cornerRadius = CornerRadius(12f, 12f)
                    )
                    drawRoundRect(
                        color = theme.primaryColor,
                        topLeft = Offset(rectLeft, rectTop),
                        size = Size(rectW, rectH),
                        cornerRadius = CornerRadius(12f, 12f),
                        style = Stroke(width = 4f)
                    )
                }
            }
        }

        // Floating Action Pills when user draws a region selection
        selectionBox?.takeIf { it.isValid }?.let { box ->
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        JarvisGuidanceEngine.explainRegionAsync(
                            context = context,
                            leftNorm = box.left,
                            topNorm = box.top,
                            rightNorm = box.right,
                            bottomNorm = box.bottom,
                            query = "Explain the selected region clearly."
                        )
                    },
                    modifier = Modifier.weight(1f).height(48.dp).testTag("explain_selected_region_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = theme.primaryColor)
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Explain Area", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = {
                        JarvisGuidanceEngine.explainRegionAsync(
                            context = context,
                            leftNorm = box.left,
                            topNorm = box.top,
                            rightNorm = box.right,
                            bottomNorm = box.bottom,
                            query = "Translate and explain the text inside this highlighted area into natural Hinglish."
                        )
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Translate Area", color = theme.primaryColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                IconButton(
                    onClick = { selectionBox = null },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear selection", tint = Color.White.copy(alpha = 0.8f))
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Browser & Visual Quick AI Actions
        BrowserQuickActionsBar(theme = theme)

        Spacer(modifier = Modifier.height(14.dp))

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
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(
                        onClick = {
                            val narration = activeExplanation?.detailParagraph ?: activeExplanation?.oneLineSummary
                            if (!narration.isNullOrBlank()) {
                                viewModel.speakMessage(narration)
                            }
                        },
                        modifier = Modifier.size(48.dp)
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

        if (snapshotVault.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            SnapshotVaultSection(
                snapshots = snapshotVault,
                theme = theme,
                onSpeakSnapshot = { text -> viewModel.speakMessage(text) }
            )
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
// LEVEL 3: ADVANCED (SPLIT WORKSPACE, TELEMETRY, FLOWCHART, VAULT & 3D HUD)
// -------------------------------------------------------------
@Composable
private fun AdvancedWorkspacePresentation(
    screenBitmap: Bitmap?,
    shareState: ScreenShareManager.ScreenShareState,
    telemetry: ScreenShareManager.ScreenCaptureTelemetry,
    snapshotVault: List<ScreenShareManager.ScreenInsightSnapshot>,
    perfSnapshot: PerformanceSnapshot,
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

                val streamBadgeText = when (val s = shareState) {
                    is ScreenShareManager.ScreenShareState.Active -> when {
                        s.isProtectedSurface -> "SHIELDED • ${perfSnapshot.effectiveTier.shortLabel}"
                        s.isStaticScreen -> "STATIC • ${perfSnapshot.effectiveTier.shortLabel}"
                        else -> "LIVE • ${s.fps} FPS • ${perfSnapshot.effectiveTier.shortLabel}"
                    }
                    is ScreenShareManager.ScreenShareState.Paused ->
                        if (telemetry.isUserPaused) "PAUSED • PRIVACY" else "ENTIRE SCREEN?"
                    is ScreenShareManager.ScreenShareState.StaleFrame ->
                        "PAUSED • ${perfSnapshot.effectiveTier.shortLabel}"
                    else ->
                        "STANDBY • ${perfSnapshot.effectiveTier.shortLabel}"
                }
                Surface(
                    color = Color.Black.copy(alpha = 0.78f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Text(
                        text = streamBadgeText,
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
                                Text("Res: ${s.width}x${s.height}", style = MaterialTheme.typography.bodySmall.copy(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp))
                                Text(
                                    if (s.isStaticScreen) "FPS: 0 (Static Screen)" else "FPS: ${s.fps}",
                                    style = MaterialTheme.typography.bodySmall.copy(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                )
                                Text("Frames: ${s.framesCaptured}", style = MaterialTheme.typography.bodySmall.copy(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp))
                            }
                            is ScreenShareManager.ScreenShareState.Paused -> {
                                Text(
                                    if (telemetry.isUserPaused) "Status: Privacy Paused" else "Status: Hidden Window",
                                    style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFFFCA28), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                )
                                Text("Frames: ${telemetry.framesCaptured}", style = MaterialTheme.typography.bodySmall.copy(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp))
                            }
                            else -> {
                                Text("Status: Standby", style = MaterialTheme.typography.bodySmall.copy(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp))
                                Text("Frames: 0", style = MaterialTheme.typography.bodySmall.copy(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp))
                            }
                        }
                        Text(
                            text = "Tier: ${perfSnapshot.effectiveTier.shortLabel} (${perfSnapshot.selectedMode.title})",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = theme.primaryColor.copy(alpha = 0.9f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                        )
                        Text(
                            text = "Latency: ${perfSnapshot.avgFrameLatencyMs.toInt()}ms • Skip: ${perfSnapshot.skippedUnchangedFrames}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = Color.White.copy(alpha = 0.78f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                        )
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
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Narrate Screen", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Browser & Visual Quick AI Actions
        BrowserQuickActionsBar(theme = theme)

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
                    "Entire-Screen MediaProjection" to true,
                    "Chrome & Surface Resize Guard" to true,
                    "OCR & Accessibility URL Context" to true,
                    "Vision & Region Select AI" to true,
                    "Voice Studio & Snapshot Vault" to true
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

        if (snapshotVault.isNotEmpty()) {
            SnapshotVaultSection(
                snapshots = snapshotVault,
                theme = theme,
                onSpeakSnapshot = { text -> viewModel.speakMessage(text) }
            )
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

@Composable
private fun BrowserQuickActionsBar(theme: JarvisThemePreset) {
    val context = LocalContext.current
    Surface(
        color = theme.surfaceColor,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, theme.primaryColor.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "CHROME & WEB QUICK ACTIONS",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = theme.primaryColor,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        JarvisGuidanceEngine.analyzeBrowserPageAsync(
                            context = context,
                            actionTitle = "Page Summary",
                            prompt = "Summarize this webpage clearly in 3 concise bullet points."
                        )
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("quick_summarize_page_button"),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) {
                    Text("Summarize", fontSize = 11.sp, color = Color.White, maxLines = 1)
                }

                OutlinedButton(
                    onClick = {
                        JarvisGuidanceEngine.analyzeBrowserPageAsync(
                            context = context,
                            actionTitle = "Translate (HI/EN)",
                            prompt = "Translate the visible Chrome/screen content into clear simple Hinglish."
                        )
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("quick_translate_page_button"),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) {
                    Text("Translate HI", fontSize = 11.sp, color = theme.primaryColor, maxLines = 1)
                }

                OutlinedButton(
                    onClick = {
                        JarvisGuidanceEngine.analyzeBrowserPageAsync(
                            context = context,
                            actionTitle = "Key Takeaways",
                            prompt = "Extract key facts, action items, links, or prices from this screen."
                        )
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("quick_takeaways_button"),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) {
                    Text("Key Points", fontSize = 11.sp, color = theme.secondaryColor, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SnapshotVaultSection(
    snapshots: List<ScreenShareManager.ScreenInsightSnapshot>,
    theme: JarvisThemePreset,
    onSpeakSnapshot: (String) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Surface(
        color = theme.surfaceColor,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, theme.primaryColor.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("snapshot_vault_section")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SNAPSHOT VAULT (${snapshots.size})",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = theme.primaryColor,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                )
                TextButton(
                    onClick = { ScreenShareManager.clearSnapshotVault() },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Clear", fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                snapshots.forEach { item ->
                    Surface(
                        color = Color.Black.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${item.title} • ${timeFormatter.format(Date(item.timestampMs))}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = theme.primaryColor,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row {
                                    IconButton(
                                        onClick = { clipboard.setText(AnnotatedString(item.summary)) },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "Copy Insight",
                                            tint = Color.White.copy(alpha = 0.75f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { onSpeakSnapshot(item.summary) },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                            contentDescription = "Speak Insight",
                                            tint = theme.primaryColor,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                            if (item.urlOrContext.isNotBlank()) {
                                Text(
                                    text = item.urlOrContext,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = Color.White.copy(alpha = 0.55f),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                            }
                            Text(
                                text = item.summary,
                                style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.9f)),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
