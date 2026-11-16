package com.example.vision

import android.content.Context
import android.util.Log
import com.example.ai.RetrofitClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Isolated Python worker clients for heavy server-side ML/rendering backends:
 * - TripoSR (https://github.com/VAST-AI-Research/TripoSR): single image -> GLB mesh reconstruction
 * - Manim (https://github.com/ManimCommunity/manim): Python Scene script -> MP4 video rendering
 *
 * Strictly zero fake responses or simulated outputs. All network calls fail cleanly with
 * actionable setup instructions if the user has not deployed/configured a worker URL.
 */
object TripoSrAndManimWorkers {

    private const val TAG = "VisionPythonWorkers"

    data class ManimValidationResult(
        val isValid: Boolean,
        val sceneClassName: String?,
        val issues: List<String>
    )

    data class WorkerExecutionResult(
        val success: Boolean,
        val outputFile: File? = null,
        val detailMessage: String,
        val serverLogs: String = ""
    )

    fun validateManimScript(script: String): ManimValidationResult {
        val trimmed = script.trim()
        val issues = mutableListOf<String>()
        if (trimmed.isEmpty()) {
            issues.add("Manim Python script is empty.")
        }
        if (!trimmed.contains("from manim import") && !trimmed.contains("import manim")) {
            issues.add("Script should import manim (e.g., `from manim import *`).")
        }
        val sceneMatch = Regex("""class\s+([A-Za-z_][A-Za-z0-9_]*)\s*\([^)]*Scene[^)]*\)\s*:""")
            .find(trimmed)
        if (sceneMatch == null) {
            issues.add("Script must define a Scene subclass (e.g., `class MyScene(Scene):`).")
        }
        if (!trimmed.contains("def construct(self)")) {
            issues.add("Scene subclass must implement `def construct(self):`.")
        }
        val dangerousTokens = listOf(
            "os.system(", "subprocess.", "shutil.rmtree(", "__import__(", "eval(", "exec("
        )
        dangerousTokens.forEach { tok ->
            if (trimmed.contains(tok)) {
                issues.add("Blocked potentially unsafe call '$tok' in Manim sandbox validation.")
            }
        }
        return ManimValidationResult(
            isValid = issues.isEmpty(),
            sceneClassName = sceneMatch?.groupValues?.getOrNull(1),
            issues = issues
        )
    }

    suspend fun testWorkerHealth(
        context: Context,
        techId: VisionTechId
    ): WorkerEndpointStatus = withContext(Dispatchers.IO) {
        val baseUrl = when (techId) {
            VisionTechId.TRIPO_SR -> VisionPackageManager.getTripoSrEndpoint(context)
            VisionTechId.MANIM -> VisionPackageManager.getManimEndpoint(context)
            else -> ""
        }.trimEnd('/')

        val token = when (techId) {
            VisionTechId.TRIPO_SR -> VisionPackageManager.getTripoSrToken(context)
            VisionTechId.MANIM -> VisionPackageManager.getManimToken(context)
            else -> ""
        }

        if (baseUrl.isBlank()) {
            VisionPackageManager.markWorkerReachability(context, techId, false)
            return@withContext WorkerEndpointStatus(
                serviceName = techId.name,
                endpointUrl = "",
                hasAuthToken = token.isNotBlank(),
                isReachable = false,
                lastCheckedMs = System.currentTimeMillis(),
                detailMessage = "No worker URL configured."
            )
        }

        val candidates = listOf("$baseUrl/health", baseUrl)
        var lastError = "Unreachable"
        for (probeUrl in candidates) {
            try {
                val reqBuilder = Request.Builder().url(probeUrl).get()
                if (token.isNotBlank()) reqBuilder.addHeader("Authorization", "Bearer $token")
                RetrofitClient.okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        VisionPackageManager.markWorkerReachability(context, techId, true)
                        return@withContext WorkerEndpointStatus(
                            serviceName = techId.name,
                            endpointUrl = baseUrl,
                            hasAuthToken = token.isNotBlank(),
                            isReachable = true,
                            lastCheckedMs = System.currentTimeMillis(),
                            detailMessage = "HTTP ${resp.code} OK from $probeUrl"
                        )
                    } else {
                        lastError = "HTTP ${resp.code} at $probeUrl"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.localizedMessage ?: "connection error"}"
            }
        }

        VisionPackageManager.markWorkerReachability(context, techId, false)
        WorkerEndpointStatus(
            serviceName = techId.name,
            endpointUrl = baseUrl,
            hasAuthToken = token.isNotBlank(),
            isReachable = false,
            lastCheckedMs = System.currentTimeMillis(),
            detailMessage = lastError
        )
    }

    /**
     * Submits a verified image file to a configured TripoSR FastAPI worker (`POST /reconstruct`)
     * supporting either synchronous GLB binary response or async JSON `{ job_id, status_url, glb_url }`.
     */
    suspend fun runTripoSrReconstruction(
        context: Context,
        imageFile: File,
        outputGlbFile: File,
        mcResolution: Int = 256,
        removeBackground: Boolean = true,
        onProgress: (Float, String) -> Unit
    ): WorkerExecutionResult = withContext(Dispatchers.IO) {
        val baseUrl = VisionPackageManager.getTripoSrEndpoint(context).trimEnd('/')
        if (baseUrl.isBlank()) {
            return@withContext WorkerExecutionResult(
                success = false,
                detailMessage = "TripoSR worker URL is not configured. Open Tech Info -> Worker Setup to connect your Python TripoSR FastAPI server."
            )
        }
        if (!imageFile.isFile || imageFile.length() == 0L) {
            return@withContext WorkerExecutionResult(
                success = false,
                detailMessage = "Input image file is missing or empty."
            )
        }

        val token = VisionPackageManager.getTripoSrToken(context)
        val mime = if (imageFile.name.endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg"
        onProgress(0.15f, "Uploading image (${imageFile.length() / 1024} KB) to TripoSR worker...")

        try {
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", imageFile.name, imageFile.asRequestBody(mime.toMediaType()))
                .addFormDataPart("mc_resolution", mcResolution.coerceIn(64, 512).toString())
                .addFormDataPart("remove_background", removeBackground.toString())
                .addFormDataPart("output_format", "glb")
                .build()

            val postUrl = if (baseUrl.endsWith("/reconstruct") || baseUrl.endsWith("/predict")) baseUrl else "$baseUrl/reconstruct"
            val req = Request.Builder()
                .url(postUrl)
                .apply { if (token.isNotBlank()) addHeader("Authorization", "Bearer $token") }
                .post(multipart)
                .build()

            RetrofitClient.okHttpClient.newCall(req).execute().use { resp ->
                currentCoroutineContext().ensureActive()
                val contentType = resp.header("Content-Type").orEmpty().lowercase()
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string().orEmpty().take(320)
                    return@withContext WorkerExecutionResult(
                        success = false,
                        detailMessage = "TripoSR worker rejected request (HTTP ${resp.code}): $errBody"
                    )
                }

                if (contentType.contains("gltf-binary") || contentType.contains("octet-stream")) {
                    onProgress(0.85f, "Downloading reconstructed GLB mesh...")
                    outputGlbFile.parentFile?.mkdirs()
                    resp.body?.byteStream()?.use { input ->
                        FileOutputStream(outputGlbFile).use { out -> input.copyTo(out) }
                    }
                    if (outputGlbFile.isFile && outputGlbFile.length() > 12L) {
                        VisionPackageManager.markWorkerReachability(context, VisionTechId.TRIPO_SR, true)
                        return@withContext WorkerExecutionResult(
                            success = true,
                            outputFile = outputGlbFile,
                            detailMessage = "Reconstructed GLB (${outputGlbFile.length() / 1024} KB) ready for 3D preview."
                        )
                    }
                    return@withContext WorkerExecutionResult(false, detailMessage = "Worker returned an empty GLB response.")
                }

                val jsonStr = resp.body?.string().orEmpty()
                val obj = JSONObject(jsonStr)
                val directDownloadUrl = obj.optString("glb_url").ifBlank { obj.optString("result_url") }
                val jobId = obj.optString("job_id")
                val resolvedUrl = when {
                    directDownloadUrl.isNotBlank() -> normalizeWorkerUrl(baseUrl, directDownloadUrl)
                    jobId.isNotBlank() -> pollAsyncWorkerArtifact(
                        baseUrl = baseUrl,
                        statusPath = obj.optString("status_url", "/jobs/$jobId"),
                        token = token,
                        artifactKey = "glb_url",
                        onProgress = onProgress
                    )
                    else -> ""
                }

                if (resolvedUrl.isBlank()) {
                    return@withContext WorkerExecutionResult(
                        success = false,
                        detailMessage = "TripoSR worker did not return a GLB URL.",
                        serverLogs = jsonStr.take(400)
                    )
                }

                downloadWorkerFile(resolvedUrl, token, outputGlbFile)
                VisionPackageManager.markWorkerReachability(context, VisionTechId.TRIPO_SR, true)
                WorkerExecutionResult(
                    success = true,
                    outputFile = outputGlbFile,
                    detailMessage = "Reconstructed GLB (${outputGlbFile.length() / 1024} KB) downloaded."
                )
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w(TAG, "TripoSR worker error: ${e.message}")
            WorkerExecutionResult(
                success = false,
                detailMessage = "TripoSR connection failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
        }
    }

    /**
     * Submits a validated Python Manim script to a configured Manim Community render worker
     * (`POST /render`), supporting immediate MP4 streams or async polled MP4 job downloads.
     */
    suspend fun runManimRenderJob(
        context: Context,
        script: String,
        sceneClass: String,
        qualityFlag: String = "m",
        outputMp4File: File,
        onProgress: (Float, String) -> Unit
    ): WorkerExecutionResult = withContext(Dispatchers.IO) {
        val validation = validateManimScript(script)
        if (!validation.isValid) {
            return@withContext WorkerExecutionResult(
                success = false,
                detailMessage = "Manim script validation failed: ${validation.issues.joinToString("; ")}"
            )
        }

        val baseUrl = VisionPackageManager.getManimEndpoint(context).trimEnd('/')
        if (baseUrl.isBlank()) {
            return@withContext WorkerExecutionResult(
                success = false,
                detailMessage = "Manim Python worker URL is not configured. Open Tech Info -> Worker Setup to connect your Manim server."
            )
        }

        val token = VisionPackageManager.getManimToken(context)
        val resolvedScene = sceneClass.ifBlank { validation.sceneClassName ?: "MainScene" }
        onProgress(0.15f, "Submitting '$resolvedScene' to Manim render worker...")

        try {
            val payload = JSONObject().apply {
                put("script", script)
                put("scene_class", resolvedScene)
                put("quality", qualityFlag.ifBlank { "m" })
                put("format", "mp4")
            }
            val postUrl = if (baseUrl.endsWith("/render")) baseUrl else "$baseUrl/render"
            val req = Request.Builder()
                .url(postUrl)
                .apply { if (token.isNotBlank()) addHeader("Authorization", "Bearer $token") }
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            RetrofitClient.okHttpClient.newCall(req).execute().use { resp ->
                currentCoroutineContext().ensureActive()
                val ct = resp.header("Content-Type").orEmpty().lowercase()
                if (!resp.isSuccessful) {
                    val err = resp.body?.string().orEmpty().take(500)
                    return@withContext WorkerExecutionResult(
                        success = false,
                        detailMessage = "Manim worker render failed (HTTP ${resp.code}).",
                        serverLogs = err
                    )
                }

                if (ct.contains("video/mp4") || ct.contains("video/webm") || ct.contains("octet-stream")) {
                    onProgress(0.85f, "Downloading rendered MP4 video...")
                    outputMp4File.parentFile?.mkdirs()
                    resp.body?.byteStream()?.use { input ->
                        FileOutputStream(outputMp4File).use { out -> input.copyTo(out) }
                    }
                    if (outputMp4File.isFile && outputMp4File.length() > 64L) {
                        VisionPackageManager.markWorkerReachability(context, VisionTechId.MANIM, true)
                        return@withContext WorkerExecutionResult(
                            success = true,
                            outputFile = outputMp4File,
                            detailMessage = "Manim MP4 video ready (${outputMp4File.length() / 1024} KB)."
                        )
                    }
                    return@withContext WorkerExecutionResult(false, detailMessage = "Manim worker returned an empty video stream.")
                }

                val jsonStr = resp.body?.string().orEmpty()
                val obj = JSONObject(jsonStr)
                val videoUrl = obj.optString("video_url").ifBlank { obj.optString("mp4_url") }
                val jobId = obj.optString("job_id")
                val resolvedUrl = when {
                    videoUrl.isNotBlank() -> normalizeWorkerUrl(baseUrl, videoUrl)
                    jobId.isNotBlank() -> pollAsyncWorkerArtifact(
                        baseUrl = baseUrl,
                        statusPath = obj.optString("status_url", "/jobs/$jobId"),
                        token = token,
                        artifactKey = "video_url",
                        onProgress = onProgress
                    )
                    else -> ""
                }

                if (resolvedUrl.isBlank()) {
                    return@withContext WorkerExecutionResult(
                        success = false,
                        detailMessage = obj.optString("error").ifBlank { "Manim worker returned no video URL." },
                        serverLogs = obj.optString("stderr").ifBlank { jsonStr.take(500) }
                    )
                }

                downloadWorkerFile(resolvedUrl, token, outputMp4File)
                VisionPackageManager.markWorkerReachability(context, VisionTechId.MANIM, true)
                WorkerExecutionResult(
                    success = true,
                    outputFile = outputMp4File,
                    detailMessage = "Manim MP4 video (${outputMp4File.length() / 1024} KB) ready for playback.",
                    serverLogs = obj.optString("logs")
                )
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w(TAG, "Manim worker error: ${e.message}")
            WorkerExecutionResult(
                success = false,
                detailMessage = "Manim connection failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
        }
    }

    private suspend fun pollAsyncWorkerArtifact(
        baseUrl: String,
        statusPath: String,
        token: String,
        artifactKey: String,
        onProgress: (Float, String) -> Unit
    ): String {
        val statusUrl = normalizeWorkerUrl(baseUrl, statusPath)
        repeat(45) { step ->
            currentCoroutineContext().ensureActive()
            delay(1500L)
            val req = Request.Builder()
                .url(statusUrl)
                .apply { if (token.isNotBlank()) addHeader("Authorization", "Bearer $token") }
                .get()
                .build()
            RetrofitClient.okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ""
                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)
                val state = json.optString("status").lowercase()
                val prog = json.optDouble("progress", (0.25 + step * 0.015).coerceAtMost(0.9)).toFloat()
                onProgress(prog, json.optString("message").ifBlank { "Worker status: $state" })
                if (state in listOf("completed", "done", "succeeded", "ready")) {
                    val rel = json.optString(artifactKey).ifBlank { json.optString("result_url") }
                    return if (rel.isNotBlank()) normalizeWorkerUrl(baseUrl, rel) else ""
                }
                if (state in listOf("failed", "error", "cancelled")) {
                    throw IllegalStateException(json.optString("error").ifBlank { "Worker reported $state" })
                }
            }
        }
        return ""
    }

    private fun downloadWorkerFile(url: String, token: String, dst: File) {
        val req = Request.Builder()
            .url(url)
            .apply { if (token.isNotBlank()) addHeader("Authorization", "Bearer $token") }
            .get()
            .build()
        RetrofitClient.okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} downloading artifact")
            dst.parentFile?.mkdirs()
            resp.body?.byteStream()?.use { input ->
                FileOutputStream(dst).use { out -> input.copyTo(out) }
            }
        }
    }

    private fun normalizeWorkerUrl(baseUrl: String, candidate: String): String {
        val c = candidate.trim()
        if (c.startsWith("http://") || c.startsWith("https://")) return c
        return "${baseUrl.trimEnd('/')}/${c.trimStart('/')}"
    }
}
