package com.example.evolution

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity

object KavyaNotificationManager {
    private const val CHANNEL_ID_EVOLUTION = "kavya_evolution_channel"
    private const val CHANNEL_ID_SECURITY = "kavya_security_channel"
    private const val CHANNEL_ID_UPGRADE = "kavya_upgrade_channel"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val evolutionChannel = NotificationChannel(
                CHANNEL_ID_EVOLUTION,
                "Kavya Evolution Reports",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Daily open-source discovery and evolution scan reports"
            }

            val securityChannel = NotificationChannel(
                CHANNEL_ID_SECURITY,
                "Kavya Security Warnings",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Security quarantine and threat alerts"
            }

            val upgradeChannel = NotificationChannel(
                CHANNEL_ID_UPGRADE,
                "Kavya Upgrade & Rollback",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Upgrade status and rollback events"
            }

            notificationManager.createNotificationChannel(evolutionChannel)
            notificationManager.createNotificationChannel(securityChannel)
            notificationManager.createNotificationChannel(upgradeChannel)
        }
    }

    fun showEvolutionNotification(context: Context, title: String, message: String, reportId: String, type: String = "DAILY") {
        createChannels(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "evolution")
            putExtra("report_id", reportId)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            reportId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = when (type) {
            "SECURITY" -> CHANNEL_ID_SECURITY
            "UPGRADE" -> CHANNEL_ID_UPGRADE
            else -> CHANNEL_ID_EVOLUTION
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            notificationManager.notify(reportId.hashCode(), notification)
        } catch (e: Exception) {
            // Handle permission gracefully
        }
    }
}
