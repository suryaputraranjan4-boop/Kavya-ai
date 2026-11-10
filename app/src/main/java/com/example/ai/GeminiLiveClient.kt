package com.example.ai

import android.content.Context
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import com.example.agent.MicrophoneEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class LiveVoiceState { DISCONNECTED, CONNECTING, LISTENING, SPEAKING, ERROR }

/**
 * Full-duplex Gemini Live client. MicrophoneEngine remains the only AudioRecord owner.
 * Input is ordered 16 kHz mono PCM; output is streamed 24 kHz mono PCM.
 */
class GeminiLiveClient(context: Context) {
    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val SUBSCRIBER_ID = "gemini_live_subscriber"
        private const val LIVE_ENDPOINT =
            "https://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val INPUT_SAMPLE_RATE = 16000
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousAudioMode: Int? = null
    private var previousSpeakerphoneState: Boolean? = null
    private val micEngine = MicrophoneEngine.getInstance(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val active = AtomicBoolean(false)
    private val setupReady = AtomicBoolean(false)
    private val socketGeneration = AtomicLong(0L)
    @Volatile private var webSocket: WebSocket? = null

    private val _state = MutableStateFlow(LiveVoiceState.DISCONNECTED)
    val state: StateFlow<LiveVoiceState> = _state.asStateFlow()
    private val _inputTranscript = MutableStateFlow("")
    val inputTranscript: StateFlow<String> = _inputTranscript.asStateFlow()
    private val _outputTranscript = MutableStateFlow("")
    val outputTranscript: StateFlow<String> = _outputTranscript.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val inputText = StringBuilder()
    private val outputText = StringBuilder()
    private val pendingAudioFrames = java.util.ArrayDeque<ByteArray>()
    private val audioPlayer = LivePcmAudioPlayer { speaking ->
        micEngine.setAssistantSpeaking(speaking)
        if (active.get()) {
            _state.value = if (speaking) LiveVoiceState.SPEAKING else LiveVoiceState.LISTENING
        }
    }

    @Volatile var onUserTurnComplete: ((String) -> Unit)? = null
    @Volatile var onAssistantTurnComplete: ((String) -> Unit)? = null
    @Volatile var onAppCommand: (suspend (String) -> String)? = null
    @Volatile var onError: ((String) -> Unit)? = null

    fun isActive(): Boolean = active.get()

    fun reportError(message: String) {
        fail(message)
    }

    /**
     * Use the user's configured credential. Never log or commit this key.
     * Public multi-user releases should exchange a server-issued ephemeral token.
     */
    fun startLiveSession(
        apiKey: String,
        model: String = GeminiModelRegistry.LIVE_STREAM_MODEL,
        conversationContext: String = ""
    ) {
        if (apiKey.isBlank()) {
            fail("Gemini API key is not configured.")
            return
        }
        if (!active.compareAndSet(false, true)) return

        _lastError.value = null
        _inputTranscript.value = ""
        _outputTranscript.value = ""
        synchronized(inputText) { inputText.setLength(0) }
        synchronized(outputText) { outputText.setLength(0) }
        setupReady.set(false)
        _state.value = LiveVoiceState.CONNECTING
        configureVoiceAudioRoute()
        micEngine.setLiveConversationActive(true)
        synchronized(pendingAudioFrames) { pendingAudioFrames.clear() }
        // Buffer a short pre-roll while the WebSocket negotiates setup, so the first
        // words are not silently lost if the user starts speaking immediately.
        micEngine.registerFrameSubscriber(SUBSCRIBER_ID) { pcm ->
            if (active.get()) {
                if (setupReady.get()) {
                    sendAudioFrame(pcm)
                } else {
                    synchronized(pendingAudioFrames) {
                        if (pendingAudioFrames.size >= 12) pendingAudioFrames.removeFirst()
                        pendingAudioFrames.addLast(pcm.copyOf())
                    }
                }
            }
        }

        val generation = socketGeneration.incrementAndGet()
        val httpsUrl = LIVE_ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("key", apiKey.trim())
            .build()
        val request = Request.Builder()
            .url(httpsUrl.toString().replaceFirst("https://", "wss://"))
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(socket: WebSocket, response: Response) {
                if (!active.get() || generation != socketGeneration.get()) {
                    socket.close(1000, "Stale session")
                    return
                }
                webSocket = socket
                if (!socket.send(buildSetupMessage(model, conversationContext).toString())) {
                    fail("Could not send Gemini Live session setup.")
                }
            }

            override fun onMessage(socket: WebSocket, text: String) {
                if (generation != socketGeneration.get() || !active.get()) return
                try {
                    handleServerMessage(JSONObject(text))
                } catch (e: Exception) {
                    Log.e(TAG, "Could not process Gemini Live message", e)
                    fail("Gemini Live returned an unreadable message.")
                }
            }

            override fun onFailure(socket: WebSocket, t: Throwable, response: Response?) {
                if (generation != socketGeneration.get()) return
                Log.e(TAG, "Gemini Live WebSocket failed (HTTP ${response?.code ?: "n/a"})", t)
                val message = when (response?.code) {
                    400, 401, 403 -> "Gemini Live rejected the API key or model access."
                    429 -> "Gemini Live is rate-limited. Please try again shortly."
                    else -> "Gemini Live connection failed: ${t.localizedMessage ?: "network error"}"
                }
                fail(message)
            }

            override fun onClosed(socket: WebSocket, code: Int, reason: String) {
                if (generation != socketGeneration.get()) return
                cleanupSession()
                if (active.get()) fail("Gemini Live session closed: $reason")
            }
        })
    }

    private fun buildSetupMessage(model: String, conversationContext: String): JSONObject {
        val persona = """
            You are Kavya, a warm, intelligent, natural female AI assistant.
            Speak in the user's language and naturally mirror Hindi, Hinglish, or English.
            Use clear Indian Hindi pronunciation when speaking Hindi. Be concise and conversational.
            Do not sound like a formal IVR. Continue naturally without repeating greetings.
            Let the user interrupt at any time and stop speaking immediately when user activity is detected.
            You are running inside the Kavya Android app. When the user explicitly asks you to
            perform an action using the phone or app, call run_kavya_command and wait for its result.
            If the result says confirmation is required, ask the user to confirm on screen. Never
            claim a phone action was completed unless the app explicitly confirms it.
            ${conversationContext.trim()}
        """.trimIndent()

        val generationConfig = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", JSONObject().put(
                "voiceConfig",
                JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", "Kore"))
            ))
        val setup = JSONObject()
            .put("model", if (model.startsWith("models/")) model else "models/$model")
            .put("generationConfig", generationConfig)
            .put("systemInstruction", JSONObject().put(
                "parts", JSONArray().put(JSONObject().put("text", persona))
            ))
            .put("realtimeInputConfig", JSONObject()
                .put("automaticActivityDetection", JSONObject().put("disabled", false))
                .put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS")
            )
            .put("inputAudioTranscription", JSONObject().put(
                "languageCodes", JSONArray().put("hi-IN").put("en-IN")
            ))
            .put("outputAudioTranscription", JSONObject().put(
                "languageCodes", JSONArray().put("hi-IN").put("en-IN")
            ))
            .put("tools", JSONArray().put(
                JSONObject().put("functionDeclarations", JSONArray().put(
                    JSONObject()
                        .put("name", "run_kavya_command")
                        .put("description", "Use only when the user explicitly asks Kavya to perform an action using the Android app or connected phone capabilities. The command is routed through Kavya's existing safety checks and confirmation flow. Never claim an action succeeded unless the app result confirms it.")
                        .put("parameters", JSONObject()
                            .put("type", "OBJECT")
                            .put("properties", JSONObject().put(
                                "command",
                                JSONObject().put("type", "STRING").put("description", "The user's requested Android app or phone action, in plain language.")
                            ))
                            .put("required", JSONArray().put("command"))
                        )
                ))
            ))
            .put("sessionResumption", JSONObject())
        return JSONObject().put("setup", setup)
    }

    private fun handleServerMessage(message: JSONObject) {
        if (message.has("toolCall")) {
            handleToolCall(message.optJSONObject("toolCall") ?: return)
            return
        }
        if (message.has("setupComplete")) {
            setupReady.set(true)
            val bufferedFrames = synchronized(pendingAudioFrames) {
                buildList {
                    while (pendingAudioFrames.isNotEmpty()) add(pendingAudioFrames.removeFirst())
                }
            }
            bufferedFrames.forEach(::sendAudioFrame)
            _state.value = LiveVoiceState.LISTENING
            return
        }

        val serverContent = message.optJSONObject("serverContent") ?: return
        if (serverContent.optBoolean("interrupted", false)) {
            audioPlayer.flush()
            micEngine.setAssistantSpeaking(false)
            _state.value = LiveVoiceState.LISTENING
        }

        serverContent.optJSONObject("inputTranscription")?.optString("text")
            ?.takeIf { it.isNotBlank() }?.let { appendTranscript(inputText, it, _inputTranscript) }
        serverContent.optJSONObject("interimInputTranscription")?.optString("text")
            ?.takeIf { it.isNotBlank() }?.let { _inputTranscript.value = it }
        serverContent.optJSONObject("outputTranscription")?.optString("text")
            ?.takeIf { it.isNotBlank() }?.let { appendTranscript(outputText, it, _outputTranscript) }

        val parts = serverContent.optJSONObject("modelTurn")?.optJSONArray("parts")
        if (parts != null) {
            for (index in 0 until parts.length()) {
                val inlineData = parts.optJSONObject(index)?.optJSONObject("inlineData") ?: continue
                if (!inlineData.optString("mimeType").startsWith("audio/pcm")) continue
                val encoded = inlineData.optString("data")
                if (encoded.isBlank()) continue
                val pcm = try { Base64.decode(encoded, Base64.DEFAULT) } catch (_: Exception) { null }
                if (pcm != null && pcm.isNotEmpty()) audioPlayer.enqueue(pcm)
            }
        }

        // Transcription events are independent of audio events. Google guarantees
        // the final output transcript before turnComplete, so finalize only there.
        if (serverContent.optBoolean("turnComplete", false)) finishCurrentTurn()
    }

    private fun handleToolCall(toolCall: JSONObject) {
        val calls = toolCall.optJSONArray("functionCalls") ?: return
        scope.launch {
            val responses = JSONArray()
            for (index in 0 until calls.length()) {
                val call = calls.optJSONObject(index) ?: continue
                val id = call.optString("id")
                val name = call.optString("name")
                val command = call.optJSONObject("args")?.optString("command").orEmpty().trim()
                val result = if (name == "run_kavya_command" && command.isNotBlank()) {
                    try {
                        onAppCommand?.invoke(command)
                            ?: "Kavya's Android action bridge is not available right now."
                    } catch (e: Exception) {
                        Log.e(TAG, "Kavya app command failed", e)
                        "The app could not complete that action: ${e.localizedMessage ?: "unknown error"}"
                    }
                } else {
                    "Unsupported Kavya app action."
                }
                responses.put(
                    JSONObject()
                        .put("id", id)
                        .put("name", name)
                        .put("response", JSONObject().put("result", result))
                )
            }
            if (responses.length() > 0) {
                webSocket?.send(
                    JSONObject().put(
                        "toolResponse",
                        JSONObject().put("functionResponses", responses)
                    ).toString()
                )
            }
        }
    }

    private fun appendTranscript(buffer: StringBuilder, incoming: String, flow: MutableStateFlow<String>) {
        synchronized(buffer) {
            when {
                buffer.isEmpty() -> buffer.append(incoming)
                incoming.startsWith(buffer.toString()) -> {
                    buffer.setLength(0)
                    buffer.append(incoming)
                }
                buffer.toString().endsWith(incoming) -> Unit
                else -> buffer.append(incoming)
            }
            flow.value = buffer.toString()
        }
    }

    private fun finishCurrentTurn() {
        val userText = synchronized(inputText) {
            inputText.toString().ifBlank { _inputTranscript.value }.trim()
                .also { inputText.setLength(0) }
        }
        val assistantText = synchronized(outputText) {
            outputText.toString().trim().also { outputText.setLength(0) }
        }
        _inputTranscript.value = ""
        _outputTranscript.value = ""
        if (userText.isNotBlank()) onUserTurnComplete?.invoke(userText)
        if (assistantText.isNotBlank()) onAssistantTurnComplete?.invoke(assistantText)
        audioPlayer.finishTurn()
        if (active.get()) _state.value = LiveVoiceState.LISTENING
    }

    private fun sendAudioFrame(pcmData: ByteArray) {
        if (!active.get() || !setupReady.get() || pcmData.isEmpty()) return
        val socket = webSocket ?: return
        // Avoid building seconds of stale audio if the network is congested.
        if (socket.queueSize() > 128 * 1024) {
            Log.w(TAG, "Dropping a live audio frame because the WebSocket send queue is congested.")
            return
        }
        val payload = JSONObject().put(
            "realtimeInput",
            JSONObject().put(
                "audio",
                JSONObject()
                    .put("mimeType", "audio/pcm;rate=$INPUT_SAMPLE_RATE")
                    .put("data", Base64.encodeToString(pcmData, Base64.NO_WRAP))
            )
        )
        if (!socket.send(payload.toString())) {
            Log.w(TAG, "Live audio frame was not queued by WebSocket.")
        }
    }

    /** Local VAD flushes speaker output before the server's interruption event arrives. */
    fun onLocalBargeIn() {
        if (!active.get()) return
        audioPlayer.flush()
        micEngine.setAssistantSpeaking(false)
        _state.value = LiveVoiceState.LISTENING
    }

    fun stopLiveSession() {
        if (!active.getAndSet(false)) return
        socketGeneration.incrementAndGet()
        cleanupSession()
        webSocket?.close(1000, "User ended live conversation")
        webSocket = null
        _state.value = LiveVoiceState.DISCONNECTED
    }

    fun release() {
        stopLiveSession()
        audioPlayer.release()
        scope.cancel()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private fun configureVoiceAudioRoute() {
        if (previousAudioMode != null) return
        try {
            previousAudioMode = audioManager.mode
            previousSpeakerphoneState = audioManager.isSpeakerphoneOn
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val bluetoothOutputConnected = outputs.any { device ->
                device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (android.os.Build.VERSION.SDK_INT >= 31 &&
                        device.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
            if (!bluetoothOutputConnected) audioManager.isSpeakerphoneOn = true
        } catch (e: Exception) {
            Log.w(TAG, "Could not configure low-latency voice audio routing", e)
        }
    }

    private fun restoreVoiceAudioRoute() {
        try {
            previousAudioMode?.let { audioManager.mode = it }
            previousSpeakerphoneState?.let { audioManager.isSpeakerphoneOn = it }
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore previous audio routing", e)
        } finally {
            previousAudioMode = null
            previousSpeakerphoneState = null
        }
    }

    private fun cleanupSession() {
        setupReady.set(false)
        micEngine.unregisterFrameSubscriber(SUBSCRIBER_ID)
        synchronized(pendingAudioFrames) { pendingAudioFrames.clear() }
        micEngine.setLiveConversationActive(false)
        micEngine.setAssistantSpeaking(false)
        audioPlayer.stop()
        restoreVoiceAudioRoute()
    }

    private fun fail(message: String) {
        Log.w(TAG, message)
        _lastError.value = message
        onError?.invoke(message)
        active.set(false)
        socketGeneration.incrementAndGet()
        cleanupSession()
        webSocket?.cancel()
        webSocket = null
        _state.value = LiveVoiceState.ERROR
    }
}

/** Low-latency streamed PCM output; avoids WAV files and MediaPlayer preparation latency. */
private class LivePcmAudioPlayer(
    private val onSpeakingChanged: (Boolean) -> Unit
) {
    private data class AudioChunk(
        val generation: Long,
        val bytes: ByteArray,
        val finish: Boolean = false
    )

    private val generation = AtomicLong(0L)
    private val queue = Channel<AudioChunk>(
        capacity = 24,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var track: AudioTrack? = null
    @Volatile private var speaking = false
    @Volatile private var framesWritten = 0L

    init {
        playerScope.launch {
            for (chunk in queue) {
                if (chunk.generation != generation.get()) continue
                val audioTrack = getOrCreateTrack() ?: continue
                if (chunk.finish) {
                    while (chunk.generation == generation.get() &&
                        audioTrack.playbackHeadPosition.toLong() < framesWritten
                    ) delay(20)
                    if (chunk.generation == generation.get()) setSpeaking(false)
                    continue
                }
                if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    try { audioTrack.play() } catch (e: Exception) {
                        Log.e("GeminiLiveAudio", "AudioTrack could not start", e)
                        continue
                    }
                }
                setSpeaking(true)
                var offset = 0
                while (offset < chunk.bytes.size && chunk.generation == generation.get()) {
                    val written = try {
                        audioTrack.write(
                            chunk.bytes, offset, chunk.bytes.size - offset,
                            AudioTrack.WRITE_NON_BLOCKING
                        )
                    } catch (e: Exception) {
                        Log.e("GeminiLiveAudio", "AudioTrack write failed", e)
                        -1
                    }
                    if (written > 0) {
                        offset += written
                        framesWritten += written / 2
                    } else if (written == 0) {
                        delay(5)
                    } else break
                }
            }
        }
    }

    fun enqueue(pcm: ByteArray) {
        if (pcm.isNotEmpty()) queue.trySend(AudioChunk(generation.get(), pcm))
    }

    fun finishTurn() {
        queue.trySend(AudioChunk(generation.get(), ByteArray(0), finish = true))
    }

    fun flush() {
        generation.incrementAndGet()
        while (queue.tryReceive().isSuccess) { }
        framesWritten = 0L
        val audioTrack = track
        if (audioTrack != null) {
            try {
                val wasPlaying = audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING
                audioTrack.pause()
                audioTrack.flush()
                if (wasPlaying) audioTrack.play()
            } catch (e: Exception) {
                Log.w("GeminiLiveAudio", "AudioTrack flush failed", e)
            }
        }
        setSpeaking(false)
    }

    fun stop() {
        flush()
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
    }

    fun release() {
        stop()
        queue.close()
        playerScope.cancel()
    }

    @Synchronized
    private fun getOrCreateTrack(): AudioTrack? {
        track?.let { return it }
        return try {
            val minBuffer = AudioTrack.getMinBufferSize(
                24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) return null
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(24000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer * 2, 4800))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { track = it }
        } catch (e: Exception) {
            Log.e("GeminiLiveAudio", "Could not create streaming AudioTrack", e)
            null
        }
    }

    private fun setSpeaking(value: Boolean) {
        if (speaking == value) return
        speaking = value
        try { onSpeakingChanged(value) } catch (e: Exception) {
            Log.w("GeminiLiveAudio", "Speaking-state callback failed", e)
        }
    }
}
