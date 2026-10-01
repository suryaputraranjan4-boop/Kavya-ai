package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.agent.*
import com.example.ai.BargeInController
import com.example.ai.BargeInState
import com.example.ai.GeminiAudioPlayer
import com.example.data.*
import com.example.scheduler.SchedulerEngine
import com.example.skills.*
import com.example.utils.AppResolver
import com.example.utils.InstalledApp
import com.example.utils.MatchConfidence
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

enum class TestVerdict {
    PASS,
    FAIL,
    NOT_SUPPORTED,
    BLOCKED
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AgentWorldEvaluationTest {

    private lateinit var context: Context
    private lateinit var appResolver: AppResolver
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = AppDatabase.getDatabase(context)
        appResolver = AppResolver(context)

        // Seed mock installed apps
        val testApps = listOf(
            InstalledApp("WhatsApp", "com.whatsapp", "com.whatsapp.Main", "whatsapp", listOf("wa", "whatsapp")),
            InstalledApp("YouTube", "com.google.android.youtube", "com.google.android.youtube.Home", "youtube", listOf("yt", "youtube")),
            InstalledApp("YouTube Create", "com.google.android.apps.youtube.creator", "com.google.android.apps.youtube.creator.Main", "youtube create", listOf("yt create", "youtube create")),
            InstalledApp("Spotify", "com.spotify.music", "com.spotify.music.MainActivity", "spotify", listOf("spotify")),
            InstalledApp("Google Chrome", "com.android.chrome", "com.google.android.apps.chrome.Main", "google chrome", listOf("chrome", "browser"))
        )
        appResolver.setInstalledAppsForTesting(testApps)
    }

    private fun logVerdict(testNumber: Int, title: String, verdict: TestVerdict, details: String = "") {
        println("TEST #$testNumber: [$verdict] $title ${if (details.isNotBlank()) "-> $details" else ""}")
        assertEquals("Test failed: $title", TestVerdict.PASS, verdict)
    }

    // 1. Open exact app ("Open WhatsApp", "WhatsApp kholo")
    @Test
    fun test01_OpenExactApp() {
        val decision = IntentGate.evaluate("Open WhatsApp", appResolver)
        val verdict = if (decision.category == IntentCategory.ACTION && decision.targetApp == "WhatsApp") {
            TestVerdict.PASS
        } else TestVerdict.FAIL
        logVerdict(1, "Open exact app", verdict, "Category: ${decision.category}, Target: ${decision.targetApp}")
    }

    // 2. Distinguish app name from conversation text ("What is WhatsApp?", "I was just talking about WhatsApp yesterday")
    @Test
    fun test02_DistinguishAppNameFromConversation() {
        val qDecision = IntentGate.evaluate("What is WhatsApp?", appResolver)
        val chatDecision = IntentGate.evaluate("I was just talking about WhatsApp yesterday.", appResolver)
        val askingDecision = IntentGate.evaluate("Don't do anything, I was just asking.", appResolver)

        val isQuestion = qDecision.category == IntentCategory.QUESTION
        val isChat = chatDecision.category == IntentCategory.CHAT
        val isAsking = askingDecision.category in listOf(IntentCategory.CHAT, IntentCategory.QUESTION)

        // Must NEVER launch WhatsApp
        val noAppLaunch = qDecision.targetApp == null && chatDecision.targetApp == null

        val verdict = if (isQuestion && isChat && isAsking && noAppLaunch) TestVerdict.PASS else TestVerdict.FAIL
        logVerdict(2, "Distinguish app name from conversation text", verdict, "Q=${qDecision.category}, Chat=${chatDecision.category}")
    }

    // 3. WhatsApp Hindi imperative ("WhatsApp kholo")
    @Test
    fun test03_OpenWhatsAppHindi() {
        val decision = IntentGate.evaluate("WhatsApp kholo", appResolver)
        val verdict = if (decision.category == IntentCategory.ACTION && decision.targetApp == "WhatsApp") {
            TestVerdict.PASS
        } else TestVerdict.FAIL
        logVerdict(3, "Open WhatsApp with Hindi verb", verdict, "Category=${decision.category}, Target=${decision.targetApp}")
    }

    // 4 & 5. Multi-step task: Open WhatsApp and send message
    @Test
    fun test04_MultiStepMessageTask() {
        val decision = IntentGate.evaluate("Open WhatsApp and message Rahul hello", appResolver)
        val hasMultiStep = decision.category == IntentCategory.MULTI_STEP_TASK
        val step1App = decision.steps.getOrNull(0)?.targetApp
        val step2Param = decision.steps.getOrNull(1)?.param

        val verdict = if (hasMultiStep && step1App == "WhatsApp" && step2Param?.contains("Rahul") == true) {
            TestVerdict.PASS
        } else TestVerdict.FAIL
        logVerdict(4, "Multi-step message task", verdict, "Steps: ${decision.steps.size}, Param: $step2Param")
    }

    // 6. Multi-step media: Open YouTube and search title
    @Test
    fun test06_OpenYouTubeAndSearch() {
        val decision = IntentGate.evaluate("Open YouTube and search Minecraft tutorial", appResolver)
        val isMulti = decision.category == IntentCategory.MULTI_STEP_TASK
        val step1App = decision.steps.getOrNull(0)?.targetApp
        val query = decision.steps.getOrNull(1)?.query

        val verdict = if (isMulti && step1App == "YouTube" && query?.contains("Minecraft") == true) {
            TestVerdict.PASS
        } else TestVerdict.FAIL
        logVerdict(6, "Open YouTube and search title", verdict, "Target: $step1App, Query: $query")
    }

    // 7. System UI navigation: Home & Back
    @Test
    fun test07_SystemNavigation() {
        val homeDecision = IntentGate.evaluate("go home", appResolver)
        val backDecision = IntentGate.evaluate("go back", appResolver)

        val homeOk = homeDecision.category == IntentCategory.ACTION && homeDecision.steps.any { it.actionType == UniversalActionType.HOME }
        val backOk = backDecision.category == IntentCategory.ACTION && backDecision.steps.any { it.actionType == UniversalActionType.BACK }

        val verdict = if (homeOk && backOk) TestVerdict.PASS else TestVerdict.FAIL
        logVerdict(7, "System navigation Home and Back", verdict)
    }

    // 8. Explicit Research Query
    @Test
    fun test08_ExplicitResearch() {
        val decision = IntentGate.evaluate("Search the latest Android AI agent projects on GitHub", appResolver)
        val isResearch = decision.category == IntentCategory.RESEARCH
        val verdict = if (isResearch && decision.query?.isNotBlank() == true) TestVerdict.PASS else TestVerdict.FAIL
        logVerdict(8, "Explicit GitHub/Web Research", verdict, "Query: ${decision.query}")
    }

    // 9. Schedule daily task
    @Test
    fun test09_ScheduleDailyTask() = runBlocking {
        val decision = IntentGate.evaluate("Every day at 9 AM remind me to study", appResolver)
        val isSchedule = decision.category == IntentCategory.SCHEDULE

        val scheduler = SchedulerEngine(context, database.taskDao())
        val task = scheduler.createSchedule("Study Reminder", "remind me to study", "DAILY", 9, 0)

        val retrieved = database.taskDao().getScheduledTaskById(task.id)
        val verdict = if (isSchedule && retrieved != null && retrieved.hour == 9 && retrieved.minute == 0) {
            TestVerdict.PASS
        } else TestVerdict.FAIL
        logVerdict(9, "Schedule daily task", verdict, "ScheduleId=${task.id}, Hour=${task.hour}")
    }

    // 10. Stop and Sleep commands
    @Test
    fun test10_StopAndSleepInterruption() {
        val stopDecision = IntentGate.evaluate("Stop Kavya", appResolver)
        val rukoDecision = IntentGate.evaluate("ruko", appResolver)
        val sleepDecision = IntentGate.evaluate("Sleep Kavya", appResolver)

        val stopOk = stopDecision.category == IntentCategory.STOP
        val rukoOk = rukoDecision.category == IntentCategory.STOP
        val sleepOk = sleepDecision.category == IntentCategory.SLEEP

        val verdict = if (stopOk && rukoOk && sleepOk) TestVerdict.PASS else TestVerdict.FAIL
        logVerdict(10, "Immediate Stop and Sleep interruption", verdict)
    }

    // 11. Voice Priority and Barge-In
    @Test
    fun test11_VoicePriorityBargeIn() {
        val player = GeminiAudioPlayer(context)
        var bargeInDetected = false
        val controller = BargeInController(player) {
            bargeInDetected = true
        }

        controller.onSpeakingStarted()
        assertEquals(BargeInState.SPEAKING, controller.state.value)

        // User speaks while Kavya is speaking
        controller.onUserSpeechDetected()

        val verdict = if (controller.state.value == BargeInState.CAPTURING && bargeInDetected && !player.isSpeaking()) {
            TestVerdict.PASS
        } else TestVerdict.FAIL
        logVerdict(11, "User Voice Priority Barge-In", verdict, "State=${controller.state.value}")
    }

    // 12. Persistent Memory and Recall
    @Test
    fun test12_PersistentMemorySaveAndRecall() = runBlocking {
        val saveDecision = IntentGate.evaluate("Remember that this project uses Gemini", appResolver)
        val recallDecision = IntentGate.evaluate("What did we do yesterday?", appResolver)

        val memoryDao = database.memoryDao()
        memoryDao.insertMemory(
            MemoryEntity(
                key = "ProjectAI",
                content = "This project uses Gemini",
                category = "PROJECT_MEMORY",
                importance = 5
            )
        )

        val recalled = memoryDao.searchMemories("Gemini")
        val isSave = saveDecision.category == IntentCategory.MEMORY_SAVE
        val isRecall = recallDecision.category == IntentCategory.MEMORY_RECALL
        val hasMemory = recalled.any { it.content.contains("Gemini") }

        val verdict = if (isSave && isRecall && hasMemory) TestVerdict.PASS else TestVerdict.FAIL
        logVerdict(12, "Persistent Memory Save and Recall", verdict, "Matches: ${recalled.size}")
    }

    // 13. Historical Session Search across Chats
    @Test
    fun test13_SessionSearchAcrossPreviousChats() = runBlocking {
        val chatDao = database.chatDao()
        val chatId = "test_chat_session_1"
        chatDao.insertChat(ChatEntity(chatId, "WhatsApp Troubleshooting", System.currentTimeMillis() - 86400000L))
        chatDao.insertMessage(MessageEntity("msg_1", chatId, "Humne WhatsApp call notification ka bug fix kiya tha", false, System.currentTimeMillis() - 86400000L))

        val searchEngine = SessionSearchEngine(chatDao)
        val results = searchEngine.searchPastConversations("WhatsApp bug fix")

        val verdict = if (results.isNotEmpty() && results.any { it.snippet.contains("notification ka bug fix") }) {
            TestVerdict.PASS
        } else TestVerdict.FAIL
        logVerdict(13, "Local Historical Session Search", verdict, "Results: ${results.size}")
    }

    // 14. Durable Task Checkpoints
    @Test
    fun test14_DurableTaskCheckpoints() = runBlocking {
        val durableEngine = DurableTaskEngine(context, database.taskDao())
        val task = durableEngine.createAndPersistTask(
            "Create Video Workflow",
            IntentCategory.MULTI_STEP_TASK,
            listOf(
                GateStep(IntentCategory.ACTION, "ChatGPT", UniversalActionType.OPEN_APP),
                GateStep(IntentCategory.ACTION, "ChatGPT", UniversalActionType.TAP, param = "Copy prompt")
            )
        )

        durableEngine.recordStepCheckpoint(task.id, 1, true, "ChatGPT open in foreground", "PASS")
        val updated = database.taskDao().getTaskById(task.id)

        val verdict = if (updated != null && updated.currentStepIndex == 1 && updated.status == TaskExecutionStatus.CHECKPOINTED.name) {
            TestVerdict.PASS
        } else TestVerdict.FAIL
        logVerdict(14, "Durable Task Step Checkpoint", verdict, "Status: ${updated?.status}")
    }

    // 15. Optional Termux Bridge Fallback
    @Test
    fun test15_TermuxBridgeFallback() = runBlocking {
        val bridge = TermuxBridgeSkill()
        val decision = IntentGateDecision(
            category = IntentCategory.ACTION,
            rawPrompt = "run shell git status"
        )
        val result = bridge.execute(decision, context)
        // If Termux not installed, it must report false cleanly without crashing
        val verdict = if (!bridge.isTermuxInstalled(context)) {
            if (!result.success && result.outputMessage.contains("Termux is not installed")) TestVerdict.PASS else TestVerdict.FAIL
        } else {
            TestVerdict.PASS
        }
        logVerdict(15, "Optional Termux Bridge Dynamic Fallback", verdict, result.outputMessage)
    }

    // 16. Alias Resolution ("YT Create" -> "YouTube Create")
    @Test
    fun test16_AliasAppResolution() {
        val resolution = appResolver.resolve("YT Create")
        val verdict = if (resolution.confidence != MatchConfidence.NONE && resolution.matchedApp?.appName == "YouTube Create") {
            TestVerdict.PASS
        } else TestVerdict.FAIL
        logVerdict(16, "Alias App Resolution (YT Create -> YouTube Create)", verdict, "Resolved: ${resolution.matchedApp?.appName}")
    }
}
