package com.example.services

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages the Android Screen Sharing / MediaProjection lifecycle for Kavya AI.
 * Ensures genuine Android MediaProjection + VirtualDisplay frame pipeline,
 * Android 14–16 entire-screen consent priority, static-frame resilience (e.g. Chrome reading),
 * privacy pause/resume, protected surface (`FLAG_SECURE`) handling, and snapshot history.
 */
object ScreenShareManager {

    private const val TAG = "KavyaScreenShareManager"
    private const val FIRST_FRAME_TIMEOUT_MS = 6500L
    private const val STATIC_SCREEN_THRESHOLD_MS = 4500L
    private const val MAX_VAULT_SNAPSHOTS = 6

    data class ScreenCaptureTelemetry(
        val sessionId: String = "",
        val width: Int = 0,
        val height: Int = 0,
        val fps: Int = 0,
        val framesCaptured: Int = 0,
        val isStaticScreen: Boolean = false,
        val isUserPaused: Boolean = false,
        val isSystemVisibilityPaused: Boolean = false,
        val isProtectedSurface: Boolean = false,
        val foregroundPackage: String = "",
        val activeBrowserUrl: String = "",
        val recoveryHint: String = "",
        val lastUpdatedMs: Long = 0L
    )

    data class ScreenInsightSnapshot(
        val id: String = UUID.randomUUID().toString(),
        val title: String,
        val summary: String,
        val appPackage: String,
        val urlOrContext: String = "",
        val timestampMs: Long = System.currentTimeMillis()
    )

    sealed class ScreenShareState {
        object Idle : ScreenShareState()
        object RequestingPermission : ScreenShareState()
        data class PermissionDenied(val reason: String) : ScreenShareState()
        object StartingService : ScreenShareState()
        data class WaitingForFirstFrame(val sessionStartedMs: Long, val sessionId: String) : ScreenShareState()
        data class Active(
            val width: Int,
            val height: Int,
            val fps: Int,
            val framesCaptured: Int,
            val lastFrameTimestamp: Long,
            val sessionId: String,
            val isStaticScreen: Boolean = false,
            val isProtectedSurface: Boolean = false
        ) : ScreenShareState()
        data class Paused(
            val width: Int,
            val height: Int,
            val sessionId: String
        ) : ScreenShareState()
        data class StaleFrame(
            val width: Int,
            val height: Int,
            val lastFrameTimestamp: Long,
            val sessionId: String
        ) : ScreenShareState()
        object Stopping : ScreenShareState()
        object Stopped : ScreenShareState()
        data class Error(val message: String, val isFatal: Boolean = false) : ScreenShareState()
    }

    /**
     * Unified Jarvis + Screen Share Session State Machine (Upgrade 3).
     * Shared across ScreenShareManager, ScreenCaptureService, VisionPipeline, and JarvisOverlayController.
     */
    sealed class UnifiedJarvisSessionState {
        object Idle : UnifiedJarvisSessionState()
        object PermissionPending : UnifiedJarvisSessionState()
        object Starting : UnifiedJarvisSessionState()
        data class Active(val width: Int, val height: Int, val sessionId: String, val isLiveStream: Boolean = true) : UnifiedJarvisSessionState()
        data class Paused(val sessionId: String, val reason: String = "Content hidden or minimized") : UnifiedJarvisSessionState()
        object Stopping : UnifiedJarvisSessionState()
        object Stopped : UnifiedJarvisSessionState()
        data class Error(val reason: String, val isPermissionDenied: Boolean = false) : UnifiedJarvisSessionState()
    }

    private val _state = MutableStateFlow<ScreenShareState>(ScreenShareState.Idle)
    val state: StateFlow<ScreenShareState> = _state.asStateFlow()

    private val _unifiedSessionState = MutableStateFlow<UnifiedJarvisSessionState>(UnifiedJarvisSessionState.Idle)
    val unifiedSessionState: StateFlow<UnifiedJarvisSessionState> = _unifiedSessionState.asStateFlow()

    private val _telemetry = MutableStateFlow(ScreenCaptureTelemetry())
    val telemetry: StateFlow<ScreenCaptureTelemetry> = _telemetry.asStateFlow()

    private val _snapshotVault = MutableStateFlow<List<ScreenInsightSnapshot>>(emptyList())
    val snapshotVault: StateFlow<List<ScreenInsightSnapshot>> = _snapshotVault.asStateFlow()

    // Tracks consumed MediaProjection consent Intent identity hashes so stale single-use tokens are never reused
    private val consumedConsentTokenHashes = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    private val frameCounter = AtomicInteger(0)
    private var lastFpsCalculationTime = 0L
    private var framesInLastSecond = 0
    private var currentFps = 0

    @Volatile
    private var currentSessionId: String = ""

    @Volatile
    private var lastFrameTimeMs: Long = 0L

    @Volatile
    var isUserPaused: Boolean = false
        private set

    @Volatile
    private var isSystemVisibilityPaused: Boolean = false

    @Volatile
    private var isProtectedSurface: Boolean = false

    private val managerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var heartbeatJob: Job? = null

    private fun updateState(newState: ScreenShareState) {
        _state.value = newState
        val mappedUnified = when (newState) {
            is ScreenShareState.Idle -> UnifiedJarvisSessionState.Idle
            is ScreenShareState.RequestingPermission -> UnifiedJarvisSessionState.PermissionPending
            is ScreenShareState.PermissionDenied -> UnifiedJarvisSessionState.Error(newState.reason, isPermissionDenied = true)
            is ScreenShareState.StartingService, is ScreenShareState.WaitingForFirstFrame -> UnifiedJarvisSessionState.Starting
            is ScreenShareState.Active -> UnifiedJarvisSessionState.Active(newState.width, newState.height, newState.sessionId)
            is ScreenShareState.Paused -> {
                val pauseReason = if (isUserPaused) {
                    "Privacy Shield active (paused by user)"
                } else {
                    "Single-app capture hidden — select 'Entire Screen' to share Chrome"
                }
                UnifiedJarvisSessionState.Paused(newState.sessionId, pauseReason)
            }
            is ScreenShareState.StaleFrame -> UnifiedJarvisSessionState.Paused(newState.sessionId, "Waiting for fresh screen change")
            is ScreenShareState.Stopping -> UnifiedJarvisSessionState.Stopping
            is ScreenShareState.Stopped -> UnifiedJarvisSessionState.Stopped
            is ScreenShareState.Error -> UnifiedJarvisSessionState.Error(newState.message)
        }
        _unifiedSessionState.value = mappedUnified
        syncTelemetryFromState(newState)
        com.example.visual.JarvisOverlayController.syncWithUnifiedSessionState(mappedUnified)
    }

    private fun syncTelemetryFromState(currentState: ScreenShareState) {
        val prev = _telemetry.value
        val now = System.currentTimeMillis()
        _telemetry.value = when (currentState) {
            is ScreenShareState.Active -> prev.copy(
                sessionId = currentState.sessionId,
                width = currentState.width,
                height = currentState.height,
                fps = currentState.fps,
                framesCaptured = currentState.framesCaptured,
                isStaticScreen = currentState.isStaticScreen,
                isUserPaused = isUserPaused,
                isSystemVisibilityPaused = isSystemVisibilityPaused,
                isProtectedSurface = currentState.isProtectedSurface || isProtectedSurface,
                recoveryHint = if (currentState.isProtectedSurface || isProtectedSurface) {
                    "Protected window (FLAG_SECURE / Incognito) detected — frame video masked for privacy, Accessibility text still active."
                } else {
                    ""
                },
                lastUpdatedMs = now
            )
            is ScreenShareState.Paused -> prev.copy(
                sessionId = currentState.sessionId,
                width = currentState.width,
                height = currentState.height,
                fps = 0,
                isStaticScreen = false,
                isUserPaused = isUserPaused,
                isSystemVisibilityPaused = isSystemVisibilityPaused,
                recoveryHint = if (isUserPaused) {
                    "Privacy Pause is ON. Tap Resume to continue streaming frames."
                } else {
                    "System hid single-app capture when switching apps. Re-share and select 'Entire Screen' for Chrome."
                },
                lastUpdatedMs = now
            )
            is ScreenShareState.Idle, is ScreenShareState.Stopped -> ScreenCaptureTelemetry(
                lastUpdatedMs = now
            )
            else -> prev.copy(
                sessionId = currentSessionId,
                isUserPaused = isUserPaused,
                isSystemVisibilityPaused = isSystemVisibilityPaused,
                isProtectedSurface = isProtectedSurface,
                lastUpdatedMs = now
            )
        }
    }

    val isScreenSharingActive: Boolean
        get() = _state.value is ScreenShareState.Active ||
            _state.value is ScreenShareState.StaleFrame ||
            _state.value is ScreenShareState.Paused

    var activeResolution: Pair<Int, Int>? = null
        private set

    private val _latestFrame = MutableStateFlow<Bitmap?>(null)
    val latestFrame: StateFlow<Bitmap?> = _latestFrame.asStateFlow()

    val latestFrameBitmap: Bitmap?
        get() = _latestFrame.value

    /**
     * Called when the user initiates screen sharing request.
     * On Android 14+ (API 34+), requests default-display (Entire Screen) config so switching
     * to Google Chrome or other apps does not kill or blank the MediaProjection stream.
     */
    fun createConsentIntent(context: Context): Intent? {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (manager == null) {
            updateState(ScreenShareState.Error("MediaProjectionManager is unavailable on this device.", isFatal = true))
            return null
        }
        updateState(ScreenShareState.RequestingPermission)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    val config = MediaProjectionConfig.createConfigForDefaultDisplay()
                    manager.createScreenCaptureIntent(config)
                } catch (e: Exception) {
                    Log.w(TAG, "MediaProjectionConfig.createConfigForDefaultDisplay fallback: ${e.message}")
                    manager.createScreenCaptureIntent()
                }
            } else {
                manager.createScreenCaptureIntent()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create screen capture intent", e)
            updateState(ScreenShareState.Error("Failed to request screen capture: ${e.message}"))
            null
        }
    }

    /**
     * Handles the result of the system MediaProjection consent dialog.
     * Enforces single-use MediaProjection consent token rules (Android 14+).
     */
    fun handleConsentResult(context: Context, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "Screen capture consent was denied or cancelled by user.")
            updateState(ScreenShareState.PermissionDenied("User denied screen recording permission."))
            return
        }

        val tokenHash = System.identityHashCode(data)
        if (!consumedConsentTokenHashes.add(tokenHash)) {
            Log.w(TAG, "Attempted to reuse an already-consumed MediaProjection consent token! Rejecting stale token.")
            updateState(ScreenShareState.PermissionDenied("Screen capture permission token was already consumed. Please grant permission again."))
            return
        }

        val newSessionId = UUID.randomUUID().toString()
        currentSessionId = newSessionId
        frameCounter.set(0)
        currentFps = 0
        framesInLastSecond = 0
        lastFrameTimeMs = 0L
        isUserPaused = false
        isSystemVisibilityPaused = false
        isProtectedSurface = false

        Log.i(TAG, "Screen capture consent granted. Starting Foreground Capture Service (sessionId=$newSessionId)...")
        updateState(ScreenShareState.StartingService)

        val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            putExtra("EXTRA_SESSION_ID", newSessionId)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            startHeartbeatMonitoring(newSessionId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ScreenCaptureService", e)
            updateState(ScreenShareState.Error("Could not launch capture service: ${e.message}", isFatal = true))
        }
    }

    /**
     * Called by ScreenCaptureService once MediaProjection and VirtualDisplay are created
     * but before the first frame arrives.
     */
    fun onServiceStarted(sessionId: String) {
        if (sessionId.isBlank() || sessionId != currentSessionId) return
        if (_state.value is ScreenShareState.StartingService) {
            updateState(
                ScreenShareState.WaitingForFirstFrame(
                    sessionStartedMs = System.currentTimeMillis(),
                    sessionId = sessionId
                )
            )
            Log.d(TAG, "Capture service active, waiting for first valid frame from VirtualDisplay...")
        }
    }

    /**
     * Called when MediaProjection.Callback.onCapturedContentResize(width, height) triggers.
     */
    fun onCapturedContentResized(width: Int, height: Int, sessionId: String = currentSessionId) {
        if (sessionId.isBlank() || sessionId != currentSessionId || width <= 0 || height <= 0) return
        activeResolution = Pair(width, height)
        com.example.visual.ScreenAnnotationManager.coordinateMapper.updateCapturedFrameGeometry(width, height)
        com.example.visual.VisionPipeline.invalidateStaleFrameOnResize(width, height)
        val current = _state.value
        if (current is ScreenShareState.Active) {
            updateState(current.copy(width = width, height = height, lastFrameTimestamp = System.currentTimeMillis()))
        }
    }

    /**
     * Called when MediaProjection.Callback.onCapturedContentVisibilityChanged(isVisible) triggers.
     */
    fun onCapturedContentVisibilityChanged(isVisible: Boolean, sessionId: String = currentSessionId) {
        if (sessionId.isBlank() || sessionId != currentSessionId) return
        isSystemVisibilityPaused = !isVisible
        val res = activeResolution ?: Pair(720, 1280)
        if (!isVisible) {
            updateState(ScreenShareState.Paused(res.first, res.second, currentSessionId))
        } else if (!isUserPaused && (_state.value is ScreenShareState.Paused || _state.value is ScreenShareState.StaleFrame)) {
            val now = System.currentTimeMillis()
            lastFrameTimeMs = now
            updateState(
                ScreenShareState.Active(
                    width = res.first,
                    height = res.second,
                    fps = currentFps,
                    framesCaptured = frameCounter.get(),
                    lastFrameTimestamp = now,
                    sessionId = currentSessionId,
                    isStaticScreen = false,
                    isProtectedSurface = isProtectedSurface
                )
            )
        }
    }

    /**
     * User-controlled Privacy Shield Pause / Resume.
     */
    fun setUserPaused(paused: Boolean) {
        if (currentSessionId.isBlank()) {
            isUserPaused = paused
            return
        }
        isUserPaused = paused
        val res = activeResolution ?: Pair(720, 1280)
        if (paused) {
            updateState(ScreenShareState.Paused(res.first, res.second, currentSessionId))
        } else if (!isSystemVisibilityPaused) {
            val now = System.currentTimeMillis()
            lastFrameTimeMs = now
            updateState(
                ScreenShareState.Active(
                    width = res.first,
                    height = res.second,
                    fps = currentFps,
                    framesCaptured = frameCounter.get(),
                    lastFrameTimestamp = now,
                    sessionId = currentSessionId,
                    isStaticScreen = false,
                    isProtectedSurface = isProtectedSurface
                )
            )
        } else {
            syncTelemetryFromState(_state.value)
        }
    }

    fun toggleUserPause(): Boolean {
        val next = !isUserPaused
        setUserPaused(next)
        return next
    }

    /**
     * Updates protected surface detection state (`FLAG_SECURE` / Chrome Incognito / DRM content)
     * without terminating the capture session.
     */
    fun updateProtectedSurfaceStatus(isProtected: Boolean, sessionId: String = currentSessionId) {
        if (sessionId.isNotBlank() && sessionId != currentSessionId) return
        if (isProtectedSurface == isProtected) return
        isProtectedSurface = isProtected
        val current = _state.value
        if (current is ScreenShareState.Active) {
            updateState(current.copy(isProtectedSurface = isProtected))
        } else {
            syncTelemetryFromState(current)
        }
    }

    /**
     * Records foreground package & Chrome/browser URL context for live workspace HUD and quick actions.
     */
    fun updateForegroundAppContext(packageName: String, browserUrl: String = "") {
        val cleanPkg = packageName.trim()
        val cleanUrl = browserUrl.trim()
        val prev = _telemetry.value
        if (prev.foregroundPackage == cleanPkg && prev.activeBrowserUrl == cleanUrl) return
        _telemetry.value = prev.copy(
            foregroundPackage = cleanPkg,
            activeBrowserUrl = cleanUrl,
            lastUpdatedMs = System.currentTimeMillis()
        )
    }

    /**
     * Stores an insight card in the bounded in-memory Snapshot Vault (max 6 entries).
     */
    fun saveSnapshotToVault(
        title: String,
        summary: String,
        appPackage: String = _telemetry.value.foregroundPackage,
        urlOrContext: String = _telemetry.value.activeBrowserUrl
    ) {
        if (title.isBlank() && summary.isBlank()) return
        val entry = ScreenInsightSnapshot(
            title = title.ifBlank { "Screen Insight" },
            summary = summary,
            appPackage = appPackage.ifBlank { "Current Screen" },
            urlOrContext = urlOrContext
        )
        _snapshotVault.value = (listOf(entry) + _snapshotVault.value).take(MAX_VAULT_SNAPSHOTS)
    }

    fun clearSnapshotVault() {
        _snapshotVault.value = emptyList()
    }

    /**
     * Lightweight heartbeat called when intermediate VirtualDisplay frames are throttled.
     */
    fun onFrameHeartbeat(width: Int, height: Int, sessionId: String = currentSessionId) {
        if (sessionId.isBlank() || sessionId != currentSessionId || isUserPaused || _state.value !is ScreenShareState.Active) return
        val now = System.currentTimeMillis()
        lastFrameTimeMs = now
        activeResolution = Pair(width, height)
        val current = _state.value
        if (current is ScreenShareState.Active) {
            updateState(current.copy(lastFrameTimestamp = now, isStaticScreen = false))
        }
    }

    /**
     * Invoked by ScreenCaptureService when a new frame is captured from the VirtualDisplay.
     */
    fun onFrameCaptured(bitmap: Bitmap, width: Int, height: Int, sessionId: String = currentSessionId): Boolean {
        if (sessionId.isBlank() || sessionId != currentSessionId || isUserPaused) return false
        val current = _state.value
        if (current !is ScreenShareState.WaitingForFirstFrame &&
            current !is ScreenShareState.Active &&
            current !is ScreenShareState.StaleFrame &&
            current !is ScreenShareState.Paused
        ) return false
        if (current is ScreenShareState.Paused && isSystemVisibilityPaused) {
            isSystemVisibilityPaused = false
        }
        if (width <= 0 || height <= 0 || bitmap.isRecycled) return false
        val now = System.currentTimeMillis()
        val totalFrames = frameCounter.incrementAndGet()
        lastFrameTimeMs = now
        activeResolution = Pair(width, height)
        com.example.visual.ScreenAnnotationManager.coordinateMapper.updateCapturedFrameGeometry(width, height)

        // Do NOT call .recycle() on oldBitmap because it may be concurrently read by
        // VisionPipeline, GeminiVisionAnalyzer, or Compose UI preview. Let GC reclaim safely.
        _latestFrame.value = bitmap

        framesInLastSecond++
        if (now - lastFpsCalculationTime >= 1000L) {
            currentFps = framesInLastSecond
            framesInLastSecond = 0
            lastFpsCalculationTime = now
        }

        // Only transition to Active once the first real frame arrives!
        updateState(
            ScreenShareState.Active(
                width = width,
                height = height,
                fps = currentFps,
                framesCaptured = totalFrames,
                lastFrameTimestamp = now,
                sessionId = sessionId,
                isStaticScreen = false,
                isProtectedSurface = isProtectedSurface
            )
        )
        return true
    }

    private fun startHeartbeatMonitoring(sessionId: String) {
        heartbeatJob?.cancel()
        heartbeatJob = managerScope.launch {
            val startMs = System.currentTimeMillis()
            while (isActive) {
                delay(1000L)
                val currentState = _state.value
                val now = System.currentTimeMillis()

                // Check for first-frame timeout
                if (currentState is ScreenShareState.WaitingForFirstFrame || currentState is ScreenShareState.StartingService) {
                    if (now - startMs > FIRST_FRAME_TIMEOUT_MS) {
                        Log.w(TAG, "First frame timeout exceeded (${FIRST_FRAME_TIMEOUT_MS}ms). VirtualDisplay not delivering frames.")
                        updateState(ScreenShareState.Error("Screen capture started but no frames were delivered by the system."))
                        break
                    }
                }

                // Static Chrome pages do not emit new dirty VirtualDisplay frames every second.
                // Keep session Active with fps=0 and isStaticScreen=true instead of demoting to Paused.
                if (currentState is ScreenShareState.Active && !isUserPaused && !isSystemVisibilityPaused) {
                    if (now - lastFrameTimeMs > STATIC_SCREEN_THRESHOLD_MS) {
                        currentFps = 0
                        framesInLastSecond = 0
                        if (!currentState.isStaticScreen || currentState.fps != 0) {
                            updateState(
                                currentState.copy(
                                    fps = 0,
                                    isStaticScreen = true
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Stops screen capture session cleanly and idempotently.
     */
    fun stopScreenShare(context: Context) {
        Log.i(TAG, "Stopping screen sharing session...")
        updateState(ScreenShareState.Stopping)
        heartbeatJob?.cancel()
        heartbeatJob = null

        val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        try {
            context.startService(serviceIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send stop intent to ScreenCaptureService: ${e.message}")
        }
        cleanupResources()
        updateState(ScreenShareState.Stopped)
    }

    /**
     * Reports an internal error from the service.
     */
    fun reportServiceError(errorMessage: String, isFatal: Boolean = false, sessionId: String = currentSessionId) {
        if (sessionId.isNotBlank() && sessionId != currentSessionId) return
        Log.e(TAG, "Screen share service error: $errorMessage")
        heartbeatJob?.cancel()
        heartbeatJob = null
        updateState(ScreenShareState.Error(errorMessage, isFatal))
    }

    /**
     * Marks session stopped internally when projection stops.
     */
    fun onServiceStopped(sessionId: String? = null) {
        if (sessionId != null && sessionId.isNotBlank() && sessionId != currentSessionId) return
        heartbeatJob?.cancel()
        heartbeatJob = null
        cleanupResources()
        currentSessionId = ""
        updateState(ScreenShareState.Stopped)
    }

    fun resetToIdle() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        consumedConsentTokenHashes.clear()
        cleanupResources()
        currentSessionId = ""
        updateState(ScreenShareState.Idle)
    }

    private fun cleanupResources() {
        frameCounter.set(0)
        currentFps = 0
        framesInLastSecond = 0
        lastFpsCalculationTime = 0L
        lastFrameTimeMs = 0L
        isUserPaused = false
        isSystemVisibilityPaused = false
        isProtectedSurface = false
        activeResolution = null
        _latestFrame.value = null
    }
}
