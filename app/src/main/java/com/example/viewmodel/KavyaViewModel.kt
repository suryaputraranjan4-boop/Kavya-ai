package com.example.viewmodel

import kotlinx.coroutines.CancellationException

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import com.example.agent.TaskPhase
import com.example.agent.ActionEvent
import com.example.agent.ActionEventBus
import com.example.agent.CommandCategory
import com.example.agent.ExecutionPhase
import com.example.agent.TaskExecutionContext
import com.example.agent.UserCommandClassifier
import com.example.agent.ClassifiedCommand
import com.example.agent.AndroidAgent
import com.example.agent.ContextEngine
import com.example.agent.DiagnosticEngine
import com.example.agent.MemoryEngine
import com.example.agent.ScreenInspector
import com.example.agent.TapEngine
import com.example.agent.TaskPlanner
import com.example.agent.UniversalActionType
import com.example.agent.VerificationEngine
import com.example.services.KavyaAccessibilityService
import com.example.ai.KavyaAI
import com.example.data.AppDatabase
import com.example.data.ChatEntity
import com.example.data.MemoryEntity
import com.example.data.MessageEntity
import com.example.memory.okf.OkfCategory
import com.example.memory.okf.OkfMemoryRepository
import com.example.memory.okf.OkfMemoryTools
import com.example.memory.okf.OkfProvenance
import com.example.scraper.maps.GoogleMapsQueryParser
import com.example.scraper.maps.GoogleMapsScraperClient
import com.example.scraper.maps.MapsScraperJobManager
import com.example.scraper.maps.ScrapeResult
import com.example.scraper.maps.ScrapedBusiness
import com.example.ui.components.VoiceState
import com.example.utils.AppLaunchDiagnostic
import com.example.utils.CommandRouter
import com.example.utils.InstalledApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.UUID

data class ChatMessage(
    val id: String,
    val text: String,
    val isUser: Boolean,
    val isLoading: Boolean = false,
    val isError: Boolean = false,
    val actionType: String? = null,
    val actionParam: String? = null,
    val isAmbiguous: Boolean = false,
    val candidateApps: List<InstalledApp> = emptyList(),
    val diagnostic: AppLaunchDiagnostic? = null
)

data class CommandHistoryItem(
    val command: String,
    val result: String,
    val timestamp: Long,
    val diagnostic: AppLaunchDiagnostic? = null
)

data class PendingConfirmation(
    val title: String,
    val description: String,
    val actionType: com.example.agent.UniversalActionType,
    val onConfirm: suspend () -> Unit,
    val onCancel: () -> Unit = {}
)

class KavyaViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "KavyaViewModel"
    }

    private val aiClient = com.example.ai.AIModelRouter(application)
    private val _voiceState = MutableStateFlow(VoiceState.IDLE)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _emotionState = MutableStateFlow(com.example.ui.components.EmotionState.NEUTRAL)
    val emotionState: StateFlow<com.example.ui.components.EmotionState> = _emotionState.asStateFlow()

    fun setEmotionState(state: com.example.ui.components.EmotionState) {
        _emotionState.value = state
    }

    private val _latestKavyaCaption = MutableStateFlow<String?>(null)
    val latestKavyaCaption: StateFlow<String?> = _latestKavyaCaption.asStateFlow()

    val geminiPlayer = com.example.ai.GeminiAudioPlayer(
        context = application,
        onSpeakingStateChanged = { isSpeaking ->
            if (isSpeaking) {
                bargeInController.onSpeakingStarted()
                _voiceState.value = VoiceState.SPEAKING
                com.example.state.KavyaStateManager.updateVoiceState(VoiceState.SPEAKING)
            } else {
                bargeInController.onSpeakingFinished()
                if (_voiceState.value == VoiceState.SPEAKING) {
                    _voiceState.value = VoiceState.IDLE
                    com.example.state.KavyaStateManager.updateVoiceState(VoiceState.IDLE)
                }
                _latestKavyaCaption.value = null
            }
        },
        onAudioChunkStarted = { spokenChunk ->
            if (spokenChunk.isNotBlank()) {
                _latestKavyaCaption.value = spokenChunk
            }
        }
    )

    val bargeInController: com.example.ai.BargeInController = com.example.ai.BargeInController(
        audioPlayer = geminiPlayer,
        onBargeInTriggered = {
            _latestKavyaCaption.value = ""
            _voiceState.value = VoiceState.IDLE
            com.example.state.KavyaStateManager.updateVoiceState(VoiceState.IDLE)
        }
    )

    val voiceEngine = com.example.ai.KavyaVoiceEngine(application, geminiPlayer, KavyaAI(application))
    private val database = AppDatabase.getDatabase(application)
    private val chatDao = database.chatDao()
    val memoryDao = database.memoryDao()
    val commandRouter = CommandRouter(application)

    // Master Architecture Engines (Context, Session Search, Durable Tasks, Scheduler)
    val sessionSearchEngine = com.example.agent.SessionSearchEngine(chatDao)
    val contextManager = com.example.agent.ConversationContextManager(sessionSearchEngine)
    val durableTaskEngine = com.example.agent.DurableTaskEngine(application, database.taskDao())
    val schedulerEngine = com.example.scheduler.SchedulerEngine(application, database.taskDao())

    // Universal Agent & Memory Engines
    val memoryEngine = MemoryEngine(application)
    val obsidianVaultManager = com.example.memory.obsidian.ObsidianVaultManager(application)
    val okfRepository: OkfMemoryRepository get() = memoryEngine.okfRepository
    val okfTools: OkfMemoryTools get() = memoryEngine.okfTools
    val mapsJobManager: MapsScraperJobManager = MapsScraperJobManager.getInstance(application)
    val mapsScraperClient = GoogleMapsScraperClient(application)
    val contextEngine = ContextEngine()
    val screenInspector = ScreenInspector()
    val verificationEngine = VerificationEngine(screenInspector)
    val taskPlanner = TaskPlanner()
    val androidAgent = AndroidAgent(application, commandRouter.appResolver, contextEngine, screenInspector, verificationEngine)
    val visualActionEngine = androidAgent.visualActionEngine
    val visualEngineStatus = visualActionEngine.engineStatus
    val agentOrchestrator = com.example.ai.providers.AgentOrchestrator(
        accessibilityProvider = com.example.ai.providers.AndroidAccessibilityToolProvider(androidAgent)
    )
    val apiSystem = com.example.api.ApiSystem()
    val ceoOrchestrator = com.example.orchestrator.CeoAiOrchestrator(application)
    val orchestrationState: StateFlow<com.example.orchestrator.OrchestrationSnapshot> =
        com.example.orchestrator.TaskOrchestrationStateMachine.snapshot

    private val recentAssistantResponses = mutableListOf<String>()

    private fun checkAndFilterRepetition(response: String): String {
        val trimmed = response.trim()
        if (trimmed.isBlank()) return trimmed
        recentAssistantResponses.add(trimmed)
        if (recentAssistantResponses.size > 5) recentAssistantResponses.removeAt(0)
        return trimmed
    }

    fun pauseAutomation(reason: String = "Paused by user") {
        ceoOrchestrator.pauseActiveTask(reason)
        _agentActionStatus.value = "Paused: $reason"
    }

    fun resumeAutomation() {
        ceoOrchestrator.resumePausedTask()
        _agentActionStatus.value = "Resuming with fresh screen check..."
    }

    fun cancelAutomation(reason: String = "Cancelled by user") {
        ceoOrchestrator.cancelActiveTask(reason)
        androidAgent.stopExecution(reason)
        _pendingConfirmation.value?.onCancel?.invoke()
        _pendingConfirmation.value = null
        _isProcessing.value = false
        _agentActionStatus.value = null
    }

    fun emergencyStop(reason: String = "User requested stop") {
        ceoOrchestrator.emergencyStop(reason)
        androidAgent.stopExecution(reason)
        _pendingConfirmation.value?.onCancel?.invoke()
        _pendingConfirmation.value = null
        com.example.visual.ScreenAnnotationManager.clearAllAnnotations()
        com.example.visual.JarvisGuidanceEngine.dismissExplanation()
        _isProcessing.value = false
        _agentActionStatus.value = "Action stopped."
        _latestKavyaCaption.value = "रुक गई हूँ। Action रोक दिया गया है।"
        com.example.state.KavyaStateManager.updateTaskState(com.example.state.TaskState.STOPPED, reason)
        if (_autoSpeak.value) {
            viewModelScope.launch {
                voiceEngine.processAndSpeak("रुक गई हूँ। Action रोक दिया गया है।", enqueue = false)
            }
        }
    }

    fun clearErrorState() {
        _latestKavyaCaption.value = null
        com.example.ai.AiErrorClassifier.clearLastError()
        com.example.state.KavyaStateManager.setGeminiState("IDLE", null)
    }

    private fun isVisualInteractionIntent(prompt: String): Boolean {
        val lower = prompt.lowercase(java.util.Locale.ROOT).trim()
        val visualKeywords = listOf(
            "br start", "cs ranked", "free fire", "freefire", "start button", "game start",
            "tap on", "click on", "dabaao", "dabao", "button दबाओ", "click करो", "tap करो",
            "screen पर", "screen par", "dhundo", "ढूंढो",
            "scroll down", "scroll up", "swipe up", "swipe down", "swipe left", "swipe right"
        )
        return visualKeywords.any { lower.contains(it) }
    }

    // Proactive Conversational Engine
    val proactiveController = com.example.proactive.ProactiveController(
        context = application,
        memoryEngine = memoryEngine,
        screenInspector = screenInspector,
        onProactiveMessageReady = { proactiveText, reason ->
            handleProactiveSpeech(proactiveText, reason)
        }
    )

    init {
        val savedPitch = com.example.utils.AppPreferences.getVoicePitch(application)
        val savedSpeed = com.example.utils.AppPreferences.getVoiceSpeed(application)

        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                com.example.state.KavyaStateManager.state,
                com.example.voice.KavyaMicrophoneEngine.engineState,
                com.example.services.ScreenShareManager.state
            ) { global, micState, shareState ->
                Triple(global, micState, shareState)
            }.collect { (global, micState, shareState) ->
                _voiceState.value = global.voiceState
                val screenSharingLive = shareState is com.example.services.ScreenShareManager.ScreenShareState.Active ||
                    shareState is com.example.services.ScreenShareManager.ScreenShareState.StaleFrame ||
                    shareState is com.example.services.ScreenShareManager.ScreenShareState.Paused
                com.example.visual.JarvisOverlayController.syncWithMicrophoneState(
                    micState = micState,
                    voiceState = global.voiceState,
                    isScreenSharingActive = screenSharingLive
                )
            }
        }

        viewModelScope.launch {
            com.example.agent.MicrophoneEngine.getInstance(application).amplitude.collect { amp ->
                com.example.visual.JarvisOverlayController.setAudioAmplitude(amp)
            }
        }

        com.example.visual.ExplanationStepController.onSpeakStep = { textToSpeak ->
            if (_autoSpeak.value && textToSpeak.isNotBlank()) {
                voiceEngine.processAndSpeak(textToSpeak, enqueue = false)
            }
        }
        com.example.visual.ExplanationStepController.onStopSpeaking = {
            voiceEngine.stop()
        }

        com.example.visual.JarvisGuidanceEngine.externalSpeakCallback = { textToSpeak ->
            if (textToSpeak.isNotBlank()) {
                voiceEngine.processAndSpeak(textToSpeak, enqueue = false)
            }
        }
        com.example.visual.JarvisGuidanceEngine.externalStopSpeakCallback = {
            voiceEngine.stop()
        }

        voiceEngine.onSpeakingStateChanged = { isSpeaking ->
            com.example.voice.KavyaMicrophoneEngine.onKavyaSpeakingChanged(isSpeaking)
        }

        com.example.voice.KavyaMicrophoneEngine.init(application)
        com.example.voice.KavyaMicrophoneEngine.setUserInterruptionListener {
            bargeInController.onUserInterrupted()
            voiceEngine.stop()
        }

        com.example.voice.KavyaMicrophoneEngine.setCommandListener { recognizedText ->
            viewModelScope.launch {
                sendMessage(recognizedText)
            }
        }

        com.example.orchestrator.AiAuthorityGateway.invalidateAllOnStartup()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                chatDao.sanitizeNoiseChatTitles()
                chatDao.deleteNoiseMessages()
            } catch (_: Exception) {}
            // NO USER COMMAND = NO AUTOMATION: do not autonomously execute due schedules on startup.
        }
    }

    val allChats = chatDao.getAllChats()

    private val _currentChatId = MutableStateFlow<String?>(null)
    val currentChatId = _currentChatId.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing = _isProcessing.asStateFlow()
    
    private val _commandHistory = MutableStateFlow<List<CommandHistoryItem>>(emptyList())
    val commandHistory: StateFlow<List<CommandHistoryItem>> = _commandHistory.asStateFlow()

    private val _latestDiagnostic = MutableStateFlow<AppLaunchDiagnostic?>(null)
    val latestDiagnostic: StateFlow<AppLaunchDiagnostic?> = _latestDiagnostic.asStateFlow()

    private val _isScreenSharing = MutableStateFlow(false)
    val isScreenSharing: StateFlow<Boolean> = _isScreenSharing.asStateFlow()

    private val _isOfflineMode = MutableStateFlow(
        com.example.utils.AppPreferences.isOfflineFallbackEnabled(application) || com.example.utils.AppPreferences.getAiProvider(application) == "GEMMA_OFFLINE"
    )
    val isOfflineMode: StateFlow<Boolean> = _isOfflineMode.asStateFlow()

    val gemmaModelStatus = com.example.ai.offline.GemmaModelManager.status

    fun toggleOfflineMode() {
        val newMode = !_isOfflineMode.value
        aiClient.setOfflineMode(newMode)
        _isOfflineMode.value = newMode
    }

    private val _screenContextText = MutableStateFlow<String?>(null)
    val screenContextText: StateFlow<String?> = _screenContextText.asStateFlow()

    private val _autoSpeak = MutableStateFlow(true)
    val autoSpeak: StateFlow<Boolean> = _autoSpeak.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingConfirmation?> = _pendingConfirmation.asStateFlow()

    private val _agentActionStatus = MutableStateFlow<String?>(null)
    val agentActionStatus: StateFlow<String?> = _agentActionStatus.asStateFlow()

    fun confirmPendingAction() {
        val pending = _pendingConfirmation.value
        _pendingConfirmation.value = null
        if (pending != null) {
            viewModelScope.launch {
                pending.onConfirm()
            }
        }
    }

    fun cancelPendingAction() {
        val pending = _pendingConfirmation.value
        _pendingConfirmation.value = null
        pending?.onCancel?.invoke()
        viewModelScope.launch {
            voiceEngine.speak("Action cancelled for security.")
        }
    }

    private suspend fun requestUserConfirmation(
        req: com.example.orchestrator.PendingConfirmationRequest
    ): Boolean {
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        _pendingConfirmation.value = PendingConfirmation(
            title = req.title,
            description = "${req.reason}\n\nTarget: ${req.action.target.description.ifBlank { req.action.action.name }}",
            actionType = com.example.agent.UniversalActionType.VERIFY,
            onConfirm = {
                if (!deferred.isCompleted) deferred.complete(true)
            },
            onCancel = {
                if (!deferred.isCompleted) deferred.complete(false)
            }
        )
        return try {
            deferred.await()
        } catch (cancelled: CancellationException) {
            _pendingConfirmation.value = null
            if (!deferred.isCompleted) deferred.complete(false)
            throw cancelled
        }
    }

    private var messagesJob: kotlinx.coroutines.Job? = null
    // Keep the latest user command instead of silently dropping it while another turn is running.
    @Volatile private var queuedPrompt: String? = null

    fun setVoiceState(state: VoiceState) {
        _voiceState.value = state
        com.example.state.KavyaStateManager.updateVoiceState(state)
        proactiveController.onKavyaSpeechStateChanged(state == VoiceState.SPEAKING)
    }

    fun startListening() {
        com.example.voice.KavyaMicrophoneEngine.startService(getApplication())
    }

    fun stopListening() {
        com.example.voice.KavyaMicrophoneEngine.stopService(getApplication())
    }

    fun sleepMicrophone() {
        com.example.voice.KavyaMicrophoneEngine.sleep(getApplication())
    }

    fun wakeMicrophone() {
        val app = getApplication<Application>()
        val mic = com.example.voice.KavyaMicrophoneEngine.getInstance()
        if (mic == null) {
            com.example.voice.KavyaMicrophoneEngine.startService(app)
        } else {
            com.example.voice.KavyaMicrophoneEngine.wake(app)
        }
    }

    fun toggleMicMute(): Boolean {
        return com.example.voice.KavyaMicrophoneEngine.toggleMute(getApplication())
    }

    private fun handleProactiveSpeech(text: String?, reason: String) {
        // NO USER COMMAND = NO AUTOMATION: proactive background triggers are disabled.
        Log.d(TAG, "Ignored proactive trigger ($reason): explicit user command required.")
        return
    }

    private suspend fun generateDynamicProactiveSpeech(reason: String, chatId: String): String? {
        return try {
            val historyEntities = _messages.value
                .filter { !it.isLoading && !it.isError }
                .takeLast(8)
                .map { MessageEntity(it.id, chatId, it.text, it.isUser, 0) }
            
            val screenContext = screenInspector.getScreenContextString()
            val prompt = "PROACTIVE COMPANION TRIGGER: $reason\n\nYou are Kavya, a friendly AI companion. Generate a short, natural proactive response (1-2 sentences max) in conversational Hinglish. \nCRITICAL RULES:\n- Mix behaviors (observations, comments, humor, encouragement, suggestions).\n- DO NOT always end with a question. In fact, avoid questions unless strictly necessary.\n- Do NOT use XML action tags here."
            
            val response = aiClient.chat(
                prompt = prompt,
                history = historyEntities,
                screenContext = screenContext,
                memoryContext = "",
                isProactiveMode = true
            )
            
            // If proactive speech hits an error (e.g. rate limit), fail silently instead of bothering the user
            if (response.contains("high traffic") || response.contains("busy right now") || response.startsWith("[sad]")) {
                return null
            }
            
            val clean = response.replace("<ACTION:[^>]+>".toRegex(), "").trim()
            if (clean.isNotBlank()) clean else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "Failed to generate dynamic proactive speech (${e.javaClass.simpleName}).")
            null
        }
    }

    fun setAutoSpeak(enabled: Boolean) {
        _autoSpeak.value = enabled
    }

    fun setVoiceSettings(pitch: Float, rate: Float) {
        voiceEngine.setBaseVoiceProfile(pitch, rate)
    }

    val screenShareState: StateFlow<com.example.services.ScreenShareManager.ScreenShareState> =
        com.example.services.ScreenShareManager.state

    fun startScreenShareConsent(ctx: Context): Intent? {
        return com.example.services.ScreenShareManager.createConsentIntent(ctx)
    }

    fun onScreenShareConsentResult(ctx: Context, resultCode: Int, data: Intent?) {
        com.example.services.ScreenShareManager.handleConsentResult(ctx, resultCode, data)
    }

    fun stopScreenShare() {
        com.example.services.ScreenShareManager.stopScreenShare(getApplication())
        _isScreenSharing.value = false
        _screenContextText.value = null
    }

    fun toggleScreenSharing(enabled: Boolean? = null) {
        val target = enabled ?: !com.example.services.ScreenShareManager.isScreenSharingActive
        if (!target) {
            stopScreenShare()
        }
    }

    fun refreshScreenContext() {
        val liveContext = com.example.services.KavyaAccessibilityService.instance?.getScreenContext()
        val perceptionSnapshot = com.example.visual.VisionPipeline.getUnifiedPerceptionSnapshot()
        val hasAccessibility = !liveContext.isNullOrBlank() && !liveContext.contains("Root node is null")
        _screenContextText.value = when {
            hasAccessibility && perceptionSnapshot.isVisualCaptureActive ->
                "$liveContext\n[Vision: Real-time screen capture feed active]"
            hasAccessibility ->
                liveContext
            perceptionSnapshot.isVisualCaptureActive ->
                "[Vision: Real-time screen capture feed active]"
            else ->
                "Status: Screen Perception is inactive. Enable Screen Share or Accessibility Service to analyze live screen content."
        }
    }

    fun analyzeScreenWithKavya(prompt: String = "What's on my screen right now? Please guide me.") {
        refreshScreenContext()
        sendMessage(prompt)
    }

    fun analyzeScreen(prompt: String = "What's on my screen right now? Please guide me.") {
        analyzeScreenWithKavya(prompt)
    }

    init {
        viewModelScope.launch {
            com.example.services.ScreenShareManager.state.collect { state ->
                when (state) {
                    is com.example.services.ScreenShareManager.ScreenShareState.Active -> {
                        _isScreenSharing.value = true
                        refreshScreenContext()
                    }
                    is com.example.services.ScreenShareManager.ScreenShareState.StaleFrame,
                    is com.example.services.ScreenShareManager.ScreenShareState.Paused -> {
                        _isScreenSharing.value = true
                        refreshScreenContext()
                    }
                    is com.example.services.ScreenShareManager.ScreenShareState.Stopped -> {
                        _isScreenSharing.value = false
                        _screenContextText.value = null
                    }
                    is com.example.services.ScreenShareManager.ScreenShareState.PermissionDenied -> {
                        _isScreenSharing.value = false
                        _screenContextText.value = null
                        val chatId = _currentChatId.value
                        if (chatId != null) {
                            val denyMsg = ChatMessage(
                                id = UUID.randomUUID().toString(),
                                text = "Screen share permission was cancelled or denied.",
                                isUser = false
                            )
                            _messages.value = _messages.value + denyMsg
                        }
                    }
                    is com.example.services.ScreenShareManager.ScreenShareState.Error -> {
                        _isScreenSharing.value = false
                        _screenContextText.value = null
                        val chatId = _currentChatId.value
                        if (chatId != null) {
                            val errMsg = ChatMessage(
                                id = UUID.randomUUID().toString(),
                                text = "Screen share failed: ${state.message}",
                                isUser = false
                            )
                            _messages.value = _messages.value + errMsg
                        }
                    }
                    else -> {
                        _isScreenSharing.value = false
                    }
                }
            }
        }

        viewModelScope.launch {
            chatDao.getAllChats().collect { chats ->
                if (_currentChatId.value == null) {
                    if (chats.isNotEmpty()) {
                        loadChat(chats.first().id)
                    } else {
                        startNewChat()
                    }
                }
            }
        }
    }

    fun startNewChat() {
        voiceEngine.stop()
        val newChatId = UUID.randomUUID().toString()
        _currentChatId.value = newChatId
        _messages.value = emptyList()
        loadChat(newChatId)
        
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            chatDao.insertChat(ChatEntity(
                id = newChatId,
                title = "New Chat",
                timestamp = System.currentTimeMillis()
            ))
        }
    }

    fun syncMemoriesToObsidian() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val all = memoryDao.getAllMemories().toMutableList()
            val roomKeys = all.map { it.key.trim().lowercase(java.util.Locale.ROOT) }.toHashSet()

            // Include active OKF knowledge that does not yet have a Room row.
            // Room remains authoritative for keys already represented in both stores.
            val okfOnly = okfRepository.listKnowledge(limit = Int.MAX_VALUE)
                .filter { it.key.trim().lowercase(java.util.Locale.ROOT) !in roomKeys }
                .map { unit ->
                    MemoryEntity(
                        key = unit.key,
                        content = unit.content,
                        category = unit.category.name,
                        importance = unit.importance.coerceIn(1, 5),
                        createdAt = unit.createdAt,
                        lastUsedAt = unit.updatedAt,
                        sourceConversation = unit.provenance.source,
                        confidence = unit.confidence.coerceIn(0f, 1f),
                        userConfirmed = unit.provenance.author.equals("User", ignoreCase = true)
                    )
                }

            all.addAll(okfOnly)
            obsidianVaultManager.syncMemoriesToVault(all)
        }
    }

    fun importMemoriesFromObsidian() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val imported = obsidianVaultManager.importMemoriesFromVault()
                .distinctBy { it.key.trim().lowercase(java.util.Locale.ROOT) }
            val existingByKey = memoryDao.getAllMemories()
                .associateBy { it.key.trim().lowercase(java.util.Locale.ROOT) }

            for (mem in imported) {
                val normalizedKey = mem.key.trim().lowercase(java.util.Locale.ROOT)
                val existing = existingByKey[normalizedKey]
                if (existing != null) {
                    memoryEngine.updateMemory(
                        existing.copy(
                            content = mem.content,
                            category = mem.category,
                            importance = mem.importance.coerceIn(1, 5),
                            lastUsedAt = maxOf(existing.lastUsedAt, mem.lastUsedAt),
                            sourceConversation = mem.sourceConversation,
                            confidence = mem.confidence.coerceIn(0f, 1f),
                            userConfirmed = true
                        )
                    )
                } else {
                    memoryEngine.insertCustomMemory(
                        key = mem.key,
                        content = mem.content,
                        category = mem.category,
                        importance = mem.importance
                    )
                }
            }
        }
    }

    fun loadChat(chatId: String) {
        voiceEngine.stop()
        _currentChatId.value = chatId
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            chatDao.getMessagesForChat(chatId).collect { msgEntities ->
                _messages.value = msgEntities.map { 
                    ChatMessage(id = it.id, text = it.text, isUser = it.isUser)
                }
            }
        }
    }

    @Volatile private var lastAnalyzedImageBytes: ByteArray? = null

    /**
     * Sends actual image pixels to Gemini Vision instead of only sending a filename.
     * Images are never uploaded while explicit offline mode is enabled.
     */
    fun analyzeImage(
        bitmap: android.graphics.Bitmap,
        sourceName: String = "image",
        customPrompt: String? = null
    ) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return
        if (_isOfflineMode.value || aiClient.isOfflineMode()) {
            sendMessage("Image analysis is not available in Offline Mode. Switch to Online Mode to send this image to Gemini Vision.")
            return
        }
        if (_isProcessing.value) {
            sendMessage("Please wait until the current response finishes before attaching another image.")
            return
        }
        val safeName = sourceName.replace(Regex("[\\r\\n]"), " ").take(120)
        viewModelScope.launch(Dispatchers.IO) {
            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, stream)
            lastAnalyzedImageBytes = stream.toByteArray()

            val chatId = _currentChatId.value ?: UUID.randomUUID().toString().also { id ->
                _currentChatId.value = id
                chatDao.insertChat(ChatEntity(id = id, title = "Image: " + safeName.take(20), timestamp = System.currentTimeMillis()))
            }
            val userText = if (!customPrompt.isNullOrBlank()) {
                "📷 Image attached: $safeName (${bitmap.width} × ${bitmap.height})\nPrompt: $customPrompt"
            } else {
                "📷 Image attached: $safeName (${bitmap.width} × ${bitmap.height})."
            }
            val userId = UUID.randomUUID().toString()
            chatDao.insertMessage(MessageEntity(userId, chatId, userText, true, System.currentTimeMillis()))
            _messages.value = _messages.value + ChatMessage(userId, userText, true)
            val loadingId = UUID.randomUUID().toString()
            _messages.value = _messages.value + ChatMessage(loadingId, "Analyzing image…", false, isLoading = true)
            try {
                val effectivePrompt = customPrompt?.takeIf { it.isNotBlank() }
                    ?: "Carefully describe and analyze this user-provided image. Extract any visible text, numbers, labels, or objects. Answer questions about its contents. Distinguish visible facts from uncertainty and do not invent details."
                val result = KavyaAI(getApplication<Application>()).analyzeVisualUI(
                    bitmap,
                    effectivePrompt
                )
                val answer = result.takeIf { it.isNotBlank() } ?: "I could not analyze this image. Please try another image."
                val assistantId = UUID.randomUUID().toString()
                _messages.value = _messages.value.filterNot { it.id == loadingId } + ChatMessage(assistantId, answer, false, isError = answer.startsWith("Visual parsing failed:") || answer.startsWith("Error:"))
                chatDao.insertMessage(MessageEntity(assistantId, chatId, answer, false, System.currentTimeMillis()))
                _latestKavyaCaption.value = answer
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                val answer = "Image analysis failed: " + (e.localizedMessage ?: "Unknown error")
                val assistantId = UUID.randomUUID().toString()
                _messages.value = _messages.value.filterNot { it.id == loadingId } + ChatMessage(assistantId, answer, false, isError = true)
                chatDao.insertMessage(MessageEntity(assistantId, chatId, answer, false, System.currentTimeMillis()))
            }
        }
    }

    /**
     * Processes a text document in bounded chunks so large documents are analyzed safely
     * without silent single-chunk truncation.
     */
    fun analyzeTextDocument(fileName: String, rawText: String) {
        val cleaned = rawText.trim()
        if (cleaned.isBlank()) {
            sendMessage("The selected document ($fileName) is empty or contains no readable text.")
            return
        }
        val chunkSize = 3500
        val maxChunks = 4
        val chunks = cleaned.chunked(chunkSize).take(maxChunks)
        val totalChars = cleaned.length
        val analyzedChars = chunks.sumOf { it.length }
        val structuredContent = buildString {
            append("Please analyze and summarize the attached document ($fileName, $totalChars characters):\n\n")
            chunks.forEachIndexed { idx, chunk ->
                append("--- Section ${idx + 1} of ${chunks.size} ---\n")
                append(chunk)
                append("\n\n")
            }
            if (totalChars > analyzedChars) {
                append("[Note: Showing first $analyzedChars of $totalChars characters across $maxChunks bounded chunks.]")
            }
        }
        sendMessage(structuredContent)
    }

    /**
     * Persists and processes a recorded voice message in Chat (Phase 8).
     * Supports playback from private storage, transcription (when online), and AI response/summary.
     */
    fun sendVoiceMessage(audioFile: java.io.File, durationSec: Int, pcmData: ByteArray?) {
        if (!audioFile.exists() || audioFile.length() <= 0L) {
            sendMessage("Recorded voice message file is empty or missing.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val chatId = _currentChatId.value ?: UUID.randomUUID().toString().also { id ->
                _currentChatId.value = id
                chatDao.insertChat(ChatEntity(id = id, title = "Voice Message", timestamp = System.currentTimeMillis()))
            }
            val safeDuration = durationSec.coerceAtLeast(1)
            val transcript = if (pcmData != null && pcmData.isNotEmpty() && !_isOfflineMode.value && !aiClient.isOfflineMode()) {
                try {
                    val rawTr = KavyaAI(getApplication<Application>()).transcribeAudio(pcmData, 16000)
                    com.example.utils.SpeechSanitizer.sanitize(rawTr)
                } catch (_: Exception) {
                    ""
                }
            } else {
                ""
            }

            val voiceTag = "[VOICE_MESSAGE:${audioFile.absolutePath}|${safeDuration}s]"
            val userBubbleText = if (transcript.isNotBlank()) {
                "$voiceTag\n🎤 Voice Note (${safeDuration}s): \"$transcript\""
            } else if (_isOfflineMode.value || aiClient.isOfflineMode()) {
                "$voiceTag\n🎤 Voice Note (${safeDuration}s) [Saved locally — Offline Mode active]"
            } else {
                "$voiceTag\n🎤 Voice Note (${safeDuration}s)"
            }

            val userId = UUID.randomUUID().toString()
            chatDao.insertMessage(MessageEntity(userId, chatId, userBubbleText, true, System.currentTimeMillis()))
            _messages.value = _messages.value + ChatMessage(userId, userBubbleText, true)

            if (transcript.isNotBlank()) {
                processAgentLoop(
                    prompt = "The user sent a ${safeDuration}-second voice message with transcript: \"$transcript\". Respond naturally to their message and provide a helpful answer or summary.",
                    chatId = chatId,
                    userMsgEntity = null,
                    recursionDepth = 0
                )
            } else {
                val replyText = if (_isOfflineMode.value || aiClient.isOfflineMode()) {
                    "Your ${safeDuration}-second voice message has been saved locally. You can replay it anytime in chat. Switch to Online Mode if you want cloud speech transcription."
                } else {
                    "Your ${safeDuration}-second voice message has been saved to chat, but no clear speech transcript was detected. You can replay it above or record again."
                }
                val assistantId = UUID.randomUUID().toString()
                chatDao.insertMessage(MessageEntity(assistantId, chatId, replyText, false, System.currentTimeMillis()))
                _messages.value = _messages.value + ChatMessage(assistantId, replyText, false)
            }
        }
    }

    /**
     * Renders each page of a selected PDF and analyzes its actual visual content.
     * Limits page count and render dimensions to keep memory use bounded.
     */
    fun analyzePdf(file: java.io.File, displayName: String = file.name) {
        if (_isOfflineMode.value || aiClient.isOfflineMode()) {
            sendMessage("PDF visual analysis is not available in Offline Mode. Switch to Online Mode to analyze this PDF.")
            return
        }
        if (!file.isFile || file.length() <= 0L) {
            sendMessage("The selected PDF could not be read.")
            return
        }
        if (_isProcessing.value) {
            sendMessage("Please wait until the current response finishes before attaching a PDF.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val chatId = _currentChatId.value ?: UUID.randomUUID().toString().also { id ->
                _currentChatId.value = id
                chatDao.insertChat(ChatEntity(id = id, title = "PDF: " + displayName.take(20), timestamp = System.currentTimeMillis()))
            }
            val userText = "PDF attached: " + displayName
            val userId = UUID.randomUUID().toString()
            chatDao.insertMessage(MessageEntity(userId, chatId, userText, true, System.currentTimeMillis()))
            _messages.value = _messages.value + ChatMessage(userId, userText, true)
            val loadingId = UUID.randomUUID().toString()
            _messages.value = _messages.value + ChatMessage(loadingId, "Reading PDF pages…", false, isLoading = true)
            try {
                val pageResults = mutableListOf<String>()
                val descriptor = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                descriptor.use { fileDescriptor ->
                    android.graphics.pdf.PdfRenderer(fileDescriptor).use { renderer ->
                    if (renderer.pageCount == 0) throw IllegalArgumentException("PDF contains no pages.")
                    val pageLimit = minOf(renderer.pageCount, 20)
                    for (index in 0 until pageLimit) {
                        renderer.openPage(index).use { page ->
                            val scale = minOf(1f, 1400f / maxOf(page.width, page.height).toFloat())
                            val width = (page.width * scale).toInt().coerceAtLeast(1)
                            val height = (page.height * scale).toInt().coerceAtLeast(1)
                            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                            page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            try {
                                val analysis = KavyaAI(getApplication<Application>()).analyzeVisualUI(
                                    bitmap,
                                    "Analyze page " + (index + 1) + " of PDF " + displayName + ". Extract all readable text, tables, headings, diagrams and important details. Clearly state anything unreadable."
                                )
                                pageResults.add("Page " + (index + 1) + ":\n" + analysis)
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                    if (renderer.pageCount > pageLimit) {
                        pageResults.add("Only the first " + pageLimit + " of " + renderer.pageCount + " pages were analyzed to protect device memory and request limits.")
                    }
                    }
                }
                val answer = pageResults.joinToString("\n\n").ifBlank { "No readable PDF pages were found." }
                val assistantId = UUID.randomUUID().toString()
                _messages.value = _messages.value.filterNot { it.id == loadingId } + ChatMessage(assistantId, answer, false)
                chatDao.insertMessage(MessageEntity(assistantId, chatId, answer, false, System.currentTimeMillis()))
                _latestKavyaCaption.value = "PDF analysis complete."
            } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
                val answer = "PDF analysis failed: " + (e.localizedMessage ?: "Unable to render this PDF.")
                val assistantId = UUID.randomUUID().toString()
                _messages.value = _messages.value.filterNot { it.id == loadingId } + ChatMessage(assistantId, answer, false, isError = true)
                chatDao.insertMessage(MessageEntity(assistantId, chatId, answer, false, System.currentTimeMillis()))
            }
        }
    }

    fun sendMessage(text: String) {
        val sanitizedPrompt = com.example.utils.SpeechSanitizer.sanitize(text)
        if (sanitizedPrompt.isBlank() || com.example.utils.SpeechSanitizer.isNoiseOnly(text)) {
            Log.w(TAG, "Suppressed noise or blank input in sendMessage: '$text'")
            return
        }

        val normalized = text.lowercase(java.util.Locale.ROOT).trim()
        val wakeCommand = normalized == "wake kavya" || normalized == "wake up kavya" || normalized == "hey kavya" || normalized == "hi kavya" || normalized == "hello kavya" || normalized == "kavya wake up"
        val sleepCommand = normalized == "sleep kavya" || normalized == "kavya sleep" || normalized == "go to sleep kavya" || normalized == "kavya go to sleep" || normalized == "so jao kavya"
        val stopCommand = normalized == "stop kavya" || normalized == "kavya stop" || normalized == "stop kavia" || normalized == "kavya ruko" || normalized == "ruko kavya"

        if (wakeCommand) {
            wakeMicrophone()
            _latestKavyaCaption.value = "I am awake."
            return
        }
        if (sleepCommand) {
            sleepMicrophone()
            _latestKavyaCaption.value = "Sleep mode on. Say Wake Kavya to wake me."
            return
        }
        if (stopCommand) {
            stopListening()
            emergencyStop("Voice stop command: $text")
            return
        }

        // Emergency Stop Voice Trigger (Hindi / English: stop, ruko, cancel, bas, etc.)
        if (visualActionEngine.safetyController.isStopCommand(text)) {
            stopListening()
            emergencyStop("Voice stop command: $text")
            return
        }
        
        // Queue the latest command instead of silently dropping it during an active turn.
        if (_isProcessing.value) {
            queuedPrompt = text.trim()
            return
        }
        
        voiceEngine.stop() // Immediate barge-in stop
        val chatId = _currentChatId.value ?: run {
            val newId = java.util.UUID.randomUUID().toString()
            _currentChatId.value = newId
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                chatDao.insertChat(com.example.data.ChatEntity(
                    id = newId,
                    title = "New Chat",
                    timestamp = System.currentTimeMillis()
                ))
            }
            newId
        }

        // Check for Silence / Resume / Proactive control commands
        val silenceCheck = com.example.proactive.SilenceCommandDetector.evaluate(text)
        if (silenceCheck.detected) {
            when (silenceCheck.action) {
                com.example.proactive.SilenceAction.TURN_OFF_PROACTIVE -> {
                    proactiveController.setProactiveMode(false)
                    voiceEngine.stop()
                }
                com.example.proactive.SilenceAction.TURN_ON_PROACTIVE -> {
                    proactiveController.setProactiveMode(true)
                    proactiveController.clearSilence()
                }
                com.example.proactive.SilenceAction.TEMPORARY_SILENCE -> {
                    proactiveController.setTemporarySilence(silenceCheck.silenceDurationMs)
                    voiceEngine.stop()
                }
                com.example.proactive.SilenceAction.DECREASE_FREQUENCY -> {
                    proactiveController.setFrequency(com.example.proactive.ProactiveFrequency.QUIET)
                }
                com.example.proactive.SilenceAction.INCREASE_FREQUENCY -> {
                    proactiveController.setFrequency(com.example.proactive.ProactiveFrequency.CHATTY)
                }
                com.example.proactive.SilenceAction.NONE -> {}
            }

            val userMsgId = UUID.randomUUID().toString()
            val userMsgEntity = MessageEntity(
                id = userMsgId,
                chatId = chatId,
                text = text,
                isUser = true,
                timestamp = System.currentTimeMillis()
            )
            _messages.value = _messages.value + ChatMessage(id = userMsgId, text = text, isUser = true)

            val modelMsgId = UUID.randomUUID().toString()
            val modelMsg = ChatMessage(id = modelMsgId, text = silenceCheck.confirmationText, isUser = false)
            _messages.value = _messages.value + modelMsg
            _latestKavyaCaption.value = silenceCheck.confirmationText

            viewModelScope.launch {
                chatDao.insertMessage(userMsgEntity)
                chatDao.insertMessage(
                    MessageEntity(
                        id = modelMsgId,
                        chatId = chatId,
                        text = silenceCheck.confirmationText,
                        isUser = false,
                        timestamp = System.currentTimeMillis()
                    )
                )
                if (_autoSpeak.value) {
                    voiceEngine.processAndSpeak(silenceCheck.confirmationText, enqueue = false)
                }
            }
            return
        }

        val userMsgId = UUID.randomUUID().toString()
        val userMsgEntity = MessageEntity(
            id = userMsgId,
            chatId = chatId,
            text = text,
            isUser = true,
            timestamp = System.currentTimeMillis()
        )
        
        val userMsg = ChatMessage(id = userMsgId, text = text, isUser = true)
        _messages.value = _messages.value + userMsg
        
        processAgentLoop(chatId, userMsgEntity, text, recursionDepth = 0)
    }

    fun selectCandidateApp(app: InstalledApp, originalQuery: String) {
        // Save preference as user alias, then route through the central AI authority pipeline (Phase 10)
        commandRouter.appResolver.saveUserAlias(originalQuery, app.packageName)
        sendMessage("Open ${app.appName}")
    }
    
    private fun processAgentLoop(
        chatId: String, 
        userMsgEntity: MessageEntity?, 
        prompt: String,
        recursionDepth: Int
    ) {
        val loadingMsgId = UUID.randomUUID().toString()
        val loadingMsg = ChatMessage(id = loadingMsgId, text = "...", isUser = false, isLoading = true)
        _messages.value = _messages.value + loadingMsg
        
        _isProcessing.value = true
        _voiceState.value = VoiceState.EXECUTING
        com.example.state.KavyaStateManager.updateVoiceState(VoiceState.EXECUTING)
        com.example.state.KavyaStateManager.setGeminiState("PROCESSING")
        _latestKavyaCaption.value = null

        viewModelScope.launch {
            var cleanResponse = ""
            var shouldRecurse = false
            try {
                if (userMsgEntity != null) {
                    chatDao.insertMessage(userMsgEntity)
                    if (_messages.value.size <= 2) {
                        val cleanForTitle = com.example.utils.SpeechSanitizer.sanitize(prompt)
                        val derivedTitle = if (cleanForTitle.isNotBlank()) {
                            cleanForTitle.take(28).replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                        } else {
                            "Conversation"
                        }
                        chatDao.updateChatTitle(chatId, derivedTitle)
                    }
                }
                
                var aType: String? = null
                var aParam: String? = null
                var isAmbiguous = false
                var alreadySpoken = false
                var candidateApps: List<InstalledApp> = emptyList()
                var launchDiagnostic: AppLaunchDiagnostic? = null
                var executionResultStr = ""

                // 0. Voice-driven Screen Share & JARVIS Guidance Controls (Phases 5 & 12)
                val lowerPromptTrimmed = prompt.lowercase(java.util.Locale.ROOT).trim()
                val isStartScreenShare = lowerPromptTrimmed in listOf(
                    "start screen share", "screen share start karo", "screen share chalu karo",
                    "screen share on karo", "share my screen", "screen share karo", "स्क्रीन शेयर चालू करो"
                )
                val isStopScreenShare = lowerPromptTrimmed in listOf(
                    "stop screen share", "screen share band karo", "screen share off karo",
                    "screen share roko", "स्क्रीन शेयर बंद करो"
                )
                val isPauseScreenShare = lowerPromptTrimmed in listOf(
                    "pause screen share", "screen share pause karo"
                )
                val isResumeScreenShare = lowerPromptTrimmed in listOf(
                    "resume screen share", "screen share resume karo"
                )

                if (isStartScreenShare) {
                    com.example.services.ScreenShareManager.requestMediaProjectionConsentFromCommand(getApplication())
                    cleanResponse = if (com.example.services.ScreenShareManager.isScreenSharingActive) {
                        "Screen Share and the Unified JARVIS Live Overlay are already active."
                    } else {
                        "Opening Screen Share permission prompt. Tap Start Now to share your screen with the Unified JARVIS Live Overlay."
                    }
                    if (_autoSpeak.value) {
                        voiceEngine.processAndSpeak(cleanResponse, enqueue = false)
                        alreadySpoken = true
                    }
                } else if (isStopScreenShare) {
                    stopScreenShare()
                    cleanResponse = "Screen Share has been stopped."
                    if (_autoSpeak.value) {
                        voiceEngine.processAndSpeak(cleanResponse, enqueue = false)
                        alreadySpoken = true
                    }
                } else if (isPauseScreenShare) {
                    com.example.services.ScreenShareManager.setUserPaused(true)
                    cleanResponse = "Screen Share paused for privacy."
                    if (_autoSpeak.value) {
                        voiceEngine.processAndSpeak(cleanResponse, enqueue = false)
                        alreadySpoken = true
                    }
                } else if (isResumeScreenShare) {
                    com.example.services.ScreenShareManager.setUserPaused(false)
                    cleanResponse = "Screen Share resumed."
                    if (_autoSpeak.value) {
                        voiceEngine.processAndSpeak(cleanResponse, enqueue = false)
                        alreadySpoken = true
                    }
                } else {
                    val jarvisVoiceControlReply = com.example.visual.JarvisGuidanceEngine.handleVoiceGuidanceControlCommand(
                        getApplication(),
                        prompt
                    )
                    if (jarvisVoiceControlReply != null) {
                        cleanResponse = jarvisVoiceControlReply
                        alreadySpoken = true
                    }
                }

                // 0a. Master Intent Gate (Requirements 1, 2, 3)
                val gateDecision = com.example.agent.IntentGate.evaluate(prompt, commandRouter.appResolver)
                val isConversationalTurn = gateDecision.category in listOf(
                    com.example.agent.IntentCategory.CHAT,
                    com.example.agent.IntentCategory.QUESTION,
                    com.example.agent.IntentCategory.EXPLANATION
                )

                // Requirement 34: Automation Event Logging
                com.example.agent.AutomationEventLogger.voice(prompt)
                com.example.agent.AutomationEventLogger.parsed("GateCategory=${gateDecision.category}, Target=${gateDecision.targetApp}, Reasoning=${gateDecision.reasoning}")

                // Requirement 20: STOP and SLEEP Handling
                if (cleanResponse.isNotBlank()) {
                    // Already handled by Screen Share or JARVIS step control above
                } else if (gateDecision.category == com.example.agent.IntentCategory.STOP || gateDecision.category == com.example.agent.IntentCategory.CANCEL) {
                    com.example.agent.TaskStateMachine.update(
                        phase = com.example.agent.TaskPhase.INTERRUPTED,
                        lastAction = "STOP",
                        actionResult = "User stopped automation"
                    )
                    com.example.agent.AutomationEventLogger.task("Automation halted by user request.")
                    stopListening()
                    emergencyStop("User requested stop")
                    cleanResponse = "Stopped, sir."
                    alreadySpoken = true
                } else if (gateDecision.category == com.example.agent.IntentCategory.SLEEP) {
                    com.example.agent.TaskStateMachine.update(
                        phase = com.example.agent.TaskPhase.SLEEP,
                        lastAction = "SLEEP",
                        actionResult = "Entering sleep mode"
                    )
                    com.example.agent.AutomationEventLogger.task("Entering sleep mode.")
                    voiceEngine.stop()
                    val app = getApplication<Application>()
                    com.example.utils.AppPreferences.setKavyaState(app, "SLEEP")
                    cleanResponse = "Good night, sir. Going to sleep. Say 'Wake Kavya' whenever you need me."
                    if (_autoSpeak.value) {
                        voiceEngine.processAndSpeak(cleanResponse, enqueue = false)
                        alreadySpoken = true
                    }
                } else if (gateDecision.category == com.example.agent.IntentCategory.SCHEDULE) {
                    cleanResponse = "Autonomous scheduled background automation is disabled under AI-only explicit command policy. Say or type a command whenever you want the selected AI to perform an action."
                    if (_autoSpeak.value) {
                        voiceEngine.processAndSpeak(cleanResponse, enqueue = false)
                        alreadySpoken = true
                    }
                } else if (gateDecision.category == com.example.agent.IntentCategory.MEMORY_SAVE) {
                    val skill = com.example.skills.SkillsRegistry.findSkillForIntent(gateDecision)
                    val res = skill?.execute(gateDecision, getApplication())
                    cleanResponse = res?.outputMessage ?: "Memory saved."
                    if (_autoSpeak.value) {
                        voiceEngine.processAndSpeak(cleanResponse, enqueue = false)
                        alreadySpoken = true
                    }
                } else if (gateDecision.category == com.example.agent.IntentCategory.MEMORY_RECALL) {
                    val pastSnippets = sessionSearchEngine.searchPastConversations(prompt)
                    val storedMems = memoryEngine.retrieveRelevantMemories(prompt)
                    if (pastSnippets.isNotEmpty()) {
                        cleanResponse = "Pichli baat-cheet ke anusaar:\n" + pastSnippets.take(2).joinToString("\n") { "• ${it.formattedDate}: ${it.snippet}" }
                    } else if (storedMems.isNotEmpty()) {
                        cleanResponse = "Aapki saved preference: " + storedMems.first().content
                    }
                    if (cleanResponse.isNotBlank() && _autoSpeak.value) {
                        voiceEngine.processAndSpeak(cleanResponse, enqueue = false)
                        alreadySpoken = true
                    }
                }

                // 0b. CEO AI ORCHESTRATOR ROUTING (AI = ONLY BRAIN, ACCESSIBILITY = RESTRICTED EXECUTOR)
                // All device actions, app opens, taps, types, scrolls, calls, messages, media, and multi-step commands
                // MUST pass exclusively through CeoAiOrchestrator -> SelectedProviderClient -> AiAuthorityGateway -> ScreenObservation -> Verification.
                val classified = if (!isConversationalTurn) {
                    UserCommandClassifier.classify(prompt, commandRouter.appResolver)
                } else {
                    ClassifiedCommand(CommandCategory.CONVERSATION, rawPrompt = prompt)
                }

                val isDeviceAutomationRequest = !isConversationalTurn && (
                    gateDecision.category == com.example.agent.IntentCategory.ACTION ||
                    gateDecision.category == com.example.agent.IntentCategory.MULTI_STEP_TASK ||
                    gateDecision.category == com.example.agent.IntentCategory.UNKNOWN_OR_AMBIGUOUS ||
                    classified.isMultiStep ||
                    classified.category in listOf(
                        CommandCategory.OPEN_APP,
                        CommandCategory.SEARCH_IN_APP,
                        CommandCategory.INTERACT_IN_APP,
                        CommandCategory.PLAY_MEDIA,
                        CommandCategory.CALL,
                        CommandCategory.SEND_MESSAGE,
                        CommandCategory.SYSTEM_ACTION
                    ) ||
                    isVisualInteractionIntent(prompt)
                )

                if (cleanResponse.isBlank() && isDeviceAutomationRequest) {
                    val outcome = ceoOrchestrator.runTask(
                        userCommand = prompt,
                        requiresScreenVision = true,
                        onStatusUpdate = { status ->
                            _agentActionStatus.value = status
                        },
                        onRequestConfirmation = { req ->
                            requestUserConfirmation(req)
                        }
                    )
                    _agentActionStatus.value = null
                    cleanResponse = outcome.finalMessage
                    aType = if (outcome.success) "CEO_ORCHESTRATED" else outcome.failureCode
                    aParam = "steps=${outcome.stepsExecuted}, verified=${outcome.verifiedSteps}"
                    val historyItem = CommandHistoryItem(
                        command = prompt.take(80),
                        result = cleanResponse,
                        timestamp = System.currentTimeMillis()
                    )
                    _commandHistory.value = listOf(historyItem) + _commandHistory.value
                    if (_autoSpeak.value && cleanResponse.isNotBlank() && !alreadySpoken) {
                        voiceEngine.processAndSpeak(cleanResponse, enqueue = false)
                        alreadySpoken = true
                    }
                }

                // 0c. Fast Path & Intent Routing Check
                var isFastChat = false
                val lowerPrompt = prompt.lowercase(java.util.Locale.ROOT).trim()
                val fastPhrases = listOf("hi", "hello", "hey", "how are you", "what's up", "good morning", "good night", "thanks", "thank you", "okay", "yes", "no", "what is your name", "who are you")
                if (fastPhrases.any { lowerPrompt == it || lowerPrompt.startsWith("$it ") || lowerPrompt.endsWith(" $it") }) {
                    isFastChat = true
                }
                
                val intentMatch = if (!isFastChat) apiSystem.router.routeIntent(prompt) else null

                if (!isFastChat && intentMatch == null) {
                    // 1. Check for explicit Memory Extraction / Forget commands
                    val memoryExtraction = memoryEngine.processAndExtractMemory(prompt, sourceConversation = prompt)
                    if (memoryExtraction.detected && memoryExtraction.confirmationText.isNotBlank()) {
                        cleanResponse = memoryExtraction.confirmationText
                        if (_autoSpeak.value) {
                            voiceEngine.processAndSpeak(cleanResponse, enqueue = true)
                            alreadySpoken = true
                        }
                    }
                    
                    // 2. Check for explicit Conversational Recall questions
                    if (cleanResponse.isBlank()) {
                        val recallAnswer = memoryEngine.handleConversationalRecall(prompt)
                        if (recallAnswer != null) {
                            cleanResponse = recallAnswer
                            if (_autoSpeak.value) {
                                voiceEngine.processAndSpeak(cleanResponse, enqueue = true)
                                alreadySpoken = true
                            }
                        }
                    }

                    // 2b. Check for explicit OKF Memory tool commands
                    if (cleanResponse.isBlank()) {
                        val memoryToolResponse = handleExplicitMemoryCommand(prompt)
                        if (memoryToolResponse != null) {
                            cleanResponse = memoryToolResponse
                            if (_autoSpeak.value) {
                                voiceEngine.processAndSpeak(cleanResponse, enqueue = true)
                                alreadySpoken = true
                            }
                        }
                    }

                    // 2c. Check for Google Maps business research queries
                    if (cleanResponse.isBlank() && isGoogleMapsQuery(prompt)) {
                        val mapsResponse = handleGoogleMapsQuery(prompt)
                        cleanResponse = mapsResponse
                        if (_autoSpeak.value) {
                            val shortSpoken = "Maine Google Maps par jaankari search kar li hai."
                            voiceEngine.processAndSpeak(shortSpoken, enqueue = false)
                            alreadySpoken = true
                        }
                    }
                }

                var apiContextStr = ""
                // 3. Intelligent Public API Orchestration
                if (!isFastChat && cleanResponse.isBlank()) {
                    val apiResult = apiSystem.processRequest(prompt)
                    if (apiResult is com.example.api.ApiExecutionResult.Success) {
                        apiContextStr = "API_RESULT: " + apiResult.data
                        // Let it fall through to Gemini LLM to format naturally
                    } else if (apiResult is com.example.api.ApiExecutionResult.Failure) {
                        // Let it fall through to LLM or TaskPlanner
                    } else if (apiResult is com.example.api.ApiExecutionResult.ToolRedirect) {
                        // Will be handled by Task Planner below
                    }
                }

                // 3b. Screen Understanding / Visual Q&A goes ONLY through online AI via JarvisGuidanceEngine / AI stream
                val jarvisMode = com.example.visual.JarvisIntelligencePlanner.classifyMode(prompt)
                var isJarvisScreenExplain = false
                if (!isFastChat && cleanResponse.isBlank() && (jarvisMode == com.example.visual.JarvisMode.EXPLAINS_ANYTHING || jarvisMode == com.example.visual.JarvisMode.SHOWS_YOU_HOW)) {
                    isJarvisScreenExplain = true
                    val freshObs = com.example.orchestrator.ScreenObservationCoordinator.captureFreshObservation(getApplication())
                    if (freshObs != null) {
                        com.example.visual.JarvisGuidanceEngine.explainActiveScreenAsync(getApplication(), prompt)
                    }
                }

                // 5. Fallback to Gemini AI streaming with semantic memory retrieval
                if (cleanResponse.isBlank()) {
                    val historyEntities = _messages.value
                        .filter { !it.isLoading && !it.isError && it.id != (userMsgEntity?.id ?: "") && it.id != loadingMsgId }
                        .map { MessageEntity(it.id, chatId, it.text, it.isUser, 0) }
                    
                    val screenContext = if (!isFastChat) screenInspector.getScreenContextString() else ""
                    val liveScreenFrame = if (_isScreenSharing.value || com.example.services.ScreenShareManager.isScreenSharingActive || isJarvisScreenExplain) {
                        com.example.visual.VisionPipeline.getCompressedJpegFrame() ?: lastAnalyzedImageBytes
                    } else {
                        lastAnalyzedImageBytes
                    }
                    
                    // Retrieve only top relevant memories (semantic & keyword scored)
                    val relevantMemories = if (!isFastChat) memoryEngine.retrieveRelevantMemories(prompt, contextEngine.activeTargetAppName) else emptyList()
                    var memoryBlock = relevantMemories.joinToString("\n") { "[${it.category} - ${it.key}]: ${it.content}" }
                    if (apiContextStr.isNotBlank()) {
                        memoryBlock += "\n\nEXTERNAL API DATA:\nThe user requested data that was just fetched from a live public API. Summarize the following API JSON data naturally and conversationally in a short sentence or two, as if you just looked it up:\n$apiContextStr"
                    }
                    
                    var fullResponse = ""
                    var streamErrorChunk = ""
                    val cleanResponseBuilder = StringBuilder()
                    val sentenceBuffer = StringBuilder()
                    var sentenceSpokenCount = 0
                    
                    try {
                        aiClient.streamChat(
                            prompt = prompt, 
                            history = historyEntities, 
                            screenContext = screenContext, 
                            memoryContext = memoryBlock,
                            isProactiveMode = false,
                            imageBytes = liveScreenFrame
                        ).collect { chunk ->
                            if (chunk.startsWith("Error:")) {
                                streamErrorChunk = chunk
                            } else {
                                fullResponse += chunk
                                val cleanChunk = voiceEngine.cleanText(chunk)
                                if (cleanChunk.isNotBlank()) {
                                    cleanResponseBuilder.append(cleanChunk)
                                    sentenceBuffer.append(cleanChunk)
                                }
                                val liveText = cleanResponseBuilder.toString().trim()
                                if (liveText.isNotBlank()) {
                                    _latestKavyaCaption.value = liveText
                                    _messages.value = _messages.value.map { msg ->
                                        if (msg.id == loadingMsgId) {
                                            msg.copy(text = liveText, isLoading = false)
                                        } else msg
                                    }
                                }

                                // Streamed chunk speech synthesis: queue sentences as they complete
                                if (_autoSpeak.value) {
                                    val currentBuffer = sentenceBuffer.toString()
                                    val sentenceEndIndex = currentBuffer.indexOfAny(charArrayOf('.', '?', '!', '\n', '।'))
                                    if (sentenceEndIndex != -1 && sentenceEndIndex >= 15) {
                                        val completeSentence = currentBuffer.substring(0, sentenceEndIndex + 1).trim()
                                        sentenceBuffer.delete(0, sentenceEndIndex + 1)
                                        if (completeSentence.isNotBlank()) {
                                            val shouldEnqueue = sentenceSpokenCount > 0
                                            voiceEngine.processAndSpeak(completeSentence, enqueue = shouldEnqueue)
                                            sentenceSpokenCount++
                                            alreadySpoken = true
                                        }
                                    }
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        val classified = com.example.ai.AiErrorClassifier.classifyThrowable(e)
                        streamErrorChunk = classified.toErrorPrefixString()
                        Log.w(TAG, "Streaming chat failed: ${classified.redactedLogSummary}")
                    }

                    // Flush any remaining partial sentence in sentenceBuffer
                    if (_autoSpeak.value && sentenceBuffer.isNotBlank() && sentenceSpokenCount > 0) {
                        val remaining = sentenceBuffer.toString().trim()
                        if (remaining.isNotBlank()) {
                            voiceEngine.processAndSpeak(remaining, enqueue = true)
                            sentenceBuffer.setLength(0)
                        }
                    }

                    // If streaming failed or was empty, fallback to non-streaming chat only if not a non-retryable auth/key/quota failure
                    val lastCat = com.example.ai.AiErrorClassifier.lastClassifiedError?.category
                    val isNonRetryableAuthOrQuota = lastCat in listOf(
                        com.example.ai.AiErrorCategory.MISSING_API_KEY,
                        com.example.ai.AiErrorCategory.INVALID_API_KEY,
                        com.example.ai.AiErrorCategory.AUTHENTICATION_FAILURE,
                        com.example.ai.AiErrorCategory.QUOTA_EXHAUSTED
                    )

                    if ((fullResponse.isBlank() || cleanResponseBuilder.isBlank()) && !isNonRetryableAuthOrQuota) {
                        try {
                            val chatResult = aiClient.chat(
                                prompt = prompt,
                                history = historyEntities,
                                screenContext = screenContext,
                                memoryContext = memoryBlock,
                                isProactiveMode = false,
                                imageBytes = liveScreenFrame
                            )
                            if (chatResult.startsWith("Error:")) {
                                streamErrorChunk = chatResult
                            } else {
                                fullResponse = chatResult
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            val classified = com.example.ai.AiErrorClassifier.classifyThrowable(e)
                            streamErrorChunk = classified.toErrorPrefixString()
                            Log.w(TAG, "Non-streaming chat fallback failed: ${classified.redactedLogSummary}")
                        }
                    }

                    // If blank, surface the exact classified AI error — NEVER invent local plans or silent offline fallbacks
                    if (fullResponse.isBlank()) {
                        fullResponse = if (streamErrorChunk.isNotBlank()) {
                            streamErrorChunk
                        } else {
                            com.example.ai.AiErrorClassifier.lastClassifiedError?.toErrorPrefixString()
                                ?: com.example.ai.AiClassifiedError(com.example.ai.AiErrorCategory.SERVICE_UNAVAILABLE).toErrorPrefixString()
                        }
                    }

                    val extractedClean = voiceEngine.cleanText(fullResponse)
                    cleanResponse = if (extractedClean.isNotBlank()) extractedClean else cleanResponseBuilder.toString().ifBlank { fullResponse }
                    if (isJarvisScreenExplain && cleanResponse.isNotBlank() && !cleanResponse.startsWith("Error:")) {
                        val bulletLines = cleanResponse.lines()
                            .map { it.trim().trimStart('•', '-', '*') }
                            .filter { it.length > 8 }
                            .take(3)
                        com.example.visual.JarvisGuidanceEngine.publishExplanation(
                            title = "Screen Visual Analysis",
                            summary = cleanResponse.take(180),
                            bulletPoints = bulletLines.ifEmpty { listOf("Real-time screen & vision analysis complete") },
                            details = cleanResponse,
                            confidence = 0.95f
                        )
                    }

                    // Auto-speak the AI response if not already spoken during streaming
                    if (cleanResponse.isNotBlank() && !alreadySpoken) {
                        voiceEngine.processAndSpeak(cleanResponse, enqueue = false)
                        alreadySpoken = true
                    }
                }
                
                cleanResponse = checkAndFilterRepetition(cleanResponse)
                memoryEngine.autoLearnTaskProgress(prompt, cleanResponse)
                
                val isErrorResponse = com.example.ai.AiErrorClassifier.isErrorText(cleanResponse) ||
                    cleanResponse.startsWith("Unable to connect") ||
                    cleanResponse.startsWith("Gemini API Key is not configured")
                com.example.state.KavyaStateManager.setGeminiState(if (isErrorResponse) "ERROR" else "SUCCESS")
                
                val modelMsgEntity = MessageEntity(
                    id = loadingMsgId,
                    chatId = chatId,
                    text = cleanResponse,
                    isUser = false,
                    timestamp = System.currentTimeMillis()
                )
                chatDao.insertMessage(modelMsgEntity)
                
                _messages.value = _messages.value.map { 
                    if (it.id == loadingMsgId) {
                        it.copy(
                            text = cleanResponse,
                            isLoading = false,
                            isError = isErrorResponse,
                            actionType = aType,
                            actionParam = aParam,
                            isAmbiguous = isAmbiguous,
                            candidateApps = candidateApps,
                            diagnostic = launchDiagnostic
                        )
                    } else it 
                }

                if (cleanResponse.isNotBlank()) {
                    _latestKavyaCaption.value = cleanResponse
                }
                
                if (shouldRecurse && recursionDepth < 5) {
                    processAgentLoop(chatId, null, "Action executed successfully. Inspect the new screen and decide the next step.", recursionDepth + 1)
                }
                
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                val classified = com.example.ai.AiErrorClassifier.classifyThrowable(e)
                val safeError = classified.userFacingText
                Log.w(TAG, "Agent turn failed: ${classified.redactedLogSummary}")
                _messages.value = _messages.value.map {
                    if (it.id == loadingMsgId) it.copy(text = safeError, isLoading = false, isError = true) else it
                }
                _latestKavyaCaption.value = safeError
                com.example.state.KavyaStateManager.setGeminiState("ERROR")
            } finally {
                if (!shouldRecurse || recursionDepth >= 5) {
                    _isProcessing.value = false
                    _agentActionStatus.value = null
                    _voiceState.value = VoiceState.IDLE
                    com.example.state.KavyaStateManager.updateVoiceState(VoiceState.IDLE)
                    proactiveController.onKavyaTurnFinished(cleanResponse)
                    val next = queuedPrompt
                    queuedPrompt = null
                    if (!next.isNullOrBlank()) {
                        viewModelScope.launch { sendMessage(next) }
                    }
                }
            }
        }
    }
    
    fun deleteCurrentChat() {
        val chatId = _currentChatId.value ?: return
        viewModelScope.launch {
            chatDao.deleteMessagesForChat(chatId)
            chatDao.deleteChatById(chatId)
            startNewChat()
        }
    }
    
    fun deleteChat(chatId: String) {
        viewModelScope.launch {
            chatDao.deleteMessagesForChat(chatId)
            chatDao.deleteChatById(chatId)
            if (_currentChatId.value == chatId) {
                startNewChat()
            }
        }
    }

    fun speakMessage(text: String) {
        voiceEngine.processAndSpeak(text)
    }

    fun stopSpeaking() {
        voiceEngine.stop()
    }

    private fun isGoogleMapsQuery(prompt: String): Boolean {
        val lower = prompt.lowercase(java.util.Locale.ROOT)
        val hasMapKeyword = lower.contains("google map") || lower.contains("google maps") || lower.contains("maps pe") || lower.contains("maps par")
        val hasBusinessSearch = (lower.contains("find") || lower.contains("search") || lower.contains("dhundo") || lower.contains("dhoondo") || lower.contains("batao") || lower.contains("nikalo")) &&
            (lower.contains("cafe") || lower.contains("restaurant") || lower.contains("dentist") || lower.contains("hospital") ||
             lower.contains("gym") || lower.contains("hotel") || lower.contains("bakery") || lower.contains("salon") ||
             lower.contains("pharmacy") || lower.contains("clinic") || lower.contains("school") || lower.contains("shop") || lower.contains("store")) &&
            (lower.contains(" in ") || lower.contains(" near ") || lower.contains(" me ") || lower.contains(" mein ") || lower.contains(" ke pass ") || lower.contains(" ke paas "))
        return hasMapKeyword || hasBusinessSearch
    }

    private suspend fun handleGoogleMapsQuery(prompt: String): String {
        val query = GoogleMapsQueryParser.parse(prompt)
        _agentActionStatus.value = "Searching Google Maps for ${query.toSearchTerm()}..."
        val result = mapsScraperClient.searchBusinesses(query)
        _agentActionStatus.value = null
        return when (result) {
            is ScrapeResult.Success -> {
                // Update persistent knowledge in OKF memory
                okfRepository.createKnowledge(
                    key = "last_maps_search",
                    content = "Last Google Maps search was for '${query.toSearchTerm()}' finding ${result.businesses.size} locations.",
                    category = OkfCategory.FACT,
                    importance = 3
                )
                
                val builder = StringBuilder()
                builder.append("📍 **Google Maps Results: ${query.toSearchTerm()}**\n\n")
                result.businesses.forEachIndexed { idx, biz ->
                    builder.append("${idx + 1}. **${biz.name}**")
                    if (biz.category.isNotBlank()) builder.append(" (${biz.category})")
                    if (biz.rating > 0) builder.append(" • ⭐ ${biz.rating} (${biz.reviewCount} reviews)")
                    builder.append("\n")
                    if (biz.address.isNotBlank()) builder.append("   🏢 ${biz.address}\n")
                    if (biz.phone.isNotBlank()) builder.append("   📞 ${biz.phone}\n")
                    if (biz.website.isNotBlank()) builder.append("   🌐 ${biz.website}\n")
                    builder.append("\n")
                }
                builder.toString().trim()
            }
            is ScrapeResult.EmptyResult -> {
                "Google Maps par '${result.query}' ke liye koi vyavsay (business) nahi mila."
            }
            is ScrapeResult.ServiceUnavailable -> {
                "Google Maps Scraper service abhi connect nahi ho pa rahi hai. Kripya sunishchit karein ki Docker container chal raha hai:\n`${result.serviceUrl}`\n\nCommand: `docker run -d -p 8080:8080 mahanaicoach/google-maps-scraper-kit`"
            }
            is ScrapeResult.Error -> {
                "Google Maps search me error aayi: ${result.message}"
            }
        }
    }

    private suspend fun handleExplicitMemoryCommand(prompt: String): String? {
        val lower = prompt.lowercase(java.util.Locale.ROOT).trim()
        if (lower.startsWith("memory search ") || lower.startsWith("search memory ")) {
            val q = prompt.substringAfter("memory ").substringAfter("search ").trim()
            return okfTools.memorySearch(q)
        }
        if (lower == "memory list" || lower == "list memory" || lower == "list memories") {
            return okfTools.memoryList()
        }
        return null
    }

    fun searchGoogleMaps(query: String, limit: Int = 15, onResult: ((ScrapeResult) -> Unit)? = null) {
        val parsed = GoogleMapsQueryParser.parse(query).copy(limit = limit)
        mapsJobManager.startSearchJob(parsed, onResult)
    }

    override fun onCleared() {
        super.onCleared()
        voiceEngine.stop()
        proactiveController.shutdown()
    }
}
