package com.example

import com.example.agent.*
import com.example.visual.JarvisGuidanceEngine
import com.example.visual.VisionPipeline
import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DynamicAgentSystemTest {

    private val contextEngine = ContextEngine()

    @Test
    fun testDynamicAgentCreation_SimpleGoalHasOneAgent() {
        val goal = DynamicGoalDecomposer.decompose("WhatsApp खोलो", contextEngine)
        assertEquals("DIRECT_EXECUTION", goal.userIntent)
        assertEquals(1, goal.tasks.size)
        assertEquals("Agent 1", goal.tasks[0].assignedAgentId)
        assertTrue(goal.tasks[0].dependencies.isEmpty())
    }

    @Test
    fun testDynamicAgentCreation_ComplexImageAndShareHasDynamicThreeAgents() {
        val goal = DynamicGoalDecomposer.decompose(
            "Gemini से एक अच्छी फोटो बनाओ और WhatsApp पर पापा को भेज दो",
            contextEngine
        )

        assertEquals("IMAGE_GENERATION_AND_SHARE", goal.userIntent)
        assertEquals(3, goal.tasks.size)

        // Verifies dynamic naming: Agent 1, Agent 2, Agent 3 (no fixed app-based agents)
        assertEquals("Agent 1", goal.tasks[0].assignedAgentId)
        assertEquals("Agent 2", goal.tasks[1].assignedAgentId)
        assertEquals("Agent 3", goal.tasks[2].assignedAgentId)

        // Verifies tools assigned according to task, NOT fixed to app
        assertEquals("generate_image", goal.tasks[0].toolName)
        assertEquals("open_app", goal.tasks[1].toolName)
        assertEquals("type_text", goal.tasks[2].toolName)

        // Verifies dependency DAG
        assertTrue(goal.tasks[0].dependencies.isEmpty())
        assertEquals(listOf(goal.tasks[0].taskId), goal.tasks[1].dependencies)
        assertEquals(listOf(goal.tasks[1].taskId), goal.tasks[2].dependencies)

        // Verifies safety approval requirement for external communication
        assertTrue(goal.requiresUserApproval)
        assertTrue(goal.tasks[2].requiresApproval)
    }

    @Test
    fun testDynamicAgentCreation_ResearchAndSummaryHasThreeAgents() {
        val goal = DynamicGoalDecomposer.decompose(
            "Internet par AI models ke bare me research karo aur document summary banao",
            contextEngine
        )

        assertEquals("RESEARCH_AND_DOCUMENT", goal.userIntent)
        assertEquals(3, goal.tasks.size)
        assertEquals("Agent 1", goal.tasks[0].assignedAgentId)
        assertEquals("Agent 2", goal.tasks[1].assignedAgentId)
        assertEquals("Agent 3", goal.tasks[2].assignedAgentId)

        assertEquals("research_web", goal.tasks[0].toolName)
        assertEquals("semantic_search", goal.tasks[1].toolName)
        assertEquals("memory_create", goal.tasks[2].toolName)
    }

    @Test
    fun testNaturalLanguage_ReversedWordOrderProducesConsistentIntent() {
        val goalStandard = DynamicGoalDecomposer.decompose("Gemini se photo banao aur WhatsApp par papa ko bhej do", contextEngine)
        val goalReversed = DynamicGoalDecomposer.decompose("WhatsApp par papa ko photo bhejo Gemini se bana kar", contextEngine)

        assertEquals(goalStandard.userIntent, goalReversed.userIntent)
        assertEquals(goalStandard.tasks.size, goalReversed.tasks.size)
        assertEquals(goalStandard.requiresUserApproval, goalReversed.requiresUserApproval)
    }

    @Test
    fun testNaturalLanguage_EnglishEquivalentProducesConsistentIntent() {
        val goalEnglish = DynamicGoalDecomposer.decompose(
            "Generate an image with Gemini and send it to Papa on WhatsApp",
            contextEngine
        )

        assertEquals("IMAGE_GENERATION_AND_SHARE", goalEnglish.userIntent)
        assertEquals(3, goalEnglish.tasks.size)
        assertTrue(goalEnglish.requiresUserApproval)
    }

    @Test
    fun testNaturalLanguage_MultiAppChainedWorkflow() {
        val goal = DynamicGoalDecomposer.decompose(
            "YouTube kholo aur video play karo, fir Instagram kholo aur message bhej kar wapas aao",
            contextEngine
        )

        assertEquals("MULTI_APP_CHAIN", goal.userIntent)
        assertTrue(goal.tasks.size >= 2)
        assertEquals("Agent 1", goal.tasks[0].assignedAgentId)
        assertEquals("Agent 2", goal.tasks[1].assignedAgentId)
        assertTrue(goal.requiresUserApproval)
    }

    @Test
    fun testJarvisVisualGuidance_PublishExplanationCard() {
        val card = JarvisGuidanceEngine.publishExplanation(
            title = "Quantum Computing Basics",
            summary = "Computation based on quantum superpositions and qubits.",
            bulletPoints = listOf("Qubits can be 0, 1, or both", "Quantum entanglement enables exponential state representation"),
            details = "Useful for cryptography, chemical simulation, and optimization.",
            diagramFlow = listOf("Qubit Initialization", "Quantum Gate Transformation", "Measurement"),
            citations = listOf("Nature 2024", "IBM Quantum")
        )

        assertNotNull(card)
        assertEquals("Quantum Computing Basics", card.title)
        assertEquals(2, card.bulletChips.size)
        assertEquals(3, card.diagramFlow.size)
        assertEquals(card, JarvisGuidanceEngine.activeExplanation.value)

        JarvisGuidanceEngine.dismissExplanation()
        assertNull(JarvisGuidanceEngine.activeExplanation.value)
    }

    @Test
    fun testJarvisVisualGuidance_ClearGuidanceState() {
        JarvisGuidanceEngine.clearGuidance()
        assertFalse(JarvisGuidanceEngine.guidanceState.value.isActive)
    }

    @Test
    fun testVisionPipeline_FrameCompressionAndPerception() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        VisionPipeline.onNewFrame(bitmap)

        assertTrue(VisionPipeline.perceptionState.value.isPipelineActive)
        assertEquals(1, VisionPipeline.perceptionState.value.totalFramesProcessed)
        assertNotNull(VisionPipeline.getLatestFrame())

        val jpegBytes = VisionPipeline.getCompressedJpegFrame(maxDimension = 100, quality = 80)
        assertNotNull(jpegBytes)
        assertTrue(jpegBytes!!.isNotEmpty())

        VisionPipeline.reset()
        assertFalse(VisionPipeline.perceptionState.value.isPipelineActive)
        assertNull(VisionPipeline.getLatestFrame())
    }

    @Test
    fun testGoalTaskNode_DependencyValidation() {
        val goalId = "test_goal_1"
        val task1 = GoalTaskNode(
            taskId = "t1",
            parentGoalId = goalId,
            assignedAgentId = "Agent 1",
            description = "Fetch data",
            toolName = "search_web",
            parameters = mapOf("query" to "Kotlin"),
            dependencies = emptyList(),
            status = TaskNodeStatus.COMPLETED
        )

        val task2 = GoalTaskNode(
            taskId = "t2",
            parentGoalId = goalId,
            assignedAgentId = "Agent 2",
            description = "Process data",
            toolName = "semantic_search",
            parameters = mapOf("query" to "Kotlin"),
            dependencies = listOf("t1"),
            status = TaskNodeStatus.PENDING
        )

        // When parent is completed, task2 dependencies are satisfied
        val tasksMap = mapOf("t1" to task1, "t2" to task2)
        val depsSatisfied = task2.dependencies.all { depId -> tasksMap[depId]?.status == TaskNodeStatus.COMPLETED }
        assertTrue(depsSatisfied)
    }
}
