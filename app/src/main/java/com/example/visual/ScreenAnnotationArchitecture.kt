package com.example.visual

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Single step in a visual screen explanation or interactive guidance flow.
 */
data class ScreenAnnotationStep(
    val id: String = UUID.randomUUID().toString(),
    val stepNumber: Int,
    val totalSteps: Int,
    val title: String,
    val label: String,
    val spokenExplanation: String,
    val bounds: Rect,
    val clickPoint: Pair<Int, Int> = Pair(bounds.centerX(), bounds.centerY()),
    val showArrow: Boolean = true,
    val showPointer: Boolean = true,
    val dimUnrelatedAreas: Boolean = true
)

/**
 * 1. ScreenCoordinateMapper
 * Maps coordinates accurately across Accessibility bounds, resized MediaProjection/Screenshot bitmaps,
 * normalized AI Vision coordinates (0..1 or 0..1000), and physical overlay display pixels.
 * Accounts for portrait/landscape orientation, rotation, status bars, navigation bars, and display cutouts.
 */
class ScreenCoordinateMapper(private val context: Context? = null) {

    data class DisplayGeometry(
        val width: Int,
        val height: Int,
        val density: Float,
        val rotation: Int,
        val isLandscape: Boolean,
        val statusBarInsetTop: Int,
        val navBarInsetBottom: Int,
        val navBarInsetLeft: Int,
        val navBarInsetRight: Int
    )

    @Volatile
    private var cachedGeometry: DisplayGeometry? = null

    @Volatile
    var capturedFrameWidth: Int = 0
        private set

    @Volatile
    var capturedFrameHeight: Int = 0
        private set

    fun updateCapturedFrameGeometry(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            capturedFrameWidth = width
            capturedFrameHeight = height
        }
    }

    fun refreshGeometry(overrideContext: Context? = context): DisplayGeometry {
        val ctx = overrideContext ?: context
        if (ctx == null) {
            return cachedGeometry ?: DisplayGeometry(
                width = 1080,
                height = 2400,
                density = 2.75f,
                rotation = Surface.ROTATION_0,
                isLandscape = false,
                statusBarInsetTop = 72,
                navBarInsetBottom = 120,
                navBarInsetLeft = 0,
                navBarInsetRight = 0
            )
        }

        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val metrics = DisplayMetrics()
        var width = 1080
        var height = 2400
        var rotation = Surface.ROTATION_0
        var statusTop = 64
        var navBottom = 96
        var navLeft = 0
        var navRight = 0

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
                val windowMetrics = wm.currentWindowMetrics
                val bounds = windowMetrics.bounds
                if (bounds.width() > 0 && bounds.height() > 0) {
                    width = bounds.width()
                    height = bounds.height()
                }
                val insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout()
                )
                statusTop = insets.top
                navBottom = insets.bottom
                navLeft = insets.left
                navRight = insets.right
            } else if (wm != null) {
                @Suppress("DEPRECATION")
                val display = wm.defaultDisplay
                @Suppress("DEPRECATION")
                display?.getRealMetrics(metrics)
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    width = metrics.widthPixels
                    height = metrics.heightPixels
                }
                @Suppress("DEPRECATION")
                rotation = display?.rotation ?: Surface.ROTATION_0
            }
        } catch (e: Exception) {
            Log.w("ScreenCoordinateMapper", "Fallback display geometry used: ${e.message}")
        }

        val res = ctx.resources
        val density = res?.displayMetrics?.density?.takeIf { it > 0f } ?: 2.75f
        if (statusTop == 0) {
            val resId = res?.getIdentifier("status_bar_height", "dimen", "android") ?: 0
            if (resId > 0) statusTop = res.getDimensionPixelSize(resId)
        }

        val geom = DisplayGeometry(
            width = width.coerceAtLeast(320),
            height = height.coerceAtLeast(480),
            density = density,
            rotation = rotation,
            isLandscape = width > height,
            statusBarInsetTop = statusTop,
            navBarInsetBottom = navBottom,
            navBarInsetLeft = navLeft,
            navBarInsetRight = navRight
        )
        cachedGeometry = geom
        return geom
    }

    fun getGeometry(): DisplayGeometry = cachedGeometry ?: refreshGeometry()

    /**
     * Maps normalized bounding box [ymin, xmin, ymax, xmax] (either 0..1 float or 0..1000 Gemini scale)
     * into exact physical screen pixel Rect.
     */
    fun mapNormalizedBoxToScreen(
        ymin: Float,
        xmin: Float,
        ymax: Float,
        xmax: Float,
        screenWidth: Int = getGeometry().width,
        screenHeight: Int = getGeometry().height
    ): Rect {
        val scale = if (ymin > 1.01f || xmin > 1.01f || ymax > 1.01f || xmax > 1.01f) 1000f else 1f
        val normLeft = (xmin / scale).coerceIn(0f, 1f)
        val normTop = (ymin / scale).coerceIn(0f, 1f)
        val normRight = (xmax / scale).coerceIn(0f, 1f)
        val normBottom = (ymax / scale).coerceIn(0f, 1f)

        val left = (normLeft * screenWidth).roundToInt().coerceIn(0, screenWidth - 1)
        val top = (normTop * screenHeight).roundToInt().coerceIn(0, screenHeight - 1)
        val right = (normRight * screenWidth).roundToInt().coerceIn((left + 24).coerceAtMost(screenWidth), screenWidth)
        val bottom = (normBottom * screenHeight).roundToInt().coerceIn((top + 24).coerceAtMost(screenHeight), screenHeight)

        return Rect(left, top, right, bottom)
    }

    /**
     * Maps a normalized point (0..1) into physical screen pixel Rect of given radius.
     */
    fun mapNormalizedPointToBounds(
        normX: Float,
        normY: Float,
        boxHalfWidthPx: Int = 90,
        boxHalfHeightPx: Int = 52,
        screenWidth: Int = getGeometry().width,
        screenHeight: Int = getGeometry().height
    ): Rect {
        val scale = if (normX > 1.01f || normY > 1.01f) 1000f else 1f
        val cx = ((normX / scale).coerceIn(0.02f, 0.98f) * screenWidth).roundToInt()
        val cy = ((normY / scale).coerceIn(0.02f, 0.98f) * screenHeight).roundToInt()
        return Rect(
            (cx - boxHalfWidthPx).coerceAtLeast(8),
            (cy - boxHalfHeightPx).coerceAtLeast(8),
            (cx + boxHalfWidthPx).coerceAtMost(screenWidth - 8),
            (cy + boxHalfHeightPx).coerceAtMost(screenHeight - 8)
        )
    }

    /**
     * Maps coordinates from a scaled/downsampled screenshot bitmap to physical screen pixels.
     */
    fun mapBitmapRectToScreen(
        bitmapRect: Rect,
        bitmapWidth: Int,
        bitmapHeight: Int,
        screenWidth: Int = getGeometry().width,
        screenHeight: Int = getGeometry().height
    ): Rect {
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return clampBoundsToScreen(bitmapRect, screenWidth, screenHeight)
        val scaleX = screenWidth.toFloat() / bitmapWidth.toFloat()
        val scaleY = screenHeight.toFloat() / bitmapHeight.toFloat()
        return clampBoundsToScreen(
            Rect(
                (bitmapRect.left * scaleX).roundToInt(),
                (bitmapRect.top * scaleY).roundToInt(),
                (bitmapRect.right * scaleX).roundToInt(),
                (bitmapRect.bottom * scaleY).roundToInt()
            ),
            screenWidth,
            screenHeight
        )
    }

    /**
     * Maps a single (x, y) point from a resized screenshot or MediaProjection frame to physical screen pixels.
     */
    fun mapBitmapPointToScreen(
        bitmapX: Float,
        bitmapY: Float,
        bitmapWidth: Int,
        bitmapHeight: Int,
        context: Context? = null
    ): Pair<Int, Int> {
        val geom = if (context != null) refreshGeometry(context) else getGeometry()
        val scaleX = geom.width.toFloat() / bitmapWidth.coerceAtLeast(1)
        val scaleY = geom.height.toFloat() / bitmapHeight.coerceAtLeast(1)
        val px = (bitmapX * scaleX).roundToInt().coerceIn(1, (geom.width - 1).coerceAtLeast(1))
        val py = (bitmapY * scaleY).roundToInt().coerceIn(1, (geom.height - 1).coerceAtLeast(1))
        return Pair(px, py)
    }

    /**
     * Clamps any Rect to valid physical display coordinates while accounting for status/navigation bars.
     */
    fun clampBoundsToScreen(
        bounds: Rect,
        screenWidth: Int = getGeometry().width,
        screenHeight: Int = getGeometry().height
    ): Rect {
        if (bounds.width() <= 0 || bounds.height() <= 0) return Rect(0, 0, 0, 0)
        val left = bounds.left.coerceIn(4, (screenWidth - 20).coerceAtLeast(4))
        val top = bounds.top.coerceIn(4, (screenHeight - 20).coerceAtLeast(4))
        val right = bounds.right.coerceIn((left + 16).coerceAtMost(screenWidth - 4), screenWidth - 4)
        val bottom = bounds.bottom.coerceIn((top + 16).coerceAtMost(screenHeight - 4), screenHeight - 4)
        return Rect(left, top, right, bottom)
    }

    /**
     * Determines whether the floating ExplanationPanel should dock at the TOP of the screen
     * so that it never obscures a highlighted target located in the bottom half of the screen.
     */
    fun shouldDockPanelAtTop(targetBounds: Rect?, screenHeight: Int = getGeometry().height): Boolean {
        if (targetBounds == null || targetBounds.width() <= 0 || targetBounds.height() <= 0) return false
        return targetBounds.centerY() > (screenHeight * 0.52f)
    }

    /**
     * Computes optimal non-occluding start and end points for an annotation arrow pointing at [targetBounds].
     */
    fun computeArrowEndpoints(
        targetBounds: Rect,
        canvasWidth: Float,
        canvasHeight: Float
    ): Pair<Offset, Offset> {
        val cx = targetBounds.centerX().toFloat()
        val cy = targetBounds.centerY().toFloat()
        val spaceAbove = targetBounds.top.toFloat()
        val spaceBelow = canvasHeight - targetBounds.bottom.toFloat()
        val spaceLeft = targetBounds.left.toFloat()
        val spaceRight = canvasWidth - targetBounds.right.toFloat()

        val arrowLength = 110f

        return when {
            spaceAbove >= 140f -> {
                // Point downward to the top edge of the box
                val end = Offset(cx, (targetBounds.top - 8f).coerceAtLeast(8f))
                val start = Offset(
                    (cx + if (cx < canvasWidth / 2f) 60f else -60f).coerceIn(24f, canvasWidth - 24f),
                    (end.y - arrowLength).coerceAtLeast(16f)
                )
                Pair(start, end)
            }
            spaceBelow >= 140f -> {
                // Point upward to the bottom edge of the box
                val end = Offset(cx, (targetBounds.bottom + 8f).coerceAtMost(canvasHeight - 8f))
                val start = Offset(
                    (cx + if (cx < canvasWidth / 2f) 60f else -60f).coerceIn(24f, canvasWidth - 24f),
                    (end.y + arrowLength).coerceAtMost(canvasHeight - 16f)
                )
                Pair(start, end)
            }
            spaceRight >= spaceLeft -> {
                // Point left to the right edge of the box
                val end = Offset((targetBounds.right + 8f).coerceAtMost(canvasWidth - 8f), cy)
                val start = Offset((end.x + arrowLength).coerceAtMost(canvasWidth - 16f), (cy - 40f).coerceAtLeast(24f))
                Pair(start, end)
            }
            else -> {
                // Point right to the left edge of the box
                val end = Offset((targetBounds.left - 8f).coerceAtLeast(8f), cy)
                val start = Offset((end.x - arrowLength).coerceAtLeast(16f), (cy - 40f).coerceAtLeast(24f))
                Pair(start, end)
            }
        }
    }

    /**
     * Computes optimal top-left position for a callout label near [targetBounds] without clipping off-screen.
     */
    fun computeCalloutLabelOrigin(
        targetBounds: Rect,
        labelWidthPx: Float,
        labelHeightPx: Float,
        canvasWidth: Float,
        canvasHeight: Float
    ): Offset {
        val preferredX = (targetBounds.centerX() - labelWidthPx / 2f)
            .coerceIn(16f, (canvasWidth - labelWidthPx - 16f).coerceAtLeast(16f))

        // Prefer placing above the highlight box if room exists; otherwise place below
        val aboveY = targetBounds.top - labelHeightPx - 18f
        val belowY = targetBounds.bottom + 18f
        val finalY = if (aboveY >= 48f) {
            aboveY
        } else if (belowY + labelHeightPx <= canvasHeight - 48f) {
            belowY
        } else {
            (targetBounds.top + 8f).coerceIn(24f, (canvasHeight - labelHeightPx - 24f).coerceAtLeast(24f))
        }
        return Offset(preferredX, finalY)
    }
}

/**
 * 2. HighlightBoxRenderer
 * Renders spotlight dimming, glowing highlight boxes, corner brackets, numbered step badges,
 * and floating callout labels on the passive annotation Canvas.
 */
object HighlightBoxRenderer {

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 32f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val stepNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        textSize = 30f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    /**
     * Optionally dims unrelated screen regions while leaving a crisp transparent cutout over [focusBounds].
     */
    fun drawSpotlightDimming(
        drawScope: DrawScope,
        focusBounds: Rect,
        dimAlpha: Float = 0.38f
    ) {
        if (focusBounds.width() <= 0 || focusBounds.height() <= 0) return
        val fullPath = Path().apply {
            addRect(androidx.compose.ui.geometry.Rect(0f, 0f, drawScope.size.width, drawScope.size.height))
        }
        val pad = 8f
        val cutoutPath = Path().apply {
            addRoundRect(
                RoundRect(
                    left = (focusBounds.left - pad).coerceAtLeast(0f),
                    top = (focusBounds.top - pad).coerceAtLeast(0f),
                    right = (focusBounds.right + pad).coerceAtMost(drawScope.size.width),
                    bottom = (focusBounds.bottom + pad).coerceAtMost(drawScope.size.height),
                    cornerRadius = CornerRadius(18f, 18f)
                )
            )
        }
        val diffPath = Path.combine(PathOperation.Difference, fullPath, cutoutPath)
        drawScope.drawPath(
            path = diffPath,
            color = Color.Black.copy(alpha = dimAlpha.coerceIn(0f, 0.65f))
        )
    }

    /**
     * Draws secondary inactive step boxes subtly so the user sees upcoming steps in a sequence.
     */
    fun drawInactiveStepOutline(
        drawScope: DrawScope,
        bounds: Rect,
        stepNumber: Int,
        theme: JarvisThemePreset
    ) {
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        val left = bounds.left.toFloat()
        val top = bounds.top.toFloat()
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()

        drawScope.drawRoundRect(
            color = theme.primaryColor.copy(alpha = 0.28f),
            topLeft = Offset(left, top),
            size = Size(width, height),
            cornerRadius = CornerRadius(12f, 12f),
            style = Stroke(
                width = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
            )
        )

        // Small numbered badge
        val badgeCenter = Offset(left + 16f, top + 16f)
        drawScope.drawCircle(
            color = theme.surfaceColor.copy(alpha = 0.85f),
            radius = 16f,
            center = badgeCenter
        )
        drawScope.drawCircle(
            color = theme.primaryColor.copy(alpha = 0.6f),
            radius = 16f,
            center = badgeCenter,
            style = Stroke(width = 1.5f)
        )
    }

    /**
     * Draws the active highlighted box, numbered step badge, and floating callout label.
     */
    fun drawActiveHighlightWithLabel(
        drawScope: DrawScope,
        bounds: Rect,
        stepNumber: Int,
        totalSteps: Int,
        label: String,
        theme: JarvisThemePreset,
        pulseProgress: Float,
        scanlineProgress: Float,
        coordinateMapper: ScreenCoordinateMapper
    ) {
        if (bounds.width() <= 0 || bounds.height() <= 0) return

        // 1. Core glowing reticle box & scanline
        JarvisDrawingEngine.drawGlowingReticleBox(
            drawScope = drawScope,
            bounds = bounds,
            theme = theme,
            pulseProgress = pulseProgress,
            scanlineProgress = scanlineProgress,
            label = label
        )

        // 2. Numbered Step Badge at top-left corner of the highlight box
        val badgeRadius = 22f
        val badgeCenter = Offset(
            bounds.left.toFloat().coerceIn(badgeRadius + 4f, drawScope.size.width - badgeRadius - 4f),
            bounds.top.toFloat().coerceIn(badgeRadius + 4f, drawScope.size.height - badgeRadius - 4f)
        )
        drawScope.drawCircle(
            color = theme.glowColor,
            radius = badgeRadius + 5f * pulseProgress,
            center = badgeCenter
        )
        drawScope.drawCircle(
            color = theme.primaryColor,
            radius = badgeRadius,
            center = badgeCenter
        )
        drawScope.drawContext.canvas.nativeCanvas.drawText(
            stepNumber.toString(),
            badgeCenter.x,
            badgeCenter.y + 10f,
            stepNumberPaint
        )

        // 3. Small Explanatory Callout Label Pill near the highlight box
        val cleanLabel = label.trim().take(52)
        if (cleanLabel.isNotBlank()) {
            val textWidth = labelTextPaint.measureText(cleanLabel)
            val pillWidth = (textWidth + 44f).coerceAtMost(drawScope.size.width - 32f)
            val pillHeight = 54f
            val pillOrigin = coordinateMapper.computeCalloutLabelOrigin(
                targetBounds = bounds,
                labelWidthPx = pillWidth,
                labelHeightPx = pillHeight,
                canvasWidth = drawScope.size.width,
                canvasHeight = drawScope.size.height
            )

            drawScope.drawRoundRect(
                color = Color(0xEB070D17),
                topLeft = pillOrigin,
                size = Size(pillWidth, pillHeight),
                cornerRadius = CornerRadius(14f, 14f)
            )
            drawScope.drawRoundRect(
                color = theme.primaryColor,
                topLeft = pillOrigin,
                size = Size(pillWidth, pillHeight),
                cornerRadius = CornerRadius(14f, 14f),
                style = Stroke(width = 2.2f)
            )

            drawScope.drawContext.canvas.nativeCanvas.drawText(
                cleanLabel,
                pillOrigin.x + 22f,
                pillOrigin.y + 36f,
                labelTextPaint
            )
        }
    }
}

/**
 * 3. ArrowRenderer
 * Renders directional arrows pointing clearly at the highlighted UI element.
 */
object ArrowRenderer {

    fun drawAnnotationArrow(
        drawScope: DrawScope,
        targetBounds: Rect,
        theme: JarvisThemePreset,
        flowProgress: Float,
        coordinateMapper: ScreenCoordinateMapper
    ) {
        if (targetBounds.width() <= 0 || targetBounds.height() <= 0) return
        val (start, end) = coordinateMapper.computeArrowEndpoints(
            targetBounds = targetBounds,
            canvasWidth = drawScope.size.width,
            canvasHeight = drawScope.size.height
        )
        JarvisDrawingEngine.drawCurvedConnector(
            drawScope = drawScope,
            start = start,
            end = end,
            theme = theme,
            flowProgress = flowProgress
        )
    }
}

/**
 * 4. PointerRenderer
 * Renders the animated cursor/pointer at the current interpolated screen position.
 */
object PointerRenderer {

    fun drawPointer(
        drawScope: DrawScope,
        position: Offset,
        style: CursorStyle,
        theme: JarvisThemePreset,
        rotationDeg: Float,
        pulseProgress: Float
    ) {
        if (position.x <= 0f && position.y <= 0f) return
        JarvisDrawingEngine.drawGlowingReticleCircle(
            drawScope = drawScope,
            center = position,
            radius = 26f,
            theme = theme,
            pulseProgress = pulseProgress
        )
        JarvisDrawingEngine.drawAnimatedCursor(
            drawScope = drawScope,
            position = position,
            style = style,
            theme = theme,
            rotationDeg = rotationDeg,
            pulseProgress = pulseProgress
        )
    }
}

/**
 * 5. ExplanationStepController
 * Manages sequential multi-step visual explanations, step navigation, auto-walkthrough,
 * and synchronization with voice narration and overlay rendering.
 */
object ExplanationStepController {

    private val _steps = MutableStateFlow<List<ScreenAnnotationStep>>(emptyList())
    val steps: StateFlow<List<ScreenAnnotationStep>> = _steps.asStateFlow()

    private val _currentStepIndex = MutableStateFlow(0)
    val currentStepIndex: StateFlow<Int> = _currentStepIndex.asStateFlow()

    private val _currentStep = MutableStateFlow<ScreenAnnotationStep?>(null)
    val currentStep: StateFlow<ScreenAnnotationStep?> = _currentStep.asStateFlow()

    private val _isAutoPlaying = MutableStateFlow(false)
    val isAutoPlaying: StateFlow<Boolean> = _isAutoPlaying.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val controllerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var autoPlayJob: Job? = null

    var onSpeakStep: ((String) -> Unit)? = null
    var onStopSpeaking: (() -> Unit)? = null

    fun startSequence(
        newSteps: List<ScreenAnnotationStep>,
        autoAdvance: Boolean = false
    ) {
        autoPlayJob?.cancel()
        if (newSteps.isEmpty()) {
            clear()
            return
        }
        val normalized = newSteps.mapIndexed { idx, step ->
            step.copy(stepNumber = idx + 1, totalSteps = newSteps.size)
        }
        _steps.value = normalized
        _currentStepIndex.value = 0
        val first = normalized.first()
        _currentStep.value = first
        syncWithOverlayController(first)
        speakIfEnabled(first)

        if (autoAdvance && normalized.size > 1) {
            startAutoWalkthrough()
        } else {
            _isAutoPlaying.value = false
        }
    }

    fun nextStep(): ScreenAnnotationStep? {
        val list = _steps.value
        if (list.isEmpty()) return null
        val nextIdx = (_currentStepIndex.value + 1).coerceAtMost(list.lastIndex)
        if (nextIdx == _currentStepIndex.value && list.size > 1) {
            // Wrap around to 0 when user taps Next on final step
            return goToStep(0)
        }
        return goToStep(nextIdx)
    }

    fun previousStep(): ScreenAnnotationStep? {
        val list = _steps.value
        if (list.isEmpty()) return null
        val prevIdx = (_currentStepIndex.value - 1).coerceAtLeast(0)
        return goToStep(prevIdx)
    }

    fun goToStep(index: Int): ScreenAnnotationStep? {
        val list = _steps.value
        if (index !in list.indices) return null
        _currentStepIndex.value = index
        val step = list[index]
        _currentStep.value = step
        syncWithOverlayController(step)
        speakIfEnabled(step)
        return step
    }

    fun toggleAutoPlay() {
        if (_isAutoPlaying.value) {
            autoPlayJob?.cancel()
            _isAutoPlaying.value = false
        } else {
            startAutoWalkthrough()
        }
    }

    fun toggleMute() {
        val newMuted = !_isMuted.value
        _isMuted.value = newMuted
        if (newMuted) {
            onStopSpeaking?.invoke()
        } else {
            _currentStep.value?.let { speakIfEnabled(it) }
        }
    }

    fun toggleDimming() {
        val current = _currentStep.value ?: return
        val updatedDim = !current.dimUnrelatedAreas
        _steps.value = _steps.value.map { it.copy(dimUnrelatedAreas = updatedDim) }
        _currentStep.value = current.copy(dimUnrelatedAreas = updatedDim)
    }

    private fun startAutoWalkthrough() {
        autoPlayJob?.cancel()
        _isAutoPlaying.value = true
        autoPlayJob = controllerScope.launch {
            while (isActive && _isAutoPlaying.value) {
                val step = _currentStep.value ?: break
                val wordCount = step.spokenExplanation.split(Regex("\\s+")).size
                val readDurationMs = (wordCount * 380L).coerceIn(3200L, 7500L)
                delay(readDurationMs)
                if (!isActive || !_isAutoPlaying.value) break
                val list = _steps.value
                val nextIdx = _currentStepIndex.value + 1
                if (nextIdx < list.size) {
                    goToStep(nextIdx)
                } else {
                    _isAutoPlaying.value = false
                    break
                }
            }
        }
    }

    private fun speakIfEnabled(step: ScreenAnnotationStep) {
        if (!_isMuted.value && step.spokenExplanation.isNotBlank()) {
            onSpeakStep?.invoke(step.spokenExplanation)
        }
    }

    private fun syncWithOverlayController(step: ScreenAnnotationStep) {
        val instruction = VisualInstruction(
            type = VisualInstructionType.HIGHLIGHT_BOX,
            bounds = step.bounds,
            clickPoint = step.clickPoint,
            label = step.label,
            text = step.spokenExplanation,
            isPassThroughTouch = true
        )
        JarvisOverlayController.setActiveStepInstruction(instruction)
    }

    fun clear() {
        autoPlayJob?.cancel()
        _isAutoPlaying.value = false
        _steps.value = emptyList()
        _currentStepIndex.value = 0
        _currentStep.value = null
        onStopSpeaking?.invoke()
    }
}

/**
 * 6. ExplanationPanel
 * Compact, non-blocking floating explanation & step-navigation panel.
 * Hosted in its own WRAP_CONTENT WindowManager view so the rest of the screen remains 100% touchable.
 */
@Composable
fun ExplanationPanel(
    currentStep: ScreenAnnotationStep?,
    activePlan: JarvisVisualPlan?,
    theme: JarvisThemePreset,
    isAutoPlaying: Boolean,
    isMuted: Boolean,
    onPreviousStep: () -> Unit,
    onNextStep: () -> Unit,
    onToggleAutoPlay: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleDim: () -> Unit,
    onExplainScreenAgain: () -> Unit,
    onDismiss: () -> Unit,
    onEmergencyStopJarvis: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeCard = activePlan?.cards?.firstOrNull()
    if (currentStep == null && activeCard == null) return

    val title = currentStep?.title ?: activeCard?.title ?: "JARVIS Screen Assistant"
    val explanationText = currentStep?.spokenExplanation
        ?: activeCard?.detailText?.ifBlank { activeCard.summary }
        ?: activeCard?.summary
        ?: ""
    val actionLabel = currentStep?.label ?: ""
    val stepNum = currentStep?.stepNumber ?: 1
    val totalSteps = currentStep?.totalSteps ?: 1

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xF2080E18)
        ),
        modifier = modifier
            .widthIn(min = 290.dp, max = 420.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(
                    listOf(theme.primaryColor, theme.secondaryColor.copy(alpha = 0.5f))
                ),
                shape = RoundedCornerShape(18.dp)
            )
            .testTag("jarvis_explanation_panel")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Top Header: Step Badge + Title + Quick Controls + Close Button
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
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = theme.primaryColor
                    ) {
                        Text(
                            text = "STEP $stepNum/$totalSteps",
                            color = Color.Black,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                    Text(
                        text = title,
                        color = theme.primaryColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Mute / Speak Toggle
                    IconButton(
                        onClick = onToggleMute,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("jarvis_panel_mute_button")
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = if (isMuted) "Unmute Voice" else "Mute Voice",
                            tint = if (isMuted) Color.White.copy(alpha = 0.5f) else theme.primaryColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Spotlight Dim Toggle
                    if (currentStep != null) {
                        IconButton(
                            onClick = onToggleDim,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("jarvis_panel_dim_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Contrast,
                                contentDescription = "Toggle Spotlight Dimming",
                                tint = if (currentStep.dimUnrelatedAreas) theme.primaryColor else Color.White.copy(alpha = 0.45f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Dismiss Visuals Button
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("jarvis_panel_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Explanation",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Action Callout Pill
            if (actionLabel.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = theme.primaryColor.copy(alpha = 0.14f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.TouchApp,
                            contentDescription = "Action Target",
                            tint = theme.primaryColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = actionLabel,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Main Explanation Body
            if (explanationText.isNotBlank()) {
                Text(
                    text = explanationText,
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Bullet points if summary card has bullets and no multi-step sequence is active
            if (currentStep == null && activeCard != null && activeCard.bullets.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    activeCard.bullets.take(3).forEach { bullet ->
                        Text(
                            text = "• $bullet",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Bottom Navigation & Emergency Exit Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (totalSteps > 1) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = onPreviousStep,
                            enabled = stepNum > 1,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier
                                .height(32.dp)
                                .testTag("jarvis_step_prev_button")
                        ) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = "Previous", modifier = Modifier.size(16.dp))
                            Text("Prev", fontSize = 11.sp)
                        }

                        Button(
                            onClick = onNextStep,
                            colors = ButtonDefaults.buttonColors(containerColor = theme.primaryColor),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier
                                .height(32.dp)
                                .testTag("jarvis_step_next_button")
                        ) {
                            Text(
                                text = if (stepNum < totalSteps) "Next" else "Restart",
                                color = Color.Black,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = "Next",
                                tint = Color.Black,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        IconButton(
                            onClick = onToggleAutoPlay,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("jarvis_step_autoplay_button")
                        ) {
                            Icon(
                                imageVector = if (isAutoPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                                contentDescription = if (isAutoPlaying) "Pause Walkthrough" else "Auto Walkthrough",
                                tint = theme.primaryColor,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = onExplainScreenAgain,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .testTag("jarvis_rescan_screen_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Re-scan Screen",
                            tint = theme.primaryColor,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Scan Screen", color = theme.primaryColor, fontSize = 11.sp)
                    }
                }

                // Emergency Stop Jarvis Button
                TextButton(
                    onClick = onEmergencyStopJarvis,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier
                        .height(32.dp)
                        .testTag("jarvis_emergency_stop_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = "Stop Jarvis Overlay",
                        tint = Color(0xFFFF5252),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Exit Jarvis",
                        color = Color(0xFFFF5252),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * 7. ScreenAnnotationManager
 * Central lifecycle and synchronization manager for screen annotations.
 * Ensures annotations stay aligned with the active app/window/orientation and automatically
 * clears outdated annotations when the user switches screens or stops screen sharing.
 */
object ScreenAnnotationManager {

    private const val TAG = "ScreenAnnotationManager"

    val coordinateMapper = ScreenCoordinateMapper()

    @Volatile
    private var annotatedPackageName: String = ""

    @Volatile
    private var annotatedScreenSignature: String = ""

    @Volatile
    private var lastAnnotationTimestampMs: Long = 0L

    fun recordAnnotatedScreen(packageName: String, screenSignature: String) {
        annotatedPackageName = packageName
        annotatedScreenSignature = screenSignature
        lastAnnotationTimestampMs = System.currentTimeMillis()
    }

    /**
     * Called when Accessibility detects a foreground window or package transition.
     * Clears outdated screen annotations so the assistant never displays stale highlight boxes
     * over a new page or app.
     */
    fun onForegroundWindowChanged(newPackage: String, newScreenSignature: String = "") {
        if (newPackage.isBlank() || newPackage == "com.android.systemui") return
        val hasActiveAnnotations = ExplanationStepController.currentStep.value != null ||
                JarvisOverlayController.activeInstruction.value != null ||
                JarvisOverlayController.activePlan.value != null

        if (!hasActiveAnnotations) return

        if (annotatedPackageName.isNotBlank() && !annotatedPackageName.equals(newPackage, ignoreCase = true)) {
            Log.i(TAG, "Foreground app changed ($annotatedPackageName -> $newPackage); clearing outdated annotations.")
            clearAllAnnotations()
        } else if (newScreenSignature.isNotBlank() &&
            annotatedScreenSignature.isNotBlank() &&
            annotatedScreenSignature != newScreenSignature &&
            System.currentTimeMillis() - lastAnnotationTimestampMs >= 800L
        ) {
            Log.i(TAG, "Screen content changed; clearing outdated annotations.")
            clearAllAnnotations()
        }
    }

    /**
     * Called when the device rotates or screen configuration changes.
     */
    fun onConfigurationChanged(context: Context) {
        val oldGeom = coordinateMapper.getGeometry()
        val newGeom = coordinateMapper.refreshGeometry(context)
        if (oldGeom.isLandscape != newGeom.isLandscape || oldGeom.rotation != newGeom.rotation) {
            Log.i(TAG, "Display orientation changed (${oldGeom.width}x${oldGeom.height} -> ${newGeom.width}x${newGeom.height}); clearing stale coordinate annotations.")
            clearAllAnnotations()
        }
    }

    fun clearAllAnnotations() {
        annotatedPackageName = ""
        annotatedScreenSignature = ""
        ExplanationStepController.clear()
        JarvisOverlayController.clearAllVisuals()
    }
}
