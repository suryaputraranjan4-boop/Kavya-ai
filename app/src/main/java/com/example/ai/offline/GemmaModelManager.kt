package com.example.ai.offline

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipFile

enum class GemmaModelFormat {
    UNKNOWN,
    MEDIAPIPE_TASK,
    LITERT_LM,
    GGUF
}

enum class GemmaModelStatus {
    NOT_INSTALLED,
    IMPORTING,
    VALIDATING,
    LOADING,
    HEALTH_CHECK,
    READY,
    RUNNING,
    ERROR
}

data class GemmaModelInfo(
    val file: File,
    val sizeBytes: Long,
    val format: GemmaModelFormat,
    val isSupported: Boolean,
    val validationMessage: String,
    val modelFamily: String = "Generic Local Model",
    val quantization: String = "4-bit / 8-bit Quantized",
    val architecture: String = "Android local inference"
)

/**
 * Single Authoritative On-Device Gemma Model Manager for Kavya AI.
 * Performs real binary header inspection, safe atomic Storage Access Framework imports,
 * and lifecycle status tracking for offline AI execution.
 */
object GemmaModelManager {

    private const val TAG = "KavyaGemmaModelManager"
    private const val PREF_NAME = "kavya_offline_model_prefs"
    private const val KEY_SAVED_MODEL_PATH = "key_saved_offline_model_path"

    private val mutex = Mutex()
    private val _status = MutableStateFlow(GemmaModelStatus.NOT_INSTALLED)
    val status: StateFlow<GemmaModelStatus> = _status.asStateFlow()

    private var _lastError: String? = null
    val lastError: String? get() = _lastError

    private var _cachedInfo: GemmaModelInfo? = null
    val cachedInfo: GemmaModelInfo? get() = _cachedInfo

    /**
     * Directory inside private app files reserved for model storage.
     */
    fun getModelDirectory(context: Context): File {
        val dir = File(context.filesDir, "models/offline")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Inspects a model file's actual binary structure and magic header bytes.
     * Does NOT trust file extension or arbitrary file size alone.
     */
    fun inspectModel(file: File): GemmaModelInfo {
        if (!file.exists() || !file.isFile) {
            return GemmaModelInfo(
                file = file,
                sizeBytes = 0L,
                format = GemmaModelFormat.UNKNOWN,
                isSupported = false,
                validationMessage = "Model file does not exist on disk."
            )
        }

        val size = file.length()
        if (size < 1024 * 1024) {
            return GemmaModelInfo(
                file = file,
                sizeBytes = size,
                format = GemmaModelFormat.UNKNOWN,
                isSupported = false,
                validationMessage = "Model file is too small (minimum 1 MB required for local model weights)."
            )
        }

        val header = ByteArray(16)
        try {
            FileInputStream(file).use { fis ->
                val read = fis.read(header, 0, 16)
                if (read < 16) {
                    return GemmaModelInfo(
                        file = file,
                        sizeBytes = size,
                        format = GemmaModelFormat.UNKNOWN,
                        isSupported = false,
                        validationMessage = "Unable to read binary header."
                    )
                }
            }
        } catch (e: Exception) {
            return GemmaModelInfo(
                file = file,
                sizeBytes = size,
                format = GemmaModelFormat.UNKNOWN,
                isSupported = false,
                validationMessage = "Error reading model binary: ${e.localizedMessage}"
            )
        }

        // 1. Check GGUF magic bytes: "GGUF" (0x47 0x47 0x55 0x46)
        if (header[0] == 'G'.code.toByte() && header[1] == 'G'.code.toByte() && header[2] == 'U'.code.toByte() && header[3] == 'F'.code.toByte()) {
            val nativeAvailable = LlamaCppEngine.isNativeAvailable()
            return GemmaModelInfo(
                file = file,
                sizeBytes = size,
                format = GemmaModelFormat.GGUF,
                isSupported = nativeAvailable,
                validationMessage = if (nativeAvailable) {
                    "GGUF format detected; llama.cpp native runtime is available."
                } else {
                    "GGUF format detected, but native llama.cpp library is not available in current build."
                },
                quantization = "GGUF Quantized"
            )
        }

        // 2. Check ZIP / MediaPipe Task Bundle magic bytes: "PK\x03\x04" (0x50 0x4B 0x03 0x04)
        if (header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() && header[2] == 0x03.toByte() && header[3] == 0x04.toByte() &&
            (file.extension.equals("task", ignoreCase = true) || file.extension.equals("zip", ignoreCase = true))) {
            try {
                ZipFile(file).use { zip ->
                    if (zip.size() == 0) {
                        return GemmaModelInfo(file, size, GemmaModelFormat.UNKNOWN, false, "MediaPipe Task bundle is empty.")
                    }
                }
            } catch (e: Exception) {
                return GemmaModelInfo(
                    file = file,
                    sizeBytes = size,
                    format = GemmaModelFormat.UNKNOWN,
                    isSupported = false,
                    validationMessage = "Invalid MediaPipe Task ZIP archive: ${e.localizedMessage ?: "unreadable archive"}"
                )
            }
            return GemmaModelInfo(
                file = file,
                sizeBytes = size,
                format = GemmaModelFormat.MEDIAPIPE_TASK,
                isSupported = true,
                validationMessage = "MediaPipe Task ZIP archive detected; runtime will perform final validation.",
                quantization = "LiteRT Task Bundle"
            )
        }

        // 3. Check LiteRT-LM / TFLite FlatBuffer magic: "TFL3" at offset 4..7 or .litertlm extension
        val isTfl3 = header[4] == 'T'.code.toByte() && header[5] == 'F'.code.toByte() && header[6] == 'L'.code.toByte() && header[7] == '3'.code.toByte()
        val isLiteRtExtension = file.extension.equals("litertlm", ignoreCase = true)
        if (isTfl3 || isLiteRtExtension) {
            return GemmaModelInfo(
                file = file,
                sizeBytes = size,
                format = GemmaModelFormat.LITERT_LM,
                isSupported = true,
                validationMessage = "LiteRT-LM FlatBuffer container detected; runtime will perform final validation.",
                quantization = "TFLite / LiteRT FlatBuffer"
            )
        }

        // 4. Fallback unknown binary
        return GemmaModelInfo(
            file = file,
            sizeBytes = size,
            format = GemmaModelFormat.UNKNOWN,
            isSupported = false,
            validationMessage = "Unsupported or unknown local model format. Use .gguf, .litertlm, or .task."
        )
    }

    /**
     * Gets the current saved Gemma model file if present.
     */
    fun getModelFile(context: Context): File? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val savedPath = prefs.getString(KEY_SAVED_MODEL_PATH, null)
        if (!savedPath.isNullOrBlank()) {
            val file = File(savedPath)
            if (file.exists() && file.isFile) {
                return file
            }
        }

        // Scan private app models directory
        val modelDir = getModelDirectory(context)
        val dirFiles = modelDir.listFiles()
        if (dirFiles != null) {
            for (f in dirFiles) {
                if (f.isFile) {
                    val info = inspectModel(f)
                    if (info.isSupported) {
                        prefs.edit().putString(KEY_SAVED_MODEL_PATH, f.absolutePath).apply()
                        return f
                    }
                }
            }
        }

        return null
    }

    /**
     * Checks and updates model status without forcing READY until runtime is initialized.
     */
    fun checkModelStatus(context: Context): GemmaModelStatus {
        val file = getModelFile(context)
        if (file == null) {
            _status.value = GemmaModelStatus.NOT_INSTALLED
            _cachedInfo = null
            return GemmaModelStatus.NOT_INSTALLED
        }

        val info = inspectModel(file)
        _cachedInfo = info

        if (!info.isSupported) {
            _status.value = GemmaModelStatus.ERROR
            _lastError = info.validationMessage
            return GemmaModelStatus.ERROR
        }

        if (_status.value == GemmaModelStatus.NOT_INSTALLED || _status.value == GemmaModelStatus.ERROR) {
            _status.value = GemmaModelStatus.VALIDATING
        }
        return _status.value
    }

    fun setStatus(newStatus: GemmaModelStatus, errorMsg: String? = null) {
        _status.value = newStatus
        _lastError = errorMsg
    }

    /**
     * Safely imports model from user-selected Uri via Storage Access Framework.
     * Uses atomic copy -> binary inspection -> save.
     * Does NOT assign READY until runtime health check passes.
     */
    suspend fun importModelFromUri(context: Context, uri: Uri, originalName: String? = null): Boolean = mutex.withLock {
        _status.value = GemmaModelStatus.IMPORTING
        _lastError = null
        var tempFile: File? = null

        return try {
            val targetDir = getModelDirectory(context)
            val requestedExtension = originalName
                ?.substringAfterLast(".", "")
                ?.lowercase(java.util.Locale.ROOT)
                .orEmpty()
            val ext = when (requestedExtension) {
                "gguf", "litertlm", "task", "zip", "bin", "tflite" -> ".$requestedExtension"
                else -> ".bin"
            }

            val safeBaseName = (originalName?.substringBeforeLast(".") ?: "local_model")
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .trim('_', '.')
                .ifBlank { "local_model" }
            tempFile = File(targetDir, "import_temp_${java.util.UUID.randomUUID()}$ext")
            var finalFile = File(targetDir, "$safeBaseName$ext")
            var duplicateIndex = 2
            while (finalFile.exists()) {
                finalFile = File(targetDir, "${safeBaseName}_${duplicateIndex++}$ext")
            }

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(tempFile!!).use { outputStream ->
                    val buffer = ByteArray(128 * 1024)
                    var bytesRead: Int
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        bytesRead = inputStream.read(buffer)
                        if (bytesRead == -1) break
                        outputStream.write(buffer, 0, bytesRead)
                    }
                    outputStream.fd.sync()
                }
            } ?: run {
                _status.value = GemmaModelStatus.ERROR
                _lastError = "Unable to open input stream from selected URI."
                return false
            }

            // Inspect temporary file
            val info = inspectModel(tempFile!!)
            _cachedInfo = info

            if (!info.isSupported) {
                tempFile!!.delete()
                _status.value = GemmaModelStatus.ERROR
                _lastError = info.validationMessage
                Log.e(TAG, "Import rejected: ${info.validationMessage}")
                return false
            }

            // The temp file lives in the same private directory. Require an atomic rename:
            // a fallback copy could leave a half-written final model if storage runs out.
            if (!tempFile!!.renameTo(finalFile)) {
                _status.value = GemmaModelStatus.ERROR
                _lastError = "Unable to finalize the imported model atomically. Check free storage and try again."
                return false
            }
            tempFile = null

            val finalInfo = inspectModel(finalFile)
            if (!finalInfo.isSupported) {
                finalFile.delete()
                _status.value = GemmaModelStatus.ERROR
                _lastError = "Imported model failed final validation: ${finalInfo.validationMessage}"
                return false
            }

            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            if (!prefs.edit().putString(KEY_SAVED_MODEL_PATH, finalFile.absolutePath).commit()) {
                finalFile.delete()
                _status.value = GemmaModelStatus.ERROR
                _lastError = "Model was validated but its selected path could not be saved."
                return false
            }

            _cachedInfo = finalInfo
            _status.value = GemmaModelStatus.VALIDATING

            Log.i(TAG, "Local model imported successfully: ${finalFile.absolutePath} (${finalFile.length()} bytes)")
            true
        } catch (cancelled: CancellationException) {
            tempFile?.delete()
            _status.value = GemmaModelStatus.ERROR
            _lastError = "Model import was cancelled before completion."
            throw cancelled
        } catch (e: Exception) {
            tempFile?.delete()
            Log.e(TAG, "Failed to import local model", e)
            _status.value = GemmaModelStatus.ERROR
            _lastError = "Import failed: ${e.localizedMessage}"
            false
        }
    }

    /** Returns all imported local models recognized by the format inspector. */
    fun getAvailableModels(context: Context): List<GemmaModelInfo> =
        getModelDirectory(context).listFiles()
            ?.asSequence()
            ?.filter { it.isFile }
            ?.map { inspectModel(it) }
            ?.filter { it.isSupported }
            ?.sortedBy { it.file.name.lowercase() }
            ?.toList()
            ?: emptyList()

    /** Selects one imported model without deleting other imported models. */
    fun selectModel(context: Context, file: File): Boolean {
        val candidate = file.absoluteFile
        val info = inspectModel(candidate)
        if (!candidate.isFile || !info.isSupported) return false
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SAVED_MODEL_PATH, candidate.absolutePath).apply()
        _cachedInfo = info
        _status.value = GemmaModelStatus.VALIDATING
        return true
    }

    /**
     * Clears saved model path reference.
     */
    fun clearModel(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_SAVED_MODEL_PATH).apply()
        _status.value = GemmaModelStatus.NOT_INSTALLED
        _cachedInfo = null
        _lastError = null
    }
}
