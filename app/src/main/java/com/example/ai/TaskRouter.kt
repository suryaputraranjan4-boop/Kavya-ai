package com.example.ai

import android.content.Context
import com.example.ai.offline.GemmaModelManager
import com.example.ai.offline.OfflineStorageProfile
import com.example.ai.providers.HuggingFaceProvider
import com.example.ai.providers.OpenRouterProvider
import com.example.utils.AppPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

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

/**
 * Canonical 7 Multi-Model GUI Intelligence Task Kinds (Section 4.1).
 */
enum class GuiIntelligenceTaskKind {
    GUI_GROUNDING,
    SCREEN_OCR_AND_DENSE_TEXT,
    ICON_AND_CONTROL_DETECTION,
    NEXT_STEP_ACTION_PLANNING,
    PRE_ACTION_VERIFICATION,
    POST_ACTION_TRANSITION_CHECK,
    CONVERSATIONAL_OR_GENERAL_QA
}

/**
 * Stepwise signal escalation order (Section 4.2):
 * 1. Fresh screen observation (Accessibility + Screenshot)
 * 2. Accessibility & OCR text/control matching
 * 3. Optional Auxiliary Vision (Florence-2 ONNX Q4F16 when EXPANDED profile is active and unlabeled icons/regions exist)
 * 4. Local GUI Grounding & Planning (SmolVLM2-2.2B-Instruct-Agentic-GUI Q4_K_M + mmproj)
 * 5. Online Gemini Escalation (only when online is permitted and online provider is configured)
 */
enum class SignalEscalationStep {
    FRESH_SCREEN_OBSERVATION,
    ACCESSIBILITY_AND_OCR,
    AUX_VISION_FLORENCE2,
    LOCAL_GUI_GROUNDING_SMOLVLM2,
    ONLINE_GEMINI_ESCALATION
}

/**
 * Structured machine-readable status codes for routing & execution readiness (Section 4.4).
 */
enum class StructuredRoutingStatus {
    READY,
    MODEL_NOT_INSTALLED,
    STORAGE_INSUFFICIENT,
    MODEL_CORRUPT,
    MMPROJ_MISSING,
    OOM_RISK,
    THERMAL_THROTTLED,
    TARGET_NOT_FOUND,
    LOW_CONFIDENCE,
    NETWORK_UNAVAILABLE
}

data class TaskRoutingDecision(
    val taskType: TaskType,
    val provider: String, // "GEMINI", "OPENROUTER", "HUGGINGFACE", "SMOLVLM2_OFFLINE", "GEMMA_OFFLINE", "NONE"
    val selectedModel: String? = null,
    val requiresVision: Boolean = false,
    val requiresConfirmation: Boolean = false,
    val explanation: String = "",
    val guiTaskKind: GuiIntelligenceTaskKind = GuiIntelligenceTaskKind.CONVERSATIONAL_OR_GENERAL_QA,
    val status: StructuredRoutingStatus = StructuredRoutingStatus.READY,
    val escalationPlan: List<SignalEscalationStep> = emptyList(),
    val strictOfflineZeroNetwork: Boolean = false,
    val auxiliaryModelId: String? = null
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("taskType", taskType.name)
            put("guiTaskKind", guiTaskKind.name)
            put("status", status.name)
            put("provider", provider)
            put("requiresVision", requiresVision)
            put("requiresConfirmation", requiresConfirmation)
            put("strictOfflineZeroNetwork", strictOfflineZeroNetwork)
            if (selectedModel != null) put("selectedModel", selectedModel)
            if (auxiliaryModelId != null) put("auxiliaryModelId", auxiliaryModelId)
            put("escalationPlan", JSONArray(escalationPlan.map { it.name }))
            put("explanation", explanation)
        }.toString(2)
    }
}

object TaskRouter {

    private const val TAG = "KavyaTaskRouter"

    fun inspectLocalMultimodalHealth(context: Context): Pair<StructuredRoutingStatus, Boolean> {
        val baseDir = GemmaModelManager.getModelDirectory(context)
        val smolReport = GemmaModelManager.verifySmolVlm2Package(baseDir, verifyChecksums = false)
        val smolStatus = when (smolReport.statusCode) {
            "READY" -> StructuredRoutingStatus.READY
            "MMPROJ_MISSING" -> StructuredRoutingStatus.MMPROJ_MISSING
            "MODEL_CORRUPT" -> StructuredRoutingStatus.MODEL_CORRUPT
            else -> StructuredRoutingStatus.MODEL_NOT_INSTALLED
        }
        val florenceHealthy = GemmaModelManager.verifyFlorence2Package(baseDir, verifyChecksums = false).isInstalledAndValid
        return Pair(smolStatus, florenceHealthy)
    }

    fun classifyGuiTaskKind(
        prompt: String,
        hasImage: Boolean = false,
        explicitKind: GuiIntelligenceTaskKind? = null
    ): GuiIntelligenceTaskKind {
        if (explicitKind != null) return explicitKind
        val lower = prompt.lowercase(Locale.ROOT).trim()
        return when {
            lower.startsWith("verify transition") || lower.contains("post-action") || lower.contains("did screen change") ->
                GuiIntelligenceTaskKind.POST_ACTION_TRANSITION_CHECK
            lower.startsWith("verify target") || lower.contains("pre-action") || lower.contains("is it safe to tap") ->
                GuiIntelligenceTaskKind.PRE_ACTION_VERIFICATION
            lower.contains("find icon") || lower.contains("unlabeled icon") || lower.contains("detect icon") ->
                GuiIntelligenceTaskKind.ICON_AND_CONTROL_DETECTION
            lower.contains("ocr") || lower.contains("extract text") || lower.contains("read all text") ->
                GuiIntelligenceTaskKind.SCREEN_OCR_AND_DENSE_TEXT
            lower.contains("ground ") || lower.contains("locate button") || lower.contains("where is ") || lower.contains("coordinates of") ->
                GuiIntelligenceTaskKind.GUI_GROUNDING
            else -> {
                val c = TaskClassifier.classify(prompt, hasImage)
                if (c.category == ClassifiedTaskCategory.PHONE_CONTROL ||
                    c.category == ClassifiedTaskCategory.PHONE_AUTOMATION
                ) {
                    GuiIntelligenceTaskKind.NEXT_STEP_ACTION_PLANNING
                } else if (c.category == ClassifiedTaskCategory.OCR) {
                    GuiIntelligenceTaskKind.SCREEN_OCR_AND_DENSE_TEXT
                } else if (c.category == ClassifiedTaskCategory.VISION) {
                    GuiIntelligenceTaskKind.GUI_GROUNDING
                } else {
                    GuiIntelligenceTaskKind.CONVERSATIONAL_OR_GENERAL_QA
                }
            }
        }
    }

    /**
     * Multi-Model GUI Intelligence Router (Online + Offline automatic backend selection).
     * Never requires the user to choose individual models manually.
     */
    fun routeGuiTask(
        prompt: String,
        context: Context,
        explicitTaskKind: GuiIntelligenceTaskKind? = null,
        hasImage: Boolean = true,
        isNetworkAvailable: Boolean = true,
        forceOfflineMode: Boolean? = null,
        modelBaseDir: File = GemmaModelManager.getModelDirectory(context),
        availableRamMb: Long = 2400L,
        thermalStatus: Int = 0,
        accessibilityResolvedHighConfidence: Boolean = false
    ): TaskRoutingDecision {
        val guiKind = classifyGuiTaskKind(prompt, hasImage, explicitTaskKind)
        val preferredProvider = AppPreferences.getAiProvider(context)
        val isOfflineMode = forceOfflineMode
            ?: (preferredProvider == "GEMMA_OFFLINE" ||
                AppPreferences.isOfflineFallbackEnabled(context) ||
                !isNetworkAvailable)
        val strictZeroNetwork = isOfflineMode || !isNetworkAvailable

        val isGeminiConfigured = AppPreferences.getEffectiveApiKey(context).isNotBlank()
        val isOpenRouterConfigured = AppPreferences.getOpenRouterApiKey(context).isNotBlank()
        val isHfConfigured = AppPreferences.getHuggingFaceApiKey(context).isNotBlank()
        val hasOnlineCredentials = isGeminiConfigured || isOpenRouterConfigured || isHfConfigured

        val requiresConfirm = prompt.contains("delete", ignoreCase = true) ||
            prompt.contains("uninstall", ignoreCase = true) ||
            prompt.contains("pay", ignoreCase = true) ||
            prompt.contains("transfer", ignoreCase = true) ||
            prompt.contains("send", ignoreCase = true)

        val smolReport = GemmaModelManager.verifySmolVlm2Package(modelBaseDir, verifyChecksums = false)
        val florenceReport = GemmaModelManager.verifyFlorence2Package(modelBaseDir, verifyChecksums = false)

        // Build stepwise escalation sequence
        val escalation = mutableListOf(
            SignalEscalationStep.FRESH_SCREEN_OBSERVATION,
            SignalEscalationStep.ACCESSIBILITY_AND_OCR
        )
        if (florenceReport.isInstalledAndValid) {
            escalation.add(SignalEscalationStep.AUX_VISION_FLORENCE2)
        }
        escalation.add(SignalEscalationStep.LOCAL_GUI_GROUNDING_SMOLVLM2)
        if (!strictZeroNetwork && hasOnlineCredentials) {
            escalation.add(SignalEscalationStep.ONLINE_GEMINI_ESCALATION)
        }

        // Check thermal and RAM constraints before local VLM execution
        if (strictZeroNetwork || !hasOnlineCredentials) {
            if (thermalStatus >= 3) {
                return TaskRoutingDecision(
                    taskType = TaskType.PHONE_AUTOMATION,
                    provider = "NONE",
                    requiresVision = true,
                    requiresConfirmation = requiresConfirm,
                    explanation = "Device is thermally throttled; local VLM inference is paused for safety.",
                    guiTaskKind = guiKind,
                    status = StructuredRoutingStatus.THERMAL_THROTTLED,
                    escalationPlan = escalation,
                    strictOfflineZeroNetwork = strictZeroNetwork
                )
            }
            if (availableRamMb < 1100L) {
                return TaskRoutingDecision(
                    taskType = TaskType.PHONE_AUTOMATION,
                    provider = "NONE",
                    requiresVision = true,
                    requiresConfirmation = requiresConfirm,
                    explanation = "Available RAM ($availableRamMb MB) is too low to load SmolVLM2 safely.",
                    guiTaskKind = guiKind,
                    status = StructuredRoutingStatus.OOM_RISK,
                    escalationPlan = escalation,
                    strictOfflineZeroNetwork = strictZeroNetwork
                )
            }

            if (!smolReport.isInstalledAndValid) {
                val mappedStatus = when (smolReport.statusCode) {
                    "MMPROJ_MISSING" -> StructuredRoutingStatus.MMPROJ_MISSING
                    "MODEL_CORRUPT" -> StructuredRoutingStatus.MODEL_CORRUPT
                    else -> StructuredRoutingStatus.MODEL_NOT_INSTALLED
                }
                return TaskRoutingDecision(
                    taskType = TaskType.PHONE_AUTOMATION,
                    provider = "NONE",
                    requiresVision = true,
                    requiresConfirmation = requiresConfirm,
                    explanation = smolReport.message,
                    guiTaskKind = guiKind,
                    status = mappedStatus,
                    escalationPlan = escalation,
                    strictOfflineZeroNetwork = strictZeroNetwork
                )
            }

            val auxModel = if (florenceReport.isInstalledAndValid) {
                GemmaModelManager.FLORENCE2_MANIFEST.modelId
            } else null

            return TaskRoutingDecision(
                taskType = TaskType.PHONE_AUTOMATION,
                provider = "SMOLVLM2_OFFLINE",
                selectedModel = GemmaModelManager.SMOLVLM2_MANIFEST.modelId,
                requiresVision = true,
                requiresConfirmation = requiresConfirm,
                explanation = "Routing offline GUI task ($guiKind) to local SmolVLM2-2.2B-Instruct-Agentic-GUI with zero network calls.",
                guiTaskKind = guiKind,
                status = StructuredRoutingStatus.READY,
                escalationPlan = escalation,
                strictOfflineZeroNetwork = true,
                auxiliaryModelId = auxModel
            )
        }

        // Online mode with valid credentials
        val selectedOnlineProvider = when {
            preferredProvider.equals("OPENROUTER", ignoreCase = true) && isOpenRouterConfigured -> "OPENROUTER"
            preferredProvider.equals("HUGGINGFACE", ignoreCase = true) && isHfConfigured -> "HUGGINGFACE"
            isGeminiConfigured -> "GEMINI"
            isOpenRouterConfigured -> "OPENROUTER"
            isHfConfigured -> "HUGGINGFACE"
            else -> "NONE"
        }
        val selectedModel = when (selectedOnlineProvider) {
            "GEMINI" -> AppPreferences.getGeminiModel(context).ifBlank { GeminiModelRegistry.DEFAULT_AGENT_MODEL }
            "OPENROUTER" -> AppPreferences.getOpenRouterModel(context)
            "HUGGINGFACE" -> AppPreferences.getHuggingFaceModel(context)
            else -> null
        }

        return TaskRoutingDecision(
            taskType = TaskType.PHONE_AUTOMATION,
            provider = selectedOnlineProvider,
            selectedModel = selectedModel,
            requiresVision = !accessibilityResolvedHighConfidence,
            requiresConfirmation = requiresConfirm,
            explanation = "Routing GUI task ($guiKind) through stepwise multi-signal escalation with online provider ($selectedOnlineProvider).",
            guiTaskKind = guiKind,
            status = StructuredRoutingStatus.READY,
            escalationPlan = escalation,
            strictOfflineZeroNetwork = false,
            auxiliaryModelId = if (florenceReport?.isInstalledAndValid == true) GemmaModelManager.FLORENCE2_MANIFEST.modelId else null
        )
    }

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
        val guiKind = classifyGuiTaskKind(prompt, hasImage)

        val isGeminiConfigured = AppPreferences.getEffectiveApiKey(context).isNotBlank()
        val isOpenRouterConfigured = AppPreferences.getOpenRouterApiKey(context).isNotBlank()
        val isHfConfigured = AppPreferences.getHuggingFaceApiKey(context).isNotBlank()
        val localModelFile = GemmaModelManager.getModelFile(context)
        val isGemmaConfigured = localModelFile?.let {
            it.isFile && GemmaModelManager.inspectModel(it).isSupported
        } == true
        val smolVlmInstalled = GemmaModelManager.verifySmolVlm2Package(
            GemmaModelManager.getModelDirectory(context),
            verifyChecksums = false
        ).isInstalledAndValid

        val preferredProvider = AppPreferences.getAiProvider(context)
        val isExplicitOffline = preferredProvider == "GEMMA_OFFLINE" || AppPreferences.isOfflineFallbackEnabled(context)

        val isPhoneAction = classification.category == ClassifiedTaskCategory.PHONE_CONTROL ||
            classification.category == ClassifiedTaskCategory.PHONE_AUTOMATION

        if (isPhoneAction) {
            val requiresVision = classification.requiresVision ||
                prompt.contains("game", ignoreCase = true) ||
                prompt.contains("free fire", ignoreCase = true)
            val requiresConfirm = prompt.contains("delete", ignoreCase = true) ||
                prompt.contains("format", ignoreCase = true) ||
                prompt.contains("pay", ignoreCase = true)

            if (isExplicitOffline && smolVlmInstalled) {
                return TaskRoutingDecision(
                    taskType = if (classification.category == ClassifiedTaskCategory.PHONE_AUTOMATION) TaskType.PHONE_AUTOMATION else TaskType.PHONE_CONTROL,
                    provider = "SMOLVLM2_OFFLINE",
                    selectedModel = GemmaModelManager.SMOLVLM2_MANIFEST.modelId,
                    requiresVision = true,
                    requiresConfirmation = requiresConfirm,
                    explanation = "Routing offline GUI automation to verified local SmolVLM2-2.2B-Instruct-Agentic-GUI with zero network calls.",
                    guiTaskKind = guiKind,
                    status = StructuredRoutingStatus.READY,
                    escalationPlan = listOf(
                        SignalEscalationStep.FRESH_SCREEN_OBSERVATION,
                        SignalEscalationStep.ACCESSIBILITY_AND_OCR,
                        SignalEscalationStep.LOCAL_GUI_GROUNDING_SMOLVLM2
                    ),
                    strictOfflineZeroNetwork = true
                )
            }

            val normalizedPreferred = preferredProvider.trim().uppercase(Locale.ROOT)
            val selectedOnlineProvider = when {
                isExplicitOffline -> "NONE"
                normalizedPreferred == "OPENROUTER" && isOpenRouterConfigured -> "OPENROUTER"
                normalizedPreferred == "HUGGINGFACE" && isHfConfigured -> "HUGGINGFACE"
                (normalizedPreferred == "CUSTOM" || normalizedPreferred == "CUSTOM_API") &&
                    AppPreferences.getCustomApis(context).any { it.isEnabled } -> "CUSTOM"
                normalizedPreferred == "GEMINI" && isGeminiConfigured -> "GEMINI"
                else -> "NONE"
            }
            val selectedModel = when (selectedOnlineProvider) {
                "GEMINI" -> AppPreferences.getGeminiModel(context)
                "OPENROUTER" -> AppPreferences.getOpenRouterModel(context)
                "HUGGINGFACE" -> AppPreferences.getHuggingFaceModel(context)
                "CUSTOM" -> AppPreferences.getCustomApis(context).firstOrNull { it.isEnabled }?.model
                else -> null
            }
            return TaskRoutingDecision(
                taskType = if (classification.category == ClassifiedTaskCategory.PHONE_AUTOMATION) TaskType.PHONE_AUTOMATION else TaskType.PHONE_CONTROL,
                provider = selectedOnlineProvider,
                selectedModel = selectedModel,
                requiresVision = requiresVision,
                requiresConfirmation = requiresConfirm,
                explanation = if (selectedOnlineProvider == "NONE") {
                    "Device automation requires a valid online AI provider and key or an installed SmolVLM2 offline GUI bundle."
                } else {
                    "Routing device automation planning exclusively to selected AI provider ($selectedOnlineProvider)."
                },
                guiTaskKind = guiKind,
                status = if (selectedOnlineProvider == "NONE") StructuredRoutingStatus.MODEL_NOT_INSTALLED else StructuredRoutingStatus.READY,
                escalationPlan = listOf(
                    SignalEscalationStep.FRESH_SCREEN_OBSERVATION,
                    SignalEscalationStep.ACCESSIBILITY_AND_OCR,
                    SignalEscalationStep.ONLINE_GEMINI_ESCALATION
                ),
                strictOfflineZeroNetwork = isExplicitOffline
            )
        }

        val requiresVisualInput = hasImage || classification.requiresVision ||
            classification.category in setOf(
                ClassifiedTaskCategory.OCR,
                ClassifiedTaskCategory.VISION
            )

        if (isExplicitOffline) {
            if (requiresVisualInput) {
                if (smolVlmInstalled) {
                    return TaskRoutingDecision(
                        taskType = when (classification.category) {
                            ClassifiedTaskCategory.OCR -> TaskType.OCR
                            else -> TaskType.VISION
                        },
                        provider = "SMOLVLM2_OFFLINE",
                        selectedModel = GemmaModelManager.SMOLVLM2_MANIFEST.modelId,
                        requiresVision = true,
                        explanation = "Routing offline visual task to local SmolVLM2-2.2B-Instruct-Agentic-GUI. No online provider was called.",
                        guiTaskKind = guiKind,
                        status = StructuredRoutingStatus.READY,
                        strictOfflineZeroNetwork = true
                    )
                }
                return TaskRoutingDecision(
                    taskType = when (classification.category) {
                        ClassifiedTaskCategory.OCR -> TaskType.OCR
                        else -> TaskType.VISION
                    },
                    provider = "NONE",
                    requiresVision = true,
                    explanation = "Offline mode is enabled, but the installed local text runtime cannot process image/OCR/vision input. No online provider was called.",
                    guiTaskKind = guiKind,
                    status = StructuredRoutingStatus.MODEL_NOT_INSTALLED,
                    strictOfflineZeroNetwork = true
                )
            }
            if (!isGemmaConfigured) {
                return TaskRoutingDecision(
                    taskType = TaskType.GENERAL_REASONING,
                    provider = "NONE",
                    explanation = "Offline mode is enabled, but no supported local model is installed. Import a supported GGUF, LiteRT-LM, or MediaPipe Task model.",
                    guiTaskKind = guiKind,
                    status = StructuredRoutingStatus.MODEL_NOT_INSTALLED,
                    strictOfflineZeroNetwork = true
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
                explanation = "Routing request to the selected local on-device model. No network fallback is allowed.",
                guiTaskKind = guiKind,
                status = StructuredRoutingStatus.READY,
                strictOfflineZeroNetwork = true
            )
        }

        if (classification.category == ClassifiedTaskCategory.OCR) {
            if (isHfConfigured) {
                return TaskRoutingDecision(
                    taskType = TaskType.OCR,
                    provider = "HUGGINGFACE",
                    selectedModel = HuggingFaceProvider.MODEL_OCR,
                    requiresVision = true,
                    explanation = "Routing document OCR to Hugging Face TrOCR.",
                    guiTaskKind = GuiIntelligenceTaskKind.SCREEN_OCR_AND_DENSE_TEXT
                )
            } else if (isGeminiConfigured) {
                return TaskRoutingDecision(
                    taskType = TaskType.OCR,
                    provider = "GEMINI",
                    selectedModel = "gemini-3.5-flash-lite",
                    requiresVision = true,
                    explanation = "Routing document OCR to Gemini multimodal engine.",
                    guiTaskKind = GuiIntelligenceTaskKind.SCREEN_OCR_AND_DENSE_TEXT
                )
            }
        }

        if (classification.category == ClassifiedTaskCategory.VISION || classification.requiresVision) {
            if (isGeminiConfigured) {
                return TaskRoutingDecision(
                    taskType = TaskType.VISION,
                    provider = "GEMINI",
                    selectedModel = "gemini-3.5-flash-lite",
                    requiresVision = true,
                    explanation = "Routing visual scene analysis to Gemini Vision.",
                    guiTaskKind = guiKind
                )
            } else if (isHfConfigured) {
                return TaskRoutingDecision(
                    taskType = TaskType.VISION,
                    provider = "HUGGINGFACE",
                    selectedModel = HuggingFaceProvider.MODEL_IMAGE_ANALYSIS,
                    requiresVision = true,
                    explanation = "Routing image analysis to Hugging Face BLIP.",
                    guiTaskKind = guiKind
                )
            } else if (isOpenRouterConfigured) {
                val selected = OpenRouterModelRouter.selectModelForTask(
                    ClassifiedTaskCategory.VISION,
                    OpenRouterProvider.DEFAULT_VERIFIED_MODELS,
                    context
                )
                return TaskRoutingDecision(
                    taskType = TaskType.VISION,
                    provider = "OPENROUTER",
                    selectedModel = selected,
                    requiresVision = true,
                    explanation = "Routing visual analysis to OpenRouter multimodal model ($selected).",
                    guiTaskKind = guiKind
                )
            }
        }

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
                    selectedModel = "gemini-3.5-flash-lite",
                    explanation = "Routing research investigation to Gemini."
                )
            }
        }

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
                    selectedModel = "gemini-3.5-flash-lite",
                    explanation = "Routing coding task to Gemini reasoning engine."
                )
            }
        }

        if (isGeminiConfigured) {
            return TaskRoutingDecision(
                taskType = TaskType.GENERAL_REASONING,
                provider = "GEMINI",
                selectedModel = "gemini-3.5-flash-lite",
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
        }

        return TaskRoutingDecision(
            taskType = TaskType.GENERAL_REASONING,
            provider = "NONE",
            explanation = "No compatible AI provider is currently configured.",
            status = StructuredRoutingStatus.MODEL_NOT_INSTALLED
        )
    }
}
