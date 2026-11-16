package com.example.orchestrator

import android.content.Context

data class CalibratedPoint(
    val xPx: Float,
    val yPx: Float,
    val sourceSpace: CoordinateSpace,
    val observationId: String
)

sealed class PrecisionCalibrationResult {
    data class Valid(val point: CalibratedPoint) : PrecisionCalibrationResult()
    data class Rejected(val reason: String) : PrecisionCalibrationResult()
}

sealed class ConfidenceGateResult {
    data class Proceed(val band: ConfidenceBand) : ConfidenceGateResult()
    data class RequireConfirmation(val band: ConfidenceBand, val reason: String) : ConfidenceGateResult()
    data class Reject(val band: ConfidenceBand, val reason: String) : ConfidenceGateResult()
}

/**
 * High-precision coordinate mapper and confidence gatekeeper.
 * Converts AI coordinates cleanly from normalized [0..1], screenshot pixels, or display pixels
 * into verified physical display coordinates, rejecting stale orientation/resolution/out-of-bounds targets.
 */
object PrecisionController {

    fun evaluateConfidence(envelope: AiActionEnvelope): ConfidenceGateResult {
        val band = envelope.confidenceBand
        return when (band) {
            ConfidenceBand.HIGH -> ConfidenceGateResult.Proceed(band)
            ConfidenceBand.MEDIUM -> ConfidenceGateResult.RequireConfirmation(
                band = band,
                reason = "Medium AI confidence (${(envelope.confidence * 100).toInt()}%) requires confirmation before acting on '${envelope.target.description}'."
            )
            ConfidenceBand.LOW -> ConfidenceGateResult.Reject(
                band = band,
                reason = "Low AI confidence (${(envelope.confidence * 100).toInt()}%) is below execution safety threshold (${(AiProtocolConstants.MEDIUM_CONFIDENCE_MIN * 100).toInt()}%). Aborting speculative action."
            )
        }
    }

    fun calibrateTargetCoordinates(
        context: Context,
        envelope: AiActionEnvelope,
        observation: ScreenObservationRecord
    ): PrecisionCalibrationResult {
        val currentGeom = ScreenObservationCoordinator.readCurrentDisplayGeometry(context)

        if (!observation.matchesGeometry(currentGeom.widthPx, currentGeom.heightPx, currentGeom.orientation)) {
            return PrecisionCalibrationResult.Rejected(
                "Screen geometry or orientation changed since observation ${observation.screenshotId} " +
                    "(captured ${observation.displayWidthPx}x${observation.displayHeightPx} orient=${observation.orientation}, " +
                    "current ${currentGeom.widthPx}x${currentGeom.heightPx} orient=${currentGeom.orientation})."
            )
        }

        if (envelope.screenWidth > 0 && envelope.screenHeight > 0 &&
            (envelope.screenWidth != observation.displayWidthPx || envelope.screenHeight != observation.displayHeightPx)
        ) {
            return PrecisionCalibrationResult.Rejected(
                "AI action screen dimensions (${envelope.screenWidth}x${envelope.screenHeight}) do not match current display (${observation.displayWidthPx}x${observation.displayHeightPx})."
            )
        }

        if (envelope.orientation > 0 && envelope.orientation != currentGeom.orientation) {
            return PrecisionCalibrationResult.Rejected(
                "AI action orientation (${envelope.orientation}) does not match active device orientation (${currentGeom.orientation})."
            )
        }

        val dispW = currentGeom.widthPx.toDouble()
        val dispH = currentGeom.heightPx.toDouble()
        val shotW = observation.bitmapWidth.coerceAtLeast(1).toDouble()
        val shotH = observation.bitmapHeight.coerceAtLeast(1).toDouble()

        val coords = envelope.target.coordinates ?: run {
            if (envelope.action == OrchestratorActionType.SWIPE || envelope.action == OrchestratorActionType.SCROLL) {
                return PrecisionCalibrationResult.Valid(
                    CalibratedPoint(
                        xPx = (dispW * 0.5).toFloat().coerceIn(1f, (currentGeom.widthPx - 1).coerceAtLeast(1).toFloat()),
                        yPx = (dispH * 0.5).toFloat().coerceIn(1f, (currentGeom.heightPx - 1).coerceAtLeast(1).toFloat()),
                        sourceSpace = CoordinateSpace.DISPLAY_PIXELS,
                        observationId = observation.screenshotId
                    )
                )
            }
            val inferred = inferPointFromUiTreeSummary(envelope.target, observation.uiTreeSummary, currentGeom.widthPx, currentGeom.heightPx)
            if (inferred != null) {
                return PrecisionCalibrationResult.Valid(
                    CalibratedPoint(
                        xPx = inferred.first,
                        yPx = inferred.second,
                        sourceSpace = CoordinateSpace.DISPLAY_PIXELS,
                        observationId = observation.screenshotId
                    )
                )
            }
            val reason = "Missing coordinates for screen action '${envelope.action}' targeting '${envelope.target.description}'."
            AiActionDiagnostics.record(
                category = ActionDiagnosticCategory.COORDINATE_NORMALIZATION_ERROR,
                stage = "calibrateTargetCoordinates",
                reason = reason,
                normalizedActionSummary = "${envelope.action.name}(${envelope.target.description})"
            )
            return PrecisionCalibrationResult.Rejected(reason)
        }

        if (coords.x.isNaN() || coords.y.isNaN() || coords.x < 0.0 || coords.y < 0.0) {
            val reason = "Coordinates contain invalid or negative values: (${coords.x}, ${coords.y})."
            AiActionDiagnostics.record(
                category = ActionDiagnosticCategory.COORDINATE_NORMALIZATION_ERROR,
                stage = "calibrateTargetCoordinates",
                reason = reason,
                normalizedActionSummary = "${envelope.action.name}(${coords.x},${coords.y},${coords.space})"
            )
            return PrecisionCalibrationResult.Rejected(reason)
        }

        val rawDisplayX: Double
        val rawDisplayY: Double

        when (coords.space) {
            CoordinateSpace.NORMALIZED_0_1 -> {
                if (coords.x > 1.0 || coords.y > 1.0) {
                    val reason = "Normalized coordinates (${coords.x}, ${coords.y}) out of range [0.0, 1.0]."
                    AiActionDiagnostics.record(
                        category = ActionDiagnosticCategory.COORDINATE_NORMALIZATION_ERROR,
                        stage = "calibrateTargetCoordinates",
                        reason = reason,
                        normalizedActionSummary = "${envelope.action.name}(${coords.x},${coords.y},${coords.space})"
                    )
                    return PrecisionCalibrationResult.Rejected(reason)
                }
                rawDisplayX = coords.x * dispW
                rawDisplayY = coords.y * dispH
            }
            CoordinateSpace.NORMALIZED_1000 -> {
                if (coords.x > 1000.0 || coords.y > 1000.0) {
                    val reason = "Normalized [0..1000] coordinates (${coords.x}, ${coords.y}) out of range [0.0, 1000.0]."
                    AiActionDiagnostics.record(
                        category = ActionDiagnosticCategory.COORDINATE_NORMALIZATION_ERROR,
                        stage = "calibrateTargetCoordinates",
                        reason = reason,
                        normalizedActionSummary = "${envelope.action.name}(${coords.x},${coords.y},${coords.space})"
                    )
                    return PrecisionCalibrationResult.Rejected(reason)
                }
                rawDisplayX = (coords.x / 1000.0) * dispW
                rawDisplayY = (coords.y / 1000.0) * dispH
            }
            CoordinateSpace.CURRENT_SCREENSHOT_PIXELS -> {
                if (coords.x > shotW || coords.y > shotH) {
                    val reason = "Screenshot pixel coordinates (${coords.x}, ${coords.y}) exceed screenshot bounds (${shotW.toInt()}x${shotH.toInt()})."
                    AiActionDiagnostics.record(
                        category = ActionDiagnosticCategory.COORDINATE_NORMALIZATION_ERROR,
                        stage = "calibrateTargetCoordinates",
                        reason = reason,
                        normalizedActionSummary = "${envelope.action.name}(${coords.x},${coords.y},${coords.space})"
                    )
                    return PrecisionCalibrationResult.Rejected(reason)
                }
                rawDisplayX = (coords.x / shotW) * dispW
                rawDisplayY = (coords.y / shotH) * dispH
            }
            CoordinateSpace.DISPLAY_PIXELS -> {
                if (coords.x > dispW || coords.y > dispH) {
                    val reason = "Display pixel coordinates (${coords.x}, ${coords.y}) exceed display bounds (${dispW.toInt()}x${dispH.toInt()})."
                    AiActionDiagnostics.record(
                        category = ActionDiagnosticCategory.COORDINATE_NORMALIZATION_ERROR,
                        stage = "calibrateTargetCoordinates",
                        reason = reason,
                        normalizedActionSummary = "${envelope.action.name}(${coords.x},${coords.y},${coords.space})"
                    )
                    return PrecisionCalibrationResult.Rejected(reason)
                }
                rawDisplayX = coords.x
                rawDisplayY = coords.y
            }
        }

        // Strict bounds check against valid display area (avoid extreme off-screen edges)
        if (rawDisplayX < 0.0 || rawDisplayX > dispW || rawDisplayY < 0.0 || rawDisplayY > dispH) {
            val reason = "Calibrated coordinate (${rawDisplayX.toInt()}, ${rawDisplayY.toInt()}) outside display bounds (${dispW.toInt()}x${dispH.toInt()})."
            AiActionDiagnostics.record(
                category = ActionDiagnosticCategory.COORDINATE_NORMALIZATION_ERROR,
                stage = "calibrateTargetCoordinates",
                reason = reason
            )
            return PrecisionCalibrationResult.Rejected(reason)
        }

        // Clamp strictly within inset-safe interactive touch area if tiny border margin overflow occurs,
        // without pulling points aggressively inward if already valid
        val safeX = rawDisplayX.toFloat().coerceIn(1f, (currentGeom.widthPx - 1).coerceAtLeast(1).toFloat())
        val safeY = rawDisplayY.toFloat().coerceIn(1f, (currentGeom.heightPx - 1).coerceAtLeast(1).toFloat())

        return PrecisionCalibrationResult.Valid(
            CalibratedPoint(
                xPx = safeX,
                yPx = safeY,
                sourceSpace = coords.space,
                observationId = observation.screenshotId
            )
        )
    }

    private fun inferPointFromUiTreeSummary(
        target: AiActionTarget,
        uiTreeSummary: String,
        displayWidthPx: Int,
        displayHeightPx: Int
    ): Pair<Float, Float>? {
        if (uiTreeSummary.isBlank()) return null
        val query = target.selector.ifBlank { target.description }.trim()
        if (query.isBlank()) return null
        val rectRegex = Regex("""(?:\[(\d+)\s*,\s*(\d+)\]\s*\[(\d+)\s*,\s*(\d+)\]|Rect\(\s*(\d+)\s*,\s*(\d+)\s*-\s*(\d+)\s*,\s*(\d+)\s*\))""")
        for (line in uiTreeSummary.lines()) {
            if (!line.contains(query, ignoreCase = true)) continue
            val m = rectRegex.find(line) ?: continue
            val nums = m.groupValues.drop(1).filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }
            if (nums.size == 4) {
                val cx = ((nums[0] + nums[2]) / 2f).coerceIn(1f, (displayWidthPx - 1).coerceAtLeast(1).toFloat())
                val cy = ((nums[1] + nums[3]) / 2f).coerceIn(1f, (displayHeightPx - 1).coerceAtLeast(1).toFloat())
                return cx to cy
            }
        }
        return null
    }
}
