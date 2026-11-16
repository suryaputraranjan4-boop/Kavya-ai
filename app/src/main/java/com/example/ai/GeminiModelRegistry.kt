package com.example.ai

/**
 * Single source of truth for all Gemini model names across Kavya.
 *
 * Preserves the configured Gemini 3.5 tier constants required by app settings and audit tests
 * while providing `resolveApiModelFallbackChain(requestedModel)` with verified official
 * Google Generative Language API model IDs (`gemini-2.5-flash`, `gemini-2.5-flash-lite`,
 * `gemini-2.5-pro`, `gemini-2.0-flash`) when an endpoint returns HTTP 404 for a 3.5 model ID.
 */
object GeminiModelRegistry {
    const val DEFAULT_AGENT_MODEL = "gemini-3.5-flash-lite"
    const val PRO_REASONING_MODEL = "gemini-3.5-pro"

    // Vision / Screen Understanding / Multimodal Tasks
    const val VISION_ANALYZER_MODEL = DEFAULT_AGENT_MODEL

    // Live bidirectional streaming model
    const val LIVE_STREAM_MODEL = DEFAULT_AGENT_MODEL

    // Native Audio / Text-to-Speech (TTS)
    const val PRIMARY_TTS_MODEL = DEFAULT_AGENT_MODEL
    const val SECONDARY_TTS_MODEL = "gemini-3.5-flash"

    val AUDIO_MODEL_TIERS = listOf(
        PRIMARY_TTS_MODEL,
        SECONDARY_TTS_MODEL
    )

    // Ordered fallback tiers for chat & agent generation
    val CHAT_MODEL_TIERS = listOf(
        DEFAULT_AGENT_MODEL,
        SECONDARY_TTS_MODEL,
        PRO_REASONING_MODEL
    )

    // Verified official Google Generative Language v1beta fallback model IDs used ONLY
    // if the configured model ID returns HTTP 404 (Model Not Found) at runtime.
    val OFFICIAL_API_FALLBACK_MODELS = listOf(
        "gemini-2.5-flash-lite",
        "gemini-2.5-flash",
        "gemini-2.5-pro",
        "gemini-2.0-flash"
    )

    fun resolveApiModelFallbackChain(requestedModel: String): List<String> {
        val primary = requestedModel.trim().ifBlank { DEFAULT_AGENT_MODEL }
        val chain = mutableListOf(primary)
        when {
            primary.contains("pro", ignoreCase = true) -> {
                chain.add("gemini-2.5-pro")
                chain.add("gemini-2.5-flash")
            }
            primary.contains("lite", ignoreCase = true) -> {
                chain.add("gemini-2.5-flash-lite")
                chain.add("gemini-2.5-flash")
            }
            else -> {
                chain.add("gemini-2.5-flash")
                chain.add("gemini-2.5-flash-lite")
            }
        }
        OFFICIAL_API_FALLBACK_MODELS.forEach { if (it !in chain) chain.add(it) }
        return chain.distinct()
    }
}
