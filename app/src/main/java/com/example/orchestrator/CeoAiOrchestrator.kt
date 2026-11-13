package com.example.orchestrator

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.services.KavyaAccessibilityService
import com.example.utils.AppResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Restricted executor interface used ONLY by CeoAiOrchestrator AFTER AiAuthorityGateway issues a permit.
 * Every execution implementation MUST present and consume the single-use permit.
 */
interface RestrictedActionExecutor {
    suspend fun executePermittedAction(
        context: Context,
        permit: ActionAuthorizationPermit,
        envelope: AiActionEnvelope,
        calibratedPoint: CalibratedPoint?
    ): ActionExecutionFeedback
}

data class ActionExecutionFeedback(
    val dispatched: Boolean,
    val detail: String,
    val errorCode: String? = null,
    val expectedForegroundPackage: String? = null,
    val launchedViaBrowser: Boolean = false
)

/**
 * Distinguishes Installed App Launch vs Website/Browser URL vs Web Search (Phase 15).
 */
object AppAndWebsiteRoutingClassifier {
    sealed class RouteDecision {
        data class InstalledApp(val appQuery: String, val canonicalPackageHint: String = "") : RouteDecision()
        data class BrowserWebsite(val url: String, val label: String) : RouteDecision()
        data class WebSearch(val searchQuery: String) : RouteDecision()
    }

    fun classifyTarget(rawTargetOrCommand: String): RouteDecision {
        val trimmed = rawTargetOrCommand.trim()
        val lower = trimmed.lowercase(java.util.Locale.ROOT)

        // 1. Explicit website / browser link request ("Open YouTube website", "Open youtube.com", "Open this link in browser")
        val isExplicitWebsite = lower.contains("website") ||
            lower.contains("in browser") ||
            lower.contains("browser me") ||
            lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            Regex("\\b[a-z0-9-]+\\.(?:com|org|io|net|in|dev|ai)\\b").containsMatchIn(lower)

        if (isExplicitWebsite) {
            val url = when {
                lower.startsWith("http://") || lower.startsWith("https://") -> trimmed.substringAfter("open ").trim()
                lower.contains("youtube") -> "https://www.youtube.com"
                lower.contains("whatsapp") -> "https://web.whatsapp.com"
                lower.contains("instagram") -> "https://www.instagram.com"
                lower.contains("github") -> "https://github.com"
                lower.contains("google") -> "https://www.google.com"
                else -> {
                    val domainMatch = Regex("\\b([a-z0-9-]+\\.(?:com|org|io|net|in|dev|ai)(?:/[^\\s]*)?)\\b").find(lower)
                    if (domainMatch != null) "https://${domainMatch.groupValues[1]}" else "https://www.google.com/search?q=${Uri.encode(trimmed)}"
                }
            }
            return RouteDecision.BrowserWebsite(url = url, label = trimmed)
        }

        // 2. Explicit web search request ("Search this on Google", "Search cats on Google", "Google par search karo")
        val isExplicitWebSearch = (lower.contains("search") || lower.contains("सर्च") || lower.contains("dhundo")) &&
            (lower.contains("on google") || lower.contains("google par") || lower.contains("google pe") || lower.contains("on web"))
        if (isExplicitWebSearch) {
            val cleanQ = trimmed
                .replace(Regex("(?i)^(?:search\\s+(?:for\\s+)?|google\\s+par\\s+|google\\s+pe\\s+)"), "")
                .replace(Regex("(?i)\\s+(?:on\\s+google|par\\s+search\\s+karo|search\\s+karo)$"), "")
                .trim()
                .ifBlank { trimmed }
            return RouteDecision.WebSearch(cleanQ)
        }

        // 3. Installed app launch ("Open YouTube", "Open Chrome", "Open WhatsApp")
        val cleanApp = trimmed
            .replace(Regex("(?i)^(?:open|launch|start|run)\\s+"), "")
            .replace(Regex("(?i)\\s+(?:kholo|khol\\s+do|open\\s+karo|chalao|खोलो)$"), "")
            .trim()
            .ifBlank { trimmed }
        val canonicalPkg = when (cleanApp.lowercase(java.util.Locale.ROOT)) {
            "youtube", "yt" -> "com.google.android.youtube"
            "chrome", "google chrome" -> "com.android.chrome"
            "whatsapp" -> "com.whatsapp"
            "instagram" -> "com.instagram.android"
            "gmail" -> "com.google.android.gm"
            "maps", "google maps" -> "com.google.android.apps.maps"
            "settings" -> "com.android.settings"
            else -> ""
        }
        return RouteDecision.InstalledApp(appQuery = cleanApp, canonicalPackageHint = canonicalPkg)
    }

    fun verifyForegroundPackageMatch(
        expectedPackage: String?,
        observedForegroundPackage: String?
    ): Boolean {
        if (expectedPackage.isNullOrBlank() || observedForegroundPackage.isNullOrBlank()) return false
        return expectedPackage.equals(observedForegroundPackage.trim(), ignoreCase = true)
    }
}

object DefaultRestrictedActionExecutor : RestrictedActionExecutor {
    override suspend fun executePermittedAction(
        context: Context,
        permit: ActionAuthorizationPermit,
        envelope: AiActionEnvelope,
        calibratedPoint: CalibratedPoint?
    ): ActionExecutionFeedback = withContext(Dispatchers.IO) {
        when (val consume = AiAuthorityGateway.beginAuthorizedDispatch(permit)) {
            is PermitConsumeResult.Rejected -> {
                return@withContext ActionExecutionFeedback(
                    dispatched = false,
                    detail = consume.reason,
                    errorCode = consume.code
                )
            }
            is PermitConsumeResult.Valid -> Unit
        }

        try {
            val a11y = KavyaAccessibilityService.instance
            when (envelope.action) {
                OrchestratorActionType.OPEN_APP -> {
                    val target = envelope.appName.ifBlank { envelope.target.description }
                    when (val route = AppAndWebsiteRoutingClassifier.classifyTarget(target)) {
                        is AppAndWebsiteRoutingClassifier.RouteDecision.BrowserWebsite -> {
                            val ok = try {
                                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(route.url)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(browserIntent)
                                true
                            } catch (_: Exception) {
                                false
                            }
                            ActionExecutionFeedback(
                                dispatched = ok,
                                detail = if (ok) "Opened website ${route.url} in browser" else "Failed opening website ${route.url}",
                                errorCode = if (ok) null else "BROWSER_LAUNCH_FAILED",
                                expectedForegroundPackage = "com.android.chrome",
                                launchedViaBrowser = true
                            )
                        }
                        is AppAndWebsiteRoutingClassifier.RouteDecision.WebSearch -> {
                            val ok = try {
                                val browserIntent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://www.google.com/search?q=${Uri.encode(route.searchQuery)}")
                                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                context.startActivity(browserIntent)
                                true
                            } catch (_: Exception) {
                                false
                            }
                            ActionExecutionFeedback(
                                dispatched = ok,
                                detail = if (ok) "Opened browser search for '${route.searchQuery}'" else "Failed opening web search",
                                errorCode = if (ok) null else "SEARCH_LAUNCH_FAILED",
                                launchedViaBrowser = true
                            )
                        }
                        is AppAndWebsiteRoutingClassifier.RouteDecision.InstalledApp -> {
                            val res = AppResolver(context).resolve(route.appQuery)
                            val app = res.matchedApp
                                ?: return@withContext ActionExecutionFeedback(
                                    dispatched = false,
                                    detail = "App '${route.appQuery}' is not installed on this device.",
                                    errorCode = "APP_NOT_FOUND"
                                )
                            val launched = try {
                                val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)?.apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                } ?: return@withContext ActionExecutionFeedback(
                                    dispatched = false,
                                    detail = "No launchable Activity found for ${app.appName} (${app.packageName}).",
                                    errorCode = "NO_LAUNCH_INTENT",
                                    expectedForegroundPackage = app.packageName
                                )
                                context.startActivity(launchIntent)
                                true
                            } catch (_: Exception) {
                                false
                            }
                            ActionExecutionFeedback(
                                dispatched = launched,
                                detail = if (launched) "Launched installed app ${app.appName} (${app.packageName})" else "Failed launching ${app.appName}",
                                errorCode = if (launched) null else "LAUNCH_FAILED",
                                expectedForegroundPackage = app.packageName,
                                launchedViaBrowser = false
                            )
                        }
                    }
                }
                OrchestratorActionType.SPLIT_SCREEN -> {
                    val primary = envelope.appName.ifBlank { envelope.target.description }
                    val secondary = envelope.text.trim()
                    val mwRes = MultiWindowController.executeSplitScreen(context, permit, primary, secondary)
                    ActionExecutionFeedback(
                        dispatched = mwRes.success,
                        detail = mwRes.detail,
                        errorCode = if (mwRes.success) null else mwRes.verificationStatus.name
                    )
                }
                OrchestratorActionType.SEARCH_WEB -> {
                    val q = envelope.text.ifBlank { envelope.target.description }.trim()
                    val route = AppAndWebsiteRoutingClassifier.classifyTarget(q)
                    val targetUri = when (route) {
                        is AppAndWebsiteRoutingClassifier.RouteDecision.BrowserWebsite -> Uri.parse(route.url)
                        is AppAndWebsiteRoutingClassifier.RouteDecision.WebSearch -> Uri.parse("https://www.google.com/search?q=${Uri.encode(route.searchQuery)}")
                        is AppAndWebsiteRoutingClassifier.RouteDecision.InstalledApp -> Uri.parse("https://www.google.com/search?q=${Uri.encode(q)}")
                    }
                    val ok = try {
                        val browserIntent = Intent(Intent.ACTION_VIEW, targetUri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(browserIntent)
                        true
                    } catch (_: Exception) {
                        false
                    }
                    ActionExecutionFeedback(
                        dispatched = ok,
                        detail = if (ok) "Opened browser URL $targetUri" else "Failed opening web search",
                        errorCode = if (ok) null else "SEARCH_LAUNCH_FAILED",
                        launchedViaBrowser = true
                    )
                }
                OrchestratorActionType.TAP -> {
                    if (a11y == null) return@withContext ActionExecutionFeedback(false, "Accessibility service not enabled.", "NO_ACCESSIBILITY")
                    val (ok, detail) = a11y.performAuthorizedMultiSignalTap(
                        permit = permit,
                        selectorHint = envelope.target.selector,
                        descriptionHint = envelope.target.description,
                        fallbackPoint = calibratedPoint
                    )
                    ActionExecutionFeedback(ok, detail, if (ok) null else if (calibratedPoint == null) "NO_COORDS" else "TAP_FAILED")
                }
                OrchestratorActionType.DOUBLE_TAP -> {
                    if (a11y == null) return@withContext ActionExecutionFeedback(false, "Accessibility service not enabled.", "NO_ACCESSIBILITY")
                    val pt = calibratedPoint ?: return@withContext ActionExecutionFeedback(false, "No calibrated double-tap coordinates.", "NO_COORDS")
                    val ok1 = a11y.performAuthorizedTap(permit, pt.xPx, pt.yPx)
                    delay(90L)
                    val ok2 = if (ok1) a11y.performAuthorizedTap(permit, pt.xPx, pt.yPx) else false
                    ActionExecutionFeedback(ok1 && ok2, if (ok1 && ok2) "Double-tapped at (${pt.xPx.toInt()}, ${pt.yPx.toInt()})" else "Double-tap failed")
                }
                OrchestratorActionType.LONG_PRESS -> {
                    if (a11y == null) return@withContext ActionExecutionFeedback(false, "Accessibility service not enabled.", "NO_ACCESSIBILITY")
                    val pt = calibratedPoint ?: return@withContext ActionExecutionFeedback(false, "No calibrated long-press coordinates.", "NO_COORDS")
                    val ok = a11y.performAuthorizedLongPress(permit, pt.xPx, pt.yPx)
                    ActionExecutionFeedback(ok, if (ok) "Long-pressed at (${pt.xPx.toInt()}, ${pt.yPx.toInt()})" else "Long-press failed")
                }
                OrchestratorActionType.TYPE -> {
                    if (a11y == null) return@withContext ActionExecutionFeedback(false, "Accessibility service not enabled.", "NO_ACCESSIBILITY")
                    val ok = a11y.performAuthorizedType(permit, envelope.text, envelope.target.selector)
                    ActionExecutionFeedback(ok, if (ok) "Typed '${envelope.text.take(30)}'" else "Failed typing into target field")
                }
                OrchestratorActionType.SWIPE, OrchestratorActionType.SCROLL -> {
                    if (a11y == null) return@withContext ActionExecutionFeedback(false, "Accessibility service not enabled.", "NO_ACCESSIBILITY")
                    val geom = ScreenObservationCoordinator.readCurrentDisplayGeometry(context)
                    val cx = (calibratedPoint?.xPx ?: (geom.widthPx * 0.5f))
                    val cy = (calibratedPoint?.yPx ?: (geom.heightPx * 0.5f))
                    val dir = envelope.direction.uppercase()
                    val (dx, dy) = when {
                        dir.contains("UP") -> 0f to -(geom.heightPx * 0.35f)
                        dir.contains("DOWN") -> 0f to (geom.heightPx * 0.35f)
                        dir.contains("LEFT") -> -(geom.widthPx * 0.45f) to 0f
                        dir.contains("RIGHT") -> (geom.widthPx * 0.45f) to 0f
                        else -> 0f to -(geom.heightPx * 0.35f)
                    }
                    val ok = a11y.performAuthorizedSwipe(permit, cx, cy, (cx + dx).coerceIn(10f, geom.widthPx - 10f), (cy + dy).coerceIn(10f, geom.heightPx - 10f))
                    ActionExecutionFeedback(ok, if (ok) "Scrolled/Swiped $dir" else "Swipe gesture failed")
                }
                OrchestratorActionType.BACK -> {
                    if (a11y == null) return@withContext ActionExecutionFeedback(false, "Accessibility service not enabled.", "NO_ACCESSIBILITY")
                    val ok = a11y.performAuthorizedGlobal(permit, AccessibilityService.GLOBAL_ACTION_BACK)
                    ActionExecutionFeedback(ok, if (ok) "Navigated BACK" else "BACK action failed")
                }
                OrchestratorActionType.HOME -> {
                    if (a11y == null) return@withContext ActionExecutionFeedback(false, "Accessibility service not enabled.", "NO_ACCESSIBILITY")
                    val ok = a11y.performAuthorizedGlobal(permit, AccessibilityService.GLOBAL_ACTION_HOME)
                    ActionExecutionFeedback(ok, if (ok) "Navigated HOME" else "HOME action failed")
                }
                OrchestratorActionType.RECENTS -> {
                    if (a11y == null) return@withContext ActionExecutionFeedback(false, "Accessibility service not enabled.", "NO_ACCESSIBILITY")
                    val ok = a11y.performAuthorizedGlobal(permit, AccessibilityService.GLOBAL_ACTION_RECENTS)
                    ActionExecutionFeedback(ok, if (ok) "Opened RECENTS" else "RECENTS action failed")
                }
                OrchestratorActionType.WAIT -> {
                    delay(1200L)
                    ActionExecutionFeedback(true, "Waited for UI update")
                }
                OrchestratorActionType.VERIFY,
                OrchestratorActionType.DONE,
                OrchestratorActionType.ABORT,
                OrchestratorActionType.ASK_USER,
                OrchestratorActionType.REQUEST_CONFIRMATION -> {
                    ActionExecutionFeedback(true, "No physical dispatch needed for ${envelope.action.name}")
                }
            }
        } finally {
            AiAuthorityGateway.endAuthorizedDispatch(permit)
        }
    }
}

data class CeoOrchestrationOutcome(
    val success: Boolean,
    val finalMessage: String,
    val taskId: String,
    val sessionId: String,
    val stepsExecuted: Int = 0,
    val verifiedSteps: Int = 0,
    val failureCode: String? = null,
    val missingPermission: String? = null
)

data class PendingConfirmationRequest(
    val title: String,
    val reason: String,
    val action: AiActionEnvelope,
    val confidenceBand: ConfidenceBand
)

/**
 * CEO-LEVEL AI ORCHESTRATOR.
 * Loop:
 * Explicit User Command -> Auth Preflight -> Fresh Screenshot -> AI CEO Plan & Action ->
 * Gateway Permit & Precision Calibration -> [Optional User Confirmation] -> Single Atomic Action ->
 * Settle Wait -> Fresh Screenshot -> AI Verification of expected_result -> Next Action or Replan.
 */
class CeoAiOrchestrator(
    private val context: Context,
    private val executor: RestrictedActionExecutor = DefaultRestrictedActionExecutor
) {
    companion object {
        private const val TAG = "CeoAiOrchestrator"
    }

    @Volatile
    private var pausedSessionId: String? = null

    @Volatile
    private var pauseGate: CompletableDeferred<Boolean>? = null

    @Volatile
    var testPermissionChecker: ((Context, AiActionEnvelope) -> Pair<Boolean, String>)? = null

    fun pauseActiveTask(reason: String = "Paused by user") {
        val snap = TaskOrchestrationStateMachine.snapshot.value
        if (!snap.isRunning || snap.sessionId.isBlank()) return
        pausedSessionId = snap.sessionId
        AiAuthorityGateway.pauseSession(snap.sessionId, reason)
        if (pauseGate == null || pauseGate?.isCompleted == true) {
            pauseGate = CompletableDeferred()
        }
        TaskOrchestrationStateMachine.transitionTo(
            OrchestrationLifecycleState.TASK_PAUSED,
            detail = reason,
            isPaused = true
        )
    }

    fun resumePausedTask() {
        val sessId = pausedSessionId ?: return
        ScreenObservationCoordinator.clearLatestObservation()
        AiAuthorityGateway.resumeSession(sessId)
        pauseGate?.complete(true)
    }

    fun cancelActiveTask(reason: String = "Cancelled by user") {
        val snap = TaskOrchestrationStateMachine.snapshot.value
        if (snap.sessionId.isNotBlank()) {
            AiAuthorityGateway.invalidateSession(snap.sessionId, reason)
        }
        pauseGate?.complete(false)
        pausedSessionId = null
        TaskOrchestrationStateMachine.transitionTo(
            OrchestrationLifecycleState.TASK_CANCELLED,
            detail = reason,
            lastError = reason,
            isPaused = false
        )
    }

    fun emergencyStop(reason: String = "Emergency stop triggered") {
        AiAuthorityGateway.emergencyStopAll(reason)
        pauseGate?.complete(false)
        pausedSessionId = null
        TaskOrchestrationStateMachine.transitionTo(
            OrchestrationLifecycleState.TASK_CANCELLED,
            detail = reason,
            lastError = reason,
            isPaused = false
        )
    }

    suspend fun runTask(
        userCommand: String,
        requiresScreenVision: Boolean = true,
        onStatusUpdate: ((String) -> Unit)? = null,
        onRequestConfirmation: suspend (PendingConfirmationRequest) -> Boolean = { false }
    ): CeoOrchestrationOutcome {
        val rawTrimmed = userCommand.trim()
        if (rawTrimmed.isBlank()) {
            val denied = AiAuthorityGateway.rejectAutonomousExecution("CeoAiOrchestrator.emptyCommand")
            TaskOrchestrationStateMachine.transitionTo(OrchestrationLifecycleState.TASK_FAILED, detail = denied.reason, lastError = denied.reason)
            return CeoOrchestrationOutcome(false, denied.reason, "", "", failureCode = denied.code)
        }

        val cleanCmd = ConversationTaskContextManager.resolveContextualRequest(rawTrimmed)
        val (providerId, modelId) = SelectedProviderClient.resolveSelectedProviderAndModel(context)
        val capabilities = SelectedProviderClient.resolveCapabilities(providerId, modelId)
        val session = AiAuthorityGateway.registerExplicitUserCommand(cleanCmd, providerId, modelId)

        ConversationTaskContextManager.onTaskStarted(
            taskId = session.taskId,
            rawRequest = rawTrimmed,
            resolvedRequest = cleanCmd
        )
        TaskOrchestrationStateMachine.startTask(
            taskId = session.taskId,
            sessionId = session.sessionId,
            userCommand = cleanCmd,
            providerId = providerId,
            modelId = modelId
        )

        // Step 1: API Authentication Preflight
        TaskOrchestrationStateMachine.transitionTo(
            OrchestrationLifecycleState.API_AUTHENTICATION,
            detail = "Authorizing $providerId ($modelId)"
        )
        val apiKey = SelectedProviderClient.resolveApiKey(context, providerId)
        val preflight = AiAuthorityGateway.checkProviderPreflight(
            context = context,
            providerId = providerId,
            modelId = modelId,
            requiresVision = requiresScreenVision,
            isNetworkConnected = SelectedProviderClient.isNetworkConnected(context),
            hasValidApiKey = apiKey.isNotBlank() || providerId == "CUSTOM",
            isQuotaExhausted = false,
            capabilities = capabilities
        )
        if (preflight is GatewayDecision.Denied) {
            AiAuthorityGateway.invalidateSession(session.sessionId, preflight.reason)
            ConversationTaskContextManager.onTaskFinished(false, preflight.reason)
            TaskOrchestrationStateMachine.transitionTo(
                OrchestrationLifecycleState.TASK_FAILED,
                detail = preflight.reason,
                lastError = preflight.reason
            )
            return CeoOrchestrationOutcome(false, preflight.reason, session.taskId, session.sessionId, failureCode = preflight.code)
        }

        // Step 2: Initial Screen Observation
        onStatusUpdate?.invoke("Checking current screen...")
        TaskOrchestrationStateMachine.transitionTo(
            OrchestrationLifecycleState.SCREEN_OBSERVATION,
            detail = "Capturing fresh screen observation"
        )
        var currentObservation = ScreenObservationCoordinator.captureFreshObservation(context)
        if (requiresScreenVision && currentObservation == null) {
            val err = "Unable to capture fresh screen observation for visual automation."
            AiAuthorityGateway.invalidateSession(session.sessionId, err)
            ConversationTaskContextManager.onTaskFinished(false, err)
            TaskOrchestrationStateMachine.transitionTo(OrchestrationLifecycleState.TASK_FAILED, detail = err, lastError = err)
            return CeoOrchestrationOutcome(false, err, session.taskId, session.sessionId, failureCode = "NO_SCREEN_OBSERVATION")
        }

        // Step 3: CEO Strategic Reasoning & Plan Generation via Selected Provider
        TaskOrchestrationStateMachine.transitionTo(
            OrchestrationLifecycleState.AI_REASONING,
            detail = "Requesting CEO plan from $providerId"
        )
        val planPrompt = buildCeoPlanPrompt(cleanCmd, session, currentObservation)
        val planRawResult = SelectedProviderClient.invokeSelectedProvider(
            context = context,
            prompt = planPrompt,
            systemInstruction = CEO_SYSTEM_PROMPT,
            imageBytes = currentObservation?.imageBytes,
            requiresVision = requiresScreenVision
        )

        if (planRawResult is ProviderInvocationResult.Failure) {
            AiAuthorityGateway.invalidateSession(session.sessionId, planRawResult.message)
            ConversationTaskContextManager.onTaskFinished(false, planRawResult.message)
            TaskOrchestrationStateMachine.transitionTo(
                OrchestrationLifecycleState.TASK_FAILED,
                detail = planRawResult.message,
                lastError = planRawResult.message
            )
            return CeoOrchestrationOutcome(
                success = false,
                finalMessage = planRawResult.message,
                taskId = session.taskId,
                sessionId = session.sessionId,
                failureCode = planRawResult.code
            )
        }

        val planText = (planRawResult as ProviderInvocationResult.Success).text
        TaskOrchestrationStateMachine.transitionTo(
            OrchestrationLifecycleState.PLAN_VALIDATION,
            detail = "Validating AI CEO protocol plan"
        )

        val parsedPlan = when (val res = AiProtocolParser.parseCeoPlan(planText, session.taskId, session.sessionId)) {
            is ProtocolParseResult.Valid -> res.value
            is ProtocolParseResult.Malformed -> {
                val err = "Rejected malformed AI plan: ${res.reason}"
                AiAuthorityGateway.invalidateSession(session.sessionId, err)
                ConversationTaskContextManager.onTaskFinished(false, err)
                TaskOrchestrationStateMachine.transitionTo(OrchestrationLifecycleState.TASK_FAILED, detail = err, lastError = err)
                return CeoOrchestrationOutcome(false, err, session.taskId, session.sessionId, failureCode = "MALFORMED_AI_RESPONSE")
            }
        }

        if (parsedPlan.firstAction == null) {
            // Pure conversational answer or direct abort without device actions
            val msg = parsedPlan.assistantMessage.ifBlank { parsedPlan.overallGoal }
            ConversationTaskContextManager.onTaskFinished(true, msg)
            TaskOrchestrationStateMachine.transitionTo(
                OrchestrationLifecycleState.TASK_COMPLETED,
                detail = "Completed conversational AI response"
            )
            return CeoOrchestrationOutcome(
                success = true,
                finalMessage = msg,
                taskId = session.taskId,
                sessionId = session.sessionId,
                stepsExecuted = 0,
                verifiedSteps = 0
            )
        }

        var nextEnvelope: AiActionEnvelope? = parsedPlan.firstAction
        var stepsExecuted = 0
        var verifiedSteps = 0
        var replans = 0
        val completedStepNumbers = mutableSetOf<Int>()
        val totalPlannedSteps = parsedPlan.steps.size.coerceAtLeast(1)

        while (nextEnvelope != null && stepsExecuted < AiProtocolConstants.MAX_STEPS_PER_TASK) {
            // Handle pause/network wait gate before touching device
            if (!awaitResumeAndVerifyEnvironment(session, requiresScreenVision)) {
                val snap = TaskOrchestrationStateMachine.snapshot.value
                val cancelMsg = snap.lastError ?: "Task stopped or cancelled."
                ConversationTaskContextManager.onTaskFinished(false, cancelMsg)
                return CeoOrchestrationOutcome(
                    success = false,
                    finalMessage = cancelMsg,
                    taskId = session.taskId,
                    sessionId = session.sessionId,
                    stepsExecuted = stepsExecuted,
                    verifiedSteps = verifiedSteps,
                    failureCode = "TASK_CANCELLED"
                )
            }

            // Refresh observation if session resumed, observation cleared, or observation expired
            if (currentObservation == null || currentObservation.isExpired() || ScreenObservationCoordinator.latestObservation == null) {
                onStatusUpdate?.invoke("Checking current screen...")
                TaskOrchestrationStateMachine.transitionTo(
                    OrchestrationLifecycleState.SCREEN_OBSERVATION,
                    detail = "Capturing fresh screen observation before action"
                )
                currentObservation = ScreenObservationCoordinator.captureFreshObservation(context)
                if (currentObservation == null) {
                    val err = "Unable to capture fresh screen observation after pause/resume."
                    AiAuthorityGateway.invalidateSession(session.sessionId, err)
                    ConversationTaskContextManager.onTaskFinished(false, err)
                    TaskOrchestrationStateMachine.transitionTo(OrchestrationLifecycleState.TASK_FAILED, detail = err, lastError = err)
                    return CeoOrchestrationOutcome(false, err, session.taskId, session.sessionId, stepsExecuted, verifiedSteps, failureCode = "NO_SCREEN_OBSERVATION")
                }
                if (nextEnvelope.action.requiresScreenObservation &&
                    nextEnvelope.screenshotReference != currentObservation.screenshotId
                ) {
                    // Re-ask AI with fresh screenshot after pause/expiry so AI sees live state
                    TaskOrchestrationStateMachine.transitionTo(
                        OrchestrationLifecycleState.REPLANNING,
                        detail = "Screen refreshed; asking AI to confirm next action on new screenshot"
                    )
                    val refreshedAction = requestReplanAction(session, cleanCmd, currentObservation, "Observation refreshed after resume/expiry", nextEnvelope.sequence, requiresScreenVision)
                    if (refreshedAction is ReplanResult.Action) {
                        nextEnvelope = refreshedAction.envelope
                    } else if (refreshedAction is ReplanResult.Done) {
                        ConversationTaskContextManager.onTaskFinished(true, refreshedAction.message)
                        TaskOrchestrationStateMachine.transitionTo(OrchestrationLifecycleState.TASK_COMPLETED, detail = refreshedAction.message)
                        return CeoOrchestrationOutcome(true, refreshedAction.message, session.taskId, session.sessionId, stepsExecuted, verifiedSteps)
                    } else {
                        val err = (refreshedAction as ReplanResult.Failed).message
                        ConversationTaskContextManager.onTaskFinished(false, err)
                        TaskOrchestrationStateMachine.transitionTo(OrchestrationLifecycleState.TASK_FAILED, detail = err, lastError = err)
                        return CeoOrchestrationOutcome(false, err, session.taskId, session.sessionId, stepsExecuted, verifiedSteps, failureCode = refreshedAction.code)
                    }
                }
            }

            val env = nextEnvelope ?: break

            // Terminal protocol actions from AI
            if (env.action == OrchestratorActionType.DONE) {
                val msg = env.spokenMessage.ifBlank { env.expectedResult.ifBlank { parsedPlan.assistantMessage.ifBlank { "Task completed and verified." } } }
                ConversationTaskContextManager.onTaskFinished(true, msg)
                TaskOrchestrationStateMachine.transitionTo(OrchestrationLifecycleState.TASK_COMPLETED, detail = msg)
                return CeoOrchestrationOutcome(true, msg, session.taskId, session.sessionId, stepsExecuted, verifiedSteps)
            }
            if (env.action == OrchestratorActionType.ABORT || env.action == OrchestratorActionType.ASK_USER) {
                val msg = env.spokenMessage.ifBlank { env.reason.ifBlank { env.expectedResult } }
                val terminalState = if (env.action == OrchestratorActionType.ABORT) OrchestrationLifecycleState.TASK_FAILED else OrchestrationLifecycleState.TASK_COMPLETED
                ConversationTaskContextManager.onTaskFinished(env.action == OrchestratorActionType.ASK_USER, msg)
                TaskOrchestrationStateMachine.transitionTo(terminalState, detail = msg, lastError = if (env.action == OrchestratorActionType.ABORT) msg else null)
                return CeoOrchestrationOutcome(env.action == OrchestratorActionType.ASK_USER, msg, session.taskId, session.sessionId, stepsExecuted, verifiedSteps)
            }

            // Section 4: Check step dependencies — never execute a dependent step if a prerequisite step failed
            val matchingPlanStep = parsedPlan.steps.firstOrNull { it.stepNumber == env.sequence }
            if (matchingPlanStep != null && matchingPlanStep.dependsOnStepNumbers.isNotEmpty() && replans == 0) {
                val unmetDeps = matchingPlanStep.dependsOnStepNumbers.filter { it !in completedStepNumbers }
                if (unmetDeps.isNotEmpty()) {
                    val depErr = "Prerequisite step(s) ${unmetDeps.joinToString()} not verified; halting dependent step ${env.sequence}."
                    AiAuthorityGateway.invalidateSession(session.sessionId, depErr)
                    ConversationTaskContextManager.onTaskFinished(false, depErr)
                    TaskOrchestrationStateMachine.transitionTo(OrchestrationLifecycleState.TASK_FAILED, detail = depErr, lastError = depErr)
                    return CeoOrchestrationOutcome(false, depErr, session.taskId, session.sessionId, stepsExecuted, verifiedSteps, failureCode = "DEPENDENCY_FAILED")
                }
            }

            TaskOrchestrationStateMachine.transitionTo(
                OrchestrationLifecycleState.ACTION_AUTHORIZATION,
                detail = "Authorizing step ${env.sequence}: ${env.action.name} -> ${env.target.description}",
                currentStep = env.sequence,
                totalSteps = maxOf(totalPlannedSteps, env.sequence)
            )
            val truthfulProgress = ConversationTaskContextManager.truthfulProgressMessageForAction(env)
            onStatusUpdate?.invoke(truthfulProgress)

            val (permGranted, missingPermName) = checkPermissionsForAction(env)
            val currentProviderNow = SelectedProviderClient.resolveSelectedProviderAndModel(context).first
            val keyValidNow = SelectedProviderClient.resolveApiKey(context, currentProviderNow).isNotBlank() || currentProviderNow == "CUSTOM"

            var userConfirmed = false
            var permitDecision = AiAuthorityGateway.requestActionPermit(
                context = context,
                session = session,
                envelope = env,
                currentObservation = currentObservation,
                activeProviderId = currentProviderNow,
                isOnline = SelectedProviderClient.isNetworkConnected(context),
                hasValidApiAuth = keyValidNow,
                userConfirmedSensitiveOrMedium = false,
                requiredPermissionsGranted = permGranted,
                missingPermissionName = missingPermName
            )

            if (permitDecision is PermitDecision.NeedsConfirmation) {
                TaskOrchestrationStateMachine.transitionTo(
                    OrchestrationLifecycleState.WAITING_FOR_CONFIRMATION,
                    detail = permitDecision.reason
                )
                val approved = onRequestConfirmation(
                    PendingConfirmationRequest(
                        title = if (permitDecision.band == ConfidenceBand.MEDIUM) "Confirm Action Precision" else "Confirm Sensitive Action",
                        reason = permitDecision.reason,
                        action = env,
                        confidenceBand = permitDecision.band
                    )
                )
                if (!approved) {
                    val cancelMsg = "Action cancelled by user before execution: ${env.target.description}"
                    AiAuthorityGateway.invalidateSession(session.sessionId, cancelMsg)
                    ConversationTaskContextManager.onTaskFinished(false, cancelMsg)
                    TaskOrchestrationStateMachine.transitionTo(OrchestrationLifecycleState.TASK_CANCELLED, detail = cancelMsg, lastError = cancelMsg)
                    return CeoOrchestrationOutcome(false, cancelMsg, session.taskId, session.sessionId, stepsExecuted, verifiedSteps, failureCode = "USER_DECLINED")
                }
                userConfirmed = true
                permitDecision = AiAuthorityGateway.requestActionPermit(
                    context = context,
                    session = session,
                    envelope = env,
                    currentObservation = currentObservation,
                    activeProviderId = SelectedProviderClient.resolveSelectedProviderAndModel(context).first,
                    isOnline = SelectedProviderClient.isNetworkConnected(context),
                    hasValidApiAuth = keyValidNow,
                    userConfirmedSensitiveOrMedium = userConfirmed,
                    requiredPermissionsGranted = permGranted,
                    missingPermissionName = missingPermName
                )
            }

            if (permitDecision is PermitDecision.NeedsPermission) {
                ConversationTaskContextManager.recordVerification(
                    StepVerificationRecord(
                        stepNumber = env.sequence,
                        actionType = env.action,
                        expectedOutcome = env.expectedResult,
                        observedOutcome = permitDecision.reason,
                        confidenceScore = env.confidence,
                        observationMode = currentObservation?.observationModeDescription ?: "NONE",
                        status = OrchestratorVerificationStatus.BLOCKED_BY_PERMISSION,
                        diagnosticExplanation = permitDecision.reason
                    )
                )
                ConversationTaskContextManager.onTaskFinished(false, permitDecision.reason)
                TaskOrchestrationStateMachine.transitionTo(
                    OrchestrationLifecycleState.WAITING_FOR_PERMISSION,
                    detail = permitDecision.reason,
                    lastError = permitDecision.reason
                )
                return CeoOrchestrationOutcome(
                    success = false,
                    finalMessage = permitDecision.reason,
                    taskId = session.taskId,
                    sessionId = session.sessionId,
                    stepsExecuted = stepsExecuted,
                    verifiedSteps = verifiedSteps,
                    failureCode = "MISSING_PERMISSION",
                    missingPermission = permitDecision.permission
                )
            }

            if (permitDecision is PermitDecision.Denied) {
                AiAuthorityGateway.invalidateSession(session.sessionId, permitDecision.reason)
                ConversationTaskContextManager.onTaskFinished(false, permitDecision.reason)
                TaskOrchestrationStateMachine.transitionTo(
                    OrchestrationLifecycleState.TASK_FAILED,
                    detail = permitDecision.reason,
                    lastError = permitDecision.reason
                )
                return CeoOrchestrationOutcome(
                    success = false,
                    finalMessage = permitDecision.reason,
                    taskId = session.taskId,
                    sessionId = session.sessionId,
                    stepsExecuted = stepsExecuted,
                    verifiedSteps = verifiedSteps,
                    failureCode = permitDecision.code
                )
            }

            val permit = (permitDecision as PermitDecision.Granted).permit
            val calibratedPoint = if (env.target.coordinates != null && currentObservation != null) {
                when (val cal = PrecisionController.calibrateTargetCoordinates(context, env, currentObservation)) {
                    is PrecisionCalibrationResult.Valid -> cal.point
                    is PrecisionCalibrationResult.Rejected -> {
                        ConversationTaskContextManager.onTaskFinished(false, cal.reason)
                        TaskOrchestrationStateMachine.transitionTo(OrchestrationLifecycleState.TASK_FAILED, detail = cal.reason, lastError = cal.reason)
                        return CeoOrchestrationOutcome(false, cal.reason, session.taskId, session.sessionId, stepsExecuted, verifiedSteps, failureCode = "COORDINATE_REJECTED")
                    }
                }
            } else null

            // Step 4: Single Atomic Execution
            TaskOrchestrationStateMachine.transitionTo(
                OrchestrationLifecycleState.ACTION_EXECUTION,
                detail = "Executing ${env.action.name}: ${env.target.description}",
                lastActionSummary = "${env.action.name} on ${env.target.description}"
            )
            ConversationTaskContextManager.onActionDispatched(env, currentObservation?.foregroundPackage.orEmpty())
            onStatusUpdate?.invoke(truthfulProgress)

            val execFeedback = executor.executePermittedAction(context, permit, env, calibratedPoint)
            stepsExecuted++

            if (!execFeedback.dispatched) {
                val err = "Action execution failed (${execFeedback.errorCode ?: "EXEC_FAIL"}): ${execFeedback.detail}"
                AiAuthorityGateway.invalidateSession(session.sessionId, err)
                ConversationTaskContextManager.onTaskFinished(false, err)
                TaskOrchestrationStateMachine.transitionTo(OrchestrationLifecycleState.TASK_FAILED, detail = err, lastError = err)
                return CeoOrchestrationOutcome(false, err, session.taskId, session.sessionId, stepsExecuted, verifiedSteps, failureCode = execFeedback.errorCode)
            }

            // Wait briefly for UI settle, check pause/cancel before capturing post-action screenshot
            delay(450L)
            if (!awaitResumeAndVerifyEnvironment(session, requiresScreenVision)) {
                val msg = TaskOrchestrationStateMachine.snapshot.value.lastError ?: "Task stopped after action."
                ConversationTaskContextManager.onTaskFinished(false, msg)
                return CeoOrchestrationOutcome(false, msg, session.taskId, session.sessionId, stepsExecuted, verifiedSteps, failureCode = "TASK_CANCELLED")
            }

            // Step 5: Mandatory Fresh Post-Action Observation & AI Verification
            onStatusUpdate?.invoke("Checking current screen...")
            TaskOrchestrationStateMachine.transitionTo(
                OrchestrationLifecycleState.SCREEN_OBSERVATION,
                detail = "Capturing fresh post-action screenshot for verification"
            )
            val afterObservation = ScreenObservationCoordinator.captureFreshObservation(context, System.currentTimeMillis() - 10L)
            if (afterObservation == null) {
                val err = "Action was dispatched, but post-action screen observation failed; cannot verify success."
                AiAuthorityGateway.invalidateSession(session.sessionId, err)
                ConversationTaskContextManager.onTaskFinished(false, err)
                TaskOrchestrationStateMachine.transitionTo(OrchestrationLifecycleState.TASK_FAILED, detail = err, lastError = err)
                return CeoOrchestrationOutcome(false, err, session.taskId, session.sessionId, stepsExecuted, verifiedSteps, failureCode = "UNVERIFIED_POST_ACTION")
            }
            currentObservation = afterObservation

            TaskOrchestrationStateMachine.transitionTo(
                OrchestrationLifecycleState.AI_VERIFICATION,
                detail = "Asking $providerId to verify '${env.expectedResult}'"
            )
            onStatusUpdate?.invoke("Verifying the result...")

            val verifyPrompt = buildVerificationPrompt(cleanCmd, session, env, execFeedback, afterObservation)
            val verifyResult = SelectedProviderClient.invokeSelectedProvider(
                context = context,
                prompt = verifyPrompt,
                systemInstruction = CEO_SYSTEM_PROMPT,
                imageBytes = afterObservation.imageBytes,
                requiresVision = requiresScreenVision
            )

            if (verifyResult is ProviderInvocationResult.Failure) {
                AiAuthorityGateway.invalidateSession(session.sessionId, verifyResult.message)
                ConversationTaskContextManager.onTaskFinished(false, verifyResult.message)
                TaskOrchestrationStateMachine.transitionTo(OrchestrationLifecycleState.TASK_FAILED, detail = verifyResult.message, lastError = verifyResult.message)
                return CeoOrchestrationOutcome(false, "Cannot verify result: ${verifyResult.message}", session.taskId, session.sessionId, stepsExecuted, verifiedSteps, failureCode = verifyResult.code)
            }

            val parsedDecision = when (
                val vr = AiProtocolParser.parseVerificationDecision(
                    (verifyResult as ProviderInvocationResult.Success).text,
                    session.taskId,
                    session.sessionId,
                    afterObservation.screenshotId
                )
            ) {
                is ProtocolParseResult.Valid -> vr.value
                is ProtocolParseResult.Malformed -> {
                    val err = "Rejected malformed AI verification JSON: ${vr.reason}"
                    AiAuthorityGateway.invalidateSession(session.sessionId, err)
                    ConversationTaskContextManager.onTaskFinished(false, err)
                    TaskOrchestrationStateMachine.transitionTo(OrchestrationLifecycleState.TASK_FAILED, detail = err, lastError = err)
                    return CeoOrchestrationOutcome(false, err, session.taskId, session.sessionId, stepsExecuted, verifiedSteps, failureCode = "MALFORMED_AI_VERIFICATION")
                }
            }

            ConversationTaskContextManager.recordVerification(
                StepVerificationRecord(
                    stepNumber = env.sequence,
                    actionType = env.action,
                    expectedOutcome = env.expectedResult,
                    observedOutcome = parsedDecision.observedScreenSummary,
                    confidenceScore = env.confidence,
                    observationMode = afterObservation.observationModeDescription,
                    status = parsedDecision.verificationStatus,
                    diagnosticExplanation = parsedDecision.mismatchReason.ifBlank { parsedDecision.observedScreenSummary }
                )
            )

            if (parsedDecision.verified) {
                verifiedSteps++
                completedStepNumbers.add(env.sequence)
                TaskOrchestrationStateMachine.transitionTo(
                    OrchestrationLifecycleState.AI_VERIFICATION,
                    detail = "Verified: ${parsedDecision.observedScreenSummary}",
                    lastVerificationSummary = parsedDecision.observedScreenSummary
                )
                if (parsedDecision.isTaskCompleted) {
                    val finalMsg = parsedDecision.assistantMessage.ifBlank { parsedDecision.observedScreenSummary.ifBlank { "Task completed and verified." } }
                    ConversationTaskContextManager.onTaskFinished(true, finalMsg)
                    TaskOrchestrationStateMachine.transitionTo(OrchestrationLifecycleState.TASK_COMPLETED, detail = finalMsg)
                    return CeoOrchestrationOutcome(true, finalMsg, session.taskId, session.sessionId, stepsExecuted, verifiedSteps)
                }
                nextEnvelope = parsedDecision.nextAction
                if (nextEnvelope == null) {
                    // Ask AI for next action if task not explicitly finished
                    val replanRes = requestReplanAction(
                        session = session,
                        userCommand = cleanCmd,
                        observation = afterObservation,
                        mismatchReason = "Previous action verified; determine next step or DONE.",
                        nextSequence = env.sequence + 1,
                        requiresVision = requiresScreenVision
                    )
                    when (replanRes) {
                        is ReplanResult.Action -> nextEnvelope = replanRes.envelope
                        is ReplanResult.Done -> {
                            TaskOrchestrationStateMachine.transitionTo(OrchestrationLifecycleState.TASK_COMPLETED, detail = replanRes.message)
                            return CeoOrchestrationOutcome(true, replanRes.message, session.taskId, session.sessionId, stepsExecuted, verifiedSteps)
                        }
                        is ReplanResult.Failed -> {
                            TaskOrchestrationStateMachine.transitionTo(OrchestrationLifecycleState.TASK_FAILED, detail = replanRes.message, lastError = replanRes.message)
                            return CeoOrchestrationOutcome(false, replanRes.message, session.taskId, session.sessionId, stepsExecuted, verifiedSteps, failureCode = replanRes.code)
                        }
                    }
                }
            } else {
                // Action NOT verified (e.g. unchanged screen or unexpected popup)
                if (!parsedDecision.needsReplan || replans >= AiProtocolConstants.MAX_REPLANS_PER_TASK) {
                    val failMsg = parsedDecision.mismatchReason.ifBlank {
                        parsedDecision.assistantMessage.ifBlank { "Verification failed: expected '${env.expectedResult}', observed '${parsedDecision.observedScreenSummary}'." }
                    }
                    AiAuthorityGateway.invalidateSession(session.sessionId, failMsg)
                    TaskOrchestrationStateMachine.transitionTo(OrchestrationLifecycleState.TASK_FAILED, detail = failMsg, lastError = failMsg)
                    return CeoOrchestrationOutcome(false, failMsg, session.taskId, session.sessionId, stepsExecuted, verifiedSteps, failureCode = "VERIFICATION_FAILED")
                }

                replans++
                TaskOrchestrationStateMachine.transitionTo(
                    OrchestrationLifecycleState.REPLANNING,
                    detail = "Verification mismatch (${parsedDecision.mismatchReason}); replanning #$replans",
                    retryCount = replans
                )
                if (parsedDecision.nextAction != null) {
                    nextEnvelope = parsedDecision.nextAction
                } else {
                    val replanRes = requestReplanAction(
                        session = session,
                        userCommand = cleanCmd,
                        observation = afterObservation,
                        mismatchReason = parsedDecision.mismatchReason.ifBlank { parsedDecision.observedScreenSummary },
                        nextSequence = env.sequence + 1,
                        requiresVision = requiresScreenVision
                    )
                    when (replanRes) {
                        is ReplanResult.Action -> nextEnvelope = replanRes.envelope
                        is ReplanResult.Done -> {
                            TaskOrchestrationStateMachine.transitionTo(OrchestrationLifecycleState.TASK_COMPLETED, detail = replanRes.message)
                            return CeoOrchestrationOutcome(true, replanRes.message, session.taskId, session.sessionId, stepsExecuted, verifiedSteps)
                        }
                        is ReplanResult.Failed -> {
                            TaskOrchestrationStateMachine.transitionTo(OrchestrationLifecycleState.TASK_FAILED, detail = replanRes.message, lastError = replanRes.message)
                            return CeoOrchestrationOutcome(false, replanRes.message, session.taskId, session.sessionId, stepsExecuted, verifiedSteps, failureCode = replanRes.code)
                        }
                    }
                }
            }
        }

        val limitMsg = "Stopped after reaching step safety limit ($stepsExecuted actions, $verifiedSteps verified)."
        AiAuthorityGateway.invalidateSession(session.sessionId, limitMsg)
        TaskOrchestrationStateMachine.transitionTo(OrchestrationLifecycleState.TASK_FAILED, detail = limitMsg, lastError = limitMsg)
        return CeoOrchestrationOutcome(false, limitMsg, session.taskId, session.sessionId, stepsExecuted, verifiedSteps, failureCode = "STEP_LIMIT_REACHED")
    }

    private sealed class ReplanResult {
        data class Action(val envelope: AiActionEnvelope) : ReplanResult()
        data class Done(val message: String) : ReplanResult()
        data class Failed(val code: String, val message: String) : ReplanResult()
    }

    private suspend fun requestReplanAction(
        session: TaskSessionToken,
        userCommand: String,
        observation: ScreenObservationRecord,
        mismatchReason: String,
        nextSequence: Int,
        requiresVision: Boolean = observation.imageBytes != null
    ): ReplanResult {
        val prompt = buildString {
            appendLine("REPLAN REQUEST")
            appendLine("task_id: ${session.taskId}")
            appendLine("session_id: ${session.sessionId}")
            appendLine("required_sequence: $nextSequence")
            appendLine("original_user_command: $userCommand")
            appendLine("reason_for_replan: $mismatchReason")
            appendLine("current_screenshot_reference: ${observation.screenshotId}")
            appendLine("screen_width: ${observation.displayWidthPx}")
            appendLine("screen_height: ${observation.displayHeightPx}")
            appendLine("orientation: ${observation.orientation}")
            appendLine("foreground_package: ${observation.foregroundPackage}")
            appendLine("browser_url: ${observation.browserUrl}")
            appendLine("ui_tree_summary:")
            appendLine(observation.uiTreeSummary.take(2000))
            appendLine()
            append("Respond with ONE valid JSON Action Envelope matching protocol_version \"1.0\" with next action (or action=\"DONE\" / \"ABORT\").")
        }

        val result = SelectedProviderClient.invokeSelectedProvider(
            context = context,
            prompt = prompt,
            systemInstruction = CEO_SYSTEM_PROMPT,
            imageBytes = observation.imageBytes,
            requiresVision = requiresVision
        )
        if (result is ProviderInvocationResult.Failure) {
            return ReplanResult.Failed(result.code, result.message)
        }
        return when (val parsed = AiProtocolParser.parseActionEnvelope((result as ProviderInvocationResult.Success).text, session.taskId, session.sessionId, nextSequence)) {
            is ProtocolParseResult.Malformed -> ReplanResult.Failed("MALFORMED_AI_REPLAN", parsed.reason)
            is ProtocolParseResult.Valid -> {
                if (parsed.value.action == OrchestratorActionType.DONE) {
                    ReplanResult.Done(parsed.value.spokenMessage.ifBlank { parsed.value.expectedResult.ifBlank { "Task completed." } })
                } else {
                    ReplanResult.Action(parsed.value)
                }
            }
        }
    }

    private suspend fun awaitResumeAndVerifyEnvironment(
        session: TaskSessionToken,
        requiresVision: Boolean
    ): Boolean {
        if (!session.isActive) return false

        // Wait if explicitly paused
        if (session.isPaused) {
            TaskOrchestrationStateMachine.transitionTo(
                OrchestrationLifecycleState.TASK_PAUSED,
                detail = "Task paused; awaiting user resume",
                isPaused = true
            )
            val gate = pauseGate ?: CompletableDeferred<Boolean>().also { pauseGate = it }
            val resumed = try {
                gate.await()
            } catch (_: CancellationException) {
                false
            }
            if (!resumed || !session.isActive) return false
        }

        // Wait if network dropped mid-task
        if (!SelectedProviderClient.isNetworkConnected(context)) {
            TaskOrchestrationStateMachine.transitionTo(
                OrchestrationLifecycleState.TASK_PAUSED,
                detail = "Network disconnected mid-task; waiting for reconnect to reassess screen.",
                isPaused = true,
                suspendedDueToNetwork = true
            )
            var attempts = 0
            while (!SelectedProviderClient.isNetworkConnected(context) && session.isActive && attempts < 10) {
                delay(300L)
                attempts++
            }
            if (!session.isActive || !SelectedProviderClient.isNetworkConnected(context)) {
                val msg = "Task suspended: internet connection unavailable for AI verification."
                AiAuthorityGateway.invalidateSession(session.sessionId, msg)
                TaskOrchestrationStateMachine.transitionTo(OrchestrationLifecycleState.TASK_FAILED, detail = msg, lastError = msg)
                return false
            }
            // Force fresh screenshot on reconnect by clearing latest observation
            ScreenObservationCoordinator.clearLatestObservation()
            TaskOrchestrationStateMachine.transitionTo(
                OrchestrationLifecycleState.SCREEN_OBSERVATION,
                detail = "Network restored; recapturing fresh screenshot for AI reassessment",
                isPaused = false,
                suspendedDueToNetwork = false
            )
        }

        // Check provider didn't switch mid-task
        val (activeProv, _) = SelectedProviderClient.resolveSelectedProviderAndModel(context)
        if (activeProv != session.providerId) {
            val msg = "Provider switched from ${session.providerId} to $activeProv; task halted."
            AiAuthorityGateway.invalidateSession(session.sessionId, msg)
            TaskOrchestrationStateMachine.transitionTo(OrchestrationLifecycleState.TASK_CANCELLED, detail = msg, lastError = msg)
            return false
        }
        return session.isActive
    }

    private fun checkPermissionsForAction(envelope: AiActionEnvelope): Pair<Boolean, String> {
        testPermissionChecker?.let { return it(context, envelope) }
        if (envelope.action.usesAccessibilityService && KavyaAccessibilityService.instance == null) {
            return Pair(false, "ACCESSIBILITY_SERVICE")
        }
        return Pair(true, "")
    }

    private fun buildCeoPlanPrompt(
        userCommand: String,
        session: TaskSessionToken,
        observation: ScreenObservationRecord?
    ): String {
        val obsSection = if (observation != null) {
            buildString {
                appendLine("screenshot_reference: ${observation.screenshotId}")
                appendLine("screen_width: ${observation.displayWidthPx}")
                appendLine("screen_height: ${observation.displayHeightPx}")
                appendLine("orientation: ${observation.orientation}")
                appendLine("foreground_package: ${observation.foregroundPackage}")
                appendLine("browser_url: ${observation.browserUrl}")
                appendLine("ui_tree_summary:")
                append(observation.uiTreeSummary.take(2200))
            }
        } else {
            "screenshot_reference: NONE"
        }

        val instructionsJson = """
            Instructions:
            If this is purely a general question / chat requiring NO device interaction, respond ONLY with JSON:
            {"protocol_version":"1.0","mode":"CONVERSATION","task_id":"${session.taskId}","session_id":"${session.sessionId}","assistant_message":"..."}

            Otherwise respond ONLY with JSON containing a CEO step plan and `first_action`:
            {
              "protocol_version":"1.0",
              "task_id":"${session.taskId}",
              "session_id":"${session.sessionId}",
              "overall_goal":"...",
              "requires_vision":true,
              "steps":[{"step_number":1,"goal":"...","expected_outcome":"...","requires_vision":true,"may_be_consequential":false}],
              "first_action":{
                "protocol_version":"1.0",
                "task_id":"${session.taskId}",
                "session_id":"${session.sessionId}",
                "action_id":"act_1",
                "sequence":1,
                "action":"OPEN_APP|SPLIT_SCREEN|TAP|DOUBLE_TAP|LONG_PRESS|TYPE|SWIPE|SCROLL|BACK|HOME|RECENTS|WAIT|SEARCH_WEB|ASK_USER|DONE|ABORT",
                "app_name":"...",
                "target":{"description":"...","selector":"...","coordinates":{"x":0.5,"y":0.5,"space":"NORMALIZED_0_1"}},
                "text":"...",
                "direction":"UP|DOWN|LEFT|RIGHT",
                "screenshot_reference":"${observation?.screenshotId ?: ""}",
                "screen_width":${observation?.displayWidthPx ?: 0},
                "screen_height":${observation?.displayHeightPx ?: 0},
                "orientation":${observation?.orientation ?: 0},
                "confidence":0.95,
                "expected_result":"...",
                "verification_required":true,
                "requires_confirmation":false,
                "reason":"..."
              }
            }
        """.trimIndent()

        return buildString {
            appendLine("USER COMMAND: $userCommand")
            appendLine("task_id: ${session.taskId}")
            appendLine("session_id: ${session.sessionId}")
            appendLine(obsSection)
            appendLine()
            append(instructionsJson)
        }
    }

    private fun buildVerificationPrompt(
        userCommand: String,
        session: TaskSessionToken,
        executedAction: AiActionEnvelope,
        feedback: ActionExecutionFeedback,
        afterObservation: ScreenObservationRecord
    ): String {
        val responseTemplate = """
            Verify whether expected_result ACTUALLY occurred on the new screen.
            Never assume success without evidence on the new screenshot/UI tree.
            Respond ONLY with JSON:
            {
              "protocol_version":"1.0",
              "task_id":"${session.taskId}",
              "session_id":"${session.sessionId}",
              "screenshot_reference":"${afterObservation.screenshotId}",
              "verified":true,
              "observed_screen_summary":"...",
              "mismatch_reason":"",
              "is_task_completed":true,
              "needs_replan":false,
              "assistant_message":"...",
              "next_action": null
            }
        """.trimIndent()

        return buildString {
            appendLine("POST-ACTION VERIFICATION")
            appendLine("original_user_command: $userCommand")
            appendLine("task_id: ${session.taskId}")
            appendLine("session_id: ${session.sessionId}")
            appendLine("executed_action_id: ${executedAction.actionId}")
            appendLine("executed_action: ${executedAction.action.name} (${executedAction.target.description})")
            appendLine("expected_result: ${executedAction.expectedResult}")
            appendLine("executor_feedback: ${feedback.detail}")
            appendLine("new_screenshot_reference: ${afterObservation.screenshotId}")
            appendLine("screen_width: ${afterObservation.displayWidthPx}")
            appendLine("screen_height: ${afterObservation.displayHeightPx}")
            appendLine("orientation: ${afterObservation.orientation}")
            appendLine("foreground_package: ${afterObservation.foregroundPackage}")
            appendLine("browser_url: ${afterObservation.browserUrl}")
            appendLine("ui_tree_summary:")
            appendLine(afterObservation.uiTreeSummary.take(2200))
            appendLine("next_sequence_if_continuing: ${executedAction.sequence + 1}")
            appendLine()
            append(responseTemplate)
        }
    }

    private val CEO_SYSTEM_PROMPT = """
        You are the CEO-level Android AI Orchestrator. You are the ONLY brain and decision-maker.
        You inspect screenshots carefully, plan step-by-step, issue ONE atomic verified action at a time in strict protocol v1.0 JSON, and verify every post-action screenshot before claiming success.
        Never guess hidden buttons or invent UI coordinates without high confidence. Always include exact task_id, session_id, action_id, monotonically increasing sequence, screenshot_reference, confidence in [0,1], and expected_result.
    """.trimIndent()
}
