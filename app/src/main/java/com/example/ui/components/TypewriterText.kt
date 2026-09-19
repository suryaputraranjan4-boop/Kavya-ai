package com.example.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign

@Composable
fun TypewriterText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle,
    textAlign: TextAlign? = null,
    delayPerChar: Long = 0L
) {
    // Instant rendering without artificial thinking delays
    Text(
        text = text,
        modifier = modifier,
        style = style,
        textAlign = textAlign,
        color = style.color
    )
}
