package com.example.ai.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ModelDownloadProgressState(
    val modelId: String,
    val state: ModelLifecycleState = ModelLifecycleState.NOT_INSTALLED,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val progress: Float = 0f,
    val speedBytesPerSec: Long = 0L,
    val activeArtifactName: String = "",
    val statusMessage: String = "Not installed",
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val autoResumePending: Boolean = false
)

data class ResolvedRemoteFileMetadata(
    val path: String,
    val sizeBytes: Long,
    val sha256: String?
)

data class BatchBudgetPreview(
    val requestedModelIds: List<String>,
    val totalDownloadBytes: Long,
    val existingInstalledBytes: Long,
    val projectedTotalBytes: Long,
    val availableDiskBytes: Long,
    val activeProfile: OfflineStorageProfile,
    val fitsBudget: Boolean,
    val reason: String
)

/**
 * Shared resumable download, backoff retry, connectivity auto-resume, dynamic HF checksum
 * resolution, and storage budget coordinator for Voice, Lightweight Vision, and Advanced Vision models.
 */
class ModelDownloadCoordinator private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "ModelDownloadCoord"
        private const val METADATA_FILENAME = "resolved_metadata.json"
        private const val MAX_TRANSIENT_RETRIES = 3

        @Volatile
        private var instance: ModelDownloadCoordinator? = null

        fun getInstance(context: Context): ModelDownloadCoordinator =
            instance ?: synchronized(this) {
                instance ?: ModelDownloadCoordinator(context.applicationContext).also { instance = it }
            }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _states = MutableStateFlow<Map<String, ModelDownloadProgressState>>(emptyMap())
    val states: StateFlow<Map<String, ModelDownloadProgressState>> = _states.asStateFlow()

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val pausedByUser = ConcurrentHashMap<String, Boolean>()
    private val downloadQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private var networkCallbackRegistered = false

    init {
        refreshAllStates()
        registerConnectivityAutoResume()
    }

    fun getState(modelId: String): ModelDownloadProgressState =
        _states.value[modelId] ?: ModelDownloadProgressState(modelId = modelId)

    fun refreshAllStates() {
        val baseDir = GemmaModelManager.getModelDirectory(appContext)
        val updated = LinkedHashMap<String, ModelDownloadProgressState>()
        for (desc in ModelCatalog.allDescriptors()) {
            val id = desc.manifest.modelId
            val existing = _states.value[id]
            if (existing?.state in listOf(
                    ModelLifecycleState.DOWNLOAD_QUEUED,
                    ModelLifecycleState.DOWNLOADING,
                    ModelLifecycleState.RESUMING,
                    ModelLifecycleState.VERIFYING,
                    ModelLifecycleState.LOADING_RUNTIME
                )
            ) {
                updated[id] = existing!!
                continue
            }

            if (!isDeviceSupported(desc)) {
                updated[id] = ModelDownloadProgressState(
                    modelId = id,
                    state = ModelLifecycleState.UNSUPPORTED_DEVICE,
                    totalBytes = desc.estimatedSizeBytes,
                    statusMessage = "Requires API ${desc.minApiLevel}+ / arm64-v8a"
                )
                continue
            }

            val installedBytes = computeInstalledOrPartialBytes(baseDir, desc.manifest)
            val isVerified = isModelInstalledAndValid(baseDir, desc.manifest, verifySha256 = false)
            val hasPartFiles = desc.manifest.artifacts.any {
                File(baseDir, "${it.relativePath}.part").exists()
            }

            updated[id] = when {
                isVerified -> ModelDownloadProgressState(
                    modelId = id,
                    state = existing?.state?.takeIf { it == ModelLifecycleState.READY } ?: ModelLifecycleState.READY,
                    bytesDownloaded = installedBytes,
                    totalBytes = installedBytes.coerceAtLeast(desc.estimatedSizeBytes),
                    progress = 1f,
                    statusMessage = "Installed & verified (${installedBytes / (1024 * 1024)} MB)"
                )
                hasPartFiles -> ModelDownloadProgressState(
                    modelId = id,
                    state = ModelLifecycleState.PAUSED,
                    bytesDownloaded = installedBytes,
                    totalBytes = desc.estimatedSizeBytes.coerceAtLeast(installedBytes),
                    progress = if (desc.estimatedSizeBytes > 0) (installedBytes.toFloat() / desc.estimatedSizeBytes).coerceIn(0f, 0.99f) else 0f,
                    statusMessage = "Paused (${installedBytes / (1024 * 1024)} MB saved)"
                )
                else -> ModelDownloadProgressState(
                    modelId = id,
                    state = ModelLifecycleState.NOT_INSTALLED,
                    totalBytes = desc.estimatedSizeBytes,
                    statusMessage = "Not installed (~${(desc.estimatedSizeBytes / (1024f * 1024f)).toInt()} MB)"
                )
            }
        }
        _states.value = updated
    }

    fun isDeviceSupported(desc: ManagedModelDescriptor): Boolean {
        if (Build.VERSION.SDK_INT < desc.minApiLevel) return false
        if (desc.requiredAbis.isNotEmpty()) {
            val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
            // Note: under Robolectric BUILD.SUPPORTED_ABIS may be empty; only reject real mismatch when non-empty
            if (abis.isNotEmpty() && desc.requiredAbis.none { it in abis }) {
                return false
            }
        }
        return true
    }

    fun calculateInstalledTotalBytes(): Long {
        val baseDir = GemmaModelManager.getModelDirectory(appContext)
        return baseDir.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".part") }
            .sumOf { it.length() }
    }

    fun getAvailableDiskBytes(): Long {
        return try {
            val dir = GemmaModelManager.getModelDirectory(appContext)
            val stat = StatFs(dir.absolutePath)
            stat.availableBytes
        } catch (_: Throwable) {
            Long.MAX_VALUE
        }
    }

    fun previewBatchBudget(
        modelIds: List<String>,
        baseDir: File = GemmaModelManager.getModelDirectory(appContext),
        availableDiskBytes: Long = getAvailableDiskBytes()
    ): BatchBudgetPreview {
        val activeProfile = GemmaModelManager.getStorageProfile(appContext)
        val existingBytes = baseDir.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".part") }
            .sumOf { it.length() }

        var pendingDownloadBytes = 0L
        for (id in modelIds.distinct()) {
            val desc = ModelCatalog.findById(id) ?: continue
            if (!isDeviceSupported(desc)) {
                return BatchBudgetPreview(
                    requestedModelIds = modelIds,
                    totalDownloadBytes = 0L,
                    existingInstalledBytes = existingBytes,
                    projectedTotalBytes = existingBytes,
                    availableDiskBytes = availableDiskBytes,
                    activeProfile = activeProfile,
                    fitsBudget = false,
                    reason = "${desc.displayName} is unsupported on this device (requires API ${desc.minApiLevel}+ / arm64-v8a)."
                )
            }
            if (desc.manifest.minimumProfile == OfflineStorageProfile.EXPANDED &&
                activeProfile != OfflineStorageProfile.EXPANDED
            ) {
                return BatchBudgetPreview(
                    requestedModelIds = modelIds,
                    totalDownloadBytes = pendingDownloadBytes + desc.estimatedSizeBytes,
                    existingInstalledBytes = existingBytes,
                    projectedTotalBytes = existingBytes + pendingDownloadBytes + desc.estimatedSizeBytes,
                    availableDiskBytes = availableDiskBytes,
                    activeProfile = activeProfile,
                    fitsBudget = false,
                    reason = "STORAGE_INSUFFICIENT: '${desc.displayName}' requires Expanded storage profile opt-in."
                )
            }
            if (!isModelInstalledAndValid(baseDir, desc.manifest, verifySha256 = false)) {
                pendingDownloadBytes += desc.estimatedSizeBytes
            }
        }

        val projected = existingBytes + pendingDownloadBytes
        if (projected > activeProfile.maxTotalBytes) {
            return BatchBudgetPreview(
                requestedModelIds = modelIds,
                totalDownloadBytes = pendingDownloadBytes,
                existingInstalledBytes = existingBytes,
                projectedTotalBytes = projected,
                availableDiskBytes = availableDiskBytes,
                activeProfile = activeProfile,
                fitsBudget = false,
                reason = "STORAGE_INSUFFICIENT: Batch requires ${projected / (1024 * 1024)} MB total, exceeding ${activeProfile.name} cap (${activeProfile.maxTotalBytes / (1024 * 1024)} MB)."
            )
        }

        val headroom = 128L * 1024L * 1024L
        if (pendingDownloadBytes > 0L && availableDiskBytes < pendingDownloadBytes + headroom) {
            return BatchBudgetPreview(
                requestedModelIds = modelIds,
                totalDownloadBytes = pendingDownloadBytes,
                existingInstalledBytes = existingBytes,
                projectedTotalBytes = projected,
                availableDiskBytes = availableDiskBytes,
                activeProfile = activeProfile,
                fitsBudget = false,
                reason = "STORAGE_INSUFFICIENT: Free disk space too low for download + 128 MB headroom."
            )
        }

        return BatchBudgetPreview(
            requestedModelIds = modelIds,
            totalDownloadBytes = pendingDownloadBytes,
            existingInstalledBytes = existingBytes,
            projectedTotalBytes = projected,
            availableDiskBytes = availableDiskBytes,
            activeProfile = activeProfile,
            fitsBudget = true,
            reason = "OK"
        )
    }

    fun startOrResumeBatch(modelIds: List<String>) {
        val preview = previewBatchBudget(modelIds)
        if (!preview.fitsBudget) {
            modelIds.forEach { id ->
                updateModelState(id) {
                    it.copy(
                        state = ModelLifecycleState.ERROR,
                        errorMessage = preview.reason,
                        statusMessage = preview.reason
                    )
                }
            }
            return
        }

        modelIds.distinct().forEach { id ->
            val current = getState(id)
            if (current.state == ModelLifecycleState.READY) return@forEach
            pausedByUser[id] = false
            updateModelState(id) {
                it.copy(
                    state = ModelLifecycleState.DOWNLOAD_QUEUED,
                    statusMessage = "Download queued",
                    errorMessage = null
                )
            }
            if (!downloadQueue.contains(id)) {
                downloadQueue.offer(id)
            }
        }
        pumpQueue()
    }

    fun startOrResumeDownload(modelId: String) {
        startOrResumeBatch(listOf(modelId))
    }

    fun pauseDownload(modelId: String) {
        pausedByUser[modelId] = true
        activeJobs.remove(modelId)?.cancel()
        downloadQueue.remove(modelId)
        val desc = ModelCatalog.findById(modelId)
        val baseDir = GemmaModelManager.getModelDirectory(appContext)
        val saved = desc?.let { computeInstalledOrPartialBytes(baseDir, it.manifest) } ?: 0L
        updateModelState(modelId) {
            it.copy(
                state = ModelLifecycleState.PAUSED,
                bytesDownloaded = saved,
                autoResumePending = false,
                statusMessage = "Paused by user (${saved / (1024 * 1024)} MB saved)"
            )
        }
    }

    fun cancelDownload(modelId: String) {
        pausedByUser[modelId] = true
        activeJobs.remove(modelId)?.cancel()
        downloadQueue.remove(modelId)
        val desc = ModelCatalog.findById(modelId)
        if (desc != null) {
            val baseDir = GemmaModelManager.getModelDirectory(appContext)
            desc.manifest.artifacts.forEach { spec ->
                File(baseDir, "${spec.relativePath}.part").delete()
            }
        }
        updateModelState(modelId) {
            ModelDownloadProgressState(
                modelId = modelId,
                state = ModelLifecycleState.NOT_INSTALLED,
                totalBytes = desc?.estimatedSizeBytes ?: 0L,
                statusMessage = "Cancelled"
            )
        }
    }

    fun deleteModel(modelId: String) {
        pausedByUser[modelId] = true
        activeJobs.remove(modelId)?.cancel()
        downloadQueue.remove(modelId)
        val desc = ModelCatalog.findById(modelId) ?: return
        val baseDir = GemmaModelManager.getModelDirectory(appContext)
        desc.manifest.artifacts.forEach { spec ->
            File(baseDir, spec.relativePath).delete()
            File(baseDir, "${spec.relativePath}.part").delete()
        }
        metadataFileFor(baseDir, desc.manifest).delete()
        updateModelState(modelId) {
            ModelDownloadProgressState(
                modelId = modelId,
                state = ModelLifecycleState.NOT_INSTALLED,
                totalBytes = desc.estimatedSizeBytes,
                statusMessage = "Deleted from device"
            )
        }
    }

    fun redownloadModel(modelId: String) {
        deleteModel(modelId)
        startOrResumeDownload(modelId)
    }

    fun verifyModelAsync(modelId: String) {
        val desc = ModelCatalog.findById(modelId) ?: return
        scope.launch {
            updateModelState(modelId) {
                it.copy(state = ModelLifecycleState.VERIFYING, statusMessage = "Verifying SHA-256 & integrity...")
            }
            val baseDir = GemmaModelManager.getModelDirectory(appContext)
            val ok = isModelInstalledAndValid(baseDir, desc.manifest, verifySha256 = true)
            if (ok) {
                val size = computeInstalledOrPartialBytes(baseDir, desc.manifest)
                updateModelState(modelId) {
                    it.copy(
                        state = ModelLifecycleState.READY,
                        bytesDownloaded = size,
                        totalBytes = size,
                        progress = 1f,
                        errorMessage = null,
                        statusMessage = "Verified intact (${size / (1024 * 1024)} MB)"
                    )
                }
            } else {
                updateModelState(modelId) {
                    it.copy(
                        state = ModelLifecycleState.ERROR,
                        errorMessage = "Checksum or size verification failed.",
                        statusMessage = "Integrity verification failed"
                    )
                }
            }
        }
    }

    private fun pumpQueue() {
        val nextId = downloadQueue.poll() ?: return
        if (activeJobs.containsKey(nextId)) return
        val desc = ModelCatalog.findById(nextId) ?: return
        val job = scope.launch {
            try {
                executeModelDownload(desc)
            } finally {
                activeJobs.remove(nextId)
                pumpQueue()
            }
        }
        activeJobs[nextId] = job
    }

    private suspend fun executeModelDownload(desc: ManagedModelDescriptor) {
        val modelId = desc.manifest.modelId
        val baseDir = GemmaModelManager.getModelDirectory(appContext)

        if (!isDeviceSupported(desc)) {
            updateModelState(modelId) {
                it.copy(
                    state = ModelLifecycleState.UNSUPPORTED_DEVICE,
                    statusMessage = "Requires API ${desc.minApiLevel}+ / arm64-v8a"
                )
            }
            return
        }

        val hadPartial = desc.manifest.artifacts.any {
            File(baseDir, "${it.relativePath}.part").exists()
        }
        updateModelState(modelId) {
            it.copy(
                state = if (hadPartial) ModelLifecycleState.RESUMING else ModelLifecycleState.DOWNLOADING,
                statusMessage = if (hadPartial) "Resuming ${desc.displayName}..." else "Downloading ${desc.displayName}...",
                errorMessage = null,
                autoResumePending = false
            )
        }

        val resolvedMeta = resolveRemoteMetadataMap(desc.manifest)
        saveResolvedMetadata(baseDir, desc.manifest, resolvedMeta)

        val totalExpected = desc.manifest.artifacts.sumOf { spec ->
            when {
                spec.expectedSizeBytes > 0L -> spec.expectedSizeBytes
                (resolvedMeta[spec.fileName]?.sizeBytes ?: 0L) > 0L -> resolvedMeta[spec.fileName]!!.sizeBytes
                else -> 0L
            }
        }.coerceAtLeast(desc.estimatedSizeBytes)

        var priorCompleted = 0L
        for (spec in desc.manifest.artifacts) {
            val remoteMeta = resolvedMeta[spec.fileName]
            val targetSize = if (spec.expectedSizeBytes > 0L) spec.expectedSizeBytes else (remoteMeta?.sizeBytes ?: 0L)
            val targetSha = spec.expectedSha256.ifBlank { remoteMeta?.sha256.orEmpty() }
            val effectiveSpec = spec.copy(
                expectedSizeBytes = targetSize,
                expectedSha256 = targetSha
            )

            val res = downloadSingleArtifactWithBackoff(
                modelId = modelId,
                baseDir = baseDir,
                spec = effectiveSpec,
                totalBundleBytes = totalExpected,
                priorBytes = priorCompleted
            )

            if (pausedByUser[modelId] == true) return
            if (res.isFailure) {
                val err = res.exceptionOrNull()?.message ?: "Download failed"
                val transientNetwork = !isNetworkConnected() || err.contains("timeout", true) || err.contains("reset", true) || err.contains("failed to connect", true)
                updateModelState(modelId) {
                    it.copy(
                        state = if (transientNetwork) ModelLifecycleState.PAUSED else ModelLifecycleState.ERROR,
                        autoResumePending = transientNetwork,
                        errorMessage = err,
                        statusMessage = if (transientNetwork) "Paused — waiting for network" else err
                    )
                }
                return
            }
            priorCompleted += res.getOrThrow().length()
        }

        updateModelState(modelId) {
            it.copy(
                state = ModelLifecycleState.VERIFYING,
                progress = 0.99f,
                statusMessage = "Verifying SHA-256 checksums & runtime format..."
            )
        }

        val verified = isModelInstalledAndValid(baseDir, desc.manifest, verifySha256 = true)
        if (!verified) {
            updateModelState(modelId) {
                it.copy(
                    state = ModelLifecycleState.ERROR,
                    errorMessage = "MODEL_CORRUPT: Final verification failed after download.",
                    statusMessage = "Corrupt file quarantined — please retry download"
                )
            }
            return
        }

        val finalBytes = computeInstalledOrPartialBytes(baseDir, desc.manifest)
        updateModelState(modelId) {
            it.copy(
                state = ModelLifecycleState.READY,
                bytesDownloaded = finalBytes,
                totalBytes = finalBytes,
                progress = 1f,
                speedBytesPerSec = 0L,
                activeArtifactName = "",
                errorMessage = null,
                autoResumePending = false,
                statusMessage = "Ready (${finalBytes / (1024 * 1024)} MB)"
            )
        }
    }

    private suspend fun downloadSingleArtifactWithBackoff(
        modelId: String,
        baseDir: File,
        spec: OfflineArtifactSpec,
        totalBundleBytes: Long,
        priorBytes: Long
    ): Result<File> {
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt <= MAX_TRANSIENT_RETRIES) {
            if (pausedByUser[modelId] == true) {
                return Result.failure(CancellationException("Paused"))
            }
            if (attempt > 0) {
                val backoffMs = (1000L shl (attempt - 1)).coerceAtMost(8000L)
                updateModelState(modelId) {
                    it.copy(
                        state = ModelLifecycleState.RESUMING,
                        retryCount = attempt,
                        statusMessage = "Retry $attempt/$MAX_TRANSIENT_RETRIES for ${spec.fileName}..."
                    )
                }
                delay(backoffMs)
            }

            val result = downloadArtifactStream(
                modelId = modelId,
                baseDir = baseDir,
                spec = spec,
                totalBundleBytes = totalBundleBytes,
                priorBytes = priorBytes
            )
            if (result.isSuccess) return result
            val ex = result.exceptionOrNull()
            if (ex is CancellationException || pausedByUser[modelId] == true) {
                throw ex ?: CancellationException("Paused")
            }
            lastError = ex
            val msg = ex?.message.orEmpty()
            // Do not retry cryptographic corruption without user notice if server mismatch persists, retry IO errors
            if (msg.contains("MODEL_CORRUPT")) {
                return Result.failure(ex ?: IllegalStateException(msg))
            }
            attempt++
        }
        return Result.failure(lastError ?: IllegalStateException("Download failed after retries"))
    }

    private suspend fun downloadArtifactStream(
        modelId: String,
        baseDir: File,
        spec: OfflineArtifactSpec,
        totalBundleBytes: Long,
        priorBytes: Long
    ): Result<File> = withContext(Dispatchers.IO) {
        val finalFile = File(baseDir, spec.relativePath)
        finalFile.parentFile?.mkdirs()

        if (finalFile.exists()) {
            val (valid, _) = verifyArtifactWithDynamicSpec(finalFile, spec, deleteCorruptFile = true)
            if (valid) return@withContext Result.success(finalFile)
        }

        val partFile = File(finalFile.parentFile, "${finalFile.name}.part")
        if (spec.expectedSizeBytes > 0L && partFile.exists() && partFile.length() > spec.expectedSizeBytes) {
            partFile.delete()
        }
        val existingOffset = if (partFile.exists()) partFile.length() else 0L

        val reqBuilder = Request.Builder().url(spec.downloadUrl)
        if (existingOffset > 0L) {
            reqBuilder.addHeader("Range", "bytes=$existingOffset-")
        }

        val response = try {
            httpClient.newCall(reqBuilder.build()).execute()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.failure(IllegalStateException("Network error: ${e.message}", e))
        }

        var resolvedHeaderSha: String? = null
        var resolvedTotalLen: Long = spec.expectedSizeBytes
        response.use { resp ->
            val code = resp.code
            if (code == 416 && partFile.exists()) {
                // Range already reached/exceeded server size -> validate partFile directly
            } else if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(
                    IllegalStateException("HTTP $code downloading ${spec.fileName}")
                )
            } else {
                val linkedEtag = resp.header("X-Linked-Etag")?.trim('"')
                    ?: resp.header("ETag")?.trim('"')
                if (linkedEtag != null && Regex("^[0-9a-fA-F]{64}$").matches(linkedEtag)) {
                    resolvedHeaderSha = linkedEtag.lowercase()
                }
                val contentLength = resp.body?.contentLength() ?: -1L
                val contentRange = resp.header("Content-Range")
                if (code == HttpURLConnection.HTTP_PARTIAL) {
                    val rangeTotal = contentRange?.substringAfter('/')?.toLongOrNull() ?: -1L
                    if (rangeTotal > 0L) resolvedTotalLen = rangeTotal
                    else if (contentLength > 0L) resolvedTotalLen = existingOffset + contentLength
                    val expectedPrefix = "bytes $existingOffset-"
                    if (existingOffset > 0L && !contentRange.orEmpty().startsWith(expectedPrefix, ignoreCase = true)) {
                        return@withContext Result.failure(
                            IllegalStateException("Invalid Content-Range '${contentRange.orEmpty()}'")
                        )
                    }
                } else {
                    if (contentLength > 0L) resolvedTotalLen = contentLength
                }

                val stream = resp.body?.byteStream()
                    ?: return@withContext Result.failure(IllegalStateException("Empty HTTP body"))

                val appendMode = (code == HttpURLConnection.HTTP_PARTIAL && existingOffset > 0L)
                var lastUiUpdateMs = 0L
                var speedWindowStartMs = System.currentTimeMillis()
                var speedWindowBytes = 0L
                var currentBytesWritten = if (appendMode) existingOffset else 0L

                val raf = if (appendMode) {
                    RandomAccessFile(partFile, "rw").apply { seek(existingOffset) }
                } else {
                    if (partFile.exists()) partFile.delete()
                    RandomAccessFile(partFile, "rw")
                }

                try {
                    stream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            if (pausedByUser[modelId] == true) throw CancellationException("Paused")
                            val read = input.read(buffer)
                            if (read <= 0) break
                            raf.write(buffer, 0, read)
                            currentBytesWritten += read
                            speedWindowBytes += read

                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdateMs >= 200L) {
                                val elapsedSec = ((now - speedWindowStartMs).coerceAtLeast(1L)) / 1000.0
                                val speed = (speedWindowBytes / elapsedSec).toLong()
                                val totalForProgress = totalBundleBytes.coerceAtLeast(priorBytes + resolvedTotalLen)
                                val cumulative = priorBytes + currentBytesWritten
                                val frac = if (totalForProgress > 0L) {
                                    (cumulative.toFloat() / totalForProgress.toFloat()).coerceIn(0f, 0.98f)
                                } else 0f

                                updateModelState(modelId) {
                                    it.copy(
                                        state = ModelLifecycleState.DOWNLOADING,
                                        bytesDownloaded = cumulative,
                                        totalBytes = totalForProgress,
                                        progress = frac,
                                        speedBytesPerSec = speed,
                                        activeArtifactName = spec.fileName,
                                        statusMessage = "Downloading ${spec.fileName} (${(frac * 100).toInt()}%)"
                                    )
                                }
                                lastUiUpdateMs = now
                                if (now - speedWindowStartMs >= 2000L) {
                                    speedWindowStartMs = now
                                    speedWindowBytes = 0L
                                }
                            }
                        }
                        raf.fd.sync()
                    }
                } finally {
                    try { raf.close() } catch (_: Exception) {}
                }
            }
        }

        val finalSpec = spec.copy(
            expectedSizeBytes = if (spec.expectedSizeBytes > 0L) spec.expectedSizeBytes else resolvedTotalLen.coerceAtLeast(0L),
            expectedSha256 = spec.expectedSha256.ifBlank { resolvedHeaderSha.orEmpty() }
        )

        val (valid, reason) = verifyArtifactWithDynamicSpec(partFile, finalSpec, deleteCorruptFile = true)
        if (!valid) {
            return@withContext Result.failure(IllegalStateException(reason))
        }
        if (finalFile.exists()) finalFile.delete()
        if (!partFile.renameTo(finalFile)) {
            return@withContext Result.failure(IllegalStateException("Atomic rename failed for ${spec.fileName}"))
        }
        Result.success(finalFile)
    }

    private fun verifyArtifactWithDynamicSpec(
        file: File,
        spec: OfflineArtifactSpec,
        deleteCorruptFile: Boolean
    ): Pair<Boolean, String> {
        if (!file.isFile || file.length() <= 0L) {
            if (deleteCorruptFile && file.exists()) file.delete()
            return Pair(false, "MODEL_CORRUPT: Missing or empty artifact ${file.name}")
        }
        if (spec.expectedSizeBytes > 0L && file.length() != spec.expectedSizeBytes) {
            if (deleteCorruptFile) file.delete()
            return Pair(false, "MODEL_CORRUPT: Size mismatch for ${file.name}")
        }
        if (spec.expectedSha256.isNotBlank()) {
            val actualSha = GemmaModelManager.computeSha256(file)
            if (!actualSha.equals(spec.expectedSha256, ignoreCase = true)) {
                if (deleteCorruptFile) file.delete()
                return Pair(false, "MODEL_CORRUPT: SHA-256 mismatch for ${file.name}")
            }
        }
        if (spec.format == GemmaModelFormat.LITERT_LM) {
            // Verify non-HTML valid FlatBuffer/archive payload
            val head = ByteArray(16)
            FileInputStream(file).use { it.read(head) }
            val textHead = String(head, Charsets.UTF_8).lowercase()
            if (textHead.startsWith("<!doctype") || textHead.startsWith("<html")) {
                if (deleteCorruptFile) file.delete()
                return Pair(false, "MODEL_CORRUPT: HTML error page downloaded instead of binary ${file.name}")
            }
        }
        return Pair(true, "READY")
    }

    fun isModelInstalledAndValid(
        baseDir: File,
        manifest: OfflineModelManifest,
        verifySha256: Boolean
    ): Boolean {
        val savedMeta = loadResolvedMetadata(baseDir, manifest)
        return manifest.artifacts.all { spec ->
            val file = File(baseDir, spec.relativePath)
            if (!file.isFile || file.length() <= 0L) return@all false
            val expectedLen = if (spec.expectedSizeBytes > 0L) spec.expectedSizeBytes
            else (savedMeta[spec.fileName]?.sizeBytes ?: 0L)
            if (expectedLen > 0L && file.length() != expectedLen) return@all false
            if (verifySha256) {
                val expectedSha = spec.expectedSha256.ifBlank { savedMeta[spec.fileName]?.sha256.orEmpty() }
                if (expectedSha.isNotBlank()) {
                    if (!GemmaModelManager.computeSha256(file).equals(expectedSha, ignoreCase = true)) {
                        return@all false
                    }
                }
            }
            true
        }
    }

    private fun computeInstalledOrPartialBytes(baseDir: File, manifest: OfflineModelManifest): Long {
        var total = 0L
        for (spec in manifest.artifacts) {
            val finalFile = File(baseDir, spec.relativePath)
            val partFile = File(baseDir, "${spec.relativePath}.part")
            when {
                finalFile.isFile -> total += finalFile.length()
                partFile.isFile -> total += partFile.length()
            }
        }
        return total
    }

    private fun resolveRemoteMetadataMap(manifest: OfflineModelManifest): Map<String, ResolvedRemoteFileMetadata> {
        val out = LinkedHashMap<String, ResolvedRemoteFileMetadata>()
        try {
            val apiUrl = "https://huggingface.co/api/models/${manifest.runtimeRepoId}/tree/${manifest.pinnedRevision}?recursive=true"
            val req = Request.Builder().url(apiUrl).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return out
                val body = resp.body?.string().orEmpty()
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val path = obj.optString("path")
                    val size = obj.optLong("size", 0L)
                    val lfsSha = obj.optJSONObject("lfs")?.optString("oid")?.takeIf { it.length == 64 }
                    val fileName = path.substringAfterLast('/')
                    out[fileName] = ResolvedRemoteFileMetadata(path = path, sizeBytes = size, sha256 = lfsSha)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "HF metadata tree lookup skipped/failed for ${manifest.runtimeRepoId}: ${e.message}")
        }
        return out
    }

    private fun metadataFileFor(baseDir: File, manifest: OfflineModelManifest): File =
        File(baseDir, "metadata/${manifest.modelId}_$METADATA_FILENAME")

    private fun saveResolvedMetadata(
        baseDir: File,
        manifest: OfflineModelManifest,
        meta: Map<String, ResolvedRemoteFileMetadata>
    ) {
        if (meta.isEmpty()) return
        try {
            val file = metadataFileFor(baseDir, manifest)
            file.parentFile?.mkdirs()
            val root = JSONObject()
            meta.forEach { (name, entry) ->
                root.put(name, JSONObject().apply {
                    put("path", entry.path)
                    put("size", entry.sizeBytes)
                    put("sha256", entry.sha256 ?: "")
                })
            }
            file.writeText(root.toString())
        } catch (_: Exception) {}
    }

    private fun loadResolvedMetadata(
        baseDir: File,
        manifest: OfflineModelManifest
    ): Map<String, ResolvedRemoteFileMetadata> {
        val file = metadataFileFor(baseDir, manifest)
        if (!file.isFile) return emptyMap()
        return try {
            val obj = JSONObject(file.readText())
            val out = LinkedHashMap<String, ResolvedRemoteFileMetadata>()
            obj.keys().forEach { key ->
                val item = obj.optJSONObject(key) ?: return@forEach
                out[key] = ResolvedRemoteFileMetadata(
                    path = item.optString("path"),
                    sizeBytes = item.optLong("size", 0L),
                    sha256 = item.optString("sha256").takeIf { it.length == 64 }
                )
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun isNetworkConnected(): Boolean {
        return try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Throwable) {
            true
        }
    }

    private fun registerConnectivityAutoResume() {
        if (networkCallbackRegistered) return
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val toResume = _states.value.values
                        .filter { it.state == ModelLifecycleState.PAUSED && it.autoResumePending }
                        .map { it.modelId }
                    if (toResume.isNotEmpty()) {
                        startOrResumeBatch(toResume)
                    }
                }
            })
            networkCallbackRegistered = true
        } catch (t: Throwable) {
            Log.w(TAG, "Connectivity callback registration unavailable: ${t.message}")
        }
    }

    private fun updateModelState(
        modelId: String,
        transform: (ModelDownloadProgressState) -> ModelDownloadProgressState
    ) {
        _states.update { current ->
            val prev = current[modelId] ?: ModelDownloadProgressState(modelId = modelId)
            current + (modelId to transform(prev))
        }
    }
}
