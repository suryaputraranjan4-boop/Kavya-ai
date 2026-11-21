package com.example.ai

import android.content.Context
import android.util.Log
import com.example.ai.offline.Gemma4E4BEngine
import com.example.ai.offline.GemmaModelManager
import com.example.ai.offline.OfflineVisionContextBuilder
import com.example.data.MessageEntity
import com.example.utils.AppPreferences
import com.example.utils.NetworkConnectivityMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Single Authoritative AI Model Router for Kavya AI.
 *
 * Routes inference requests seamlessly between:
 * • ONLINE MODE → Gemini / configured cloud provider
 * • OFFLINE MODE → local vision (SmolVLM2 / Florence-2 / ML Kit OCR / Accessibility) + local LLM runtime
 *
 * Guarantees zero cloud leakage when Strict Offline Mode is enabled.
 */
class AIModelRouter(private val context: Context) {

    companion object {
        private const val TAG = "KavyaAIModelRouter"
    }

    init {
        OfflinePrivacyGuard.bindApplicationContext(context)
    }

    private val onlineEngine = KavyaAI(context)
    private val localEngine = Gemma4E4BEngine.getInstance()

    fun isOfflineMode(): Boolean {
        return OfflinePrivacyGuard.isStrictOfflineMode(context)
    }

    fun setOfflineMode(enabled: Boolean) {
        AppPreferences.setOfflineFallbackEnabled(context, enabled)
        if (enabled) {
            AppPreferences.setAiProvider(context, "GEMMA_OFFLINE")
            OfflinePrivacyGuard.cancelAllActiveCloudRequests("AIModelRouter switched to OFFLINE MODE")
            Log.i(TAG, "AI Model Router switched to OFFLINE MODE (selected local model)")
            GemmaModelManager.checkModelStatus(context)
        } else {
            AppPreferences.setAiProvider(context, "GEMINI")
            Log.i(TAG, "AI Model Router switched to ONLINE MODE (Gemini)")
        }
    }

    /**
     * Executes non-streaming chat query via the active AI inference engine.
     * In offline mode, image/screenshot payloads are analyzed locally via [OfflineVisionContextBuilder]
     * and forwarded into local LLM inference (or answered directly by SmolVLM2) without ever calling cloud APIs.
     */
    suspend fun chat(
        prompt: String,
        history: List<MessageEntity> = emptyList(),
        screenContext: String? = null,
        memoryContext: String = "",
        isProactiveMode: Boolean = false,
        imageBytes: ByteArray? = null
    ): String = withContext(Dispatchers.IO) {
        if (isOfflineMode()) {
            Log.d(TAG, "Routing chat request to LOCAL offline engine (hasImage=${imageBytes != null && imageBytes.isNotEmpty()})")
            var enrichedScreenContext = screenContext
            var enrichedPrompt = prompt

            if (imageBytes != null && imageBytes.isNotEmpty()) {
                val vision = OfflineVisionContextBuilder.analyzeImageBytes(
                    context = context,
                    imageBytes = imageBytes,
                    userPrompt = prompt,
                    accessibilityContext = screenContext
                )
                if (!vision.succeeded) {
                    return@withContext vision.errorReason
                        ?: "LOCAL OFFLINE VISION ERROR: Local vision analysis unavailable for the attached image."
                }
                if (!vision.directVlmAnswer.isNullOrBlank() && !localEngine.isReady()) {
                    OfflinePrivacyGuard.recordEngineAttribution("LOCAL_VLM:SmolVLM2")
                    return@withContext vision.directVlmAnswer
                }
                enrichedScreenContext = listOfNotNull(
                    screenContext?.takeIf { it.isNotBlank() },
                    vision.contextSummary.takeIf { it.isNotBlank() }
                ).joinToString("\n\n")
                enrichedPrompt = buildString {
                    appendLine(prompt)
                    appendLine()
                    appendLine("Use the LOCAL OFFLINE VISUAL EVIDENCE above to answer accurately.")
                }.trim()
            }

            if (!localEngine.isReady()) {
                val initResult = localEngine.initialize(context)
                if (initResult.isFailure) {
                    val err = initResult.exceptionOrNull()?.message ?: "Selected local model is not initialized or ready."
                    return@withContext if (imageBytes != null && imageBytes.isNotEmpty() && !enrichedScreenContext.isNullOrBlank()) {
                        "LOCAL OFFLINE VISION SUMMARY (local chat LLM not loaded: $err):\n\n$enrichedScreenContext"
                    } else {
                        "LOCAL OFFLINE ERROR: $err"
                    }
                }
            }
            OfflinePrivacyGuard.recordEngineAttribution("LOCAL_LLM:GemmaOffline")
            val sysInstruction = SystemPrompt.buildSystemPrompt(enrichedScreenContext, memoryContext, isProactiveMode)
            val localPrompt = buildOfflineConversationPrompt(enrichedPrompt, history)
            val genResult = localEngine.generate(localPrompt, sysInstruction)
            return@withContext genResult.getOrElse {
                "LOCAL OFFLINE ERROR: ${it.localizedMessage ?: "Generation failed."}"
            }
        } else {
            if (!NetworkConnectivityMonitor.isValidatedOnline(context)) {
                Log.w(TAG, "No validated internet detected in online mode; attempting request with clear connectivity diagnostics.")
            }
            Log.d(TAG, "Routing chat request to ONLINE provider")
            OfflinePrivacyGuard.recordEngineAttribution("CLOUD:${AppPreferences.getAiProvider(context)}")
            return@withContext onlineEngine.chat(
                prompt = prompt,
                history = history,
                screenContext = screenContext,
                memoryContext = memoryContext,
                isProactiveMode = isProactiveMode,
                imageBytes = imageBytes
            )
        }
    }

    /**
     * Streams generated response tokens from the active AI inference engine.
     * In offline mode, forwards image/screenshot bytes through [OfflineVisionContextBuilder]
     * before streaming local LLM tokens (or emits direct SmolVLM2 output / clear local error).
     */
    suspend fun streamChat(
        prompt: String,
        history: List<MessageEntity> = emptyList(),
        screenContext: String? = null,
        memoryContext: String = "",
        isProactiveMode: Boolean = false,
        imageBytes: ByteArray? = null
    ): Flow<String> {
        return if (isOfflineMode()) {
            flow {
                Log.d(TAG, "Routing streamChat request to LOCAL offline engine (hasImage=${imageBytes != null && imageBytes.isNotEmpty()})")
                var enrichedScreenContext = screenContext
                var enrichedPrompt = prompt

                if (imageBytes != null && imageBytes.isNotEmpty()) {
                    val vision = OfflineVisionContextBuilder.analyzeImageBytes(
                        context = context,
                        imageBytes = imageBytes,
                        userPrompt = prompt,
                        accessibilityContext = screenContext
                    )
                    if (!vision.succeeded) {
                        emit(
                            vision.errorReason
                                ?: "LOCAL OFFLINE VISION ERROR: Local vision analysis unavailable for the attached image."
                        )
                        return@flow
                    }
                    if (!vision.directVlmAnswer.isNullOrBlank() && !localEngine.isReady()) {
                        OfflinePrivacyGuard.recordEngineAttribution("LOCAL_VLM:SmolVLM2")
                        emit(vision.directVlmAnswer)
                        return@flow
                    }
                    enrichedScreenContext = listOfNotNull(
                        screenContext?.takeIf { it.isNotBlank() },
                        vision.contextSummary.takeIf { it.isNotBlank() }
                    ).joinToString("\n\n")
                    enrichedPrompt = buildString {
                        appendLine(prompt)
                        appendLine()
                        appendLine("Use the LOCAL OFFLINE VISUAL EVIDENCE above to answer accurately.")
                    }.trim()
                }

                val sysInstruction = SystemPrompt.buildSystemPrompt(enrichedScreenContext, memoryContext, isProactiveMode)
                if (!localEngine.isReady()) {
                    val initResult = localEngine.initialize(context)
                    if (initResult.isFailure) {
                        val error = initResult.exceptionOrNull()?.localizedMessage ?: "Local model initialization failed."
                        if (imageBytes != null && imageBytes.isNotEmpty() && !enrichedScreenContext.isNullOrBlank()) {
                            emit("LOCAL OFFLINE VISION SUMMARY (local chat LLM not loaded: $error):\n\n$enrichedScreenContext")
                        } else {
                            emit("LOCAL OFFLINE ERROR: $error")
                        }
                        return@flow
                    }
                }
                OfflinePrivacyGuard.recordEngineAttribution("LOCAL_LLM:GemmaOffline")
                localEngine.streamGenerate(buildOfflineConversationPrompt(enrichedPrompt, history), sysInstruction, context)
                    .collect { token -> emit(token) }
            }.flowOn(Dispatchers.IO)
        } else {
            Log.d(TAG, "Routing streamChat request to ONLINE provider")
            OfflinePrivacyGuard.recordEngineAttribution("CLOUD:${AppPreferences.getAiProvider(context)}")
            onlineEngine.streamChat(
                prompt = prompt,
                history = history,
                screenContext = screenContext,
                memoryContext = memoryContext,
                isProactiveMode = isProactiveMode,
                imageBytes = imageBytes
            )
        }
    }

    private fun buildOfflineConversationPrompt(
        prompt: String,
        history: List<MessageEntity>
    ): String {
        val recentTurns = history.asSequence()
            .filter { it.text.isNotBlank() }
            .toList()
            .takeLast(10)
            .map { message ->
                val role = if (message.isUser) "USER" else "ASSISTANT"
                val text = message.text.trim().takeLast(2000)
                "$role: $text"
            }
            .toList()

        if (recentTurns.isEmpty()) return prompt
        return buildString {
            appendLine("RECENT CONVERSATION:")
            recentTurns.forEach(::appendLine)
            appendLine()
            appendLine("CURRENT USER MESSAGE:")
            append(prompt)
        }
    }

    /**
     * Generates TTS speech audio from response text using the active voice engine.
     */
    suspend fun generateSpeechAudio(
        text: String,
        voiceName: String = "Kore",
        emotionLabel: String = "Sweet Anime Voice",
        pitchMultiplier: Float = 1.0f,
        speedMultiplier: Float = 1.0f
    ): ByteArray? {
        if (isOfflineMode()) {
            Log.w(TAG, "Offline mode: refusing cloud TTS to preserve zero-network offline behavior")
            return null
        }
        return onlineEngine.generateSpeechAudio(text, voiceName, emotionLabel, pitchMultiplier, speedMultiplier)
    }
}
