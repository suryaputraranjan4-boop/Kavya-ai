package com.example.ai.offline

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.visual.PrecisionCoordinateMapper
import com.example.visual.SignalSource
import com.example.visual.VisionElementSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

enum class VisionModelDiagnosticStage {
    CONFIGURED,
    DOWNLOADED,
    VERIFIED,
    LOADED,
    EXECUTED
}

data class VisionModelDiagnosticReport(
    val modelId: String,
    val displayName: String,
    val stage: VisionModelDiagnosticStage,
    val isConfigured: Boolean,
    val isDownloaded: Boolean,
    val isVerified: Boolean,
    val isLoaded: Boolean,
    val isExecuted: Boolean,
    val lastExecutedAtMs: Long = 0L,
    val detail: String = ""
)

data class LocalVisionExecutionSummary(
    val visionSignals: List<VisionElementSignal>,
    val sceneTags: List<String>,
    val executedModelIds: List<String>,
    val latencyMs: Long,
    val statusNote: String
)

/**
 * Orchestrates installed lightweight ONNX vision models via ONNX Runtime (`OrtSession`) with:
 * - LRU caching of at most 2 lightweight sessions (to stay well within 8 GB RAM budgets)
 * - Automatic yield/release before heavy VLM loads or under `onTrimMemory` pressure
 * - Dynamic tensor input shape inspection & ImageNet / RGB normalization
 * - Real NMS bounding-box decoding & `[0..1]` -> device pixel mapping via `PrecisionCoordinateMapper`
 * - Explicit per-model lifecycle diagnostics (`CONFIGURED`, `DOWNLOADED`, `VERIFIED`, `LOADED`, `EXECUTED`)
 */
class OnDeviceVisionOrchestrator private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "OnDeviceVisionOrch"
        private const val MAX_CACHED_LIGHTWEIGHT_SESSIONS = 2
        private const val MIN_RAM_MB_LIGHTWEIGHT_VISION = 480L

        private val COCO_80_LABELS = arrayOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light",
            "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow",
            "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
            "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard",
            "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
            "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone",
            "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors", "teddy bear",
            "hair drier", "toothbrush"
        )

        private fun cocoLabel(classIdx: Int): String =
            if (classIdx in COCO_80_LABELS.indices) COCO_80_LABELS[classIdx] else "Object #$classIdx"

        @Volatile
        private var instance: OnDeviceVisionOrchestrator? = null

        fun getInstance(context: Context): OnDeviceVisionOrchestrator =
            instance ?: synchronized(this) {
                instance ?: OnDeviceVisionOrchestrator(context.applicationContext).also { instance = it }
            }

        /**
         * Applies greedy Non-Maximum Suppression on candidate `[Rect, Float, String]` detections.
         */
        internal fun nonMaxSuppression(
            candidates: List<Triple<Rect, Float, String>>,
            iouThreshold: Float = 0.45f,
            maxResults: Int = 15
        ): List<Triple<Rect, Float, String>> {
            val sorted = candidates.sortedByDescending { it.second }.toMutableList()
            val kept = mutableListOf<Triple<Rect, Float, String>>()
            while (sorted.isNotEmpty() && kept.size < maxResults) {
                val top = sorted.removeAt(0)
                kept.add(top)
                val iter = sorted.iterator()
                while (iter.hasNext()) {
                    val other = iter.next()
                    if (rectIoU(top.first, other.first) > iouThreshold) {
                        iter.remove()
                    }
                }
            }
            return kept
        }

        private fun rectIoU(a: Rect, b: Rect): Float {
            val left = max(a.left, b.left)
            val top = max(a.top, b.top)
            val right = min(a.right, b.right)
            val bottom = min(a.bottom, b.bottom)
            if (right <= left || bottom <= top) return 0f
            val inter = (right - left).toFloat() * (bottom - top).toFloat()
            val union = (a.width() * a.height()).toFloat().coerceAtLeast(1f) +
                (b.width() * b.height()).toFloat().coerceAtLeast(1f) - inter
            return if (union <= 0f) 0f else inter / union
        }
    }

    private val mutex = Mutex()
    private var ortEnv: OrtEnvironment? = null
    private val lastExecutedTimestamps = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val sessionCache = object : LinkedHashMap<String, OrtSession>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OrtSession>?): Boolean {
            if (size > MAX_CACHED_LIGHTWEIGHT_SESSIONS) {
                try {
                    eldest?.value?.close()
                } catch (_: Throwable) {}
                return true
            }
            return false
        }
    }

    fun recordModelExecuted(modelId: String) {
        lastExecutedTimestamps[modelId] = System.currentTimeMillis()
    }

    fun getLoadedSessionIds(): List<String> = synchronized(sessionCache) {
        sessionCache.keys.toList()
    }

    fun inspectModelDiagnostics(
        modelId: String,
        baseDir: File = GemmaModelManager.getModelDirectory(appContext)
    ): VisionModelDiagnosticReport {
        val desc = ModelCatalog.findById(modelId)
        val displayName = desc?.displayName ?: modelId
        val configured = desc != null && desc.manifest.artifacts.isNotEmpty()
        if (!configured || desc == null) {
            return VisionModelDiagnosticReport(
                modelId = modelId,
                displayName = displayName,
                stage = VisionModelDiagnosticStage.CONFIGURED,
                isConfigured = false,
                isDownloaded = false,
                isVerified = false,
                isLoaded = false,
                isExecuted = false,
                detail = "Model not found in catalog"
            )
        }
        val downloaded = desc.manifest.artifacts.all { spec ->
            val f = File(baseDir, spec.relativePath)
            f.isFile && f.length() > 0L
        }
        val coordinator = ModelDownloadCoordinator.getInstance(appContext)
        val verified = downloaded && coordinator.isModelInstalledAndValid(baseDir, desc.manifest, verifySha256 = false)
        val engine = Gemma4E4BEngine.getInstance(appContext)
        val loaded = when (modelId) {
            GemmaModelManager.SMOLVLM2_MANIFEST.modelId -> engine.isSmolVlm2Loaded()
            GemmaModelManager.FLORENCE2_MANIFEST.modelId -> engine.isFlorence2Loaded()
            else -> sessionCache.containsKey(modelId)
        }
        val lastExec = lastExecutedTimestamps[modelId] ?: 0L
        val executed = lastExec > 0L
        val highestStage = when {
            executed -> VisionModelDiagnosticStage.EXECUTED
            loaded -> VisionModelDiagnosticStage.LOADED
            verified -> VisionModelDiagnosticStage.VERIFIED
            downloaded -> VisionModelDiagnosticStage.DOWNLOADED
            else -> VisionModelDiagnosticStage.CONFIGURED
        }
        return VisionModelDiagnosticReport(
            modelId = modelId,
            displayName = displayName,
            stage = highestStage,
            isConfigured = true,
            isDownloaded = downloaded,
            isVerified = verified,
            isLoaded = loaded,
            isExecuted = executed,
            lastExecutedAtMs = lastExec,
            detail = "Stage=${highestStage.name} (downloaded=$downloaded, verified=$verified, loaded=$loaded, executed=$executed)"
        )
    }

    fun inspectAllVisionDiagnostics(
        baseDir: File = GemmaModelManager.getModelDirectory(appContext)
    ): Map<String, VisionModelDiagnosticReport> {
        val allVision = ModelCatalog.LIGHTWEIGHT_VISION_DESCRIPTORS + ModelCatalog.getAdvancedVisionDescriptors()
        return allVision.associate { desc ->
            desc.manifest.modelId to inspectModelDiagnostics(desc.manifest.modelId, baseDir)
        }
    }

    fun getInstalledVisionModels(
        baseDir: File = GemmaModelManager.getModelDirectory(appContext)
    ): List<ManagedModelDescriptor> {
        val coordinator = ModelDownloadCoordinator.getInstance(appContext)
        return ModelCatalog.LIGHTWEIGHT_VISION_DESCRIPTORS.filter { desc ->
            coordinator.isModelInstalledAndValid(baseDir, desc.manifest, verifySha256 = false)
        }
    }

    suspend fun analyzeScreenWithInstalledModels(
        screenshot: Bitmap,
        screenWidth: Int,
        screenHeight: Int,
        includeRegionCropClassification: Boolean = false
    ): LocalVisionExecutionSummary = mutex.withLock {
        withContext(Dispatchers.Default) {
            val startMs = System.currentTimeMillis()
            val installed = getInstalledVisionModels()
            if (installed.isEmpty()) {
                return@withContext LocalVisionExecutionSummary(
                    visionSignals = emptyList(),
                    sceneTags = emptyList(),
                    executedModelIds = emptyList(),
                    latencyMs = 0L,
                    statusNote = "No lightweight ONNX vision models installed."
                )
            }

            val resources = Gemma4E4BEngine.getInstance(appContext).inspectDeviceResources(appContext)
            if (resources.isThermalThrottled || resources.availableRamMb < MIN_RAM_MB_LIGHTWEIGHT_VISION) {
                return@withContext LocalVisionExecutionSummary(
                    visionSignals = emptyList(),
                    sceneTags = emptyList(),
                    executedModelIds = emptyList(),
                    latencyMs = 0L,
                    statusNote = "Skipped local vision under thermal/RAM constraint (avail=${resources.availableRamMb}MB)."
                )
            }

            val baseDir = GemmaModelManager.getModelDirectory(appContext)
            val allSignals = mutableListOf<VisionElementSignal>()
            val sceneTags = mutableListOf<String>()
            val executedIds = mutableListOf<String>()

            // Run at most one object detector, one OCR detector + optional OCR recognizer, one classifier (+ optional segmentation)
            val ocrRecognizerDesc = installed.firstOrNull { it.capability == LightweightVisionCapability.OCR_TEXT_RECOGNITION }
            val prioritized = buildList {
                installed.firstOrNull { it.capability == LightweightVisionCapability.OBJECT_DETECTION }?.let(::add)
                installed.firstOrNull { it.capability == LightweightVisionCapability.OCR_TEXT_DETECTION }?.let(::add)
                installed.firstOrNull { it.capability == LightweightVisionCapability.UI_CLASSIFICATION }?.let(::add)
                if (includeRegionCropClassification) {
                    installed.firstOrNull { it.capability == LightweightVisionCapability.REGION_SEGMENTATION }?.let(::add)
                }
            }

            var detectedOcrRegions: List<VisionElementSignal> = emptyList()

            for (desc in prioritized) {
                try {
                    val primaryArtifact = desc.manifest.artifacts.firstOrNull { it.format == GemmaModelFormat.ONNX } ?: continue
                    val modelFile = File(baseDir, primaryArtifact.relativePath)
                    val session = getOrCreateSession(desc.manifest.modelId, modelFile, resources.recommendedThreads)
                    when (desc.capability) {
                        LightweightVisionCapability.OBJECT_DETECTION -> {
                            val dets = runObjectDetectorSession(desc, session, screenshot, screenWidth, screenHeight)
                            allSignals.addAll(dets)
                            executedIds.add(desc.manifest.modelId)
                            recordModelExecuted(desc.manifest.modelId)
                        }
                        LightweightVisionCapability.OCR_TEXT_DETECTION -> {
                            val regions = runOcrDetectorSession(desc, session, screenshot, screenWidth, screenHeight)
                            detectedOcrRegions = regions
                            if (ocrRecognizerDesc == null) {
                                allSignals.addAll(regions)
                            }
                            executedIds.add(desc.manifest.modelId)
                            recordModelExecuted(desc.manifest.modelId)
                        }
                        LightweightVisionCapability.UI_CLASSIFICATION -> {
                            val tag = runClassifierSession(desc, session, screenshot)
                            if (tag.isNotBlank()) sceneTags.add(tag)
                            executedIds.add(desc.manifest.modelId)
                            recordModelExecuted(desc.manifest.modelId)
                        }
                        LightweightVisionCapability.REGION_SEGMENTATION -> {
                            val segSignals = runSegmentationSession(desc, session, screenshot, screenWidth, screenHeight)
                            allSignals.addAll(segSignals)
                            executedIds.add(desc.manifest.modelId)
                            recordModelExecuted(desc.manifest.modelId)
                        }
                        else -> Unit
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "ONNX execution failed for ${desc.manifest.modelId}: ${t.message}")
                }
            }

            // Execute OCR text recognition (PaddleOCR v6 rec) on detected text bands (or top-screen fallback band)
            if (ocrRecognizerDesc != null) {
                try {
                    val recArtifact = ocrRecognizerDesc.manifest.artifacts.firstOrNull { it.format == GemmaModelFormat.ONNX }
                    if (recArtifact != null) {
                        val recFile = File(baseDir, recArtifact.relativePath)
                        val recSession = getOrCreateSession(ocrRecognizerDesc.manifest.modelId, recFile, resources.recommendedThreads)
                        val dictArtifact = ocrRecognizerDesc.manifest.artifacts.firstOrNull {
                            it.fileName.endsWith(".txt", ignoreCase = true)
                        }
                        val dictionary = dictArtifact?.let { loadOcrDictionary(File(baseDir, it.relativePath)) }.orEmpty()
                        val enriched = runOcrRecognizerSession(
                            desc = ocrRecognizerDesc,
                            session = recSession,
                            bitmap = screenshot,
                            detectedRegions = detectedOcrRegions,
                            screenWidth = screenWidth,
                            screenHeight = screenHeight,
                            dictionary = dictionary
                        )
                        allSignals.addAll(enriched)
                        executedIds.add(ocrRecognizerDesc.manifest.modelId)
                        recordModelExecuted(ocrRecognizerDesc.manifest.modelId)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "ONNX OCR recognition failed for ${ocrRecognizerDesc.manifest.modelId}: ${t.message}")
                    if (detectedOcrRegions.isNotEmpty()) {
                        allSignals.addAll(detectedOcrRegions)
                    }
                }
            }

            LocalVisionExecutionSummary(
                visionSignals = allSignals,
                sceneTags = sceneTags.distinct(),
                executedModelIds = executedIds,
                latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L),
                statusNote = if (executedIds.isNotEmpty()) {
                    "Executed ${executedIds.joinToString(", ")}"
                } else {
                    "No compatible ONNX vision session produced output."
                }
            )
        }
    }

    private fun getOrCreateSession(modelId: String, file: File, threads: Int): OrtSession {
        sessionCache[modelId]?.let { return it }
        val env = ortEnv ?: OrtEnvironment.getEnvironment().also { ortEnv = it }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads.coerceIn(1, 4))
        }
        val session = env.createSession(file.absolutePath, opts)
        sessionCache[modelId] = session
        return session
    }

    private data class ResolvedInputSpec(
        val name: String,
        val height: Int,
        val width: Int,
        val isNhwc: Boolean,
        val isFp16: Boolean
    )

    private fun resolveInputSpec(session: OrtSession, defaultH: Int, defaultW: Int): ResolvedInputSpec {
        val entry = session.inputInfo.entries.firstOrNull()
            ?: return ResolvedInputSpec("images", defaultH, defaultW, isNhwc = false, isFp16 = false)
        val info: NodeInfo = entry.value
        val tensorInfo = info.info as? TensorInfo
        val shape = tensorInfo?.shape
        val isNhwc = shape != null && shape.size >= 4 && shape[3] == 3L && shape[1] != 3L
        val h = if (shape != null && shape.size >= 4) {
            val rawH = if (isNhwc) shape[1] else shape[2]
            if (rawH in 16..1536) rawH.toInt() else defaultH
        } else defaultH
        val w = if (shape != null && shape.size >= 4) {
            val rawW = if (isNhwc) shape[2] else shape[3]
            if (rawW in 16..1536) rawW.toInt() else defaultW
        } else defaultW
        val isFp16 = tensorInfo?.type == OnnxJavaType.FLOAT16
        return ResolvedInputSpec(entry.key, h, w, isNhwc = isNhwc, isFp16 = isFp16)
    }

    private fun createImageTensor(
        bitmap: Bitmap,
        spec: ResolvedInputSpec,
        imageNetNormalize: Boolean,
        paddleRecNormalize: Boolean = false
    ): OnnxTensor {
        val env = ortEnv ?: OrtEnvironment.getEnvironment().also { ortEnv = it }
        val targetW = spec.width
        val targetH = spec.height
        val scaled = if (bitmap.width == targetW && bitmap.height == targetH) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        }
        val pixels = IntArray(targetW * targetH)
        scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        if (scaled !== bitmap) scaled.recycle()

        val planeSize = targetW * targetH
        val meanR = when {
            paddleRecNormalize -> 0.5f
            imageNetNormalize -> 0.485f
            else -> 0f
        }
        val meanG = when {
            paddleRecNormalize -> 0.5f
            imageNetNormalize -> 0.456f
            else -> 0f
        }
        val meanB = when {
            paddleRecNormalize -> 0.5f
            imageNetNormalize -> 0.406f
            else -> 0f
        }
        val stdR = when {
            paddleRecNormalize -> 0.5f
            imageNetNormalize -> 0.229f
            else -> 1f
        }
        val stdG = when {
            paddleRecNormalize -> 0.5f
            imageNetNormalize -> 0.224f
            else -> 1f
        }
        val stdB = when {
            paddleRecNormalize -> 0.5f
            imageNetNormalize -> 0.225f
            else -> 1f
        }

        val arr = FloatArray(3 * planeSize)
        if (spec.isNhwc) {
            for (i in pixels.indices) {
                val px = pixels[i]
                val r = ((px ushr 16) and 0xFF) / 255f
                val g = ((px ushr 8) and 0xFF) / 255f
                val b = (px and 0xFF) / 255f
                val base = i * 3
                arr[base] = (r - meanR) / stdR
                arr[base + 1] = (g - meanG) / stdG
                arr[base + 2] = (b - meanB) / stdB
            }
        } else {
            for (i in pixels.indices) {
                val px = pixels[i]
                val r = ((px ushr 16) and 0xFF) / 255f
                val g = ((px ushr 8) and 0xFF) / 255f
                val b = (px and 0xFF) / 255f
                arr[i] = (r - meanR) / stdR
                arr[planeSize + i] = (g - meanG) / stdG
                arr[2 * planeSize + i] = (b - meanB) / stdB
            }
        }
        val dims = if (spec.isNhwc) {
            longArrayOf(1L, targetH.toLong(), targetW.toLong(), 3L)
        } else {
            longArrayOf(1L, 3L, targetH.toLong(), targetW.toLong())
        }
        return if (spec.isFp16) {
            val byteBuf = ByteBuffer.allocateDirect(arr.size * 2).order(ByteOrder.nativeOrder())
            for (v in arr) {
                byteBuf.putShort(floatToFp16Bits(v))
            }
            byteBuf.rewind()
            OnnxTensor.createTensor(env, byteBuf, dims, OnnxJavaType.FLOAT16)
        } else {
            val buffer = FloatBuffer.allocate(arr.size)
            buffer.put(arr)
            buffer.rewind()
            OnnxTensor.createTensor(env, buffer, dims)
        }
    }

    private fun runClassifierSession(
        desc: ManagedModelDescriptor,
        session: OrtSession,
        bitmap: Bitmap
    ): String {
        val spec = resolveInputSpec(session, 224, 224)
        createImageTensor(bitmap, spec, imageNetNormalize = true).use { tensor ->
            session.run(mapOf(spec.name to tensor)).use { result ->
                val raw = extractFlatFloats(result.firstOrNull()?.value) ?: return ""
                if (raw.isEmpty()) return ""
                var maxIdx = 0
                var maxVal = Float.NEGATIVE_INFINITY
                for (i in raw.indices) {
                    if (raw[i] > maxVal) {
                        maxVal = raw[i]
                        maxIdx = i
                    }
                }
                var sumExp = 0.0
                for (v in raw) sumExp += exp((v - maxVal).toDouble().coerceIn(-50.0, 0.0))
                val topProb = (1.0 / sumExp.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)
                return "${desc.displayName}: class#$maxIdx (${(topProb * 100).toInt()}%)"
            }
        }
    }

    private fun runObjectDetectorSession(
        desc: ManagedModelDescriptor,
        session: OrtSession,
        bitmap: Bitmap,
        screenWidth: Int,
        screenHeight: Int
    ): List<VisionElementSignal> {
        val spec = resolveInputSpec(session, 640, 640)
        val targetW = spec.width
        val targetH = spec.height
        val rawCandidates = mutableListOf<Triple<Rect, Float, String>>()
        createImageTensor(bitmap, spec, imageNetNormalize = false).use { tensor ->
            session.run(mapOf(spec.name to tensor)).use { result ->
                // Case 1: Qualcomm AI Hub 3-output detector (boxes [1,N,4], scores [1,N], class_idx [1,N])
                if (result.size() >= 3) {
                    val boxesTensor = result.get(0) as? OnnxTensor
                    val scoresTensor = result.get(1) as? OnnxTensor
                    val classesTensor = result.get(2) as? OnnxTensor
                    val boxes = extractFlatFloats(boxesTensor)
                    val scores = extractFlatFloats(scoresTensor)
                    if (boxes != null && scores != null && boxes.size >= scores.size * 4) {
                        val classInts: IntArray? = classesTensor?.let { ct ->
                            ct.intBuffer?.let { ib -> IntArray(ib.remaining()).also { ib.get(it) } }
                                ?: ct.longBuffer?.let { lb -> IntArray(lb.remaining()) { lb.get().toInt() } }
                                ?: extractFlatFloats(ct)?.let { fl -> IntArray(fl.size) { fl[it].toInt() } }
                        }
                        val count = min(scores.size, boxes.size / 4)
                        val coordsAreNormalized = boxes.take(min(boxes.size, 40)).all { it <= 1.5f }
                        for (i in 0 until count) {
                            val score = scores[i]
                            if (score < 0.38f) continue
                            val base = i * 4
                            val denomX = if (coordsAreNormalized) 1f else targetW.toFloat()
                            val denomY = if (coordsAreNormalized) 1f else targetH.toFloat()
                            val x1 = (boxes[base] / denomX).coerceIn(0f, 1f)
                            val y1 = (boxes[base + 1] / denomY).coerceIn(0f, 1f)
                            val x2 = (boxes[base + 2] / denomX).coerceIn(0f, 1f)
                            val y2 = (boxes[base + 3] / denomY).coerceIn(0f, 1f)
                            val rect = PrecisionCoordinateMapper.mapNormalizedBoxToDevice(
                                normLeft = min(x1, x2),
                                normTop = min(y1, y2),
                                normRight = max(x1, x2),
                                normBottom = max(y1, y2),
                                screenWidth = screenWidth,
                                screenHeight = screenHeight
                            )
                            if (rect.width() >= 12 && rect.height() >= 12) {
                                val cls = classInts?.getOrNull(i) ?: 0
                                rawCandidates.add(Triple(rect, score.coerceIn(0f, 1f), cocoLabel(cls)))
                            }
                        }
                    }
                }

                if (rawCandidates.isEmpty()) {
                    val outTensor = result.firstOrNull()?.value as? OnnxTensor ?: return emptyList()
                    val shape = outTensor.info.shape
                    val data = extractFlatFloats(outTensor) ?: return emptyList()

                    if (shape.size == 3 && shape[1] in 5..120 && shape[2] > shape[1]) {
                        // YOLOv8/v11 [1, C, N] layout (cx, cy, w, h, class_scores...)
                        val channels = shape[1].toInt()
                        val numBoxes = shape[2].toInt()
                        for (i in 0 until numBoxes) {
                            var bestScore = 0f
                            var bestClass = 0
                            for (c in 4 until channels) {
                                val score = data[c * numBoxes + i]
                                if (score > bestScore) {
                                    bestScore = score
                                    bestClass = c - 4
                                }
                            }
                            if (bestScore >= 0.38f) {
                                val cxRaw = data[0 * numBoxes + i]
                                val cyRaw = data[1 * numBoxes + i]
                                val bwRaw = data[2 * numBoxes + i]
                                val bhRaw = data[3 * numBoxes + i]
                                val normScaleX = if (cxRaw <= 1.5f && bwRaw <= 1.5f) 1f else targetW.toFloat()
                                val normScaleY = if (cyRaw <= 1.5f && bhRaw <= 1.5f) 1f else targetH.toFloat()
                                val cx = cxRaw / normScaleX
                                val cy = cyRaw / normScaleY
                                val bw = bwRaw / normScaleX
                                val bh = bhRaw / normScaleY
                                val rect = PrecisionCoordinateMapper.mapNormalizedBoxToDevice(
                                    normLeft = (cx - bw / 2f).coerceIn(0f, 1f),
                                    normTop = (cy - bh / 2f).coerceIn(0f, 1f),
                                    normRight = (cx + bw / 2f).coerceIn(0f, 1f),
                                    normBottom = (cy + bh / 2f).coerceIn(0f, 1f),
                                    screenWidth = screenWidth,
                                    screenHeight = screenHeight
                                )
                                if (rect.width() >= 12 && rect.height() >= 12) {
                                    rawCandidates.add(Triple(rect, bestScore.coerceIn(0f, 1f), cocoLabel(bestClass)))
                                }
                            }
                        }
                    } else if (shape.size == 3 && shape[2] in 5..120 && shape[1] >= shape[2]) {
                        // YOLOv5/v7 [1, N, 5+C] layout (cx, cy, w, h, obj_conf, class_scores...) or [1, N, 6] NMS-fused
                        val numBoxes = shape[1].toInt()
                        val stride = shape[2].toInt()
                        for (i in 0 until numBoxes) {
                            val base = i * stride
                            if (stride == 6) {
                                val score = data[base + 4]
                                if (score < 0.38f) continue
                                val cls = data[base + 5].toInt()
                                val x1Raw = data[base]
                                val y1Raw = data[base + 1]
                                val x2Raw = data[base + 2]
                                val y2Raw = data[base + 3]
                                val normScaleX = if (x2Raw <= 1.5f && x1Raw <= 1.5f) 1f else targetW.toFloat()
                                val normScaleY = if (y2Raw <= 1.5f && y1Raw <= 1.5f) 1f else targetH.toFloat()
                                val rect = PrecisionCoordinateMapper.mapNormalizedBoxToDevice(
                                    normLeft = min(x1Raw, x2Raw) / normScaleX,
                                    normTop = min(y1Raw, y2Raw) / normScaleY,
                                    normRight = max(x1Raw, x2Raw) / normScaleX,
                                    normBottom = max(y1Raw, y2Raw) / normScaleY,
                                    screenWidth = screenWidth,
                                    screenHeight = screenHeight
                                )
                                if (rect.width() >= 12 && rect.height() >= 12) {
                                    rawCandidates.add(Triple(rect, score.coerceIn(0f, 1f), cocoLabel(cls)))
                                }
                            } else {
                                val objConf = data[base + 4]
                                if (objConf < 0.25f) continue
                                var bestScore = 0f
                                var bestClass = 0
                                for (c in 5 until stride) {
                                    val score = objConf * data[base + c]
                                    if (score > bestScore) {
                                        bestScore = score
                                        bestClass = c - 5
                                    }
                                }
                                if (bestScore >= 0.38f) {
                                    val cxRaw = data[base]
                                    val cyRaw = data[base + 1]
                                    val bwRaw = data[base + 2]
                                    val bhRaw = data[base + 3]
                                    val normScaleX = if (cxRaw <= 1.5f && bwRaw <= 1.5f) 1f else targetW.toFloat()
                                    val normScaleY = if (cyRaw <= 1.5f && bhRaw <= 1.5f) 1f else targetH.toFloat()
                                    val cx = cxRaw / normScaleX
                                    val cy = cyRaw / normScaleY
                                    val bw = bwRaw / normScaleX
                                    val bh = bhRaw / normScaleY
                                    val rect = PrecisionCoordinateMapper.mapNormalizedBoxToDevice(
                                        normLeft = (cx - bw / 2f).coerceIn(0f, 1f),
                                        normTop = (cy - bh / 2f).coerceIn(0f, 1f),
                                        normRight = (cx + bw / 2f).coerceIn(0f, 1f),
                                        normBottom = (cy + bh / 2f).coerceIn(0f, 1f),
                                        screenWidth = screenWidth,
                                        screenHeight = screenHeight
                                    )
                                    if (rect.width() >= 12 && rect.height() >= 12) {
                                        rawCandidates.add(Triple(rect, bestScore.coerceIn(0f, 1f), cocoLabel(bestClass)))
                                    }
                                }
                            }
                        }
                    } else if (shape.size == 2 && shape[1] >= 6) {
                        // NMS-fused [N, 6 or 7] output: (x1, y1, x2, y2, score, class) or (batch, x1, y1, x2, y2, class, score)
                        val cols = shape[1].toInt()
                        val rows = shape[0].toInt()
                        for (r in 0 until rows) {
                            val base = r * cols
                            val score = if (cols == 6) data[base + 4] else data[base + 6]
                            if (score < 0.38f) continue
                            val cls = if (cols == 6) data[base + 5].toInt() else data[base + 5].toInt()
                            val x1 = (data[base + if (cols == 6) 0 else 1] / targetW.toFloat()).coerceIn(0f, 1f)
                            val y1 = (data[base + if (cols == 6) 1 else 2] / targetH.toFloat()).coerceIn(0f, 1f)
                            val x2 = (data[base + if (cols == 6) 2 else 3] / targetW.toFloat()).coerceIn(0f, 1f)
                            val y2 = (data[base + if (cols == 6) 3 else 4] / targetH.toFloat()).coerceIn(0f, 1f)
                            val rect = PrecisionCoordinateMapper.mapNormalizedBoxToDevice(
                                normLeft = min(x1, x2),
                                normTop = min(y1, y2),
                                normRight = max(x1, x2),
                                normBottom = max(y1, y2),
                                screenWidth = screenWidth,
                                screenHeight = screenHeight
                            )
                            if (rect.width() >= 12 && rect.height() >= 12) {
                                rawCandidates.add(Triple(rect, score.coerceIn(0f, 1f), cocoLabel(cls)))
                            }
                        }
                    }
                }
            }
        }

        return nonMaxSuppression(rawCandidates).map { (rect, conf, label) ->
            VisionElementSignal(
                label = label,
                roleHint = "detected_region",
                bounds = rect,
                confidence = conf.coerceIn(0f, 0.99f),
                sourceSignal = SignalSource.LOCAL_DETECTOR,
                sourceModelId = desc.manifest.modelId,
                sourceModelRevision = desc.manifest.pinnedRevision,
                isConfidenceCalibrated = true
            )
        }
    }

    private fun runOcrDetectorSession(
        desc: ManagedModelDescriptor,
        session: OrtSession,
        bitmap: Bitmap,
        screenWidth: Int,
        screenHeight: Int
    ): List<VisionElementSignal> {
        val spec = resolveInputSpec(session, 640, 640)
        createImageTensor(bitmap, spec, imageNetNormalize = true).use { tensor ->
            session.run(mapOf(spec.name to tensor)).use { result ->
                val outTensor = result.firstOrNull()?.value as? OnnxTensor ?: return emptyList()
                val shape = outTensor.info.shape
                if (shape.size < 4) return emptyList()
                val mapH = shape[2].toInt().coerceAtLeast(1)
                val mapW = shape[3].toInt().coerceAtLeast(1)
                val probs = extractFlatFloats(outTensor) ?: return emptyList()
                if (probs.size < mapH * mapW) return emptyList()
                // Scan row-bands of DBNet probability map to find active text lines
                val rowHasText = BooleanArray(mapH)
                val rowMinX = IntArray(mapH) { mapW }
                val rowMaxX = IntArray(mapH) { 0 }
                val rowPeak = FloatArray(mapH)
                for (y in 0 until mapH) {
                    for (x in 0 until mapW) {
                        val p = probs[y * mapW + x]
                        if (p > 0.35f) {
                            rowHasText[y] = true
                            rowMinX[y] = min(rowMinX[y], x)
                            rowMaxX[y] = max(rowMaxX[y], x)
                            rowPeak[y] = max(rowPeak[y], p)
                        }
                    }
                }
                val bands = mutableListOf<Triple<Rect, Float, String>>()
                var yStart = -1
                var bandMinX = mapW
                var bandMaxX = 0
                var bandPeak = 0f
                for (y in 0 until mapH) {
                    if (rowHasText[y]) {
                        if (yStart < 0) {
                            yStart = y
                            bandMinX = rowMinX[y]
                            bandMaxX = rowMaxX[y]
                            bandPeak = rowPeak[y]
                        } else {
                            bandMinX = min(bandMinX, rowMinX[y])
                            bandMaxX = max(bandMaxX, rowMaxX[y])
                            bandPeak = max(bandPeak, rowPeak[y])
                        }
                    } else if (yStart >= 0) {
                        if (y - yStart >= 4 && bandMaxX > bandMinX + 6) {
                            val rect = PrecisionCoordinateMapper.mapNormalizedBoxToDevice(
                                normLeft = bandMinX.toFloat() / mapW,
                                normTop = yStart.toFloat() / mapH,
                                normRight = bandMaxX.toFloat() / mapW,
                                normBottom = y.toFloat() / mapH,
                                screenWidth = screenWidth,
                                screenHeight = screenHeight
                            )
                            bands.add(Triple(rect, bandPeak.coerceIn(0f, 0.95f), "Text Region"))
                        }
                        yStart = -1
                    }
                }
                // Flush trailing text band reaching the bottom row of the probability map
                if (yStart >= 0 && mapH - yStart >= 4 && bandMaxX > bandMinX + 6) {
                    val rect = PrecisionCoordinateMapper.mapNormalizedBoxToDevice(
                        normLeft = bandMinX.toFloat() / mapW,
                        normTop = yStart.toFloat() / mapH,
                        normRight = bandMaxX.toFloat() / mapW,
                        normBottom = 1f,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight
                    )
                    bands.add(Triple(rect, bandPeak.coerceIn(0f, 0.95f), "Text Region"))
                }
                return bands.take(12).map { (rect, conf, label) ->
                    VisionElementSignal(
                        label = label,
                        roleHint = "ocr_region",
                        bounds = rect,
                        confidence = conf,
                        sourceSignal = SignalSource.LOCAL_DETECTOR,
                        sourceModelId = desc.manifest.modelId,
                        sourceModelRevision = desc.manifest.pinnedRevision
                    )
                }
            }
        }
    }

    private fun loadOcrDictionary(file: File): List<String> {
        if (!file.isFile) return emptyList()
        return try {
            val lines = file.readLines(Charsets.UTF_8)
            // PaddleOCR CTC blank token is index 0; dictionary lines map to indices 1..N, trailing space at end
            buildList(lines.size + 2) {
                add("") // CTC blank index 0
                addAll(lines)
                add(" ")
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun runOcrRecognizerSession(
        desc: ManagedModelDescriptor,
        session: OrtSession,
        bitmap: Bitmap,
        detectedRegions: List<VisionElementSignal>,
        screenWidth: Int,
        screenHeight: Int,
        dictionary: List<String>
    ): List<VisionElementSignal> {
        val spec = resolveInputSpec(session, defaultH = 48, defaultW = 320)
        val targets = if (detectedRegions.isNotEmpty()) {
            detectedRegions.take(8)
        } else {
            listOf(
                VisionElementSignal(
                    label = "Text Region",
                    roleHint = "ocr_region",
                    bounds = Rect(0, 0, screenWidth.coerceAtLeast(1), (screenHeight / 4).coerceAtLeast(48)),
                    confidence = 0.7f,
                    sourceSignal = SignalSource.LOCAL_DETECTOR,
                    sourceModelId = desc.manifest.modelId,
                    sourceModelRevision = desc.manifest.pinnedRevision
                )
            )
        }
        val scaleX = bitmap.width.toFloat() / screenWidth.coerceAtLeast(1).toFloat()
        val scaleY = bitmap.height.toFloat() / screenHeight.coerceAtLeast(1).toFloat()
        val out = mutableListOf<VisionElementSignal>()

        for (region in targets) {
            val cropLeft = (region.bounds.left * scaleX).toInt().coerceIn(0, (bitmap.width - 1).coerceAtLeast(0))
            val cropTop = (region.bounds.top * scaleY).toInt().coerceIn(0, (bitmap.height - 1).coerceAtLeast(0))
            val cropRight = (region.bounds.right * scaleX).toInt().coerceIn(cropLeft + 1, bitmap.width.coerceAtLeast(1))
            val cropBottom = (region.bounds.bottom * scaleY).toInt().coerceIn(cropTop + 1, bitmap.height.coerceAtLeast(1))
            val cropW = (cropRight - cropLeft).coerceAtLeast(1)
            val cropH = (cropBottom - cropTop).coerceAtLeast(1)
            val crop = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropW, cropH)
            try {
                createImageTensor(crop, spec, imageNetNormalize = false, paddleRecNormalize = true).use { tensor ->
                    session.run(mapOf(spec.name to tensor)).use { result ->
                        val outTensor = result.firstOrNull()?.value as? OnnxTensor
                        val shape = outTensor?.info?.shape
                        val logits = extractFlatFloats(outTensor)
                        if (shape != null && shape.size >= 3 && logits != null) {
                            val timeSteps = shape[1].toInt().coerceAtLeast(1)
                            val vocabSize = shape[2].toInt().coerceAtLeast(1)
                            val decoded = decodeCtcGreedy(logits, timeSteps, vocabSize, dictionary)
                            if (decoded.first.isNotBlank()) {
                                out.add(
                                    region.copy(
                                        label = decoded.first,
                                        roleHint = "ocr_text",
                                        confidence = decoded.second.coerceIn(0.3f, 0.99f),
                                        sourceModelId = desc.manifest.modelId,
                                        sourceModelRevision = desc.manifest.pinnedRevision
                                    )
                                )
                            } else {
                                out.add(region)
                            }
                        } else {
                            out.add(region)
                        }
                    }
                }
            } finally {
                if (!crop.isRecycled) crop.recycle()
            }
        }
        return out
    }

    private fun decodeCtcGreedy(
        logits: FloatArray,
        timeSteps: Int,
        vocabSize: Int,
        dictionary: List<String>
    ): Pair<String, Float> {
        if (dictionary.isEmpty() || logits.size < timeSteps * vocabSize) return "" to 0f
        val sb = StringBuilder()
        var prevIdx = -1
        var confSum = 0f
        var charCount = 0
        for (t in 0 until timeSteps) {
            val base = t * vocabSize
            var bestIdx = 0
            var bestVal = Float.NEGATIVE_INFINITY
            for (v in 0 until vocabSize) {
                val lv = logits[base + v]
                if (lv > bestVal) {
                    bestVal = lv
                    bestIdx = v
                }
            }
            if (bestIdx != 0 && bestIdx != prevIdx && bestIdx < dictionary.size) {
                sb.append(dictionary[bestIdx])
                confSum += bestVal.coerceIn(0f, 1f)
                charCount++
            }
            prevIdx = bestIdx
        }
        val avgConf = if (charCount > 0) (confSum / charCount).coerceIn(0.35f, 0.99f) else 0f
        return sb.toString().trim() to avgConf
    }

    private fun runSegmentationSession(
        desc: ManagedModelDescriptor,
        session: OrtSession,
        bitmap: Bitmap,
        screenWidth: Int,
        screenHeight: Int
    ): List<VisionElementSignal> {
        val spec = resolveInputSpec(session, 520, 520)
        createImageTensor(bitmap, spec, imageNetNormalize = true).use { tensor ->
            session.run(mapOf(spec.name to tensor)).use { result ->
                val outTensor = result.firstOrNull()?.value as? OnnxTensor ?: return emptyList()
                val shape = outTensor.info.shape
                if (shape.size < 4) return emptyList()
                val classes = shape[1].toInt().coerceAtLeast(2)
                val mapH = shape[2].toInt().coerceAtLeast(1)
                val mapW = shape[3].toInt().coerceAtLeast(1)
                val logits = extractFlatFloats(outTensor) ?: return emptyList()
                val plane = mapH * mapW
                if (logits.size < classes * plane) return emptyList()
                var minX = mapW
                var minY = mapH
                var maxX = -1
                var maxY = -1
                for (y in 0 until mapH) {
                    for (x in 0 until mapW) {
                        val idx = y * mapW + x
                        var bestC = 0
                        var bestVal = Float.NEGATIVE_INFINITY
                        for (c in 0 until classes) {
                            val v = logits[c * plane + idx]
                            if (v > bestVal) {
                                bestVal = v
                                bestC = c
                            }
                        }
                        if (bestC != 0) {
                            minX = min(minX, x)
                            minY = min(minY, y)
                            maxX = max(maxX, x)
                            maxY = max(maxY, y)
                        }
                    }
                }
                if (maxX <= minX || maxY <= minY) return emptyList()
                val rect = PrecisionCoordinateMapper.mapNormalizedBoxToDevice(
                    normLeft = minX.toFloat() / mapW,
                    normTop = minY.toFloat() / mapH,
                    normRight = maxX.toFloat() / mapW,
                    normBottom = maxY.toFloat() / mapH,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight
                )
                return listOf(
                    VisionElementSignal(
                        label = "Salient Foreground Region",
                        roleHint = "segmented_subject",
                        bounds = rect,
                        confidence = 0.78f,
                        sourceSignal = SignalSource.LOCAL_DETECTOR,
                        sourceModelId = desc.manifest.modelId,
                        sourceModelRevision = desc.manifest.pinnedRevision
                    )
                )
            }
        }
    }

    private fun extractFlatFloats(value: Any?): FloatArray? {
        val tensor = value as? OnnxTensor ?: return null
        tensor.floatBuffer?.let { buf ->
            val dup = buf.duplicate()
            return FloatArray(dup.remaining()).also { dup.get(it) }
        }
        if (tensor.info?.type == OnnxJavaType.FLOAT16) {
            val bb = tensor.byteBuffer?.duplicate()?.order(ByteOrder.nativeOrder()) ?: return null
            val count = bb.remaining() / 2
            val out = FloatArray(count)
            for (i in 0 until count) {
                out[i] = fp16BitsToFloat(bb.short)
            }
            return out
        }
        return null
    }

    private fun floatToFp16Bits(value: Float): Short {
        val bits = java.lang.Float.floatToIntBits(value)
        val sign = (bits ushr 16) and 0x8000
        var exponent = ((bits ushr 23) and 0xFF) - 127 + 15
        var mantissa = bits and 0x7FFFFF
        if (exponent <= 0) {
            if (exponent < -10) return sign.toShort()
            mantissa = (mantissa or 0x800000) shr (1 - exponent)
            return (sign or (mantissa shr 13)).toShort()
        } else if (exponent >= 31) {
            return (sign or 0x7C00).toShort()
        }
        return (sign or (exponent shl 10) or (mantissa shr 13)).toShort()
    }

    private fun fp16BitsToFloat(half: Short): Float {
        val h = half.toInt() and 0xFFFF
        val sign = (h and 0x8000) shl 16
        val exp = (h ushr 10) and 0x1F
        val mant = h and 0x03FF
        return when (exp) {
            0 -> {
                if (mant == 0) {
                    java.lang.Float.intBitsToFloat(sign)
                } else {
                    var m = mant
                    var e = 127 - 14
                    while ((m and 0x0400) == 0) {
                        m = m shl 1
                        e--
                    }
                    m = m and 0x03FF
                    java.lang.Float.intBitsToFloat(sign or (e shl 23) or (m shl 13))
                }
            }
            31 -> java.lang.Float.intBitsToFloat(sign or 0x7F800000 or (mant shl 13))
            else -> java.lang.Float.intBitsToFloat(sign or ((exp + 112) shl 23) or (mant shl 13))
        }
    }

    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            clearSessions()
        }
    }

    fun clearSessions() {
        for (sess in sessionCache.values) {
            try {
                sess.close()
            } catch (_: Throwable) {}
        }
        sessionCache.clear()
    }
}
