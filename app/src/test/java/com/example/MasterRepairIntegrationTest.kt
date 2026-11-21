package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.example.agent.MicrophoneEngine
import com.example.agent.VoiceActivityDetector
import com.example.ai.offline.GemmaModelFormat
import com.example.ai.offline.GemmaModelManager
import com.example.ai.offline.ModelCatalog
import com.example.ai.offline.ModelDownloadCoordinator
import com.example.ai.offline.OfflineArtifactSpec
import com.example.ai.offline.OfflineStorageProfile
import com.example.ai.offline.OnDeviceVisionOrchestrator
import com.example.ai.offline.VisionModelDiagnosticStage
import com.example.services.ScreenShareManager
import com.example.utils.SpeechSanitizer
import com.example.visual.JarvisGuidanceEngine
import com.example.visual.JarvisIntelligencePlanner
import com.example.visual.JarvisMode
import com.example.visual.JarvisOverlayController
import com.example.visual.ScreenAnnotationManager
import com.example.visual.ScreenCoordinateMapper
import com.example.visual.ScreenLayoutZones
import com.example.visual.ScreenshotManager
import com.example.visual.SemanticElement
import com.example.visual.SemanticElementType
import com.example.visual.SemanticScreenMap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MasterRepairIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        ScreenshotManager.resetCaptureCounterForTesting()
        ScreenAnnotationManager.clearAllAnnotations()
        JarvisOverlayController.clearAllVisuals()
    }

    // =========================================================================
    // A. STORAGE TESTS (PHASE 2)
    // =========================================================================

    @Test
    fun storage_noArtificial2GbOr4GbCapAndDynamicFreeSpaceCalculation() {
        assertEquals(Long.MAX_VALUE, OfflineStorageProfile.STRICT_2GB.maxTotalBytes)
        assertEquals(Long.MAX_VALUE, OfflineStorageProfile.EXPANDED.maxTotalBytes)

        val baseDir = tempFolder.newFolder("dynamic_storage_test")
        val dummyInstalled = File(baseDir, "models/sample_installed.onnx")
        dummyInstalled.parentFile?.mkdirs()
        FileOutputStream(dummyInstalled).use { it.write(ByteArray(4096) { 0x11 }) }

        val dummyPart = File(baseDir, "models/partial_download.gguf.part")
        FileOutputStream(dummyPart).use { it.write(ByteArray(2048) { 0x22 }) }

        val estimate = GemmaModelManager.calculateDynamicStorageEstimate(
            context = context,
            baseDir = baseDir,
            additionalRequestedBytes = 500L * 1024L * 1024L,
            overrideAvailableDiskBytes = 4L * 1024L * 1024L * 1024L
        )
        assertEquals(4096L, estimate.installedModelsBytes)
        assertEquals(2048L, estimate.partialDownloadsBytes)
        assertTrue(estimate.hasSufficientStorage)
        assertTrue(estimate.statusMessage.contains("Available Storage"))

        val insufficientEstimate = GemmaModelManager.calculateDynamicStorageEstimate(
            context = context,
            baseDir = baseDir,
            additionalRequestedBytes = 2L * 1024L * 1024L * 1024L,
            overrideAvailableDiskBytes = 500L * 1024L * 1024L
        )
        assertFalse(insufficientEstimate.hasSufficientStorage)
        assertTrue(insufficientEstimate.statusMessage.contains("Insufficient device storage"))
    }

    // =========================================================================
    // B. DOWNLOAD & INTEGRITY TESTS (PHASE 5)
    // =========================================================================

    @Test
    fun downloadIntegrity_rejectsHtmlErrorPagesAcrossAllFormatsAndVerifiesMagicHeaders() {
        val baseDir = tempFolder.newFolder("integrity_test")
        val coordinator = ModelDownloadCoordinator.getInstance(context)

        val htmlBytes = "<!DOCTYPE html><html><body>Error 404 Not Found</body></html>".toByteArray()

        // 1. HTML disguised as ONNX must be rejected
        val onnxSpec = OfflineArtifactSpec(
            fileName = "fake_model.onnx",
            relativePath = "test/fake_model.onnx",
            downloadUrl = "https://huggingface.co/test/fake_model.onnx",
            expectedSizeBytes = htmlBytes.size.toLong(),
            expectedSha256 = "",
            format = GemmaModelFormat.ONNX
        )
        val fakeOnnxFile = File(baseDir, onnxSpec.relativePath)
        fakeOnnxFile.parentFile?.mkdirs()
        FileOutputStream(fakeOnnxFile).use { it.write(htmlBytes) }

        val onnxVerify = coordinator.verifyArtifactWithDynamicSpec(fakeOnnxFile, onnxSpec)
        assertTrue("HTML error page disguised as ONNX must fail verification", onnxVerify.isFailure)
        assertTrue(onnxVerify.exceptionOrNull()?.message?.contains("HTML/XML error page") == true)

        // 2. HTML disguised as GGUF must be rejected
        val ggufSpec = onnxSpec.copy(
            fileName = "fake_model.gguf",
            relativePath = "test/fake_model.gguf",
            format = GemmaModelFormat.GGUF
        )
        val fakeGgufFile = File(baseDir, ggufSpec.relativePath)
        FileOutputStream(fakeGgufFile).use { it.write(htmlBytes) }
        val ggufVerify = coordinator.verifyArtifactWithDynamicSpec(fakeGgufFile, ggufSpec)
        assertTrue("HTML error page disguised as GGUF must fail verification", ggufVerify.isFailure)

        // 3. Valid binary ONNX payload (> 64 bytes, non-HTML) passes
        val validOnnxBytes = ByteArray(256) { idx -> ((idx * 17 + 8) and 0xFF).toByte() }
        val validOnnxSpec = onnxSpec.copy(
            fileName = "valid_model.onnx",
            relativePath = "test/valid_model.onnx",
            expectedSizeBytes = validOnnxBytes.size.toLong()
        )
        val validOnnxFile = File(baseDir, validOnnxSpec.relativePath)
        FileOutputStream(validOnnxFile).use { it.write(validOnnxBytes) }
        val validOnnxVerify = coordinator.verifyArtifactWithDynamicSpec(validOnnxFile, validOnnxSpec)
        assertTrue("Genuine binary ONNX artifact should pass verification", validOnnxVerify.isSuccess)
    }

    // =========================================================================
    // C. VISION MODEL DIAGNOSTICS & 12 ONNX WORKSPACE TESTS (PHASES 4 & 6)
    // =========================================================================

    @Test
    fun visionModels_advancedVisionModelsReportHonestDiagnosticStagesAfterLegacy12Replaced() {
        val baseDir = tempFolder.newFolder("vision_diag_test")
        val orchestrator = OnDeviceVisionOrchestrator.getInstance(context)

        // Legacy 12 lightweight ONNX models are removed and replaced by 12 Visualization Technologies
        assertTrue(ModelCatalog.LIGHTWEIGHT_VISION_DESCRIPTORS.isEmpty())
        assertEquals(12, com.example.vision.VisionTechRegistry.core12Technologies.size)

        // Inspect retained advanced vision models (SmolVLM2 + Florence-2) when none are downloaded
        val initialReports = orchestrator.inspectAllVisionDiagnostics(baseDir)
        assertEquals(2, initialReports.size)
        assertTrue(initialReports.values.all { it.stage == VisionModelDiagnosticStage.CONFIGURED })
        assertTrue(initialReports.values.none { it.isVerified })

        // Install Florence-2 fixture and verify stage advances to VERIFIED
        val florenceDesc = ModelCatalog.findById(GemmaModelManager.FLORENCE2_MANIFEST.modelId)!!
        for (art in florenceDesc.manifest.artifacts) {
            val f = File(baseDir, art.relativePath)
            f.parentFile?.mkdirs()
            if (art.fileName.endsWith(".json", ignoreCase = true)) {
                f.writeText("""{"model_type":"florence2"}""")
            } else {
                java.io.RandomAccessFile(f, "rw").use { raf ->
                    raf.write(ByteArray(128) { 0x08 })
                    if (art.expectedSizeBytes > 128L) {
                        raf.setLength(art.expectedSizeBytes)
                    }
                }
            }
        }
        val verifiedReport = orchestrator.inspectModelDiagnostics(GemmaModelManager.FLORENCE2_MANIFEST.modelId, baseDir)
        assertNotNull(verifiedReport)
        assertEquals(VisionModelDiagnosticStage.STRUCTURALLY_VALID, verifiedReport.stage)
        assertTrue(verifiedReport.isDownloaded)
        assertFalse(verifiedReport.isVerified)
        assertFalse(verifiedReport.isExecuted)

        // Record execution and verify stage advances to EXECUTED
        orchestrator.recordModelExecuted(GemmaModelManager.FLORENCE2_MANIFEST.modelId)
        val executedReport = orchestrator.inspectModelDiagnostics(GemmaModelManager.FLORENCE2_MANIFEST.modelId, baseDir)
        assertEquals(VisionModelDiagnosticStage.EXECUTED, executedReport.stage)
        assertTrue(executedReport.isExecuted)
    }

    // =========================================================================
    // D. SCREEN CAPTURE TESTS (PHASE 3)
    // =========================================================================

    @Test
    fun screenCapture_supportsUnlimitedSequentialScreenshotsWithoutCountRestriction() {
        ScreenshotManager.resetCaptureCounterForTesting()
        assertEquals(0L, ScreenshotManager.getTotalScreenshotsCaptured())
        val screenshotMgr = ScreenshotManager(context)

        // Capture and encode 25 consecutive screenshots (well beyond the old 6-snapshot ceiling)
        for (i in 1..25) {
            val bmp = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.rgb(i * 5, 100, 180))
            val (encoded, retained) = screenshotMgr.encodeToOptimizedBase64(bmp, retainBitmap = false)
            retained?.let { if (!it.isRecycled) it.recycle() }
            if (!bmp.isRecycled) bmp.recycle()
            assertTrue("Screenshot #$i must encode to non-empty base64", encoded.isNotBlank())
            ScreenShareManager.saveSnapshotToVault(
                title = "Capture #$i",
                summary = "Automated test capture #$i",
                appPackage = "com.example.test"
            )
        }

        assertEquals(25L, ScreenshotManager.getTotalScreenshotsCaptured())
        assertTrue(
            "ScreenShareManager vault must retain > 6 snapshots without dropping after 6",
            ScreenShareManager.snapshotVault.value.size >= 25
        )
    }

    // =========================================================================
    // E. MICROPHONE, VAD & SPEECH SANITIZER TESTS (PHASE 7)
    // =========================================================================

    @Test
    fun vad_rejectsSilenceClicksBroadbandNoiseAndAcceptsSustainedVoiceSpeech() {
        var speechStarted = false
        var speechEnded = false
        var currentTime = 1_000L

        val vad = VoiceActivityDetector(
            sampleRate = 16000,
            speechStartRmsThreshold = 300.0f,
            speechEndRmsThreshold = 200.0f,
            minSpeechDurationMs = 100L,
            silenceDurationMs = 260L
        )
        vad.timeProvider = { currentTime }
        vad.onSpeechStart = { speechStarted = true }
        vad.onSpeechEnd = { speechEnded = true }

        // 1. Silence frames -> must NOT trigger speech
        val silenceFrame = ByteArray(640)
        repeat(10) {
            currentTime += 20L
            vad.processFrame(silenceFrame, silenceFrame.size)
        }
        assertFalse("Silence must not trigger speech", speechStarted)

        // 2. Impulsive click/pop (1 loud sample spike, rest near 0 -> high crest factor > 4.2) -> must NOT trigger speech
        val clickFrame = ByteArray(640)
        ByteBuffer.wrap(clickFrame).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0, 18000)
            putShort(2, -18000)
        }
        repeat(10) {
            currentTime += 20L
            vad.processFrame(clickFrame, clickFrame.size)
        }
        assertFalse("Sharp click/pop transients must be rejected by crest-factor filter", speechStarted)

        // 3. High-frequency alternating hiss/noise (ZCR ~ 1.0 > 0.45) -> must NOT trigger speech
        val hissFrame = ByteArray(640)
        val hissBuf = ByteBuffer.wrap(hissFrame).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 320) {
            hissBuf.putShort(if (i % 2 == 0) 900 else -900)
        }
        repeat(10) {
            currentTime += 20L
            vad.processFrame(hissFrame, hissFrame.size)
        }
        assertFalse("Broadband high-ZCR hiss must be rejected", speechStarted)

        // 4. Natural 250 Hz voice-band harmonic signal -> MUST trigger speech start & end
        val voiceFrame = ByteArray(640)
        val voiceBuf = ByteBuffer.wrap(voiceFrame).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 320) {
            val sample = (1400.0 * sin(2.0 * Math.PI * 250.0 * i / 16000.0)).toInt().toShort()
            voiceBuf.putShort(sample)
        }
        repeat(12) {
            currentTime += 20L
            vad.processFrame(voiceFrame, voiceFrame.size)
        }
        assertTrue("Sustained voice-band speech must trigger onSpeechStart", speechStarted)

        repeat(18) {
            currentTime += 20L
            vad.processFrame(silenceFrame, silenceFrame.size)
        }
        assertTrue("Trailing silence after speech must trigger onSpeechEnd", speechEnded)
    }

    @Test
    fun speechSanitizer_rejectsHallucinationsFillersAndDuplicateTranscripts() {
        // Single-char and noise fillers must sanitize to empty string
        assertEquals("", SpeechSanitizer.sanitize("a"))
        assertEquals("", SpeechSanitizer.sanitize("uh"))
        assertEquals("", SpeechSanitizer.sanitize("hmm"))
        assertEquals("", SpeechSanitizer.sanitize("..."))
        assertFalse(SpeechSanitizer.isValidCommandTranscript("uh", 0.9f))
        assertFalse(SpeechSanitizer.isValidCommandTranscript("open youtube", 0.15f))
        assertTrue(SpeechSanitizer.isValidCommandTranscript("open youtube", 0.85f))

        // Duplicate transcript suppression within debounce window
        assertFalse(SpeechSanitizer.isDuplicateTranscript("Open YouTube", "", elapsedMs = 500L))
        assertTrue(SpeechSanitizer.isDuplicateTranscript("open youtube", "Open YouTube", elapsedMs = 800L))
        assertFalse(SpeechSanitizer.isDuplicateTranscript("open youtube", "Open YouTube", elapsedMs = 3000L))
    }

    // =========================================================================
    // F. WAKE-WORD ("HEY KAVYA") STATE MACHINE TESTS (PHASE 8)
    // =========================================================================

    @Test
    fun wakeWord_stateMachineIgnoresNonWakeSpeechAndActivatesOnHeyKavyaAndExecutesInlineCommand() {
        val engine = MicrophoneEngine.getInstance(context)
        engine.resetTranscriptDeduplicationForTesting()
        var dispatchedCommand: String? = null
        engine.setCommandListener { cmd -> dispatchedCommand = cmd }

        engine.setSleeping(true)
        assertEquals(MicrophoneEngine.WakeWordAssistantState.SLEEPING_WAKE_WORD_LISTENING, engine.assistantState.value)
        assertFalse(engine.isAssistantPopupVisible.value)

        // 1. Non-wake speech while sleeping must be ignored
        dispatchedCommand = null
        val ignored = engine.processRecognizedTranscript("the weather is sunny today", confidence = 0.92f, nowMs = 10_000L)
        assertFalse("Non-wake speech must not execute as a command while sleeping", ignored)
        assertNull(dispatchedCommand)
        assertEquals(MicrophoneEngine.WakeWordAssistantState.SLEEPING_WAKE_WORD_LISTENING, engine.assistantState.value)
        assertFalse(engine.isAssistantPopupVisible.value)

        // 2. Saying "Hey Kavya" alone wakes the assistant and opens the popup in COMMAND_LISTENING
        dispatchedCommand = null
        val wakeOnly = engine.processRecognizedTranscript("Hey Kavya", confidence = 0.95f, nowMs = 15_000L)
        assertTrue(wakeOnly)
        assertEquals("hey kavya", dispatchedCommand)
        assertEquals(MicrophoneEngine.WakeWordAssistantState.COMMAND_LISTENING, engine.assistantState.value)
        assertTrue("Assistant activation popup must become visible on wake word", engine.isAssistantPopupVisible.value)

        // Follow-up command while awake is dispatched immediately
        dispatchedCommand = null
        val followUpOk = engine.processRecognizedTranscript("Open Settings", confidence = 0.94f, nowMs = 20_000L)
        assertTrue(followUpOk)
        assertEquals("Open Settings", dispatchedCommand)
        assertEquals(MicrophoneEngine.WakeWordAssistantState.PROCESSING, engine.assistantState.value)

        // 3. Sleep command returns assistant to SLEEPING_WAKE_WORD_LISTENING and closes popup
        dispatchedCommand = null
        val sleepOk = engine.processRecognizedTranscript("Kavya sleep", confidence = 0.95f, nowMs = 25_000L)
        assertTrue(sleepOk)
        assertNull(dispatchedCommand)
        assertEquals(MicrophoneEngine.WakeWordAssistantState.SLEEPING_WAKE_WORD_LISTENING, engine.assistantState.value)
        assertFalse(engine.isAssistantPopupVisible.value)

        // 4. Inline wake + command ("Hey Kavya explain this screen") wakes AND dispatches command in one turn
        dispatchedCommand = null
        val inlineOk = engine.processRecognizedTranscript("Hey Kavya explain this screen", confidence = 0.96f, nowMs = 30_000L)
        assertTrue(inlineOk)
        assertEquals("explain this screen", dispatchedCommand)
        assertTrue(engine.isAssistantPopupVisible.value)
        assertEquals(MicrophoneEngine.WakeWordAssistantState.PROCESSING, engine.assistantState.value)

        // 5. Hindi wake phrase ("हे काव्या") also wakes from sleep
        engine.setSleeping(true)
        dispatchedCommand = null
        val hindiWakeOk = engine.processRecognizedTranscript("हे काव्या यूट्यूब खोलो", confidence = 0.93f, nowMs = 35_000L)
        assertTrue(hindiWakeOk)
        assertEquals("यूट्यूब खोलो", dispatchedCommand)

        engine.setSleeping(false)
    }

    // =========================================================================
    // G. JARVIS EXPLANATION MODE & COORDINATE MAPPING TESTS (PHASES 9 & 10)
    // =========================================================================

    @Test
    fun jarvisExplanation_distinguishesScreenExplanationFromStepByStepAndAlignsCoordinates() {
        // 1. Screen Explanation queries must NOT be classified as step-by-step guidance
        assertFalse(JarvisGuidanceEngine.isStepByStepGuidanceQuery("Explain this screen"))
        assertFalse(JarvisGuidanceEngine.isStepByStepGuidanceQuery("What is on my screen?"))
        assertFalse(JarvisGuidanceEngine.isStepByStepGuidanceQuery("Ye screen kya hai"))
        assertFalse(JarvisGuidanceEngine.isStepByStepGuidanceQuery("Describe this screen"))

        // 2. How-to / guidance queries MUST be classified as step-by-step guidance
        assertTrue(JarvisGuidanceEngine.isStepByStepGuidanceQuery("How do I change Wi-Fi settings?"))
        assertTrue(JarvisGuidanceEngine.isStepByStepGuidanceQuery("Where do I tap to checkout?"))
        assertTrue(JarvisGuidanceEngine.isStepByStepGuidanceQuery("Guide me step by step"))
        assertTrue(JarvisGuidanceEngine.isStepByStepGuidanceQuery("Login kaise kare"))

        // 3. EXPLAINS_ANYTHING plan must use natural section labels without "Step 1:" prefixes
        val header = SemanticElement(
            type = SemanticElementType.TITLE_HEADER,
            bounds = Rect(40, 120, 800, 220),
            text = "Account Overview",
            contentDescription = "Account Overview"
        )
        val btn = SemanticElement(
            type = SemanticElementType.ACTION_BUTTON,
            bounds = Rect(60, 500, 900, 620),
            text = "Transfer Funds",
            contentDescription = "Transfer Funds",
            isClickable = true
        )
        val screenMap = SemanticScreenMap(
            packageName = "com.example.finance",
            elements = listOf(header, btn),
            primaryHeading = header,
            actionButtons = listOf(btn),
            inputFields = emptyList(),
            contentBlocks = emptyList(),
            mediaRegions = emptyList(),
            layoutZones = ScreenLayoutZones(1080, 2400)
        )
        val explainPlan = JarvisIntelligencePlanner.createPlan(
            userQuery = "Explain this screen",
            screenMap = screenMap,
            forcedMode = JarvisMode.EXPLAINS_ANYTHING
        )
        assertEquals(JarvisMode.EXPLAINS_ANYTHING, explainPlan.mode)
        assertTrue(explainPlan.instructions.none { it.label.startsWith("Step ", ignoreCase = true) })

        // 4. Coordinate mapping across window offsets & strict clickPoint clamping inside target bounds
        val mapper = ScreenCoordinateMapper()
        mapper.updateOverlayWindowOffset(offsetX = 0, offsetY = 72)
        val screenRect = Rect(100, 400, 500, 520)
        val canvasRect = mapper.toOverlayCanvasRect(screenRect, canvasWidth = 1080, canvasHeight = 2400)
        assertEquals(100, canvasRect.left)
        assertEquals(328, canvasRect.top)
        assertEquals(500, canvasRect.right)
        assertEquals(448, canvasRect.bottom)

        val clamped = mapper.clampPointInsideBounds(point = Pair(950, 20), bounds = screenRect)
        assertTrue("Clamped X must lie strictly inside target bounds", clamped.first in screenRect.left..screenRect.right)
        assertTrue("Clamped Y must lie strictly inside target bounds", clamped.second in screenRect.top..screenRect.bottom)
    }

    // =========================================================================
    // H. VOICE PRIVACY CONSENT, PARTIAL CLEANUP & FLORENCE-2 HONEST FAILURE
    // =========================================================================

    @Test
    fun chatterbox_referenceVoiceUploadRequiresExplicitConsentAndDeleteCleansPartFiles() = runBlocking {
        com.example.utils.AppPreferences.setChatterboxVoiceUploadConsented(context, false)
        assertFalse(com.example.utils.AppPreferences.isChatterboxVoiceUploadConsented(context))

        com.example.utils.AppPreferences.setChatterboxBackendUrl(context, "http://127.0.0.1:8004")
        val client = com.example.ai.ChatterboxTtsClient(context)
        val result = client.synthesize(
            text = "नमस्ते काव्या",
            referenceAudioBytes = ByteArray(64) { 0x1A }
        )
        assertTrue("Unconsented voice reference upload must fail explicitly", result is com.example.ai.ChatterboxResult.Error)
        val errMsg = (result as com.example.ai.ChatterboxResult.Error).message
        assertTrue("Error must cite VOICE_UPLOAD_CONSENT_REQUIRED", errMsg.contains("VOICE_UPLOAD_CONSENT_REQUIRED"))

        // Verify partial file cleanup on deleteFile()
        val mgr = com.example.ai.ChatterboxModelManager.getInstance(context)
        val spec = com.example.ai.ChatterboxModelManager.OFFICIAL_SPECS.first()
        val target = mgr.getModelFile(spec.fileName)
        val part = File(target.parentFile, target.name + ".part")
        val marker = File(target.parentFile, target.name + ".verified")
        target.writeBytes(byteArrayOf(1, 2, 3))
        part.writeBytes(byteArrayOf(4, 5, 6))
        marker.writeText("3")

        mgr.deleteFile(spec.id)
        assertFalse("Target file must be removed", target.exists())
        assertFalse("Orphan .part file must be removed on deleteFile", part.exists())
        assertFalse("Verification marker must be removed on deleteFile", marker.exists())
    }

    @Test
    fun florence2_unloadedSessionFailsHonestlyWithoutFabricatedGridBoxes() = runBlocking {
        val engine = com.example.ai.offline.Gemma4E4BEngine.getInstance(context)
        val bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.DKGRAY)
        val result = engine.runFlorence2AuxiliaryPass(bmp, "<OD>")
        if (!bmp.isRecycled) bmp.recycle()
        assertTrue(
            "Florence-2 must return failure when ONNX sessions are not loaded rather than fabricating saliency boxes",
            result.isFailure
        )
    }
}
