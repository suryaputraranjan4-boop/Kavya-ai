package com.example.ai

/**
 * Single Authoritative Gemini Model Registry for Kavya AI.
 *
 * Centralizes all Gemini model endpoints across reasoning, streaming live multimodal,
 * TTS speech generation, vision analysis, and text embeddings.
 * Verified against the latest Google Generative AI production API documentation.
 */
object GeminiModelRegistry {
    // Primary Fast Multimodal & Agent Reasoning Model
    const val DEFAULT_AGENT_MODEL = "gemini-2.5-flash"

    // High-complexity Planning & Code Architecture Model
    const val PRO_REASONING_MODEL = "gemini-2.5-pro"

    // Ultra-low Latency Bidi Live Streaming Model (WebSocket)
    const val LIVE_STREAM_MODEL = "models/gemini-2.5-flash"

    // Primary High-Fidelity Audio & Speech Synthesis Model (Gemini TTS)
    const val PRIMARY_TTS_MODEL = "gemini-2.5-flash-preview-tts"

    // High-volume / Low-latency Audio Model Fallback
    const val SECONDARY_TTS_MODEL = "gemini-2.5-flash"

    // Real-time Screen & UI Vision Understanding Model
    const val VISION_ANALYZER_MODEL = "gemini-2.5-flash"

    // Semantic Text Embedding Model for Knowledge & Memory Retrieval
    const val EMBEDDING_MODEL = "gemini-embedding-2-preview"

    // Ordered candidate tiers for fallback audio generation
    val AUDIO_MODEL_TIERS = listOf(
        PRIMARY_TTS_MODEL,
        SECONDARY_TTS_MODEL,
        "gemini-2.0-flash",
        "gemini-1.5-flash"
    )

    // Ordered candidate tiers for chat and reasoning
    val CHAT_MODEL_TIERS = listOf(
        DEFAULT_AGENT_MODEL,
        "gemini-2.0-flash",
        "gemini-1.5-flash",
        "gemini-flash-latest",
        PRO_REASONING_MODEL
    )
}

/**
 * Structured Voice Persona & Pronunciation Configuration for Kavya AI.
 */
object KavyaVoiceProfile {
    // Default Youthful, Feminine, Anime-Inspired Voice Persona
    const val DEFAULT_VOICE_NAME = "Kore"

    // Prebuilt candidate voices suitable for Kavya's personality
    val PREBUILT_VOICES = listOf(
        "Kore",         // Sweet, expressive, anime-inspired
        "Aoede",        // Breezy, cheerful
        "Leda",         // Youthful, energetic
        "Achird",       // Friendly, conversational
        "Vindemiatrix", // Gentle, warm
        "Zephyr"        // Bright, crisp
    )

    /**
     * Authoritative voice-direction prompt enforcing Indian Hindi accent + English term preservation.
     */
    fun buildVoiceDirectionPrompt(
        cleanText: String,
        emotionLabel: String = "Sweet Anime Voice",
        speedDesc: String = "at a natural, conversational pace",
        pitchDesc: String = "in a natural, comfortable pitch"
    ): String {
        return """
            Audio Profile:
            A youthful, feminine, warm, intelligent virtual assistant with an anime-inspired character feel. The voice should sound natural and mature enough for an everyday personal assistant, never childish or squeaky.

            Scene:
            A friendly personal AI assistant speaking directly to her user during normal phone interaction.

            Director's Notes:
            Speak naturally and clearly with a $emotionLabel feeling ($speedDesc, $pitchDesc).
            Use natural Indian Hindi pronunciation when speaking Hindi.
            Preserve English app names, technical terms, product names, abbreviations, and proper nouns (e.g. WhatsApp, YouTube, Spotify, Instagram, Chrome, Bluetooth, Wi-Fi, Settings, Google Maps, Gemini) with clear recognizable English pronunciation.
            For Hinglish, naturally switch between Hindi and English without changing meaning.
            Do not read out asterisks, markdown, brackets, or emojis.
            Do not translate, replace, invent, or omit words.

            Text:
            $cleanText
        """.trimIndent()
    }
}
