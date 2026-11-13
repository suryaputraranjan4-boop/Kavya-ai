package com.example.orchestrator

import android.content.Context
import android.util.Log
import com.example.utils.AppPreferences
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class TaskSessionToken(
    val taskId: String,
    val sessionId: String,
    val userCommand: String,
    val providerId: String,
    val modelId: String,
    val hasExplicitUserCommand: Boolean,
    val createdAtMs: Long = System.currentTimeMillis(),
    @Volatile var lastSequence: Int = 0,
    @Volatile var isActive: Boolean = true,
    @Volatile var isPaused: Boolean = false,
    @Volatile var invalidationReason: String? = null
)

data class ActionAuthorizationPermit(
    val permitId: String,
    val taskId: String,
    val sessionId: String,
    val actionId: String,
    val sequence: Int,
    val action: OrchestratorActionType,
    val providerId: String,
    val screenshotReference: String,
    val issuedAtMs: Long,
    val expiresAtMs: Long
)

sealed class GatewayDecision {
    data class Authorized(val providerId: String, val modelId: String) : GatewayDecision()
    data class Denied(val code: String, val reason: String) : GatewayDecision()
}

sealed class PermitDecision {
    data class Granted(val permit: ActionAuthorizationPermit) : PermitDecision()
    data class NeedsConfirmation(val reason: String, val band: ConfidenceBand) : PermitDecision()
    data class NeedsPermission(val permission: String, val reason: String) : PermitDecision()
    data class Denied(val code: String, val reason: String) : PermitDecision()
}

sealed class PermitConsumeResult {
    data class Valid(val permit: ActionAuthorizationPermit) : PermitConsumeResult()
    data class Rejected(val code: String, val reason: String) : PermitConsumeResult()
}

/**
 * SINGLE NON-BYPASSABLE AI AUTHORITY GATEWAY.
 *
 * Security & Control Invariants:
 * 1. AI = THE ONLY BRAIN (local/offline engines cannot authorize device actions).
 * 2. ACCESSIBILITY = RESTRICTED EXECUTOR ONLY (must present a fresh, unconsumed permit).
 * 3. NO USER COMMAND = NO AUTOMATION (autonomous background loops always blocked).
 * 4. NO VALID CURRENT API AUTHORIZATION = NO ACTION.
 * 5. NO FRESH SCREEN OBSERVATION = NO NEW VISUAL ACTION.
 * 6. Provider switch, Emergency Stop, Cancel, Pause, or App Restart immediately invalidates active permits.
 */
object AiAuthorityGateway {
    private const val TAG = "AiAuthorityGateway"

    private val sessions = ConcurrentHashMap<String, TaskSessionToken>()
    private val activePermits = ConcurrentHashMap<String, ActionAuthorizationPermit>()
    private val consumedActionIds = ConcurrentHashMap.newKeySet<String>()
    private val observationRegistry = ConcurrentHashMap<String, ScreenObservationRecord>()

    @Volatile
    var lastDenialReason: String? = null
        private set

    @Volatile
    private var emergencyStopEpoch: Long = 0L

    @Volatile
    private var startupInvalidated: Boolean = false

    @Synchronized
    fun registerExplicitUserCommand(
        userCommand: String,
        providerId: String,
        modelId: String
    ): TaskSessionToken {
        val cleanCmd = userCommand.trim()
        val taskId = "task_${UUID.randomUUID().toString().take(12)}"
        val sessionId = "sess_${UUID.randomUUID().toString().take(12)}"
        val token = TaskSessionToken(
            taskId = taskId,
            sessionId = sessionId,
            userCommand = cleanCmd,
            providerId = providerId.trim().uppercase(),
            modelId = modelId.trim(),
            hasExplicitUserCommand = cleanCmd.isNotBlank()
        )
        sessions[sessionId] = token
        return token
    }

    fun rejectAutonomousExecution(source: String): GatewayDecision.Denied {
        val msg = "Blocked autonomous execution from '$source': NO USER COMMAND = NO AUTOMATION."
        lastDenialReason = msg
        Log.w(TAG, msg)
        return GatewayDecision.Denied("NO_USER_COMMAND", msg)
    }

    fun checkProviderPreflight(
        context: Context,
        providerId: String,
        modelId: String,
        requiresVision: Boolean,
        isNetworkConnected: Boolean,
        hasValidApiKey: Boolean,
        isQuotaExhausted: Boolean,
        capabilities: AiProviderCapabilities
    ): GatewayDecision {
        val normalizedProvider = providerId.trim().uppercase()

        if (!capabilities.isOnlineProvider || normalizedProvider == "GEMMA_OFFLINE" || normalizedProvider == "LOCAL") {
            val msg = "Device automation requires an active online AI provider (Gemini, OpenRouter, HuggingFace, or Custom API). Local/offline models cannot authorize Android automation."
            lastDenialReason = msg
            return GatewayDecision.Denied("OFFLINE_PROVIDER_FORBIDDEN", msg)
        }

        if (AppPreferences.isOfflineFallbackEnabled(context)) {
            val msg = "Offline mode is enabled. Disable offline mode and connect an online AI API key before running device automation."
            lastDenialReason = msg
            return GatewayDecision.Denied("OFFLINE_MODE_ACTIVE", msg)
        }

        if (!isNetworkConnected) {
            val msg = "No internet connection. Online AI authorization is required before any automation can run."
            lastDenialReason = msg
            return GatewayDecision.Denied("NETWORK_UNAVAILABLE", msg)
        }

        if (!hasValidApiKey) {
            val msg = "API key for selected provider '$normalizedProvider' is missing or invalid. Please configure a valid key in API Dashboard."
            lastDenialReason = msg
            return GatewayDecision.Denied("MISSING_OR_INVALID_API_KEY", msg)
        }

        if (isQuotaExhausted) {
            val msg = "API rate limit or quota exhausted for '$normalizedProvider'. Automation halted without switching providers or falling back locally."
            lastDenialReason = msg
            return GatewayDecision.Denied("QUOTA_OR_RATE_LIMIT", msg)
        }

        if (requiresVision && !capabilities.supportsVision) {
            val msg = "Selected model '$modelId' on provider '$normalizedProvider' does not support screen vision analysis required for this task."
            lastDenialReason = msg
            return GatewayDecision.Denied("CAPABILITY_MISMATCH_NO_VISION", msg)
        }

        return GatewayDecision.Authorized(normalizedProvider, modelId)
    }

    fun registerScreenObservation(record: ScreenObservationRecord) {
        observationRegistry[record.screenshotId] = record
        // Keep bounded history
        if (observationRegistry.size > 20) {
            val oldest = observationRegistry.values.minByOrNull { it.capturedAtMs }
            if (oldest != null) {
                observationRegistry.remove(oldest.screenshotId)
            }
        }
    }

    @Synchronized
    fun requestActionPermit(
        context: Context,
        session: TaskSessionToken,
        envelope: AiActionEnvelope,
        currentObservation: ScreenObservationRecord?,
        activeProviderId: String,
        isOnline: Boolean,
        hasValidApiAuth: Boolean,
        userConfirmedSensitiveOrMedium: Boolean,
        requiredPermissionsGranted: Boolean,
        missingPermissionName: String = ""
    ): PermitDecision {
        // 1. Explicit user command check
        if (!session.hasExplicitUserCommand || session.userCommand.isBlank()) {
            return deny("NO_USER_COMMAND", "Automation blocked: no explicit user command initiated this session.")
        }

        // 2. Session active / not paused / not cancelled / not stopped
        if (!session.isActive) {
            return deny("SESSION_INACTIVE", session.invalidationReason ?: "Task session is no longer active.")
        }
        if (session.isPaused) {
            return deny("TASK_PAUSED", "Task is paused; resume and capture fresh observation first.")
        }

        // 3. Provider consistency check (NEVER allow silent switching mid-task)
        val normalizedActive = activeProviderId.trim().uppercase()
        if (normalizedActive != session.providerId) {
            invalidateSession(session.sessionId, "Provider switched from ${session.providerId} to $normalizedActive")
            return deny("PROVIDER_SWITCHED", "Selected AI provider changed from ${session.providerId} to $normalizedActive. Active automation invalidated.")
        }
        if (normalizedActive == "GEMMA_OFFLINE" || normalizedActive == "LOCAL") {
            return deny("OFFLINE_PROVIDER_FORBIDDEN", "Local/offline model cannot authorize device automation.")
        }

        // 4. Live API authorization & network check
        if (!isOnline || !hasValidApiAuth) {
            return deny("NO_VALID_API_AUTH", "Online AI API authorization is unavailable. Execution blocked.")
        }

        // 5. Task & session binding
        if (envelope.taskId != session.taskId || envelope.sessionId != session.sessionId) {
            return deny("SESSION_MISMATCH", "AI envelope task/session IDs do not match active task.")
        }

        // 6. Expiry & Replay / Sequence checks
        val now = System.currentTimeMillis()
        if (envelope.expiresAtMs <= now) {
            return deny("TOKEN_EXPIRED", "AI action token '${envelope.actionId}' expired before execution.")
        }
        val replayKey = "${session.sessionId}:${envelope.actionId}"
        if (consumedActionIds.contains(replayKey) || activePermits.values.any { it.actionId == envelope.actionId && it.sessionId == session.sessionId }) {
            return deny("DUPLICATE_ACTION_ID", "Duplicate action_id '${envelope.actionId}' rejected to prevent double execution.")
        }
        if (envelope.sequence <= session.lastSequence) {
            return deny("OUT_OF_ORDER_SEQUENCE", "Out-of-order action sequence ${envelope.sequence} (expected > ${session.lastSequence}).")
        }

        // 7. Required Android permissions check
        if (!requiredPermissionsGranted) {
            val perm = missingPermissionName.ifBlank { "REQUIRED_ANDROID_PERMISSION" }
            return PermitDecision.NeedsPermission(perm, "Missing required Android permission: $perm")
        }

        // 8. Fresh screenshot & coordinate calibration for visual actions
        if (envelope.action.requiresScreenObservation) {
            val obs = currentObservation ?: observationRegistry[envelope.screenshotReference]
            if (obs == null) {
                return deny("NO_FRESH_SCREEN_OBSERVATION", "Screen action '${envelope.action}' blocked: no captured screenshot observation available.")
            }
            if (envelope.screenshotReference != obs.screenshotId) {
                return deny("SCREENSHOT_REFERENCE_MISMATCH", "Action targets screenshot '${envelope.screenshotReference}', which does not match current observation '${obs.screenshotId}'.")
            }
            if (obs.isExpired(now)) {
                return deny("STALE_SCREENSHOT", "Screenshot observation '${obs.screenshotId}' is stale. Capture a fresh screenshot first.")
            }

            if (envelope.action in setOf(
                    OrchestratorActionType.TAP,
                    OrchestratorActionType.DOUBLE_TAP,
                    OrchestratorActionType.LONG_PRESS,
                    OrchestratorActionType.SWIPE
                )
            ) {
                when (val cal = PrecisionController.calibrateTargetCoordinates(context, envelope, obs)) {
                    is PrecisionCalibrationResult.Rejected -> {
                        return deny("COORDINATE_VALIDATION_FAILED", cal.reason)
                    }
                    is PrecisionCalibrationResult.Valid -> Unit
                }
            } else {
                // Even for TYPE / SCROLL, verify geometry hasn't rotated/resized since screenshot
                val geom = ScreenObservationCoordinator.readCurrentDisplayGeometry(context)
                if (!obs.matchesGeometry(geom.widthPx, geom.heightPx, geom.orientation)) {
                    return deny("STALE_GEOMETRY", "Display geometry or orientation changed since observation '${obs.screenshotId}'.")
                }
            }
        }

        // 9. Confidence band gate
        when (val confGate = PrecisionController.evaluateConfidence(envelope)) {
            is ConfidenceGateResult.Reject -> {
                return deny("LOW_CONFIDENCE_ABORT", confGate.reason)
            }
            is ConfidenceGateResult.RequireConfirmation -> {
                if (!userConfirmedSensitiveOrMedium) {
                    return PermitDecision.NeedsConfirmation(confGate.reason, confGate.band)
                }
            }
            is ConfidenceGateResult.Proceed -> Unit
        }

        // 10. Consequential / sensitive action confirmation gate
        if (SensitiveActionClassifier.isConsequential(envelope, session.userCommand) && !userConfirmedSensitiveOrMedium) {
            return PermitDecision.NeedsConfirmation(
                reason = "Explicit user confirmation required before consequential action: ${envelope.target.description.ifBlank { envelope.action.name }}",
                band = envelope.confidenceBand
            )
        }

        // Issue single-use permit
        session.lastSequence = envelope.sequence
        val permit = ActionAuthorizationPermit(
            permitId = "perm_${UUID.randomUUID().toString().take(12)}",
            taskId = session.taskId,
            sessionId = session.sessionId,
            actionId = envelope.actionId,
            sequence = envelope.sequence,
            action = envelope.action,
            providerId = session.providerId,
            screenshotReference = envelope.screenshotReference,
            issuedAtMs = now,
            expiresAtMs = envelope.expiresAtMs
        )
        activePermits[permit.permitId] = permit
        return PermitDecision.Granted(permit)
    }

    /**
     * MUST be called by Accessibility / executor layer immediately prior to dispatching ANY physical gesture or system action.
     * Single-use: immediately removes the permit on success so replay is impossible.
     */
    @Synchronized
    fun consumePermit(permit: ActionAuthorizationPermit?): PermitConsumeResult {
        if (permit == null) {
            val msg = "Accessibility execution denied: missing AI authorization permit."
            lastDenialReason = msg
            Log.w(TAG, msg)
            return PermitConsumeResult.Rejected("MISSING_PERMIT", msg)
        }

        val session = sessions[permit.sessionId]
        if (session == null || !session.isActive || session.isPaused) {
            activePermits.remove(permit.permitId)
            val msg = session?.invalidationReason ?: "Accessibility execution denied: session '${permit.sessionId}' is inactive or paused."
            lastDenialReason = msg
            return PermitConsumeResult.Rejected("SESSION_INVALIDATED", msg)
        }

        val stored = activePermits.remove(permit.permitId)
            ?: return PermitConsumeResult.Rejected(
                "PERMIT_ALREADY_CONSUMED_OR_INVALID",
                "Accessibility execution denied: permit '${permit.permitId}' was already used or revoked."
            ).also { lastDenialReason = it.reason }

        val replayKey = "${stored.sessionId}:${stored.actionId}"
        if (!consumedActionIds.add(replayKey)) {
            val msg = "Accessibility execution denied: duplicate action '${stored.actionId}'."
            lastDenialReason = msg
            return PermitConsumeResult.Rejected("DUPLICATE_ACTION", msg)
        }

        if (System.currentTimeMillis() > stored.expiresAtMs) {
            val msg = "Accessibility execution denied: permit '${stored.permitId}' has expired."
            lastDenialReason = msg
            return PermitConsumeResult.Rejected("PERMIT_EXPIRED", msg)
        }

        return PermitConsumeResult.Valid(stored)
    }

    private val inFlightDispatches = ConcurrentHashMap<String, ActionAuthorizationPermit>()

    @Synchronized
    fun beginAuthorizedDispatch(permit: ActionAuthorizationPermit?): PermitConsumeResult {
        val result = consumePermit(permit)
        if (result is PermitConsumeResult.Valid) {
            inFlightDispatches[result.permit.permitId] = result.permit
        }
        return result
    }

    @Synchronized
    fun endAuthorizedDispatch(permit: ActionAuthorizationPermit?) {
        if (permit != null) {
            inFlightDispatches.remove(permit.permitId)
        }
    }

    fun isInFlightPermitValid(permit: ActionAuthorizationPermit?): Boolean {
        if (permit == null) return false
        val active = inFlightDispatches[permit.permitId] ?: return false
        val session = sessions[active.sessionId]
        return session != null && session.isActive && !session.isPaused && System.currentTimeMillis() <= active.expiresAtMs
    }

    fun hasActiveAuthorizedDispatch(): Boolean {
        val now = System.currentTimeMillis()
        inFlightDispatches.entries.removeIf { (_, perm) ->
            val session = sessions[perm.sessionId]
            session == null || !session.isActive || session.isPaused || now > perm.expiresAtMs
        }
        return inFlightDispatches.isNotEmpty()
    }

    @Synchronized
    fun pauseSession(sessionId: String, reason: String = "Paused by user") {
        sessions[sessionId]?.isPaused = true
        activePermits.entries.removeIf { it.value.sessionId == sessionId }
        inFlightDispatches.entries.removeIf { it.value.sessionId == sessionId }
        lastDenialReason = reason
    }

    @Synchronized
    fun resumeSession(sessionId: String) {
        val sess = sessions[sessionId] ?: return
        if (sess.isActive) {
            sess.isPaused = false
        }
    }

    @Synchronized
    fun invalidateSession(sessionId: String, reason: String) {
        sessions[sessionId]?.apply {
            isActive = false
            invalidationReason = reason
        }
        activePermits.entries.removeIf { it.value.sessionId == sessionId }
        inFlightDispatches.entries.removeIf { it.value.sessionId == sessionId }
        lastDenialReason = reason
        Log.i(TAG, "Session $sessionId invalidated: $reason")
    }

    @Synchronized
    fun onProviderSwitched(newProviderId: String) {
        val reason = "AI provider switched to $newProviderId; active automation sessions and permits invalidated."
        sessions.values.forEach { session ->
            if (session.isActive) {
                session.isActive = false
                session.invalidationReason = reason
            }
        }
        activePermits.clear()
        inFlightDispatches.clear()
        lastDenialReason = reason
        Log.i(TAG, reason)
    }

    @Synchronized
    fun emergencyStopAll(reason: String = "Emergency stop triggered") {
        emergencyStopEpoch++
        sessions.values.forEach { session ->
            session.isActive = false
            session.invalidationReason = reason
        }
        activePermits.clear()
        inFlightDispatches.clear()
        lastDenialReason = reason
        Log.w(TAG, "EMERGENCY STOP: $reason")
    }

    @Synchronized
    fun invalidateAllOnStartup() {
        if (!startupInvalidated) {
            startupInvalidated = true
            sessions.values.forEach { session ->
                session.isActive = false
                session.invalidationReason = "Invalidated across app restart."
            }
            activePermits.clear()
            inFlightDispatches.clear()
            observationRegistry.clear()
        }
    }

    fun activePermitCount(): Int = activePermits.size

    fun getSession(sessionId: String): TaskSessionToken? = sessions[sessionId]

    private fun deny(code: String, reason: String): PermitDecision.Denied {
        lastDenialReason = reason
        Log.w(TAG, "Gateway denied [$code]: $reason")
        return PermitDecision.Denied(code, reason)
    }

    @Synchronized
    fun resetForTesting() {
        sessions.clear()
        activePermits.clear()
        inFlightDispatches.clear()
        consumedActionIds.clear()
        observationRegistry.clear()
        lastDenialReason = null
        startupInvalidated = false
    }
}
