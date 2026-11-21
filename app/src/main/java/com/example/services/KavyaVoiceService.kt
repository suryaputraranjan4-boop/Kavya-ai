package com.example.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.agent.MicrophoneEngine
import com.example.utils.AppPreferences

/**
 * Foreground Service controlling background audio capture lifecycle.
 *
 * Guarantees compliance with Android 14+ foreground microphone service policies.
 * Delegates physical recording exclusively to [MicrophoneEngine].
 */
class KavyaVoiceService : Service() {

    companion object {
        private const val TAG = "KavyaVoiceService"
        const val NOTIFICATION_ID = 2001
        const val CHANNEL_ID = "kavya_voice_service_channel"

        const val ACTION_START = "com.example.action.START_MIC"
        const val ACTION_STOP = "com.example.action.STOP_MIC"
        const val ACTION_SLEEP = "com.example.action.SLEEP_MIC"
        const val ACTION_WAKE = "com.example.action.WAKE_MIC"
        const val ACTION_TOGGLE = "com.example.action.TOGGLE_MIC"

        fun startService(context: Context) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Refusing to start KavyaVoiceService: RECORD_AUDIO permission not granted.")
                AppPreferences.setMicEnabled(context, false)
                AppPreferences.setKavyaState(context, "OFF")
                return
            }
            AppPreferences.setMicEnabled(context, true)
            AppPreferences.setKavyaState(context, "ACTIVE")
            val intent = Intent(context, KavyaVoiceService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start KavyaVoiceService: ${e.message}")
                AppPreferences.setMicEnabled(context, false)
                AppPreferences.setKavyaState(context, "OFF")
            }
        }

        fun stopService(context: Context) {
            AppPreferences.setMicEnabled(context, false)
            AppPreferences.setKavyaState(context, "OFF")
            try {
                MicrophoneEngine.getInstance(context).stopListening()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping MicrophoneEngine directly: ${e.message}")
            }
            try {
                context.stopService(Intent(context, KavyaVoiceService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping KavyaVoiceService: ${e.message}")
            }
        }

        fun sleep(context: Context) {
            try {
                MicrophoneEngine.getInstance(context).setSleeping(true)
            } catch (e: Exception) {
                Log.w(TAG, "Error putting MicrophoneEngine to sleep: ${e.message}")
            }
        }

        fun wake(context: Context) {
            try {
                MicrophoneEngine.getInstance(context).setSleeping(false)
            } catch (e: Exception) {
                Log.w(TAG, "Error waking MicrophoneEngine: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.d(TAG, "onStartCommand received action: $action")

        if (action == ACTION_STOP) {
            MicrophoneEngine.getInstance(this).stopListening()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO missing in onStartCommand — refusing microphone FGS start to avoid Android 14+ SecurityException.")
            AppPreferences.setMicEnabled(this, false)
            AppPreferences.setKavyaState(this, "OFF")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException starting microphone foreground service: ${se.message}")
            AppPreferences.setMicEnabled(this, false)
            AppPreferences.setKavyaState(this, "OFF")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val micEngine = MicrophoneEngine.getInstance(this)

        when (action) {
            ACTION_START, ACTION_WAKE -> {
                micEngine.setSleeping(false)
                if (!micEngine.startListening()) {
                    Log.e(TAG, "Microphone failed to start; stopping foreground service instead of leaving a false listening state.")
                    AppPreferences.setMicEnabled(this, false)
                    AppPreferences.setKavyaState(this, "OFF")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
            }
            ACTION_SLEEP -> {
                micEngine.setSleeping(true)
            }
            ACTION_STOP -> {
                micEngine.stopListening()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_TOGGLE -> {
                val currentState = micEngine.engineState.value
                if (currentState == MicrophoneEngine.State.IDLE || currentState == MicrophoneEngine.State.STOPPING || currentState == MicrophoneEngine.State.ERROR) {
                    if (!micEngine.startListening()) {
                        Log.e(TAG, "Microphone toggle start failed; stopping foreground service.")
                        AppPreferences.setMicEnabled(this, false)
                        AppPreferences.setKavyaState(this, "OFF")
                        stopForeground(true)
                        stopSelf(startId)
                        return START_NOT_STICKY
                    }
                } else {
                    micEngine.stopListening()
                    stopSelf()
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        MicrophoneEngine.getInstance(this).stopListening()
        Log.i(TAG, "KavyaVoiceService destroyed and microphone released.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Kavya Voice Assistant Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Visible microphone indicator while Kavya AI voice listening is active."
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, KavyaVoiceService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kavya AI Microphone Active")
            .setContentText("Voice listening active • Tap Stop anytime")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Listening", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
