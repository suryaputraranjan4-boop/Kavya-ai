package com.example.visual

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 9-State Animated Floating JARVIS Orb with Sound Reactivity,
 * Edge Snapping Physics, and Holographic Visual Signatures.
 */
@Composable
fun JarvisFloatingOrb(
    state: JarvisOrbState,
    config: JarvisOrbConfig,
    amplitude: Float = 0f,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
    onDoubleTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onPositionChanged: (Offset) -> Unit = {}
) {
    var orbOffset by remember { mutableStateOf(Offset(40f, 200f)) }

    val baseSize = config.sizeDp.dp
    val theme = config.theme

    // Amplitude smoothing
    val smoothAmplitude by animateFloatAsState(
        targetValue = amplitude.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "SmoothAmplitude"
    )

    // Infinite animation loops for ambient, rotation, and waves
    val infiniteTransition = rememberInfiniteTransition(label = "OrbMasterTransition")

    val ambientBreath by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween((2800 / config.speedMultiplier).roundToInt(), easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "AmbientBreath"
    )

    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                when (state) {
                    JarvisOrbState.THINKING -> (1400 / config.speedMultiplier).roundToInt()
                    JarvisOrbState.WORKING -> (2000 / config.speedMultiplier).roundToInt()
                    JarvisOrbState.EXPLAINING -> (3000 / config.speedMultiplier).roundToInt()
                    else -> (6000 / config.speedMultiplier).roundToInt()
                },
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "SpinAngle"
    )

    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween((1400 / config.speedMultiplier).roundToInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "WavePhase"
    )

    // Dynamic state colors
    val corePrimaryColor by animateColorAsState(
        targetValue = when (state) {
            JarvisOrbState.ERROR -> Color(0xFFFF1744)
            JarvisOrbState.SUCCESS -> Color(0xFF00E676)
            JarvisOrbState.GUIDING -> Color(0xFFFF9100)
            JarvisOrbState.EXPLAINING -> theme.primaryColor
            JarvisOrbState.THINKING -> theme.secondaryColor
            JarvisOrbState.WORKING -> theme.primaryColor
            JarvisOrbState.SPEAKING -> theme.primaryColor
            JarvisOrbState.LISTENING -> theme.primaryColor
            JarvisOrbState.IDLE -> theme.primaryColor
        },
        animationSpec = tween(400),
        label = "CoreColor"
    )

    val glowAlpha = when (state) {
        JarvisOrbState.LISTENING -> 0.5f + smoothAmplitude * 0.45f
        JarvisOrbState.SPEAKING -> 0.6f + smoothAmplitude * 0.4f
        JarvisOrbState.THINKING -> 0.65f
        JarvisOrbState.WORKING -> 0.55f
        JarvisOrbState.ERROR -> 0.75f
        JarvisOrbState.SUCCESS -> 0.75f
        else -> 0.35f
    } * config.glowIntensity

    val scaleMultiplier = when (state) {
        JarvisOrbState.SPEAKING -> 1.08f + smoothAmplitude * 0.15f
        JarvisOrbState.LISTENING -> 1.04f + smoothAmplitude * 0.12f
        JarvisOrbState.THINKING -> 1.06f
        JarvisOrbState.SUCCESS -> 1.15f
        JarvisOrbState.ERROR -> 1.02f
        else -> 1.0f
    } * ambientBreath

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .offset { IntOffset(orbOffset.x.roundToInt(), orbOffset.y.roundToInt()) }
            .size(baseSize + 56.dp)
            .scale(scaleMultiplier)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { onDoubleTap() },
                    onLongPress = { onLongPress() }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        orbOffset = Offset(
                            (orbOffset.x + dragAmount.x).coerceIn(0f, 1000f),
                            (orbOffset.y + dragAmount.y).coerceIn(40f, 2100f)
                        )
                        onPositionChanged(orbOffset)
                    },
                    onDragEnd = {
                        if (config.snapToEdge) {
                            // Snap to left or right margin
                            val snapX = if (orbOffset.x < 500f) 20f else 860f
                            orbOffset = Offset(snapX, orbOffset.y)
                            onPositionChanged(orbOffset)
                        }
                    }
                )
            }
    ) {
        // Outer Glowing Aura Halo
        Canvas(modifier = Modifier.size(baseSize + 48.dp)) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        corePrimaryColor.copy(alpha = glowAlpha),
                        corePrimaryColor.copy(alpha = glowAlpha * 0.35f),
                        Color.Transparent
                    )
                ),
                radius = size.width / 2f
            )
        }

        // Animated Rotating Gyroscopic Rings for THINKING / EXPLAINING / WORKING
        if (state == JarvisOrbState.THINKING || state == JarvisOrbState.EXPLAINING || state == JarvisOrbState.WORKING) {
            Canvas(modifier = Modifier.size(baseSize + 24.dp).rotate(spinAngle)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val r = (size.width / 2f) - 6f
                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(corePrimaryColor, Color.Transparent, corePrimaryColor.copy(alpha = 0.8f), Color.Transparent)
                    ),
                    radius = r,
                    center = center,
                    style = Stroke(width = 2.5f)
                )

                // High-tech satellite tick nodes
                for (i in 0..3) {
                    val angle = (i * 90f) * (PI.toFloat() / 180f)
                    val px = center.x + r * cos(angle)
                    val py = center.y + r * sin(angle)
                    drawCircle(Color.White, radius = 3f, center = Offset(px, py))
                }
            }
        }

        // Reactive Audio Undulation Rings for SPEAKING / LISTENING
        if (state == JarvisOrbState.SPEAKING || state == JarvisOrbState.LISTENING) {
            Canvas(modifier = Modifier.size(baseSize + 20.dp)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val baseRadius = (size.width / 2f) - 10f
                val ampFactor = 6f + smoothAmplitude * 14f

                val path = Path()
                val steps = 60
                for (i in 0..steps) {
                    val angle = (i.toFloat() / steps) * 2 * PI.toFloat()
                    val wave = sin(angle * 5 + wavePhase) * ampFactor
                    val r = baseRadius + wave
                    val x = center.x + r * cos(angle)
                    val y = center.y + r * sin(angle)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()

                drawPath(
                    path = path,
                    brush = Brush.sweepGradient(listOf(corePrimaryColor, theme.secondaryColor, corePrimaryColor)),
                    style = Stroke(width = 2.5f, cap = StrokeCap.Round),
                    alpha = 0.85f
                )
            }
        }

        // Main Glassmorphic Orb Body
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(baseSize)
                .shadow(elevation = 16.dp, shape = CircleShape)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            theme.surfaceColor.copy(alpha = config.transparency),
                            Color(0xFF04070D).copy(alpha = config.transparency)
                        ),
                        center = Offset(baseSize.value * 0.4f, baseSize.value * 0.4f),
                        radius = baseSize.value * 1.3f
                    )
                )
                .border(
                    width = 1.8.dp,
                    brush = Brush.linearGradient(
                        listOf(
                            corePrimaryColor.copy(alpha = 0.95f),
                            theme.secondaryColor.copy(alpha = 0.4f),
                            Color.White.copy(alpha = 0.2f)
                        )
                    ),
                    shape = CircleShape
                )
        ) {
            // Internal Holographic Core Visuals
            when (state) {
                JarvisOrbState.SPEAKING -> {
                    // Equalizer bars
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(26.dp)
                    ) {
                        for (i in 0..3) {
                            val barH by infiniteTransition.animateFloat(
                                initialValue = 8f,
                                targetValue = 24f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(280 + i * 70, easing = FastOutSlowInEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "EqBar$i"
                            )
                            val finalBarH = (barH + smoothAmplitude * 16f).coerceIn(4f, 26f)
                            Box(
                                modifier = Modifier
                                    .width(3.5.dp)
                                    .height(finalBarH.dp)
                                    .clip(CircleShape)
                                    .background(corePrimaryColor)
                            )
                        }
                    }
                }

                JarvisOrbState.LISTENING -> {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Listening",
                        tint = corePrimaryColor,
                        modifier = Modifier.size(24.dp)
                    )
                }

                JarvisOrbState.THINKING -> {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(spinAngle * 2)
                            .border(2.dp, corePrimaryColor, CircleShape)
                    )
                }

                JarvisOrbState.SUCCESS -> {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Success",
                        tint = corePrimaryColor,
                        modifier = Modifier.size(24.dp)
                    )
                }

                JarvisOrbState.ERROR -> {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Error",
                        tint = corePrimaryColor,
                        modifier = Modifier.size(24.dp)
                    )
                }

                JarvisOrbState.GUIDING -> {
                    Icon(
                        imageVector = Icons.Default.Navigation,
                        contentDescription = "Guiding",
                        tint = corePrimaryColor,
                        modifier = Modifier.size(22.dp)
                    )
                }

                else -> {
                    // IDLE / EXPLAINING / WORKING: Futuristic AI Core Icon
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "JARVIS Core",
                        tint = corePrimaryColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // Optional State Badge Label
        if (config.showStateBadge) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 12.dp)
                    .border(0.8.dp, corePrimaryColor.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            ) {
                Text(
                    text = state.name,
                    color = corePrimaryColor,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}
