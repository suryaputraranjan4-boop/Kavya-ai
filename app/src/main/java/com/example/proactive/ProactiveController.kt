package com.example.proactive

import android.content.Context
import android.util.Log
import com.example.agent.MemoryEngine
import com.example.agent.ScreenInspector
import com.example.utils.AppPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ProactiveController(
    private val context: Context,
    private val memoryEngine: MemoryEngine,
    private val screenInspector: ScreenInspector,
    private val onProactiveMessageReady: (text: String?, reason: String) -> Unit
) {
    companion object {
        private const val TAG = "ProactiveController"
        private const val MIN_COOLDOWN_AFTER_SPEECH_MS = 12000L
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val random = Random()

    private val _isProactiveMode = MutableStateFlow(AppPreferences.isProactiveModeEnabled(context))
    val isProactiveMode: StateFlow<Boolean> = _isProactiveMode.asStateFlow()

    private val _isScreenAwareness = MutableStateFlow(AppPreferences.isScreenAwarenessEnabled(context))
    val isScreenAwareness: StateFlow<Boolean> = _isScreenAwareness.asStateFlow()

    private val _frequency = MutableStateFlow(ProactiveFrequency.fromString(AppPreferences.getProactiveFrequency(context)))
    val frequency: StateFlow<ProactiveFrequency> = _frequency.asStateFlow()

    private val _silenceUntil = MutableStateFlow(AppPreferences.getSilenceUntilTimestamp(context))
    val silenceUntil: StateFlow<Long> = _silenceUntil.asStateFlow()

    private val _engagementScore = MutableStateFlow(AppPreferences.getProactiveEngagementScore(context))
    val engagementScore: StateFlow<Float> = _engagementScore.asStateFlow()

    private val _lastProactiveText = MutableStateFlow<String?>(null)
    val lastProactiveText: StateFlow<String?> = _lastProactiveText.asStateFlow()

    // Tracking state
    private var lastUserInteractionTs = System.currentTimeMillis()
    private var lastKavyaSpeechTs = System.currentTimeMillis()
    private var lastProactiveSpeechTs = 0L
    private val isUserSpeaking = AtomicBoolean(false)
    private val isKavyaSpeaking = AtomicBoolean(false)
    private val consecutiveUnansweredCount = AtomicInteger(0)

    private var activeLoopJob: Job? = null
    private var nextIntervalTargetMs = 25000L
    private var lastScreenHash: Int = 0



    init {
        computeNextInterval()
        startProactiveLoop()
    }

    fun setProactiveMode(enabled: Boolean) {
        _isProactiveMode.value = enabled
        AppPreferences.setProactiveModeEnabled(context, enabled)
        if (!enabled) {
            cancelPendingSpeech()
        } else {
            computeNextInterval()
        }
    }

    fun setScreenAwareness(enabled: Boolean) {
        _isScreenAwareness.value = enabled
        AppPreferences.setScreenAwarenessEnabled(context, enabled)
    }

    fun setFrequency(freq: ProactiveFrequency) {
        _frequency.value = freq
        AppPreferences.setProactiveFrequency(context, freq.name)
        computeNextInterval()
    }

    fun setTemporarySilence(durationMs: Long) {
        val until = System.currentTimeMillis() + durationMs
        _silenceUntil.value = until
        AppPreferences.setSilenceUntilTimestamp(context, until)
    }

    fun clearSilence() {
        _silenceUntil.value = 0L
        AppPreferences.setSilenceUntilTimestamp(context, 0L)
    }

    fun onUserSpeechStateChanged(isSpeaking: Boolean) {
        isUserSpeaking.set(isSpeaking)
        if (isSpeaking) {
            lastUserInteractionTs = System.currentTimeMillis()
            // Reset unanswered count because user spoke!
            if (consecutiveUnansweredCount.get() > 0) {
                consecutiveUnansweredCount.set(0)
                updateEngagementScore(delta = 0.1f)
            }
        }
    }

    fun onKavyaSpeechStateChanged(isSpeaking: Boolean) {
        isKavyaSpeaking.set(isSpeaking)
        if (isSpeaking) {
            lastKavyaSpeechTs = System.currentTimeMillis()
        } else {
            lastKavyaSpeechTs = System.currentTimeMillis()
            computeNextInterval()
        }
    }

    fun onUserInteraction(text: String? = null) {
        lastUserInteractionTs = System.currentTimeMillis()
        consecutiveUnansweredCount.set(0)
        computeNextInterval()
    }

    fun onKavyaTurnFinished(responseText: String) {
        lastKavyaSpeechTs = System.currentTimeMillis()
        computeNextInterval()
    }

    fun cancelPendingSpeech() {
        computeNextInterval()
    }

    private fun computeNextInterval() {
        val freq = _frequency.value
        val minMs = freq.minIntervalMs
        val maxMs = freq.maxIntervalMs
        val base = minMs + (random.nextFloat() * (maxMs - minMs)).toLong()
        
        // Scale by engagement score & unanswered backoff
        val score = _engagementScore.value
        val engagementFactor = if (score > 1.2f) 0.8f else if (score < 0.8f) 1.5f else 1.0f
        val unansweredMultiplier = 1f + (consecutiveUnansweredCount.get() * 0.5f).coerceAtMost(3.0f)
        
        nextIntervalTargetMs = (base * engagementFactor * unansweredMultiplier).toLong().coerceIn(6000L, 180000L)
    }

    private fun updateEngagementScore(delta: Float) {
        val newScore = (_engagementScore.value + delta).coerceIn(0.3f, 2.0f)
        _engagementScore.value = newScore
        AppPreferences.setProactiveEngagementScore(context, newScore)
    }

    private fun startProactiveLoop() {
        activeLoopJob?.cancel()
        activeLoopJob = scope.launch {
            while (isActive) {
                delay(3000L) // Lightweight tick check
                try {
                    evaluateAndTrigger()
                } catch (e: Exception) {
                    Log.w(TAG, "Error in proactive tick: ${e.message}")
                }
            }
        }
    }

    private suspend fun evaluateAndTrigger() {
        val now = System.currentTimeMillis()

        // 1. Proactive mode check
        if (!_isProactiveMode.value) return

        // 2. Silence window check
        if (now < _silenceUntil.value) return

        // 3. Activity / barge-in safety
        if (isUserSpeaking.get() || isKavyaSpeaking.get()) return

        // 4. Anti-spam timing check
        val elapsedSinceUser = now - lastUserInteractionTs
        val elapsedSinceKavya = now - lastKavyaSpeechTs
        val elapsedSinceProactive = now - lastProactiveSpeechTs

        if (elapsedSinceKavya < MIN_COOLDOWN_AFTER_SPEECH_MS) return
        if (elapsedSinceProactive < nextIntervalTargetMs) return
        if (elapsedSinceUser < nextIntervalTargetMs) return

        // 5. Build intelligent contextual decision
        val decision = buildProactiveDecision()
        if (decision.shouldSpeak) {
            lastProactiveSpeechTs = now
            lastKavyaSpeechTs = now
            _lastProactiveText.value = decision.text ?: "Dynamic AI Generation"
            consecutiveUnansweredCount.incrementAndGet()
            
            // If user ignores too many times consecutively, lower score
            if (consecutiveUnansweredCount.get() >= 3) {
                updateEngagementScore(-0.15f)
            }

            computeNextInterval()

            withContext(Dispatchers.Main) {
                onProactiveMessageReady(decision.text, decision.reason)
            }
        }
    }

    private suspend fun buildProactiveDecision(): ProactiveDecision {
        // Priority 1: Screen context observation (ONLY if enabled by user and meaningful change occurs)
        if (_isScreenAwareness.value) {
            val screenContext = screenInspector.getScreenContextString()
            if (!screenContext.isNullOrBlank() && !screenContext.contains("Root node is null")) {
                val currentHash = screenContext.hashCode()
                if (currentHash != lastScreenHash) {
                    lastScreenHash = currentHash
                    val lowerScreen = screenContext.lowercase()
                    when {
                        lowerScreen.contains("chrome") || lowerScreen.contains("browser") || lowerScreen.contains("wikipedia") || lowerScreen.contains("medium") -> {
                            return ProactiveDecision(
                                shouldSpeak = true,
                                reason = "User is reading/researching online. Screen context: ${screenContext.take(120)}",
                                text = null, // Dynamically generated by Gemini in ViewModel
                                triggerType = ProactiveTriggerType.SCREEN_OBSERVATION,
                                priorityScore = 0.85f
                            )
                        }
                        lowerScreen.contains("docs") || lowerScreen.contains("notes") || lowerScreen.contains("code") || lowerScreen.contains("studio") -> {
                            return ProactiveDecision(
                                shouldSpeak = true,
                                reason = "User is focused on work or study documents. Screen context: ${screenContext.take(120)}",
                                text = null, // Dynamically generated by Gemini in ViewModel
                                triggerType = ProactiveTriggerType.CURRENT_ACTIVITY,
                                priorityScore = 0.8f
                            )
                        }
                    }
                }
            }
        }

        // Priority 2: Memory-based meaningful recall (only for high importance memories)
        val recentMemories = memoryEngine.retrieveRelevantMemories("project goal exam work milestone", null)
        val candidateMemory = recentMemories.filter { it.importance >= 4 }.randomOrNull()
        if (candidateMemory != null && random.nextFloat() < 0.4f) {
            val topic = candidateMemory.key
            val details = candidateMemory.content.take(80)
            return ProactiveDecision(
                shouldSpeak = true,
                reason = "Follow up on a high-priority user goal or project: '$topic' ($details)",
                text = null, // Dynamically generated by Gemini in ViewModel
                triggerType = ProactiveTriggerType.MEMORY_RECALL,
                priorityScore = 0.75f
            )
        }

        // Default: Silence. Do not interrupt with generic chatter.
        return ProactiveDecision(
            shouldSpeak = false,
            reason = "Silence by default: no high-relevance trigger"
        )
    }

    fun shutdown() {
        activeLoopJob?.cancel()
        scope.cancel()
    }
}
