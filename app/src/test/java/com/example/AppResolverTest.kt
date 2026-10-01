package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.utils.AppResolver
import com.example.utils.InstalledApp
import com.example.utils.MatchConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AppResolverTest {

    private lateinit var context: Context
    private lateinit var appResolver: AppResolver

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        appResolver = AppResolver(context)

        // Inject verified mock installed apps for critical benchmark test suite:
        // YouTube, Spotify, Chrome, Camera, Settings, Calculator, Instagram, WhatsApp
        val mockApps = listOf(
            InstalledApp(
                appName = "YouTube",
                packageName = "com.google.android.youtube",
                launcherActivity = "com.google.android.youtube.HomeActivity",
                normalizedName = "youtube",
                aliases = listOf("yt", "यूट्यूब")
            ),
            InstalledApp(
                appName = "Google Chrome",
                packageName = "com.android.chrome",
                launcherActivity = "com.google.android.apps.chrome.Main",
                normalizedName = "google chrome",
                aliases = listOf("chrome", "क्रोम")
            ),
            InstalledApp(
                appName = "Spotify",
                packageName = "com.spotify.music",
                launcherActivity = "com.spotify.music.MainActivity",
                normalizedName = "spotify",
                aliases = listOf("स्पॉटिफ़ाई")
            ),
            InstalledApp(
                appName = "Camera",
                packageName = "com.google.android.GoogleCamera",
                launcherActivity = "com.android.camera.CameraLauncher",
                normalizedName = "camera",
                aliases = listOf("कैमरा", "cam")
            ),
            InstalledApp(
                appName = "Settings",
                packageName = "com.android.settings",
                launcherActivity = "com.android.settings.Settings",
                normalizedName = "settings",
                aliases = listOf("सेटिंग्स", "setting")
            ),
            InstalledApp(
                appName = "Calculator",
                packageName = "com.google.android.calculator",
                launcherActivity = "com.android.calculator2.Calculator",
                normalizedName = "calculator",
                aliases = listOf("calc", "कैलकुलेटर")
            ),
            InstalledApp(
                appName = "Instagram",
                packageName = "com.instagram.android",
                launcherActivity = "com.instagram.mainactivity.MainActivity",
                normalizedName = "instagram",
                aliases = listOf("insta", "ig", "इंस्टाग्राम")
            ),
            InstalledApp(
                appName = "WhatsApp",
                packageName = "com.whatsapp",
                launcherActivity = "com.whatsapp.Main",
                normalizedName = "whatsapp",
                aliases = listOf("wa", "व्हाट्सएप")
            ),
            InstalledApp(
                appName = "YouTube Music",
                packageName = "com.google.android.apps.youtube.music",
                launcherActivity = "com.google.android.apps.youtube.music.activities.MusicActivity",
                normalizedName = "youtube music",
                aliases = listOf("yt music")
            )
        )
        appResolver.setInstalledAppsForTesting(mockApps)
    }

    @Test
    fun testNaturalLanguageParsing_EnglishHindiHinglish() {
        assertEquals("YouTube", appResolver.extractAppNameFromNaturalLanguage("Open YouTube"))
        assertEquals("YouTube", appResolver.extractAppNameFromNaturalLanguage("open the YouTube app"))
        assertEquals("YouTube", appResolver.extractAppNameFromNaturalLanguage("YouTube kholo"))
        assertEquals("YouTube", appResolver.extractAppNameFromNaturalLanguage("YouTube khol do"))
        assertEquals("Spotify", appResolver.extractAppNameFromNaturalLanguage("Spotify open karo"))
        assertEquals("Spotify", appResolver.extractAppNameFromNaturalLanguage("Spotify chalao"))
        assertEquals("Camera", appResolver.extractAppNameFromNaturalLanguage("Camera kholo"))
        assertEquals("कैमरा", appResolver.extractAppNameFromNaturalLanguage("कैमरा खोलो"))
        assertEquals("Settings", appResolver.extractAppNameFromNaturalLanguage("Settings open kar"))
        assertEquals("Calculator", appResolver.extractAppNameFromNaturalLanguage("Launch Calculator"))
        assertEquals("Instagram", appResolver.extractAppNameFromNaturalLanguage("Instagram kholo"))
        assertEquals("WhatsApp", appResolver.extractAppNameFromNaturalLanguage("WhatsApp open karo"))
        assertEquals("यूट्यूब", appResolver.extractAppNameFromNaturalLanguage("यूट्यूब चालू करो"))
    }

    @Test
    fun testMandatoryAppSuite_ExactResolutions() {
        // 1. YouTube
        val yt = appResolver.resolve("Open YouTube")
        assertEquals("com.google.android.youtube", yt.matchedApp?.packageName)
        assertEquals(MatchConfidence.EXACT, yt.confidence)

        val ytHinglish = appResolver.resolve("YouTube kholo")
        assertEquals("com.google.android.youtube", ytHinglish.matchedApp?.packageName)

        // 2. Spotify
        val spotify = appResolver.resolve("Spotify open karo")
        assertEquals("com.spotify.music", spotify.matchedApp?.packageName)
        assertEquals(MatchConfidence.EXACT, spotify.confidence)

        // 3. Chrome
        val chrome = appResolver.resolve("Launch Google Chrome")
        assertEquals("com.android.chrome", chrome.matchedApp?.packageName)

        val chromeAlias = appResolver.resolve("Chrome khol do")
        assertEquals("com.android.chrome", chromeAlias.matchedApp?.packageName)

        // 4. Camera
        val camera = appResolver.resolve("Camera kholo")
        assertEquals("com.google.android.GoogleCamera", camera.matchedApp?.packageName)

        // 5. Settings
        val settings = appResolver.resolve("Open Settings")
        assertEquals("com.android.settings", settings.matchedApp?.packageName)

        // 6. Calculator
        val calc = appResolver.resolve("Calculator kholo")
        assertEquals("com.google.android.calculator", calc.matchedApp?.packageName)

        val calcAlias = appResolver.resolve("Open calc")
        assertEquals("com.google.android.calculator", calcAlias.matchedApp?.packageName)

        // 7. Instagram
        val insta = appResolver.resolve("Open Instagram")
        assertEquals("com.instagram.android", insta.matchedApp?.packageName)

        val instaAlias = appResolver.resolve("Insta kholo")
        assertEquals("com.instagram.android", instaAlias.matchedApp?.packageName)

        // 8. WhatsApp
        val wa = appResolver.resolve("WhatsApp open karo")
        assertEquals("com.whatsapp", wa.matchedApp?.packageName)

        val waAlias = appResolver.resolve("WA kholo")
        assertEquals("com.whatsapp", waAlias.matchedApp?.packageName)
    }

    @Test
    fun testNeverGuessOrLaunchUnrelatedApp() {
        // Unknown or fake app names MUST return NONE confidence and NULL matchedApp
        val nonExistent1 = appResolver.resolve("Open RandomFictionalAppXYZ")
        assertEquals(MatchConfidence.NONE, nonExistent1.confidence)
        assertNull(nonExistent1.matchedApp)

        val nonExistent2 = appResolver.resolve("SomeUninstalledBankingApp kholo")
        assertEquals(MatchConfidence.NONE, nonExistent2.confidence)
        assertNull(nonExistent2.matchedApp)
    }

    @Test
    fun testWebsiteIntentDetection() {
        val web1 = appResolver.resolve("Open YouTube website")
        assertTrue(web1.isWebsiteRequest)
        assertNull(web1.matchedApp)

        val web2 = appResolver.resolve("open spotify in browser")
        assertTrue(web2.isWebsiteRequest)
        assertNull(web2.matchedApp)
    }

    @Test
    fun testJarvisNaturalLanguageAppVariations() {
        // "Open YouTube."
        assertEquals("YouTube", appResolver.extractAppNameFromNaturalLanguage("Open YouTube."))
        val r1 = appResolver.resolve("Open YouTube.")
        assertEquals("com.google.android.youtube", r1.matchedApp?.packageName)

        // "Can you open YouTube?"
        assertEquals("YouTube", appResolver.extractAppNameFromNaturalLanguage("Can you open YouTube?"))
        val r2 = appResolver.resolve("Can you open YouTube?")
        assertEquals("com.google.android.youtube", r2.matchedApp?.packageName)

        // "Launch YouTube for me."
        assertEquals("YouTube", appResolver.extractAppNameFromNaturalLanguage("Launch YouTube for me."))
        val r3 = appResolver.resolve("Launch YouTube for me.")
        assertEquals("com.google.android.youtube", r3.matchedApp?.packageName)

        // "Go to YouTube."
        assertEquals("YouTube", appResolver.extractAppNameFromNaturalLanguage("Go to YouTube."))
        val r4 = appResolver.resolve("Go to YouTube.")
        assertEquals("com.google.android.youtube", r4.matchedApp?.packageName)
    }

    @Test
    fun testCommandInterpreterJarvisAgentCommands() {
        // Natural app opening variations
        val cmd1 = com.example.agent.CommandInterpreter.interpret("Can you open YouTube?")
        assertTrue(cmd1 is com.example.agent.CommandIntent.OpenApp)
        assertEquals("YouTube", (cmd1 as com.example.agent.CommandIntent.OpenApp).appName)

        val cmd2 = com.example.agent.CommandInterpreter.interpret("Launch YouTube for me.")
        assertTrue(cmd2 is com.example.agent.CommandIntent.OpenApp)
        assertEquals("YouTube", (cmd2 as com.example.agent.CommandIntent.OpenApp).appName)

        val cmd3 = com.example.agent.CommandInterpreter.interpret("Go to YouTube.")
        assertTrue(cmd3 is com.example.agent.CommandIntent.OpenApp)
        assertEquals("YouTube", (cmd3 as com.example.agent.CommandIntent.OpenApp).appName)

        // Multi-step messaging: "Open WhatsApp and message my sister hello"
        val cmdMsg = com.example.agent.CommandInterpreter.interpret("Open WhatsApp and message my sister hello")
        assertTrue(cmdMsg is com.example.agent.CommandIntent.SendMessage)
        val sendMsg = cmdMsg as com.example.agent.CommandIntent.SendMessage
        assertEquals("WhatsApp", sendMsg.app)
        assertEquals("my sister", sendMsg.recipient)
        assertEquals("hello", sendMsg.messageText)

        // Multi-step media: "Open Spotify and play Believer"
        val cmdMedia = com.example.agent.CommandInterpreter.interpret("Open Spotify and play Believer")
        assertTrue(cmdMedia is com.example.agent.CommandIntent.PlayMedia)
        val playMedia = cmdMedia as com.example.agent.CommandIntent.PlayMedia
        assertEquals("Spotify", playMedia.app)
        assertEquals("Believer", playMedia.mediaQuery)

        // Multi-step search: "Open YouTube and search for GTA 5"
        val cmdSearch = com.example.agent.CommandInterpreter.interpret("Open YouTube and search for GTA 5")
        assertTrue(cmdSearch is com.example.agent.CommandIntent.WebSearch)
        val webSearch = cmdSearch as com.example.agent.CommandIntent.WebSearch
        assertEquals("YouTube", webSearch.engineOrApp)
        assertEquals("GTA 5", webSearch.query)

        // Interruption & Stop commands
        assertTrue(com.example.agent.CommandInterpreter.interpret("Stop Kavya") is com.example.agent.CommandIntent.Stop)
        assertTrue(com.example.agent.CommandInterpreter.interpret("Stop") is com.example.agent.CommandIntent.Stop)
        assertTrue(com.example.agent.CommandInterpreter.interpret("Cancel") is com.example.agent.CommandIntent.Stop)
        assertTrue(com.example.agent.CommandInterpreter.interpret("Sleep Kavya") is com.example.agent.CommandIntent.Sleep)
        assertTrue(com.example.agent.CommandInterpreter.interpret("so jao") is com.example.agent.CommandIntent.Sleep)
    }
}
