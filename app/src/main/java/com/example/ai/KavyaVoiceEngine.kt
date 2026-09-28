package com.example.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class KavyaVoiceEngine(
    private val context: Context,
    private val geminiPlayer: GeminiAudioPlayer? = null,
    private val kavyaAI: KavyaAI? = null
) {

    companion object {
        private const val TAG = "KavyaVoiceEngine"
        private val SENTIMENT_TAG_REGEX = "(?i)\\[(happy|sad|warm|empathetic|excited|calm|witty|confident|thoughtful|playful|focused|neutral)\\]".toRegex()
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var activeSpeechJob: Job? = null
    private var androidTts: android.speech.tts.TextToSpeech? = null

    @Volatile
    var isSpeaking: Boolean = false
        private set

    var onSpeakingStateChanged: ((Boolean) -> Unit)? = null

    private fun updateSpeakingState(speaking: Boolean) {
        if (isSpeaking != speaking) {
            isSpeaking = speaking
            scope.launch(Dispatchers.Main) {
                onSpeakingStateChanged?.invoke(speaking)
            }
        }
    }

    init {
        try {
            androidTts = android.speech.tts.TextToSpeech(context.applicationContext) { status ->
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    val result = androidTts?.setLanguage(Locale("hi", "IN"))
                    if (result == android.speech.tts.TextToSpeech.LANG_MISSING_DATA || result == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) {
                        androidTts?.language = Locale.getDefault()
                    }
                    androidTts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            updateSpeakingState(true)
                        }
                        override fun onDone(utteranceId: String?) {
                            updateSpeakingState(false)
                        }
                        override fun onError(utteranceId: String?) {
                            updateSpeakingState(false)
                        }
                    })
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to init Android TTS fallback: ${e.message}")
        }
    }

    fun processAndSpeak(rawText: String, enqueue: Boolean = false, onComplete: (() -> Unit)? = null): SpeechAnalysisResult {
        val analysis = analyzeSpeech(rawText)
        if (analysis.cleanSpokenText.isNotBlank()) {
            speak(analysis.cleanSpokenText, analysis.sentiment, enqueue, onComplete)
        } else {
            onComplete?.invoke()
        }
        return analysis
    }

    fun speak(
        text: String,
        explicitSentiment: Sentiment? = null,
        enqueue: Boolean = false,
        onComplete: (() -> Unit)? = null
    ) {
        val sentiment = explicitSentiment ?: inferSentiment(text)
        val basePitch = com.example.utils.AppPreferences.getVoicePitch(context).coerceIn(0.95f, 1.25f)
        val baseSpeed = com.example.utils.AppPreferences.getVoiceSpeed(context).coerceIn(0.80f, 1.25f)

        val effectivePitch = (basePitch * sentiment.pitchMultiplier).coerceIn(0.95f, 1.25f)
        val effectiveSpeed = (baseSpeed * sentiment.speedMultiplier).coerceIn(0.80f, 1.25f)

        val cleanText = cleanText(text)
        if (cleanText.isBlank()) {
            onComplete?.invoke()
            return
        }

        if (!enqueue) {
            stop()
        }

        updateSpeakingState(true)

        val job = scope.launch(Dispatchers.IO) {
            var geminiSuccess = false
            if (geminiPlayer != null) {
                val effectiveAI = kavyaAI ?: KavyaAI(context)
                val selectedVoice = com.example.utils.AppPreferences.getGeminiVoice(context)
                
                try {
                    com.example.api.QuotaManager.instance.totalVoiceRequests++
                    Log.d(TAG, "VOICE_REQUEST_STARTED: length=${cleanText.length}, voice=$selectedVoice")
                    val audioBytes = effectiveAI.generateSpeechAudio(
                        cleanText,
                        selectedVoice,
                        sentiment.label,
                        effectivePitch,
                        effectiveSpeed
                    )

                    if (audioBytes != null && audioBytes.isNotEmpty()) {
                        Log.d(TAG, "GEMINI_VOICE_PLAYBACK_STARTED: ${audioBytes.size} bytes")
                        geminiPlayer.playAudioBytes(audioBytes, text = cleanText, enqueue = enqueue) {
                            Log.d(TAG, "VOICE_PLAYBACK_COMPLETED")
                            updateSpeakingState(false)
                            onComplete?.invoke()
                        }
                        geminiSuccess = true
                    } else {
                        Log.w(TAG, "Gemini voice returned empty audio.")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Gemini Voice failed: ${e.message}")
                }
            }
            
            if (!geminiSuccess) {
                Log.w(TAG, "Falling back to Android TTS for guaranteed speech output.")
                withContext(Dispatchers.Main) {
                    geminiPlayer?.notifyCaption(cleanText)
                    try {
                        androidTts?.setPitch(1.02f)
                        androidTts?.setSpeechRate(1.0f)
                        val uId = "KAVYA_TTS_${System.currentTimeMillis()}"
                        androidTts?.speak(cleanText, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, uId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Android TTS fallback failed: ${e.message}")
                        updateSpeakingState(false)
                        onComplete?.invoke()
                    }
                }
            }
        }
        if (!enqueue) {
            activeSpeechJob = job
        }
    }

    suspend fun speakSuspending(text: String, explicitSentiment: Sentiment? = null) {
        val clean = cleanText(text)
        if (clean.isBlank()) return
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
            speak(clean, explicitSentiment, enqueue = false) {
                if (continuation.isActive) {
                    continuation.resume(Unit) {}
                }
            }
            // Timeout safety fallback after 10s if TTS hangs
            scope.launch {
                delay(10000)
                if (continuation.isActive) {
                    continuation.resume(Unit) {}
                }
            }
        }
    }

    fun setBaseVoiceProfile(pitch: Float, rate: Float) {
        // Now handled via AppPreferences directly in speak()
    }
    
    fun stop() {
        activeSpeechJob?.cancel()
        geminiPlayer?.stop()
        try {
            androidTts?.stop()
        } catch (e: Exception) {}
        Log.d(TAG, "VOICE_PLAYBACK_INTERRUPTED")
    }

    private fun analyzeSpeech(rawText: String): SpeechAnalysisResult {
        val detectedSentiment = extractSentimentTag(rawText) ?: inferSentiment(rawText)
        
        val basePitch = com.example.utils.AppPreferences.getVoicePitch(context).coerceIn(0.95f, 1.25f)
        val baseSpeed = com.example.utils.AppPreferences.getVoiceSpeed(context).coerceIn(0.80f, 1.25f)

        val effectivePitch = (basePitch * detectedSentiment.pitchMultiplier).coerceIn(0.95f, 1.25f)
        val effectiveSpeed = (baseSpeed * detectedSentiment.speedMultiplier).coerceIn(0.80f, 1.25f)

        val cleanSpoken = cleanText(rawText)

        return SpeechAnalysisResult(
            originalText = rawText,
            cleanSpokenText = cleanSpoken,
            sentiment = detectedSentiment,
            effectivePitch = effectivePitch,
            effectiveSpeed = effectiveSpeed
        )
    }

    private fun extractSentimentTag(text: String): Sentiment? {
        val match = SENTIMENT_TAG_REGEX.find(text) ?: return null
        val tagWord = match.groupValues[1].lowercase(Locale.ROOT)
        return when (tagWord) {
            "happy" -> Sentiment.HAPPY
            "sad" -> Sentiment.SAD
            "warm" -> Sentiment.WARM
            "empathetic" -> Sentiment.EMPATHETIC
            "excited" -> Sentiment.EXCITED
            "calm" -> Sentiment.CALM
            "witty" -> Sentiment.WITTY
            "confident" -> Sentiment.CONFIDENT
            "thoughtful" -> Sentiment.THOUGHTFUL
            "playful" -> Sentiment.PLAYFUL
            "focused" -> Sentiment.FOCUSED
            "neutral" -> Sentiment.NEUTRAL
            else -> Sentiment.NEUTRAL
        }
    }

    private fun inferSentiment(text: String): Sentiment {
        val lowerText = text.lowercase(Locale.ROOT)
        return when {
            lowerText.contains("yay") || lowerText.contains("wow") || lowerText.contains("awesome") -> Sentiment.EXCITED
            lowerText.contains("sorry") || lowerText.contains("apologize") || lowerText.contains("unfortunately") -> Sentiment.EMPATHETIC
            lowerText.contains("interesting") || lowerText.contains("let me think") || lowerText.contains("hmm") -> Sentiment.THOUGHTFUL
            lowerText.contains("hello") || lowerText.contains("hi there") || lowerText.contains("welcome") -> Sentiment.WARM
            lowerText.contains("haha") || lowerText.contains("joke") || lowerText.contains("funny") -> Sentiment.PLAYFUL
            lowerText.contains("focus") || lowerText.contains("step by step") || lowerText.contains("execute") -> Sentiment.FOCUSED
            else -> Sentiment.NEUTRAL
        }
    }

    fun cleanText(text: String): String {
        var clean = text.replace(SENTIMENT_TAG_REGEX, "")
        clean = com.example.agent.StructuredActionParser.stripActions(clean)
        clean = clean.replace("```[a-zA-Z]*\\s*[\\s\\S]*?```".toRegex(), "")
        clean = clean.replace("\\{[^}]*\\}".toRegex(), "")
        clean = clean.replace("\\[[^\\]]*\\]".toRegex(), "")
        clean = clean.replace("(?i)\\bjson\\b".toRegex(), "")
        clean = clean.replace("[*#_~`]+".toRegex(), "")
        return clean.trim()
    }
}

enum class Sentiment(val label: String, val pitchMultiplier: Float, val speedMultiplier: Float) {
    HAPPY("Sweet and happy", 1.05f, 1.05f),
    SAD("Soft and sad", 0.95f, 0.90f),
    WARM("Warm and comforting", 0.98f, 0.95f),
    EMPATHETIC("Empathetic and gentle", 0.95f, 0.92f),
    EXCITED("Excited and lively", 1.1f, 1.1f),
    CALM("Calm and relaxed", 0.95f, 0.90f),
    WITTY("Witty and playful", 1.02f, 1.05f),
    CONFIDENT("Confident and clear", 1.0f, 1.0f),
    THOUGHTFUL("Thoughtful and pondering", 0.98f, 0.90f),
    PLAYFUL("Playful and adorable", 1.08f, 1.05f),
    FOCUSED("Focused and serious", 0.95f, 0.98f),
    NEUTRAL("Natural and conversational", 1.0f, 1.0f)
}

data class SpeechAnalysisResult(
    val originalText: String,
    val cleanSpokenText: String,
    val sentiment: Sentiment,
    val effectivePitch: Float,
    val effectiveSpeed: Float
)
