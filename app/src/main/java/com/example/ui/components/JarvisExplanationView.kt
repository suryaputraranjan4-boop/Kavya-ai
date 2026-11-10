package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.example.visual.JarvisGuidanceState
import com.example.visual.VisualExplanationCard

/**
 * Renders the JARVIS Visual Explanation Card ("JARVIS Explains Anything").
 */
@Composable
fun JarvisExplanationCardView(
    card: VisualExplanationCard,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceGlass),
        border = BorderStroke(1.dp, PrimaryLight.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Primary.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Lightbulb, contentDescription = null, tint = PrimaryLight, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "JARVIS EXPLAINS",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = PrimaryLight
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = card.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = card.oneLineSummary,
                style = MaterialTheme.typography.bodyMedium,
                color = AccentCyan
            )

            if (card.bulletChips.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    card.bulletChips.forEach { chip ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text("• ", color = PrimaryLight, fontWeight = FontWeight.Bold)
                            Text(chip, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.9f))
                        }
                    }
                }
            }

            if (card.diagramFlow.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text("Process Flow:", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = TextSecondary)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    card.diagramFlow.forEachIndexed { idx, step ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Primary.copy(alpha = 0.15f),
                            border = BorderStroke(0.5.dp, PrimaryLight.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = "${idx + 1}. $step",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            if (card.sourceCitations.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Sources: " + card.sourceCitations.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = TextTertiary
                )
            }
        }
    }
}

/**
 * Visual Reticle and Banner for "JARVIS Shows You How" interactive guidance.
 */
@Composable
fun JarvisGuidanceBanner(
    guidance: JarvisGuidanceState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!guidance.isActive) return

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1E2337).copy(alpha = 0.95f),
        border = BorderStroke(1.5.dp, AccentCyan),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(AccentCyan.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.TouchApp, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(22.dp))
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "JARVIS SHOWS YOU HOW",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = AccentCyan
                )
                Text(
                    text = guidance.instructionText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White
                )
            }

            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
            }
        }
    }
}
