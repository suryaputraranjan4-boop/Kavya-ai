package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.example.agent.AnalyzedNode
import com.example.ai.offline.Gemma4E4BEngine
import com.example.ai.tts.VoiceEngine
import com.example.ai.tts.VoiceEngineManager
import com.example.orchestrator.AiActionDiagnostics
import com.example.orchestrator.ProviderInvocationRequest
import com.example.orchestrator.ProviderInvocationResult
import com.example.orchestrator.ProviderTransport
import com.example.orchestrator.ScreenObservationCoordinator
import com.example.orchestrator.ScreenObservationRecord
import com.example.orchestrator.SelectedProviderClient
import com.example.utils.AppPreferences
import com.example.visual.CloudVisionEscalationRunner
import com.example.visual.ConsensusConfig
import com.example.visual.ConsensusDecisionType
import com.example.visual.ConsensusReasonCode
import com.example.visual.LocalVisionDetectionRunner
import com.example.visual.ModelDetectionStatus
import com.example.visual.MultiSignalFusionEngine
import com.example.visual.NormalizedBox
import com.example.visual.NormalizedPoint
import com.example.visual.PreActionVerifier
import com.example.visual.VisionConsensusCoordinator
import com.example.visual.VisionConsensusEngine
import com.example.visual.VisionDetectionCandidate
import com.example.visual.VisionModelId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MultiModelVisionConsensusTest {

    private lateinit var context: Context
    private lateinit var sampleObs: ScreenObservationRecord

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("kavya_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("kavya_app_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        SelectedProviderClient.testTransport = null
        SelectedProviderClient.testNetworkConnectedOverride = null
        ScreenObservationCoordinator.clearLatestObservation()
        AiActionDiagnostics.clear()

        val bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 75, baos)
        bmp.recycle()

        sampleObs = ScreenObservationRecord(
            screenshotId = "scr_consensus_001",
            capturedAtMs = System.currentTimeMillis(),
            ttlMs = 30_000L,
            displayWidthPx = 1080,
            displayHeightPx = 2400,
            bitmapWidth = 1080,
            bitmapHeight = 2400,
            orientation = 1,
            densityDpi = 420,
            foregroundPackage = "com.google.android.youtube",
            uiTreeSummary = "Subscribe | Like | Search",
            imageBytes = baos.toByteArray(),
            source = "TEST"
        )
    }

    @After
    fun tearDown() {
        SelectedProviderClient.testTransport = null
        SelectedProviderClient.testNetworkConnectedOverride = null
        ScreenObservationCoordinator.clearLatestObservation()
    }

    private fun cand(
        modelId: VisionModelId,
        x: Float,
        y: Float,
        conf: Float,
        label: String = "Subscribe",
        shotId: String = sampleObs.screenshotId,
        providerId: String? = null
    ) = VisionDetectionCandidate(
        modelId = modelId,
        screenshotId = shotId,
        targetLabel = label,
        normalizedCenter = NormalizedPoint(x, y),
        normalizedBox = NormalizedBox.fromCenter(NormalizedPoint(x, y), 0.05f, 0.025f),
        rawConfidence = conf,
        status = ModelDetectionStatus.SUCCESS,
        providerId = providerId
    )

    private fun node(label: String, rect: Rect, clickable: Boolean = true): AnalyzedNode {
        return AnalyzedNode(
            node = AccessibilityNodeInfo.obtain(),
            text = label,
            contentDescription = label,
            resourceId = "com.google.android.youtube:id/${label.lowercase().replace(" ", "_")}",
            className = "android.widget.Button",
            bounds = rect,
            isClickable = clickable,
            isEditable = false,
            isScrollable = false
        )
    }

    // =========================================================================
    // 1. CONSENSUS RULES 1 - 7 DETERMINISTIC MATRIX
    // =========================================================================

    @Test
    fun rule1_bothBelow50AndCoordinatesMatch_usesHigherConfidenceOfflineWithoutCloudOrAveraging() = runBlocking {
        val cloudCalls = AtomicInteger(0)
        val smolCand = cand(VisionModelId.SMOLVLM2_2_2B, x = 0.72f, y = 0.18f, conf = 0.44f)
        val florCand = cand(VisionModelId.FLORENCE_2, x = 0.73f, y = 0.185f, conf = 0.27f)

        val coordinator = VisionConsensusCoordinator(
            context = context,
            smolVlm2Runner = LocalVisionDetectionRunner { obs, _, _ -> smolCand.copy(screenshotId = obs.screenshotId) },
            florence2Runner = LocalVisionDetectionRunner { obs, _, _ -> florCand.copy(screenshotId = obs.screenshotId) },
            cloudRunner = CloudVisionEscalationRunner { _, obs, _, _, _ ->
                cloudCalls.incrementAndGet()
                cand(VisionModelId.CLOUD_VISION, 0.5f, 0.5f, 0.9f, shotId = obs.screenshotId)
            },
            accessibilityNodesProvider = { listOf(node("Subscribe", Rect(700, 390, 860, 480))) }
        )

        val outcome = coordinator.resolveTargetWithConsensus(sampleObs, "Subscribe")

        assertEquals(0, cloudCalls.get())
        assertEquals(ConsensusDecisionType.USE_OFFLINE_LOW_CONF_AGREEMENT, outcome.decision)
        assertTrue(outcome.reasonCodes.contains(ConsensusReasonCode.LOW_CONFIDENCE_AGREEMENT))
        assertEquals(VisionModelId.SMOLVLM2_2_2B, outcome.winningCandidate?.modelId)
        // Must pick higher-confidence coords directly (0.72, 0.18), NEVER average (0.725, 0.1825)
        assertEquals(0.72f, outcome.winningNormalizedCenter!!.x, 0.0001f)
        assertEquals(0.18f, outcome.winningNormalizedCenter!!.y, 0.0001f)
        assertTrue(outcome.accessibilityMatch?.matched == true)
        assertFalse(outcome.shouldBlindTapCoordinates)

        // PreActionVerifier must approve corroborated Rule 1 consensus result
        val unified = MultiSignalFusionEngine.buildCandidateFromConsensus(outcome, 1080, 2400)!!
        val ver = PreActionVerifier.verifyCandidate(PreActionVerifier.IntendedActionKind.TAP_TARGET, "Subscribe", listOf(unified))
        assertTrue(ver.approved)
    }

    @Test
    fun rule2_bothBelow30AndEqualConfidence_evenWithMatchingCoordinates_escalatesToCloud() = runBlocking {
        val cloudCalls = AtomicInteger(0)
        val smolCand = cand(VisionModelId.SMOLVLM2_2_2B, x = 0.50f, y = 0.40f, conf = 0.22f)
        val florCand = cand(VisionModelId.FLORENCE_2, x = 0.505f, y = 0.402f, conf = 0.22004f) // within 1e-4 epsilon

        val coordinator = VisionConsensusCoordinator(
            context = context,
            smolVlm2Runner = LocalVisionDetectionRunner { _, _, _ -> smolCand },
            florence2Runner = LocalVisionDetectionRunner { _, _, _ -> florCand },
            cloudRunner = CloudVisionEscalationRunner { _, _, _, _, _ ->
                cloudCalls.incrementAndGet()
                cand(VisionModelId.CLOUD_VISION, x = 0.502f, y = 0.401f, conf = 0.91f, providerId = "GEMINI:gemini-2.5-flash")
            }
        )

        val outcome = coordinator.resolveTargetWithConsensus(sampleObs, "Subscribe")

        assertEquals("Rule 2 must override Rule 1 and escalate to cloud", 1, cloudCalls.get())
        assertTrue(outcome.reasonCodes.contains(ConsensusReasonCode.EQUAL_VERY_LOW_CONFIDENCE))
        assertEquals(ConsensusDecisionType.USE_CLOUD_TWO_OF_THREE_CONSENSUS, outcome.decision)
        assertEquals(VisionModelId.CLOUD_VISION, outcome.winningCandidate?.modelId)
    }

    @Test
    fun rule3_bothBelow50AndDifferentCoordinates_escalatesToCloud() = runBlocking {
        val cloudCalls = AtomicInteger(0)
        val smolCand = cand(VisionModelId.SMOLVLM2_2_2B, x = 0.20f, y = 0.20f, conf = 0.35f)
        val florCand = cand(VisionModelId.FLORENCE_2, x = 0.80f, y = 0.80f, conf = 0.25f)

        val coordinator = VisionConsensusCoordinator(
            context = context,
            smolVlm2Runner = LocalVisionDetectionRunner { _, _, _ -> smolCand },
            florence2Runner = LocalVisionDetectionRunner { _, _, _ -> florCand },
            cloudRunner = CloudVisionEscalationRunner { _, _, _, _, _ ->
                cloudCalls.incrementAndGet()
                cand(VisionModelId.CLOUD_VISION, x = 0.81f, y = 0.79f, conf = 0.93f, providerId = "GEMINI:gemini-2.5-flash")
            }
        )

        val outcome = coordinator.resolveTargetWithConsensus(sampleObs, "Subscribe")

        assertEquals(1, cloudCalls.get())
        assertTrue(outcome.reasonCodes.contains(ConsensusReasonCode.LOW_CONFIDENCE_DISAGREEMENT))
        assertTrue(outcome.reasonCodes.contains(ConsensusReasonCode.CLOUD_TWO_OF_THREE_AGREEMENT))
        assertEquals(ConsensusDecisionType.USE_CLOUD_TWO_OF_THREE_CONSENSUS, outcome.decision)
        assertEquals(0.81f, outcome.winningNormalizedCenter!!.x, 0.001f)
    }

    @Test
    fun rule4_bothAbove50AndCoordinatesMatch_keepsOfflineHigherConfidenceWithoutCloud() = runBlocking {
        val cloudCalls = AtomicInteger(0)
        val smolCand = cand(VisionModelId.SMOLVLM2_2_2B, x = 0.30f, y = 0.70f, conf = 0.68f)
        val florCand = cand(VisionModelId.FLORENCE_2, x = 0.31f, y = 0.71f, conf = 0.84f)

        val coordinator = VisionConsensusCoordinator(
            context = context,
            smolVlm2Runner = LocalVisionDetectionRunner { _, _, _ -> smolCand },
            florence2Runner = LocalVisionDetectionRunner { _, _, _ -> florCand },
            cloudRunner = CloudVisionEscalationRunner { _, _, _, _, _ ->
                cloudCalls.incrementAndGet()
                cand(VisionModelId.CLOUD_VISION, 0.5f, 0.5f, 0.95f)
            }
        )

        val outcome = coordinator.resolveTargetWithConsensus(sampleObs, "Subscribe")

        assertEquals(0, cloudCalls.get())
        assertEquals(ConsensusDecisionType.USE_OFFLINE_HIGH_AGREEMENT, outcome.decision)
        assertTrue(outcome.reasonCodes.contains(ConsensusReasonCode.OFFLINE_AGREEMENT))
        // Florence-2 had higher confidence (0.84 vs 0.68), so Florence-2's exact coords must win without averaging
        assertEquals(VisionModelId.FLORENCE_2, outcome.winningCandidate?.modelId)
        assertEquals(0.31f, outcome.winningNormalizedCenter!!.x, 0.0001f)
        assertEquals(0.71f, outcome.winningNormalizedCenter!!.y, 0.0001f)
    }

    @Test
    fun rule5_bothHighConfidenceBandsIncluding50To75AndAbove75Disagree_alwaysEscalatesToCloud() = runBlocking {
        // Case A: 50% - 75% medium-high band disagreement
        val cloudCallsA = AtomicInteger(0)
        val coordMedHigh = VisionConsensusCoordinator(
            context = context,
            smolVlm2Runner = LocalVisionDetectionRunner { _, _, _ -> cand(VisionModelId.SMOLVLM2_2_2B, 0.15f, 0.20f, 0.62f) },
            florence2Runner = LocalVisionDetectionRunner { _, _, _ -> cand(VisionModelId.FLORENCE_2, 0.85f, 0.82f, 0.74f) },
            cloudRunner = CloudVisionEscalationRunner { _, _, _, _, _ ->
                cloudCallsA.incrementAndGet()
                cand(VisionModelId.CLOUD_VISION, 0.14f, 0.21f, 0.90f, providerId = "OPENROUTER:qwen-vl")
            }
        )
        val outA = coordMedHigh.resolveTargetWithConsensus(sampleObs.copy(screenshotId = "scr_r5a"), "Like")
        assertEquals(1, cloudCallsA.get())
        assertTrue(outA.reasonCodes.contains(ConsensusReasonCode.HIGH_CONFIDENCE_DISAGREEMENT))
        assertEquals(ConsensusDecisionType.USE_CLOUD_TWO_OF_THREE_CONSENSUS, outA.decision)

        // Case B: > 75% high confidence disagreement must STILL escalate to cloud (never auto-pick stronger offline)
        val cloudCallsB = AtomicInteger(0)
        val coordVeryHigh = VisionConsensusCoordinator(
            context = context,
            smolVlm2Runner = LocalVisionDetectionRunner { _, _, _ -> cand(VisionModelId.SMOLVLM2_2_2B, 0.18f, 0.22f, 0.94f) },
            florence2Runner = LocalVisionDetectionRunner { _, _, _ -> cand(VisionModelId.FLORENCE_2, 0.79f, 0.81f, 0.88f) },
            cloudRunner = CloudVisionEscalationRunner { _, _, _, _, _ ->
                cloudCallsB.incrementAndGet()
                cand(VisionModelId.CLOUD_VISION, 0.78f, 0.80f, 0.96f, providerId = "GEMINI:gemini-2.5-flash")
            }
        )
        val outB = coordVeryHigh.resolveTargetWithConsensus(sampleObs.copy(screenshotId = "scr_r5b"), "Search")
        assertEquals(1, cloudCallsB.get())
        assertTrue(outB.reasonCodes.contains(ConsensusReasonCode.HIGH_CONFIDENCE_DISAGREEMENT))
        assertEquals(0.78f, outB.winningNormalizedCenter!!.x, 0.001f)
    }

    @Test
    fun rule6_intermediate30To50Band_sameCoordsStayOfflineAndDifferentCoordsEscalate() = runBlocking {
        // Same coordinates in [0.30, 0.50], even equal confidence 0.35 == 0.35 -> Rule 2 does NOT trigger because >= 0.30!
        val cloudCallsSame = AtomicInteger(0)
        val coordSame = VisionConsensusCoordinator(
            context = context,
            smolVlm2Runner = LocalVisionDetectionRunner { _, _, _ -> cand(VisionModelId.SMOLVLM2_2_2B, 0.45f, 0.45f, 0.35f) },
            florence2Runner = LocalVisionDetectionRunner { _, _, _ -> cand(VisionModelId.FLORENCE_2, 0.455f, 0.452f, 0.35f) },
            cloudRunner = CloudVisionEscalationRunner { _, _, _, _, _ ->
                cloudCallsSame.incrementAndGet()
                cand(VisionModelId.CLOUD_VISION, 0.45f, 0.45f, 0.9f)
            }
        )
        val outSame = coordSame.resolveTargetWithConsensus(sampleObs.copy(screenshotId = "scr_r6_same"), "Search")
        assertEquals(0, cloudCallsSame.get())
        assertEquals(ConsensusDecisionType.USE_OFFLINE_LOW_CONF_AGREEMENT, outSame.decision)

        // Different coordinates in [0.30, 0.50] -> escalate to cloud
        val cloudCallsDiff = AtomicInteger(0)
        val coordDiff = VisionConsensusCoordinator(
            context = context,
            smolVlm2Runner = LocalVisionDetectionRunner { _, _, _ -> cand(VisionModelId.SMOLVLM2_2_2B, 0.25f, 0.25f, 0.48f) },
            florence2Runner = LocalVisionDetectionRunner { _, _, _ -> cand(VisionModelId.FLORENCE_2, 0.75f, 0.75f, 0.41f) },
            cloudRunner = CloudVisionEscalationRunner { _, _, _, _, _ ->
                cloudCallsDiff.incrementAndGet()
                cand(VisionModelId.CLOUD_VISION, 0.255f, 0.252f, 0.90f, providerId = "GEMINI")
            }
        )
        val outDiff = coordDiff.resolveTargetWithConsensus(sampleObs.copy(screenshotId = "scr_r6_diff"), "Search")
        assertEquals(1, cloudCallsDiff.get())
        assertEquals(ConsensusDecisionType.USE_CLOUD_TWO_OF_THREE_CONSENSUS, outDiff.decision)
    }

    @Test
    fun rule7_twoOfThreeOfflineAgreementOverCloud_andThreeWayDisagreementSafeFallback() = runBlocking {
        // Case A: Rule 2 escalates with matching offline coords (both < 0.30 equal), Cloud disagrees -> 2-of-3 offline wins!
        val coordOfflinePair = VisionConsensusCoordinator(
            context = context,
            smolVlm2Runner = LocalVisionDetectionRunner { _, _, _ -> cand(VisionModelId.SMOLVLM2_2_2B, 0.22f, 0.66f, 0.24f) },
            florence2Runner = LocalVisionDetectionRunner { _, _, _ -> cand(VisionModelId.FLORENCE_2, 0.225f, 0.662f, 0.24f) },
            cloudRunner = CloudVisionEscalationRunner { _, _, _, _, _ ->
                cand(VisionModelId.CLOUD_VISION, 0.88f, 0.12f, 0.70f, providerId = "CUSTOM")
            }
        )
        val offlinePairOutcome = coordOfflinePair.resolveTargetWithConsensus(sampleObs.copy(screenshotId = "scr_r7_off2"), "Share")
        assertEquals(ConsensusDecisionType.USE_OFFLINE_TWO_OF_THREE_CONSENSUS, offlinePairOutcome.decision)
        assertTrue(offlinePairOutcome.reasonCodes.contains(ConsensusReasonCode.OFFLINE_TWO_OF_THREE_AGREEMENT))
        assertEquals(0.22f, offlinePairOutcome.winningNormalizedCenter!!.x, 0.001f)

        // Case B: 3-way disagreement + Accessibility node match -> safe Accessibility fallback (NEVER blind tap)
        val coord3WayWithA11y = VisionConsensusCoordinator(
            context = context,
            smolVlm2Runner = LocalVisionDetectionRunner { _, _, _ -> cand(VisionModelId.SMOLVLM2_2_2B, 0.12f, 0.15f, 0.85f) },
            florence2Runner = LocalVisionDetectionRunner { _, _, _ -> cand(VisionModelId.FLORENCE_2, 0.82f, 0.25f, 0.82f) },
            cloudRunner = CloudVisionEscalationRunner { _, _, _, _, _ ->
                cand(VisionModelId.CLOUD_VISION, 0.50f, 0.85f, 0.88f, providerId = "GEMINI")
            },
            accessibilityNodesProvider = { listOf(node("Subscribe", Rect(680, 420, 960, 520))) }
        )
        val a11yFallbackOut = coord3WayWithA11y.resolveTargetWithConsensus(sampleObs.copy(screenshotId = "scr_r7_3way_a11y"), "Subscribe")
        assertEquals(ConsensusDecisionType.FALLBACK_ACCESSIBILITY_ONLY, a11yFallbackOut.decision)
        assertTrue(a11yFallbackOut.reasonCodes.contains(ConsensusReasonCode.THREE_WAY_DISAGREEMENT))
        assertTrue(a11yFallbackOut.reasonCodes.contains(ConsensusReasonCode.ACCESSIBILITY_MATCH_FOUND))
        assertFalse(a11yFallbackOut.shouldBlindTapCoordinates)
        assertEquals(VisionModelId.ACCESSIBILITY_HIERARCHY, a11yFallbackOut.winningCandidate?.modelId)

        // Case C: 3-way disagreement + no Accessibility node -> REQUIRE_FRESH_OBSERVATION, winningCandidate == null
        val coord3WayNoA11y = VisionConsensusCoordinator(
            context = context,
            smolVlm2Runner = LocalVisionDetectionRunner { _, _, _ -> cand(VisionModelId.SMOLVLM2_2_2B, 0.12f, 0.15f, 0.85f) },
            florence2Runner = LocalVisionDetectionRunner { _, _, _ -> cand(VisionModelId.FLORENCE_2, 0.82f, 0.25f, 0.82f) },
            cloudRunner = CloudVisionEscalationRunner { _, _, _, _, _ ->
                cand(VisionModelId.CLOUD_VISION, 0.50f, 0.85f, 0.88f, providerId = "GEMINI")
            },
            accessibilityNodesProvider = { emptyList() }
        )
        val refuseTapOut = coord3WayNoA11y.resolveTargetWithConsensus(sampleObs.copy(screenshotId = "scr_r7_3way_none"), "Subscribe")
        assertEquals(ConsensusDecisionType.REQUIRE_FRESH_OBSERVATION, refuseTapOut.decision)
        assertNull(refuseTapOut.winningCandidate)
        assertTrue(refuseTapOut.requiresFreshObservation)
        assertFalse(refuseTapOut.shouldBlindTapCoordinates)
    }

    @Test
    fun boundaryAndResolutionCases_exact30And50ThresholdsAndNanProtectionAndNormalizedScale() {
        // Exact 0.30f + 0.30f with matching coords -> Rule 6 (stays offline, NOT Rule 2 because not strictly < 0.30)
        val exact30 = VisionConsensusEngine.evaluateOfflineConsensus(
            screenshotId = "scr_b30",
            smolVlm2 = cand(VisionModelId.SMOLVLM2_2_2B, 0.5f, 0.5f, 0.30f),
            florence2 = cand(VisionModelId.FLORENCE_2, 0.51f, 0.50f, 0.30f)
        )
        assertFalse(exact30.requiresCloudEscalation)
        assertEquals(ConsensusDecisionType.USE_OFFLINE_LOW_CONF_AGREEMENT, exact30.decision)

        // Exact 0.50f + 0.50f with matching coords -> stays offline low-conf agreement
        val exact50 = VisionConsensusEngine.evaluateOfflineConsensus(
            screenshotId = "scr_b50",
            smolVlm2 = cand(VisionModelId.SMOLVLM2_2_2B, 0.5f, 0.5f, 0.50f),
            florence2 = cand(VisionModelId.FLORENCE_2, 0.51f, 0.50f, 0.50f)
        )
        assertFalse(exact50.requiresCloudEscalation)

        // NaN confidence protection -> safely sanitized to 0f and triggers Rule 2 escalation
        val nanCand = cand(VisionModelId.SMOLVLM2_2_2B, 0.5f, 0.5f, Float.NaN)
        assertEquals(0f, nanCand.calibratedConfidence, 0.0001f)
        val nanEval = VisionConsensusEngine.evaluateOfflineConsensus(
            screenshotId = "scr_nan",
            smolVlm2 = nanCand,
            florence2 = cand(VisionModelId.FLORENCE_2, 0.5f, 0.5f, 0.0f)
        )
        assertTrue(nanEval.requiresCloudEscalation)
        assertEquals(listOf(ConsensusReasonCode.EQUAL_VERY_LOW_CONFIDENCE), nanEval.reasonCodes)

        // Different pixel resolutions representing same normalized center [0.50, 0.25] must match!
        val box1080p = NormalizedBox.fromDeviceRect(Rect(486, 540, 594, 660), 1080, 2400)
        val box540p = NormalizedBox.fromDeviceRect(Rect(243, 270, 297, 330), 540, 1200)
        val candA = VisionDetectionCandidate(VisionModelId.SMOLVLM2_2_2B, screenshotId = "s", normalizedBox = box1080p, rawConfidence = 0.8f)
        val candB = VisionDetectionCandidate(VisionModelId.FLORENCE_2, screenshotId = "s", normalizedBox = box540p, rawConfidence = 0.82f)
        assertTrue(VisionConsensusEngine.coordinatesAgree(candA, candB))
    }

    // =========================================================================
    // 2. RESILIENCE, TIMEOUTS, STALENESS, DEDUP, CANCELLATION & CLOUD PROVIDERS
    // =========================================================================

    @Test
    fun bothModelsAnalyzeExactSameScreenshotId_andPartialFailureOrTimeoutEscalatesCleanly() = runBlocking {
        val seenIds = mutableListOf<String>()
        val coordTimeout = VisionConsensusCoordinator(
            context = context,
            config = ConsensusConfig(localModelTimeoutMs = 40L),
            smolVlm2Runner = LocalVisionDetectionRunner { obs, _, _ ->
                synchronized(seenIds) { seenIds.add("smol:${obs.screenshotId}") }
                cand(VisionModelId.SMOLVLM2_2_2B, 0.40f, 0.60f, 0.82f, shotId = obs.screenshotId)
            },
            florence2Runner = LocalVisionDetectionRunner { obs, _, _ ->
                synchronized(seenIds) { seenIds.add("flor:${obs.screenshotId}") }
                delay(200L) // Exceeds 40ms localModelTimeoutMs
                cand(VisionModelId.FLORENCE_2, 0.40f, 0.60f, 0.85f, shotId = obs.screenshotId)
            },
            cloudRunner = CloudVisionEscalationRunner { _, obs, _, _, _ ->
                cand(VisionModelId.CLOUD_VISION, 0.41f, 0.605f, 0.94f, shotId = obs.screenshotId, providerId = "GEMINI")
            }
        )

        val outcome = coordTimeout.resolveTargetWithConsensus(sampleObs, "Search")
        assertEquals(listOf("smol:${sampleObs.screenshotId}", "flor:${sampleObs.screenshotId}"), seenIds.sorted().reversed())
        assertEquals(ModelDetectionStatus.TIMEOUT, outcome.florence2Result?.status)
        assertTrue(outcome.reasonCodes.contains(ConsensusReasonCode.OFFLINE_MODEL_FAILURE))
        assertEquals(ConsensusDecisionType.USE_CLOUD_TWO_OF_THREE_CONSENSUS, outcome.decision)
        assertNotNull(AiActionDiagnostics.lastDiagnostic)
    }

    @Test
    fun cloudProviderUnavailableOrDisabledOrStrictOffline_failsOverSafelyWithoutBlindTap() = runBlocking {
        // Strict offline mode enabled and no cloud key -> SelectedProviderClient must return CLOUD_PROVIDER_UNAVAILABLE
        AppPreferences.setOfflineFallbackEnabled(context, true)
        AppPreferences.setAiProvider(context, "GEMINI")
        AppPreferences.setCustomApiKey(context, "AIzaSyValidKeyButOfflineBlocked12345")
        SelectedProviderClient.testNetworkConnectedOverride = false

        val coordinator = VisionConsensusCoordinator(
            context = context,
            smolVlm2Runner = LocalVisionDetectionRunner { obs, _, _ ->
                cand(VisionModelId.SMOLVLM2_2_2B, 0.15f, 0.15f, 0.80f, shotId = obs.screenshotId)
            },
            florence2Runner = LocalVisionDetectionRunner { obs, _, _ ->
                cand(VisionModelId.FLORENCE_2, 0.85f, 0.85f, 0.82f, shotId = obs.screenshotId)
            },
            accessibilityNodesProvider = { emptyList() }
        )

        val outNoA11y = coordinator.resolveTargetWithConsensus(sampleObs.copy(screenshotId = "scr_nocloud"), "Subscribe")
        assertEquals(ConsensusDecisionType.UNAVAILABLE_OR_FAILED, outNoA11y.decision)
        assertTrue(outNoA11y.reasonCodes.contains(ConsensusReasonCode.CLOUD_PROVIDER_UNAVAILABLE))
        assertFalse(outNoA11y.shouldBlindTapCoordinates)
        assertNull(outNoA11y.winningCandidate)
    }

    @Test
    fun cloudProviderDiscoveryAndVisionEscalation_usesActiveConfiguredProviderAndTestTransport() = runBlocking {
        SelectedProviderClient.testNetworkConnectedOverride = true
        AppPreferences.setOfflineFallbackEnabled(context, false)
        AppPreferences.setAiProvider(context, "OPENROUTER")
        AppPreferences.setOpenRouterApiKey(context, "sk-or-test-key-123456")
        AppPreferences.setOpenRouterModel(context, "google/gemini-2.5-flash")

        var capturedProviderId = ""
        SelectedProviderClient.testTransport = object : ProviderTransport {
            override suspend fun invoke(
                context: Context,
                request: ProviderInvocationRequest
            ): ProviderInvocationResult {
                capturedProviderId = request.providerId
                return ProviderInvocationResult.Success(
                    text = """{"found":true,"target_label":"Subscribe","coordinate_space":"NORMALIZED_0_1","normalized_center":[0.74,0.32],"confidence":0.95}""",
                    providerId = request.providerId,
                    modelId = request.modelId
                )
            }
        }

        val coordinator = VisionConsensusCoordinator(
            context = context,
            smolVlm2Runner = LocalVisionDetectionRunner { obs, _, _ ->
                cand(VisionModelId.SMOLVLM2_2_2B, 0.74f, 0.315f, 0.81f, shotId = obs.screenshotId)
            },
            florence2Runner = LocalVisionDetectionRunner { obs, _, _ ->
                cand(VisionModelId.FLORENCE_2, 0.20f, 0.20f, 0.83f, shotId = obs.screenshotId)
            }
        )

        val outcome = coordinator.resolveTargetWithConsensus(sampleObs, "Subscribe")
        assertEquals("OPENROUTER", capturedProviderId)
        assertEquals(ConsensusDecisionType.USE_CLOUD_TWO_OF_THREE_CONSENSUS, outcome.decision)
        assertEquals(0.74f, outcome.winningNormalizedCenter!!.x, 0.001f)
        // Ensure AiActionDiagnostics never leaks API key
        val logsText = AiActionDiagnostics.records.value.joinToString(" ") { "${it.errorMessage} ${it.executionResult}" }
        assertFalse(logsText.contains("sk-or-test-key"))
    }

    @Test
    fun staleObservationDetectedMidInference_abortsCloudAndActionWithRequireFreshObservation() = runBlocking {
        var makeStale = false
        val cloudCalls = AtomicInteger(0)

        val coordinator = VisionConsensusCoordinator(
            context = context,
            smolVlm2Runner = LocalVisionDetectionRunner { obs, _, _ ->
                cand(VisionModelId.SMOLVLM2_2_2B, 0.20f, 0.20f, 0.80f, shotId = obs.screenshotId)
            },
            florence2Runner = LocalVisionDetectionRunner { obs, _, _ ->
                makeStale = true // Screen rotated / changed right during local inference
                cand(VisionModelId.FLORENCE_2, 0.80f, 0.80f, 0.80f, shotId = obs.screenshotId)
            },
            cloudRunner = CloudVisionEscalationRunner { _, _, _, _, _ ->
                cloudCalls.incrementAndGet()
                cand(VisionModelId.CLOUD_VISION, 0.20f, 0.20f, 0.9f)
            },
            staleChecker = { makeStale }
        )

        val outcome = coordinator.resolveTargetWithConsensus(sampleObs, "Subscribe")
        assertTrue(outcome.isStale)
        assertEquals(ConsensusDecisionType.REQUIRE_FRESH_OBSERVATION, outcome.decision)
        assertTrue(outcome.reasonCodes.contains(ConsensusReasonCode.OBSERVATION_STALE))
        assertTrue(outcome.requiresFreshObservation)
        assertEquals(0, cloudCalls.get())
        assertNull(outcome.winningCandidate)
    }

    @Test
    fun singleFlightDedup_identicalConcurrentRequestsShareResultAndCancellationPropagates() = runBlocking {
        val smolInvocations = AtomicInteger(0)
        val florInvocations = AtomicInteger(0)

        val coordinator = VisionConsensusCoordinator(
            context = context,
            smolVlm2Runner = LocalVisionDetectionRunner { obs, _, _ ->
                smolInvocations.incrementAndGet()
                delay(40L)
                cand(VisionModelId.SMOLVLM2_2_2B, 0.5f, 0.5f, 0.88f, shotId = obs.screenshotId)
            },
            florence2Runner = LocalVisionDetectionRunner { obs, _, _ ->
                florInvocations.incrementAndGet()
                delay(40L)
                cand(VisionModelId.FLORENCE_2, 0.505f, 0.502f, 0.79f, shotId = obs.screenshotId)
            }
        )

        val d1 = async { coordinator.resolveTargetWithConsensus(sampleObs, "Search") }
        val d2 = async { coordinator.resolveTargetWithConsensus(sampleObs, "  search  ") }
        val (r1, r2) = listOf(d1, d2).awaitAll()

        assertEquals(1, smolInvocations.get())
        assertEquals(1, florInvocations.get())
        assertEquals(r1.decision, r2.decision)
        assertEquals(r1.winningNormalizedCenter, r2.winningNormalizedCenter)

        // Cancellation propagation check
        var caughtCancellation = false
        val cancellableCoord = VisionConsensusCoordinator(
            context = context,
            smolVlm2Runner = LocalVisionDetectionRunner { _, _, _ ->
                delay(1_000L)
                cand(VisionModelId.SMOLVLM2_2_2B, 0.5f, 0.5f, 0.8f)
            },
            florence2Runner = LocalVisionDetectionRunner { _, _, _ ->
                cand(VisionModelId.FLORENCE_2, 0.5f, 0.5f, 0.8f)
            }
        )
        val job = launch {
            try {
                cancellableCoord.resolveTargetWithConsensus(sampleObs.copy(screenshotId = "scr_cancel"), "Play")
            } catch (_: CancellationException) {
                caughtCancellation = true
            }
        }
        delay(20L)
        job.cancel()
        job.join()
        assertTrue("Coroutine CancellationException must propagate cleanly", caughtCancellation)
    }

    @Test
    fun florence2LocationTagParser_parsesNormalized1000ScaleBoxesAccurately() {
        val raw = "Subscribe<loc_650><loc_200><loc_900><loc_280>Like<loc_100><loc_200><loc_240><loc_280>"
        val parsed = Gemma4E4BEngine.parseFlorence2LocationOutput(raw)
        assertEquals(2, parsed.size)
        assertEquals("Subscribe", parsed[0].label)
        assertEquals(0.65f, parsed[0].normalizedBox.left, 0.001f)
        assertEquals(0.20f, parsed[0].normalizedBox.top, 0.001f)
        assertEquals(0.90f, parsed[0].normalizedBox.right, 0.001f)
        assertEquals(0.28f, parsed[0].normalizedBox.bottom, 0.001f)
        assertEquals("Like", parsed[1].label)
    }

    @Test
    fun voiceDefault_usesGeminiVoiceOutOfTheBoxWithoutMandatoryIndicTinyTtsDownload() {
        val voiceMgr = VoiceEngineManager.getInstance(context)
        assertEquals("GEMINI_VOICE", AppPreferences.getSpeechEngine(context))
        assertEquals(VoiceEngine.GEMINI_VOICE, voiceMgr.getActiveEngine())
        assertTrue(VoiceEngine.GEMINI_PREBUILT_VOICES.contains("Kore"))

        voiceMgr.setGeminiVoiceName("Aoede")
        assertEquals("Aoede", voiceMgr.getGeminiVoiceName())

        // Indic Tiny TTS remains selectable if user explicitly opts in later
        voiceMgr.setActiveEngine(VoiceEngine.INDIC_TINY_TTS)
        assertEquals(VoiceEngine.INDIC_TINY_TTS, voiceMgr.getActiveEngine())
    }
}
