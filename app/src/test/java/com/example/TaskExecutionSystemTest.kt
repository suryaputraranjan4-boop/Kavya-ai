package com.example

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.agent.AnalyzedNode
import com.example.agent.ContextEngine
import com.example.agent.FreshScreenState
import com.example.agent.IntelligentWaitingManager
import com.example.agent.StepStatus
import com.example.agent.TaskPlanExecutionState
import com.example.agent.TaskPlanner
import com.example.agent.TaskStep
import com.example.agent.UIElementResolver
import com.example.agent.UniversalActionType
import com.example.agent.UniversalTaskDecomposer
import com.example.agent.WaitConditionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TaskExecutionSystemTest {

    private val planner = TaskPlanner()
    private val contextEngine = ContextEngine()

    // -------------------------------------------------------------------------
    // SCENARIO 1: Repeated App Navigation & Dependent Search/Play + Message Draft
    // -------------------------------------------------------------------------
    @Test
    fun testScenario1_RepeatedNavigationAndExecution() {
        val prompt = "Open Instagram, then YouTube, then Instagram again, then YouTube again. Search for a song, play it, then go back to Instagram and message my friend, then return to YouTube."
        val plan = UniversalTaskDecomposer.decompose(prompt)
        assertNotNull("Plan must be decomposed for Scenario 1", plan)
        assertTrue("Scenario 1 must have at least 10 sequential steps", plan!!.steps.size >= 10)

        // Verify strict dependency chain: Step N depends on Step N-1
        for (i in 1 until plan.steps.size) {
            val step = plan.steps[i]
            val prevStep = plan.steps[i - 1]
            assertTrue("Step ${step.id} must depend on previous step ${prevStep.id}", step.dependsOnStepIds.contains(prevStep.stepId))
        }

        // Verify sequential transitions exist: Instagram -> YouTube -> Instagram -> YouTube
        val openSteps = plan.steps.filter { it.actionType == UniversalActionType.OPEN_APP }
        assertTrue("Must have multiple open app steps", openSteps.size >= 4)

        // Verify Search and Play exist
        assertTrue("Plan must contain search/play steps", plan.steps.any { it.actionType == UniversalActionType.PLAY })

        // Verify User Confirmation before sending message
        val confirmStep = plan.steps.find { it.actionType == UniversalActionType.CONFIRM_ACTION || it.requiresUserConfirmation }
        assertNotNull("User confirmation step must be present before sending message", confirmStep)
        assertTrue("Step must enforce user confirmation flag", confirmStep!!.requiresUserConfirmation)
    }

    // -------------------------------------------------------------------------
    // SCENARIO 2: Gemini Image Generation & WhatsApp Sharing with Download Waiting
    // -------------------------------------------------------------------------
    @Test
    fun testScenario2_GeminiImageGenerationAndWhatsApp() {
        val prompt = "Open Gemini, generate an image using a suitable prompt, wait until generation is complete, download the image, verify the downloaded file, open WhatsApp, find Papa's chat, attach the image, ask for confirmation before sending, send it only after confirmation, and return to Kavya AI."
        val plan = UniversalTaskDecomposer.decompose(prompt)
        assertNotNull("Plan must be decomposed for Scenario 2", plan)

        // Verify intelligent waiting for image generation
        val waitGenStep = plan!!.steps.find { it.waitingStrategy.waitCondition == WaitConditionType.IMAGE_GENERATED }
        assertNotNull("Wait condition for IMAGE_GENERATED must be configured", waitGenStep)

        // Verify waiting for download
        val waitDownloadStep = plan.steps.find { it.waitingStrategy.waitCondition == WaitConditionType.FILE_DOWNLOADED }
        assertNotNull("Wait condition for FILE_DOWNLOADED must be configured", waitDownloadStep)

        // Verify user confirmation gate
        val confirmStep = plan.steps.find { it.requiresUserConfirmation }
        assertNotNull("User confirmation before sending must be gated", confirmStep)

        // Verify return to Kavya AI
        val lastStep = plan.steps.last()
        assertEquals(UniversalActionType.OPEN_APP, lastStep.actionType)
        assertTrue("Last step must return to Kavya", lastStep.targetAppOrUrl.contains("Kavya", ignoreCase = true))
    }

    // -------------------------------------------------------------------------
    // SCENARIO 3: Multi-Topic Research, Comparison & Report Creation
    // -------------------------------------------------------------------------
    @Test
    fun testScenario3_MultiTopicResearchAndReport() {
        val prompt = "Open browser, research Topic 1, Topic 2, and Topic 3, compare the findings, create a structured report, save it as a document, verify that the document was actually saved, and show me the final result."
        val plan = UniversalTaskDecomposer.decompose(prompt)
        assertNotNull("Plan must be decomposed for Scenario 3", plan)

        assertTrue("Must include web research steps", plan!!.steps.any { it.actionType == UniversalActionType.RESEARCH_WEB })
        assertTrue("Must include compare / extract data step", plan.steps.any { it.actionType == UniversalActionType.EXTRACT_DATA })
        assertTrue("Must include create report step", plan.steps.any { it.actionType == UniversalActionType.CREATE_REPORT })
        assertTrue("Must include save document step", plan.steps.any { it.actionType == UniversalActionType.CREATE_FILE })
        assertTrue("Must verify saved document", plan.steps.any { it.actionType == UniversalActionType.VERIFY && it.param.contains("report") })
    }

    // -------------------------------------------------------------------------
    // SCENARIO 4: Multi-App File Workflow (Gmail -> Attachment -> Summary)
    // -------------------------------------------------------------------------
    @Test
    fun testScenario4_MultiAppFileWorkflow() {
        val prompt = "Open Gmail, find the specified email, verify the attachment before downloading, download its attachment, wait for the download to finish, confirm the file is accessible, open a document editor, extract the important information, create a summary, save the document, verify the saved document, and return to Gmail."
        val plan = UniversalTaskDecomposer.decompose(prompt)
        assertNotNull("Plan must be decomposed for Scenario 4", plan)

        val waitStep = plan!!.steps.find { it.waitingStrategy.waitCondition == WaitConditionType.FILE_DOWNLOADED }
        assertNotNull("Must wait for file download completion", waitStep)
        assertTrue("Must extract data and summarize", plan.steps.any { it.actionType == UniversalActionType.SUMMARIZE_TEXT })
        val finalStep = plan.steps.last()
        assertTrue("Must return to Gmail", finalStep.targetAppOrUrl.equals("Gmail", ignoreCase = true))
    }

    // -------------------------------------------------------------------------
    // SCENARIO 5: YouTube Educational Video Research & Study Notes
    // -------------------------------------------------------------------------
    @Test
    fun testScenario5_YouTubeStudyNotes() {
        val prompt = "Open YouTube, search for a particular educational video on machine learning, open it and start playback, wait until it loads and starts playing, analyze the available content and collect important points, create study notes, save study notes, and return to the home screen."
        val plan = UniversalTaskDecomposer.decompose(prompt)
        assertNotNull("Plan must be decomposed for Scenario 5", plan)

        assertTrue("Must play media", plan!!.steps.any { it.actionType == UniversalActionType.PLAY })
        val waitPlayback = plan.steps.find { it.waitingStrategy.waitCondition == WaitConditionType.MEDIA_PLAYING }
        assertNotNull("Must wait intelligently for media playing condition", waitPlayback)
        assertTrue("Must create study notes file", plan.steps.any { it.actionType == UniversalActionType.CREATE_FILE })
        assertTrue("Must return to home screen", plan.steps.any { it.actionType == UniversalActionType.HOME })
    }

    // -------------------------------------------------------------------------
    // SCENARIO 6: Multi-Image Generation, Download & Folder Organization
    // -------------------------------------------------------------------------
    @Test
    fun testScenario6_MultiImageFolderOrganization() {
        val prompt = "Open an available image generator, generate three images one by one, wait for each generation and download to finish, create a folder, organize the images inside the folder, and verify final folder contents."
        val plan = UniversalTaskDecomposer.decompose(prompt)
        assertNotNull("Plan must be decomposed for Scenario 6", plan)

        val genSteps = plan!!.steps.filter { it.actionType == UniversalActionType.GENERATE_IMAGE }
        assertEquals("Must generate 3 images", 3, genSteps.size)
        assertTrue("Must create folder", plan.steps.any { it.actionType == UniversalActionType.CREATE_FOLDER })
        assertTrue("Must organize files", plan.steps.any { it.actionType == UniversalActionType.ORGANIZE_FILES })
        assertTrue("Must verify final folder contents", plan.steps.any { it.actionType == UniversalActionType.VERIFY })
    }

    // -------------------------------------------------------------------------
    // SCENARIO 7: Multi-App Messaging with Pre-Send Review
    // -------------------------------------------------------------------------
    @Test
    fun testScenario7_MultiAppMessagingWithConfirmation() {
        val prompt = "Open WhatsApp, find one specified conversation, prepare first message, open another messaging application, find another specified conversation, prepare a different message, show me both before sending and ask confirmation, send only after confirmation, and verify both messages sent."
        val plan = UniversalTaskDecomposer.decompose(prompt)
        assertNotNull("Plan must be decomposed for Scenario 7", plan)

        val confirmStep = plan!!.steps.find { it.actionType == UniversalActionType.CONFIRM_ACTION || it.requiresUserConfirmation }
        assertNotNull("Must gate sending behind confirmation", confirmStep)
        val sendSteps = plan.steps.filter { it.actionType == UniversalActionType.SEND_MESSAGE }
        assertEquals("Must have 2 send steps", 2, sendSteps.size)
        sendSteps.forEach {
            assertTrue("Send step must require user confirmation", it.requiresUserConfirmation)
        }
    }

    // -------------------------------------------------------------------------
    // SCENARIO 8: Long Navigation & Context Preservation
    // -------------------------------------------------------------------------
    @Test
    fun testScenario8_LongNavigationContextPreservation() {
        val prompt = "Open Chrome, search for a topic, open a useful result, wait for page to load, copy the important information, open a notes application, save the information, verify that the notes were saved, open YouTube, search for a related video, and finally return to the notes application."
        val plan = UniversalTaskDecomposer.decompose(prompt)
        assertNotNull("Plan must be decomposed for Scenario 8", plan)

        val waitPage = plan!!.steps.find { it.waitingStrategy.waitCondition == WaitConditionType.PAGE_LOADED }
        assertNotNull("Must wait for page loaded", waitPage)
        val lastStep = plan.steps.last()
        assertTrue("Must return to notes application with context preserved", lastStep.targetAppOrUrl.contains("Notes", ignoreCase = true))
    }

    // -------------------------------------------------------------------------
    // SCENARIO 9: Complex Multi-Step Form Workflow
    // -------------------------------------------------------------------------
    @Test
    fun testScenario9_FormFillingAndSubmission() {
        val prompt = "Open specified website with a form, wait for website to load, find the relevant form, identify each field individually and enter requested information, review and validate all fields, show me the completed form and wait for my confirmation before submitting, and submit only after explicit confirmation."
        val plan = UniversalTaskDecomposer.decompose(prompt)
        assertNotNull("Plan must be decomposed for Scenario 9", plan)

        assertTrue("Must fill form fields", plan!!.steps.any { it.actionType == UniversalActionType.FILL_FORM })
        assertTrue("Must validate form fields", plan.steps.any { it.actionType == UniversalActionType.VALIDATE_FORM })
        val confirmStep = plan.steps.find { it.actionType == UniversalActionType.CONFIRM_ACTION || it.requiresUserConfirmation }
        assertNotNull("Must gate submission with user confirmation", confirmStep)
        val submitStep = plan.steps.find { it.actionType == UniversalActionType.SUBMIT }
        assertNotNull("Must have submit step", submitStep)
        assertTrue("Submit step must require confirmation", submitStep!!.requiresUserConfirmation)
    }

    // -------------------------------------------------------------------------
    // SCENARIO 10: Dynamic Error Recovery & Replanning Workflow
    // -------------------------------------------------------------------------
    @Test
    fun testScenario10_DynamicRecoveryAndReplanning() {
        val prompt = "Open Gemini, generate an image, if generation fails recover and fix, wait for completion, download the image, open WhatsApp, find conversation, prepare attachment, if any step fails apply alternative strategy, and return to Gemini."
        val plan = UniversalTaskDecomposer.decompose(prompt)
        assertNotNull("Plan must be decomposed for Scenario 10", plan)

        // Verify alternative retry strategies are specified
        val stepsWithAlternatives = plan!!.steps.filter { it.retryStrategy.alternativeStrategy != null }
        assertTrue("Steps must specify alternative recovery strategies", stepsWithAlternatives.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // DEPENDENCY MANAGEMENT & EXECUTION GRAPH TESTS
    // -------------------------------------------------------------------------
    @Test
    fun testStrictSequentialExecutionGraph() {
        val step1 = TaskStep(id = 1, actionType = UniversalActionType.OPEN_APP, stepId = "step_1")
        val step2 = TaskStep(id = 2, actionType = UniversalActionType.VERIFY, stepId = "step_2", dependsOnStepIds = listOf("step_1"))
        val step3 = TaskStep(id = 3, actionType = UniversalActionType.SEARCH, stepId = "step_3", dependsOnStepIds = listOf("step_2"))

        val state = TaskPlanExecutionState()

        // Step 1 is executable (no dependencies)
        assertTrue("Step 1 with no dependencies must be executable", step1.isExecutable(state.completedStepIds))
        // Step 2 is not executable yet
        assertFalse("Step 2 must NOT be executable before Step 1 finishes", step2.isExecutable(state.completedStepIds))

        // Complete Step 1
        state.completedStepIds.add(step1.stepId)
        step1.status = StepStatus.COMPLETED

        // Step 2 is now executable
        assertTrue("Step 2 must become executable once Step 1 is completed", step2.isExecutable(state.completedStepIds))
        assertFalse("Step 3 must still NOT be executable", step3.isExecutable(state.completedStepIds))

        // Complete Step 2
        state.completedStepIds.add(step2.stepId)
        step2.status = StepStatus.COMPLETED

        // Step 3 is now executable
        assertTrue("Step 3 is executable after Step 2 completes", step3.isExecutable(state.completedStepIds))
    }

    // -------------------------------------------------------------------------
    // UI ELEMENT RESOLVER SEMANTIC DETECTION TESTS
    // -------------------------------------------------------------------------
    @Test
    fun testUIElementResolverSemanticDetection() {
        val mockNode = AccessibilityNodeInfo.obtain()
        val bounds = Rect(10, 10, 100, 100)
        val searchNode = AnalyzedNode(
            node = mockNode,
            text = "",
            contentDescription = "Search YouTube",
            resourceId = "com.google.android.youtube:id/menu_search",
            className = "android.widget.ImageView",
            bounds = bounds,
            isClickable = true,
            isEditable = false,
            isScrollable = false
        )
        val sendNode = AnalyzedNode(
            node = mockNode,
            text = "Send",
            contentDescription = "Send message",
            resourceId = "com.whatsapp:id/send",
            className = "android.widget.ImageButton",
            bounds = bounds,
            isClickable = true,
            isEditable = false,
            isScrollable = false
        )
        val downloadNode = AnalyzedNode(
            node = mockNode,
            text = "Download",
            contentDescription = "Save to device",
            resourceId = "download_btn",
            className = "android.widget.Button",
            bounds = bounds,
            isClickable = true,
            isEditable = false,
            isScrollable = false
        )
        val screen = FreshScreenState(
            root = mockNode,
            foregroundPackage = "com.google.android.youtube",
            nodes = listOf(searchNode, sendNode, downloadNode),
            visibleTexts = listOf("Search YouTube", "Send", "Download"),
            summaryString = "Mock screen with search, send, and download buttons",
            isLoading = false,
            searchFields = emptyList(),
            searchButtons = listOf(searchNode),
            organicContentResults = emptyList(),
            adNodes = emptyList()
        )

        val resolvedSearch = UIElementResolver.findSearchButton(screen)
        assertNotNull("Search button must be semantically resolved", resolvedSearch)
        assertEquals("Search YouTube", resolvedSearch!!.targetText)

        val resolvedSend = UIElementResolver.findSendButton(screen)
        assertNotNull("Send button must be semantically resolved", resolvedSend)
        assertTrue(resolvedSend!!.targetText.contains("Send"))

        val resolvedDownload = UIElementResolver.findDownloadButton(screen)
        assertNotNull("Download button must be semantically resolved", resolvedDownload)
        assertTrue(resolvedDownload!!.targetText.contains("Download"))
    }

    // -------------------------------------------------------------------------
    // INTELLIGENT WAITING CONDITIONS TEST
    // -------------------------------------------------------------------------
    @Test
    fun testIntelligentWaitingConditionTimeout() = runBlocking {
        val waitingMgr = IntelligentWaitingManager(null)
        // Testing timeout handling without crashing
        val res = waitingMgr.waitForCondition(
            condition = WaitConditionType.ELEMENT_VISIBLE,
            expectedIdentifier = "non_existent_element_identifier_xyz",
            timeoutMs = 150L,
            pollIntervalMs = 50L
        )
        assertFalse("Waiting for non-existent element should time out safely", res.satisfied)
        assertTrue("Timed out result should report elapsed time", res.elapsedMs >= 100L)
    }
}
