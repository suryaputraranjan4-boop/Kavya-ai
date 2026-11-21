package com.example.ui.components

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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
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
    IDLE, UNDERSTANDING, THINKING, EXECUTING, SPEAKING, ERROR
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
        targetValue = (amplitude / 1500f).coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "AmplitudeSpring"
    )

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "PressScale"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "OrbLoop")

    val idleBreath by infiniteTransition.animateFloat(
        initialValue = 0.985f,
        targetValue = 1.015f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "IdleBreath"
    )

    val speakingFloatY by infiniteTransition.animateFloat(
        initialValue = -4f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "SpeakingFloatY"
    )

    val speakingPulse by infiniteTransition.animateFloat(
        initialValue = 1.01f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "SpeakingPulse"
    )

    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "WavePhase"
    )

    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ThinkSpin"
    )

    val baseScaleTarget = when (state) {
        VoiceState.IDLE -> 1.0f
        VoiceState.UNDERSTANDING -> 1.03f
        VoiceState.THINKING -> 1.04f
        VoiceState.EXECUTING -> 1.05f
        VoiceState.SPEAKING -> 1.08f
        VoiceState.ERROR -> 1.0f
    }

    val stateScale by animateFloatAsState(
        targetValue = baseScaleTarget + (animatedAmplitude * 0.06f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "StateScale"
    )

    val finalScale = when (state) {
        VoiceState.IDLE -> stateScale * idleBreath * pressScale
        VoiceState.SPEAKING -> stateScale * speakingPulse * pressScale
        VoiceState.THINKING -> stateScale * idleBreath * pressScale
        else -> stateScale * pressScale
    }

    val offsetY = if (state == VoiceState.SPEAKING) speakingFloatY else 0f

    val glowColor = when (state) {
        VoiceState.IDLE -> Primary.copy(alpha = 0.20f)
        VoiceState.UNDERSTANDING -> PrimaryLight.copy(alpha = 0.34f)
        VoiceState.THINKING -> AccentCyan.copy(alpha = 0.34f)
        VoiceState.EXECUTING -> SuccessGreen.copy(alpha = 0.34f)
        VoiceState.SPEAKING -> PrimaryLight.copy(alpha = 0.44f)
        VoiceState.ERROR -> Error.copy(alpha = 0.32f)
    }

    val gradientColors = when (state) {
        VoiceState.IDLE -> listOf(Primary, PrimaryLight, Secondary)
        VoiceState.UNDERSTANDING -> listOf(PrimaryLight, AccentCyan, Primary)
        VoiceState.THINKING -> listOf(AccentCyan, Secondary, PrimaryLight)
        VoiceState.EXECUTING -> listOf(SuccessGreen, AccentCyan, Primary)
        VoiceState.SPEAKING -> listOf(Primary, PrimaryLight, AccentPurpleLight, Primary)
        VoiceState.ERROR -> listOf(Error, Color(0xFFFF7A70))
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .offset(y = offsetY.dp)
            .size(baseSize + 44.dp)
            .scale(finalScale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        // Outer Ambient Radial Glow Halo (density-correct Canvas)
        Canvas(modifier = Modifier.size(baseSize + 40.dp)) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glowColor,
                        glowColor.copy(alpha = glowColor.alpha * 0.35f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = size.minDimension / 2f
                ),
                radius = size.minDimension / 2f,
                center = center
            )
        }

        // Waveform ring when speaking
        if (state == VoiceState.SPEAKING) {
            Canvas(modifier = Modifier.size(baseSize + 24.dp)) {
                val c = center
                val baseRadius = (size.minDimension / 2f) - 8f
                val waveAmp = 5.5f + (animatedAmplitude * 4f)
                val path = Path()
                val steps = 72
                for (i in 0..steps) {
                    val angle = (i.toFloat() / steps) * 2f * Math.PI.toFloat()
                    val wave = sin(angle * 6f + wavePhase) * waveAmp
                    val r = baseRadius + wave
                    val x = c.x + r * cos(angle)
                    val y = c.y + r * sin(angle)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(
                    path = path,
                    brush = Brush.sweepGradient(gradientColors),
                    style = Stroke(width = 2.4f, cap = StrokeCap.Round),
                    alpha = 0.85f
                )
            }
        }

        // Frosted Apple Liquid Glass Sphere
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(baseSize)
                .shadow(
                    elevation = 16.dp,
                    shape = CircleShape,
                    ambientColor = Primary.copy(alpha = 0.25f),
                    spotColor = Color(0x80000000)
                )
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xEB22293A),
                            Color(0xF0121621)
                        )
                    )
                )
                .border(
                    width = 1.4.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0x75FFFFFF),
                            PrimaryLight.copy(alpha = if (state == VoiceState.IDLE) 0.35f else 0.72f),
                            Color(0x1CFFFFFF)
                        )
                    ),
                    shape = CircleShape
                )
        ) {
            // Density-correct specular glass dome & subtle core tint
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            (if (state == VoiceState.ERROR) Error else PrimaryLight).copy(alpha = 0.24f),
                            Color.Transparent
                        ),
                        center = Offset(w * 0.34f, h * 0.28f),
                        radius = w * 0.78f
                    )
                )
                // Crisp top-left Apple glass specular crescent
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x42FFFFFF), Color.Transparent),
                        center = Offset(w * 0.32f, h * 0.22f),
                        radius = w * 0.42f
                    )
                )
            }

            when (state) {
                VoiceState.SPEAKING -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(34.dp)
                    ) {
                        for (i in 0..4) {
                            val barHeight by infiniteTransition.animateFloat(
                                initialValue = 9f,
                                targetValue = 30f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(380 + ((4 - i) * 75), easing = FastOutSlowInEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "SpeakBar$i"
                            )
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height(barHeight.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(Color.White, PrimaryLight)
                                        )
                                    )
                            )
                        }
                    }
                }
                VoiceState.THINKING, VoiceState.EXECUTING -> {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .rotate(spinAngle)
                            .clip(CircleShape)
                            .border(
                                width = 2.5.dp,
                                brush = Brush.sweepGradient(listOf(PrimaryLight, AccentCyan, Color.Transparent)),
                                shape = CircleShape
                            )
                    )
                }
                else -> {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(0x1AFFFFFF))
                            .border(1.dp, Color(0x2BFFFFFF), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Kavya Voice Control",
                            tint = TextPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}
