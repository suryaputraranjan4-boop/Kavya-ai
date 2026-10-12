package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "durable_tasks")
data class DurableTaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val intentCategory: String,
    val status: String, // PENDING, RUNNING, CHECKPOINTED, PAUSED, COMPLETED, FAILED, CANCELLED
    val totalSteps: Int,
    val currentStepIndex: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val resultSummary: String? = null,
    val lastError: String? = null
)

@Entity(
    tableName = "durable_task_steps",
    indices = [Index(value = ["taskId"])]
)
data class DurableTaskStepEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val stepIndex: Int,
    val title: String,
    val actionType: String,
    val targetApp: String? = null,
    val param: String? = null,
    val status: String, // PENDING, RUNNING, COMPLETED, FAILED, SKIPPED
    val expectedState: String? = null,
    val actualState: String? = null,
    val result: String? = null,
    val failureReason: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "scheduled_tasks")
data class ScheduledTaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val promptOrAction: String,
    val scheduleType: String, // ONE_TIME, DAILY, WEEKLY, WEEKDAYS
    val hour: Int,
    val minute: Int,
    val dayOfWeek: Int = -1, // -1 if daily/one_time
    val isActive: Boolean = true,
    val lastRunTimestamp: Long = 0L,
    val nextRunTimestamp: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)
