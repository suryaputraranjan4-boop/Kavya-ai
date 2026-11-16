package com.example.ai.tts

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.ai.offline.GemmaModelManager
import com.example.ai.offline.ModelCatalog
import com.example.ai.offline.ModelDownloadCoordinator
import com.example.ai.offline.ModelLifecycleState
import com.example.utils.AppPreferences
import com.google.mediapipe.tasks.audio.texttospeech.AudioResult
import com.google.mediapipe.tasks.audio.texttospeech.SynthesisOptions
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeech
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeechOptions
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

enum class KokoroVariantPreference(val id: String, val displayName: String) {
    AUTO("AUTO", "Auto (GPU OpenCL -> CPU Fallback)"),
    GPU_FP16("GPU_FP16", "GPU OpenCL FP16 (kokoro_int8_gpu_fp16)"),
    CPU_FP16("CPU_FP16", "CPU XNNPACK (kokoro_int8_fp16)");

    companion object {
        fun fromId(id: String): KokoroVariantPreference =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO
    }
}

/**
 * Offline Kokoro-82M bilingual Hindi + English TTS Engine powered by MediaPipe Tasks Audio
 * (`com.google.mediapipe.tasks.audio.texttospeech.TextToSpeech`) using verified Hugging Face
 * `.litertlm` bundles from `litert-community/Kokoro-82M`.
 *
 * - Keeps APK minSdk=24 while gating native Kokoro execution safely to Android API 26+ & arm64-v8a.
 * - Loads model weights once and switches female Hindi/English voice IDs dynamically per segment.
 * - Fails loudly and honestly if model is missing/corrupt or warmup fails; never fakes readiness.
 */
class KokoroTtsEngine(private val context: Context) {

    companion object {
        private const val TAG = "KokoroTtsEngine"
        const val SAMPLE_RATE = 24_000
        const val MIN_SUPPORTED_API = 26

        fun isDeviceSupported(): Boolean {
            if (Build.VERSION.SDK_INT < MIN_SUPPORTED_API) return false
            val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
            return abis.isEmpty() || abis.contains("arm64-v8a")
        }

        fun floatPcmToPcm16Bytes(samples: FloatArray): ByteArray {
            val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in samples) {
                val clamped = sample.coerceIn(-1.0f, 1.0f)
                buf.putShort((clamped * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
            }
            return buf.array()
        }
    }

    private val appContext = context.applicationContext
    private val runtimeMutex = Mutex()
    @Volatile private var ttsInstance: TextToSpeech? = null
    @Volatile private var loadedModelPath: String? = null
    @Volatile private var loadedDelegate: Delegate? = null
    @Volatile private var warmupPassed: Boolean = false
    @Volatile var lastError: String? = null
        private set

    fun isModelInstalled(): Boolean = findInstalledKokoroArtifact() != null

    fun isRuntimeReady(): Boolean = ttsInstance != null && warmupPassed

    fun getInstalledVariantLabel(): String? {
        val pair = findInstalledKokoroArtifact() ?: return null
        return pair.first
    }

    fun getAvailableFemaleVoices(): List<KokoroVoiceOption> =
        KokoroLanguageSegmenter.ALL_ALLOWED_FEMALE_VOICES

    private fun findInstalledKokoroArtifact(): Pair<String, File>? {
        val baseDir = GemmaModelManager.getModelDirectory(appContext)
        val pref = KokoroVariantPreference.fromId(AppPreferences.getKokoroVariant(appContext))
        val gpuSpec = ModelCatalog.KOKORO_GPU_MANIFEST.artifacts.first()
        val cpuSpec = ModelCatalog.KOKORO_CPU_MANIFEST.artifacts.first()
        val gpuFile = File(baseDir, gpuSpec.relativePath)
        val cpuFile = File(baseDir, cpuSpec.relativePath)

        val candidates: List<Pair<String, File>> = when (pref) {
            KokoroVariantPreference.GPU_FP16 -> listOf("GPU_FP16" to gpuFile)
            KokoroVariantPreference.CPU_FP16 -> listOf("CPU_FP16" to cpuFile)
            KokoroVariantPreference.AUTO -> listOf("GPU_FP16" to gpuFile, "CPU_FP16" to cpuFile)
        }
        return candidates.firstOrNull { (_, f) -> f.isFile && f.length() > 1_000_000L }
    }

    suspend fun ensureLoadedAndWarmedUp(): Result<String> = runtimeMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!isDeviceSupported()) {
                val msg = "Kokoro-82M requires Android 8.0+ (API 26+) on arm64-v8a."
                lastError = msg
                return@withContext Result.failure(IllegalStateException("UNSUPPORTED_DEVICE: $msg"))
            }

            val artifact = findInstalledKokoroArtifact()
                ?: return@withContext Result.failure(
                    IllegalStateException("Kokoro-82M model is not installed. Download Kokoro-82M in Voice Studio or AI Hub first.")
                )

            val (variantName, modelFile) = artifact
            val requestedDelegate = if (variantName == "GPU_FP16") Delegate.GPU else Delegate.CPU

            if (ttsInstance != null && loadedModelPath == modelFile.absolutePath && warmupPassed) {
                return@withContext Result.success(variantName)
            }

            closeInternal()
            val coordinator = ModelDownloadCoordinator.getInstance(appContext)
            val modelId = if (variantName == "GPU_FP16") {
                ModelCatalog.KOKORO_GPU_MANIFEST.modelId
            } else {
                ModelCatalog.KOKORO_CPU_MANIFEST.modelId
            }

            try {
                val defaultVoice = AppPreferences.getKokoroEnglishVoice(appContext)
                val cacheDir = File(appContext.cacheDir, "kokoro_tts").apply { mkdirs() }.absolutePath
                val baseOpts = BaseOptions.builder()
                    .setModelAssetPath(modelFile.absolutePath)
                    .setDelegate(requestedDelegate)
                    .build()
                val options = TextToSpeechOptions(
                    baseOptions = baseOpts,
                    defaultVoiceId = defaultVoice,
                    defaultLocale = "en-US",
                    speakingRate = 1.0f,
                    cacheDir = cacheDir
                )

                val created = TextToSpeech.create(options)
                // Warmup smoke synthesis before declaring Ready
                val warmupResult: AudioResult = created.synthesize(
                    "Ready.",
                    SynthesisOptions(voiceId = defaultVoice, locale = "en-US")
                )
                val warmupSamples = warmupResult.samples
                if (warmupSamples.isEmpty()) {
                    created.close()
                    val msg = "Kokoro warmup synthesis produced empty audio."
                    lastError = msg
                    return@withContext Result.failure(IllegalStateException(msg))
                }

                ttsInstance = created
                loadedModelPath = modelFile.absolutePath
                loadedDelegate = requestedDelegate
                warmupPassed = true
                lastError = null
                coordinator.refreshAllStates()
                Result.success("$variantName ($modelId)")
            } catch (t: Throwable) {
                Log.e(TAG, "Kokoro initialization failed for $variantName: ${t.message}", t)
                closeInternal()
                // Clean retry on CPU variant ONLY if AUTO and CPU file also exists
                if (variantName == "GPU_FP16" &&
                    KokoroVariantPreference.fromId(AppPreferences.getKokoroVariant(appContext)) == KokoroVariantPreference.AUTO
                ) {
                    val cpuFile = File(
                        GemmaModelManager.getModelDirectory(appContext),
                        ModelCatalog.KOKORO_CPU_MANIFEST.artifacts.first().relativePath
                    )
                    if (cpuFile.isFile && cpuFile.length() > 1_000_000L) {
                        try {
                            val defaultVoice = AppPreferences.getKokoroEnglishVoice(appContext)
                            val cacheDir = File(appContext.cacheDir, "kokoro_tts").apply { mkdirs() }.absolutePath
                            val cpuOpts = TextToSpeechOptions(
                                baseOptions = BaseOptions.builder()
                                    .setModelAssetPath(cpuFile.absolutePath)
                                    .setDelegate(Delegate.CPU)
                                    .build(),
                                defaultVoiceId = defaultVoice,
                                defaultLocale = "en-US",
                                speakingRate = 1.0f,
                                cacheDir = cacheDir
                            )
                            val cpuCreated = TextToSpeech.create(cpuOpts)
                            val warmup = cpuCreated.synthesize(
                                "Ready.",
                                SynthesisOptions(voiceId = defaultVoice, locale = "en-US")
                            )
                            if (warmup.samples.isNotEmpty()) {
                                ttsInstance = cpuCreated
                                loadedModelPath = cpuFile.absolutePath
                                loadedDelegate = Delegate.CPU
                                warmupPassed = true
                                lastError = null
                                return@withContext Result.success("CPU_FP16")
                            }
                            cpuCreated.close()
                        } catch (cpuErr: Throwable) {
                            Log.e(TAG, "Kokoro CPU fallback init also failed", cpuErr)
                        }
                    }
                }
                val errMsg = "Kokoro runtime failed to initialize: ${t.localizedMessage ?: t.message ?: "unknown error"}"
                lastError = errMsg
                Result.failure(IllegalStateException(errMsg, t))
            }
        }
    }

    /**
     * Synthesizes bilingual text by segmenting Hindi / Hinglish / English clauses and invoking
     * the single loaded Kokoro engine per segment with female voice ID switching.
     */
    suspend fun synthesizeSegmented(
        text: String,
        speed: Float = 1.0f,
        onSegmentReady: suspend (segment: KokoroSpeechSegment, pcm16Bytes: ByteArray, sampleRate: Int) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        val loadRes = ensureLoadedAndWarmedUp()
        if (loadRes.isFailure) {
            return@withContext Result.failure(loadRes.exceptionOrNull() ?: IllegalStateException("Kokoro not ready"))
        }

        val langMode = KokoroLanguageMode.fromId(AppPreferences.getKokoroLanguageMode(appContext))
        val hindiVoice = AppPreferences.getKokoroHindiVoice(appContext)
        val englishVoice = AppPreferences.getKokoroEnglishVoice(appContext)
        val segments = KokoroLanguageSegmenter.segmentText(
            rawText = text,
            languageMode = langMode,
            hindiVoiceId = hindiVoice,
            englishVoiceId = englishVoice
        )
        if (segments.isEmpty()) {
            return@withContext Result.success(0)
        }

        val engine = ttsInstance ?: return@withContext Result.failure(IllegalStateException("Kokoro engine unloaded"))
        var emittedCount = 0

        for (segment in segments) {
            ensureActive()
            if (!KokoroLanguageSegmenter.isAllowedFemaleVoice(segment.voiceId)) {
                return@withContext Result.failure(IllegalStateException("Non-female Kokoro voice blocked: ${segment.voiceId}"))
            }
            try {
                val synOpts = SynthesisOptions(
                    voiceId = segment.voiceId,
                    locale = segment.locale
                )
                val audioResult = runtimeMutex.withLock {
                    engine.synthesize(segment.text, synOpts)
                }
                val floats = audioResult.samples
                if (floats.isEmpty()) {
                    return@withContext Result.failure(IllegalStateException("Kokoro returned empty PCM for segment '${segment.text.take(24)}'"))
                }
                val rate = audioResult.sampleRateHz.takeIf { it > 0 } ?: SAMPLE_RATE
                val pcm16 = floatPcmToPcm16Bytes(floats)
                onSegmentReady(segment, pcm16, rate)
                emittedCount++
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                val err = "Kokoro synthesis failed for ${segment.voiceId}: ${t.localizedMessage ?: t.message}"
                lastError = err
                return@withContext Result.failure(IllegalStateException(err, t))
            }
        }
        Result.success(emittedCount)
    }

    fun onTrimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            close()
        }
    }

    fun close() {
        closeInternal()
    }

    private fun closeInternal() {
        try {
            ttsInstance?.close()
        } catch (_: Throwable) {}
        ttsInstance = null
        loadedModelPath = null
        loadedDelegate = null
        warmupPassed = false
    }
}
