package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.agent.FileAgent
import com.example.agent.UniversalActionType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FileAgentTest {

    @Test
    fun createAndReadFileUsesRealStorage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val agent = FileAgent(context)
        val fileName = "file-agent-${UUID.randomUUID()}.txt"

        val created = agent.createFile(fileName, "Kavya file read test", stepId = 11)
        assertTrue(created.success)
        assertTrue(created.output.contains(fileName))

        val read = agent.readFile(fileName, stepId = 12)
        assertTrue(read.success)
        assertTrue(read.output.contains("Kavya file read test"))
    }

    @Test
    fun unsupportedFileActionsNeverReportSuccess() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result = FileAgent(context).executeFileAction(
            UniversalActionType.DELETE_FILE,
            "missing.txt",
            "",
            stepId = 13
        )

        assertFalse(result.success)
        assertTrue(result.output.contains("not implemented", ignoreCase = true))
    }

    @Test
    fun missingFileNeverReportsSuccessfulRead() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result = FileAgent(context).readFile("missing-${UUID.randomUUID()}.txt", stepId = 14)

        assertFalse(result.success)
    }
}
