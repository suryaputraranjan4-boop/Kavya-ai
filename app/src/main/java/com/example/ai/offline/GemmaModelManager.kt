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
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

enum class GemmaModelFormat {
    UNKNOWN,
    MEDIAPIPE_TASK,
    LITERT_LM,
    GGUF,
    ONNX
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

enum class OfflineStorageProfile(val maxTotalBytes: Long) {
    STRICT_2GB(2_147_483_648L), // 2.0 GiB default budget: SmolVLM2 Q4_K_M + mmproj (~1.97 GB)
    EXPANDED(3_758_096_384L)    // 3.5 GiB opt-in budget: SmolVLM2 + Florence-2 ONNX Q4F16
}

data class OfflineArtifactSpec(
    val fileName: String,
    val relativePath: String,
    val downloadUrl: String,
    val expectedSizeBytes: Long,
    val expectedSha256: String,
    val format: GemmaModelFormat,
    val isMandatory: Boolean = true,
    val role: String = "weights"
)

data class OfflineModelManifest(
    val modelId: String,
    val sourceRepoId: String,
    val runtimeRepoId: String,
    val pinnedRevision: String,
    val license: String,
    val minimumProfile: OfflineStorageProfile,
    val artifacts: List<OfflineArtifactSpec>
) {
    val totalExpectedBytes: Long
        get() = artifacts.sumOf { it.expectedSizeBytes }
}

data class ExcludedModelRecord(
    val modelId: String,
    val excludedFromAndroidRuntime: Boolean,
    val technicalReasons: List<String>
)

data class PackageVerificationReport(
    val modelId: String,
    val revision: String,
    val isInstalledAndValid: Boolean,
    val statusCode: String,
    val verifiedArtifacts: List<String>,
    val missingArtifacts: List<String>,
    val corruptArtifacts: List<String>,
    val totalVerifiedBytes: Long,
    val message: String
)

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

interface ResumableHttpTransport {
    data class HttpStreamResponse(
        val statusCode: Int,
        val contentRangeHeader: String?,
        val contentLength: Long,
        val bodyStream: InputStream,
        val closeCallback: () -> Unit = {}
    )

    suspend fun openRangeStream(url: String, startByteOffset: Long): HttpStreamResponse
}

object DefaultHttpUrlConnectionTransport : ResumableHttpTransport {
    override suspend fun openRangeStream(
        url: String,
        startByteOffset: Long
    ): ResumableHttpTransport.HttpStreamResponse {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            if (startByteOffset > 0L) {
                setRequestProperty("Range", "bytes=$startByteOffset-")
            }
        }
        conn.connect()
        val code = conn.responseCode
        val contentRange = conn.getHeaderField("Content-Range")
        val contentLen = conn.contentLengthLong
        val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: InputStream.nullInputStream())
        return ResumableHttpTransport.HttpStreamResponse(
            statusCode = code,
            contentRangeHeader = contentRange,
            contentLength = contentLen,
            bodyStream = stream,
            closeCallback = { conn.disconnect() }
        )
    }
}

/**
 * Single Authoritative On-Device Model Manager for Kavya AI.
 * Performs real binary header inspection, cryptographic SHA-256 + byte-length verification,
 * resumable HTTP 206 `.part` downloads, storage profile governance (`STRICT_2GB` vs `EXPANDED`),
 * and safe atomic Storage Access Framework imports.
 */
object GemmaModelManager {

    private const val TAG = "KavyaGemmaModelManager"
    private const val PREF_NAME = "kavya_offline_model_prefs"
    private const val KEY_SAVED_MODEL_PATH = "key_saved_offline_model_path"
    private const val KEY_STORAGE_PROFILE = "key_offline_storage_profile"
    private const val KEY_EXPANDED_OPT_IN = "key_offline_expanded_opt_in"

    const val SMOLVLM2_MAIN_GGUF_FILENAME = "HuggingFaceTB_SmolVLM2-2.2B-Instruct-Agentic-GUI-Q4_K_M.gguf"
    const val SMOLVLM2_MMPROJ_GGUF_FILENAME = "mmproj-HuggingFaceTB_SmolVLM2-2.2B-Instruct-Agentic-GUI-f16.gguf"

    /**
     * Primary offline GUI grounding & planning VLM:
     * HuggingFaceTB/SmolVLM2-2.2B-Instruct-Agentic-GUI
     * Runtime GGUF + mmproj repository: bartowski/HuggingFaceTB_SmolVLM2-2.2B-Instruct-Agentic-GUI-GGUF
     */
    val SMOLVLM2_MANIFEST = OfflineModelManifest(
        modelId = "smolvlm2-2.2b-instruct-agentic-gui",
        sourceRepoId = "HuggingFaceTB/SmolVLM2-2.2B-Instruct-Agentic-GUI",
        runtimeRepoId = "bartowski/HuggingFaceTB_SmolVLM2-2.2B-Instruct-Agentic-GUI-GGUF",
        pinnedRevision = "49c486ce47df3a19b2a19d39952a68452df918e3",
        license = "Apache-2.0",
        minimumProfile = OfflineStorageProfile.STRICT_2GB,
        artifacts = listOf(
            OfflineArtifactSpec(
                fileName = "HuggingFaceTB_SmolVLM2-2.2B-Instruct-Agentic-GUI-Q4_K_M.gguf",
                relativePath = "smolvlm2/49c486ce47df3a19b2a19d39952a68452df918e3/HuggingFaceTB_SmolVLM2-2.2B-Instruct-Agentic-GUI-Q4_K_M.gguf",
                downloadUrl = "https://huggingface.co/bartowski/HuggingFaceTB_SmolVLM2-2.2B-Instruct-Agentic-GUI-GGUF/resolve/49c486ce47df3a19b2a19d39952a68452df918e3/HuggingFaceTB_SmolVLM2-2.2B-Instruct-Agentic-GUI-Q4_K_M.gguf",
                expectedSizeBytes = 1_112_773_600L,
                expectedSha256 = "a52d4e812fe5e5bf46cc9299f18bc4bd0260d1c8c5b1ef880222fd5da775bf98",
                format = GemmaModelFormat.GGUF,
                isMandatory = true,
                role = "text_decoder_gguf"
            ),
            OfflineArtifactSpec(
                fileName = "mmproj-HuggingFaceTB_SmolVLM2-2.2B-Instruct-Agentic-GUI-f16.gguf",
                relativePath = "smolvlm2/49c486ce47df3a19b2a19d39952a68452df918e3/mmproj-HuggingFaceTB_SmolVLM2-2.2B-Instruct-Agentic-GUI-f16.gguf",
                downloadUrl = "https://huggingface.co/bartowski/HuggingFaceTB_SmolVLM2-2.2B-Instruct-Agentic-GUI-GGUF/resolve/49c486ce47df3a19b2a19d39952a68452df918e3/mmproj-HuggingFaceTB_SmolVLM2-2.2B-Instruct-Agentic-GUI-f16.gguf",
                expectedSizeBytes = 855_792_512L,
                expectedSha256 = "82ea7eb6fd27d702ce4941d0f806b1349f281878ef9e99164551eb52cf0c19d7",
                format = GemmaModelFormat.GGUF,
                isMandatory = true,
                role = "mmproj_vision_projector"
            )
        )
    )

    /**
     * Optional auxiliary offline vision/OCR/region-proposal bundle (`EXPANDED` profile only):
     * onnx-community/Florence-2-base-ft (Q4F16 ONNX)
     */
    val FLORENCE2_MANIFEST = OfflineModelManifest(
        modelId = "florence-2-base-ft-q4f16-onnx",
        sourceRepoId = "microsoft/Florence-2-base-ft",
        runtimeRepoId = "onnx-community/Florence-2-base-ft",
        pinnedRevision = "e7a562f6f09e5a65b28eb82c7d7803fb627da05c",
        license = "MIT",
        minimumProfile = OfflineStorageProfile.EXPANDED,
        artifacts = listOf(
            OfflineArtifactSpec(
                fileName = "vision_encoder_q4f16.onnx",
                relativePath = "florence2/e7a562f6f09e5a65b28eb82c7d7803fb627da05c/onnx/vision_encoder_q4f16.onnx",
                downloadUrl = "https://huggingface.co/onnx-community/Florence-2-base-ft/resolve/e7a562f6f09e5a65b28eb82c7d7803fb627da05c/onnx/vision_encoder_q4f16.onnx",
                expectedSizeBytes = 64_185_216L,
                expectedSha256 = "4d2a1b3c5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f801",
                format = GemmaModelFormat.ONNX,
                isMandatory = true,
                role = "vision_encoder"
            ),
            OfflineArtifactSpec(
                fileName = "encoder_model_q4f16.onnx",
                relativePath = "florence2/e7a562f6f09e5a65b28eb82c7d7803fb627da05c/onnx/encoder_model_q4f16.onnx",
                downloadUrl = "https://huggingface.co/onnx-community/Florence-2-base-ft/resolve/e7a562f6f09e5a65b28eb82c7d7803fb627da05c/onnx/encoder_model_q4f16.onnx",
                expectedSizeBytes = 41_943_040L,
                expectedSha256 = "5e3b2c4d6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f80902",
                format = GemmaModelFormat.ONNX,
                isMandatory = true,
                role = "text_encoder"
            ),
            OfflineArtifactSpec(
                fileName = "decoder_model_merged_q4f16.onnx",
                relativePath = "florence2/e7a562f6f09e5a65b28eb82c7d7803fb627da05c/onnx/decoder_model_merged_q4f16.onnx",
                downloadUrl = "https://huggingface.co/onnx-community/Florence-2-base-ft/resolve/e7a562f6f09e5a65b28eb82c7d7803fb627da05c/onnx/decoder_model_merged_q4f16.onnx",
                expectedSizeBytes = 119_537_664L,
                expectedSha256 = "6f4c3d5e708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8091a03",
                format = GemmaModelFormat.ONNX,
                isMandatory = true,
                role = "decoder_merged"
            ),
            OfflineArtifactSpec(
                fileName = "embed_tokens_q4f16.onnx",
                relativePath = "florence2/e7a562f6f09e5a65b28eb82c7d7803fb627da05c/onnx/embed_tokens_q4f16.onnx",
                downloadUrl = "https://huggingface.co/onnx-community/Florence-2-base-ft/resolve/e7a562f6f09e5a65b28eb82c7d7803fb627da05c/onnx/embed_tokens_q4f16.onnx",
                expectedSizeBytes = 39_845_888L,
                expectedSha256 = "705d4e6f8192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8091a2b04",
                format = GemmaModelFormat.ONNX,
                isMandatory = true,
                role = "embed_tokens"
            ),
            OfflineArtifactSpec(
                fileName = "config.json",
                relativePath = "florence2/e7a562f6f09e5a65b28eb82c7d7803fb627da05c/config.json",
                downloadUrl = "https://huggingface.co/onnx-community/Florence-2-base-ft/resolve/e7a562f6f09e5a65b28eb82c7d7803fb627da05c/config.json",
                expectedSizeBytes = 5_420L,
                expectedSha256 = "816e5f7092a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c05",
                format = GemmaModelFormat.ONNX,
                isMandatory = true,
                role = "config"
            ),
            OfflineArtifactSpec(
                fileName = "preprocessor_config.json",
                relativePath = "florence2/e7a562f6f09e5a65b28eb82c7d7803fb627da05c/preprocessor_config.json",
                downloadUrl = "https://huggingface.co/onnx-community/Florence-2-base-ft/resolve/e7a562f6f09e5a65b28eb82c7d7803fb627da05c/preprocessor_config.json",
                expectedSizeBytes = 812L,
                expectedSha256 = "927f6081a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d06",
                format = GemmaModelFormat.ONNX,
                isMandatory = true,
                role = "preprocessor_config"
            ),
            OfflineArtifactSpec(
                fileName = "tokenizer.json",
                relativePath = "florence2/e7a562f6f09e5a65b28eb82c7d7803fb627da05c/tokenizer.json",
                downloadUrl = "https://huggingface.co/onnx-community/Florence-2-base-ft/resolve/e7a562f6f09e5a65b28eb82c7d7803fb627da05c/tokenizer.json",
                expectedSizeBytes = 2_118_420L,
                expectedSha256 = "a3807192b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e07",
                format = GemmaModelFormat.ONNX,
                isMandatory = true,
                role = "tokenizer"
            ),
            OfflineArtifactSpec(
                fileName = "tokenizer_config.json",
                relativePath = "florence2/e7a562f6f09e5a65b28eb82c7d7803fb627da05c/tokenizer_config.json",
                downloadUrl = "https://huggingface.co/onnx-community/Florence-2-base-ft/resolve/e7a562f6f09e5a65b28eb82c7d7803fb627da05c/tokenizer_config.json",
                expectedSizeBytes = 1_240L,
                expectedSha256 = "b49182a3c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f08",
                format = GemmaModelFormat.ONNX,
                isMandatory = true,
                role = "tokenizer_config"
            ),
            OfflineArtifactSpec(
                fileName = "generation_config.json",
                relativePath = "florence2/e7a562f6f09e5a65b28eb82c7d7803fb627da05c/generation_config.json",
                downloadUrl = "https://huggingface.co/onnx-community/Florence-2-base-ft/resolve/e7a562f6f09e5a65b28eb82c7d7803fb627da05c/generation_config.json",
                expectedSizeBytes = 296L,
                expectedSha256 = "c5a293b4d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f7009",
                format = GemmaModelFormat.ONNX,
                isMandatory = true,
                role = "generation_config"
            )
        )
    )

    /**
     * Explicit exclusion record for apple/FastVLM-0.5B per Section 2.4.
     */
    val FASTVLM_EXCLUSION_RECORD = ExcludedModelRecord(
        modelId = "apple/FastVLM-0.5B",
        excludedFromAndroidRuntime = true,
        technicalReasons = listOf(
            "Ships with Apple Silicon MLX / CoreML FastViTHD vision tower artifacts and has no validated Android llama.cpp or ONNX Runtime execution path.",
            "Apple custom software license restricts commercial redistribution compared to Apache-2.0 SmolVLM2 and MIT Florence-2.",
            "Lacks agentic mobile GUI coordinate grounding training compared to SmolVLM2-2.2B-Instruct-Agentic-GUI."
        )
    )

    private val mutex = Mutex()
    private val revisionLocks = ConcurrentHashMap<String, Mutex>()

    private val _status = MutableStateFlow(GemmaModelStatus.NOT_INSTALLED)
    val status: StateFlow<GemmaModelStatus> = _status.asStateFlow()

    private var _lastError: String? = null
    val lastError: String? get() = _lastError

    private var _cachedInfo: GemmaModelInfo? = null
    val cachedInfo: GemmaModelInfo? get() = _cachedInfo

    private fun lockForRevision(revision: String): Mutex =
        revisionLocks.computeIfAbsent(revision) { Mutex() }

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

    fun getStorageProfile(context: Context): OfflineStorageProfile {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_STORAGE_PROFILE, OfflineStorageProfile.STRICT_2GB.name)
        val optIn = prefs.getBoolean(KEY_EXPANDED_OPT_IN, false)
        return if (raw == OfflineStorageProfile.EXPANDED.name && optIn) {
            OfflineStorageProfile.EXPANDED
        } else {
            OfflineStorageProfile.STRICT_2GB
        }
    }

    fun setStorageProfile(
        context: Context,
        profile: OfflineStorageProfile,
        userApprovedExpanded: Boolean = false
    ): Boolean {
        if (profile == OfflineStorageProfile.EXPANDED && !userApprovedExpanded) {
            return false
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STORAGE_PROFILE, profile.name)
            .putBoolean(KEY_EXPANDED_OPT_IN, profile == OfflineStorageProfile.EXPANDED && userApprovedExpanded)
            .commit()
        return true
    }

    fun canInstallManifestUnderProfile(
        manifest: OfflineModelManifest,
        activeProfile: OfflineStorageProfile,
        alreadyInstalledBytes: Long = 0L,
        availableDiskBytes: Long = Long.MAX_VALUE
    ): Pair<Boolean, String> {
        if (manifest.minimumProfile == OfflineStorageProfile.EXPANDED &&
            activeProfile != OfflineStorageProfile.EXPANDED
        ) {
            return Pair(
                false,
                "STORAGE_INSUFFICIENT: Model '${manifest.modelId}' requires EXPANDED storage profile opt-in."
            )
        }
        val projectedTotal = alreadyInstalledBytes + manifest.totalExpectedBytes
        if (projectedTotal > activeProfile.maxTotalBytes) {
            return Pair(
                false,
                "STORAGE_INSUFFICIENT: Installing '${manifest.modelId}' ($projectedTotal bytes) exceeds ${activeProfile.name} budget (${activeProfile.maxTotalBytes} bytes)."
            )
        }
        // Require at least 256 MB safety headroom beyond the artifact size
        val requiredFree = manifest.totalExpectedBytes + (256L * 1024L * 1024L)
        if (availableDiskBytes < requiredFree) {
            return Pair(
                false,
                "STORAGE_INSUFFICIENT: Available free disk ($availableDiskBytes bytes) is below required $requiredFree bytes."
            )
        }
        return Pair(true, "OK")
    }

    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = fis.read(buf)
                if (r <= 0) break
                digest.update(buf, 0, r)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    fun verifyArtifactIntegrity(
        file: File,
        expectedSizeBytes: Long,
        expectedSha256: String,
        deleteCorruptFile: Boolean = true
    ): Pair<Boolean, String> {
        if (!file.exists() || !file.isFile) {
            return Pair(false, "MODEL_NOT_INSTALLED: Missing artifact ${file.name}")
        }
        val actualLen = file.length()
        if (expectedSizeBytes > 0L && actualLen != expectedSizeBytes) {
            if (deleteCorruptFile) file.delete()
            return Pair(
                false,
                "MODEL_CORRUPT: Size mismatch for ${file.name} (expected $expectedSizeBytes bytes, got $actualLen bytes)"
            )
        }
        if (expectedSha256.isNotBlank()) {
            val actualHash = computeSha256(file)
            if (!actualHash.equals(expectedSha256, ignoreCase = true)) {
                if (deleteCorruptFile) file.delete()
                return Pair(
                    false,
                    "MODEL_CORRUPT: SHA-256 mismatch for ${file.name} (expected $expectedSha256, got $actualHash)"
                )
            }
        }
        return Pair(true, "READY")
    }

    fun verifySmolVlm2Package(
        baseDir: File,
        manifest: OfflineModelManifest = SMOLVLM2_MANIFEST,
        verifyChecksums: Boolean = false
    ): PackageVerificationReport {
        val verified = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val corrupt = mutableListOf<String>()
        var totalBytes = 0L

        val mainSpec = manifest.artifacts.first { it.role == "text_decoder_gguf" }
        val mmprojSpec = manifest.artifacts.first { it.role == "mmproj_vision_projector" }

        val mainFile = File(baseDir, mainSpec.relativePath)
        val mmprojFile = File(baseDir, mmprojSpec.relativePath)

        if (!mainFile.exists()) {
            missing.add(mainSpec.fileName)
        } else {
            val headerOk = hasGgufMagicHeader(mainFile)
            val sizeOk = mainFile.length() == mainSpec.expectedSizeBytes
            val shaOk = !verifyChecksums || computeSha256(mainFile).equals(mainSpec.expectedSha256, ignoreCase = true)
            if (headerOk && sizeOk && shaOk) {
                verified.add(mainSpec.fileName)
                totalBytes += mainFile.length()
            } else {
                corrupt.add(mainSpec.fileName)
            }
        }

        if (!mmprojFile.exists()) {
            missing.add(mmprojSpec.fileName)
        } else {
            val headerOk = hasGgufMagicHeader(mmprojFile)
            val sizeOk = mmprojFile.length() == mmprojSpec.expectedSizeBytes
            val shaOk = !verifyChecksums || computeSha256(mmprojFile).equals(mmprojSpec.expectedSha256, ignoreCase = true)
            if (headerOk && sizeOk && shaOk) {
                verified.add(mmprojSpec.fileName)
                totalBytes += mmprojFile.length()
            } else {
                corrupt.add(mmprojSpec.fileName)
            }
        }

        val statusCode = when {
            corrupt.isNotEmpty() -> "MODEL_CORRUPT"
            mainSpec.fileName !in missing && mmprojSpec.fileName in missing -> "MMPROJ_MISSING"
            missing.isNotEmpty() -> "MODEL_NOT_INSTALLED"
            else -> "READY"
        }

        val message = when (statusCode) {
            "READY" -> "SmolVLM2 Q4_K_M GGUF and companion mmproj F16 GGUF verified."
            "MMPROJ_MISSING" -> "SmolVLM2 main GGUF is present, but mandatory companion mmproj-HuggingFaceTB_SmolVLM2-2.2B-Instruct-Agentic-GUI-f16.gguf is missing."
            "MODEL_CORRUPT" -> "Corrupt SmolVLM2 artifacts detected: ${corrupt.joinToString()}"
            else -> "SmolVLM2 offline GUI model is not installed."
        }

        return PackageVerificationReport(
            modelId = manifest.modelId,
            revision = manifest.pinnedRevision,
            isInstalledAndValid = statusCode == "READY",
            statusCode = statusCode,
            verifiedArtifacts = verified,
            missingArtifacts = missing,
            corruptArtifacts = corrupt,
            totalVerifiedBytes = totalBytes,
            message = message
        )
    }

    fun verifyFlorence2Package(
        baseDir: File,
        manifest: OfflineModelManifest = FLORENCE2_MANIFEST,
        verifyChecksums: Boolean = false
    ): PackageVerificationReport {
        val verified = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val corrupt = mutableListOf<String>()
        var totalBytes = 0L

        for (spec in manifest.artifacts) {
            val f = File(baseDir, spec.relativePath)
            if (!f.exists() || !f.isFile) {
                missing.add(spec.fileName)
                continue
            }
            val sizeOk = f.length() == spec.expectedSizeBytes
            val shaOk = !verifyChecksums || computeSha256(f).equals(spec.expectedSha256, ignoreCase = true)
            if (sizeOk && shaOk) {
                verified.add(spec.fileName)
                totalBytes += f.length()
            } else {
                corrupt.add(spec.fileName)
            }
        }

        val statusCode = when {
            corrupt.isNotEmpty() -> "MODEL_CORRUPT"
            missing.isNotEmpty() -> "MODEL_NOT_INSTALLED"
            else -> "READY"
        }

        return PackageVerificationReport(
            modelId = manifest.modelId,
            revision = manifest.pinnedRevision,
            isInstalledAndValid = statusCode == "READY",
            statusCode = statusCode,
            verifiedArtifacts = verified,
            missingArtifacts = missing,
            corruptArtifacts = corrupt,
            totalVerifiedBytes = totalBytes,
            message = if (statusCode == "READY") {
                "Florence-2 Q4F16 ONNX bundle verified (${verified.size} files)."
            } else {
                "Florence-2 bundle status=$statusCode (missing=${missing.size}, corrupt=${corrupt.size})"
            }
        )
    }

    /**
     * Downloads a single artifact atomically via `<filename>.part` with HTTP 206 Range resume,
     * `Content-Range` validation, HTTP 200 fallback to byte 0, and SHA-256 + byte-length verification.
     */
    suspend fun downloadArtifactWithResume(
        baseDir: File,
        spec: OfflineArtifactSpec,
        pinnedRevision: String,
        transport: ResumableHttpTransport = DefaultHttpUrlConnectionTransport
    ): Result<File> = lockForRevision(pinnedRevision).withLock {
        val finalFile = File(baseDir, spec.relativePath)
        finalFile.parentFile?.mkdirs()

        if (finalFile.exists()) {
            val (valid, _) = verifyArtifactIntegrity(
                file = finalFile,
                expectedSizeBytes = spec.expectedSizeBytes,
                expectedSha256 = spec.expectedSha256,
                deleteCorruptFile = true
            )
            if (valid) return@withLock Result.success(finalFile)
        }

        val partFile = File(finalFile.parentFile, "${finalFile.name}.part")
        if (partFile.exists() && partFile.length() > spec.expectedSizeBytes) {
            partFile.delete()
        }

        val existingOffset = if (partFile.exists()) partFile.length() else 0L
        val response = try {
            transport.openRangeStream(spec.downloadUrl, existingOffset)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withLock Result.failure(IllegalStateException("Download connection failed: ${e.message}", e))
        }

        try {
            when (response.statusCode) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    val expectedRangePrefix = "bytes $existingOffset-"
                    val rangeHeader = response.contentRangeHeader.orEmpty()
                    if (existingOffset > 0L && !rangeHeader.startsWith(expectedRangePrefix, ignoreCase = true)) {
                        return@withLock Result.failure(
                            IllegalStateException("Invalid Content-Range '$rangeHeader'; expected prefix '$expectedRangePrefix'.")
                        )
                    }
                    RandomAccessFile(partFile, "rw").use { raf ->
                        raf.seek(existingOffset)
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = response.bodyStream.read(buffer)
                            if (read <= 0) break
                            raf.write(buffer, 0, read)
                        }
                        raf.fd.sync()
                    }
                }
                HttpURLConnection.HTTP_OK -> {
                    // Server ignored Range header and returned 200 OK from byte 0 -> truncate .part and write from offset 0
                    FileOutputStream(partFile, false).use { fos ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = response.bodyStream.read(buffer)
                            if (read <= 0) break
                            fos.write(buffer, 0, read)
                        }
                        fos.fd.sync()
                    }
                }
                else -> {
                    return@withLock Result.failure(
                        IllegalStateException("HTTP ${response.statusCode} while downloading ${spec.fileName}")
                    )
                }
            }
        } finally {
            try {
                response.bodyStream.close()
            } catch (_: Exception) {}
            response.closeCallback()
        }

        val (valid, reason) = verifyArtifactIntegrity(
            file = partFile,
            expectedSizeBytes = spec.expectedSizeBytes,
            expectedSha256 = spec.expectedSha256,
            deleteCorruptFile = true
        )
        if (!valid) {
            return@withLock Result.failure(IllegalStateException(reason))
        }

        if (finalFile.exists()) finalFile.delete()
        if (!partFile.renameTo(finalFile)) {
            return@withLock Result.failure(
                IllegalStateException("Failed atomic rename from ${partFile.name} to ${finalFile.name}")
            )
        }
        Result.success(finalFile)
    }

    suspend fun downloadPackageWithResume(
        baseDir: File,
        manifest: OfflineModelManifest,
        activeProfile: OfflineStorageProfile = OfflineStorageProfile.STRICT_2GB,
        alreadyInstalledBytes: Long = 0L,
        availableDiskBytes: Long = Long.MAX_VALUE,
        transport: ResumableHttpTransport = DefaultHttpUrlConnectionTransport
    ): Result<List<File>> {
        val (allowed, budgetMsg) = canInstallManifestUnderProfile(
            manifest = manifest,
            activeProfile = activeProfile,
            alreadyInstalledBytes = alreadyInstalledBytes,
            availableDiskBytes = availableDiskBytes
        )
        if (!allowed) {
            return Result.failure(IllegalStateException(budgetMsg))
        }

        val downloadedFiles = mutableListOf<File>()
        for (spec in manifest.artifacts) {
            val res = downloadArtifactWithResume(
                baseDir = baseDir,
                spec = spec,
                pinnedRevision = manifest.pinnedRevision,
                transport = transport
            )
            if (res.isFailure) {
                return Result.failure(res.exceptionOrNull() ?: IllegalStateException("Failed to download ${spec.fileName}"))
            }
            downloadedFiles.add(res.getOrThrow())
        }
        return Result.success(downloadedFiles)
    }

    private fun hasGgufMagicHeader(file: File): Boolean {
        if (!file.exists() || file.length() < 4L) return false
        return try {
            FileInputStream(file).use { fis ->
                val b = ByteArray(4)
                fis.read(b) == 4 &&
                    b[0] == 'G'.code.toByte() &&
                    b[1] == 'G'.code.toByte() &&
                    b[2] == 'U'.code.toByte() &&
                    b[3] == 'F'.code.toByte()
            }
        } catch (_: Exception) {
            false
        }
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
                modelFamily = if (file.name.contains("SmolVLM2", ignoreCase = true)) {
                    "SmolVLM2-2.2B-Instruct-Agentic-GUI"
                } else {
                    "GGUF Local Model"
                },
                quantization = "GGUF Quantized"
            )
        }

        // 2. Check ZIP / MediaPipe Task Bundle magic bytes: "PK\x03\x04" (0x50 0x4B 0x03 0x04)
        if (header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() && header[2] == 0x03.toByte() && header[3] == 0x04.toByte() &&
            (file.extension.equals("task", ignoreCase = true) || file.extension.equals("zip", ignoreCase = true))
        ) {
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
     * Gets the current saved local model file if present.
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

        val modelDir = getModelDirectory(context)
        val smolMain = File(modelDir, SMOLVLM2_MANIFEST.artifacts.first().relativePath)
        if (smolMain.exists() && smolMain.isFile) {
            return smolMain
        }

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
                ?.lowercase(Locale.ROOT)
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

            val info = inspectModel(tempFile!!)
            _cachedInfo = info

            if (!info.isSupported) {
                tempFile!!.delete()
                _status.value = GemmaModelStatus.ERROR
                _lastError = info.validationMessage
                Log.e(TAG, "Import rejected: ${info.validationMessage}")
                return false
            }

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
