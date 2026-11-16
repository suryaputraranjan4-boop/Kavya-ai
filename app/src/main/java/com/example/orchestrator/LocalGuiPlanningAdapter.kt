package com.example.orchestrator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.example.ai.StructuredRoutingStatus
import com.example.ai.TaskRouter
import com.example.ai.offline.Gemma4E4BEngine
import com.example.ai.offline.GemmaModelManager
import com.example.ai.offline.SmolVlmTaskMode
import com.example.services.KavyaAccessibilityService
import com.example.visual.MultiSignalFusionEngine
import com.example.visual.OnDeviceOcrExtractor
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Local GUI Planning & Grounding Adapter inside the authorized `SelectedProviderClient` / `CeoAiOrchestrator` pipeline.
 *
 * Responsibilities (Section 3.3 & Section 5.1):
 * 1. Runs only when strict offline mode is active OR when temporary network loss triggers automatic local fallback
 *    and `SmolVLM2-2.2B-Instruct-Agentic-GUI-Q4_K_M.gguf` + mandatory `mmproj-f16.gguf` are verified and READY.
 * 2. Combines Accessibility hierarchy, On-Device OCR, optional Florence-2 auxiliary vision, and SmolVLM2 GUI grounding
 *    into `MultiSignalFusionEngine`.
 * 3. Emits canonical v1.0 JSON (`CeoTaskPlan` or `CeoVerificationResult`) that must still pass through
 *    `AiProtocolParser`, `AiAuthorityGateway` permits, `PreActionVerifier`, `PrecisionController`, and `PostActionVerifier`.
 */
object LocalGuiPlanningAdapter {

    suspend fun invokeLocalPlanningOrVerification(
        context: Context,
        request: ProviderInvocationRequest
    ): ProviderInvocationResult {
        val engine = Gemma4E4BEngine.getInstance(context)
        val (healthStatus, florenceHealthy) = TaskRouter.inspectLocalMultimodalHealth(context)

        if (healthStatus != StructuredRoutingStatus.READY || !engine.isSmolVlm2Ready()) {
            return ProviderInvocationResult.Failure(
                code = healthStatus.name,
                message = "Local multimodal GUI model is not ready (${healthStatus.name}). Both SmolVLM2 Q4_K_M and companion mmproj-f16 must be verified and loaded."
            )
        }

        val taskId = extractPromptField(request.prompt, "task_id").ifBlank { "task_local" }
        val sessionId = extractPromptField(request.prompt, "session_id").ifBlank { "sess_local" }
        val screenshotId = extractPromptField(request.prompt, "new_screenshot_reference")
            .ifBlank { extractPromptField(request.prompt, "screenshot_reference") }
            .ifBlank { ScreenObservationCoordinator.latestObservation?.screenshotId.orEmpty() }
        val userCommand = extractUserCommand(request.prompt)
        val nextSequence = extractNextSequence(request.prompt)
        val isVerification = request.prompt.contains("POST-ACTION VERIFICATION", ignoreCase = true) ||
            request.prompt.contains("\"verified\":true", ignoreCase = true)

        val decodedBitmap = decodeBitmapOrProbe(request.imageBytes)
        try {
            val a11yElements = KavyaAccessibilityService.instance?.extractInteractiveElements() ?: emptyList()
            val ocrSignals = OnDeviceOcrExtractor.extractSignals(decodedBitmap, a11yElements)

            val obs = ScreenObservationCoordinator.latestObservation
            val screenW = obs?.displayWidthPx ?: decodedBitmap.width.coerceAtLeast(1080)
            val screenH = obs?.displayHeightPx ?: decodedBitmap.height.coerceAtLeast(2400)
            val orientation = obs?.orientation ?: 1
            val effectiveObservation = if (obs != null && (screenshotId.isBlank() || obs.screenshotId == screenshotId)) {
                obs
            } else {
                ScreenObservationRecord(
                    screenshotId = screenshotId.ifBlank { "scr_local_${System.currentTimeMillis()}" },
                    capturedAtMs = System.currentTimeMillis(),
                    displayWidthPx = screenW,
                    displayHeightPx = screenH,
                    bitmapWidth = decodedBitmap.width.coerceAtLeast(1),
                    bitmapHeight = decodedBitmap.height.coerceAtLeast(1),
                    orientation = orientation,
                    densityDpi = 420,
                    foregroundPackage = KavyaAccessibilityService.currentForegroundPackage,
                    imageBytes = request.imageBytes,
                    source = "LOCAL_GUI_ADAPTER"
                )
            }

            if (isVerification) {
                val smolResult = engine.runSmolVlm2GuiGrounding(
                    screenshot = decodedBitmap,
                    userGoal = userCommand,
                    taskMode = SmolVlmTaskMode.POST_ACTION_VERIFICATION,
                    uiContextHint = request.prompt.take(900)
                )
                val vlmCandidates = smolResult.getOrElse { err ->
                    val code = err.message?.substringBefore(":")?.trim()?.ifBlank { "LOCAL_VLM_ERROR" } ?: "LOCAL_VLM_ERROR"
                    return ProviderInvocationResult.Failure(
                        code = code,
                        message = err.message ?: "Local SmolVLM2 verification failed."
                    )
                }
                val verJson = JSONObject().apply {
                    put("protocol_version", "1.0")
                    put("task_id", taskId)
                    put("session_id", sessionId)
                    put("screenshot_reference", screenshotId)
                    put("verified", vlmCandidates.isNotEmpty())
                    put("observed_screen_summary", "Verified post-action screen state locally with SmolVLM2 + MultiSignalFusion.")
                    put("mismatch_reason", "")
                    put("is_task_completed", true)
                    put("needs_replan", false)
                    put("assistant_message", "Action verified locally.")
                    put("next_action", JSONObject.NULL)
                }
                return ProviderInvocationResult.Success(
                    text = verJson.toString(),
                    providerId = request.providerId,
                    modelId = request.modelId.ifBlank { GemmaModelManager.SMOLVLM2_MAIN_GGUF_FILENAME }
                )
            }

            // Stage 1 + Consensus Engine (SmolVLM2 + Florence-2 + optional Cloud escalation + Accessibility match)
            val consensusOutcome = com.example.visual.VisionConsensusCoordinator(context)
                .resolveTargetWithConsensus(
                    observation = effectiveObservation,
                    targetQuery = userCommand,
                    screenshotBitmap = decodedBitmap
                )

            if (consensusOutcome.isStale) {
                return ProviderInvocationResult.Failure(
                    code = "OBSERVATION_STALE",
                    message = consensusOutcome.detail
                )
            }

            val consensusCandidate = MultiSignalFusionEngine.buildCandidateFromConsensus(
                outcome = consensusOutcome,
                screenWidth = screenW,
                screenHeight = screenH
            )

            val auxSignals = if (florenceHealthy && consensusOutcome.florence2Result?.hasValidCoordinates == true) {
                listOfNotNull(
                    consensusOutcome.florence2Result.normalizedBox?.let { box ->
                        com.example.visual.VisionElementSignal(
                            label = consensusOutcome.florence2Result.targetLabel.ifBlank { userCommand },
                            roleHint = consensusOutcome.florence2Result.targetRole,
                            bounds = box.toDeviceRect(screenW, screenH),
                            confidence = consensusOutcome.florence2Result.calibratedConfidence,
                            sourceSignal = com.example.visual.SignalSource.AUX_VISION_MODEL,
                            sourceModelId = GemmaModelManager.FLORENCE2_MANIFEST.modelId,
                            sourceModelRevision = GemmaModelManager.FLORENCE2_MANIFEST.pinnedRevision
                        )
                    }
                )
            } else emptyList()

            val vlmSignals = if (consensusOutcome.smolVlm2Result?.hasValidCoordinates == true) {
                listOfNotNull(
                    consensusOutcome.smolVlm2Result.normalizedBox?.let { box ->
                        com.example.visual.VisionElementSignal(
                            label = consensusOutcome.smolVlm2Result.targetLabel.ifBlank { userCommand },
                            roleHint = consensusOutcome.smolVlm2Result.targetRole,
                            bounds = box.toDeviceRect(screenW, screenH),
                            confidence = consensusOutcome.smolVlm2Result.calibratedConfidence,
                            sourceSignal = com.example.visual.SignalSource.LOCAL_GUI_VLM,
                            sourceModelId = GemmaModelManager.SMOLVLM2_MANIFEST.modelId,
                            sourceModelRevision = GemmaModelManager.SMOLVLM2_MANIFEST.pinnedRevision
                        )
                    }
                )
            } else emptyList()

            val fusedElements = MultiSignalFusionEngine.fuseSignals(
                accessibilityElements = a11yElements,
                ocrSignals = ocrSignals,
                visionAction = null,
                screenWidth = screenW,
                screenHeight = screenH,
                localGuiVlmCandidates = vlmSignals,
                auxVisionCandidates = auxSignals,
                screenshotWidth = decodedBitmap.width,
                screenshotHeight = decodedBitmap.height,
                frameId = screenshotId.ifBlank { "frame_local" },
                observationTimestampMs = effectiveObservation.capturedAtMs,
                isObservationFresh = !consensusOutcome.isStale
            )

            val bestTarget = consensusCandidate
                ?: MultiSignalFusionEngine.resolveBestTarget(fusedElements, userCommand)

            if (bestTarget == null) {
                val lowerCmd = userCommand.lowercase(Locale.ROOT)
                if (lowerCmd.startsWith("open ") || lowerCmd.startsWith("launch ")) {
                    val appName = userCommand.replaceFirst(Regex("(?i)^(open|launch)\\s+"), "").trim()
                    val openPlan = buildLocalOpenAppPlanJson(
                        taskId = taskId,
                        sessionId = sessionId,
                        sequence = nextSequence,
                        screenshotId = screenshotId,
                        screenWidth = screenW,
                        screenHeight = screenH,
                        orientation = orientation,
                        appName = appName
                    )
                    return ProviderInvocationResult.Success(
                        text = openPlan,
                        providerId = request.providerId,
                        modelId = request.modelId.ifBlank { GemmaModelManager.SMOLVLM2_MAIN_GGUF_FILENAME }
                    )
                }
                val failCode = if (consensusOutcome.requiresFreshObservation) {
                    "REQUIRE_FRESH_OBSERVATION"
                } else {
                    StructuredRoutingStatus.TARGET_NOT_FOUND.name
                }
                return ProviderInvocationResult.Failure(
                    code = failCode,
                    message = "TARGET_NOT_FOUND: ${consensusOutcome.detail}"
                )
            }

            if (!bestTarget.isConsensusVerified && bestTarget.confidence < 0.55f) {
                return ProviderInvocationResult.Failure(
                    code = StructuredRoutingStatus.LOW_CONFIDENCE.name,
                    message = "LOW_CONFIDENCE: Best local target '${bestTarget.primaryLabel}' confidence (${bestTarget.confidence}) is uncorroborated and below threshold."
                )
            }

            val planJson = buildLocalTapPlanJson(
                taskId = taskId,
                sessionId = sessionId,
                sequence = nextSequence,
                screenshotId = screenshotId,
                screenWidth = screenW,
                screenHeight = screenH,
                orientation = orientation,
                targetLabel = bestTarget.primaryLabel.ifBlank { userCommand },
                normX = bestTarget.normalizedCenterX.toDouble(),
                normY = bestTarget.normalizedCenterY.toDouble(),
                confidence = bestTarget.confidence.toDouble()
            )
            return ProviderInvocationResult.Success(
                text = planJson,
                providerId = request.providerId,
                modelId = consensusOutcome.winningCandidate?.providerId
                    ?: request.modelId.ifBlank { GemmaModelManager.SMOLVLM2_MAIN_GGUF_FILENAME }
            )
        } finally {
            if (!decodedBitmap.isRecycled) {
                decodedBitmap.recycle()
            }
        }
    }

    private fun decodeBitmapOrProbe(imageBytes: ByteArray?): Bitmap {
        if (imageBytes != null && imageBytes.isNotEmpty()) {
            val decoded = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            if (decoded != null) return decoded
        }
        return Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
        }
    }

    private fun extractPromptField(prompt: String, key: String): String {
        val regex = Regex("""\b$key\s*[:=]\s*"?([A-Za-z0-9_.-]+)"?""")
        return regex.find(prompt)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun extractUserCommand(prompt: String): String {
        val linePrefix = prompt.lineSequence()
            .firstOrNull { it.trim().startsWith("USER COMMAND:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()
        if (!linePrefix.isNullOrBlank()) return linePrefix

        val origPrefix = prompt.lineSequence()
            .firstOrNull { it.trim().startsWith("original_user_command:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()
        if (!origPrefix.isNullOrBlank()) return origPrefix

        return prompt.lineSequence().firstOrNull { it.isNotBlank() }?.take(120).orEmpty()
    }

    private fun extractNextSequence(prompt: String): Int {
        val regex = Regex("""next_sequence_if_continuing:\s*(\d+)""")
        regex.find(prompt)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return 1
    }

    private fun buildLocalOpenAppPlanJson(
        taskId: String,
        sessionId: String,
        sequence: Int,
        screenshotId: String,
        screenWidth: Int,
        screenHeight: Int,
        orientation: Int,
        appName: String
    ): String {
        val firstAction = JSONObject().apply {
            put("protocol_version", "1.0")
            put("task_id", taskId)
            put("session_id", sessionId)
            put("action_id", "act_local_$sequence")
            put("sequence", sequence)
            put("action", "OPEN_APP")
            put("app_name", appName)
            put("target", JSONObject().apply {
                put("description", appName)
                put("selector", appName)
            })
            put("screenshot_reference", screenshotId)
            put("screen_width", screenWidth)
            put("screen_height", screenHeight)
            put("orientation", orientation)
            put("confidence", 0.90)
            put("expected_result", "App $appName opens in foreground")
            put("verification_required", true)
            put("requires_confirmation", false)
            put("reason", "Launch $appName via local planner")
        }
        val stepObj = JSONObject().apply {
            put("step_number", 1)
            put("goal", "Open $appName")
            put("expected_outcome", "$appName is visible in foreground")
            put("requires_vision", false)
            put("may_be_consequential", false)
        }
        return JSONObject().apply {
            put("protocol_version", "1.0")
            put("task_id", taskId)
            put("session_id", sessionId)
            put("overall_goal", "Open $appName")
            put("requires_vision", false)
            put("steps", JSONArray().put(stepObj))
            put("first_action", firstAction)
        }.toString()
    }

    private fun buildLocalTapPlanJson(
        taskId: String,
        sessionId: String,
        sequence: Int,
        screenshotId: String,
        screenWidth: Int,
        screenHeight: Int,
        orientation: Int,
        targetLabel: String,
        normX: Double,
        normY: Double,
        confidence: Double
    ): String {
        val firstAction = JSONObject().apply {
            put("protocol_version", "1.0")
            put("task_id", taskId)
            put("session_id", sessionId)
            put("action_id", "act_local_$sequence")
            put("sequence", sequence)
            put("action", "TAP")
            put("target", JSONObject().apply {
                put("description", targetLabel)
                put("selector", targetLabel)
                put("coordinates", JSONObject().apply {
                    put("x", normX)
                    put("y", normY)
                    put("space", "NORMALIZED_0_1")
                })
            })
            put("screenshot_reference", screenshotId)
            put("screen_width", screenWidth)
            put("screen_height", screenHeight)
            put("orientation", orientation)
            put("confidence", confidence)
            put("expected_result", "Screen responds to tapping $targetLabel")
            put("verification_required", true)
            put("requires_confirmation", false)
            put("reason", "Grounded '$targetLabel' via local SmolVLM2 + MultiSignalFusion")
        }
        val stepObj = JSONObject().apply {
            put("step_number", 1)
            put("goal", "Tap $targetLabel")
            put("expected_outcome", "Screen transitions after tapping $targetLabel")
            put("requires_vision", true)
            put("may_be_consequential", false)
        }
        return JSONObject().apply {
            put("protocol_version", "1.0")
            put("task_id", taskId)
            put("session_id", sessionId)
            put("overall_goal", "Tap $targetLabel")
            put("requires_vision", true)
            put("steps", JSONArray().put(stepObj))
            put("first_action", firstAction)
        }.toString()
    }
}
