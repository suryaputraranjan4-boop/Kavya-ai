package com.example.visual

import android.graphics.Rect
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class VisionModelId(val displayName: String) {
    SMOLVLM2_2_2B("SmolVLM2 2.2B"),
    FLORENCE_2("Florence-2"),
    CLOUD_VISION("Cloud Vision"),
    ACCESSIBILITY_HIERARCHY("Accessibility Hierarchy")
}

enum class ModelDetectionStatus {
    SUCCESS,
    FAILED,
    TIMEOUT,
    UNAVAILABLE,
    STALE
}

enum class ConsensusDecisionType {
    USE_OFFLINE_HIGH_AGREEMENT,
    USE_OFFLINE_LOW_CONF_AGREEMENT,
    ESCALATE_TO_CLOUD,
    USE_CLOUD_TWO_OF_THREE_CONSENSUS,
    USE_OFFLINE_TWO_OF_THREE_CONSENSUS,
    FALLBACK_ACCESSIBILITY_ONLY,
    REQUIRE_FRESH_OBSERVATION,
    UNAVAILABLE_OR_FAILED
}

enum class ConsensusReasonCode {
    OFFLINE_AGREEMENT,
    LOW_CONFIDENCE_AGREEMENT,
    LOW_CONFIDENCE_DISAGREEMENT,
    EQUAL_VERY_LOW_CONFIDENCE,
    HIGH_CONFIDENCE_DISAGREEMENT,
    CLOUD_PROVIDER_UNAVAILABLE,
    CLOUD_TWO_OF_THREE_AGREEMENT,
    OFFLINE_TWO_OF_THREE_AGREEMENT,
    THREE_WAY_DISAGREEMENT,
    ACCESSIBILITY_MATCH_FOUND,
    ACCESSIBILITY_MATCH_FAILED,
    OBSERVATION_STALE,
    OFFLINE_MODEL_FAILURE,
    CLOUD_VERIFICATION_FAILED
}

data class NormalizedPoint(
    val x: Float,
    val y: Float
) {
    fun clamped(): NormalizedPoint = NormalizedPoint(
        x = x.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f,
        y = y.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    )

    fun distanceTo(other: NormalizedPoint): Float {
        return hypot(x - other.x, y - other.y)
    }

    fun toDevicePx(screenWidth: Int, screenHeight: Int): Pair<Float, Float> {
        val c = clamped()
        return (c.x * screenWidth.coerceAtLeast(1)) to (c.y * screenHeight.coerceAtLeast(1))
    }
}

data class NormalizedBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun normalized(): NormalizedBox {
        val l = left.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val t = top.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val r = right.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val b = bottom.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        return NormalizedBox(
            left = min(l, r),
            top = min(t, b),
            right = max(l, r),
            bottom = max(t, b)
        )
    }

    val center: NormalizedPoint
        get() = NormalizedPoint(
            x = ((left + right) * 0.5f).coerceIn(0f, 1f),
            y = ((top + bottom) * 0.5f).coerceIn(0f, 1f)
        )

    val area: Float
        get() = ((right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f))

    fun contains(point: NormalizedPoint, margin: Float = 0.015f): Boolean {
        val n = normalized()
        return point.x in (n.left - margin)..(n.right + margin) &&
            point.y in (n.top - margin)..(n.bottom + margin)
    }

    fun iou(other: NormalizedBox): Float {
        val a = normalized()
        val b = other.normalized()
        val iLeft = max(a.left, b.left)
        val iTop = max(a.top, b.top)
        val iRight = min(a.right, b.right)
        val iBottom = min(a.bottom, b.bottom)
        if (iRight <= iLeft || iBottom <= iTop) return 0f
        val inter = (iRight - iLeft) * (iBottom - iTop)
        val union = (a.area + b.area - inter).coerceAtLeast(1e-6f)
        return (inter / union).coerceIn(0f, 1f)
    }

    fun toDeviceRect(screenWidth: Int, screenHeight: Int): Rect {
        val n = normalized()
        return PrecisionCoordinateMapper.mapNormalizedBoxToDevice(
            normLeft = n.left,
            normTop = n.top,
            normRight = n.right,
            normBottom = n.bottom,
            screenWidth = screenWidth.coerceAtLeast(1),
            screenHeight = screenHeight.coerceAtLeast(1)
        )
    }

    companion object {
        fun fromDeviceRect(rect: Rect, screenWidth: Int, screenHeight: Int): NormalizedBox {
            val w = screenWidth.coerceAtLeast(1).toFloat()
            val h = screenHeight.coerceAtLeast(1).toFloat()
            return NormalizedBox(
                left = (rect.left / w).coerceIn(0f, 1f),
                top = (rect.top / h).coerceIn(0f, 1f),
                right = (rect.right / w).coerceIn(0f, 1f),
                bottom = (rect.bottom / h).coerceIn(0f, 1f)
            ).normalized()
        }

        fun fromCenter(center: NormalizedPoint, halfWidth: Float = 0.04f, halfHeight: Float = 0.025f): NormalizedBox {
            val c = center.clamped()
            return NormalizedBox(
                left = (c.x - halfWidth).coerceIn(0f, 1f),
                top = (c.y - halfHeight).coerceIn(0f, 1f),
                right = (c.x + halfWidth).coerceIn(0f, 1f),
                bottom = (c.y + halfHeight).coerceIn(0f, 1f)
            )
        }
    }
}

data class VisionDetectionCandidate(
    val modelId: VisionModelId,
    val modelDisplayName: String = modelId.displayName,
    val screenshotId: String,
    val targetLabel: String = "",
    val targetRole: String = "",
    val normalizedCenter: NormalizedPoint? = null,
    val normalizedBox: NormalizedBox? = null,
    val rawConfidence: Float = 0f,
    val calibratedConfidence: Float = sanitizeConfidence(rawConfidence),
    val status: ModelDetectionStatus = ModelDetectionStatus.SUCCESS,
    val latencyMs: Long = 0L,
    val errorMessage: String? = null,
    val providerId: String? = null
) {
    val effectiveCenter: NormalizedPoint?
        get() = normalizedCenter?.clamped() ?: normalizedBox?.normalized()?.center

    val hasValidCoordinates: Boolean
        get() = status == ModelDetectionStatus.SUCCESS && effectiveCenter != null

    companion object {
        fun sanitizeConfidence(value: Float): Float {
            if (!value.isFinite()) return 0f
            return if (value > 1f && value <= 100f) {
                (value / 100f).coerceIn(0f, 1f)
            } else {
                value.coerceIn(0f, 1f)
            }
        }
    }
}

data class ConsensusConfig(
    val confidenceEpsilon: Float = 1e-4f,
    val veryLowConfidenceCeiling: Float = 0.30f,
    val lowConfidenceCeiling: Float = 0.50f,
    val highConfidenceFloor: Float = 0.75f,
    val centerToleranceNorm: Float = 0.06f,
    val boxIouTolerance: Float = 0.35f,
    val localModelTimeoutMs: Long = 12_000L,
    val cloudEscalationTimeoutMs: Long = 15_000L
)

data class OfflineConsensusEvaluation(
    val decision: ConsensusDecisionType,
    val requiresCloudEscalation: Boolean,
    val reasonCodes: List<ConsensusReasonCode>,
    val winningCandidate: VisionDetectionCandidate?,
    val coordinatesMatch: Boolean,
    val detail: String
)

data class AccessibilityMatchResult(
    val matched: Boolean,
    val matchedLabel: String = "",
    val matchedResourceId: String = "",
    val matchedClassName: String = "",
    val matchedBounds: Rect? = null,
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val matchScore: Float = 0f,
    val reason: String = ""
)

data class VisionConsensusOutcome(
    val screenshotId: String,
    val isStale: Boolean = false,
    val decision: ConsensusDecisionType,
    val reasonCodes: List<ConsensusReasonCode>,
    val winningCandidate: VisionDetectionCandidate?,
    val winningNormalizedCenter: NormalizedPoint?,
    val winningNormalizedBox: NormalizedBox?,
    val smolVlm2Result: VisionDetectionCandidate?,
    val florence2Result: VisionDetectionCandidate?,
    val cloudResult: VisionDetectionCandidate? = null,
    val accessibilityMatch: AccessibilityMatchResult? = null,
    val shouldBlindTapCoordinates: Boolean,
    val requiresFreshObservation: Boolean = false,
    val detail: String
)
