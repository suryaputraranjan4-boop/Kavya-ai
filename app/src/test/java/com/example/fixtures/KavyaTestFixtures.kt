package com.example.fixtures

import android.graphics.Rect
import com.example.ai.AiErrorCategory
import com.example.ai.VoiceInputErrorCategory
import com.example.visual.UnifiedUIElement
import com.example.visual.SignalSource
import com.example.visual.InputFieldRole
import com.example.visual.IconRole
import com.example.agent.UniversalTaskDecomposer
import com.example.agent.TaskPlan
import com.example.agent.TaskStep
import com.example.agent.UniversalActionType
import java.io.IOException

/**
 * Reusable, deterministic test fixtures for Kavya AI unit, integration, and Robolectric tests.
 */
object KavyaTestFixtures {

    // ==========================================
    // 1. AI & API Response & Error Fixtures
    // ==========================================

    data class MockHttpResponse(
        val statusCode: Int,
        val bodyText: String,
        val headers: Map<String, String> = emptyMap()
    )

    fun createSuccessfulAiResponseJson(text: String = "यह आपका जवाब है।"): String {
        return """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [{"text": "$text"}]
                  },
                  "finishReason": "STOP"
                }
              ]
            }
        """.trimIndent()
    }

    fun createMalformedJsonResponse(): String {
        return """{ "candidates": [ { "content": { "parts": [ { "text": "truncated... """
    }

    fun createQuotaExhaustedError(): MockHttpResponse {
        return MockHttpResponse(
            statusCode = 429,
            bodyText = """{"error": {"code": 429, "message": "You exceeded your current daily quota and billing plan limit.", "status": "RESOURCE_EXHAUSTED"}}""",
            headers = mapOf("Retry-After" to "30")
        )
    }

    fun createRateLimitError(): MockHttpResponse {
        return MockHttpResponse(
            statusCode = 429,
            bodyText = """{"error": {"code": 429, "message": "Rate limit reached per minute. Please retry in 5s.", "status": "RESOURCE_EXHAUSTED"}}""",
            headers = mapOf("Retry-After" to "5")
        )
    }

    fun createInvalidApiKeyError(): MockHttpResponse {
        return MockHttpResponse(
            statusCode = 401,
            bodyText = """{"error": {"code": 401, "message": "API key not valid. Please pass a valid API key.", "status": "UNAUTHENTICATED"}}"""
        )
    }

    fun createServer500Error(): MockHttpResponse {
        return MockHttpResponse(
            statusCode = 500,
            bodyText = """{"error": {"code": 500, "message": "Internal server error.", "status": "INTERNAL"}}"""
        )
    }

    fun createNetworkTimeoutException(): IOException {
        return IOException("java.net.SocketTimeoutException: Read timed out to api.generativeai.google")
    }

    // ==========================================
    // 2. Voice & Microphone Input Fixtures
    // ==========================================

    data class MockVoiceUtterance(
        val text: String,
        val language: String,
        val confidence: Float = 0.95f
    )

    fun createMockHindiUtterance(): MockVoiceUtterance {
        return MockVoiceUtterance(
            text = "YouTube खोलकर Arijit Singh के गाने चलाओ और फिर WhatsApp पर राहुल को मैसेज भेजो",
            language = "hi-IN"
        )
    }

    fun createMockHinglishUtterance(): MockVoiceUtterance {
        return MockVoiceUtterance(
            text = "YouTube kholke Hi search karo aur WhatsApp pe dost को Hi bhejo",
            language = "hi-IN"
        )
    }

    fun createMockEnglishUtterance(): MockVoiceUtterance {
        return MockVoiceUtterance(
            text = "Open YouTube, search for relax music, and send a message to Mom",
            language = "en-US"
        )
    }

    // ==========================================
    // 3. Screen Perception & Element Fixtures
    // ==========================================

    fun createMockSearchFieldElement(
        id: String = "com.google.android.youtube:id/search_edit_text",
        bounds: Rect = Rect(100, 50, 900, 150)
    ): UnifiedUIElement {
        return UnifiedUIElement(
            elementId = id,
            sourceSignals = setOf(SignalSource.ACCESSIBILITY),
            packageName = "com.google.android.youtube",
            className = "android.widget.EditText",
            viewIdResourceName = id,
            visibleText = "",
            contentDescription = "Search",
            hintText = "Search YouTube",
            inferredInputRole = InputFieldRole.SEARCH_FIELD,
            iconRole = IconRole.NONE,
            bounds = bounds,
            isClickable = true,
            isEditable = true,
            isScrollable = false,
            isEnabled = true,
            confidence = 0.95f
        )
    }

    fun createMockChatComposerElement(
        id: String = "com.whatsapp:id/entry",
        bounds: Rect = Rect(100, 1800, 850, 1950)
    ): UnifiedUIElement {
        return UnifiedUIElement(
            elementId = id,
            sourceSignals = setOf(SignalSource.ACCESSIBILITY),
            packageName = "com.whatsapp",
            className = "android.widget.EditText",
            viewIdResourceName = id,
            visibleText = "",
            contentDescription = "Message input box",
            hintText = "Type a message",
            inferredInputRole = InputFieldRole.CHAT_COMPOSER,
            iconRole = IconRole.NONE,
            bounds = bounds,
            isClickable = true,
            isEditable = true,
            isScrollable = false,
            isEnabled = true,
            confidence = 0.95f
        )
    }

    fun createMockSendIconButtonElement(
        id: String = "com.whatsapp:id/send_button",
        bounds: Rect = Rect(860, 1800, 980, 1950)
    ): UnifiedUIElement {
        return UnifiedUIElement(
            elementId = id,
            sourceSignals = setOf(SignalSource.ACCESSIBILITY),
            packageName = "com.whatsapp",
            className = "android.widget.ImageButton",
            viewIdResourceName = id,
            visibleText = "",
            contentDescription = "Send message",
            hintText = "",
            inferredInputRole = InputFieldRole.NOT_INPUT,
            iconRole = IconRole.SEND_ICON,
            bounds = bounds,
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isEnabled = true,
            confidence = 0.92f
        )
    }

    fun createMockSearchIconButtonElement(
        id: String = "com.google.android.youtube:id/search_button",
        bounds: Rect = Rect(850, 60, 950, 140)
    ): UnifiedUIElement {
        return UnifiedUIElement(
            elementId = id,
            sourceSignals = setOf(SignalSource.ACCESSIBILITY),
            packageName = "com.google.android.youtube",
            className = "android.widget.ImageView",
            viewIdResourceName = id,
            visibleText = "",
            contentDescription = "Search",
            hintText = "",
            inferredInputRole = InputFieldRole.NOT_INPUT,
            iconRole = IconRole.SEARCH_ICON,
            bounds = bounds,
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isEnabled = true,
            confidence = 0.90f
        )
    }

    // ==========================================
    // 4. Session & Task Execution Fixtures
    // ==========================================

    fun createMockMultiStepTaskPlan(): TaskPlan {
        val step1 = TaskStep(
            id = 1,
            actionType = UniversalActionType.OPEN_APP,
            targetAppOrUrl = "YouTube",
            param = "YouTube",
            ordinalIndex = 0
        )
        val step2 = TaskStep(
            id = 2,
            actionType = UniversalActionType.SEARCH,
            targetAppOrUrl = "YouTube",
            param = "Arijit Singh",
            ordinalIndex = 1
        )
        return TaskPlan(
            originalPrompt = "Open YouTube and search Arijit Singh",
            targetAppName = "YouTube",
            steps = listOf(step1, step2)
        )
    }
}
