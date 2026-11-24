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
import java.util.TimeZone
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
        const val ACTION_ALARM_SNOOZE = "com.example.action.ALARM_SNOOZE"
        const val ACTION_ALARM_DISMISS = "com.example.action.ALARM_DISMISS"
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

    /**
     * Creates an advanced recurring schedule or monitoring trigger (MONTHLY, INTERVAL_<N>M,
     * MONITOR_POLL_<N>M, DAILY, WEEKLY, WEEKDAYS, ONE_TIME) with timezone/DST awareness.
     */
    suspend fun createAdvancedSchedule(
        title: String,
        promptOrAction: String,
        scheduleType: String,
        hour: Int = 9,
        minute: Int = 0,
        dayOfWeek: Int? = null,
        timeZoneId: String = TimeZone.getDefault().id
    ): ScheduledTaskEntity = withContext(Dispatchers.IO) {
        val normalizedDay = dayOfWeek ?: -1
        val nextRun = calculateNextRunTimestamp(
            scheduleType = scheduleType,
            hour = hour,
            minute = minute,
            dayOfWeek = normalizedDay,
            timeZoneId = timeZoneId
        )
        val schedule = ScheduledTaskEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            promptOrAction = promptOrAction,
            scheduleType = scheduleType,
            hour = hour,
            minute = minute,
            dayOfWeek = normalizedDay,
            isActive = true,
            nextRunTimestamp = nextRun
        )
        taskDao.insertScheduledTask(schedule)
        scheduleSystemAlarm(schedule)
        Log.d(TAG, "Created advanced schedule ${schedule.id} ($scheduleType, tz=$timeZoneId, next=$nextRun)")
        schedule
    }

    /**
     * Immediately runs a persisted schedule through UnifiedAgentOrchestrator and updates lastRunTimestamp.
     */
    suspend fun runScheduleNow(scheduleId: String): com.example.agent.OrchestrationRunSummary? = withContext(Dispatchers.IO) {
        val schedule = taskDao.getScheduledTaskById(scheduleId) ?: return@withContext null
        val now = System.currentTimeMillis()
        val summary = com.example.agent.UnifiedAgentOrchestrator.getInstance(context).executeScheduledTask(schedule)
        val next = if (schedule.scheduleType != "ONE_TIME") {
            calculateNextRunTimestamp(schedule.scheduleType, schedule.hour, schedule.minute, schedule.dayOfWeek)
        } else {
            schedule.nextRunTimestamp
        }
        taskDao.updateScheduledTaskRunTimes(schedule.id, now, next)
        summary
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
        dayOfWeek: Int,
        timeZoneId: String = TimeZone.getDefault().id,
        nowMs: Long = System.currentTimeMillis()
    ): Long {
        val tz = TimeZone.getTimeZone(timeZoneId.ifBlank { TimeZone.getDefault().id })
        val upper = scheduleType.trim().uppercase(java.util.Locale.ROOT)

        // Interval or monitoring poll schedules: INTERVAL_<N>M or MONITOR_POLL_<N>M
        val intervalMatch = Regex("^(?:INTERVAL|MONITOR_POLL)_(\\d+)M$").find(upper)
        if (intervalMatch != null) {
            val minutes = intervalMatch.groupValues[1].toLongOrNull()?.coerceIn(1L, 43_200L) ?: 60L
            return nowMs + minutes * 60_000L
        }

        val calendar = Calendar.getInstance(tz).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        when {
            upper == "ONE_TIME" || upper == "DAILY" || upper == "AGENT_DAILY" -> {
                if (calendar.timeInMillis <= nowMs) {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            upper == "WEEKDAYS" -> {
                while (
                    calendar.timeInMillis <= nowMs ||
                    calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY ||
                    calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
                ) {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            (upper == "WEEKLY" || upper == "AGENT_WEEKLY") -> {
                if (dayOfWeek in Calendar.SUNDAY..Calendar.SATURDAY) {
                    calendar.set(Calendar.DAY_OF_WEEK, dayOfWeek)
                }
                if (calendar.timeInMillis <= nowMs) {
                    calendar.add(Calendar.WEEK_OF_YEAR, 1)
                }
            }
            upper == "MONTHLY" || upper == "AGENT_MONTHLY" -> {
                if (dayOfWeek in 1..28) {
                    calendar.set(Calendar.DAY_OF_MONTH, dayOfWeek)
                }
                if (calendar.timeInMillis <= nowMs) {
                    calendar.add(Calendar.MONTH, 1)
                }
            }
            else -> {
                if (calendar.timeInMillis <= nowMs) {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                }
            }
        }
        return calendar.timeInMillis
    }

    fun scheduleExactAlarm(schedule: ScheduledTaskEntity) {
        scheduleSystemAlarm(schedule)
    }

    private fun scheduleSystemAlarm(schedule: ScheduledTaskEntity) {
        if (alarmManager == null || schedule.nextRunTimestamp <= System.currentTimeMillis()) return

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_TRIGGER_SCHEDULE
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
            val showIntent = Intent(context, com.example.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val showPendingIntent = PendingIntent.getActivity(
                context,
                schedule.id.hashCode(),
                showIntent,
                flags
            )
            val alarmClockInfo = AlarmManager.AlarmClockInfo(
                schedule.nextRunTimestamp,
                showPendingIntent
            )
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
            Log.i(TAG, "Scheduled alarm clock for ${schedule.title} at ${schedule.nextRunTimestamp}")
        } catch (e: Exception) {
            Log.w(TAG, "setAlarmClock not permitted, falling back to setExactAndAllowWhileIdle: ${e.message}")
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
            } catch (secEx: SecurityException) {
                Log.w(TAG, "Exact alarm permission not granted, falling back to inexact alarm: ${secEx.message}")
                alarmManager.set(AlarmManager.RTC_WAKEUP, schedule.nextRunTimestamp, pendingIntent)
            }
        }
    }

    private fun cancelSystemAlarm(scheduleId: String) {
        if (alarmManager == null) return
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_TRIGGER_SCHEDULE
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
