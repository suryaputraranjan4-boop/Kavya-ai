package com.example.agent

import android.content.Context
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.DurableTaskEntity
import com.example.data.DurableTaskStepEntity
import com.example.data.TaskDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

enum class TaskExecutionStatus {
    PENDING,
    RUNNING,
    CHECKPOINTED,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class DurableTaskOutcome(
    val taskId: String,
    val success: Boolean,
    val completedSteps: Int,
    val totalSteps: Int,
    val resultSummary: String,
    val failureReason: String? = null
)

/**
 * Long-Running Durable Task Engine (Requirements 12, 14, 26, 27).
 * Separates conversation lifecycle from task lifecycle and persists checkpoints in Room.
 */
class DurableTaskEngine(
    private val context: Context,
    private val taskDao: TaskDao = AppDatabase.getDatabase(context).taskDao()
) {
    companion object {
        private const val TAG = "KavyaDurableTaskEngine"
    }

    val allTasksFlow: Flow<List<DurableTaskEntity>> = taskDao.getAllTasksFlow()

    suspend fun createAndPersistTask(
        title: String,
        intentCategory: IntentCategory,
        steps: List<GateStep>
    ): DurableTaskEntity = withContext(Dispatchers.IO) {
        val taskId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val taskEntity = DurableTaskEntity(
            id = taskId,
            title = title,
            intentCategory = intentCategory.name,
            status = TaskExecutionStatus.PENDING.name,
            totalSteps = steps.size,
            currentStepIndex = 0,
            createdAt = now,
            updatedAt = now
        )

        val stepEntities = steps.mapIndexed { index, step ->
            DurableTaskStepEntity(
                id = "${taskId}_step_${index + 1}",
                taskId = taskId,
                stepIndex = index + 1,
                title = step.actionType.name,
                actionType = step.actionType.name,
                targetApp = step.targetApp,
                param = step.param ?: step.query,
                status = TaskExecutionStatus.PENDING.name,
                expectedState = "Execution of ${step.actionType.name}",
                actualState = "Awaiting execution",
                timestamp = now
            )
        }

        taskDao.insertTask(taskEntity)
        taskDao.insertSteps(stepEntities)
        Log.d(TAG, "Created durable task $taskId with ${steps.size} steps")
        return@withContext taskEntity
    }

    suspend fun recordStepCheckpoint(
        taskId: String,
        stepIndex: Int,
        success: Boolean,
        actualState: String,
        result: String?,
        failureReason: String? = null
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val status = if (success) TaskExecutionStatus.COMPLETED else TaskExecutionStatus.FAILED
        val stepId = "${taskId}_step_$stepIndex"

        taskDao.updateStep(
            DurableTaskStepEntity(
                id = stepId,
                taskId = taskId,
                stepIndex = stepIndex,
                title = "Step $stepIndex",
                actionType = "ACTION",
                status = status.name,
                actualState = actualState,
                result = result,
                failureReason = failureReason,
                timestamp = now
            )
        )

        val taskStatus = if (success) TaskExecutionStatus.CHECKPOINTED.name else TaskExecutionStatus.FAILED.name
        taskDao.updateTaskProgress(
            taskId = taskId,
            status = taskStatus,
            currentStepIndex = stepIndex,
            updatedAt = now,
            result = result,
            error = failureReason
        )
        Log.d(TAG, "Persisted checkpoint for task $taskId step $stepIndex ($taskStatus)")
    }

    suspend fun finalizeTask(
        taskId: String,
        success: Boolean,
        summary: String,
        error: String? = null
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val status = if (success) TaskExecutionStatus.COMPLETED.name else TaskExecutionStatus.FAILED.name
        val task = taskDao.getTaskById(taskId)
        val finalStep = task?.totalSteps ?: 1

        taskDao.updateTaskProgress(
            taskId = taskId,
            status = status,
            currentStepIndex = finalStep,
            updatedAt = now,
            result = summary,
            error = error
        )
        Log.d(TAG, "Finalized durable task $taskId: $status")
    }

    suspend fun getResumableTasks(): List<DurableTaskEntity> = withContext(Dispatchers.IO) {
        return@withContext taskDao.getActiveTasks()
    }
}
