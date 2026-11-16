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
    copyPayload: String = "",
    onReadAloud: ((String) -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    onMinimize: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var isExpanded by remember { mutableStateOf(true) }

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
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
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
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
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

                    if (onReadAloud != null && copyPayload.isNotBlank()) {
                        IconButton(
                            onClick = { onReadAloud(copyPayload) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.VolumeUp,
                                contentDescription = "Read aloud",
                                tint = theme.primaryColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    if (copyPayload.isNotBlank()) {
                        IconButton(
                            onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(copyPayload)) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy text",
                                tint = Color.White.copy(alpha = 0.75f),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "Collapse card" else "Expand card",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    if (onMinimize != null) {
                        IconButton(
                            onClick = onMinimize,
                            modifier = Modifier.size(28.dp)
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
                            modifier = Modifier.size(28.dp)
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

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    content()
                }
            }
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
        copyPayload = buildString {
            append(card.summary)
            if (card.bullets.isNotEmpty()) append("\n• " + card.bullets.joinToString("\n• "))
        },
        onReadAloud = JarvisGuidanceEngine.externalSpeakCallback,
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
        copyPayload = fullText,
        onReadAloud = JarvisGuidanceEngine.externalSpeakCallback,
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
        copyPayload = graphData.labels.zip(graphData.values).joinToString(", ") { "${it.first}: ${it.second.toInt()}${graphData.unit}" },
        onReadAloud = JarvisGuidanceEngine.externalSpeakCallback,
        modifier = modifier,
        onClose = onClose
    ) {
        val maxValue = graphData.values.maxOrNull() ?: 0f
        val totalValue = graphData.values.sum()

        if (maxValue <= 0f || graphData.values.isEmpty()) {
            Text(
                text = "All recorded values are 0${graphData.unit} — no non-zero metrics detected.",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(145.dp)
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
                                val barHeight = if (value <= 0f) 0f else (value / maxValue) * (h - 24f) * progress
                                val left = spacing + i * (barWidth + spacing)
                                val top = h - barHeight

                                if (barHeight > 0f) {
                                    drawRect(
                                        brush = Brush.verticalGradient(
                                            listOf(theme.primaryColor, theme.secondaryColor.copy(alpha = 0.4f)),
                                            startY = top,
                                            endY = h
                                        ),
                                        topLeft = Offset(left, top),
                                        size = Size(barWidth, barHeight)
                                    )
                                    drawLine(
                                        color = Color.White,
                                        start = Offset(left, top),
                                        end = Offset(left + barWidth, top),
                                        strokeWidth = 2.5f
                                    )
                                }
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

                                drawPath(
                                    path = path,
                                    color = theme.primaryColor,
                                    style = Stroke(width = 3f, cap = StrokeCap.Round)
                                )
                                for (pt in points) {
                                    drawCircle(Color.White, radius = 4f, center = pt)
                                    drawCircle(theme.primaryColor, radius = 7f, center = pt, style = Stroke(width = 1.8f))
                                }
                            }
                        }

                        GraphChartType.PIE_CHART, GraphChartType.DONUT_CHART -> {
                            val palette = listOf(
                                theme.primaryColor,
                                theme.secondaryColor,
                                Color(0xFF00E676),
                                Color(0xFFFFD700),
                                Color(0xFF7C4DFF)
                            )
                            val diameter = kotlin.math.min(w, h) * 0.85f
                            val topLeft = Offset((w - diameter) / 2f, (h - diameter) / 2f)
                            var startAngle = -90f
                            graphData.values.forEachIndexed { i, value ->
                                val sweep = if (totalValue > 0f) (value / totalValue) * 360f * progress else 0f
                                if (sweep > 0.5f) {
                                    drawArc(
                                        color = palette[i % palette.size],
                                        startAngle = startAngle,
                                        sweepAngle = sweep,
                                        useCenter = graphData.type == GraphChartType.PIE_CHART,
                                        topLeft = topLeft,
                                        size = Size(diameter, diameter),
                                        style = if (graphData.type == GraphChartType.DONUT_CHART) {
                                            Stroke(width = diameter * 0.24f)
                                        } else {
                                            androidx.compose.ui.graphics.drawscope.Fill
                                        }
                                    )
                                    startAngle += sweep
                                }
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
    var zoomScale by remember { mutableFloatStateOf(1.0f) }
    var focusedPartIndex by remember { mutableIntStateOf(-1) }
    var schematic2D by remember { mutableStateOf(false) }
    var resetTrigger by remember { mutableIntStateOf(0) }

    JarvisCardFrame(
        title = card.title,
        theme = theme,
        confidence = card.confidence,
        copyPayload = "${card.title}: ${card.summary}",
        onReadAloud = JarvisGuidanceEngine.externalSpeakCallback,
        modifier = modifier,
        onClose = onClose
    ) {
        Text(
            text = if (schematic2D) "2D Schematic Projection • Toggle 3D to orbit" else "Holographic 3D Object • Drag to rotate, Zoom, or focus part",
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
                zoomScale = zoomScale,
                focusedPartIndex = focusedPartIndex,
                schematic2DMode = schematic2D,
                resetTrigger = resetTrigger,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Zoom, Reset & 2D Schematic controls
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(
                onClick = { zoomScale = (zoomScale - 0.2f).coerceAtLeast(0.6f) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) { Text("- Zoom", fontSize = 10.sp, color = Color.White) }
            OutlinedButton(
                onClick = { zoomScale = (zoomScale + 0.2f).coerceAtMost(2.2f) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) { Text("+ Zoom", fontSize = 10.sp, color = Color.White) }
            OutlinedButton(
                onClick = {
                    zoomScale = 1.0f
                    focusedPartIndex = -1
                    schematic2D = false
                    resetTrigger++
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) { Text("Reset", fontSize = 10.sp, color = theme.primaryColor) }
            OutlinedButton(
                onClick = { schematic2D = !schematic2D },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) { Text(if (schematic2D) "3D Mode" else "2D View", fontSize = 10.sp, color = theme.primaryColor) }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Preset switcher + Part focus chips
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                FilterChip(
                    selected = currentMesh.name.contains("Core"),
                    onClick = { currentMesh = WireframeMesh3D.createDiamond(); focusedPartIndex = -1 },
                    label = { Text("Quantum Core", fontSize = 10.sp) }
                )
            }
            item {
                FilterChip(
                    selected = currentMesh.name.contains("Cube"),
                    onClick = { currentMesh = WireframeMesh3D.createCube(); focusedPartIndex = -1 },
                    label = { Text("Cube Matrix", fontSize = 10.sp) }
                )
            }
            item {
                FilterChip(
                    selected = currentMesh.name.contains("Prism"),
                    onClick = { currentMesh = WireframeMesh3D.createPyramid(); focusedPartIndex = -1 },
                    label = { Text("Prism", fontSize = 10.sp) }
                )
            }
            items(currentMesh.parts.size) { partIdx ->
                FilterChip(
                    selected = focusedPartIndex == partIdx,
                    onClick = { focusedPartIndex = if (focusedPartIndex == partIdx) -1 else partIdx },
                    label = { Text("Focus: ${currentMesh.parts[partIdx]}", fontSize = 10.sp) }
                )
            }
        }
    }
}

/**
 * Interactive Flowchart & System Architecture DAG Card.
 */
@Composable
fun JarvisFlowchartCardView(
    card: VisualCardData,
    theme: JarvisThemePreset,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null
) {
    val nodes = card.flowchartNodes.ifEmpty {
        listOf(
            FlowchartNode("capture", "Screen Capture", "INPUT"),
            FlowchartNode("ocr", "ML Kit OCR + ONNX", "FUSION"),
            FlowchartNode("reason", "VLM / Gemini Plan", "AI"),
            FlowchartNode("render", "Overlay Guide", "OUTPUT")
        )
    }

    JarvisCardFrame(
        title = card.title,
        theme = theme,
        confidence = card.confidence,
        copyPayload = "${card.title}: ${nodes.joinToString(" -> ") { it.label }}",
        onReadAloud = JarvisGuidanceEngine.externalSpeakCallback,
        modifier = modifier,
        onClose = onClose
    ) {
        Text(
            text = card.summary,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            nodes.forEachIndexed { idx, node ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.White.copy(alpha = 0.05f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, theme.primaryColor.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(node.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Surface(shape = RoundedCornerShape(6.dp), color = theme.primaryColor.copy(alpha = 0.2f)) {
                            Text(
                                text = node.role,
                                color = theme.primaryColor,
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                if (idx < nodes.lastIndex) {
                    Box(modifier = Modifier.fillMaxWidth().height(16.dp), contentAlignment = Alignment.Center) {
                        Canvas(modifier = Modifier.size(16.dp)) {
                            drawLine(
                                color = theme.primaryColor,
                                start = Offset(size.width / 2f, 0f),
                                end = Offset(size.width / 2f, size.height),
                                strokeWidth = 2.5f
                            )
                        }
                    }
                }
            }
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
        VisualCardType.FLOWCHART -> JarvisFlowchartCardView(card, theme, modifier, onClose)
    }
}
