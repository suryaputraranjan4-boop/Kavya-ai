package com.example.ui.components

import android.view.LayoutInflater
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.R

enum class EmotionState {
    NEUTRAL, HAPPY, EXCITED, CURIOUS, THOUGHTFUL, SURPRISED, CONFUSED, CONCERNED, SAD, CALM, PLAYFUL, PROUD, EMBARRASSED, TIRED
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun HolographicKavya(
    voiceState: VoiceState,
    modifier: Modifier = Modifier,
    emotionState: EmotionState = EmotionState.NEUTRAL
) {
    val context = LocalContext.current
    var isPlayerAvailable by remember { mutableStateOf(true) }

    // Target media resource based on voice state
    val targetRawRes = remember(voiceState) {
        when (voiceState) {
            VoiceState.IDLE, VoiceState.ERROR -> R.raw.kavya_idle
            VoiceState.UNDERSTANDING, VoiceState.THINKING, VoiceState.EXECUTING -> R.raw.kavya_thinking
            VoiceState.SPEAKING -> R.raw.kavya_speaking
        }
    }

    // Initialize a SINGLE shared ExoPlayer safely
    val player = remember(context) {
        try {
            ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_ALL
                volume = 0f // Mute
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        // If system media codec fails, fallback gracefully without crashing
                        isPlayerAvailable = false
                    }
                })
            }
        } catch (e: Throwable) {
            isPlayerAvailable = false
            null
        }
    }

    // Switch media item when targetRawRes changes
    LaunchedEffect(targetRawRes, player) {
        player?.let { p ->
            try {
                val mediaItem = MediaItem.fromUri("android.resource://${context.packageName}/$targetRawRes")
                p.setMediaItem(mediaItem)
                p.prepare()
                p.play()
            } catch (e: Throwable) {
                isPlayerAvailable = false
            }
        }
    }

    DisposableEffect(player) {
        onDispose {
            player?.release()
        }
    }

    // Holographic Glow Pulse
    val infiniteTransition = rememberInfiniteTransition(label = "KavyaGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = when (voiceState) {
            VoiceState.THINKING -> 0.35f
            VoiceState.SPEAKING -> 0.4f
            else -> 0.25f
        },
        animationSpec = infiniteRepeatable(
            animation = tween(
                when (voiceState) {
                    VoiceState.THINKING -> 600
                    VoiceState.SPEAKING -> 1200
                    else -> 4000
                },
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GlowAlpha"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
    ) {
        // Holographic Backlight
        Box(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .fillMaxWidth(0.6f)
                .alpha(glowAlpha)
                .blur(40.dp)
                .background(
                    color = when (voiceState) {
                        VoiceState.UNDERSTANDING, VoiceState.THINKING, VoiceState.EXECUTING -> Color(0xFFBA68C8)
                        VoiceState.ERROR -> Color(0xFFE57373)
                        else -> Color(0xFF81D4FA)
                    },
                    shape = androidx.compose.foundation.shape.CircleShape
                )
        )

        // Single video player or fallback visual avatar
        if (isPlayerAvailable && player != null) {
            AndroidView(
                factory = { ctx ->
                    val view = LayoutInflater.from(ctx).inflate(R.layout.view_video_player, null) as PlayerView
                    view.player = player
                    view
                },
                update = { view ->
                    if (view.player != player) {
                        view.player = player
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Elegant Holographic Fallback Avatar when media codecs are not available on emulator
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .alpha(glowAlpha * 0.8f)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF81D4FA).copy(alpha = 0.4f),
                                    Color(0xFFBA68C8).copy(alpha = 0.2f),
                                    Color.Transparent
                                )
                            ),
                            shape = androidx.compose.foundation.shape.CircleShape
                        )
                )
            }
        }

        // Front Holographic overlay (subtle scan effect)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.05f),
                            Color.Transparent
                        )
                    )
                )
                .alpha(glowAlpha)
        )
    }
}
