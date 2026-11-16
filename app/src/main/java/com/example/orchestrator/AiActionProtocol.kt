package com.example.orchestrator

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * Versioned AI <-> Execution Protocol (v1.0).
 * Every device automation action MUST originate from the selected online AI provider
 * using this canonical structured schema and pass validation & normalization before execution.
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

/**
 * Standardized diagnostic categories for the complete AI action lifecycle (Section 10).
 */
enum class ActionDiagnosticCategory {
    AI_GENERATION_ERROR,
    JSON_PARSE_ERROR,
    UNSUPPORTED_ACTION,
    SCHEMA_VALIDATION_ERROR,
    COORDINATE_NORMALIZATION_ERROR,
    APP_RESOLUTION_ERROR,
    EXECUTOR_ERROR,
    EXECUTION_TIMEOUT,
    VERIFICATION_FAILED
}

data class ActionLifecycleDiagnosticRecord(
    val timestampMs: Long = System.currentTimeMillis(),
    val taskId: String = "",
    val sessionId: String = "",
    val protocolVersion: String = AiProtocolConstants.PROTOCOL_VERSION,
    val stage: String,
    val category: ActionDiagnosticCategory? = null,
    val rawAiResponseExcerpt: String = "",
    val parsedActionSummary: String = "",
    val validationResult: String = "",
    val normalizedActionSummary: String = "",
    val selectedExecutor: String = "",
    val executionResult: String = "",
    val verificationResult: String = "",
    val failureStage: String = "",
    val errorMessage: String = ""
)

/**
 * Centralized diagnostic logger for the complete AI action lifecycle (Section 10).
 * Redacts API keys and credentials while preserving actionable debugging telemetry.
 */
object AiActionDiagnostics {
    private const val TAG = "AiActionDiagnostics"
    private const val MAX_RECORDS = 120

    private val secretPattern = Regex(
        """(AIza[0-9A-Za-z_-]{10,}|sk-[0-9A-Za-z_-]{10,}|hf_[0-9A-Za-z_-]{10,}|Bearer\s+[0-9A-Za-z._-]+)""",
        RegexOption.IGNORE_CASE
    )

    private val _records = MutableStateFlow<List<ActionLifecycleDiagnosticRecord>>(emptyList())
    val records: StateFlow<List<ActionLifecycleDiagnosticRecord>> = _records.asStateFlow()

    val lastDiagnostic: ActionLifecycleDiagnosticRecord?
        get() = _records.value.lastOrNull()

    fun redactSecrets(input: String): String {
        if (input.isBlank()) return ""
        return secretPattern.replace(input, "[REDACTED_SECRET]")
    }

    @Synchronized
    fun record(
        stage: String,
        taskId: String = "",
        sessionId: String = "",
        category: ActionDiagnosticCategory? = null,
        rawAiResponse: String = "",
        parsedAction: String = "",
        validationResult: String = "",
        normalizedAction: String = "",
        normalizedActionSummary: String = "",
        selectedExecutor: String = "",
        executionResult: String = "",
        verificationResult: String = "",
        failureStage: String = "",
        errorMessage: String = "",
        reason: String = ""
    ): ActionLifecycleDiagnosticRecord {
        val effectiveNorm = normalizedAction.ifBlank { normalizedActionSummary }
        val effectiveErr = errorMessage.ifBlank { reason }
        val entry = ActionLifecycleDiagnosticRecord(
            taskId = taskId,
            sessionId = sessionId,
            stage = stage,
            category = category,
            rawAiResponseExcerpt = redactSecrets(rawAiResponse.take(280)),
            parsedActionSummary = redactSecrets(parsedAction.take(200)),
            validationResult = redactSecrets(validationResult.take(200)),
            normalizedActionSummary = redactSecrets(effectiveNorm.take(200)),
            selectedExecutor = selectedExecutor,
            executionResult = redactSecrets(executionResult.take(200)),
            verificationResult = redactSecrets(verificationResult.take(200)),
            failureStage = failureStage,
            errorMessage = redactSecrets(effectiveErr.take(280))
        )
        val updated = (_records.value + entry).takeLast(MAX_RECORDS)
        _records.value = updated
        if (category != null || effectiveErr.isNotBlank()) {
            Log.w(TAG, "[$stage] category=${category?.name ?: "INFO"} task=$taskId err=${entry.errorMessage}")
        } else {
            Log.d(TAG, "[$stage] task=$taskId action=${entry.normalizedActionSummary.ifBlank { entry.parsedActionSummary }}")
        }
        return entry
    }

    fun recentRecords(): List<ActionLifecycleDiagnosticRecord> = _records.value

    @Synchronized
    fun clear() {
        _records.value = emptyList()
    }

    @Synchronized
    fun clearForTesting() {
        _records.value = emptyList()
    }
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
            val cleaned = raw.trim().uppercase(Locale.ROOT)
                .removePrefix("ACTION_")
                .removePrefix("UI_")
            return when (cleaned) {
                "OPEN_APP", "LAUNCH_APP", "START_APP", "OPEN", "LAUNCH", "RUN_APP", "OPEN_APPLICATION" -> OPEN_APP
                "SPLIT_SCREEN", "MULTI_WINDOW", "ENTER_SPLIT_SCREEN", "SIDE_BY_SIDE" -> SPLIT_SCREEN
                "TAP", "CLICK", "PRESS", "SELECT", "TOUCH", "TAP_ELEMENT", "CLICK_ELEMENT", "FIND_AND_CLICK" -> TAP
                "DOUBLE_TAP", "DOUBLE_CLICK", "DBL_CLICK", "DOUBLE_PRESS" -> DOUBLE_TAP
                "LONG_PRESS", "LONG_CLICK", "HOLD", "PRESS_AND_HOLD", "TOUCH_AND_HOLD" -> LONG_PRESS
                "TYPE", "INPUT", "ENTER_TEXT", "SET_TEXT", "TYPE_TEXT", "WRITE", "INPUT_TEXT", "SEARCH_INPUT" -> TYPE
                "SWIPE", "FLING", "DRAG", "SWIPE_SCREEN" -> SWIPE
                "SCROLL", "SCROLL_DOWN", "SCROLL_UP", "SCROLL_LEFT", "SCROLL_RIGHT", "PAGE_DOWN", "PAGE_UP" -> SCROLL
                "BACK", "NAVIGATE_BACK", "GO_BACK", "PRESS_BACK" -> BACK
                "HOME", "GO_HOME", "PRESS_HOME", "NAVIGATE_HOME" -> HOME
                "RECENTS", "RECENT_APPS", "OVERVIEW", "APP_SWITCHER" -> RECENTS
                "WAIT", "SLEEP", "DELAY", "PAUSE" -> WAIT
                "VERIFY", "CHECK", "OBSERVE", "INSPECT" -> VERIFY
                "SEARCH_WEB", "WEB_SEARCH", "GOOGLE_SEARCH", "OPEN_URL", "BROWSE", "OPEN_WEBSITE" -> SEARCH_WEB
                "REQUEST_CONFIRMATION", "CONFIRM", "ASK_CONFIRMATION" -> REQUEST_CONFIRMATION
                "ASK_USER", "CLARIFY", "NEEDS_USER_INPUT", "PROMPT_USER" -> ASK_USER
                "DONE", "COMPLETE", "COMPLETED", "FINISH", "FINISHED", "SUCCESS", "TASK_COMPLETE" -> DONE
                "ABORT", "CANCEL", "STOP", "FAIL", "FAILED", "ERROR", "HALT" -> ABORT
                else -> try {
                    valueOf(cleaned)
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
    NORMALIZED_1000,
    CURRENT_SCREENSHOT_PIXELS,
    DISPLAY_PIXELS;

    companion object {
        fun parse(raw: String?): CoordinateSpace? {
            return when (raw?.trim()?.uppercase(Locale.ROOT)) {
                "NORMALIZED_0_1", "NORMALIZED", "REL_0_1", "RELATIVE", "FRACTION", "UNIT", "0_1" -> NORMALIZED_0_1
                "NORMALIZED_1000", "GEMINI_1000", "SCALE_1000", "1000", "REL_1000", "0_1000" -> NORMALIZED_1000
                "CURRENT_SCREENSHOT_PIXELS", "SCREENSHOT_PIXELS", "IMAGE_PIXELS", "BITMAP_PIXELS", "SCREENSHOT" -> CURRENT_SCREENSHOT_PIXELS
                "DISPLAY_PIXELS", "PHYSICAL_PIXELS", "SCREEN_PIXELS", "ABSOLUTE", "ABSOLUTE_PIXELS",
                "ABS", "PIXELS", "PIXEL", "PX", "SCREEN", "DEVICE_PIXELS" -> DISPLAY_PIXELS
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
    val spokenMessage: String = "",
    val waitMs: Long = 1200L
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
    val assistantMessage: String = "",
    val plannedActions: List<AiActionEnvelope> = listOfNotNull(firstAction)
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
    data class Malformed(
        val reason: String,
        val rawExcerpt: String = "",
        val category: ActionDiagnosticCategory = ActionDiagnosticCategory.SCHEMA_VALIDATION_ERROR
    ) : ProtocolParseResult<Nothing>()
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

        val targetLower = envelope.target.description.trim().lowercase(Locale.ROOT)
        if (envelope.action == OrchestratorActionType.TAP &&
            targetLower in setOf("send", "call", "pay", "pay now", "buy", "buy now", "place order", "confirm", "delete", "uninstall", "allow")
        ) {
            return true
        }

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

    /**
     * Extracts a JSON object (or converts a top-level JSON array of actions into a wrapper JSON object)
     * from a raw model response that may contain markdown code fences or surrounding commentary.
     */
    fun extractJsonObjectString(rawResponse: String): String? {
        val trimmed = rawResponse.trim()
        if (trimmed.isEmpty()) return null

        val fencedObj = Regex("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```", RegexOption.IGNORE_CASE)
            .find(trimmed)?.groupValues?.getOrNull(1)?.trim()
        if (!fencedObj.isNullOrEmpty()) return fencedObj

        val fencedArr = Regex("```(?:json)?\\s*(\\[[\\s\\S]*?\\])\\s*```", RegexOption.IGNORE_CASE)
            .find(trimmed)?.groupValues?.getOrNull(1)?.trim()
        if (!fencedArr.isNullOrEmpty()) {
            return "{\"actions\":$fencedArr}"
        }

        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        val firstBracket = trimmed.indexOf('[')
        val lastBracket = trimmed.lastIndexOf(']')

        if (firstBracket >= 0 && lastBracket > firstBracket && (firstBrace < 0 || firstBracket < firstBrace)) {
            val arrCandidate = trimmed.substring(firstBracket, lastBracket + 1).trim()
            return "{\"actions\":$arrCandidate}"
        }

        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1).trim()
        }
        return null
    }

    /**
     * Case-insensitive, null-safe string lookup on [JSONObject].
     * Never returns the literal string `"null"` when a field is `JSONObject.NULL` or null.
     */
    private fun optCleanStringCI(obj: JSONObject, vararg keys: String): String {
        for (reqKey in keys) {
            if (obj.has(reqKey) && !obj.isNull(reqKey)) {
                val v = obj.optString(reqKey, "").trim()
                if (v.isNotEmpty() && !v.equals("null", ignoreCase = true)) return v
            }
        }
        val names = obj.names() ?: return ""
        for (i in 0 until names.length()) {
            val actualKey = names.optString(i, "")
            if (keys.any { it.equals(actualKey, ignoreCase = true) } && !obj.isNull(actualKey)) {
                val v = obj.optString(actualKey, "").trim()
                if (v.isNotEmpty() && !v.equals("null", ignoreCase = true)) return v
            }
        }
        return ""
    }

    private fun hasKeyCI(obj: JSONObject, vararg keys: String): Boolean {
        for (k in keys) {
            if (obj.has(k) && !obj.isNull(k)) return true
        }
        val names = obj.names() ?: return false
        for (i in 0 until names.length()) {
            val actualKey = names.optString(i, "")
            if (keys.any { it.equals(actualKey, ignoreCase = true) } && !obj.isNull(actualKey)) {
                return true
            }
        }
        return false
    }

    private fun optObjectCI(obj: JSONObject, vararg keys: String): JSONObject? {
        for (k in keys) {
            obj.optJSONObject(k)?.let { return it }
            if (obj.has(k) && !obj.isNull(k)) {
                val maybeStr = obj.optString(k, "").trim()
                if (maybeStr.startsWith("{") && maybeStr.endsWith("}")) {
                    try {
                        return JSONObject(maybeStr)
                    } catch (_: Exception) {}
                }
            }
        }
        val names = obj.names() ?: return null
        for (i in 0 until names.length()) {
            val actualKey = names.optString(i, "")
            if (keys.any { it.equals(actualKey, ignoreCase = true) }) {
                obj.optJSONObject(actualKey)?.let { return it }
                if (!obj.isNull(actualKey)) {
                    val maybeStr = obj.optString(actualKey, "").trim()
                    if (maybeStr.startsWith("{") && maybeStr.endsWith("}")) {
                        try {
                            return JSONObject(maybeStr)
                        } catch (_: Exception) {}
                    }
                }
            }
        }
        return null
    }

    private fun optArrayCI(obj: JSONObject, vararg keys: String): JSONArray? {
        for (k in keys) {
            obj.optJSONArray(k)?.let { return it }
        }
        val names = obj.names() ?: return null
        for (i in 0 until names.length()) {
            val actualKey = names.optString(i, "")
            if (keys.any { it.equals(actualKey, ignoreCase = true) }) {
                obj.optJSONArray(actualKey)?.let { return it }
            }
        }
        return null
    }

    private fun optDoubleCI(obj: JSONObject, defaultVal: Double, vararg keys: String): Double {
        for (k in keys) {
            if (obj.has(k) && !obj.isNull(k)) {
                val d = obj.optDouble(k, Double.NaN)
                if (!d.isNaN()) return d
            }
        }
        val names = obj.names() ?: return defaultVal
        for (i in 0 until names.length()) {
            val actualKey = names.optString(i, "")
            if (keys.any { it.equals(actualKey, ignoreCase = true) } && !obj.isNull(actualKey)) {
                val d = obj.optDouble(actualKey, Double.NaN)
                if (!d.isNaN()) return d
            }
        }
        return defaultVal
    }

    private fun optIntCI(obj: JSONObject, defaultVal: Int, vararg keys: String): Int {
        for (k in keys) {
            if (obj.has(k) && !obj.isNull(k)) {
                return obj.optInt(k, defaultVal)
            }
        }
        val names = obj.names() ?: return defaultVal
        for (i in 0 until names.length()) {
            val actualKey = names.optString(i, "")
            if (keys.any { it.equals(actualKey, ignoreCase = true) } && !obj.isNull(actualKey)) {
                return obj.optInt(actualKey, defaultVal)
            }
        }
        return defaultVal
    }

    private fun optLongCI(obj: JSONObject, defaultVal: Long, vararg keys: String): Long {
        for (k in keys) {
            if (obj.has(k) && !obj.isNull(k)) {
                return obj.optLong(k, defaultVal)
            }
        }
        val names = obj.names() ?: return defaultVal
        for (i in 0 until names.length()) {
            val actualKey = names.optString(i, "")
            if (keys.any { it.equals(actualKey, ignoreCase = true) } && !obj.isNull(actualKey)) {
                return obj.optLong(actualKey, defaultVal)
            }
        }
        return defaultVal
    }

    private fun optBooleanCI(obj: JSONObject, defaultVal: Boolean, vararg keys: String): Boolean {
        for (k in keys) {
            if (obj.has(k) && !obj.isNull(k)) {
                return obj.optBoolean(k, defaultVal)
            }
        }
        val names = obj.names() ?: return defaultVal
        for (i in 0 until names.length()) {
            val actualKey = names.optString(i, "")
            if (keys.any { it.equals(actualKey, ignoreCase = true) } && !obj.isNull(actualKey)) {
                return obj.optBoolean(actualKey, defaultVal)
            }
        }
        return defaultVal
    }

    /**
     * Unwraps an action JSONObject if the model nested a serialized JSON object inside `"action"` / `"TYPE"`.
     * Example: `{"action": "{\"TYPE\":\"CLICK\",\"TARGET_NAME\":\"CHROME\",\"COORDINATES\":[614,825],\"TEXT_INPUT\":null}"}`
     */
    private fun unwrapNestedActionJsonIfNeeded(rawObj: JSONObject): JSONObject {
        val candidateActionVal = optCleanStringCI(rawObj, "action", "type", "action_type", "command")
        if (candidateActionVal.startsWith("{") && candidateActionVal.endsWith("}")) {
            try {
                val inner = JSONObject(candidateActionVal)
                val merged = JSONObject(rawObj.toString())
                merged.remove("action")
                val innerNames = inner.names()
                if (innerNames != null) {
                    for (i in 0 until innerNames.length()) {
                        val k = innerNames.getString(i)
                        merged.put(k, inner.get(k))
                    }
                }
                return merged
            } catch (_: Exception) {
                return rawObj
            }
        }
        return rawObj
    }

    private fun hasNonEmptyActionField(obj: JSONObject?): Boolean {
        if (obj == null) return false
        val unwrapped = unwrapNestedActionJsonIfNeeded(obj)
        val raw = optCleanStringCI(unwrapped, "action", "type", "action_type", "command")
        return raw.isNotBlank() &&
            !raw.equals("null", ignoreCase = true) &&
            !raw.equals("none", ignoreCase = true)
    }

    fun parseActionEnvelope(
        rawJsonOrText: String,
        expectedTaskId: String,
        expectedSessionId: String,
        defaultSequence: Int = 1,
        defaultScreenshotRef: String = ""
    ): ProtocolParseResult<AiActionEnvelope> {
        val jsonStr = extractJsonObjectString(rawJsonOrText)
        if (jsonStr == null) {
            val reason = "AI response did not contain a valid JSON object."
            AiActionDiagnostics.record(
                stage = "PROTOCOL_PARSING",
                taskId = expectedTaskId,
                sessionId = expectedSessionId,
                category = ActionDiagnosticCategory.JSON_PARSE_ERROR,
                rawAiResponse = rawJsonOrText,
                failureStage = "extractJsonObjectString",
                errorMessage = reason
            )
            return ProtocolParseResult.Malformed(
                reason = reason,
                rawExcerpt = rawJsonOrText.take(180),
                category = ActionDiagnosticCategory.JSON_PARSE_ERROR
            )
        }

        return try {
            val rootObj = JSONObject(jsonStr)
            val actionObj = optObjectCI(rootObj, "first_action", "next_action")
                ?: optArrayCI(rootObj, "actions")?.optJSONObject(0)
                ?: rootObj
            parseEnvelopeObject(
                rawObj = actionObj,
                expectedTaskId = expectedTaskId,
                expectedSessionId = expectedSessionId,
                defaultSequence = defaultSequence,
                defaultScreenshotRef = defaultScreenshotRef
            )
        } catch (e: Exception) {
            val reason = "Malformed AI action JSON: ${e.message}"
            AiActionDiagnostics.record(
                stage = "PROTOCOL_PARSING",
                taskId = expectedTaskId,
                sessionId = expectedSessionId,
                category = ActionDiagnosticCategory.JSON_PARSE_ERROR,
                rawAiResponse = jsonStr,
                failureStage = "parseActionEnvelope",
                errorMessage = reason
            )
            ProtocolParseResult.Malformed(
                reason = reason,
                rawExcerpt = jsonStr.take(180),
                category = ActionDiagnosticCategory.JSON_PARSE_ERROR
            )
        }
    }

    fun parseCeoPlan(
        rawJsonOrText: String,
        expectedTaskId: String,
        expectedSessionId: String,
        defaultScreenshotRef: String = ""
    ): ProtocolParseResult<CeoTaskPlan> {
        val jsonStr = extractJsonObjectString(rawJsonOrText)
        if (jsonStr == null) {
            val reason = "AI CEO response missing structured JSON plan."
            AiActionDiagnostics.record(
                stage = "PROTOCOL_PARSING",
                taskId = expectedTaskId,
                sessionId = expectedSessionId,
                category = ActionDiagnosticCategory.JSON_PARSE_ERROR,
                rawAiResponse = rawJsonOrText,
                failureStage = "parseCeoPlan",
                errorMessage = reason
            )
            return ProtocolParseResult.Malformed(
                reason = reason,
                rawExcerpt = rawJsonOrText.take(180),
                category = ActionDiagnosticCategory.JSON_PARSE_ERROR
            )
        }

        return try {
            val obj = JSONObject(jsonStr)
            val version = optCleanStringCI(obj, "protocol_version").ifBlank { AiProtocolConstants.PROTOCOL_VERSION }
            if (version != AiProtocolConstants.PROTOCOL_VERSION && version != "1" && version != "1.0.0") {
                val reason = "Unsupported protocol_version: $version"
                AiActionDiagnostics.record(
                    stage = "SCHEMA_VALIDATION",
                    taskId = expectedTaskId,
                    sessionId = expectedSessionId,
                    category = ActionDiagnosticCategory.SCHEMA_VALIDATION_ERROR,
                    rawAiResponse = jsonStr,
                    failureStage = "parseCeoPlan.version",
                    errorMessage = reason
                )
                return ProtocolParseResult.Malformed(reason, jsonStr.take(180), ActionDiagnosticCategory.SCHEMA_VALIDATION_ERROR)
            }

            val taskId = optCleanStringCI(obj, "task_id").ifBlank { expectedTaskId }
            val sessionId = optCleanStringCI(obj, "session_id").ifBlank { expectedSessionId }
            if (taskId != expectedTaskId || sessionId != expectedSessionId) {
                val reason = "Plan task_id/session_id mismatch."
                AiActionDiagnostics.record(
                    stage = "SCHEMA_VALIDATION",
                    taskId = expectedTaskId,
                    sessionId = expectedSessionId,
                    category = ActionDiagnosticCategory.SCHEMA_VALIDATION_ERROR,
                    rawAiResponse = jsonStr,
                    failureStage = "parseCeoPlan.sessionBinding",
                    errorMessage = reason
                )
                return ProtocolParseResult.Malformed(reason, jsonStr.take(180), ActionDiagnosticCategory.SCHEMA_VALIDATION_ERROR)
            }

            val mode = optCleanStringCI(obj, "mode").uppercase(Locale.ROOT)
            val overallGoal = optCleanStringCI(obj, "overall_goal", "goal")
            val assistantMsg = optCleanStringCI(obj, "assistant_message", "spoken_message", "reason")
            val topLevelScreenshotRef = optCleanStringCI(obj, "screenshot_reference").ifBlank { defaultScreenshotRef }

            val firstActionCandidate = optObjectCI(obj, "first_action", "action_envelope")
            val actionsArray = optArrayCI(obj, "actions", "action_list")
            val hasTopLevelAction = hasNonEmptyActionField(obj)

            // Allow conversational response if AI explicitly designates non-automation reply
            if (mode == "CONVERSATION" && firstActionCandidate == null && actionsArray == null && !hasTopLevelAction) {
                return ProtocolParseResult.Valid(
                    CeoTaskPlan(
                        protocolVersion = AiProtocolConstants.PROTOCOL_VERSION,
                        taskId = taskId,
                        sessionId = sessionId,
                        overallGoal = overallGoal.ifBlank { "Conversation" },
                        requiresVision = false,
                        steps = emptyList(),
                        firstAction = null,
                        assistantMessage = assistantMsg,
                        plannedActions = emptyList()
                    )
                )
            }

            val stepsArray = optArrayCI(obj, "steps", "plan")
            val steps = mutableListOf<CeoPlanStep>()
            if (stepsArray != null) {
                for (i in 0 until stepsArray.length()) {
                    val sObj = stepsArray.optJSONObject(i) ?: continue
                    val goal = optCleanStringCI(sObj, "goal", "description", "title", "action")
                    if (goal.isBlank()) continue
                    val stepNum = optIntCI(sObj, i + 1, "step_number", "step", "sequence")
                    val depsArr = optArrayCI(sObj, "depends_on", "dependencies")
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
                            expectedOutcome = optCleanStringCI(sObj, "expected_outcome", "expected_result"),
                            likelyRequiresVision = optBooleanCI(sObj, true, "requires_vision"),
                            mayBeConsequential = optBooleanCI(sObj, false, "may_be_consequential", "requires_confirmation"),
                            dependsOnStepNumbers = deps
                        )
                    )
                }
            }

            // Parse multi-action array if provided (`"actions": [...]`)
            val plannedActions = mutableListOf<AiActionEnvelope>()
            if (actionsArray != null && actionsArray.length() > 0) {
                for (i in 0 until actionsArray.length()) {
                    val actObj = actionsArray.optJSONObject(i) ?: continue
                    if (!hasNonEmptyActionField(actObj)) continue
                    when (
                        val envRes = parseEnvelopeObject(
                            rawObj = actObj,
                            expectedTaskId = expectedTaskId,
                            expectedSessionId = expectedSessionId,
                            defaultSequence = i + 1,
                            defaultScreenshotRef = topLevelScreenshotRef
                        )
                    ) {
                        is ProtocolParseResult.Valid -> plannedActions.add(envRes.value)
                        is ProtocolParseResult.Malformed -> return envRes
                    }
                }
            }

            val resolvedFirstActionObj = firstActionCandidate ?: if (hasTopLevelAction) obj else null
            val firstAction = if (plannedActions.isNotEmpty()) {
                plannedActions.first()
            } else if (resolvedFirstActionObj != null && hasNonEmptyActionField(resolvedFirstActionObj)) {
                when (
                    val envRes = parseEnvelopeObject(
                        rawObj = resolvedFirstActionObj,
                        expectedTaskId = expectedTaskId,
                        expectedSessionId = expectedSessionId,
                        defaultSequence = 1,
                        defaultScreenshotRef = topLevelScreenshotRef
                    )
                ) {
                    is ProtocolParseResult.Valid -> {
                        plannedActions.add(envRes.value)
                        envRes.value
                    }
                    is ProtocolParseResult.Malformed -> return envRes
                }
            } else null

            if (steps.isEmpty() && firstAction == null && assistantMsg.isBlank()) {
                val reason = "CEO plan contains neither executable steps nor an action."
                AiActionDiagnostics.record(
                    stage = "SCHEMA_VALIDATION",
                    taskId = expectedTaskId,
                    sessionId = expectedSessionId,
                    category = ActionDiagnosticCategory.SCHEMA_VALIDATION_ERROR,
                    rawAiResponse = jsonStr,
                    failureStage = "parseCeoPlan.empty",
                    errorMessage = reason
                )
                return ProtocolParseResult.Malformed(reason, jsonStr.take(180), ActionDiagnosticCategory.SCHEMA_VALIDATION_ERROR)
            }

            // If plannedActions were provided without explicit steps, synthesize CeoPlanSteps so dependency tracking stays aligned
            val finalSteps = if (steps.isNotEmpty()) {
                steps
            } else {
                plannedActions.mapIndexed { idx, act ->
                    CeoPlanStep(
                        stepNumber = idx + 1,
                        goal = "${act.action.name} ${act.target.description.ifBlank { act.appName }}".trim(),
                        expectedOutcome = act.expectedResult,
                        likelyRequiresVision = act.action.requiresScreenObservation,
                        mayBeConsequential = act.requiresConfirmation
                    )
                }
            }

            val plan = CeoTaskPlan(
                protocolVersion = AiProtocolConstants.PROTOCOL_VERSION,
                taskId = taskId,
                sessionId = sessionId,
                overallGoal = overallGoal.ifBlank { firstAction?.expectedResult ?: "Execute user command" },
                requiresVision = optBooleanCI(obj, firstAction?.action?.requiresScreenObservation == true, "requires_vision"),
                steps = finalSteps,
                firstAction = firstAction,
                assistantMessage = assistantMsg,
                plannedActions = plannedActions
            )

            AiActionDiagnostics.record(
                stage = "PROTOCOL_PARSING",
                taskId = taskId,
                sessionId = sessionId,
                rawAiResponse = jsonStr,
                parsedAction = firstAction?.action?.name ?: "CONVERSATION",
                validationResult = "VALID_CEO_PLAN",
                normalizedAction = firstAction?.let { "${it.action.name}(target='${it.target.description}', app='${it.appName}', text='${it.text}')" } ?: "NONE"
            )
            ProtocolParseResult.Valid(plan)
        } catch (e: Exception) {
            val reason = "Failed parsing CEO plan JSON: ${e.message}"
            AiActionDiagnostics.record(
                stage = "PROTOCOL_PARSING",
                taskId = expectedTaskId,
                sessionId = expectedSessionId,
                category = ActionDiagnosticCategory.JSON_PARSE_ERROR,
                rawAiResponse = jsonStr,
                failureStage = "parseCeoPlan.exception",
                errorMessage = reason
            )
            ProtocolParseResult.Malformed(reason, jsonStr.take(180), ActionDiagnosticCategory.JSON_PARSE_ERROR)
        }
    }

    fun parseVerificationDecision(
        rawJsonOrText: String,
        expectedTaskId: String,
        expectedSessionId: String,
        expectedScreenshotRef: String,
        expectedNextSequence: Int = 2
    ): ProtocolParseResult<AiVerificationDecision> {
        val jsonStr = extractJsonObjectString(rawJsonOrText)
        if (jsonStr == null) {
            val reason = "AI verification response missing structured JSON."
            AiActionDiagnostics.record(
                stage = "VERIFICATION",
                taskId = expectedTaskId,
                sessionId = expectedSessionId,
                category = ActionDiagnosticCategory.JSON_PARSE_ERROR,
                rawAiResponse = rawJsonOrText,
                failureStage = "parseVerificationDecision",
                errorMessage = reason
            )
            return ProtocolParseResult.Malformed(reason, rawJsonOrText.take(180), ActionDiagnosticCategory.JSON_PARSE_ERROR)
        }

        return try {
            val obj = JSONObject(jsonStr)
            val taskId = optCleanStringCI(obj, "task_id").ifBlank { expectedTaskId }
            val sessionId = optCleanStringCI(obj, "session_id").ifBlank { expectedSessionId }
            if (taskId != expectedTaskId || sessionId != expectedSessionId) {
                return ProtocolParseResult.Malformed(
                    "Verification task_id/session_id mismatch.",
                    jsonStr.take(180),
                    ActionDiagnosticCategory.SCHEMA_VALIDATION_ERROR
                )
            }
            if (!hasKeyCI(obj, "verified")) {
                return ProtocolParseResult.Malformed(
                    "Verification JSON missing mandatory boolean field 'verified'.",
                    jsonStr.take(180),
                    ActionDiagnosticCategory.SCHEMA_VALIDATION_ERROR
                )
            }

            val verified = optBooleanCI(obj, false, "verified")
            val isTaskCompleted = optBooleanCI(obj, false, "is_task_completed", "task_completed", "completed")
            val needsReplan = optBooleanCI(obj, !verified, "needs_replan")
            val screenshotRef = optCleanStringCI(obj, "screenshot_reference", "new_screenshot_reference")
                .ifBlank { expectedScreenshotRef }

            val nextActionObj = optObjectCI(obj, "next_action")
            val unwrappedNextObj = nextActionObj?.let { unwrapNestedActionJsonIfNeeded(it) }
            val nextActionRaw = unwrappedNextObj?.let {
                optCleanStringCI(it, "action", "type", "action_type", "command")
            }.orEmpty()

            val shouldParseNextAction = unwrappedNextObj != null &&
                !isTaskCompleted &&
                nextActionRaw.isNotBlank() &&
                !nextActionRaw.equals("null", ignoreCase = true) &&
                !nextActionRaw.equals("none", ignoreCase = true) &&
                !nextActionRaw.equals("done", ignoreCase = true)

            val nextAction = if (shouldParseNextAction && unwrappedNextObj != null) {
                when (
                    val envRes = parseEnvelopeObject(
                        rawObj = unwrappedNextObj,
                        expectedTaskId = expectedTaskId,
                        expectedSessionId = expectedSessionId,
                        defaultSequence = expectedNextSequence,
                        defaultScreenshotRef = screenshotRef,
                        minSequenceFloor = expectedNextSequence
                    )
                ) {
                    is ProtocolParseResult.Valid -> envRes.value
                    is ProtocolParseResult.Malformed -> return envRes
                }
            } else null

            val status = OrchestratorVerificationStatus.parse(
                raw = optCleanStringCI(obj, "verification_status", "status"),
                verified = verified,
                isTaskCompleted = isTaskCompleted,
                needsReplan = needsReplan
            )

            ProtocolParseResult.Valid(
                AiVerificationDecision(
                    protocolVersion = optCleanStringCI(obj, "protocol_version").ifBlank { AiProtocolConstants.PROTOCOL_VERSION },
                    taskId = taskId,
                    sessionId = sessionId,
                    screenshotReference = screenshotRef,
                    verified = verified,
                    observedScreenSummary = optCleanStringCI(obj, "observed_screen_summary", "screen_summary", "summary"),
                    mismatchReason = optCleanStringCI(obj, "mismatch_reason", "error", "reason"),
                    isTaskCompleted = isTaskCompleted,
                    needsReplan = needsReplan,
                    nextAction = nextAction,
                    assistantMessage = optCleanStringCI(obj, "assistant_message", "spoken_message", "message"),
                    verificationStatus = status
                )
            )
        } catch (e: Exception) {
            val reason = "Malformed AI verification JSON: ${e.message}"
            AiActionDiagnostics.record(
                stage = "VERIFICATION",
                taskId = expectedTaskId,
                sessionId = expectedSessionId,
                category = ActionDiagnosticCategory.JSON_PARSE_ERROR,
                rawAiResponse = jsonStr,
                failureStage = "parseVerificationDecision.exception",
                errorMessage = reason
            )
            ProtocolParseResult.Malformed(reason, jsonStr.take(180), ActionDiagnosticCategory.JSON_PARSE_ERROR)
        }
    }

    private fun parseEnvelopeObject(
        rawObj: JSONObject,
        expectedTaskId: String,
        expectedSessionId: String,
        defaultSequence: Int,
        defaultScreenshotRef: String = "",
        minSequenceFloor: Int = 1
    ): ProtocolParseResult<AiActionEnvelope> {
        val obj = unwrapNestedActionJsonIfNeeded(rawObj)

        val version = optCleanStringCI(obj, "protocol_version").ifBlank { AiProtocolConstants.PROTOCOL_VERSION }
        if (version != AiProtocolConstants.PROTOCOL_VERSION && version != "1" && version != "1.0.0") {
            return ProtocolParseResult.Malformed(
                "Unsupported protocol_version: $version",
                obj.toString().take(180),
                ActionDiagnosticCategory.SCHEMA_VALIDATION_ERROR
            )
        }

        // Resolve action type across canonical and legacy/uppercase keys ("action", "ACTION", "type", "TYPE", "action_type")
        val rawAction = optCleanStringCI(obj, "action", "type", "action_type", "command").uppercase(Locale.ROOT)
        val actionStr = if (rawAction.isBlank()) {
            if (optBooleanCI(obj, false, "is_task_completed") || hasKeyCI(obj, "verified")) {
                "DONE"
            } else {
                ""
            }
        } else {
            rawAction
        }

        if (actionStr.isBlank()) {
            val reason = "Missing or empty AI protocol action field ('action' / 'TYPE')."
            AiActionDiagnostics.record(
                stage = "SCHEMA_VALIDATION",
                taskId = expectedTaskId,
                sessionId = expectedSessionId,
                category = ActionDiagnosticCategory.UNSUPPORTED_ACTION,
                rawAiResponse = obj.toString(),
                failureStage = "parseEnvelopeObject.action",
                errorMessage = reason
            )
            return ProtocolParseResult.Malformed(
                reason = "Unknown or unsupported AI protocol action: ''",
                rawExcerpt = obj.toString().take(180),
                category = ActionDiagnosticCategory.UNSUPPORTED_ACTION
            )
        }

        val actionType = OrchestratorActionType.fromRawOrAlias(actionStr)
        if (actionType == null) {
            val reason = "Unknown or unsupported AI protocol action: '$actionStr'"
            AiActionDiagnostics.record(
                stage = "SCHEMA_VALIDATION",
                taskId = expectedTaskId,
                sessionId = expectedSessionId,
                category = ActionDiagnosticCategory.UNSUPPORTED_ACTION,
                rawAiResponse = obj.toString(),
                failureStage = "parseEnvelopeObject.actionType",
                errorMessage = reason
            )
            return ProtocolParseResult.Malformed(
                reason = reason,
                rawExcerpt = obj.toString().take(180),
                category = ActionDiagnosticCategory.UNSUPPORTED_ACTION
            )
        }

        // Inherit task_id and session_id when omitted in compact/nested action objects,
        // while strictly rejecting explicit mismatches.
        val explicitTaskId = optCleanStringCI(obj, "task_id")
        val taskId = explicitTaskId.ifBlank { expectedTaskId }
        if (taskId.isBlank() || taskId != expectedTaskId) {
            return ProtocolParseResult.Malformed(
                "Action envelope task_id '$taskId' does not match active task '$expectedTaskId'.",
                obj.toString().take(180),
                ActionDiagnosticCategory.SCHEMA_VALIDATION_ERROR
            )
        }

        val explicitSessionId = optCleanStringCI(obj, "session_id")
        val sessionId = explicitSessionId.ifBlank { expectedSessionId }
        if (sessionId.isBlank() || sessionId != expectedSessionId) {
            return ProtocolParseResult.Malformed(
                "Action envelope session_id '$sessionId' does not match active session '$expectedSessionId'.",
                obj.toString().take(180),
                ActionDiagnosticCategory.SCHEMA_VALIDATION_ERROR
            )
        }

        val parsedSeq = optIntCI(obj, defaultSequence, "sequence", "step_number", "step")
        if (parsedSeq <= 0) {
            return ProtocolParseResult.Malformed(
                "Action envelope 'sequence' must be >= 1.",
                obj.toString().take(180),
                ActionDiagnosticCategory.SCHEMA_VALIDATION_ERROR
            )
        }
        val sequence = maxOf(parsedSeq, minSequenceFloor)

        val screenWidth = optIntCI(obj, 0, "screen_width", "display_width", "width")
        val screenHeight = optIntCI(obj, 0, "screen_height", "display_height", "height")
        val orientation = optIntCI(obj, 0, "orientation")

        // Parse target object or string or flat target fields ("target", "TARGET_NAME", "target_description", "selector")
        val targetObj = optObjectCI(obj, "target")
        val targetStringLiteral = if (targetObj == null) optCleanStringCI(obj, "target") else ""

        // Parse coordinates from either JSONObject {"x":..,"y":..,"space":..} or JSONArray [x, y] / box_2d [ymin, xmin, ymax, xmax]
        val coordsObj = (targetObj?.let { optObjectCI(it, "coordinates", "point", "coords", "position", "location") })
            ?: optObjectCI(obj, "coordinates", "point", "coords", "position", "location")
        val regularCoordsArr = (targetObj?.let { optArrayCI(it, "coordinates", "point", "coords", "bounds") })
            ?: optArrayCI(obj, "coordinates", "point", "coords", "bounds")
        val box2dArr = (targetObj?.let { optArrayCI(it, "box_2d") })
            ?: optArrayCI(obj, "box_2d")
        val isBox2d = regularCoordsArr == null && box2dArr != null
        val coordsArr = regularCoordsArr ?: box2dArr

        val explicitTopSpaceRaw = optCleanStringCI(obj, "coordinate_space", "space")
        val coordinates: AiCoordinates? = when {
            coordsObj != null -> {
                val x = optDoubleCI(coordsObj, Double.NaN, "x", "left", "cx")
                val y = optDoubleCI(coordsObj, Double.NaN, "y", "top", "cy")
                if (x.isNaN() || y.isNaN() || x < 0.0 || y < 0.0) {
                    val reason = "Invalid or negative coordinates: ($x, $y)"
                    AiActionDiagnostics.record(
                        stage = "COORDINATE_NORMALIZATION",
                        taskId = taskId,
                        sessionId = sessionId,
                        category = ActionDiagnosticCategory.COORDINATE_NORMALIZATION_ERROR,
                        rawAiResponse = obj.toString(),
                        failureStage = "parseEnvelopeObject.coordsObj",
                        errorMessage = reason
                    )
                    return ProtocolParseResult.Malformed(reason, obj.toString().take(180), ActionDiagnosticCategory.COORDINATE_NORMALIZATION_ERROR)
                }
                val explicitSpaceRaw = optCleanStringCI(coordsObj, "space", "coordinate_space").ifBlank { explicitTopSpaceRaw }
                val space = resolveAndValidateCoordinateSpace(
                    explicitSpaceRaw = explicitSpaceRaw,
                    x = x,
                    y = y,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight
                ) ?: return ProtocolParseResult.Malformed(
                    reason = if (explicitSpaceRaw.isNotBlank() && CoordinateSpace.parse(explicitSpaceRaw) == null) {
                        "Unknown coordinate_space '$explicitSpaceRaw'"
                    } else {
                        "Normalized coordinates out of range [0.0, 1.0]: ($x, $y)"
                    },
                    rawExcerpt = obj.toString().take(180),
                    category = ActionDiagnosticCategory.COORDINATE_NORMALIZATION_ERROR
                )
                AiCoordinates(x = x, y = y, space = space)
            }
            coordsArr != null && coordsArr.length() >= 2 -> {
                val x: Double
                val y: Double
                if (coordsArr.length() >= 4) {
                    val v0 = coordsArr.optDouble(0, Double.NaN)
                    val v1 = coordsArr.optDouble(1, Double.NaN)
                    val v2 = coordsArr.optDouble(2, Double.NaN)
                    val v3 = coordsArr.optDouble(3, Double.NaN)
                    if (v0.isNaN() || v1.isNaN() || v2.isNaN() || v3.isNaN() || v0 < 0.0 || v1 < 0.0 || v2 < 0.0 || v3 < 0.0) {
                        return ProtocolParseResult.Malformed(
                            "Invalid bounding-box array coordinates: $coordsArr",
                            obj.toString().take(180),
                            ActionDiagnosticCategory.COORDINATE_NORMALIZATION_ERROR
                        )
                    }
                    if (isBox2d) {
                        // Gemini box_2d convention: [ymin, xmin, ymax, xmax]
                        y = (v0 + v2) / 2.0
                        x = (v1 + v3) / 2.0
                    } else {
                        x = (v0 + v2) / 2.0
                        y = (v1 + v3) / 2.0
                    }
                } else {
                    x = coordsArr.optDouble(0, Double.NaN)
                    y = coordsArr.optDouble(1, Double.NaN)
                }
                if (x.isNaN() || y.isNaN() || x < 0.0 || y < 0.0) {
                    return ProtocolParseResult.Malformed(
                        "Invalid or negative array coordinates: ($x, $y)",
                        obj.toString().take(180),
                        ActionDiagnosticCategory.COORDINATE_NORMALIZATION_ERROR
                    )
                }
                val arraySpaceRaw = if (isBox2d && explicitTopSpaceRaw.isBlank()) "NORMALIZED_1000" else explicitTopSpaceRaw
                val space = resolveAndValidateCoordinateSpace(
                    explicitSpaceRaw = arraySpaceRaw,
                    x = x,
                    y = y,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight
                ) ?: return ProtocolParseResult.Malformed(
                    reason = if (arraySpaceRaw.isNotBlank() && CoordinateSpace.parse(arraySpaceRaw) == null) {
                        "Unknown coordinate_space '$arraySpaceRaw'"
                    } else {
                        "Coordinates out of valid range: ($x, $y)"
                    },
                    rawExcerpt = obj.toString().take(180),
                    category = ActionDiagnosticCategory.COORDINATE_NORMALIZATION_ERROR
                )
                AiCoordinates(x = x, y = y, space = space)
            }
            hasKeyCI(obj, "x") && hasKeyCI(obj, "y") -> {
                val x = optDoubleCI(obj, Double.NaN, "x")
                val y = optDoubleCI(obj, Double.NaN, "y")
                if (x.isNaN() || y.isNaN() || x < 0.0 || y < 0.0) {
                    return ProtocolParseResult.Malformed(
                        "Invalid or negative top-level coordinates: ($x, $y)",
                        obj.toString().take(180),
                        ActionDiagnosticCategory.COORDINATE_NORMALIZATION_ERROR
                    )
                }
                val space = resolveAndValidateCoordinateSpace(
                    explicitSpaceRaw = explicitTopSpaceRaw,
                    x = x,
                    y = y,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight
                ) ?: return ProtocolParseResult.Malformed(
                    reason = if (explicitTopSpaceRaw.isNotBlank() && CoordinateSpace.parse(explicitTopSpaceRaw) == null) {
                        "Unknown coordinate_space '$explicitTopSpaceRaw'"
                    } else {
                        "Coordinates out of valid range: ($x, $y)"
                    },
                    rawExcerpt = obj.toString().take(180),
                    category = ActionDiagnosticCategory.COORDINATE_NORMALIZATION_ERROR
                )
                AiCoordinates(x = x, y = y, space = space)
            }
            else -> null
        }

        val resolvedAppName = optCleanStringCI(
            obj,
            "app_name",
            "application",
            "app",
            "package_name"
        ).ifBlank {
            targetObj?.let { optCleanStringCI(it, "app_name", "package_name") }.orEmpty()
        }

        val resolvedDescription = (targetObj?.let {
            optCleanStringCI(it, "description", "name", "target_name", "label", "text", "content_description")
        }.orEmpty())
            .ifBlank { targetStringLiteral }
            .ifBlank {
                optCleanStringCI(
                    obj,
                    "target_description",
                    "target_name",
                    "element",
                    "element_name",
                    "label",
                    "button"
                )
            }
            .ifBlank { resolvedAppName }

        val resolvedSelector = (targetObj?.let {
            optCleanStringCI(it, "selector", "resource_id", "id", "view_id")
        }.orEmpty()).ifBlank {
            optCleanStringCI(obj, "selector", "resource_id", "view_id")
        }

        val target = AiActionTarget(
            description = resolvedDescription,
            selector = resolvedSelector,
            coordinates = coordinates
        )

        val resolvedText = optCleanStringCI(
            obj,
            "text",
            "text_input",
            "input",
            "input_text",
            "query",
            "search_query",
            "value"
        ).ifBlank {
            targetObj?.let { optCleanStringCI(it, "text_input", "input") }.orEmpty()
        }

        val inferredDirectionFromAction = when {
            actionStr.endsWith("_UP") -> "UP"
            actionStr.endsWith("_DOWN") -> "DOWN"
            actionStr.endsWith("_LEFT") -> "LEFT"
            actionStr.endsWith("_RIGHT") -> "RIGHT"
            else -> ""
        }
        val resolvedDirection = optCleanStringCI(obj, "direction", "scroll_direction", "swipe_direction")
            .ifBlank { inferredDirectionFromAction }
            .ifBlank { if (actionType == OrchestratorActionType.SCROLL) "DOWN" else "" }

        // Action-specific strict target & field validation
        val finalAppName = resolvedAppName.ifBlank { target.description }
        if (actionType == OrchestratorActionType.OPEN_APP && finalAppName.isBlank()) {
            return ProtocolParseResult.Malformed(
                "OPEN_APP action must specify 'app_name' or 'target.description' / 'TARGET_NAME'.",
                obj.toString().take(180),
                ActionDiagnosticCategory.SCHEMA_VALIDATION_ERROR
            )
        }

        if (actionType in setOf(OrchestratorActionType.TAP, OrchestratorActionType.DOUBLE_TAP, OrchestratorActionType.LONG_PRESS) &&
            coordinates == null &&
            target.description.isBlank() &&
            target.selector.isBlank()
        ) {
            return ProtocolParseResult.Malformed(
                "Action '$actionType' must specify target coordinates, selector, or target_name/description.",
                obj.toString().take(180),
                ActionDiagnosticCategory.SCHEMA_VALIDATION_ERROR
            )
        }

        if (actionType == OrchestratorActionType.TYPE && resolvedText.isBlank()) {
            return ProtocolParseResult.Malformed(
                "TYPE action must specify non-empty 'text' / 'TEXT_INPUT'.",
                obj.toString().take(180),
                ActionDiagnosticCategory.SCHEMA_VALIDATION_ERROR
            )
        }

        val actionId = optCleanStringCI(obj, "action_id", "id").ifBlank {
            "act_${sequence}_${actionType.name.lowercase(Locale.ROOT)}"
        }

        val rawConfidence = optDoubleCI(obj, Double.NaN, "confidence", "score")
        val confidence = when {
            rawConfidence.isNaN() -> 0.95
            rawConfidence in 0.0..1.0 -> rawConfidence
            rawConfidence in 1.0001..100.0 -> (rawConfidence / 100.0).coerceIn(0.0, 1.0)
            else -> {
                return ProtocolParseResult.Malformed(
                    "Action envelope 'confidence' out of bounds [0.0, 1.0]: $rawConfidence",
                    obj.toString().take(180),
                    ActionDiagnosticCategory.SCHEMA_VALIDATION_ERROR
                )
            }
        }

        val expectedResult = optCleanStringCI(obj, "expected_result", "expected_outcome").ifBlank {
            synthesizeExpectedResult(actionType, target, finalAppName, resolvedText, resolvedDirection)
        }

        val screenshotRef = optCleanStringCI(obj, "screenshot_reference", "current_screenshot_reference", "new_screenshot_reference")
            .ifBlank { defaultScreenshotRef }
            .ifBlank { ScreenObservationCoordinator.latestObservation?.screenshotId.orEmpty() }

        if (actionType.requiresScreenObservation && screenshotRef.isBlank()) {
            return ProtocolParseResult.Malformed(
                "Screen action '$actionType' missing mandatory 'screenshot_reference'.",
                obj.toString().take(180),
                ActionDiagnosticCategory.SCHEMA_VALIDATION_ERROR
            )
        }

        val expiresAtMs = optLongCI(
            obj,
            System.currentTimeMillis() + AiProtocolConstants.DEFAULT_PERMIT_TTL_MS,
            "expires_at_ms"
        )
        val waitMs = optLongCI(obj, 1200L, "wait_ms", "duration_ms", "delay_ms").coerceIn(100L, 15_000L)

        val envelope = AiActionEnvelope(
            protocolVersion = AiProtocolConstants.PROTOCOL_VERSION,
            taskId = taskId,
            sessionId = sessionId,
            actionId = actionId,
            sequence = sequence,
            action = actionType,
            target = target,
            text = resolvedText,
            direction = resolvedDirection,
            appName = finalAppName,
            screenshotReference = screenshotRef,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            orientation = orientation,
            confidence = confidence,
            expectedResult = expectedResult,
            verificationRequired = optBooleanCI(obj, true, "verification_required"),
            requiresConfirmation = optBooleanCI(obj, false, "requires_confirmation"),
            reason = optCleanStringCI(obj, "reason", "rationale"),
            expiresAtMs = expiresAtMs,
            spokenMessage = optCleanStringCI(obj, "spoken_message", "assistant_message"),
            waitMs = waitMs
        )

        AiActionDiagnostics.record(
            stage = "NORMALIZATION",
            taskId = taskId,
            sessionId = sessionId,
            rawAiResponse = obj.toString(),
            parsedAction = actionStr,
            validationResult = "VALID_ACTION_ENVELOPE",
            normalizedAction = "${envelope.action.name}(id=${envelope.actionId}, seq=${envelope.sequence}, target='${envelope.target.description}', coords=${envelope.target.coordinates})"
        )

        return ProtocolParseResult.Valid(envelope)
    }

    /**
     * Resolves and validates coordinate space, supporting:
     * - `NORMALIZED_0_1` (`0.0..1.0`)
     * - `NORMALIZED_1000` (`0.0..1000.0`)
     * - `CURRENT_SCREENSHOT_PIXELS`
     * - `DISPLAY_PIXELS` (including `"ABSOLUTE"`, `"PIXELS"`, `"SCREEN_PIXELS"`, and array pixel coordinates `[614, 825]`)
     */
    private fun resolveAndValidateCoordinateSpace(
        explicitSpaceRaw: String,
        x: Double,
        y: Double,
        screenWidth: Int,
        screenHeight: Int
    ): CoordinateSpace? {
        if (explicitSpaceRaw.isNotBlank()) {
            val parsed = CoordinateSpace.parse(explicitSpaceRaw) ?: return null
            return when (parsed) {
                CoordinateSpace.NORMALIZED_0_1 -> {
                    if (x <= 1.0 && y <= 1.0) {
                        CoordinateSpace.NORMALIZED_0_1
                    } else if (screenWidth > 0 && screenHeight > 0 && x <= screenWidth && y <= screenHeight && (x >= 2.0 || y >= 2.0)) {
                        // Model left template default "NORMALIZED_0_1" while providing explicit screen pixel coordinates
                        CoordinateSpace.DISPLAY_PIXELS
                    } else {
                        null
                    }
                }
                CoordinateSpace.NORMALIZED_1000 -> {
                    if (x <= 1000.0 && y <= 1000.0) CoordinateSpace.NORMALIZED_1000 else null
                }
                CoordinateSpace.CURRENT_SCREENSHOT_PIXELS -> CoordinateSpace.CURRENT_SCREENSHOT_PIXELS
                CoordinateSpace.DISPLAY_PIXELS -> CoordinateSpace.DISPLAY_PIXELS
            }
        }

        // No coordinate_space explicitly provided (e.g. "COORDINATES": [614, 825] or {"x": 0.5, "y": 0.5})
        return when {
            x <= 1.0 && y <= 1.0 -> CoordinateSpace.NORMALIZED_0_1
            else -> CoordinateSpace.DISPLAY_PIXELS
        }
    }

    private fun synthesizeExpectedResult(
        actionType: OrchestratorActionType,
        target: AiActionTarget,
        appName: String,
        text: String,
        direction: String
    ): String {
        val label = target.description.ifBlank { target.selector.ifBlank { appName } }
        return when (actionType) {
            OrchestratorActionType.OPEN_APP -> "Application ${appName.ifBlank { label }} is open in foreground"
            OrchestratorActionType.SPLIT_SCREEN -> "Split-screen active with $appName"
            OrchestratorActionType.TAP -> "Clicked ${label.ifBlank { "target element" }}"
            OrchestratorActionType.DOUBLE_TAP -> "Double-tapped ${label.ifBlank { "target element" }}"
            OrchestratorActionType.LONG_PRESS -> "Long-pressed ${label.ifBlank { "target element" }}"
            OrchestratorActionType.TYPE -> "Entered text '$text' into ${label.ifBlank { "input field" }}"
            OrchestratorActionType.SWIPE, OrchestratorActionType.SCROLL -> "Scrolled ${direction.ifBlank { "DOWN" }}"
            OrchestratorActionType.BACK -> "Navigated back to previous screen"
            OrchestratorActionType.HOME -> "Navigated to Android Home screen"
            OrchestratorActionType.RECENTS -> "Opened Recent Apps overview"
            OrchestratorActionType.WAIT -> "Waited for UI update"
            OrchestratorActionType.SEARCH_WEB -> "Opened web search for '${text.ifBlank { label }}'"
            else -> "Completed ${actionType.name}"
        }
    }
}
