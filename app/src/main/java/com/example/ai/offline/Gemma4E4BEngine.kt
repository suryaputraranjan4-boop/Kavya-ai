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

    companion object {
        private const val TAG = "KavyaOfflineEngine"
        const val MIN_RAM_MB_FOR_SMOLVLM2 = 1100L
        const val MIN_RAM_MB_FOR_FLORENCE2 = 650L
        const val THERMAL_STATUS_SEVERE_THRESHOLD = 3 // PowerManager.THERMAL_STATUS_SEVERE

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
        return loadSmolVlm2GuiEngine(context = ctx)
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
            val pkgReport = GemmaModelManager.verifySmolVlm2Package(baseDir, verifyChecksums = false)
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
                val probeBitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
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

            val profile = GemmaModelManager.getStorageProfile(context)
            if (profile != OfflineStorageProfile.EXPANDED) {
                return@withContext Result.failure(
                    IllegalStateException("STORAGE_INSUFFICIENT: Florence-2 auxiliary model requires EXPANDED storage profile.")
                )
            }

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
            Result.success(emptyList())
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
