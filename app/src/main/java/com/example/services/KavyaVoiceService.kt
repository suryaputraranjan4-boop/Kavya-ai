package com.example.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
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
            AppPreferences.setMicEnabled(context, true)
            AppPreferences.setKavyaState(context, "ACTIVE")
            val intent = Intent(context, KavyaVoiceService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            AppPreferences.setMicEnabled(context, false)
            AppPreferences.setKavyaState(context, "OFF")
            val intent = Intent(context, KavyaVoiceService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun sleep(context: Context) {
            val intent = Intent(context, KavyaVoiceService::class.java).apply {
                action = ACTION_SLEEP
            }
            context.startService(intent)
        }

        fun wake(context: Context) {
            val intent = Intent(context, KavyaVoiceService::class.java).apply {
                action = ACTION_WAKE
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.d(TAG, "onStartCommand received action: $action")

        val micEngine = MicrophoneEngine.getInstance(this)

        // Stop must remain possible even when the runtime microphone permission
        // has been revoked. Do not promote a stop-only request to a mic FGS.
        if (action == ACTION_STOP) {
            micEngine.stopListening()
            AppPreferences.setMicEnabled(this, false)
            AppPreferences.setKavyaState(this, "OFF")
            stopForeground(true)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Android 14+ requires RECORD_AUDIO before a microphone foreground
        // service can be promoted. Check before calling startForeground().
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Microphone permission missing; refusing to start microphone foreground service.")
            micEngine.stopListening()
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
        } catch (e: SecurityException) {
            Log.e(TAG, "Android rejected microphone foreground-service promotion.", e)
            micEngine.stopListening()
            AppPreferences.setMicEnabled(this, false)
            AppPreferences.setKavyaState(this, "OFF")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_START, ACTION_WAKE -> {
                micEngine.setSleeping(false)
                if (!micEngine.startListening()) {
                    Log.e(TAG, "Microphone failed to start; stopping foreground service instead of leaving a false listening state.")
                    AppPreferences.setMicEnabled(this, false)
                    AppPreferences.setKavyaState(this, "OFF")
                    stopForeground(true)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
            }
            ACTION_SLEEP -> {
                micEngine.setSleeping(true)
            }
            ACTION_TOGGLE -> {
                val currentState = micEngine.engineState.value
                if (currentState == MicrophoneEngine.State.IDLE ||
                    currentState == MicrophoneEngine.State.STOPPING ||
                    currentState == MicrophoneEngine.State.ERROR
                ) {
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
                    AppPreferences.setMicEnabled(this, false)
                    AppPreferences.setKavyaState(this, "OFF")
                    stopForeground(true)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
            }
        }

        return START_STICKY
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
                description = "Keeps Kavya AI active for continuous voice interaction and wake word detection."
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kavya AI Listening")
            .setContentText("Continuous voice assistant active • Say 'Hey Kavya'")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
