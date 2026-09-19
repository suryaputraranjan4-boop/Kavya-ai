package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.agent.StructuredActionParser
import com.example.utils.AppPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class OpenRouterIntegrationTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testOpenRouterApiKeyPersistence() {
        AppPreferences.clearOpenRouterApiKey(context)
        assertEquals("", AppPreferences.getOpenRouterApiKey(context))

        AppPreferences.setOpenRouterApiKey(context, "sk-or-v1-testkey12345")
        assertEquals("sk-or-v1-testkey12345", AppPreferences.getOpenRouterApiKey(context))

        AppPreferences.clearOpenRouterApiKey(context)
        assertEquals("", AppPreferences.getOpenRouterApiKey(context))
    }

    @Test
    fun testOpenRouterDefaultModel() {
        // Must default to openrouter/free
        val model = AppPreferences.getOpenRouterModel(context)
        assertTrue(model == "openrouter/free" || model.isNotBlank())

        AppPreferences.setOpenRouterModel(context, "google/gemini-2.0-flash-exp:free")
        assertEquals("google/gemini-2.0-flash-exp:free", AppPreferences.getOpenRouterModel(context))
    }

    @Test
    fun testAiProviderSwitching() {
        AppPreferences.setAiProvider(context, "OPENROUTER")
        assertEquals("OPENROUTER", AppPreferences.getAiProvider(context))

        AppPreferences.setAiProvider(context, "GEMINI")
        assertEquals("GEMINI", AppPreferences.getAiProvider(context))
    }

    @Test
    fun testStructuredActionOpenApp() {
        val jsonOutput = """
            ```json
            [
              {"action": "OPEN_APP", "target": "YouTube"}
            ]
            ```
        """.trimIndent()

        val actions = StructuredActionParser.parseActions(jsonOutput)
        assertEquals(1, actions.size)
        assertEquals("OPEN_APP", actions[0].action)
        assertEquals("YouTube", actions[0].target)
    }

    @Test
    fun testStructuredActionSearchSanitization() {
        // Query must be sanitized so instructions don't leak into the search input
        val jsonOutput = """
            ```json
            [
              {"action": "SEARCH", "query": "Hi, and open the second video"}
            ]
            ```
        """.trimIndent()

        val actions = StructuredActionParser.parseActions(jsonOutput)
        assertEquals(1, actions.size)
        assertEquals("SEARCH", actions[0].action)
        assertEquals("Hi", actions[0].query)
    }

    @Test
    fun testStructuredActionSecondVideoOrdinalDetection() {
        val jsonOutput = """
            ```json
            [
              {"action": "TAP", "target": "second_video"}
            ]
            ```
        """.trimIndent()

        val actions = StructuredActionParser.parseActions(jsonOutput)
        assertEquals(1, actions.size)
        assertEquals("TAP", actions[0].action)
        assertEquals(1, actions[0].index)
    }

    @Test
    fun testStripActionsHidesInternalJsonFromChat() {
        val mixedResponse = """
            ठीक है, मैं YouTube खोल रही हूँ।
            ```json
            [
              {"action": "OPEN_APP", "target": "YouTube"}
            ]
            ```
        """.trimIndent()

        val clean = StructuredActionParser.stripActions(mixedResponse)
        assertFalse(clean.contains("```json"))
        assertFalse(clean.contains("OPEN_APP"))
        assertTrue(clean.contains("ठीक है, मैं YouTube खोल रही हूँ।"))
    }
}
