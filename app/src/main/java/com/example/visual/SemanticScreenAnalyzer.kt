package com.example.visual

import android.graphics.Rect
import android.util.Log
import com.example.agent.AnalyzedNode
import com.example.agent.FreshScreenRecognizer
import com.example.agent.FreshScreenState
import java.util.UUID

/**
 * High-level semantic classification of UI elements.
 */
enum class SemanticElementType {
    TITLE_HEADER,
    PARAGRAPH_TEXT,
    ACTION_BUTTON,
    INPUT_FIELD,
    MEDIA_IMAGE,
    LIST_ITEM,
    GRAPH_CHART_REGION,
    NAV_BAR,
    GENERIC
}

/**
 * Enriched semantic representation of a UI component on screen.
 */
data class SemanticElement(
    val id: String = UUID.randomUUID().toString(),
    val type: SemanticElementType,
    val bounds: Rect,
    val text: String,
    val contentDescription: String,
    val resourceId: String = "",
    val className: String = "",
    val isClickable: Boolean = false,
    val centerPoint: Pair<Int, Int> = Pair(bounds.centerX(), bounds.centerY()),
    val importanceScore: Float = 0.5f
) {
    val displayLabel: String
        get() = when {
            text.isNotBlank() -> text
            contentDescription.isNotBlank() -> contentDescription
            resourceId.isNotBlank() -> resourceId.substringAfterLast("/")
            else -> type.name
        }
}

/**
 * Screen layout zones for non-overlapping card positioning and central screen preview.
 */
data class ScreenLayoutZones(
    val screenWidth: Int,
    val screenHeight: Int,
    val topBannerZone: Rect = Rect(16, 80, screenWidth - 16, 260),
    val bottomDockZone: Rect = Rect(16, screenHeight - 420, screenWidth - 16, screenHeight - 60),
    val leftMarginZone: Rect = Rect(16, 280, (screenWidth * 0.44f).toInt(), screenHeight - 440),
    val rightMarginZone: Rect = Rect((screenWidth * 0.56f).toInt(), 280, screenWidth - 16, screenHeight - 440),
    val centerFocusZone: Rect = Rect(
        (screenWidth * 0.15f).toInt(),
        (screenHeight * 0.18f).toInt(),
        (screenWidth * 0.85f).toInt(),
        (screenHeight * 0.75f).toInt()
    )
) {
    /**
     * Determines the optimal non-overlapping position for an explanation card
     * adjacent to the target element without obscuring it.
     */
    fun findBestCardAnchor(
        targetBounds: Rect,
        cardWidth: Int = 540,
        cardHeight: Int = 320,
        margin: Int = 24
    ): Rect {
        val fitsBelow = targetBounds.bottom + cardHeight + margin <= screenHeight - 80
        val fitsAbove = targetBounds.top - cardHeight - margin >= 80
        val fitsRight = targetBounds.right + cardWidth + margin <= screenWidth - 16
        val fitsLeft = targetBounds.left - cardWidth - margin >= 16

        // Prefer placing below, then above, then right, then left
        return when {
            fitsBelow -> {
                val left = (targetBounds.centerX() - cardWidth / 2).coerceIn(16, screenWidth - cardWidth - 16)
                Rect(left, targetBounds.bottom + margin, left + cardWidth, targetBounds.bottom + margin + cardHeight)
            }
            fitsAbove -> {
                val left = (targetBounds.centerX() - cardWidth / 2).coerceIn(16, screenWidth - cardWidth - 16)
                Rect(left, targetBounds.top - cardHeight - margin, left + cardWidth, targetBounds.top - margin)
            }
            fitsRight -> {
                val top = (targetBounds.centerY() - cardHeight / 2).coerceIn(80, screenHeight - cardHeight - 80)
                Rect(targetBounds.right + margin, top, targetBounds.right + margin + cardWidth, top + cardHeight)
            }
            fitsLeft -> {
                val top = (targetBounds.centerY() - cardHeight / 2).coerceIn(80, screenHeight - cardHeight - 80)
                Rect(targetBounds.left - cardWidth - margin, top, targetBounds.left - margin, top + cardHeight)
            }
            else -> {
                // Default fallback: place at bottom dock
                Rect(16, screenHeight - cardHeight - 90, screenWidth - 16, screenHeight - 90)
            }
        }
    }
}

/**
 * Complete semantic map of the active screen with spatial relationships.
 */
data class SemanticScreenMap(
    val packageName: String,
    val elements: List<SemanticElement>,
    val primaryHeading: SemanticElement?,
    val actionButtons: List<SemanticElement>,
    val inputFields: List<SemanticElement>,
    val contentBlocks: List<SemanticElement>,
    val mediaRegions: List<SemanticElement>,
    val layoutZones: ScreenLayoutZones,
    val screenTitle: String = primaryHeading?.displayLabel ?: ""
) {
    fun findElementNear(x: Int, y: Int, threshold: Int = 100): SemanticElement? {
        return elements.minByOrNull { elem ->
            val dx = elem.centerPoint.first - x
            val dy = elem.centerPoint.second - y
            dx * dx + dy * dy
        }?.takeIf {
            val dx = it.centerPoint.first - x
            val dy = it.centerPoint.second - y
            kotlin.math.sqrt((dx * dx + dy * dy).toDouble()) <= threshold
        }
    }

    fun findElementMatching(query: String): SemanticElement? {
        val rawQ = query.trim().lowercase()
        if (rawQ.isBlank()) return actionButtons.firstOrNull() ?: elements.firstOrNull()

        // 1. Direct match
        val direct = elements.firstOrNull { elem ->
            elem.text.lowercase().contains(rawQ) ||
            elem.contentDescription.lowercase().contains(rawQ) ||
            elem.resourceId.lowercase().contains(rawQ)
        }
        if (direct != null) return direct

        // 2. Stripped keyword match (e.g. "the settings button" -> "settings")
        val cleaned = rawQ
            .replace(Regex("\\b(the|a|an|this|that|button|icon|option|tab|section|menu|field|element|control)\\b"), " ")
            .trim()
        val tokens = cleaned.split(Regex("\\s+")).filter { it.length >= 2 }
        if (tokens.isNotEmpty()) {
            return elements
                .filter { elem ->
                    val hay = "${elem.text} ${elem.contentDescription} ${elem.resourceId}".lowercase()
                    tokens.any { hay.contains(it) }
                }
                .maxByOrNull { elem ->
                    val hay = "${elem.text} ${elem.contentDescription} ${elem.resourceId}".lowercase()
                    val matchCount = tokens.count { hay.contains(it) }
                    matchCount * 10 + (if (elem.isClickable) 5 else 0) + elem.importanceScore
                }
        }
        return null
    }
}

/**
 * Universal Semantic Screen Analyzer combining Accessibility Hierarchy,
 * classification, and spatial geometry.
 */
object SemanticScreenAnalyzer {

    private const val TAG = "SemanticScreenAnalyzer"

    /**
     * Builds a comprehensive SemanticScreenMap from a fresh snapshot of the screen.
     */
    fun analyzeScreen(
        freshScreen: FreshScreenState = FreshScreenRecognizer.inspectFreshScreenNow(),
        screenWidth: Int = ScreenAnnotationManager.coordinateMapper.getGeometry().width,
        screenHeight: Int = ScreenAnnotationManager.coordinateMapper.getGeometry().height
    ): SemanticScreenMap {
        val zones = ScreenLayoutZones(screenWidth, screenHeight)
        val elements = mutableListOf<SemanticElement>()

        for (node in freshScreen.nodes) {
            if (node.bounds.width() <= 0 || node.bounds.height() <= 0) continue

            val type = classifyNodeType(node, screenHeight)
            val importance = calculateImportance(node, type)

            elements.add(
                SemanticElement(
                    type = type,
                    bounds = node.bounds,
                    text = node.text,
                    contentDescription = node.contentDescription,
                    resourceId = node.resourceId,
                    className = node.className,
                    isClickable = node.isClickable,
                    importanceScore = importance
                )
            )
        }

        val primaryHeading = elements
            .filter { it.type == SemanticElementType.TITLE_HEADER }
            .maxByOrNull { it.bounds.width() * it.bounds.height() }
            ?: elements.firstOrNull { it.type == SemanticElementType.TITLE_HEADER }

        val actionButtons = elements.filter { it.type == SemanticElementType.ACTION_BUTTON }
        val inputFields = elements.filter { it.type == SemanticElementType.INPUT_FIELD }
        val contentBlocks = elements.filter { it.type == SemanticElementType.PARAGRAPH_TEXT }
        val mediaRegions = elements.filter { it.type == SemanticElementType.MEDIA_IMAGE || it.type == SemanticElementType.GRAPH_CHART_REGION }

        Log.d(TAG, "Analyzed screen: ${elements.size} elements, ${actionButtons.size} buttons, ${contentBlocks.size} content blocks")

        return SemanticScreenMap(
            packageName = freshScreen.foregroundPackage,
            elements = elements,
            primaryHeading = primaryHeading,
            actionButtons = actionButtons,
            inputFields = inputFields,
            contentBlocks = contentBlocks,
            mediaRegions = mediaRegions,
            layoutZones = zones,
            screenTitle = freshScreen.screenTitle
        )
    }

    private fun classifyNodeType(node: AnalyzedNode, screenHeight: Int): SemanticElementType {
        val cls = node.className.lowercase()
        val text = node.text
        val desc = node.contentDescription
        val id = node.resourceId.lowercase()

        return when {
            node.isEditable || cls.contains("edittext") || cls.contains("input") || id.contains("search") ->
                SemanticElementType.INPUT_FIELD

            node.isClickable && (cls.contains("button") || id.contains("btn") || id.contains("action") || id.contains("submit")) ->
                SemanticElementType.ACTION_BUTTON

            cls.contains("imageview") || cls.contains("image") || node.isAvatarOrPhoto ->
                SemanticElementType.MEDIA_IMAGE

            id.contains("chart") || id.contains("graph") || desc.contains("chart") || desc.contains("graph") ->
                SemanticElementType.GRAPH_CHART_REGION

            node.bounds.top > (screenHeight * 0.88f) && node.isClickable ->
                SemanticElementType.NAV_BAR

            node.bounds.top < (screenHeight * 0.18f) && (text.length in 3..60 || desc.length in 3..60) ->
                SemanticElementType.TITLE_HEADER

            text.length > 50 || (text.contains("\n") && text.length > 30) ->
                SemanticElementType.PARAGRAPH_TEXT

            node.isClickable ->
                SemanticElementType.ACTION_BUTTON

            text.isNotBlank() || desc.isNotBlank() ->
                SemanticElementType.PARAGRAPH_TEXT

            else ->
                SemanticElementType.GENERIC
        }
    }

    private fun calculateImportance(node: AnalyzedNode, type: SemanticElementType): Float {
        var score = 0.5f
        if (node.isClickable) score += 0.25f
        if (node.isEditable) score += 0.35f
        if (type == SemanticElementType.TITLE_HEADER) score += 0.3f
        if (type == SemanticElementType.ACTION_BUTTON) score += 0.25f
        if (node.isAd) score -= 0.4f
        return score.coerceIn(0.0f, 1.0f)
    }
}
