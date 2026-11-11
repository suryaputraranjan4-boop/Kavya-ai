package com.example.visual

import android.graphics.Rect
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * High-performance 60fps Native Drawing Engine for Futuristic JARVIS UI Overlays.
 * Handles glowing bounding boxes, curved connectors, animated holographic pointers,
 * scanlines, and 3D wireframe models on Compose Canvas.
 */
object JarvisDrawingEngine {

    /**
     * Draws a futuristic high-tech glowing bounding box around a target rect.
     */
    fun drawGlowingReticleBox(
        drawScope: DrawScope,
        bounds: Rect,
        theme: JarvisThemePreset,
        pulseProgress: Float,
        scanlineProgress: Float,
        label: String = ""
    ) {
        val left = bounds.left.toFloat()
        val top = bounds.top.toFloat()
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        val right = left + width
        val bottom = top + height

        // 1. Ambient outer glow border
        val glowAlpha = 0.25f + 0.25f * sin(pulseProgress * 2f * PI.toFloat())
        drawScope.drawRect(
            color = theme.glowColor.copy(alpha = glowAlpha),
            topLeft = Offset(left - 4f, top - 4f),
            size = Size(width + 8f, height + 8f),
            style = Stroke(width = 6f)
        )

        // 2. Inner crisp neon border
        drawScope.drawRect(
            color = theme.primaryColor.copy(alpha = 0.85f),
            topLeft = Offset(left, top),
            size = Size(width, height),
            style = Stroke(width = 2.5f)
        )

        // 3. Cyberpunk corner brackets (L-shapes)
        val cornerLen = (width.coerceAtMost(height) * 0.25f).coerceIn(12f, 32f)
        val cornerStroke = Stroke(width = 4f, cap = StrokeCap.Square)
        val cornerColor = theme.primaryColor

        // Top-Left
        drawScope.drawLine(cornerColor, Offset(left - 4f, top - 4f), Offset(left - 4f + cornerLen, top - 4f), strokeWidth = cornerStroke.width)
        drawScope.drawLine(cornerColor, Offset(left - 4f, top - 4f), Offset(left - 4f, top - 4f + cornerLen), strokeWidth = cornerStroke.width)

        // Top-Right
        drawScope.drawLine(cornerColor, Offset(right + 4f, top - 4f), Offset(right + 4f - cornerLen, top - 4f), strokeWidth = cornerStroke.width)
        drawScope.drawLine(cornerColor, Offset(right + 4f, top - 4f), Offset(right + 4f, top - 4f + cornerLen), strokeWidth = cornerStroke.width)

        // Bottom-Left
        drawScope.drawLine(cornerColor, Offset(left - 4f, bottom + 4f), Offset(left - 4f + cornerLen, bottom + 4f), strokeWidth = cornerStroke.width)
        drawScope.drawLine(cornerColor, Offset(left - 4f, bottom + 4f), Offset(left - 4f, bottom + 4f - cornerLen), strokeWidth = cornerStroke.width)

        // Bottom-Right
        drawScope.drawLine(cornerColor, Offset(right + 4f, bottom + 4f), Offset(right + 4f - cornerLen, bottom + 4f), strokeWidth = cornerStroke.width)
        drawScope.drawLine(cornerColor, Offset(right + 4f, bottom + 4f), Offset(right + 4f, bottom + 4f - cornerLen), strokeWidth = cornerStroke.width)

        // 4. Animated scanline sweep inside the target rect
        val scanY = top + height * scanlineProgress
        drawScope.drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    theme.primaryColor.copy(alpha = 0.7f),
                    Color.White.copy(alpha = 0.9f),
                    theme.primaryColor.copy(alpha = 0.7f),
                    Color.Transparent
                ),
                startX = left,
                endX = right
            ),
            start = Offset(left, scanY),
            end = Offset(right, scanY),
            strokeWidth = 2.5f
        )
    }

    /**
     * Draws an animated pulsing circle / reticle around a target point.
     */
    fun drawGlowingReticleCircle(
        drawScope: DrawScope,
        center: Offset,
        radius: Float,
        theme: JarvisThemePreset,
        pulseProgress: Float
    ) {
        // Expanding halo
        val expandingRadius = radius * (1f + 0.35f * pulseProgress)
        val fadeAlpha = (1f - pulseProgress).coerceIn(0f, 1f) * 0.6f

        drawScope.drawCircle(
            color = theme.primaryColor.copy(alpha = fadeAlpha),
            radius = expandingRadius,
            center = center,
            style = Stroke(width = 2.5f)
        )

        // Inner solid core ring
        drawScope.drawCircle(
            color = theme.primaryColor,
            radius = radius,
            center = center,
            style = Stroke(width = 3f)
        )

        // Center dot
        drawScope.drawCircle(
            color = Color.White,
            radius = 4f,
            center = center
        )
    }

    /**
     * Draws a smooth curved Bezier connector line from a card anchor to a target element
     * with animated energy particles flowing along the path.
     */
    fun drawCurvedConnector(
        drawScope: DrawScope,
        start: Offset,
        end: Offset,
        theme: JarvisThemePreset,
        flowProgress: Float
    ) {
        val dx = end.x - start.x
        val dy = end.y - start.y

        // Control points for cubic bezier to produce organic high-tech curve
        val control1 = Offset(start.x + dx * 0.25f, start.y + dy * 0.05f)
        val control2 = Offset(start.x + dx * 0.75f, start.y + dy * 0.95f)

        val path = Path().apply {
            moveTo(start.x, start.y)
            cubicTo(control1.x, control1.y, control2.x, control2.y, end.x, end.y)
        }

        // Draw background subtle neon glow line
        drawScope.drawPath(
            path = path,
            color = theme.glowColor,
            style = Stroke(width = 5f, cap = StrokeCap.Round)
        )

        // Draw crisp core curve
        drawScope.drawPath(
            path = path,
            brush = Brush.linearGradient(
                colors = listOf(theme.secondaryColor, theme.primaryColor),
                start = start,
                end = end
            ),
            style = Stroke(width = 2f, cap = StrokeCap.Round)
        )

        // Calculate position of traveling energy pulse along the cubic bezier curve:
        // B(t) = (1-t)^3 * P0 + 3(1-t)^2 * t * P1 + 3(1-t) * t^2 * P2 + t^3 * P3
        val t = flowProgress.coerceIn(0f, 1f)
        val u = 1f - t
        val px = u * u * u * start.x + 3 * u * u * t * control1.x + 3 * u * t * t * control2.x + t * t * t * end.x
        val py = u * u * u * start.y + 3 * u * u * t * control1.y + 3 * u * t * t * control2.y + t * t * t * end.y
        val pulsePos = Offset(px, py)

        // Traveling glowing photon dot
        drawScope.drawCircle(
            color = Color.White,
            radius = 4.5f,
            center = pulsePos
        )
        drawScope.drawCircle(
            color = theme.primaryColor,
            radius = 8.5f,
            center = pulsePos,
            style = Stroke(width = 2f)
        )

        // Small anchor dot at start and arrowhead at end
        drawScope.drawCircle(
            color = theme.secondaryColor,
            radius = 3.5f,
            center = start
        )

        // Arrowhead at destination
        val angle = atan2(end.y - py, end.x - px)
        val arrowLen = 14f
        val a1 = Offset(
            end.x - arrowLen * cos(angle - 0.45f),
            end.y - arrowLen * sin(angle - 0.45f)
        )
        val a2 = Offset(
            end.x - arrowLen * cos(angle + 0.45f),
            end.y - arrowLen * sin(angle + 0.45f)
        )
        drawScope.drawLine(theme.primaryColor, end, a1, strokeWidth = 3f, cap = StrokeCap.Round)
        drawScope.drawLine(theme.primaryColor, end, a2, strokeWidth = 3f, cap = StrokeCap.Round)
    }

    /**
     * Draws animated futuristic cursors (Neon Arrow, Holographic Reticle, Robotic Hand, Pen, Minimal Glow).
     */
    fun drawAnimatedCursor(
        drawScope: DrawScope,
        position: Offset,
        style: CursorStyle,
        theme: JarvisThemePreset,
        rotationDeg: Float,
        pulseProgress: Float
    ) {
        when (style) {
            CursorStyle.HOLOGRAPHIC_POINTER -> {
                // Concentric rotating rings with crosshair tick marks
                val r1 = 22f
                val r2 = 32f

                drawScope.drawCircle(
                    color = theme.primaryColor.copy(alpha = 0.85f),
                    radius = r1,
                    center = position,
                    style = Stroke(width = 2f)
                )

                drawScope.drawCircle(
                    color = theme.secondaryColor.copy(alpha = 0.5f),
                    radius = r2,
                    center = position,
                    style = Stroke(width = 1.5f)
                )

                // Crosshairs
                val tick = 10f
                drawScope.drawLine(theme.primaryColor, Offset(position.x - r2 - tick, position.y), Offset(position.x - r1, position.y), strokeWidth = 2f)
                drawScope.drawLine(theme.primaryColor, Offset(position.x + r1, position.y), Offset(position.x + r2 + tick, position.y), strokeWidth = 2f)
                drawScope.drawLine(theme.primaryColor, Offset(position.x, position.y - r2 - tick), Offset(position.x, position.y - r1), strokeWidth = 2f)
                drawScope.drawLine(theme.primaryColor, Offset(position.x, position.y + r1), Offset(position.x, position.y + r2 + tick), strokeWidth = 2f)

                // Central pinpoint
                drawScope.drawCircle(Color.White, radius = 3.5f, center = position)
            }

            CursorStyle.NEON_ARROW -> {
                val path = Path().apply {
                    moveTo(position.x, position.y)
                    lineTo(position.x + 20f, position.y + 26f)
                    lineTo(position.x + 8f, position.y + 24f)
                    lineTo(position.x + 2f, position.y + 36f)
                    lineTo(position.x - 5f, position.y + 34f)
                    lineTo(position.x + 2f, position.y + 22f)
                    lineTo(position.x - 12f, position.y + 18f)
                    close()
                }

                drawScope.drawPath(path, color = theme.glowColor)
                drawScope.drawPath(path, color = theme.primaryColor, style = Stroke(width = 2f))
                drawScope.drawCircle(Color.White, radius = 3f, center = position)
            }

            CursorStyle.ROBOTIC_HAND -> {
                // Stylized cybernetic index pointer
                drawScope.drawLine(
                    color = theme.primaryColor,
                    start = position,
                    end = Offset(position.x + 24f, position.y + 28f),
                    strokeWidth = 5f,
                    cap = StrokeCap.Round
                )
                drawScope.drawCircle(theme.secondaryColor, radius = 7f, center = Offset(position.x + 24f, position.y + 28f))
                drawScope.drawCircle(Color.White, radius = 4f, center = position)
            }

            CursorStyle.PEN -> {
                // High-tech laser stylus
                drawScope.drawLine(
                    brush = Brush.linearGradient(listOf(Color.White, theme.primaryColor, theme.secondaryColor)),
                    start = position,
                    end = Offset(position.x + 28f, position.y - 32f),
                    strokeWidth = 4f,
                    cap = StrokeCap.Round
                )
                drawScope.drawCircle(Color.White, radius = 3.5f, center = position)
            }

            CursorStyle.MINIMAL_GLOW -> {
                val glowR = 14f + 8f * pulseProgress
                drawScope.drawCircle(theme.glowColor, radius = glowR, center = position)
                drawScope.drawCircle(theme.primaryColor, radius = 8f, center = position)
                drawScope.drawCircle(Color.White, radius = 3f, center = position)
            }
        }
    }

    /**
     * Interactive 3D Wireframe Canvas renderer.
     * Projects 3D vertices using Euler rotation and draws connected wireframe lines
     * with depth illumination.
     */
    fun drawWireframeMesh3D(
        drawScope: DrawScope,
        mesh: WireframeMesh3D,
        rotX: Float,
        rotY: Float,
        rotZ: Float,
        theme: JarvisThemePreset,
        center: Offset,
        scale: Float = 1.0f
    ) {
        val width = drawScope.size.width
        val height = drawScope.size.height

        // 1. Rotate and project all vertices
        val projected = mesh.vertices.map { v ->
            val rotated = v.rotateX(rotX).rotateY(rotY).rotateZ(rotZ)
            val p = rotated.project(width, height, fov = 340f * scale)
            // Center relative to offset
            val screenPos = Offset(p.x - width / 2f + center.x, p.y - height / 2f + center.y)
            Pair(screenPos, rotated.z)
        }

        // 2. Draw wireframe edges
        for (edge in mesh.edges) {
            if (edge.startIdx in projected.indices && edge.endIdx in projected.indices) {
                val (p1, z1) = projected[edge.startIdx]
                val (p2, z2) = projected[edge.endIdx]

                // Depth fading (edges closer have higher alpha)
                val avgZ = (z1 + z2) / 2f
                val depthAlpha = ((avgZ + 1.5f) / 3.0f).coerceIn(0.25f, 0.95f)

                drawScope.drawLine(
                    color = theme.primaryColor.copy(alpha = depthAlpha),
                    start = p1,
                    end = p2,
                    strokeWidth = 2.2f,
                    cap = StrokeCap.Round
                )
            }
        }

        // 3. Draw glowing vertex nodes
        for ((point, z) in projected) {
            val depthAlpha = ((z + 1.5f) / 3.0f).coerceIn(0.3f, 1.0f)
            drawScope.drawCircle(
                color = theme.primaryColor.copy(alpha = depthAlpha * 0.5f),
                radius = 5f,
                center = point
            )
            drawScope.drawCircle(
                color = Color.White.copy(alpha = depthAlpha),
                radius = 2.5f,
                center = point
            )
        }
    }
}

/**
 * Interactive 3D Mesh Viewer Composable supporting touch drag rotation.
 */
@Composable
fun Interactive3DMeshViewer(
    mesh: WireframeMesh3D,
    theme: JarvisThemePreset,
    modifier: Modifier = Modifier,
    initialRotX: Float = 0.35f,
    initialRotY: Float = 0.55f
) {
    var rotX by remember { mutableFloatStateOf(initialRotX) }
    var rotY by remember { mutableFloatStateOf(initialRotY) }

    val infiniteTransition = rememberInfiniteTransition(label = "MeshAmbientSpin")
    val autoSpin by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "AutoSpin"
    )

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    rotY += dragAmount.x * 0.012f
                    rotX -= dragAmount.y * 0.012f
                }
            }
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        JarvisDrawingEngine.drawWireframeMesh3D(
            drawScope = this,
            mesh = mesh,
            rotX = rotX,
            rotY = rotY + autoSpin * 0.25f,
            rotZ = 0f,
            theme = theme,
            center = center,
            scale = 1.0f
        )
    }
}
