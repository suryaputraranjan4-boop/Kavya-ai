package com.example.visual

import android.graphics.Rect
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Passive 60fps JARVIS Annotation Overlay Canvas.
 * Renders highlight boxes, numbered step badges, callout labels, curved arrows,
 * spotlight dimming, and smoothly animated pointers across step transitions.
 *
 * IMPORTANT: In JarvisOverlayService, this Composable is hosted inside a window with
 * FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN so it NEVER blocks
 * user touches on the underlying mobile screen.
 */
@Composable
fun JarvisOverlayCanvas(
    plan: JarvisVisualPlan?,
    orbState: JarvisOrbState,
    orbConfig: JarvisOrbConfig,
    activeInstruction: VisualInstruction?,
    theme: JarvisThemePreset,
    modifier: Modifier = Modifier,
    allSteps: List<ScreenAnnotationStep> = emptyList(),
    currentStep: ScreenAnnotationStep? = null,
    showInlinePanel: Boolean = false,
    onDismissCard: (String) -> Unit = {},
    onDismissAll: () -> Unit = {},
    onStepCompleted: (VisualInstruction) -> Unit = {}
) {
    val effectiveBounds: Rect? = currentStep?.bounds
        ?: activeInstruction?.bounds
        ?: plan?.instructions?.firstOrNull()?.bounds
        ?: plan?.cards?.firstOrNull()?.targetAnchorBounds

    val hasValidBounds = effectiveBounds != null && effectiveBounds.width() > 0 && effectiveBounds.height() > 0
    if (plan == null && activeInstruction == null && currentStep == null) return

    val infiniteTransition = rememberInfiniteTransition(label = "OverlayCanvasLoop")

    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "PulseProgress"
    )

    val scanlineProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ScanlineProgress"
    )

    val cursorRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "CursorRot"
    )

    val rawClickPoint = currentStep?.clickPoint
        ?: activeInstruction?.clickPoint
        ?: effectiveBounds?.let { Pair(it.centerX(), it.centerY()) }

    val targetX = (rawClickPoint?.first ?: 0).toFloat()
    val targetY = (rawClickPoint?.second ?: 0).toFloat()

    // Smoothly animate pointer movement between explanation steps
    val animatedPointerX by animateFloatAsState(
        targetValue = targetX,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "PointerX"
    )
    val animatedPointerY by animateFloatAsState(
        targetValue = targetY,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "PointerY"
    )

    val activeLabel = currentStep?.label?.ifBlank { currentStep.title }
        ?: activeInstruction?.label?.ifBlank { activeInstruction.text }
        ?: plan?.instructions?.firstOrNull()?.label
        ?: ""

    val stepNum = currentStep?.stepNumber ?: 1
    val totalSteps = currentStep?.totalSteps ?: allSteps.size.coerceAtLeast(1)
    val shouldDim = currentStep?.dimUnrelatedAreas ?: false
    val shouldShowArrow = currentStep?.showArrow ?: true
    val shouldShowPointer = currentStep?.showPointer ?: true

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("jarvis_passive_annotation_canvas")
    ) {
        if (hasValidBounds && effectiveBounds != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val mapper = ScreenAnnotationManager.coordinateMapper
                val clampedBounds = mapper.clampBoundsToScreen(
                    bounds = effectiveBounds,
                    screenWidth = size.width.toInt().coerceAtLeast(320),
                    screenHeight = size.height.toInt().coerceAtLeast(480)
                )

                // 1. Optional Spotlight Dimming around active element
                if (shouldDim) {
                    HighlightBoxRenderer.drawSpotlightDimming(
                        drawScope = this,
                        focusBounds = clampedBounds,
                        dimAlpha = 0.34f
                    )
                }

                // 2. Subtle outlines for other steps in a multi-step explanation
                if (allSteps.size > 1) {
                    for (step in allSteps) {
                        if (step.stepNumber != stepNum && step.bounds.width() > 0 && step.bounds.height() > 0) {
                            val stepClamped = mapper.clampBoundsToScreen(
                                bounds = step.bounds,
                                screenWidth = size.width.toInt().coerceAtLeast(320),
                                screenHeight = size.height.toInt().coerceAtLeast(480)
                            )
                            HighlightBoxRenderer.drawInactiveStepOutline(
                                drawScope = this,
                                bounds = stepClamped,
                                stepNumber = step.stepNumber,
                                theme = theme
                            )
                        }
                    }
                }

                // 3. Active Highlight Box + Numbered Step Badge + Floating Callout Label
                HighlightBoxRenderer.drawActiveHighlightWithLabel(
                    drawScope = this,
                    bounds = clampedBounds,
                    stepNumber = stepNum,
                    totalSteps = totalSteps,
                    label = activeLabel,
                    theme = theme,
                    pulseProgress = pulseProgress,
                    scanlineProgress = scanlineProgress,
                    coordinateMapper = mapper
                )

                // 4. Directional Arrow pointing at the highlighted box
                if (shouldShowArrow) {
                    ArrowRenderer.drawAnnotationArrow(
                        drawScope = this,
                        targetBounds = clampedBounds,
                        theme = theme,
                        flowProgress = pulseProgress,
                        coordinateMapper = mapper
                    )
                }

                // 5. Smoothly Animated Visible Cursor / Pointer
                if (shouldShowPointer && animatedPointerX > 0f && animatedPointerY > 0f) {
                    PointerRenderer.drawPointer(
                        drawScope = this,
                        position = Offset(animatedPointerX, animatedPointerY),
                        style = orbConfig.cursorStyle,
                        theme = theme,
                        rotationDeg = cursorRotation,
                        pulseProgress = pulseProgress
                    )
                }
            }
        }

        // Optional inline panel only when rendered inside an in-app preview (NOT in the passive overlay window)
        if (showInlinePanel) {
            val dockTop = ScreenAnnotationManager.coordinateMapper.shouldDockPanelAtTop(effectiveBounds)
            Box(
                modifier = Modifier
                    .align(if (dockTop) Alignment.TopCenter else Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 20.dp)
            ) {
                ExplanationPanel(
                    currentStep = currentStep,
                    activePlan = plan,
                    theme = theme,
                    isAutoPlaying = false,
                    isMuted = false,
                    onPreviousStep = { ExplanationStepController.previousStep() },
                    onNextStep = { ExplanationStepController.nextStep() },
                    onToggleAutoPlay = { ExplanationStepController.toggleAutoPlay() },
                    onToggleMute = { ExplanationStepController.toggleMute() },
                    onToggleDim = { ExplanationStepController.toggleDimming() },
                    onExplainScreenAgain = {},
                    onDismiss = onDismissAll,
                    onEmergencyStopJarvis = onDismissAll
                )
            }
        }
    }
}
