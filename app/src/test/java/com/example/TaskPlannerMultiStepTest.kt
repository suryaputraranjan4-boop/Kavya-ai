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
}
