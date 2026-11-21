package com.example.ai.tts

import android.content.Context
import android.util.Log
import com.example.agent.MicrophoneEngine
import com.example.ai.ChatterboxResult
import com.example.ai.ChatterboxTtsClient
import com.example.ai.GeminiAudioPlayer
import com.example.ai.VoiceProfileManager
import com.example.utils.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

enum class VoiceEngine(val id: String, val displayName: String, val isOffline: Boolean) {
    GEMINI_VOICE("GEMINI_VOICE", "Gemini Voice (Default • Natural Multilingual)", false),
    KOKORO_82M("KOKORO_82M", "Kokoro-82M Bilingual (Hindi + English Female)", true),
    INDIC_TINY_TTS("INDIC_TINY_TTS", "Indic Tiny TTS Hindi (Optional Offline Female)", true),
    CHATTERBOX("CHATTERBOX_HINDI", "Chatterbox Multilingual Hindi", false);

    companion object {
        val GEMINI_PREBUILT_VOICES: List<String> = listOf(
            "Kore", "Aoede", "Puck", "Charon", "Fenrir", "Leda", "Orus", "Zephyr"
        )

        fun fromId(id: String): VoiceEngine =
            entries.find { it.id.equals(id, ignoreCase = true) } ?: GEMINI_VOICE
    }
}

/**
 * Centralized speech routing.
 * Defaults to Gemini Voice (with built-in Android System TTS fallback when offline or unconfigured)
 * so zero model downloads are required out of the box.
 * Preserves Strict Engine Isolation when optional local engines (Kokoro-82M / Indic Tiny TTS / Chatterbox) are selected.
 */
class VoiceEngineManager private constructor(private val appContext: Context) {
    companion object {
        private const val TAG = "VoiceEngineManager"
        const val PREVIEW_SAMPLE_HINDI =
            "नमस्ते! मैं काव्या हूँ, आपकी एआई सहायिका। मैं आपकी सहायता करने के लिए तैयार हूँ।"
        const val PREVIEW_SAMPLE_BILINGUAL =
            "नमस्ते! Kavya ready hai. Please tap Continue on the screen to proceed."

        @Volatile private var instance: VoiceEngineManager? = null

        fun getInstance(context: Context): VoiceEngineManager {
            val targetCtx = context.applicationContext ?: context
            val current = instance
            if (current != null && current.appContext === targetCtx) {
                return current
            }
            return synchronized(this) {
                val again = instance
                if (again != null && again.appContext === targetCtx) {
                    again
                } else {
                    again?.stop()
                    VoiceEngineManager(targetCtx).also { instance = it }
                }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val kokoroEngine = KokoroTtsEngine(appContext)
    private val indicTinyEngine = IndicTinyTtsEngine(appContext)
    private val chatterboxClient = ChatterboxTtsClient(appContext)
    private val voiceProfileManager = VoiceProfileManager.getInstance(appContext)
    private val kavyaAiTts by lazy { com.example.ai.KavyaAI(appContext) }
    @Volatile private var androidSystemTts: android.speech.tts.TextToSpeech? = null
    @Volatile private var androidSystemTtsReady: Boolean = false
    @Volatile private var systemTtsListenerRegistered: Boolean = false
    private val systemTtsCompletions = java.util.concurrent.ConcurrentHashMap<String, CompletionOnce>()
    private val activeSystemTtsUtterances = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val internalPlayer = GeminiAudioPlayer(
        appContext,
        onSpeakingStateChanged = { speaking -> updateSpeakingState(speaking) },
        onAudioChunkStarted = { caption -> onAudioChunkStarted?.invoke(caption) }
    )
    @Volatile private var attachedPlayer: GeminiAudioPlayer? = null
    private val player: GeminiAudioPlayer
        get() = attachedPlayer ?: internalPlayer
    private val playerSpeakingListener: (Boolean) -> Unit = { speaking -> updateSpeakingState(speaking) }
    private val playerCaptionListener: (String) -> Unit = { caption -> onAudioChunkStarted?.invoke(caption) }
    private val synthesisMutex = Mutex()
    private var activeSpeechJob: Job? = null
    private val activeSpeechJobs = java.util.Collections.synchronizedSet(mutableSetOf<Job>())
    private val pendingCompletions = java.util.Collections.synchronizedSet(mutableSetOf<CompletionOnce>())

    @Volatile var isSpeaking: Boolean = false
        private set

    var onSpeakingStateChanged: ((Boolean) -> Unit)? = null
    var onAudioChunkStarted: ((String) -> Unit)? = null
    var onSpeechError: ((String) -> Unit)? = null

    private inner class CompletionOnce(private val callback: () -> Unit) : () -> Unit {
        private val fired = AtomicBoolean(false)
        override fun invoke() {
            if (fired.compareAndSet(false, true)) {
                pendingCompletions.remove(this)
                callback()
            }
        }
    }

    @Synchronized
    fun attachAudioPlayer(audioPlayer: GeminiAudioPlayer?) {
        if (audioPlayer === internalPlayer || audioPlayer === attachedPlayer) return
        attachedPlayer?.removeSpeakingStateListener(playerSpeakingListener)
        attachedPlayer?.removeCaptionListener(playerCaptionListener)
        attachedPlayer = audioPlayer
        if (audioPlayer != null) {
            audioPlayer.addSpeakingStateListener(playerSpeakingListener)
            audioPlayer.addCaptionListener(playerCaptionListener)
            updateSpeakingState(audioPlayer.isSpeaking())
        } else {
            updateSpeakingState(internalPlayer.isSpeaking())
        }
    }

    fun getKokoroEngine(): KokoroTtsEngine = kokoroEngine

    fun getActiveEngine(): VoiceEngine =
        VoiceEngine.fromId(AppPreferences.getSpeechEngine(appContext))

    fun setActiveEngine(engine: VoiceEngine) {
        AppPreferences.setSpeechEngine(appContext, engine.id)
        Log.i(TAG, "Active voice engine set to: " + engine.displayName)
    }

    fun getGeminiVoiceName(): String =
        AppPreferences.getGeminiVoice(appContext).ifBlank { "Kore" }

    fun setGeminiVoiceName(voiceName: String) {
        val cleaned = voiceName.trim().ifBlank { "Kore" }
        AppPreferences.setGeminiVoice(appContext, cleaned)
        setActiveEngine(VoiceEngine.GEMINI_VOICE)
    }

    fun speak(
        text: String,
        enqueue: Boolean = false,
        onComplete: (() -> Unit)? = null,
        speed: Float = AppPreferences.getVoiceSpeed(appContext),
        pitch: Float = AppPreferences.getVoicePitch(appContext),
        emotion: String = "natural"
    ) {
        speakUsingEngine(text, getActiveEngine(), enqueue, onComplete, speed, pitch, emotion)
    }

    private fun speakUsingEngine(
        text: String,
        engine: VoiceEngine,
        enqueue: Boolean,
        onComplete: (() -> Unit)?,
        speed: Float = 1.0f,
        pitch: Float = 1.0f,
        emotion: String = "natural"
    ) {
        if (text.isBlank()) {
            onComplete?.invoke()
            return
        }
        if (!enqueue) stop()
        val completion = onComplete?.let { CompletionOnce(it).also { item -> pendingCompletions.add(item) } }

        Log.d(TAG, "Routing speech through " + engine.displayName)
        val speechJob = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            var playbackQueued = false
            try {
                synthesisMutex.withLock {
                    updateSpeakingState(true)
                    when (engine) {
                        VoiceEngine.GEMINI_VOICE -> {
                            val isStrictOffline = com.example.ai.OfflinePrivacyGuard.isStrictOfflineMode(appContext)
                            val audioBytes = if (isStrictOffline) {
                                null
                            } else {
                                val selectedVoice = AppPreferences.getGeminiVoice(appContext).ifBlank { "Kore" }
                                kavyaAiTts.generateSpeechAudio(
                                    text = text,
                                    voiceName = selectedVoice,
                                    emotionLabel = emotion,
                                    pitchMultiplier = pitch,
                                    speedMultiplier = speed
                                )
                            }
                            if (audioBytes != null && audioBytes.isNotEmpty()) {
                                com.example.ai.OfflinePrivacyGuard.recordEngineAttribution("CLOUD_TTS:GEMINI_VOICE")
                                player.playWav(
                                    wavBytes = audioBytes,
                                    text = text,
                                    enqueue = enqueue,
                                    onComplete = completion
                                )
                                playbackQueued = true
                            } else if (speakViaAndroidSystemTts(text, speed, pitch, enqueue, completion)) {
                                com.example.ai.OfflinePrivacyGuard.recordEngineAttribution("LOCAL_TTS:ANDROID_SYSTEM_TTS")
                                playbackQueued = true
                            } else {
                                withContext(Dispatchers.Main) {
                                    onSpeechError?.invoke("Offline Android System TTS is unavailable or missing required language voice data.")
                                    completion?.invoke()
                                }
                                updateSpeakingState(false)
                            }
                        }
                        VoiceEngine.KOKORO_82M -> {
                            // Never silently fall back to Indic Tiny TTS or cloud TTS on Kokoro errors.
                            var segmentIdx = 0
                            val res = kokoroEngine.synthesizeSegmented(
                                text = text,
                                speed = speed.coerceIn(0.6f, 2.0f)
                            ) { segment, pcm16Bytes, sampleRate ->
                                val shouldEnqueue = enqueue || segmentIdx > 0
                                player.playPcm(
                                    pcmBytes = pcm16Bytes,
                                    sampleRate = sampleRate,
                                    text = segment.text,
                                    enqueue = shouldEnqueue,
                                    onComplete = null
                                )
                                playbackQueued = true
                                segmentIdx++
                            }
                            if (res.isFailure) {
                                throw (res.exceptionOrNull() ?: IllegalStateException("Kokoro-82M synthesis failed"))
                            }
                            com.example.ai.OfflinePrivacyGuard.recordEngineAttribution("LOCAL_TTS:KOKORO_82M")
                            if (playbackQueued && completion != null) {
                                player.playPcm(
                                    pcmBytes = ByteArray(2),
                                    sampleRate = KokoroTtsEngine.SAMPLE_RATE,
                                    text = null,
                                    enqueue = true,
                                    onComplete = completion
                                )
                            } else if (!playbackQueued) {
                                withContext(Dispatchers.Main) { completion?.invoke() }
                            }
                        }
                        VoiceEngine.INDIC_TINY_TTS -> {
                            val pcm = indicTinyEngine.synthesizeSpeech(text, speed = speed.coerceIn(0.25f, 4.0f))
                            if (pcm.isEmpty()) {
                                throw IllegalStateException("Indic Tiny TTS returned empty audio.")
                            }
                            com.example.ai.OfflinePrivacyGuard.recordEngineAttribution("LOCAL_TTS:INDIC_TINY_TTS")
                            player.playPcm(
                                pcmBytes = pcm,
                                sampleRate = IndicTinyTtsEngine.SAMPLE_RATE,
                                text = text,
                                enqueue = enqueue,
                                onComplete = completion
                            )
                            playbackQueued = true
                        }
                        VoiceEngine.CHATTERBOX -> {
                            if (com.example.ai.OfflinePrivacyGuard.isStrictOfflineMode(appContext)) {
                                throw com.example.ai.StrictOfflineBlockedException(
                                    operation = "tts:chatterbox",
                                    message = "Chatterbox TTS requires a remote backend and is blocked while Strict Offline Mode is enabled. Switch to Kokoro-82M or Offline System TTS."
                                )
                            }
                            val activeProfile = voiceProfileManager.getActiveProfile()
                            when (val result = chatterboxClient.synthesize(
                                text = text,
                                referenceAudioPath = activeProfile.audioFilePath,
                                pitchMultiplier = pitch,
                                speedMultiplier = speed,
                                emotion = emotion
                            )) {
                                is ChatterboxResult.Success -> {
                                    com.example.ai.OfflinePrivacyGuard.recordEngineAttribution("CLOUD_TTS:CHATTERBOX")
                                    player.playWav(
                                        wavBytes = result.audioBytes,
                                        text = text,
                                        enqueue = enqueue,
                                        onComplete = completion
                                    )
                                    playbackQueued = true
                                }
                                is ChatterboxResult.Error -> {
                                    throw IllegalStateException(result.message)
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                updateSpeakingState(false)
                if (!playbackQueued) withContext(Dispatchers.Main) { completion?.invoke() }
                throw cancelled
            } catch (e: Exception) {
                Log.e(TAG, "Speech synthesis failed with " + engine.displayName, e)
                if (!player.isSpeaking()) updateSpeakingState(false)
                withContext(Dispatchers.Main) {
                    onSpeechError?.invoke(e.message ?: "Speech generation failed.")
                    if (!playbackQueued) completion?.invoke()
                }
            }
        }
        activeSpeechJob = speechJob
        activeSpeechJobs.add(speechJob)
        speechJob.invokeOnCompletion { activeSpeechJobs.remove(speechJob) }
        speechJob.start()
    }

    private suspend fun speakViaAndroidSystemTts(
        text: String,
        speed: Float,
        pitch: Float,
        enqueue: Boolean,
        completion: CompletionOnce?
    ): Boolean = withContext(Dispatchers.Main) {
        try {
            val tts = androidSystemTts ?: kotlinx.coroutines.withTimeoutOrNull(4_000L) {
                kotlinx.coroutines.suspendCancellableCoroutine<android.speech.tts.TextToSpeech?> { cont ->
                    var created: android.speech.tts.TextToSpeech? = null
                    created = android.speech.tts.TextToSpeech(appContext) { status ->
                        androidSystemTtsReady = (status == android.speech.tts.TextToSpeech.SUCCESS)
                        if (cont.isActive) {
                            cont.resumeWith(Result.success(if (androidSystemTtsReady) created else null))
                        }
                    }
                    androidSystemTts = created
                }
            } ?: return@withContext false

            if (!androidSystemTtsReady) return@withContext false

            if (!systemTtsListenerRegistered) {
                tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) {
                        if (id != null) activeSystemTtsUtterances.add(id)
                        updateSpeakingState(true)
                    }

                    override fun onDone(id: String?) {
                        handleSystemTtsTerminal(id, errorCode = null)
                    }

                    @Deprecated("Deprecated in API 21")
                    override fun onError(id: String?) {
                        handleSystemTtsTerminal(id, errorCode = android.speech.tts.TextToSpeech.ERROR)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        handleSystemTtsTerminal(utteranceId, errorCode = errorCode)
                    }

                    private fun handleSystemTtsTerminal(id: String?, errorCode: Int?) {
                        if (id != null) activeSystemTtsUtterances.remove(id)
                        val cb = id?.let { systemTtsCompletions.remove(it) }
                        if (activeSystemTtsUtterances.isEmpty() && !player.isSpeaking()) {
                            updateSpeakingState(false)
                        }
                        if (errorCode != null) {
                            Log.w(TAG, "Android System TTS utterance failed (id=$id, code=$errorCode)")
                        }
                        if (cb != null) {
                            scope.launch(Dispatchers.Main) { cb.invoke() }
                        }
                    }
                })
                systemTtsListenerRegistered = true
            }

            if (!enqueue) {
                activeSystemTtsUtterances.clear()
                val flushed = systemTtsCompletions.values.toList()
                systemTtsCompletions.clear()
                flushed.forEach { cb -> scope.launch(Dispatchers.Main) { cb.invoke() } }
            }

            tts.setSpeechRate(speed.coerceIn(0.6f, 2.0f))
            tts.setPitch(pitch.coerceIn(0.6f, 1.5f))

            val segments = KokoroLanguageSegmenter.segmentText(
                rawText = text,
                languageMode = KokoroLanguageMode.AUTO_HINDI_ENGLISH,
                hindiVoiceId = AppPreferences.getKokoroHindiVoice(appContext),
                englishVoiceId = AppPreferences.getKokoroEnglishVoice(appContext)
            ).ifEmpty {
                listOf(
                    KokoroSpeechSegment(
                        text = text.trim(),
                        voiceId = AppPreferences.getKokoroEnglishVoice(appContext),
                        locale = if (text.any { it in '\u0900'..'\u097F' }) "hi-IN" else "en-US",
                        script = if (text.any { it in '\u0900'..'\u097F' }) KokoroSpeechScript.HINDI_DEVANAGARI else KokoroSpeechScript.ENGLISH_LATIN
                    )
                )
            }

            var queuedAny = false
            segments.forEachIndexed { idx, seg ->
                val targetLocale = if (seg.script == KokoroSpeechScript.HINDI_DEVANAGARI) {
                    java.util.Locale("hi", "IN")
                } else {
                    java.util.Locale.US
                }
                val langStatus = tts.isLanguageAvailable(targetLocale)
                if (langStatus >= android.speech.tts.TextToSpeech.LANG_AVAILABLE) {
                    tts.language = targetLocale
                } else {
                    val fallbackLocale = java.util.Locale.US
                    if (tts.isLanguageAvailable(fallbackLocale) >= android.speech.tts.TextToSpeech.LANG_AVAILABLE) {
                        tts.language = fallbackLocale
                    }
                }

                val isLast = idx == segments.lastIndex
                val utteranceId = "kavya_tts_${System.nanoTime()}_$idx"
                activeSystemTtsUtterances.add(utteranceId)
                if (isLast && completion != null) {
                    systemTtsCompletions[utteranceId] = completion
                }
                val queueMode = if (enqueue || idx > 0) {
                    android.speech.tts.TextToSpeech.QUEUE_ADD
                } else {
                    android.speech.tts.TextToSpeech.QUEUE_FLUSH
                }
                val res = tts.speak(seg.text, queueMode, null, utteranceId)
                if (res != android.speech.tts.TextToSpeech.SUCCESS) {
                    activeSystemTtsUtterances.remove(utteranceId)
                    systemTtsCompletions.remove(utteranceId)
                } else {
                    queuedAny = true
                }
            }
            queuedAny
        } catch (t: Throwable) {
            Log.w(TAG, "Android system TTS fallback unavailable: ${t.message}")
            false
        }
    }

    fun previewEngine(
        engine: VoiceEngine,
        sampleText: String = when (engine) {
            VoiceEngine.KOKORO_82M, VoiceEngine.GEMINI_VOICE -> PREVIEW_SAMPLE_BILINGUAL
            else -> PREVIEW_SAMPLE_HINDI
        },
        onComplete: (() -> Unit)? = null
    ) {
        speakUsingEngine(sampleText, engine, enqueue = false, onComplete = onComplete, speed = 1.0f, pitch = 1.0f, emotion = "natural")
    }

    fun stop() {
        val jobs = synchronized(activeSpeechJobs) { activeSpeechJobs.toList().also { activeSpeechJobs.clear() } }
        jobs.forEach { it.cancel() }
        activeSpeechJob = null
        player.stop()
        activeSystemTtsUtterances.clear()
        systemTtsCompletions.clear()
        try {
            androidSystemTts?.stop()
        } catch (_: Throwable) {}
        val callbacks = synchronized(pendingCompletions) { pendingCompletions.toList() }
        callbacks.forEach { completion ->
            scope.launch(Dispatchers.Main) { completion.invoke() }
        }
        updateSpeakingState(false)
    }

    fun onTrimMemory(level: Int) {
        kokoroEngine.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            try {
                indicTinyEngine.release()
            } catch (_: Throwable) {}
        }
    }

    private fun updateSpeakingState(speaking: Boolean) {
        if (isSpeaking == speaking) return
        isSpeaking = speaking
        MicrophoneEngine.getInstance(appContext).setAssistantSpeaking(speaking)
        scope.launch(Dispatchers.Main) {
            onSpeakingStateChanged?.invoke(speaking)
        }
    }
}
