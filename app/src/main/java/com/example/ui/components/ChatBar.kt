package com.example.ui.components

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.example.utils.PermissionsManager

@Composable
fun ChatBar(
    onSend: (String) -> Unit,
    onMicClick: () -> Unit,
    onPlusClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholderText: String = "Talk to Kavya...",
    isListening: Boolean = false
) {
    var text by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    var showPermissionAlert by remember { mutableStateOf(false) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            onMicClick()
        } else {
            showPermissionAlert = true
        }
    }

    if (showPermissionAlert) {
        AlertDialog(
            onDismissRequest = { showPermissionAlert = false },
            title = {
                Text(
                    text = "माइक्रोफ़ोन अनुमति आवश्यक है",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            },
            text = {
                Text(
                    text = "Kavya AI को आपकी आवाज़ सुनने के लिए Android Microphone अनुमति चाहिए। कृपया App Settings में जाकर Microphone permission Allow करें।",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPermissionAlert = false
                        PermissionsManager.openAppSettings(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showPermissionAlert = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                }
            },
            containerColor = Color(0xFF1E1E2E)
        )
    }

    // Pulse animation when listening
    val infiniteTransition = rememberInfiniteTransition(label = "MicListeningPulse")
    val listeningPulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(SurfaceGlass.copy(alpha = 0.85f))
            .border(
                1.dp,
                if (isListening) Color(0xFFFF5252).copy(alpha = 0.6f) else GlassBorder,
                RoundedCornerShape(28.dp)
            )
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Attachment / Vision Action Button
        IconButton(
            onClick = onPlusClick,
            modifier = Modifier.size(42.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.05f))
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Screen & Actions",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Input Field
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = TextPrimary,
                fontSize = 15.sp
            ),
            cursorBrush = SolidColor(Primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                if (text.isNotBlank()) {
                    onSend(text)
                    text = ""
                }
            }),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) {
                        Text(
                            text = if (isListening) "सुन रही हूँ... (Listening...)" else placeholderText,
                            color = if (isListening) Color(0xFFFF7A7A) else TextTertiary,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp)
                        )
                    }
                    innerTextField()
                }
            }
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Dynamic Send / Mic Button with smooth morph
        val isTyping = text.isNotBlank()
        val interactionSource = remember { MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()
        val btnScale by animateFloatAsState(
            targetValue = if (isPressed) 0.90f else if (isListening) listeningPulse else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "ActionBtnScale"
        )

        IconButton(
            onClick = {
                if (isTyping) {
                    onSend(text)
                    text = ""
                } else {
                    val permGranted = PermissionsManager.hasRecordAudioPermission(context)
                    if (permGranted) {
                        onMicClick()
                    } else {
                        micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                }
            },
            modifier = Modifier
                .size(44.dp)
                .scale(btnScale)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isTyping -> Brush.linearGradient(listOf(BrandGradientStart, BrandGradientEnd))
                            isListening -> Brush.linearGradient(listOf(Color(0xFFE53935), Color(0xFFFF5252)))
                            else -> Brush.linearGradient(listOf(SurfaceElevated, SurfaceVariant))
                        }
                    )
                    .border(
                        1.dp,
                        when {
                            isTyping -> Color.White.copy(alpha = 0.2f)
                            isListening -> Color(0xFFFFCDD2)
                            else -> GlassBorder
                        },
                        CircleShape
                    )
            ) {
                when {
                    isTyping -> {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send message",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    isListening -> {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop Listening",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    else -> {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice Input",
                            tint = PrimaryLight,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
