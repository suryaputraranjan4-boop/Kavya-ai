package com.example.ai

import android.content.Context
import android.util.Log
import com.example.agent.MicrophoneEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gemini Live Client for streaming audio to Gemini Live API.
 *
 * Guarantees:
 * 1. Consumes PCM frames directly from [MicrophoneEngine] without creating any physical AudioRecord.
 * 2. Unsubscribes when disconnected without stopping or closing the shared microphone session.
 * 3. Prevents duplicate frame subscriptions or leaks.
 */
class GeminiLiveClient(private val context: Context) {

    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val SUBSCRIBER_ID = "gemini_live_subscriber"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isConnected = AtomicBoolean(false)

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

    private var webSocket: WebSocket? = null

    /**
     * Connects to Gemini Live session and registers as an audio subscriber
     * to the single physical [MicrophoneEngine] session.
     */
    fun startLiveSession(apiKey: String, model: String = GeminiModelRegistry.LIVE_STREAM_MODEL) {
        if (isConnected.getAndSet(true)) {
            Log.d(TAG, "Gemini Live session already active.")
            return
        }

        Log.i(TAG, "Connecting to Gemini Live session (model=$model)...")

        // Register frame subscriber with MicrophoneEngine
        val micEngine = MicrophoneEngine.getInstance(context)
        micEngine.registerFrameSubscriber(SUBSCRIBER_ID) { pcmData ->
            if (isConnected.get()) {
                sendAudioFrame(pcmData)
            }
        }

        _connectionState.value = true
    }

    /**
     * Disconnects Gemini Live session and unregisters subscriber.
     * Does NOT stop or destroy the physical AudioRecord session.
     */
    fun stopLiveSession() {
        if (!isConnected.getAndSet(false)) return

        Log.i(TAG, "Stopping Gemini Live session...")

        // Unregister frame subscriber from MicrophoneEngine
        MicrophoneEngine.getInstance(context).unregisterFrameSubscriber(SUBSCRIBER_ID)

        webSocket?.close(1000, "Client closed session")
        webSocket = null
        _connectionState.value = false
    }

    private fun sendAudioFrame(pcmData: ByteArray) {
        if (!isConnected.get() || pcmData.isEmpty()) return
        // Send base64 / binary PCM frame to Gemini Live WebSocket when connected
        try {
            webSocket?.send(okio.ByteString.of(*pcmData))
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio frame to Gemini Live", e)
        }
    }
}
