package com.example.visual

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 9-State Animated Floating JARVIS Orb with Sound Reactivity,
 * WindowManager Drag Delegation, Minimize Mode, Quick Explain Action, and Emergency Close Button.
 */
@Composable
fun JarvisFloatingOrb(
    state: JarvisOrbState,
    config: JarvisOrbConfig,
    amplitude: Float = 0f,
    modifier: Modifier = Modifier,
    isWindowOverlay: Boolean = false,
    isMinimized: Boolean = false,
    onTap: () -> Unit = {},
    onDoubleTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onPositionChanged: (Offset) -> Unit = {},
    onDragDelta: ((Offset) -> Unit)? = null,
    onDragFinished: (() -> Unit)? = null,
    onToggleMinimize: (() -> Unit)? = null,
    onExplainScreen: (() -> Unit)? = null,
    onCloseOrb: (() -> Unit)? = null
) {
    var showQuickActions by remember { mutableStateOf(false) }

    val baseSize = config.sizeDp.dp
    val theme = config.theme

    val smoothAmplitude by animateFloatAsState(
        targetValue = amplitude.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "SmoothAmplitude"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "OrbMasterTransition")

    val ambientBreath by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween((2800 / config.speedMultiplier.coerceAtLeast(0.2f)).roundToInt(), easing = FastOutSlowInEasing),
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
                    JarvisOrbState.THINKING -> (1400 / config.speedMultiplier.coerceAtLeast(0.2f)).roundToInt()
                    JarvisOrbState.WORKING -> (2000 / config.speedMultiplier.coerceAtLeast(0.2f)).roundToInt()
                    JarvisOrbState.EXPLAINING -> (3000 / config.speedMultiplier.coerceAtLeast(0.2f)).roundToInt()
                    else -> (6000 / config.speedMultiplier.coerceAtLeast(0.2f)).roundToInt()
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
            animation = tween((1400 / config.speedMultiplier.coerceAtLeast(0.2f)).roundToInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "WavePhase"
    )

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
        JarvisOrbState.SPEAKING -> 1.06f + smoothAmplitude * 0.12f
        JarvisOrbState.LISTENING -> 1.03f + smoothAmplitude * 0.10f
        JarvisOrbState.THINKING -> 1.04f
        JarvisOrbState.SUCCESS -> 1.10f
        JarvisOrbState.ERROR -> 1.02f
        else -> 1.0f
    } * ambientBreath

    if (isMinimized) {
        // Minimized Edge Pill — compact, non-intrusive, easy to restore or close
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xEB070D17),
            modifier = modifier
                .border(1.2.dp, corePrimaryColor, RoundedCornerShape(20.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDragDelta?.invoke(dragAmount)
                            onPositionChanged(dragAmount)
                        },
                        onDragEnd = {
                            onDragFinished?.invoke()
                        }
                    )
                }
                .testTag("jarvis_minimized_orb")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(corePrimaryColor)
                        .clickable { onToggleMinimize?.invoke() }
                )
                Text(
                    text = "JARVIS",
                    color = corePrimaryColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable { onToggleMinimize?.invoke() }
                )
                if (onCloseOrb != null) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Jarvis",
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier
                            .size(15.dp)
                            .clickable { onCloseOrb() }
                            .testTag("jarvis_minimized_close_button")
                    )
                }
            }
        }
        return
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.testTag("jarvis_floating_orb_root")
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(baseSize + 40.dp)
                .scale(scaleMultiplier)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (isWindowOverlay) {
                                showQuickActions = !showQuickActions
                            }
                            onTap()
                        },
                        onDoubleTap = { onDoubleTap() },
                        onLongPress = { onLongPress() }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDragDelta?.invoke(dragAmount)
                            onPositionChanged(dragAmount)
                        },
                        onDragEnd = {
                            onDragFinished?.invoke()
                        }
                    )
                }
        ) {
            // Outer Glowing Aura Halo
            Canvas(modifier = Modifier.size(baseSize + 36.dp)) {
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
                Canvas(modifier = Modifier.size(baseSize + 18.dp).rotate(spinAngle)) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val r = (size.width / 2f) - 4f
                    drawCircle(
                        brush = Brush.sweepGradient(
                            listOf(corePrimaryColor, Color.Transparent, corePrimaryColor.copy(alpha = 0.8f), Color.Transparent)
                        ),
                        radius = r,
                        center = center,
                        style = Stroke(width = 2.5f)
                    )

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
                Canvas(modifier = Modifier.size(baseSize + 16.dp)) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val baseRadius = (size.width / 2f) - 8f
                    val ampFactor = 5f + smoothAmplitude * 12f

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
                    .shadow(elevation = 12.dp, shape = CircleShape)
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
                when (state) {
                    JarvisOrbState.SPEAKING -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.height(24.dp)
                        ) {
                            for (i in 0..3) {
                                val barH by infiniteTransition.animateFloat(
                                    initialValue = 8f,
                                    targetValue = 22f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(280 + i * 70, easing = FastOutSlowInEasing),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "EqBar$i"
                                )
                                val finalBarH = (barH + smoothAmplitude * 14f).coerceIn(4f, 24f)
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
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    JarvisOrbState.THINKING -> {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .rotate(spinAngle * 2)
                                .border(2.dp, corePrimaryColor, CircleShape)
                        )
                    }

                    JarvisOrbState.SUCCESS -> {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Success",
                            tint = corePrimaryColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    JarvisOrbState.ERROR -> {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Error",
                            tint = corePrimaryColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    JarvisOrbState.GUIDING -> {
                        Icon(
                            imageVector = Icons.Default.Navigation,
                            contentDescription = "Guiding",
                            tint = corePrimaryColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    else -> {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "JARVIS Core",
                            tint = corePrimaryColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // Direct 1-Tap Emergency Close ('X') Badge on top-right corner of overlay orb
            if (isWindowOverlay && onCloseOrb != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-2).dp, y = 2.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color(0xEE181E29))
                        .border(1.dp, Color(0xFFFF5252), CircleShape)
                        .clickable { onCloseOrb() }
                        .testTag("jarvis_orb_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Jarvis Overlay",
                        tint = Color(0xFFFF5252),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            // State Badge Label at bottom of orb
            if (config.showStateBadge) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .border(0.8.dp, corePrimaryColor.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                ) {
                    Text(
                        text = state.name,
                        color = corePrimaryColor,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // Expandable Quick Action Bar when tapped in overlay mode
        AnimatedVisibility(visible = isWindowOverlay && showQuickActions) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xF2080E18),
                modifier = Modifier
                    .padding(top = 4.dp)
                    .border(1.dp, corePrimaryColor.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                    .testTag("jarvis_orb_quick_menu")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    if (onExplainScreen != null) {
                        IconButton(
                            onClick = {
                                showQuickActions = false
                                onExplainScreen()
                            },
                            modifier = Modifier
                                .size(30.dp)
                                .testTag("jarvis_orb_explain_action")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = "Explain Screen",
                                tint = corePrimaryColor,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }

                    if (onToggleMinimize != null) {
                        IconButton(
                            onClick = {
                                showQuickActions = false
                                onToggleMinimize()
                            },
                            modifier = Modifier
                                .size(30.dp)
                                .testTag("jarvis_orb_minimize_action")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Remove,
                                contentDescription = "Minimize Orb",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }

                    if (onCloseOrb != null) {
                        IconButton(
                            onClick = {
                                showQuickActions = false
                                onCloseOrb()
                            },
                            modifier = Modifier
                                .size(30.dp)
                                .testTag("jarvis_orb_stop_action")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = "Stop Jarvis",
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
