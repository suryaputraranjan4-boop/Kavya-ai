package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.agent.VoiceActivityDetector
import com.example.ai.offline.DeviceResourceProfile
import com.example.ai.offline.GemmaModelFormat
import com.example.ai.offline.GemmaModelManager
import com.example.ai.offline.ModelCatalog
import com.example.ai.offline.ModelDownloadCoordinator
import com.example.ai.offline.OfflineArtifactSpec
import com.example.ai.offline.ResolvedRemoteFileMetadata
import com.example.ai.offline.SmartVisionRouter
import com.example.ai.offline.VisionTaskType
import com.example.services.KavyaAssistantInteractionService
import com.example.utils.SpeechSanitizer
import com.example.visual.JarvisGuidanceEngine
import com.example.visual.ScreenSharePresentationLevel
import com.example.visual.VisionPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MasterRepairVerificationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        VisionPipeline.reset()
        JarvisGuidanceEngine.resetForTesting()
    }

    @Test
    fun catalogReplacedLegacy12OnnxWith12VisualizationTechnologiesAndRetainsExclusions() {
        assertTrue(ModelCatalog.LIGHTWEIGHT_VISION_DESCRIPTORS.isEmpty())
        assertEquals(12, com.example.vision.VisionTechRegistry.core12Technologies.size)

        val advIds = ModelCatalog.getAdvancedVisionDescriptors().map { it.manifest.modelId }.toSet()
        assertTrue(GemmaModelManager.SMOLVLM2_MANIFEST.modelId in advIds)
        assertTrue(GemmaModelManager.FLORENCE2_MANIFEST.modelId in advIds)

        val excludedIds = ModelCatalog.EXCLUDED_MODELS.map { it.modelId }.toSet()
        assertTrue(excludedIds.any { it.contains("FastVLM", ignoreCase = true) })
        assertTrue(excludedIds.any { it.contains("LFM3-VL-2B", ignoreCase = true) })
        assertTrue(excludedIds.any { it.contains("UI-TARS-2B", ignoreCase = true) })
        assertTrue(excludedIds.any { it.contains("ShowUI-2B", ignoreCase = true) })
        assertTrue(excludedIds.any { it.contains("VisionPsy-Nano-460M", ignoreCase = true) })
        assertTrue(excludedIds.any { it.contains("moondream2", ignoreCase = true) })
    }

    @Test
    fun downloadCoordinatorResolvesOnnxZipAndSubfolderPathsFromHuggingFaceTree() {
        val spec = OfflineArtifactSpec(
            fileName = "MobileNet-v3-Small.onnx",
            relativePath = "vision/mobilenet/MobileNet-v3-Small.onnx",
            downloadUrl = "https://huggingface.co/qualcomm/MobileNet-v3-Small/resolve/main/MobileNet-v3-Small.onnx?download=true",
            expectedSizeBytes = 9_500_000L,
            expectedSha256 = "",
            format = GemmaModelFormat.ONNX
        )
        val treeWithZip = listOf(
            ResolvedRemoteFileMetadata(path = "README.md", sizeBytes = 2048L, sha256 = null),
            ResolvedRemoteFileMetadata(
                path = "float/MobileNet-v3-Small.onnx.zip",
                sizeBytes = 9_500_000L,
                sha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            )
        )
        val resolved = ModelDownloadCoordinator.resolveRemoteCandidateForSpec(spec, treeWithZip)
        assertNotNull(resolved)
        assertEquals("float/MobileNet-v3-Small.onnx.zip", resolved!!.path)
        val url = ModelDownloadCoordinator.buildHuggingFaceResolveUrl("qualcomm/MobileNet-v3-Small", "main", resolved.path)
        assertEquals(
            "https://huggingface.co/qualcomm/MobileNet-v3-Small/resolve/main/float/MobileNet-v3-Small.onnx.zip?download=true",
            url
        )
    }

    @Test
    fun downloadCoordinatorExtractsOnnxZipAndRejectsLfsPointerAndHtml() {
        val coordinator = ModelDownloadCoordinator.getInstance(context)
        val tmpDir = File(context.cacheDir, "onnx_zip_test_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            // 1. Create a valid .onnx.zip archive containing a synthetic binary .onnx (>64 bytes)
            val zipFile = File(tmpDir, "model.onnx.zip")
            val onnxPayload = ByteArray(128) { idx -> ((idx * 7 + 13) and 0xFF).toByte() }
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                zos.putNextEntry(ZipEntry("nested/MobileNet-v3-Small.onnx"))
                zos.write(onnxPayload)
                zos.closeEntry()
            }
            assertTrue(coordinator.isZipFileMagic(zipFile))
            val targetOnnx = File(tmpDir, "extracted/MobileNet-v3-Small.onnx")
            val extractRes = coordinator.extractOnnxFromZipArchive(zipFile, targetOnnx)
            assertTrue(extractRes.isSuccess)
            assertEquals(128L, targetOnnx.length())

            // 2. Reject Git LFS pointer file
            val lfsPointerFile = File(tmpDir, "pointer.onnx").apply {
                writeText(
                    "version https://git-lfs.github.com/spec/v1\noid sha256:0123456789abcdef\nsize 10184906\n"
                )
            }
            val spec = OfflineArtifactSpec(
                fileName = "pointer.onnx",
                relativePath = "pointer.onnx",
                downloadUrl = "https://example.com/pointer.onnx",
                expectedSizeBytes = 0L,
                expectedSha256 = "",
                format = GemmaModelFormat.ONNX
            )
            val (lfsOk, lfsReason) = coordinator.verifyArtifactWithDynamicSpec(lfsPointerFile, spec, deleteCorruptFile = false)
            assertFalse(lfsOk)
            assertTrue(lfsReason.contains("MODEL_CORRUPT"))

            // 3. Reject HTML error page
            val htmlFile = File(tmpDir, "err.onnx").apply {
                writeText("<!DOCTYPE html><html><body>404 Not Found</body></html>")
            }
            val (htmlOk, _) = coordinator.verifyArtifactWithDynamicSpec(htmlFile, spec, deleteCorruptFile = false)
            assertFalse(htmlOk)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun smartVisionRouterRespectsRamBatteryThermalAndNeverLoadsAllModels() {
        val all12Ready = setOf(
            "shufflenet-v2",
            "mobilenet-v3-small",
            "yolov8n-gui-detect",
            "paddleocr-v6-det",
            "deeplabv3-plus-mobilenet",
            GemmaModelManager.SMOLVLM2_MANIFEST.modelId
        )

        // Standard RAM online: max 2 lightweight models + cloud fusion, no heavy local VLM needed
        val normalOnline = SmartVisionRouter.route(
            taskType = VisionTaskType.DEEP_SCREEN_EXPLANATION,
            readyModelIds = all12Ready,
            profile = DeviceResourceProfile(
                availableRamMb = 3200L, totalRamMb = 8192L, batteryPercent = 80,
                isPowerSaveMode = false, isThermalThrottled = false, isOnline = true, hasCloudAiKey = true
            )
        )
        assertEquals(2, normalOnline.selectedOnDeviceModelIds.size)
        assertTrue(normalOnline.useCloudVisionFallback)
        assertFalse(normalOnline.useAdvancedLocalVlm)

        // Low battery power-save: capped to 1 lightweight model
        val lowBattery = SmartVisionRouter.route(
            taskType = VisionTaskType.UI_DETECTION,
            readyModelIds = all12Ready,
            profile = DeviceResourceProfile(
                availableRamMb = 3200L, totalRamMb = 8192L, batteryPercent = 12,
                isPowerSaveMode = true, isThermalThrottled = false, isOnline = false, hasCloudAiKey = false
            )
        )
        assertEquals(1, lowBattery.selectedOnDeviceModelIds.size)

        // Offline high RAM: enables advanced local VLM
        val offlineDeep = SmartVisionRouter.route(
            taskType = VisionTaskType.DEEP_SCREEN_EXPLANATION,
            readyModelIds = all12Ready,
            profile = DeviceResourceProfile(
                availableRamMb = 3500L, totalRamMb = 8192L, batteryPercent = 75,
                isPowerSaveMode = false, isThermalThrottled = false, isOnline = false, hasCloudAiKey = false
            )
        )
        assertTrue(offlineDeep.useAdvancedLocalVlm)
        assertFalse(offlineDeep.useCloudVisionFallback)

        // Thermal throttled: 0 local models
        val throttled = SmartVisionRouter.route(
            taskType = VisionTaskType.FAST_CLASSIFY,
            readyModelIds = all12Ready,
            profile = DeviceResourceProfile(
                availableRamMb = 3500L, totalRamMb = 8192L, batteryPercent = 75,
                isPowerSaveMode = false, isThermalThrottled = true, isOnline = true, hasCloudAiKey = true
            )
        )
        assertTrue(throttled.selectedOnDeviceModelIds.isEmpty())
        assertTrue(throttled.useCloudVisionFallback)
    }

    @Test
    fun automaticExplanationLevelSelectionAdaptsToPhrasingAndScreenComplexity() {
        assertEquals(
            ScreenSharePresentationLevel.BASIC,
            JarvisGuidanceEngine.selectAutomaticExplanationLevel("Explain simply")
        )
        assertEquals(
            ScreenSharePresentationLevel.STANDARD,
            JarvisGuidanceEngine.selectAutomaticExplanationLevel("Explain in detail")
        )
        assertEquals(
            ScreenSharePresentationLevel.ADVANCED,
            JarvisGuidanceEngine.selectAutomaticExplanationLevel("Explain technically")
        )
        // Automatic selection for generic "Explain this"
        assertEquals(
            ScreenSharePresentationLevel.BASIC,
            JarvisGuidanceEngine.selectAutomaticExplanationLevel(
                query = "Explain this",
                uiElementCount = 3,
                ocrTextSample = "Play Music"
            )
        )
        assertEquals(
            ScreenSharePresentationLevel.ADVANCED,
            JarvisGuidanceEngine.selectAutomaticExplanationLevel(
                query = "Explain this",
                uiElementCount = 24,
                ocrTextSample = "OAuth token API JSON GraphQL HTTP 401 stacktrace error debug latency",
                foregroundPackage = "com.android.settings"
            )
        )
    }

    @Test
    fun visionPipelineSuppressesDuplicateFramesAndDetectsRealScreenChange() {
        val bmp1 = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(20, 20, 20)) }
        val bmp1NearIdentical = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(21, 20, 20)) }
        val bmp2Changed = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(220, 220, 220)) }

        var meaningfulChanges = 0
        VisionPipeline.onMeaningfulScreenChange = { _, _, _ -> meaningfulChanges++ }

        VisionPipeline.onNewFrame(bmp1, bypassIntervalThrottle = true)
        assertTrue(VisionPipeline.perceptionState.value.hasVisualChanged)
        assertEquals(1L, VisionPipeline.perceptionState.value.frameSequenceNumber)

        VisionPipeline.onNewFrame(bmp1NearIdentical, bypassIntervalThrottle = true)
        assertFalse(VisionPipeline.perceptionState.value.hasVisualChanged)
        assertEquals(2L, VisionPipeline.perceptionState.value.frameSequenceNumber)

        VisionPipeline.onNewFrame(bmp2Changed, bypassIntervalThrottle = true)
        assertTrue(VisionPipeline.perceptionState.value.hasVisualChanged)
        assertEquals(3L, VisionPipeline.perceptionState.value.frameSequenceNumber)
        assertEquals(2, meaningfulChanges)

        VisionPipeline.onMeaningfulScreenChange = null
    }

    @Test
    fun vadAndSpeechSanitizerRejectSilenceAndHallucinations() {
        val vad = VoiceActivityDetector()
        var speechEnded = false
        vad.onSpeechEnd = { speechEnded = true }

        // Pure silence (all zeros) for 1 second should never trigger speech end and score 0 confidence
        val silencePcm = ByteArray(16000 * 2)
        assertEquals(0f, vad.computeUtteranceConfidence(silencePcm), 0.001f)

        var fakeNow = 1000L
        vad.timeProvider = { fakeNow }
        val silenceFrame = ByteArray(2048)
        repeat(20) {
            vad.processFrame(silenceFrame, silenceFrame.size)
            fakeNow += 64L
        }
        assertFalse(speechEnded)

        // Low-level synthetic hum (RMS ~100) must also have 0 acoustic confidence
        val humPcm = ByteArray(16000 * 2)
        val bb = ByteBuffer.wrap(humPcm).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 16000) {
            val sample = (kotlin.math.sin(i * 0.05) * 100.0).toInt().toShort()
            bb.putShort(sample)
        }
        assertEquals(0f, vad.computeUtteranceConfidence(humPcm), 0.001f)

        // SpeechSanitizer rejects neural STT silence hallucinations & low-confidence phantom phrases
        assertEquals("", SpeechSanitizer.sanitize("Thanks for watching."))
        assertEquals("", SpeechSanitizer.sanitize("No intelligible human speech"))
        assertEquals("", SpeechSanitizer.sanitize("[silence] <noise>"))
        assertFalse(SpeechSanitizer.isValidCommandTranscript("Thank you", confidence = 0.45f))
        assertFalse(SpeechSanitizer.isValidCommandTranscript("Okay", confidence = 0.40f))
        assertTrue(SpeechSanitizer.isValidCommandTranscript("Explain this screen", confidence = 0.85f))
        assertTrue(SpeechSanitizer.isValidCommandTranscript("YouTube kholo", confidence = 0.80f))
    }

    @Test
    fun defaultAssistantSetupIntentIsCreatedSafely() {
        val setupIntent = KavyaAssistantInteractionService.createDefaultAssistantSetupIntent(context)
        assertNotNull(setupIntent)
    }
}
