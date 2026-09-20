package com.example

import com.example.agent.ContextEngine
import com.example.agent.KavyaToolRegistry
import com.example.agent.SelectorType
import com.example.agent.TaskPlanner
import com.example.agent.UniversalActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TaskPlannerMultiStepTest {

    @Test
    fun testMultiStepYouTubeSearchAndOrdinalSelection() {
        val planner = TaskPlanner()
        val contextEngine = ContextEngine()
        val prompt = "Open YouTube, search AI news, and open the second result"
        
        val plan = planner.createPlan(prompt, contextEngine)
        assertNotNull("Plan should be generated for multi-step YouTube command", plan)
        assertTrue("Plan must have multiple steps", plan!!.steps.size >= 3)
        
        // Verify Step 1: Open YouTube
        val step1 = plan.steps[0]
        assertEquals(UniversalActionType.OPEN_APP, step1.actionType)
        assertTrue(step1.targetAppOrUrl.lowercase().contains("youtube"))
        
        // Verify Step 2: Search/Type
        val step2 = plan.steps[1]
        assertTrue(
            step2.actionType == UniversalActionType.SEARCH || 
            step2.actionType == UniversalActionType.TYPE
        )
        assertTrue("Search query must be clean without ordinal wrapper", step2.param.contains("AI news"))
        
        // Verify Ordinal Step exists
        val ordinalStep = plan.steps.find { 
            it.actionType == UniversalActionType.SELECT || 
            it.actionType == UniversalActionType.SELECT_RESULT ||
            it.actionType == UniversalActionType.TAP 
        }
        assertNotNull("Ordinal selection step must be present", ordinalStep)
    }

    @Test
    fun testKavyaToolRegistryHasAllSeventeenTools() {
        val tools = KavyaToolRegistry.AVAILABLE_TOOLS
        assertEquals(17, tools.size)
        
        val expectedTools = listOf(
            "open_app", "close_app", "tap", "type_text", "scroll", "swipe",
            "press_back", "search_web", "open_url", "read_screen", "screenshot",
            "download_file", "switch_app", "analyze_image", "research_web",
            "generate_image", "semantic_search"
        )
        
        for (expected in expectedTools) {
            assertTrue("Registry must contain tool: $expected", tools.any { it.name == expected })
        }
    }

    @Test
    fun testYouTubeOpenVideoCommandAddsPlayStep() {
        val planner = TaskPlanner()
        val contextEngine = ContextEngine()
        val prompt = "youtube pe koi video open karke do"
        
        val plan = planner.createPlan(prompt, contextEngine)
        assertNotNull("Plan should be generated for YouTube open video command", plan)
        
        // Must contain OPEN_APP for YouTube
        val openAppStep = plan!!.steps.find { it.actionType == UniversalActionType.OPEN_APP }
        assertNotNull("Must have OPEN_APP step", openAppStep)
        assertTrue(openAppStep!!.targetAppOrUrl.lowercase().contains("youtube"))

        // Must have PLAY step to open and play the video (not just search!)
        val playStep = plan.steps.find { it.actionType == UniversalActionType.PLAY }
        assertNotNull("Must have PLAY step to completely open the video", playStep)
        assertEquals(0, playStep!!.ordinalIndex)
    }

    @Test
    fun testOrdinalWebsiteSelectionParsing() {
        val router = com.example.utils.CommandRouter(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        
        // 2nd website
        val second1 = router.parseOrdinalSelectionCommand("open 2nd website")
        assertNotNull(second1)
        assertEquals(1, second1!!.first)

        val second2 = router.parseOrdinalSelectionCommand("open second website")
        assertNotNull(second2)
        assertEquals(1, second2!!.first)

        val second3 = router.parseOrdinalSelectionCommand("dusri website kholo")
        assertNotNull(second3)
        assertEquals(1, second3!!.first)

        val secondOrThird = router.parseOrdinalSelectionCommand("open 2nd or third website")
        assertNotNull(secondOrThird)
        assertEquals(1, secondOrThird!!.first)

        // 3rd website
        val third1 = router.parseOrdinalSelectionCommand("open 3rd website")
        assertNotNull(third1)
        assertEquals(2, third1!!.first)

        val third2 = router.parseOrdinalSelectionCommand("open third website")
        assertNotNull(third2)
        assertEquals(2, third2!!.first)

        val third3 = router.parseOrdinalSelectionCommand("teesri website kholo")
        assertNotNull(third3)
        assertEquals(2, third3!!.first)
    }
}
