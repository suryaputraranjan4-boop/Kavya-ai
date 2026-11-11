package com.example.visual

import android.content.Context
import android.graphics.Rect
import android.util.Log
import com.example.agent.FreshScreenRecognizer
import com.example.agent.UIElementResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State for JARVIS "Shows You How" interactive visual guidance.
 */
data class JarvisGuidanceState(
    val isActive: Boolean = false,
    val targetQuery: String = "",
    val targetLabel: String = "",
    val targetBounds: Rect? = null,
    val clickPoint: Pair<Int, Int>? = null,
    val instructionText: String = "",
    val stepIndex: Int = 1,
    val totalSteps: Int = 1,
    val isUserActionDetected: Boolean = false,
    val confidence: Float = 1.0f
)

/**
 * Rich Visual Explanation Card model for "JARVIS Explains Anything".
 */
data class VisualExplanationCard(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val oneLineSummary: String,
    val bulletChips: List<String>,
    val detailParagraph: String,
    val comparisonTable: List<Pair<String, String>> = emptyList(),
    val diagramFlow: List<String> = emptyList(),
    val sourceCitations: List<String> = emptyList(),
    val confidenceScore: Float = 0.95f,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * High-precision guidance and visual explanation engine for JARVIS UI.
 */
object JarvisGuidanceEngine {

    private const val TAG = "JarvisGuidanceEngine"

    private val _guidanceState = MutableStateFlow(JarvisGuidanceState())
    val guidanceState: StateFlow<JarvisGuidanceState> = _guidanceState.asStateFlow()

    private val _activeExplanation = MutableStateFlow<VisualExplanationCard?>(null)
    val activeExplanation: StateFlow<VisualExplanationCard?> = _activeExplanation.asStateFlow()

    /**
     * Resolves and highlights an element on the active screen for step-by-step guidance.
     */
    fun showHowToTap(targetDescription: String, customPrompt: String? = null): Boolean {
        Log.i(TAG, "Locating element for 'Shows You How': '$targetDescription'")
        val freshScreen = FreshScreenRecognizer.inspectFreshScreenNow()
        val resolved = UIElementResolver.resolveTargetElement(freshScreen, targetDescription)

        if (resolved != null && resolved.bounds.width() > 0 && resolved.bounds.height() > 0) {
            val spokenPrompt = customPrompt ?: "यहाँ टैप कीजिए: ${resolved.targetText}"
            _guidanceState.value = JarvisGuidanceState(
                isActive = true,
                targetQuery = targetDescription,
                targetLabel = resolved.targetText,
                targetBounds = resolved.bounds,
                clickPoint = resolved.clickPoint,
                instructionText = spokenPrompt,
                confidence = resolved.confidence
            )
            // Propagate to JarvisOverlayController for screen overlay
            JarvisOverlayController.highlightElement(
                bounds = resolved.bounds,
                label = resolved.targetText,
                clickPoint = resolved.clickPoint,
                prompt = spokenPrompt
            )
            Log.i(TAG, "Highlighted '${resolved.targetText}' at bounds: ${resolved.bounds}")
            return true
        }

        Log.w(TAG, "Could not resolve element for '$targetDescription' on active screen.")
        return false
    }

    /**
     * Highlights an element while talking ("Circle While Talking").
     */
    fun circleWhileTalking(targetText: String, speechText: String) {
        showHowToTap(targetText, speechText)
    }

    /**
     * Dismisses active reticle / highlight guidance.
     */
    fun clearGuidance() {
        _guidanceState.value = JarvisGuidanceState(isActive = false)
        JarvisOverlayController.clearAllVisuals()
    }

    /**
     * Creates and publishes a structured visual explanation card for "JARVIS Explains Anything".
     */
    fun publishExplanation(
        title: String,
        summary: String,
        bulletPoints: List<String>,
        details: String = "",
        comparisons: List<Pair<String, String>> = emptyList(),
        diagramFlow: List<String> = emptyList(),
        citations: List<String> = emptyList(),
        confidence: Float = 0.95f
    ): VisualExplanationCard {
        val card = VisualExplanationCard(
            title = title,
            oneLineSummary = summary,
            bulletChips = bulletPoints,
            detailParagraph = details,
            comparisonTable = comparisons,
            diagramFlow = diagramFlow,
            sourceCitations = citations,
            confidenceScore = confidence
        )
        _activeExplanation.value = card

        // Also compile into rich VisualCardData for the JARVIS Overlay system
        val overlayCard = VisualCardData(
            id = card.id,
            type = if (comparisons.isNotEmpty()) VisualCardType.COMPARISON else if (details.isNotBlank()) VisualCardType.DETAIL_TYPEWRITER else VisualCardType.SUMMARY,
            title = title,
            summary = summary,
            bullets = bulletPoints,
            detailText = details,
            comparisonItems = comparisons.map { ComparisonItem(it.first, it.second, "", "") },
            citations = citations,
            confidence = confidence
        )
        val plan = JarvisVisualPlan(
            userQuery = title,
            mode = JarvisMode.EXPLAINS_ANYTHING,
            title = title,
            cards = listOf(overlayCard)
        )
        JarvisOverlayController.presentVisualPlan(plan)

        return card
    }

    /**
     * Universal entry point: executes a user's natural language request through JARVIS Intelligence.
     */
    fun executeJarvisQuery(query: String) {
        val plan = JarvisIntelligencePlanner.createPlan(query)
        JarvisOverlayController.presentVisualPlan(plan)
        if (plan.cards.isNotEmpty()) {
            val firstCard = plan.cards.first()
            _activeExplanation.value = VisualExplanationCard(
                id = firstCard.id,
                title = firstCard.title,
                oneLineSummary = firstCard.summary,
                bulletChips = firstCard.bullets,
                detailParagraph = firstCard.detailText,
                confidenceScore = firstCard.confidence
            )
        }
        if (plan.instructions.isNotEmpty()) {
            val firstInst = plan.instructions.first()
            _guidanceState.value = JarvisGuidanceState(
                isActive = true,
                targetQuery = firstInst.label,
                targetLabel = firstInst.label,
                targetBounds = firstInst.bounds,
                clickPoint = firstInst.clickPoint,
                instructionText = firstInst.text
            )
        }
    }

    fun dismissExplanation() {
        _activeExplanation.value = null
        JarvisOverlayController.clearAllVisuals()
    }
}
