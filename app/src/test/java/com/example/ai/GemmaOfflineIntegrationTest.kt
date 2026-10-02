package com.example.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.offline.GemmaModelManager
import com.example.ai.offline.GemmaModelStatus
import com.example.ai.providers.AgentOrchestrator
import com.example.ai.providers.GemmaOfflineProvider
import com.example.utils.AppPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GemmaOfflineIntegrationTest {

    private lateinit var context: Context
    private lateinit var gemmaProvider: GemmaOfflineProvider

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        gemmaProvider = GemmaOfflineProvider()
    }

    @Test
    fun testGemmaProviderMetadata() {
        assertEquals("gemma_offline", gemmaProvider.providerId)
        assertEquals("Gemma 4 E4B (Offline)", gemmaProvider.displayName)
    }

    @Test
    fun testMissingModelHandling() {
        // Without importing model file, status should report false / NOT_INSTALLED
        val isConfigured = gemmaProvider.isConfigured(context)
        val validation = kotlinx.coroutines.runBlocking { gemmaProvider.validateCredentials(context) }

        if (!isConfigured) {
            assertFalse(validation.first)
            assertTrue(validation.second.contains("not found", ignoreCase = true))
        }
    }

    @Test
    fun testOfflineRoutingWhenSelected() {
        // When Gemma provider is explicitly selected
        AppPreferences.setAiProvider(context, "GEMMA_OFFLINE")
        AppPreferences.setOfflineFallbackEnabled(context, true)

        // Mock model file in manager to enable offline routing
        val testFile = java.io.File(GemmaModelManager.getModelDirectory(context), "gemma-4-e4b.bin")
        testFile.writeBytes(ByteArray(11 * 1024 * 1024)) // >10MB mock model

        val decision = TaskRouter.routeTask("What is the speed of light?", false, context)
        assertEquals("GEMMA_OFFLINE", decision.provider)
        assertEquals("Gemma 4 E4B", decision.selectedModel)

        // Clean up mock file
        testFile.delete()
    }

    @Test
    fun testOnlineRoutingPreservedWhenGeminiSelected() {
        AppPreferences.setAiProvider(context, "GEMINI")
        AppPreferences.setCustomApiKey(context, "AIzaSyTestApiKeyForVerification12345")
        AppPreferences.setOfflineFallbackEnabled(context, false)

        val decision = TaskRouter.routeTask("Explain quantum computing", false, context)
        assertEquals("GEMINI", decision.provider)
    }

    @Test
    fun testStructuredToolCallParsing() {
        val validJsonOutput = """
            YouTube खोल रही हूँ।
            ```json
            [
              {
                "action": "OPEN_APP",
                "target": "YouTube"
              }
            ]
            ```
        """.trimIndent()

        val jsonStartIndex = validJsonOutput.indexOf("```json")
        val jsonEndIndex = validJsonOutput.indexOf("```", jsonStartIndex + 7)
        assertTrue(jsonStartIndex >= 0)
        assertTrue(jsonEndIndex > jsonStartIndex)

        val rawJson = validJsonOutput.substring(jsonStartIndex + 7, jsonEndIndex).trim()
        val array = JSONArray(rawJson)
        assertEquals(1, array.length())

        val actionObj = array.getJSONObject(0)
        assertEquals("OPEN_APP", actionObj.getString("action"))
        assertEquals("YouTube", actionObj.getString("target"))
    }

    @Test
    fun testInvalidToolCallRejection() {
        val malformedOutput = "This is plain text without any valid JSON action."
        val jsonStartIndex = malformedOutput.indexOf("```json")
        assertEquals(-1, jsonStartIndex)
    }

    @Test
    fun testAgentOrchestratorIncludesGemma() {
        val orchestrator = AgentOrchestrator()
        val allProviders = orchestrator.getAllProviders()

        val gemmaInList = allProviders.any { it.providerId == "gemma_offline" }
        assertTrue(gemmaInList)
    }

    @Test
    fun testMemoryContextFormatting() {
        val memoryContext = "User preference: Name is Rahul, Tone is friendly."
        val screenContext = "App: YouTube, Title: Home"
        val sysPrompt = SystemPrompt.buildSystemPrompt(screenContext, memoryContext, false)

        assertTrue(sysPrompt.contains("Rahul"))
        assertTrue(sysPrompt.contains("YouTube"))
        assertTrue(sysPrompt.contains("GEMMA"))
    }
}
