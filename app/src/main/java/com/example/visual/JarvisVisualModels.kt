package com.example.visual

import android.graphics.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * Core modes for JARVIS visual intelligence system.
 */
enum class JarvisMode {
    IDLE,
    EXPLAINS_ANYTHING,
    SHOWS_YOU_HOW,
    DO_IT
}

/**
 * Animated states of the JARVIS Orb.
 */
enum class JarvisOrbState {
    IDLE,
    LISTENING,
    THINKING,
    PROCESSING,
    SPEAKING,
    WORKING,
    EXPLAINING,
    GUIDING,
    CONNECTING,
    LOADING,
    SCREEN_SHARE_ACTIVE,
    SUCCESS,
    ERROR
}

/**
 * Transient motion states for tactile drag, dock, expand, and collapse feedback.
 */
enum class OrbTransientMotion {
    NONE,
    EXPANDING,
    COLLAPSING,
    DRAGGING,
    DOCKING
}

/**
 * Honest WindowManager attachment lifecycle for the Floating Orb.
 */
enum class OverlayAttachmentState {
    STOPPED,
    STARTING,
    ATTACHED_VISIBLE,
    PERMISSION_MISSING,
    ERROR
}

/**
 * Selectable animated cursor styles for screen guidance.
 */
enum class CursorStyle {
    NEON_ARROW,
    HOLOGRAPHIC_POINTER,
    ROBOTIC_HAND,
    PEN,
    MINIMAL_GLOW
}

/**
 * Color themes for the JARVIS interface and orb.
 */
enum class JarvisThemePreset(
    val displayName: String,
    val primaryColor: Color,
    val secondaryColor: Color,
    val glowColor: Color,
    val surfaceColor: Color
) {
    NEON_CYAN(
        displayName = "Neon Cyan",
        primaryColor = Color(0xFF00F5FF),
        secondaryColor = Color(0xFF0083B0),
        glowColor = Color(0x6600F5FF),
        surfaceColor = Color(0xF00A1118)
    ),
    ELECTRIC_BLUE(
        displayName = "Electric Blue",
        primaryColor = Color(0xFF2979FF),
        secondaryColor = Color(0xFF7C4DFF),
        glowColor = Color(0x662979FF),
        surfaceColor = Color(0xF00D1326)
    ),
    AMBER_GOLD(
        displayName = "Amber Gold",
        primaryColor = Color(0xFFFFD700),
        secondaryColor = Color(0xFFFF6F00),
        glowColor = Color(0x66FFD700),
        surfaceColor = Color(0xF01A1608)
    ),
    HOLOGRAPHIC_VIOLET(
        displayName = "Holographic Violet",
        primaryColor = Color(0xFFD500F9),
        secondaryColor = Color(0xFFFF4081),
        glowColor = Color(0x66D500F9),
        surfaceColor = Color(0xF0180B22)
    ),
    EMERALD_MATRIX(
        displayName = "Emerald Matrix",
        primaryColor = Color(0xFF00E676),
        secondaryColor = Color(0xFF00B0FF),
        glowColor = Color(0x6600E676),
        surfaceColor = Color(0xF006180E)
    )
}

/**
 * Customization configuration for the floating JARVIS Orb.
 */
data class JarvisOrbConfig(
    val sizeDp: Int = 84,
    val theme: JarvisThemePreset = JarvisThemePreset.NEON_CYAN,
    val glowIntensity: Float = 0.85f,
    val speedMultiplier: Float = 1.0f,
    val transparency: Float = 0.92f,
    val soundReactive: Boolean = true,
    val snapToEdge: Boolean = true,
    val showStateBadge: Boolean = true,
    val reducedMotion: Boolean = false,
    val cursorStyle: CursorStyle = CursorStyle.HOLOGRAPHIC_POINTER
)

/**
 * 3D Point in coordinate space.
 */
data class Point3D(val x: Float, val y: Float, val z: Float) {
    fun rotateX(angleRad: Float): Point3D {
        val cosA = cos(angleRad)
        val sinA = sin(angleRad)
        return Point3D(
            x = x,
            y = y * cosA - z * sinA,
            z = y * sinA + z * cosA
        )
    }

    fun rotateY(angleRad: Float): Point3D {
        val cosA = cos(angleRad)
        val sinA = sin(angleRad)
        return Point3D(
            x = x * cosA + z * sinA,
            y = y,
            z = -x * sinA + z * cosA
        )
    }

    fun rotateZ(angleRad: Float): Point3D {
        val cosA = cos(angleRad)
        val sinA = sin(angleRad)
        return Point3D(
            x = x * cosA - y * sinA,
            y = x * sinA + y * cosA,
            z = z
        )
    }

    /**
     * Projects 3D point to 2D screen coordinate using perspective projection.
     */
    fun project(
        screenWidth: Float,
        screenHeight: Float,
        fov: Float = 360f,
        viewDistance: Float = 3.5f
    ): Offset {
        val distance = (z + viewDistance).coerceAtLeast(0.1f)
        val factor = fov / distance
        val screenX = screenWidth / 2f + x * factor
        val screenY = screenHeight / 2f - y * factor
        return Offset(screenX, screenY)
    }
}

/**
 * Edge connecting two vertex indices.
 */
data class Edge3D(val startIdx: Int, val endIdx: Int)

/**
 * 3D Wireframe mesh model for interactive holographic rendering.
 */
data class WireframeMesh3D(
    val name: String,
    val vertices: List<Point3D>,
    val edges: List<Edge3D>
) {
    companion object {
        fun createCube(size: Float = 1.0f): WireframeMesh3D {
            val h = size / 2f
            val vertices = listOf(
                Point3D(-h, -h, -h), // 0
                Point3D(h, -h, -h),  // 1
                Point3D(h, h, -h),   // 2
                Point3D(-h, h, -h),  // 3
                Point3D(-h, -h, h),  // 4
                Point3D(h, -h, h),   // 5
                Point3D(h, h, h),    // 6
                Point3D(-h, h, h)    // 7
            )
            val edges = listOf(
                Edge3D(0, 1), Edge3D(1, 2), Edge3D(2, 3), Edge3D(3, 0),
                Edge3D(4, 5), Edge3D(5, 6), Edge3D(6, 7), Edge3D(7, 4),
                Edge3D(0, 4), Edge3D(1, 5), Edge3D(2, 6), Edge3D(3, 7)
            )
            return WireframeMesh3D("Holographic Cube", vertices, edges)
        }

        fun createDiamond(size: Float = 1.2f): WireframeMesh3D {
            val h = size / 2f
            val vertices = listOf(
                Point3D(0f, size * 0.8f, 0f),   // top apex 0
                Point3D(-h, 0f, -h),            // 1
                Point3D(h, 0f, -h),             // 2
                Point3D(h, 0f, h),              // 3
                Point3D(-h, 0f, h),             // 4
                Point3D(0f, -size * 0.8f, 0f)   // bottom apex 5
            )
            val edges = listOf(
                Edge3D(0, 1), Edge3D(0, 2), Edge3D(0, 3), Edge3D(0, 4),
                Edge3D(1, 2), Edge3D(2, 3), Edge3D(3, 4), Edge3D(4, 1),
                Edge3D(5, 1), Edge3D(5, 2), Edge3D(5, 3), Edge3D(5, 4)
            )
            return WireframeMesh3D("AI Quantum Core", vertices, edges)
        }

        fun createPyramid(size: Float = 1.1f): WireframeMesh3D {
            val h = size / 2f
            val vertices = listOf(
                Point3D(0f, size * 0.7f, 0f), // Apex 0
                Point3D(-h, -h, -h),          // 1
                Point3D(h, -h, -h),           // 2
                Point3D(h, -h, h),            // 3
                Point3D(-h, -h, h)            // 4
            )
            val edges = listOf(
                Edge3D(0, 1), Edge3D(0, 2), Edge3D(0, 3), Edge3D(0, 4),
                Edge3D(1, 2), Edge3D(2, 3), Edge3D(3, 4), Edge3D(4, 1)
            )
            return WireframeMesh3D("Data Prism", vertices, edges)
        }
    }
}

/**
 * Types of interactive and animated explanation cards.
 */
enum class VisualCardType {
    SUMMARY,
    DETAIL_TYPEWRITER,
    COMPARISON,
    CANVAS_GRAPH,
    TIMELINE_FLOW,
    OBJECT_3D
}

/**
 * Chart representation types for Canvas rendering.
 */
enum class GraphChartType {
    BAR_CHART,
    LINE_CHART
}

/**
 * Dynamic data for animated Canvas graph card.
 */
data class GraphData(
    val title: String,
    val type: GraphChartType = GraphChartType.BAR_CHART,
    val labels: List<String>,
    val values: List<Float>,
    val unit: String = ""
)

/**
 * Comparison entry for comparison card.
 */
data class ComparisonItem(
    val feature: String,
    val optionA: String,
    val optionB: String,
    val winner: String = ""
)

/**
 * Sequential step entry for timeline cards.
 */
data class TimelineStep(
    val stepIndex: Int,
    val title: String,
    val description: String,
    val isCompleted: Boolean = false,
    val isCurrent: Boolean = false
)

/**
 * Rich model for animated visual explanation cards.
 */
data class VisualCardData(
    val id: String = UUID.randomUUID().toString(),
    val type: VisualCardType = VisualCardType.SUMMARY,
    val title: String,
    val summary: String,
    val bullets: List<String> = emptyList(),
    val detailText: String = "",
    val comparisonItems: List<ComparisonItem> = emptyList(),
    val graphData: GraphData? = null,
    val timelineSteps: List<TimelineStep> = emptyList(),
    val mesh3d: WireframeMesh3D? = null,
    val citations: List<String> = emptyList(),
    val targetAnchorBounds: Rect? = null,
    val confidence: Float = 0.95f
)

/**
 * Granular visual instruction executed deterministically on the Canvas.
 */
enum class VisualInstructionType {
    HIGHLIGHT_BOX,
    DRAW_CIRCLE,
    DRAW_CONNECTOR_ARROW,
    MOVE_CURSOR,
    PULSE_RETICLE,
    SHOW_CARD,
    TYPEWRITER_TEXT,
    RENDER_GRAPH,
    RENDER_3D_OBJECT,
    WAIT_USER_TAP,
    VERIFY_SCREEN_STATE,
    CLEAR_HIGHLIGHTS
}

/**
 * Concrete visual instruction executed by the drawing engine.
 */
data class VisualInstruction(
    val id: String = UUID.randomUUID().toString(),
    val type: VisualInstructionType,
    val bounds: Rect? = null,
    val clickPoint: Pair<Int, Int>? = null,
    val label: String = "",
    val text: String = "",
    val cardData: VisualCardData? = null,
    val durationMs: Long = 1200L,
    val isPassThroughTouch: Boolean = true,
    val expectedNextScreenHint: String = ""
)

/**
 * Unified plan created by the AI or rule-based analyzer and handed to the local rendering engine.
 */
data class JarvisVisualPlan(
    val id: String = UUID.randomUUID().toString(),
    val userQuery: String,
    val mode: JarvisMode,
    val title: String,
    val instructions: List<VisualInstruction> = emptyList(),
    val cards: List<VisualCardData> = emptyList(),
    val fullScreenExplainerEnabled: Boolean = false,
    val targetPackage: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
