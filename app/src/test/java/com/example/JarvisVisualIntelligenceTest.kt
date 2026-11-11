package com.example

import android.graphics.Rect
import com.example.visual.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class JarvisVisualIntelligenceTest {

    // -------------------------------------------------------------------------
    // TEST 1: 3D Point Math, Euler Rotation, and Perspective Projection
    // -------------------------------------------------------------------------
    @Test
    fun test3DProjectionAndEulerRotation() {
        val original = Point3D(1.0f, 0.0f, 0.0f)

        // Rotate 90 degrees (PI / 2) around Y axis -> should point in Z direction
        val rotY = original.rotateY((Math.PI / 2.0).toFloat())
        assertTrue("X should be near 0", abs(rotY.x) < 0.001f)
        assertTrue("Z should be near -1", abs(rotY.z - (-1.0f)) < 0.001f)

        // Test Perspective Projection to 2D
        val screenWidth = 1080f
        val screenHeight = 2400f
        val projected = original.project(screenWidth, screenHeight, fov = 300f, viewDistance = 3f)

        // Screen center is (540, 1200); positive X projects to right of center
        assertTrue("Projected X should be right of center", projected.x > screenWidth / 2f)
        assertEquals("Projected Y should be at center", screenHeight / 2f, projected.y, 0.01f)

        // Test Standard Meshes
        val cube = WireframeMesh3D.createCube()
        assertEquals(8, cube.vertices.size)
        assertEquals(12, cube.edges.size)

        val diamond = WireframeMesh3D.createDiamond()
        assertEquals(6, diamond.vertices.size)
        assertEquals(12, diamond.edges.size)

        val pyramid = WireframeMesh3D.createPyramid()
        assertEquals(5, pyramid.vertices.size)
        assertEquals(8, pyramid.edges.size)
    }

    // -------------------------------------------------------------------------
    // TEST 2: Screen Layout Zones & Non-Overlapping Card Positioning
    // -------------------------------------------------------------------------
    @Test
    fun testScreenLayoutZonesCardAnchoring() {
        val zones = ScreenLayoutZones(screenWidth = 1080, screenHeight = 2400)

        // Target located in the upper half of screen (e.g. top = 200, bottom = 300)
        val upperTarget = Rect(200, 200, 600, 300)
        val anchorBelow = zones.findBestCardAnchor(upperTarget, cardWidth = 500, cardHeight = 300)

        // Card should be placed below the target element
        assertTrue("Card top must be >= target bottom", anchorBelow.top >= upperTarget.bottom)
        assertTrue("Card must fit within screen bounds", anchorBelow.bottom <= 2400)

        // Target located near the bottom of screen (e.g. top = 2100, bottom = 2200)
        val lowerTarget = Rect(200, 2100, 600, 2200)
        val anchorAbove = zones.findBestCardAnchor(lowerTarget, cardWidth = 500, cardHeight = 300)

        // Card should be placed above the target element
        assertTrue("Card bottom must be <= target top", anchorAbove.bottom <= lowerTarget.top)
        assertTrue("Card top must be >= top margin", anchorAbove.top >= 80)
    }

    // -------------------------------------------------------------------------
    // TEST 3: Semantic Screen Map Lookup & Distance Metrics
    // -------------------------------------------------------------------------
    @Test
    fun testSemanticScreenMapElementQuerying() {
        val btn = SemanticElement(
            type = SemanticElementType.ACTION_BUTTON,
            bounds = Rect(100, 500, 400, 600),
            text = "Submit Application",
            contentDescription = "Submit button",
            isClickable = true
        )
        val heading = SemanticElement(
            type = SemanticElementType.TITLE_HEADER,
            bounds = Rect(50, 100, 800, 200),
            text = "Loan Details",
            contentDescription = "Heading",
            isClickable = false
        )

        val map = SemanticScreenMap(
            packageName = "com.example.bank",
            elements = listOf(btn, heading),
            primaryHeading = heading,
            actionButtons = listOf(btn),
            inputFields = emptyList(),
            contentBlocks = emptyList(),
            mediaRegions = emptyList(),
            layoutZones = ScreenLayoutZones(1080, 2400)
        )

        // Query matching text
        val foundByText = map.findElementMatching("submit")
        assertNotNull(foundByText)
        assertEquals("Submit Application", foundByText?.text)

        // Query spatial proximity (center of btn is 250, 550)
        val foundNear = map.findElementNear(260, 560, threshold = 50)
        assertNotNull(foundNear)
        assertEquals("Submit Application", foundNear?.text)

        // Too far away
        val notFound = map.findElementNear(900, 1800, threshold = 50)
        assertNull(notFound)
    }

    // -------------------------------------------------------------------------
    // TEST 4: Intent Classification across Three Core Modes
    // -------------------------------------------------------------------------
    @Test
    fun testIntentClassificationModes() {
        // Mode A: Explains Anything
        assertEquals(JarvisMode.EXPLAINS_ANYTHING, JarvisIntelligencePlanner.classifyMode("Explain this screen"))
        assertEquals(JarvisMode.EXPLAINS_ANYTHING, JarvisIntelligencePlanner.classifyMode("What does this graph mean?"))
        assertEquals(JarvisMode.EXPLAINS_ANYTHING, JarvisIntelligencePlanner.classifyMode("Summarize the details on this page"))
        assertEquals(JarvisMode.EXPLAINS_ANYTHING, JarvisIntelligencePlanner.classifyMode("Yeh screen par kya hai samjhao"))

        // Mode B: Shows You How
        assertEquals(JarvisMode.SHOWS_YOU_HOW, JarvisIntelligencePlanner.classifyMode("How do I tap on the settings button?"))
        assertEquals(JarvisMode.SHOWS_YOU_HOW, JarvisIntelligencePlanner.classifyMode("Where is the checkout button"))
        assertEquals(JarvisMode.SHOWS_YOU_HOW, JarvisIntelligencePlanner.classifyMode("Guide me to profile page"))
        assertEquals(JarvisMode.SHOWS_YOU_HOW, JarvisIntelligencePlanner.classifyMode("Kaise kare login dikhao"))

        // Mode C: Do It
        assertEquals(JarvisMode.DO_IT, JarvisIntelligencePlanner.classifyMode("Click submit"))
        assertEquals(JarvisMode.DO_IT, JarvisIntelligencePlanner.classifyMode("Tap on back button"))
        assertEquals(JarvisMode.DO_IT, JarvisIntelligencePlanner.classifyMode("Do it for me"))
        assertEquals(JarvisMode.DO_IT, JarvisIntelligencePlanner.classifyMode("Turn on wifi"))
    }

    // -------------------------------------------------------------------------
    // TEST 5: Visual Plan Synthesis (Mode A: Explains Anything)
    // -------------------------------------------------------------------------
    @Test
    fun testVisualPlanGenerationModeA() {
        val heading = SemanticElement(
            type = SemanticElementType.TITLE_HEADER,
            bounds = Rect(40, 100, 600, 220),
            text = "Analytics Dashboard",
            contentDescription = "Dashboard"
        )
        val button = SemanticElement(
            type = SemanticElementType.ACTION_BUTTON,
            bounds = Rect(40, 300, 400, 420),
            text = "Export Data",
            contentDescription = "Export"
        )

        val screenMap = SemanticScreenMap(
            packageName = "com.example.analytics",
            elements = listOf(heading, button),
            primaryHeading = heading,
            actionButtons = listOf(button),
            inputFields = emptyList(),
            contentBlocks = emptyList(),
            mediaRegions = emptyList(),
            layoutZones = ScreenLayoutZones(1080, 2400)
        )

        val plan = JarvisIntelligencePlanner.createPlan(
            userQuery = "Explain this analytics graph",
            screenMap = screenMap,
            forcedMode = JarvisMode.EXPLAINS_ANYTHING
        )

        assertEquals(JarvisMode.EXPLAINS_ANYTHING, plan.mode)
        assertTrue("Must have multiple explanation cards", plan.cards.size >= 2)

        // Must have summary card and detail typewriter card
        val summaryCard = plan.cards.find { it.type == VisualCardType.SUMMARY }
        assertNotNull(summaryCard)
        assertTrue(summaryCard!!.summary.contains("com.example.analytics") || summaryCard.summary.contains("Analytics"))

        // Since query mentioned "graph", must also contain CANVAS_GRAPH card
        val graphCard = plan.cards.find { it.type == VisualCardType.CANVAS_GRAPH }
        assertNotNull(graphCard)
        assertNotNull(graphCard?.graphData)
        assertEquals(GraphChartType.BAR_CHART, graphCard?.graphData?.type)
    }

    // -------------------------------------------------------------------------
    // TEST 6: Visual Plan Synthesis (Mode B: Shows You How)
    // -------------------------------------------------------------------------
    @Test
    fun testVisualPlanGenerationModeB() {
        val button = SemanticElement(
            type = SemanticElementType.ACTION_BUTTON,
            bounds = Rect(100, 500, 400, 620),
            text = "Proceed to Checkout",
            contentDescription = "Checkout button",
            isClickable = true
        )

        val screenMap = SemanticScreenMap(
            packageName = "com.example.shop",
            elements = listOf(button),
            primaryHeading = null,
            actionButtons = listOf(button),
            inputFields = emptyList(),
            contentBlocks = emptyList(),
            mediaRegions = emptyList(),
            layoutZones = ScreenLayoutZones(1080, 2400)
        )

        val plan = JarvisIntelligencePlanner.createPlan(
            userQuery = "Where is checkout button",
            screenMap = screenMap,
            forcedMode = JarvisMode.SHOWS_YOU_HOW
        )

        assertEquals(JarvisMode.SHOWS_YOU_HOW, plan.mode)
        assertTrue("Must have visual instruction for highlighting target", plan.instructions.isNotEmpty())

        val highlightInst = plan.instructions.first()
        assertEquals(VisualInstructionType.HIGHLIGHT_BOX, highlightInst.type)
        assertNotNull(highlightInst.bounds)
        assertTrue(highlightInst.isPassThroughTouch)

        // Must include timeline sequence card
        val timelineCard = plan.cards.find { it.type == VisualCardType.TIMELINE_FLOW }
        assertNotNull(timelineCard)
        assertTrue(timelineCard!!.timelineSteps.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // TEST 7: JarvisOverlayController State & Presentation Lifecycle
    // -------------------------------------------------------------------------
    @Test
    fun testJarvisOverlayControllerLifecycle() {
        JarvisOverlayController.clearAllVisuals()
        assertEquals(JarvisOrbState.IDLE, JarvisOverlayController.orbState.value)
        assertNull(JarvisOverlayController.activePlan.value)

        val card = VisualCardData(
            type = VisualCardType.SUMMARY,
            title = "Test Card",
            summary = "Sample summary"
        )
        val plan = JarvisVisualPlan(
            userQuery = "Test",
            mode = JarvisMode.EXPLAINS_ANYTHING,
            title = "Test Title",
            cards = listOf(card)
        )

        JarvisOverlayController.presentVisualPlan(plan)
        assertEquals(JarvisOrbState.EXPLAINING, JarvisOverlayController.orbState.value)
        assertEquals(plan, JarvisOverlayController.activePlan.value)

        // Highlight element
        val testBounds = Rect(50, 100, 250, 200)
        JarvisOverlayController.highlightElement(testBounds, "Sample Element")
        assertEquals(JarvisOrbState.GUIDING, JarvisOverlayController.orbState.value)
        assertNotNull(JarvisOverlayController.activeInstruction.value)
        assertEquals(testBounds, JarvisOverlayController.activeInstruction.value?.bounds)

        // Clear all visuals
        JarvisOverlayController.clearAllVisuals()
        assertEquals(JarvisOrbState.IDLE, JarvisOverlayController.orbState.value)
        assertNull(JarvisOverlayController.activePlan.value)
        assertNull(JarvisOverlayController.activeInstruction.value)
    }

    // -------------------------------------------------------------------------
    // TEST 8: 9 Orb States & Theme Presets
    // -------------------------------------------------------------------------
    @Test
    fun testOrbStatesAndThemePresets() {
        assertEquals(9, JarvisOrbState.entries.size)
        assertTrue(JarvisOrbState.entries.contains(JarvisOrbState.IDLE))
        assertTrue(JarvisOrbState.entries.contains(JarvisOrbState.LISTENING))
        assertTrue(JarvisOrbState.entries.contains(JarvisOrbState.THINKING))
        assertTrue(JarvisOrbState.entries.contains(JarvisOrbState.SPEAKING))
        assertTrue(JarvisOrbState.entries.contains(JarvisOrbState.WORKING))
        assertTrue(JarvisOrbState.entries.contains(JarvisOrbState.EXPLAINING))
        assertTrue(JarvisOrbState.entries.contains(JarvisOrbState.GUIDING))
        assertTrue(JarvisOrbState.entries.contains(JarvisOrbState.SUCCESS))
        assertTrue(JarvisOrbState.entries.contains(JarvisOrbState.ERROR))

        // Themes
        assertEquals(5, JarvisThemePreset.entries.size)
        assertTrue(JarvisThemePreset.entries.contains(JarvisThemePreset.NEON_CYAN))
        assertTrue(JarvisThemePreset.entries.contains(JarvisThemePreset.ELECTRIC_BLUE))
        assertTrue(JarvisThemePreset.entries.contains(JarvisThemePreset.AMBER_GOLD))
        assertTrue(JarvisThemePreset.entries.contains(JarvisThemePreset.HOLOGRAPHIC_VIOLET))
        assertTrue(JarvisThemePreset.entries.contains(JarvisThemePreset.EMERALD_MATRIX))
    }

    // -------------------------------------------------------------------------
    // TEST 9: Truthful Inactive Screen Perception & Non-Screen General Queries
    // -------------------------------------------------------------------------
    @Test
    fun testInactiveScreenPerceptionTruthfulness() {
        // General knowledge query must NOT trigger screen overlay EXPLAINS_ANYTHING
        assertEquals(JarvisMode.IDLE, JarvisIntelligencePlanner.classifyMode("Explain recursion in programming"))

        // Empty screen map & no live frame must report 0.0f confidence and Screen Perception Inactive
        VisionPipeline.clearFrame()
        val emptyMap = SemanticScreenMap(
            packageName = "",
            elements = emptyList(),
            primaryHeading = null,
            actionButtons = emptyList(),
            inputFields = emptyList(),
            contentBlocks = emptyList(),
            mediaRegions = emptyList(),
            layoutZones = ScreenLayoutZones(1080, 2400)
        )
        val inactivePlan = JarvisIntelligencePlanner.createPlan(
            userQuery = "What is on my screen?",
            screenMap = emptyMap
        )
        assertEquals(JarvisMode.EXPLAINS_ANYTHING, inactivePlan.mode)
        assertEquals(0.0f, inactivePlan.cards.first().confidence, 0.001f)
        assertEquals("Screen Perception Inactive", inactivePlan.cards.first().title)
    }

    // -------------------------------------------------------------------------
    // TEST 10: Hindi, English & Hinglish Natural-Language Paraphrase Suite
    // -------------------------------------------------------------------------
    @Test
    fun testNaturalLanguageParaphrasesHindiEnglishHinglish() {
        val openYt1 = com.example.agent.CommandInterpreter.interpret("YouTube kholo")
        val openYt2 = com.example.agent.CommandInterpreter.interpret("Zara YouTube open kar do")
        val openYt3 = com.example.agent.CommandInterpreter.interpret("Can you launch YouTube please?")
        val openYt4 = com.example.agent.CommandInterpreter.interpret("Mujhe YouTube dikhao")
        assertTrue(openYt1 is com.example.agent.CommandIntent.OpenApp && openYt1.appName == "YouTube")
        assertTrue(openYt2 is com.example.agent.CommandIntent.OpenApp && openYt2.appName == "YouTube")
        assertTrue(openYt3 is com.example.agent.CommandIntent.OpenApp && openYt3.appName == "YouTube")
        assertTrue(openYt4 is com.example.agent.CommandIntent.OpenApp && openYt4.appName == "YouTube")

        val msg1 = com.example.agent.CommandInterpreter.interpret("WhatsApp par Rohan ko hello bhejo")
        val msg2 = com.example.agent.CommandInterpreter.interpret("Rohan ko WhatsApp pe hi likh do")
        val msg3 = com.example.agent.CommandInterpreter.interpret("Send hello to Rohan on WhatsApp")
        assertTrue(msg1 is com.example.agent.CommandIntent.SendMessage && msg1.recipient.equals("Rohan", true) && msg1.messageText.equals("hello", true))
        assertTrue(msg2 is com.example.agent.CommandIntent.SendMessage && msg2.recipient.equals("Rohan", true) && msg2.messageText.equals("hi", true))
        assertTrue(msg3 is com.example.agent.CommandIntent.SendMessage && msg3.recipient.equals("Rohan", true) && msg3.messageText.equals("hello", true))

        val play1 = com.example.agent.CommandInterpreter.interpret("Spotify pe Arijit Singh chalao")
        val play2 = com.example.agent.CommandInterpreter.interpret("Arijit ke gaane baja do Spotify par")
        val play3 = com.example.agent.CommandInterpreter.interpret("Play something on YouTube")
        assertTrue(play1 is com.example.agent.CommandIntent.PlayMedia && play1.app == "Spotify" && play1.mediaQuery.contains("Arijit", true))
        assertTrue(play2 is com.example.agent.CommandIntent.PlayMedia && play2.app == "Spotify" && play2.mediaQuery.contains("Arijit", true))
        assertTrue(play3 is com.example.agent.CommandIntent.PlayMedia && play3.app == "YouTube")
    }
}
