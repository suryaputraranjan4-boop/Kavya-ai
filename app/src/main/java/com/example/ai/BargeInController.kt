package com.example.ai

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BargeInState {
    IDLE,
    THINKING,
    SPEAKING,
    BARGE_IN_DETECTED,
    PROCESSING
}

/**
 * Authoritative Barge-In and Voice Priority Controller.
 * Guarantees that user actions or triggers can halt Kavya's audio playback.
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
     * Called when assistant speech should be interrupted immediately.
     */
    fun onUserInterrupted() {
        val currentState = _state.value
        if (currentState == BargeInState.SPEAKING || audioPlayer.isSpeaking()) {
            Log.w(TAG, "BARGE_IN_TRIGGERED: Interrupting assistant audio playback.")
            _state.value = BargeInState.BARGE_IN_DETECTED
            audioPlayer.stop()
            onBargeInTriggered?.invoke()
            _state.value = BargeInState.IDLE
        }
    }

    fun onSpeechProcessing() {
        _state.value = BargeInState.PROCESSING
    }

    fun resetToIdle() {
        _state.value = BargeInState.IDLE
    }
}
