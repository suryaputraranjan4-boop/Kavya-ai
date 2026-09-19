package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.agent.ContextEngine
import com.example.agent.MemoryEngine
import com.example.agent.TaskPlanner
import com.example.agent.UniversalActionType
import com.example.utils.AppResolver
import com.example.utils.InstalledApp
import com.example.utils.MatchConfidence
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AcceptanceVerificationTest {

    private lateinit var context: Context
    private lateinit var appResolver: AppResolver
    private lateinit var contextEngine: ContextEngine
    private lateinit var taskPlanner: TaskPlanner
    private lateinit var memoryEngine: MemoryEngine

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        appResolver = AppResolver(context)
        contextEngine = ContextEngine()
        taskPlanner = TaskPlanner(appResolver)
        memoryEngine = MemoryEngine(context)

        val mockApps = listOf(
            InstalledApp("YouTube", "com.google.android.youtube", "com.google.android.youtube.HomeActivity", "youtube", listOf("yt", "यूट्यूब")),
            InstalledApp("Google Chrome", "com.android.chrome", "com.google.android.apps.chrome.Main", "google chrome", listOf("chrome", "क्रोम")),
            InstalledApp("Instagram", "com.instagram.android", "com.instagram.mainactivity.MainActivity", "instagram", listOf("insta", "ig", "इंस्टाग्राम")),
            InstalledApp("WhatsApp", "com.whatsapp", "com.whatsapp.Main", "whatsapp", listOf("wa", "व्हाट्सएप")),
            InstalledApp("Calculator", "com.google.android.calculator", "com.android.calculator2.Calculator", "calculator", listOf("calc", "कैलकुलेटर")),
            InstalledApp("Settings", "com.android.settings", "com.android.settings.Settings", "settings", listOf("सेटिंग्स", "setting"))
        )
        appResolver.setInstalledAppsForTesting(mockApps)
    }

    @Test
    fun testCase1_YouTubeKholo() {
        val res = appResolver.resolve("YouTube खोलो")
        assertNotNull(res.matchedApp)
        assertEquals("com.google.android.youtube", res.matchedApp?.packageName)
        assertEquals(MatchConfidence.EXACT, res.confidence)
    }

    @Test
    fun testCase2_YouTubeMinecraftSearch() {
        val plan = taskPlanner.createPlan("YouTube पर Minecraft search करो", contextEngine)
        assertNotNull(plan)
        assertEquals("YouTube", plan?.targetAppName)
        assertTrue(plan!!.steps.isNotEmpty())
        assertEquals(UniversalActionType.OPEN_APP, plan.steps[0].actionType)
    }

    @Test
    fun testCase3_InstagramKholo() {
        val res = appResolver.resolve("Instagram खोलो")
        assertNotNull(res.matchedApp)
        assertEquals("com.instagram.android", res.matchedApp?.packageName)
    }

    @Test
    fun testCase4_ChromeWeatherSearch() {
        val plan = taskPlanner.createPlan("Chrome पर weather search करो", contextEngine)
        assertNotNull(plan)
        assertEquals("Google Chrome", plan?.targetAppName)
        assertTrue(plan!!.steps.isNotEmpty())
        assertEquals(UniversalActionType.OPEN_APP, plan.steps[0].actionType)
    }

    @Test
    fun testCase5_CalculatorKholo() {
        val res = appResolver.resolve("Calculator खोलो")
        assertNotNull(res.matchedApp)
        assertEquals("com.google.android.calculator", res.matchedApp?.packageName)
    }

    @Test
    fun testCase6_WhatsAppKholo() {
        val res = appResolver.resolve("WhatsApp खोलो")
        assertNotNull(res.matchedApp)
        assertEquals("com.whatsapp", res.matchedApp?.packageName)
    }

    @Test
    fun testCase7_SettingsKholo() {
        val res = appResolver.resolve("Settings खोलो")
        assertNotNull(res.matchedApp)
        assertEquals("com.android.settings", res.matchedApp?.packageName)
    }

    @Test
    fun testCase8_9_MemoryPersistenceAndRecall() = runBlocking {
        // Save preference: "मेरी favorite movie Inception याद रखो"
        val saveResult = memoryEngine.processAndExtractMemory(
            "मेरी favorite movie Inception याद रखो",
            "मेरी favorite movie Inception याद रखो"
        )
        assertTrue(saveResult.detected)
        assertTrue(saveResult.confirmationText.contains("Inception") || saveResult.confirmationText.contains("movie") || saveResult.confirmationText.contains("याद"))

        // Recall: "मुझे क्या याद है?" or favorite movie query
        val recallResult = memoryEngine.handleConversationalRecall("मुझे क्या याद है?")
        assertNotNull(recallResult)
        assertTrue(recallResult!!.contains("Inception"))
    }
}

