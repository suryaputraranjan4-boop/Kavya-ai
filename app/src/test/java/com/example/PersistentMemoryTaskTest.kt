package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.agent.*
import com.example.data.AppDatabase
import com.example.data.MemoryDao
import com.example.data.MemoryEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class PersistentMemoryTaskTest {

    private lateinit var context: Context
    private lateinit var memoryEngine: MemoryEngine
    private lateinit var memoryDao: MemoryDao
    private lateinit var taskPlanner: TaskPlanner
    private lateinit var contextEngine: ContextEngine

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        memoryDao = AppDatabase.getDatabase(context).memoryDao()
        memoryEngine = MemoryEngine(context)
        taskPlanner = TaskPlanner()
        contextEngine = ContextEngine()

        runBlocking {
            memoryDao.clearAll()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            memoryDao.clearAll()
        }
    }

    // ====================================================
    // TEST 1: EXPLICIT MEMORY EXTRACTION & STORAGE (Requirement 1 & 8)
    // ====================================================
    @Test
    fun testExplicitMemoryCommands_StorageAndConfirmation() = runBlocking {
        // Example from prompt:
        // "WhatsApp me jab main Didi ko message karu, chat open hone ke baad message box me type karna. Ye yaad rakhna."
        val userPrompt1 = "WhatsApp me jab main Didi ko message karu, chat open hone ke baad message box me type karna. Ye yaad rakhna."
        
        assertTrue(UserCommandClassifier.isExplicitMemoryCommand(userPrompt1))
        
        val result1 = memoryEngine.processAndExtractMemory(userPrompt1, sourceConversation = userPrompt1)
        assertTrue("Memory should be detected", result1.detected)
        assertTrue("Memory should be saved", result1.saved)
        assertTrue("Confirmation should be provided", result1.confirmationText.contains("yaad rakh liya", ignoreCase = true))

        val allMemories = memoryDao.getAllMemories()
        assertEquals(1, allMemories.size)
        val saved = allMemories.first()
        assertTrue("Key should reflect WhatsApp Didi", saved.key.contains("WhatsApp", ignoreCase = true) && saved.key.contains("Didi", ignoreCase = true))
        assertTrue("Content should store the rule", saved.content.contains("message box me type karna", ignoreCase = true))
        assertEquals("TASK_WORKFLOW", saved.category)
        assertEquals(5, saved.importance)

        // Test 2: "Aage se Spotify par Arijit Singh ke songs chalao. Isko remember karo"
        val userPrompt2 = "Aage se Spotify par Arijit Singh ke songs chalao. Isko remember karo"
        assertTrue(UserCommandClassifier.isExplicitMemoryCommand(userPrompt2))
        val result2 = memoryEngine.processAndExtractMemory(userPrompt2, sourceConversation = userPrompt2)
        assertTrue(result2.detected)
        assertTrue(result2.saved)

        val updatedMemories = memoryDao.getAllMemories()
        assertEquals(2, updatedMemories.size)
    }

    // ====================================================
    // TEST 2: FUTURE TASK RETRIEVAL & PLANNING ADAPTATION (Requirement 2, 5 & 8)
    // ====================================================
    @Test
    fun testMemoryRetrieval_InfluencesTaskPlanning() = runBlocking {
        // Step 1: User explicitly saves preference
        val rememberCmd = "WhatsApp me jab main Didi ko message karu, chat open hone ke baad message box me type karna. Ye yaad rakhna."
        memoryEngine.processAndExtractMemory(rememberCmd)

        // Step 2: Later, user says "Didi ko WhatsApp par message karo: Hello"
        val taskCmd = "Didi ko WhatsApp par message karo: Hello"
        
        // Find relevant task memories
        val relevantMems = memoryEngine.findRelevantTaskMemories(
            targetApp = "WhatsApp",
            targetEntity = "Didi",
            action = "SEND_MESSAGE",
            prompt = taskCmd
        )
        assertFalse("Should find relevant memory", relevantMems.isEmpty())
        val matchedMem = relevantMems.first()
        assertTrue(matchedMem.content.contains("message box", ignoreCase = true))

        // Create plan with memory
        val plan = taskPlanner.createPlan(taskCmd, contextEngine, relevantMems)
        assertNotNull(plan)
        assertEquals("WhatsApp", plan?.targetAppName)
        assertTrue("Should mark remembered workflow used", plan?.rememberedWorkflowUsed == true)
        assertEquals(matchedMem.key, plan?.appliedMemory?.key)

        // Verify adapted steps: should have OPEN_APP -> OPEN_CHAT -> VERIFY message_composer -> TYPE -> TAP Send -> VERIFY message_sent
        val steps = plan!!.steps
        assertEquals(6, steps.size)
        assertEquals(UniversalActionType.OPEN_APP, steps[0].actionType)
        assertEquals(UniversalActionType.OPEN_CHAT, steps[1].actionType)
        assertEquals("Didi", steps[1].recipient)
        
        // Step 3 MUST be verification of message_composer as remembered!
        assertEquals(UniversalActionType.VERIFY, steps[2].actionType)
        assertEquals("message_composer", steps[2].param)

        // Step 4 is TYPE message
        assertEquals(UniversalActionType.TYPE, steps[3].actionType)
        assertEquals("Hello", steps[3].param)

        // Step 5 is TAP send
        assertEquals(UniversalActionType.TAP, steps[4].actionType)
        assertEquals("Send", steps[4].param)

        // Step 6 is VERIFY message_sent
        assertEquals(UniversalActionType.VERIFY, steps[5].actionType)
    }

    // ====================================================
    // TEST 3: NO FAKE MEMORY (Requirement 9)
    // ====================================================
    @Test
    fun testNoFakeMemory_ReturnsHonestRecall() = runBlocking {
        // When no memory exists for a specific topic, Kavya must NOT hallucinate
        val recallQuery = "Kya tumhe yaad hai Rohan ko WhatsApp par kaise message karna hai?"
        val recallResponse = memoryEngine.handleConversationalRecall(recallQuery)
        assertNotNull(recallResponse)
        assertEquals("Mujhe is baare mein koi memory saved nahi mili.", recallResponse)

        // Now save a memory for Rohan
        memoryEngine.processAndExtractMemory("WhatsApp par Rohan ko message karte waqt confirm karna. Ye yaad rakhna.")

        // Query again
        val recallResponse2 = memoryEngine.handleConversationalRecall(recallQuery)
        assertNotNull(recallResponse2)
        assertTrue("Should now recall actual stored fact", recallResponse2!!.contains("confirm karna", ignoreCase = true))
    }

    // ====================================================
    // TEST 4: LEARN FROM SUCCESSFUL TASKS (Requirement 6 & 7)
    // ====================================================
    @Test
    fun testLearnFromSuccessfulTasks_ReinforcesMemory() = runBlocking {
        val rememberCmd = "WhatsApp me jab main Didi ko message karu, chat open hone ke baad message box me type karna. Ye yaad rakhna."
        val ext = memoryEngine.processAndExtractMemory(rememberCmd)
        val initialMemory = ext.persistedMemory ?: memoryDao.getAllMemories().first()
        val initialTimestamp = initialMemory.lastUsedAt

        // Simulate successful task execution with recovery path
        memoryEngine.recordWorkflowSuccess(initialMemory, "SearchContact -> ClickResult -> FocusComposer")

        val updated = memoryDao.getMemoryById(initialMemory.id)
        assertNotNull(updated)
        assertTrue(updated!!.lastUsedAt >= initialTimestamp)
        assertTrue("Should include learned verified path", updated.content.contains("[Verified Path: SearchContact", ignoreCase = true))

        // Update workflow memory without duplicate creation
        memoryEngine.updateWorkflowMemory(initialMemory.key, "Updated workflow: directly click composer")
        val finalMemories = memoryDao.getAllMemories()
        assertEquals("Should NOT create duplicate memory", 1, finalMemories.size)
        assertEquals("Updated workflow: directly click composer", finalMemories.first().content)
    }

    // ====================================================
    // TEST 5: CHECKPOINT DEDUPLICATION GUARD WITH MEMORY
    // ====================================================
    @Test
    fun testCheckpointDeduplicationGuard_PreventsDoubleSend() {
        val checkpointManager = CheckpointManager()

        checkpointManager.recordCheckpoint(
            stepId = 1,
            type = CheckpointType.CHECKPOINT_1_APP_OPENED,
            targetApp = "WhatsApp",
            targetPackage = "com.whatsapp"
        )

        checkpointManager.recordCheckpoint(
            stepId = 5,
            type = CheckpointType.CHECKPOINT_6_ACTION_COMPLETED,
            targetApp = "WhatsApp",
            targetPackage = "com.whatsapp",
            data = mapOf(
                "action" to "SEND_MESSAGE",
                "recipient" to "Didi",
                "message" to "Hello"
            )
        )

        assertTrue(checkpointManager.isMessageAlreadySent("Didi", "Hello"))
        assertFalse(checkpointManager.isMessageAlreadySent("Didi", "Other message"))
        assertFalse(checkpointManager.isMessageAlreadySent("Rohan", "Hello"))
    }
}
