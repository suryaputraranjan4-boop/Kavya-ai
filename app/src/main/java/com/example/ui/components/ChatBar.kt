package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.material.icons.filled.MicOff
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

@Composable
fun ChatBar(
    onSend: (String) -> Unit,
    onPlusClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholderText: String = "Message Kavya...",
    onMicClick: (() -> Unit)? = null,
    isMicListening: Boolean = false
) {
    var text by remember { mutableStateOf("") }
    val pillShape = RoundedCornerShape(28.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(pillShape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xDE1D2331), Color(0xD9131722))
                )
            )
            .border(1.dp, AppleGlassBorderBrush, pillShape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onPlusClick,
            modifier = Modifier.size(40.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(0x1CFFFFFF))
                    .border(1.dp, Color(0x28FFFFFF), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Attach or share",
                    tint = TextPrimary,
                    modifier = Modifier.size(19.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp, horizontal = 4.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = TextPrimary,
                fontSize = 15.sp
            ),
            cursorBrush = SolidColor(PrimaryLight),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                if (text.isNotBlank()) {
                    onSend(text)
                    text = ""
                }
            }),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (text.isEmpty()) {
                        Text(
                            text = placeholderText,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = TextTertiary,
                                fontSize = 15.sp
                            )
                        )
                    }
                    innerTextField()
                }
            }
        )

        Spacer(modifier = Modifier.width(4.dp))

        val isTyping = text.isNotBlank()
        val interactionSource = remember { MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()
        val btnScale by animateFloatAsState(
            targetValue = if (isPressed) 0.90f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMedium),
            label = "ActionBtnScale"
        )

        IconButton(
            onClick = {
                if (isTyping) {
                    onSend(text)
                    text = ""
                } else {
                    onMicClick?.invoke()
                }
            },
            enabled = isTyping || onMicClick != null,
            interactionSource = interactionSource,
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
                        if (isTyping) {
                            Brush.linearGradient(listOf(Primary, PrimaryLight))
                        } else if (isMicListening) {
                            Brush.linearGradient(listOf(SuccessGreen, AccentCyan))
                        } else {
                            Brush.verticalGradient(listOf(Color(0x28FFFFFF), Color(0x14FFFFFF)))
                        }
                    )
                    .border(
                        1.dp,
                        if (isTyping || isMicListening) Color(0x55FFFFFF) else Color(0x2EFFFFFF),
                        CircleShape
                    )
            ) {
                val icon = if (isTyping) {
                    Icons.AutoMirrored.Filled.Send
                } else if (isMicListening) {
                    Icons.Default.Mic
                } else {
                    Icons.Default.MicOff
                }
                Icon(
                    imageVector = icon,
                    contentDescription = if (isTyping) "Send message" else "Voice input",
                    tint = if (isTyping || isMicListening) Color.White else TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
