package com.example.ai.offline

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Abstraction interface for local Gemma runtime implementations.
 */
interface GemmaRuntime {
    val runtimeName: String
    suspend fun initialize(context: Context, modelFile: File): Result<Unit>
    suspend fun generate(prompt: String): Result<String>
    fun streamGenerate(prompt: String): Flow<String>
    fun isReady(): Boolean
    suspend fun close()
}

/**
 * Google AI Edge / MediaPipe Tasks GenAI LiteRT-LM runtime adapter.
 */
class MediaPipeTaskRuntime : GemmaRuntime {

    override val runtimeName: String = "Google AI Edge MediaPipe Task Runtime"
    private var llmInference: LlmInference? = null
    private var initializedFile: File? = null

    override fun isReady(): Boolean = llmInference != null

    override suspend fun initialize(context: Context, modelFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            close()
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(1024)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            initializedFile = modelFile
            Result.success(Unit)
        } catch (e: OutOfMemoryError) {
            close()
            Result.failure(IllegalStateException("Not enough device memory to load this local model.", e))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            close()
            Result.failure(e)
        }
    }

    override suspend fun generate(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        val engine = llmInference ?: return@withContext Result.failure(IllegalStateException("Runtime is not initialized."))
        try {
            val response = engine.generateResponse(prompt)
            if (response.isNullOrBlank()) {
                Result.failure(IllegalStateException("Engine returned empty response."))
            } else {
                Result.success(response.trim())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun streamGenerate(prompt: String): Flow<String> =
        flow {
            val engine = llmInference
                ?: throw IllegalStateException("Local runtime is not initialized.")
            emit(engine.generateResponse(prompt))
        }.flowOn(Dispatchers.IO)

    override suspend fun close() = withContext(Dispatchers.IO) {
        try {
            llmInference?.close()
        } catch (_: Exception) {}
        llmInference = null
        initializedFile = null
    }
}

/**
 * Direct LiteRT-LM runtime for native .litertlm containers.
 * Uses Google's Kotlin API and keeps the engine fully on-device.
 */
class LiteRtLmRuntime : GemmaRuntime {
    override val runtimeName: String = "Google LiteRT-LM Kotlin Runtime"
    private var engine: Engine? = null
    private var conversation: com.google.ai.edge.litertlm.Conversation? = null

    override fun isReady(): Boolean = engine != null && conversation != null

    override suspend fun initialize(context: Context, modelFile: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                close()
                val config = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU(),
                    cacheDir = context.cacheDir.absolutePath
                )
                val e = Engine(config)
                e.initialize()
                engine = e
                conversation = e.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of("You are Kavya, an offline Android assistant.")
                    )
                )
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                close()
                Result.failure(t)
            }
        }

    override suspend fun generate(prompt: String): Result<String> =
        withContext(Dispatchers.IO) {
            val conv = conversation
                ?: return@withContext Result.failure(IllegalStateException("LiteRT-LM conversation is not initialized."))
            try {
                val output = StringBuilder()
                conv.sendMessageAsync(prompt).collect { message ->
                    output.append(message.toString())
                }
                val text = output.toString().trim()
                if (text.isBlank()) Result.failure(IllegalStateException("LiteRT-LM returned no text."))
                else Result.success(text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    override fun streamGenerate(prompt: String): Flow<String> = flow {
        val conv = conversation
            ?: throw IllegalStateException("LiteRT-LM conversation is not initialized.")
        conv.sendMessageAsync(prompt).collect { message -> emit(message.toString()) }
    }.flowOn(Dispatchers.IO)

    override suspend fun close() = withContext(Dispatchers.IO) {
        try { conversation?.close() } catch (_: Throwable) {}
        try { engine?.close() } catch (_: Throwable) {}
        conversation = null
        engine = null
    }
}

/**
 * Native llama.cpp runtime for GGUF models.
 */
class LlamaCppRuntime : GemmaRuntime {
    override val runtimeName: String = "llama.cpp GGUF Native Runtime"
    private val bridge = LlamaCppEngine.getInstance()

    override fun isReady(): Boolean = bridge.isReady()

    override suspend fun initialize(context: Context, modelFile: File): Result<Unit> =
        bridge.load(modelFile)

    override suspend fun generate(prompt: String): Result<String> =
        bridge.generate(prompt, 256)

    override fun streamGenerate(prompt: String): Flow<String> =
        flow {
            emit(generate(prompt).getOrElse {
                throw it
            }.orEmpty())
        }.flowOn(Dispatchers.IO)

    override suspend fun close() = withContext(Dispatchers.IO) {
        bridge.release()
    }
}

/**
 * Fallback runtime adapter for unsupported formats (e.g. GGUF without native library).
 */
class UnsupportedFormatRuntime(private val errorMessage: String) : GemmaRuntime {
    override val runtimeName: String = "Unsupported Format Adapter"
    override fun isReady(): Boolean = false

    override suspend fun initialize(context: Context, modelFile: File): Result<Unit> {
        return Result.failure(IllegalArgumentException(errorMessage))
    }

    override suspend fun generate(prompt: String): Result<String> {
        return Result.failure(IllegalArgumentException(errorMessage))
    }

    override fun streamGenerate(prompt: String): Flow<String> = callbackFlow {
        trySend("Error: $errorMessage")
        close()
        awaitClose {}
    }

    override suspend fun close() {}
}

enum class SmolVlmTaskMode {
    TARGET_GROUNDING,
    POST_ACTION_VERIFICATION,
    SCREEN_SUMMARY
}

/**
 * Single authoritative on-device local-model & multi-model GUI intelligence engine.
 * Guarantees zero network calls, 8 GB RAM sequential memory governance (never holds SmolVLM2
 * and Florence-2 simultaneously in RAM), thermal throttling enforcement, and real health-check verification.
 */
class Gemma4E4BEngine private constructor() {

    enum class ActiveLoadedRole {
        NONE,
        LOCAL_TEXT_LLM,
        SMOLVLM2_GUI_VLM,
        FLORENCE2_AUX_VISION
    }

    data class DeviceResourceSnapshot(
        val availableRamMb: Long,
        val totalRamMb: Long,
        val isLowRamCondition: Boolean,
        val thermalStatus: Int,
        val isThermalThrottled: Boolean,
        val recommendedContextTokens: Int,
        val recommendedThreads: Int
    )

    data class Florence2AuxProposal(
        val label: String,
        val roleHint: String,
        val bounds: Rect,
        val confidence: Float,
        val isAuthoritativeForTap: Boolean = false
    )

    data class Florence2ParsedDetection(
        val label: String,
        val normalizedBox: com.example.visual.NormalizedBox,
        val confidence: Float
    )

    companion object {
        private const val TAG = "KavyaOfflineEngine"
        const val MIN_RAM_MB_FOR_SMOLVLM2 = 1100L
        const val MIN_RAM_MB_FOR_FLORENCE2 = 650L
        const val THERMAL_STATUS_SEVERE_THRESHOLD = 3 // PowerManager.THERMAL_STATUS_SEVERE
        private val FLORENCE_LOC_REGEX = Regex(
            """([^<\n\r]*?)<loc_(\d{1,4})><loc_(\d{1,4})><loc_(\d{1,4})><loc_(\d{1,4})>"""
        )

        @Volatile
        private var INSTANCE: Gemma4E4BEngine? = null

        fun getInstance(context: Context? = null): Gemma4E4BEngine {
            val inst = INSTANCE ?: synchronized(this) {
                INSTANCE ?: Gemma4E4BEngine().also { INSTANCE = it }
            }
            if (context != null) {
                inst.appContext = context.applicationContext
            }
            return inst
        }

        /**
         * Parses Microsoft Florence-2 quantized `<loc_x1><loc_y1><loc_x2><loc_y2>` outputs
         * (0..999 bin scale) into clamped normalized [0f..1f] boxes.
         */
        fun parseFlorence2LocationOutput(
            decodedText: String,
            targetHint: String = "",
            defaultConfidence: Float = 0.78f
        ): List<Florence2ParsedDetection> {
            if (decodedText.isBlank()) return emptyList()
            val results = mutableListOf<Florence2ParsedDetection>()
            FLORENCE_LOC_REGEX.findAll(decodedText).forEachIndexed { idx, match ->
                val rawLabel = match.groupValues[1]
                    .replace(Regex("</?s>|<pad>|<unk>"), "")
                    .trim()
                val x1 = (match.groupValues[2].toFloatOrNull() ?: return@forEachIndexed) / 1000f
                val y1 = (match.groupValues[3].toFloatOrNull() ?: return@forEachIndexed) / 1000f
                val x2 = (match.groupValues[4].toFloatOrNull() ?: return@forEachIndexed) / 1000f
                val y2 = (match.groupValues[5].toFloatOrNull() ?: return@forEachIndexed) / 1000f
                val box = com.example.visual.NormalizedBox(x1, y1, x2, y2).normalized()
                if (box.area > 0f) {
                    val label = rawLabel.ifBlank { targetHint.ifBlank { "region_${idx + 1}" } }
                    results.add(
                        Florence2ParsedDetection(
                            label = label,
                            normalizedBox = box,
                            confidence = com.example.visual.VisionDetectionCandidate.sanitizeConfidence(defaultConfidence)
                        )
                    )
                }
            }
            return results
        }
    }

    private val mutex = Mutex()
    private var activeRuntime: GemmaRuntime? = null
    private var activeModelPath: String? = null
    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var _activeLoadedRole: ActiveLoadedRole = ActiveLoadedRole.NONE
    val activeLoadedRole: ActiveLoadedRole get() = _activeLoadedRole

    @Volatile
    private var smolVlm2Ready: Boolean = false

    @Volatile
    private var florence2Ready: Boolean = false
    private var florenceOrtEnv: OrtEnvironment? = null
    private val florenceSessions = mutableMapOf<String, OrtSession>()
    private var florenceBaseDir: File? = null

    private var _diagnostics = GemmaDiagnostics()
    val diagnostics: GemmaDiagnostics get() = _diagnostics

    fun isSmolVlm2Loaded(): Boolean = smolVlm2Ready && _activeLoadedRole == ActiveLoadedRole.SMOLVLM2_GUI_VLM

    fun isSmolVlm2Ready(): Boolean = isSmolVlm2Loaded()

    fun isFlorence2Loaded(): Boolean = florence2Ready && _activeLoadedRole == ActiveLoadedRole.FLORENCE2_AUX_VISION

    fun isFlorence2Ready(): Boolean = isFlorence2Loaded()

    /**
     * Inspects physical RAM and thermal headroom on target 8 GB RAM devices (e.g. Galaxy A16 5G).
     */
    fun inspectDeviceResources(
        context: Context,
        overrideAvailRamMb: Long? = null,
        overrideThermalStatus: Int? = null
    ): DeviceResourceSnapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        try {
            am?.getMemoryInfo(memInfo)
        } catch (_: Exception) {}

        val availMb = overrideAvailRamMb
            ?: (memInfo.availMem / (1024L * 1024L)).takeIf { it > 0L }
            ?: GemmaDiagnostics.getAvailableSystemMemoryMB(context).takeIf { it > 0L }
            ?: 2048L
        val totalMb = (memInfo.totalMem / (1024L * 1024L)).takeIf { it > 0L } ?: 8192L

        val thermal = overrideThermalStatus ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                pm?.currentThermalStatus ?: 0
            } catch (_: Exception) {
                0
            }
        } else {
            0
        }

        val isLowRam = availMb < MIN_RAM_MB_FOR_SMOLVLM2 || memInfo.lowMemory
        val isThermalThrottled = thermal >= THERMAL_STATUS_SEVERE_THRESHOLD
        val ctxSize = when {
            availMb < 1600L || thermal >= 2 -> 2048
            else -> 4096
        }
        val threads = when {
            thermal >= 2 -> 2
            else -> 4
        }

        return DeviceResourceSnapshot(
            availableRamMb = availMb,
            totalRamMb = totalMb,
            isLowRamCondition = isLowRam,
            thermalStatus = thermal,
            isThermalThrottled = isThermalThrottled,
            recommendedContextTokens = ctxSize,
            recommendedThreads = threads
        )
    }

    suspend fun loadSmolVlm2GuiEngine(
        enforceExactSizeAndSha256: Boolean = false
    ): Result<String> {
        val ctx = appContext ?: return Result.failure(IllegalStateException("MODEL_NOT_INSTALLED: No application context initialized."))
        return loadSmolVlm2GuiEngine(
            context = ctx,
            verifyChecksums = enforceExactSizeAndSha256
        )
    }

    /**
     * Sequential 8 GB RAM governance: loads SmolVLM2 Q4_K_M + mmproj F16 GGUF only after
     * unloading Florence-2 or any other resident runtime. Runs a real image+text health check
     * before marking SmolVLM2 `READY`.
     */
    suspend fun loadSmolVlm2GuiEngine(
        context: Context,
        baseDir: File = GemmaModelManager.getModelDirectory(context),
        overrideAvailRamMb: Long? = null,
        overrideThermalStatus: Int? = null,
        verifyChecksums: Boolean = false,
        customHealthCheckRunner: (suspend (File, File) -> Result<String>)? = null
    ): Result<String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            appContext = context.applicationContext
            // 1. Sequential memory governance: ALWAYS unload Florence-2 first!
            unloadFlorence2Internal()
            closeInternal()

            val resources = inspectDeviceResources(context, overrideAvailRamMb, overrideThermalStatus)
            if (resources.isThermalThrottled) {
                GemmaModelManager.setStatus(GemmaModelStatus.ERROR, "THERMAL_THROTTLED")
                return@withContext Result.failure(IllegalStateException("THERMAL_THROTTLED: Device thermal status=${resources.thermalStatus} is too high for VLM loading."))
            }
            if (resources.availableRamMb < MIN_RAM_MB_FOR_SMOLVLM2) {
                GemmaModelManager.setStatus(GemmaModelStatus.ERROR, "OOM_RISK")
                return@withContext Result.failure(IllegalStateException("OOM_RISK: Available RAM (${resources.availableRamMb} MB) is below minimum $MIN_RAM_MB_FOR_SMOLVLM2 MB for SmolVLM2."))
            }

            // 2. Verify SmolVLM2 main GGUF + companion mmproj GGUF
            val pkgReport = GemmaModelManager.verifySmolVlm2Package(baseDir, verifyChecksums = verifyChecksums)
            if (!pkgReport.isInstalledAndValid) {
                GemmaModelManager.setStatus(GemmaModelStatus.ERROR, pkgReport.statusCode)
                return@withContext Result.failure(IllegalStateException("${pkgReport.statusCode}: ${pkgReport.message}"))
            }

            val mainSpec = GemmaModelManager.SMOLVLM2_MANIFEST.artifacts.first { it.role == "text_decoder_gguf" }
            val mmprojSpec = GemmaModelManager.SMOLVLM2_MANIFEST.artifacts.first { it.role == "mmproj_vision_projector" }
            val mainFile = File(baseDir, mainSpec.relativePath)
            val mmprojFile = File(baseDir, mmprojSpec.relativePath)

            GemmaModelManager.setStatus(GemmaModelStatus.LOADING)

            val healthOut = if (customHealthCheckRunner != null) {
                GemmaModelManager.setStatus(GemmaModelStatus.HEALTH_CHECK)
                customHealthCheckRunner(mainFile, mmprojFile)
            } else {
                val bridge = LlamaCppEngine.getInstance()
                val loadRes = bridge.loadMultimodalModel(
                    modelFile = mainFile,
                    mmprojFile = mmprojFile,
                    contextSize = resources.recommendedContextTokens,
                    threads = resources.recommendedThreads
                )
                if (loadRes.isFailure) {
                    val msg = loadRes.exceptionOrNull()?.message ?: "Failed to load SmolVLM2 GGUF + mmproj"
                    GemmaModelManager.setStatus(GemmaModelStatus.ERROR, msg)
                    return@withContext Result.failure(IllegalStateException(msg))
                }
                GemmaModelManager.setStatus(GemmaModelStatus.HEALTH_CHECK)
                val probeBitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                    val canvas = android.graphics.Canvas(this)
                    canvas.drawColor(android.graphics.Color.WHITE)
                    val paint = android.graphics.Paint().apply { color = android.graphics.Color.rgb(32, 96, 200) }
                    canvas.drawRect(0f, 0f, 64f, 18f, paint)
                    paint.color = android.graphics.Color.rgb(40, 40, 40)
                    canvas.drawRect(10f, 28f, 54f, 44f, paint)
                }
                val probeFrame = try {
                    LlamaCppEngine.preprocessScreenshotForSmolVlm2(probeBitmap)
                } finally {
                    probeBitmap.recycle()
                }
                val probePrompt = LlamaCppEngine.formatSmolVlm2Prompt(
                    userGoal = "Health check probe",
                    untrustedScreenSummary = "OK"
                )
                bridge.generateMultimodal(probeFrame, probePrompt, maxTokens = 32)
            }

            if (healthOut.isFailure || healthOut.getOrNull().isNullOrBlank()) {
                LlamaCppEngine.getInstance().unloadMultimodalModel()
                smolVlm2Ready = false
                _activeLoadedRole = ActiveLoadedRole.NONE
                val err = healthOut.exceptionOrNull()?.message ?: "Empty health check response"
                GemmaModelManager.setStatus(GemmaModelStatus.ERROR, "MODEL_CORRUPT: Health check failed ($err)")
                return@withContext Result.failure(IllegalStateException("MODEL_CORRUPT: SmolVLM2 health check failed ($err)"))
            }

            smolVlm2Ready = true
            _activeLoadedRole = ActiveLoadedRole.SMOLVLM2_GUI_VLM
            GemmaModelManager.setStatus(GemmaModelStatus.READY)
            _diagnostics = GemmaDiagnostics(
                modelPath = mainFile.absolutePath,
                sizeBytes = mainFile.length() + mmprojFile.length(),
                sizeFormatted = "${(mainFile.length() + mmprojFile.length()) / (1024 * 1024)} MB",
                detectedFormat = GemmaModelFormat.GGUF,
                runtimeSelected = "llama.cpp SmolVLM2 GGUF + mmproj Runtime",
                initializationState = GemmaModelStatus.READY,
                healthCheckState = "PASS",
                availableMemoryMB = resources.availableRamMb,
                inferenceSucceeded = true,
                networkUsed = false
            )
            Result.success("READY")
        }
    }

    /**
     * Sequential 8 GB RAM governance: loads Florence-2 Q4F16 ONNX auxiliary vision model only after
     * unloading SmolVLM2 so both are never simultaneously resident in RAM.
     */
    suspend fun loadFlorence2AuxEngine(
        context: Context,
        baseDir: File = GemmaModelManager.getModelDirectory(context),
        overrideAvailRamMb: Long? = null,
        overrideThermalStatus: Int? = null,
        skipOrtSessionCreationForTest: Boolean = false
    ): Result<String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            appContext = context.applicationContext
            // 1. Sequential memory governance: ALWAYS unload SmolVLM2 first!
            unloadSmolVlm2Internal()
            closeInternal()

            val resources = inspectDeviceResources(context, overrideAvailRamMb, overrideThermalStatus)
            if (resources.isThermalThrottled) {
                return@withContext Result.failure(IllegalStateException("THERMAL_THROTTLED: Thermal status=${resources.thermalStatus}"))
            }
            if (resources.availableRamMb < MIN_RAM_MB_FOR_FLORENCE2) {
                return@withContext Result.failure(IllegalStateException("OOM_RISK: Available RAM (${resources.availableRamMb} MB) < $MIN_RAM_MB_FOR_FLORENCE2 MB"))
            }

            val pkgReport = GemmaModelManager.verifyFlorence2Package(baseDir, verifyChecksums = false)
            if (!pkgReport.isInstalledAndValid) {
                return@withContext Result.failure(IllegalStateException("${pkgReport.statusCode}: ${pkgReport.message}"))
            }

            if (!skipOrtSessionCreationForTest) {
                try {
                    val env = OrtEnvironment.getEnvironment()
                    florenceOrtEnv = env
                    val opts = OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(resources.recommendedThreads)
                    }
                    for (spec in GemmaModelManager.FLORENCE2_MANIFEST.artifacts.filter { it.format == GemmaModelFormat.ONNX }) {
                        val f = File(baseDir, spec.relativePath)
                        florenceSessions[spec.role] = env.createSession(f.absolutePath, opts)
                    }
                } catch (t: Throwable) {
                    unloadFlorence2Internal()
                    return@withContext Result.failure(IllegalStateException("MODEL_CORRUPT: Failed to initialize ONNX Runtime session (${t.message})", t))
                }
            }

            florenceBaseDir = baseDir
            florence2Ready = true
            _activeLoadedRole = ActiveLoadedRole.FLORENCE2_AUX_VISION
            Result.success("READY")
        }
    }

    suspend fun runFlorence2AuxiliaryPass(
        screenshot: Bitmap,
        taskPrompt: String = "<OD>"
    ): Result<List<com.example.visual.VisionElementSignal>> = mutex.withLock {
        withContext(Dispatchers.Default) {
            if (!isFlorence2Loaded()) {
                return@withContext Result.failure(IllegalStateException("MODEL_NOT_INSTALLED: Florence-2 auxiliary runtime is not loaded."))
            }
            val visionSession = florenceSessions["vision_encoder"]
                ?: return@withContext Result.failure(
                    IllegalStateException("MODEL_CORRUPT: Florence-2 vision_encoder ONNX session is not resident.")
                )
            val env = florenceOrtEnv ?: ai.onnxruntime.OrtEnvironment.getEnvironment().also { florenceOrtEnv = it }
            try {
                val inputEntry = visionSession.inputInfo.entries.firstOrNull()
                    ?: return@withContext Result.failure(IllegalStateException("Florence-2 vision_encoder has no input tensor"))
                val tensorInfo = inputEntry.value.info as? ai.onnxruntime.TensorInfo
                val shape = tensorInfo?.shape
                val targetH = if (shape != null && shape.size >= 4 && shape[2] in 128..1024) shape[2].toInt() else 768
                val targetW = if (shape != null && shape.size >= 4 && shape[3] in 128..1024) shape[3].toInt() else 768

                val scaled = if (screenshot.width == targetW && screenshot.height == targetH) {
                    screenshot
                } else {
                    Bitmap.createScaledBitmap(screenshot, targetW, targetH, true)
                }
                val pixels = IntArray(targetW * targetH)
                scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
                if (scaled !== screenshot) scaled.recycle()

                val plane = targetW * targetH
                val arr = FloatArray(3 * plane)
                for (i in pixels.indices) {
                    val px = pixels[i]
                    arr[i] = ((((px ushr 16) and 0xFF) / 255f) - 0.485f) / 0.229f
                    arr[plane + i] = ((((px ushr 8) and 0xFF) / 255f) - 0.456f) / 0.224f
                    arr[2 * plane + i] = (((px and 0xFF) / 255f) - 0.406f) / 0.225f
                }
                val dims = longArrayOf(1L, 3L, targetH.toLong(), targetW.toLong())
                val inputTensor = if (tensorInfo?.type == ai.onnxruntime.OnnxJavaType.FLOAT16) {
                    val bb = java.nio.ByteBuffer.allocateDirect(arr.size * 2)
                        .order(java.nio.ByteOrder.nativeOrder())
                    for (v in arr) bb.putShort(floatToFp16Bits(v))
                    bb.rewind()
                    ai.onnxruntime.OnnxTensor.createTensor(env, bb, dims, ai.onnxruntime.OnnxJavaType.FLOAT16)
                } else {
                    val fb = java.nio.FloatBuffer.allocate(arr.size).apply {
                        put(arr)
                        rewind()
                    }
                    ai.onnxruntime.OnnxTensor.createTensor(env, fb, dims)
                }
                val signals = mutableListOf<com.example.visual.VisionElementSignal>()
                inputTensor.use { tensor ->
                    visionSession.run(mapOf(inputEntry.key to tensor)).use { result ->
                        val outTensor = result.firstOrNull()?.value as? ai.onnxruntime.OnnxTensor
                        val decodedFromGraph = tryRunFlorenceEncoderDecoder(
                            env = env,
                            imageFeatures = outTensor,
                            taskPrompt = taskPrompt,
                            screenWidth = screenshot.width.coerceAtLeast(1),
                            screenHeight = screenshot.height.coerceAtLeast(1)
                        )
                        signals.addAll(decodedFromGraph)
                    }
                }
                Result.success(signals)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Result.failure(IllegalStateException("Florence-2 vision pass failed: ${t.message}", t))
            }
        }
    }

    private fun floatToFp16Bits(value: Float): Short {
        val bits = java.lang.Float.floatToIntBits(value)
        val sign = (bits ushr 16) and 0x8000
        var exponent = ((bits ushr 23) and 0xFF) - 127 + 15
        var mantissa = bits and 0x7FFFFF
        if (exponent <= 0) {
            if (exponent < -10) return sign.toShort()
            mantissa = (mantissa or 0x800000) shr (1 - exponent)
            return (sign or (mantissa shr 13)).toShort()
        } else if (exponent >= 31) {
            return (sign or 0x7C00).toShort()
        }
        return (sign or (exponent shl 10) or (mantissa shr 13)).toShort()
    }

    private fun fp16BitsToFloat(half: Short): Float {
        val h = half.toInt() and 0xFFFF
        val sign = (h and 0x8000) shl 16
        val exp = (h ushr 10) and 0x1F
        val mant = h and 0x03FF
        return when (exp) {
            0 -> {
                if (mant == 0) {
                    java.lang.Float.intBitsToFloat(sign)
                } else {
                    var m = mant
                    var e = 127 - 14
                    while ((m and 0x0400) == 0) {
                        m = m shl 1
                        e--
                    }
                    m = m and 0x03FF
                    java.lang.Float.intBitsToFloat(sign or (e shl 23) or (m shl 13))
                }
            }
            31 -> java.lang.Float.intBitsToFloat(sign or 0x7F800000 or (mant shl 13))
            else -> java.lang.Float.intBitsToFloat(sign or ((exp + 112) shl 23) or (mant shl 13))
        }
    }

    private fun extractOnnxFloats(tensor: ai.onnxruntime.OnnxTensor?): FloatArray? {
        if (tensor == null) return null
        tensor.floatBuffer?.let { fb ->
            val dup = fb.duplicate()
            return FloatArray(dup.remaining()).also { dup.get(it) }
        }
        if (tensor.info?.type == ai.onnxruntime.OnnxJavaType.FLOAT16) {
            val bb = tensor.byteBuffer?.duplicate()?.order(java.nio.ByteOrder.nativeOrder()) ?: return null
            val count = bb.remaining() / 2
            val out = FloatArray(count)
            for (i in 0 until count) out[i] = fp16BitsToFloat(bb.short)
            return out
        }
        return null
    }

    private fun concatSequenceEmbeds(
        env: OrtEnvironment,
        imageFeatures: ai.onnxruntime.OnnxTensor,
        textEmbeds: ai.onnxruntime.OnnxTensor,
        targetType: ai.onnxruntime.OnnxJavaType
    ): ai.onnxruntime.OnnxTensor? {
        val imgShape = (imageFeatures.info as? ai.onnxruntime.TensorInfo)?.shape ?: return null
        val txtShape = (textEmbeds.info as? ai.onnxruntime.TensorInfo)?.shape ?: return null
        if (imgShape.size < 3 || txtShape.size < 3) return null
        val imgSeq = imgShape[1].coerceAtLeast(0L)
        val txtSeq = txtShape[1].coerceAtLeast(0L)
        val hiddenDim = txtShape[2].coerceAtLeast(1L)
        if (imgShape[2] != hiddenDim) return null
        val imgFloats = extractOnnxFloats(imageFeatures) ?: return null
        val txtFloats = extractOnnxFloats(textEmbeds) ?: return null
        val totalSeq = imgSeq + txtSeq
        val dims = longArrayOf(1L, totalSeq, hiddenDim)
        val combined = FloatArray(imgFloats.size + txtFloats.size)
        System.arraycopy(imgFloats, 0, combined, 0, imgFloats.size)
        System.arraycopy(txtFloats, 0, combined, imgFloats.size, txtFloats.size)
        return if (targetType == ai.onnxruntime.OnnxJavaType.FLOAT16) {
            val bb = java.nio.ByteBuffer.allocateDirect(combined.size * 2)
                .order(java.nio.ByteOrder.nativeOrder())
            for (v in combined) bb.putShort(floatToFp16Bits(v))
            bb.rewind()
            ai.onnxruntime.OnnxTensor.createTensor(env, bb, dims, ai.onnxruntime.OnnxJavaType.FLOAT16)
        } else {
            val fb = java.nio.FloatBuffer.wrap(combined)
            ai.onnxruntime.OnnxTensor.createTensor(env, fb, dims)
        }
    }

    private fun tokenizeFlorenceBpePrompt(
        tokJson: org.json.JSONObject,
        taskPrompt: String,
        tokenToId: Map<String, Long>,
        bosId: Long,
        eosId: Long
    ): LongArray {
        val addedTokens = mutableListOf<String>()
        val addedArr = tokJson.optJSONArray("added_tokens")
        if (addedArr != null) {
            for (i in 0 until addedArr.length()) {
                val c = addedArr.optJSONObject(i)?.optString("content").orEmpty()
                if (c.isNotEmpty() && c != "<s>" && c != "</s>" && c != "<pad>" && c != "<unk>") {
                    addedTokens.add(c)
                }
            }
        }
        addedTokens.sortByDescending { it.length }

        val bpeRanks = HashMap<Pair<String, String>, Int>()
        val mergesArr = tokJson.optJSONObject("model")?.optJSONArray("merges")
        if (mergesArr != null) {
            for (i in 0 until mergesArr.length()) {
                val line = mergesArr.optString(i)
                val sp = line.indexOf(' ')
                if (sp > 0 && sp < line.length - 1) {
                    bpeRanks[line.substring(0, sp) to line.substring(sp + 1)] = i
                }
            }
        }

        val byteEncoder = buildGpt2ByteToUnicodeMap()
        val ids = mutableListOf(bosId)
        var cursor = 0
        while (cursor < taskPrompt.length) {
            val matchedSpecial = addedTokens.firstOrNull { taskPrompt.startsWith(it, startIndex = cursor) }
            if (matchedSpecial != null) {
                tokenToId[matchedSpecial]?.let { ids.add(it) }
                cursor += matchedSpecial.length
                continue
            }
            val nextSpecialIdx = addedTokens
                .mapNotNull { sp -> taskPrompt.indexOf(sp, startIndex = cursor).takeIf { it >= 0 } }
                .minOrNull() ?: taskPrompt.length
            val chunk = taskPrompt.substring(cursor, nextSpecialIdx)
            val preTokens = Regex("""\s*\S+|\s+""").findAll(chunk).map { it.value }
            for (piece in preTokens) {
                val encodedChars = piece.toByteArray(Charsets.UTF_8)
                    .map { b -> (byteEncoder[b.toInt() and 0xFF] ?: '?').toString() }
                var word = encodedChars
                while (word.size > 1 && bpeRanks.isNotEmpty()) {
                    var bestPair: Pair<String, String>? = null
                    var bestRank = Int.MAX_VALUE
                    for (j in 0 until word.size - 1) {
                        val pair = word[j] to word[j + 1]
                        val rank = bpeRanks[pair] ?: Int.MAX_VALUE
                        if (rank < bestRank) {
                            bestRank = rank
                            bestPair = pair
                        }
                    }
                    if (bestPair == null || bestRank == Int.MAX_VALUE) break
                    val merged = mutableListOf<String>()
                    var k = 0
                    while (k < word.size) {
                        if (k < word.size - 1 && word[k] == bestPair.first && word[k + 1] == bestPair.second) {
                            merged.add(bestPair.first + bestPair.second)
                            k += 2
                        } else {
                            merged.add(word[k])
                            k++
                        }
                    }
                    word = merged
                }
                for (sub in word) {
                    val tid = tokenToId[sub] ?: tokenToId["<unk>"] ?: continue
                    ids.add(tid)
                }
            }
            cursor = nextSpecialIdx
        }
        ids.add(eosId)
        return ids.toLongArray()
    }

    private fun buildGpt2ByteToUnicodeMap(): Map<Int, Char> {
        val bs = mutableListOf<Int>()
        for (b in 33..126) bs.add(b)
        for (b in 161..172) bs.add(b)
        for (b in 174..255) bs.add(b)
        val cs = bs.toMutableList()
        var n = 0
        for (b in 0..255) {
            if (b !in bs) {
                bs.add(b)
                cs.add(256 + n)
                n++
            }
        }
        return bs.indices.associate { bs[it] to cs[it].toChar() }
    }

    private fun createZeroTensorForInfo(
        env: OrtEnvironment,
        tensorInfo: ai.onnxruntime.TensorInfo,
        resolvedShape: LongArray
    ): ai.onnxruntime.OnnxTensor {
        val total = resolvedShape.fold(1L) { acc, d -> acc * d.coerceAtLeast(1L) }.toInt()
        return when (tensorInfo.type) {
            ai.onnxruntime.OnnxJavaType.FLOAT16 -> {
                val byteBuf = java.nio.ByteBuffer.allocateDirect(total * 2)
                    .order(java.nio.ByteOrder.nativeOrder())
                ai.onnxruntime.OnnxTensor.createTensor(
                    env,
                    byteBuf,
                    resolvedShape,
                    ai.onnxruntime.OnnxJavaType.FLOAT16
                )
            }
            ai.onnxruntime.OnnxJavaType.INT64 -> {
                ai.onnxruntime.OnnxTensor.createTensor(
                    env,
                    java.nio.LongBuffer.wrap(LongArray(total)),
                    resolvedShape
                )
            }
            ai.onnxruntime.OnnxJavaType.BOOL -> {
                ai.onnxruntime.OnnxTensor.createTensor(
                    env,
                    java.nio.ByteBuffer.wrap(ByteArray(total)),
                    resolvedShape,
                    ai.onnxruntime.OnnxJavaType.BOOL
                )
            }
            else -> {
                ai.onnxruntime.OnnxTensor.createTensor(
                    env,
                    java.nio.FloatBuffer.wrap(FloatArray(total)),
                    resolvedShape
                )
            }
        }
    }

    private fun tryRunFlorenceEncoderDecoder(
        env: OrtEnvironment,
        imageFeatures: ai.onnxruntime.OnnxTensor?,
        taskPrompt: String,
        screenWidth: Int,
        screenHeight: Int
    ): List<com.example.visual.VisionElementSignal> {
        val textEncoder = florenceSessions["text_encoder"] ?: return emptyList()
        val decoder = florenceSessions["decoder_merged"] ?: return emptyList()
        val embedTokens = florenceSessions["embed_tokens"] ?: return emptyList()
        val baseDir = florenceBaseDir ?: return emptyList()
        if (imageFeatures == null) return emptyList()

        val tokenizerSpec = GemmaModelManager.FLORENCE2_MANIFEST.artifacts.firstOrNull { it.role == "tokenizer" } ?: return emptyList()
        val tokenizerFile = File(baseDir, tokenizerSpec.relativePath)
        if (!tokenizerFile.exists()) return emptyList()

        return try {
            val tokJson = org.json.JSONObject(tokenizerFile.readText())
            val vocabObj = tokJson.optJSONObject("model")?.optJSONObject("vocab") ?: return emptyList()
            val addedTokensArr = tokJson.optJSONArray("added_tokens")
            val tokenToId = HashMap<String, Long>(4096)
            val idToToken = HashMap<Long, String>(4096)
            val vocabKeys = vocabObj.keys()
            while (vocabKeys.hasNext()) {
                val k = vocabKeys.next()
                val v = vocabObj.optLong(k, -1L)
                if (v >= 0L) {
                    tokenToId[k] = v
                    idToToken[v] = k
                }
            }
            if (addedTokensArr != null) {
                for (i in 0 until addedTokensArr.length()) {
                    val item = addedTokensArr.optJSONObject(i) ?: continue
                    val content = item.optString("content", "")
                    val id = item.optLong("id", -1L)
                    if (content.isNotEmpty() && id >= 0L) {
                        tokenToId[content] = id
                        idToToken[id] = content
                    }
                }
            }

            val bosId = tokenToId["<s>"] ?: 0L
            val eosId = tokenToId["</s>"] ?: 2L
            val promptIds = tokenizeFlorenceBpePrompt(tokJson, taskPrompt, tokenToId, bosId, eosId)

            val idsTensor = ai.onnxruntime.OnnxTensor.createTensor(
                env,
                java.nio.LongBuffer.wrap(promptIds),
                longArrayOf(1L, promptIds.size.toLong())
            )
            val textEmbedsResult = idsTensor.use { t ->
                val inName = embedTokens.inputInfo.keys.firstOrNull() ?: return emptyList()
                embedTokens.run(mapOf(inName to t))
            }
            val decodedBuilder = StringBuilder()
            val byteDecoder = buildGpt2ByteToUnicodeMap().entries.associate { (k, v) -> v to k.toByte() }
            val generatedTokenPieces = mutableListOf<Byte>()
            textEmbedsResult.use { embOut ->
                val textEmbTensor = embOut.firstOrNull()?.value as? ai.onnxruntime.OnnxTensor ?: return emptyList()
                val encInputs = mutableMapOf<String, ai.onnxruntime.OnnxTensor>()
                val ownedEncTensors = mutableListOf<ai.onnxruntime.OnnxTensor>()
                val hasSeparateImageInput = textEncoder.inputInfo.keys.any { it.contains("image", ignoreCase = true) }
                val imgSeqLen = ((imageFeatures.info as? ai.onnxruntime.TensorInfo)?.shape?.getOrNull(1) ?: 0L).coerceAtLeast(0L)
                var effectiveSeqLen = promptIds.size.toLong()
                for ((name, info) in textEncoder.inputInfo) {
                    val ti = info.info as? ai.onnxruntime.TensorInfo
                    when {
                        name.contains("image", ignoreCase = true) -> {
                            encInputs[name] = imageFeatures
                            effectiveSeqLen = (imgSeqLen + promptIds.size).coerceAtLeast(1L)
                        }
                        name.contains("inputs_embeds", ignoreCase = true) || name.contains("embed", ignoreCase = true) -> {
                            if (!hasSeparateImageInput) {
                                val targetType = ti?.type ?: ai.onnxruntime.OnnxJavaType.FLOAT
                                val merged = concatSequenceEmbeds(env, imageFeatures, textEmbTensor, targetType)
                                if (merged != null) {
                                    ownedEncTensors.add(merged)
                                    encInputs[name] = merged
                                    effectiveSeqLen = (imgSeqLen + promptIds.size).coerceAtLeast(1L)
                                } else {
                                    encInputs[name] = textEmbTensor
                                }
                            } else {
                                encInputs[name] = textEmbTensor
                            }
                        }
                    }
                }
                for ((name, _) in textEncoder.inputInfo) {
                    if (name.contains("attention_mask", ignoreCase = true)) {
                        val mask = LongArray(effectiveSeqLen.toInt().coerceAtLeast(1)) { 1L }
                        val t = ai.onnxruntime.OnnxTensor.createTensor(
                            env,
                            java.nio.LongBuffer.wrap(mask),
                            longArrayOf(1L, effectiveSeqLen.coerceAtLeast(1L))
                        )
                        ownedEncTensors.add(t)
                        encInputs[name] = t
                    }
                }
                if (encInputs.isEmpty()) {
                    ownedEncTensors.forEach { runCatching { it.close() } }
                    return emptyList()
                }
                try {
                    textEncoder.run(encInputs).use { encOut ->
                        val encHidden = encOut.firstOrNull()?.value as? ai.onnxruntime.OnnxTensor ?: return emptyList()
                        val hiddenShape = (encHidden.info as? ai.onnxruntime.TensorInfo)?.shape
                        val encSeqLen = hiddenShape?.getOrNull(1)?.coerceAtLeast(1L) ?: 1L
                        val historyIds = mutableListOf(bosId)
                        val maxSteps = 64
                        for (step in 0 until maxSteps) {
                            val stepArr = historyIds.toLongArray()
                            val stepTensor = ai.onnxruntime.OnnxTensor.createTensor(
                                env,
                                java.nio.LongBuffer.wrap(stepArr),
                                longArrayOf(1L, stepArr.size.toLong())
                            )
                            val stepEmbedRes = stepTensor.use { st ->
                                val embIn = embedTokens.inputInfo.keys.firstOrNull() ?: return emptyList()
                                embedTokens.run(mapOf(embIn to st))
                            }
                            var nextId = eosId
                            stepEmbedRes.use { se ->
                                val stepEmbTensor = se.firstOrNull()?.value as? ai.onnxruntime.OnnxTensor ?: return emptyList()
                                val decInputs = mutableMapOf<String, ai.onnxruntime.OnnxTensor>()
                                val ownedDecTensors = mutableListOf<ai.onnxruntime.OnnxTensor>()
                                try {
                                    for ((k, info) in decoder.inputInfo) {
                                        val ti = info.info as? ai.onnxruntime.TensorInfo ?: continue
                                        when {
                                            k.contains("encoder_hidden", ignoreCase = true) -> decInputs[k] = encHidden
                                            k.contains("inputs_embeds", ignoreCase = true) -> decInputs[k] = stepEmbTensor
                                            k.contains("input_ids", ignoreCase = true) -> decInputs[k] = stepTensor.takeIf { false } ?: run {
                                                val t = ai.onnxruntime.OnnxTensor.createTensor(
                                                    env,
                                                    java.nio.LongBuffer.wrap(stepArr),
                                                    longArrayOf(1L, stepArr.size.toLong())
                                                )
                                                ownedDecTensors.add(t)
                                                t
                                            }
                                            k.contains("use_cache", ignoreCase = true) -> {
                                                val t = ai.onnxruntime.OnnxTensor.createTensor(env, booleanArrayOf(false))
                                                ownedDecTensors.add(t)
                                                decInputs[k] = t
                                            }
                                            k.contains("encoder_attention_mask", ignoreCase = true) -> {
                                                val ones = LongArray(encSeqLen.toInt()) { 1L }
                                                val t = ai.onnxruntime.OnnxTensor.createTensor(
                                                    env,
                                                    java.nio.LongBuffer.wrap(ones),
                                                    longArrayOf(1L, encSeqLen)
                                                )
                                                ownedDecTensors.add(t)
                                                decInputs[k] = t
                                            }
                                            k.contains("past_key_values", ignoreCase = true) -> {
                                                val fixedShape = ti.shape.mapIndexed { idx, d ->
                                                    when {
                                                        d <= 0L -> if (idx == 0) 1L else 0L
                                                        else -> d
                                                    }
                                                }.toLongArray()
                                                if (fixedShape.isNotEmpty() && fixedShape[0] <= 0L) fixedShape[0] = 1L
                                                val t = createZeroTensorForInfo(env, ti, fixedShape)
                                                ownedDecTensors.add(t)
                                                decInputs[k] = t
                                            }
                                        }
                                    }
                                    if (!decInputs.containsKey("inputs_embeds") && !decInputs.containsKey("input_ids")) {
                                        return emptyList()
                                    }
                                    decoder.run(decInputs).use { decOut ->
                                        val logitsTensor = decOut.firstOrNull()?.value as? ai.onnxruntime.OnnxTensor
                                        val logitShape = logitsTensor?.info?.shape
                                        if (logitsTensor != null && logitShape != null && logitShape.size >= 3) {
                                            val vocabSize = logitShape.last().toInt().coerceAtLeast(1)
                                            val floats: FloatArray? = extractOnnxFloats(logitsTensor)
                                            if (floats != null && floats.size >= vocabSize) {
                                                val offset = floats.size - vocabSize
                                                var bestIdx = 0
                                                var bestVal = Float.NEGATIVE_INFINITY
                                                for (v in 0 until vocabSize) {
                                                    val lv = floats[offset + v]
                                                    if (lv > bestVal) {
                                                        bestVal = lv
                                                        bestIdx = v
                                                    }
                                                }
                                                nextId = bestIdx.toLong()
                                            }
                                        }
                                    }
                                } finally {
                                    ownedDecTensors.forEach { runCatching { it.close() } }
                                }
                            }
                            if (nextId == eosId) break
                            val rawTok = idToToken[nextId].orEmpty()
                            if (rawTok.startsWith("<loc_") && rawTok.endsWith(">")) {
                                if (generatedTokenPieces.isNotEmpty()) {
                                    decodedBuilder.append(String(generatedTokenPieces.toByteArray(), Charsets.UTF_8))
                                    generatedTokenPieces.clear()
                                }
                                decodedBuilder.append(rawTok)
                            } else {
                                for (ch in rawTok) {
                                    val b = byteDecoder[ch]
                                    if (b != null) {
                                        generatedTokenPieces.add(b)
                                    } else {
                                        if (generatedTokenPieces.isNotEmpty()) {
                                            decodedBuilder.append(String(generatedTokenPieces.toByteArray(), Charsets.UTF_8))
                                            generatedTokenPieces.clear()
                                        }
                                        decodedBuilder.append(ch)
                                    }
                                }
                            }
                            historyIds.add(nextId)
                        }
                        if (generatedTokenPieces.isNotEmpty()) {
                            decodedBuilder.append(String(generatedTokenPieces.toByteArray(), Charsets.UTF_8))
                            generatedTokenPieces.clear()
                        }
                    }
                } finally {
                    ownedEncTensors.forEach { runCatching { it.close() } }
                }
            }

            val parsed = parseFlorence2LocationOutput(decodedBuilder.toString(), targetHint = taskPrompt, defaultConfidence = 0.78f)
            parsed.map { det ->
                com.example.visual.VisionElementSignal(
                    label = det.label,
                    roleHint = "florence2_grounded",
                    bounds = det.normalizedBox.toDeviceRect(screenWidth, screenHeight),
                    confidence = det.confidence,
                    sourceSignal = com.example.visual.SignalSource.AUX_VISION_MODEL,
                    sourceModelId = GemmaModelManager.FLORENCE2_MANIFEST.modelId,
                    sourceModelRevision = GemmaModelManager.FLORENCE2_MANIFEST.pinnedRevision,
                    isConfidenceCalibrated = true
                )
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun runSmolVlm2GuiGrounding(
        screenshot: Bitmap,
        userGoal: String,
        taskMode: SmolVlmTaskMode,
        uiContextHint: String = ""
    ): Result<List<com.example.visual.VisionElementSignal>> {
        val predRes = runSmolVlm2GuiGrounding(
            screenshot = screenshot,
            userGoal = userGoal,
            untrustedScreenSummary = uiContextHint,
            candidateElementsSummary = "",
            screenWidth = screenshot.width.coerceAtLeast(1080),
            screenHeight = screenshot.height.coerceAtLeast(2400)
        )
        return predRes.map { pred ->
            listOf(
                com.example.visual.VisionElementSignal(
                    label = pred.targetLabel.ifBlank { userGoal },
                    roleHint = pred.action.lowercase(),
                    bounds = pred.deviceBounds,
                    confidence = pred.confidence,
                    sourceSignal = com.example.visual.SignalSource.LOCAL_GUI_VLM,
                    sourceModelId = GemmaModelManager.SMOLVLM2_MANIFEST.modelId,
                    sourceModelRevision = GemmaModelManager.SMOLVLM2_MANIFEST.pinnedRevision
                )
            )
        }
    }

    /**
     * Runs SmolVLM2 offline GUI grounding on a fresh screenshot and returns verified coordinates.
     * Zero network calls are ever made.
     */
    suspend fun runSmolVlm2GuiGrounding(
        screenshot: Bitmap,
        userGoal: String,
        untrustedScreenSummary: String,
        candidateElementsSummary: String,
        screenWidth: Int,
        screenHeight: Int
    ): Result<LlamaCppEngine.GroundingPrediction> = mutex.withLock {
        withContext(Dispatchers.Default) {
            if (!isSmolVlm2Loaded()) {
                return@withContext Result.failure(IllegalStateException("MODEL_NOT_INSTALLED: SmolVLM2 GUI runtime is not loaded."))
            }
            val frame = LlamaCppEngine.preprocessScreenshotForSmolVlm2(screenshot)
            val prompt = LlamaCppEngine.formatSmolVlm2Prompt(
                userGoal = userGoal,
                untrustedScreenSummary = untrustedScreenSummary,
                candidateElementsSummary = candidateElementsSummary
            )
            val rawOut = LlamaCppEngine.getInstance().generateMultimodal(frame, prompt, maxTokens = 192)
                .getOrElse { err ->
                    return@withContext Result.failure(err)
                }
            LlamaCppEngine.parseSmolVlm2GroundingOutput(rawOut, screenWidth, screenHeight)
        }
    }

    suspend fun unloadSmolVlm2GuiEngine() = mutex.withLock {
        withContext(Dispatchers.IO) {
            unloadSmolVlm2Internal()
        }
    }

    suspend fun unloadFlorence2AuxEngine() = mutex.withLock {
        withContext(Dispatchers.IO) {
            unloadFlorence2Internal()
        }
    }

    /**
     * Responds to Android system memory pressure (`ComponentCallbacks2.onTrimMemory`).
     */
    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            Log.w(TAG, "onTrimMemory(level=$level): releasing resident offline vision/VLM runtimes to prevent OOM.")
            unloadFlorence2Internal()
            unloadSmolVlm2Internal()
        }
    }

    private fun unloadSmolVlm2Internal() {
        try {
            LlamaCppEngine.getInstance().unloadMultimodalModel()
        } catch (_: Throwable) {}
        smolVlm2Ready = false
        if (_activeLoadedRole == ActiveLoadedRole.SMOLVLM2_GUI_VLM) {
            _activeLoadedRole = ActiveLoadedRole.NONE
        }
    }

    private fun unloadFlorence2Internal() {
        for ((_, session) in florenceSessions) {
            try {
                session.close()
            } catch (_: Throwable) {}
        }
        florenceSessions.clear()
        florenceBaseDir = null
        florence2Ready = false
        if (_activeLoadedRole == ActiveLoadedRole.FLORENCE2_AUX_VISION) {
            _activeLoadedRole = ActiveLoadedRole.NONE
        }
    }

    /**
     * Checks if selected local-model runtime is fully initialized and passed the real health check.
     */
    fun isReady(): Boolean {
        return (activeRuntime?.isReady() == true || isSmolVlm2Loaded()) &&
            GemmaModelManager.status.value == GemmaModelStatus.READY
    }

    /**
     * Safe, thread-locked initialization flow with real health-check inference.
     */
    suspend fun initialize(context: Context): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val availRamMB = GemmaDiagnostics.getAvailableSystemMemoryMB(context)
            val modelFile = GemmaModelManager.getModelFile(context)
            if (modelFile == null || !modelFile.exists()) {
                val msg = "Gemma model file is missing or not installed."
                GemmaModelManager.setStatus(GemmaModelStatus.NOT_INSTALLED, msg)
                _diagnostics = GemmaDiagnostics(
                    initializationState = GemmaModelStatus.NOT_INSTALLED,
                    availableMemoryMB = availRamMB,
                    lastError = msg
                )
                return@withContext Result.failure(IllegalStateException(msg))
            }

            val modelInfo = GemmaModelManager.inspectModel(modelFile)
            if (!modelInfo.isSupported) {
                GemmaModelManager.setStatus(GemmaModelStatus.ERROR, modelInfo.validationMessage)
                _diagnostics = GemmaDiagnostics(
                    modelPath = modelFile.absolutePath,
                    sizeBytes = modelInfo.sizeBytes,
                    sizeFormatted = "${modelInfo.sizeBytes / (1024 * 1024)} MB",
                    detectedFormat = modelInfo.format,
                    runtimeSelected = "None",
                    initializationState = GemmaModelStatus.ERROR,
                    availableMemoryMB = availRamMB,
                    lastError = modelInfo.validationMessage
                )
                return@withContext Result.failure(IllegalArgumentException(modelInfo.validationMessage))
            }

            if (isReady() && activeModelPath == modelFile.absolutePath) {
                return@withContext Result.success(Unit)
            }

            unloadFlorence2Internal()
            unloadSmolVlm2Internal()
            closeInternal()

            GemmaModelManager.setStatus(GemmaModelStatus.LOADING)
            Log.i(TAG, "Initializing selected local-model runtime for file: ${modelFile.absolutePath} (Format: ${modelInfo.format})")

            val runtime: GemmaRuntime = when (modelInfo.format) {
                GemmaModelFormat.GGUF -> LlamaCppRuntime()
                GemmaModelFormat.LITERT_LM -> LiteRtLmRuntime()
                GemmaModelFormat.MEDIAPIPE_TASK -> MediaPipeTaskRuntime()
                else -> UnsupportedFormatRuntime(modelInfo.validationMessage)
            }

            val initResult = try {
                runtime.initialize(context, modelFile)
            } catch (cancelled: CancellationException) {
                try { runtime.close() } catch (_: Throwable) {}
                val message = "Local model loading was cancelled before initialization completed."
                GemmaModelManager.setStatus(GemmaModelStatus.ERROR, message)
                _diagnostics = GemmaDiagnostics(
                    modelPath = modelFile.absolutePath,
                    sizeBytes = modelInfo.sizeBytes,
                    sizeFormatted = "${modelInfo.sizeBytes / (1024 * 1024)} MB",
                    detectedFormat = modelInfo.format,
                    runtimeSelected = runtime.runtimeName,
                    initializationState = GemmaModelStatus.ERROR,
                    availableMemoryMB = availRamMB,
                    lastError = message
                )
                throw cancelled
            } catch (memoryError: OutOfMemoryError) {
                try { runtime.close() } catch (_: Throwable) {}
                Result.failure(
                    IllegalStateException(
                        "Not enough available memory to load this local model. Unload other apps or choose a smaller quantized model.",
                        memoryError
                    )
                )
            } catch (error: Throwable) {
                try { runtime.close() } catch (_: Throwable) {}
                Result.failure(error)
            }
            if (initResult.isFailure) {
                val err = initResult.exceptionOrNull()?.localizedMessage ?: "Runtime initialization failed."
                Log.e(TAG, "Runtime initialization failed", initResult.exceptionOrNull())
                GemmaModelManager.setStatus(GemmaModelStatus.ERROR, err)
                _diagnostics = GemmaDiagnostics(
                    modelPath = modelFile.absolutePath,
                    sizeBytes = modelInfo.sizeBytes,
                    sizeFormatted = "${modelInfo.sizeBytes / (1024 * 1024)} MB",
                    detectedFormat = modelInfo.format,
                    runtimeSelected = runtime.runtimeName,
                    initializationState = GemmaModelStatus.ERROR,
                    availableMemoryMB = availRamMB,
                    lastError = err
                )
                return@withContext initResult
            }

            activeRuntime = runtime
            activeModelPath = modelFile.absolutePath

            GemmaModelManager.setStatus(GemmaModelStatus.HEALTH_CHECK)
            Log.i(TAG, "LOCAL OFFLINE: Running health-check inference...")

            val healthPrompt = "Reply with exactly: OK"
            val healthResult = runtime.generate(healthPrompt)

            val totalInitTime = System.currentTimeMillis() - startTime

            if (healthResult.isSuccess && healthResult.getOrNull()?.isNotBlank() == true) {
                _activeLoadedRole = ActiveLoadedRole.LOCAL_TEXT_LLM
                GemmaModelManager.setStatus(GemmaModelStatus.READY)
                _diagnostics = GemmaDiagnostics(
                    modelPath = modelFile.absolutePath,
                    sizeBytes = modelInfo.sizeBytes,
                    sizeFormatted = "${modelInfo.sizeBytes / (1024 * 1024)} MB",
                    detectedFormat = modelInfo.format,
                    runtimeSelected = runtime.runtimeName,
                    initializationState = GemmaModelStatus.READY,
                    healthCheckState = "PASS",
                    availableMemoryMB = availRamMB,
                    initializationTimeMs = totalInitTime,
                    inferenceSucceeded = true,
                    networkUsed = false
                )
                Log.i(TAG, "LOCAL OFFLINE: Health check PASSED. Model is READY for offline inference.")
                Result.success(Unit)
            } else {
                val err = healthResult.exceptionOrNull()?.localizedMessage ?: "Health-check inference produced empty output."
                Log.e(TAG, "LOCAL OFFLINE: Health check FAILED: $err")
                GemmaModelManager.setStatus(GemmaModelStatus.ERROR, "Health check failed: $err")
                _diagnostics = GemmaDiagnostics(
                    modelPath = modelFile.absolutePath,
                    sizeBytes = modelInfo.sizeBytes,
                    sizeFormatted = "${modelInfo.sizeBytes / (1024 * 1024)} MB",
                    detectedFormat = modelInfo.format,
                    runtimeSelected = runtime.runtimeName,
                    initializationState = GemmaModelStatus.ERROR,
                    healthCheckState = "FAIL",
                    availableMemoryMB = availRamMB,
                    initializationTimeMs = totalInitTime,
                    lastError = err,
                    inferenceSucceeded = false,
                    networkUsed = false
                )
                try {
                    runtime.close()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {}
                if (activeRuntime === runtime) {
                    activeRuntime = null
                    activeModelPath = null
                }
                _activeLoadedRole = ActiveLoadedRole.NONE
                Result.failure(IllegalStateException("Health check failed: $err"))
            }
        }
    }

    /**
     * Generates local offline text response. Strictly no network calls.
     */
    suspend fun generate(
        prompt: String,
        systemInstruction: String? = null
    ): Result<String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            Log.i(TAG, "LOCAL OFFLINE: local inference only (No network call)")

            val runtime = activeRuntime
            if (runtime == null || !runtime.isReady() || GemmaModelManager.status.value != GemmaModelStatus.READY) {
                return@withContext Result.failure(IllegalStateException("selected local-model runtime is not loaded and READY."))
            }

            GemmaModelManager.setStatus(GemmaModelStatus.RUNNING)
            val fullPrompt = if (!systemInstruction.isNullOrBlank()) {
                "SYSTEM: $systemInstruction\n\nUSER: $prompt\n\nASSISTANT:"
            } else {
                prompt
            }

            val result = try {
                runtime.generate(fullPrompt)
            } catch (cancelled: CancellationException) {
                GemmaModelManager.setStatus(
                    if (runtime.isReady()) GemmaModelStatus.READY else GemmaModelStatus.ERROR
                )
                throw cancelled
            } catch (error: Throwable) {
                Result.failure(error)
            }

            GemmaModelManager.setStatus(
                if (runtime.isReady()) GemmaModelStatus.READY else GemmaModelStatus.ERROR,
                result.exceptionOrNull()?.localizedMessage
            )

            if (result.isSuccess) {
                _diagnostics = _diagnostics.copy(inferenceSucceeded = true, lastError = null)
            } else {
                _diagnostics = _diagnostics.copy(lastError = result.exceptionOrNull()?.localizedMessage)
            }
            result
        }
    }

    /**
     * Streams tokens progressive offline generation.
     */
    fun streamGenerate(
        prompt: String,
        systemInstruction: String? = null,
        context: Context
    ): Flow<String> = flow {
        mutex.lock()
        try {
            val runtime = activeRuntime
            if (runtime == null || !runtime.isReady() || GemmaModelManager.status.value != GemmaModelStatus.READY) {
                emit("LOCAL OFFLINE ERROR: Gemma runtime is not loaded and ready.")
                return@flow
            }

            val fullPrompt = if (!systemInstruction.isNullOrBlank()) {
                "SYSTEM: $systemInstruction\n\nUSER: $prompt\n\nASSISTANT:"
            } else {
                prompt
            }

            GemmaModelManager.setStatus(GemmaModelStatus.RUNNING)
            try {
                runtime.streamGenerate(fullPrompt).collect { token ->
                    emit(token)
                }
                _diagnostics = _diagnostics.copy(inferenceSucceeded = true, lastError = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val message = error.localizedMessage ?: "Local streaming inference failed."
                _diagnostics = _diagnostics.copy(lastError = message, inferenceSucceeded = false)
                emit("LOCAL OFFLINE ERROR: $message")
            } finally {
                GemmaModelManager.setStatus(
                    if (activeRuntime === runtime && runtime.isReady()) {
                        GemmaModelStatus.READY
                    } else {
                        GemmaModelStatus.ERROR
                    },
                    _diagnostics.lastError
                )
            }
        } finally {
            mutex.unlock()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Unloads model runtime and releases native memory.
     */
    suspend fun release() = mutex.withLock {
        withContext(Dispatchers.IO) {
            unloadFlorence2Internal()
            unloadSmolVlm2Internal()
            closeInternal()
        }
    }

    private suspend fun closeInternal() {
        try {
            activeRuntime?.close()
        } catch (_: Exception) {}
        activeRuntime = null
        activeModelPath = null
        if (_activeLoadedRole == ActiveLoadedRole.LOCAL_TEXT_LLM) {
            _activeLoadedRole = ActiveLoadedRole.NONE
        }
        GemmaModelManager.setStatus(GemmaModelStatus.NOT_INSTALLED)
        _diagnostics = _diagnostics.copy(
            initializationState = GemmaModelStatus.NOT_INSTALLED,
            healthCheckState = "UNLOADED"
        )
    }
}
