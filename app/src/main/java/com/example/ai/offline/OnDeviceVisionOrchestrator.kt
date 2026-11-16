package com.example.ai.offline

import ai.onnxruntime.NodeInfo
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
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

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
 */
class OnDeviceVisionOrchestrator private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "OnDeviceVisionOrch"
        private const val MAX_CACHED_LIGHTWEIGHT_SESSIONS = 2
        private const val MIN_RAM_MB_LIGHTWEIGHT_VISION = 480L

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

    fun getInstalledVisionModels(
        baseDir: File = GemmaModelManager.getModelDirectory(appContext)
    ): List<ManagedModelDescriptor> {
        return ModelCatalog.LIGHTWEIGHT_VISION_DESCRIPTORS.filter { desc ->
            desc.manifest.artifacts.all { spec ->
                val f = File(baseDir, spec.relativePath)
                f.isFile && f.length() > 0L
            }
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

            // Run at most one object detector, one OCR detector (fallback), one classifier (+ optional segmentation)
            val prioritized = buildList {
                installed.firstOrNull { it.capability == LightweightVisionCapability.OBJECT_DETECTION }?.let(::add)
                installed.firstOrNull { it.capability == LightweightVisionCapability.OCR_TEXT_DETECTION }?.let(::add)
                installed.firstOrNull { it.capability == LightweightVisionCapability.UI_CLASSIFICATION }?.let(::add)
                if (includeRegionCropClassification) {
                    installed.firstOrNull { it.capability == LightweightVisionCapability.REGION_SEGMENTATION }?.let(::add)
                }
            }

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
                        }
                        LightweightVisionCapability.OCR_TEXT_DETECTION -> {
                            val regions = runOcrDetectorSession(desc, session, screenshot, screenWidth, screenHeight)
                            allSignals.addAll(regions)
                            executedIds.add(desc.manifest.modelId)
                        }
                        LightweightVisionCapability.UI_CLASSIFICATION -> {
                            val tag = runClassifierSession(desc, session, screenshot)
                            if (tag.isNotBlank()) sceneTags.add(tag)
                            executedIds.add(desc.manifest.modelId)
                        }
                        LightweightVisionCapability.REGION_SEGMENTATION -> {
                            val segSignals = runSegmentationSession(desc, session, screenshot, screenWidth, screenHeight)
                            allSignals.addAll(segSignals)
                            executedIds.add(desc.manifest.modelId)
                        }
                        else -> Unit
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "ONNX execution failed for ${desc.manifest.modelId}: ${t.message}")
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

    private fun resolveInputSpec(session: OrtSession, defaultH: Int, defaultW: Int): Triple<String, Int, Int> {
        val entry = session.inputInfo.entries.firstOrNull()
            ?: return Triple("images", defaultH, defaultW)
        val info: NodeInfo = entry.value
        val tensorInfo = info.info as? TensorInfo
        val shape = tensorInfo?.shape
        val h = if (shape != null && shape.size >= 4 && shape[2] in 64..1536) shape[2].toInt() else defaultH
        val w = if (shape != null && shape.size >= 4 && shape[3] in 64..1536) shape[3].toInt() else defaultW
        return Triple(entry.key, h, w)
    }

    private fun createNchwFloatTensor(
        bitmap: Bitmap,
        targetW: Int,
        targetH: Int,
        imageNetNormalize: Boolean
    ): OnnxTensor {
        val env = ortEnv ?: OrtEnvironment.getEnvironment().also { ortEnv = it }
        val scaled = if (bitmap.width == targetW && bitmap.height == targetH) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        }
        val pixels = IntArray(targetW * targetH)
        scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        if (scaled !== bitmap) scaled.recycle()

        val planeSize = targetW * targetH
        val buffer = FloatBuffer.allocate(3 * planeSize)
        val meanR = if (imageNetNormalize) 0.485f else 0f
        val meanG = if (imageNetNormalize) 0.456f else 0f
        val meanB = if (imageNetNormalize) 0.406f else 0f
        val stdR = if (imageNetNormalize) 0.229f else 1f
        val stdG = if (imageNetNormalize) 0.224f else 1f
        val stdB = if (imageNetNormalize) 0.225f else 1f

        val arr = FloatArray(3 * planeSize)
        for (i in pixels.indices) {
            val px = pixels[i]
            val r = ((px ushr 16) and 0xFF) / 255f
            val g = ((px ushr 8) and 0xFF) / 255f
            val b = (px and 0xFF) / 255f
            arr[i] = (r - meanR) / stdR
            arr[planeSize + i] = (g - meanG) / stdG
            arr[2 * planeSize + i] = (b - meanB) / stdB
        }
        buffer.put(arr)
        buffer.rewind()
        return OnnxTensor.createTensor(env, buffer, longArrayOf(1L, 3L, targetH.toLong(), targetW.toLong()))
    }

    private fun runClassifierSession(
        desc: ManagedModelDescriptor,
        session: OrtSession,
        bitmap: Bitmap
    ): String {
        val (inputName, targetH, targetW) = resolveInputSpec(session, 224, 224)
        createNchwFloatTensor(bitmap, targetW, targetH, imageNetNormalize = true).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
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
        val (inputName, targetH, targetW) = resolveInputSpec(session, 640, 640)
        val rawCandidates = mutableListOf<Triple<Rect, Float, String>>()
        createNchwFloatTensor(bitmap, targetW, targetH, imageNetNormalize = false).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val outTensor = result.firstOrNull()?.value as? OnnxTensor ?: return emptyList()
                val shape = outTensor.info.shape
                val data = outTensor.floatBuffer?.let { buf ->
                    FloatArray(buf.remaining()).also { buf.get(it) }
                } ?: return emptyList()

                if (shape.size == 3 && shape[1] in 5..120 && shape[2] > shape[1]) {
                    // YOLOv8 [1, C, N] layout (cx, cy, w, h, class_scores...)
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
                            val cx = data[0 * numBoxes + i] / targetW.toFloat()
                            val cy = data[1 * numBoxes + i] / targetH.toFloat()
                            val bw = data[2 * numBoxes + i] / targetW.toFloat()
                            val bh = data[3 * numBoxes + i] / targetH.toFloat()
                            val rect = PrecisionCoordinateMapper.mapNormalizedBoxToDevice(
                                normLeft = (cx - bw / 2f).coerceIn(0f, 1f),
                                normTop = (cy - bh / 2f).coerceIn(0f, 1f),
                                normRight = (cx + bw / 2f).coerceIn(0f, 1f),
                                normBottom = (cy + bh / 2f).coerceIn(0f, 1f),
                                screenWidth = screenWidth,
                                screenHeight = screenHeight
                            )
                            if (rect.width() >= 12 && rect.height() >= 12) {
                                rawCandidates.add(Triple(rect, bestScore, "Region #$bestClass"))
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
                            rawCandidates.add(Triple(rect, score.coerceIn(0f, 1f), "Detected UI Region"))
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
        val (inputName, targetH, targetW) = resolveInputSpec(session, 640, 640)
        createNchwFloatTensor(bitmap, targetW, targetH, imageNetNormalize = true).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val outTensor = result.firstOrNull()?.value as? OnnxTensor ?: return emptyList()
                val shape = outTensor.info.shape
                if (shape.size < 4) return emptyList()
                val mapH = shape[2].toInt().coerceAtLeast(1)
                val mapW = shape[3].toInt().coerceAtLeast(1)
                val buf = outTensor.floatBuffer ?: return emptyList()
                val probs = FloatArray(buf.remaining()).also { buf.get(it) }
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

    private fun runSegmentationSession(
        desc: ManagedModelDescriptor,
        session: OrtSession,
        bitmap: Bitmap,
        screenWidth: Int,
        screenHeight: Int
    ): List<VisionElementSignal> {
        val (inputName, targetH, targetW) = resolveInputSpec(session, 520, 520)
        createNchwFloatTensor(bitmap, targetW, targetH, imageNetNormalize = true).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val outTensor = result.firstOrNull()?.value as? OnnxTensor ?: return emptyList()
                val shape = outTensor.info.shape
                if (shape.size < 4) return emptyList()
                val classes = shape[1].toInt().coerceAtLeast(2)
                val mapH = shape[2].toInt().coerceAtLeast(1)
                val mapW = shape[3].toInt().coerceAtLeast(1)
                val buf = outTensor.floatBuffer ?: return emptyList()
                val logits = FloatArray(buf.remaining()).also { buf.get(it) }
                val plane = mapH * mapW
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
        val buf = tensor.floatBuffer ?: return null
        return FloatArray(buf.remaining()).also { buf.get(it) }
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
