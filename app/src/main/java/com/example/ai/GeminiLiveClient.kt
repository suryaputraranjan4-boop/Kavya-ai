package com.example.ai

import android.content.Context
import android.util.Base64
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
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real Gemini Live WebSocket Client for streaming audio to Gemini Live API.
 *
 * Guarantees:
 * 1. Establishes a real OkHttp WebSocket connection to Gemini Multimodal Live API.
 * 2. Consumes PCM frames directly from [MicrophoneEngine] without creating any physical AudioRecord.
 * 3. Unsubscribes when disconnected without stopping or closing the shared microphone session.
 * 4. Prevents duplicate frame subscriptions or leaks.
 */
class GeminiLiveClient(
    private val context: Context,
    private val audioPlayer: GeminiAudioPlayer? = null
) {

    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val SUBSCRIBER_ID = "gemini_live_subscriber"
        private const val BASE_WS_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isConnected = AtomicBoolean(false)

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

    private val _lastServerText = MutableStateFlow("")
    val lastServerText: StateFlow<String> = _lastServerText.asStateFlow()

    private var okHttpClient: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var screenStreamJob: kotlinx.coroutines.Job? = null

    /**
     * Connects to Gemini Live session via OkHttp WebSocket and registers as an audio subscriber
     * to the single physical [MicrophoneEngine] session.
     */
    fun startLiveSession(apiKey: String, model: String = GeminiModelRegistry.LIVE_STREAM_MODEL) {
        if (apiKey.isBlank()) {
            Log.e(TAG, "Cannot start Gemini Live session: API key is blank.")
            return
        }

        if (isConnected.getAndSet(true)) {
            Log.d(TAG, "Gemini Live session already active.")
            return
        }

        val targetModel = if (model.startsWith("models/")) model else "models/$model"
        Log.i(TAG, "Connecting to Gemini Live WebSocket endpoint (model=$targetModel)...")

        _connectionState.value = true

        // Register frame subscriber with MicrophoneEngine
        val micEngine = MicrophoneEngine.getInstance(context)
        micEngine.registerFrameSubscriber(SUBSCRIBER_ID) { pcmData ->
            if (isConnected.get()) {
                sendAudioFrame(pcmData)
            }
        }

        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
        okHttpClient = client

        val wsUrl = "$BASE_WS_URL?key=$apiKey"
        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Gemini Live WebSocket connection established. Sending setup payload...")
                _connectionState.value = true

                // Send setup frame
                val setupJson = JSONObject().apply {
                    put("setup", JSONObject().apply {
                        put("model", targetModel)
                        put("generationConfig", JSONObject().apply {
                            put("responseModalities", org.json.JSONArray().apply {
                                put("AUDIO")
                                put("TEXT")
                            })
                            put("speechConfig", JSONObject().apply {
                                put("voiceConfig", JSONObject().apply {
                                    put("prebuiltVoiceConfig", JSONObject().apply {
                                        put("voiceName", "Puck")
                                    })
                                })
                            })
                        })
                    })
                }

                webSocket.send(setupJson.toString())
                Log.d(TAG, "Setup frame sent successfully.")
                startPeriodicScreenFrameLoop()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Gemini Live WebSocket failure", t)
                cleanup()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Gemini Live WebSocket closed: $code / $reason")
                cleanup()
            }
        })
    }

    /**
     * Disconnects Gemini Live session and unregisters subscriber.
     * Does NOT stop or destroy the physical AudioRecord session.
     */
    fun stopLiveSession() {
        if (!isConnected.get()) return
        Log.i(TAG, "Stopping Gemini Live session...")
        cleanup()
    }

    private fun cleanup() {
        if (!isConnected.getAndSet(false)) return
        _connectionState.value = false

        // Unregister frame subscriber from MicrophoneEngine
        try {
            MicrophoneEngine.getInstance(context).unregisterFrameSubscriber(SUBSCRIBER_ID)
        } catch (e: Exception) {
            Log.w(TAG, "Failed unregistering subscriber", e)
        }

        screenStreamJob?.cancel()
        screenStreamJob = null
        try {
            webSocket?.close(1000, "Client disconnect requested")
        } catch (_: Exception) {}
        webSocket = null
        okHttpClient = null
    }

    private fun startPeriodicScreenFrameLoop() {
        screenStreamJob?.cancel()
        screenStreamJob = scope.launch {
            while (isConnected.get()) {
                kotlinx.coroutines.delay(2000L)
                val now = System.currentTimeMillis()
                if (now - lastScreenFrameSentAt >= 1800L &&
                    com.example.services.ScreenShareManager.state.value is com.example.services.ScreenShareManager.ScreenShareState.Active
                ) {
                    val jpegBytes = com.example.visual.VisionPipeline.getCompressedJpegFrame()
                    val currentWs = webSocket
                    if (jpegBytes != null && jpegBytes.isNotEmpty() && currentWs != null) {
                        lastScreenFrameSentAt = now
                        val base64Jpeg = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
                        val frameJson = JSONObject().apply {
                            put("realtimeInput", JSONObject().apply {
                                put("mediaChunks", org.json.JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("mimeType", "image/jpeg")
                                        put("data", base64Jpeg)
                                    })
                                })
                            })
                        }
                        currentWs.send(frameJson.toString())
                    }
                }
            }
        }
    }

    @Volatile private var lastScreenFrameSentAt = 0L

    private fun sendAudioFrame(pcmData: ByteArray) {
        if (!isConnected.get() || pcmData.isEmpty()) return
        val currentWs = webSocket ?: return

        try {
            val base64Pcm = Base64.encodeToString(pcmData, Base64.NO_WRAP)
            val mediaChunks = org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("mimeType", "audio/pcm;rate=16000")
                    put("data", base64Pcm)
                })
                // Attach live screen JPEG frame when ScreenShareManager is active and frame is fresh
                val now = System.currentTimeMillis()
                if (now - lastScreenFrameSentAt >= 1500L &&
                    com.example.services.ScreenShareManager.state.value is com.example.services.ScreenShareManager.ScreenShareState.Active
                ) {
                    val jpegBytes = com.example.visual.VisionPipeline.getCompressedJpegFrame()
                    if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                        lastScreenFrameSentAt = now
                        val base64Jpeg = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
                        put(JSONObject().apply {
                            put("mimeType", "image/jpeg")
                            put("data", base64Jpeg)
                        })
                    }
                }
            }
            val realtimeInputJson = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("mediaChunks", mediaChunks)
                })
            }
            currentWs.send(realtimeInputJson.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio/screen frame to Gemini Live WebSocket", e)
        }
    }

    private fun handleIncomingMessage(jsonText: String) {
        try {
            val root = JSONObject(jsonText)

            // Server Content (Audio / Text response)
            if (root.has("serverContent")) {
                val serverContent = root.getJSONObject("serverContent")
                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    if (modelTurn.has("parts")) {
                        val parts = modelTurn.getJSONArray("parts")
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (part.has("text")) {
                                val textChunk = part.optString("text", "")
                                if (textChunk.isNotBlank()) {
                                    _lastServerText.value = textChunk
                                }
                            }
                            if (part.has("inlineData")) {
                                val inlineData = part.getJSONObject("inlineData")
                                val mimeType = inlineData.optString("mimeType", "")
                                val dataBase64 = inlineData.optString("data", "")
                                if (dataBase64.isNotBlank()) {
                                    val audioBytes = Base64.decode(dataBase64, Base64.DEFAULT)
                                    val sampleRate = if (mimeType.contains("24000")) 24000 else 16000
                                    audioPlayer?.playPcm(audioBytes, sampleRate = sampleRate, enqueue = true)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing incoming Gemini Live message", e)
        }
    }
}
