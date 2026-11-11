package com.example.visual

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Universal Container for JARVIS Holographic Explanation Cards.
 */
@Composable
fun JarvisCardFrame(
    title: String,
    theme: JarvisThemePreset,
    modifier: Modifier = Modifier,
    confidence: Float = 0.95f,
    onClose: (() -> Unit)? = null,
    onMinimize: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = theme.surfaceColor.copy(alpha = 0.94f)
        ),
        modifier = modifier
            .border(
                width = 1.2.dp,
                brush = Brush.linearGradient(
                    listOf(theme.primaryColor.copy(alpha = 0.8f), theme.secondaryColor.copy(alpha = 0.3f))
                ),
                shape = RoundedCornerShape(16.dp)
            )
            .widthIn(min = 280.dp, max = 560.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header Bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(theme.primaryColor)
                    )
                    Text(
                        text = title.uppercase(),
                        color = theme.primaryColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Confidence pill
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = theme.primaryColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "${(confidence * 100).toInt()}% CONF",
                            color = theme.primaryColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    if (onMinimize != null) {
                        IconButton(
                            onClick = onMinimize,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Remove,
                                contentDescription = "Minimize",
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    if (onClose != null) {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            content()
        }
    }
}

/**
 * Summary Card with Key Takeaways.
 */
@Composable
fun JarvisSummaryCardView(
    card: VisualCardData,
    theme: JarvisThemePreset,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null
) {
    JarvisCardFrame(
        title = card.title,
        theme = theme,
        confidence = card.confidence,
        modifier = modifier,
        onClose = onClose
    ) {
        Text(
            text = card.summary,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 20.sp
        )

        if (card.bullets.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                card.bullets.forEach { bullet ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "▸",
                            color = theme.primaryColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = bullet,
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Typewriter Detailed Card with live character reveal animation.
 */
@Composable
fun JarvisDetailTypewriterCardView(
    card: VisualCardData,
    theme: JarvisThemePreset,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null
) {
    var revealedLength by remember { mutableIntStateOf(0) }
    val fullText = card.detailText.ifBlank { card.summary }

    LaunchedEffect(fullText) {
        revealedLength = 0
        for (i in 1..fullText.length) {
            revealedLength = i
            delay(12L) // fast fluid typewriter reveal
        }
    }

    JarvisCardFrame(
        title = card.title,
        theme = theme,
        confidence = card.confidence,
        modifier = modifier,
        onClose = onClose
    ) {
        val displayedText = fullText.take(revealedLength)
        val isTyping = revealedLength < fullText.length

        Text(
            text = buildString {
                append(displayedText)
                if (isTyping) append(" ▌")
            },
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 13.5.sp,
            lineHeight = 20.sp,
            fontFamily = FontFamily.SansSerif
        )

        if (card.citations.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Source,
                    contentDescription = "Source",
                    tint = theme.primaryColor,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "Ref: ${card.citations.joinToString(", ")}",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/**
 * Comparison Table Card (Option A vs Option B or Pros vs Cons).
 */
@Composable
fun JarvisComparisonCardView(
    card: VisualCardData,
    theme: JarvisThemePreset,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null
) {
    JarvisCardFrame(
        title = card.title,
        theme = theme,
        confidence = card.confidence,
        modifier = modifier,
        onClose = onClose
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            card.comparisonItems.forEach { item ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.White.copy(alpha = 0.04f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(0.8.dp, theme.primaryColor.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                        .padding(8.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = item.feature,
                            color = theme.primaryColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "A: ${item.optionA}",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "B: ${item.optionB}",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (item.winner.isNotBlank()) {
                            Text(
                                text = "✓ Verdict: ${item.winner}",
                                color = theme.primaryColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dynamic Native Canvas Chart Card (Bar or Line).
 */
@Composable
fun JarvisCanvasGraphCardView(
    card: VisualCardData,
    theme: JarvisThemePreset,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null
) {
    val graphData = card.graphData ?: return

    val animatedProgress = remember { Animatable(0f) }
    LaunchedEffect(graphData) {
        animatedProgress.snapTo(0f)
        animatedProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 1100, easing = FastOutSlowInEasing)
        )
    }

    JarvisCardFrame(
        title = graphData.title,
        theme = theme,
        confidence = card.confidence,
        modifier = modifier,
        onClose = onClose
    ) {
        val maxValue = graphData.values.maxOrNull()?.coerceAtLeast(1f) ?: 1f

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .padding(vertical = 4.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val progress = animatedProgress.value

                when (graphData.type) {
                    GraphChartType.BAR_CHART -> {
                        val barCount = graphData.values.size
                        val spacing = 12f
                        val barWidth = ((w - spacing * (barCount + 1)) / barCount).coerceAtLeast(8f)

                        graphData.values.forEachIndexed { i, value ->
                            val barHeight = (value / maxValue) * (h - 24f) * progress
                            val left = spacing + i * (barWidth + spacing)
                            val top = h - barHeight

                            // Gradient Bar
                            drawRect(
                                brush = Brush.verticalGradient(
                                    listOf(theme.primaryColor, theme.secondaryColor.copy(alpha = 0.4f)),
                                    startY = top,
                                    endY = h
                                ),
                                topLeft = Offset(left, top),
                                size = Size(barWidth, barHeight)
                            )

                            // Top Glow Cap
                            drawLine(
                                color = Color.White,
                                start = Offset(left, top),
                                end = Offset(left + barWidth, top),
                                strokeWidth = 2.5f
                            )
                        }
                    }

                    GraphChartType.LINE_CHART -> {
                        val count = graphData.values.size
                        if (count > 1) {
                            val stepX = w / (count - 1)
                            val path = Path()
                            val points = mutableListOf<Offset>()

                            graphData.values.forEachIndexed { i, value ->
                                val x = i * stepX
                                val y = h - ((value / maxValue) * (h - 24f) * progress)
                                val pt = Offset(x, y)
                                points.add(pt)
                                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }

                            // Glowing Line
                            drawPath(
                                path = path,
                                color = theme.primaryColor,
                                style = Stroke(width = 3f, cap = StrokeCap.Round)
                            )

                            // Points
                            for (pt in points) {
                                drawCircle(Color.White, radius = 4f, center = pt)
                                drawCircle(theme.primaryColor, radius = 7f, center = pt, style = Stroke(width = 1.8f))
                            }
                        }
                    }
                }
            }
        }

        // Labels row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            graphData.labels.forEachIndexed { i, label ->
                val value = graphData.values.getOrNull(i) ?: 0f
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = label,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "${value.toInt()}${graphData.unit}",
                        color = theme.primaryColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Step-by-step Interactive Timeline Card.
 */
@Composable
fun JarvisTimelineCardView(
    card: VisualCardData,
    theme: JarvisThemePreset,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null
) {
    JarvisCardFrame(
        title = card.title,
        theme = theme,
        confidence = card.confidence,
        modifier = modifier,
        onClose = onClose
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            card.timelineSteps.forEach { step ->
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Node indicator
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(
                                if (step.isCurrent) theme.primaryColor
                                else if (step.isCompleted) theme.secondaryColor
                                else Color.White.copy(alpha = 0.1f)
                            )
                    ) {
                        Text(
                            text = "${step.stepIndex}",
                            color = if (step.isCurrent) Color.Black else Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = step.title,
                            color = if (step.isCurrent) theme.primaryColor else Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (step.description.isNotBlank()) {
                            Text(
                                text = step.description,
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 3D Holographic Object Viewer Card with touch rotation.
 */
@Composable
fun Jarvis3DViewerCardView(
    card: VisualCardData,
    theme: JarvisThemePreset,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null
) {
    val initialMesh = card.mesh3d ?: WireframeMesh3D.createDiamond()
    var currentMesh by remember { mutableStateOf(initialMesh) }

    JarvisCardFrame(
        title = card.title,
        theme = theme,
        confidence = card.confidence,
        modifier = modifier,
        onClose = onClose
    ) {
        Text(
            text = "Holographic 3D Object • Drag to rotate in real-time",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )

        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .border(0.8.dp, theme.primaryColor.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
        ) {
            Interactive3DMeshViewer(
                mesh = currentMesh,
                theme = theme,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Preset switcher buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            FilterChip(
                selected = currentMesh.name.contains("Core"),
                onClick = { currentMesh = WireframeMesh3D.createDiamond() },
                label = { Text("Quantum Core", fontSize = 10.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = theme.primaryColor.copy(alpha = 0.3f),
                    selectedLabelColor = Color.White
                )
            )
            FilterChip(
                selected = currentMesh.name.contains("Cube"),
                onClick = { currentMesh = WireframeMesh3D.createCube() },
                label = { Text("Cube Matrix", fontSize = 10.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = theme.primaryColor.copy(alpha = 0.3f),
                    selectedLabelColor = Color.White
                )
            )
            FilterChip(
                selected = currentMesh.name.contains("Prism"),
                onClick = { currentMesh = WireframeMesh3D.createPyramid() },
                label = { Text("Prism", fontSize = 10.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = theme.primaryColor.copy(alpha = 0.3f),
                    selectedLabelColor = Color.White
                )
            )
        }
    }
}

/**
 * Universal dispatcher rendering the appropriate Card View for any VisualCardData.
 */
@Composable
fun JarvisExplanationCardDispatcher(
    card: VisualCardData,
    theme: JarvisThemePreset,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null
) {
    when (card.type) {
        VisualCardType.SUMMARY -> JarvisSummaryCardView(card, theme, modifier, onClose)
        VisualCardType.DETAIL_TYPEWRITER -> JarvisDetailTypewriterCardView(card, theme, modifier, onClose)
        VisualCardType.COMPARISON -> JarvisComparisonCardView(card, theme, modifier, onClose)
        VisualCardType.CANVAS_GRAPH -> JarvisCanvasGraphCardView(card, theme, modifier, onClose)
        VisualCardType.TIMELINE_FLOW -> JarvisTimelineCardView(card, theme, modifier, onClose)
        VisualCardType.OBJECT_3D -> Jarvis3DViewerCardView(card, theme, modifier, onClose)
    }
}
