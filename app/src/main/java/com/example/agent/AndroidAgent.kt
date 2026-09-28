package com.example.agent

import android.accessibilityservice.AccessibilityService
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.AppDatabase
import com.example.data.AutomationFailureDao
import com.example.data.AutomationFailureEntity
import com.example.memory.okf.OkfMemoryRepository
import com.example.memory.okf.OkfMemoryTools
import com.example.scraper.maps.GoogleMapsQueryParser
import com.example.scraper.maps.GoogleMapsScraperClient
import com.example.scraper.maps.ScrapeResult
import com.example.services.KavyaAccessibilityService
import com.example.state.KavyaStateManager
import com.example.state.TaskState
import com.example.utils.AppLaunchDiagnostic
import com.example.utils.AppResolver
import com.example.utils.CommandRouter
import com.example.utils.InstalledApp
import com.example.utils.MatchConfidence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * Universal Android and Web interaction agent for Kavya.
 * Executes atomic and multi-step tasks across ANY app, browser, website, and device controls.
 * Follows the strict pipeline: Intent -> Target -> Screen Inspection -> Universal Action -> Verification.
 */
class AndroidAgent(
    private val context: Context,
    val appResolver: AppResolver,
    val contextEngine: ContextEngine,
    val screenInspector: ScreenInspector,
    val verificationEngine: VerificationEngine
) {

    companion object {
        private const val TAG = "KavyaAndroidAgent"
    }

    private val failureDao: AutomationFailureDao = AppDatabase.getDatabase(context).automationFailureDao()
    val freshRecognizer: FreshScreenRecognizer = screenInspector.freshRecognizer
    val workflowEngine: WorkflowEngine = WorkflowEngine(screenInspector, verificationEngine, TaskPlanner(appResolver))
    val visualActionEngine: com.example.visual.VisualActionEngine = com.example.visual.VisualActionEngine(context)

    /**
     * Executes an end-to-end screen automation goal using the Kavya Visual Action Engine.
     */
    suspend fun executeVisualGoal(
        goal: String,
        onProgress: ((String) -> Unit)? = null
    ): com.example.visual.VisualActionExecutionResult = withContext(Dispatchers.IO) {
        visualActionEngine.executeGoal(goal, onProgress)
    }

    @Volatile
    private var isCancelled: Boolean = false

    /**
     * Immediately terminates pending visual action engine automation and task plan execution.
     */
    fun stopExecution(reason: String = "User requested stop") {
        isCancelled = true
        visualActionEngine.stopAutomation(reason)
    }

    /**
     * Executes an end-to-end multi-step workflow using the WorkflowEngine:
     * Plan -> Observe -> Execute -> Verify -> Recover -> Complete
     */
    suspend fun executeWorkflow(
        goal: String,
        onProgress: ((String) -> Unit)? = null
    ): WorkflowResult = withContext(Dispatchers.IO) {
        workflowEngine.runWorkflow(goal, context, onProgress)
    }

    /**
     * Executes a structured multi-step task plan step-by-step with progressive voice feedback.
     */
    suspend fun executeTaskPlan(
        plan: TaskPlan,
        onSpeakProgress: (suspend (String) -> Unit)? = null
    ): TaskExecutionOutcome = withContext(Dispatchers.IO) {
        isCancelled = false
        DiagnosticEngine.updateLiveState(AgentLiveState.PLANNING, plan.originalPrompt, plan.targetAppName)
        KavyaStateManager.updateTaskState(TaskState.PLANNING, "Plan created with ${plan.steps.size} steps")
        val completedDiagnostics = mutableListOf<AppLaunchDiagnostic>()
        val stepSummaries = mutableListOf<String>()
        val debugLogs = mutableListOf<AutomationDebugLog>()
        var completedSteps = 0

        for (step in plan.steps) {
            if (isCancelled) {
                Log.i(TAG, "Task plan execution cancelled: user requested stop")
                KavyaStateManager.updateTaskState(TaskState.CANCELLED, "Execution cancelled by user")
                return@withContext TaskExecutionOutcome(
                    success = false,
                    finalSpokenMessage = "Action stopped.",
                    completedStepsCount = completedSteps,
                    totalStepsCount = plan.steps.size,
                    failureReason = "User requested stop",
                    diagnostics = completedDiagnostics,
                    debugLogs = debugLogs
                )
            }
            DiagnosticEngine.updateLiveState(
                AgentLiveState.EXECUTING,
                "Step ${step.id}/${plan.steps.size}: ${step.actionType} ${step.param.take(15)}",
                plan.targetAppName
            )
            KavyaStateManager.updateTaskState(TaskState.EXECUTING, "Step ${step.id}/${plan.steps.size}: ${step.actionType}")

            // 1. FRESH SCREEN RECOGNITION PASS with adaptive UI stability wait
            val freshScreenBefore = freshRecognizer.acquireFreshScreen(
                expectedPackage = if (step.actionType != UniversalActionType.OPEN_APP) plan.targetAppName else null,
                waitForStability = true
            )

            KavyaStateManager.updateTaskState(TaskState.EXECUTING, "Executing step ${step.id}")
            var stepResult = executeAtomicStep(step)

            // 2. BOUNDED RETRY with fresh screen invalidation if first attempt failed
            if (!stepResult.success && !stepResult.isAmbiguous && step.actionType != UniversalActionType.OPEN_APP && step.actionType != UniversalActionType.VERIFY) {
                Log.w(TAG, "Step ${step.id} (${step.actionType}) initial attempt unverified: ${stepResult.output}. Retrying with fresh scan...")
                for (retry in 1..2) {
                    delay(300)
                    freshRecognizer.acquireFreshScreen(expectedPackage = plan.targetAppName, waitForStability = true)
                    val retryResult = executeAtomicStep(step)
                    if (retryResult.success) {
                        Log.i(TAG, "Step ${step.id} (${step.actionType}) recovered on retry #$retry")
                        stepResult = retryResult
                        break
                    }
                }
            }

            stepSummaries.add("Step ${step.id} (${step.actionType}): ${if (stepResult.success) "SUCCESS" else "FAIL - " + stepResult.output}")

            KavyaStateManager.updateTaskState(TaskState.VERIFYING, "Verifying step ${step.id}")
            DiagnosticEngine.recordVerification(
                if (stepResult.success) "PASS: ${step.actionType}" else "FAIL: ${step.actionType} - ${stepResult.output}"
            )

            if (stepResult.diagnostic != null) {
                completedDiagnostics.add(stepResult.diagnostic)
                DiagnosticEngine.recordAppLaunch(stepResult.diagnostic)
            }

            // If milestone succeeded and multi-step plan, notify progress
            if (stepResult.success && step.spokenAnnouncement.isNotBlank() && onSpeakProgress != null && plan.steps.size > 1 && step.id < plan.steps.size) {
                onSpeakProgress(step.spokenAnnouncement)
            }

            // Create structured debug log
            val debugLog = AutomationDebugLog(
                id = UUID.randomUUID().toString(),
                command = plan.originalPrompt,
                target = plan.targetAppName,
                resolvedApp = stepResult.diagnostic?.resolvedApp ?: plan.targetAppName,
                foregroundPackage = stepResult.verifiedPackage ?: screenInspector.getCurrentForegroundPackage(),
                currentScreen = freshScreenBefore.summaryString.take(200),
                detectedElements = freshScreenBefore.nodes.map { it.displayLabel },
                action = "${step.actionType} [${step.param}]",
                result = stepResult.output,
                verification = if (stepResult.success) "VERIFIED_PASS" else "VERIFIED_FAIL",
                error = if (!stepResult.success) stepResult.output else null
            )
            debugLogs.add(debugLog)
            DiagnosticEngine.recordAutomationDebugLog(debugLog)

            if (stepResult.isAmbiguous) {
                DiagnosticEngine.updateLiveState(AgentLiveState.FAILED, "Ambiguous app selection", plan.targetAppName)
                KavyaStateManager.updateTaskState(TaskState.NEEDS_USER, "Ambiguous app selection")
                return@withContext TaskExecutionOutcome(
                    success = false,
                    finalSpokenMessage = stepResult.output,
                    completedStepsCount = completedSteps,
                    totalStepsCount = plan.steps.size,
                    failureReason = "Ambiguous app selection",
                    diagnostics = completedDiagnostics,
                    candidateApps = stepResult.candidateApps,
                    isAmbiguous = true,
                    debugLogs = debugLogs
                )
            }

            if (!stepResult.success) {
                // Record failure for behavioral learning
                failureDao.insertFailure(
                    AutomationFailureEntity(
                        targetApp = plan.targetAppName,
                        targetAction = step.actionType.name,
                        failureType = "EXECUTION_FAILURE",
                        screenSummary = stepResult.screenSummary ?: "Screen node not matched"
                    )
                )

                DiagnosticEngine.updateLiveState(AgentLiveState.FAILED, "Failed at step ${step.id}", plan.targetAppName)
                KavyaStateManager.updateTaskState(TaskState.FAILED, stepResult.output)
                val failureMessage = "${plan.targetAppName} खुल गया, लेकिन आगे का step complete नहीं हो पाया: ${stepResult.output}"
                DiagnosticEngine.recordAgentExecution(
                    AgentExecutionDiagnostic(
                        id = UUID.randomUUID().toString(),
                        prompt = plan.originalPrompt,
                        targetApp = plan.targetAppName,
                        totalSteps = plan.steps.size,
                        completedSteps = completedSteps,
                        success = false,
                        stepsSummary = stepSummaries,
                        foregroundApp = screenInspector.getCurrentForegroundPackage(),
                        verificationStatus = "FAIL at Step ${step.id}"
                    )
                )

                return@withContext TaskExecutionOutcome(
                    success = false,
                    finalSpokenMessage = failureMessage,
                    completedStepsCount = completedSteps,
                    totalStepsCount = plan.steps.size,
                    failureReason = stepResult.output,
                    diagnostics = completedDiagnostics,
                    debugLogs = debugLogs
                )
            }

            completedSteps++
            delay(80) // Fast settle delay between consecutive UI steps
        }

        DiagnosticEngine.updateLiveState(AgentLiveState.COMPLETED, "Completed ${plan.steps.size} steps", plan.targetAppName)
        KavyaStateManager.updateTaskState(TaskState.COMPLETED, "Completed all ${plan.steps.size} steps")
        DiagnosticEngine.recordAgentExecution(
            AgentExecutionDiagnostic(
                id = UUID.randomUUID().toString(),
                prompt = plan.originalPrompt,
                targetApp = plan.targetAppName,
                totalSteps = plan.steps.size,
                completedSteps = completedSteps,
                success = true,
                stepsSummary = stepSummaries,
                foregroundApp = screenInspector.getCurrentForegroundPackage(),
                verificationStatus = "ALL_STEPS_PASSED"
            )
        )

        val verifiedMessage = if (plan.steps.size == 1 && plan.steps.first().actionType == UniversalActionType.OPEN_APP) {
            val isHindi = plan.originalPrompt.any { it in '\u0900'..'\u097F' } ||
                    plan.originalPrompt.lowercase(Locale.ROOT).let { it.contains("kholo") || it.contains("chalao") || it.contains("jao") }
            if (isHindi) "${plan.targetAppName} खुल गया।" else "Opened ${plan.targetAppName}."
        } else {
            val isHindi = plan.originalPrompt.any { it in '\u0900'..'\u097F' }
            if (isHindi) "Task पूरा हो गया।" else "Done."
        }

        return@withContext TaskExecutionOutcome(
            success = true,
            finalSpokenMessage = verifiedMessage,
            completedStepsCount = completedSteps,
            totalStepsCount = plan.steps.size,
            diagnostics = completedDiagnostics,
            debugLogs = debugLogs
        )
    }

    /**
     * Executes an individual atomic action step using semantic UI perception.
     */
    private val fileAgent = FileAgent(context)

    suspend fun executeAtomicStep(step: TaskStep): StepExecutionResult {
        return when (step.actionType) {
            UniversalActionType.OPEN_APP -> handleOpenApp(step.targetAppOrUrl)
            UniversalActionType.OPEN_URL -> handleOpenUrl(step.targetAppOrUrl)
            UniversalActionType.OPEN_FILE,
            UniversalActionType.READ_FILE,
            UniversalActionType.DELETE_FILE,
            UniversalActionType.SAVE_FILE,
            UniversalActionType.SHARE_FILE,
            UniversalActionType.UPLOAD_FILE,
            UniversalActionType.DOWNLOAD_FILE -> fileAgent.executeFileAction(step.actionType, step.param, step.targetAppOrUrl)
            UniversalActionType.CREATE_FILE -> fileAgent.executeFileAction(step.actionType, step.param, step.targetAppOrUrl, step.messageText)
            UniversalActionType.SEARCH -> handleSearch(step)
            UniversalActionType.TAP -> handleTapNode(step)
            UniversalActionType.TYPE -> handleTypeNode(step)
            UniversalActionType.CLEAR, UniversalActionType.CLEAR_TEXT -> handleClearText()
            UniversalActionType.SUBMIT -> handleSubmitSearch()
            UniversalActionType.SELECT -> handleSelectOrdinalResult(step)
            UniversalActionType.PLAY -> handlePlayMedia(step)
            UniversalActionType.SCROLL -> handleScroll(step.param)
            UniversalActionType.SWIPE -> handleScroll(step.param)
            UniversalActionType.CHESS_MOVE -> handleChessMove(step.param)
            UniversalActionType.CALL,
            UniversalActionType.MAKE_PHONE_CALL -> {
                if (step.targetAppOrUrl.equals("WhatsApp", ignoreCase = true)) {
                    handleWhatsAppCall(step)
                } else {
                    handlePhoneCall(step)
                }
            }
            UniversalActionType.MAKE_WHATSAPP_CALL -> handleWhatsAppCall(step)
            UniversalActionType.SEND_MESSAGE -> {
                if (step.targetAppOrUrl.equals("WhatsApp", ignoreCase = true)) {
                    handleWhatsAppMessage(step)
                } else {
                    handleSms(step)
                }
            }
            UniversalActionType.SEND_SMS -> handleSms(step)
            UniversalActionType.SEND_WHATSAPP_MESSAGE -> handleWhatsAppMessage(step)
            UniversalActionType.SEND_EMAIL -> handleEmail(step)
            UniversalActionType.SELECT_RESULT -> handleSelectOrdinalResult(step)
            UniversalActionType.CREATE_FOLDER -> fileAgent.executeFileAction(step.actionType, step.param, step.targetAppOrUrl)
            UniversalActionType.VERIFY -> StepExecutionResult(step.id, true, step.actionType, "Verified")
            UniversalActionType.BACK -> handleGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK, step.id)
            UniversalActionType.HOME -> handleGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME, step.id)
            UniversalActionType.SYSTEM_CONTROL -> handleSystemControl(step)
            UniversalActionType.NAVIGATE -> handleNavigate(step)
            UniversalActionType.CLOSE_APP -> handleCloseApp(step)
            UniversalActionType.READ_SCREEN -> handleReadScreen(step)
            UniversalActionType.SCREENSHOT -> handleScreenshot(step)
            UniversalActionType.SWITCH_APP -> handleSwitchApp(step)
            UniversalActionType.ANALYZE_IMAGE -> handleAnalyzeImage(step)
            UniversalActionType.RESEARCH_WEB -> handleResearchWeb(step)
            UniversalActionType.GENERATE_IMAGE -> handleGenerateImage(step)
            UniversalActionType.SEMANTIC_SEARCH -> handleSemanticSearch(step)
            UniversalActionType.GOOGLE_MAPS_SEARCH -> handleGoogleMapsSearch(step)
            UniversalActionType.MEMORY_TOOL -> handleMemoryTool(step)
            else -> StepExecutionResult(step.id, true, step.actionType, "Action executed")
        }
    }

    private suspend fun handleGoogleMapsSearch(step: TaskStep): StepExecutionResult = withContext(Dispatchers.IO) {
        val query = GoogleMapsQueryParser.parse(step.param)
        val client = GoogleMapsScraperClient(context)
        when (val res = client.searchBusinesses(query)) {
            is ScrapeResult.Success -> {
                val preview = res.businesses.take(5).joinToString("\n") { "• ${it.name} (${it.category}) - ⭐ ${it.rating} | ${it.phone}" }
                StepExecutionResult(
                    stepId = step.id,
                    success = true,
                    actionType = UniversalActionType.GOOGLE_MAPS_SEARCH,
                    output = "Found ${res.businesses.size} businesses for '${query.toSearchTerm()}':\n$preview"
                )
            }
            is ScrapeResult.EmptyResult -> {
                StepExecutionResult(
                    stepId = step.id,
                    success = true,
                    actionType = UniversalActionType.GOOGLE_MAPS_SEARCH,
                    output = "No businesses found for '${query.toSearchTerm()}'"
                )
            }
            is ScrapeResult.ServiceUnavailable -> {
                StepExecutionResult(
                    stepId = step.id,
                    success = false,
                    actionType = UniversalActionType.GOOGLE_MAPS_SEARCH,
                    output = res.error
                )
            }
            is ScrapeResult.Error -> {
                StepExecutionResult(
                    stepId = step.id,
                    success = false,
                    actionType = UniversalActionType.GOOGLE_MAPS_SEARCH,
                    output = res.message
                )
            }
        }
    }

    private suspend fun handleMemoryTool(step: TaskStep): StepExecutionResult = withContext(Dispatchers.IO) {
        val repo = OkfMemoryRepository.getInstance(context)
        val tools = OkfMemoryTools(repo)
        val output = tools.memorySearch(step.param)
        StepExecutionResult(
            stepId = step.id,
            success = true,
            actionType = UniversalActionType.MEMORY_TOOL,
            output = output
        )
    }

    private suspend fun handleCloseApp(step: TaskStep): StepExecutionResult {
        val res = handleGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME, step.id)
        return StepExecutionResult(
            stepId = step.id,
            success = res.success,
            actionType = UniversalActionType.CLOSE_APP,
            output = if (res.success) "Closed foreground application" else "Failed to close application"
        )
    }

    private suspend fun handleReadScreen(step: TaskStep): StepExecutionResult {
        val summaries = screenInspector.getVisibleElementSummaries()
        val summaryStr = screenInspector.getScreenContextString()
        val isAvailable = screenInspector.isAccessibilityAvailable()
        return StepExecutionResult(
            stepId = step.id,
            success = isAvailable,
            actionType = UniversalActionType.READ_SCREEN,
            output = if (isAvailable) "Found ${summaries.size} elements on screen. $summaryStr" else "Accessibility service unavailable to inspect screen"
        )
    }

    private suspend fun handleScreenshot(step: TaskStep): StepExecutionResult {
        val service = KavyaAccessibilityService.instance
        if (service == null) {
            return StepExecutionResult(
                stepId = step.id,
                success = false,
                actionType = UniversalActionType.SCREENSHOT,
                output = "Accessibility service unavailable for screenshot"
            )
        }
        val bitmap = service.captureScreenBitmap()
        return if (bitmap != null) {
            StepExecutionResult(
                stepId = step.id,
                success = true,
                actionType = UniversalActionType.SCREENSHOT,
                output = "Screenshot captured successfully (${bitmap.width}x${bitmap.height})"
            )
        } else {
            StepExecutionResult(
                stepId = step.id,
                success = false,
                actionType = UniversalActionType.SCREENSHOT,
                output = "Screenshot capture failed"
            )
        }
    }

    private suspend fun handleSwitchApp(step: TaskStep): StepExecutionResult {
        return if (step.targetAppOrUrl.isNotBlank()) {
            handleOpenApp(step.targetAppOrUrl)
        } else {
            handleGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS, step.id)
        }
    }

    private suspend fun handleAnalyzeImage(step: TaskStep): StepExecutionResult {
        val hfProvider = com.example.ai.providers.HuggingFaceProvider()
        val res = hfProvider.executeSpecializedTask(
            task = com.example.ai.providers.HfSpecializedTask.IMAGE_ANALYSIS,
            textInput = step.param,
            context = context
        )
        return StepExecutionResult(
            stepId = step.id,
            success = res.success,
            actionType = UniversalActionType.ANALYZE_IMAGE,
            output = if (res.success) res.outputText else "Image analysis failed: ${res.error}"
        )
    }

    private suspend fun handleResearchWeb(step: TaskStep): StepExecutionResult {
        val openRouterProvider = com.example.ai.providers.OpenRouterProvider()
        val res = openRouterProvider.deepResearch(
            query = step.param.ifBlank { step.targetAppOrUrl },
            context = context
        )
        return StepExecutionResult(
            stepId = step.id,
            success = res.success,
            actionType = UniversalActionType.RESEARCH_WEB,
            output = if (res.success) res.report else "Research failed: ${res.error}"
        )
    }

    private suspend fun handleGenerateImage(step: TaskStep): StepExecutionResult {
        val hfProvider = com.example.ai.providers.HuggingFaceProvider()
        val res = hfProvider.executeSpecializedTask(
            task = com.example.ai.providers.HfSpecializedTask.IMAGE_GENERATION,
            textInput = step.param,
            context = context
        )
        return StepExecutionResult(
            stepId = step.id,
            success = res.success,
            actionType = UniversalActionType.GENERATE_IMAGE,
            output = if (res.success) res.outputText else "Image generation failed: ${res.error}"
        )
    }

    private suspend fun handleSemanticSearch(step: TaskStep): StepExecutionResult {
        val hfProvider = com.example.ai.providers.HuggingFaceProvider()
        val res = hfProvider.executeSpecializedTask(
            task = com.example.ai.providers.HfSpecializedTask.EMBEDDINGS,
            textInput = step.param,
            context = context
        )
        return StepExecutionResult(
            stepId = step.id,
            success = res.success,
            actionType = UniversalActionType.SEMANTIC_SEARCH,
            output = if (res.success) res.outputText else "Semantic search embedding failed: ${res.error}"
        )
    }

    private suspend fun handleOpenApp(appName: String): StepExecutionResult {
        val resolution = appResolver.resolve(appName)
        if (resolution.confidence == MatchConfidence.NONE) {
            return StepExecutionResult(
                stepId = 1,
                success = false,
                actionType = UniversalActionType.OPEN_APP,
                output = "Mujhe aapke device par '${resolution.requestedName}' app nahi mila."
            )
        }

        if (resolution.confidence == MatchConfidence.AMBIGUOUS) {
            return StepExecutionResult(
                stepId = 1,
                success = false,
                actionType = UniversalActionType.OPEN_APP,
                output = "Multiple apps match '${resolution.requestedName}'. Please choose:",
                isAmbiguous = true,
                candidateApps = resolution.candidateApps
            )
        }

        val app = resolution.matchedApp ?: return StepExecutionResult(
            stepId = 1,
            success = false,
            actionType = UniversalActionType.OPEN_APP,
            output = "Cannot resolve app"
        )

        val beforePkg = screenInspector.getCurrentForegroundPackage()
        val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }

        if (launchIntent == null) {
            return StepExecutionResult(
                stepId = 1,
                success = false,
                actionType = UniversalActionType.OPEN_APP,
                output = "Launch Intent unavailable for ${app.appName}"
            )
        }

        try {
            context.startActivity(launchIntent)
        } catch (e: Exception) {
            return StepExecutionResult(
                stepId = 1,
                success = false,
                actionType = UniversalActionType.OPEN_APP,
                output = "Launch failed: ${e.message}"
            )
        }

        val verification = verificationEngine.verifyAppForeground(app.packageName)
        val diag = AppLaunchDiagnostic(
            requestedApp = appName,
            resolvedApp = app.appName,
            packageName = app.packageName,
            launchIntent = launchIntent.toString(),
            confidence = resolution.confidence.name,
            foregroundBefore = beforePkg,
            foregroundAfter = verification.currentPackage,
            verification = verification.reason
        )

        contextEngine.updateContext(appName = app.appName, packageName = app.packageName)

        return StepExecutionResult(
            stepId = 1,
            success = verification.passed,
            actionType = UniversalActionType.OPEN_APP,
            output = if (verification.passed) "Opened ${app.appName}" else "App opened but foreground not verified",
            verifiedPackage = verification.currentPackage,
            diagnostic = diag
        )
    }

    private suspend fun handleOpenUrl(url: String): StepExecutionResult {
        val targetUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            contextEngine.updateContext(url = targetUrl, appName = "Browser")
            delay(200)
            val currentPkg = screenInspector.getCurrentForegroundPackage()
            StepExecutionResult(
                stepId = 1,
                success = true,
                actionType = UniversalActionType.OPEN_URL,
                output = "Opened $targetUrl",
                verifiedPackage = currentPkg
            )
        } catch (e: Exception) {
            StepExecutionResult(
                stepId = 1,
                success = false,
                actionType = UniversalActionType.OPEN_URL,
                output = "Failed to open $targetUrl: ${e.message}"
            )
        }
    }

    private suspend fun handleTapNode(step: TaskStep): StepExecutionResult {
        val service = KavyaAccessibilityService.instance
        if (service == null) {
            return StepExecutionResult(step.id, false, UniversalActionType.TAP, "Accessibility Service is not enabled.")
        }

        val screenBefore = screenInspector.getScreenContextString()
        val candidates = when (step.param.uppercase(Locale.ROOT)) {
            "BR-RANKED", "BR RANKED" -> listOf("BR-RANKED", "BR Ranked", "Battle Royale", "BR", "Ranked")
            "CS-RANKED", "CS RANKED" -> listOf("CS-RANKED", "CS Ranked", "Clash Squad", "CS", "Clash")
            "START" -> listOf("Start", "START", "Play", "PLAY", "शुरू", "Ready")
            else -> listOf(step.param)
        }

        for (candidate in candidates) {
            val targetNode = screenInspector.findTargetNodeForStep(step.copy(param = candidate))
                ?: screenInspector.findTargetNode(candidate)

            if (targetNode != null) {
                val clicked = service.clickNode(targetNode)
                if (clicked) {
                    val verif = verificationEngine.verifyUiInteraction(UniversalActionType.TAP, candidate, screenBefore)
                    return StepExecutionResult(step.id, true, UniversalActionType.TAP, "Tapped '$candidate'", screenSummary = verif.screenSummary)
                }
            }

            // Fallback: try by text
            val clickedByText = service.clickNodeByText(candidate)
            if (clickedByText) {
                val verif = verificationEngine.verifyUiInteraction(UniversalActionType.TAP, candidate, screenBefore)
                return StepExecutionResult(step.id, true, UniversalActionType.TAP, "Tapped '$candidate'", screenSummary = verif.screenSummary)
            }
        }

        // Gemini Visual Fallback (max 1 call)
        val fallbackResult = executeGeminiVisualFallback(step.actionType, step.param)
        if (fallbackResult != null && fallbackResult.success) {
            val verif = verificationEngine.verifyUiInteraction(UniversalActionType.TAP, step.param, screenBefore)
            return StepExecutionResult(step.id, true, UniversalActionType.TAP, fallbackResult.output, screenSummary = verif.screenSummary)
        }

        val fgPkg = screenInspector.getCurrentForegroundPackage().lowercase()
        if (fgPkg.contains("freefire") || fgPkg.contains("dts")) {
            return StepExecutionResult(
                step.id,
                false,
                UniversalActionType.TAP,
                "Free Fire launched, but in-game accessibility elements are obscured or render via OpenGL canvas. Visual target '${step.param}' could not be reliably verified."
            )
        }

        return StepExecutionResult(step.id, false, UniversalActionType.TAP, "Could not find '${step.param}' on current screen.")
    }

    private suspend fun handleTypeNode(step: TaskStep): StepExecutionResult {
        val service = KavyaAccessibilityService.instance
        if (service == null) {
            return StepExecutionResult(step.id, false, UniversalActionType.TYPE, "Accessibility Service is not enabled.")
        }

        val screenBefore = screenInspector.getScreenContextString()
        val textToType = step.param

        var targetField = screenInspector.findTargetNodeForStep(step)
            ?: service.findSearchField(null)

        // If editable field is not directly open, look for a search icon/button to open search mode first
        if (targetField == null) {
            val root = service.rootInActiveWindow
            val currentPkg = service.getForegroundPackage().lowercase(Locale.ROOT)
            val searchBtn = if (currentPkg.contains("spotify")) {
                service.findSpotifySearchTab(root)
            } else if (currentPkg.contains("whatsapp")) {
                service.findWhatsAppSearchButton(root)
            } else if (currentPkg.contains("youtube")) {
                service.findYouTubeSearchButton(root)
            } else {
                service.findNodeRecursively(root, "Search")
                    ?: service.findNodeRecursively(root, "खोजें")
                    ?: service.findNodeRecursively(root, "search")
            }
            if (searchBtn != null && (searchBtn.isClickable || searchBtn.parent?.isClickable == true)) {
                service.clickNode(searchBtn)
                delay(300) // Wait for search edit box to animate in
                targetField = service.findSearchField(null)
            }
        }

        var typed = if (targetField != null) {
            service.typeInNode(targetField, textToType)
        } else {
            service.typeInNodeByText("search", textToType)
        }

        if (!typed) {
            // It might have clicked a wrapper field (like Google Search bar) which opens an editable field.
            delay(150)
            targetField = service.findSearchField(null)
            if (targetField != null) {
                typed = service.typeInNode(targetField, textToType)
            }
        }

        if (!typed) {
            // Gemini Visual Fallback (max 1 call)
            val fallbackResult = executeGeminiVisualFallback(step.actionType, step.param)
            if (fallbackResult != null && fallbackResult.success) {
                // We tapped on the text field, now we need to type
                delay(100)
                typed = service.typeInFocusedNode(textToType) || service.typeInNodeByText(textToType, textToType) || service.typeInNodeByText("search", textToType)
                if (typed) {
                    val verif = verificationEngine.verifyUiInteraction(UniversalActionType.TYPE, textToType, screenBefore)
                    return StepExecutionResult(step.id, true, UniversalActionType.TYPE, "Typed via visual fallback: '$textToType'", screenSummary = verif.screenSummary)
                }
            }
            return StepExecutionResult(step.id, false, UniversalActionType.TYPE, "Could not find an editable input field on screen.")
        }

        val verif = verificationEngine.verifyUiInteraction(UniversalActionType.TYPE, textToType, screenBefore)
        
        delay(100)
        val root = service.rootInActiveWindow
        val submitBtn = service.findNodeRecursively(root, "Search")
            ?: service.findNodeRecursively(root, "खोजें")
            ?: service.findNodeRecursively(root, "Go")
            ?: service.findNodeRecursively(root, "Submit")
            ?: service.findNodeRecursively(root, "Enter")

        if (submitBtn != null && submitBtn.isClickable) {
            service.clickNode(submitBtn)
            delay(100)
        }
        
        return StepExecutionResult(step.id, true, UniversalActionType.TYPE, "Typed '$textToType'")
    }

    private fun handleClearText(): StepExecutionResult {
        val service = KavyaAccessibilityService.instance ?: return StepExecutionResult(1, false, UniversalActionType.CLEAR_TEXT, "Accessibility off")
        val root = service.rootInActiveWindow
        val field = service.findSearchField(root)
        val cleared = service.clearNodeText(field)
        return StepExecutionResult(1, cleared, UniversalActionType.CLEAR_TEXT, if (cleared) "Cleared input text" else "Could not clear text")
    }

    private suspend fun handleSearch(step: TaskStep): StepExecutionResult {
        val typeResult = handleTypeNode(step)
        if (!typeResult.success) {
            return StepExecutionResult(step.id, false, UniversalActionType.SEARCH, "Failed to type search query: ${typeResult.output}")
        }
        val submitResult = handleSubmitSearch()
        return StepExecutionResult(step.id, submitResult.success, UniversalActionType.SEARCH, "Searched for '${step.param}'")
    }

    private suspend fun handleSubmitSearch(): StepExecutionResult {
        val service = KavyaAccessibilityService.instance ?: return StepExecutionResult(3, false, UniversalActionType.SUBMIT, "Accessibility Service off")
        val submitted = service.clickSearchOrSubmitButton()
        delay(200) // Fast wait for search results to render
        val screenAfter = screenInspector.inspectScreen()
        val hasResults = screenAfter.elementSummaries.isNotEmpty()
        return StepExecutionResult(3, true, UniversalActionType.SUBMIT, if (hasResults) "Search submitted and results rendered" else "Search submitted")
    }

    private suspend fun handleSelectOrdinalResult(step: TaskStep): StepExecutionResult {
        val service = KavyaAccessibilityService.instance ?: return StepExecutionResult(step.id, false, UniversalActionType.SELECT, "Accessibility Service off")

        // Poll up to 3.5 seconds for dynamic web or app search results to render
        var targetNode: AccessibilityNodeInfo? = null
        for (attempt in 0..11) {
            val freshState = freshRecognizer.acquireFreshScreen(waitForStability = true, maxWaitMs = 1200L)
            val root = freshState.root
            if (root != null) {
                targetNode = service.findOrdinalContentNode(root, step.ordinalIndex)
                    ?: freshState.organicContentResults.getOrNull(if (step.ordinalIndex == -1) freshState.organicContentResults.size - 1 else step.ordinalIndex)?.node
                    ?: screenInspector.findTargetNode("first_result", "FIRST_RESULT")
                if (targetNode != null) break
            }
            delay(250)
        }

        if (targetNode != null) {
            val screenBefore = screenInspector.getScreenContextString()
            val clicked = service.clickNode(targetNode)
            if (clicked) {
                delay(350)
                val verif = verificationEngine.verifyUiInteraction(UniversalActionType.SELECT, "Ordinal_${step.ordinalIndex}", screenBefore)
                return StepExecutionResult(step.id, true, UniversalActionType.SELECT, "Result at index ${step.ordinalIndex} selected", screenSummary = verif.screenSummary)
            }
        }

        return StepExecutionResult(step.id, false, UniversalActionType.SELECT, "Could not identify search result at index ${step.ordinalIndex}.")
    }

    private suspend fun handlePlayMedia(step: TaskStep): StepExecutionResult {
        val service = KavyaAccessibilityService.instance ?: return StepExecutionResult(step.id, false, UniversalActionType.PLAY, "Accessibility off")

        // Poll up to 3.5 seconds for video cards or media items to render
        var resultNode: AccessibilityNodeInfo? = null
        for (attempt in 0..11) {
            val freshState = freshRecognizer.acquireFreshScreen(waitForStability = true, maxWaitMs = 1200L)
            val root = freshState.root
            if (root != null) {
                resultNode = service.findOrdinalContentNode(root, step.ordinalIndex)
                    ?: freshState.organicContentResults.getOrNull(if (step.ordinalIndex == -1) freshState.organicContentResults.size - 1 else step.ordinalIndex)?.node
                    ?: screenInspector.findTargetNode("Play", "PLAY_BUTTON")
                if (resultNode != null) break
            }
            delay(250)
        }

        if (resultNode != null) {
            val screenBefore = screenInspector.getScreenContextString()
            val clicked = service.clickNode(resultNode)
            if (clicked) {
                delay(400)
                val verif = verificationEngine.verifyUiInteraction(UniversalActionType.PLAY, "Media_${step.ordinalIndex}", screenBefore)
                return StepExecutionResult(step.id, true, UniversalActionType.PLAY, "Playing media item", screenSummary = verif.screenSummary)
            }
        }

        val clickedText = service.clickNodeByText("Play") || service.clickNodeByText("Play online")
        return StepExecutionResult(step.id, clickedText, UniversalActionType.PLAY, if (clickedText) "Play clicked" else "Cannot locate play item")
    }

    private suspend fun handleScroll(direction: String): StepExecutionResult {
        val service = KavyaAccessibilityService.instance ?: return StepExecutionResult(1, false, UniversalActionType.SCROLL, "Accessibility Service off")
        val scrolled = service.scroll(direction)
        return StepExecutionResult(1, scrolled, UniversalActionType.SCROLL, if (scrolled) "Scrolled $direction" else "Screen is not scrollable")
    }

    private suspend fun handleChessMove(moveNotation: String): StepExecutionResult {
        val service = KavyaAccessibilityService.instance ?: return StepExecutionResult(1, false, UniversalActionType.CHESS_MOVE, "Accessibility Service off")
        
        val squareNode = screenInspector.findTargetNode(moveNotation.lowercase(Locale.ROOT))
        if (squareNode != null) {
            val clicked = service.clickNode(squareNode)
            return StepExecutionResult(1, clicked, UniversalActionType.CHESS_MOVE, "Played $moveNotation on board")
        }

        val typed = service.typeInNodeByText("move", moveNotation)
        return StepExecutionResult(1, typed, UniversalActionType.CHESS_MOVE, if (typed) "Entered move $moveNotation" else "Cannot locate $moveNotation square on chess board")
    }

    private suspend fun handlePhoneCall(step: TaskStep): StepExecutionResult {
        val target = step.recipient.ifBlank { step.param }.trim()
        screenInspector.logStep("Initiating real phone call to '$target'")

        val isPhoneNumber = target.all { it.isDigit() || it == '+' || it == ' ' || it == '-' } && target.filter { it.isDigit() }.length >= 3
        if (isPhoneNumber) {
            val cleanNumber = target.filter { it.isDigit() || it == '+' }
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$cleanNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(intent)
                delay(400)
                val callVerif = verificationEngine.verifyCallActive()
                StepExecutionResult(step.id, callVerif.passed, UniversalActionType.MAKE_PHONE_CALL, if (callVerif.passed) "Calling $cleanNumber" else "Call verify nahi hui.")
            } catch (e: SecurityException) {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanNumber")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
                StepExecutionResult(step.id, true, UniversalActionType.MAKE_PHONE_CALL, "Dialer opened for $cleanNumber")
            } catch (e: Exception) {
                StepExecutionResult(step.id, false, UniversalActionType.MAKE_PHONE_CALL, "Phone call start failed: ${e.message}")
            }
        }

        // Target is Contact Name
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            delay(400)
            val service = KavyaAccessibilityService.instance
            if (service != null) {
                service.typeInNodeByText("Search", target) || service.typeInNodeByText("Search contacts", target)
                delay(300)
                val root = service.rootInActiveWindow
                val contactNode = if (root != null) screenInspector.findContactNode(root, target) else null
                if (contactNode != null) {
                    service.clickNode(contactNode)
                    delay(300)
                    val callBtn = service.findAudioCallButton(service.rootInActiveWindow)
                        ?: service.findNodeRecursively(service.rootInActiveWindow, "Call")
                    if (callBtn != null) {
                        service.clickNode(callBtn)
                        delay(300)
                    }
                }
            }
            val callVerif = verificationEngine.verifyCallActive()
            return StepExecutionResult(step.id, callVerif.passed, UniversalActionType.MAKE_PHONE_CALL, if (callVerif.passed) "Calling $target" else "Dialer opened for $target")
        } catch(e: Exception) {
            return StepExecutionResult(step.id, false, UniversalActionType.MAKE_PHONE_CALL, "Could not start call: ${e.message}")
        }
    }

    private suspend fun handleWhatsAppCall(step: TaskStep): StepExecutionResult {
        val contactName = step.recipient.ifBlank { step.param }.trim()
        screenInspector.logStep("Initiating real WhatsApp audio call to '$contactName'")

        val launchResult = handleOpenApp("WhatsApp")
        if (!launchResult.success) {
            return StepExecutionResult(step.id, false, UniversalActionType.MAKE_WHATSAPP_CALL, "WhatsApp open nahi ho paya: ${launchResult.output}")
        }

        val service = KavyaAccessibilityService.instance
        if (service == null) {
            return StepExecutionResult(step.id, false, UniversalActionType.MAKE_WHATSAPP_CALL, "Accessibility service active nahi hai.")
        }

        delay(400)
        var root = service.rootInActiveWindow

        // Check if audio call button is directly accessible on screen
        var callBtn = service.findAudioCallButton(root)

        if (callBtn == null) {
            val searchBtn = service.findNodeRecursively(root, "Search")
                ?: service.findNodeRecursively(root, "खोजें")
                ?: screenInspector.findTargetNode("search")

            if (searchBtn != null) {
                service.clickNode(searchBtn)
                delay(250)
            }

            service.typeInFocusedNode(contactName) || service.typeInNodeByText("Search…", contactName)
            delay(500)

            root = service.rootInActiveWindow
            val contactNode = if (root != null) screenInspector.findContactNode(root, contactName) else null
            if (contactNode != null) {
                service.clickNode(contactNode)
                delay(400)
            } else {
                return StepExecutionResult(step.id, false, UniversalActionType.MAKE_WHATSAPP_CALL, "WhatsApp par contact '$contactName' nahi mila.")
            }

            // Check if profile popup dialog opened
            root = service.rootInActiveWindow
            if (service.isWhatsAppProfileDialogVisible(root)) {
                val popupCallIcon = service.getWhatsAppProfileDialogAction(root, "call")
                if (popupCallIcon != null) {
                    service.clickNode(popupCallIcon)
                    delay(400)
                    val callVerif = verificationEngine.verifyCallActive()
                    return StepExecutionResult(step.id, callVerif.passed, UniversalActionType.MAKE_WHATSAPP_CALL, if (callVerif.passed) "WhatsApp calling $contactName" else "Call screen verify nahi hui.")
                }
            }

            root = service.rootInActiveWindow
            callBtn = service.findAudioCallButton(root)
        }

        if (callBtn != null) {
            val clicked = service.clickNode(callBtn)
            if (clicked) {
                delay(400)
                val callVerif = verificationEngine.verifyCallActive()
                return StepExecutionResult(step.id, callVerif.passed, UniversalActionType.MAKE_WHATSAPP_CALL, if (callVerif.passed) "WhatsApp calling $contactName" else "Call start nahi ho payi.")
            }
        }

        return StepExecutionResult(step.id, false, UniversalActionType.MAKE_WHATSAPP_CALL, "WhatsApp audio call button locate nahi hua.")
    }

    private suspend fun handleSms(step: TaskStep): StepExecutionResult {
        var contactName = step.recipient
        var messageText = step.messageText
        if (contactName.isBlank() && step.param.contains("||")) {
            val parts = step.param.split("||")
            contactName = parts.getOrNull(0) ?: ""
            messageText = parts.getOrNull(1) ?: ""
        }
        if (contactName.isBlank()) contactName = step.param

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:")
            putExtra("sms_body", messageText)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            delay(200)
            val service = KavyaAccessibilityService.instance
            if (service != null) {
                service.typeInNodeByText("To", contactName)
                delay(150)
                service.clickNodeByText(contactName)
                delay(150)
                service.clickSearchOrSubmitButton() || service.clickNodeByText("Send")
            }
            return StepExecutionResult(step.id, true, UniversalActionType.SEND_SMS, "Sent SMS to $contactName")
        } catch(e: Exception) {
            return StepExecutionResult(step.id, false, UniversalActionType.SEND_SMS, "Could not start messaging intent")
        }
    }

    private suspend fun handleWhatsAppMessage(step: TaskStep): StepExecutionResult {
        var contactName = step.recipient
        var messageText = step.messageText
        if (contactName.isBlank() && step.param.contains("||")) {
            val parts = step.param.split("||", limit = 2)
            contactName = parts.getOrNull(0)?.trim() ?: ""
            messageText = parts.getOrNull(1)?.trim() ?: ""
        }
        if (contactName.isBlank() && step.param.contains(":")) {
            val parts = step.param.split(":", limit = 2)
            contactName = parts.getOrNull(0)?.trim() ?: ""
            messageText = parts.getOrNull(1)?.trim() ?: ""
        }
        if (contactName.isBlank()) contactName = step.param.trim()
        if (messageText.isBlank()) messageText = "Hello"

        val lowerContact = contactName.lowercase(Locale.ROOT)
        val isGenericContact = lowerContact in listOf(
            "इस contact", "is contact", "this contact", "current contact", "contact", "chat",
            "दोस्त", "friend", "my friend", "mere dost", "is chat", "yeh contact", "ye contact"
        )

        screenInspector.logStep("Initiating real WhatsApp message to '$contactName': '$messageText'")

        // 1. Verify if WhatsApp is already active in foreground. If not, launch it.
        val currentFg = screenInspector.getCurrentForegroundPackage().lowercase(Locale.ROOT)
        if (!currentFg.contains("whatsapp")) {
            val launchResult = handleOpenApp("WhatsApp")
            if (!launchResult.success) {
                return StepExecutionResult(step.id, false, UniversalActionType.SEND_WHATSAPP_MESSAGE, "WhatsApp open nahi ho paya: ${launchResult.output}")
            }
            delay(400)
        }

        val service = KavyaAccessibilityService.instance
        if (service == null) {
            return StepExecutionResult(step.id, false, UniversalActionType.SEND_WHATSAPP_MESSAGE, "Accessibility service active nahi hai, automation sambhav nahi hai.")
        }

        delay(300)

        // 2. Check if chat input is already visible on the current screen
        var root = service.rootInActiveWindow
        var msgInput = service.findWhatsAppMessageInput(root)

        if (msgInput == null) {
            // Check if contact is already visible on chat list
            var contactElem = if (!isGenericContact) {
                ScreenUnderstanding.findElementMatching(
                    ScreenUnderstanding.capture(root, "com.whatsapp"),
                    contactName
                )
            } else null

            // If not visible and not generic, scroll the chat container
            if (contactElem == null && !isGenericContact) {
                contactElem = ScrollEngine.findAndScrollTo(service, "com.whatsapp", contactName, maxAttempts = 3)
            }

            if (contactElem != null && contactElem.nodeInfo != null) {
                service.clickNode(contactElem.nodeInfo)
                delay(400)
            } else if (isGenericContact) {
                // If generic contact (e.g. "इस contact को"), click the top/first conversation item in chat list
                val snapshot = ScreenUnderstanding.capture(root, "com.whatsapp")
                val firstChat = snapshot.listItems.firstOrNull { it.isValidTarget() && it.bounds.top > 180 }
                    ?: snapshot.allElements.firstOrNull { it.isClickable && !it.isEditable && it.bounds.top in 180..1800 && it.bounds.height() in 60..300 }
                if (firstChat?.nodeInfo != null) {
                    service.clickNode(firstChat.nodeInfo)
                    delay(400)
                }
            } else {
                val searchBtn = service.findNodeRecursively(root, "Search")
                    ?: service.findNodeRecursively(root, "खोजें")
                    ?: screenInspector.findTargetNode("search")

                if (searchBtn != null) {
                    service.clickNode(searchBtn)
                    delay(250)
                }

                service.typeInFocusedNode(contactName) || service.typeInNodeByText("Search…", contactName) || service.typeInNodeByText("Search", contactName)
                delay(500)

                root = service.rootInActiveWindow
                val contactNode = if (root != null) screenInspector.findContactNode(root, contactName) else null
                if (contactNode != null) {
                    service.clickNode(contactNode)
                    delay(400)
                } else {
                    return StepExecutionResult(step.id, false, UniversalActionType.SEND_WHATSAPP_MESSAGE, "WhatsApp par contact '$contactName' nahi mila.")
                }
            }

            // Check if profile photo popup dialog opened
            root = service.rootInActiveWindow
            if (service.isWhatsAppProfileDialogVisible(root)) {
                val chatIcon = service.getWhatsAppProfileDialogAction(root, "message")
                if (chatIcon != null) {
                    service.clickNode(chatIcon)
                    delay(300)
                }
            }

            val chatVerif = verificationEngine.verifyWhatsAppChatOpen(contactName, timeoutMs = 2500L)
            if (!chatVerif.passed) {
                return StepExecutionResult(step.id, false, UniversalActionType.SEND_WHATSAPP_MESSAGE, "Chat window open nahi ho saki: ${chatVerif.reason}")
            }
        }

        // 3. Type message into input
        root = service.rootInActiveWindow
        msgInput = service.findWhatsAppMessageInput(root)
        if (msgInput != null) {
            val typed = service.typeInNode(msgInput, messageText)
            if (!typed) {
                service.typeInFocusedNode(messageText)
            }
            delay(250)
        } else {
            return StepExecutionResult(step.id, false, UniversalActionType.SEND_WHATSAPP_MESSAGE, "Message input field nahi mila.")
        }

        // 4. Click Send using deterministic TapEngine
        val sendResult = TapEngine.tapSendButton(service, "com.whatsapp")
        if (sendResult.success) {
            screenInspector.logStep("WhatsApp message sent verified for $contactName")
            val targetLabel = if (isGenericContact) "contact" else contactName
            return StepExecutionResult(step.id, true, UniversalActionType.SEND_WHATSAPP_MESSAGE, "WhatsApp par $targetLabel ko message bhej diya gaya hai.")
        }

        return StepExecutionResult(step.id, false, UniversalActionType.SEND_WHATSAPP_MESSAGE, sendResult.diagnostic)
    }

    private suspend fun handleEmail(step: TaskStep): StepExecutionResult {
        val recipient = step.recipient.ifBlank { step.param }
        val messageText = step.messageText
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$recipient")
            putExtra(Intent.EXTRA_TEXT, messageText)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            delay(500)
            val service = KavyaAccessibilityService.instance
            if (service != null) {
                service.clickNodeByText("Send")
            }
            return StepExecutionResult(step.id, true, UniversalActionType.SEND_EMAIL, "Sent email to $recipient")
        } catch(e: Exception) {
            return StepExecutionResult(step.id, false, UniversalActionType.SEND_EMAIL, "Could not start email intent")
        }
    }

    private fun handleGlobalAction(action: Int, stepId: Int): StepExecutionResult {
        val service = KavyaAccessibilityService.instance ?: return StepExecutionResult(stepId, false, UniversalActionType.BACK, "Accessibility Service off")
        val ok = service.performGlobal(action)
        return StepExecutionResult(stepId, ok, UniversalActionType.BACK, "Action executed")
    }

    private suspend fun handleSystemControl(step: TaskStep): StepExecutionResult {
        val cmd = step.param.uppercase(Locale.ROOT)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        return when {
            cmd.contains("VOLUME_DOWN") || cmd.contains("VOLUME_LOWER") -> {
                audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                StepExecutionResult(step.id, true, UniversalActionType.SYSTEM_CONTROL, "Turned volume down")
            }
            cmd.contains("VOLUME_UP") || cmd.contains("VOLUME_RAISE") -> {
                audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                StepExecutionResult(step.id, true, UniversalActionType.SYSTEM_CONTROL, "Turned volume up")
            }
            cmd.contains("VOLUME_MUTE") -> {
                audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                StepExecutionResult(step.id, true, UniversalActionType.SYSTEM_CONTROL, "Muted volume")
            }
            cmd.contains("BRIGHTNESS_UP") || cmd.contains("BRIGHTNESS_DOWN") || cmd.contains("BRIGHTNESS") -> {
                val canWrite = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Settings.System.canWrite(context)
                } else true

                if (canWrite) {
                    try {
                        val current = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
                        val delta = if (cmd.contains("UP")) 50 else -50
                        val target = (current + delta).coerceIn(10, 255)
                        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, target)
                        StepExecutionResult(step.id, true, UniversalActionType.SYSTEM_CONTROL, "Adjusted brightness to $target")
                    } catch (e: Exception) {
                        openDisplaySettings(step.id)
                    }
                } else {
                    openDisplaySettings(step.id)
                }
            }
            else -> {
                StepExecutionResult(step.id, true, UniversalActionType.SYSTEM_CONTROL, "Executed system control: ${step.param}")
            }
        }
    }

    private fun openDisplaySettings(stepId: Int): StepExecutionResult {
        return try {
            val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            StepExecutionResult(stepId, true, UniversalActionType.SYSTEM_CONTROL, "Opened Display Settings to adjust brightness")
        } catch (e: Exception) {
            StepExecutionResult(stepId, false, UniversalActionType.SYSTEM_CONTROL, "Unable to adjust brightness: ${e.message}")
        }
    }

    private suspend fun handleNavigate(step: TaskStep): StepExecutionResult {
        if (step.param.startsWith("DATE:")) {
            val rawDate = step.param.removePrefix("DATE:").trim()
            return openCalendarAtDate(step.id, rawDate)
        }
        return StepExecutionResult(step.id, true, UniversalActionType.NAVIGATE, "Navigated to ${step.param}")
    }

    private suspend fun openCalendarAtDate(stepId: Int, rawDate: String): StepExecutionResult {
        return try {
            val cal = Calendar.getInstance()
            val currentYear = cal.get(Calendar.YEAR)
            val cleanDate = rawDate.replace("(?i)(st|nd|rd|th)".toRegex(), "").trim()

            var timeMillis = System.currentTimeMillis()
            val formats = listOf("dd MMMM", "MMMM dd", "dd MMM", "MMM dd", "dd/MM/yyyy", "dd-MM-yyyy")
            for (fmt in formats) {
                try {
                    val sdf = SimpleDateFormat(fmt, Locale.ENGLISH)
                    val parsed = sdf.parse(cleanDate)
                    if (parsed != null) {
                        val parsedCal = Calendar.getInstance()
                        parsedCal.time = parsed
                        parsedCal.set(Calendar.YEAR, currentYear)
                        timeMillis = parsedCal.timeInMillis
                        break
                    }
                } catch (e: Exception) { }
            }

            val builder = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
            ContentUris.appendId(builder, timeMillis)
            val intent = Intent(Intent.ACTION_VIEW).setData(builder.build()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            delay(500)

            val service = KavyaAccessibilityService.instance
            if (service != null) {
                val dayStr = cleanDate.filter { it.isDigit() }
                if (dayStr.isNotBlank()) {
                    service.clickNodeByText(dayStr)
                }
            }

            StepExecutionResult(stepId, true, UniversalActionType.NAVIGATE, "Navigated Calendar to $rawDate")
        } catch (e: Exception) {
            StepExecutionResult(stepId, false, UniversalActionType.NAVIGATE, "Failed to open Calendar at $rawDate: ${e.message}")
        }
    }

    private suspend fun executeGeminiVisualFallback(actionType: UniversalActionType, param: String): StepExecutionResult? {
        Log.d(TAG, "Triggering Kavya Visual Action Engine fallback for action: $actionType on '$param'")
        val (width, height) = visualActionEngine.screenshotManager.getScreenDimensions()
        val snapshot = visualActionEngine.screenObserver.observeScreen(captureVisual = true)
        val screenshot = snapshot.screenshot ?: return null
        val (base64Img, _) = visualActionEngine.screenshotManager.encodeToOptimizedBase64(screenshot, retainBitmap = false)
        if (base64Img.isBlank()) return null

        val (_, visionAction) = visualActionEngine.visionAnalyzer.analyzeScreenForAction(
            base64Image = base64Img,
            userGoal = "$actionType on $param",
            currentApp = snapshot.foregroundPackage,
            hierarchySummary = snapshot.visibleTextSummary
        )

        if (visionAction != null && visionAction.confidence >= 0.70f) {
            val norm = com.example.visual.NormalizedCoordinates(visionAction.normalizedX, visionAction.normalizedY)
            val (ok, detail) = visualActionEngine.touchController.performTap(visionAction.target, norm, width, height)
            return StepExecutionResult(-1, ok, actionType, detail)
        }
        return StepExecutionResult(-1, false, actionType, "Visual fallback could not locate target: $param")
    }

    private fun parseCoordinates(response: String): Pair<Float, Float>? {
        val regex = Regex("""\b(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)\b""")
        val match = regex.find(response)
        if (match != null) {
            return Pair(match.groupValues[1].toFloat(), match.groupValues[2].toFloat())
        }
        return null
    }

    /**
     * Executes a structured action directly produced from Gemini or high-level pipeline.
     */
    suspend fun executeStructuredAction(action: StructuredAction): StepExecutionResult {
        if (action.action == "OPEN_AND_SEARCH") {
            val app = action.target.ifBlank { action.rawParam.substringBefore(":") }.trim()
            val query = action.query.ifBlank { action.rawParam.substringAfter(":", "") }.trim()
            val router = CommandRouter(context)
            val compoundResult = router.handleCompoundCommand(AppResolver.CompoundCommand(app, query, "SEARCH"))
            return StepExecutionResult(
                stepId = 1,
                success = compoundResult.success,
                actionType = UniversalActionType.SEARCH,
                output = compoundResult.output,
                isAmbiguous = compoundResult.isAmbiguous,
                candidateApps = compoundResult.candidateApps,
                diagnostic = compoundResult.diagnostic
            )
        }

        if (action.action == "SEARCH_WEB") {
            val query = action.query.ifBlank { action.rawParam }.trim()
            val router = CommandRouter(context)
            val webResult = router.handleSearchWeb(query)
            return StepExecutionResult(
                stepId = 1,
                success = webResult.success,
                actionType = UniversalActionType.OPEN_URL,
                output = webResult.output,
                diagnostic = webResult.diagnostic
            )
        }

        if (action.action == "CALL" || action.action == "PHONE") {
            val contact = action.target.ifBlank { action.rawParam }.trim()
            val router = CommandRouter(context)
            val callResult = router.handleCall(contact)
            return StepExecutionResult(
                stepId = 1,
                success = callResult.success,
                actionType = UniversalActionType.CALL,
                output = callResult.output,
                diagnostic = callResult.diagnostic
            )
        }

        if (action.action == "SMS" || action.action == "MESSAGE") {
            val contact = action.target.ifBlank { action.rawParam.substringBefore(":") }.trim()
            val message = action.query.ifBlank { action.rawParam.substringAfter(":", "Hello") }.trim()
            val router = CommandRouter(context)
            val smsResult = router.handleSms(contact, message)
            return StepExecutionResult(
                stepId = 1,
                success = smsResult.success,
                actionType = UniversalActionType.SEND_MESSAGE,
                output = smsResult.output,
                diagnostic = smsResult.diagnostic
            )
        }

        val uAction = when (action.action) {
            "OPEN_APP" -> UniversalActionType.OPEN_APP
            "CLOSE_APP" -> UniversalActionType.CLOSE_APP
            "UI_CLICK", "TAP", "CLICK" -> UniversalActionType.TAP
            "UI_TYPE", "TYPE", "TYPE_TEXT" -> UniversalActionType.TYPE
            "UI_SCROLL", "SCROLL" -> UniversalActionType.SCROLL
            "SWIPE" -> UniversalActionType.SWIPE
            "PRESS_BACK", "BACK" -> UniversalActionType.BACK
            "HOME" -> UniversalActionType.HOME
            "SEARCH_WEB", "SEARCH" -> UniversalActionType.SEARCH
            "OPEN_URL" -> UniversalActionType.OPEN_URL
            "READ_SCREEN" -> UniversalActionType.READ_SCREEN
            "SCREENSHOT" -> UniversalActionType.SCREENSHOT
            "DOWNLOAD_FILE", "DOWNLOAD" -> UniversalActionType.DOWNLOAD_FILE
            "SWITCH_APP" -> UniversalActionType.SWITCH_APP
            "ANALYZE_IMAGE" -> UniversalActionType.ANALYZE_IMAGE
            "RESEARCH_WEB" -> UniversalActionType.RESEARCH_WEB
            "GENERATE_IMAGE" -> UniversalActionType.GENERATE_IMAGE
            "SEMANTIC_SEARCH" -> UniversalActionType.SEMANTIC_SEARCH
            "GOOGLE_MAPS_SEARCH", "MAPS_SEARCH" -> UniversalActionType.GOOGLE_MAPS_SEARCH
            "MEMORY_SEARCH", "MEMORY_TOOL", "MEMORY_CREATE" -> UniversalActionType.MEMORY_TOOL
            "GLOBAL_ACTION" -> {
                when (action.rawParam.uppercase()) {
                    "BACK" -> UniversalActionType.BACK
                    "HOME" -> UniversalActionType.HOME
                    "RECENTS" -> UniversalActionType.SWITCH_APP
                    else -> UniversalActionType.SYSTEM_CONTROL
                }
            }
            "CLEAR_TEXT", "CLEAR" -> UniversalActionType.CLEAR_TEXT
            else -> UniversalActionType.SYSTEM_CONTROL
        }

        val step = TaskStep(
            id = 1,
            actionType = uAction,
            targetAppOrUrl = action.target,
            param = when (action.action) {
                "UI_CLICK", "TAP" -> action.selector.ifBlank { action.rawParam }
                "UI_TYPE", "TYPE" -> action.text.ifBlank { action.rawParam }
                "UI_SCROLL", "SCROLL" -> action.direction.ifBlank { action.rawParam }
                else -> action.rawParam.ifBlank { action.target }
            }
        )

        return executeAtomicStep(step)
    }
}

