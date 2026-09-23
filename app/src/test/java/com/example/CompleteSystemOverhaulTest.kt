package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.agent.*
import com.example.utils.AppResolver
import com.example.utils.InstalledApp
import com.example.utils.MatchConfidence
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class CompleteSystemOverhaulTest {

    private lateinit var context: Context
    private lateinit var appResolver: AppResolver
    private lateinit var contextEngine: ContextEngine

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        appResolver = AppResolver(context)
        contextEngine = ContextEngine()

        val mockApps = listOf(
            InstalledApp("WhatsApp", "com.whatsapp", "com.whatsapp.Main", "whatsapp", listOf("wa", "व्हाट्सएप")),
            InstalledApp("YouTube", "com.google.android.youtube", "com.google.android.youtube.HomeActivity", "youtube", listOf("yt", "यूट्यूब")),
            InstalledApp("Spotify", "com.spotify.music", "com.spotify.music.MainActivity", "spotify", listOf("स्पॉटिफ़ाई", "म्यूजिक")),
            InstalledApp("Google Play Store", "com.android.vending", "com.google.android.finsky.activities.MainActivity", "google play store", listOf("play store", "प्ले स्टोर")),
            InstalledApp("YT Create", "com.google.android.apps.youtube.creator", "com.google.android.apps.youtube.creator.MainActivity", "yt create", listOf("youtube create", "वाईटी क्रिएट")),
            InstalledApp("Settings", "com.android.settings", "com.android.settings.Settings", "settings", listOf("सेटिंग्स", "setting")),
            InstalledApp("Instagram", "com.instagram.android", "com.instagram.mainactivity.MainActivity", "instagram", listOf("insta", "ig"))
        )
        appResolver.setInstalledAppsForTesting(mockApps)
    }

    // Test 1: "Open WhatsApp and message my friend hello"
    @Test
    fun testCase1_WhatsAppMultiStepMessage() {
        val prompt = "Open WhatsApp and message my friend hello"
        val classified = UserCommandClassifier.classify(prompt, appResolver)

        assertTrue("Should be classified as multi-step", classified.isMultiStep)
        assertEquals("WhatsApp", classified.targetApp)
        assertEquals(2, classified.steps.size)

        val step1 = classified.steps[0]
        assertEquals(CommandCategory.OPEN_APP, step1.category)

        val step2 = classified.steps[1]
        assertEquals(CommandCategory.SEND_MESSAGE, step2.category)
        val parts = step2.param?.split("||")
        assertNotNull(parts)
        assertEquals("my friend", parts!![0].trim())
        assertEquals("hello", parts[1].trim())

        // Ensure "hello" and "my friend" are never passed as app names
        assertEquals("WhatsApp", classified.targetApp)
        assertFalse(classified.targetApp!!.contains("hello", ignoreCase = true))
        assertFalse(classified.targetApp!!.contains("my friend", ignoreCase = true))
    }

    // Test 2: "Open YouTube and search Minecraft"
    @Test
    fun testCase2_YouTubeMultiStepSearch() {
        val prompt = "Open YouTube and search Minecraft"
        val classified = UserCommandClassifier.classify(prompt, appResolver)

        assertTrue(classified.isMultiStep)
        assertEquals("YouTube", classified.targetApp)
        assertEquals(2, classified.steps.size)

        val step2 = classified.steps[1]
        assertEquals(CommandCategory.SEARCH_IN_APP, step2.category)
        assertEquals("Minecraft", step2.query)
    }

    // Test 3: Contextual search "Search Minecraft" when YouTube was already opened
    @Test
    fun testCase3_ContextualSearch() {
        // Simulate previous command context
        contextEngine.updateContext(appName = "YouTube", packageName = "com.google.android.youtube")

        val prompt = "Search Minecraft"
        val classified = UserCommandClassifier.classify(prompt, appResolver)
        assertEquals(CommandCategory.SEARCH_IN_APP, classified.category)
        assertEquals("Minecraft", classified.query)

        // ViewModel contextual fallback logic
        val effectiveApp = if (!classified.targetApp.isNullOrBlank()) classified.targetApp!! else contextEngine.activeTargetAppName
        assertEquals("YouTube", effectiveApp)
    }

    // Test 4: "Tap the first result"
    @Test
    fun testCase4_TapFirstResult() {
        val prompt = "Tap the first result"
        val classified = UserCommandClassifier.classify(prompt, appResolver)
        assertEquals(CommandCategory.INTERACT_IN_APP, classified.category)
    }

    // Test 5: "Open Spotify and play Believer"
    @Test
    fun testCase5_SpotifyMultiStepPlay() {
        val prompt = "Open Spotify and play Believer"
        val classified = UserCommandClassifier.classify(prompt, appResolver)

        assertTrue(classified.isMultiStep)
        assertEquals("Spotify", classified.targetApp)
        assertEquals(2, classified.steps.size)

        val step2 = classified.steps[1]
        assertEquals(CommandCategory.PLAY_MEDIA, step2.category)
        assertEquals("Believer", step2.query)
    }

    // Test 6: Contextual play "Play Believer"
    @Test
    fun testCase6_ContextualPlay() {
        contextEngine.updateContext(appName = "Spotify", packageName = "com.spotify.music")
        val prompt = "Play Believer"
        val classified = UserCommandClassifier.classify(prompt, appResolver)
        assertEquals(CommandCategory.PLAY_MEDIA, classified.category)
        assertEquals("Believer", classified.query)
    }

    // Test 7: "Open Play Store and search Telegram"
    @Test
    fun testCase7_PlayStoreMultiStepSearch() {
        val prompt = "Open Play Store and search Telegram"
        val classified = UserCommandClassifier.classify(prompt, appResolver)

        assertTrue(classified.isMultiStep)
        val res = appResolver.resolve(classified.targetApp ?: "Play Store")
        assertNotNull(res.matchedApp)
        assertEquals("com.android.vending", res.matchedApp?.packageName)

        val step2 = classified.steps[1]
        assertEquals(CommandCategory.SEARCH_IN_APP, step2.category)
        assertEquals("Telegram", step2.query)
    }

    // Test 8: "Open YouTube Create"
    @Test
    fun testCase8_YouTubeCreateAliasResolution() {
        val res = appResolver.resolve("YouTube Create")
        assertNotNull(res.matchedApp)
        assertEquals("com.google.android.apps.youtube.creator", res.matchedApp?.packageName)
        assertTrue(res.confidence == MatchConfidence.EXACT || res.confidence == MatchConfidence.HIGH)
    }

    // Test 9: "Open YT"
    @Test
    fun testCase9_YTAcronymResolution() {
        val res = appResolver.resolve("YT")
        assertNotNull(res.matchedApp)
        assertEquals("com.google.android.youtube", res.matchedApp?.packageName)
    }

    // Test 10: "Open Settings"
    @Test
    fun testCase10_SettingsResolution() {
        val res = appResolver.resolve("Settings")
        assertNotNull(res.matchedApp)
        assertEquals("com.android.settings", res.matchedApp?.packageName)
    }

    // Test 11: "Stop Kavya" and "रुको"
    @Test
    fun testCase11_StopCommands() {
        val classified1 = UserCommandClassifier.classify("Stop Kavya", appResolver)
        assertEquals(CommandCategory.STOP_AUTOMATION, classified1.category)

        val classified2 = UserCommandClassifier.classify("रुको", appResolver)
        assertEquals(CommandCategory.STOP_AUTOMATION, classified2.category)

        val classified3 = UserCommandClassifier.classify("stop", appResolver)
        assertEquals(CommandCategory.STOP_AUTOMATION, classified3.category)
    }

    // Test 12: "Sleep Kavya" and "सो जाओ"
    @Test
    fun testCase12_SleepCommands() {
        val classified1 = UserCommandClassifier.classify("Sleep Kavya", appResolver)
        assertEquals(CommandCategory.SLEEP, classified1.category)

        val classified2 = UserCommandClassifier.classify("सो जाओ", appResolver)
        assertEquals(CommandCategory.SLEEP, classified2.category)
    }

    // Test 13: Non-existent app must be strictly rejected (NO hallucination/guessing)
    @Test
    fun testCase13_NonExistentAppRejection() {
        val res = appResolver.resolve("fakeappthatdoesnotexist123")
        assertEquals(MatchConfidence.NONE, res.confidence)
        assertNull(res.matchedApp)
        assertTrue(res.reason.contains("No installed application found"))
    }

    // Test 14: Fast calculation / math without launching any app
    @Test
    fun testCase14_FastCalculation() {
        val prompt = "What is 25 * 4?"
        val result = com.example.ai.LocalAssistantEngine.handleFastQuery(prompt)
        assertNotNull(result)
        assertTrue(result!!.contains("100"))
    }

    // Test 15: Send button identifier validation
    @Test
    fun testCase15_SendButtonIdentification() {
        val element = UiElement(
            text = "Send",
            resourceId = "com.whatsapp:id/send",
            className = "android.widget.ImageButton",
            bounds = android.graphics.Rect(900, 1800, 1050, 1950),
            isClickable = true,
            isEnabled = true
        )
        assertTrue(element.isValidTarget())
        assertEquals("Send", element.primaryLabel)
        assertTrue(element.isClickable)
        assertTrue(element.isEnabled)
    }
}
