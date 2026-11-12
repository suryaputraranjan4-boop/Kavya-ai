package com.example.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.visual.PerformanceProfileManager
import com.example.visual.ScreenShareQualityMode
import com.example.visual.VisionPipeline

/**
 * Android Foreground Service for Real-time MediaProjection Screen Capture.
 * Full compliance with Android 14–16 (API 34–36) Foreground Service & MediaProjection rules:
 * - Immediate ServiceCompat.startForeground with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION.
 * - Privacy Pause / Resume notification action.
 * - MediaProjection.Callback & VirtualDisplay.Callback registration.
 * - Hardened ImageReader RGBA_8888 extraction with Chrome resize/IME/FLAG_SECURE/OOM guards.
 */
class ScreenCaptureService : Service(), ComponentCallbacks2 {

    companion object {
        private const val TAG = "KavyaScreenCaptureService"
        const val ACTION_START = "com.example.services.ACTION_START_CAPTURE"
        const val ACTION_STOP = "com.example.services.ACTION_STOP_CAPTURE"
        const val ACTION_TOGGLE_PAUSE = "com.example.services.ACTION_TOGGLE_PAUSE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val NOTIFICATION_CHANNEL_ID = "kavya_screen_capture_channel"
        private const val NOTIFICATION_ID = 8801
    }

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var currentDensityDpi: Int = DisplayMetrics.DENSITY_DEVICE_STABLE
    @Volatile
    private var reusableRawBufferBitmap: Bitmap? = null

    @Volatile
    private var activeSessionId: String = ""

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection was stopped by the system.")
            stopCapture()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            super.onCapturedContentResize(width, height)
            if (width <= 0 || height <= 0) return
            Log.i(TAG, "MediaProjection captured content resized to ${width}x${height}")
            resizeCapturePipeline(width, height, activeSessionId)
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            super.onCapturedContentVisibilityChanged(isVisible)
            Log.i(TAG, "MediaProjection captured content visibility changed: isVisible=$isVisible")
            ScreenShareManager.onCapturedContentVisibilityChanged(isVisible, activeSessionId)
        }
    }

    private val virtualDisplayCallback = object : VirtualDisplay.Callback() {
        override fun onPaused() {
            super.onPaused()
            Log.i(TAG, "VirtualDisplay paused by system.")
        }

        override fun onResumed() {
            super.onResumed()
            Log.i(TAG, "VirtualDisplay resumed by system.")
        }

        override fun onStopped() {
            super.onStopped()
            Log.i(TAG, "VirtualDisplay stopped by system.")
            if (activeSessionId.isNotBlank()) {
                stopCapture()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager

        val thread = HandlerThread("ScreenCaptureThread").apply { start() }
        backgroundThread = thread
        backgroundHandler = Handler(thread.looper)

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }

        if (action == ACTION_TOGGLE_PAUSE) {
            ScreenShareManager.toggleUserPause()
            updateNotificationOnly()
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            // Enter foreground immediately before inspecting extras so Android 14+ never throws
            // ForegroundServiceDidNotStartInTimeException even if token extraction fails.
            startForegroundWithNotification()

            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }
            val sessionId = intent.getStringExtra("EXTRA_SESSION_ID")

            if (resultCode == 0 || resultData == null || sessionId.isNullOrBlank()) {
                Log.e(TAG, "Invalid screen capture permission data or sessionId received.")
                ScreenShareManager.reportServiceError("Invalid screen capture permission token.")
                stopSelfCleanly()
                return START_NOT_STICKY
            }

            activeSessionId = sessionId
            startCapture(resultCode, resultData, sessionId)
        }

        return START_NOT_STICKY
    }

    private fun buildForegroundNotification(): Notification {
        val isPaused = ScreenShareManager.isUserPaused

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            this.action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = Intent(this, ScreenCaptureService::class.java).apply {
            this.action = ACTION_TOGGLE_PAUSE
        }
        val pausePendingIntent = PendingIntent.getService(
            this,
            1,
            pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(if (isPaused) "Kavya Screen Share (Paused)" else "Kavya Live Perception")
            .setContentText(
                if (isPaused) "Privacy Shield is ON — tap Resume to share screen again"
                else "Screen perception and vision are active across apps"
            )
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (isPaused) "Resume" else "Pause",
                pausePendingIntent
            )
            .addAction(android.R.drawable.ic_delete, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundWithNotification() {
        val notification = buildForegroundNotification()
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
    }

    private fun updateNotificationOnly() {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, buildForegroundNotification())
        } catch (_: Exception) {
        }
    }

    private fun queryDisplayDimensionsAndDpi(): Triple<Int, Int, Int> {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val densityDpi = resources.displayMetrics.densityDpi.coerceAtLeast(DisplayMetrics.DENSITY_MEDIUM)
        if (windowManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return try {
                val bounds = windowManager.maximumWindowMetrics.bounds
                Triple(
                    bounds.width().coerceAtLeast(360),
                    bounds.height().coerceAtLeast(360),
                    densityDpi
                )
            } catch (_: Exception) {
                Triple(
                    resources.displayMetrics.widthPixels.coerceAtLeast(360),
                    resources.displayMetrics.heightPixels.coerceAtLeast(360),
                    densityDpi
                )
            }
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        return Triple(
            metrics.widthPixels.coerceAtLeast(360),
            metrics.heightPixels.coerceAtLeast(360),
            metrics.densityDpi.coerceAtLeast(DisplayMetrics.DENSITY_MEDIUM)
        )
    }

    private fun startCapture(resultCode: Int, resultData: Intent, sessionId: String) {
        activeSessionId = sessionId
        try {
            val projection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
            if (projection == null) {
                ScreenShareManager.reportServiceError("Unable to initialize MediaProjection token.", isFatal = true)
                stopSelfCleanly()
                return
            }
            mediaProjection = projection

            // On Android 14+ registering callback is MANDATORY before createVirtualDisplay
            val callbackHandler = backgroundHandler ?: Handler(Looper.getMainLooper())
            projection.registerCallback(projectionCallback, callbackHandler)

            val (screenWidth, screenHeight, densityDpi) = queryDisplayDimensionsAndDpi()
            currentDensityDpi = densityDpi

            val perf = PerformanceProfileManager.loadAndRefresh(this)
            val maxCaptureWidth = perf.maxWidthPx.coerceAtLeast(360)

            val targetWidth = if (screenWidth > maxCaptureWidth) maxCaptureWidth else screenWidth
            val targetHeight = (screenHeight * (targetWidth.toFloat() / screenWidth.coerceAtLeast(1))).toInt().coerceAtLeast(1)

            val reader = createConfiguredImageReader(targetWidth, targetHeight, sessionId)
            imageReader = reader

            ScreenShareManager.onServiceStarted(sessionId)

            virtualDisplay = projection.createVirtualDisplay(
                "KavyaPerceptionDisplay",
                targetWidth,
                targetHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                virtualDisplayCallback,
                callbackHandler
            )

            Log.i(TAG, "VirtualDisplay created successfully (${targetWidth}x${targetHeight} @ ${densityDpi}dpi). Screen capture active.")
        } catch (t: Throwable) {
            Log.e(TAG, "Fatal failure starting virtual screen capture", t)
            ScreenShareManager.reportServiceError("Capture failed: ${t.message}", isFatal = true)
            stopSelfCleanly()
        }
    }

    private fun createConfiguredImageReader(targetWidth: Int, targetHeight: Int, sessionId: String): ImageReader {
        val reader = ImageReader.newInstance(targetWidth, targetHeight, PixelFormat.RGBA_8888, 2)
        var lastFrameExtractMs = 0L
        reader.setOnImageAvailableListener({ imageReaderInstance ->
            val image = try {
                imageReaderInstance.acquireLatestImage()
            } catch (_: Throwable) {
                null
            } ?: return@setOnImageAvailableListener

            try {
                if (sessionId != activeSessionId) return@setOnImageAvailableListener

                if (ScreenShareManager.isUserPaused) {
                    return@setOnImageAvailableListener
                }

                val now = System.currentTimeMillis()
                val minIntervalMs = PerformanceProfileManager.snapshot.value.minFrameIntervalMs
                if (now - lastFrameExtractMs < minIntervalMs) {
                    ScreenShareManager.onFrameHeartbeat(targetWidth, targetHeight, sessionId)
                    return@setOnImageAvailableListener
                }
                lastFrameExtractMs = now
                val extractStartMs = android.os.SystemClock.elapsedRealtime()

                val imgWidth = image.width.coerceAtLeast(1)
                val imgHeight = image.height.coerceAtLeast(1)
                val planes = image.planes
                if (planes.isEmpty()) return@setOnImageAvailableListener

                val plane = planes[0]
                val buffer = plane.buffer ?: return@setOnImageAvailableListener
                buffer.rewind()
                if (!buffer.hasRemaining()) return@setOnImageAvailableListener

                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                if (pixelStride <= 0 || rowStride <= 0) return@setOnImageAvailableListener

                val rowPadding = rowStride - pixelStride * imgWidth
                val paddedWidth = if (rowPadding > 0) {
                    (rowStride / pixelStride).coerceAtLeast(imgWidth)
                } else {
                    imgWidth
                }

                // Guard against partial/truncated buffers emitted during Chrome transitions or surface reconfigs
                val requiredBytes = rowStride.toLong() * (imgHeight - 1).toLong() + (pixelStride.toLong() * imgWidth)
                if (buffer.remaining().toLong() < requiredBytes) {
                    Log.w(TAG, "Skipping partial ImageReader buffer: remaining=${buffer.remaining()} < required=$requiredBytes")
                    return@setOnImageAvailableListener
                }

                val cleanBitmap = if (rowPadding <= 0) {
                    Bitmap.createBitmap(paddedWidth, imgHeight, Bitmap.Config.ARGB_8888).apply {
                        copyPixelsFromBuffer(buffer)
                    }
                } else {
                    val scratch = reusableRawBufferBitmap
                    val rawBitmap = if (scratch != null && !scratch.isRecycled &&
                        scratch.width == paddedWidth && scratch.height == imgHeight
                    ) {
                        scratch
                    } else {
                        if (scratch != null && !scratch.isRecycled) {
                            scratch.recycle()
                        }
                        Bitmap.createBitmap(paddedWidth, imgHeight, Bitmap.Config.ARGB_8888).also {
                            reusableRawBufferBitmap = it
                        }
                    }
                    rawBitmap.copyPixelsFromBuffer(buffer)
                    val safeCropW = minOf(targetWidth, rawBitmap.width).coerceAtLeast(1)
                    val safeCropH = minOf(targetHeight, rawBitmap.height).coerceAtLeast(1)
                    Bitmap.createBitmap(rawBitmap, 0, 0, safeCropW, safeCropH)
                }

                // Detect FLAG_SECURE / Chrome Incognito / DRM black buffer safely via 5-point sample
                if (isProtectedBlackFrame(cleanBitmap)) {
                    ScreenShareManager.updateProtectedSurfaceStatus(true, sessionId)
                    ScreenShareManager.onFrameHeartbeat(cleanBitmap.width, cleanBitmap.height, sessionId)
                    if (!cleanBitmap.isRecycled) cleanBitmap.recycle()
                    return@setOnImageAvailableListener
                } else {
                    ScreenShareManager.updateProtectedSurfaceStatus(false, sessionId)
                }

                if (ScreenShareManager.onFrameCaptured(cleanBitmap, cleanBitmap.width, cleanBitmap.height, sessionId)) {
                    VisionPipeline.onNewFrame(cleanBitmap)
                    PerformanceProfileManager.recordFrameProcessingLatencyMs(
                        android.os.SystemClock.elapsedRealtime() - extractStartMs
                    )
                } else if (!cleanBitmap.isRecycled) {
                    cleanBitmap.recycle()
                }
            } catch (oom: OutOfMemoryError) {
                Log.e(TAG, "OOM processing VirtualDisplay frame — freeing scratch buffer & downgrading to LOW_END", oom)
                reusableRawBufferBitmap?.let { if (!it.isRecycled) it.recycle() }
                reusableRawBufferBitmap = null
                PerformanceProfileManager.setQualityMode(applicationContext, ScreenShareQualityMode.LOW_END)
            } catch (t: Throwable) {
                Log.w(TAG, "Exception processing VirtualDisplay frame: ${t.message}")
            } finally {
                try {
                    image.close()
                } catch (_: Throwable) {
                }
            }
        }, backgroundHandler)
        return reader
    }

    private fun isProtectedBlackFrame(bitmap: Bitmap): Boolean {
        return try {
            val w = bitmap.width
            val h = bitmap.height
            if (w < 4 || h < 4) return false
            val points = arrayOf(
                bitmap.getPixel(w / 2, h / 2),
                bitmap.getPixel(w / 4, h / 4),
                bitmap.getPixel((w * 3) / 4, h / 4),
                bitmap.getPixel(w / 4, (h * 3) / 4),
                bitmap.getPixel((w * 3) / 4, (h * 3) / 4)
            )
            // SurfaceFlinger zeroes RGB and/or alpha on FLAG_SECURE protected windows (Chrome Incognito / DRM)
            points.all { pixel -> (pixel and 0x00FFFFFF) == 0 }
        } catch (_: Throwable) {
            false
        }
    }

    private fun resizeCapturePipeline(rawWidth: Int, rawHeight: Int, sessionId: String) {
        if (sessionId.isBlank() || sessionId != activeSessionId) return
        try {
            val perf = PerformanceProfileManager.loadAndRefresh(this)
            val maxCaptureWidth = perf.maxWidthPx.coerceAtLeast(360)
            val targetWidth = if (rawWidth > maxCaptureWidth) maxCaptureWidth else rawWidth.coerceAtLeast(1)
            val targetHeight = (rawHeight * (targetWidth.toFloat() / rawWidth.coerceAtLeast(1))).toInt().coerceAtLeast(1)

            val oldReader = imageReader
            // Detach callback on old reader first so in-flight resize never races old/new dimensions
            try {
                oldReader?.setOnImageAvailableListener(null, null)
            } catch (_: Exception) {
            }

            reusableRawBufferBitmap?.let { if (!it.isRecycled) it.recycle() }
            reusableRawBufferBitmap = null

            val newReader = createConfiguredImageReader(targetWidth, targetHeight, sessionId)
            imageReader = newReader
            virtualDisplay?.resize(targetWidth, targetHeight, currentDensityDpi)
            virtualDisplay?.surface = newReader.surface

            try {
                oldReader?.close()
            } catch (_: Exception) {
            }

            ScreenShareManager.onCapturedContentResized(targetWidth, targetHeight, sessionId)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to dynamically resize VirtualDisplay/ImageReader: ${t.message}")
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            reusableRawBufferBitmap?.let { if (!it.isRecycled) it.recycle() }
            reusableRawBufferBitmap = null
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        com.example.visual.ScreenAnnotationManager.onConfigurationChanged(this)
    }

    private fun stopSelfCleanly() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    private fun stopCapture() {
        val stoppedSessionId = activeSessionId
        if (stoppedSessionId.isBlank() && mediaProjection == null && virtualDisplay == null && imageReader == null) {
            stopSelfCleanly()
            return
        }
        activeSessionId = ""
        Log.i(TAG, "Cleaning up screen capture service resources...")
        try {
            virtualDisplay?.release()
            virtualDisplay = null

            try {
                imageReader?.setOnImageAvailableListener(null, null)
            } catch (_: Exception) {
            }
            imageReader?.close()
            imageReader = null

            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null

            reusableRawBufferBitmap?.let { if (!it.isRecycled) it.recycle() }
            reusableRawBufferBitmap = null
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning projection: ${e.message}")
        }

        ScreenShareManager.onServiceStopped(stoppedSessionId)
        VisionPipeline.reset()
        stopSelfCleanly()
    }

    override fun onDestroy() {
        stopCapture()
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Screen Perception Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifies when Kavya is actively perceiving your screen"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
