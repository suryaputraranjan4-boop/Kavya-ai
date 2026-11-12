package com.example.visual

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material3.minimumInteractiveComponentSize
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
 * Full-State Animated Floating JARVIS Orb with Sound Reactivity,
 * Transient Motion Effects (Drag / Dock / Expand / Collapse),
 * Reduced Motion mode, Minimize Pill, Quick Action Hub, and Emergency Close.
 */
@Composable
fun JarvisFloatingOrb(
    state: JarvisOrbState,
    config: JarvisOrbConfig,
    amplitude: Float = 0f,
    modifier: Modifier = Modifier,
    isWindowOverlay: Boolean = false,
    isMinimized: Boolean = false,
    isExpanded: Boolean? = null,
    transientMotion: OrbTransientMotion = OrbTransientMotion.NONE,
    isMicActive: Boolean = false,
    onTap: () -> Unit = {},
    onDoubleTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onPositionChanged: (Offset) -> Unit = {},
    onDragDelta: ((Offset) -> Unit)? = null,
    onDragFinished: (() -> Unit)? = null,
    onToggleMinimize: (() -> Unit)? = null,
    onExplainScreen: (() -> Unit)? = null,
    onReadScreenText: (() -> Unit)? = null,
    onToggleMic: (() -> Unit)? = null,
    onCloseOrb: (() -> Unit)? = null
) {
    var localQuickActions by remember { mutableStateOf(false) }
    val showQuickActions = isExpanded ?: localQuickActions

    val baseSize = config.sizeDp.dp
    val theme = config.theme
    val reducedMotion = config.reducedMotion

    val smoothAmplitude by animateFloatAsState(
        targetValue = if (config.soundReactive) amplitude.coerceIn(0f, 1f) else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "SmoothAmplitude"
    )

    val motionScaleTarget = when (transientMotion) {
        OrbTransientMotion.DRAGGING -> 1.10f
        OrbTransientMotion.DOCKING -> 0.96f
        OrbTransientMotion.EXPANDING -> 1.08f
        OrbTransientMotion.COLLAPSING -> 0.92f
        OrbTransientMotion.NONE -> 1.0f
    }
    val transientScale by animateFloatAsState(
        targetValue = motionScaleTarget,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "TransientMotionScale"
    )

    val speedFactor = config.speedMultiplier.coerceAtLeast(0.2f)
    val shouldAnimate = !reducedMotion

    val infiniteTransition = if (shouldAnimate) {
        rememberInfiniteTransition(label = "OrbMasterTransition")
    } else {
        null
    }

    val ambientBreathState = if (infiniteTransition != null) {
        infiniteTransition.animateFloat(
            initialValue = 0.97f,
            targetValue = 1.03f,
            animationSpec = infiniteRepeatable(
                animation = tween((2800 / speedFactor).roundToInt(), easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "AmbientBreath"
        )
    } else {
        rememberUpdatedState(1.0f)
    }

    val spinAngleState = if (infiniteTransition != null) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    when (state) {
                        JarvisOrbState.THINKING -> (1300 / speedFactor).roundToInt()
                        JarvisOrbState.PROCESSING -> (1100 / speedFactor).roundToInt()
                        JarvisOrbState.WORKING -> (1900 / speedFactor).roundToInt()
                        JarvisOrbState.CONNECTING, JarvisOrbState.LOADING -> (1500 / speedFactor).roundToInt()
                        JarvisOrbState.EXPLAINING, JarvisOrbState.SCREEN_SHARE_ACTIVE -> (2800 / speedFactor).roundToInt()
                        else -> (5800 / speedFactor).roundToInt()
                    },
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "SpinAngle"
        )
    } else {
        rememberUpdatedState(0f)
    }

    val reverseSpinAngleState = if (infiniteTransition != null) {
        infiniteTransition.animateFloat(
            initialValue = 360f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween((2200 / speedFactor).roundToInt(), easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "ReverseSpinAngle"
        )
    } else {
        rememberUpdatedState(0f)
    }

    val wavePhaseState = if (infiniteTransition != null) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 2f * PI.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween((1400 / speedFactor).roundToInt(), easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "WavePhase"
        )
    } else {
        rememberUpdatedState(0f)
    }

    val loadingSweepState = if (infiniteTransition != null) {
        infiniteTransition.animateFloat(
            initialValue = 40f,
            targetValue = 260f,
            animationSpec = infiniteRepeatable(
                animation = tween((1200 / speedFactor).roundToInt(), easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "LoadingSweep"
        )
    } else {
        rememberUpdatedState(160f)
    }

    val ambientBreath = ambientBreathState.value
    val spinAngle = spinAngleState.value
    val reverseSpinAngle = reverseSpinAngleState.value
    val wavePhase = wavePhaseState.value
    val loadingSweep = loadingSweepState.value

    val corePrimaryColor by animateColorAsState(
        targetValue = when (state) {
            JarvisOrbState.ERROR -> Color(0xFFFF1744)
            JarvisOrbState.SUCCESS -> Color(0xFF00E676)
            JarvisOrbState.SCREEN_SHARE_ACTIVE -> Color(0xFF00E5FF)
            JarvisOrbState.GUIDING -> Color(0xFFFF9100)
            JarvisOrbState.PROCESSING -> Color(0xFF7C4DFF)
            JarvisOrbState.CONNECTING, JarvisOrbState.LOADING -> Color(0xFF40C4FF)
            JarvisOrbState.EXPLAINING -> theme.primaryColor
            JarvisOrbState.THINKING -> theme.secondaryColor
            JarvisOrbState.WORKING -> theme.primaryColor
            JarvisOrbState.SPEAKING -> theme.primaryColor
            JarvisOrbState.LISTENING -> theme.primaryColor
            JarvisOrbState.IDLE -> theme.primaryColor
        },
        animationSpec = tween(if (reducedMotion) 120 else 350),
        label = "CoreColor"
    )

    val glowAlpha = when (state) {
        JarvisOrbState.LISTENING -> 0.55f + smoothAmplitude * 0.42f
        JarvisOrbState.SPEAKING -> 0.62f + smoothAmplitude * 0.38f
        JarvisOrbState.THINKING, JarvisOrbState.PROCESSING -> 0.70f
        JarvisOrbState.WORKING, JarvisOrbState.SCREEN_SHARE_ACTIVE -> 0.62f
        JarvisOrbState.CONNECTING, JarvisOrbState.LOADING -> 0.52f
        JarvisOrbState.ERROR, JarvisOrbState.SUCCESS -> 0.78f
        else -> 0.36f
    } * config.glowIntensity

    val stateScaleTarget = when (state) {
        JarvisOrbState.SPEAKING -> 1.06f + smoothAmplitude * 0.12f
        JarvisOrbState.LISTENING -> 1.03f + smoothAmplitude * 0.10f
        JarvisOrbState.THINKING, JarvisOrbState.PROCESSING -> 1.04f
        JarvisOrbState.SCREEN_SHARE_ACTIVE -> 1.05f
        JarvisOrbState.SUCCESS -> 1.09f
        JarvisOrbState.ERROR -> 1.02f
        else -> 1.0f
    }
    val stateScale by animateFloatAsState(
        targetValue = stateScaleTarget,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "StateScale"
    )
    val finalScale = stateScale * ambientBreath * transientScale

    val wavePath = remember { Path() }

    if (isMinimized) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color(0xEE070D17),
            modifier = modifier
                .scale(transientScale)
                .border(1.2.dp, corePrimaryColor, RoundedCornerShape(22.dp))
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
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clickable { onToggleMinimize?.invoke() }
                        .padding(horizontal = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(corePrimaryColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "JARVIS",
                        color = corePrimaryColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (onCloseOrb != null) {
                    IconButton(
                        onClick = { onCloseOrb() },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("jarvis_minimized_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Jarvis",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
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
                .size(baseSize + 44.dp)
                .scale(finalScale)
                .pointerInput(isWindowOverlay) {
                    detectTapGestures(
                        onTap = {
                            if (isWindowOverlay && isExpanded == null) {
                                localQuickActions = !localQuickActions
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
                            corePrimaryColor.copy(alpha = glowAlpha.coerceIn(0f, 1f)),
                            corePrimaryColor.copy(alpha = (glowAlpha * 0.35f).coerceIn(0f, 1f)),
                            Color.Transparent
                        )
                    ),
                    radius = size.width / 2f
                )
            }

            // Dual Gyroscopic Rings for THINKING / PROCESSING / EXPLAINING / WORKING / SCREEN_SHARE_ACTIVE
            if (state == JarvisOrbState.THINKING ||
                state == JarvisOrbState.PROCESSING ||
                state == JarvisOrbState.EXPLAINING ||
                state == JarvisOrbState.WORKING ||
                state == JarvisOrbState.SCREEN_SHARE_ACTIVE
            ) {
                Canvas(modifier = Modifier.size(baseSize + 20.dp).rotate(spinAngle)) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val r = (size.width / 2f) - 4f
                    drawCircle(
                        brush = Brush.sweepGradient(
                            listOf(corePrimaryColor, Color.Transparent, corePrimaryColor.copy(alpha = 0.85f), Color.Transparent)
                        ),
                        radius = r,
                        center = center,
                        style = Stroke(width = 2.6f)
                    )
                    for (i in 0..3) {
                        val angle = (i * 90f) * (PI.toFloat() / 180f)
                        drawCircle(
                            Color.White,
                            radius = 3f,
                            center = Offset(center.x + r * cos(angle), center.y + r * sin(angle))
                        )
                    }
                }
                if (state == JarvisOrbState.PROCESSING || state == JarvisOrbState.THINKING) {
                    Canvas(modifier = Modifier.size(baseSize + 10.dp).rotate(reverseSpinAngle)) {
                        drawArc(
                            color = theme.secondaryColor.copy(alpha = 0.8f),
                            startAngle = 0f,
                            sweepAngle = 140f,
                            useCenter = false,
                            style = Stroke(width = 2.2f, cap = StrokeCap.Round)
                        )
                    }
                }
            }

            // Progress Arc Ring for CONNECTING / LOADING
            if (state == JarvisOrbState.CONNECTING || state == JarvisOrbState.LOADING) {
                Canvas(modifier = Modifier.size(baseSize + 16.dp).rotate(spinAngle)) {
                    drawArc(
                        brush = Brush.sweepGradient(listOf(Color.Transparent, corePrimaryColor, Color.White)),
                        startAngle = 0f,
                        sweepAngle = loadingSweep,
                        useCenter = false,
                        style = Stroke(width = 3.2f, cap = StrokeCap.Round)
                    )
                }
            }

            // Reactive Audio Undulation Rings for SPEAKING / LISTENING
            if (state == JarvisOrbState.SPEAKING || state == JarvisOrbState.LISTENING) {
                Canvas(modifier = Modifier.size(baseSize + 18.dp)) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val baseRadius = (size.width / 2f) - 8f
                    val ampFactor = if (reducedMotion) 2f else (5f + smoothAmplitude * 12f)

                    wavePath.reset()
                    val steps = 56
                    for (i in 0..steps) {
                        val angle = (i.toFloat() / steps) * 2 * PI.toFloat()
                        val wave = sin(angle * 5 + wavePhase) * ampFactor
                        val r = baseRadius + wave
                        val x = center.x + r * cos(angle)
                        val y = center.y + r * sin(angle)
                        if (i == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
                    }
                    wavePath.close()

                    drawPath(
                        path = wavePath,
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
                                theme.secondaryColor.copy(alpha = 0.45f),
                                Color.White.copy(alpha = 0.22f)
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
                                val finalBarH = if (reducedMotion) {
                                    (10f + i * 3f + smoothAmplitude * 10f).coerceIn(6f, 24f)
                                } else {
                                    val barH = 10f + 10f * kotlin.math.abs(sin(wavePhase + i * 0.9f))
                                    (barH + smoothAmplitude * 12f).coerceIn(4f, 24f)
                                }
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

                    JarvisOrbState.THINKING, JarvisOrbState.PROCESSING -> {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .rotate(spinAngle * 2)
                                    .border(2.dp, corePrimaryColor, CircleShape)
                            )
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(corePrimaryColor)
                            )
                        }
                    }

                    JarvisOrbState.CONNECTING, JarvisOrbState.LOADING -> {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = state.name,
                            tint = corePrimaryColor,
                            modifier = Modifier
                                .size(22.dp)
                                .rotate(spinAngle)
                        )
                    }

                    JarvisOrbState.SCREEN_SHARE_ACTIVE -> {
                        Icon(
                            imageVector = Icons.Default.ScreenShare,
                            contentDescription = "Screen Share Active",
                            tint = corePrimaryColor,
                            modifier = Modifier.size(22.dp)
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
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "JARVIS Core",
                            tint = corePrimaryColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // Direct 1-Tap Emergency Close ('X') Badge on top-right corner of overlay orb (accessible hit area)
            if (isWindowOverlay && onCloseOrb != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable { onCloseOrb() }
                        .testTag("jarvis_orb_close_button")
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color(0xEE181E29))
                            .border(1.dp, Color(0xFFFF5252), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Jarvis Overlay",
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }

            // State Badge Label at bottom of orb
            if (config.showStateBadge) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.88f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .border(0.8.dp, corePrimaryColor.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                ) {
                    Text(
                        text = state.name.replace("_", " "),
                        color = corePrimaryColor,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // Expandable Quick Action Bar when tapped in overlay mode
        AnimatedVisibility(
            visible = isWindowOverlay && showQuickActions,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xF5080E18),
                modifier = Modifier
                    .padding(top = 4.dp)
                    .border(1.dp, corePrimaryColor.copy(alpha = 0.75f), RoundedCornerShape(16.dp))
                    .testTag("jarvis_orb_quick_menu")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    if (onToggleMic != null) {
                        IconButton(
                            onClick = {
                                if (isExpanded == null) localQuickActions = false
                                onToggleMic()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("jarvis_orb_mic_action")
                        ) {
                            Icon(
                                imageVector = if (isMicActive) Icons.Default.Mic else Icons.Default.MicOff,
                                contentDescription = if (isMicActive) "Mute Microphone" else "Unmute Microphone",
                                tint = if (isMicActive) Color(0xFF00E676) else Color(0xFFFFB74D),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    if (onExplainScreen != null) {
                        IconButton(
                            onClick = {
                                if (isExpanded == null) localQuickActions = false
                                onExplainScreen()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("jarvis_orb_explain_action")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = "Explain Screen",
                                tint = corePrimaryColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    if (onReadScreenText != null) {
                        IconButton(
                            onClick = {
                                if (isExpanded == null) localQuickActions = false
                                onReadScreenText()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("jarvis_orb_read_action")
                        ) {
                            Icon(
                                imageVector = Icons.Default.RecordVoiceOver,
                                contentDescription = "Read Screen Text",
                                tint = theme.secondaryColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    if (onToggleMinimize != null) {
                        IconButton(
                            onClick = {
                                if (isExpanded == null) localQuickActions = false
                                onToggleMinimize()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("jarvis_orb_minimize_action")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Remove,
                                contentDescription = "Minimize Orb",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    if (onCloseOrb != null) {
                        IconButton(
                            onClick = {
                                if (isExpanded == null) localQuickActions = false
                                onCloseOrb()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("jarvis_orb_stop_action")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = "Stop Jarvis",
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
