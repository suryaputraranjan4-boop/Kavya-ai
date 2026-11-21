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
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

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
 * Shared resumable download, backoff retry, connectivity auto-resume, dynamic HF tree URL &
 * checksum resolution, `.onnx.zip` extraction, and storage budget coordinator for Voice,
 * Lightweight Vision, and Advanced Vision models.
 */
class ModelDownloadCoordinator private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "ModelDownloadCoord"
        private const val METADATA_FILENAME = "resolved_metadata.json"
        private const val MAX_TRANSIENT_RETRIES = 3
        private const val USER_AGENT = "KavyaAI-Android/2.0 (ModelManager; OkHttp)"

        @Volatile
        private var instance: ModelDownloadCoordinator? = null

        fun getInstance(context: Context): ModelDownloadCoordinator =
            instance ?: synchronized(this) {
                instance ?: ModelDownloadCoordinator(context.applicationContext).also { instance = it }
            }

        /**
         * Resolves the best remote Hugging Face file path for a requested artifact spec from
         * the repository's live tree listing. Handles:
         * 1. Exact filename match anywhere in repo tree (root or subfolder like `float/...`)
         * 2. Case-insensitive filename match
         * 3. `.onnx.zip` archive match (Qualcomm AI Hub repos)
         * 4. Stem-matched `.onnx` / `.onnx.zip` fallback when repo renamed a file
         */
        internal fun resolveRemoteCandidateForSpec(
            spec: OfflineArtifactSpec,
            treeEntries: Collection<ResolvedRemoteFileMetadata>
        ): ResolvedRemoteFileMetadata? {
            if (treeEntries.isEmpty()) return null
            val targetName = spec.fileName
            val targetLower = targetName.lowercase()
            val targetStem = targetLower.removeSuffix(".onnx").removeSuffix(".zip")
                .replace("-", "").replace("_", "")

            // 1. Exact path suffix or filename match
            treeEntries.firstOrNull { it.path.substringAfterLast('/') == targetName }?.let { return it }

            // 2. Case-insensitive exact filename match
            treeEntries.firstOrNull { it.path.substringAfterLast('/').equals(targetName, ignoreCase = true) }?.let { return it }

            // 3. Exact `.zip` archive variant (e.g. `MobileNet-v3-Small.onnx.zip`)
            if (spec.format == GemmaModelFormat.ONNX) {
                treeEntries.firstOrNull {
                    it.path.substringAfterLast('/').equals("$targetName.zip", ignoreCase = true)
                }?.let { return it }

                // 4. Normalized stem match for `.onnx` or `.onnx.zip` (prefers direct `.onnx`, then float `.onnx.zip`)
                val onnxMatches = treeEntries.filter { entry ->
                    val name = entry.path.substringAfterLast('/').lowercase()
                    val isOnnx = name.endsWith(".onnx") || name.endsWith(".onnx.zip")
                    if (!isOnnx) return@filter false
                    val stem = name.removeSuffix(".zip").removeSuffix(".onnx")
                        .replace("-", "").replace("_", "")
                    stem == targetStem || stem.contains(targetStem) || targetStem.contains(stem)
                }
                onnxMatches.firstOrNull { it.path.endsWith(".onnx", ignoreCase = true) }?.let { return it }
                onnxMatches.firstOrNull {
                    it.path.endsWith(".onnx.zip", ignoreCase = true) && !it.path.contains("w8a8", ignoreCase = true)
                }?.let { return it }
                onnxMatches.firstOrNull()?.let { return it }

                // 5. Last resort for single-model repos: pick the primary `.onnx` or `.onnx.zip` file
                val anyOnnx = treeEntries.filter {
                    val p = it.path.lowercase()
                    (p.endsWith(".onnx") || p.endsWith(".onnx.zip")) && !p.contains("test", ignoreCase = true)
                }
                if (anyOnnx.size == 1) return anyOnnx.first()
                anyOnnx.firstOrNull { it.path.lowercase().contains("float") }?.let { return it }
            }
            return null
        }

        internal fun buildHuggingFaceResolveUrl(repoId: String, revision: String, remotePath: String): String {
            val encodedPath = remotePath.split("/").joinToString("/") { seg ->
                URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
            }
            return "https://huggingface.co/$repoId/resolve/$revision/$encodedPath?download=true"
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = com.example.ai.OfflinePrivacyGuard.registerCloudClient(
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(com.example.ai.StrictOfflineInterceptor(appContext, operationTag = "ModelDownloadCoordinator"))
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    )

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
            val isStructurallyValid = isModelInstalledAndValid(baseDir, desc.manifest, verifySha256 = false)
            val hasPinnedChecksums = desc.manifest.artifacts.any { it.expectedSha256.isNotBlank() }
            val hasPartFiles = desc.manifest.artifacts.any {
                File(baseDir, "${it.relativePath}.part").exists() ||
                    File(baseDir, "${it.relativePath}.zip.part").exists()
            }

            updated[id] = when {
                isStructurallyValid -> ModelDownloadProgressState(
                    modelId = id,
                    state = existing?.state?.takeIf { it == ModelLifecycleState.READY } ?: ModelLifecycleState.READY,
                    bytesDownloaded = installedBytes,
                    totalBytes = installedBytes.coerceAtLeast(desc.estimatedSizeBytes),
                    progress = 1f,
                    statusMessage = if (hasPinnedChecksums) {
                        "Installed & structurally valid (SHA-256 pinned, ${installedBytes / (1024 * 1024)} MB)"
                    } else {
                        "Installed & structurally valid (${installedBytes / (1024 * 1024)} MB)"
                    }
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
        val existingBytes = if (baseDir.exists()) {
            baseDir.walkTopDown()
                .filter { it.isFile && !it.name.endsWith(".part") }
                .sumOf { it.length() }
        } else 0L

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
            if (!isModelInstalledAndValid(baseDir, desc.manifest, verifySha256 = false)) {
                pendingDownloadBytes += desc.estimatedSizeBytes
            }
        }

        val projected = existingBytes + pendingDownloadBytes
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
        val offlineCheck = com.example.ai.OfflinePrivacyGuard.checkModelDownloadAllowed(
            context = appContext,
            modelId = modelIds.joinToString(",")
        )
        if (offlineCheck.isFailure) {
            val msg = offlineCheck.exceptionOrNull()?.message
                ?: "Strict Offline Mode is active; disable Offline Mode to download models."
            modelIds.forEach { id ->
                updateModelState(id) {
                    it.copy(
                        state = ModelLifecycleState.ERROR,
                        errorMessage = msg,
                        statusMessage = msg
                    )
                }
            }
            return
        }
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
                File(baseDir, "${spec.relativePath}.zip.part").delete()
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
            File(baseDir, "${spec.relativePath}.zip.part").delete()
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

    fun verifyAllInstalledModels() {
        ModelCatalog.allDescriptors().forEach { desc ->
            val st = getState(desc.manifest.modelId)
            if (st.state == ModelLifecycleState.READY || st.state == ModelLifecycleState.ERROR) {
                verifyModelAsync(desc.manifest.modelId)
            }
        }
    }

    private fun pumpQueue() {
        synchronized(downloadQueue) {
            if (activeJobs.isNotEmpty()) return
            val nextId = downloadQueue.poll() ?: return
            if (activeJobs.containsKey(nextId)) return
            val desc = ModelCatalog.findById(nextId) ?: run {
                pumpQueue()
                return
            }
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
    }

    private fun buildAuthorizedRequest(url: String, existingOffset: Long = 0L): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
        if (existingOffset > 0L) {
            builder.header("Range", "bytes=$existingOffset-")
        }
        // Attach optional Hugging Face token ONLY for huggingface.co host (never leaked to S3 presigned redirects)
        if (url.startsWith("https://huggingface.co/", ignoreCase = true)) {
            val hfToken = runCatching {
                com.example.utils.AppPreferences.getHuggingFaceApiKey(appContext)
            }.getOrNull()?.trim().orEmpty()
            if (hfToken.isNotBlank()) {
                builder.header("Authorization", "Bearer $hfToken")
            }
        }
        return builder.build()
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
            File(baseDir, "${it.relativePath}.part").exists() ||
                File(baseDir, "${it.relativePath}.zip.part").exists()
        }
        updateModelState(modelId) {
            it.copy(
                state = if (hadPartial) ModelLifecycleState.RESUMING else ModelLifecycleState.DOWNLOADING,
                statusMessage = if (hadPartial) "Resuming ${desc.displayName}..." else "Resolving ${desc.displayName} on Hugging Face...",
                errorMessage = null,
                autoResumePending = false
            )
        }

        val remoteTreeEntries = resolveRemoteTreeEntries(desc.manifest)
        val resolvedMeta = loadResolvedMetadata(baseDir, desc.manifest).toMutableMap()
        val effectiveArtifactSpecs = desc.manifest.artifacts.map { spec ->
            val matched = resolveRemoteCandidateForSpec(spec, remoteTreeEntries)
            if (matched != null) {
                val resolvedUrl = buildHuggingFaceResolveUrl(
                    repoId = desc.manifest.runtimeRepoId,
                    revision = desc.manifest.pinnedRevision,
                    remotePath = matched.path
                )
                val isZipArchive = matched.path.endsWith(".zip", ignoreCase = true) &&
                    !spec.fileName.endsWith(".zip", ignoreCase = true)
                // On moving branch `main`, live HF LFS tree size & OID are authoritative over older catalog snapshots.
                val liveSize = if (matched.sizeBytes > 0L) matched.sizeBytes else spec.expectedSizeBytes
                val liveSha = when {
                    !matched.sha256.isNullOrBlank() -> matched.sha256
                    isZipArchive -> "" // Hardcoded hash was for raw .onnx, not .onnx.zip archive
                    else -> spec.expectedSha256
                }
                resolvedMeta[spec.fileName] = matched
                spec.copy(
                    downloadUrl = resolvedUrl,
                    expectedSizeBytes = liveSize,
                    expectedSha256 = liveSha
                ) to isZipArchive
            } else {
                val fallbackUrl = if (spec.downloadUrl.contains("huggingface.co") && !spec.downloadUrl.contains("download=true")) {
                    "${spec.downloadUrl}?download=true"
                } else {
                    spec.downloadUrl
                }
                spec.copy(downloadUrl = fallbackUrl) to false
            }
        }
        saveResolvedMetadata(baseDir, desc.manifest, resolvedMeta)

        val totalExpected = effectiveArtifactSpecs.sumOf { (spec, _) ->
            if (spec.expectedSizeBytes > 0L) spec.expectedSizeBytes else 0L
        }.coerceAtLeast(desc.estimatedSizeBytes)

        var priorCompleted = 0L
        for ((effectiveSpec, isZipArchive) in effectiveArtifactSpecs) {
            val res = downloadSingleArtifactWithBackoff(
                modelId = modelId,
                baseDir = baseDir,
                spec = effectiveSpec,
                isZipArchive = isZipArchive,
                totalBundleBytes = totalExpected,
                priorBytes = priorCompleted,
                discoveredMetadata = resolvedMeta
            )

            if (pausedByUser[modelId] == true) return
            if (res.isFailure) {
                val err = res.exceptionOrNull()?.message ?: "Download failed"
                val transientNetwork = !isNetworkConnected() ||
                    err.contains("timeout", true) ||
                    err.contains("reset", true) ||
                    err.contains("failed to connect", true) ||
                    err.contains("unable to resolve host", true)
                val actionableError = when {
                    err.contains("HTTP 401") || err.contains("HTTP 403") ->
                        "Access restricted ($err). Add a Hugging Face token in API Hub if gated, or retry."
                    err.contains("HTTP 404") ->
                        "Artifact not found on Hugging Face ($err). Repository layout may have changed."
                    err.contains("HTTP 429") ->
                        "Hugging Face rate limit (HTTP 429). Wait a moment or configure an HF token in API Hub."
                    else -> err
                }
                updateModelState(modelId) {
                    it.copy(
                        state = if (transientNetwork) ModelLifecycleState.PAUSED else ModelLifecycleState.ERROR,
                        autoResumePending = transientNetwork,
                        errorMessage = actionableError,
                        statusMessage = if (transientNetwork) "Paused — waiting for network" else actionableError
                    )
                }
                return
            }
            priorCompleted += res.getOrThrow().length()
        }

        saveResolvedMetadata(baseDir, desc.manifest, resolvedMeta)

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
                    errorMessage = "MODEL_CORRUPT: Final integrity verification failed after download.",
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
                statusMessage = "Ready (${(finalBytes / (1024f * 1024f)).let { String.format(java.util.Locale.US, "%.1f", it) }} MB)"
            )
        }
    }

    private suspend fun downloadSingleArtifactWithBackoff(
        modelId: String,
        baseDir: File,
        spec: OfflineArtifactSpec,
        isZipArchive: Boolean,
        totalBundleBytes: Long,
        priorBytes: Long,
        discoveredMetadata: MutableMap<String, ResolvedRemoteFileMetadata>
    ): Result<File> {
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt <= MAX_TRANSIENT_RETRIES) {
            if (pausedByUser[modelId] == true) {
                return Result.failure(CancellationException("Paused"))
            }
            if (attempt > 0) {
                val backoffMs = (1200L shl (attempt - 1)).coerceAtMost(10000L)
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
                isZipArchive = isZipArchive,
                totalBundleBytes = totalBundleBytes,
                priorBytes = priorBytes,
                discoveredMetadata = discoveredMetadata
            )
            if (result.isSuccess) return result
            val ex = result.exceptionOrNull()
            if (ex is CancellationException || pausedByUser[modelId] == true) {
                throw ex ?: CancellationException("Paused")
            }
            lastError = ex
            val msg = ex?.message.orEmpty()
            // Do not blindly retry permanent 401/403/404 or confirmed cryptographic mismatch
            if (msg.contains("MODEL_CORRUPT") || msg.contains("HTTP 404") || msg.contains("HTTP 401") || msg.contains("HTTP 403")) {
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
        isZipArchive: Boolean,
        totalBundleBytes: Long,
        priorBytes: Long,
        discoveredMetadata: MutableMap<String, ResolvedRemoteFileMetadata>
    ): Result<File> = withContext(Dispatchers.IO) {
        val finalFile = File(baseDir, spec.relativePath)
        finalFile.parentFile?.mkdirs()

        if (finalFile.exists() && !isZipArchive) {
            val (valid, _) = verifyArtifactWithDynamicSpec(finalFile, spec, deleteCorruptFile = true)
            if (valid) return@withContext Result.success(finalFile)
        }

        val partFile = File(finalFile.parentFile, if (isZipArchive) "${finalFile.name}.zip.part" else "${finalFile.name}.part")
        if (spec.expectedSizeBytes > 0L && partFile.exists() && partFile.length() > spec.expectedSizeBytes) {
            partFile.delete()
        }
        val existingOffset = if (partFile.exists()) partFile.length() else 0L

        val request = buildAuthorizedRequest(spec.downloadUrl, existingOffset)

        val response = try {
            httpClient.newCall(request).execute()
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
                val contentType = resp.header("Content-Type").orEmpty().lowercase()
                if (contentType.contains("text/html") || contentType.contains("application/xhtml")) {
                    return@withContext Result.failure(
                        IllegalStateException("MODEL_CORRUPT: Server returned HTML ($contentType) instead of ${spec.fileName}")
                    )
                }

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
                        partFile.delete()
                        return@withContext Result.failure(
                            IllegalStateException("Invalid Content-Range '${contentRange.orEmpty()}'; restarted cleanly")
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
                            if (now - lastUiUpdateMs >= 180L) {
                                val elapsedSec = ((now - speedWindowStartMs).coerceAtLeast(1L)) / 1000.0
                                val speed = (speedWindowBytes / elapsedSec).toLong()
                                val totalForProgress = totalBundleBytes.coerceAtLeast(priorBytes + resolvedTotalLen)
                                val cumulative = priorBytes + currentBytesWritten
                                val frac = if (totalForProgress > 0L) {
                                    (cumulative.toFloat() / totalForProgress.toFloat()).coerceIn(0f, 0.96f)
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

        // Handle `.onnx.zip` archives (Qualcomm AI Hub repos) cleanly: verify archive then extract `.onnx` (+ optional `.data`)
        val fileIsZip = isZipArchive || isZipFileMagic(partFile)
        if (fileIsZip && spec.format == GemmaModelFormat.ONNX) {
            if (spec.expectedSizeBytes > 0L && partFile.length() != spec.expectedSizeBytes) {
                partFile.delete()
                return@withContext Result.failure(
                    IllegalStateException("MODEL_CORRUPT: Archive size mismatch for ${spec.fileName}")
                )
            }
            val expectedArchiveSha = spec.expectedSha256.ifBlank { resolvedHeaderSha.orEmpty() }
            if (expectedArchiveSha.isNotBlank()) {
                val actualArchiveSha = GemmaModelManager.computeSha256(partFile)
                if (!actualArchiveSha.equals(expectedArchiveSha, ignoreCase = true)) {
                    partFile.delete()
                    return@withContext Result.failure(
                        IllegalStateException("MODEL_CORRUPT: Archive SHA-256 mismatch for ${spec.fileName}")
                    )
                }
            }

            updateModelState(modelId) {
                it.copy(
                    state = ModelLifecycleState.VERIFYING,
                    progress = 0.97f,
                    statusMessage = "Extracting ${spec.fileName} from archive..."
                )
            }
            val extractResult = extractOnnxFromZipArchive(partFile, finalFile)
            partFile.delete()
            if (extractResult.isFailure) {
                return@withContext extractResult
            }
            val extractedFile = extractResult.getOrThrow()
            val (fmtOk, fmtReason) = verifyFormatAndNotHtmlErrorPage(extractedFile, spec)
            if (!fmtOk) {
                extractedFile.delete()
                return@withContext Result.failure(IllegalStateException(fmtReason))
            }
            val extractedSha = GemmaModelManager.computeSha256(extractedFile)
            discoveredMetadata[spec.fileName] = ResolvedRemoteFileMetadata(
                path = spec.relativePath,
                sizeBytes = extractedFile.length(),
                sha256 = extractedSha
            )
            return@withContext Result.success(extractedFile)
        }

        val finalSpec = spec.copy(
            expectedSizeBytes = if (spec.expectedSizeBytes > 0L) spec.expectedSizeBytes else resolvedTotalLen.coerceAtLeast(0L),
            expectedSha256 = resolvedHeaderSha?.takeIf { it.length == 64 } ?: spec.expectedSha256
        )

        val (valid, reason) = verifyArtifactWithDynamicSpec(partFile, finalSpec, deleteCorruptFile = true)
        if (!valid) {
            return@withContext Result.failure(IllegalStateException(reason))
        }
        if (finalFile.exists()) finalFile.delete()
        if (!partFile.renameTo(finalFile)) {
            return@withContext Result.failure(IllegalStateException("Atomic rename failed for ${spec.fileName}"))
        }
        val persistedSize = finalFile.length()
        val persistedSha = finalSpec.expectedSha256.takeIf { it.length == 64 } ?: GemmaModelManager.computeSha256(finalFile)
        val prev = discoveredMetadata[spec.fileName]
        discoveredMetadata[spec.fileName] = ResolvedRemoteFileMetadata(
            path = prev?.path?.ifBlank { spec.relativePath } ?: spec.relativePath,
            sizeBytes = persistedSize,
            sha256 = persistedSha
        )
        Result.success(finalFile)
    }

    internal fun isZipFileMagic(file: File): Boolean {
        if (!file.isFile || file.length() < 4L) return false
        return try {
            val magic = ByteArray(4)
            FileInputStream(file).use { it.read(magic) }
            magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
                (magic[2] == 0x03.toByte() || magic[2] == 0x05.toByte())
        } catch (_: Exception) {
            false
        }
    }

    internal fun extractOnnxFromZipArchive(zipFile: File, targetOnnxFile: File): Result<File> {
        val parentDir = targetOnnxFile.parentFile ?: return Result.failure(IllegalStateException("Missing parent directory"))
        parentDir.mkdirs()
        val canonicalParent = parentDir.canonicalPath
        var extractedOnnx: File? = null
        return try {
            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                val buffer = ByteArray(64 * 1024)
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val rawName = entry.name.substringAfterLast('/')
                        val lower = rawName.lowercase()
                        val destFile = when {
                            lower.endsWith(".onnx") -> targetOnnxFile
                            lower.endsWith(".data") || lower.endsWith(".onnx_data") || lower.endsWith(".txt") || lower.endsWith(".json") ->
                                File(parentDir, rawName)
                            else -> null
                        }
                        if (destFile != null) {
                            val canonicalDest = destFile.canonicalPath
                            if (!canonicalDest.startsWith(canonicalParent + File.separator) && canonicalDest != canonicalParent) {
                                return Result.failure(SecurityException("Blocked zip-slip entry: ${entry.name}"))
                            }
                            if (destFile.exists()) destFile.delete()
                            FileOutputStream(destFile).use { fos ->
                                while (true) {
                                    val count = zis.read(buffer)
                                    if (count <= 0) break
                                    fos.write(buffer, 0, count)
                                }
                                fos.fd.sync()
                            }
                            if (destFile == targetOnnxFile) {
                                extractedOnnx = destFile
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            val out = extractedOnnx
            if (out != null && out.isFile && out.length() >= 64L) {
                Result.success(out)
            } else {
                Result.failure(IllegalStateException("MODEL_CORRUPT: Archive did not contain a valid .onnx model file"))
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Failed to extract ONNX archive: ${e.message}", e))
        }
    }

    internal fun verifyArtifactWithDynamicSpec(
        file: File,
        spec: OfflineArtifactSpec
    ): Result<Unit> {
        val (ok, reason) = verifyArtifactWithDynamicSpec(file, spec, deleteCorruptFile = false)
        return if (ok) Result.success(Unit) else Result.failure(IllegalStateException(reason))
    }

    internal fun verifyArtifactWithDynamicSpec(
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
            return Pair(false, "MODEL_CORRUPT: Size mismatch for ${file.name} (got ${file.length()}, expected ${spec.expectedSizeBytes})")
        }
        val (formatOk, formatReason) = verifyFormatAndNotHtmlErrorPage(file, spec)
        if (!formatOk) {
            if (deleteCorruptFile) file.delete()
            return Pair(false, formatReason)
        }
        if (spec.expectedSha256.isNotBlank()) {
            val actualSha = GemmaModelManager.computeSha256(file)
            if (!actualSha.equals(spec.expectedSha256, ignoreCase = true)) {
                if (deleteCorruptFile) file.delete()
                return Pair(false, "MODEL_CORRUPT: SHA-256 mismatch for ${file.name}")
            }
        }
        return Pair(true, "READY")
    }

    private fun verifyFormatAndNotHtmlErrorPage(
        file: File,
        spec: OfflineArtifactSpec
    ): Pair<Boolean, String> {
        val headLen = minOf(file.length(), 160L).toInt()
        if (headLen <= 0) return Pair(false, "MODEL_CORRUPT: Empty file ${file.name}")
        val head = ByteArray(headLen)
        FileInputStream(file).use { it.read(head) }
        val textHead = String(head, Charsets.UTF_8).trimStart().lowercase()
        if (textHead.startsWith("version https://git-lfs.github.com/spec/v1")) {
            return Pair(false, "MODEL_CORRUPT: Git LFS pointer file downloaded instead of ${file.name}")
        }
        if (textHead.startsWith("<!doctype") ||
            textHead.startsWith("<html") ||
            textHead.startsWith("<?xml") ||
            textHead.startsWith("<error") ||
            textHead.contains("404 not found") ||
            textHead.contains("entry not found")
        ) {
            return Pair(false, "MODEL_CORRUPT: HTML/XML error page downloaded instead of ${file.name}")
        }
        when (spec.format) {
            GemmaModelFormat.GGUF -> {
                if (headLen < 4 || head[0] != 'G'.code.toByte() || head[1] != 'G'.code.toByte() ||
                    head[2] != 'U'.code.toByte() || head[3] != 'F'.code.toByte()
                ) {
                    return Pair(false, "MODEL_CORRUPT: Invalid GGUF magic header in ${file.name}")
                }
            }
            GemmaModelFormat.ONNX -> {
                if (file.length() < 64L || textHead.startsWith("{") || textHead.startsWith("[")) {
                    return Pair(false, "MODEL_CORRUPT: Invalid or truncated ONNX binary ${file.name}")
                }
                if (isZipFileMagic(file)) {
                    return Pair(false, "MODEL_CORRUPT: Unextracted ZIP archive present where .onnx binary expected (${file.name})")
                }
            }
            GemmaModelFormat.UNKNOWN -> {
                if (file.name.endsWith(".json", ignoreCase = true) && !textHead.startsWith("{") && !textHead.startsWith("[")) {
                    return Pair(false, "MODEL_CORRUPT: Invalid JSON companion file ${file.name}")
                }
            }
            else -> {}
        }
        return Pair(true, "OK")
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
            val savedEntry = savedMeta[spec.fileName]
            // Prefer verified saved metadata (actual extracted or live-branch artifact) over static snapshot
            val expectedLen = when {
                (savedEntry?.sizeBytes ?: 0L) > 0L -> savedEntry!!.sizeBytes
                spec.expectedSizeBytes > 0L -> spec.expectedSizeBytes
                else -> 0L
            }
            if (expectedLen > 0L && file.length() != expectedLen) return@all false
            if (!verifyFormatAndNotHtmlErrorPage(file, spec).first) return@all false
            if (verifySha256) {
                val expectedSha = savedEntry?.sha256?.takeIf { it.length == 64 }
                    ?: spec.expectedSha256
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
            val zipPartFile = File(baseDir, "${spec.relativePath}.zip.part")
            when {
                finalFile.isFile -> total += finalFile.length()
                partFile.isFile -> total += partFile.length()
                zipPartFile.isFile -> total += zipPartFile.length()
            }
        }
        return total
    }

    private fun resolveRemoteTreeEntries(manifest: OfflineModelManifest): List<ResolvedRemoteFileMetadata> {
        val out = mutableListOf<ResolvedRemoteFileMetadata>()
        try {
            val apiUrl = "https://huggingface.co/api/models/${manifest.runtimeRepoId}/tree/${manifest.pinnedRevision}?recursive=true"
            val req = buildAuthorizedRequest(apiUrl)
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return out
                val body = resp.body?.string().orEmpty()
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    if (obj.optString("type") == "directory") continue
                    val path = obj.optString("path")
                    if (path.isBlank()) continue
                    val lfsObj = obj.optJSONObject("lfs")
                    val size = lfsObj?.optLong("size", 0L)?.takeIf { it > 0L } ?: obj.optLong("size", 0L)
                    val lfsSha = lfsObj?.optString("oid")?.takeIf { it.length == 64 }
                    out.add(ResolvedRemoteFileMetadata(path = path, sizeBytes = size, sha256 = lfsSha))
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
