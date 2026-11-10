package com.example.ai.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class TtsModelFileSpec(
    val id: String,
    val fileName: String,
    val remotePath: String,
    val expectedSizeBytes: Long,
    val sha256: String? = null
)

enum class ModelDownloadStatus { NOT_DOWNLOADED, DOWNLOADING, PAUSED, VERIFYING, READY, ERROR }

/** Official Indic Tiny TTS Hindi ONNX model downloader and integrity verifier. */
class IndicTinyTtsModelManager private constructor(private val appContext: Context) {
    companion object {
        private const val TAG = "IndicTinyTtsManager"
        private const val HF_BASE_URL = "https://huggingface.co/sulabhkatiyar/indic-tiny-tts-hindi/resolve/main/"
        private const val MODEL_DIR_NAME = "models/indic_tiny_tts_hindi"

        val MODEL_SPECS = listOf(
            TtsModelFileSpec("text_encoder", "onnx/text_encoder.onnx", "onnx/text_encoder.onnx", 17_695_838L, "38df505ea822800d91a13bdc4a670f81ee964e9e0fe24a705590859fe7cfd08f"),
            TtsModelFileSpec("flow_decoder", "onnx/flow_decoder.onnx", "onnx/flow_decoder.onnx", 19_169_253L, "923a0472ec4d4c05c43bfc8ea512f8f762cf447b9f6ce1f01043144e1f6d1252"),
            TtsModelFileSpec("vocoder", "onnx/vocoder.onnx", "onnx/vocoder.onnx", 54_150_036L, "44d36238a761750447038895ab194e2378f6f4ba2c2d089a4766fa52810ef353"),
            TtsModelFileSpec("onnx_manifest", "onnx/onnx_manifest.json", "onnx/onnx_manifest.json", 0L, "30ce6bb339f2d27b0aada791c57f4197b2dd9ddd7d710dbcb36eb095bd5f762f"),
            TtsModelFileSpec("config", "config.json", "config.json", 0L, "8303f0f7f727c12cd6d4852317a75003d658370da354af2cc683b5db50165f1e"),
            TtsModelFileSpec("release_manifest", "MANIFEST.json", "MANIFEST.json", 0L),
            TtsModelFileSpec("license", "LICENSE.md", "LICENSE.md", 0L, "64fa648284b590c9e62a0d40521ba8c40728ec16cfb485245ef620436f0579ed")
        )

        @Volatile private var instance: IndicTinyTtsModelManager? = null
        fun getInstance(context: Context): IndicTinyTtsModelManager =
            instance ?: synchronized(this) {
                instance ?: IndicTinyTtsModelManager(context.applicationContext).also { instance = it }
            }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val _status = MutableStateFlow(ModelDownloadStatus.NOT_DOWNLOADED)
    val status: StateFlow<ModelDownloadStatus> = _status.asStateFlow()
    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()
    private val _progressText = MutableStateFlow("Model not installed")
    val progressText: StateFlow<String> = _progressText.asStateFlow()
    private var downloadJob: Job? = null
    @Volatile private var activeCall: Call? = null
    @Volatile private var paused = false

    val modelDir: File
        get() = File(appContext.filesDir, MODEL_DIR_NAME).apply { if (!exists()) mkdirs() }

    init { checkInstalledState() }

    fun getInstalledSizeBytes(): Long = modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun isModelReady(): Boolean {
        val filesPresent = MODEL_SPECS.all { spec ->
            val file = File(modelDir, spec.fileName)
            file.isFile && file.length() > 0L &&
                (spec.expectedSizeBytes == 0L || file.length() == spec.expectedSizeBytes)
        }
        if (!filesPresent) return false
        return try {
            val config = JSONObject(File(modelDir, "config.json").readText(Charsets.UTF_8))
            val onnxManifest = JSONObject(File(modelDir, "onnx/onnx_manifest.json").readText(Charsets.UTF_8))
            val releaseManifest = JSONObject(File(modelDir, "MANIFEST.json").readText(Charsets.UTF_8))
            config.optString("language") == "hi" &&
                config.optInt("sample_rate") == 24000 &&
                config.optString("format") == "indic-tiny-tts/1" &&
                config.getJSONObject("voices").has("female") &&
                config.getJSONObject("vocab").optString("normalizer") == "textnorm" &&
                onnxManifest.optString("format") == "indic-tiny-tts-onnx/1" &&
                onnxManifest.optInt("opset") == 18 &&
                onnxManifest.getJSONObject("graphs").length() == 3 &&
                releaseManifest.optString("build_code_sha") == "gd0099827" &&
                releaseManifest.getJSONObject("files").has("config.json")
        } catch (_: Exception) { false }
    }

    fun verifyModelIntegrity(): Boolean {
        if (!isModelReady()) return false
        return try {
            for (spec in MODEL_SPECS.filter { it.sha256 != null }) {
                val file = File(modelDir, spec.fileName)
                if (sha256(file) != spec.sha256) return false
            }
            val releaseManifest = JSONObject(File(modelDir, "MANIFEST.json").readText(Charsets.UTF_8))
            val releaseFiles = releaseManifest.getJSONObject("files")
            for (spec in MODEL_SPECS.filter { it.sha256 != null }) {
                val declared = releaseFiles.optString(spec.remotePath)
                if (!declared.equals(spec.sha256, ignoreCase = true)) return false
            }
            val onnxManifest = JSONObject(File(modelDir, "onnx/onnx_manifest.json").readText(Charsets.UTF_8))
            for (spec in MODEL_SPECS.filter { it.id in setOf("text_encoder", "flow_decoder", "vocoder") }) {
                val graphSha = onnxManifest.getJSONObject("graphs").getJSONObject(spec.id).optString("sha256")
                if (!graphSha.equals(spec.sha256, ignoreCase = true)) return false
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Model integrity verification failed", e)
            false
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun checkInstalledState() {
        if (isModelReady()) {
            _status.value = ModelDownloadStatus.READY
            _downloadProgress.value = 1f
            _progressText.value = "Model files installed; integrity checked before synthesis"
        } else {
            _status.value = ModelDownloadStatus.NOT_DOWNLOADED
            _downloadProgress.value = 0f
            _progressText.value = "Ready to download (approximately 91 MB)"
        }
    }

    fun downloadAll() {
        if (_status.value == ModelDownloadStatus.DOWNLOADING) return
        paused = false
        _status.value = ModelDownloadStatus.DOWNLOADING
        _progressText.value = "Downloading official Indic Tiny TTS Hindi ONNX files..."
        downloadJob = scope.launch {
            try {
                val totalBytes = MODEL_SPECS.sumOf { if (it.expectedSizeBytes > 0L) it.expectedSizeBytes else 4_000L }
                var completedBytes = MODEL_SPECS.sumOf { spec ->
                    val f = File(modelDir, spec.fileName)
                    if (f.isFile && f.length() > 0L &&
                        (spec.expectedSizeBytes == 0L || f.length() == spec.expectedSizeBytes)
                    ) f.length() else 0L
                }
                for (spec in MODEL_SPECS) {
                    if (paused) {
                        _status.value = ModelDownloadStatus.PAUSED
                        _progressText.value = "Download paused"
                        return@launch
                    }
                    val target = File(modelDir, spec.fileName)
                    if (target.isFile && target.length() > 0L &&
                        (spec.expectedSizeBytes == 0L || target.length() == spec.expectedSizeBytes)
                    ) continue
                    val downloaded = downloadOne(spec, target) { bytes ->
                        val progress = ((completedBytes + bytes).toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.99f)
                        _downloadProgress.value = progress
                        _progressText.value = "Downloading " + spec.fileName
                    }
                    if (!downloaded) {
                        if (paused) {
                            _status.value = ModelDownloadStatus.PAUSED
                            _progressText.value = "Download paused"
                            return@launch
                        }
                        throw IllegalStateException("Could not download or validate " + spec.fileName)
                    }
                    completedBytes += if (spec.expectedSizeBytes > 0L) spec.expectedSizeBytes else target.length()
                }
                _status.value = ModelDownloadStatus.VERIFYING
                _progressText.value = "Checking model files and SHA-256..."
                val valid = withContext(Dispatchers.IO) { verifyModelIntegrity() }
                if (!valid) throw IllegalStateException("Model checksum or manifest validation failed. Delete the model and download it again.")
                _status.value = ModelDownloadStatus.READY
                _downloadProgress.value = 1f
                _progressText.value = "Indic Tiny TTS Hindi installed and verified (approximately 91 MB)"
            } catch (cancelled: CancellationException) {
                _status.value = if (paused) ModelDownloadStatus.PAUSED else ModelDownloadStatus.NOT_DOWNLOADED
                throw cancelled
            } catch (e: Exception) {
                Log.e(TAG, "Model download failed", e)
                _status.value = ModelDownloadStatus.ERROR
                _progressText.value = "Download failed: " + (e.message ?: "unknown error")
            }
        }
    }

    private suspend fun downloadOne(spec: TtsModelFileSpec, target: File, onProgress: (Long) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val parent = target.parentFile ?: return@withContext false
            if (!parent.exists() && !parent.mkdirs()) return@withContext false
            val partial = File(parent, target.name + ".part")
            try {
                val request = Request.Builder().url(HF_BASE_URL + spec.remotePath).get().build()
                val call = client.newCall(request)
                activeCall = call
                call.execute().use { response ->
                    if (!response.isSuccessful) return@withContext false
                    val body = response.body ?: return@withContext false
                    if (spec.expectedSizeBytes > 0L && body.contentLength() > 0L &&
                        body.contentLength() != spec.expectedSizeBytes
                    ) return@withContext false
                    FileOutputStream(partial).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (paused) return@withContext false
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                onProgress(count.toLong())
                            }
                            output.fd.sync()
                        }
                    }
                }
                if (!partial.isFile || partial.length() <= 0L ||
                    (spec.expectedSizeBytes > 0L && partial.length() != spec.expectedSizeBytes)
                ) return@withContext false
                if (spec.sha256 != null && sha256(partial) != spec.sha256) return@withContext false
                if (target.exists() && !target.delete()) return@withContext false
                if (!partial.renameTo(target)) return@withContext false
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w(TAG, "Download failed for " + spec.fileName, e)
                false
            } finally {
                if (partial.exists()) partial.delete()
                activeCall = null
            }
        }

    fun pauseDownload() {
        paused = true
        activeCall?.cancel()
        downloadJob?.cancel()
        _status.value = ModelDownloadStatus.PAUSED
        _progressText.value = "Download paused"
    }

    fun resumeDownload() = downloadAll()

    fun cancelDownload() {
        paused = true
        activeCall?.cancel()
        downloadJob?.cancel()
        downloadJob = null
        checkInstalledState()
    }

    fun verifyModel(): Boolean {
        _status.value = ModelDownloadStatus.VERIFYING
        val valid = verifyModelIntegrity()
        if (valid) {
            _status.value = ModelDownloadStatus.READY
            _downloadProgress.value = 1f
            _progressText.value = "Model integrity verified"
        } else {
            _status.value = ModelDownloadStatus.ERROR
            _progressText.value = "Model verification failed. Re-download the model."
        }
        return valid
    }

    fun deleteModel() {
        paused = true
        activeCall?.cancel()
        downloadJob?.cancel()
        downloadJob = null
        modelDir.deleteRecursively()
        checkInstalledState()
    }

    fun redownloadModel() {
        deleteModel()
        downloadAll()
    }
}
