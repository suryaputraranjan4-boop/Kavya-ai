package com.example.ai

import android.content.Context
import com.example.ai.providers.ProviderModelInfo
import com.example.utils.AppPreferences

/**
 * Requirement 3: OpenRouter Model Router.
 * Matches user task requirements to specific capable models,
 * respecting user selection, capability tags, and automatic fallbacks.
 */
object OpenRouterModelRouter {

    /**
     * Resolves the optimal OpenRouter model for a given task classification.
     */
    fun selectModelForTask(
        category: ClassifiedTaskCategory,
        availableModels: List<ProviderModelInfo>,
        context: Context
    ): String {
        val userPreference = AppPreferences.getOpenRouterModel(context).trim()

        // If user explicitly configured a specific model and it matches general availability, respect preference
        if (userPreference.isNotBlank() && userPreference != "openrouter/free") {
            val userModelInfo = availableModels.firstOrNull { it.id.equals(userPreference, ignoreCase = true) }
            if (userModelInfo != null) {
                // If the user's preferred model supports this specific capability, use it
                val isVisionNeeded = (category == ClassifiedTaskCategory.VISION || category == ClassifiedTaskCategory.OCR)
                if (!isVisionNeeded || userModelInfo.supportedTasks.contains("vision") || userModelInfo.id.contains("vl") || userModelInfo.id.contains("vision")) {
                    return userPreference
                }
            }
        }

        return when (category) {
            ClassifiedTaskCategory.REASONING -> {
                availableModels.firstOrNull { it.id == "deepseek/deepseek-r1:free" }?.id
                    ?: availableModels.firstOrNull { it.id == "meta-llama/llama-3.3-70b-instruct:free" }?.id
                    ?: "meta-llama/llama-3.3-70b-instruct:free"
            }
            ClassifiedTaskCategory.RESEARCH -> {
                availableModels.firstOrNull { it.id == "perplexity/sonar" }?.id
                    ?: availableModels.firstOrNull { it.supportedTasks.contains("research") }?.id
                    ?: "openrouter/free"
            }
            ClassifiedTaskCategory.CODING -> {
                availableModels.firstOrNull { it.id.contains("coder") || it.id.contains("qwen") }?.id
                    ?: availableModels.firstOrNull { it.id == "meta-llama/llama-3.3-70b-instruct:free" }?.id
                    ?: "meta-llama/llama-3.3-70b-instruct:free"
            }
            ClassifiedTaskCategory.VISION, ClassifiedTaskCategory.OCR -> {
                availableModels.firstOrNull { it.id.contains("flash") || it.id.contains("vl") || it.supportedTasks.contains("vision") }?.id
                    ?: "google/gemini-2.0-flash-exp:free"
            }
            ClassifiedTaskCategory.FAST_GENERAL,
            ClassifiedTaskCategory.PHONE_CONTROL,
            ClassifiedTaskCategory.PHONE_AUTOMATION -> {
                if (userPreference.isNotBlank()) userPreference else "openrouter/free"
            }
        }
    }

    /**
     * Resolves an ordered fallback sequence of candidate models.
     */
    fun getCandidateFallbackModels(
        primaryModel: String,
        availableModels: List<ProviderModelInfo>
    ): List<String> {
        val candidates = mutableListOf(primaryModel)
        val freeFallbacks = listOf(
            "openrouter/free",
            "meta-llama/llama-3.3-70b-instruct:free",
            "google/gemini-2.0-flash-exp:free",
            "mistralai/mistral-7b-instruct:free"
        )
        for (m in freeFallbacks) {
            if (!candidates.contains(m)) {
                candidates.add(m)
            }
        }
        return candidates
    }
}
