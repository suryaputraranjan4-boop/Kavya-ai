package com.example.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.agent.AndroidAgent
import com.example.agent.ContextEngine
import com.example.agent.DiagnosticEngine
import com.example.agent.MemoryEngine
import com.example.agent.ScreenInspector
import com.example.agent.TaskPlanner
import com.example.agent.UniversalActionType
import com.example.agent.VerificationEngine
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

    private val aiClient = KavyaAI(application)
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
                _voiceState.value = VoiceState.SPEAKING
                com.example.state.KavyaStateManager.updateVoiceState(VoiceState.SPEAKING)
            } else {
                if (_voiceState.value == VoiceState.SPEAKING) {
                    _voiceState.value = VoiceState.IDLE
                    com.example.state.KavyaStateManager.updateVoiceState(VoiceState.IDLE)
                }
            }
        },
        onAudioChunkStarted = { spokenChunk ->
            if (spokenChunk.isNotBlank()) {
                _latestKavyaCaption.value = spokenChunk
            }
        }
    )

    val voiceEngine = com.example.ai.KavyaVoiceEngine(application, geminiPlayer, aiClient)
    private val database = AppDatabase.getDatabase(application)
    private val chatDao = database.chatDao()
    val memoryDao = database.memoryDao()
    val commandRouter = CommandRouter(application)

    // Universal Agent & Memory Engines
    val memoryEngine = MemoryEngine(application)
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

    fun emergencyStop(reason: String = "User requested stop") {
        androidAgent.stopExecution(reason)
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

    private fun isVisualInteractionIntent(prompt: String): Boolean {
        val lower = prompt.lowercase(java.util.Locale.ROOT).trim()
        val visualKeywords = listOf(
            "br start", "cs ranked", "free fire", "freefire", "start button", "game start",
            "tap on", "click on", "dabaao", "dabao", "button दबाओ", "click करो", "tap करो",
            "screen पर", "screen par", "dhundo", "ढूंढो", "खोलो और", "kholo aur",
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

    private val _isMicMuted = MutableStateFlow(false)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

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

    private var messagesJob: kotlinx.coroutines.Job? = null

    fun setVoiceState(state: VoiceState) {
        _voiceState.value = state
        com.example.state.KavyaStateManager.updateVoiceState(state)
        proactiveController.onUserSpeechStateChanged(state == VoiceState.LISTENING)
        proactiveController.onKavyaSpeechStateChanged(state == VoiceState.SPEAKING)
    }

    private fun handleProactiveSpeech(text: String?, reason: String) {
        if (_isProcessing.value || _voiceState.value == VoiceState.SPEAKING || _voiceState.value == VoiceState.LISTENING) {
            return
        }
        val chatId = _currentChatId.value ?: return
        viewModelScope.launch {
            val generatedText = if (text.isNullOrBlank()) {
                generateDynamicProactiveSpeech(reason, chatId)
            } else {
                text
            }
            if (generatedText.isNullOrBlank()) return@launch

            val msgId = UUID.randomUUID().toString()
            val proactiveMsg = ChatMessage(id = msgId, text = generatedText, isUser = false)
            _messages.value = _messages.value + proactiveMsg
            chatDao.insertMessage(
                MessageEntity(
                    id = msgId,
                    chatId = chatId,
                    text = generatedText,
                    isUser = false,
                    timestamp = System.currentTimeMillis()
                )
            )
            _latestKavyaCaption.value = generatedText
            voiceEngine.processAndSpeak(generatedText, enqueue = false)
        }
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
        } catch (e: Exception) {
            Log.w(TAG, "Failed to generate dynamic proactive speech: ${e.message}")
            null
        }
    }

    fun toggleMicMute() {
        _isMicMuted.value = !_isMicMuted.value
    }

    fun setMicMuted(muted: Boolean) {
        _isMicMuted.value = muted
    }

    fun setAutoSpeak(enabled: Boolean) {
        _autoSpeak.value = enabled
    }

    fun setVoiceSettings(pitch: Float, rate: Float) {
        voiceEngine.setBaseVoiceProfile(pitch, rate)
    }

    fun toggleScreenSharing(enabled: Boolean? = null) {
        val target = enabled ?: !_isScreenSharing.value
        _isScreenSharing.value = target
        if (target) {
            // Keep microphone enabled automatically during screen share
            _isMicMuted.value = false
            refreshScreenContext()
            // Announce in natural, confident tone
            val chatId = _currentChatId.value
            if (chatId != null) {
                val announcement = "Screen sharing is active! I'm Kavya, and I can see what's on your screen. Just tell me what you'd like me to help with."
                val introMsg = ChatMessage(id = UUID.randomUUID().toString(), text = announcement, isUser = false)
                _messages.value = _messages.value + introMsg
                if (_autoSpeak.value) {
                    voiceEngine.speak(announcement, com.example.ai.Sentiment.WARM)
                }
            }
        } else {
            _screenContextText.value = null
        }
    }

    fun refreshScreenContext() {
        val liveContext = com.example.services.KavyaAccessibilityService.instance?.getScreenContext()
        _screenContextText.value = if (!liveContext.isNullOrBlank() && !liveContext.contains("Root node is null")) {
            liveContext
        } else {
            "Active Screen: Home / App Overview\nStatus: Screen Perception is active. (Enable Accessibility in Settings for deep UI tree perception)"
        }
    }

    fun analyzeScreenWithKavya(prompt: String = "What's on my screen right now? Please guide me.") {
        if (!_isScreenSharing.value) {
            toggleScreenSharing(true)
        } else {
            refreshScreenContext()
        }
        sendMessage(prompt)
    }

    fun analyzeScreen(prompt: String = "What's on my screen right now? Please guide me.") {
        analyzeScreenWithKavya(prompt)
    }

    init {
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

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        // Emergency Stop Voice Trigger (Hindi / English: stop, ruko, cancel, bas, etc.)
        if (visualActionEngine.safetyController.isStopCommand(text)) {
            emergencyStop("Voice stop command: $text")
            return
        }
        
        // Request Deduplication
        if (_isProcessing.value) {
            return
        }
        
        voiceEngine.stop() // Immediate barge-in stop
        proactiveController.onUserInteraction(text)
        
        val chatId = _currentChatId.value ?: return

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
        val chatId = _currentChatId.value ?: return
        val userMsgId = UUID.randomUUID().toString()
        val selectionText = "Open ${app.appName}"
        
        // Save preference as user alias
        commandRouter.appResolver.saveUserAlias(originalQuery, app.packageName)

        val userMsgEntity = MessageEntity(
            id = userMsgId,
            chatId = chatId,
            text = selectionText,
            isUser = true,
            timestamp = System.currentTimeMillis()
        )
        _messages.value = _messages.value + ChatMessage(id = userMsgId, text = selectionText, isUser = true)

        viewModelScope.launch {
            chatDao.insertMessage(userMsgEntity)
            val execResult = commandRouter.launchSpecificApp(app)
            _latestDiagnostic.value = execResult.diagnostic

            val modelMsgId = UUID.randomUUID().toString()
            val modelMsg = ChatMessage(
                id = modelMsgId,
                text = execResult.output,
                isUser = false,
                diagnostic = execResult.diagnostic
            )
            _messages.value = _messages.value + modelMsg
            chatDao.insertMessage(
                MessageEntity(
                    id = modelMsgId,
                    chatId = chatId,
                    text = execResult.output,
                    isUser = false,
                    timestamp = System.currentTimeMillis()
                )
            )

            // Add to history
            val historyItem = CommandHistoryItem(
                command = "OPEN_APP:${app.appName}",
                result = execResult.output,
                timestamp = System.currentTimeMillis(),
                diagnostic = execResult.diagnostic
            )
            _commandHistory.value = listOf(historyItem) + _commandHistory.value
        }
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
                        val derivedTitle = prompt.trim()
                            .take(28)
                            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
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

                // 0a. REMOVED Direct Device & App Launch bypass.
                // We now force all requests through the LLM for structured intent parsing.

                // 0b. Fast Local Assistant Engine (instant math, time, date, common greetings)
                if (cleanResponse.isBlank()) {
                    val fastLocal = com.example.ai.LocalAssistantEngine.handleFastQuery(prompt)
                    if (fastLocal != null) {
                        cleanResponse = fastLocal
                        if (_autoSpeak.value) {
                            voiceEngine.processAndSpeak(cleanResponse, enqueue = true)
                            alreadySpoken = true
                        }
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

                // 4. Kavya Visual Action Engine execution check
                if (!isFastChat && cleanResponse.isBlank() && isVisualInteractionIntent(prompt)) {
                    val visualOutcome = androidAgent.executeVisualGoal(prompt) { milestoneText ->
                        _agentActionStatus.value = milestoneText
                        _latestKavyaCaption.value = milestoneText
                        if (_autoSpeak.value) {
                            voiceEngine.processAndSpeak(milestoneText, enqueue = false)
                        }
                    }
                    _agentActionStatus.value = null
                    cleanResponse = visualOutcome.message
                    if (_autoSpeak.value && !alreadySpoken) {
                        voiceEngine.processAndSpeak(cleanResponse, enqueue = false)
                        alreadySpoken = true
                    }
                }

                // 5. Fallback to Gemini AI streaming with semantic memory retrieval
                if (cleanResponse.isBlank()) {
                    val historyEntities = _messages.value
                        .filter { !it.isLoading && !it.isError && it.id != (userMsgEntity?.id ?: "") && it.id != loadingMsgId }
                        .map { MessageEntity(it.id, chatId, it.text, it.isUser, 0) }
                    
                    val screenContext = if (!isFastChat) screenInspector.getScreenContextString() else ""
                    
                    // Retrieve only top relevant memories (semantic & keyword scored)
                    val relevantMemories = if (!isFastChat) memoryEngine.retrieveRelevantMemories(prompt, contextEngine.activeTargetAppName) else emptyList()
                    var memoryBlock = relevantMemories.joinToString("\n") { "[${it.category} - ${it.key}]: ${it.content}" }
                    if (apiContextStr.isNotBlank()) {
                        memoryBlock += "\n\nEXTERNAL API DATA:\nThe user requested data that was just fetched from a live public API. Summarize the following API JSON data naturally and conversationally in a short sentence or two, as if you just looked it up:\n$apiContextStr"
                    }
                    
                    var fullResponse = ""
                    val cleanResponseBuilder = StringBuilder()
                    val sentenceBuffer = StringBuilder()
                    var sentenceSpokenCount = 0
                    
                    try {
                        aiClient.streamChat(
                            prompt = prompt, 
                            history = historyEntities, 
                            screenContext = screenContext, 
                            memoryContext = memoryBlock,
                            isProactiveMode = false
                        ).collect { chunk ->
                            if (!chunk.startsWith("Error:")) {
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
                    } catch (e: Exception) {
                        Log.w(TAG, "Streaming chat exception: ${e.message}")
                    }

                    // Flush any remaining partial sentence in sentenceBuffer
                    if (_autoSpeak.value && sentenceBuffer.isNotBlank() && sentenceSpokenCount > 0) {
                        val remaining = sentenceBuffer.toString().trim()
                        if (remaining.isNotBlank()) {
                            voiceEngine.processAndSpeak(remaining, enqueue = true)
                            sentenceBuffer.setLength(0)
                        }
                    }

                    // If streaming failed or was empty, fallback to non-streaming chat
                    if (fullResponse.isBlank() || cleanResponseBuilder.isBlank()) {
                        try {
                            fullResponse = aiClient.chat(
                                prompt = prompt,
                                history = historyEntities,
                                screenContext = screenContext,
                                memoryContext = memoryBlock,
                                isProactiveMode = false
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Non-streaming chat fallback exception: ${e.message}")
                        }
                    }

                    // If still blank, use pleasant default response
                    if (fullResponse.isBlank()) {
                        fullResponse = "AI network abhi busy hai, kripya thodi der baad punah prayas karein."
                    }

                    // Parse structured actions (JSON schema or action tags)
                    val structuredActions = com.example.agent.StructuredActionParser.parseActions(fullResponse)
                    val extractedClean = voiceEngine.cleanText(fullResponse)
                    cleanResponse = if (extractedClean.isNotBlank()) extractedClean else cleanResponseBuilder.toString()
                    
                    if (structuredActions.isNotEmpty()) {
                        val taskSteps = structuredActions.mapIndexed { index, action ->
                            val mappedAction = when (action.action) {
                                "OPEN_APP", "OPEN" -> com.example.agent.UniversalActionType.OPEN_APP
                                "CLOSE_APP" -> com.example.agent.UniversalActionType.CLOSE_APP
                                "SEARCH", "OPEN_AND_SEARCH", "SEARCH_WEB" -> com.example.agent.UniversalActionType.SEARCH
                                "TAP", "UI_CLICK", "CLICK" -> com.example.agent.UniversalActionType.TAP
                                "TYPE", "UI_TYPE", "TYPE_TEXT" -> com.example.agent.UniversalActionType.TYPE
                                "MAKE_PHONE_CALL", "CALL" -> com.example.agent.UniversalActionType.MAKE_PHONE_CALL
                                "MAKE_WHATSAPP_CALL" -> com.example.agent.UniversalActionType.MAKE_WHATSAPP_CALL
                                "SEND_SMS", "SMS" -> com.example.agent.UniversalActionType.SEND_SMS
                                "SEND_WHATSAPP_MESSAGE" -> com.example.agent.UniversalActionType.SEND_WHATSAPP_MESSAGE
                                "SEND_EMAIL" -> com.example.agent.UniversalActionType.SEND_EMAIL
                                "CREATE_FOLDER" -> com.example.agent.UniversalActionType.CREATE_FOLDER
                                "CREATE_FILE" -> com.example.agent.UniversalActionType.CREATE_FILE
                                "DELETE_FILE" -> com.example.agent.UniversalActionType.DELETE_FILE
                                "SELECT_RESULT", "SELECT" -> com.example.agent.UniversalActionType.SELECT_RESULT
                                "PLAY" -> com.example.agent.UniversalActionType.PLAY
                                "SUBMIT" -> com.example.agent.UniversalActionType.SUBMIT
                                "CLEAR", "CLEAR_TEXT" -> com.example.agent.UniversalActionType.CLEAR_TEXT
                                "READ_SCREEN" -> com.example.agent.UniversalActionType.READ_SCREEN
                                "SCREENSHOT" -> com.example.agent.UniversalActionType.SCREENSHOT
                                "DOWNLOAD_FILE" -> com.example.agent.UniversalActionType.DOWNLOAD_FILE
                                "SWITCH_APP" -> com.example.agent.UniversalActionType.SWITCH_APP
                                "ANALYZE_IMAGE" -> com.example.agent.UniversalActionType.ANALYZE_IMAGE
                                "RESEARCH_WEB" -> com.example.agent.UniversalActionType.RESEARCH_WEB
                                "GENERATE_IMAGE" -> com.example.agent.UniversalActionType.GENERATE_IMAGE
                                "SEMANTIC_SEARCH" -> com.example.agent.UniversalActionType.SEMANTIC_SEARCH
                                "UI_SCROLL", "SCROLL" -> com.example.agent.UniversalActionType.SCROLL
                                "SWIPE" -> com.example.agent.UniversalActionType.SWIPE
                                "BACK" -> com.example.agent.UniversalActionType.BACK
                                "HOME" -> com.example.agent.UniversalActionType.HOME
                                "NAVIGATE" -> com.example.agent.UniversalActionType.NAVIGATE
                                "SYSTEM_CONTROL" -> com.example.agent.UniversalActionType.SYSTEM_CONTROL
                                "DONE" -> com.example.agent.UniversalActionType.VERIFY
                                "GLOBAL_ACTION" -> {
                                    val globalTarget = action.target.ifBlank { action.rawParam }.uppercase()
                                    when (globalTarget) {
                                        "BACK" -> com.example.agent.UniversalActionType.BACK
                                        "HOME" -> com.example.agent.UniversalActionType.HOME
                                        "RECENTS" -> com.example.agent.UniversalActionType.SWITCH_APP
                                        else -> com.example.agent.UniversalActionType.SYSTEM_CONTROL
                                    }
                                }
                                "SAVE_MEMORY" -> com.example.agent.UniversalActionType.SAVE_MEMORY
                                "GOOGLE_MAPS_SEARCH", "MAPS_SEARCH" -> com.example.agent.UniversalActionType.GOOGLE_MAPS_SEARCH
                                "MEMORY_TOOL", "MEMORY_SEARCH" -> com.example.agent.UniversalActionType.MEMORY_TOOL
                                else -> com.example.agent.UniversalActionType.SYSTEM_CONTROL
                            }

                            val paramStr = if (action.query.isNotBlank()) action.query else if (action.text.isNotBlank()) action.text else if (action.selector.isNotBlank()) action.selector else action.rawParam.ifBlank { action.target }

                            com.example.agent.TaskStep(
                                id = index + 1,
                                actionType = mappedAction,
                                targetAppOrUrl = action.target,
                                param = paramStr,
                                recipient = action.recipient,
                                messageText = action.message,
                                ordinalIndex = action.index,
                                spokenAnnouncement = action.spokenMessage
                            )
                        }

                        val taskPlan = com.example.agent.TaskPlan(
                            originalPrompt = prompt,
                            targetAppName = structuredActions.firstOrNull()?.target ?: "",
                            steps = taskSteps,
                            isMultiStep = taskSteps.size > 1
                        )

                        val sensitiveStep = taskSteps.firstOrNull {
                            it.actionType in listOf(
                                com.example.agent.UniversalActionType.CALL,
                                com.example.agent.UniversalActionType.MAKE_PHONE_CALL,
                                com.example.agent.UniversalActionType.MAKE_WHATSAPP_CALL,
                                com.example.agent.UniversalActionType.SEND_SMS,
                                com.example.agent.UniversalActionType.SEND_WHATSAPP_MESSAGE,
                                com.example.agent.UniversalActionType.SEND_EMAIL,
                                com.example.agent.UniversalActionType.DELETE_FILE
                            )
                        }

                        val executePlan: suspend () -> Unit = {
                            val outcome = androidAgent.executeTaskPlan(
                                taskPlan,
                                onSpeakProgress = { spokenAnnouncement ->
                                    _agentActionStatus.value = spokenAnnouncement
                                    if (_autoSpeak.value) {
                                        voiceEngine.processAndSpeak(spokenAnnouncement, enqueue = false)
                                        alreadySpoken = true
                                    }
                                }
                            )
                            _agentActionStatus.value = null
                            
                            if (!outcome.success && outcome.finalSpokenMessage.isNotBlank()) {
                                cleanResponse = outcome.finalSpokenMessage
                            } else if (outcome.success) {
                                val hasDone = taskSteps.any { it.actionType == com.example.agent.UniversalActionType.VERIFY }
                                if (!hasDone) {
                                    shouldRecurse = true
                                }
                            }
                            isAmbiguous = outcome.isAmbiguous
                            candidateApps = outcome.candidateApps
                            launchDiagnostic = outcome.diagnostics.lastOrNull()
                            executionResultStr = outcome.finalSpokenMessage
                            aType = "TASK_PLAN"
                            aParam = taskPlan.targetAppName
                            
                            if (launchDiagnostic != null) {
                                _latestDiagnostic.value = launchDiagnostic
                            }
                            
                            val historyItem = CommandHistoryItem(
                                command = "TASK_PLAN:${taskPlan.targetAppName}",
                                result = executionResultStr,
                                timestamp = System.currentTimeMillis(),
                                diagnostic = launchDiagnostic
                            )
                            _commandHistory.value = listOf(historyItem) + _commandHistory.value
                        }

                        if (sensitiveStep != null) {
                            val actionDesc = when (sensitiveStep.actionType) {
                                com.example.agent.UniversalActionType.CALL,
                                com.example.agent.UniversalActionType.MAKE_PHONE_CALL -> "Phone call to ${sensitiveStep.param.ifBlank { sensitiveStep.targetAppOrUrl }}"
                                com.example.agent.UniversalActionType.MAKE_WHATSAPP_CALL -> "WhatsApp call to ${sensitiveStep.param.ifBlank { sensitiveStep.targetAppOrUrl }}"
                                com.example.agent.UniversalActionType.SEND_SMS -> "Send SMS to ${sensitiveStep.recipient.ifBlank { sensitiveStep.param }}"
                                com.example.agent.UniversalActionType.SEND_WHATSAPP_MESSAGE -> "Send WhatsApp message to ${sensitiveStep.recipient.ifBlank { sensitiveStep.param }}"
                                com.example.agent.UniversalActionType.SEND_EMAIL -> "Send Email to ${sensitiveStep.recipient.ifBlank { sensitiveStep.param }}"
                                com.example.agent.UniversalActionType.DELETE_FILE -> "Delete file ${sensitiveStep.param}"
                                else -> "Sensitive operation: ${sensitiveStep.actionType.name}"
                            }
                            _pendingConfirmation.value = PendingConfirmation(
                                title = "Security Confirmation",
                                description = "Kavya is requesting permission to perform:\n$actionDesc\nDo you want to proceed?",
                                actionType = sensitiveStep.actionType,
                                onConfirm = executePlan,
                                onCancel = {
                                    _pendingConfirmation.value = null
                                }
                            )
                        } else {
                            executePlan()
                        }
                    } else {
                        // Fallback check: Did taskPlanner detect a real automation intent?
                        val fallbackPlan = taskPlanner.createPlan(prompt, contextEngine)
                        if (fallbackPlan != null && fallbackPlan.steps.isNotEmpty()) {
                            val outcome = androidAgent.executeTaskPlan(
                                fallbackPlan,
                                onSpeakProgress = { spokenAnnouncement ->
                                    _agentActionStatus.value = spokenAnnouncement
                                    if (_autoSpeak.value) {
                                        voiceEngine.processAndSpeak(spokenAnnouncement, enqueue = false)
                                        alreadySpoken = true
                                    }
                                }
                            )
                            _agentActionStatus.value = null
                            if (outcome.finalSpokenMessage.isNotBlank()) {
                                cleanResponse = outcome.finalSpokenMessage
                            }
                        }
                    }

                    // Auto-speak the AI response if not already spoken during streaming
                    if (cleanResponse.isNotBlank() && !alreadySpoken) {
                        voiceEngine.processAndSpeak(cleanResponse, enqueue = false)
                        alreadySpoken = true
                    }
                }
                
                val isErrorResponse = cleanResponse.startsWith("Unable to connect") || cleanResponse.startsWith("Gemini API Key is not configured")
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
                
            } catch (e: Exception) {
                _messages.value = _messages.value.map { 
                    if (it.id == loadingMsgId) it.copy(text = "Connection error: ${e.message}", isLoading = false, isError = true) else it 
                }
            } finally {
                if (!shouldRecurse || recursionDepth >= 5) {
                    _isProcessing.value = false
                    proactiveController.onKavyaTurnFinished(cleanResponse)
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
