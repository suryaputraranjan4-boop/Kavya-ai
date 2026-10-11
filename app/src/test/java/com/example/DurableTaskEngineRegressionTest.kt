package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.agent.DurableTaskEngine
import com.example.data.AppDatabase
import com.example.data.DurableTaskEntity
import com.example.data.DurableTaskStepEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DurableTaskEngineRegressionTest {

    @Test
    fun checkpointPreservesOriginalStepMetadata() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dao = AppDatabase.getDatabase(context).taskDao()
        val taskId = "regression-${UUID.randomUUID()}"
        val stepId = "${taskId}_step_1"
        val now = System.currentTimeMillis()

        dao.insertTask(
            DurableTaskEntity(
                id = taskId,
                title = "Open YouTube",
                intentCategory = "ACTION",
                status = "RUNNING",
                totalSteps = 1,
                currentStepIndex = 0,
                createdAt = now,
                updatedAt = now
            )
        )
        dao.insertStep(
            DurableTaskStepEntity(
                id = stepId,
                taskId = taskId,
                stepIndex = 1,
                title = "Open YouTube",
                actionType = "OPEN_APP",
                targetApp = "com.google.android.youtube",
                param = "YouTube",
                status = "PENDING",
                expectedState = "YouTube is foreground",
                actualState = "Awaiting execution",
                timestamp = now
            )
        )

        try {
            val engine = DurableTaskEngine(context, dao)
            engine.recordStepCheckpoint(
                taskId = taskId,
                stepIndex = 1,
                success = true,
                actualState = "com.google.android.youtube is foreground",
                result = "verified"
            )

            val savedStep = dao.getStepsForTask(taskId).single()
            assertEquals("Open YouTube", savedStep.title)
            assertEquals("OPEN_APP", savedStep.actionType)
            assertEquals("com.google.android.youtube", savedStep.targetApp)
            assertEquals("YouTube", savedStep.param)
            assertEquals("YouTube is foreground", savedStep.expectedState)
            assertEquals("COMPLETED", savedStep.status)
            assertEquals("com.google.android.youtube is foreground", savedStep.actualState)
            assertEquals("verified", savedStep.result)
            assertNull(savedStep.failureReason)

            val savedTask = dao.getTaskById(taskId)
            assertTrue(savedTask != null)
            assertEquals("CHECKPOINTED", savedTask?.status)
            assertEquals(1, savedTask?.currentStepIndex)
        } finally {
            dao.deleteStepsForTask(taskId)
            dao.deleteTask(taskId)
        }
    }
}
