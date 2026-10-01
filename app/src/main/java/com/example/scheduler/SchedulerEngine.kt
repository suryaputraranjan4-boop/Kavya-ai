package com.example.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.ScheduledTaskEntity
import com.example.data.TaskDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.UUID

/**
 * Native Android Persistent Task and Reminder Scheduler (Requirements 15, 16).
 * Backed by Room Database and Android AlarmManager for non-volatile execution across restarts.
 */
class SchedulerEngine(
    private val context: Context,
    private val taskDao: TaskDao = AppDatabase.getDatabase(context).taskDao()
) {
    companion object {
        private const val TAG = "KavyaSchedulerEngine"
        const val ACTION_TRIGGER_SCHEDULE = "com.example.action.TRIGGER_SCHEDULED_TASK"
        const val EXTRA_SCHEDULE_ID = "extra_schedule_id"
    }

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    val allSchedulesFlow: Flow<List<ScheduledTaskEntity>> = taskDao.getAllScheduledTasksFlow()

    suspend fun createSchedule(
        title: String,
        promptOrAction: String,
        scheduleType: String, // ONE_TIME, DAILY, WEEKLY, WEEKDAYS
        hour: Int,
        minute: Int,
        dayOfWeek: Int = -1
    ): ScheduledTaskEntity = withContext(Dispatchers.IO) {
        val nextRun = calculateNextRunTimestamp(scheduleType, hour, minute, dayOfWeek)
        val schedule = ScheduledTaskEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            promptOrAction = promptOrAction,
            scheduleType = scheduleType,
            hour = hour,
            minute = minute,
            dayOfWeek = dayOfWeek,
            isActive = true,
            nextRunTimestamp = nextRun
        )

        taskDao.insertScheduledTask(schedule)
        scheduleSystemAlarm(schedule)
        Log.d(TAG, "Created persistent schedule ${schedule.id} for $hour:$minute (Next: $nextRun)")
        return@withContext schedule
    }

    suspend fun toggleActive(scheduleId: String, isActive: Boolean) = withContext(Dispatchers.IO) {
        taskDao.toggleScheduledTaskActive(scheduleId, isActive)
        val schedule = taskDao.getScheduledTaskById(scheduleId)
        if (schedule != null) {
            if (isActive) {
                scheduleSystemAlarm(schedule)
            } else {
                cancelSystemAlarm(schedule.id)
            }
        }
    }

    suspend fun deleteSchedule(scheduleId: String) = withContext(Dispatchers.IO) {
        cancelSystemAlarm(scheduleId)
        taskDao.deleteScheduledTaskById(scheduleId)
    }

    suspend fun executeDueSchedules(onTrigger: suspend (ScheduledTaskEntity) -> Unit) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val active = taskDao.getActiveScheduledTasks()
        for (item in active) {
            if (item.nextRunTimestamp in 1..now) {
                Log.d(TAG, "Triggering due schedule: ${item.title}")
                val next = calculateNextRunTimestamp(item.scheduleType, item.hour, item.minute, item.dayOfWeek)
                val isStillActive = item.scheduleType != "ONE_TIME"
                taskDao.updateScheduledTaskRunTimes(item.id, now, if (isStillActive) next else 0L)
                if (!isStillActive) {
                    taskDao.toggleScheduledTaskActive(item.id, false)
                }
                onTrigger(item)
            }
        }
    }

    fun calculateNextRunTimestamp(
        scheduleType: String,
        hour: Int,
        minute: Int,
        dayOfWeek: Int
    ): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val now = System.currentTimeMillis()
        if (scheduleType == "ONE_TIME" || scheduleType == "DAILY") {
            if (calendar.timeInMillis <= now) {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
            }
        } else if (scheduleType == "WEEKLY" && dayOfWeek > 0) {
            calendar.set(Calendar.DAY_OF_WEEK, dayOfWeek)
            if (calendar.timeInMillis <= now) {
                calendar.add(Calendar.WEEK_OF_YEAR, 1)
            }
        }
        return calendar.timeInMillis
    }

    private fun scheduleSystemAlarm(schedule: ScheduledTaskEntity) {
        if (alarmManager == null || schedule.nextRunTimestamp <= System.currentTimeMillis()) return

        val intent = Intent(ACTION_TRIGGER_SCHEDULE).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_SCHEDULE_ID, schedule.id)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            schedule.id.hashCode(),
            intent,
            flags
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    schedule.nextRunTimestamp,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    schedule.nextRunTimestamp,
                    pendingIntent
                )
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm permission not granted, falling back to inexact alarm: ${e.message}")
            alarmManager.set(AlarmManager.RTC_WAKEUP, schedule.nextRunTimestamp, pendingIntent)
        }
    }

    private fun cancelSystemAlarm(scheduleId: String) {
        if (alarmManager == null) return
        val intent = Intent(ACTION_TRIGGER_SCHEDULE).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_NO_CREATE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            scheduleId.hashCode(),
            intent,
            flags
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }
}
