package com.example.viewmodel

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
                microphoneEngine.resumeAfterTts(350L)
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
            _latestKavyaCaption.value = "सुन रही हूँ... (Listening...)"
            _voiceState.value = VoiceState.LISTENING
            com.example.state.KavyaStateManager.updateVoiceState(VoiceState.LISTENING)
        }
    )

    val voiceEngine = com.example.ai.KavyaVoiceEngine(application, geminiPlayer, KavyaAI(application))
    val microphoneEngine = com.example.agent.MicrophoneEngine.getInstance(application)
    val micAmplitude: StateFlow<Float> = microphoneEngine.amplitude
    val micEngineState: StateFlow<com.example.agent.MicrophoneState> = microphoneEngine.micState
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

    private val recentAssistantResponses = mutableListOf<String>()

    private fun checkAndFilterRepetition(response: String): String {
        val trimmed = response.trim()
        if (trimmed.isBlank()) return trimmed
        if (recentAssistantResponses.any { it.equals(trimmed, ignoreCase = true) }) {
            val varied = "$trimmed (Let's proceed to the next step or explore a different aspect of this task.)"
            recentAssistantResponses.add(varied)
            if (recentAssistantResponses.size > 5) recentAssistantResponses.removeAt(0)
            return varied
        }
        recentAssistantResponses.add(trimmed)
        if (recentAssistantResponses.size > 5) recentAssistantResponses.removeAt(0)
        return trimmed
    }

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
            com.example.state.KavyaStateManager.state.collect { global ->
                _voiceState.value = global.voiceState
            }
        }

        viewModelScope.launch {
            try {
                schedulerEngine.executeDueSchedules { dueTask ->
                    sendMessage(dueTask.promptOrAction)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error executing due schedules: ${e.message}")
            }
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

    val isMicMuted: StateFlow<Boolean> = microphoneEngine.isMuted

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
        microphoneEngine.toggleMute()
    }

    fun setMicMuted(muted: Boolean) {
        microphoneEngine.setMuted(muted)
    }

    fun startVoiceInput() {
        if (_isProcessing.value) return
        voiceEngine.stop()

        val app = getApplication<Application>()
        val hasPerm = com.example.utils.PermissionsManager.hasRecordAudioPermission(app)
        if (!hasPerm) {
            Log.e(TAG, "MIC_PERMISSION_DENIED: Cannot start voice input without RECORD_AUDIO")
            _voiceState.value = VoiceState.ERROR
            com.example.state.KavyaStateManager.updateVoiceState(VoiceState.ERROR)
            _latestKavyaCaption.value = "Microphone permission required"
            viewModelScope.launch {
                delay(3000)
                if (_voiceState.value == VoiceState.ERROR) {
                    _voiceState.value = VoiceState.IDLE
                    com.example.state.KavyaStateManager.updateVoiceState(VoiceState.IDLE)
                    _latestKavyaCaption.value = null
                }
            }
            return
        }

        // Start Foreground Service before backgrounding to comply with Android 14 restrictions
        if (com.example.utils.AppPreferences.isBackgroundVoiceEnabled(app)) {
            try {
                val serviceIntent = Intent(app, com.example.services.KavyaVoiceService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    androidx.core.content.ContextCompat.startForegroundService(app, serviceIntent)
                } else {
                    app.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not start KavyaVoiceService: ${e.message}")
            }
        }

        Log.d(TAG, "MIC_INITIALIZING: User initiated continuous hands-free voice input session")
        _latestKavyaCaption.value = "Starting microphone..."

        microphoneEngine.startContinuousListening(
            onListeningStarted = {
                // Audio capture is confirmed active and Android green privacy indicator is visible
                viewModelScope.launch(Dispatchers.Main) {
                    Log.d(TAG, "MIC_STARTED: Continuous microphone active, listening for speech")
                    _voiceState.value = VoiceState.LISTENING
                    com.example.state.KavyaStateManager.updateVoiceState(VoiceState.LISTENING)
                    _latestKavyaCaption.value = "सुन रही हूँ... (Listening...)"
                }
            },
            onPartialResult = { partial ->
                viewModelScope.launch(Dispatchers.Main) {
                    if (partial.isNotBlank()) {
                        bargeInController.onUserSpeechDetected()
                        _latestKavyaCaption.value = partial
                    }
                }
            },
            onResult = { recognizedText ->
                viewModelScope.launch {
                    Log.d(TAG, "STT_RESULT: Candidates received, text=\"$recognizedText\"")
                    if (recognizedText.isNotBlank()) {
                        _voiceState.value = VoiceState.THINKING
                        com.example.state.KavyaStateManager.updateVoiceState(VoiceState.THINKING)
                        _latestKavyaCaption.value = recognizedText

                        if (com.example.agent.SleepWakeDetector.isSleepCommand(recognizedText)) {
                            Log.d(TAG, "SLEEP_COMMAND: Putting Kavya to sleep")
                            com.example.utils.AppPreferences.setKavyaState(app, "SLEEP")
                            stopVoiceInput()
                            _latestKavyaCaption.value = "Kavya sleeping."
                            microphoneEngine.pauseForTts()
                            voiceEngine.processAndSpeak("Going to sleep. Say 'Wake Kavya' whenever you need me.")
                            return@launch
                        }

                        if (com.example.agent.SleepWakeDetector.isWakeCommand(recognizedText)) {
                            Log.d(TAG, "WAKE_COMMAND: Waking Kavya up")
                            com.example.utils.AppPreferences.setKavyaState(app, "ACTIVE")
                            _latestKavyaCaption.value = "Kavya awake."
                            microphoneEngine.pauseForTts()
                            voiceEngine.processAndSpeak("Kavya is awake and listening! How can I help you?")
                            delay(350)
                            microphoneEngine.resumeAfterTts(350L)
                            return@launch
                        }

                        val lower = recognizedText.lowercase().trim()
                        if (lower == "stop" || lower == "ruko" || lower == "cancel" || lower == "bas" ||
                            lower.contains("stop kavya") || lower.contains("kavya stop") ||
                            lower.contains("kavya chup") || lower.contains("chup ho jao")
                        ) {
                            Log.d(TAG, "STOP_COMMAND: User stopped Kavya listening")
                            stopVoiceInput()
                            _latestKavyaCaption.value = "Kavya stopped."
                            return@launch
                        }

                        sendMessage(recognizedText)
                    } else {
                        Log.d(TAG, "STT_EMPTY: No speech detected in audio window")
                        _voiceState.value = VoiceState.IDLE
                        com.example.state.KavyaStateManager.updateVoiceState(VoiceState.IDLE)
                        _latestKavyaCaption.value = null
                    }
                }
            },
            onError = { error ->
                Log.w(TAG, "STT_ERROR: $error")
                viewModelScope.launch(Dispatchers.Main) {
                    _voiceState.value = VoiceState.ERROR
                    com.example.state.KavyaStateManager.updateVoiceState(VoiceState.ERROR)
                    _latestKavyaCaption.value = "आवाज़ सुनाई नहीं दी। फिर से बोलें।"
                    delay(2500)
                    if (_voiceState.value == VoiceState.ERROR) {
                        _voiceState.value = VoiceState.IDLE
                        com.example.state.KavyaStateManager.updateVoiceState(VoiceState.IDLE)
                        _latestKavyaCaption.value = null
                    }
                }
            }
        )
    }

    fun stopVoiceInput() {
        Log.d(TAG, "MIC_STOPPING: Stopping microphone capture")
        microphoneEngine.stopListening()
        _voiceState.value = VoiceState.IDLE
        com.example.state.KavyaStateManager.updateVoiceState(VoiceState.IDLE)
        _latestKavyaCaption.value = null
    }

    fun toggleVoiceInput() {
        if (_voiceState.value == VoiceState.SPEAKING) {
            voiceEngine.stop()
            _voiceState.value = VoiceState.IDLE
            com.example.state.KavyaStateManager.updateVoiceState(VoiceState.IDLE)
            return
        }

        val app = getApplication<Application>()
        val hasPerm = com.example.utils.PermissionsManager.hasRecordAudioPermission(app)
        if (!hasPerm) {
            _voiceState.value = VoiceState.ERROR
            com.example.state.KavyaStateManager.updateVoiceState(VoiceState.ERROR)
            _latestKavyaCaption.value = "Microphone permission required"
            return
        }

        val isCurrentlyListening = _voiceState.value == VoiceState.LISTENING ||
                microphoneEngine.micState.value == com.example.agent.MicrophoneState.LISTENING ||
                microphoneEngine.micState.value == com.example.agent.MicrophoneState.STARTING

        if (isCurrentlyListening) {
            stopVoiceInput()
        } else {
            startVoiceInput()
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
        val target = enabled ?: !_isScreenSharing.value
        if (!target) {
            stopScreenShare()
        } else {
            // Request system consent through UI launcher
            _isScreenSharing.value = true
            microphoneEngine.setMuted(false)
            refreshScreenContext()
        }
    }

    fun refreshScreenContext() {
        val liveContext = com.example.services.KavyaAccessibilityService.instance?.getScreenContext()
        val perceptionSnapshot = com.example.visual.VisionPipeline.getUnifiedPerceptionSnapshot()
        _screenContextText.value = if (!liveContext.isNullOrBlank() && !liveContext.contains("Root node is null")) {
            if (perceptionSnapshot.isVisualCaptureActive) {
                "$liveContext\n[Vision: Real-time screen capture feed active]"
            } else {
                liveContext
            }
        } else {
            "Active Screen: Home / App Overview\nStatus: Screen Perception is active."
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
            com.example.services.ScreenShareManager.state.collect { state ->
                when (state) {
                    is com.example.services.ScreenShareManager.ScreenShareState.Active -> {
                        _isScreenSharing.value = true
                        microphoneEngine.setMuted(false)
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
                    else -> {}
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
                if (gateDecision.category == com.example.agent.IntentCategory.STOP || gateDecision.category == com.example.agent.IntentCategory.CANCEL) {
                    com.example.agent.TaskStateMachine.update(
                        phase = com.example.agent.TaskPhase.INTERRUPTED,
                        lastAction = "STOP",
                        actionResult = "User stopped automation"
                    )
                    com.example.agent.AutomationEventLogger.task("Automation halted by user request.")
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
                    microphoneEngine.stopListening()
                    cleanResponse = "Good night, sir. Going to sleep."
                    if (_autoSpeak.value) {
                        voiceEngine.processAndSpeak(cleanResponse, enqueue = false)
                        alreadySpoken = true
                    }
                } else if (gateDecision.category == com.example.agent.IntentCategory.SCHEDULE) {
                    val skill = com.example.skills.SkillsRegistry.findSkillForIntent(gateDecision)
                    val res = skill?.execute(gateDecision, getApplication())
                    cleanResponse = res?.outputMessage ?: "Scheduled."
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
                } else if (gateDecision.category == com.example.agent.IntentCategory.UNKNOWN_OR_AMBIGUOUS && gateDecision.isAmbiguous) {
                    isAmbiguous = true
                    candidateApps = gateDecision.candidateApps
                    cleanResponse = "Which app would you like to open? Found: " + candidateApps.joinToString(", ") { it.appName }
                    if (_autoSpeak.value) {
                        voiceEngine.processAndSpeak(cleanResponse, enqueue = false)
                        alreadySpoken = true
                    }
                }

                // 0b. Primary Source: Direct User Command Classification (only for explicit actions)
                val classified = if (!isConversationalTurn) {
                    UserCommandClassifier.classify(prompt, commandRouter.appResolver)
                } else {
                    ClassifiedCommand(CommandCategory.CONVERSATION, rawPrompt = prompt)
                }

                if (!isConversationalTurn && cleanResponse.isBlank() && classified.isMultiStep && classified.steps.isNotEmpty()) {
                    viewModelScope.launch(Dispatchers.IO) {
                        durableTaskEngine.createAndPersistTask(prompt, gateDecision.category, gateDecision.steps)
                    }
                    val firstStep = classified.steps[0]
                    val secondStep = classified.steps.getOrNull(1)

                    val appTarget = classified.targetApp ?: firstStep.targetApp ?: ""
                    val taskCtx = ActionEventBus.startNewTask(prompt, CommandCategory.OPEN_APP, appTarget)
                    ActionEventBus.transitionTo(taskCtx, ExecutionPhase.PARSED, ActionEvent.TaskParsed(taskCtx))
                    ActionEventBus.transitionTo(taskCtx, ExecutionPhase.PLANNED, ActionEvent.TaskPlanned(taskCtx, classified.steps.size))

                    com.example.agent.TaskStateMachine.update(
                        phase = com.example.agent.TaskPhase.APP_RESOLUTION,
                        currentApp = appTarget,
                        stepIndex = 1,
                        totalSteps = classified.steps.size
                    )

                    val resolution = commandRouter.appResolver.resolve(appTarget)
                    if (resolution.confidence == com.example.utils.MatchConfidence.NONE) {
                        val isHindiOrHinglish = prompt.any { it in '\u0900'..'\u097F' } || prompt.lowercase(java.util.Locale.ROOT).let {
                            it.contains("kholo") || it.contains("khol") || it.contains("chalao") || it.contains("jao") || it.contains("open")
                        }
                        cleanResponse = if (isHindiOrHinglish) "मुझे '$appTarget' app नहीं मिला।" else "I couldn't find the '$appTarget' app."
                        ActionEventBus.transitionTo(taskCtx, ExecutionPhase.FAILED, ActionEvent.ActionFailed(taskCtx, "App not installed", cleanResponse))
                        com.example.agent.TaskStateMachine.update(phase = com.example.agent.TaskPhase.FAILED, lastError = cleanResponse)
                        com.example.agent.AutomationEventLogger.error("App not found: $appTarget")
                    } else {
                        val matched = resolution.matchedApp!!
                        com.example.agent.TaskStateMachine.update(
                            phase = com.example.agent.TaskPhase.APP_OPENING,
                            currentApp = matched.appName,
                            currentStep = "Launching ${matched.appName}"
                        )
                        com.example.agent.AutomationEventLogger.app("Launching ${matched.appName} (${matched.packageName})")

                        val launchResult = commandRouter.launchSpecificApp(matched)
                        launchDiagnostic = launchResult.diagnostic

                        if (!launchResult.success) {
                            cleanResponse = launchResult.output
                            ActionEventBus.transitionTo(taskCtx, ExecutionPhase.FAILED, ActionEvent.ActionFailed(taskCtx, "Launch failed", cleanResponse))
                            com.example.agent.TaskStateMachine.update(phase = com.example.agent.TaskPhase.FAILED, lastError = cleanResponse)
                            com.example.agent.AutomationEventLogger.error("Launch failed: $cleanResponse")
                        } else {
                            com.example.agent.AutomationEventLogger.verify("Verified app in foreground: ${matched.appName}")
                            delay(400)
                            if (secondStep != null) {
                                com.example.agent.TaskStateMachine.update(
                                    phase = com.example.agent.TaskPhase.ACTION_EXECUTION,
                                    stepIndex = 2,
                                    currentStep = secondStep.category.name
                                )
                                com.example.agent.AutomationEventLogger.action("Executing step 2: ${secondStep.category}")
                                var step2Success = true
                                when (secondStep.category) {
                                    CommandCategory.PLAY_MEDIA -> {
                                        val mediaQuery = secondStep.query ?: secondStep.param ?: ""
                                        val mediaResult = commandRouter.handleCompoundCommand(com.example.utils.AppResolver.CompoundCommand(appTarget, mediaQuery, "PLAY"))
                                        step2Success = mediaResult.success
                                        cleanResponse = if (mediaResult.success) {
                                            "$appTarget par $mediaQuery play kar diya hai."
                                        } else {
                                            mediaResult.output
                                        }
                                        com.example.agent.TaskStateMachine.update(phase = if (mediaResult.success) com.example.agent.TaskPhase.COMPLETED else com.example.agent.TaskPhase.FAILED, actionResult = cleanResponse)
                                        com.example.agent.AutomationEventLogger.verify(cleanResponse)
                                    }
                                    CommandCategory.SEARCH_IN_APP -> {
                                        val searchQ = secondStep.query ?: secondStep.param ?: ""
                                        val searchResult = commandRouter.handleCompoundCommand(com.example.utils.AppResolver.CompoundCommand(appTarget, searchQ, "SEARCH"))
                                        step2Success = searchResult.success
                                        cleanResponse = if (searchResult.success) {
                                            "$appTarget par $searchQ search kar diya hai."
                                        } else {
                                            searchResult.output
                                        }
                                        com.example.agent.TaskStateMachine.update(phase = if (searchResult.success) com.example.agent.TaskPhase.COMPLETED else com.example.agent.TaskPhase.FAILED, actionResult = cleanResponse)
                                        com.example.agent.AutomationEventLogger.verify(cleanResponse)
                                    }
                                    CommandCategory.SEND_MESSAGE -> {
                                        val rawParam = secondStep.param ?: ""
                                        val stepObj = com.example.agent.TaskStep(
                                            id = 2,
                                            actionType = if (appTarget.contains("WhatsApp", ignoreCase = true)) com.example.agent.UniversalActionType.SEND_WHATSAPP_MESSAGE else com.example.agent.UniversalActionType.SEND_MESSAGE,
                                            targetAppOrUrl = appTarget,
                                            param = rawParam
                                        )
                                        val agentRes = androidAgent.executeAtomicStep(stepObj)
                                        step2Success = agentRes.success
                                        cleanResponse = agentRes.output
                                        com.example.agent.TaskStateMachine.update(phase = if (agentRes.success) com.example.agent.TaskPhase.COMPLETED else com.example.agent.TaskPhase.FAILED, actionResult = cleanResponse)
                                        com.example.agent.AutomationEventLogger.verify(cleanResponse)
                                    }
                                    CommandCategory.CALL -> {
                                        val rawParam = secondStep.param ?: ""
                                        val stepObj = com.example.agent.TaskStep(
                                            id = 2,
                                            actionType = if (appTarget.contains("WhatsApp", ignoreCase = true)) com.example.agent.UniversalActionType.MAKE_WHATSAPP_CALL else com.example.agent.UniversalActionType.MAKE_PHONE_CALL,
                                            targetAppOrUrl = appTarget,
                                            param = rawParam
                                        )
                                        val agentRes = androidAgent.executeAtomicStep(stepObj)
                                        step2Success = agentRes.success
                                        cleanResponse = agentRes.output
                                        com.example.agent.TaskStateMachine.update(phase = if (agentRes.success) com.example.agent.TaskPhase.COMPLETED else com.example.agent.TaskPhase.FAILED, actionResult = cleanResponse)
                                        com.example.agent.AutomationEventLogger.verify(cleanResponse)
                                    }
                                    CommandCategory.INTERACT_IN_APP -> {
                                        val rawParam = secondStep.param ?: ""
                                        val isOrdinalFirst = rawParam.contains("first", ignoreCase = true) || rawParam.contains("pehla", ignoreCase = true) || rawParam.contains("1st", ignoreCase = true)
                                        val tapResult = if (isOrdinalFirst) {
                                            TapEngine.tapFirstResult(KavyaAccessibilityService.instance, matched.packageName)
                                        } else {
                                            TapEngine.tapElement(KavyaAccessibilityService.instance, matched.packageName, rawParam)
                                        }
                                        step2Success = tapResult.success
                                        cleanResponse = if (tapResult.success) {
                                            "$appTarget par '${tapResult.targetLabel}' select kar diya hai."
                                        } else {
                                            "$appTarget open ho gaya hai, lekin target locate nahi ho paya."
                                        }
                                        com.example.agent.TaskStateMachine.update(phase = if (step2Success) com.example.agent.TaskPhase.COMPLETED else com.example.agent.TaskPhase.FAILED, actionResult = cleanResponse)
                                        com.example.agent.AutomationEventLogger.verify(cleanResponse)
                                    }
                                    else -> {
                                        cleanResponse = launchResult.output
                                    }
                                }
                                if (step2Success) {
                                    ActionEventBus.transitionTo(taskCtx, ExecutionPhase.SUCCESS, ActionEvent.ActionSuccess(taskCtx, appTarget, cleanResponse))
                                } else {
                                    ActionEventBus.transitionTo(taskCtx, ExecutionPhase.FAILED, ActionEvent.ActionFailed(taskCtx, "Step 2 failed", cleanResponse))
                                }
                            } else {
                                cleanResponse = launchResult.output
                                ActionEventBus.transitionTo(taskCtx, ExecutionPhase.SUCCESS, ActionEvent.ActionSuccess(taskCtx, appTarget, cleanResponse))
                            }
                        }
                    }
                    if (_autoSpeak.value) {
                        voiceEngine.processAndSpeak(cleanResponse, enqueue = false)
                        alreadySpoken = true
                    }
                } else if (classified.category == CommandCategory.OPEN_APP && !classified.targetApp.isNullOrBlank()) {
                    val appTarget = classified.targetApp
                    val taskCtx = ActionEventBus.startNewTask(prompt, CommandCategory.OPEN_APP, appTarget)
                    ActionEventBus.transitionTo(taskCtx, ExecutionPhase.PARSED, ActionEvent.TaskParsed(taskCtx))
                    ActionEventBus.transitionTo(taskCtx, ExecutionPhase.PLANNED, ActionEvent.TaskPlanned(taskCtx, 1))

                    val resolution = commandRouter.appResolver.resolve(appTarget)
                    if (resolution.confidence == com.example.utils.MatchConfidence.NONE) {
                        val failMsg = "I couldn't find that app: '$appTarget'."
                        ActionEventBus.transitionTo(taskCtx, ExecutionPhase.FAILED, ActionEvent.ActionFailed(taskCtx, "App not installed", failMsg))
                        cleanResponse = failMsg
                        launchDiagnostic = AppLaunchDiagnostic(
                            requestedApp = appTarget,
                            resolvedApp = "None",
                            packageName = "None",
                            launchIntent = "None",
                            confidence = "NONE",
                            foregroundBefore = screenInspector.getCurrentForegroundPackage(),
                            foregroundAfter = "Unchanged",
                            verification = "FAIL - App not found"
                        )
                    } else if (resolution.confidence == com.example.utils.MatchConfidence.AMBIGUOUS) {
                        isAmbiguous = true
                        candidateApps = resolution.candidateApps
                        val disambiguateMsg = "Which app would you like to open? Found: ${candidateApps.joinToString(", ") { it.appName }}"
                        ActionEventBus.transitionTo(taskCtx, ExecutionPhase.BLOCKED, ActionEvent.ActionBlocked(taskCtx, "Ambiguous app selection", disambiguateMsg))
                        cleanResponse = disambiguateMsg
                    } else {
                        val matched = resolution.matchedApp!!
                        ActionEventBus.transitionTo(taskCtx, ExecutionPhase.EXECUTING, ActionEvent.ActionStarted(taskCtx, "Launching ${matched.appName}"))
                        val execResult = commandRouter.launchSpecificApp(matched)
                        launchDiagnostic = execResult.diagnostic
                        if (execResult.success) {
                            ActionEventBus.transitionTo(taskCtx, ExecutionPhase.SUCCESS, ActionEvent.ActionSuccess(taskCtx, matched.packageName, execResult.output))
                            cleanResponse = execResult.output
                        } else {
                            ActionEventBus.transitionTo(taskCtx, ExecutionPhase.FAILED, ActionEvent.ActionFailed(taskCtx, "Launch failed", execResult.output))
                            cleanResponse = execResult.output
                        }
                    }

                    if (_autoSpeak.value) {
                        voiceEngine.processAndSpeak(cleanResponse, enqueue = false)
                        alreadySpoken = true
                    }
                } else if (classified.category == CommandCategory.SEARCH_IN_APP) {
                    val appTarget = if (!classified.targetApp.isNullOrBlank()) {
                        classified.targetApp!!
                    } else {
                        contextEngine.activeTargetAppName ?: screenInspector.getCurrentForegroundPackage()
                    }
                    val query = classified.query ?: ""
                    if (appTarget.isBlank()) {
                        cleanResponse = "Kis app me search karna hai?"
                        if (_autoSpeak.value) {
                            voiceEngine.processAndSpeak(cleanResponse, enqueue = false)
                            alreadySpoken = true
                        }
                    } else if (query.isBlank()) {
                        cleanResponse = "$appTarget me kya search karna hai?"
                        if (_autoSpeak.value) {
                            voiceEngine.processAndSpeak(cleanResponse, enqueue = false)
                            alreadySpoken = true
                        }
                    } else {
                        val taskCtx = ActionEventBus.startNewTask(prompt, CommandCategory.SEARCH_IN_APP, appTarget, query)
                        ActionEventBus.transitionTo(taskCtx, ExecutionPhase.PARSED, ActionEvent.TaskParsed(taskCtx))
                        ActionEventBus.transitionTo(taskCtx, ExecutionPhase.EXECUTING, ActionEvent.ActionStarted(taskCtx, "Searching $query on $appTarget"))
                        val searchResult = commandRouter.handleCompoundCommand(com.example.utils.AppResolver.CompoundCommand(appTarget, query, "SEARCH"))
                        launchDiagnostic = searchResult.diagnostic
                        if (searchResult.success) {
                            val isHindi = prompt.any { it in '\u0900'..'\u097F' } || prompt.lowercase(java.util.Locale.ROOT).let { it.contains("kholo") || it.contains("chalao") || it.contains("dhoondo") || it.contains("search") }
                            cleanResponse = if (isHindi) "$appTarget par $query search kar diya hai." else "Searched for '$query' on $appTarget."
                            ActionEventBus.transitionTo(taskCtx, ExecutionPhase.SUCCESS, ActionEvent.ActionSuccess(taskCtx, appTarget, cleanResponse))
                        } else {
                            cleanResponse = searchResult.output
                            ActionEventBus.transitionTo(taskCtx, ExecutionPhase.FAILED, ActionEvent.ActionFailed(taskCtx, "Search failed", cleanResponse))
                        }
                        if (_autoSpeak.value) {
                            voiceEngine.processAndSpeak(cleanResponse, enqueue = false)
                            alreadySpoken = true
                        }
                    }
                } else if (classified.category == CommandCategory.INTERACT_IN_APP) {
                    val lowerP = prompt.lowercase(java.util.Locale.ROOT)
                    if (lowerP.contains("first") || lowerP.contains("पहला") || lowerP.contains("pahla")) {
                        com.example.agent.TaskStateMachine.update(
                            phase = com.example.agent.TaskPhase.ACTION_EXECUTION,
                            currentStep = "Tap first result"
                        )
                        com.example.agent.AutomationEventLogger.action("Tapping first result on active screen")
                        val tapRes = com.example.agent.TapEngine.tapFirstResult(
                            com.example.services.KavyaAccessibilityService.instance,
                            screenInspector.getCurrentForegroundPackage()
                        )
                        cleanResponse = tapRes.diagnostic
                        com.example.agent.TaskStateMachine.update(
                            phase = if (tapRes.success) com.example.agent.TaskPhase.COMPLETED else com.example.agent.TaskPhase.FAILED,
                            actionResult = tapRes.diagnostic
                        )
                        com.example.agent.AutomationEventLogger.verify(tapRes.diagnostic)
                        if (_autoSpeak.value) {
                            voiceEngine.processAndSpeak(cleanResponse, enqueue = false)
                            alreadySpoken = true
                        }
                    }
                } else if (classified.category == CommandCategory.PLAY_MEDIA && !classified.targetApp.isNullOrBlank()) {
                    val appTarget = classified.targetApp
                    val query = classified.query ?: ""
                    val taskCtx = ActionEventBus.startNewTask(prompt, CommandCategory.PLAY_MEDIA, appTarget, query)
                    ActionEventBus.transitionTo(taskCtx, ExecutionPhase.PARSED, ActionEvent.TaskParsed(taskCtx))
                    ActionEventBus.transitionTo(taskCtx, ExecutionPhase.EXECUTING, ActionEvent.ActionStarted(taskCtx, "Playing $query on $appTarget"))
                    val mediaResult = commandRouter.handleCompoundCommand(com.example.utils.AppResolver.CompoundCommand(appTarget, query, "PLAY"))
                    launchDiagnostic = mediaResult.diagnostic
                    if (mediaResult.success) {
                        val isHindi = prompt.any { it in '\u0900'..'\u097F' }
                        cleanResponse = if (isHindi) "$appTarget par $query play kar diya hai." else "Playing '$query' on $appTarget."
                        ActionEventBus.transitionTo(taskCtx, ExecutionPhase.SUCCESS, ActionEvent.ActionSuccess(taskCtx, appTarget, cleanResponse))
                    } else {
                        cleanResponse = mediaResult.output
                        ActionEventBus.transitionTo(taskCtx, ExecutionPhase.FAILED, ActionEvent.ActionFailed(taskCtx, "Play failed", cleanResponse))
                    }
                    if (_autoSpeak.value) {
                        voiceEngine.processAndSpeak(cleanResponse, enqueue = false)
                        alreadySpoken = true
                    }
                } else if (classified.category == CommandCategory.CALL) {
                    val target = classified.actionParam ?: ""
                    val isWhatsApp = prompt.contains("WhatsApp", ignoreCase = true) || prompt.contains("व्हाट्सएप", ignoreCase = true)
                    val stepObj = com.example.agent.TaskStep(
                        id = 1,
                        actionType = if (isWhatsApp) com.example.agent.UniversalActionType.MAKE_WHATSAPP_CALL else com.example.agent.UniversalActionType.MAKE_PHONE_CALL,
                        targetAppOrUrl = if (isWhatsApp) "WhatsApp" else "Phone",
                        param = target
                    )
                    val execRes = androidAgent.executeAtomicStep(stepObj)
                    cleanResponse = execRes.output
                    if (_autoSpeak.value) {
                        voiceEngine.processAndSpeak(cleanResponse, enqueue = false)
                        alreadySpoken = true
                    }
                } else if (classified.category == CommandCategory.SEND_MESSAGE) {
                    val rawParam = classified.actionParam ?: ""
                    val isWhatsApp = prompt.contains("WhatsApp", ignoreCase = true) || prompt.contains("व्हाट्सएप", ignoreCase = true)
                    val stepObj = com.example.agent.TaskStep(
                        id = 1,
                        actionType = if (isWhatsApp) com.example.agent.UniversalActionType.SEND_WHATSAPP_MESSAGE else com.example.agent.UniversalActionType.SEND_SMS,
                        targetAppOrUrl = if (isWhatsApp) "WhatsApp" else "Messages",
                        param = rawParam
                    )
                    val execRes = androidAgent.executeAtomicStep(stepObj)
                    cleanResponse = execRes.output
                    if (_autoSpeak.value) {
                        voiceEngine.processAndSpeak(cleanResponse, enqueue = false)
                        alreadySpoken = true
                    }
                } else if (classified.category == CommandCategory.SYSTEM_ACTION) {
                    val sysResult = commandRouter.executeDirectUserCommand(prompt)
                    cleanResponse = sysResult.output
                    launchDiagnostic = sysResult.diagnostic
                    if (_autoSpeak.value) {
                        voiceEngine.processAndSpeak(cleanResponse, enqueue = false)
                        alreadySpoken = true
                    }
                }

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

                    // If still blank, use pleasant natural response
                    if (fullResponse.isBlank()) {
                        fullResponse = "अभी connection में problem आ रही है। कृपया थोड़ी देर में दोबारा try करें।"
                    }

                    // Parse structured actions (JSON schema or action tags)
                    val structuredActions = com.example.agent.StructuredActionParser.parseActions(fullResponse)
                    val extractedClean = voiceEngine.cleanText(fullResponse)
                    cleanResponse = if (extractedClean.isNotBlank()) extractedClean else cleanResponseBuilder.toString()
                    
                    if (structuredActions.isNotEmpty() && !isConversationalTurn) {
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

                        val targetAppNameStr = structuredActions.firstOrNull()?.target ?: ""
                        val targetEntityStr = structuredActions.firstOrNull()?.recipient ?: ""
                        val relevantWorkflowMem = memoryEngine.findWorkflowMemory(targetAppNameStr, targetEntityStr, query = prompt)

                        val taskPlan = com.example.agent.TaskPlan(
                            originalPrompt = prompt,
                            targetAppName = targetAppNameStr,
                            steps = taskSteps,
                            isMultiStep = taskSteps.size > 1,
                            appliedMemory = relevantWorkflowMem,
                            rememberedWorkflowUsed = relevantWorkflowMem != null
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
                    } else if (!isConversationalTurn) {
                        // Fallback check: Did taskPlanner detect a real automation intent?
                        val relevantMems = memoryEngine.findRelevantTaskMemories(
                            targetApp = null,
                            targetEntity = null,
                            action = null,
                            prompt = prompt
                        )
                        val fallbackPlan = taskPlanner.createPlan(prompt, contextEngine, relevantMems)
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
                        microphoneEngine.pauseForTts()
                        voiceEngine.processAndSpeak(cleanResponse, enqueue = false, onComplete = {
                            microphoneEngine.resumeAfterTts(350L)
                        })
                        alreadySpoken = true
                    }
                }
                
                cleanResponse = checkAndFilterRepetition(cleanResponse)
                memoryEngine.autoLearnTaskProgress(prompt, cleanResponse)
                
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
