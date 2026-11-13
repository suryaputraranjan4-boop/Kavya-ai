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

        val coords = envelope.target.coordinates
            ?: return PrecisionCalibrationResult.Rejected(
                "Missing coordinates for screen action '${envelope.action}' targeting '${envelope.target.description}'."
            )

        if (coords.x.isNaN() || coords.y.isNaN() || coords.x < 0.0 || coords.y < 0.0) {
            return PrecisionCalibrationResult.Rejected("Coordinates contain invalid or negative values: (${coords.x}, ${coords.y}).")
        }

        val dispW = currentGeom.widthPx.toDouble()
        val dispH = currentGeom.heightPx.toDouble()
        val shotW = observation.bitmapWidth.coerceAtLeast(1).toDouble()
        val shotH = observation.bitmapHeight.coerceAtLeast(1).toDouble()

        val rawDisplayX: Double
        val rawDisplayY: Double

        when (coords.space) {
            CoordinateSpace.NORMALIZED_0_1 -> {
                if (coords.x > 1.0 || coords.y > 1.0) {
                    return PrecisionCalibrationResult.Rejected(
                        "Normalized coordinates (${coords.x}, ${coords.y}) out of range [0.0, 1.0]."
                    )
                }
                rawDisplayX = coords.x * dispW
                rawDisplayY = coords.y * dispH
            }
            CoordinateSpace.CURRENT_SCREENSHOT_PIXELS -> {
                if (coords.x > shotW || coords.y > shotH) {
                    return PrecisionCalibrationResult.Rejected(
                        "Screenshot pixel coordinates (${coords.x}, ${coords.y}) exceed screenshot bounds (${shotW.toInt()}x${shotH.toInt()})."
                    )
                }
                rawDisplayX = (coords.x / shotW) * dispW
                rawDisplayY = (coords.y / shotH) * dispH
            }
            CoordinateSpace.DISPLAY_PIXELS -> {
                if (coords.x > dispW || coords.y > dispH) {
                    return PrecisionCalibrationResult.Rejected(
                        "Display pixel coordinates (${coords.x}, ${coords.y}) exceed display bounds (${dispW.toInt()}x${dispH.toInt()})."
                    )
                }
                rawDisplayX = coords.x
                rawDisplayY = coords.y
            }
        }

        // Strict bounds check against valid display area (avoid extreme off-screen edges)
        if (rawDisplayX < 0.0 || rawDisplayX > dispW || rawDisplayY < 0.0 || rawDisplayY > dispH) {
            return PrecisionCalibrationResult.Rejected(
                "Calibrated coordinate (${rawDisplayX.toInt()}, ${rawDisplayY.toInt()}) outside display bounds (${dispW.toInt()}x${dispH.toInt()})."
            )
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
}
