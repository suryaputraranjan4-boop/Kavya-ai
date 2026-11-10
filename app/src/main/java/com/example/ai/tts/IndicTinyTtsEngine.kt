package com.example.ai.tts

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Random
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.PI

/**
 * Android/ONNX Runtime port of the official Indic Tiny TTS ONNX inference flow.
 * Model contract: Hindi, 24 kHz mono, female/male speaker IDs from config.json.
 */
class IndicTinyTtsEngine(private val context: Context) {
    companion object {
        private const val TAG = "IndicTinyTtsEngine"
        const val SAMPLE_RATE = 24000
        private const val N_FFT = 1024
        private const val HOP = 256
        private const val MAX_CHUNK = 250
        private const val N_TIMESTEPS = 4
        private const val TEMPERATURE = 0.667f
        private const val FRAME_MULTIPLE = 2
        private const val MEL_MEAN = -1.73840439356988f
        private const val MEL_STD = 2.352135264770519f
        private const val PAUSE_SAMPLES = SAMPLE_RATE * 150 / 1000
    }

    private val modelManager = IndicTinyTtsModelManager.getInstance(context)
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private var vocoder: OrtSession? = null
    private var vocabulary: Map<String, Int> = emptyMap()
    private var femaleSpeakerId = 2L
    private var maleSpeakerId = 3L
    private var languageId = 1L
    private var styleId = 0L
    @Volatile private var initialized = false

    @Synchronized
    fun initializeSessions() {
        if (initialized) return
        check(modelManager.verifyModelIntegrity()) {
            "Indic Tiny TTS model files failed integrity verification. Re-download the model in Voice Studio."
        }
        val root = modelManager.modelDir
        val config = JSONObject(File(root, "config.json").readText(Charsets.UTF_8))
        require(config.optString("language") == "hi") { "Downloaded model is not the Hindi model." }
        require(config.optInt("sample_rate", 0) == SAMPLE_RATE) { "Unexpected model sample rate." }

        val voices = config.getJSONObject("voices")
        val ids = config.getJSONObject("ids")
        val speakerIds = ids.getJSONObject("speakers")
        femaleSpeakerId = speakerIds.getLong(voices.getString("female"))
        maleSpeakerId = speakerIds.getLong(voices.getString("male"))
        languageId = ids.getLong("language")
        styleId = ids.getJSONObject("styles").getLong(config.getJSONObject("synthesis").getString("style"))

        val symbols = config.getJSONObject("vocab").getJSONArray("symbols")
        val idsBySymbol = HashMap<String, Int>(symbols.length())
        for (i in 0 until symbols.length()) idsBySymbol[symbols.getString(i)] = i
        vocabulary = idsBySymbol

        val options = OrtSession.SessionOptions()
        try {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setInterOpNumThreads(1)
            options.setIntraOpNumThreads(2)
            encoder = env.createSession(File(root, "onnx/text_encoder.onnx").absolutePath, options)
            decoder = env.createSession(File(root, "onnx/flow_decoder.onnx").absolutePath, options)
            vocoder = env.createSession(File(root, "onnx/vocoder.onnx").absolutePath, options)
            validateGraphSignatures()
            initialized = true
            Log.i(TAG, "Indic Tiny TTS Hindi ONNX sessions initialized.")
        } catch (t: Throwable) {
            release()
            throw IllegalStateException("Could not initialize the official Indic Tiny TTS ONNX graphs.", t)
        } finally {
            options.close()
        }
    }

    private fun validateGraphSignatures() {
        val e = encoder ?: error("Text encoder unavailable")
        val d = decoder ?: error("Flow decoder unavailable")
        val v = vocoder ?: error("Vocoder unavailable")
        require(e.inputNames.containsAll(setOf("tokens", "token_lengths", "language", "speaker", "style")))
        require(e.outputNames.containsAll(setOf("mu_x", "logw", "cond")))
        require(d.inputNames.containsAll(setOf("x", "mask", "mu", "t", "cond")))
        require(d.outputNames.contains("v"))
        require(v.inputNames.contains("mel"))
        require(v.outputNames.containsAll(setOf("real", "imag")))
    }

    suspend fun synthesizeSpeech(
        text: String,
        voice: String = "female",
        speed: Float = 1.0f,
        seed: Long? = null
    ): ByteArray = withContext(Dispatchers.Default) {
        require(speed in 0.25f..4.0f) { "Speech speed must be between 0.25 and 4.0." }
        require(voice == "female" || voice == "male") { "Voice must be female or male." }
        require(modelManager.isModelReady()) { "Indic Tiny TTS Hindi is not installed. Download it in Voice Studio." }
        initializeSessions()

        val normalized = HindiTextNormalizer.normalize(text)
        if (normalized.isBlank()) return@withContext ByteArray(0)
        val chunks = splitIntoChunks(normalized)
        if (chunks.isEmpty()) return@withContext ByteArray(0)

        val random = if (seed == null) Random() else Random(seed)
        val output = ByteArrayOutputStream()
        var hasAudio = false
        for (chunk in chunks) {
            currentCoroutineContext().ensureActive()
            val ids = encodeCharacters(chunk)
            if (ids.isEmpty()) continue
            val wav = synthesizeChunk(ids, if (voice == "female") femaleSpeakerId else maleSpeakerId, speed, random)
            if (wav.isEmpty()) continue
            if (hasAudio) output.write(ByteArray(PAUSE_SAMPLES * 2))
            writePcm16(output, wav)
            hasAudio = true
        }
        output.toByteArray()
    }

    private fun splitIntoChunks(text: String): List<String> {
        val sentences = Regex("(?<=[।?!.])\\s+").split(text)
        val result = ArrayList<String>()
        for (part in sentences) {
            var remaining = part.trim()
            while (remaining.length > MAX_CHUNK) {
                val comma = remaining.lastIndexOf(',', MAX_CHUNK - 1)
                val cut = if (comma > 0) {
                    comma + 1
                } else {
                    val space = remaining.lastIndexOf(' ', MAX_CHUNK - 1)
                    if (space > 0) space else MAX_CHUNK
                }
                result.add(remaining.substring(0, cut).trim())
                remaining = remaining.substring(cut).trim()
            }
            if (remaining.isNotBlank()) result.add(remaining)
        }
        return result.filter { value -> value.any { it.isLetter() } }
    }

    private fun encodeCharacters(text: String): LongArray {
        val result = ArrayList<Long>(text.length * 2 + 1)
        val blank = vocabulary["<blank>"]?.toLong() ?: 0L
        result.add(blank)
        for (ch in text) {
            val token = vocabulary["c:" + ch]
            val fallback = when (ch) {
                ':', ';' -> vocabulary["c:,"]
                '…' -> vocabulary["c:."]
                '–', '—' -> vocabulary["c:,"]
                else -> null
            }
            val id = token ?: fallback ?: vocabulary["c: "] ?: continue
            result.add(id.toLong())
            result.add(blank)
        }
        return result.toLongArray()
    }

    private suspend fun synthesizeChunk(ids: LongArray, speaker: Long, speed: Float, random: Random): FloatArray {
        currentCoroutineContext().ensureActive()
        val encSession = encoder ?: error("Text encoder is not initialized")
        val decSession = decoder ?: error("Flow decoder is not initialized")
        val vocSession = vocoder ?: error("Vocoder is not initialized")

        val encoderInputs = linkedMapOf<String, OnnxTensor>()
        val encResult: OrtSession.Result
        try {
            encoderInputs["tokens"] = tensorLong(longArrayOf(1, ids.size.toLong()), ids)
            encoderInputs["token_lengths"] = tensorLong(longArrayOf(1), longArrayOf(ids.size.toLong()))
            encoderInputs["language"] = tensorLong(longArrayOf(1), longArrayOf(languageId))
            encoderInputs["speaker"] = tensorLong(longArrayOf(1), longArrayOf(speaker))
            encoderInputs["style"] = tensorLong(longArrayOf(1), longArrayOf(styleId))
            encResult = encSession.run(encoderInputs)
        } finally {
            encoderInputs.values.forEach { it.close() }
        }

        val encoded = encResult.use { result ->
            Triple(
                result.get("mu_x").get().value as Array<Array<FloatArray>>,
                result.get("logw").get().value as Array<Array<FloatArray>>,
                result.get("cond").get().value as Array<FloatArray>
            )
        }
        val muX = encoded.first
        val logW = encoded.second
        val cond = encoded.third
        val tokenCount = ids.size
        require(muX[0].all { it.size == tokenCount } && logW[0][0].size == tokenCount) {
            "Text encoder returned unexpected tensor dimensions."
        }

        val lengthScale = 1.0f / speed
        val durations = FloatArray(tokenCount) { index ->
            val predicted = exp(logW[0][0][index].toDouble()).toFloat()
            ceil(predicted.toDouble()).toFloat() * lengthScale
        }
        val melLength = max(1, durations.sumOf { it.toDouble() }.toFloat().toInt())
        if (melLength < 2) return FloatArray(0)
        val paddedLength = ((melLength + FRAME_MULTIPLE - 1) / FRAME_MULTIPLE) * FRAME_MULTIPLE
        require(paddedLength <= 12000) { "Generated speech chunk is too long for the device." }

        val channels = muX[0].size
        val muY = Array(channels) { FloatArray(paddedLength) }
        val mask = FloatArray(paddedLength)
        var frame = 0
        var cumulativeDuration = 0.0
        for (token in 0 until tokenCount) {
            cumulativeDuration = (cumulativeDuration + durations[token].toDouble()).toFloat().toDouble()
            val tokenEndFrame = ceil(cumulativeDuration).toInt().coerceAtMost(melLength)
            while (frame < tokenEndFrame) {
                mask[frame] = 1.0f
                for (channel in 0 until channels) muY[channel][frame] = muX[0][channel][token]
                frame++
            }
        }
        while (frame < melLength) {
            mask[frame] = 1.0f
            frame++
        }

        var x = Array(channels) { FloatArray(paddedLength) }
        for (c in 0 until channels) for (t in 0 until paddedLength) {
            x[c][t] = (random.nextGaussian() * TEMPERATURE).toFloat()
        }
        val tSpan = FloatArray(N_TIMESTEPS + 1) { it.toFloat() / N_TIMESTEPS.toFloat() }
        for (step in 1 until tSpan.size) {
            currentCoroutineContext().ensureActive()
            val dt = tSpan[step] - tSpan[step - 1]
            val xFlat = flatten3d(x)
            val muFlat = flatten3d(muY)
            val maskFlat = mask
            val inputs = linkedMapOf<String, OnnxTensor>()
            val result: OrtSession.Result
            try {
                inputs["x"] = tensorFloat(longArrayOf(1, channels.toLong(), paddedLength.toLong()), xFlat)
                inputs["mask"] = tensorFloat(longArrayOf(1, 1, paddedLength.toLong()), maskFlat)
                inputs["mu"] = tensorFloat(longArrayOf(1, channels.toLong(), paddedLength.toLong()), muFlat)
                inputs["t"] = tensorFloat(longArrayOf(1), floatArrayOf(tSpan[step - 1]))
                inputs["cond"] = tensorFloat(longArrayOf(1, cond[0].size.toLong()), cond[0])
                result = decSession.run(inputs)
            } finally {
                inputs.values.forEach { it.close() }
            }
            val velocity = result.use { it.get("v").get().value as Array<Array<FloatArray>> }
            for (c in 0 until channels) for (t in 0 until paddedLength) {
                x[c][t] += dt * velocity[0][c][t]
            }
        }

        val mel = FloatArray(channels * melLength)
        for (c in 0 until channels) for (t in 0 until melLength) {
            mel[c * melLength + t] = x[c][t] * MEL_STD + MEL_MEAN
        }
        val vocInputs = linkedMapOf<String, OnnxTensor>()
        val vocResult: OrtSession.Result
        try {
            vocInputs["mel"] = tensorFloat(longArrayOf(1, channels.toLong(), melLength.toLong()), mel)
            vocResult = vocSession.run(vocInputs)
        } finally {
            vocInputs.values.forEach { it.close() }
        }
        val spectra = vocResult.use { result ->
            Pair(
                result.get("real").get().value as Array<Array<FloatArray>>,
                result.get("imag").get().value as Array<Array<FloatArray>>
            )
        }
        val real = spectra.first
        val imag = spectra.second
        return inverseStft(real[0], imag[0])
    }

    private fun runtimeEnvForTensor(): OrtEnvironment = env

    private fun tensorLong(shape: LongArray, data: LongArray): OnnxTensor =
        OnnxTensor.createTensor(runtimeEnvForTensor(), LongBuffer.wrap(data), shape)

    private fun tensorFloat(shape: LongArray, data: FloatArray): OnnxTensor =
        OnnxTensor.createTensor(runtimeEnvForTensor(), FloatBuffer.wrap(data), shape)

    private fun flatten3d(data: Array<FloatArray>): FloatArray {
        val size = data.sumOf { it.size }
        val out = FloatArray(size)
        var index = 0
        for (row in data) for (value in row) out[index++] = value
        return out
    }

    /** Vocos ISTFT: periodic Hann window, inverse real FFT, overlap-add, center trim. */
    private fun inverseStft(real: Array<FloatArray>, imag: Array<FloatArray>): FloatArray {
        val bins = real.size
        require(bins == N_FFT / 2 + 1 && imag.size == bins) { "Vocoder returned unexpected FFT bins." }
        val frames = real[0].size
        require(frames >= 2) { "Vocoder returned too few mel frames." }
        val total = N_FFT + HOP * (frames - 1)
        val output = DoubleArray(total)
        val envelope = DoubleArray(total)
        val window = DoubleArray(N_FFT) { i -> 0.5 - 0.5 * cos(2.0 * PI * i / N_FFT) }

        for (frameIndex in 0 until frames) {
            val re = DoubleArray(N_FFT)
            val im = DoubleArray(N_FFT)
            for (k in 0 until bins) {
                re[k] = real[k][frameIndex].toDouble()
                im[k] = imag[k][frameIndex].toDouble()
            }
            im[0] = 0.0
            im[N_FFT / 2] = 0.0
            for (k in 1 until N_FFT / 2) {
                re[N_FFT - k] = re[k]
                im[N_FFT - k] = -im[k]
            }
            fft(re, im, inverse = true)
            val offset = frameIndex * HOP
            for (i in 0 until N_FFT) {
                output[offset + i] += re[i] * window[i]
                envelope[offset + i] += window[i] * window[i]
            }
        }

        val trim = N_FFT / 2
        val length = total - 2 * trim
        val waveform = FloatArray(length)
        for (i in 0 until length) {
            val envValue = envelope[i + trim]
            check(envValue >= 1e-11) { "ISTFT overlap-add normalization failed." }
            waveform[i] = (output[i + trim] / envValue).toFloat().coerceIn(-1f, 1f)
        }
        return waveform
    }

    private fun fft(real: DoubleArray, imag: DoubleArray, inverse: Boolean) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val angle = (if (inverse) 2.0 else -2.0) * PI / len
            val wLenR = cos(angle)
            val wLenI = kotlin.math.sin(angle)
            var start = 0
            while (start < n) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until len / 2) {
                    val uR = real[start + k]
                    val uI = imag[start + k]
                    val vR = real[start + k + len / 2] * wr - imag[start + k + len / 2] * wi
                    val vI = real[start + k + len / 2] * wi + imag[start + k + len / 2] * wr
                    real[start + k] = uR + vR
                    imag[start + k] = uI + vI
                    real[start + k + len / 2] = uR - vR
                    imag[start + k + len / 2] = uI - vI
                    val nextWr = wr * wLenR - wi * wLenI
                    wi = wr * wLenI + wi * wLenR
                    wr = nextWr
                }
                start += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) {
            real[i] /= n.toDouble()
            imag[i] /= n.toDouble()
        }
    }

    private fun writePcm16(out: ByteArrayOutputStream, samples: FloatArray) {
        for (sample in samples) {
            val value = (sample.coerceIn(-1f, 1f) * 32767f).toInt()
            out.write(value and 0xff)
            out.write((value shr 8) and 0xff)
        }
    }

    @Synchronized
    fun release() {
        try { encoder?.close() } catch (_: Exception) {}
        try { decoder?.close() } catch (_: Exception) {}
        try { vocoder?.close() } catch (_: Exception) {}
        encoder = null
        decoder = null
        vocoder = null
        initialized = false
    }
}
