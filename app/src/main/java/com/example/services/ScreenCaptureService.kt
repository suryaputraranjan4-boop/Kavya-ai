package com.example.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import com.example.MainActivity
import com.example.visual.VisionPipeline

/**
 * Android Foreground Service for Real-time MediaProjection Screen Capture.
 * Full compliance with Android 14+ (API 34) Foreground Service & MediaProjection rules:
 * - Proper FOREGROUND_SERVICE_MEDIA_PROJECTION declaration & notification.
 * - MediaProjection.Callback registration prior to VirtualDisplay creation.
 * - Hardware VirtualDisplay -> ImageReader RGBA_8888 -> Live Frame processing.
 * - Feeding frames to ScreenShareManager & VisionPipeline.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "KavyaScreenCaptureService"
        const val ACTION_START = "com.example.services.ACTION_START_CAPTURE"
        const val ACTION_STOP = "com.example.services.ACTION_STOP_CAPTURE"
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

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection was stopped by the system.")
            stopCapture()
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

        if (action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }

            if (resultCode == 0 || resultData == null) {
                Log.e(TAG, "Invalid screen capture permission data received.")
                ScreenShareManager.reportServiceError("Invalid screen capture permission token.")
                stopSelf()
                return START_NOT_STICKY
            }

            startForegroundWithNotification()
            startCapture(resultCode, resultData)
        }

        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            this.action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Kavya Live Perception")
            .setContentText("Screen perception and vision are active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_delete, "Stop Sharing", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        try {
            val projection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
            if (projection == null) {
                ScreenShareManager.reportServiceError("Unable to initialize MediaProjection token.")
                stopSelf()
                return
            }
            mediaProjection = projection

            // On Android 14+ registering callback is MANDATORY before createVirtualDisplay
            projection.registerCallback(projectionCallback, backgroundHandler ?: Handler(Looper.getMainLooper()))

            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)

            // Scale down to high-performance perception resolution (max width 720)
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels
            val densityDpi = metrics.densityDpi

            val targetWidth = if (screenWidth > 720) 720 else screenWidth
            val targetHeight = (screenHeight * (targetWidth.toFloat() / screenWidth)).toInt()

            val reader = ImageReader.newInstance(targetWidth, targetHeight, PixelFormat.RGBA_8888, 2)
            imageReader = reader

            reader.setOnImageAvailableListener({ imageReaderInstance ->
                val image = imageReaderInstance.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * targetWidth

                    val rawBitmap = Bitmap.createBitmap(
                        targetWidth + rowPadding / pixelStride,
                        targetHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    rawBitmap.copyPixelsFromBuffer(buffer)

                    val cleanBitmap = if (rowPadding == 0) {
                        rawBitmap
                    } else {
                        Bitmap.createBitmap(rawBitmap, 0, 0, targetWidth, targetHeight)
                    }

                    ScreenShareManager.onFrameCaptured(cleanBitmap, targetWidth, targetHeight)
                    VisionPipeline.onNewFrame(cleanBitmap)
                } catch (e: Exception) {
                    Log.w(TAG, "Exception processing VirtualDisplay frame: ${e.message}")
                } finally {
                    image.close()
                }
            }, backgroundHandler)

            virtualDisplay = projection.createVirtualDisplay(
                "KavyaPerceptionDisplay",
                targetWidth,
                targetHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                backgroundHandler
            )

            Log.i(TAG, "VirtualDisplay created successfully (${targetWidth}x${targetHeight} @ ${densityDpi}dpi). Screen capture active.")
        } catch (e: Exception) {
            Log.e(TAG, "Fatal failure starting virtual screen capture", e)
            ScreenShareManager.reportServiceError("Capture failed: ${e.message}")
            stopSelf()
        }
    }

    private fun stopCapture() {
        Log.i(TAG, "Cleaning up screen capture service resources...")
        try {
            virtualDisplay?.release()
            virtualDisplay = null

            imageReader?.close()
            imageReader = null

            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning projection: ${e.message}")
        }

        ScreenShareManager.onServiceStopped()
        VisionPipeline.reset()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
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
