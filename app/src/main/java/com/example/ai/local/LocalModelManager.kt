package com.example.ai.local

import android.content.Context
import android.os.Environment
import android.util.Log
import com.example.utils.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

enum class LocalModelStatusState {
    NOT_FOUND,
    DISCOVERED,
    LOADING,
    READY,
    ERROR,
    UNLOADED
}

data class LocalModelInfo(
    val statusState: LocalModelStatusState = LocalModelStatusState.NOT_FOUND,
    val modelName: String = "Qwen3-4B",
    val fileName: String = "Qwen3-4B-Q4_K_M.gguf",
    val filePath: String = "",
    val fileSizeBytes: Long = 0L,
    val quantization: String = "Q4_K_M",
    val contextWindowTokens: Int = 4096,
    val isGgufHeaderValid: Boolean = false,
    val memoryUsageMb: Int = 0,
    val errorMessage: String = ""
) {
    val fileSizeFormatted: String
        get() = if (fileSizeBytes <= 0) "0 MB" else String.format("%.2f GB", fileSizeBytes / (1024.0 * 1024.0 * 1024.0))
}

/**
 * Local Model Manager for Kavya AI.
 * Handles discovery, verification, header inspection, loading, unloading,
 * and memory management for Qwen3-4B-Q4_K_M.gguf.
 */
class LocalModelManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "KavyaLocalModelManager"
        private const val TARGET_MODEL_FILENAME = "Qwen3-4B-Q4_K_M.gguf"
        private const val GGUF_MAGIC = 0x46554747 // "GGUF" in little-endian ASCII

        @Volatile
        private var instance: LocalModelManager? = null

        fun getInstance(context: Context): LocalModelManager {
            return instance ?: synchronized(this) {
                instance ?: LocalModelManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val mutex = Mutex()

    private val _modelInfo = MutableStateFlow(LocalModelInfo())
    val modelInfo: StateFlow<LocalModelInfo> = _modelInfo.asStateFlow()

    init {
        discoverModelFile()
    }

    /**
     * Scans standard Android storage locations and custom path preferences to locate Qwen3-4B-Q4_K_M.gguf.
     */
    fun discoverModelFile(): LocalModelInfo {
        val customPath = AppPreferences.getLocalModelPath(context)
        val preferredName = AppPreferences.getLocalModelName(context).ifBlank { TARGET_MODEL_FILENAME }

        val candidatePaths = mutableListOf<String>()

        if (customPath.isNotBlank()) {
            candidatePaths.add(customPath)
        }

        // Standard Android Download & Document locations
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloadsDir != null) {
            candidatePaths.add(File(downloadsDir, preferredName).absolutePath)
            candidatePaths.add(File(downloadsDir, TARGET_MODEL_FILENAME).absolutePath)
        }

        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        if (documentsDir != null) {
            candidatePaths.add(File(documentsDir, preferredName).absolutePath)
            candidatePaths.add(File(documentsDir, TARGET_MODEL_FILENAME).absolutePath)
        }

        val appFilesDir = context.getExternalFilesDir(null)
        if (appFilesDir != null) {
            candidatePaths.add(File(appFilesDir, preferredName).absolutePath)
            candidatePaths.add(File(appFilesDir, TARGET_MODEL_FILENAME).absolutePath)
        }

        // Additional root storage fallback paths for Android devices
        candidatePaths.add("/sdcard/Download/$preferredName")
        candidatePaths.add("/sdcard/Download/$TARGET_MODEL_FILENAME")
        candidatePaths.add("/storage/emulated/0/Download/$preferredName")
        candidatePaths.add("/storage/emulated/0/Download/$TARGET_MODEL_FILENAME")

        var foundFile: File? = null

        for (path in candidatePaths.distinct()) {
            val file = File(path)
            if (file.exists() && file.isFile && file.length() > 100 * 1024 * 1024L) { // Must be at least 100MB
                foundFile = file
                break
            }
        }

        if (foundFile != null) {
            val isValidGguf = validateGgufHeader(foundFile)
            val info = LocalModelInfo(
                statusState = if (isValidGguf) LocalModelStatusState.DISCOVERED else LocalModelStatusState.ERROR,
                modelName = "Qwen3-4B",
                fileName = foundFile.name,
                filePath = foundFile.absolutePath,
                fileSizeBytes = foundFile.length(),
                quantization = if (foundFile.name.contains("Q4_K_M", ignoreCase = true)) "Q4_K_M" else "Q4_K_M / GGUF",
                isGgufHeaderValid = isValidGguf,
                errorMessage = if (!isValidGguf) "Invalid GGUF header signature in model file" else ""
            )
            _modelInfo.value = info
            AppPreferences.setLocalModelPath(context, foundFile.absolutePath)
            Log.i(TAG, "Model discovered at: ${foundFile.absolutePath} (${info.fileSizeFormatted}, Valid GGUF: $isValidGguf)")
            return info
        } else {
            val missingInfo = LocalModelInfo(
                statusState = LocalModelStatusState.NOT_FOUND,
                errorMessage = "Qwen3-4B-Q4_K_M.gguf not found in Download or app files folder"
            )
            _modelInfo.value = missingInfo
            Log.w(TAG, "Qwen3-4B-Q4_K_M.gguf model file not found in any standard storage path")
            return missingInfo
        }
    }

    /**
     * Inspects the first 4 bytes of the file for the official GGUF magic header "GGUF" (0x46554747).
     */
    private fun validateGgufHeader(file: File): Boolean {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = Integer.reverseBytes(raf.readInt())
                magic == GGUF_MAGIC
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error validating GGUF header for ${file.name}: ${e.message}")
            false
        }
    }

    /**
     * Safely loads the local Qwen3-4B GGUF model into memory for inference.
     */
    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = _modelInfo.value
            if (current.statusState == LocalModelStatusState.READY) {
                return@withContext true
            }

            val discovery = discoverModelFile()
            if (discovery.statusState == LocalModelStatusState.NOT_FOUND || !discovery.isGgufHeaderValid) {
                _modelInfo.value = discovery.copy(
                    statusState = LocalModelStatusState.ERROR,
                    errorMessage = discovery.errorMessage.ifBlank { "Model file missing or corrupted" }
                )
                return@withContext false
            }

            _modelInfo.value = discovery.copy(statusState = LocalModelStatusState.LOADING)
            Log.i(TAG, "Loading local Qwen3-4B model from: ${discovery.filePath}...")

            try {
                // Initialize local inference runtime
                val initialized = QwenLocalInferenceEngine.getInstance(context).initialize(discovery.filePath)
                if (initialized) {
                    _modelInfo.value = discovery.copy(
                        statusState = LocalModelStatusState.READY,
                        memoryUsageMb = 2350, // Approx ~2.35GB RAM allocation for Q4_K_M
                        errorMessage = ""
                    )
                    Log.i(TAG, "Local Qwen3-4B model loaded successfully and READY for inference.")
                    return@withContext true
                } else {
                    _modelInfo.value = discovery.copy(
                        statusState = LocalModelStatusState.ERROR,
                        errorMessage = "Local inference engine failed to load tensor weights"
                    )
                    return@withContext false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load Qwen3-4B model", e)
                _modelInfo.value = discovery.copy(
                    statusState = LocalModelStatusState.ERROR,
                    errorMessage = e.message ?: "Model load exception"
                )
                return@withContext false
            }
        }
    }

    /**
     * Unloads the model from RAM to free system memory when returning online or handling low-memory events.
     */
    suspend fun unloadModel() = withContext(Dispatchers.IO) {
        mutex.withLock {
            Log.i(TAG, "Unloading Qwen3-4B model from system RAM...")
            QwenLocalInferenceEngine.getInstance(context).unload()
            val current = _modelInfo.value
            _modelInfo.value = current.copy(
                statusState = LocalModelStatusState.UNLOADED,
                memoryUsageMb = 0
            )
        }
    }
}
