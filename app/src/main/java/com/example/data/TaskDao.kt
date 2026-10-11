package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    // --- Durable Tasks ---
    @Query("SELECT * FROM durable_tasks ORDER BY updatedAt DESC")
    fun getAllTasksFlow(): Flow<List<DurableTaskEntity>>

    @Query("SELECT * FROM durable_tasks WHERE id = :taskId LIMIT 1")
    suspend fun getTaskById(taskId: String): DurableTaskEntity?

    @Query("SELECT * FROM durable_tasks WHERE status IN ('PENDING', 'RUNNING', 'CHECKPOINTED', 'PAUSED') ORDER BY updatedAt DESC")
    suspend fun getActiveTasks(): List<DurableTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: DurableTaskEntity)

    /** Persist the task and its steps atomically so a failed second insert cannot leave an orphan task. */
    @Transaction
    suspend fun insertTaskWithSteps(task: DurableTaskEntity, steps: List<DurableTaskStepEntity>) {
        insertTask(task)
        if (steps.isNotEmpty()) insertSteps(steps)
    }

    @Update
    suspend fun updateTask(task: DurableTaskEntity)

    @Query("UPDATE durable_tasks SET status = :status, currentStepIndex = :currentStepIndex, updatedAt = :updatedAt, resultSummary = :result, lastError = :error WHERE id = :taskId")
    suspend fun updateTaskProgress(
        taskId: String,
        status: String,
        currentStepIndex: Int,
        updatedAt: Long,
        result: String?,
        error: String?
    )

    @Query("DELETE FROM durable_tasks WHERE id = :taskId")
    suspend fun deleteTask(taskId: String)

    // --- Durable Task Steps ---
    @Query("SELECT * FROM durable_task_steps WHERE taskId = :taskId ORDER BY stepIndex ASC")
    fun getStepsForTaskFlow(taskId: String): Flow<List<DurableTaskStepEntity>>

    @Query("SELECT * FROM durable_task_steps WHERE taskId = :taskId ORDER BY stepIndex ASC")
    suspend fun getStepsForTask(taskId: String): List<DurableTaskStepEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSteps(steps: List<DurableTaskStepEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStep(step: DurableTaskStepEntity)

    @Update
    suspend fun updateStep(step: DurableTaskStepEntity)

    /** Update only checkpoint fields; preserve the original title, action, target, parameters, and expected state. */
    @Query("UPDATE durable_task_steps SET status = :status, actualState = :actualState, result = :result, failureReason = :failureReason, timestamp = :timestamp WHERE id = :stepId")
    suspend fun updateStepCheckpoint(
        stepId: String,
        status: String,
        actualState: String,
        result: String?,
        failureReason: String?,
        timestamp: Long
    ): Int

    @Query("DELETE FROM durable_task_steps WHERE taskId = :taskId")
    suspend fun deleteStepsForTask(taskId: String)

    // --- Scheduled Tasks ---
    @Query("SELECT * FROM scheduled_tasks ORDER BY nextRunTimestamp ASC")
    fun getAllScheduledTasksFlow(): Flow<List<ScheduledTaskEntity>>

    @Query("SELECT * FROM scheduled_tasks WHERE isActive = 1 ORDER BY nextRunTimestamp ASC")
    suspend fun getActiveScheduledTasks(): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_tasks WHERE id = :scheduleId LIMIT 1")
    suspend fun getScheduledTaskById(scheduleId: String): ScheduledTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScheduledTask(scheduledTask: ScheduledTaskEntity)

    @Update
    suspend fun updateScheduledTask(scheduledTask: ScheduledTaskEntity)

    @Query("UPDATE scheduled_tasks SET isActive = :isActive WHERE id = :scheduleId")
    suspend fun toggleScheduledTaskActive(scheduleId: String, isActive: Boolean)

    @Query("UPDATE scheduled_tasks SET lastRunTimestamp = :lastRun, nextRunTimestamp = :nextRun WHERE id = :scheduleId")
    suspend fun updateScheduledTaskRunTimes(scheduleId: String, lastRun: Long, nextRun: Long)

    @Delete
    suspend fun deleteScheduledTask(scheduledTask: ScheduledTaskEntity)

    @Query("DELETE FROM scheduled_tasks WHERE id = :scheduleId")
    suspend fun deleteScheduledTaskById(scheduleId: String)
}
