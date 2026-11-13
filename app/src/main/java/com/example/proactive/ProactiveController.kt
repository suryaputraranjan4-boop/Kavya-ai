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
        // NO USER COMMAND = NO AUTOMATION / NO AUTONOMOUS TRIGGERS.
        // Proactive background monitoring must never autonomously fire commands or screen triggers.
        return
    }

    private suspend fun buildProactiveDecision(): ProactiveDecision {
        // Silence by default: explicit user command required for any AI or automation turn.
        return ProactiveDecision(
            shouldSpeak = false,
            reason = "Blocked: explicit user command required (NO USER COMMAND = NO AUTOMATION)"
        )
    }

    fun shutdown() {
        activeLoopJob?.cancel()
        scope.cancel()
    }
}
