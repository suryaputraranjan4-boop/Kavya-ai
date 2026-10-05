package com.example.agent

import android.content.Context
import android.util.Log
import com.example.ai.KavyaAI
import com.example.ai.KavyaVoiceEngine
import com.example.data.AppDatabase
import com.example.data.ChatEntity
import com.example.data.MessageEntity
import com.example.skills.SkillsRegistry
import com.example.ai.AIModelRouter
import com.example.bots.CeoOrchestrator
import com.example.state.KavyaStateManager
import com.example.state.TaskState
import com.example.ui.components.VoiceState
import com.example.utils.AppPreferences
import com.example.utils.CommandRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Production-grade Master Assistant Pipeline for Kavya AI.
 *
 * Implements conversational AI execution pipeline:
 * 1. Single authoritative orchestrator used by [KavyaViewModel].
 * 2. Every user interaction is persisted into Room database as genuine text.
 * 3. Two-state Sleep / Wake engine (ACTIVE vs SLEEP).
 * 4. Emergency stop and conversation handling.
 * 5. IntentGate evaluation: Chat, Questions, App Launches, Skills, Media, Automation.
 * 6. Real-time state updates propagated to [KavyaStateManager] and UI.
 */
class KavyaAssistantPipeline(
    private val context: Context,
    private val voiceEngine: KavyaVoiceEngine,
    private val commandRouter: CommandRouter,
    private val androidAgent: AndroidAgent,
    private val memoryEngine: MemoryEngine
) {
    companion object {
        private const val TAG = "KavyaAssistantPipeline"

        @Volatile
        private var instance: KavyaAssistantPipeline? = null

        fun getInstance(
            context: Context,
            voiceEngine: KavyaVoiceEngine,
            commandRouter: CommandRouter,
            androidAgent: AndroidAgent,
            memoryEngine: MemoryEngine
        ): KavyaAssistantPipeline {
            return instance ?: synchronized(this) {
                instance ?: KavyaAssistantPipeline(
                    context.applicationContext,
                    voiceEngine,
                    commandRouter,
                    androidAgent,
                    memoryEngine
                ).also { instance = it }
            }
        }
    }

    private val pipelineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val db = AppDatabase.getInstance(context)
    private val chatDao = db.chatDao()
    private val screenInspector = ScreenInspector()
    private val taskPlanner = TaskPlanner(commandRouter.appResolver)
    private val ceoOrchestrator = CeoOrchestrator(context, AIModelRouter(context), androidAgent, taskPlanner)
    private val durableTaskEngine = DurableTaskEngine(context, db.taskDao())
    private val sessionSearchEngine = SessionSearchEngine(chatDao)
    private val schedulerEngine = com.example.scheduler.SchedulerEngine(context)

    @Volatile
    private var isBusyProcessing = false

    fun isBusy(): Boolean = isBusyProcessing

    /**
     * Executes a user utterance through the master pipeline.
     */
    fun processUtterance(
        rawText: String,
        explicitChatId: String? = null,
        autoSpeak: Boolean = true,
        onProgress: ((String) -> Unit)? = null,
        onTurnFinished: ((String) -> Unit)? = null
    ) {
        val prompt = rawText.trim()
        if (prompt.isBlank()) {
            return
        }

        if (isBusyProcessing) {
            Log.w(TAG, "Pipeline is busy. Dropping duplicate request: \"$prompt\"")
            return
        }

        isBusyProcessing = true

        pipelineScope.launch {
            try {
                // Immediate barge-in stop of any ongoing speech
                voiceEngine.stop()

                // =============================================================
                // 1. EMERGENCY STOP COMMAND CHECK
                // =============================================================
                val lower = prompt.lowercase().trim()
                if (lower == "stop" || lower == "ruko" || lower == "cancel" || lower == "bas" ||
                    lower.contains("stop kavya") || lower.contains("kavya stop") ||
                    lower.contains("chup ho jao") || lower.contains("kavya chup")
                ) {
                    Log.i(TAG, "EMERGENCY_STOP triggered: \"$prompt\"")
                    androidAgent.stopExecution("Stop command: $prompt")
                    KavyaStateManager.updateTaskState(TaskState.STOPPED, "Halted by user")
                    KavyaStateManager.updateVoiceState(VoiceState.IDLE)
                    val stopReply = "Stopped, sir."
                    if (autoSpeak) {
                        voiceEngine.speakSuspending(stopReply)
                    }
                    onTurnFinished?.invoke(stopReply)
                    return@launch
                }

                // =============================================================
                // 2. PERSIST REAL USER MESSAGE TO ROOM DATABASE
                // =============================================================
                val chatId = explicitChatId ?: ensureActiveChatId()
                val userMsgId = UUID.randomUUID().toString()
                val userMsgEntity = MessageEntity(
                    id = userMsgId,
                    chatId = chatId,
                    text = prompt,
                    isUser = true,
                    timestamp = System.currentTimeMillis()
                )
                chatDao.insertMessage(userMsgEntity)
                AutomationEventLogger.voice(prompt)

                // Update state machine to UNDERSTANDING
                KavyaStateManager.updateVoiceState(VoiceState.THINKING)
                KavyaStateManager.updateTaskState(TaskState.UNDERSTANDING, prompt)

                // =============================================================
                // 4. MASTER INTENT GATE EVALUATION
                // =============================================================
                val gateDecision = IntentGate.evaluate(prompt, commandRouter.appResolver)
                AutomationEventLogger.parsed("GateCategory=${gateDecision.category}, Target=${gateDecision.targetApp}, Reasoning=${gateDecision.reasoning}")

                var finalResponse = ""

                when (gateDecision.category) {
                    IntentCategory.SCHEDULE -> {
                        val skill = SkillsRegistry.findSkillForIntent(gateDecision)
                        val res = skill?.execute(gateDecision, context)
                        finalResponse = res?.outputMessage ?: "Scheduled."
                        KavyaStateManager.updateTaskState(TaskState.COMPLETED, finalResponse)
                    }

                    IntentCategory.MEMORY_SAVE -> {
                        val skill = SkillsRegistry.findSkillForIntent(gateDecision)
                        val res = skill?.execute(gateDecision, context)
                        finalResponse = res?.outputMessage ?: "Memory saved."
                        KavyaStateManager.updateTaskState(TaskState.COMPLETED, finalResponse)
                    }

                    IntentCategory.MEMORY_RECALL -> {
                        val pastSnippets = sessionSearchEngine.searchPastConversations(prompt)
                        val storedMems = memoryEngine.retrieveRelevantMemories(prompt)
                        finalResponse = if (pastSnippets.isNotEmpty()) {
                            "Pichli baat-cheet ke anusaar:\n" + pastSnippets.take(2).joinToString("\n") { "• ${it.formattedDate}: ${it.snippet}" }
                        } else if (storedMems.isNotEmpty()) {
                            "Aapki saved preference: " + storedMems.first().content
                        } else {
                            "Mujhe is vishay me koi purani jaankari nahi mili."
                        }
                        KavyaStateManager.updateTaskState(TaskState.COMPLETED, finalResponse)
                    }

                    IntentCategory.ACTION, IntentCategory.MULTI_STEP_TASK -> {
                        // Multi-step or direct action execution
                        KavyaStateManager.updateTaskState(TaskState.EXECUTING, prompt)
                        val skill = SkillsRegistry.findSkillForIntent(gateDecision)
                        if (skill != null) {
                            val res = skill.execute(gateDecision, context)
                            finalResponse = res.outputMessage
                        } else if (commandRouter.isDirectDeviceCommand(prompt)) {
                            val directRes = commandRouter.executeDirectUserCommand(prompt)
                            finalResponse = directRes.output
                        } else {
                            val plan = taskPlanner.createPlan(prompt, ContextEngine())
                            if (plan != null && plan.steps.isNotEmpty()) {
                                durableTaskEngine.createAndPersistTask(prompt, gateDecision.category, gateDecision.steps)
                                val outcome = androidAgent.executeTaskPlan(
                                    plan,
                                    onSpeakProgress = { ann ->
                                        onProgress?.invoke(ann)
                                    }
                                )
                                finalResponse = outcome.finalSpokenMessage.ifBlank {
                                    if (outcome.success) "Task pura ho gaya." else "Task execution me rukawat aayi."
                                }
                            } else {
                                finalResponse = "Action plan nahi ban paya."
                            }
                        }
                        KavyaStateManager.updateTaskState(TaskState.COMPLETED, finalResponse)
                    }

                    IntentCategory.UNKNOWN_OR_AMBIGUOUS -> {
                        if (gateDecision.isAmbiguous && gateDecision.candidateApps.isNotEmpty()) {
                            finalResponse = "Kaunsa app kholna hai? Mile: " + gateDecision.candidateApps.joinToString(", ") { it.appName }
                        } else {
                            // Fallback to conversational AI
                            finalResponse = executeConversationalTurn(prompt)
                        }
                        KavyaStateManager.updateTaskState(TaskState.COMPLETED, finalResponse)
                    }

                    else -> {
                        // Conversational Turn (CHAT, QUESTION, EXPLANATION, RESEARCH)
                        KavyaStateManager.updateTaskState(TaskState.UNDERSTANDING, prompt)
                        finalResponse = executeConversationalTurn(prompt)
                        KavyaStateManager.updateTaskState(TaskState.COMPLETED, finalResponse)
                    }
                }

                // =============================================================
                // 5. PERSIST REAL ASSISTANT RESPONSE TO ROOM DATABASE
                // =============================================================
                val assistantMsgId = UUID.randomUUID().toString()
                val assistantMsgEntity = MessageEntity(
                    id = assistantMsgId,
                    chatId = chatId,
                    text = finalResponse,
                    isUser = false,
                    timestamp = System.currentTimeMillis()
                )
                chatDao.insertMessage(assistantMsgEntity)
                AutomationEventLogger.verify("Response: $finalResponse")

                // =============================================================
                // 6. TTS PLAYBACK
                // =============================================================
                if (autoSpeak && finalResponse.isNotBlank()) {
                    KavyaStateManager.updateVoiceState(VoiceState.SPEAKING)
                    voiceEngine.speakSuspending(finalResponse)
                }

                onTurnFinished?.invoke(finalResponse)

            } catch (e: Exception) {
                Log.e(TAG, "Error in processUtterance: ${e.message}", e)
                val errMsg = "माफ़ कीजिए, अभी process करने में समस्या आई।"
                KavyaStateManager.updateVoiceState(VoiceState.ERROR)
                KavyaStateManager.updateTaskState(TaskState.FAILED, errMsg)
                if (autoSpeak) {
                    voiceEngine.speakSuspending("अभी connection में थोड़ी problem है, थोड़ी देर में फिर try करती हूँ।")
                }
                onTurnFinished?.invoke(errMsg)
            } finally {
                isBusyProcessing = false
                KavyaStateManager.updateVoiceState(VoiceState.IDLE)
            }
        }
    }

    private suspend fun executeConversationalTurn(prompt: String): String = withContext(Dispatchers.IO) {
        val ai = KavyaAI(context)
        val relevantMems = memoryEngine.retrieveRelevantMemories(prompt, screenInspector.getCurrentForegroundPackage())
        val memoryBlock = if (relevantMems.isNotEmpty()) relevantMems.joinToString("\n") { "- ${it.key}: ${it.content}" } else ""
        val response = ai.chat(
            prompt = prompt,
            history = emptyList(),
            screenContext = screenInspector.getCurrentForegroundPackage(),
            memoryContext = memoryBlock,
            isProactiveMode = false
        )
        voiceEngine.cleanText(response)
    }

    private suspend fun ensureActiveChatId(): String = withContext(Dispatchers.IO) {
        val latest = chatDao.getLatestChat()
        if (latest != null) {
            latest.id
        } else {
            val newId = UUID.randomUUID().toString()
            chatDao.insertChat(
                ChatEntity(
                    id = newId,
                    title = "Kavya Voice Session",
                    timestamp = System.currentTimeMillis()
                )
            )
            newId
        }
    }
}
