package com.example.orchestrator

import org.json.JSONObject
import java.util.Locale

/**
 * Canonical Action Protocol v1.0 Strict Validator & Safety Confirmation Gate.
 *
 * Enforces:
 * 1. Exact single JSON object extraction (rejects multiple concatenated top-level JSON objects).
 * 2. Strict `protocol_version == "1.0"`.
 * 3. Allowed canonical action types:
 *    CLICK, LONG_CLICK, DOUBLE_CLICK, TYPE_TEXT, CLEAR_TEXT, SCROLL, SWIPE,
 *    PRESS_BACK, PRESS_HOME, OPEN_RECENTS, OPEN_NOTIFICATIONS, OPEN_APP,
 *    WAIT, VERIFY_VISIBLE, ASK_USER_CONFIRMATION, COMPLETE, FAIL.
 * 4. Rejection of unknown top-level or target fields when `strictUnknownFields = true`.
 * 5. Mandatory `requiresConfirmation = true` for destructive, financial/payment,
 *    account/permission change, or external message-sending actions.
 */
object ActionProtocolValidator {

    const val PROTOCOL_VERSION = "1.0"

    val ALLOWED_ACTIONS: Set<String> = setOf(
        "CLICK",
        "LONG_CLICK",
        "DOUBLE_CLICK",
        "TYPE_TEXT",
        "CLEAR_TEXT",
        "SCROLL",
        "SWIPE",
        "PRESS_BACK",
        "PRESS_HOME",
        "OPEN_RECENTS",
        "OPEN_NOTIFICATIONS",
        "OPEN_APP",
        "WAIT",
        "VERIFY_VISIBLE",
        "ASK_USER_CONFIRMATION",
        "COMPLETE",
        "FAIL"
    )

    private val ALLOWED_TOP_LEVEL_KEYS: Set<String> = setOf(
        "protocol_version",
        "task_id",
        "session_id",
        "step_index",
        "sequence",
        "action_id",
        "action",
        "target",
        "text",
        "direction",
        "app_name",
        "package_name",
        "wait_ms",
        "confidence",
        "requires_confirmation",
        "expected_outcome",
        "expected_result",
        "verification_required",
        "reason",
        "spoken_message",
        "screenshot_reference",
        "frame_id"
    )

    private val ALLOWED_TARGET_KEYS: Set<String> = setOf(
        "element_id",
        "label",
        "description",
        "role",
        "selector",
        "resource_id",
        "package_name",
        "coordinate_space",
        "bbox",
        "point",
        "x",
        "y",
        "center_x",
        "center_y",
        "source_signal",
        "confidence"
    )

    private val DESTRUCTIVE_OR_FINANCIAL_KEYWORDS: List<String> = listOf(
        "delete", "uninstall", "erase", "wipe", "clear data", "remove account",
        "pay", "payment", "checkout", "place order", "buy now", "transfer", "upi",
        "send money", "wire", "bank", "confirm payment", "authorize",
        "send message", "send whatsapp", "send sms", "send email", "post comment", "publish",
        "grant permission", "allow access", "factory reset"
    )

    data class ValidatedProtocolTarget(
        val elementId: String = "",
        val label: String = "",
        val role: String = "",
        val selector: String = "",
        val coordinateSpace: CoordinateSpace = CoordinateSpace.NORMALIZED_0_1,
        val x: Double? = null,
        val y: Double? = null,
        val bbox: DoubleArray? = null,
        val sourceSignal: String = "",
        val confidence: Double = 0.0
    )

    data class ValidatedProtocolAction(
        val protocolVersion: String = PROTOCOL_VERSION,
        val action: String,
        val target: ValidatedProtocolTarget? = null,
        val text: String = "",
        val direction: String = "",
        val appName: String = "",
        val packageName: String = "",
        val waitMs: Long = 1000L,
        val confidence: Double = 0.0,
        val requiresConfirmation: Boolean = false,
        val safetyGateTriggered: Boolean = false,
        val expectedOutcome: String = "",
        val reason: String = "",
        val frameId: String = ""
    )

    sealed class ValidationOutcome {
        data class Valid(val action: ValidatedProtocolAction) : ValidationOutcome()
        data class Invalid(
            val errorCode: String,
            val message: String
        ) : ValidationOutcome()
    }

    /**
     * Extracts a single balanced JSON object from raw text.
     * Returns null if zero JSON objects exist, or if multiple separate top-level JSON objects exist.
     */
    fun extractSingleJsonObject(raw: String): Result<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Empty response: expected a single JSON object."))
        }

        val candidate = if (trimmed.startsWith("```")) {
            val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE)
                .find(trimmed)?.groupValues?.getOrNull(1)?.trim()
            fenced ?: trimmed
        } else {
            trimmed
        }

        var depth = 0
        var inString = false
        var escaped = false
        var firstStart = -1
        var firstEnd = -1
        var objectCount = 0

        for (i in candidate.indices) {
            val ch = candidate[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\' && inString) {
                escaped = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (inString) continue

            if (ch == '{') {
                if (depth == 0) {
                    objectCount++
                    if (objectCount == 1) {
                        firstStart = i
                    } else {
                        return Result.failure(
                            IllegalArgumentException("Multiple top-level JSON objects detected; only one canonical JSON object is permitted.")
                        )
                    }
                }
                depth++
            } else if (ch == '}') {
                depth--
                if (depth < 0) {
                    return Result.failure(IllegalArgumentException("Unbalanced closing brace in JSON payload."))
                }
                if (depth == 0 && firstEnd == -1) {
                    firstEnd = i
                }
            }
        }

        if (depth != 0 || firstStart == -1 || firstEnd == -1) {
            return Result.failure(IllegalArgumentException("No complete balanced JSON object found."))
        }

        val trailing = candidate.substring(firstEnd + 1).trim()
        if (trailing.contains('{') || trailing.contains('}')) {
            return Result.failure(IllegalArgumentException("Trailing JSON content detected after root object."))
        }

        return Result.success(candidate.substring(firstStart, firstEnd + 1))
    }

    fun validateActionJson(
        rawJson: String,
        strictUnknownFields: Boolean = true,
        userCommandContext: String = ""
    ): ValidationOutcome {
        val singleJson = extractSingleJsonObject(rawJson).getOrElse { err ->
            return ValidationOutcome.Invalid("JSON_EXTRACTION_ERROR", err.message ?: "Invalid JSON")
        }

        val obj = try {
            JSONObject(singleJson)
        } catch (e: Exception) {
            return ValidationOutcome.Invalid("JSON_PARSE_ERROR", "Malformed JSON object: ${e.message}")
        }

        if (strictUnknownFields) {
            val names = obj.names()
            if (names != null) {
                for (i in 0 until names.length()) {
                    val key = names.getString(i)
                    if (key.lowercase(Locale.ROOT) !in ALLOWED_TOP_LEVEL_KEYS) {
                        return ValidationOutcome.Invalid(
                            "UNKNOWN_FIELD",
                            "Unknown top-level field '$key' is not allowed in protocol v1.0."
                        )
                    }
                }
            }
        }

        val version = obj.optString("protocol_version", "").trim()
        if (version != PROTOCOL_VERSION) {
            return ValidationOutcome.Invalid(
                "UNSUPPORTED_PROTOCOL_VERSION",
                "Expected protocol_version '$PROTOCOL_VERSION', got '$version'."
            )
        }

        val rawAction = obj.optString("action", "").trim().uppercase(Locale.ROOT)
        if (rawAction !in ALLOWED_ACTIONS) {
            return ValidationOutcome.Invalid(
                "UNSUPPORTED_ACTION",
                "Action '$rawAction' is not in canonical v1.0 action set."
            )
        }

        val confidence = obj.optDouble("confidence", 0.85)
        if (confidence.isNaN() || confidence < 0.0 || confidence > 1.0) {
            return ValidationOutcome.Invalid(
                "INVALID_CONFIDENCE",
                "Confidence must be within [0.0, 1.0], got $confidence."
            )
        }

        val targetObj = obj.optJSONObject("target")
        val validatedTarget = if (targetObj != null) {
            if (strictUnknownFields) {
                val tNames = targetObj.names()
                if (tNames != null) {
                    for (i in 0 until tNames.length()) {
                        val tk = tNames.getString(i)
                        if (tk.lowercase(Locale.ROOT) !in ALLOWED_TARGET_KEYS) {
                            return ValidationOutcome.Invalid(
                                "UNKNOWN_TARGET_FIELD",
                                "Unknown target field '$tk' is not allowed in protocol v1.0."
                            )
                        }
                    }
                }
            }

            val rawSpace = targetObj.optString("coordinate_space", "NORMALIZED_0_1")
            val parsedSpace = CoordinateSpace.parse(rawSpace)
                ?: return ValidationOutcome.Invalid(
                    "INVALID_COORDINATE_SPACE",
                    "Unknown coordinate_space '$rawSpace'."
                )

            val pointArr = targetObj.optJSONArray("point")
            val xVal: Double? = when {
                pointArr != null && pointArr.length() >= 2 -> pointArr.optDouble(0, Double.NaN).takeIf { !it.isNaN() }
                targetObj.has("x") -> targetObj.optDouble("x", Double.NaN).takeIf { !it.isNaN() }
                targetObj.has("center_x") -> targetObj.optDouble("center_x", Double.NaN).takeIf { !it.isNaN() }
                else -> null
            }
            val yVal: Double? = when {
                pointArr != null && pointArr.length() >= 2 -> pointArr.optDouble(1, Double.NaN).takeIf { !it.isNaN() }
                targetObj.has("y") -> targetObj.optDouble("y", Double.NaN).takeIf { !it.isNaN() }
                targetObj.has("center_y") -> targetObj.optDouble("center_y", Double.NaN).takeIf { !it.isNaN() }
                else -> null
            }

            val bboxArr = targetObj.optJSONArray("bbox")
            val bbox = if (bboxArr != null) {
                if (bboxArr.length() != 4) {
                    return ValidationOutcome.Invalid("INVALID_BBOX", "bbox must have 4 elements [xMin, yMin, xMax, yMax].")
                }
                val b = DoubleArray(4) { idx -> bboxArr.optDouble(idx, Double.NaN) }
                if (b.any { it.isNaN() } || b[2] < b[0] || b[3] < b[1]) {
                    return ValidationOutcome.Invalid("INVALID_BBOX", "bbox coordinates are NaN or inverted.")
                }
                b
            } else null

            if (parsedSpace == CoordinateSpace.NORMALIZED_0_1) {
                if (xVal != null && (xVal < 0.0 || xVal > 1.0)) {
                    return ValidationOutcome.Invalid("COORDINATE_OUT_OF_RANGE", "Normalized x=$xVal out of [0.0, 1.0].")
                }
                if (yVal != null && (yVal < 0.0 || yVal > 1.0)) {
                    return ValidationOutcome.Invalid("COORDINATE_OUT_OF_RANGE", "Normalized y=$yVal out of [0.0, 1.0].")
                }
                if (bbox != null && bbox.any { it < 0.0 || it > 1.0 }) {
                    return ValidationOutcome.Invalid("COORDINATE_OUT_OF_RANGE", "Normalized bbox out of [0.0, 1.0].")
                }
            } else if (parsedSpace == CoordinateSpace.NORMALIZED_1000) {
                if (xVal != null && (xVal < 0.0 || xVal > 1000.0)) {
                    return ValidationOutcome.Invalid("COORDINATE_OUT_OF_RANGE", "Normalized_1000 x=$xVal out of [0, 1000].")
                }
                if (yVal != null && (yVal < 0.0 || yVal > 1000.0)) {
                    return ValidationOutcome.Invalid("COORDINATE_OUT_OF_RANGE", "Normalized_1000 y=$yVal out of [0, 1000].")
                }
            }

            val sourceSignal = targetObj.optString("source_signal", "").trim().uppercase(Locale.ROOT)
            if (sourceSignal == "AUX_VISION_MODEL" && rawAction in setOf("CLICK", "LONG_CLICK", "DOUBLE_CLICK", "TYPE_TEXT")) {
                return ValidationOutcome.Invalid(
                    "UNVERIFIED_AUX_VISION_TARGET",
                    "Florence-2 AUX_VISION_MODEL proposals alone are never authoritative for direct tap/type execution."
                )
            }

            ValidatedProtocolTarget(
                elementId = targetObj.optString("element_id", "").trim(),
                label = targetObj.optString("label", targetObj.optString("description", "")).trim(),
                role = targetObj.optString("role", "").trim(),
                selector = targetObj.optString("selector", targetObj.optString("resource_id", "")).trim(),
                coordinateSpace = parsedSpace,
                x = xVal,
                y = yVal,
                bbox = bbox,
                sourceSignal = sourceSignal,
                confidence = targetObj.optDouble("confidence", confidence)
            )
        } else null

        val text = obj.optString("text", "").trim()
        val direction = obj.optString("direction", "").trim().uppercase(Locale.ROOT)
        val appName = obj.optString("app_name", "").trim()
        val packageName = obj.optString("package_name", "").trim()

        // Action-specific required field checks
        when (rawAction) {
            "CLICK", "LONG_CLICK", "DOUBLE_CLICK" -> {
                if (validatedTarget == null ||
                    (validatedTarget.elementId.isBlank() &&
                        validatedTarget.label.isBlank() &&
                        validatedTarget.selector.isBlank() &&
                        (validatedTarget.x == null || validatedTarget.y == null) &&
                        validatedTarget.bbox == null)
                ) {
                    return ValidationOutcome.Invalid(
                        "MISSING_TARGET",
                        "$rawAction requires a non-empty target (element_id, label, selector, point, or bbox)."
                    )
                }
            }
            "TYPE_TEXT" -> {
                if (text.isBlank()) {
                    return ValidationOutcome.Invalid("MISSING_TEXT", "TYPE_TEXT requires a non-blank 'text' field.")
                }
            }
            "SCROLL", "SWIPE" -> {
                if (direction !in setOf("UP", "DOWN", "LEFT", "RIGHT")) {
                    return ValidationOutcome.Invalid(
                        "INVALID_DIRECTION",
                        "$rawAction requires direction in [UP, DOWN, LEFT, RIGHT], got '$direction'."
                    )
                }
            }
            "OPEN_APP" -> {
                if (appName.isBlank() && packageName.isBlank() && validatedTarget?.label.isNullOrBlank()) {
                    return ValidationOutcome.Invalid(
                        "MISSING_APP_IDENTIFIER",
                        "OPEN_APP requires 'app_name' or 'package_name'."
                    )
                }
            }
        }

        val explicitConfirm = obj.optBoolean("requires_confirmation", false)
        val combinedContext = listOf(
            validatedTarget?.label.orEmpty(),
            validatedTarget?.selector.orEmpty(),
            text,
            obj.optString("expected_outcome", ""),
            obj.optString("reason", ""),
            userCommandContext
        ).joinToString(" ").lowercase(Locale.ROOT)

        val safetyGateTriggered = rawAction == "ASK_USER_CONFIRMATION" ||
            DESTRUCTIVE_OR_FINANCIAL_KEYWORDS.any { combinedContext.contains(it) } ||
            (rawAction in setOf("CLICK", "LONG_CLICK") &&
                validatedTarget?.label?.lowercase(Locale.ROOT)?.trim() in setOf(
                    "delete", "uninstall", "pay", "pay now", "buy now", "place order", "send", "confirm", "transfer"
                ))

        return ValidationOutcome.Valid(
            ValidatedProtocolAction(
                protocolVersion = PROTOCOL_VERSION,
                action = rawAction,
                target = validatedTarget,
                text = text,
                direction = direction,
                appName = appName,
                packageName = packageName,
                waitMs = obj.optLong("wait_ms", 1000L).coerceIn(0L, 30_000L),
                confidence = confidence,
                requiresConfirmation = explicitConfirm || safetyGateTriggered,
                safetyGateTriggered = safetyGateTriggered,
                expectedOutcome = obj.optString("expected_outcome", obj.optString("expected_result", "")).trim(),
                reason = obj.optString("reason", "").trim(),
                frameId = obj.optString("frame_id", obj.optString("screenshot_reference", "")).trim()
            )
        )
    }
}
