package com.example.services

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages the Android Screen Sharing / MediaProjection lifecycle for Kavya AI.
 * Ensures genuine Android MediaProjection + VirtualDisplay frame pipeline,
 * strict state verification, and eliminates fake/dummy success claims.
 */
object ScreenShareManager {

    private const val TAG = "KavyaScreenShareManager"

    sealed class ScreenShareState {
        object Idle : ScreenShareState()
        object RequestingPermission : ScreenShareState()
        object StartingService : ScreenShareState()
        data class Active(
            val width: Int,
            val height: Int,
            val fps: Int,
            val framesCaptured: Int,
            val lastFrameTimestamp: Long
        ) : ScreenShareState()
        object Stopped : ScreenShareState()
        data class PermissionDenied(val reason: String) : ScreenShareState()
        data class Error(val message: String) : ScreenShareState()
    }

    private val _state = MutableStateFlow<ScreenShareState>(ScreenShareState.Idle)
    val state: StateFlow<ScreenShareState> = _state.asStateFlow()

    private val frameCounter = AtomicInteger(0)
    private var lastFpsCalculationTime = 0L
    private var framesInLastSecond = 0
    private var currentFps = 0

    val isScreenSharingActive: Boolean
        get() = _state.value is ScreenShareState.Active

    var activeResolution: Pair<Int, Int>? = null
        private set

    var latestFrameBitmap: Bitmap? = null
        private set

    /**
     * Called when the user initiates screen sharing request.
     */
    fun createConsentIntent(context: Context): Intent? {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (manager == null) {
            _state.value = ScreenShareState.Error("MediaProjectionManager is unavailable on this device.")
            return null
        }
        _state.value = ScreenShareState.RequestingPermission
        return try {
            manager.createScreenCaptureIntent()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create screen capture intent", e)
            _state.value = ScreenShareState.Error("Failed to request screen capture: ${e.message}")
            null
        }
    }

    /**
     * Handles the result of the system MediaProjection consent dialog.
     */
    fun handleConsentResult(context: Context, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "Screen capture consent was denied or cancelled by user.")
            _state.value = ScreenShareState.PermissionDenied("User denied screen recording permission.")
            return
        }

        Log.i(TAG, "Screen capture consent granted. Starting Foreground Capture Service...")
        _state.value = ScreenShareState.StartingService

        val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ScreenCaptureService", e)
            _state.value = ScreenShareState.Error("Could not launch capture service: ${e.message}")
        }
    }

    /**
     * Invoked by ScreenCaptureService when a new frame is captured from the VirtualDisplay.
     */
    fun onFrameCaptured(bitmap: Bitmap, width: Int, height: Int) {
        val now = System.currentTimeMillis()
        val totalFrames = frameCounter.incrementAndGet()
        activeResolution = Pair(width, height)
        latestFrameBitmap = bitmap

        framesInLastSecond++
        if (now - lastFpsCalculationTime >= 1000L) {
            currentFps = framesInLastSecond
            framesInLastSecond = 0
            lastFpsCalculationTime = now
        }

        _state.value = ScreenShareState.Active(
            width = width,
            height = height,
            fps = currentFps,
            framesCaptured = totalFrames,
            lastFrameTimestamp = now
        )
    }

    /**
     * Stops screen capture session cleanly.
     */
    fun stopScreenShare(context: Context) {
        Log.i(TAG, "Stopping screen sharing session...")
        val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        try {
            context.startService(serviceIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send stop intent to ScreenCaptureService: ${e.message}")
        }
        frameCounter.set(0)
        currentFps = 0
        latestFrameBitmap = null
        _state.value = ScreenShareState.Stopped
    }

    /**
     * Reports an internal error from the service.
     */
    fun reportServiceError(errorMessage: String) {
        Log.e(TAG, "Screen share service error: $errorMessage")
        _state.value = ScreenShareState.Error(errorMessage)
    }

    /**
     * Marks session stopped internally when projection stops.
     */
    fun onServiceStopped() {
        frameCounter.set(0)
        currentFps = 0
        latestFrameBitmap = null
        _state.value = ScreenShareState.Stopped
    }
}
