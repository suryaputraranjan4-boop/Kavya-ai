package com.example.ai.providers

/**
 * Internal Model Registry.
 * Holds verified supported models, tasks, capabilities, and availability.
 */
object ModelRegistry {

    private val models = listOf(
        // Gemini Models (Main Brain)
        RegisteredModel(
            providerId = "gemini",
            modelId = com.example.ai.GeminiModelRegistry.DEFAULT_AGENT_MODEL,
            displayName = "Gemini 2.5 Flash",
            task = "Primary Multimodal Orchestration & Planning",
            capabilities = listOf("Chat", "Reasoning", "Planning", "Tools", "Fast", "Vision"),
            isFree = true
        ),
        RegisteredModel(
            providerId = "gemini",
            modelId = com.example.ai.GeminiModelRegistry.PRO_REASONING_MODEL,
            displayName = "Gemini 2.5 Pro",
            task = "Deep Reasoning & Complex Architecture Planning",
            capabilities = listOf("Chat", "Deep Reasoning", "Vision", "Tools"),
            isFree = false
        ),
        RegisteredModel(
            providerId = "gemini",
            modelId = com.example.ai.GeminiModelRegistry.PRIMARY_TTS_MODEL,
            displayName = "Gemini Voice Synthesis (TTS)",
            task = "Direct Speech & Voice Output",
            capabilities = listOf("TTS Audio", "Realtime Voice"),
            isFree = true
        ),

        // Gemma 4 E4B (Offline Model)
        RegisteredModel(
            providerId = "gemma_offline",
            modelId = "gemma-4-e4b",
            displayName = "Gemma 4 E4B (Offline)",
            task = "On-Device Offline Reasoning & Task Planning",
            capabilities = listOf("Offline", "LiteRT-LM", "On-Device", "Privacy"),
            isFree = true
        ),

        // OpenRouter Models (Secondary AI Partner)
        RegisteredModel(
            providerId = "openrouter",
            modelId = "openrouter/free",
            displayName = "OpenRouter Free Auto",
            task = "Secondary AI Reasoning & Perspectives",
            capabilities = listOf("AI Assistance", "Partner Synthesis", "Reasoning"),
            isFree = true
        ),
        RegisteredModel(
            providerId = "openrouter",
            modelId = "google/gemini-2.0-flash-exp:free",
            displayName = "Gemini 2.0 Flash (Free)",
            task = "Alternative Model Evaluation",
            capabilities = listOf("Reasoning", "Speed"),
            isFree = true
        ),
        RegisteredModel(
            providerId = "openrouter",
            modelId = "meta-llama/llama-3.3-70b-instruct:free",
            displayName = "Llama 3.3 70B Instruct (Free)",
            task = "Deep Open-Weights Reasoning",
            capabilities = listOf("Deep Reasoning", "Code", "Synthesis"),
            isFree = true
        ),
        RegisteredModel(
            providerId = "openrouter",
            modelId = "mistralai/mistral-7b-instruct:free",
            displayName = "Mistral 7B Instruct (Free)",
            task = "Fast Secondary Verification",
            capabilities = listOf("Concise Reasoning", "Validation"),
            isFree = true
        ),

        // Hugging Face Models (Specialized AI Resource)
        RegisteredModel(
            providerId = "huggingface",
            modelId = "distilbert-base-uncased",
            displayName = "DistilBERT Uncased",
            task = "Text Inference & Classification",
            capabilities = listOf("Inference", "Classification", "Masking"),
            isFree = true
        ),
        RegisteredModel(
            providerId = "huggingface",
            modelId = "cardiffnlp/twitter-roberta-base-sentiment-latest",
            displayName = "RoBERTa Sentiment",
            task = "Sentiment & Emotion Analysis",
            capabilities = listOf("Sentiment Analysis", "Classification"),
            isFree = true
        ),
        RegisteredModel(
            providerId = "huggingface",
            modelId = "deepset/roberta-base-squad2",
            displayName = "RoBERTa SQuAD2",
            task = "Extractive Question Answering",
            capabilities = listOf("QA Extraction", "Document Analysis"),
            isFree = true
        ),
        RegisteredModel(
            providerId = "huggingface",
            modelId = "google/vit-base-patch16-224",
            displayName = "Vision Transformer (ViT)",
            task = "Visual Feature & Object Classification",
            capabilities = listOf("Vision", "Object Recognition"),
            isFree = true
        ),

        // Public APIs (External Data Tools)
        RegisteredModel(
            providerId = "public_apis",
            modelId = "open-meteo",
            displayName = "Open-Meteo Weather API",
            task = "Live Weather & Forecasts",
            capabilities = listOf("Weather", "Temperature", "Wind", "Precipitation"),
            isFree = true
        ),
        RegisteredModel(
            providerId = "public_apis",
            modelId = "frankfurter",
            displayName = "Frankfurter Currency API",
            task = "Real-Time Exchange Rates",
            capabilities = listOf("FX Rates", "Currency Conversion"),
            isFree = true
        ),
        RegisteredModel(
            providerId = "public_apis",
            modelId = "rest-countries",
            displayName = "REST Countries API",
            task = "Geographic & Demographic Data",
            capabilities = listOf("Country Info", "Capitals", "Currencies"),
            isFree = true
        )
    )

    fun getAllModels(): List<RegisteredModel> = models

    fun getModelsForProvider(providerId: String): List<RegisteredModel> {
        return models.filter { it.providerId.equals(providerId, ignoreCase = true) }
    }

    fun findModel(modelId: String): RegisteredModel? {
        return models.firstOrNull { it.modelId.equals(modelId, ignoreCase = true) }
    }

    fun getActiveModelCount(): Int = models.size
}
