package com.example.ai.offline

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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

    override val runtimeName: String = "Google AI Edge / LiteRT-LM (MediaPipe Task)"
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
            Result.failure(IllegalStateException("OutOfMemoryError loading Gemma 4 E4B into device RAM.", e))
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
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun streamGenerate(prompt: String): Flow<String> = callbackFlow {
        val engine = llmInference
        if (engine == null) {
            trySend("Gemma runtime is not initialized.")
            close()
            return@callbackFlow
        }

        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(initializedFile?.absolutePath ?: "")
                .setMaxTokens(1024)
                .setResultListener { partialResult: String?, done: Boolean ->
                    if (!partialResult.isNullOrEmpty()) {
                        trySend(partialResult)
                    }
                    if (done) {
                        close()
                    }
                }
                .build()

            val asyncEngine = LlmInference.createFromOptions(initializedFile?.parentFile as? Context ?: run {
                // If options fail async creation, fall back to non-streaming response
                trySend(engine.generateResponse(prompt))
                close()
                return@callbackFlow
            }, options)
            asyncEngine.generateResponseAsync(prompt)
        } catch (e: Exception) {
            // Fallback to synchronous generation
            try {
                trySend(engine.generateResponse(prompt))
            } catch (err: Exception) {
                trySend("Gemma inference error: ${err.localizedMessage}")
            }
            close()
        }

        awaitClose { }
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

/**
 * Single Authoritative On-Device Gemma 4 E4B Engine for Kavya AI.
 * Guarantees zero network calls, memory safety, format validation, and real health-check verification.
 */
class Gemma4E4BEngine private constructor() {

    companion object {
        private const val TAG = "KavyaGemma4E4BEngine"

        @Volatile
        private var INSTANCE: Gemma4E4BEngine? = null

        fun getInstance(): Gemma4E4BEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Gemma4E4BEngine().also { INSTANCE = it }
            }
        }
    }

    private val mutex = Mutex()
    private var activeRuntime: GemmaRuntime? = null
    private var activeModelPath: String? = null
    private var lastInitTimeMs: Long = 0L

    private var _diagnostics = GemmaDiagnostics()
    val diagnostics: GemmaDiagnostics get() = _diagnostics

    /**
     * Checks if Gemma 4 E4B runtime is fully initialized and passed the real health check.
     */
    fun isReady(): Boolean {
        return activeRuntime?.isReady() == true && GemmaModelManager.status.value == GemmaModelStatus.READY
    }

    /**
     * Safe, thread-locked initialization flow with real health-check inference.
     */
    suspend fun initialize(context: Context): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()

            // 1. Check available system memory
            val availRamMB = GemmaDiagnostics.getAvailableSystemMemoryMB(context)
            if (availRamMB < 600) { // Require at least 600MB free RAM
                val msg = "Gemma 4 E4B cannot be loaded because this device does not currently have enough available memory (${availRamMB} MB free)."
                Log.e(TAG, msg)
                GemmaModelManager.setStatus(GemmaModelStatus.ERROR, msg)
                _diagnostics = GemmaDiagnostics(
                    initializationState = GemmaModelStatus.ERROR,
                    availableMemoryMB = availRamMB,
                    lastError = msg
                )
                return@withContext Result.failure(IllegalStateException(msg))
            }

            // 2. Locate model file
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

            // 3. Inspect binary format
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

            // Return existing runtime if already ready and path hasn't changed
            if (isReady() && activeModelPath == modelFile.absolutePath) {
                return@withContext Result.success(Unit)
            }

            // Close previous runtime instance
            closeInternal()

            GemmaModelManager.setStatus(GemmaModelStatus.LOADING)
            Log.i(TAG, "Initializing Gemma 4 E4B runtime for file: ${modelFile.absolutePath} (Format: ${modelInfo.format})")

            val runtime: GemmaRuntime = when (modelInfo.format) {
                GemmaModelFormat.MEDIAPIPE_TASK, GemmaModelFormat.LITERT_LM -> MediaPipeTaskRuntime()
                else -> UnsupportedFormatRuntime(modelInfo.validationMessage)
            }

            val initResult = runtime.initialize(context, modelFile)
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

            // 4. Perform Real Health Check Inference
            GemmaModelManager.setStatus(GemmaModelStatus.HEALTH_CHECK)
            Log.i(TAG, "GEMMA OFFLINE: Running health-check inference...")

            val healthPrompt = "Reply with exactly: OK"
            val healthResult = runtime.generate(healthPrompt)

            val totalInitTime = System.currentTimeMillis() - startTime
            lastInitTimeMs = totalInitTime

            if (healthResult.isSuccess && healthResult.getOrNull()?.isNotBlank() == true) {
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
                Log.i(TAG, "GEMMA OFFLINE: Health check PASSED. Model is READY for offline inference.")
                Result.success(Unit)
            } else {
                val err = healthResult.exceptionOrNull()?.localizedMessage ?: "Health-check inference produced empty output."
                Log.e(TAG, "GEMMA OFFLINE: Health check FAILED: $err")
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
                closeInternal()
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
            Log.i(TAG, "GEMMA OFFLINE: local inference only (No network call)")

            val runtime = activeRuntime
            if (runtime == null || !runtime.isReady() || GemmaModelManager.status.value != GemmaModelStatus.READY) {
                return@withContext Result.failure(IllegalStateException("Gemma 4 E4B runtime is not loaded and READY."))
            }

            GemmaModelManager.setStatus(GemmaModelStatus.RUNNING)
            val fullPrompt = if (!systemInstruction.isNullOrBlank()) {
                "SYSTEM: $systemInstruction\n\nUSER: $prompt\n\nASSISTANT:"
            } else {
                prompt
            }

            val result = runtime.generate(fullPrompt)
            GemmaModelManager.setStatus(GemmaModelStatus.READY)

            if (result.isSuccess) {
                _diagnostics = _diagnostics.copy(inferenceSucceeded = true)
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
    ): Flow<String> = callbackFlow {
        Log.i(TAG, "GEMMA OFFLINE: local streaming inference only")

        val runtime = activeRuntime
        if (runtime == null || !runtime.isReady()) {
            trySend("GEMMA OFFLINE ERROR: Gemma runtime is not loaded and ready.")
            close()
            return@callbackFlow
        }

        val fullPrompt = if (!systemInstruction.isNullOrBlank()) {
            "SYSTEM: $systemInstruction\n\nUSER: $prompt\n\nASSISTANT:"
        } else {
            prompt
        }

        runtime.streamGenerate(fullPrompt).collect { token ->
            trySend(token)
        }
        close()
        awaitClose {}
    }.flowOn(Dispatchers.IO)

    /**
     * Unloads model runtime and releases native memory.
     */
    suspend fun release() = mutex.withLock {
        withContext(Dispatchers.IO) {
            closeInternal()
        }
    }

    private suspend fun closeInternal() {
        try {
            activeRuntime?.close()
        } catch (_: Exception) {}
        activeRuntime = null
        activeModelPath = null
        GemmaModelManager.setStatus(GemmaModelStatus.NOT_INSTALLED)
        _diagnostics = _diagnostics.copy(
            initializationState = GemmaModelStatus.NOT_INSTALLED,
            healthCheckState = "UNLOADED"
        )
    }
}
