package com.example.visual

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen "Explain the Whole Screen" JARVIS Experience.
 * Features a centralized live screen preview flanked by contextual explanation cards
 * linked by real-time animated Bezier connector lines.
 */
@Composable
fun JarvisFullScreenExplainer(
    plan: JarvisVisualPlan,
    screenPreviewBitmap: Bitmap?,
    theme: JarvisThemePreset,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    onPauseToggle: (Boolean) -> Unit = {}
) {
    var isPaused by remember { mutableStateOf(false) }
    var selectedCardIndex by remember { mutableIntStateOf(0) }
    var isMinimized by remember { mutableStateOf(false) }

    // Screen preview layout coordinates
    var previewOffset by remember { mutableStateOf(Offset.Zero) }
    var previewSize by remember { mutableStateOf(Offset.Zero) }

    // Infinite animation for connector energy pulse
    val infiniteTransition = rememberInfiniteTransition(label = "ExplainerPulse")
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "EnergyPulse"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f))
    ) {
        if (!isMinimized) {
            // Main Stage: Center Screen Preview + Surrounding Cards
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Header Control Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(theme.primaryColor)
                        )
                        Text(
                            text = "JARVIS SCREEN INTELLIGENCE • ${plan.title.uppercase()}",
                            color = theme.primaryColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                    }

                    // Floating Controls
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(
                            onClick = {
                                isPaused = !isPaused
                                onPauseToggle(isPaused)
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (isPaused) "Resume" else "Pause",
                                tint = theme.primaryColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = { isMinimized = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Remove,
                                contentDescription = "Minimize",
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Middle area: Center Preview surrounded by cards
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Left Column Cards (if multiple)
                    val leftCards = plan.cards.filterIndexed { idx, _ -> idx % 2 == 0 }
                    if (leftCards.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .weight(0.48f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            leftCards.forEach { card ->
                                JarvisExplanationCardDispatcher(
                                    card = card,
                                    theme = theme,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    // Center Screen Preview Box
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(0.52f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF10141D))
                            .border(1.5.dp, theme.primaryColor.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                            .onGloballyPositioned { coordinates ->
                                previewOffset = coordinates.positionInRoot()
                                previewSize = Offset(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
                            }
                    ) {
                        if (screenPreviewBitmap != null) {
                            Image(
                                bitmap = screenPreviewBitmap.asImageBitmap(),
                                contentDescription = "Active Screen Snapshot",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            // Holographic Wireframe Grid representation if screenshot is unavailable
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val w = size.width
                                val h = size.height
                                val gridStep = 40f
                                for (x in 0..(w / gridStep).toInt()) {
                                    drawLine(
                                        color = theme.primaryColor.copy(alpha = 0.12f),
                                        start = Offset(x * gridStep, 0f),
                                        end = Offset(x * gridStep, h),
                                        strokeWidth = 1f
                                    )
                                }
                                for (y in 0..(h / gridStep).toInt()) {
                                    drawLine(
                                        color = theme.primaryColor.copy(alpha = 0.12f),
                                        start = Offset(0f, y * gridStep),
                                        end = Offset(w, y * gridStep),
                                        strokeWidth = 1f
                                    )
                                }
                            }
                            Text(
                                text = "SCREEN MATRIX ACTIVE",
                                color = theme.primaryColor.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        // Overlay high-tech scanline sweep over the preview
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val scanY = size.height * pulseProgress
                            drawLine(
                                brush = Brush.horizontalGradient(
                                    listOf(Color.Transparent, theme.primaryColor, Color.White, theme.primaryColor, Color.Transparent)
                                ),
                                start = Offset(0f, scanY),
                                end = Offset(size.width, scanY),
                                strokeWidth = 2f
                            )
                        }
                    }

                    // Right Column Cards
                    val rightCards = plan.cards.filterIndexed { idx, _ -> idx % 2 == 1 }
                    if (rightCards.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .weight(0.48f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            rightCards.forEach { card ->
                                JarvisExplanationCardDispatcher(
                                    card = card,
                                    theme = theme,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Bottom Status Footer
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = 0.05f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "STATUS: ${if (isPaused) "PAUSED" else "STREAMING INTELLIGENCE"}",
                            color = if (isPaused) Color(0xFFFFB300) else theme.primaryColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "${plan.cards.size} EXPLANATION NODES GENERATED",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        } else {
            // Minimized Floating Pill
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = theme.surfaceColor,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .border(1.2.dp, theme.primaryColor, RoundedCornerShape(24.dp))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(theme.primaryColor)
                    )
                    Text(
                        text = "JARVIS ACTIVE",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    IconButton(
                        onClick = { isMinimized = false },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInFull,
                            contentDescription = "Expand",
                            tint = theme.primaryColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
