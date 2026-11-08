package com.example.scheduler

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Native Android BroadcastReceiver handling AlarmManager triggers,
 * notification actions (Snooze, Dismiss), and device boot restoration (Requirement 15, 16).
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "KavyaAlarmReceiver"
        const val CHANNEL_ID = "kavya_alarms_channel"
        const val CHANNEL_NAME = "Kavya Alarms & Reminders"
        const val NOTIFICATION_ID_BASE = 50000
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "onReceive: action=$action")

        val pendingResult = goAsync()
        val scope = CoroutineScope(Dispatchers.IO)

        scope.launch {
            try {
                when (action) {
                    SchedulerEngine.ACTION_TRIGGER_SCHEDULE -> {
                        val scheduleId = intent.getStringExtra(SchedulerEngine.EXTRA_SCHEDULE_ID) ?: ""
                        handleAlarmTrigger(context, scheduleId)
                    }
                    SchedulerEngine.ACTION_ALARM_SNOOZE -> {
                        val scheduleId = intent.getStringExtra(SchedulerEngine.EXTRA_SCHEDULE_ID) ?: ""
                        handleAlarmSnooze(context, scheduleId)
                    }
                    SchedulerEngine.ACTION_ALARM_DISMISS -> {
                        val scheduleId = intent.getStringExtra(SchedulerEngine.EXTRA_SCHEDULE_ID) ?: ""
                        handleAlarmDismiss(context, scheduleId)
                    }
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED -> {
                        handleBootOrTimeChange(context)
                    }
                    else -> {
                        Log.d(TAG, "Unhandled action: $action")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing alarm action $action: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleAlarmTrigger(context: Context, scheduleId: String) {
        val db = AppDatabase.getDatabase(context)
        val taskDao = db.taskDao()
        val schedule = taskDao.getScheduledTaskById(scheduleId)

        val title = schedule?.title?.ifBlank { "Alarm" } ?: "Alarm"
        val message = schedule?.promptOrAction?.ifBlank { "Time to wake up!" } ?: "Alarm ringing"
        val notifId = NOTIFICATION_ID_BASE + (scheduleId.hashCode() and 0x7FFF)

        createNotificationChannel(context)

        // Show Intent when tapped
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Snooze Intent (10 minutes)
        val snoozeIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = SchedulerEngine.ACTION_ALARM_SNOOZE
            putExtra(SchedulerEngine.EXTRA_SCHEDULE_ID, scheduleId)
        }
        val snoozePendingIntent = PendingIntent.getBroadcast(
            context,
            notifId + 1,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Dismiss Intent
        val dismissIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = SchedulerEngine.ACTION_ALARM_DISMISS
            putExtra(SchedulerEngine.EXTRA_SCHEDULE_ID, scheduleId)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            notifId + 2,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("⏰ $title")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openPendingIntent)
            .setAutoCancel(true)
            .setSound(alarmSound)
            .setVibrate(longArrayOf(0, 600, 300, 600, 300, 600))
            .addAction(0, "Snooze (10m)", snoozePendingIntent)
            .addAction(0, "Dismiss", dismissPendingIntent)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(notifId, notification)

        // Update database and reschedule if repeating
        val now = System.currentTimeMillis()
        if (schedule != null) {
            val isRecurring = schedule.scheduleType != "ONE_TIME"
            val scheduler = SchedulerEngine(context, taskDao)
            val nextRun = if (isRecurring) {
                scheduler.calculateNextRunTimestamp(schedule.scheduleType, schedule.hour, schedule.minute, schedule.dayOfWeek)
            } else {
                0L
            }
            taskDao.updateScheduledTaskRunTimes(schedule.id, now, nextRun)
            if (!isRecurring) {
                taskDao.toggleScheduledTaskActive(schedule.id, false)
            } else {
                scheduler.createSchedule(
                    title = schedule.title,
                    promptOrAction = schedule.promptOrAction,
                    scheduleType = schedule.scheduleType,
                    hour = schedule.hour,
                    minute = schedule.minute,
                    dayOfWeek = schedule.dayOfWeek
                )
            }
        }
    }

    private suspend fun handleAlarmSnooze(context: Context, scheduleId: String) {
        val notifId = NOTIFICATION_ID_BASE + (scheduleId.hashCode() and 0x7FFF)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancel(notifId)

        val db = AppDatabase.getDatabase(context)
        val taskDao = db.taskDao()
        val schedule = taskDao.getScheduledTaskById(scheduleId)

        val snoozeTime = System.currentTimeMillis() + 10 * 60 * 1000L // 10 minutes
        Log.i(TAG, "Alarm $scheduleId snoozed for 10 minutes (until $snoozeTime)")

        val scheduler = SchedulerEngine(context, taskDao)
        if (schedule != null) {
            val snoozedSchedule = schedule.copy(
                nextRunTimestamp = snoozeTime,
                isActive = true
            )
            taskDao.updateScheduledTask(snoozedSchedule)
            scheduler.scheduleExactAlarm(snoozedSchedule)
        }
    }

    private fun handleAlarmDismiss(context: Context, scheduleId: String) {
        val notifId = NOTIFICATION_ID_BASE + (scheduleId.hashCode() and 0x7FFF)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancel(notifId)
        Log.i(TAG, "Alarm $scheduleId dismissed.")
    }

    private suspend fun handleBootOrTimeChange(context: Context) {
        Log.i(TAG, "Restoring alarms after boot / time change...")
        val db = AppDatabase.getDatabase(context)
        val taskDao = db.taskDao()
        val scheduler = SchedulerEngine(context, taskDao)
        val activeSchedules = taskDao.getActiveScheduledTasks()

        val now = System.currentTimeMillis()
        for (schedule in activeSchedules) {
            val targetTimestamp = if (schedule.nextRunTimestamp > now) {
                schedule.nextRunTimestamp
            } else {
                scheduler.calculateNextRunTimestamp(schedule.scheduleType, schedule.hour, schedule.minute, schedule.dayOfWeek)
            }
            val updated = schedule.copy(nextRunTimestamp = targetTimestamp, isActive = true)
            taskDao.updateScheduledTask(updated)
            scheduler.scheduleExactAlarm(updated)
            Log.d(TAG, "Restored alarm ${schedule.title} for $targetTimestamp")
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()

                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications and audio for scheduled alarms and reminders"
                    enableLights(true)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
                    setSound(soundUri, audioAttributes)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }
}
