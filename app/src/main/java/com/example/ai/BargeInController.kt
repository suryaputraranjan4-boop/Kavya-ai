package com.example.ai

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BargeInState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    BARGE_IN_DETECTED,
    CAPTURING,
    PROCESSING
}

/**
 * Authoritative Barge-In and Voice Priority Controller (Requirements 23, 46).
 * Guarantees that user speech always has absolute priority over Kavya's audio playback.
 */
class BargeInController(
    private val audioPlayer: GeminiAudioPlayer,
    private val onBargeInTriggered: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "KavyaBargeIn"
    }

    private val _state = MutableStateFlow(BargeInState.IDLE)
    val state: StateFlow<BargeInState> = _state.asStateFlow()

    fun onSpeakingStarted() {
        if (_state.value != BargeInState.BARGE_IN_DETECTED) {
            _state.value = BargeInState.SPEAKING
            Log.d(TAG, "STATE -> SPEAKING")
        }
    }

    fun onSpeakingFinished() {
        if (_state.value == BargeInState.SPEAKING) {
            _state.value = BargeInState.IDLE
            Log.d(TAG, "STATE -> IDLE")
        }
    }

    /**
     * Called the instant user speech presence or STT activity is detected while audio is playing.
     */
    fun onUserSpeechDetected() {
        val currentState = _state.value
        if (currentState == BargeInState.SPEAKING || audioPlayer.isSpeaking()) {
            Log.w(TAG, "BARGE_IN_TRIGGERED: User spoke while assistant was speaking! Immediately cutting TTS.")
            _state.value = BargeInState.BARGE_IN_DETECTED
            audioPlayer.stop()
            onBargeInTriggered?.invoke()
            _state.value = BargeInState.CAPTURING
            Log.d(TAG, "STATE -> CAPTURING (User has priority)")
        } else if (currentState == BargeInState.IDLE) {
            _state.value = BargeInState.LISTENING
        }
    }

    fun onSpeechProcessing() {
        _state.value = BargeInState.PROCESSING
    }

    fun resetToIdle() {
        _state.value = BargeInState.IDLE
    }
}
