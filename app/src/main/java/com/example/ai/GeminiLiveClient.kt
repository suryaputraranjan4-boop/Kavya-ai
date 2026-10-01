package com.example.ai

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.AudioManager
import android.media.MediaRecorder
import android.annotation.SuppressLint
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.example.api.ApiSystem
import com.example.agent.AndroidAgent
import com.example.utils.CommandRouter
import com.example.agent.ContextEngine
import com.example.agent.ScreenInspector
import com.example.agent.VerificationEngine
import com.example.utils.AppPreferences

class GeminiLiveClient(

    private val context: Context,
    private val apiSystem: ApiSystem,
    private val androidAgent: AndroidAgent,
    private val onStateChange: (String) -> Unit, // "IDLE", "LISTENING", "SPEAKING", "THINKING", "ERROR"
    private val onCaption: (String) -> Unit
) {
    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val SAMPLE_RATE_IN = 16000
        private const val SAMPLE_RATE_OUT = 24000
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
        
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    
    private var isRecording = false
    private var isPlaying = false
    private var isConnected = false
    private var connectionJob: Job? = null
    
    fun startSession() {
        if (isConnected) return
        val apiKey = AppPreferences.getEffectiveApiKey(context).ifBlank { com.example.BuildConfig.GEMINI_API_KEY }
        if (apiKey.isBlank()) {
            onStateChange("ERROR")
            return
        }
        
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url).build()
        
        onStateChange("THINKING")
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                isConnected = true
                sendSetup(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
                handleDisconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error", t)
                handleDisconnect()
            }
        })
    }
    
    private fun sendSetup(ws: WebSocket) {
        try {
            val setupMsg = JSONObject().apply {
                put("setup", JSONObject().apply {
                    put("model", GeminiModelRegistry.LIVE_STREAM_MODEL)
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().apply {
                            put("text", "You are Kavya, an intelligent Android voice assistant. When a user gives a command like 'Open YouTube and search Hi' or 'Go to Instagram and search for football', you MUST break it down into sequential steps. CRITICAL RULES: 1. For OPEN_APP, the param MUST ONLY be the exact app name (e.g. 'YouTube'). NEVER pass the whole sentence to OPEN_APP. 2. Issue actions sequentially: first OPEN_APP, wait, then TAP/TYPE for the subsequent actions like searching.")
                        }))
                    })
                    put("generationConfig", JSONObject().apply {
                        put("responseModalities", JSONArray().put("AUDIO"))
                    })
                    put("tools", JSONArray().put(JSONObject().apply {
                        put("functionDeclarations", JSONArray().apply {

                            put(JSONObject().apply {
                                put("name", "executeTaskPlan")
                                put("description", "Execute a multi-step structured action plan on the Android device. Use this for complex commands like 'Open YouTube and search Hi'.")
                                put("parameters", JSONObject().apply {
                                    put("type", "OBJECT")
                                    put("properties", JSONObject().apply {
                                        put("intent", JSONObject().apply { put("type", "STRING") })
                                        put("steps", JSONObject().apply {
                                            put("type", "ARRAY")
                                            put("items", JSONObject().apply {
                                                put("type", "OBJECT")
                                                put("properties", JSONObject().apply {
                                                    put("action", JSONObject().apply { put("type", "STRING") })
                                                    put("target", JSONObject().apply { put("type", "STRING") })
                                                    put("query", JSONObject().apply { put("type", "STRING") })
                                                    put("recipient", JSONObject().apply { put("type", "STRING") })
                                                    put("message", JSONObject().apply { put("type", "STRING") })
                                                    put("index", JSONObject().apply { put("type", "INTEGER") })
                                                })
                                                put("required", JSONArray().put("action").put("target"))
                                            })
                                        })
                                    })
                                    put("required", JSONArray().put("intent").put("steps"))
                                })
                            })
                            put(JSONObject().apply {
                                put("name", "performAndroidAction")
                                put("description", "Perform a UI action on the Android device. Supported actions: OPEN_APP, TAP, TYPE, SCROLL, BACK, HOME, CLEAR_TEXT")
                                put("parameters", JSONObject().apply {
                                    put("type", "OBJECT")
                                    put("properties", JSONObject().apply {
                                        put("action", JSONObject().apply {
                                            put("type", "STRING")
                                        })
                                        put("param", JSONObject().apply {
                                            put("type", "STRING")
                                        })
                                    })
                                    put("required", JSONArray().put("action").put("param"))
                                })
                            })
                            put(JSONObject().apply {
                                put("name", "callApi")
                                put("description", "Call an external API or Hugging Face model.")
                                put("parameters", JSONObject().apply {
                                    put("type", "OBJECT")
                                    put("properties", JSONObject().apply {
                                        put("apiId", JSONObject().apply {
                                            put("type", "STRING")
                                        })
                                        put("params", JSONObject().apply {
                                            put("type", "STRING")
                                        })
                                    })
                                    put("required", JSONArray().put("apiId").put("params"))
                                })
                            })
                        })
                    }))
                })
            }
            ws.send(setupMsg.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending setup", e)
        }
    }
    
    private fun handleServerMessage(text: String) {
        try {
            val root = JSONObject(text)
            if (root.has("setupComplete")) {
                Log.d(TAG, "Setup complete")
                startAudioIO()
            } else if (root.has("serverContent")) {
                val serverContent = root.getJSONObject("serverContent")
                if (serverContent.has("modelTurn")) {
                    val parts = serverContent.getJSONObject("modelTurn").getJSONArray("parts")
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        if (part.has("text")) {
                            val msgText = part.getString("text")
                            if (msgText.isNotBlank()) {
                                onCaption(msgText)
                            }
                        }
                        if (part.has("inlineData")) {
                            val inlineData = part.getJSONObject("inlineData")
                            val base64Data = inlineData.getString("data")
                            val audioBytes = Base64.decode(base64Data, Base64.DEFAULT)
                            playAudioChunk(audioBytes)
                        }
                    }
                }
                
                if (serverContent.has("turnComplete") && serverContent.getBoolean("turnComplete")) {
                    // Turn finished
                    if (!isPlaying) {
                        onStateChange("LISTENING")
                    }
                }
                
                if (serverContent.has("interrupted") && serverContent.getBoolean("interrupted")) {
                    stopPlaying()
                }
            } else if (root.has("toolCall")) {
                val toolCall = root.getJSONObject("toolCall")
                val functionCalls = toolCall.getJSONArray("functionCalls")
                val functionResponses = JSONArray()
                
                for (i in 0 until functionCalls.length()) {
                    val fc = functionCalls.getJSONObject(i)
                    val id = fc.getString("id")
                    val name = fc.getString("name")
                    val args = fc.getJSONObject("args")
                    
                    var resultStr = ""

                    if (name == "executeTaskPlan") {
                        val intent = args.getString("intent")
                        val stepsArray = args.getJSONArray("steps")
                        Log.d(TAG, "Tool call: executeTaskPlan($intent)")
                        scope.launch {
                            val taskSteps = mutableListOf<com.example.agent.TaskStep>()
                            var targetApp = ""
                            for (j in 0 until stepsArray.length()) {
                                val stepObj = stepsArray.getJSONObject(j)
                                val actionStr = stepObj.getString("action").uppercase()
                                val targetStr = stepObj.optString("target", "")
                                val queryStr = stepObj.optString("query", "")
                                val msgStr = stepObj.optString("message", "")
                                
                                val recipStr = stepObj.optString("recipient", "")

                                if (j == 0 && targetStr.isNotBlank()) targetApp = targetStr
                                
                                val paramStr = if (queryStr.isNotBlank()) queryStr else if (msgStr.isNotBlank()) msgStr else targetStr
                                
                                val mappedAction = when (actionStr) {
                                    "OPEN_APP", "OPEN" -> com.example.agent.UniversalActionType.OPEN_APP
                                    "SEARCH" -> com.example.agent.UniversalActionType.SEARCH
                                    "TAP" -> com.example.agent.UniversalActionType.TAP
                                    "TYPE" -> com.example.agent.UniversalActionType.TYPE
                                    "MAKE_PHONE_CALL" -> com.example.agent.UniversalActionType.MAKE_PHONE_CALL
                                    "MAKE_WHATSAPP_CALL" -> com.example.agent.UniversalActionType.MAKE_WHATSAPP_CALL
                                    "SEND_SMS" -> com.example.agent.UniversalActionType.SEND_SMS
                                    "SEND_WHATSAPP_MESSAGE" -> com.example.agent.UniversalActionType.SEND_WHATSAPP_MESSAGE
                                    "SEND_EMAIL" -> com.example.agent.UniversalActionType.SEND_EMAIL
                                    "CREATE_FOLDER" -> com.example.agent.UniversalActionType.CREATE_FOLDER
                                    "CREATE_FILE" -> com.example.agent.UniversalActionType.CREATE_FILE
                                    "SELECT_RESULT" -> com.example.agent.UniversalActionType.SELECT_RESULT
                                    else -> com.example.agent.UniversalActionType.SYSTEM_CONTROL
                                }
                                
                                taskSteps.add(
                                    com.example.agent.TaskStep(
                                        id = j + 1,
                                        actionType = mappedAction,
                                        targetAppOrUrl = targetStr,
                                        param = paramStr,
                                        recipient = recipStr,
                                        messageText = msgStr,
                                        ordinalIndex = stepObj.optInt("index", 0)
                                    )
                                )
                            }
                            
                            val taskPlan = com.example.agent.TaskPlan(
                                originalPrompt = "Parsed Intent: $intent",
                                targetAppName = targetApp,
                                steps = taskSteps,
                                isMultiStep = taskSteps.size > 1
                            )
                            
                            val res = androidAgent.executeTaskPlan(taskPlan)
                            val responseObj = JSONObject().apply {
                                put("success", res.success)
                                put("result", res.finalSpokenMessage)
                            }
                            sendToolResponse(id, name, responseObj)
                        }
                    } else if (name == "performAndroidAction") {

                        val action = args.getString("action")
                        val param = args.getString("param")
                        Log.d(TAG, "Tool call: performAndroidAction(\$action, \$param)")
                        // Launch a coroutine to execute
                        scope.launch {
                            val step = com.example.agent.TaskStep(
                                id = 1,
                                actionType = when (action) {
                                    "OPEN_APP" -> com.example.agent.UniversalActionType.OPEN_APP
                                    "TAP" -> com.example.agent.UniversalActionType.TAP
                                    "TYPE" -> com.example.agent.UniversalActionType.TYPE
                                    "SCROLL" -> com.example.agent.UniversalActionType.SCROLL
                                    "BACK" -> com.example.agent.UniversalActionType.BACK
                                    "HOME" -> com.example.agent.UniversalActionType.HOME
                                    "CLEAR_TEXT" -> com.example.agent.UniversalActionType.CLEAR_TEXT
                                    else -> com.example.agent.UniversalActionType.SYSTEM_CONTROL
                                },
                                targetAppOrUrl = param,
                                param = param
                            )
                            val res = androidAgent.executeAtomicStep(step)
                            val responseObj = JSONObject().apply {
                                put("success", res.success)
                                put("result", res.output)
                            }
                            sendToolResponse(id, name, responseObj)
                        }
                    } else if (name == "callApi") {
                         val apiId = args.getString("apiId")
                         val params = args.getString("params")
                         Log.d(TAG, "Tool call: callApi($apiId, $params)")
                         scope.launch {
                             val res = apiSystem.executeApiDirectly(apiId, params)
                             val responseObj = JSONObject().apply {
                                 put("success", true)
                                 put("result", res)
                             }
                             sendToolResponse(id, name, responseObj)
                         }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing server message: \$text", e)
        }
    }
    
    
    private fun sendToolResponse(id: String, name: String, responseObj: JSONObject) {
        try {
            val msg = JSONObject().apply {
                put("toolResponse", JSONObject().apply {
                    put("functionResponses", JSONArray().put(JSONObject().apply {
                        put("id", id)
                        put("name", name)
                        put("response", responseObj)
                    }))
                })
            }
            webSocket?.send(msg.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send tool response", e)
        }
    }

    private fun startAudioIO() {
        startRecording()
        startPlaybackSystem()
        onStateChange("LISTENING")
    }
    
    private val frameSubscriber: (ByteArray, Int) -> Unit = { frame, _ ->
        if (isRecording && isConnected) {
            val base64Audio = Base64.encodeToString(frame, 0, frame.size, Base64.NO_WRAP)
            val realtimeMsg = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("mediaChunks", JSONArray().apply {
                        put(JSONObject().apply {
                            put("mimeType", "audio/pcm;rate=16000")
                            put("data", base64Audio)
                        })
                    })
                })
            }
            webSocket?.send(realtimeMsg.toString())
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (isRecording) return
        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Log.e(TAG, "RECORD_AUDIO permission not granted!")
            onStateChange("ERROR")
            return
        }

        try {
            val micEngine = com.example.agent.MicrophoneEngine.getInstance(context)
            micEngine.subscribeAudioFrames(frameSubscriber)
            micEngine.startRecording(
                onRecordingStarted = { isRecording = true },
                onAudioCaptured = { _, _ -> },
                onError = { onStateChange("ERROR") }
            )
            isRecording = true
        } catch (e: Exception) {
            Log.e(TAG, "Audio recording failed", e)
            onStateChange("ERROR")
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        val micEngine = com.example.agent.MicrophoneEngine.getInstance(context)
        micEngine.unsubscribeAudioFrames(frameSubscriber)
        micEngine.stopRecording()
    }
    
    private fun startPlaybackSystem() {
        if (audioTrack != null) return
        val minSize = AudioTrack.getMinBufferSize(SAMPLE_RATE_OUT, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            SAMPLE_RATE_OUT,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minSize * 2,
            AudioTrack.MODE_STREAM
        )
        audioTrack?.play()
    }
    
    private fun playAudioChunk(bytes: ByteArray) {
        if (!isPlaying) {
            isPlaying = true
            onStateChange("SPEAKING")
        }
        audioTrack?.write(bytes, 0, bytes.size)
        // Wait, how to know when it finishes? AudioTrack is blocking on write, but actually plays in background.
        // It's tricky to know exactly when it stops speaking.
    }
    
    private fun stopPlaying() {
        audioTrack?.pause()
        audioTrack?.flush()
        audioTrack?.play()
        isPlaying = false
        onStateChange("LISTENING")
    }
    
    private fun handleDisconnect() {
        isConnected = false
        stopSession()
        onStateChange("IDLE")
    }
    
    fun stopSession() {
        isConnected = false
        isRecording = false
        isPlaying = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        
        webSocket?.close(1000, "User stopped")
        webSocket = null
        scope.cancel()
    }
}
