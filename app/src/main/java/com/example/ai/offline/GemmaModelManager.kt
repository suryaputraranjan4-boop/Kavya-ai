package com.example.ai.offline

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.utils.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

enum class GemmaModelStatus {
    NOT_INSTALLED,
    INSTALLING,
    READY,
    LOADING,
    RUNNING,
    ERROR
}

/**
 * Dedicated local model manager for Gemma 4 E4B on-device model.
 * Handles locating, validating, copying/importing, and checking model file status.
 * Optimized for mobile devices like Samsung Galaxy A16 5G (offline, no cloud calls).
 */
object GemmaModelManager {

    private const val TAG = "GemmaModelManager"
    private const val MODEL_FILENAME = "gemma-4-e4b.bin"
    private const val ALT_MODEL_FILENAME = "gemma-4-e4b.task"
    private const val PREF_CUSTOM_MODEL_PATH = "key_gemma_custom_model_path"

    private val mutex = Mutex()
    private val _status = MutableStateFlow(GemmaModelStatus.NOT_INSTALLED)
    val status: StateFlow<GemmaModelStatus> = _status.asStateFlow()

    private var _lastError: String? = null
    val lastError: String? get() = _lastError

    /**
     * Gets the designated model storage directory for Kavya AI.
     */
    fun getModelDirectory(context: Context): File {
        val dir = File(context.filesDir, "models/gemma")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Locate locally installed Gemma 4 E4B model file.
     * Checks:
     * 1. Saved custom path in preferences
     * 2. App's internal storage directory (filesDir/models/gemma/)
     * 3. App's external files directory
     * 4. Standard download locations (/sdcard/Download/)
     */
    fun getModelFile(context: Context): File? {
        val prefs = context.getSharedPreferences("kavya_gemma_prefs", Context.MODE_PRIVATE)
        val customPath = prefs.getString(PREF_CUSTOM_MODEL_PATH, null)
        if (!customPath.isNullOrBlank()) {
            val customFile = File(customPath)
            if (isValidModelFile(customFile)) {
                return customFile
            }
        }

        val internalDir = getModelDirectory(context)
        val internalBin = File(internalDir, MODEL_FILENAME)
        if (isValidModelFile(internalBin)) return internalBin

        val internalTask = File(internalDir, ALT_MODEL_FILENAME)
        if (isValidModelFile(internalTask)) return internalTask

        val externalDir = context.getExternalFilesDir(null)
        if (externalDir != null) {
            val extBin = File(externalDir, MODEL_FILENAME)
            if (isValidModelFile(extBin)) return extBin

            val extTask = File(externalDir, ALT_MODEL_FILENAME)
            if (isValidModelFile(extTask)) return extTask
        }

        // Common download directory scan
        val downloadsDir = File("/sdcard/Download")
        if (downloadsDir.exists() && downloadsDir.isDirectory) {
            val candidates = listOf(
                File(downloadsDir, MODEL_FILENAME),
                File(downloadsDir, ALT_MODEL_FILENAME),
                File(downloadsDir, "gemma-4-e4b-it.bin"),
                File(downloadsDir, "gemma-4-e4b-it.task"),
                File(downloadsDir, "gemma4-e4b.bin")
            )
            for (candidate in candidates) {
                if (isValidModelFile(candidate)) {
                    return candidate
                }
            }
        }

        return null
    }

    /**
     * Check whether a model file exists and is non-empty (>10MB threshold for valid quant artifact).
     */
    private fun isValidModelFile(file: File): Boolean {
        return file.exists() && file.isFile && file.length() > 10 * 1024 * 1024 // > 10MB
    }

    /**
     * Checks and updates current model availability status.
     */
    fun checkModelStatus(context: Context): GemmaModelStatus {
        val file = getModelFile(context)
        val newStatus = if (file != null && isValidModelFile(file)) {
            if (_status.value == GemmaModelStatus.NOT_INSTALLED || _status.value == GemmaModelStatus.ERROR) {
                GemmaModelStatus.READY
            } else {
                _status.value
            }
        } else {
            GemmaModelStatus.NOT_INSTALLED
        }
        _status.value = newStatus
        return newStatus
    }

    fun setStatus(newStatus: GemmaModelStatus, errorMsg: String? = null) {
        _status.value = newStatus
        _lastError = errorMsg
    }

    /**
     * Import model file from user selected Uri (via Storage Access Framework).
     */
    suspend fun importModelFromUri(context: Context, uri: Uri, fileName: String? = null): Boolean = mutex.withLock {
        _status.value = GemmaModelStatus.INSTALLING
        _lastError = null

        return try {
            val targetDir = getModelDirectory(context)
            val destName = if (!fileName.isNullOrBlank() && (fileName.endsWith(".bin") || fileName.endsWith(".task"))) {
                fileName
            } else {
                MODEL_FILENAME
            }
            val destFile = File(targetDir, destName)

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(destFile).use { outputStream ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                    outputStream.flush()
                }
            }

            if (isValidModelFile(destFile)) {
                val prefs = context.getSharedPreferences("kavya_gemma_prefs", Context.MODE_PRIVATE)
                prefs.edit().putString(PREF_CUSTOM_MODEL_PATH, destFile.absolutePath).apply()
                _status.value = GemmaModelStatus.READY
                Log.i(TAG, "Gemma 4 E4B model successfully imported: ${destFile.absolutePath} (${destFile.length()} bytes)")
                true
            } else {
                _lastError = "Imported file is invalid or too small to be a Gemma 4 E4B model."
                _status.value = GemmaModelStatus.ERROR
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import Gemma model from Uri", e)
            _lastError = "Model import failed: ${e.localizedMessage}"
            _status.value = GemmaModelStatus.ERROR
            false
        }
    }

    /**
     * Directly set path to an existing local model file.
     */
    fun setCustomModelPath(context: Context, path: String): Boolean {
        val file = File(path)
        if (isValidModelFile(file)) {
            val prefs = context.getSharedPreferences("kavya_gemma_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString(PREF_CUSTOM_MODEL_PATH, file.absolutePath).apply()
            _status.value = GemmaModelStatus.READY
            _lastError = null
            return true
        } else {
            _lastError = "Specified file is not a valid model file."
            _status.value = GemmaModelStatus.ERROR
            return false
        }
    }
}
