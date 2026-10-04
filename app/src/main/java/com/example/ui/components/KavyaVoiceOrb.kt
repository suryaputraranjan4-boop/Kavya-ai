package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

enum class VoiceState {
    IDLE, LISTENING, UNDERSTANDING, THINKING, EXECUTING, SPEAKING, ERROR
}

@Composable
fun KavyaVoiceOrb(
    state: VoiceState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    baseSize: Dp = 150.dp,
    amplitude: Float = 0f
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val animatedAmplitude by animateFloatAsState(
        targetValue = amplitude.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "AmplitudeSpring"
    )

    // Smooth press feedback
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "PressScale"
    )

    // Infinite transitions for ambient, listening, and speaking effects
    val infiniteTransition = rememberInfiniteTransition(label = "OrbLoop")

    // Ambient Idle Breathing (very slow, graceful)
    val idleBreath by infiniteTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "IdleBreath"
    )

    // Listening Glow Pulse (responsive & rhythmic)
    val listeningPulse by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ListeningPulse"
    )

    val listeningGlowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ListeningGlowAlpha"
    )

    // Speaking Gentle Float / Wobble (Smooth, gentle floating - never shaky)
    val speakingFloatY by infiniteTransition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "SpeakingFloatY"
    )

    val speakingRotation by infiniteTransition.animateFloat(
        initialValue = -2.5f,
        targetValue = 2.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "SpeakingRotation"
    )

    val speakingPulse by infiniteTransition.animateFloat(
        initialValue = 1.02f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "SpeakingPulse"
    )

    // Continuous wave phase for waveform animation
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "WavePhase"
    )

    // Dynamic sizing & transforms based on VoiceState
    val baseScaleTarget = when (state) {
        VoiceState.IDLE -> 1.0f
        VoiceState.LISTENING -> 1.08f
        VoiceState.UNDERSTANDING -> 1.02f
        VoiceState.THINKING -> 1.04f
        VoiceState.EXECUTING -> 1.06f
        VoiceState.SPEAKING -> 1.12f
        VoiceState.ERROR -> 1.0f
    }

    val stateScale by animateFloatAsState(
        targetValue = baseScaleTarget,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "StateScale"
    )

    val ampMultiplier = if (state == VoiceState.LISTENING) 1.0f + (animatedAmplitude * 0.45f) else 1.0f
    val finalScale = when (state) {
        VoiceState.IDLE -> stateScale * idleBreath * pressScale
        VoiceState.LISTENING -> stateScale * listeningPulse * pressScale * ampMultiplier
        VoiceState.SPEAKING -> stateScale * speakingPulse * pressScale
        VoiceState.UNDERSTANDING -> 1.02f
        VoiceState.THINKING -> stateScale * idleBreath * pressScale
        VoiceState.EXECUTING -> 1.06f
        VoiceState.ERROR -> stateScale * pressScale
    }

    val offsetY = if (state == VoiceState.SPEAKING) speakingFloatY else 0f
    val rotationAngle = if (state == VoiceState.SPEAKING) speakingRotation else 0f

    val glowColor = when (state) {
        VoiceState.IDLE -> BrandGradientStart.copy(alpha = 0.18f)
        VoiceState.LISTENING -> BrandGradientMid.copy(alpha = listeningGlowAlpha)
        VoiceState.UNDERSTANDING -> AccentCyan.copy(alpha = 0.35f)
        VoiceState.THINKING -> AccentCyan.copy(alpha = 0.35f)
        VoiceState.EXECUTING -> AccentCyan.copy(alpha = 0.45f)
        VoiceState.SPEAKING -> AccentPurpleLight.copy(alpha = 0.5f)
        VoiceState.ERROR -> Error.copy(alpha = 0.3f)
    }

    val gradientColors = when (state) {
        VoiceState.IDLE -> listOf(BrandGradientStart, BrandGradientEnd)
        VoiceState.LISTENING -> listOf(BrandGradientStart, BrandGradientMid, BrandGradientEnd)
        VoiceState.UNDERSTANDING -> listOf(AccentCyan, AccentPurple)
        VoiceState.THINKING -> listOf(AccentCyan, AccentPurple)
        VoiceState.EXECUTING -> listOf(AccentCyan, AccentPurpleLight)
        VoiceState.SPEAKING -> listOf(BrandGradientStart, AccentPurpleLight, Secondary)
        VoiceState.ERROR -> listOf(Error, BrandGradientStart)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .offset(y = offsetY.dp)
            .rotate(rotationAngle)
            .size(baseSize + 60.dp)
            .scale(finalScale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        // Outer Ambient Radial Glow Halo
        Box(
            modifier = Modifier
                .size(baseSize + 50.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            glowColor,
                            glowColor.copy(alpha = glowColor.alpha * 0.4f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        // Waveform Rings for LISTENING and SPEAKING states
        if (state == VoiceState.LISTENING || state == VoiceState.SPEAKING) {
            Canvas(
                modifier = Modifier
                    .size(baseSize + 28.dp)
            ) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val baseRadius = (size.width / 2f) - 10f
                val waveAmplitude = if (state == VoiceState.SPEAKING) 7f else 9f

                val path = Path()
                val steps = 72
                for (i in 0..steps) {
                    val angle = (i.toFloat() / steps) * 2 * Math.PI.toFloat()
                    val wave = sin(angle * 6 + wavePhase) * waveAmplitude
                    val r = baseRadius + wave
                    val x = center.x + r * cos(angle)
                    val y = center.y + r * sin(angle)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()

                drawPath(
                    path = path,
                    brush = Brush.sweepGradient(gradientColors),
                    style = Stroke(width = 2.5f, cap = StrokeCap.Round),
                    alpha = if (state == VoiceState.SPEAKING) 0.8f else 0.95f
                )
            }
        }

        // Frosted Glass Main Orb Sphere
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(baseSize)
                .shadow(
                    elevation = 16.dp,
                    shape = CircleShape,
                    ambientColor = Primary.copy(alpha = 0.3f),
                    spotColor = AccentPurple.copy(alpha = 0.4f)
                )
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            SurfaceGlass.copy(alpha = 0.92f),
                            Surface.copy(alpha = 0.95f)
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(300f, 300f)
                    )
                )
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            BrandGradientStart.copy(alpha = if (state == VoiceState.IDLE) 0.5f else 0.9f),
                            AccentPurple.copy(alpha = 0.4f),
                            Color.White.copy(alpha = 0.15f)
                        )
                    ),
                    shape = CircleShape
                )
        ) {
            // Subtle Radial highlight inside the sphere
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                (if (state == VoiceState.SPEAKING) AccentPurple else BrandGradientStart).copy(alpha = 0.22f),
                                Color.Transparent
                            ),
                            center = Offset(baseSize.value * 0.4f, baseSize.value * 0.4f),
                            radius = baseSize.value * 1.2f
                        )
                    )
            )

            // Center Content: Audio Waves / Animated Visualizers
            when (state) {
                VoiceState.LISTENING -> {
                    // Modern audio wave bars
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(36.dp)
                    ) {
                        for (i in 0..4) {
                            val barHeight by infiniteTransition.animateFloat(
                                initialValue = 8f,
                                targetValue = 32f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(380 + (i * 90), easing = FastOutSlowInEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "Bar$i"
                            )
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height(barHeight.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(BrandGradientStart, BrandGradientEnd)
                                        )
                                    )
                            )
                        }
                    }
                }
                VoiceState.SPEAKING -> {
                    // Glowing speaking wave pattern
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(38.dp)
                    ) {
                        for (i in 0..4) {
                            val barHeight by infiniteTransition.animateFloat(
                                initialValue = 10f,
                                targetValue = 34f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(420 + ((4 - i) * 80), easing = FastOutSlowInEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "SpeakBar$i"
                            )
                            Box(
                                modifier = Modifier
                                    .width(4.5.dp)
                                    .height(barHeight.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(AccentPurpleLight, BrandGradientStart)
                                        )
                                    )
                            )
                        }
                    }
                }
                VoiceState.THINKING -> {
                    // Smooth rotating pulse
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.sweepGradient(listOf(AccentCyan, AccentPurple, Color.Transparent))
                            )
                    )
                }
                else -> {
                    // IDLE: Clean, minimalist modern AI core icon
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.06f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Kavya AI Core",
                            tint = TextPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    }
}
