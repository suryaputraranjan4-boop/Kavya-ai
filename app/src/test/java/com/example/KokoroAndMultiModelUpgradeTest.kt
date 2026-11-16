package com.example

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.example.agent.AnalyzedNode
import com.example.agent.FreshScreenState
import com.example.ai.offline.GemmaModelManager
import com.example.ai.offline.ModelCatalog
import com.example.ai.offline.ModelDownloadCoordinator
import com.example.ai.offline.ModelLifecycleState
import com.example.ai.offline.OfflineStorageProfile
import com.example.ai.offline.OnDeviceVisionOrchestrator
import com.example.ai.tts.KokoroLanguageMode
import com.example.ai.tts.KokoroLanguageSegmenter
import com.example.ai.tts.KokoroSpeechScript
import com.example.ai.tts.KokoroTtsEngine
import com.example.ai.tts.VoiceEngine
import com.example.ai.tts.VoiceEngineManager
import com.example.visual.ExplanationStepController
import com.example.visual.GraphChartType
import com.example.visual.JarvisGuidanceEngine
import com.example.visual.JarvisIntelligencePlanner
import com.example.visual.JarvisMode
import com.example.visual.PreActionVerifier
import com.example.visual.ScreenAnnotationManager
import com.example.visual.ScreenAnnotationStep
import com.example.visual.ScreenLayoutZones
import com.example.visual.ScreenSharePresentationLevel
import com.example.visual.SemanticElement
import com.example.visual.SemanticElementType
import com.example.visual.SemanticScreenMap
import com.example.visual.VisualCardType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KokoroAndMultiModelUpgradeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        GemmaModelManager.setStorageProfile(context, OfflineStorageProfile.STRICT_2GB)
        ScreenAnnotationManager.clearAllAnnotations()
    }

    // =========================================================================
    // 1. KOKORO LANGUAGE SEGMENTER & FEMALE-ONLY POLICY TESTS
    // =========================================================================

    @Test
    fun kokoroSegmenter_switchesBilingualHindiHinglishEnglishToFemaleVoicesOnly() {
        val mixed = "नमस्ते! Kavya ready hai. Please open the Settings menu now."
        val segments = KokoroLanguageSegmenter.segmentText(
            rawText = mixed,
            languageMode = KokoroLanguageMode.AUTO_HINDI_ENGLISH,
            hindiVoiceId = "hf_alpha",
            englishVoiceId = "af_heart"
        )

        assertTrue("Expected >= 2 bilingual clauses", segments.size >= 2)
        assertTrue(segments.any { it.script == KokoroSpeechScript.HINDI_DEVANAGARI && it.voiceId == "hf_alpha" && it.locale == "hi" })
        assertTrue(segments.any { it.script == KokoroSpeechScript.ENGLISH_LATIN && it.voiceId == "af_heart" && it.locale == "en-US" })
        assertTrue("Every voice ID must be female-only", segments.all { KokoroLanguageSegmenter.isAllowedFemaleVoice(it.voiceId) })
    }

    @Test
    fun kokoroSegmenter_detectsRomanizedHinglishAndBlocksMaleVoices() {
        assertFalse(KokoroLanguageSegmenter.isAllowedFemaleVoice("am_adam"))
        assertFalse(KokoroLanguageSegmenter.isAllowedFemaleVoice("hm_omega"))
        assertFalse(KokoroLanguageSegmenter.isAllowedFemaleVoice("bm_george"))
        assertTrue(KokoroLanguageSegmenter.isAllowedFemaleVoice("hf_alpha"))
        assertTrue(KokoroLanguageSegmenter.isAllowedFemaleVoice("hf_beta"))
        assertTrue(KokoroLanguageSegmenter.isAllowedFemaleVoice("af_heart"))
        assertTrue(KokoroLanguageSegmenter.isAllowedFemaleVoice("bf_emma"))

        assertEquals("hf_alpha", KokoroLanguageSegmenter.sanitizeHindiVoice("hm_omega"))
        assertEquals("af_heart", KokoroLanguageSegmenter.sanitizeEnglishVoice("am_adam"))

        val filtered = KokoroLanguageSegmenter.filterFemaleVoices(listOf("am_adam", "hm_omega", "hf_beta", "af_nova"))
        assertEquals(listOf("hf_beta", "af_nova"), filtered.map { it.id })

        val hinglishSegs = KokoroLanguageSegmenter.segmentText(
            rawText = "Namaste, aapka kaam abhi shuru karo",
            languageMode = KokoroLanguageMode.AUTO_HINDI_ENGLISH,
            hindiVoiceId = "hf_beta",
            englishVoiceId = "af_heart"
        )
        assertTrue(hinglishSegs.isNotEmpty())
        assertEquals("hf_beta", hinglishSegs.first().voiceId)
        assertEquals(KokoroSpeechScript.HINGLISH_ROMAN, hinglishSegs.first().script)
    }

    @Test
    fun kokoroTtsEngine_pcm16ConversionAndStrictNoSilentFallbackOnMissingModel() {
        val pcmBytes = KokoroTtsEngine.floatPcmToPcm16Bytes(floatArrayOf(0.0f, 1.0f, -1.0f))
        assertEquals(6, pcmBytes.size)
        val shorts = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        assertEquals(0.toShort(), shorts.get(0))
        assertEquals(32767.toShort(), shorts.get(1))
        assertEquals((-32767).toShort(), shorts.get(2))

        // Verify VoiceEngineManager surfaces real Kokoro error cleanly without falling back silently
        val voiceMgr = VoiceEngineManager.getInstance(context)
        voiceMgr.setActiveEngine(VoiceEngine.KOKORO_82M)
        val latch = CountDownLatch(1)
        var capturedError: String? = null
        voiceMgr.onSpeechError = { msg ->
            capturedError = msg
            latch.countDown()
        }
        voiceMgr.speak("Namaste world")
        val deadline = System.currentTimeMillis() + 5_000L
        while (latch.count > 0 && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
        assertTrue("Kokoro error callback should fire", latch.count == 0L)
        assertNotNull(capturedError)
        assertEquals(VoiceEngine.KOKORO_82M, voiceMgr.getActiveEngine())
        voiceMgr.onSpeechError = null
    }

    @Test
    @Config(sdk = [24])
    fun kokoroTtsEngine_gatesSafelyOnApi24WhileKeepingMinSdk24ApkCompatibility() = runBlocking {
        assertEquals(24, Build.VERSION.SDK_INT)
        assertFalse(KokoroTtsEngine.isDeviceSupported())
        val engine = KokoroTtsEngine(context)
        val res = engine.ensureLoadedAndWarmedUp()
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull()?.message?.contains("UNSUPPORTED_DEVICE") == true)
    }

    // =========================================================================
    // 2. MODEL CATALOG, LIFECYCLE & BATCH DOWNLOAD BUDGET TESTS
    // =========================================================================

    @Test
    fun modelCatalog_containsAll12LightweightOnnxModels3ExcludedAndNoSyntheticHashes() {
        assertEquals(12, ModelCatalog.LIGHTWEIGHT_VISION_DESCRIPTORS.size)
        assertEquals(3, ModelCatalog.EXCLUDED_MODELS.size)
        assertEquals(10, ModelLifecycleState.entries.size)

        val excludedIds = ModelCatalog.EXCLUDED_MODELS.map { it.modelId }
        assertTrue("apple/FastVLM-0.5B" in excludedIds)
        assertTrue("qualcomm/FastViT" in excludedIds)
        assertTrue("qualcomm/SqueezeNet-1_1" in excludedIds)

        // Ensure no old synthetic revision hashes appear in any catalog descriptor
        for (desc in ModelCatalog.allDescriptors()) {
            assertFalse(desc.manifest.pinnedRevision.equals("e7a562f6f09e5a65b28eb82c7d7803fb627da05c", ignoreCase = true))
            for (art in desc.manifest.artifacts) {
                assertTrue(art.downloadUrl.startsWith("https://huggingface.co/"))
                if (art.expectedSha256.isNotBlank()) {
                    assertEquals(64, art.expectedSha256.length)
                }
            }
        }
    }

    @Test
    fun modelDownloadCoordinator_previewBatchBudgetEnforcesStorageAndHeadroom() {
        val baseDir = tempFolder.newFolder("budget_check")
        val coordinator = ModelDownloadCoordinator.getInstance(context)
        val recommendedIds = ModelCatalog.LIGHTWEIGHT_VISION_DESCRIPTORS
            .filter { it.recommendedDefault }
            .map { it.manifest.modelId }

        val okPreview = coordinator.previewBatchBudget(
            modelIds = recommendedIds,
            baseDir = baseDir,
            availableDiskBytes = 2L * 1024L * 1024L * 1024L
        )
        assertTrue(okPreview.fitsBudget)

        // Rejects when disk space is smaller than pending + 128 MB headroom
        val lowDiskPreview = coordinator.previewBatchBudget(
            modelIds = recommendedIds,
            baseDir = baseDir,
            availableDiskBytes = 50L * 1024L * 1024L
        )
        assertFalse(lowDiskPreview.fitsBudget)
        assertTrue(lowDiskPreview.reason.contains("STORAGE_INSUFFICIENT"))

        // Rejects Florence-2 under STRICT_2GB
        val blockedFlorence = coordinator.previewBatchBudget(
            modelIds = listOf(GemmaModelManager.FLORENCE2_MANIFEST.modelId),
            baseDir = baseDir,
            availableDiskBytes = 4L * 1024L * 1024L * 1024L
        )
        assertFalse(blockedFlorence.fitsBudget)
        assertTrue(blockedFlorence.reason.contains("STORAGE_INSUFFICIENT"))
    }

    // =========================================================================
    // 3. ON-DEVICE VISION ORCHESTRATOR NMS & JARVIS VISUAL PLANNER TESTS
    // =========================================================================

    @Test
    fun visionOrchestrator_nonMaxSuppressionFiltersOverlappingDetections() {
        val candidates = listOf(
            Triple(Rect(100, 100, 300, 300), 0.92f, "Button A"),
            Triple(Rect(110, 110, 305, 305), 0.78f, "Button A Dup"), // high IoU -> suppressed
            Triple(Rect(600, 800, 900, 950), 0.85f, "Search Bar")   // distinct -> kept
        )
        val kept = OnDeviceVisionOrchestrator.nonMaxSuppression(candidates, iouThreshold = 0.45f)
        assertEquals(2, kept.size)
        assertEquals("Button A", kept[0].third)
        assertEquals("Search Bar", kept[1].third)
    }

    @Test
    fun jarvisPlanner_preservesZeroChartMetricsAndSupportsPieAndFlowchartCards() {
        val button = SemanticElement(
            type = SemanticElementType.ACTION_BUTTON,
            bounds = Rect(80, 400, 500, 520),
            text = "Continue",
            contentDescription = "Continue Button",
            isClickable = true
        )
        val screenMap = SemanticScreenMap(
            packageName = "com.example.demo",
            elements = listOf(button),
            primaryHeading = null,
            actionButtons = listOf(button),
            inputFields = emptyList(),
            contentBlocks = emptyList(),
            mediaRegions = emptyList(),
            layoutZones = ScreenLayoutZones(1080, 2400)
        )

        // Pie chart preserves exact zero counts (0f, NEVER coerced to 1f!)
        val piePlan = JarvisIntelligencePlanner.createPlan(
            userQuery = "Show pie chart breakdown of this screen",
            screenMap = screenMap,
            forcedMode = JarvisMode.EXPLAINS_ANYTHING
        )
        val chartCard = piePlan.cards.find { it.type == VisualCardType.CANVAS_GRAPH }
        assertNotNull(chartCard)
        assertEquals(GraphChartType.PIE_CHART, chartCard!!.graphData!!.type)
        assertEquals(listOf(1f, 0f, 0f, 0f), chartCard.graphData!!.values)

        // Flowchart card creation
        val flowPlan = JarvisIntelligencePlanner.createPlan(
            userQuery = "Explain flowchart diagram of this screen workflow",
            screenMap = screenMap,
            forcedMode = JarvisMode.EXPLAINS_ANYTHING
        )
        val flowCard = flowPlan.cards.find { it.type == VisualCardType.FLOWCHART }
        assertNotNull(flowCard)
        assertTrue(flowCard!!.flowchartNodes.size >= 2)
        assertTrue(flowCard.flowchartEdges.isNotEmpty())
    }

    @Test
    fun jarvisGuidance_hindiHinglishLevelsAndVerifiedTransitionAdvanceOnlyOnChange() {
        assertEquals(ScreenSharePresentationLevel.BASIC, JarvisGuidanceEngine.inferExplanationLevelFromQuery("Aasan bhasha mein samjhao"))
        assertEquals(ScreenSharePresentationLevel.ADVANCED, JarvisGuidanceEngine.inferExplanationLevelFromQuery("Vistar aur technical detail mein batao"))
        assertEquals(ScreenSharePresentationLevel.STANDARD, JarvisGuidanceEngine.inferExplanationLevelFromQuery("Explain this screen"))

        val step1 = ScreenAnnotationStep(
            stepNumber = 1,
            totalSteps = 2,
            title = "Step 1",
            label = "Tap Input",
            spokenExplanation = "Tap the box",
            bounds = Rect(50, 200, 500, 300)
        )
        val step2 = ScreenAnnotationStep(
            stepNumber = 2,
            totalSteps = 2,
            title = "Step 2",
            label = "Tap Submit",
            spokenExplanation = "Tap Submit",
            bounds = Rect(50, 500, 500, 600)
        )
        ExplanationStepController.startSequence(listOf(step1, step2), autoAdvance = false)
        assertEquals(0, ExplanationStepController.currentStepIndex.value)

        val nodeA = AnalyzedNode(
            node = AccessibilityNodeInfo.obtain(),
            text = "Input",
            contentDescription = "Input",
            resourceId = "id/input",
            className = "android.widget.EditText",
            bounds = Rect(50, 200, 500, 300),
            isClickable = true,
            isEditable = true,
            isScrollable = false
        )
        val beforeScreen = FreshScreenState(
            root = null,
            foregroundPackage = "com.example.app",
            nodes = listOf(nodeA),
            visibleTexts = listOf("Input"),
            summaryString = "Before",
            isLoading = false,
            searchFields = emptyList(),
            searchButtons = emptyList(),
            organicContentResults = emptyList(),
            adNodes = emptyList()
        )

        // Same screen -> NOT verified -> step index stays 0
        val sameRes = JarvisGuidanceEngine.verifyStepTransitionAndAdvance(beforeScreen, beforeScreen)
        assertFalse(sameRes.transitionVerified)
        assertEquals(0, ExplanationStepController.currentStepIndex.value)

        // Genuinely transitioned screen -> verified -> step advances to 1
        val nodeB = nodeA.copy(text = "Confirmation", resourceId = "id/confirm", bounds = Rect(50, 500, 500, 600))
        val afterScreen = beforeScreen.copy(nodes = listOf(nodeB), visibleTexts = listOf("Confirmation"))
        val changedRes = JarvisGuidanceEngine.verifyStepTransitionAndAdvance(beforeScreen, afterScreen)
        assertTrue(changedRes.transitionVerified)
        assertEquals(1, ExplanationStepController.currentStepIndex.value)
    }
}
