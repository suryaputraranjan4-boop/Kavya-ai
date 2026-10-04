package com.example.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Android Foreground Service Lifecycle Host for KavyaMicrophoneEngine.
 *
 * Guarantees continuous background listening when Kavya is backgrounded,
 * screen is off, or another app is active.
 *
 * DELEGATES 100% OF MICROPHONE OPERATION TO [KavyaMicrophoneEngine].
 */
class KavyaMicService : Service() {

    companion object {
        private const val TAG = "KavyaMicService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "kavya_mic_channel"
        private const val CHANNEL_NAME = "Kavya Voice Input"

        fun startService(context: Context) {
            val intent = Intent(context, KavyaMicService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, KavyaMicService::class.java)
            context.stopService(intent)
        }
    }

    private lateinit var engine: KavyaMicrophoneEngine

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "KavyaMicService onCreate: Initializing engine delegate...")
        engine = KavyaMicrophoneEngine.getInstance(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "KavyaMicService onStartCommand: Starting foreground notification & engine...")
        createNotificationChannel()
        val notification = buildForegroundNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        engine.start()

        return START_STICKY
    }

    override fun onDestroy() {

        Log.i(TAG, "KavyaMicService onDestroy: Stopping engine...")
        engine.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Kavya AI Active Microphone Listening"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildForegroundNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kavya AI Voice Active")
            .setContentText("Listening for commands...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }
}
