package com.example.visual

import android.graphics.Rect
import android.util.Log
import com.example.agent.FreshScreenRecognizer
import com.example.agent.UIElementResolver
import java.util.Locale

/**
 * High-Level Intelligence Planner for JARVIS Screen Understanding.
 * Automatically classifies natural language intent into Mode A, Mode B, or Mode C,
 * and compiles deterministic Visual Plans with real screen coordinates for the 60fps annotation engine.
 */
object JarvisIntelligencePlanner {

    private const val TAG = "JarvisIntelPlanner"

    /**
     * Classifies user intent from natural language query into the appropriate JARVIS Mode.
     */
    fun classifyMode(query: String): JarvisMode {
        val q = query.trim().lowercase(Locale.ROOT)

        // Mode B: "Shows You How" (Interactive visual guide / pointer / where to tap)
        val showPatterns = listOf(
            "how do i", "how to", "show me how", "where is", "guide me",
            "walk me through", "help me find", "point to", "kaise kare", "dikhao",
            "where should i tap", "show me where to click", "where to click", "where to tap",
            "what should i do next", "kahan tap karu", "kaha click karu", "button kahan hai"
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

        // Mode A: "Explains Anything" (Screen, visible UI, button/option explanation)
        val screenContextMarkers = listOf(
            "screen", "on my screen", "screen pe", "screen par", "is page", "this page",
            "entire page", "whole page", "what is this", "what's this", "what does this mean",
            "read this", "summarize this", "analyze this", "ye graph", "this graph",
            "this chart", "ye chart", "yahan kya", "dikha raha", "visible", "kya likha hai",
            "what is happening here", "what's happening here", "explain this",
            "what does this option do", "what does this button do",
            "what happens if i press", "what happens if i tap", "ye button kya hai"
        )
        if (screenContextMarkers.any { q.contains(it) }) {
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
     * Mode A: Generates multi-step visual explanations & highlight boxes linked to real on-screen elements.
     */
    private fun buildExplainsAnythingPlan(
        userQuery: String,
        screenMap: SemanticScreenMap,
        title: String
    ): JarvisVisualPlan {
        val cards = mutableListOf<VisualCardData>()
        val instructions = mutableListOf<VisualInstruction>()

        val hasAccessibilityData = screenMap.elements.isNotEmpty()
        val hasLiveFrame = VisionPipeline.getLatestFrame() != null

        if (!hasAccessibilityData && !hasLiveFrame) {
            cards.add(
                VisualCardData(
                    type = VisualCardType.SUMMARY,
                    title = "Screen Perception Inactive",
                    summary = "No live screen frame or Accessibility UI tree is currently available.",
                    bullets = listOf(
                        "Enable Screen Share or Accessibility Service to let Kavya see your screen",
                        "Once active, ask 'Explain this screen' or 'Where should I tap?'"
                    ),
                    targetAnchorBounds = null,
                    confidence = 0.0f
                )
            )
            return JarvisVisualPlan(
                userQuery = userQuery,
                mode = JarvisMode.EXPLAINS_ANYTHING,
                title = title,
                instructions = emptyList(),
                cards = cards,
                fullScreenExplainerEnabled = false,
                targetPackage = screenMap.packageName
            )
        }

        val appName = screenMap.packageName.substringAfterLast(".").ifBlank { "Current App" }.replaceFirstChar { it.uppercase() }

        // Check if the user asked about a specific button/element on screen
        val specificTargetName = extractSpecificTargetFromExplainQuery(userQuery)
        val matchedSpecificElement = if (specificTargetName.isNotBlank()) {
            screenMap.findElementMatching(specificTargetName)
        } else null

        // Collect ordered key elements on screen for visual step-by-step explanation
        val orderedSections = mutableListOf<SemanticElement>()
        if (matchedSpecificElement != null) {
            orderedSections.add(matchedSpecificElement)
        } else {
            screenMap.primaryHeading?.let { orderedSections.add(it) }
            screenMap.inputFields.firstOrNull()?.let { input ->
                if (orderedSections.none { overlapsSignificantly(it.bounds, input.bounds) }) {
                    orderedSections.add(input)
                }
            }
            screenMap.contentBlocks.take(2).forEach { block ->
                if (orderedSections.none { overlapsSignificantly(it.bounds, block.bounds) }) {
                    orderedSections.add(block)
                }
            }
            screenMap.actionButtons.take(3).forEach { btn ->
                if (orderedSections.none { overlapsSignificantly(it.bounds, btn.bounds) }) {
                    orderedSections.add(btn)
                }
            }
            if (orderedSections.isEmpty() && screenMap.elements.isNotEmpty()) {
                orderedSections.addAll(screenMap.elements.take(3))
            }
        }

        // Sort top-to-bottom so visual walkthrough flows naturally down the screen
        val sortedSections = orderedSections
            .filter { it.bounds.width() > 0 && it.bounds.height() > 0 }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .take(5)

        val primaryTarget = sortedSections.firstOrNull()
            ?: screenMap.primaryHeading
            ?: screenMap.contentBlocks.firstOrNull()
            ?: screenMap.elements.firstOrNull()

        val anchorBounds = primaryTarget?.bounds
        val headingText = primaryTarget?.displayLabel?.take(60)
            ?: if (hasLiveFrame) "Live Screen Frame Captured" else "Active Interface"
        val actionCount = screenMap.actionButtons.size

        val computedConfidence = when {
            hasAccessibilityData && hasLiveFrame -> 0.95f
            hasAccessibilityData || hasLiveFrame -> 0.88f
            else -> 0.0f
        }

        // Build multi-step VisualInstructions for each key screen section
        sortedSections.forEachIndexed { idx, elem ->
            val cleanName = elem.displayLabel.take(40)
            val calloutLabel = when {
                elem.type == SemanticElementType.INPUT_FIELD -> "Input field: $cleanName"
                elem.isClickable || elem.type == SemanticElementType.ACTION_BUTTON -> "Tap here: $cleanName"
                elem.type == SemanticElementType.TITLE_HEADER -> "Header: $cleanName"
                else -> "Section: $cleanName"
            }
            val spokenExplanation = when {
                elem.type == SemanticElementType.TITLE_HEADER ->
                    "Step ${idx + 1}: This is the '$cleanName' header at the top of $appName."
                elem.type == SemanticElementType.INPUT_FIELD ->
                    "Step ${idx + 1}: Tap '$cleanName' here to type or search."
                elem.isClickable || elem.type == SemanticElementType.ACTION_BUTTON ->
                    "Step ${idx + 1}: '$cleanName' is an interactive button. Tap here to open or activate '$cleanName'."
                else ->
                    "Step ${idx + 1}: This section displays '$cleanName'."
            }
            instructions.add(
                VisualInstruction(
                    type = VisualInstructionType.HIGHLIGHT_BOX,
                    bounds = elem.bounds,
                    clickPoint = Pair(elem.bounds.centerX(), elem.bounds.centerY()),
                    label = calloutLabel,
                    text = spokenExplanation,
                    isPassThroughTouch = true
                )
            )
        }

        // 1. Primary Summary Card grounded in actual screen nodes/frame
        cards.add(
            VisualCardData(
                type = VisualCardType.SUMMARY,
                title = "Screen Overview ($appName)",
                summary = if (hasAccessibilityData) {
                    "Active screen in $appName focuses on '$headingText' with $actionCount interactive controls and ${sortedSections.size} highlighted sections."
                } else {
                    "Live visual frame captured from $appName. Analyzing visible content with Vision AI."
                },
                bullets = buildList {
                    sortedSections.take(3).forEachIndexed { i, sec ->
                        add("Step ${i + 1}: ${sec.displayLabel.take(36)} (${sec.type.name.replace("_", " ").lowercase()})")
                    }
                    if (hasLiveFrame) {
                        add("Live MediaProjection frame active (${VisionPipeline.getFrameAgeMs()}ms age)")
                    }
                },
                targetAnchorBounds = anchorBounds,
                confidence = computedConfidence
            )
        )

        // 2. Detailed Breakdown with Typewriter Reveal
        val detailsText = buildString {
            if (hasAccessibilityData) {
                append("Extracted ${screenMap.elements.size} UI elements from $appName. ")
                if (screenMap.inputFields.isNotEmpty()) {
                    append("Detected ${screenMap.inputFields.size} text input field(s). ")
                }
                if (screenMap.actionButtons.isNotEmpty()) {
                    val topBtns = screenMap.actionButtons.take(4).joinToString(", ") { it.displayLabel.take(25) }
                    append("Key buttons: $topBtns. ")
                }
                if (screenMap.contentBlocks.isNotEmpty()) {
                    val sampleContent = screenMap.contentBlocks.take(3).joinToString(" | ") { it.displayLabel.take(40) }
                    append("Visible content: $sampleContent.")
                }
            } else {
                append("Visual frame captured via MediaProjection. Vision analysis is inspecting the rendered pixels.")
            }
        }

        cards.add(
            VisualCardData(
                type = VisualCardType.DETAIL_TYPEWRITER,
                title = "Semantic Breakdown",
                summary = "Grounded analysis of on-screen components.",
                detailText = detailsText,
                targetAnchorBounds = anchorBounds,
                confidence = computedConfidence
            )
        )

        // 3. Dynamic Canvas Graph if data or metrics are on screen or query requests stats
        if (userQuery.contains("graph", ignoreCase = true) ||
            userQuery.contains("chart", ignoreCase = true) ||
            userQuery.contains("stat", ignoreCase = true) ||
            screenMap.mediaRegions.isNotEmpty()
        ) {
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

        // 4. 3D Holographic Object Card if requested
        if (userQuery.contains("3d", ignoreCase = true) ||
            userQuery.contains("model", ignoreCase = true) ||
            userQuery.contains("structure", ignoreCase = true)
        ) {
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

        return JarvisVisualPlan(
            userQuery = userQuery,
            mode = JarvisMode.EXPLAINS_ANYTHING,
            title = title,
            instructions = instructions,
            cards = cards,
            fullScreenExplainerEnabled = userQuery.contains("whole screen", ignoreCase = true) ||
                    userQuery.contains("full screen", ignoreCase = true),
            targetPackage = screenMap.packageName
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
        val lowerQ = userQuery.lowercase(Locale.ROOT)
        val isMultiStepPageGuide = lowerQ.contains("through this page") ||
                lowerQ.contains("step by step") ||
                lowerQ.contains("entire page")

        val instructions = mutableListOf<VisualInstruction>()

        if (isMultiStepPageGuide && screenMap.elements.size > 1) {
            val interactiveElements = (screenMap.inputFields + screenMap.actionButtons)
                .ifEmpty { screenMap.elements }
                .filter { it.bounds.width() > 0 && it.bounds.height() > 0 }
                .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
                .take(5)

            interactiveElements.forEachIndexed { idx, elem ->
                val cleanLabel = elem.displayLabel.take(36)
                instructions.add(
                    VisualInstruction(
                        type = VisualInstructionType.HIGHLIGHT_BOX,
                        bounds = elem.bounds,
                        clickPoint = Pair(elem.bounds.centerX(), elem.bounds.centerY()),
                        label = "Tap here to open $cleanLabel",
                        text = "Step ${idx + 1}: Tap on '$cleanLabel' to interact with this control.",
                        isPassThroughTouch = true
                    )
                )
            }
        }

        if (instructions.isEmpty()) {
            val freshScreen = FreshScreenRecognizer.inspectFreshScreenNow()
            val targetQuery = extractTargetFromHowQuery(userQuery)

            val resolved = if (targetQuery.isNotBlank()) {
                UIElementResolver.resolveTargetElement(freshScreen, targetQuery)
            } else null

            val targetBounds: Rect
            val targetLabel: String
            val clickPoint: Pair<Int, Int>

            if (resolved != null && resolved.bounds.width() > 0 && resolved.bounds.height() > 0) {
                targetBounds = resolved.bounds
                targetLabel = resolved.targetText
                clickPoint = resolved.clickPoint
            } else {
                val fallback = screenMap.findElementMatching(targetQuery)
                    ?: screenMap.actionButtons.firstOrNull()
                    ?: screenMap.inputFields.firstOrNull()
                    ?: screenMap.elements.firstOrNull()

                if (fallback != null && fallback.bounds.width() > 0 && fallback.bounds.height() > 0) {
                    targetBounds = fallback.bounds
                    targetLabel = fallback.displayLabel.ifBlank { targetQuery.ifBlank { "Action Button" } }
                    clickPoint = Pair(targetBounds.centerX(), targetBounds.centerY())
                } else {
                    targetBounds = Rect(0, 0, 0, 0)
                    targetLabel = "Target '${targetQuery.ifBlank { "button" }}' not found on current screen"
                    clickPoint = Pair(0, 0)
                }
            }

            val callout = if (targetBounds.width() > 0) {
                "Tap here to open $targetLabel"
            } else {
                targetLabel
            }
            val spoken = if (targetBounds.width() > 0) {
                "Tap here on '$targetLabel' to proceed."
            } else {
                "Could not locate '${targetQuery.ifBlank { "the requested button" }}' on the current screen."
            }

            instructions.add(
                VisualInstruction(
                    type = VisualInstructionType.HIGHLIGHT_BOX,
                    bounds = targetBounds,
                    clickPoint = clickPoint,
                    label = callout,
                    text = spoken,
                    isPassThroughTouch = true
                )
            )
        }

        val firstInst = instructions.first()
        val guideCard = VisualCardData(
            type = VisualCardType.TIMELINE_FLOW,
            title = "Guidance Sequence",
            summary = firstInst.text.ifBlank { "Follow the glowing highlight box and arrow on screen." },
            timelineSteps = instructions.mapIndexed { idx, inst ->
                TimelineStep(
                    stepIndex = idx + 1,
                    title = inst.label,
                    description = inst.text,
                    isCompleted = false,
                    isCurrent = idx == 0
                )
            }.ifEmpty {
                listOf(
                    TimelineStep(1, "Locate Element", "Identified target on screen", isCompleted = true, isCurrent = false),
                    TimelineStep(2, "Tap Target", "Tap the highlighted area to activate", isCompleted = false, isCurrent = true)
                )
            },
            targetAnchorBounds = firstInst.bounds
        )

        return JarvisVisualPlan(
            userQuery = userQuery,
            mode = JarvisMode.SHOWS_YOU_HOW,
            title = title,
            instructions = instructions,
            cards = listOf(guideCard),
            targetPackage = screenMap.packageName
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
        val targetQuery = userQuery
            .replace(Regex("^(click|tap on|tap|press)\\s+", RegexOption.IGNORE_CASE), "")
            .trim()
        val targetElem = screenMap.findElementMatching(targetQuery)
            ?: screenMap.actionButtons.firstOrNull()

        val bounds = targetElem?.bounds ?: Rect(0, 0, 0, 0)
        val clickPoint = Pair(bounds.centerX(), bounds.centerY())

        val instruction = VisualInstruction(
            type = VisualInstructionType.MOVE_CURSOR,
            bounds = bounds,
            clickPoint = clickPoint,
            label = targetElem?.displayLabel ?: targetQuery,
            text = "Executing action on: ${targetElem?.displayLabel ?: targetQuery}",
            isPassThroughTouch = true
        )

        return JarvisVisualPlan(
            userQuery = userQuery,
            mode = JarvisMode.DO_IT,
            title = title,
            instructions = listOf(instruction),
            cards = emptyList(),
            targetPackage = screenMap.packageName
        )
    }

    private fun overlapsSignificantly(a: Rect, b: Rect): Boolean {
        val dx = kotlin.math.abs(a.centerX() - b.centerX())
        val dy = kotlin.math.abs(a.centerY() - b.centerY())
        return dx < 48 && dy < 48
    }

    private fun extractSpecificTargetFromExplainQuery(query: String): String {
        val q = query.lowercase(Locale.ROOT).trim()
        if (q in listOf(
                "explain this screen", "explain this", "explain the entire page",
                "explain this page", "what is happening here", "what's happening here",
                "what is on my screen", "what's on my screen"
            )
        ) {
            return ""
        }
        val cleaned = q
            .replace("explain this", "")
            .replace("explain the", "")
            .replace("what does this", "")
            .replace("what does the", "")
            .replace("what happens if i press", "")
            .replace("what happens if i tap", "")
            .replace("option do", "")
            .replace("button do", "")
            .replace("button", "")
            .replace("option", "")
            .replace("?", "")
            .trim()
        return if (cleaned.length in 2..35 && !cleaned.contains("screen") && !cleaned.contains("page")) {
            cleaned
        } else {
            ""
        }
    }

    private fun extractTargetFromHowQuery(query: String): String {
        return query
            .replace("how do i tap on the", "", ignoreCase = true)
            .replace("how do i tap on", "", ignoreCase = true)
            .replace("how do i", "", ignoreCase = true)
            .replace("how to", "", ignoreCase = true)
            .replace("show me how to", "", ignoreCase = true)
            .replace("show me where to click", "", ignoreCase = true)
            .replace("where should i tap", "", ignoreCase = true)
            .replace("where is the", "", ignoreCase = true)
            .replace("where is", "", ignoreCase = true)
            .replace("guide me through this page", "", ignoreCase = true)
            .replace("guide me step by step", "", ignoreCase = true)
            .replace("guide me to", "", ignoreCase = true)
            .replace("point to the next", "", ignoreCase = true)
            .replace("point to", "", ignoreCase = true)
            .replace("what should i do next", "", ignoreCase = true)
            .replace("find", "", ignoreCase = true)
            .replace("?", "")
            .trim()
    }
}
