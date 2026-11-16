package com.example.visual

import com.example.agent.AnalyzedNode
import kotlin.math.abs

/**
 * Deterministic Multi-Model Vision Consensus Engine.
 *
 * Implements exact confidence & coordinate agreement rules across:
 * 1. SmolVLM2 2.2B (local VLM)
 * 2. Florence-2 (local ONNX vision/grounding)
 * 3. Configured Cloud Vision Provider (when escalated)
 * 4. Android Accessibility Hierarchy validation/fallback
 *
 * Rules summary (floating-point safe via `config.confidenceEpsilon`):
 * - Rule 2 (Precedence over Rule 1): Both < 0.30 AND equal confidence -> ESCALATE_TO_CLOUD (`EQUAL_VERY_LOW_CONFIDENCE`).
 * - Rule 1: Both < 0.50 (not Rule 2) AND coordinates agree -> Keep offline, choose higher-confidence coords (no averaging) (`LOW_CONFIDENCE_AGREEMENT`).
 * - Rule 3: Both < 0.50 AND coordinates disagree -> ESCALATE_TO_CLOUD (`LOW_CONFIDENCE_DISAGREEMENT`).
 * - Rule 6: Both in [0.30, 0.50] -> same coords = offline higher-confidence wins; different coords = ESCALATE_TO_CLOUD.
 * - Rule 4: Both > 0.50 (including [0.50..0.75] and > 0.75) AND coordinates agree -> Keep offline, choose higher-confidence coords (no averaging) (`OFFLINE_AGREEMENT`).
 * - Rule 5: Both > 0.50 (including [0.50..0.75] and > 0.75) AND coordinates disagree -> ESCALATE_TO_CLOUD (`HIGH_CONFIDENCE_DISAGREEMENT`, never auto-pick!).
 * - Rule 7: 2-of-3 comparison after Cloud escalation:
 *     * Cloud agrees with either offline (or all 3 agree) -> Use Cloud coordinates (`CLOUD_TWO_OF_THREE_AGREEMENT`).
 *     * Both offline agree while Cloud disagrees -> Use higher-confidence offline coordinates (`OFFLINE_TWO_OF_THREE_AGREEMENT`).
 *     * 3-way disagreement (or Cloud unavailable while offline disagreed/failed) -> Never blind-tap; fall back safely to Accessibility or request fresh screen capture.
 */
object VisionConsensusEngine {

    fun coordinatesAgree(
        a: VisionDetectionCandidate?,
        b: VisionDetectionCandidate?,
        config: ConsensusConfig = ConsensusConfig()
    ): Boolean {
        if (a == null || b == null) return false
        if (!a.hasValidCoordinates || !b.hasValidCoordinates) return false
        val centerA = a.effectiveCenter ?: return false
        val centerB = b.effectiveCenter ?: return false

        val dist = centerA.distanceTo(centerB)
        if (dist <= config.centerToleranceNorm + 1e-6f) {
            return true
        }

        val boxA = a.normalizedBox
        val boxB = b.normalizedBox
        if (boxA != null && boxB != null) {
            if (boxA.iou(boxB) >= config.boxIouTolerance - 1e-6f) {
                return true
            }
            if (boxA.contains(centerB) && boxB.contains(centerA)) {
                return true
            }
        }
        return false
    }

    fun evaluateOfflineConsensus(
        screenshotId: String,
        smolVlm2: VisionDetectionCandidate?,
        florence2: VisionDetectionCandidate?,
        config: ConsensusConfig = ConsensusConfig()
    ): OfflineConsensusEvaluation {
        val eps = config.confidenceEpsilon

        if (smolVlm2?.status == ModelDetectionStatus.STALE || florence2?.status == ModelDetectionStatus.STALE) {
            return OfflineConsensusEvaluation(
                decision = ConsensusDecisionType.REQUIRE_FRESH_OBSERVATION,
                requiresCloudEscalation = false,
                reasonCodes = listOf(ConsensusReasonCode.OBSERVATION_STALE),
                winningCandidate = null,
                coordinatesMatch = false,
                detail = "Offline vision candidates marked stale for screenshotId=$screenshotId."
            )
        }

        val smolValid = smolVlm2 != null && smolVlm2.hasValidCoordinates
        val florValid = florence2 != null && florence2.hasValidCoordinates

        // Partial or dual local model failure: never fabricate dual-model agreement.
        if (!smolValid || !florValid) {
            return OfflineConsensusEvaluation(
                decision = ConsensusDecisionType.ESCALATE_TO_CLOUD,
                requiresCloudEscalation = true,
                reasonCodes = listOf(ConsensusReasonCode.OFFLINE_MODEL_FAILURE),
                winningCandidate = null,
                coordinatesMatch = false,
                detail = "Offline dual-model consensus incomplete (SmolVLM2=${smolVlm2?.status ?: "MISSING"}, Florence2=${florence2?.status ?: "MISSING"}); escalating to cloud vision."
            )
        }

        val s = smolVlm2!!
        val f = florence2!!
        val cS = s.calibratedConfidence
        val cF = f.calibratedConfidence
        val coordsMatch = coordinatesAgree(s, f, config)

        val bothBelow30 = cS < (config.veryLowConfidenceCeiling - eps) && cF < (config.veryLowConfidenceCeiling - eps)
        val equalConfidence = abs(cS - cF) <= eps

        // Rule 2: Takes precedence over Rule 1 even if coordinates match!
        if (bothBelow30 && equalConfidence) {
            return OfflineConsensusEvaluation(
                decision = ConsensusDecisionType.ESCALATE_TO_CLOUD,
                requiresCloudEscalation = true,
                reasonCodes = listOf(ConsensusReasonCode.EQUAL_VERY_LOW_CONFIDENCE),
                winningCandidate = null,
                coordinatesMatch = coordsMatch,
                detail = "Rule 2: Both offline models below 30% ($cS, $cF) with equal confidence -> escalating to cloud."
            )
        }

        val bothStrictlyAbove50 = cS > (config.lowConfidenceCeiling + eps) && cF > (config.lowConfidenceCeiling + eps)
        val bothBelowOrAt50 = cS <= (config.lowConfidenceCeiling + eps) && cF <= (config.lowConfidenceCeiling + eps)
        val bothIn30To50 = cS >= (config.veryLowConfidenceCeiling - eps) &&
            cF >= (config.veryLowConfidenceCeiling - eps) &&
            bothBelowOrAt50

        val higherConfCandidate = pickHigherConfidenceCandidate(s, f, config)

        // Rule 4 & Rule 5: Both above 50% (including 50%-75% medium-high band and > 75% high band)
        if (bothStrictlyAbove50) {
            return if (coordsMatch) {
                OfflineConsensusEvaluation(
                    decision = ConsensusDecisionType.USE_OFFLINE_HIGH_AGREEMENT,
                    requiresCloudEscalation = false,
                    reasonCodes = listOf(ConsensusReasonCode.OFFLINE_AGREEMENT),
                    winningCandidate = higherConfCandidate,
                    coordinatesMatch = true,
                    detail = "Rule 4: Both offline models >50% ($cS, $cF) and agree on coordinates -> using ${higherConfCandidate.modelId.displayName}."
                )
            } else {
                OfflineConsensusEvaluation(
                    decision = ConsensusDecisionType.ESCALATE_TO_CLOUD,
                    requiresCloudEscalation = true,
                    reasonCodes = listOf(ConsensusReasonCode.HIGH_CONFIDENCE_DISAGREEMENT),
                    winningCandidate = null,
                    coordinatesMatch = false,
                    detail = "Rule 5: Both offline models >50% ($cS, $cF) but disagree on coordinates -> escalating to cloud (never auto-pick)."
                )
            }
        }

        // Rule 1, Rule 3, Rule 6: Both <= 50% (including [30%, 50%] and < 30% unequal)
        if (bothBelowOrAt50) {
            return if (coordsMatch) {
                val bandNote = if (bothIn30To50) "Rule 6 [30%-50%]" else "Rule 1 (<50%)"
                OfflineConsensusEvaluation(
                    decision = ConsensusDecisionType.USE_OFFLINE_LOW_CONF_AGREEMENT,
                    requiresCloudEscalation = false,
                    reasonCodes = listOf(ConsensusReasonCode.LOW_CONFIDENCE_AGREEMENT),
                    winningCandidate = higherConfCandidate,
                    coordinatesMatch = true,
                    detail = "$bandNote: Both offline models <=50% ($cS, $cF) and agree on coordinates -> using higher-confidence ${higherConfCandidate.modelId.displayName}."
                )
            } else {
                val bandNote = if (bothIn30To50) "Rule 6 [30%-50%]" else "Rule 3 (<50%)"
                OfflineConsensusEvaluation(
                    decision = ConsensusDecisionType.ESCALATE_TO_CLOUD,
                    requiresCloudEscalation = true,
                    reasonCodes = listOf(ConsensusReasonCode.LOW_CONFIDENCE_DISAGREEMENT),
                    winningCandidate = null,
                    coordinatesMatch = false,
                    detail = "$bandNote: Both offline models <=50% ($cS, $cF) and disagree on coordinates -> escalating to cloud."
                )
            }
        }

        // Mixed boundary band (one model <= 0.50f and the other > 0.50f)
        return if (coordsMatch) {
            OfflineConsensusEvaluation(
                decision = ConsensusDecisionType.USE_OFFLINE_HIGH_AGREEMENT,
                requiresCloudEscalation = false,
                reasonCodes = listOf(ConsensusReasonCode.OFFLINE_AGREEMENT),
                winningCandidate = higherConfCandidate,
                coordinatesMatch = true,
                detail = "Mixed offline confidence ($cS, $cF) with coordinate agreement -> using higher-confidence ${higherConfCandidate.modelId.displayName}."
            )
        } else {
            OfflineConsensusEvaluation(
                decision = ConsensusDecisionType.ESCALATE_TO_CLOUD,
                requiresCloudEscalation = true,
                reasonCodes = listOf(ConsensusReasonCode.HIGH_CONFIDENCE_DISAGREEMENT),
                winningCandidate = null,
                coordinatesMatch = false,
                detail = "Mixed offline confidence ($cS, $cF) with coordinate disagreement -> escalating to cloud."
            )
        }
    }

    fun resolveWithCloudVerification(
        screenshotId: String,
        targetQuery: String,
        offlineEval: OfflineConsensusEvaluation,
        smolVlm2: VisionDetectionCandidate?,
        florence2: VisionDetectionCandidate?,
        cloudCandidate: VisionDetectionCandidate?,
        cloudUnavailableReason: String? = null,
        accessibilityNodes: List<AnalyzedNode> = emptyList(),
        screenWidth: Int = 1080,
        screenHeight: Int = 2400,
        isObservationStale: Boolean = false,
        config: ConsensusConfig = ConsensusConfig()
    ): VisionConsensusOutcome {
        if (isObservationStale || offlineEval.decision == ConsensusDecisionType.REQUIRE_FRESH_OBSERVATION) {
            return VisionConsensusOutcome(
                screenshotId = screenshotId,
                isStale = true,
                decision = ConsensusDecisionType.REQUIRE_FRESH_OBSERVATION,
                reasonCodes = listOf(ConsensusReasonCode.OBSERVATION_STALE),
                winningCandidate = null,
                winningNormalizedCenter = null,
                winningNormalizedBox = null,
                smolVlm2Result = smolVlm2,
                florence2Result = florence2,
                cloudResult = cloudCandidate,
                accessibilityMatch = null,
                shouldBlindTapCoordinates = false,
                requiresFreshObservation = true,
                detail = "Observation $screenshotId became stale before final consensus; fresh screenshot required."
            )
        }

        // Offline decision required no cloud escalation -> proceed directly to Accessibility matching/validation
        if (!offlineEval.requiresCloudEscalation && offlineEval.winningCandidate != null) {
            val winner = offlineEval.winningCandidate
            val a11yMatch = matchAccessibilityTarget(
                nodes = accessibilityNodes,
                targetLabel = targetQuery.ifBlank { winner.targetLabel },
                predictedBox = winner.normalizedBox,
                predictedCenter = winner.effectiveCenter,
                screenWidth = screenWidth,
                screenHeight = screenHeight
            )
            val codes = offlineEval.reasonCodes + if (a11yMatch.matched) {
                ConsensusReasonCode.ACCESSIBILITY_MATCH_FOUND
            } else {
                ConsensusReasonCode.ACCESSIBILITY_MATCH_FAILED
            }
            return VisionConsensusOutcome(
                screenshotId = screenshotId,
                isStale = false,
                decision = offlineEval.decision,
                reasonCodes = codes,
                winningCandidate = winner,
                winningNormalizedCenter = winner.effectiveCenter,
                winningNormalizedBox = winner.normalizedBox,
                smolVlm2Result = smolVlm2,
                florence2Result = florence2,
                cloudResult = null,
                accessibilityMatch = a11yMatch,
                shouldBlindTapCoordinates = false,
                requiresFreshObservation = false,
                detail = "${offlineEval.detail} | Accessibility=${a11yMatch.reason}"
            )
        }

        // Cloud escalation was required
        val reasons = offlineEval.reasonCodes.toMutableList()

        if (cloudCandidate == null || !cloudCandidate.hasValidCoordinates) {
            val unavailableCode = if (cloudUnavailableReason != null || cloudCandidate == null || cloudCandidate.status == ModelDetectionStatus.UNAVAILABLE) {
                ConsensusReasonCode.CLOUD_PROVIDER_UNAVAILABLE
            } else {
                ConsensusReasonCode.CLOUD_VERIFICATION_FAILED
            }
            reasons.add(unavailableCode)

            // Safe fallback ONLY via Accessibility semantic match — never blindly trust disagreed offline coords,
            // and never fabricate a single-model failure as dual consensus unless Accessibility verifies it.
            val hintCandidate = listOfNotNull(smolVlm2, florence2)
                .filter { it.hasValidCoordinates }
                .maxByOrNull { it.calibratedConfidence }
            val a11yMatch = matchAccessibilityTarget(
                nodes = accessibilityNodes,
                targetLabel = targetQuery,
                predictedBox = null,
                predictedCenter = null,
                screenWidth = screenWidth,
                screenHeight = screenHeight
            )
            return if (a11yMatch.matched) {
                reasons.add(ConsensusReasonCode.ACCESSIBILITY_MATCH_FOUND)
                val matchedNormBox = a11yMatch.matchedBounds?.let {
                    NormalizedBox.fromDeviceRect(it, screenWidth, screenHeight)
                }
                val a11yCandidate = VisionDetectionCandidate(
                    modelId = VisionModelId.ACCESSIBILITY_HIERARCHY,
                    screenshotId = screenshotId,
                    targetLabel = a11yMatch.matchedLabel.ifBlank { targetQuery },
                    normalizedCenter = matchedNormBox?.center,
                    normalizedBox = matchedNormBox,
                    rawConfidence = a11yMatch.matchScore,
                    status = ModelDetectionStatus.SUCCESS
                )
                VisionConsensusOutcome(
                    screenshotId = screenshotId,
                    isStale = false,
                    decision = ConsensusDecisionType.FALLBACK_ACCESSIBILITY_ONLY,
                    reasonCodes = reasons.distinct(),
                    winningCandidate = a11yCandidate,
                    winningNormalizedCenter = a11yCandidate.effectiveCenter,
                    winningNormalizedBox = matchedNormBox,
                    smolVlm2Result = smolVlm2,
                    florence2Result = florence2,
                    cloudResult = cloudCandidate,
                    accessibilityMatch = a11yMatch,
                    shouldBlindTapCoordinates = false,
                    requiresFreshObservation = false,
                    detail = "Cloud verification unavailable (${cloudUnavailableReason ?: cloudCandidate?.errorMessage ?: "NO_PROVIDER"}); matched live Accessibility node '${a11yMatch.matchedLabel}'."
                )
            } else {
                reasons.add(ConsensusReasonCode.ACCESSIBILITY_MATCH_FAILED)
                VisionConsensusOutcome(
                    screenshotId = screenshotId,
                    isStale = false,
                    decision = ConsensusDecisionType.UNAVAILABLE_OR_FAILED,
                    reasonCodes = reasons.distinct(),
                    winningCandidate = null,
                    winningNormalizedCenter = null,
                    winningNormalizedBox = null,
                    smolVlm2Result = smolVlm2,
                    florence2Result = florence2,
                    cloudResult = cloudCandidate,
                    accessibilityMatch = a11yMatch,
                    shouldBlindTapCoordinates = false,
                    requiresFreshObservation = true,
                    detail = "Cloud verification unavailable (${cloudUnavailableReason ?: cloudCandidate?.errorMessage ?: "UNAVAILABLE"}) and no Accessibility match found; refusing blind coordinate tap."
                )
            }
        }

        // Rule 7: Compare {SmolVLM2, Florence-2, Cloud}
        val cloudAgreesWithSmol = coordinatesAgree(cloudCandidate, smolVlm2, config)
        val cloudAgreesWithFlorence = coordinatesAgree(cloudCandidate, florence2, config)
        val offlinePairAgree = coordinatesAgree(smolVlm2, florence2, config)

        if (cloudAgreesWithSmol || cloudAgreesWithFlorence) {
            reasons.add(ConsensusReasonCode.CLOUD_TWO_OF_THREE_AGREEMENT)
            val a11yMatch = matchAccessibilityTarget(
                nodes = accessibilityNodes,
                targetLabel = targetQuery.ifBlank { cloudCandidate.targetLabel },
                predictedBox = cloudCandidate.normalizedBox,
                predictedCenter = cloudCandidate.effectiveCenter,
                screenWidth = screenWidth,
                screenHeight = screenHeight
            )
            reasons.add(if (a11yMatch.matched) ConsensusReasonCode.ACCESSIBILITY_MATCH_FOUND else ConsensusReasonCode.ACCESSIBILITY_MATCH_FAILED)
            val agreedWith = buildList {
                if (cloudAgreesWithSmol) add("SmolVLM2")
                if (cloudAgreesWithFlorence) add("Florence-2")
            }.joinToString("+")
            return VisionConsensusOutcome(
                screenshotId = screenshotId,
                isStale = false,
                decision = ConsensusDecisionType.USE_CLOUD_TWO_OF_THREE_CONSENSUS,
                reasonCodes = reasons.distinct(),
                winningCandidate = cloudCandidate,
                winningNormalizedCenter = cloudCandidate.effectiveCenter,
                winningNormalizedBox = cloudCandidate.normalizedBox,
                smolVlm2Result = smolVlm2,
                florence2Result = florence2,
                cloudResult = cloudCandidate,
                accessibilityMatch = a11yMatch,
                shouldBlindTapCoordinates = false,
                requiresFreshObservation = false,
                detail = "Rule 7: 2-of-3 consensus reached with Cloud + $agreedWith -> preferring Cloud Vision (${cloudCandidate.providerId ?: "CLOUD"}) coordinates."
            )
        }

        if (offlinePairAgree && smolVlm2 != null && florence2 != null) {
            reasons.add(ConsensusReasonCode.OFFLINE_TWO_OF_THREE_AGREEMENT)
            val offlineWinner = pickHigherConfidenceCandidate(smolVlm2, florence2, config)
            val a11yMatch = matchAccessibilityTarget(
                nodes = accessibilityNodes,
                targetLabel = targetQuery.ifBlank { offlineWinner.targetLabel },
                predictedBox = offlineWinner.normalizedBox,
                predictedCenter = offlineWinner.effectiveCenter,
                screenWidth = screenWidth,
                screenHeight = screenHeight
            )
            reasons.add(if (a11yMatch.matched) ConsensusReasonCode.ACCESSIBILITY_MATCH_FOUND else ConsensusReasonCode.ACCESSIBILITY_MATCH_FAILED)
            return VisionConsensusOutcome(
                screenshotId = screenshotId,
                isStale = false,
                decision = ConsensusDecisionType.USE_OFFLINE_TWO_OF_THREE_CONSENSUS,
                reasonCodes = reasons.distinct(),
                winningCandidate = offlineWinner,
                winningNormalizedCenter = offlineWinner.effectiveCenter,
                winningNormalizedBox = offlineWinner.normalizedBox,
                smolVlm2Result = smolVlm2,
                florence2Result = florence2,
                cloudResult = cloudCandidate,
                accessibilityMatch = a11yMatch,
                shouldBlindTapCoordinates = false,
                requiresFreshObservation = false,
                detail = "Rule 7: 2-of-3 consensus reached by SmolVLM2 + Florence-2 (cloud disagreed) -> using stronger offline ${offlineWinner.modelId.displayName}."
            )
        }

        // 3-way disagreement (SmolVLM2 != Florence-2 != Cloud) or 1 offline failed + remaining offline != Cloud -> NEVER blind tap!
        reasons.add(ConsensusReasonCode.THREE_WAY_DISAGREEMENT)
        val a11yMatch = matchAccessibilityTarget(
            nodes = accessibilityNodes,
            targetLabel = targetQuery,
            predictedBox = null,
            predictedCenter = null,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
        return if (a11yMatch.matched) {
            reasons.add(ConsensusReasonCode.ACCESSIBILITY_MATCH_FOUND)
            val a11yNormBox = a11yMatch.matchedBounds?.let {
                NormalizedBox.fromDeviceRect(it, screenWidth, screenHeight)
            }
            val a11yWinner = VisionDetectionCandidate(
                modelId = VisionModelId.ACCESSIBILITY_HIERARCHY,
                screenshotId = screenshotId,
                targetLabel = a11yMatch.matchedLabel.ifBlank { targetQuery },
                normalizedCenter = a11yNormBox?.center,
                normalizedBox = a11yNormBox,
                rawConfidence = a11yMatch.matchScore,
                status = ModelDetectionStatus.SUCCESS
            )
            VisionConsensusOutcome(
                screenshotId = screenshotId,
                isStale = false,
                decision = ConsensusDecisionType.FALLBACK_ACCESSIBILITY_ONLY,
                reasonCodes = reasons.distinct(),
                winningCandidate = a11yWinner,
                winningNormalizedCenter = a11yWinner.effectiveCenter,
                winningNormalizedBox = a11yNormBox,
                smolVlm2Result = smolVlm2,
                florence2Result = florence2,
                cloudResult = cloudCandidate,
                accessibilityMatch = a11yMatch,
                shouldBlindTapCoordinates = false,
                requiresFreshObservation = false,
                detail = "Rule 7: 3-way vision disagreement; falling back safely to Accessibility node '${a11yMatch.matchedLabel}' (no blind tap)."
            )
        } else {
            reasons.add(ConsensusReasonCode.ACCESSIBILITY_MATCH_FAILED)
            VisionConsensusOutcome(
                screenshotId = screenshotId,
                isStale = false,
                decision = ConsensusDecisionType.REQUIRE_FRESH_OBSERVATION,
                reasonCodes = reasons.distinct(),
                winningCandidate = null,
                winningNormalizedCenter = null,
                winningNormalizedBox = null,
                smolVlm2Result = smolVlm2,
                florence2Result = florence2,
                cloudResult = cloudCandidate,
                accessibilityMatch = a11yMatch,
                shouldBlindTapCoordinates = false,
                requiresFreshObservation = true,
                detail = "Rule 7: No 2-of-3 agreement and no Accessibility match found; refusing blind coordinate tap and requesting fresh screen observation."
            )
        }
    }

    fun matchAccessibilityTarget(
        nodes: List<AnalyzedNode>,
        targetLabel: String,
        predictedBox: NormalizedBox?,
        predictedCenter: NormalizedPoint?,
        screenWidth: Int,
        screenHeight: Int
    ): AccessibilityMatchResult {
        if (nodes.isEmpty()) {
            return AccessibilityMatchResult(matched = false, reason = "Accessibility hierarchy empty or disabled")
        }

        val cleanQuery = targetLabel
            .lowercase()
            .replace(Regex("^(?:tap|click|press|open|select|search|type\\s+in|on)\\s+"), "")
            .trim()
        val queryTokens = cleanQuery.split(Regex("\\s+")).filter { it.length >= 2 }

        var bestNode: AnalyzedNode? = null
        var bestScore = 0f

        for (node in nodes) {
            if (node.bounds.width() <= 0 || node.bounds.height() <= 0) continue
            val isGiantContainer = node.bounds.width() > screenWidth * 0.94 && node.bounds.height() > screenHeight * 0.85
            if (isGiantContainer && !node.isEditable && !node.isClickable) continue

            val nodeBox = NormalizedBox.fromDeviceRect(node.bounds, screenWidth, screenHeight)
            val nodeCenter = nodeBox.center
            val combinedText = listOf(
                node.text,
                node.contentDescription,
                node.resourceId.substringAfterLast("/")
            ).filter { it.isNotBlank() }.joinToString(" ").lowercase()

            var textScore = 0f
            if (cleanQuery.isNotBlank() && combinedText.isNotBlank()) {
                when {
                    combinedText == cleanQuery -> textScore = 1.0f
                    combinedText.contains(cleanQuery) -> textScore = 0.85f
                    queryTokens.isNotEmpty() -> {
                        val hits = queryTokens.count { combinedText.contains(it) }
                        if (hits > 0) textScore = (hits.toFloat() / queryTokens.size) * 0.75f
                    }
                }
            }

            var spatialScore = 0f
            if (predictedBox != null) {
                val iou = predictedBox.iou(nodeBox)
                val centerInBox = if (predictedCenter != null && nodeBox.contains(predictedCenter, margin = 0.01f)) 0.85f else 0f
                val dist = predictedCenter?.distanceTo(nodeCenter) ?: predictedBox.center.distanceTo(nodeCenter)
                val distScore = (1f - (dist / 0.25f)).coerceIn(0f, 1f)
                spatialScore = maxOf(iou, centerInBox, distScore * 0.8f)
            } else if (predictedCenter != null) {
                val contains = if (nodeBox.contains(predictedCenter, margin = 0.01f)) 0.90f else 0f
                val dist = predictedCenter.distanceTo(nodeCenter)
                val distScore = (1f - (dist / 0.20f)).coerceIn(0f, 1f)
                spatialScore = maxOf(contains, distScore * 0.85f)
            }

            // Do not allow purely unrelated text when predicted coordinates are present without reasonable spatial proximity,
            // and when no predicted coordinates are provided (fallback mode), require strong semantic text match.
            val combinedScore = when {
                predictedCenter != null || predictedBox != null -> {
                    val interactiveBonus = if (node.isClickable || node.isEditable) 0.12f else 0f
                    if (cleanQuery.isBlank()) {
                        (spatialScore + interactiveBonus).coerceAtMost(1f)
                    } else if (spatialScore < 0.20f && textScore < 0.65f) {
                        0f
                    } else {
                        (spatialScore * 0.60f + textScore * 0.35f + interactiveBonus).coerceAtMost(1f)
                    }
                }
                else -> {
                    val interactiveBonus = if (node.isClickable || node.isEditable) 0.15f else 0f
                    if (textScore >= 0.55f) (textScore * 0.85f + interactiveBonus).coerceAtMost(1f) else 0f
                }
            }

            if (combinedScore > bestScore) {
                bestScore = combinedScore
                bestNode = node
            }
        }

        val threshold = if (predictedCenter != null || predictedBox != null) 0.48f else 0.65f
        return if (bestNode != null && bestScore >= threshold) {
            val label = bestNode.text.ifBlank { bestNode.contentDescription.ifBlank { bestNode.resourceId.substringAfterLast("/") } }
            AccessibilityMatchResult(
                matched = true,
                matchedLabel = label,
                matchedResourceId = bestNode.resourceId,
                matchedClassName = bestNode.className,
                matchedBounds = bestNode.bounds,
                isClickable = bestNode.isClickable,
                isEditable = bestNode.isEditable,
                matchScore = bestScore,
                reason = "Matched node '$label' (score=${"%.2f".format(bestScore)}, clickable=${bestNode.isClickable})"
            )
        } else {
            AccessibilityMatchResult(
                matched = false,
                matchScore = bestScore,
                reason = "No Accessibility node passed threshold $threshold (bestScore=${"%.2f".format(bestScore)})"
            )
        }
    }

    private fun pickHigherConfidenceCandidate(
        a: VisionDetectionCandidate,
        b: VisionDetectionCandidate,
        config: ConsensusConfig
    ): VisionDetectionCandidate {
        return when {
            a.calibratedConfidence > b.calibratedConfidence + config.confidenceEpsilon -> a
            b.calibratedConfidence > a.calibratedConfidence + config.confidenceEpsilon -> b
            // Deterministic tie-break: SmolVLM2 GUI-tuned VLM first, otherwise non-zero/earlier
            a.modelId == VisionModelId.SMOLVLM2_2_2B -> a
            else -> b
        }
    }
}
