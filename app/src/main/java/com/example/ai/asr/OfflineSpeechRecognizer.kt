package com.example.ai.asr

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import com.example.ai.OfflinePrivacyGuard
import com.example.ai.offline.GemmaModelManager
import com.example.ai.offline.ModelCatalog
import com.example.utils.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

sealed class OfflineAsrResult {
    data class Success(
        val transcript: String,
        val engineName: String,
        val languageTag: String,
        val latencyMs: Long
    ) : OfflineAsrResult()

    data class Unavailable(
        val reason: String,
        val missingArtifacts: List<String> = emptyList(),
        val unsupportedLanguage: String? = null
    ) : OfflineAsrResult()

    data class Error(
        val reason: String,
        val cause: Throwable? = null
    ) : OfflineAsrResult()
}

/**
 * Genuine On-Device Speech-to-Text Engine for Kavya AI.
 *
 * Uses Whisper Tiny Multilingual ONNX (`onnx-community/whisper-tiny`, Apache-2.0) executed
 * locally via Microsoft ONNX Runtime (`ai.onnxruntime`).
 *
 * - Accepts 16kHz 16-bit mono PCM captured by the single-owner [com.example.agent.MicrophoneEngine]
 *   (never creates a duplicate `AudioRecord` handle).
 * - Supports Hindi (`hi`), English (`en`), and Hinglish (`hi-Latn` / auto multilingual decoding).
 * - Never makes any network calls or silently falls back to cloud transcription.
 * - Reports explicit diagnostics when required local ASR model files or language packs are missing.
 */
class OfflineSpeechRecognizer private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "OfflineSpeechRecognizer"
        const val ENGINE_NAME = "Whisper-Tiny-Multilingual-ONNX"
        const val SAMPLE_RATE = 16_000
        private const val N_FFT = 400
        private const val HOP_LENGTH = 160
        private const val N_MELS = 80
        private const val N_FRAMES = 3000
        private const val MAX_DECODE_TOKENS = 128

        // Standard Whisper multilingual special token IDs (openai/whisper-tiny)
        private const val TOKEN_SOT = 50258L
        private const val TOKEN_EOT = 50257L
        private const val TOKEN_LANG_EN = 50259L
        private const val TOKEN_LANG_HI = 50276L
        private const val TOKEN_TRANSCRIBE = 50359L
        private const val TOKEN_NO_TIMESTAMPS = 50363L

        private val SUPPORTED_WHISPER_LANG_PREFIXES = setOf(
            "en", "hi", "hinglish", "hi-in", "hi-latn", "en-us", "en-in", "en-gb", "auto", ""
        )

        @Volatile
        private var instance: OfflineSpeechRecognizer? = null

        fun getInstance(context: Context): OfflineSpeechRecognizer {
            val targetCtx = context.applicationContext ?: context
            val current = instance
            if (current != null && current.appContext === targetCtx) return current
            return synchronized(this) {
                val again = instance
                if (again != null && again.appContext === targetCtx) {
                    again
                } else {
                    OfflineSpeechRecognizer(targetCtx).also { instance = it }
                }
            }
        }

        fun isLanguageSupportedByWhisper(languageTag: String?): Boolean {
            val normalized = languageTag.orEmpty().trim().lowercase(Locale.ROOT)
            if (normalized.isEmpty() || normalized == "auto") return true
            if (normalized in SUPPORTED_WHISPER_LANG_PREFIXES) return true
            val primary = normalized.substringBefore('-').substringBefore('_')
            return primary in setOf("en", "hi", "ur", "mr", "bn", "gu", "ta", "te", "kn", "ml", "pa", "es", "fr", "de", "ja", "ko", "zh")
        }
    }

    private val sessionMutex = Mutex()
    @Volatile private var ortEnv: OrtEnvironment? = null
    @Volatile private var encoderSession: OrtSession? = null
    @Volatile private var decoderSession: OrtSession? = null
    @Volatile private var idToToken: Map<Int, String> = emptyMap()
    @Volatile private var loadedModelDirPath: String? = null
    @Volatile var lastStatusMessage: String = "Offline speech recognition model not loaded."
        private set

    /**
     * Test hook for deterministic JVM/Robolectric verification of the local ASR routing path
     * without requiring a 150MB Whisper ONNX binary inside unit test resources.
     */
    @Volatile
    internal var testCustomPcmTranscriber: (suspend (ByteArray, Int, String) -> OfflineAsrResult)? = null

    fun getMissingArtifacts(): List<String> {
        val baseDir = GemmaModelManager.getModelDirectory(appContext)
        return ModelCatalog.WHISPER_TINY_ASR_MANIFEST.artifacts
            .filter { it.isMandatory }
            .mapNotNull { spec ->
                val file = File(baseDir, spec.relativePath)
                if (!file.isFile || file.length() <= 16L) spec.fileName else null
            }
    }

    fun isWhisperModelInstalled(): Boolean = getMissingArtifacts().isEmpty()

    suspend fun transcribePcm(
        pcmData: ByteArray,
        sampleRate: Int = SAMPLE_RATE,
        languageHint: String? = null
    ): OfflineAsrResult = withContext(Dispatchers.IO) {
        val resolvedLang = (languageHint ?: AppPreferences.getLanguage(appContext)).trim().ifBlank { "auto" }

        if (!isLanguageSupportedByWhisper(resolvedLang)) {
            val msg = "Offline speech language '$resolvedLang' is not supported by local Whisper ASR. Switch to Hindi, English, or Hinglish."
            lastStatusMessage = msg
            return@withContext OfflineAsrResult.Unavailable(
                reason = msg,
                unsupportedLanguage = resolvedLang
            )
        }

        val customHook = testCustomPcmTranscriber
        if (customHook != null) {
            val res = customHook.invoke(pcmData, sampleRate, resolvedLang)
            if (res is OfflineAsrResult.Success) {
                OfflinePrivacyGuard.recordEngineAttribution("LOCAL_ASR:$ENGINE_NAME")
                lastStatusMessage = "Transcribed locally via $ENGINE_NAME (${res.languageTag})"
            } else if (res is OfflineAsrResult.Unavailable) {
                lastStatusMessage = res.reason
            }
            return@withContext res
        }

        val missing = getMissingArtifacts()
        if (missing.isNotEmpty()) {
            val msg = "Offline speech model (${ModelCatalog.WHISPER_TINY_ASR_MANIFEST.modelId}) is not installed. Missing: ${missing.joinToString(", ")}. Download it in Offline Models while online to enable offline Hindi/English/Hinglish voice commands."
            lastStatusMessage = msg
            return@withContext OfflineAsrResult.Unavailable(
                reason = msg,
                missingArtifacts = missing
            )
        }

        if (pcmData.size < sampleRate / 4) {
            return@withContext OfflineAsrResult.Error("Audio clip too short for offline transcription.")
        }

        val startMs = System.currentTimeMillis()
        try {
            val loaded = ensureSessionsLoaded()
            if (loaded.isFailure) {
                val err = loaded.exceptionOrNull()?.message ?: "Could not initialize local Whisper ONNX runtime."
                lastStatusMessage = err
                return@withContext OfflineAsrResult.Error(err, loaded.exceptionOrNull())
            }

            val floatAudio = pcm16ToNormalizedFloats(pcmData, sampleRate)
            val melFeatures = computeLogMelSpectrogram(floatAudio)
            val transcript = runWhisperGreedyDecode(melFeatures, resolvedLang)
            val latency = System.currentTimeMillis() - startMs

            OfflinePrivacyGuard.recordEngineAttribution("LOCAL_ASR:$ENGINE_NAME")
            lastStatusMessage = "Local Whisper ASR completed in ${latency}ms ($resolvedLang)"
            OfflineAsrResult.Success(
                transcript = transcript.trim(),
                engineName = ENGINE_NAME,
                languageTag = resolvedLang,
                latencyMs = latency
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            val msg = "Offline speech recognition failed: ${t.localizedMessage ?: t.javaClass.simpleName}"
            lastStatusMessage = msg
            Log.e(TAG, msg, t)
            OfflineAsrResult.Error(msg, t)
        }
    }

    private suspend fun ensureSessionsLoaded(): Result<Unit> = sessionMutex.withLock {
        val baseDir = GemmaModelManager.getModelDirectory(appContext)
        val manifest = ModelCatalog.WHISPER_TINY_ASR_MANIFEST
        val encoderSpec = manifest.artifacts.firstOrNull { it.role == "asr_encoder_onnx" }
            ?: return@withLock Result.failure(IllegalStateException("Whisper encoder artifact missing from manifest."))
        val decoderSpec = manifest.artifacts.firstOrNull { it.role == "asr_decoder_onnx" }
            ?: return@withLock Result.failure(IllegalStateException("Whisper decoder artifact missing from manifest."))
        val tokenizerSpec = manifest.artifacts.firstOrNull { it.role == "asr_tokenizer_json" }
            ?: return@withLock Result.failure(IllegalStateException("Whisper tokenizer artifact missing from manifest."))

        val encoderFile = File(baseDir, encoderSpec.relativePath)
        val decoderFile = File(baseDir, decoderSpec.relativePath)
        val tokenizerFile = File(baseDir, tokenizerSpec.relativePath)

        if (!encoderFile.isFile || !decoderFile.isFile || !tokenizerFile.isFile) {
            return@withLock Result.failure(
                IllegalStateException("Whisper Tiny ONNX files incomplete on disk. Re-download the Offline Speech model.")
            )
        }

        val dirKey = encoderFile.parentFile?.absolutePath
        if (encoderSession != null && decoderSession != null && loadedModelDirPath == dirKey && idToToken.isNotEmpty()) {
            return@withLock Result.success(Unit)
        }

        closeInternal()
        return@withLock try {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }
            val enc = env.createSession(encoderFile.absolutePath, opts)
            val dec = env.createSession(decoderFile.absolutePath, opts)
            val vocab = parseWhisperTokenizerVocab(tokenizerFile)
            if (vocab.isEmpty()) {
                enc.close()
                dec.close()
                return@withLock Result.failure(IllegalStateException("Whisper tokenizer.json contained no valid vocabulary entries."))
            }
            ortEnv = env
            encoderSession = enc
            decoderSession = dec
            idToToken = vocab
            loadedModelDirPath = dirKey
            Log.i(TAG, "Loaded $ENGINE_NAME locally (vocab=${vocab.size})")
            Result.success(Unit)
        } catch (t: Throwable) {
            closeInternal()
            Result.failure(IllegalStateException("Failed to load local Whisper ONNX sessions: ${t.message}", t))
        }
    }

    private suspend fun runWhisperGreedyDecode(melFeatures: FloatArray, languageHint: String): String = sessionMutex.withLock {
        val env = ortEnv ?: throw IllegalStateException("ONNX environment unavailable")
        val enc = encoderSession ?: throw IllegalStateException("Whisper encoder not loaded")
        val dec = decoderSession ?: throw IllegalStateException("Whisper decoder not loaded")

        val encInputName = enc.inputNames.firstOrNull() ?: "input_features"
        val melTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(melFeatures),
            longArrayOf(1L, N_MELS.toLong(), N_FRAMES.toLong())
        )

        val generatedIds = mutableListOf<Int>()
        melTensor.use { mt ->
            enc.run(mapOf(encInputName to mt)).use { encResults ->
                val hiddenTensor = encResults.firstOrNull()?.value as? OnnxTensor
                    ?: throw IllegalStateException("Whisper encoder returned no hidden states")

                val promptTokens = buildInitialTokens(languageHint).toMutableList()
                for (step in 0 until MAX_DECODE_TOKENS) {
                    val decInputs = mutableMapOf<String, OnnxTensor>()
                    val ownedTensors = mutableListOf<OnnxTensor>()
                    try {
                        for ((inputName, nodeInfo) in dec.inputInfo) {
                            val tensorInfo = nodeInfo.info as? TensorInfo
                            when {
                                inputName.contains("input_ids") -> {
                                    val ids = LongArray(promptTokens.size) { promptTokens[it] }
                                    val t = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1L, ids.size.toLong()))
                                    decInputs[inputName] = t
                                    ownedTensors.add(t)
                                }
                                inputName.contains("encoder_hidden_states") -> {
                                    decInputs[inputName] = hiddenTensor
                                }
                                inputName.contains("use_cache_branch") -> {
                                    val t = OnnxTensor.createTensor(env, booleanArrayOf(false))
                                    decInputs[inputName] = t
                                    ownedTensors.add(t)
                                }
                                inputName.contains("past_key_values") && tensorInfo != null -> {
                                    val shape = tensorInfo.shape.map { d -> if (d <= 0L) 0L else d }.toLongArray()
                                    val elemCount = shape.fold(1L) { acc, v -> acc * v }.toInt().coerceAtLeast(0)
                                    val t = if (tensorInfo.type == OnnxJavaType.FLOAT16) {
                                        val bb = ByteBuffer.allocateDirect(elemCount * 2).order(ByteOrder.nativeOrder())
                                        OnnxTensor.createTensor(env, bb, shape, OnnxJavaType.FLOAT16)
                                    } else {
                                        OnnxTensor.createTensor(env, FloatBuffer.allocate(elemCount), shape)
                                    }
                                    decInputs[inputName] = t
                                    ownedTensors.add(t)
                                }
                            }
                        }

                        dec.run(decInputs).use { decResults ->
                            val logitsTensor = decResults.firstOrNull()?.value as? OnnxTensor
                                ?: break
                            val nextToken = argMaxLastStep(logitsTensor)
                            if (nextToken == TOKEN_EOT.toInt() || nextToken < 0) {
                                return@use
                            }
                            promptTokens.add(nextToken.toLong())
                            if (nextToken < 50257) {
                                generatedIds.add(nextToken)
                            }
                        }
                        if (promptTokens.lastOrNull() == TOKEN_EOT) break
                    } finally {
                        ownedTensors.forEach { runCatching { it.close() } }
                    }
                }
            }
        }

        return@withLock decodeWhisperTokens(generatedIds, idToToken)
    }

    private fun buildInitialTokens(languageHint: String): List<Long> {
        val norm = languageHint.lowercase(Locale.ROOT)
        val langToken = when {
            norm.startsWith("en") -> TOKEN_LANG_EN
            norm.startsWith("hi") && !norm.contains("latn") && norm != "hinglish" -> TOKEN_LANG_HI
            else -> TOKEN_LANG_HI // Multilingual Whisper decodes Devanagari + Latin Hinglish cleanly
        }
        return listOf(TOKEN_SOT, langToken, TOKEN_TRANSCRIBE, TOKEN_NO_TIMESTAMPS)
    }

    private fun argMaxLastStep(logitsTensor: OnnxTensor): Int {
        val info = logitsTensor.info as? TensorInfo ?: return -1
        val shape = info.shape
        if (shape.size < 3) return -1
        val seqLen = shape[1].toInt()
        val vocabSize = shape[2].toInt()
        if (seqLen <= 0 || vocabSize <= 0) return -1
        val fb = logitsTensor.floatBuffer ?: return -1
        val offset = (seqLen - 1) * vocabSize
        var bestIdx = 0
        var bestVal = Float.NEGATIVE_INFINITY
        for (i in 0 until vocabSize) {
            val v = fb.get(offset + i)
            if (v > bestVal) {
                bestVal = v
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun parseWhisperTokenizerVocab(file: File): Map<Int, String> {
        val result = HashMap<Int, String>(52_000)
        val root = JSONObject(file.readText())
        val modelObj = root.optJSONObject("model")
        val vocabObj = modelObj?.optJSONObject("vocab")
        if (vocabObj != null) {
            val keys = vocabObj.keys()
            while (keys.hasNext()) {
                val token = keys.next()
                val id = vocabObj.optInt(token, -1)
                if (id >= 0) result[id] = token
            }
        }
        val addedTokens = root.optJSONArray("added_tokens")
        if (addedTokens != null) {
            for (i in 0 until addedTokens.length()) {
                val item = addedTokens.optJSONObject(i) ?: continue
                val id = item.optInt("id", -1)
                val content = item.optString("content", "")
                if (id >= 0 && content.isNotEmpty()) result[id] = content
            }
        }
        return result
    }

    private fun decodeWhisperTokens(ids: List<Int>, vocab: Map<Int, String>): String {
        if (ids.isEmpty()) return ""
        val joined = ids.mapNotNull { vocab[it] }.joinToString("")
        // GPT-2 / Whisper byte-level BPE space marker 'Ġ' -> space
        return joined
            .replace('Ġ', ' ')
            .replace('Ċ', '\n')
            .replace(Regex("<\\|[^|]+\\|>"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun pcm16ToNormalizedFloats(pcmData: ByteArray, inputSampleRate: Int): FloatArray {
        val sampleCount = pcmData.size / 2
        val raw = FloatArray(sampleCount)
        val bb = ByteBuffer.wrap(pcmData, 0, sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            raw[i] = (bb.short.toFloat() / 32768.0f).coerceIn(-1.0f, 1.0f)
        }
        if (inputSampleRate == SAMPLE_RATE || inputSampleRate <= 0) return raw
        // Simple linear resampling to 16kHz
        val ratio = inputSampleRate.toDouble() / SAMPLE_RATE.toDouble()
        val outLen = max(1, (raw.size / ratio).toInt())
        return FloatArray(outLen) { idx ->
            val srcPos = idx * ratio
            val i0 = min(srcPos.toInt(), raw.size - 1)
            val i1 = min(i0 + 1, raw.size - 1)
            val frac = (srcPos - i0).toFloat()
            raw[i0] * (1f - frac) + raw[i1] * frac
        }
    }

    /**
     * Computes Whisper's 80-bin log-Mel spectrogram padded/truncated to 3000 frames (30s window).
     */
    internal fun computeLogMelSpectrogram(audio: FloatArray): FloatArray {
        val targetSamples = N_FRAMES * HOP_LENGTH
        val padded = FloatArray(targetSamples)
        val copyLen = min(audio.size, targetSamples)
        System.arraycopy(audio, 0, padded, 0, copyLen)

        val window = FloatArray(N_FFT) { n ->
            (0.5 * (1.0 - cos(2.0 * PI * n / N_FFT))).toFloat()
        }
        val nFreqs = N_FFT / 2 + 1
        val melFilters = buildSlaneyMelFilterbank(SAMPLE_RATE, N_FFT, N_MELS)
        val out = FloatArray(N_MELS * N_FRAMES)
        var maxVal = Float.NEGATIVE_INFINITY

        val fftRe = DoubleArray(nFreqs)
        val fftIm = DoubleArray(nFreqs)

        val framesToCompute = min(N_FRAMES, max(1, (copyLen + HOP_LENGTH - 1) / HOP_LENGTH))
        val cosTable = Array(nFreqs) { k -> DoubleArray(N_FFT) { n -> cos(2.0 * PI * k * n / N_FFT) } }
        val sinTable = Array(nFreqs) { k -> DoubleArray(N_FFT) { n -> sin(2.0 * PI * k * n / N_FFT) } }

        for (t in 0 until framesToCompute) {
            val start = t * HOP_LENGTH
            for (k in 0 until nFreqs) {
                var re = 0.0
                var im = 0.0
                val cRow = cosTable[k]
                val sRow = sinTable[k]
                for (n in 0 until N_FFT) {
                    val idx = start + n - N_FFT / 2
                    val sample = if (idx in padded.indices) padded[idx] * window[n] else 0f
                    if (sample != 0f) {
                        re += sample * cRow[n]
                        im -= sample * sRow[n]
                    }
                }
                fftRe[k] = re
                fftIm[k] = im
            }

            for (m in 0 until N_MELS) {
                var melEnergy = 0.0
                val filterRow = melFilters[m]
                for (k in 0 until nFreqs) {
                    val w = filterRow[k]
                    if (w > 0.0) {
                        val magSq = fftRe[k] * fftRe[k] + fftIm[k] * fftIm[k]
                        melEnergy += w * magSq
                    }
                }
                val logVal = log10(max(melEnergy, 1e-10)).toFloat()
                out[m * N_FRAMES + t] = logVal
                if (logVal > maxVal) maxVal = logVal
            }
        }

        val floorVal = if (maxVal.isFinite()) maxVal - 8.0f else -8.0f
        for (i in out.indices) {
            val clamped = max(out[i], floorVal)
            out[i] = (clamped + 4.0f) / 4.0f
        }
        return out
    }

    private fun buildSlaneyMelFilterbank(sampleRate: Int, nFft: Int, nMels: Int): Array<DoubleArray> {
        val nFreqs = nFft / 2 + 1
        fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

        val minMel = hzToMel(0.0)
        val maxMel = hzToMel(sampleRate / 2.0)
        val melPoints = DoubleArray(nMels + 2) { i ->
            minMel + (maxMel - minMel) * i / (nMels + 1)
        }
        val hzPoints = DoubleArray(melPoints.size) { melToHz(melPoints[it]) }
        val binFreqs = DoubleArray(nFreqs) { k -> k.toDouble() * sampleRate / nFft }

        return Array(nMels) { m ->
            val left = hzPoints[m]
            val center = hzPoints[m + 1]
            val right = hzPoints[m + 2]
            val enorm = 2.0 / max(1e-6, right - left)
            DoubleArray(nFreqs) { k ->
                val f = binFreqs[k]
                val weight = when {
                    f in left..center && center > left -> (f - left) / (center - left)
                    f in center..right && right > center -> (right - f) / (right - center)
                    else -> 0.0
                }
                weight * enorm
            }
        }
    }

    fun close() {
        closeInternal()
    }

    private fun closeInternal() {
        runCatching { encoderSession?.close() }
        runCatching { decoderSession?.close() }
        encoderSession = null
        decoderSession = null
        loadedModelDirPath = null
        idToToken = emptyMap()
    }
}
