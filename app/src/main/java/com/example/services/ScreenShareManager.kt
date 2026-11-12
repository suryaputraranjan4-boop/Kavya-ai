package com.example.services

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
 * strict state verification, first-frame detection, heartbeat, and stale-frame monitoring.
 */
object ScreenShareManager {

    private const val TAG = "KavyaScreenShareManager"
    private const val FIRST_FRAME_TIMEOUT_MS = 6000L
    private const val STALE_FRAME_THRESHOLD_MS = 4500L

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
            val sessionId: String
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
            is ScreenShareState.Paused -> UnifiedJarvisSessionState.Paused(newState.sessionId)
            is ScreenShareState.StaleFrame -> UnifiedJarvisSessionState.Paused(newState.sessionId, "Waiting for fresh screen change")
            is ScreenShareState.Stopping -> UnifiedJarvisSessionState.Stopping
            is ScreenShareState.Stopped -> UnifiedJarvisSessionState.Stopped
            is ScreenShareState.Error -> UnifiedJarvisSessionState.Error(newState.message)
        }
        _unifiedSessionState.value = mappedUnified
        com.example.visual.JarvisOverlayController.syncWithUnifiedSessionState(mappedUnified)
    }

    val isScreenSharingActive: Boolean
        get() = _state.value is ScreenShareState.Active || _state.value is ScreenShareState.StaleFrame

    var activeResolution: Pair<Int, Int>? = null
        private set

    @Volatile
    var latestFrameBitmap: Bitmap? = null
        private set

    /**
     * Called when the user initiates screen sharing request.
     */
    fun createConsentIntent(context: Context): Intent? {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (manager == null) {
            updateState(ScreenShareState.Error("MediaProjectionManager is unavailable on this device.", isFatal = true))
            return null
        }
        updateState(ScreenShareState.RequestingPermission)
        return try {
            manager.createScreenCaptureIntent()
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
        lastFrameTimeMs = 0L

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
    fun onCapturedContentResized(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
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
    fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
        val res = activeResolution ?: Pair(720, 1280)
        if (!isVisible) {
            updateState(ScreenShareState.Paused(res.first, res.second, currentSessionId))
        } else if (_state.value is ScreenShareState.Paused) {
            updateState(
                ScreenShareState.Active(
                    width = res.first,
                    height = res.second,
                    fps = currentFps,
                    framesCaptured = frameCounter.get(),
                    lastFrameTimestamp = System.currentTimeMillis(),
                    sessionId = currentSessionId
                )
            )
        }
    }

    /**
     * Lightweight heartbeat called when intermediate VirtualDisplay frames are throttled.
     */
    fun onFrameHeartbeat(width: Int, height: Int) {
        val now = System.currentTimeMillis()
        lastFrameTimeMs = now
        activeResolution = Pair(width, height)
        val current = _state.value
        if (current is ScreenShareState.Active) {
            updateState(current.copy(lastFrameTimestamp = now))
        }
    }

    /**
     * Invoked by ScreenCaptureService when a new frame is captured from the VirtualDisplay.
     */
    fun onFrameCaptured(bitmap: Bitmap, width: Int, height: Int) {
        val now = System.currentTimeMillis()
        val totalFrames = frameCounter.incrementAndGet()
        lastFrameTimeMs = now
        activeResolution = Pair(width, height)
        com.example.visual.ScreenAnnotationManager.coordinateMapper.updateCapturedFrameGeometry(width, height)

        // Do NOT call .recycle() on oldBitmap because it may be concurrently read by
        // VisionPipeline, GeminiVisionAnalyzer, or Compose UI preview. Let GC reclaim safely.
        latestFrameBitmap = bitmap

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
                sessionId = currentSessionId
            )
        )
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

                // Check for stale frame in Active state
                if (currentState is ScreenShareState.Active) {
                    if (now - lastFrameTimeMs > STALE_FRAME_THRESHOLD_MS) {
                        Log.w(TAG, "No new frames received in ${STALE_FRAME_THRESHOLD_MS}ms; marking state as STALE_FRAME.")
                        val res = activeResolution ?: Pair(720, 1280)
                        updateState(
                            ScreenShareState.StaleFrame(
                                width = res.first,
                                height = res.second,
                                lastFrameTimestamp = lastFrameTimeMs,
                                sessionId = sessionId
                            )
                        )
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
    fun reportServiceError(errorMessage: String, isFatal: Boolean = false) {
        Log.e(TAG, "Screen share service error: $errorMessage")
        heartbeatJob?.cancel()
        heartbeatJob = null
        updateState(ScreenShareState.Error(errorMessage, isFatal))
    }

    /**
     * Marks session stopped internally when projection stops.
     */
    fun onServiceStopped() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        cleanupResources()
        updateState(ScreenShareState.Stopped)
    }

    fun resetToIdle() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        consumedConsentTokenHashes.clear()
        cleanupResources()
        updateState(ScreenShareState.Idle)
    }

    private fun cleanupResources() {
        frameCounter.set(0)
        currentFps = 0
        lastFrameTimeMs = 0L
        latestFrameBitmap = null
    }
}
