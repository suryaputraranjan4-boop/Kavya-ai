package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.ai.GeminiModelRegistry
import com.example.ai.TaskRouter
import com.example.ai.providers.GeminiProvider
import com.example.ai.providers.ModelRegistry
import com.example.ai.providers.OpenRouterProvider
import com.example.utils.AppPreferences
import com.example.visual.EffectivePerformanceTier
import com.example.visual.PerformanceProfileManager
import com.example.visual.ScreenShareQualityMode
import com.example.visual.VisionPipeline
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PerformanceAndModelAuditTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        VisionPipeline.reset()
        PerformanceProfileManager.resetMetrics()
        AppPreferences.setScreenShareQualityMode(context, ScreenShareQualityMode.AUTO.id)
    }

    @Test
    fun testAdaptivePerformanceTierResolutionAcrossDeviceConditions() {
        // Explicit Low-End always resolves LOW_END
        assertEquals(
            EffectivePerformanceTier.LOW_END,
            PerformanceProfileManager.resolveEffectiveTier(
                mode = ScreenShareQualityMode.LOW_END,
                isLowRamDevice = false,
                totalRamMb = 8192L,
                isMemoryLow = false,
                isThermalThrottled = false,
                avgLatencyMs = 10f
            )
        )

        // Balanced steps down under thermal throttling or low memory
        assertEquals(
            EffectivePerformanceTier.BALANCED,
            PerformanceProfileManager.resolveEffectiveTier(
                mode = ScreenShareQualityMode.BALANCED,
                isLowRamDevice = false,
                totalRamMb = 6144L,
                isMemoryLow = false,
                isThermalThrottled = false,
                avgLatencyMs = 20f
            )
        )
        assertEquals(
            EffectivePerformanceTier.LOW_END,
            PerformanceProfileManager.resolveEffectiveTier(
                mode = ScreenShareQualityMode.BALANCED,
                isLowRamDevice = false,
                totalRamMb = 6144L,
                isMemoryLow = false,
                isThermalThrottled = true,
                avgLatencyMs = 20f
            )
        )

        // High quality caps to BALANCED under low memory / low RAM / thermal throttling
        assertEquals(
            EffectivePerformanceTier.HIGH_QUALITY,
            PerformanceProfileManager.resolveEffectiveTier(
                mode = ScreenShareQualityMode.HIGH_QUALITY,
                isLowRamDevice = false,
                totalRamMb = 8192L,
                isMemoryLow = false,
                isThermalThrottled = false,
                avgLatencyMs = 15f
            )
        )
        assertEquals(
            EffectivePerformanceTier.BALANCED,
            PerformanceProfileManager.resolveEffectiveTier(
                mode = ScreenShareQualityMode.HIGH_QUALITY,
                isLowRamDevice = true,
                totalRamMb = 2048L,
                isMemoryLow = false,
                isThermalThrottled = false,
                avgLatencyMs = 15f
            )
        )

        // AUTO branches: low-RAM device, high latency stepdown, mid RAM balanced, high RAM high quality
        assertEquals(
            EffectivePerformanceTier.LOW_END,
            PerformanceProfileManager.resolveEffectiveTier(
                mode = ScreenShareQualityMode.AUTO,
                isLowRamDevice = true,
                totalRamMb = 2048L,
                isMemoryLow = false,
                isThermalThrottled = false,
                avgLatencyMs = 12f
            )
        )
        assertEquals(
            EffectivePerformanceTier.LOW_END,
            PerformanceProfileManager.resolveEffectiveTier(
                mode = ScreenShareQualityMode.AUTO,
                isLowRamDevice = false,
                totalRamMb = 8192L,
                isMemoryLow = false,
                isThermalThrottled = false,
                avgLatencyMs = 95f
            )
        )
        assertEquals(
            EffectivePerformanceTier.BALANCED,
            PerformanceProfileManager.resolveEffectiveTier(
                mode = ScreenShareQualityMode.AUTO,
                isLowRamDevice = false,
                totalRamMb = 4096L,
                isMemoryLow = false,
                isThermalThrottled = false,
                avgLatencyMs = 20f
            )
        )
        assertEquals(
            EffectivePerformanceTier.HIGH_QUALITY,
            PerformanceProfileManager.resolveEffectiveTier(
                mode = ScreenShareQualityMode.AUTO,
                isLowRamDevice = false,
                totalRamMb = 8192L,
                isMemoryLow = false,
                isThermalThrottled = false,
                avgLatencyMs = 18f
            )
        )
    }

    @Test
    fun testQualityModePersistenceAndEwmaLatencyStepDown() {
        val savedLow = PerformanceProfileManager.setQualityMode(context, ScreenShareQualityMode.LOW_END)
        assertEquals(ScreenShareQualityMode.LOW_END, savedLow.selectedMode)
        assertEquals(EffectivePerformanceTier.LOW_END, savedLow.effectiveTier)
        assertEquals("LOW_END", AppPreferences.getScreenShareQualityMode(context))

        val reloaded = PerformanceProfileManager.loadAndRefresh(context)
        assertEquals(ScreenShareQualityMode.LOW_END, reloaded.selectedMode)

        PerformanceProfileManager.setQualityMode(context, ScreenShareQualityMode.AUTO)
        PerformanceProfileManager.resetMetrics()
        // Sustained high processing latency should push EWMA above 85ms threshold
        repeat(10) {
            PerformanceProfileManager.recordFrameProcessingLatencyMs(120L)
        }
        val afterSlowFrames = PerformanceProfileManager.snapshot.value
        assertTrue("EWMA latency should reflect slow frames", afterSlowFrames.avgFrameLatencyMs >= 85f)
        assertEquals(EffectivePerformanceTier.LOW_END, afterSlowFrames.effectiveTier)
    }

    @Test
    fun testVisionPipelineCompressionAndUnchangedFrameSkip() {
        PerformanceProfileManager.setQualityMode(context, ScreenShareQualityMode.LOW_END)
        PerformanceProfileManager.resetMetrics()

        val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(30, 120, 200))
        }

        VisionPipeline.onNewFrame(bitmap)
        val firstState = VisionPipeline.perceptionState.value
        assertTrue(firstState.isPipelineActive)
        assertTrue(firstState.hasVisualChanged)
        assertEquals(1, firstState.totalFramesProcessed)

        val jpegBytes = VisionPipeline.getCompressedJpegFrame()
        assertNotNull("Compressed JPEG bytes must be produced", jpegBytes)
        assertTrue("JPEG bytes must not be empty", (jpegBytes?.size ?: 0) > 100)
        // Input source bitmap must remain usable after compression scales down & recycles only temp scaled bitmap
        assertFalse("Original frame bitmap must not be recycled by getCompressedJpegFrame", bitmap.isRecycled)

        // Simulate frame interval elapsed using same visual pixels -> should increment skipped unchanged counter
        val field = VisionPipeline::class.java.getDeclaredField("lastProcessTimestamp").apply { isAccessible = true }
        field.setLong(VisionPipeline, System.currentTimeMillis() - 1000L)

        VisionPipeline.onNewFrame(bitmap)
        val secondState = VisionPipeline.perceptionState.value
        assertFalse("Identical bitmap should report hasVisualChanged = false", secondState.hasVisualChanged)
        assertEquals(1L, PerformanceProfileManager.snapshot.value.skippedUnchangedFrames)

        bitmap.recycle()
    }

    @Test
    fun testGeminiModelsStrictly35LiteAndAboveAndOpenRouterVisionSlugValid() {
        assertEquals("gemini-3.5-flash-lite", GeminiModelRegistry.DEFAULT_AGENT_MODEL)
        assertEquals("gemini-3.5-pro", GeminiModelRegistry.PRO_REASONING_MODEL)
        assertEquals("gemini-3.5-flash", GeminiModelRegistry.SECONDARY_TTS_MODEL)

        assertTrue(GeminiModelRegistry.CHAT_MODEL_TIERS.isNotEmpty())
        GeminiModelRegistry.CHAT_MODEL_TIERS.forEach { tier ->
            assertFalse("Discontinued model $tier must not be in CHAT_MODEL_TIERS", tier.contains("2.0") || tier.contains("2.5") || tier.contains("1.5"))
            assertTrue("Model $tier must be Gemini 3.5+ tier", tier.startsWith("gemini-3.5"))
        }

        val geminiRegistered = ModelRegistry.getModelsForProvider("gemini")
        assertTrue(geminiRegistered.any { it.displayName == "Gemini 3.5 Flash Lite" })
        assertTrue(geminiRegistered.any { it.displayName == "Gemini 3.5 Pro" })
        assertFalse(geminiRegistered.any { it.displayName.contains("2.5") })

        assertTrue(GeminiProvider.VERIFIED_MODELS.any { it.name == "Gemini 3.5 Flash Lite" })
        assertTrue(GeminiProvider.VERIFIED_MODELS.any { it.name == "Gemini 3.5 Pro" })

        // Verify TaskRouter routes Vision to a valid OpenRouter model when only OpenRouter is enabled
        AppPreferences.clearCustomApiKey(context)
        AppPreferences.setHuggingFaceEnabled(context, false)
        AppPreferences.setOpenRouterEnabled(context, true)
        AppPreferences.setOpenRouterApiKey(context, "sk-or-v1-test-vision-router-key-123456")

        val decision = TaskRouter.routeTask(
            prompt = "Describe what is on this screenshot image",
            hasImage = true,
            context = context
        )
        assertEquals("OPENROUTER", decision.provider)
        assertNotEquals("google/gemini-3.5-flash-lite-exp:free", decision.selectedModel)
        assertTrue(
            "OpenRouter vision model must be in curated catalog or auto router",
            OpenRouterProvider.DEFAULT_VERIFIED_MODELS.any { it.id == decision.selectedModel } ||
                decision.selectedModel == "openrouter/free"
        )
    }
}
