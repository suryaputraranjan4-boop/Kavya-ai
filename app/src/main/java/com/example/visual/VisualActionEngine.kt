package com.example.visual

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.agent.AutomationDebugLog
import com.example.agent.DiagnosticEngine
import com.example.services.KavyaAccessibilityService
import com.example.state.KavyaStateManager
import com.example.state.TaskState
import com.example.utils.AppPreferences
import com.example.utils.AppResolver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Kavya Visual Action Engine.
 * Master orchestrator for visual screen control and human-like UI automation on Android.
 *
 * Implements the interaction lifecycle:
 * OBSERVE -> UNDERSTAND -> LOCATE -> VALIDATE -> ACT -> OBSERVE -> VERIFY -> NEXT STEP
 */
class VisualActionEngine(private val context: Context) {

    companion object {
        private const val TAG = "KavyaVisualEngine"
    }

    val screenshotManager = ScreenshotManager(context)
    val hierarchyAnalyzer = UIHierarchyAnalyzer()
    val visionAnalyzer = GeminiVisionAnalyzer(context)
    val screenObserver = ScreenObserver(context, screenshotManager, hierarchyAnalyzer)
    val actionPlanner = VisualActionPlanner(context)
    val touchController = TouchController(hierarchyAnalyzer)
    val gestureController = GestureController()
    val textInputController = TextInputController(hierarchyAnalyzer)
    val scrollController = ScrollController(gestureController)
    val actionVerifier = VisualActionVerifier(hierarchyAnalyzer)
    val safetyController = VisualSafetyController(context)
    val precisionEngine = PrecisionComputerUseEngine(context, hierarchyAnalyzer = hierarchyAnalyzer)

    private val _engineStatus = MutableStateFlow(VisualEngineStatus())
    val engineStatus = _engineStatus.asStateFlow()

    /**
     * Emergency Stop triggered from UI or voice command.
     */
    fun stopAutomation(reason: String = "User requested stop") {
        safetyController.triggerEmergencyStop()
        _engineStatus.value = VisualEngineStatus(
            isActive = false,
            stepTitle = "Stopped",
            detailMessage = reason
        )
        KavyaStateManager.updateTaskState(TaskState.STOPPED, reason)
        DiagnosticEngine.recordAutomationDebugLog(
            AutomationDebugLog(
                id = UUID.randomUUID().toString(),
                command = reason,
                result = "STOPPED",
                verification = "EMERGENCY_STOP"
            )
        )
    }

    /**
     * Main entry point for executing visual automation goals.
     */
    suspend fun executeGoal(
        userGoal: String,
        onProgress: ((String) -> Unit)? = null
    ): VisualActionExecutionResult {
        safetyController.resetEmergencyStop()
        val memory = actionPlanner.initializePlan(userGoal)

        DiagnosticEngine.recordAutomationDebugLog(
            AutomationDebugLog(
                id = UUID.randomUUID().toString(),
                command = userGoal,
                result = "STARTED",
                verification = "PLANNING"
            )
        )
        updateStatus(true, "Understanding Goal", userGoal, 1.0f)
        onProgress?.invoke("लक्ष्य समझ रही हूँ: $userGoal")

        // 1. Check if Accessibility Service is available
        val service = KavyaAccessibilityService.instance
        if (service == null) {
            val errorMsg = "Accessibility Service is not enabled. Please enable Kavya in Settings."
            updateStatus(false, "Failed", errorMsg, 0f)
            KavyaStateManager.updateTaskState(TaskState.FAILED, errorMsg)
            return VisualActionExecutionResult(
                success = false,
                actionType = VisualActionType.STOP,
                target = userGoal,
                executionMethod = "None",
                message = errorMsg,
                confidence = 0f,
                error = errorMsg
            )
        }

        // 2. Multi-step: Step A - Launch App if needed
        val targetApp = memory.currentApp
        if (targetApp != "Current Screen" && actionPlanner.requiresAppLaunch(userGoal)) {
            if (safetyController.isInterrupted()) return buildStoppedResult(userGoal)

            updateStatus(true, "Opening App", targetApp, 1.0f)
            KavyaStateManager.updateTaskState(TaskState.OPENING_APP, targetApp)
            onProgress?.invoke("$targetApp खोल रही हूँ...")

            val appResolver = AppResolver(context)
            val resolved = appResolver.resolve(targetApp)
            val resolvedPkg = resolved.matchedApp?.packageName
            if (resolvedPkg != null) {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(resolvedPkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                }
                // Adaptive wait for app to be ready (no long fixed sleeps)
                val ready = screenObserver.waitUntilAppReady(resolvedPkg, timeoutMs = 8000L)
                if (!ready) {
                    delay(1200) // Fallback brief launch settle
                }
                memory.completedSteps.add("Opened $targetApp")
            }
        }

        // 3. Execution loop for on-screen target interaction
        var loopCount = 0
        var lastExecutionResult: VisualActionExecutionResult? = null

        while (loopCount < VisualSafetyController.MAX_ACTION_RETRIES) {
            if (safetyController.isInterrupted()) return buildStoppedResult(userGoal)

            loopCount++
            memory.retryCount = loopCount

            // STEP 1: OBSERVE
            updateStatus(true, "Observing Screen", "Analyzing active window", 0.95f)
            KavyaStateManager.updateTaskState(TaskState.LOOKING_AT_SCREEN, "Observing Screen")
            onProgress?.invoke("Screen observe कर रही हूँ...")

            val (screenWidth, screenHeight) = screenshotManager.getScreenDimensions()
            val screenSnapshot = screenObserver.observeScreen(captureVisual = true)
            val hierarchySummary = screenSnapshot.visibleTextSummary

            // STEP 2: LOCATE & UNDERSTAND TARGET
            updateStatus(true, "Finding Target", userGoal, 0.90f)
            KavyaStateManager.updateTaskState(TaskState.FINDING_TARGET, userGoal)

            var plannedAction: VisualAction? = null

            // First: Check if an accessibility node matches semantically with clean target candidate
            val targetCandidate = actionPlanner.extractActionTarget(userGoal)
            val (matchedNodeInfo, _) = hierarchyAnalyzer.findMatchingNode(targetCandidate, null)
            val isGiant = matchedNodeInfo != null && (matchedNodeInfo.bounds.width() > screenWidth * 0.85 && matchedNodeInfo.bounds.height() > screenHeight * 0.75)
            if (matchedNodeInfo != null && !isGiant && matchedNodeInfo.bounds.width() > 0 && matchedNodeInfo.bounds.height() > 0) {
                val normCenter = matchedNodeInfo.getNormalizedCenter(screenWidth, screenHeight)
                plannedAction = VisualAction(
                    type = if (matchedNodeInfo.isEditable) VisualActionType.TYPE else VisualActionType.TAP,
                    target = matchedNodeInfo.text ?: matchedNodeInfo.contentDescription ?: targetCandidate,
                    confidence = 0.95f,
                    normalizedX = normCenter.x,
                    normalizedY = normCenter.y,
                    expectedResult = "Target interacted"
                )
            }

            // Second: If not found via hierarchy alone, invoke Gemini Vision Analyzer
            if (plannedAction == null && screenSnapshot.screenshot != null) {
                val (base64Img, _) = screenshotManager.encodeToOptimizedBase64(screenSnapshot.screenshot, retainBitmap = false)
                if (base64Img.isNotBlank()) {
                    val (understanding, visionAction) = visionAnalyzer.analyzeScreenForAction(
                        base64Image = base64Img,
                        userGoal = userGoal,
                        currentApp = screenSnapshot.foregroundPackage,
                        hierarchySummary = hierarchySummary
                    )
                    if (visionAction != null && visionAction.confidence >= 0.70f) {
                        plannedAction = visionAction
                    }
                }
            }

            // Fallback: Check for search action or common queries
            val searchQuery = actionPlanner.extractSearchQuery(userGoal)
            if (plannedAction == null && searchQuery != null) {
                plannedAction = VisualAction(
                    type = VisualActionType.TYPE,
                    target = "Search bar",
                    textToType = searchQuery,
                    confidence = 0.90f,
                    expectedResult = "Search submitted"
                )
            }

            // If still no action can be planned
            if (plannedAction == null) {
                val msg = "मैं इस स्क्रीन पर '${userGoal}' को reliably नहीं ढूँढ पा रही हूँ।"
                onProgress?.invoke(msg)
                DiagnosticEngine.recordAutomationDebugLog(
                    AutomationDebugLog(
                        id = UUID.randomUUID().toString(),
                        command = userGoal,
                        result = "TARGET_NOT_FOUND",
                        error = "Target not found on screen"
                    )
                )
                return VisualActionExecutionResult(
                    success = false,
                    actionType = VisualActionType.STOP,
                    target = userGoal,
                    executionMethod = "Perception",
                    message = msg,
                    confidence = 0.5f,
                    error = "Target not found on screen"
                )
            }

            // STEP 3: VALIDATE SAFETY
            val mode = VisualAutomationMode.AUTOMATIC_SAFE
            val (isSafe, safetyMsg) = safetyController.evaluateActionSafety(plannedAction, mode)
            if (!isSafe) {
                onProgress?.invoke("सुरक्षा रोक: $safetyMsg")
                return VisualActionExecutionResult(
                    success = false,
                    actionType = plannedAction.type,
                    target = plannedAction.target,
                    executionMethod = "Safety",
                    message = safetyMsg,
                    confidence = plannedAction.confidence
                )
            }

            // STEP 4: ACT
            updateStatus(true, "Performing Action", "${plannedAction.type}: ${plannedAction.target}", plannedAction.confidence)
            KavyaStateManager.updateTaskState(TaskState.PERFORMING_ACTION, "${plannedAction.type} ${plannedAction.target}")
            val announcement = plannedAction.spokenAnnouncement.ifBlank {
                "${plannedAction.target} पर ${plannedAction.type.name.lowercase()} कर रही हूँ..."
            }
            onProgress?.invoke(announcement)

            val actionStartTime = System.currentTimeMillis()
            val (actionSuccess, actionDetail) = executeSingleAction(plannedAction, screenWidth, screenHeight)
            val actionLatency = System.currentTimeMillis() - actionStartTime

            // STEP 5: VERIFY
            updateStatus(true, "Verifying Action", plannedAction.target, plannedAction.confidence)
            KavyaStateManager.updateTaskState(TaskState.VERIFYING, "Verifying ${plannedAction.target}")

            val (verified, verifyReason) = actionVerifier.verifyAction(plannedAction, hierarchySummary)

            if (actionSuccess && verified) {
                memory.completedSteps.add("${plannedAction.type} on ${plannedAction.target}")
                val successMessage = "Action completed and verified: $actionDetail"
                updateStatus(false, "Completed", successMessage, plannedAction.confidence)
                KavyaStateManager.updateTaskState(TaskState.COMPLETED, successMessage)
                onProgress?.invoke("Action verify हो गया! काम पूरा हुआ।")

                return VisualActionExecutionResult(
                    success = true,
                    actionType = plannedAction.type,
                    target = plannedAction.target,
                    executionMethod = actionDetail,
                    message = successMessage,
                    confidence = plannedAction.confidence,
                    latencyMs = actionLatency,
                    verificationPassed = true
                )
            } else {
                Log.w(TAG, "Action verification failed (attempt $loopCount): $verifyReason")
                delay(400) // Settle before next attempt
            }
        }

        val finalError = "Maximum retries ($loopCount) exceeded without conclusive verification."
        updateStatus(false, "Inconclusive", finalError, 0.5f)
        KavyaStateManager.updateTaskState(TaskState.FAILED, finalError)
        return lastExecutionResult ?: VisualActionExecutionResult(
            success = false,
            actionType = VisualActionType.STOP,
            target = userGoal,
            executionMethod = "Exhausted",
            message = finalError,
            confidence = 0f,
            error = finalError
        )
    }

    private suspend fun executeSingleAction(
        action: VisualAction,
        screenWidth: Int,
        screenHeight: Int
    ): Pair<Boolean, String> {
        return when (action.type) {
            VisualActionType.TAP, VisualActionType.SELECT -> {
                val norm = action.getNormalizedCoordinates()
                if (norm != null) {
                    val res = precisionEngine.performPreciseTap(action.target, norm.x, norm.y, normalized = true)
                    Pair(res.success, res.detail)
                } else {
                    val selectRes = precisionEngine.performSelectElement(action.target)
                    if (selectRes.success) {
                        Pair(true, selectRes.detail)
                    } else {
                        touchController.performTap(action.target, null, screenWidth, screenHeight)
                    }
                }
            }
            VisualActionType.LONG_PRESS -> {
                val norm = action.getNormalizedCoordinates()
                if (norm != null) {
                    val res = precisionEngine.performLongPress(action.target, norm.x, norm.y, normalized = true)
                    Pair(res.success, res.detail)
                } else {
                    touchController.performLongPress(action.target, null, screenWidth, screenHeight)
                }
            }
            VisualActionType.SWIPE -> {
                val res = precisionEngine.performSwipe(
                    startX = action.swipeStartX,
                    startY = action.swipeStartY,
                    endX = action.swipeEndX,
                    endY = action.swipeEndY,
                    durationMs = action.swipeDurationMs,
                    normalized = true
                )
                Pair(res.success, res.detail)
            }
            VisualActionType.SCROLL -> {
                val res = precisionEngine.performScroll(action.scrollDirection)
                Pair(res.success, res.detail)
            }
            VisualActionType.TYPE -> {
                val res = precisionEngine.performTextInput(action.target, action.textToType)
                Pair(res.success, res.detail)
            }
            VisualActionType.BACK -> {
                val res = precisionEngine.performBack()
                Pair(res.success, res.detail)
            }
            VisualActionType.HOME -> {
                val res = precisionEngine.performHome()
                Pair(res.success, res.detail)
            }
            VisualActionType.WAIT -> {
                delay(action.waitDurationMs)
                Pair(true, "Waited ${action.waitDurationMs}ms")
            }
            VisualActionType.OPEN -> {
                val res = precisionEngine.performAppLaunch(action.target)
                Pair(res.success, res.detail)
            }
            VisualActionType.CLOSE, VisualActionType.STOP -> {
                val res = precisionEngine.performDismissDialog()
                stopAutomation("Action requested stop")
                Pair(true, "Stopped dialog or automation (${res.detail})")
            }
        }
    }

    private fun updateStatus(isActive: Boolean, title: String, detail: String, confidence: Float) {
        _engineStatus.value = VisualEngineStatus(
            isActive = isActive,
            stepTitle = title,
            detailMessage = detail,
            confidence = confidence
        )
    }

    private fun buildStoppedResult(userGoal: String): VisualActionExecutionResult {
        return VisualActionExecutionResult(
            success = false,
            actionType = VisualActionType.STOP,
            target = userGoal,
            executionMethod = "Emergency Stop",
            message = "Action was stopped by user request.",
            confidence = 1.0f
        )
    }
}
