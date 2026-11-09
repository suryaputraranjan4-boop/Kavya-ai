package com.example.ai

import android.content.Context
import android.util.Log
import com.example.ai.providers.*
import com.example.utils.AppPreferences
import org.json.JSONObject

/**
 * Requirement 6: The central real TaskRouter.
 * Selects the appropriate execution system and returns a structured decision.
 * The router itself does not execute the action.
 */
enum class TaskType {
    PHONE_CONTROL,
    GENERAL_REASONING,
    RESEARCH,
    VISION,
    OCR,
    IMAGE_AI,
    TEXT_GENERATION,
    CODING,
    PHONE_AUTOMATION
}

data class TaskRoutingDecision(
    val taskType: TaskType,
    val provider: String, // "ANDROID_ACCESSIBILITY", "GEMINI", "OPENROUTER", "HUGGINGFACE"
    val selectedModel: String? = null,
    val requiresVision: Boolean = false,
    val requiresConfirmation: Boolean = false,
    val explanation: String = ""
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("taskType", taskType.name)
            put("provider", provider)
            put("requiresVision", requiresVision)
            put("requiresConfirmation", requiresConfirmation)
            if (selectedModel != null) put("selectedModel", selectedModel)
            put("explanation", explanation)
        }.toString(2)
    }
}

object TaskRouter {

    private const val TAG = "KavyaTaskRouter"

    /**
     * Requirement 6: Routes user prompt to the appropriate execution system.
     * Evaluates real configured providers and capability constraints.
     */
    fun routeTask(
        prompt: String,
        hasImage: Boolean = false,
        context: Context
    ): TaskRoutingDecision {
        val classification = TaskClassifier.classify(prompt, hasImage)

        val isGeminiConfigured = AppPreferences.getEffectiveApiKey(context).isNotBlank()
        val isOpenRouterConfigured = AppPreferences.getOpenRouterApiKey(context).isNotBlank()
        val isHfConfigured = AppPreferences.getHuggingFaceApiKey(context).isNotBlank()
        val localModelFile = com.example.ai.offline.GemmaModelManager.getModelFile(context)
        val isGemmaConfigured = localModelFile?.let {
            it.isFile && com.example.ai.offline.GemmaModelManager.inspectModel(it).isSupported
        } == true
        val preferredProvider = AppPreferences.getAiProvider(context)
        val isExplicitOffline = preferredProvider == "GEMMA_OFFLINE" || AppPreferences.isOfflineFallbackEnabled(context)

        val isPhoneAction = classification.category == ClassifiedTaskCategory.PHONE_CONTROL ||
            classification.category == ClassifiedTaskCategory.PHONE_AUTOMATION

        // Device actions remain on Android Accessibility; do not route them to a cloud model.
        if (isPhoneAction) {
            val requiresVision = classification.requiresVision ||
                prompt.contains("game", ignoreCase = true) ||
                prompt.contains("free fire", ignoreCase = true)
            val requiresConfirm = prompt.contains("delete", ignoreCase = true) ||
                prompt.contains("format", ignoreCase = true) ||
                prompt.contains("pay", ignoreCase = true)
            return TaskRoutingDecision(
                taskType = if (classification.category == ClassifiedTaskCategory.PHONE_AUTOMATION) TaskType.PHONE_AUTOMATION else TaskType.PHONE_CONTROL,
                provider = "ANDROID_ACCESSIBILITY",
                requiresVision = requiresVision,
                requiresConfirmation = requiresConfirm,
                explanation = "Routing phone interaction to Android Accessibility Engine."
            )
        }

        val requiresVisualInput = hasImage || classification.requiresVision ||
            classification.category in setOf(
                ClassifiedTaskCategory.OCR,
                ClassifiedTaskCategory.VISION
            )

        // Explicit offline mode is a hard network boundary. Never route a request to
        // Gemini/OpenRouter/Hugging Face when a local model is missing or lacks vision.
        if (isExplicitOffline) {
            if (requiresVisualInput) {
                return TaskRoutingDecision(
                    taskType = when (classification.category) {
                        ClassifiedTaskCategory.OCR -> TaskType.OCR
                        else -> TaskType.VISION
                    },
                    provider = "NONE",
                    requiresVision = true,
                    explanation = "Offline mode is enabled, but the installed local text runtime cannot process image/OCR/vision input. No online provider was called."
                )
            }
            if (!isGemmaConfigured) {
                return TaskRoutingDecision(
                    taskType = TaskType.GENERAL_REASONING,
                    provider = "NONE",
                    explanation = "Offline mode is enabled, but no supported local model is installed. Import a supported GGUF, LiteRT-LM, or MediaPipe Task model."
                )
            }
            return TaskRoutingDecision(
                taskType = when (classification.category) {
                    ClassifiedTaskCategory.CODING -> TaskType.CODING
                    ClassifiedTaskCategory.RESEARCH -> TaskType.RESEARCH
                    else -> TaskType.GENERAL_REASONING
                },
                provider = "GEMMA_OFFLINE",
                selectedModel = localModelFile?.name,
                explanation = "Routing request to the selected local on-device model. No network fallback is allowed."
            )
        }

        // When no online provider is configured, use a supported local model for
        // text-only tasks. Vision tasks remain unsupported by this text runtime.

        // 2. OCR Tasks -> Hugging Face OCR or Gemini Vision
        if (classification.category == ClassifiedTaskCategory.OCR) {
            if (isHfConfigured) {
                return TaskRoutingDecision(
                    taskType = TaskType.OCR,
                    provider = "HUGGINGFACE",
                    selectedModel = HuggingFaceProvider.MODEL_OCR,
                    requiresVision = true,
                    explanation = "Routing document OCR to Hugging Face TrOCR."
                )
            } else if (isGeminiConfigured) {
                return TaskRoutingDecision(
                    taskType = TaskType.OCR,
                    provider = "GEMINI",
                    selectedModel = "gemini-2.5-flash",
                    requiresVision = true,
                    explanation = "Routing document OCR to Gemini multimodal engine."
                )
            }
        }

        // 3. Vision & Image Analysis -> Gemini / Hugging Face / OpenRouter Vision
        if (classification.category == ClassifiedTaskCategory.VISION || classification.requiresVision) {
            if (isGeminiConfigured) {
                return TaskRoutingDecision(
                    taskType = TaskType.VISION,
                    provider = "GEMINI",
                    selectedModel = "gemini-2.5-flash",
                    requiresVision = true,
                    explanation = "Routing visual scene analysis to Gemini Vision."
                )
            } else if (isHfConfigured) {
                return TaskRoutingDecision(
                    taskType = TaskType.VISION,
                    provider = "HUGGINGFACE",
                    selectedModel = HuggingFaceProvider.MODEL_IMAGE_ANALYSIS,
                    requiresVision = true,
                    explanation = "Routing image analysis to Hugging Face BLIP."
                )
            } else if (isOpenRouterConfigured) {
                return TaskRoutingDecision(
                    taskType = TaskType.VISION,
                    provider = "OPENROUTER",
                    selectedModel = "google/gemini-2.0-flash-exp:free",
                    requiresVision = true,
                    explanation = "Routing visual analysis to OpenRouter multimodal model."
                )
            }
        }

        // 4. Research -> OpenRouter Sonar or Gemini Search
        if (classification.category == ClassifiedTaskCategory.RESEARCH) {
            if (isOpenRouterConfigured) {
                return TaskRoutingDecision(
                    taskType = TaskType.RESEARCH,
                    provider = "OPENROUTER",
                    selectedModel = "perplexity/sonar",
                    explanation = "Routing multi-source deep research to OpenRouter Perplexity Sonar."
                )
            } else if (isGeminiConfigured) {
                return TaskRoutingDecision(
                    taskType = TaskType.RESEARCH,
                    provider = "GEMINI",
                    selectedModel = "gemini-2.5-flash",
                    explanation = "Routing research investigation to Gemini."
                )
            }
        }

        // 5. Coding -> Code-specialized model
        if (classification.category == ClassifiedTaskCategory.CODING) {
            if (isOpenRouterConfigured) {
                val selected = OpenRouterModelRouter.selectModelForTask(
                    ClassifiedTaskCategory.CODING,
                    OpenRouterProvider.DEFAULT_VERIFIED_MODELS,
                    context
                )
                return TaskRoutingDecision(
                    taskType = TaskType.CODING,
                    provider = "OPENROUTER",
                    selectedModel = selected,
                    explanation = "Routing programming task to code-capable OpenRouter model ($selected)."
                )
            } else if (isGeminiConfigured) {
                return TaskRoutingDecision(
                    taskType = TaskType.CODING,
                    provider = "GEMINI",
                    selectedModel = "gemini-2.5-flash",
                    explanation = "Routing coding task to Gemini reasoning engine."
                )
            }
        }

        // 6. General Reasoning & Fast General
        if (isGeminiConfigured) {
            return TaskRoutingDecision(
                taskType = TaskType.GENERAL_REASONING,
                provider = "GEMINI",
                selectedModel = "gemini-2.5-flash",
                explanation = "Routing reasoning prompt to Gemini."
            )
        } else if (isOpenRouterConfigured) {
            val selected = OpenRouterModelRouter.selectModelForTask(
                ClassifiedTaskCategory.REASONING,
                OpenRouterProvider.DEFAULT_VERIFIED_MODELS,
                context
            )
            return TaskRoutingDecision(
                taskType = TaskType.GENERAL_REASONING,
                provider = "OPENROUTER",
                selectedModel = selected,
                explanation = "Routing reasoning prompt to OpenRouter ($selected)."
            )
        } else if (isHfConfigured) {
            return TaskRoutingDecision(
                taskType = TaskType.TEXT_GENERATION,
                provider = "HUGGINGFACE",
                selectedModel = HuggingFaceProvider.MODEL_TEXT_GEN,
                explanation = "Routing prompt to Hugging Face text generation."
            )
        } else if (
            isGemmaConfigured &&
            !hasImage &&
            !classification.requiresVision &&
            classification.category !in setOf(
                ClassifiedTaskCategory.OCR,
                ClassifiedTaskCategory.VISION
            )
        ) {
            return TaskRoutingDecision(
                taskType = TaskType.GENERAL_REASONING,
                provider = "GEMMA_OFFLINE",
                selectedModel = localModelFile?.name,
                explanation = "Routing text-only task to the selected on-device local model."
            )
        }

        // Unconfigured fallback
        return TaskRoutingDecision(
            taskType = TaskType.GENERAL_REASONING,
            provider = "NONE",
            explanation = "No compatible AI provider is currently configured."
        )
    }
}
