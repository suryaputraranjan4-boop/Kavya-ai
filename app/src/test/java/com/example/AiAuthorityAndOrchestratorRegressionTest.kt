package com.example

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.example.agent.AndroidAgent
import com.example.agent.CheckpointManager
import com.example.agent.ContextEngine
import com.example.agent.RecoveryActionType
import com.example.agent.RecoveryLevel
import com.example.agent.RecoveryManager
import com.example.agent.RecoveryStrategy
import com.example.agent.ScreenInspector
import com.example.agent.TaskPlan
import com.example.agent.TaskPlanner
import com.example.agent.TaskStep
import com.example.agent.UniversalActionType
import com.example.agent.VerificationEngine
import com.example.agent.WorkflowEngine
import com.example.ai.KavyaAI
import com.example.ai.LocalAssistantEngine
import com.example.ai.TaskRouter
import com.example.ai.providers.AgentOrchestrator
import com.example.ai.providers.AndroidAccessibilityToolProvider
import com.example.orchestrator.*
import com.example.proactive.ProactiveController
import com.example.utils.ActionExecutor
import com.example.utils.AppPreferences
import com.example.utils.AppResolver
import com.example.utils.CommandRouter
import com.example.visual.JarvisGuidanceEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AiAuthorityAndOrchestratorRegressionTest {

    private lateinit var app: Application
    private lateinit var trackingExecutor: TrackingExecutor
    private var obsSeq = 0

    private class TrackingExecutor(
        private val onExecute: (suspend (ActionAuthorizationPermit, AiActionEnvelope, CalibratedPoint?) -> ActionExecutionFeedback)? = null
    ) : RestrictedActionExecutor {
        val executedEnvelopes = mutableListOf<AiActionEnvelope>()
        val executedPoints = mutableListOf<CalibratedPoint?>()

        override suspend fun executePermittedAction(
            context: android.content.Context,
            permit: ActionAuthorizationPermit,
            envelope: AiActionEnvelope,
            calibratedPoint: CalibratedPoint?
        ): ActionExecutionFeedback {
            val consumed = AiAuthorityGateway.beginAuthorizedDispatch(permit)
            if (consumed is PermitConsumeResult.Rejected) {
                return ActionExecutionFeedback(false, consumed.reason, consumed.code)
            }
            return try {
                executedEnvelopes.add(envelope)
                executedPoints.add(calibratedPoint)
                onExecute?.invoke(permit, envelope, calibratedPoint)
                    ?: ActionExecutionFeedback(true, "Executed ${envelope.action.name}")
            } finally {
                AiAuthorityGateway.endAuthorizedDispatch(permit)
            }
        }
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        obsSeq = 0
        AiAuthorityGateway.resetForTesting()
        TaskOrchestrationStateMachine.resetToIdle()
        ScreenObservationCoordinator.clearLatestObservation()
        SelectedProviderClient.testTransport = null
        SelectedProviderClient.testNetworkConnectedOverride = true

        AppPreferences.setOfflineFallbackEnabled(app, false)
        AppPreferences.setAiProvider(app, "GEMINI")
        AppPreferences.setGeminiModel(app, "gemini-3.5-flash-lite")
        AppPreferences.setCustomApiKey(app, "AIzaSyTestValidDummyKey1234567890")
        AppPreferences.clearOpenRouterApiKey(app)
        AppPreferences.clearHuggingFaceApiKey(app)

        trackingExecutor = TrackingExecutor()
        setGeometryAndObservation(1080, 2400, Configuration.ORIENTATION_PORTRAIT)
    }

    @After
    fun tearDown() {
        SelectedProviderClient.testTransport = null
        SelectedProviderClient.testNetworkConnectedOverride = null
        ScreenObservationCoordinator.setTestProviders(null, null)
        AiAuthorityGateway.resetForTesting()
        TaskOrchestrationStateMachine.resetToIdle()
    }

    private fun setGeometryAndObservation(
        width: Int = 1080,
        height: Int = 2400,
        orientation: Int = Configuration.ORIENTATION_PORTRAIT,
        ttlMs: Long = 15_000L,
        capturedAtOverride: Long? = null
    ) {
        val geom = DisplayGeometrySnapshot(width, height, orientation, 420)
        ScreenObservationCoordinator.setTestProviders(
            observer = {
                obsSeq++
                val record = ScreenObservationRecord(
                    screenshotId = "scr_test_$obsSeq",
                    capturedAtMs = capturedAtOverride ?: System.currentTimeMillis(),
                    ttlMs = ttlMs,
                    displayWidthPx = width,
                    displayHeightPx = height,
                    bitmapWidth = width,
                    bitmapHeight = height,
                    orientation = orientation,
                    densityDpi = 420,
                    foregroundPackage = "com.example.app",
                    uiTreeSummary = "Button Login clickable",
                    imageBytes = byteArrayOf(1, 2, 3),
                    source = "TEST_MOCK_SCREEN"
                )
                AiAuthorityGateway.registerScreenObservation(record)
                record
            },
            geometry = { geom }
        )
    }

    private fun buildOrchestrator(): CeoAiOrchestrator {
        return CeoAiOrchestrator(app, trackingExecutor).apply {
            testPermissionChecker = { _, _ -> Pair(true, "") }
        }
    }

    private fun extractField(prompt: String, key: String): String {
        val regex = Regex("""(?:^|\n)\s*$key:\s*([^\s\n]+)""")
        return regex.find(prompt)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    // 1. Valid API key -> full CEO flow: Command -> Fresh Screenshot -> AI JSON -> Permit -> Single Action -> Fresh Post-Screenshot -> AI Verification -> Verified Success
    @Test
    fun test01_validApiKey_fullCeoFlow_succeedsOnlyAfterVerification() = runBlocking {
        val calls = AtomicInteger(0)
        SelectedProviderClient.testTransport = object : ProviderTransport {
            override suspend fun invoke(context: android.content.Context, request: ProviderInvocationRequest): ProviderInvocationResult {
                val n = calls.incrementAndGet()
                val taskId = extractField(request.prompt, "task_id")
                val sessionId = extractField(request.prompt, "session_id")
                val scr = extractField(request.prompt, if (n == 1) "screenshot_reference" else "new_screenshot_reference")
                return if (n == 1) {
                    ProviderInvocationResult.Success(
                        text = """
                        {
                          "protocol_version":"1.0",
                          "task_id":"$taskId",
                          "session_id":"$sessionId",
                          "overall_goal":"Tap Login",
                          "requires_vision":true,
                          "steps":[{"step_number":1,"goal":"Tap Login button","expected_outcome":"Dashboard opens"}],
                          "first_action":{
                            "protocol_version":"1.0",
                            "task_id":"$taskId",
                            "session_id":"$sessionId",
                            "action_id":"act_1",
                            "sequence":1,
                            "action":"TAP",
                            "target":{"description":"Login button","coordinates":{"x":0.5,"y":0.6,"space":"NORMALIZED_0_1"}},
                            "screenshot_reference":"$scr",
                            "screen_width":1080,
                            "screen_height":2400,
                            "orientation":${Configuration.ORIENTATION_PORTRAIT},
                            "confidence":0.95,
                            "expected_result":"Dashboard screen becomes visible"
                          }
                        }
                        """.trimIndent(),
                        providerId = request.providerId,
                        modelId = request.modelId
                    )
                } else {
                    assertTrue("Verification must use fresh post-action screenshot scr_test_2", scr == "scr_test_2")
                    ProviderInvocationResult.Success(
                        text = """
                        {
                          "protocol_version":"1.0",
                          "task_id":"$taskId",
                          "session_id":"$sessionId",
                          "screenshot_reference":"$scr",
                          "verified":true,
                          "observed_screen_summary":"Dashboard visible",
                          "is_task_completed":true,
                          "needs_replan":false,
                          "assistant_message":"Logged in and verified on dashboard."
                        }
                        """.trimIndent(),
                        providerId = request.providerId,
                        modelId = request.modelId
                    )
                }
            }
        }

        val outcome = buildOrchestrator().runTask("Tap login button")
        assertTrue(outcome.success)
        assertEquals(1, outcome.stepsExecuted)
        assertEquals(1, outcome.verifiedSteps)
        assertEquals(1, trackingExecutor.executedEnvelopes.size)
        assertEquals(2, calls.get())
        assertEquals(OrchestrationLifecycleState.TASK_COMPLETED, TaskOrchestrationStateMachine.snapshot.value.state)
    }

    // 2. Missing API key -> ZERO plans, ZERO Accessibility actions
    @Test
    fun test02_missingApiKey_blocksAllPlanningAndExecution() = runBlocking {
        AppPreferences.clearCustomApiKey(app)
        var transportCalled = false
        SelectedProviderClient.testTransport = object : ProviderTransport {
            override suspend fun invoke(context: android.content.Context, request: ProviderInvocationRequest): ProviderInvocationResult {
                transportCalled = true
                return ProviderInvocationResult.Failure("SHOULD_NOT_CALL", "No key")
            }
        }

        val outcome = buildOrchestrator().runTask("Open WhatsApp and send hello")
        assertFalse(outcome.success)
        assertEquals("MISSING_OR_INVALID_API_KEY", outcome.failureCode)
        assertFalse(transportCalled)
        assertEquals(0, trackingExecutor.executedEnvelopes.size)
    }

    // 3. Invalid API key / 401 -> ZERO retries without API, ZERO local fallback, ZERO Accessibility actions
    @Test
    fun test03_invalidApiKey401_noRetryNoFallbackNoExecution() = runBlocking {
        val calls = AtomicInteger(0)
        SelectedProviderClient.testTransport = object : ProviderTransport {
            override suspend fun invoke(context: android.content.Context, request: ProviderInvocationRequest): ProviderInvocationResult {
                calls.incrementAndGet()
                return ProviderInvocationResult.Failure("GEMINI_HTTP_401", "Invalid API key", isAuthFailure = true)
            }
        }

        val outcome = buildOrchestrator().runTask("Open Settings")
        assertFalse(outcome.success)
        assertEquals("GEMINI_HTTP_401", outcome.failureCode)
        assertEquals(1, calls.get())
        assertEquals(0, trackingExecutor.executedEnvelopes.size)
    }

    // 4. Offline / disconnected network -> ZERO actions
    @Test
    fun test04_offlineNetwork_blocksAllAutomation() = runBlocking {
        SelectedProviderClient.testNetworkConnectedOverride = false
        val outcome = buildOrchestrator().runTask("Open YouTube")
        assertFalse(outcome.success)
        assertEquals("NETWORK_UNAVAILABLE", outcome.failureCode)
        assertEquals(0, trackingExecutor.executedEnvelopes.size)
    }

    // 5. Rate limit / HTTP 429 / quota exhausted -> ZERO silent provider switch, ZERO local fallback, ZERO actions
    @Test
    fun test05_rateLimit429_neverSwitchesProviderOrFallsBackLocally() = runBlocking {
        AppPreferences.setAiProvider(app, "GEMINI")
        AppPreferences.setOpenRouterApiKey(app, "sk-or-test-key-backup")
        val providersCalled = mutableListOf<String>()
        SelectedProviderClient.testTransport = object : ProviderTransport {
            override suspend fun invoke(context: android.content.Context, request: ProviderInvocationRequest): ProviderInvocationResult {
                providersCalled.add(request.providerId)
                return ProviderInvocationResult.Failure("GEMINI_HTTP_429", "Quota exhausted", isRateLimitOrQuota = true)
            }
        }

        val outcome = buildOrchestrator().runTask("Open YouTube and search music")
        assertFalse(outcome.success)
        assertEquals("GEMINI_HTTP_429", outcome.failureCode)
        assertEquals(listOf("GEMINI"), providersCalled)
        assertEquals(0, trackingExecutor.executedEnvelopes.size)
        val localMsg = LocalAssistantEngine.resolveLocalCommandOrFallback("open settings")
        assertFalse(localMsg.contains("ACTION:", ignoreCase = true))
    }

    // 6. Malformed AI JSON response -> rejected cleanly, ZERO actions
    @Test
    fun test06_malformedAiJson_rejectedWithZeroExecution() = runBlocking {
        SelectedProviderClient.testTransport = object : ProviderTransport {
            override suspend fun invoke(context: android.content.Context, request: ProviderInvocationRequest): ProviderInvocationResult {
                return ProviderInvocationResult.Success("Not valid JSON { broken", request.providerId, request.modelId)
            }
        }

        val outcome = buildOrchestrator().runTask("Tap submit")
        assertFalse(outcome.success)
        assertEquals("MALFORMED_AI_RESPONSE", outcome.failureCode)
        assertEquals(0, trackingExecutor.executedEnvelopes.size)
    }

    // 7. Provider switch mid-task -> old task token & permits immediately invalidated
    @Test
    fun test07_providerSwitchMidTask_invalidatesSessionAndPermits() = runBlocking {
        val session = AiAuthorityGateway.registerExplicitUserCommand("Tap next", "GEMINI", "gemini-3.5-flash-lite")
        val obs = ScreenObservationCoordinator.captureFreshObservation(app)!!
        val env = AiActionEnvelope(
            taskId = session.taskId,
            sessionId = session.sessionId,
            actionId = "act_switch",
            sequence = 1,
            action = OrchestratorActionType.TAP,
            target = AiActionTarget("Next", coordinates = AiCoordinates(0.5, 0.5)),
            screenshotReference = obs.screenshotId,
            screenWidth = obs.displayWidthPx,
            screenHeight = obs.displayHeightPx,
            orientation = obs.orientation,
            confidence = 0.95,
            expectedResult = "Next screen opens"
        )
        val permitRes = AiAuthorityGateway.requestActionPermit(
            context = app, session = session, envelope = env, currentObservation = obs,
            activeProviderId = "GEMINI", isOnline = true, hasValidApiAuth = true,
            userConfirmedSensitiveOrMedium = false, requiredPermissionsGranted = true
        )
        assertTrue(permitRes is PermitDecision.Granted)
        val permit = (permitRes as PermitDecision.Granted).permit

        AppPreferences.setAiProvider(app, "OPENROUTER")
        val consume = AiAuthorityGateway.consumePermit(permit)
        assertTrue(consume is PermitConsumeResult.Rejected)
        assertFalse(session.isActive)
    }

    // 8. Autonomous background/proactive trigger -> rejected immediately
    @Test
    fun test08_autonomousBackgroundOrEmptyTrigger_rejected() = runBlocking {
        val emptyOutcome = buildOrchestrator().runTask("   ")
        assertFalse(emptyOutcome.success)
        assertEquals("NO_USER_COMMAND", emptyOutcome.failureCode)

        val controller = ProactiveController(app, com.example.agent.MemoryEngine(app), ScreenInspector()) { _, _ -> }
        controller.setProactiveMode(true)
        controller.setScreenAwareness(true)
        delay(50L)
        assertNull(controller.lastProactiveText.value)
        controller.shutdown()
    }

    // 9. Direct execution helpers bypassing AiAuthorityGateway -> denied immediately (NO_AI_PERMIT)
    @Test
    fun test09_directBypassCalls_blockedWithoutPermit() = runBlocking {
        val router = CommandRouter(app)
        val screenInspector = ScreenInspector()
        val verificationEngine = VerificationEngine(screenInspector)
        val agent = AndroidAgent(app, router.appResolver, ContextEngine(), screenInspector, verificationEngine)
        val wfEngine = WorkflowEngine(screenInspector, verificationEngine, TaskPlanner(router.appResolver))
        val recMgr = RecoveryManager(screenInspector)

        val r1 = router.executeDirectUserCommand("open settings")
        assertFalse(r1.success)
        assertTrue(r1.output.contains("AI authorization", ignoreCase = true))

        val r2 = ActionExecutor.searchWeb(app, "cats")
        assertFalse(r2)

        val r3 = agent.executeTaskPlan(TaskPlan("open settings", "Settings", listOf(TaskStep(1, UniversalActionType.OPEN_APP, "Settings"))))
        assertFalse(r3.success)
        assertEquals("NO_AI_PERMIT", r3.failureReason)

        val r4 = agent.executeAtomicStep(TaskStep(1, UniversalActionType.TAP, param = "Login"))
        assertFalse(r4.success)

        val r5 = agent.executeVisualGoal("tap login")
        assertFalse(r5.success)
        assertEquals("NO_AI_PERMIT", r5.error)

        val r6 = wfEngine.runWorkflow("open settings", app)
        assertFalse(r6.success)
        assertEquals("NO_AI_PERMIT", r6.error)

        val r7 = recMgr.executeRecovery(
            strategy = RecoveryStrategy(RecoveryLevel.LEVEL_1_LOCAL_UI, RecoveryActionType.NAVIGATE_BACK, "Back"),
            context = app,
            step = TaskStep(1, UniversalActionType.TAP),
            checkpointManager = CheckpointManager()
        )
        assertFalse(r7)

        val toolRes = com.example.agent.KavyaToolRegistry.executeTool("search_web", mapOf("query" to "kotlin"), app, agent)
        assertFalse(toolRes.success)

        val orchRes = AgentOrchestrator(accessibilityProvider = AndroidAccessibilityToolProvider(agent))
            .executeWithFallback(prompt = "open settings", context = app)
        assertFalse(orchRes.success)
    }

    // 10. Expired or mismatched task_id / session_id token -> permit rejected
    @Test
    fun test10_expiredAndMismatchedTokens_rejected() = runBlocking {
        val session = AiAuthorityGateway.registerExplicitUserCommand("Tap save", "GEMINI", "gemini-3.5-flash-lite")
        val obs = ScreenObservationCoordinator.captureFreshObservation(app)!!

        val mismatched = AiActionEnvelope(
            taskId = "task_wrong", sessionId = session.sessionId, actionId = "a1", sequence = 1,
            action = OrchestratorActionType.TAP, target = AiActionTarget("Save", coordinates = AiCoordinates(0.5, 0.5)),
            screenshotReference = obs.screenshotId, screenWidth = obs.displayWidthPx, screenHeight = obs.displayHeightPx,
            orientation = obs.orientation, confidence = 0.95, expectedResult = "Saved"
        )
        val resMismatch = AiAuthorityGateway.requestActionPermit(
            app, session, mismatched, obs, "GEMINI", true, true, false, true
        )
        assertTrue(resMismatch is PermitDecision.Denied)
        assertEquals("SESSION_MISMATCH", (resMismatch as PermitDecision.Denied).code)

        val expired = mismatched.copy(taskId = session.taskId, expiresAtMs = System.currentTimeMillis() - 1000L)
        val resExpired = AiAuthorityGateway.requestActionPermit(
            app, session, expired, obs, "GEMINI", true, true, false, true
        )
        assertTrue(resExpired is PermitDecision.Denied)
        assertEquals("TOKEN_EXPIRED", (resExpired as PermitDecision.Denied).code)
    }

    // 11. Duplicate action_id or non-monotonic sequence -> anti-replay rejects
    @Test
    fun test11_duplicateActionIdAndOutOfOrderSequence_rejected() = runBlocking {
        val session = AiAuthorityGateway.registerExplicitUserCommand("Tap item", "GEMINI", "gemini-3.5-flash-lite")
        val obs = ScreenObservationCoordinator.captureFreshObservation(app)!!
        val env1 = AiActionEnvelope(
            taskId = session.taskId, sessionId = session.sessionId, actionId = "act_unique_1", sequence = 1,
            action = OrchestratorActionType.TAP, target = AiActionTarget("Item 1", coordinates = AiCoordinates(0.5, 0.5)),
            screenshotReference = obs.screenshotId, screenWidth = obs.displayWidthPx, screenHeight = obs.displayHeightPx,
            orientation = obs.orientation, confidence = 0.96, expectedResult = "Item opened"
        )
        val g1 = AiAuthorityGateway.requestActionPermit(app, session, env1, obs, "GEMINI", true, true, false, true)
        assertTrue(g1 is PermitDecision.Granted)
        val permit1 = (g1 as PermitDecision.Granted).permit
        assertTrue(AiAuthorityGateway.consumePermit(permit1) is PermitConsumeResult.Valid)
        // Re-consuming same permit must fail
        assertTrue(AiAuthorityGateway.consumePermit(permit1) is PermitConsumeResult.Rejected)

        // Duplicate action_id
        val dupId = env1.copy(sequence = 2)
        val resDup = AiAuthorityGateway.requestActionPermit(app, session, dupId, obs, "GEMINI", true, true, false, true)
        assertTrue(resDup is PermitDecision.Denied)
        assertEquals("DUPLICATE_ACTION_ID", (resDup as PermitDecision.Denied).code)

        // Non-monotonic sequence
        val badSeq = env1.copy(actionId = "act_unique_2", sequence = 1)
        val resSeq = AiAuthorityGateway.requestActionPermit(app, session, badSeq, obs, "GEMINI", true, true, false, true)
        assertTrue(resSeq is PermitDecision.Denied)
        assertEquals("OUT_OF_ORDER_SEQUENCE", (resSeq as PermitDecision.Denied).code)
    }

    // 12. Stale screenshot_reference, expired screenshot, or screen rotation/resize -> rejected
    @Test
    fun test12_staleOrRotatedScreenshot_rejected() = runBlocking {
        val session = AiAuthorityGateway.registerExplicitUserCommand("Tap button", "GEMINI", "gemini-3.5-flash-lite")
        val oldObs = ScreenObservationRecord(
            screenshotId = "scr_expired", capturedAtMs = System.currentTimeMillis() - 60_000L, ttlMs = 5_000L,
            displayWidthPx = 1080, displayHeightPx = 2400, bitmapWidth = 1080, bitmapHeight = 2400,
            orientation = Configuration.ORIENTATION_PORTRAIT, densityDpi = 420
        )
        val envExpired = AiActionEnvelope(
            taskId = session.taskId, sessionId = session.sessionId, actionId = "act_stale", sequence = 1,
            action = OrchestratorActionType.TAP, target = AiActionTarget("Btn", coordinates = AiCoordinates(0.5, 0.5)),
            screenshotReference = oldObs.screenshotId, screenWidth = 1080, screenHeight = 2400,
            orientation = Configuration.ORIENTATION_PORTRAIT, confidence = 0.95, expectedResult = "Clicked"
        )
        val d1 = AiAuthorityGateway.requestActionPermit(app, session, envExpired, oldObs, "GEMINI", true, true, false, true)
        assertTrue(d1 is PermitDecision.Denied)
        assertEquals("STALE_SCREENSHOT", (d1 as PermitDecision.Denied).code)

        // Now fresh observation captured in portrait, but device rotated to landscape!
        val portraitObs = oldObs.copy(screenshotId = "scr_portrait", capturedAtMs = System.currentTimeMillis(), ttlMs = 15_000L)
        setGeometryAndObservation(2400, 1080, Configuration.ORIENTATION_LANDSCAPE)
        val envRotated = envExpired.copy(actionId = "act_rot", screenshotReference = portraitObs.screenshotId)
        val d2 = AiAuthorityGateway.requestActionPermit(app, session, envRotated, portraitObs, "GEMINI", true, true, false, true)
        assertTrue(d2 is PermitDecision.Denied)
        assertEquals("COORDINATE_VALIDATION_FAILED", (d2 as PermitDecision.Denied).code)
    }

    // 13. Coordinates negative / NaN / outside screen bounds -> rejected
    @Test
    fun test13_invalidCoordinates_rejectedByPrecisionController() = runBlocking {
        val session = AiAuthorityGateway.registerExplicitUserCommand("Tap", "GEMINI", "gemini-3.5-flash-lite")
        val obs = ScreenObservationCoordinator.captureFreshObservation(app)!!

        val negEnv = AiActionEnvelope(
            taskId = session.taskId, sessionId = session.sessionId, actionId = "c1", sequence = 1,
            action = OrchestratorActionType.TAP,
            target = AiActionTarget("Bad", coordinates = AiCoordinates(-0.1, 0.5, CoordinateSpace.NORMALIZED_0_1)),
            screenshotReference = obs.screenshotId, screenWidth = 1080, screenHeight = 2400,
            orientation = Configuration.ORIENTATION_PORTRAIT, confidence = 0.95, expectedResult = "None"
        )
        assertTrue(PrecisionController.calibrateTargetCoordinates(app, negEnv, obs) is PrecisionCalibrationResult.Rejected)

        val nanEnv = negEnv.copy(target = AiActionTarget("NaN", coordinates = AiCoordinates(Double.NaN, 0.5, CoordinateSpace.DISPLAY_PIXELS)))
        assertTrue(PrecisionController.calibrateTargetCoordinates(app, nanEnv, obs) is PrecisionCalibrationResult.Rejected)

        val outOfBoundsEnv = negEnv.copy(target = AiActionTarget("OOB", coordinates = AiCoordinates(1500.0, 2600.0, CoordinateSpace.DISPLAY_PIXELS)))
        assertTrue(PrecisionController.calibrateTargetCoordinates(app, outOfBoundsEnv, obs) is PrecisionCalibrationResult.Rejected)

        val gatewayDeny = AiAuthorityGateway.requestActionPermit(app, session, outOfBoundsEnv, obs, "GEMINI", true, true, false, true)
        assertTrue(gatewayDeny is PermitDecision.Denied)
        assertEquals("COORDINATE_VALIDATION_FAILED", (gatewayDeny as PermitDecision.Denied).code)
    }

    // 14. LOW confidence (< 0.70) -> abort, ZERO execution
    @Test
    fun test14_lowConfidence_rejectedZeroExecution() = runBlocking {
        SelectedProviderClient.testTransport = object : ProviderTransport {
            override suspend fun invoke(context: android.content.Context, request: ProviderInvocationRequest): ProviderInvocationResult {
                val taskId = extractField(request.prompt, "task_id")
                val sessionId = extractField(request.prompt, "session_id")
                val scr = extractField(request.prompt, "screenshot_reference")
                return ProviderInvocationResult.Success(
                    text = """
                    {
                      "protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId",
                      "overall_goal":"Tap uncertain button","requires_vision":true,
                      "first_action":{
                        "protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId",
                        "action_id":"act_low","sequence":1,"action":"TAP",
                        "target":{"description":"Unknown Icon","coordinates":{"x":0.5,"y":0.5,"space":"NORMALIZED_0_1"}},
                        "screenshot_reference":"$scr","confidence":0.42,"expected_result":"Something opens"
                      }
                    }
                    """.trimIndent(),
                    providerId = request.providerId, modelId = request.modelId
                )
            }
        }
        val outcome = buildOrchestrator().runTask("Tap that small icon")
        assertFalse(outcome.success)
        assertEquals("LOW_CONFIDENCE_ABORT", outcome.failureCode)
        assertEquals(0, trackingExecutor.executedEnvelopes.size)
    }

    // 15. MEDIUM confidence (0.70..0.89) -> blocked until user confirms
    @Test
    fun test15_mediumConfidence_requiresConfirmationBeforeExecution() = runBlocking {
        var confirmationAsked = false
        val calls = AtomicInteger(0)
        SelectedProviderClient.testTransport = object : ProviderTransport {
            override suspend fun invoke(context: android.content.Context, request: ProviderInvocationRequest): ProviderInvocationResult {
                val n = calls.incrementAndGet()
                val taskId = extractField(request.prompt, "task_id")
                val sessionId = extractField(request.prompt, "session_id")
                val scr = extractField(request.prompt, if (n == 1) "screenshot_reference" else "new_screenshot_reference")
                return if (n == 1) {
                    ProviderInvocationResult.Success(
                        """{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","overall_goal":"Tap tab","first_action":{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","action_id":"act_med","sequence":1,"action":"TAP","target":{"description":"Settings Tab","coordinates":{"x":0.4,"y":0.8,"space":"NORMALIZED_0_1"}},"screenshot_reference":"$scr","confidence":0.82,"expected_result":"Settings tab active"}}""",
                        request.providerId, request.modelId
                    )
                } else {
                    ProviderInvocationResult.Success(
                        """{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","screenshot_reference":"$scr","verified":true,"observed_screen_summary":"Settings tab active","is_task_completed":true,"needs_replan":false}""",
                        request.providerId, request.modelId
                    )
                }
            }
        }

        val deniedOutcome = buildOrchestrator().runTask("Tap settings tab", onRequestConfirmation = {
            confirmationAsked = true
            false
        })
        assertTrue(confirmationAsked)
        assertFalse(deniedOutcome.success)
        assertEquals("USER_DECLINED", deniedOutcome.failureCode)
        assertEquals(0, trackingExecutor.executedEnvelopes.size)

        calls.set(0)
        val approvedOutcome = buildOrchestrator().runTask("Tap settings tab", onRequestConfirmation = { true })
        assertTrue(approvedOutcome.success)
        assertEquals(1, trackingExecutor.executedEnvelopes.size)
    }

    // 16. HIGH confidence (>= 0.90) non-consequential -> proceeds with permit without blocking
    @Test
    fun test16_highConfidenceNonConsequential_proceedsWithoutBlockingConfirmation() = runBlocking {
        var confirmationPrompted = false
        val calls = AtomicInteger(0)
        SelectedProviderClient.testTransport = object : ProviderTransport {
            override suspend fun invoke(context: android.content.Context, request: ProviderInvocationRequest): ProviderInvocationResult {
                val n = calls.incrementAndGet()
                val taskId = extractField(request.prompt, "task_id")
                val sessionId = extractField(request.prompt, "session_id")
                val scr = extractField(request.prompt, if (n == 1) "screenshot_reference" else "new_screenshot_reference")
                return if (n == 1) {
                    ProviderInvocationResult.Success(
                        """{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","overall_goal":"Scroll feed","first_action":{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","action_id":"act_hi","sequence":1,"action":"SCROLL","direction":"DOWN","target":{"description":"Feed list","coordinates":{"x":0.5,"y":0.5,"space":"NORMALIZED_0_1"}},"screenshot_reference":"$scr","confidence":0.96,"expected_result":"Feed scrolled down"}}""",
                        request.providerId, request.modelId
                    )
                } else {
                    ProviderInvocationResult.Success(
                        """{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","screenshot_reference":"$scr","verified":true,"observed_screen_summary":"New posts visible","is_task_completed":true,"needs_replan":false}""",
                        request.providerId, request.modelId
                    )
                }
            }
        }
        val outcome = buildOrchestrator().runTask("Scroll down", onRequestConfirmation = {
            confirmationPrompted = true
            false
        })
        assertTrue(outcome.success)
        assertFalse(confirmationPrompted)
        assertEquals(1, trackingExecutor.executedEnvelopes.size)
    }

    // 17. Consequential/sensitive action -> confirmation required, cancel leaves device untouched
    @Test
    fun test17_consequentialAction_requiresConfirmationAndCancelTouchesNothing() = runBlocking {
        var asked = false
        SelectedProviderClient.testTransport = object : ProviderTransport {
            override suspend fun invoke(context: android.content.Context, request: ProviderInvocationRequest): ProviderInvocationResult {
                val taskId = extractField(request.prompt, "task_id")
                val sessionId = extractField(request.prompt, "session_id")
                val scr = extractField(request.prompt, "screenshot_reference")
                return ProviderInvocationResult.Success(
                    """{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","overall_goal":"Send payment","first_action":{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","action_id":"act_pay","sequence":1,"action":"TAP","target":{"description":"Pay Now","coordinates":{"x":0.5,"y":0.9,"space":"NORMALIZED_0_1"}},"screenshot_reference":"$scr","confidence":0.99,"expected_result":"Payment sent"}}""",
                    request.providerId, request.modelId
                )
            }
        }
        val outcome = buildOrchestrator().runTask("Pay 500", onRequestConfirmation = {
            asked = true
            false
        })
        assertTrue(asked)
        assertFalse(outcome.success)
        assertEquals("USER_DECLINED", outcome.failureCode)
        assertEquals(0, trackingExecutor.executedEnvelopes.size)
    }

    // 18. Action runs but post-action screen unchanged -> AI returns verified=false, needs_replan=false -> NEVER claims success
    @Test
    fun test18_unchangedPostActionScreen_neverClaimsSuccess() = runBlocking {
        val calls = AtomicInteger(0)
        SelectedProviderClient.testTransport = object : ProviderTransport {
            override suspend fun invoke(context: android.content.Context, request: ProviderInvocationRequest): ProviderInvocationResult {
                val n = calls.incrementAndGet()
                val taskId = extractField(request.prompt, "task_id")
                val sessionId = extractField(request.prompt, "session_id")
                val scr = extractField(request.prompt, if (n == 1) "screenshot_reference" else "new_screenshot_reference")
                return if (n == 1) {
                    ProviderInvocationResult.Success(
                        """{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","overall_goal":"Tap Submit","first_action":{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","action_id":"act_sub","sequence":1,"action":"TAP","target":{"description":"Disabled Submit","coordinates":{"x":0.5,"y":0.7,"space":"NORMALIZED_0_1"}},"screenshot_reference":"$scr","confidence":0.94,"expected_result":"Form submitted"}}""",
                        request.providerId, request.modelId
                    )
                } else {
                    ProviderInvocationResult.Success(
                        """{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","screenshot_reference":"$scr","verified":false,"observed_screen_summary":"Form still open unchanged","mismatch_reason":"Screen did not change after tap","is_task_completed":false,"needs_replan":false}""",
                        request.providerId, request.modelId
                    )
                }
            }
        }
        val outcome = buildOrchestrator().runTask("Tap submit")
        assertFalse(outcome.success)
        assertEquals(0, outcome.verifiedSteps)
        assertEquals("VERIFICATION_FAILED", outcome.failureCode)
    }

    // 19. Unexpected screen/popup after action -> AI replans via fresh screenshot & verifies recovery step
    @Test
    fun test19_unexpectedPopupAfterAction_aiReplansAndVerifies() = runBlocking {
        val calls = AtomicInteger(0)
        SelectedProviderClient.testTransport = object : ProviderTransport {
            override suspend fun invoke(context: android.content.Context, request: ProviderInvocationRequest): ProviderInvocationResult {
                val n = calls.incrementAndGet()
                val taskId = extractField(request.prompt, "task_id")
                val sessionId = extractField(request.prompt, "session_id")
                val scr = extractField(request.prompt, if (n == 1) "screenshot_reference" else "new_screenshot_reference")
                return when (n) {
                    1 -> ProviderInvocationResult.Success(
                        """{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","overall_goal":"Tap Profile","first_action":{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","action_id":"act_1","sequence":1,"action":"TAP","target":{"description":"Profile","coordinates":{"x":0.8,"y":0.1,"space":"NORMALIZED_0_1"}},"screenshot_reference":"$scr","confidence":0.95,"expected_result":"Profile page opens"}}""",
                        request.providerId, request.modelId
                    )
                    2 -> ProviderInvocationResult.Success(
                        """{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","screenshot_reference":"$scr","verified":false,"observed_screen_summary":"Promo popup appeared","mismatch_reason":"Unexpected popup blocking profile","is_task_completed":false,"needs_replan":true,"next_action":{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","action_id":"act_2","sequence":2,"action":"TAP","target":{"description":"Dismiss popup X","coordinates":{"x":0.9,"y":0.2,"space":"NORMALIZED_0_1"}},"screenshot_reference":"$scr","confidence":0.95,"expected_result":"Popup closed and profile visible"}}""",
                        request.providerId, request.modelId
                    )
                    else -> ProviderInvocationResult.Success(
                        """{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","screenshot_reference":"$scr","verified":true,"observed_screen_summary":"Profile page visible","is_task_completed":true,"needs_replan":false}""",
                        request.providerId, request.modelId
                    )
                }
            }
        }
        val outcome = buildOrchestrator().runTask("Open profile")
        assertTrue(outcome.success)
        assertEquals(2, outcome.stepsExecuted)
        assertEquals(1, outcome.verifiedSteps)
        assertEquals(listOf("act_1", "act_2"), trackingExecutor.executedEnvelopes.map { it.actionId })
    }

    // 20. Emergency Stop mid-task -> immediate halt, permits revoked, remaining actions unexecuted
    @Test
    fun test20_emergencyStopMidTask_haltsRemainingActions() = runBlocking {
        val orchestrator = buildOrchestrator()
        val calls = AtomicInteger(0)
        SelectedProviderClient.testTransport = object : ProviderTransport {
            override suspend fun invoke(context: android.content.Context, request: ProviderInvocationRequest): ProviderInvocationResult {
                val n = calls.incrementAndGet()
                val taskId = extractField(request.prompt, "task_id")
                val sessionId = extractField(request.prompt, "session_id")
                val scr = extractField(request.prompt, if (n == 1) "screenshot_reference" else "new_screenshot_reference")
                if (n == 2) {
                    orchestrator.emergencyStop("Emergency stop during verification")
                }
                return if (n == 1) {
                    ProviderInvocationResult.Success(
                        """{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","overall_goal":"Multi step","first_action":{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","action_id":"act_s1","sequence":1,"action":"TAP","target":{"description":"Search","coordinates":{"x":0.5,"y":0.1,"space":"NORMALIZED_0_1"}},"screenshot_reference":"$scr","confidence":0.95,"expected_result":"Search opens"}}""",
                        request.providerId, request.modelId
                    )
                } else {
                    ProviderInvocationResult.Success(
                        """{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","screenshot_reference":"$scr","verified":true,"observed_screen_summary":"Search open","is_task_completed":false,"next_action":{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","action_id":"act_s2","sequence":2,"action":"TYPE","text":"hello","target":{"description":"Search input"},"screenshot_reference":"$scr","confidence":0.95,"expected_result":"Text typed"}}""",
                        request.providerId, request.modelId
                    )
                }
            }
        }
        val outcome = orchestrator.runTask("Search hello")
        assertFalse(outcome.success)
        assertEquals(1, trackingExecutor.executedEnvelopes.size)
        assertEquals(0, AiAuthorityGateway.activePermitCount())
    }

    // 21. Pause -> Resume -> fresh screenshot required before continuing
    @Test
    fun test21_pauseAndResume_forcesFreshScreenshotAndAiReconfirmation() = runBlocking {
        val orchestrator = buildOrchestrator()
        val calls = AtomicInteger(0)
        val seenRefsForAct2 = mutableListOf<String>()

        SelectedProviderClient.testTransport = object : ProviderTransport {
            override suspend fun invoke(context: android.content.Context, request: ProviderInvocationRequest): ProviderInvocationResult {
                val n = calls.incrementAndGet()
                val taskId = extractField(request.prompt, "task_id")
                val sessionId = extractField(request.prompt, "session_id")
                val scr = extractField(request.prompt, "screenshot_reference")
                    .ifBlank { extractField(request.prompt, "new_screenshot_reference") }
                    .ifBlank { extractField(request.prompt, "current_screenshot_reference") }

                return when (n) {
                    1 -> ProviderInvocationResult.Success(
                        """{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","overall_goal":"2 steps","first_action":{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","action_id":"act_p1","sequence":1,"action":"TAP","target":{"description":"Tab 1","coordinates":{"x":0.2,"y":0.9,"space":"NORMALIZED_0_1"}},"screenshot_reference":"$scr","confidence":0.95,"expected_result":"Tab 1 active"}}""",
                        request.providerId, request.modelId
                    )
                    2 -> {
                        // Pause right as verification completes and returns next_action bound to scr_test_2
                        orchestrator.pauseActiveTask("User paused")
                        ProviderInvocationResult.Success(
                            """{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","screenshot_reference":"$scr","verified":true,"observed_screen_summary":"Tab 1 active","is_task_completed":false,"next_action":{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","action_id":"act_p2_stale","sequence":2,"action":"TAP","target":{"description":"Btn 2","coordinates":{"x":0.5,"y":0.5,"space":"NORMALIZED_0_1"}},"screenshot_reference":"$scr","confidence":0.95,"expected_result":"Btn 2 opens"}}""",
                            request.providerId, request.modelId
                        )
                    }
                    3 -> {
                        // Replan after resume MUST provide brand-new screenshot scr_test_3!
                        seenRefsForAct2.add(scr)
                        ProviderInvocationResult.Success(
                            """{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","action_id":"act_p2_fresh","sequence":2,"action":"TAP","target":{"description":"Btn 2 fresh","coordinates":{"x":0.5,"y":0.5,"space":"NORMALIZED_0_1"}},"screenshot_reference":"$scr","confidence":0.95,"expected_result":"Btn 2 opens"}""",
                            request.providerId, request.modelId
                        )
                    }
                    else -> ProviderInvocationResult.Success(
                        """{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","screenshot_reference":"$scr","verified":true,"observed_screen_summary":"Btn 2 opened","is_task_completed":true,"needs_replan":false}""",
                        request.providerId, request.modelId
                    )
                }
            }
        }

        val job = async { orchestrator.runTask("Open tab 1 then tap btn 2") }
        delay(800L)
        assertEquals(OrchestrationLifecycleState.TASK_PAUSED, TaskOrchestrationStateMachine.snapshot.value.state)
        orchestrator.resumePausedTask()

        val outcome = job.await()
        assertTrue(outcome.success)
        assertEquals(listOf("scr_test_3"), seenRefsForAct2)
        assertEquals(listOf("act_p1", "act_p2_fresh"), trackingExecutor.executedEnvelopes.map { it.actionId })
    }

    // 22. Network drop mid-task -> suspended/paused, reconnect forces fresh screenshot & AI reassessment
    @Test
    fun test22_networkDropMidTask_pausesAndReobservesOnReconnect() = runBlocking {
        val orchestrator = buildOrchestrator()
        val calls = AtomicInteger(0)
        val replanRefs = mutableListOf<String>()

        SelectedProviderClient.testTransport = object : ProviderTransport {
            override suspend fun invoke(context: android.content.Context, request: ProviderInvocationRequest): ProviderInvocationResult {
                val n = calls.incrementAndGet()
                val taskId = extractField(request.prompt, "task_id")
                val sessionId = extractField(request.prompt, "session_id")
                val scr = extractField(request.prompt, "screenshot_reference")
                    .ifBlank { extractField(request.prompt, "new_screenshot_reference") }
                    .ifBlank { extractField(request.prompt, "current_screenshot_reference") }

                return when (n) {
                    1 -> ProviderInvocationResult.Success(
                        """{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","overall_goal":"2 steps","first_action":{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","action_id":"act_n1","sequence":1,"action":"TAP","target":{"description":"Menu","coordinates":{"x":0.1,"y":0.1,"space":"NORMALIZED_0_1"}},"screenshot_reference":"$scr","confidence":0.95,"expected_result":"Menu open"}}""",
                        request.providerId, request.modelId
                    )
                    2 -> {
                        // Drop network right before step 2
                        SelectedProviderClient.testNetworkConnectedOverride = false
                        ProviderInvocationResult.Success(
                            """{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","screenshot_reference":"$scr","verified":true,"observed_screen_summary":"Menu open","is_task_completed":false,"next_action":{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","action_id":"act_n2_old","sequence":2,"action":"TAP","target":{"description":"Settings","coordinates":{"x":0.3,"y":0.5,"space":"NORMALIZED_0_1"}},"screenshot_reference":"$scr","confidence":0.95,"expected_result":"Settings open"}}""",
                            request.providerId, request.modelId
                        )
                    }
                    3 -> {
                        replanRefs.add(scr)
                        ProviderInvocationResult.Success(
                            """{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","action_id":"act_n2_fresh","sequence":2,"action":"TAP","target":{"description":"Settings fresh","coordinates":{"x":0.3,"y":0.5,"space":"NORMALIZED_0_1"}},"screenshot_reference":"$scr","confidence":0.95,"expected_result":"Settings open"}""",
                            request.providerId, request.modelId
                        )
                    }
                    else -> ProviderInvocationResult.Success(
                        """{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","screenshot_reference":"$scr","verified":true,"observed_screen_summary":"Settings open","is_task_completed":true,"needs_replan":false}""",
                        request.providerId, request.modelId
                    )
                }
            }
        }

        val job = async { orchestrator.runTask("Open menu then settings") }
        delay(800L)
        SelectedProviderClient.testNetworkConnectedOverride = true
        val outcome = job.await()
        assertTrue(outcome.success)
        assertEquals(listOf("scr_test_3"), replanRefs)
        assertEquals(listOf("act_n1", "act_n2_fresh"), trackingExecutor.executedEnvelopes.map { it.actionId })
    }

    // 23. App restart (invalidateAllOnStartup) -> stale tasks/permits never auto-execute
    @Test
    fun test23_appRestart_invalidatesStaleSessionsAndPermits() = runBlocking {
        val session = AiAuthorityGateway.registerExplicitUserCommand("Tap confirm", "GEMINI", "gemini-3.5-flash-lite")
        val obs = ScreenObservationCoordinator.captureFreshObservation(app)!!
        val env = AiActionEnvelope(
            taskId = session.taskId, sessionId = session.sessionId, actionId = "act_restart", sequence = 1,
            action = OrchestratorActionType.TAP, target = AiActionTarget("Item", coordinates = AiCoordinates(0.5, 0.5)),
            screenshotReference = obs.screenshotId, screenWidth = obs.displayWidthPx, screenHeight = obs.displayHeightPx,
            orientation = obs.orientation, confidence = 0.95, expectedResult = "Opened"
        )
        val permit = (AiAuthorityGateway.requestActionPermit(app, session, env, obs, "GEMINI", true, true, false, true) as PermitDecision.Granted).permit

        AiAuthorityGateway.invalidateAllOnStartup()
        assertEquals(0, AiAuthorityGateway.activePermitCount())
        assertFalse(session.isActive)
        assertTrue(AiAuthorityGateway.consumePermit(permit) is PermitConsumeResult.Rejected)
    }

    // 24. Non-vision model selected + screen vision task -> explicit CAPABILITY_MISMATCH_NO_VISION refusal
    @Test
    fun test24_nonVisionModelOnVisionTask_refusedWithoutProviderSwitch() = runBlocking {
        AppPreferences.setAiProvider(app, "OPENROUTER")
        AppPreferences.setOpenRouterApiKey(app, "sk-or-test-key-123456")
        AppPreferences.setOpenRouterModel(app, "meta-llama/llama-3.1-8b-instruct")

        var called = false
        SelectedProviderClient.testTransport = object : ProviderTransport {
            override suspend fun invoke(context: android.content.Context, request: ProviderInvocationRequest): ProviderInvocationResult {
                called = true
                return ProviderInvocationResult.Failure("ERR", "Should not be reached")
            }
        }
        val outcome = buildOrchestrator().runTask("Tap the blue button on screen", requiresScreenVision = true)
        assertFalse(outcome.success)
        assertEquals("CAPABILITY_MISMATCH_NO_VISION", outcome.failureCode)
        assertFalse(called)
        assertEquals(0, trackingExecutor.executedEnvelopes.size)
    }

    // 25. Voice / Orb / Screen Share entry points route through SelectedProviderClient & never fall back offline
    @Test
    fun test25_orbScreenShareAndTaskRouter_obeyAiOnlyAuthority() = runBlocking {
        // Offline / missing key must make JarvisGuidanceEngine publish an honest error, NEVER a local plan
        AppPreferences.clearCustomApiKey(app)
        JarvisGuidanceEngine.executeJarvisQuery("explain this screen")
        val exp = JarvisGuidanceEngine.activeExplanation.value
        assertNotNull(exp)
        assertTrue(exp!!.confidenceScore == 0.0f)

        // TaskRouter must route phone control to NONE when no online API key is present, NEVER GEMMA_OFFLINE or ANDROID_ACCESSIBILITY
        val routeNoKey = TaskRouter.routeTask(prompt = "open youtube", hasImage = false, context = app)
        assertEquals("NONE", routeNoKey.provider)

        // With valid Gemini key, TaskRouter routes phone control strictly to GEMINI
        AppPreferences.setCustomApiKey(app, "AIzaSyTestValidDummyKey1234567890")
        val routeWithKey = TaskRouter.routeTask(prompt = "open youtube", hasImage = false, context = app)
        assertEquals("GEMINI", routeWithKey.provider)

        // KavyaAI must fail honestly when key is missing and never fall back to LocalAssistantEngine or GemmaOffline
        AppPreferences.clearCustomApiKey(app)
        val aiReply = KavyaAI(app).chat("open settings", emptyList(), "", "")
        assertTrue(aiReply.startsWith("Error:"))
    }

    // 26. Pure conversational question answers cleanly with ZERO Accessibility actions
    @Test
    fun test26_pureConversationalQuestion_answersWithZeroAccessibilityActions() = runBlocking {
        SelectedProviderClient.testTransport = object : ProviderTransport {
            override suspend fun invoke(context: android.content.Context, request: ProviderInvocationRequest): ProviderInvocationResult {
                val taskId = extractField(request.prompt, "task_id")
                val sessionId = extractField(request.prompt, "session_id")
                return ProviderInvocationResult.Success(
                    """{"protocol_version":"1.0","mode":"CONVERSATION","task_id":"$taskId","session_id":"$sessionId","assistant_message":"Kotlin is a modern statically typed programming language."}""",
                    request.providerId, request.modelId
                )
            }
        }
        val outcome = buildOrchestrator().runTask("What is Kotlin?", requiresScreenVision = false)
        assertTrue(outcome.success)
        assertEquals(0, outcome.stepsExecuted)
        assertEquals(0, trackingExecutor.executedEnvelopes.size)
        assertTrue(outcome.finalMessage.contains("Kotlin"))
    }

    // 27. Verification JSON with empty/blank next_action object (screenshot_1 bug fix) succeeds cleanly
    @Test
    fun test27_verificationJsonWithEmptyOrNullNextAction_succeedsWithoutMalformedActionError() = runBlocking {
        val calls = AtomicInteger(0)
        SelectedProviderClient.testTransport = object : ProviderTransport {
            override suspend fun invoke(context: android.content.Context, request: ProviderInvocationRequest): ProviderInvocationResult {
                val n = calls.incrementAndGet()
                val taskId = extractField(request.prompt, "task_id")
                val sessionId = extractField(request.prompt, "session_id")
                val scr = extractField(request.prompt, if (n == 1) "screenshot_reference" else "new_screenshot_reference")
                return if (n == 1) {
                    ProviderInvocationResult.Success(
                        """{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","overall_goal":"Open YouTube","first_action":{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","action_id":"act_yt_1","sequence":1,"action":"OPEN_APP","app_name":"YouTube","target":{"description":"YouTube"},"screenshot_reference":"$scr","confidence":0.98,"expected_result":"YouTube is in foreground"}}""",
                        request.providerId, request.modelId
                    )
                } else {
                    // Simulate Gemini returning next_action with empty action string when is_task_completed=true
                    ProviderInvocationResult.Success(
                        """{"protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId","screenshot_reference":"$scr","verified":true,"verification_status":"SUCCESS","observed_screen_summary":"YouTube home screen visible","mismatch_reason":"","is_task_completed":true,"needs_replan":false,"assistant_message":"YouTube khul gaya hai.","next_action":{"action":""}}""",
                        request.providerId, request.modelId
                    )
                }
            }
        }
        val outcome = buildOrchestrator().runTask("YouTube kholo")
        assertTrue(outcome.success)
        assertEquals(1, outcome.stepsExecuted)
        assertEquals(1, outcome.verifiedSteps)
        assertTrue(outcome.finalMessage.contains("YouTube"))
    }

    // 28. Single reliable ApiStateManager covers all 8 states and never confuses timeout/network with missing key
    @Test
    fun test28_apiStateManager_distinguishesMissingKeyFromTimeoutAndNetworkErrorsWithoutDeletingKey() {
        AppPreferences.clearCustomApiKey(app)
        val noKeySnap = ApiStateManager.syncFromPreferences(app)
        assertEquals(ApiConnectionState.NOT_CONFIGURED, noKeySnap.state)
        assertFalse(noKeySnap.keyConfigured)

        val secretKey = "AIzaSySuperSecretKey99887766"
        AppPreferences.setCustomApiKey(app, secretKey)
        ApiStateManager.markValidating("GEMINI", "gemini-3.5-flash-lite", secretKey)
        assertEquals(ApiConnectionState.VALIDATING, ApiStateManager.state.value.state)
        assertFalse(ApiStateManager.state.value.diagnosticMessage.contains(secretKey))

        // Timeout must transition to TEMPORARILY_UNAVAILABLE, keep keyConfigured=true, and NEVER delete saved key
        val timeoutSnap = ApiStateManager.recordFailure("GEMINI", "gemini-3.5-flash-lite", secretKey, "TIMEOUT", "SocketTimeoutException timed out")
        assertEquals(ApiConnectionState.TEMPORARILY_UNAVAILABLE, timeoutSnap.state)
        assertTrue(timeoutSnap.keyConfigured)
        assertEquals(secretKey, AppPreferences.getEffectiveApiKey(app))
        assertFalse(timeoutSnap.diagnosticMessage.contains("No API key configured"))

        // Network error -> NETWORK_ERROR
        val netSnap = ApiStateManager.recordFailure("GEMINI", "gemini-3.5-flash-lite", secretKey, "NETWORK_UNAVAILABLE", "Unable to resolve host")
        assertEquals(ApiConnectionState.NETWORK_ERROR, netSnap.state)
        assertTrue(netSnap.keyConfigured)

        // Quota -> QUOTA_EXCEEDED
        val quotaSnap = ApiStateManager.recordFailure("GEMINI", "gemini-3.5-flash-lite", secretKey, "HTTP_429", "Quota exceeded 429")
        assertEquals(ApiConnectionState.QUOTA_EXCEEDED, quotaSnap.state)

        // Invalid key -> INVALID_KEY
        val invalidSnap = ApiStateManager.recordFailure("GEMINI", "gemini-3.5-flash-lite", secretKey, "HTTP_401", "Invalid API key 401")
        assertEquals(ApiConnectionState.INVALID_KEY, invalidSnap.state)

        // Provider error -> PROVIDER_ERROR (with secret redaction)
        val provSnap = ApiStateManager.recordFailure("GEMINI", "gemini-3.5-flash-lite", secretKey, "HTTP_500", "Internal error with key $secretKey")
        assertEquals(ApiConnectionState.PROVIDER_ERROR, provSnap.state)
        assertFalse(provSnap.diagnosticMessage.contains(secretKey))

        // Connected -> CONNECTED
        ApiStateManager.recordSuccess("GEMINI", "gemini-3.5-flash-lite", secretKey)
        assertEquals(ApiConnectionState.CONNECTED, ApiStateManager.state.value.state)
    }

    // 29. Unified Intent Understanding: Hindi, English, Hinglish, and normal conversation mentioning app names
    @Test
    fun test29_unifiedIntentUnderstanding_hindiEnglishHinglishAndConversationalAppMentions() {
        val resolver = com.example.utils.AppResolver(app)

        // Normal conversation mentioning app names MUST NOT trigger app launch
        val q1 = com.example.agent.IntentGate.evaluate("Instagram kya hai?", resolver)
        assertTrue(q1.category == com.example.agent.IntentCategory.QUESTION || q1.category == com.example.agent.IntentCategory.CHAT)
        assertFalse(q1.isExplicitAction)

        val q2 = com.example.agent.IntentGate.evaluate("Mujhe Instagram pasand hai", resolver)
        assertEquals(com.example.agent.IntentCategory.CHAT, q2.category)
        assertFalse(q2.isExplicitAction)

        val c2 = com.example.agent.UserCommandClassifier.classify("Mujhe Instagram pasand hai", resolver)
        assertEquals(com.example.agent.CommandCategory.CONVERSATION, c2.category)

        // Explicit app launch
        val open1 = com.example.agent.IntentGate.evaluate("Instagram kholo", resolver)
        assertEquals(com.example.agent.IntentCategory.ACTION, open1.category)
        assertTrue(open1.isExplicitAction)

        // Multi-step task
        val multi = com.example.agent.IntentGate.evaluate("Chrome kholo aur weather search karo", resolver)
        assertEquals(com.example.agent.IntentCategory.MULTI_STEP_TASK, multi.category)
        assertEquals(2, multi.steps.size)

        // Stop / Cancel in Hinglish
        val stop = com.example.agent.IntentGate.evaluate("Ab ruk jao", resolver)
        assertEquals(com.example.agent.IntentCategory.STOP, stop.category)
    }

    // 30. Context-dependent follow-up resolution ("wahan", "usko", "phir se") & truthful progress updates
    @Test
    fun test30_contextDependentFollowUpResolution_andTruthfulProgressUpdates() {
        ConversationTaskContextManager.resetForTest()
        val envOpen = AiActionEnvelope(
            taskId = "t1", sessionId = "s1", actionId = "a1", sequence = 1,
            action = OrchestratorActionType.OPEN_APP, appName = "YouTube",
            target = AiActionTarget("YouTube"), confidence = 0.96, expectedResult = "YouTube open"
        )
        assertEquals("Opening application...", ConversationTaskContextManager.truthfulProgressMessageForAction(envOpen))

        ConversationTaskContextManager.onTaskStarted("t1", "YouTube kholo", "YouTube kholo")
        ConversationTaskContextManager.onActionDispatched(envOpen, "com.google.android.youtube")
        ConversationTaskContextManager.onTaskFinished(true, "Opened YouTube")

        assertTrue(ConversationTaskContextManager.isContextualFollowUp("wahan music search karo"))
        val resolvedFollowUp = ConversationTaskContextManager.resolveContextualRequest("wahan music search karo")
        assertTrue(resolvedFollowUp.contains("YouTube"))

        val repeatResolved = ConversationTaskContextManager.resolveContextualRequest("phir se")
        assertEquals("YouTube kholo", repeatResolved)

        // Unrelated explicit new command must NOT be Polluted by old context
        val unrelated = ConversationTaskContextManager.resolveContextualRequest("Open Calculator")
        assertEquals("Open Calculator", unrelated)
    }

    // 31. Dependency-aware task planner blocks dependent step when prerequisite step was not completed
    @Test
    fun test31_dependencyAwareTaskPlanner_blocksDependentStepWhenPrerequisiteNotCompleted() = runBlocking {
        SelectedProviderClient.testTransport = object : ProviderTransport {
            override suspend fun invoke(context: android.content.Context, request: ProviderInvocationRequest): ProviderInvocationResult {
                val taskId = extractField(request.prompt, "task_id")
                val sessionId = extractField(request.prompt, "session_id")
                val scr = extractField(request.prompt, "screenshot_reference")
                // Plan declares step 2 depends on step 1, and first_action jumps to sequence 2 without step 1 verified
                return ProviderInvocationResult.Success(
                    """
                    {
                      "protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId",
                      "overall_goal":"Search inside Chrome",
                      "steps":[
                        {"step_number":1,"goal":"Open Chrome","expected_outcome":"Chrome open","depends_on":[]},
                        {"step_number":2,"goal":"Type query","expected_outcome":"Query typed","depends_on":[1]}
                      ],
                      "first_action":{
                        "protocol_version":"1.0","task_id":"$taskId","session_id":"$sessionId",
                        "action_id":"act_skip","sequence":2,"action":"TYPE","text":"weather",
                        "target":{"description":"Search box","selector":"search_box"},
                        "screenshot_reference":"$scr","confidence":0.95,"expected_result":"Typed weather"
                      }
                    }
                    """.trimIndent(),
                    request.providerId, request.modelId
                )
            }
        }
        val outcome = buildOrchestrator().runTask("Chrome kholo aur weather search karo")
        assertFalse(outcome.success)
        assertEquals("DEPENDENCY_FAILED", outcome.failureCode)
        assertEquals(0, trackingExecutor.executedEnvelopes.size)
    }

    // 32. Multi-Window / Split-Screen controller parses app pairs and rejects unpermitted requests
    @Test
    fun test32_multiWindowSplitScreenController_parsesAppsAndRejectsUnpermittedRequests(): Unit = runBlocking {
        val pair = MultiWindowController.parseSplitAppPair("Split screen me YouTube aur Chrome kholo")
        assertEquals("YouTube", pair.first)
        assertEquals("Chrome", pair.second)

        val fakePermit = ActionAuthorizationPermit(
            permitId = "forged_mw", taskId = "t", sessionId = "s", actionId = "a", sequence = 1,
            action = OrchestratorActionType.SPLIT_SCREEN, providerId = "GEMINI", screenshotReference = "scr",
            issuedAtMs = System.currentTimeMillis(), expiresAtMs = System.currentTimeMillis() + 5000L
        )
        val unpermittedResult = MultiWindowController.executeSplitScreen(app, fakePermit, "YouTube", "Chrome")
        assertFalse(unpermittedResult.success)
        assertEquals(OrchestratorVerificationStatus.FAILED, unpermittedResult.verificationStatus)
    }

    // 33. Unified JARVIS Live Overlay lifecycle: persists across explanation and AI errors, single window root, safe expand/minimize/start/stop
    @Test
    fun test33_unifiedJarvisOverlayLifecycle_persistsAcrossExplanationAndAiErrorsWithoutDuplicateWindows() {
        com.example.visual.JarvisOverlayController.reportOverlayAttached(true)
        assertTrue(com.example.visual.JarvisOverlayController.isOverlayRunning.value)
        assertEquals(1, com.example.visual.JarvisOverlayController.activeWindowCount.value)
        assertEquals(
            com.example.visual.OverlayAttachmentState.ATTACHED_VISIBLE,
            com.example.visual.JarvisOverlayController.attachmentState.value
        )

        // Starting an explanation MUST NOT detach or hide the overlay or create duplicate windows
        com.example.visual.JarvisGuidanceEngine.publishExplanation(
            title = "Screen Explanation",
            summary = "YouTube home feed is visible.",
            bulletPoints = listOf("Search icon at top right", "Home tab selected"),
            details = "Full explanation",
            confidence = 0.95f
        )
        assertTrue(com.example.visual.JarvisOverlayController.isOverlayRunning.value)
        assertEquals(1, com.example.visual.JarvisOverlayController.activeWindowCount.value)
        assertEquals(com.example.visual.JarvisOrbState.EXPLAINING, com.example.visual.JarvisOverlayController.orbState.value)
        assertNotNull(com.example.visual.JarvisOverlayController.activePlan.value)

        // AI error during explanation MUST keep overlay alive and show inline error card
        com.example.visual.JarvisOverlayController.showInlineOverlayError(
            title = "AI Visual Analysis Failed",
            message = "Temporary network timeout"
        )
        assertTrue(com.example.visual.JarvisOverlayController.isOverlayRunning.value)
        assertEquals(1, com.example.visual.JarvisOverlayController.activeWindowCount.value)
        assertEquals(com.example.visual.JarvisOrbState.ERROR, com.example.visual.JarvisOverlayController.orbState.value)
        assertEquals("Temporary network timeout", com.example.visual.JarvisOverlayController.inlineStatusBanner.value)

        // Expand and minimize cleanly
        com.example.visual.JarvisOverlayController.setOrbExpanded(true)
        assertTrue(com.example.visual.JarvisOverlayController.isOrbExpanded.value)
        com.example.visual.JarvisOverlayController.setOrbMinimized(true)
        assertTrue(com.example.visual.JarvisOverlayController.isOrbMinimized.value)
        assertFalse(com.example.visual.JarvisOverlayController.isOrbExpanded.value)
        com.example.visual.JarvisOverlayController.setOrbMinimized(false)
        assertFalse(com.example.visual.JarvisOverlayController.isOrbMinimized.value)

        // Repeated start/stop must never crash
        com.example.visual.JarvisOverlayController.stopOverlay(app)
        com.example.visual.JarvisOverlayController.stopOverlay(app)
        assertFalse(com.example.visual.JarvisOverlayController.isOverlayRunning.value)
        assertEquals(0, com.example.visual.JarvisOverlayController.activeWindowCount.value)
    }

    // 34. Screenshot validation: accepts valid non-empty bitmap and rejects null, recycled, invalid dimensions, or stale timestamps
    @Test
    fun test34_screenshotValidation_acceptsValidAndRejectsNullEmptyRecycledOrStaleBitmaps() {
        val now = System.currentTimeMillis()
        val validBmp = android.graphics.Bitmap.createBitmap(320, 640, android.graphics.Bitmap.Config.ARGB_8888)
        val validRes = ScreenObservationCoordinator.validateScreenshotBitmap(
            bitmap = validBmp,
            capturedAtMs = now,
            minTimestampMs = now - 500L,
            source = "MEDIA_PROJECTION_FRESH"
        )
        assertTrue(validRes is ScreenObservationCoordinator.ScreenshotValidationResult.Valid)
        val validBytes = (validRes as ScreenObservationCoordinator.ScreenshotValidationResult.Valid).imageBytes
        assertTrue(validBytes.isNotEmpty())

        // Null bitmap rejected
        val nullRes = ScreenObservationCoordinator.validateScreenshotBitmap(null, now)
        assertTrue(nullRes is ScreenObservationCoordinator.ScreenshotValidationResult.Invalid)
        assertEquals("NULL_BITMAP", (nullRes as ScreenObservationCoordinator.ScreenshotValidationResult.Invalid).code)

        // Stale timestamp rejected
        val staleRes = ScreenObservationCoordinator.validateScreenshotBitmap(
            bitmap = validBmp,
            capturedAtMs = now - 10_000L,
            minTimestampMs = now - 1_000L
        )
        assertTrue(staleRes is ScreenObservationCoordinator.ScreenshotValidationResult.Invalid)
        assertEquals("STALE_TIMESTAMP", (staleRes as ScreenObservationCoordinator.ScreenshotValidationResult.Invalid).code)

        // Empty pre-encoded bytes rejected
        val emptyBytesRes = ScreenObservationCoordinator.validateScreenshotBitmap(
            bitmap = validBmp,
            capturedAtMs = now,
            preEncodedBytes = ByteArray(0)
        )
        assertTrue(emptyBytesRes is ScreenObservationCoordinator.ScreenshotValidationResult.Invalid)
        assertEquals("EMPTY_IMAGE_BYTES", (emptyBytesRes as ScreenObservationCoordinator.ScreenshotValidationResult.Invalid).code)

        // Recycled bitmap rejected
        validBmp.recycle()
        val recycledRes = ScreenObservationCoordinator.validateScreenshotBitmap(validBmp, now)
        assertTrue(recycledRes is ScreenObservationCoordinator.ScreenshotValidationResult.Invalid)
        assertEquals("RECYCLED_BITMAP", (recycledRes as ScreenObservationCoordinator.ScreenshotValidationResult.Invalid).code)
    }

    // 35. Selected provider receives real non-empty screenshot image bytes for visual requests
    @Test
    fun test35_aiProviderImagePayload_transmitsNonEmptyImageBytesToSelectedProvider() = runBlocking {
        var receivedBytes: ByteArray? = null
        SelectedProviderClient.testTransport = object : ProviderTransport {
            override suspend fun invoke(context: android.content.Context, request: ProviderInvocationRequest): ProviderInvocationResult {
                receivedBytes = request.imageBytes
                return ProviderInvocationResult.Success("Screen shows Settings.", request.providerId, request.modelId)
            }
        }
        val sampleBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x01, 0x02)
        val res = SelectedProviderClient.invokeSelectedProvider(
            context = app,
            prompt = "Explain this screen",
            systemInstruction = "Visual analyst",
            imageBytes = sampleBytes,
            requiresVision = true
        )
        assertTrue(res is ProviderInvocationResult.Success)
        assertNotNull(receivedBytes)
        assertEquals(sampleBytes.size, receivedBytes!!.size)
    }

    // 36. Voice-first Basic / Standard / Advanced explanation level inference and step-by-step Next / Previous / Repeat controls
    @Test
    fun test36_voiceFirstExplanationLevelsAndStepGuidance_basicStandardAdvancedAndNextStop() {
        // Default unspecified query -> STANDARD
        assertEquals(
            com.example.visual.ScreenSharePresentationLevel.STANDARD,
            com.example.visual.JarvisGuidanceEngine.inferExplanationLevelFromQuery("Is screen ko samjhao")
        )
        assertEquals(
            com.example.visual.ScreenSharePresentationLevel.STANDARD,
            com.example.visual.JarvisGuidanceEngine.inferExplanationLevelFromQuery("Explain this screen")
        )

        // Basic / Simple -> BASIC
        assertEquals(
            com.example.visual.ScreenSharePresentationLevel.BASIC,
            com.example.visual.JarvisGuidanceEngine.inferExplanationLevelFromQuery("Simple aasan bhasha me samjhao")
        )
        assertEquals(
            com.example.visual.ScreenSharePresentationLevel.BASIC,
            com.example.visual.JarvisGuidanceEngine.inferExplanationLevelFromQuery("Give me a quick basic explanation")
        )

        // Advanced / Detailed / Technical -> ADVANCED
        assertEquals(
            com.example.visual.ScreenSharePresentationLevel.ADVANCED,
            com.example.visual.JarvisGuidanceEngine.inferExplanationLevelFromQuery("Explain this screen in advanced technical detail")
        )
        assertEquals(
            com.example.visual.ScreenSharePresentationLevel.ADVANCED,
            com.example.visual.JarvisGuidanceEngine.inferExplanationLevelFromQuery("Vistar se poori jankari do")
        )

        // Step-by-step voice navigation ("Next", "Previous", "Repeat")
        val steps = listOf(
            com.example.visual.ScreenAnnotationStep(
                stepNumber = 1, totalSteps = 2, title = "Step 1", label = "Tap Settings",
                spokenExplanation = "First tap the Settings icon.", bounds = android.graphics.Rect(10, 10, 100, 100)
            ),
            com.example.visual.ScreenAnnotationStep(
                stepNumber = 2, totalSteps = 2, title = "Step 2", label = "Tap Wi-Fi",
                spokenExplanation = "Next tap Wi-Fi to configure network.", bounds = android.graphics.Rect(10, 150, 100, 250)
            )
        )
        com.example.visual.ExplanationStepController.startSequence(steps, autoAdvance = false)
        assertEquals(1, com.example.visual.ExplanationStepController.currentStep.value?.stepNumber)

        val nextReply = com.example.visual.JarvisGuidanceEngine.handleVoiceGuidanceControlCommand(app, "next")
        assertNotNull(nextReply)
        assertTrue(nextReply!!.contains("Step 2 of 2"))
        assertEquals(2, com.example.visual.ExplanationStepController.currentStep.value?.stepNumber)

        val prevReply = com.example.visual.JarvisGuidanceEngine.handleVoiceGuidanceControlCommand(app, "previous")
        assertNotNull(prevReply)
        assertTrue(prevReply!!.contains("Step 1 of 2"))
        assertEquals(1, com.example.visual.ExplanationStepController.currentStep.value?.stepNumber)

        val repeatReply = com.example.visual.JarvisGuidanceEngine.handleVoiceGuidanceControlCommand(app, "phir se batao")
        assertNotNull(repeatReply)
        assertTrue(repeatReply!!.contains("First tap the Settings icon"))

        com.example.visual.ExplanationStepController.clear()
    }

    // 37. App Launch vs Browser Routing: "Open YouTube" launches installed app, "Open YouTube website" opens browser URL
    @Test
    fun test37_appLaunchVsBrowserRouting_openYouTubeLaunchesAppAndNotBrowserWebsite() {
        val ytAppRoute = AppAndWebsiteRoutingClassifier.classifyTarget("Open YouTube")
        assertTrue(ytAppRoute is AppAndWebsiteRoutingClassifier.RouteDecision.InstalledApp)
        assertEquals(
            "com.google.android.youtube",
            (ytAppRoute as AppAndWebsiteRoutingClassifier.RouteDecision.InstalledApp).canonicalPackageHint
        )

        val ytHindiAppRoute = AppAndWebsiteRoutingClassifier.classifyTarget("YouTube kholo")
        assertTrue(ytHindiAppRoute is AppAndWebsiteRoutingClassifier.RouteDecision.InstalledApp)
        assertEquals(
            "com.google.android.youtube",
            (ytHindiAppRoute as AppAndWebsiteRoutingClassifier.RouteDecision.InstalledApp).canonicalPackageHint
        )

        val ytWebsiteRoute = AppAndWebsiteRoutingClassifier.classifyTarget("Open YouTube website")
        assertTrue(ytWebsiteRoute is AppAndWebsiteRoutingClassifier.RouteDecision.BrowserWebsite)
        assertEquals(
            "https://www.youtube.com",
            (ytWebsiteRoute as AppAndWebsiteRoutingClassifier.RouteDecision.BrowserWebsite).url
        )

        val webSearchRoute = AppAndWebsiteRoutingClassifier.classifyTarget("Search Android Compose on Google")
        assertTrue(webSearchRoute is AppAndWebsiteRoutingClassifier.RouteDecision.WebSearch)
        assertTrue((webSearchRoute as AppAndWebsiteRoutingClassifier.RouteDecision.WebSearch).searchQuery.contains("Android Compose"))

        val chromeAppRoute = AppAndWebsiteRoutingClassifier.classifyTarget("Open Chrome")
        assertTrue(chromeAppRoute is AppAndWebsiteRoutingClassifier.RouteDecision.InstalledApp)
        assertEquals(
            "com.android.chrome",
            (chromeAppRoute as AppAndWebsiteRoutingClassifier.RouteDecision.InstalledApp).canonicalPackageHint
        )

        // Foreground package verification check
        assertTrue(AppAndWebsiteRoutingClassifier.verifyForegroundPackageMatch("com.google.android.youtube", "com.google.android.youtube"))
        assertFalse(AppAndWebsiteRoutingClassifier.verifyForegroundPackageMatch("com.google.android.youtube", "com.android.chrome"))
    }

    // 38. Screen Share lifecycle: consent denial, single-use token guard, real frame requirement for Active, and clean stop
    @Test
    fun test38_screenShareLifecycle_consentDenialSingleUseTokenAndRealFrameRequirement() {
        com.example.services.ScreenShareManager.resetToIdle()
        assertTrue(com.example.services.ScreenShareManager.state.value is com.example.services.ScreenShareManager.ScreenShareState.Idle)

        // 1. Denied consent transitions cleanly to PermissionDenied & Unified Error
        com.example.services.ScreenShareManager.handleConsentResult(app, android.app.Activity.RESULT_CANCELED, null)
        assertTrue(com.example.services.ScreenShareManager.state.value is com.example.services.ScreenShareManager.ScreenShareState.PermissionDenied)
        assertFalse(com.example.services.ScreenShareManager.isScreenSharingActive)

        // 2. Granted consent transitions to StartingService -> WaitingForFirstFrame, and requires a real frame before becoming Active
        val tokenIntent = android.content.Intent("test_media_projection_token")
        com.example.services.ScreenShareManager.handleConsentResult(app, android.app.Activity.RESULT_OK, tokenIntent)
        assertTrue(
            com.example.services.ScreenShareManager.state.value is com.example.services.ScreenShareManager.ScreenShareState.StartingService ||
            com.example.services.ScreenShareManager.state.value is com.example.services.ScreenShareManager.ScreenShareState.WaitingForFirstFrame ||
            com.example.services.ScreenShareManager.state.value is com.example.services.ScreenShareManager.ScreenShareState.Error
        )

        // Reusing the exact same single-use MediaProjection consent Intent token MUST be rejected
        com.example.services.ScreenShareManager.handleConsentResult(app, android.app.Activity.RESULT_OK, tokenIntent)
        assertTrue(com.example.services.ScreenShareManager.state.value is com.example.services.ScreenShareManager.ScreenShareState.PermissionDenied)

        // Clean stop resets state to Stopped
        com.example.services.ScreenShareManager.stopScreenShare(app)
        assertTrue(com.example.services.ScreenShareManager.state.value is com.example.services.ScreenShareManager.ScreenShareState.Stopped)
        assertFalse(com.example.services.ScreenShareManager.isScreenSharingActive)
    }
}


