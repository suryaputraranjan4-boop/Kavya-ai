package com.example.visual

import android.graphics.Rect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Fullscreen Interactive JARVIS Overlay Canvas.
 * Combines 60fps native drawing (bounding boxes, bezier arrows, cursors)
 * with non-blocking explanation cards and step-by-step guidance.
 */
@Composable
fun JarvisOverlayCanvas(
    plan: JarvisVisualPlan?,
    orbState: JarvisOrbState,
    orbConfig: JarvisOrbConfig,
    activeInstruction: VisualInstruction?,
    theme: JarvisThemePreset,
    modifier: Modifier = Modifier,
    onDismissCard: (String) -> Unit = {},
    onDismissAll: () -> Unit = {},
    onStepCompleted: (VisualInstruction) -> Unit = {}
) {
    if (plan == null && activeInstruction == null) return

    val infiniteTransition = rememberInfiniteTransition(label = "OverlayCanvasLoop")

    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "PulseProgress"
    )

    val scanlineProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ScanlineProgress"
    )

    val cursorRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "CursorRot"
    )

    // Current target bounds and click point from active instruction or plan
    val targetBounds = activeInstruction?.bounds ?: plan?.cards?.firstOrNull()?.targetAnchorBounds
    val clickPoint = activeInstruction?.clickPoint ?: targetBounds?.let { Pair(it.centerX(), it.centerY()) }

    Box(modifier = modifier.fillMaxSize()) {
        // 1. Native Drawing Canvas for highlights, connectors, and cursors
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Draw Reticle Box around target element if present
            if (targetBounds != null) {
                JarvisDrawingEngine.drawGlowingReticleBox(
                    drawScope = this,
                    bounds = targetBounds,
                    theme = theme,
                    pulseProgress = pulseProgress,
                    scanlineProgress = scanlineProgress,
                    label = activeInstruction?.label ?: ""
                )
            }

            // Draw Reticle Circle / Cursor on Click Point
            if (clickPoint != null) {
                val cursorOffset = Offset(clickPoint.first.toFloat(), clickPoint.second.toFloat())

                JarvisDrawingEngine.drawGlowingReticleCircle(
                    drawScope = this,
                    center = cursorOffset,
                    radius = 28f,
                    theme = theme,
                    pulseProgress = pulseProgress
                )

                JarvisDrawingEngine.drawAnimatedCursor(
                    drawScope = this,
                    position = cursorOffset,
                    style = orbConfig.cursorStyle,
                    theme = theme,
                    rotationDeg = cursorRotation,
                    pulseProgress = pulseProgress
                )
            }

            // Draw Curved Bezier Connectors from Target to Visible Cards
            if (targetBounds != null && plan != null) {
                val targetCenter = Offset(targetBounds.centerX().toFloat(), targetBounds.centerY().toFloat())
                // Connect to top-center of bottom dock or card position
                val cardAnchor = Offset(targetBounds.centerX().toFloat(), (targetBounds.bottom + 40f).coerceAtMost(size.height - 240f))
                JarvisDrawingEngine.drawCurvedConnector(
                    drawScope = this,
                    start = targetCenter,
                    end = cardAnchor,
                    theme = theme,
                    flowProgress = pulseProgress
                )
            }
        }

        // 2. Mode B Interactive Guidance Banner (if in SHOWS_YOU_HOW mode)
        if (plan?.mode == JarvisMode.SHOWS_YOU_HOW && activeInstruction != null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = theme.surfaceColor.copy(alpha = 0.95f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp, start = 16.dp, end = 16.dp)
                    .fillMaxWidth()
                    .border(1.2.dp, theme.primaryColor, RoundedCornerShape(16.dp))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(theme.primaryColor)
                    ) {
                        Icon(
                            imageVector = Icons.Default.TouchApp,
                            contentDescription = "Tap Target",
                            tint = Color.Black,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "INTERACTIVE GUIDANCE • SHOWS YOU HOW",
                            color = theme.primaryColor,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = activeInstruction.text.ifBlank { "Tap highlighted element: ${activeInstruction.label}" },
                            color = Color.White,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    IconButton(
                        onClick = onDismissAll,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // 3. Dynamic Explanation Cards (Mode A: Explains Anything)
        if (plan != null && plan.cards.isNotEmpty()) {
            val activeCard = plan.cards.firstOrNull()
            if (activeCard != null) {
                // Layout card at bottom dock or calculated anchor
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 24.dp)
                ) {
                    JarvisExplanationCardDispatcher(
                        card = activeCard,
                        theme = theme,
                        modifier = Modifier.fillMaxWidth(),
                        onClose = { onDismissCard(activeCard.id) }
                    )
                }
            }
        }
    }
}
