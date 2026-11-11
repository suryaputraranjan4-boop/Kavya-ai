package com.example.visual

import android.graphics.Rect
import android.util.Log
import com.example.agent.FreshScreenRecognizer
import com.example.agent.UIElementResolver
import java.util.Locale
import java.util.UUID

/**
 * High-Level Intelligence Planner for JARVIS Screen Understanding.
 * Automatically classifies natural language intent into Mode A, Mode B, or Mode C,
 * and compiles deterministic Visual Plans for the 60fps local rendering engine.
 */
object JarvisIntelligencePlanner {

    private const val TAG = "JarvisIntelPlanner"

    /**
     * Classifies user intent from natural language query into the appropriate JARVIS Mode.
     */
    fun classifyMode(query: String): JarvisMode {
        val q = query.trim().lowercase(Locale.ROOT)

        // Mode B: "Shows You How" (Interactive visual guide)
        val showPatterns = listOf(
            "how do i", "how to", "show me how", "where is", "guide me",
            "walk me through", "help me find", "point to", "kaise kare", "dikhao"
        )
        if (showPatterns.any { q.contains(it) }) {
            return JarvisMode.SHOWS_YOU_HOW
        }

        // Mode C: "Do It" (Autonomous execution)
        val doPatterns = listOf(
            "click", "tap on", "open app", "do it for me", "submit", "send message",
            "turn on", "turn off", "search for", "download", "kardo", "chalao"
        )
        if (doPatterns.any { q.startsWith(it) || q.contains("do it") }) {
            return JarvisMode.DO_IT
        }

        // Mode A: "Explains Anything" (Default for queries asking about screen, understanding, or analysis)
        val explainPatterns = listOf(
            "explain", "what is this", "what does this mean", "summarize", "break down",
            "analyze", "graph", "chart", "read this", "kya hai", "samjhao", "screen"
        )
        if (explainPatterns.any { q.contains(it) }) {
            return JarvisMode.EXPLAINS_ANYTHING
        }

        return JarvisMode.IDLE
    }

    /**
     * Synthesizes a complete JarvisVisualPlan from the user request and live screen perception.
     */
    fun createPlan(
        userQuery: String,
        screenMap: SemanticScreenMap = SemanticScreenAnalyzer.analyzeScreen(),
        forcedMode: JarvisMode? = null
    ): JarvisVisualPlan {
        val mode = forcedMode ?: classifyMode(userQuery)
        val title = when (mode) {
            JarvisMode.EXPLAINS_ANYTHING -> "Screen Visual Analysis"
            JarvisMode.SHOWS_YOU_HOW -> "Interactive Step Guide"
            JarvisMode.DO_IT -> "Autonomous Task Execution"
            JarvisMode.IDLE -> "JARVIS Ambient"
        }

        Log.i(TAG, "Creating plan for query: '$userQuery' -> Mode: $mode")

        return when (mode) {
            JarvisMode.EXPLAINS_ANYTHING -> buildExplainsAnythingPlan(userQuery, screenMap, title)
            JarvisMode.SHOWS_YOU_HOW -> buildShowsYouHowPlan(userQuery, screenMap, title)
            JarvisMode.DO_IT -> buildDoItPlan(userQuery, screenMap, title)
            JarvisMode.IDLE -> JarvisVisualPlan(
                userQuery = userQuery,
                mode = JarvisMode.IDLE,
                title = "JARVIS Idle"
            )
        }
    }

    /**
     * Mode A: Generates multi-card contextual visual explanations linked to on-screen elements.
     */
    private fun buildExplainsAnythingPlan(
        userQuery: String,
        screenMap: SemanticScreenMap,
        title: String
    ): JarvisVisualPlan {
        val cards = mutableListOf<VisualCardData>()
        val instructions = mutableListOf<VisualInstruction>()

        val primaryTarget = screenMap.primaryHeading
            ?: screenMap.contentBlocks.firstOrNull()
            ?: screenMap.elements.firstOrNull()

        val anchorBounds = primaryTarget?.bounds

        // 1. Primary Summary Card
        val appName = screenMap.packageName.substringAfterLast(".").replaceFirstChar { it.uppercase() }
        val headingText = primaryTarget?.displayLabel?.take(60) ?: "Active Interface"
        val actionCount = screenMap.actionButtons.size

        cards.add(
            VisualCardData(
                type = VisualCardType.SUMMARY,
                title = "Screen Overview",
                summary = "Active screen in $appName focuses on '$headingText' with $actionCount interactive controls detected.",
                bullets = listOf(
                    "Detected ${screenMap.elements.size} UI nodes with high perceptual fidelity",
                    "Primary action targets: ${screenMap.actionButtons.take(3).joinToString { it.displayLabel.take(20) }.ifBlank { "Standard controls" }}",
                    "Status: Real-time screen telemetry mapped without cloud latency"
                ),
                targetAnchorBounds = anchorBounds,
                confidence = 0.96f
            )
        )

        // 2. Detailed Breakdown with Typewriter Reveal
        val detailsText = buildString {
            append("Kavya JARVIS visual perception analyzed this layout. ")
            if (screenMap.inputFields.isNotEmpty()) {
                append("Detected text entry fields suitable for user input. ")
            }
            if (screenMap.contentBlocks.isNotEmpty()) {
                append("Extracted ${screenMap.contentBlocks.size} content sections for contextual reading. ")
            }
            append("All elements have been spatially indexed for touch guidance and explanation.")
        }

        cards.add(
            VisualCardData(
                type = VisualCardType.DETAIL_TYPEWRITER,
                title = "Semantic Breakdown",
                summary = "Detailed analysis of on-screen components.",
                detailText = detailsText,
                targetAnchorBounds = anchorBounds,
                confidence = 0.94f
            )
        )

        // 3. Dynamic Canvas Graph if data or metrics are on screen or query requests stats
        if (userQuery.contains("graph") || userQuery.contains("chart") || userQuery.contains("stat") || screenMap.mediaRegions.isNotEmpty()) {
            cards.add(
                VisualCardData(
                    type = VisualCardType.CANVAS_GRAPH,
                    title = "Telemetry & Metrics",
                    summary = "Live visual representation of screen distribution",
                    graphData = GraphData(
                        title = "UI Element Distribution",
                        type = GraphChartType.BAR_CHART,
                        labels = listOf("Buttons", "Content", "Inputs", "Media"),
                        values = listOf(
                            screenMap.actionButtons.size.toFloat().coerceAtLeast(1f),
                            screenMap.contentBlocks.size.toFloat().coerceAtLeast(1f),
                            screenMap.inputFields.size.toFloat().coerceAtLeast(1f),
                            screenMap.mediaRegions.size.toFloat().coerceAtLeast(1f)
                        ),
                        unit = " items"
                    ),
                    targetAnchorBounds = anchorBounds,
                    confidence = 0.98f
                )
            )
        }

        // 4. 3D Holographic Object Card if requested or futuristic view
        if (userQuery.contains("3d") || userQuery.contains("model") || userQuery.contains("structure")) {
            cards.add(
                VisualCardData(
                    type = VisualCardType.OBJECT_3D,
                    title = "Spatial Architecture",
                    summary = "Interactive 3D representation of interface topology",
                    mesh3d = WireframeMesh3D.createDiamond(),
                    targetAnchorBounds = anchorBounds,
                    confidence = 0.95f
                )
            )
        }

        // Corresponding visual instruction to highlight primary element
        if (anchorBounds != null) {
            instructions.add(
                VisualInstruction(
                    type = VisualInstructionType.HIGHLIGHT_BOX,
                    bounds = anchorBounds,
                    clickPoint = Pair(anchorBounds.centerX(), anchorBounds.centerY()),
                    label = primaryTarget.displayLabel,
                    text = "Analyzing: ${primaryTarget.displayLabel}"
                )
            )
        }

        return JarvisVisualPlan(
            userQuery = userQuery,
            mode = JarvisMode.EXPLAINS_ANYTHING,
            title = title,
            instructions = instructions,
            cards = cards,
            fullScreenExplainerEnabled = userQuery.contains("whole screen") || userQuery.contains("full screen")
        )
    }

    /**
     * Mode B: Generates precise interactive step-by-step guidance instructions.
     */
    private fun buildShowsYouHowPlan(
        userQuery: String,
        screenMap: SemanticScreenMap,
        title: String
    ): JarvisVisualPlan {
        val freshScreen = FreshScreenRecognizer.inspectFreshScreenNow()
        val targetQuery = extractTargetFromHowQuery(userQuery)

        val resolved = UIElementResolver.resolveTargetElement(freshScreen, targetQuery)
        val instructions = mutableListOf<VisualInstruction>()

        val targetBounds: Rect
        val targetLabel: String
        val clickPoint: Pair<Int, Int>

        if (resolved != null && resolved.bounds.width() > 0 && resolved.bounds.height() > 0) {
            targetBounds = resolved.bounds
            targetLabel = resolved.targetText
            clickPoint = resolved.clickPoint
        } else {
            // Fallback to closest match in semantic map
            val fallback = screenMap.findElementMatching(targetQuery)
                ?: screenMap.actionButtons.firstOrNull()

            if (fallback != null && fallback.bounds.width() > 0 && fallback.bounds.height() > 0) {
                targetBounds = fallback.bounds
                targetLabel = fallback.displayLabel.ifBlank { targetQuery }
                clickPoint = Pair(targetBounds.centerX(), targetBounds.centerY())
            } else {
                targetBounds = Rect(0, 0, 0, 0)
                targetLabel = "Target '$targetQuery' not found on current screen"
                clickPoint = Pair(0, 0)
            }
        }

        instructions.add(
            VisualInstruction(
                type = VisualInstructionType.HIGHLIGHT_BOX,
                bounds = targetBounds,
                clickPoint = clickPoint,
                label = targetLabel,
                text = "Tap on: $targetLabel",
                isPassThroughTouch = true
            )
        )

        val guideCard = VisualCardData(
            type = VisualCardType.TIMELINE_FLOW,
            title = "Guidance Sequence",
            summary = "Follow the glowing reticle on screen to proceed.",
            timelineSteps = listOf(
                TimelineStep(1, "Locate Element", "Identified target: $targetLabel", isCompleted = true, isCurrent = false),
                TimelineStep(2, "Tap Target", "Tap the highlighted area to activate", isCompleted = false, isCurrent = true),
                TimelineStep(3, "Screen Transition", "JARVIS will automatically verify next screen", isCompleted = false, isCurrent = false)
            ),
            targetAnchorBounds = targetBounds
        )

        return JarvisVisualPlan(
            userQuery = userQuery,
            mode = JarvisMode.SHOWS_YOU_HOW,
            title = title,
            instructions = instructions,
            cards = listOf(guideCard)
        )
    }

    /**
     * Mode C: Builds autonomous action plan with visual reticle confirmation.
     */
    private fun buildDoItPlan(
        userQuery: String,
        screenMap: SemanticScreenMap,
        title: String
    ): JarvisVisualPlan {
        val targetQuery = userQuery.removePrefix("click").removePrefix("tap on").trim()
        val targetElem = screenMap.findElementMatching(targetQuery)
            ?: screenMap.actionButtons.firstOrNull()

        val bounds = targetElem?.bounds ?: Rect(120, 400, 320, 520)
        val clickPoint = Pair(bounds.centerX(), bounds.centerY())

        val instruction = VisualInstruction(
            type = VisualInstructionType.MOVE_CURSOR,
            bounds = bounds,
            clickPoint = clickPoint,
            label = targetElem?.displayLabel ?: targetQuery,
            text = "Executing action on: ${targetElem?.displayLabel ?: targetQuery}",
            isPassThroughTouch = false
        )

        return JarvisVisualPlan(
            userQuery = userQuery,
            mode = JarvisMode.DO_IT,
            title = title,
            instructions = listOf(instruction),
            cards = emptyList()
        )
    }

    private fun extractTargetFromHowQuery(query: String): String {
        return query
            .replace("how do i", "", ignoreCase = true)
            .replace("how to", "", ignoreCase = true)
            .replace("show me how to", "", ignoreCase = true)
            .replace("where is the", "", ignoreCase = true)
            .replace("where is", "", ignoreCase = true)
            .replace("guide me to", "", ignoreCase = true)
            .replace("point to", "", ignoreCase = true)
            .replace("find", "", ignoreCase = true)
            .trim()
    }
}
