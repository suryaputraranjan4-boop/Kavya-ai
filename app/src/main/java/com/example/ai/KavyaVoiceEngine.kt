package com.example.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.util.Locale

class KavyaVoiceEngine(
    private val context: Context,
    private val geminiPlayer: GeminiAudioPlayer? = null,
    private val kavyaAI: KavyaAI? = null
) {

    companion object {
        private const val TAG = "KavyaVoiceEngine"
        private val SENTIMENT_TAG_REGEX = "(?i)\\[(happy|sad|warm|empathetic|excited|calm|witty|confident|thoughtful|playful|focused|neutral)\\]".toRegex()
        private const val ERROR_THROTTLE_WINDOW_MS = 15000L
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var activeSpeechJob: Job? = null
    private var lastErrorSpeechTimestamp = 0L

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
        Log.i(TAG, "KavyaVoiceEngine initialized with pure Gemini Cloud Voice audio synthesis (Zero Android TTS).")
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

    private fun isTechnicalErrorString(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return lower.contains("http") || lower.contains("exception") || lower.contains("status code") ||
                lower.contains("429") || lower.contains("503") || lower.contains("500") ||
                lower.contains("quota") || lower.contains("rate limit") || lower.contains("ratelimit") ||
                lower.contains("resource exhausted") || lower.contains("resource_exhausted") ||
                lower.contains("timeout") || lower.contains("connection refused") ||
                lower.contains("high traffic") || lower.contains("server busy") ||
                lower.contains("overloaded") || lower.contains("stack trace") ||
                lower.contains("failed to connect") || lower.contains("sockettimeout") ||
                lower.contains("network error")
    }

    fun speak(
        text: String,
        explicitSentiment: Sentiment? = null,
        enqueue: Boolean = false,
        onComplete: (() -> Unit)? = null
    ) {
        val cleanText = cleanText(text)
        if (cleanText.isBlank()) {
            updateSpeakingState(false)
            onComplete?.invoke()
            return
        }

        // Suppress raw technical errors from ever being spoken aloud
        if (isTechnicalErrorString(cleanText)) {
            Log.w(TAG, "Suppressed technical error string from voice output: $cleanText")
            updateSpeakingState(false)
            val now = System.currentTimeMillis()
            if (now - lastErrorSpeechTimestamp > ERROR_THROTTLE_WINDOW_MS) {
                lastErrorSpeechTimestamp = now
                speakCleanFallback(enqueue, onComplete)
            } else {
                onComplete?.invoke()
            }
            return
        }

        val sentiment = explicitSentiment ?: inferSentiment(text)
        val basePitch = com.example.utils.AppPreferences.getVoicePitch(context).coerceIn(0.95f, 1.25f)
        val baseSpeed = com.example.utils.AppPreferences.getVoiceSpeed(context).coerceIn(0.80f, 1.25f)

        val effectivePitch = (basePitch * sentiment.pitchMultiplier).coerceIn(0.95f, 1.25f)
        val effectiveSpeed = (baseSpeed * sentiment.speedMultiplier).coerceIn(0.80f, 1.25f)

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
                    Log.w(TAG, "Gemini Voice generation failed: ${e.message}")
                }
            }

            if (!geminiSuccess) {
                // If Gemini voice generation fails, NEVER use Android TTS and NEVER trigger recursive speech requests
                Log.w(TAG, "Gemini voice synthesis unavailable. Gracefully terminating voice turn without robotic TTS fallback.")
                withContext(Dispatchers.Main) {
                    geminiPlayer?.notifyCaption(cleanText)
                    updateSpeakingState(false)
                    onComplete?.invoke()
                }
            }
        }
        if (!enqueue) {
            activeSpeechJob = job
        }
    }

    private fun speakCleanFallback(enqueue: Boolean, onComplete: (() -> Unit)?) {
        val naturalFallback = "अभी connection में थोड़ी problem है, थोड़ी देर में फिर try करती हूँ।"
        val selectedVoice = com.example.utils.AppPreferences.getGeminiVoice(context)
        val effectiveAI = kavyaAI ?: KavyaAI(context)
        scope.launch(Dispatchers.IO) {
            try {
                val audio = effectiveAI.generateSpeechAudio(naturalFallback, selectedVoice, "Calm and relaxed", 1.0f, 1.0f)
                if (audio != null && audio.isNotEmpty() && geminiPlayer != null) {
                    geminiPlayer.playAudioBytes(audio, text = naturalFallback, enqueue = enqueue) {
                        updateSpeakingState(false)
                        onComplete?.invoke()
                    }
                } else {
                    updateSpeakingState(false)
                    onComplete?.invoke()
                }
            } catch (_: Exception) {
                updateSpeakingState(false)
                onComplete?.invoke()
            }
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
            // Timeout safety fallback after 10s if network hangs
            scope.launch {
                delay(10000)
                if (continuation.isActive) {
                    continuation.resume(Unit) {}
                }
            }
        }
    }

    fun setBaseVoiceProfile(pitch: Float, rate: Float) {
        // Handled via AppPreferences directly in speak()
    }

    fun stop() {
        activeSpeechJob?.cancel()
        geminiPlayer?.stop()
        updateSpeakingState(false)
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
