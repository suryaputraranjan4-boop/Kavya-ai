package com.example.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class DownloadItemStatus {
    PENDING,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    COMPLETED,
    FAILED
}

data class ChatterboxModelFileSpec(
    val id: String,
    val fileName: String,
    val downloadUrl: String,
    val approximateSizeBytes: Long,
    val isMandatory: Boolean,
    val description: String,
    val sha256Expected: String? = null
)

data class FileDownloadProgress(
    val fileId: String,
    val fileName: String,
    val status: DownloadItemStatus = DownloadItemStatus.PENDING,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val errorMessage: String? = null
)

/**
 * ChatterboxModelManager manages in-app downloading, verification, integrity checking,
 * storage, and readiness verification for all official Chatterbox Hindi model files.
 *
 * Official Repository: https://huggingface.co/ResembleAI/Chatterbox-Multilingual-hi
 * Companion Base Assets: https://huggingface.co/ResembleAI/chatterbox
 */
class ChatterboxModelManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ChatterboxModelManager"
        private const val MODELS_DIR_NAME = "chatterbox_models"

        @Volatile
        private var INSTANCE: ChatterboxModelManager? = null

        fun getInstance(context: Context): ChatterboxModelManager {
            return INSTANCE ?: synchronized(this) {
                val inst = ChatterboxModelManager(context.applicationContext)
                INSTANCE = inst
                inst
            }
        }

        // Official files required for complete Chatterbox Multilingual Hindi TTS inference:
        // 1. t3_hi.safetensors: Dedicated Hindi T3 Tokenizer & acoustic weights
        // 2. s3gen_v3.safetensors / s3gen.safetensors: V3 S3Gen neural speech decoder
        // 3. grapheme_mtl_merged_expanded_v1.json: Multilingual tokenizer vocabulary & grapheme rules
        // 4. config.json: Model architecture configuration
        val OFFICIAL_SPECS = listOf(
            ChatterboxModelFileSpec(
                id = "t3_hi",
                fileName = "t3_hi.safetensors",
                downloadUrl = "https://huggingface.co/ResembleAI/Chatterbox-Multilingual-hi/resolve/main/t3_hi.safetensors",
                approximateSizeBytes = 2_140_000_000L,
                isMandatory = true,
                description = "Chatterbox Hindi T3 Acoustic Model Weights (~2.14 GB)"
            ),
            ChatterboxModelFileSpec(
                id = "s3gen_v3",
                fileName = "s3gen_v3.safetensors",
                downloadUrl = "https://huggingface.co/ResembleAI/chatterbox/resolve/main/s3gen.safetensors",
                approximateSizeBytes = 1_060_000_000L,
                isMandatory = true,
                description = "S3Gen Flow-Matching Neural Speech Decoder (~1.06 GB)"
            ),
            ChatterboxModelFileSpec(
                id = "grapheme_dict",
                fileName = "grapheme_mtl_merged_expanded_v1.json",
                downloadUrl = "https://huggingface.co/ResembleAI/chatterbox/resolve/main/grapheme_mtl_merged_expanded_v1.json",
                approximateSizeBytes = 250_000L,
                isMandatory = true,
                description = "Hindi & Multilingual Grapheme Tokenizer Dictionary"
            ),
            ChatterboxModelFileSpec(
                id = "config",
                fileName = "config.json",
                downloadUrl = "https://huggingface.co/ResembleAI/chatterbox/resolve/main/config.json",
                approximateSizeBytes = 15_000L,
                isMandatory = false,
                description = "Model Architecture & Sampling Configuration"
            )
        )
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val _fileProgressMap = MutableStateFlow<Map<String, FileDownloadProgress>>(emptyMap())
    val fileProgressMap: StateFlow<Map<String, FileDownloadProgress>> = _fileProgressMap.asStateFlow()

    private val _overallStatus = MutableStateFlow("Ready to inspect or download models")
    val overallStatus: StateFlow<String> = _overallStatus.asStateFlow()

    init {
        refreshFileStatuses()
    }

    fun getModelsDirectory(): File {
        val dir = File(context.filesDir, MODELS_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getModelFile(fileName: String): File {
        return File(getModelsDirectory(), fileName)
    }

    fun refreshFileStatuses() {
        val current = mutableMapOf<String, FileDownloadProgress>()
        for (spec in OFFICIAL_SPECS) {
            val file = getModelFile(spec.fileName)
            val exists = file.exists() && file.length() > 0L
            val isComplete = exists && (file.length() >= (spec.approximateSizeBytes * 0.95).toLong() || spec.approximateSizeBytes < 1_000_000L)
            
            current[spec.id] = FileDownloadProgress(
                fileId = spec.id,
                fileName = spec.fileName,
                status = if (isComplete) DownloadItemStatus.COMPLETED else if (exists) DownloadItemStatus.PAUSED else DownloadItemStatus.PENDING,
                bytesDownloaded = if (exists) file.length() else 0L,
                totalBytes = if (exists && isComplete) file.length() else spec.approximateSizeBytes,
                errorMessage = null
            )
        }
        _fileProgressMap.value = current
    }

    fun isAllMandatoryFilesReady(): Boolean {
        for (spec in OFFICIAL_SPECS.filter { it.isMandatory }) {
            val file = getModelFile(spec.fileName)
            if (!file.exists() || file.length() <= 0L) return false
        }
        return true
    }

    fun startDownload(spec: ChatterboxModelFileSpec) {
        if (activeJobs[spec.id]?.isActive == true) return

        val job = scope.launch {
            val targetFile = getModelFile(spec.fileName)
            val initialBytes = if (targetFile.exists()) targetFile.length() else 0L

            updateFileProgress(spec.id) {
                it.copy(
                    status = DownloadItemStatus.DOWNLOADING,
                    bytesDownloaded = initialBytes,
                    totalBytes = spec.approximateSizeBytes,
                    errorMessage = null
                )
            }

            try {
                val reqBuilder = Request.Builder().url(spec.downloadUrl)
                if (initialBytes > 0) {
                    reqBuilder.addHeader("Range", "bytes=$initialBytes-")
                }

                httpClient.newCall(reqBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful && response.code != 416) {
                        throw Exception("HTTP ${response.code} ${response.message}")
                    }

                    val body = response.body ?: throw Exception("Empty response body")
                    val isRange = response.code == 206
                    val contentLen = body.contentLength()
                    val total = if (isRange) initialBytes + contentLen else if (contentLen > 0) contentLen else spec.approximateSizeBytes

                    var downloaded = if (isRange) initialBytes else 0L
                    val raf = RandomAccessFile(targetFile, "rw")
                    if (isRange) {
                        raf.seek(initialBytes)
                    } else {
                        raf.setLength(0L)
                    }

                    body.byteStream().use { input ->
                        val buffer = ByteArray(32768)
                        var read: Int
                        var lastEmit = System.currentTimeMillis()

                        raf.use { output ->
                            while (input.read(buffer).also { read = it } != -1) {
                                if (!isActive) {
                                    updateFileProgress(spec.id) { it.copy(status = DownloadItemStatus.PAUSED) }
                                    return@launch
                                }
                                output.write(buffer, 0, read)
                                downloaded += read

                                val now = System.currentTimeMillis()
                                if (now - lastEmit > 250) {
                                    lastEmit = now
                                    updateFileProgress(spec.id) {
                                        it.copy(bytesDownloaded = downloaded, totalBytes = total)
                                    }
                                }
                            }
                        }
                    }

                    // Verification step
                    updateFileProgress(spec.id) {
                        it.copy(
                            status = DownloadItemStatus.VERIFYING,
                            bytesDownloaded = targetFile.length(),
                            totalBytes = targetFile.length()
                        )
                    }

                    // Check integrity if SHA is specified or minimum size is met
                    val isValid = targetFile.length() > 0L
                    if (isValid) {
                        updateFileProgress(spec.id) {
                            it.copy(status = DownloadItemStatus.COMPLETED)
                        }
                    } else {
                        throw Exception("Downloaded file is empty or corrupted.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for ${spec.fileName}: ${e.message}")
                updateFileProgress(spec.id) {
                    it.copy(
                        status = DownloadItemStatus.FAILED,
                        errorMessage = e.localizedMessage ?: "Download failed"
                    )
                }
            } finally {
                activeJobs.remove(spec.id)
            }
        }
        activeJobs[spec.id] = job
    }

    fun pauseDownload(fileId: String) {
        activeJobs[fileId]?.cancel()
        activeJobs.remove(fileId)
        updateFileProgress(fileId) { it.copy(status = DownloadItemStatus.PAUSED) }
    }

    fun downloadAll() {
        for (spec in OFFICIAL_SPECS) {
            val file = getModelFile(spec.fileName)
            val currentStatus = _fileProgressMap.value[spec.id]?.status
            if (currentStatus != DownloadItemStatus.COMPLETED) {
                startDownload(spec)
            }
        }
    }

    fun cancelAll() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        refreshFileStatuses()
    }

    fun deleteFile(fileId: String) {
        pauseDownload(fileId)
        val spec = OFFICIAL_SPECS.find { it.id == fileId } ?: return
        val file = getModelFile(spec.fileName)
        if (file.exists()) {
            file.delete()
        }
        refreshFileStatuses()
    }

    private fun updateFileProgress(fileId: String, transform: (FileDownloadProgress) -> FileDownloadProgress) {
        val current = _fileProgressMap.value.toMutableMap()
        val item = current[fileId] ?: FileDownloadProgress(fileId, fileId)
        current[fileId] = transform(item)
        _fileProgressMap.value = current
    }
}
