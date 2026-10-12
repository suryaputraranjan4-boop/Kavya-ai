package com.example.visual

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.ai.*
import com.example.security.SecureStorage
import com.example.utils.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * Gemini Vision Analyzer for the Kavya Visual Action Engine.
 * Responsibilities:
 * 1. Analyzes the current screen using Gemini multimodal vision models.
 * 2. Returns a strict structured action format (never arbitrary code or shell commands).
 * 3. Enforces normalized coordinates (0.0 to 1.0) so no fixed resolutions are assumed.
 * 4. Merges visual findings with UI hierarchy information.
 * 5. Provides graceful fallback when API key is missing or model is unavailable.
 */
class GeminiVisionAnalyzer(private val context: Context) {

    companion object {
        private const val TAG = "KavyaGeminiVision"
        private val VISION_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "gemini-1.5-flash"
        )
    }

    private fun getApiKey(): String {
        val secureKey = SecureStorage.getSecret(context, "gemini_api_key")
        if (secureKey.isNotBlank()) return secureKey
        return AppPreferences.getEffectiveApiKey(context)
    }

    /**
     * Requirement 4 & 8: Analyzes screen and returns a validated structured VisualAction.
     */
    suspend fun analyzeScreenForAction(
        base64Image: String,
        userGoal: String,
        currentApp: String,
        hierarchySummary: String
    ): Pair<ScreenUnderstanding, VisualAction?> = withContext(Dispatchers.IO) {
        val key = getApiKey()
        if (key.isBlank()) {
            Log.w(TAG, "Gemini API key is not configured for visual analysis.")
            return@withContext Pair(
                ScreenUnderstanding(
                    description = "Gemini API key is not configured in settings.",
                    targetFound = false,
                    confidence = 0f
                ),
                null
            )
        }

        val prompt = buildVisionPrompt(userGoal, currentApp, hierarchySummary)

        val request = GenerateContentRequest(
            contents = listOf(
                Content(
                    role = "user",
                    parts = listOf(
                        Part(text = prompt),
                        Part(inlineData = InlineData(mimeType = "image/jpeg", data = base64Image))
                    )
                )
            ),
            generationConfig = GenerationConfig(
                temperature = 0.2f,
                maxOutputTokens = 512
            )
        )

        var rawResponseText: String? = null
        for (model in VISION_MODELS) {
            try {
                val response = RetrofitClient.service.generateContent(
                    model = model,
                    apiKey = key,
                    request = request
                )
                val text = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (!text.isNullOrBlank()) {
                    rawResponseText = text.trim()
                    break
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gemini vision call failed on $model: ${e.message}")
            }
        }

        if (rawResponseText.isNullOrBlank()) {
            return@withContext Pair(
                ScreenUnderstanding(
                    description = "Failed to obtain visual analysis from Gemini vision models.",
                    targetFound = false,
                    confidence = 0f
                ),
                null
            )
        }

        parseStructuredActionResponse(rawResponseText, userGoal)
    }

    private fun buildVisionPrompt(userGoal: String, currentApp: String, hierarchySummary: String): String {
        return """
You are the visual screen perception engine for Kavya AI on Android.
Analyze the provided Android screenshot to accomplish the following user goal:
USER GOAL: "$userGoal"
FOREGROUND APP: "$currentApp"

ACCESSIBILITY HIERARCHY HINTS:
$hierarchySummary

CRITICAL INSTRUCTIONS:
1. Identify the exact UI element needed for the immediate next step.
2. Provide NORMALIZED coordinates (normalized_x, normalized_y) where (0.0, 0.0) is top-left and (1.0, 1.0) is bottom-right of the screen.
3. NEVER assume a fixed device resolution.
4. Only use allowed action types: tap, long_press, swipe, scroll, type, back, wait, select, open, close, stop.
5. You MUST output ONLY valid JSON matching this exact schema, without markdown fences or extra words:

{
  "screen_understanding": {
    "description": "Brief description of the current screen",
    "target_found": true
  },
  "action": {
    "type": "tap",
    "target": "Descriptive target name",
    "confidence": 0.95,
    "normalized_x": 0.82,
    "normalized_y": 0.89,
    "swipe_start_x": 0.5,
    "swipe_start_y": 0.7,
    "swipe_end_x": 0.5,
    "swipe_end_y": 0.3,
    "swipe_duration_ms": 400,
    "text_to_type": "",
    "scroll_direction": "DOWN",
    "wait_duration_ms": 1000,
    "expected_result": "What should happen after this action",
    "spoken_announcement": "Brief friendly milestone feedback in Hindi/English"
  }
}
""".trimIndent()
    }

    private fun parseStructuredActionResponse(jsonStr: String, userGoal: String): Pair<ScreenUnderstanding, VisualAction?> {
        try {
            val cleaned = jsonStr.substringAfter("{").substringBeforeLast("}")
            val root = JSONObject("{$cleaned}")

            val understandingObj = root.optJSONObject("screen_understanding")
            val desc = understandingObj?.optString("description", "Screen analyzed") ?: "Screen analyzed"
            val targetFound = understandingObj?.optBoolean("target_found", true) ?: true

            val actionObj = root.optJSONObject("action")
            if (actionObj == null) {
                return Pair(ScreenUnderstanding(desc, false, 0f), null)
            }

            val rawType = actionObj.optString("type", "tap").uppercase(Locale.ROOT)
            val actionType = try {
                VisualActionType.valueOf(rawType)
            } catch (_: Exception) {
                when {
                    rawType.contains("CLICK") || rawType.contains("PRESS") -> VisualActionType.TAP
                    rawType.contains("LONG") -> VisualActionType.LONG_PRESS
                    rawType.contains("SWIPE") -> VisualActionType.SWIPE
                    rawType.contains("SCROLL") -> VisualActionType.SCROLL
                    rawType.contains("TYPE") || rawType.contains("WRITE") -> VisualActionType.TYPE
                    rawType.contains("BACK") -> VisualActionType.BACK
                    rawType.contains("STOP") -> VisualActionType.STOP
                    else -> VisualActionType.TAP
                }
            }

            val target = actionObj.optString("target", userGoal)
            val confidence = actionObj.optDouble("confidence", 0.90).toFloat().coerceIn(0f, 1f)
            val normX = if (actionObj.has("normalized_x")) {
                actionObj.optDouble("normalized_x").toFloat().coerceIn(0f, 1f)
            } else {
                -1f
            }
            val normY = if (actionObj.has("normalized_y")) {
                actionObj.optDouble("normalized_y").toFloat().coerceIn(0f, 1f)
            } else {
                -1f
            }
            val swipeStartX = actionObj.optDouble("swipe_start_x", 0.5).toFloat().coerceIn(0f, 1f)
            val swipeStartY = actionObj.optDouble("swipe_start_y", 0.7).toFloat().coerceIn(0f, 1f)
            val swipeEndX = actionObj.optDouble("swipe_end_x", 0.5).toFloat().coerceIn(0f, 1f)
            val swipeEndY = actionObj.optDouble("swipe_end_y", 0.3).toFloat().coerceIn(0f, 1f)
            val swipeDuration = actionObj.optLong("swipe_duration_ms", 400L).coerceIn(100L, 2000L)
            val textToType = actionObj.optString("text_to_type", "")
            val scrollDir = actionObj.optString("scroll_direction", "DOWN")
            val waitDuration = actionObj.optLong("wait_duration_ms", 1000L)
            val expectedResult = actionObj.optString("expected_result", "Action completed")
            val spokenFeedback = actionObj.optString("spoken_announcement", "")

            val visualAction = VisualAction(
                type = actionType,
                target = target,
                confidence = confidence,
                normalizedX = normX,
                normalizedY = normY,
                swipeStartX = swipeStartX,
                swipeStartY = swipeStartY,
                swipeEndX = swipeEndX,
                swipeEndY = swipeEndY,
                swipeDurationMs = swipeDuration,
                textToType = textToType,
                scrollDirection = scrollDir,
                waitDurationMs = waitDuration,
                expectedResult = expectedResult,
                spokenAnnouncement = spokenFeedback
            )

            val understanding = ScreenUnderstanding(desc, targetFound, confidence)
            return Pair(understanding, visualAction)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse structured action JSON: ${e.message}\nRaw: $jsonStr", e)
            return Pair(
                ScreenUnderstanding(
                    description = "JSON parsing failed for visual response.",
                    targetFound = false,
                    confidence = 0f
                ),
                null
            )
        }
    }
}
