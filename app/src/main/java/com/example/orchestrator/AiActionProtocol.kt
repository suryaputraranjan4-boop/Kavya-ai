package com.example.orchestrator

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * Versioned AI <-> Execution Protocol (v1.0).
 * Every device automation action MUST originate from the selected online AI provider
 * using this strict structured schema and pass validation before any execution occurs.
 */
object AiProtocolConstants {
    const val PROTOCOL_VERSION = "1.0"
    const val HIGH_CONFIDENCE_MIN = 0.90
    const val MEDIUM_CONFIDENCE_MIN = 0.70
    const val DEFAULT_PERMIT_TTL_MS = 20_000L
    const val DEFAULT_SCREENSHOT_TTL_MS = 15_000L
    const val MAX_STEPS_PER_TASK = 15
    const val MAX_REPLANS_PER_TASK = 4
}

enum class OrchestratorActionType {
    OPEN_APP,
    SPLIT_SCREEN,
    TAP,
    DOUBLE_TAP,
    LONG_PRESS,
    TYPE,
    SWIPE,
    SCROLL,
    BACK,
    HOME,
    RECENTS,
    WAIT,
    VERIFY,
    SEARCH_WEB,
    REQUEST_CONFIRMATION,
    ASK_USER,
    DONE,
    ABORT;

    val requiresScreenObservation: Boolean
        get() = this in setOf(TAP, DOUBLE_TAP, LONG_PRESS, TYPE, SWIPE, SCROLL)

    val usesAccessibilityService: Boolean
        get() = this in setOf(TAP, DOUBLE_TAP, LONG_PRESS, TYPE, SWIPE, SCROLL, BACK, HOME, RECENTS)

    val isConsequentialByDefault: Boolean
        get() = false

    companion object {
        fun fromRawOrAlias(raw: String): OrchestratorActionType? {
            return when (raw.trim().uppercase(Locale.ROOT)) {
                "OPEN_APP", "LAUNCH_APP", "START_APP" -> OPEN_APP
                "SPLIT_SCREEN", "MULTI_WINDOW", "ENTER_SPLIT_SCREEN" -> SPLIT_SCREEN
                "TAP", "CLICK", "PRESS", "SELECT" -> TAP
                "DOUBLE_TAP", "DOUBLE_CLICK" -> DOUBLE_TAP
                "LONG_PRESS", "LONG_CLICK", "HOLD" -> LONG_PRESS
                "TYPE", "INPUT", "ENTER_TEXT", "SET_TEXT" -> TYPE
                "SWIPE", "FLING", "DRAG" -> SWIPE
                "SCROLL", "SCROLL_DOWN", "SCROLL_UP" -> SCROLL
                "BACK", "NAVIGATE_BACK", "GO_BACK" -> BACK
                "HOME", "GO_HOME" -> HOME
                "RECENTS", "RECENT_APPS", "OVERVIEW" -> RECENTS
                "WAIT", "SLEEP", "DELAY" -> WAIT
                "VERIFY", "CHECK", "OBSERVE" -> VERIFY
                "SEARCH_WEB", "WEB_SEARCH", "GOOGLE_SEARCH" -> SEARCH_WEB
                "REQUEST_CONFIRMATION", "CONFIRM" -> REQUEST_CONFIRMATION
                "ASK_USER", "CLARIFY", "NEEDS_USER_INPUT" -> ASK_USER
                "DONE", "COMPLETE", "COMPLETED", "FINISH", "SUCCESS" -> DONE
                "ABORT", "CANCEL", "STOP", "FAIL" -> ABORT
                else -> try {
                    valueOf(raw.trim().uppercase(Locale.ROOT))
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}

/**
 * Explicit verification statuses required by Section 8 (Independent Verification Engine).
 */
enum class OrchestratorVerificationStatus {
    SUCCESS,
    FAILED,
    PARTIALLY_COMPLETED,
    NEEDS_RETRY,
    NEEDS_USER_INPUT,
    BLOCKED_BY_PERMISSION,
    CANCELLED;

    companion object {
        fun parse(
            raw: String?,
            verified: Boolean,
            isTaskCompleted: Boolean,
            needsReplan: Boolean
        ): OrchestratorVerificationStatus {
            val clean = raw?.trim()?.uppercase(Locale.ROOT).orEmpty()
            if (clean.isNotBlank()) {
                entries.firstOrNull { it.name == clean }?.let { return it }
            }
            return when {
                verified && isTaskCompleted -> SUCCESS
                verified -> SUCCESS
                needsReplan -> NEEDS_RETRY
                else -> FAILED
            }
        }
    }
}

enum class CoordinateSpace {
    NORMALIZED_0_1,
    CURRENT_SCREENSHOT_PIXELS,
    DISPLAY_PIXELS;

    companion object {
        fun parse(raw: String?): CoordinateSpace? {
            return when (raw?.trim()?.uppercase(Locale.ROOT)) {
                "NORMALIZED_0_1", "NORMALIZED", "REL_0_1" -> NORMALIZED_0_1
                "CURRENT_SCREENSHOT_PIXELS", "SCREENSHOT_PIXELS", "IMAGE_PIXELS" -> CURRENT_SCREENSHOT_PIXELS
                "DISPLAY_PIXELS", "PHYSICAL_PIXELS", "SCREEN_PIXELS" -> DISPLAY_PIXELS
                else -> null
            }
        }
    }
}

enum class ConfidenceBand {
    HIGH,
    MEDIUM,
    LOW;

    companion object {
        fun fromScore(score: Double): ConfidenceBand {
            return when {
                score >= AiProtocolConstants.HIGH_CONFIDENCE_MIN -> HIGH
                score >= AiProtocolConstants.MEDIUM_CONFIDENCE_MIN -> MEDIUM
                else -> LOW
            }
        }
    }
}

data class AiCoordinates(
    val x: Double,
    val y: Double,
    val space: CoordinateSpace = CoordinateSpace.NORMALIZED_0_1
)

data class AiActionTarget(
    val description: String,
    val selector: String = "",
    val coordinates: AiCoordinates? = null
)

data class AiActionEnvelope(
    val protocolVersion: String = AiProtocolConstants.PROTOCOL_VERSION,
    val taskId: String,
    val sessionId: String,
    val actionId: String,
    val sequence: Int,
    val action: OrchestratorActionType,
    val target: AiActionTarget,
    val text: String = "",
    val direction: String = "",
    val appName: String = "",
    val screenshotReference: String = "",
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val orientation: Int = 0,
    val confidence: Double = 0.0,
    val expectedResult: String = "",
    val verificationRequired: Boolean = true,
    val requiresConfirmation: Boolean = false,
    val reason: String = "",
    val expiresAtMs: Long = System.currentTimeMillis() + AiProtocolConstants.DEFAULT_PERMIT_TTL_MS,
    val spokenMessage: String = ""
) {
    val confidenceBand: ConfidenceBand
        get() = ConfidenceBand.fromScore(confidence)
}

data class CeoPlanStep(
    val stepNumber: Int,
    val goal: String,
    val expectedOutcome: String,
    val likelyRequiresVision: Boolean = true,
    val mayBeConsequential: Boolean = false,
    val dependsOnStepNumbers: List<Int> = if (stepNumber > 1) listOf(stepNumber - 1) else emptyList()
)

data class CeoTaskPlan(
    val protocolVersion: String = AiProtocolConstants.PROTOCOL_VERSION,
    val taskId: String,
    val sessionId: String,
    val overallGoal: String,
    val requiresVision: Boolean,
    val steps: List<CeoPlanStep>,
    val firstAction: AiActionEnvelope? = null,
    val assistantMessage: String = ""
)

data class AiVerificationDecision(
    val protocolVersion: String = AiProtocolConstants.PROTOCOL_VERSION,
    val taskId: String,
    val sessionId: String,
    val screenshotReference: String,
    val verified: Boolean,
    val observedScreenSummary: String,
    val mismatchReason: String = "",
    val isTaskCompleted: Boolean = false,
    val needsReplan: Boolean = false,
    val nextAction: AiActionEnvelope? = null,
    val assistantMessage: String = "",
    val verificationStatus: OrchestratorVerificationStatus = if (verified) {
        OrchestratorVerificationStatus.SUCCESS
    } else if (needsReplan) {
        OrchestratorVerificationStatus.NEEDS_RETRY
    } else {
        OrchestratorVerificationStatus.FAILED
    }
)

data class AiProviderCapabilities(
    val providerId: String,
    val modelId: String,
    val isOnlineProvider: Boolean,
    val supportsVision: Boolean,
    val supportsStructuredJson: Boolean = true
)

sealed class ProtocolParseResult<out T> {
    data class Valid<T>(val value: T) : ProtocolParseResult<T>()
    data class Malformed(val reason: String, val rawExcerpt: String = "") : ProtocolParseResult<Nothing>()
}

/**
 * Identifies actions with real-world, financial, communication, security, or destructive consequences.
 */
object SensitiveActionClassifier {
    private val sensitiveKeywords = listOf(
        "send message", "send sms", "send whatsapp", "send email", "compose",
        "call ", "dial ", "phone call", "whatsapp call",
        "delete", "remove", "erase", "uninstall", "wipe", "clear data",
        "pay", "payment", "checkout", "purchase", "buy now", "place order",
        "transfer", "upi", "bank", "transaction",
        "grant permission", "allow permission", "change password", "reset password",
        "two-factor", "2fa", "security setting", "delete account", "sign out"
    )

    fun isConsequential(
        envelope: AiActionEnvelope,
        userCommand: String = ""
    ): Boolean {
        if (envelope.requiresConfirmation) return true
        val combined = listOf(
            envelope.target.description,
            envelope.target.selector,
            envelope.text,
            envelope.expectedResult,
            envelope.reason,
            envelope.appName
        ).joinToString(" ").lowercase(Locale.ROOT)

        if (sensitiveKeywords.any { combined.contains(it) }) return true

        // Exact send / call button taps
        val targetLower = envelope.target.description.trim().lowercase(Locale.ROOT)
        if (envelope.action == OrchestratorActionType.TAP &&
            targetLower in setOf("send", "call", "pay", "pay now", "buy", "buy now", "place order", "confirm", "delete", "uninstall", "allow")
        ) {
            return true
        }

        // Fallback check on explicit SMS/Call phrasing when targeting send/dial action
        val cmdLower = userCommand.lowercase(Locale.ROOT)
        if (envelope.action == OrchestratorActionType.TAP &&
            (cmdLower.contains("pay ") || cmdLower.contains("transfer ") || cmdLower.contains("delete ")) &&
            (targetLower.contains("confirm") || targetLower.contains("submit") || targetLower.contains("ok"))
        ) {
            return true
        }
        return false
    }
}

object AiProtocolParser {

    fun extractJsonObjectString(rawResponse: String): String? {
        val trimmed = rawResponse.trim()
        if (trimmed.isEmpty()) return null

        val fenced = Regex("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```", RegexOption.IGNORE_CASE)
            .find(trimmed)?.groupValues?.getOrNull(1)?.trim()
        if (!fenced.isNullOrEmpty()) return fenced

        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1).trim()
        }
        return null
    }

    fun parseActionEnvelope(
        rawJsonOrText: String,
        expectedTaskId: String,
        expectedSessionId: String,
        defaultSequence: Int = 1
    ): ProtocolParseResult<AiActionEnvelope> {
        val jsonStr = extractJsonObjectString(rawJsonOrText)
            ?: return ProtocolParseResult.Malformed("AI response did not contain a valid JSON object.", rawJsonOrText.take(180))

        return try {
            val obj = JSONObject(jsonStr)
            parseEnvelopeObject(obj, expectedTaskId, expectedSessionId, defaultSequence)
        } catch (e: Exception) {
            ProtocolParseResult.Malformed("Malformed AI action JSON: ${e.message}", jsonStr.take(180))
        }
    }

    fun parseCeoPlan(
        rawJsonOrText: String,
        expectedTaskId: String,
        expectedSessionId: String
    ): ProtocolParseResult<CeoTaskPlan> {
        val jsonStr = extractJsonObjectString(rawJsonOrText)
            ?: return ProtocolParseResult.Malformed("AI CEO response missing structured JSON plan.", rawJsonOrText.take(180))

        return try {
            val obj = JSONObject(jsonStr)
            val version = obj.optString("protocol_version", AiProtocolConstants.PROTOCOL_VERSION)
            if (version.isNotBlank() && version != AiProtocolConstants.PROTOCOL_VERSION) {
                return ProtocolParseResult.Malformed("Unsupported protocol_version: $version")
            }

            val taskId = obj.optString("task_id", "").ifBlank { expectedTaskId }
            val sessionId = obj.optString("session_id", "").ifBlank { expectedSessionId }
            if (taskId != expectedTaskId || sessionId != expectedSessionId) {
                return ProtocolParseResult.Malformed("Plan task_id/session_id mismatch.")
            }

            val mode = obj.optString("mode", "").uppercase(Locale.ROOT)
            val overallGoal = obj.optString("overall_goal", "").trim()
            val assistantMsg = obj.optString("assistant_message", obj.optString("reason", "")).trim()

            // Allow conversational response if AI explicitly designates non-automation reply
            if (mode == "CONVERSATION" && obj.optJSONObject("first_action") == null && !obj.has("action")) {
                return ProtocolParseResult.Valid(
                    CeoTaskPlan(
                        protocolVersion = version,
                        taskId = taskId,
                        sessionId = sessionId,
                        overallGoal = overallGoal.ifBlank { "Conversation" },
                        requiresVision = false,
                        steps = emptyList(),
                        firstAction = null,
                        assistantMessage = assistantMsg
                    )
                )
            }

            val stepsArray = obj.optJSONArray("steps")
            val steps = mutableListOf<CeoPlanStep>()
            if (stepsArray != null) {
                for (i in 0 until stepsArray.length()) {
                    val sObj = stepsArray.optJSONObject(i) ?: continue
                    val goal = sObj.optString("goal", "").trim()
                    if (goal.isBlank()) continue
                    val stepNum = sObj.optInt("step_number", i + 1)
                    val depsArr = sObj.optJSONArray("depends_on")
                    val deps = mutableListOf<Int>()
                    if (depsArr != null) {
                        for (d in 0 until depsArr.length()) {
                            val depStep = depsArr.optInt(d, -1)
                            if (depStep > 0) deps.add(depStep)
                        }
                    } else if (stepNum > 1) {
                        deps.add(stepNum - 1)
                    }
                    steps.add(
                        CeoPlanStep(
                            stepNumber = stepNum,
                            goal = goal,
                            expectedOutcome = sObj.optString("expected_outcome", ""),
                            likelyRequiresVision = sObj.optBoolean("requires_vision", true),
                            mayBeConsequential = sObj.optBoolean("may_be_consequential", false),
                            dependsOnStepNumbers = deps
                        )
                    )
                }
            }

            val firstActionObj = obj.optJSONObject("first_action")
                ?: if (obj.has("action")) obj else null

            val firstAction = if (firstActionObj != null &&
                firstActionObj.optString("action", "").trim().let {
                    it.isNotBlank() && !it.equals("null", true) && !it.equals("none", true)
                }
            ) {
                when (val envRes = parseEnvelopeObject(firstActionObj, expectedTaskId, expectedSessionId, 1)) {
                    is ProtocolParseResult.Valid -> envRes.value
                    is ProtocolParseResult.Malformed -> return ProtocolParseResult.Malformed(envRes.reason, envRes.rawExcerpt)
                }
            } else null

            if (steps.isEmpty() && firstAction == null && assistantMsg.isBlank()) {
                return ProtocolParseResult.Malformed("CEO plan contains neither executable steps nor an action.")
            }

            ProtocolParseResult.Valid(
                CeoTaskPlan(
                    protocolVersion = version,
                    taskId = taskId,
                    sessionId = sessionId,
                    overallGoal = overallGoal.ifBlank { firstAction?.expectedResult ?: "Execute user command" },
                    requiresVision = obj.optBoolean("requires_vision", firstAction?.action?.requiresScreenObservation == true),
                    steps = steps,
                    firstAction = firstAction,
                    assistantMessage = assistantMsg
                )
            )
        } catch (e: Exception) {
            ProtocolParseResult.Malformed("Failed parsing CEO plan JSON: ${e.message}", jsonStr.take(180))
        }
    }

    fun parseVerificationDecision(
        rawJsonOrText: String,
        expectedTaskId: String,
        expectedSessionId: String,
        expectedScreenshotRef: String
    ): ProtocolParseResult<AiVerificationDecision> {
        val jsonStr = extractJsonObjectString(rawJsonOrText)
            ?: return ProtocolParseResult.Malformed("AI verification response missing structured JSON.", rawJsonOrText.take(180))

        return try {
            val obj = JSONObject(jsonStr)
            val taskId = obj.optString("task_id", "").ifBlank { expectedTaskId }
            val sessionId = obj.optString("session_id", "").ifBlank { expectedSessionId }
            if (taskId != expectedTaskId || sessionId != expectedSessionId) {
                return ProtocolParseResult.Malformed("Verification task_id/session_id mismatch.")
            }
            if (!obj.has("verified")) {
                return ProtocolParseResult.Malformed("Verification JSON missing mandatory boolean field 'verified'.")
            }

            val verified = obj.getBoolean("verified")
            val isTaskCompleted = obj.optBoolean("is_task_completed", false)
            val needsReplan = obj.optBoolean("needs_replan", !verified)
            val screenshotRef = obj.optString("screenshot_reference", "").ifBlank { expectedScreenshotRef }
            val nextActionObj = obj.optJSONObject("next_action")
            val nextActionRaw = nextActionObj?.optString("action", "")?.trim().orEmpty()
            val shouldParseNextAction = nextActionObj != null &&
                !isTaskCompleted &&
                nextActionRaw.isNotBlank() &&
                !nextActionRaw.equals("null", ignoreCase = true) &&
                !nextActionRaw.equals("none", ignoreCase = true) &&
                !nextActionRaw.equals("done", ignoreCase = true)

            val nextAction = if (shouldParseNextAction && nextActionObj != null) {
                // Inherit task_id, session_id, screenshot_reference if omitted inside nested next_action object
                if (nextActionObj.optString("task_id", "").isBlank()) {
                    nextActionObj.put("task_id", expectedTaskId)
                }
                if (nextActionObj.optString("session_id", "").isBlank()) {
                    nextActionObj.put("session_id", expectedSessionId)
                }
                if (nextActionObj.optString("screenshot_reference", "").isBlank()) {
                    nextActionObj.put("screenshot_reference", screenshotRef)
                }
                when (val envRes = parseEnvelopeObject(nextActionObj, expectedTaskId, expectedSessionId, 1)) {
                    is ProtocolParseResult.Valid -> envRes.value
                    is ProtocolParseResult.Malformed -> return ProtocolParseResult.Malformed(envRes.reason, envRes.rawExcerpt)
                }
            } else null

            val status = OrchestratorVerificationStatus.parse(
                raw = obj.optString("verification_status", obj.optString("status", "")),
                verified = verified,
                isTaskCompleted = isTaskCompleted,
                needsReplan = needsReplan
            )

            ProtocolParseResult.Valid(
                AiVerificationDecision(
                    protocolVersion = obj.optString("protocol_version", AiProtocolConstants.PROTOCOL_VERSION),
                    taskId = taskId,
                    sessionId = sessionId,
                    screenshotReference = screenshotRef,
                    verified = verified,
                    observedScreenSummary = obj.optString("observed_screen_summary", ""),
                    mismatchReason = obj.optString("mismatch_reason", ""),
                    isTaskCompleted = isTaskCompleted,
                    needsReplan = needsReplan,
                    nextAction = nextAction,
                    assistantMessage = obj.optString("assistant_message", ""),
                    verificationStatus = status
                )
            )
        } catch (e: Exception) {
            ProtocolParseResult.Malformed("Malformed AI verification JSON: ${e.message}", jsonStr.take(180))
        }
    }

    private fun parseEnvelopeObject(
        obj: JSONObject,
        expectedTaskId: String,
        expectedSessionId: String,
        defaultSequence: Int
    ): ProtocolParseResult<AiActionEnvelope> {
        val version = obj.optString("protocol_version", AiProtocolConstants.PROTOCOL_VERSION)
        if (version.isNotBlank() && version != AiProtocolConstants.PROTOCOL_VERSION) {
            return ProtocolParseResult.Malformed("Unsupported protocol_version: $version")
        }

        val rawAction = obj.optString("action", "").trim().uppercase(Locale.ROOT)
        val actionStr = if (rawAction.isBlank()) {
            if (obj.optBoolean("is_task_completed", false) || obj.has("verified")) "DONE" else "VERIFY"
        } else {
            rawAction
        }
        val actionType = OrchestratorActionType.fromRawOrAlias(actionStr)
            ?: return ProtocolParseResult.Malformed("Unknown or unsupported AI protocol action: '$actionStr'")

        val taskId = obj.optString("task_id", "").trim()
        val sessionId = obj.optString("session_id", "").trim()
        if (taskId.isBlank() || taskId != expectedTaskId) {
            return ProtocolParseResult.Malformed("Action envelope task_id '$taskId' does not match active task '$expectedTaskId'.")
        }
        if (sessionId.isBlank() || sessionId != expectedSessionId) {
            return ProtocolParseResult.Malformed("Action envelope session_id '$sessionId' does not match active session '$expectedSessionId'.")
        }

        val sequence = obj.optInt("sequence", defaultSequence)
        if (sequence <= 0) {
            return ProtocolParseResult.Malformed("Action envelope 'sequence' must be >= 1.")
        }

        val isTerminalOrMeta = actionType in setOf(
            OrchestratorActionType.DONE,
            OrchestratorActionType.ABORT,
            OrchestratorActionType.ASK_USER,
            OrchestratorActionType.VERIFY
        )

        val actionId = obj.optString("action_id", "").trim().ifBlank {
            if (isTerminalOrMeta) "act_meta_$sequence" else ""
        }
        if (actionId.isBlank()) {
            return ProtocolParseResult.Malformed("Action envelope missing required 'action_id'.")
        }

        if (!obj.has("confidence") || obj.isNull("confidence")) {
            if (!isTerminalOrMeta) {
                return ProtocolParseResult.Malformed("Action envelope missing required 'confidence' score.")
            }
        }
        val confidence = if (obj.has("confidence") && !obj.isNull("confidence")) {
            obj.optDouble("confidence", Double.NaN)
        } else {
            1.0
        }
        if (confidence.isNaN() || confidence < 0.0 || confidence > 1.0) {
            return ProtocolParseResult.Malformed("Action envelope 'confidence' out of bounds [0.0, 1.0]: $confidence")
        }

        val targetObj = obj.optJSONObject("target")
        val coordsObj = targetObj?.optJSONObject("coordinates") ?: obj.optJSONObject("coordinates")
        val coordinates = if (coordsObj != null) {
            val x = coordsObj.optDouble("x", Double.NaN)
            val y = coordsObj.optDouble("y", Double.NaN)
            if (x.isNaN() || y.isNaN() || x < 0.0 || y < 0.0) {
                return ProtocolParseResult.Malformed("Invalid or negative coordinates: ($x, $y)")
            }
            val spaceRaw = coordsObj.optString("space", obj.optString("coordinate_space", "NORMALIZED_0_1"))
            val space = CoordinateSpace.parse(spaceRaw)
                ?: return ProtocolParseResult.Malformed("Unknown coordinate_space '$spaceRaw'")
            if (space == CoordinateSpace.NORMALIZED_0_1 && (x > 1.0 || y > 1.0)) {
                return ProtocolParseResult.Malformed("Normalized coordinates out of range [0.0, 1.0]: ($x, $y)")
            }
            AiCoordinates(x = x, y = y, space = space)
        } else null

        val target = AiActionTarget(
            description = targetObj?.optString("description", "")?.trim()
                ?: obj.optString("target_description", obj.optString("app_name", "")).trim(),
            selector = targetObj?.optString("selector", "")?.trim() ?: obj.optString("selector", "").trim(),
            coordinates = coordinates
        )

        val expectedResult = obj.optString("expected_result", "").trim()
        if (actionType !in setOf(OrchestratorActionType.DONE, OrchestratorActionType.ABORT, OrchestratorActionType.ASK_USER) &&
            expectedResult.isBlank()
        ) {
            return ProtocolParseResult.Malformed("Action '$actionType' must declare non-empty 'expected_result'.")
        }

        val appName = obj.optString("app_name", "").trim()
        if (actionType == OrchestratorActionType.OPEN_APP && appName.isBlank() && target.description.isBlank()) {
            return ProtocolParseResult.Malformed("OPEN_APP action must specify 'app_name' or 'target.description'.")
        }

        val screenshotRef = obj.optString("screenshot_reference", "").trim()
        if (actionType.requiresScreenObservation && screenshotRef.isBlank()) {
            return ProtocolParseResult.Malformed("Screen action '$actionType' missing mandatory 'screenshot_reference'.")
        }

        val expiresAtMs = obj.optLong("expires_at_ms", System.currentTimeMillis() + AiProtocolConstants.DEFAULT_PERMIT_TTL_MS)

        return ProtocolParseResult.Valid(
            AiActionEnvelope(
                protocolVersion = version,
                taskId = taskId,
                sessionId = sessionId,
                actionId = actionId,
                sequence = sequence,
                action = actionType,
                target = target,
                text = obj.optString("text", ""),
                direction = obj.optString("direction", ""),
                appName = appName.ifBlank { target.description },
                screenshotReference = screenshotRef,
                screenWidth = obj.optInt("screen_width", 0),
                screenHeight = obj.optInt("screen_height", 0),
                orientation = obj.optInt("orientation", 0),
                confidence = confidence,
                expectedResult = expectedResult,
                verificationRequired = obj.optBoolean("verification_required", true),
                requiresConfirmation = obj.optBoolean("requires_confirmation", false),
                reason = obj.optString("reason", ""),
                expiresAtMs = expiresAtMs,
                spokenMessage = obj.optString("spoken_message", "")
            )
        )
    }
}
