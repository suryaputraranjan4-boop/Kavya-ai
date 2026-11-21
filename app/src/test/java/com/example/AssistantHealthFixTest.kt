package com.example

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AIModelRouter
import com.example.ai.KavyaAI
import com.example.ai.OfflinePrivacyGuard
import com.example.ai.RetrofitClient
import com.example.ai.StrictOfflineBlockedException
import com.example.ai.asr.OfflineAsrResult
import com.example.ai.asr.OfflineSpeechRecognizer
import com.example.ai.offline.GemmaModelManager
import com.example.ai.offline.ModelCatalog
import com.example.ai.offline.ModelCatalogGroup
import com.example.ai.offline.ModelDownloadCoordinator
import com.example.ai.offline.OfflineVisionAnalysis
import com.example.ai.offline.OfflineVisionContextBuilder
import com.example.ai.tts.KokoroTtsEngine
import com.example.ai.tts.VoiceEngine
import com.example.ai.tts.VoiceEngineManager
import com.example.services.KavyaRecognitionService
import com.example.services.ScreenShareManager
import com.example.utils.AppPreferences
import com.example.utils.NetworkConnectivityMonitor
import com.example.utils.NetworkFailureKind
import com.example.visual.ScreenshotManager
import com.example.visual.VisionPipeline
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.math.PI
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AssistantHealthFixTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        OfflinePrivacyGuard.bindApplicationContext(context)
        OfflinePrivacyGuard.resetMetricsForTest()
        AppPreferences.setOfflineFallbackEnabled(context, false)
        AppPreferences.setAiProvider(context, "GEMINI")
        OfflineSpeechRecognizer.getInstance(context).testCustomPcmTranscriber = null
        OfflineVisionContextBuilder.testCustomVisionAnalyzer = null
        NetworkConnectivityMonitor.testOverrideOnlineState = null
        VisionPipeline.clearCachedFrameForPrivacy("test setup")
        ScreenShareManager.setUserPaused(false)
        ScreenShareManager.updateProtectedSurfaceStatus(false)
    }

    @After
    fun tearDown() {
        AppPreferences.setOfflineFallbackEnabled(context, false)
        AppPreferences.setAiProvider(context, "GEMINI")
        OfflinePrivacyGuard.resetMetricsForTest()
        OfflineSpeechRecognizer.getInstance(context).testCustomPcmTranscriber = null
        OfflineVisionContextBuilder.testCustomVisionAnalyzer = null
        NetworkConnectivityMonitor.testOverrideOnlineState = null
        VisionPipeline.clearCachedFrameForPrivacy("test teardown")
        ScreenShareManager.setUserPaused(false)
        ScreenShareManager.updateProtectedSurfaceStatus(false)
    }

    private fun createSyntheticSpeechPcm16k(durationSec: Double = 1.0): ByteArray {
        val sampleRate = 16_000
        val totalSamples = (sampleRate * durationSec).toInt()
        val bytes = ByteArray(totalSamples * 2)
        for (i in 0 until totalSamples) {
            val sample = (sin(2.0 * PI * 440.0 * i / sampleRate) * 12_000.0).toInt().toShort()
            bytes[i * 2] = (sample.toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun createTestJpegBytes(): ByteArray {
        val bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(40, 120, 200))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        return out.toByteArray()
    }

    // =========================================================================
    // 1. OFFLINE SPEECH RECOGNITION (WHISPER ONNX)
    // =========================================================================

    @Test
    fun offlineAsr_strictOfflineModeRoutesLocallyForHindiEnglishHinglishWithoutCloud() = runBlocking {
        AppPreferences.setOfflineFallbackEnabled(context, true)
        val recognizer = OfflineSpeechRecognizer.getInstance(context)
        var invokedLang: String? = null
        recognizer.testCustomPcmTranscriber = { _, _, lang ->
            invokedLang = lang
            OfflineAsrResult.Success(
                transcript = "Namaste Kavya, aaj ka mausam batao",
                engineName = OfflineSpeechRecognizer.ENGINE_NAME,
                languageTag = lang,
                latencyMs = 42L
            )
        }

        val kavyaAi = KavyaAI(context)
        val pcm = createSyntheticSpeechPcm16k()
        val transcript = kavyaAi.transcribeAudio(pcm, sampleRate = 16_000, languageHint = "hi-IN")

        assertEquals("Namaste Kavya, aaj ka mausam batao", transcript)
        assertEquals("hi-IN", invokedLang)
        assertEquals(0, OfflinePrivacyGuard.blockedRequestCount)
        assertTrue(OfflineSpeechRecognizer.isLanguageSupportedByWhisper("hi-IN"))
        assertTrue(OfflineSpeechRecognizer.isLanguageSupportedByWhisper("en-US"))
        assertTrue(OfflineSpeechRecognizer.isLanguageSupportedByWhisper("hinglish"))
        assertFalse(OfflineSpeechRecognizer.isLanguageSupportedByWhisper("klingon-XX"))
    }

    @Test
    fun offlineAsr_whenWhisperModelMissing_returnsEmptyWithClearDiagnosticAndNoCloudFallback() = runBlocking {
        AppPreferences.setOfflineFallbackEnabled(context, true)
        val kavyaAi = KavyaAI(context)
        val pcm = createSyntheticSpeechPcm16k()

        val transcript = kavyaAi.transcribeAudio(pcm, sampleRate = 16_000, languageHint = "en-US")

        assertEquals("", transcript)
        val diagnostic = kavyaAi.lastTranscriptionStatusMessage.orEmpty()
        assertTrue(
            "Expected clear missing Whisper model diagnostic, got: $diagnostic",
            diagnostic.contains("not installed", ignoreCase = true) ||
                diagnostic.contains("whisper", ignoreCase = true)
        )
        assertEquals(0, OfflinePrivacyGuard.blockedRequestCount)
    }

    @Test
    fun offlineAsr_whisperManifestRegisteredInCatalogWithRequiredRoles() {
        val desc = ModelCatalog.findById(ModelCatalog.WHISPER_TINY_ASR_MANIFEST.modelId)
        assertNotNull("Whisper Tiny ASR manifest must be present in ModelCatalog", desc)
        assertEquals(ModelCatalogGroup.SPEECH_RECOGNITION, desc!!.group)
        val roles = desc.manifest.artifacts.map { it.role }.toSet()
        assertTrue("asr_encoder_onnx" in roles)
        assertTrue("asr_decoder_onnx" in roles)
        assertTrue("asr_tokenizer_json" in roles)
        assertTrue("asr_config_json" in roles)
    }

    // =========================================================================
    // 2. OFFLINE SCREENSHOT & VISION INTEGRATION
    // =========================================================================

    @Test
    fun offlineVision_routerChatAndStreamChatForwardImageLocallyWithoutCloud() = runBlocking {
        AppPreferences.setOfflineFallbackEnabled(context, true)
        var hookCalls = 0
        OfflineVisionContextBuilder.testCustomVisionAnalyzer = { _, _, _ ->
            hookCalls++
            OfflineVisionAnalysis(
                succeeded = true,
                contextSummary = "Detected Settings screen with Wi-Fi toggle ON.",
                enginesUsed = listOf("MLKit-OCR", "SmolVLM2-2.2B"),
                ocrSnippets = listOf("Settings", "Wi-Fi ON"),
                directVlmAnswer = "The screenshot shows Android Settings with Wi-Fi enabled."
            )
        }

        val jpeg = createTestJpegBytes()
        val router = AIModelRouter(context)

        val chatReply = router.chat(
            prompt = "What is on my screen?",
            imageBytes = jpeg
        )
        assertTrue(
            "Expected local vision answer in chat(), got: $chatReply",
            chatReply.contains("Wi-Fi enabled", ignoreCase = true) ||
                chatReply.contains("Settings", ignoreCase = true)
        )

        val streamChunks = router.streamChat(
            prompt = "Describe this screenshot",
            imageBytes = jpeg
        ).toList().joinToString("")
        assertTrue(
            "Expected local vision answer in streamChat(), got: $streamChunks",
            streamChunks.contains("Wi-Fi enabled", ignoreCase = true) ||
                streamChunks.contains("Settings", ignoreCase = true)
        )
        assertEquals(2, hookCalls)
        assertEquals(0, OfflinePrivacyGuard.blockedRequestCount)
    }

    @Test
    fun offlineVision_whenNoLocalVisionEngineAvailable_returnsExplicitErrorWithoutCloud() = runBlocking {
        AppPreferences.setOfflineFallbackEnabled(context, true)
        OfflineVisionContextBuilder.testCustomVisionAnalyzer = { _, _, _ ->
            OfflineVisionAnalysis(
                succeeded = false,
                contextSummary = "",
                enginesUsed = emptyList(),
                errorReason = "LOCAL OFFLINE VISION UNAVAILABLE: Download SmolVLM2 or Florence-2."
            )
        }

        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.BLACK)
        try {
            val result = KavyaAI(context).analyzeVisualUI(bmp, "What is here?")
            assertTrue(
                "Expected explicit local vision unavailability message, got: $result",
                result.contains("UNAVAILABLE", ignoreCase = true) ||
                    result.contains("SmolVLM2", ignoreCase = true)
            )
            assertEquals(0, OfflinePrivacyGuard.blockedRequestCount)
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    // =========================================================================
    // 3. STRICT OFFLINE PRIVACY GUARD & LOG REDACTION
    // =========================================================================

    @Test
    fun offlinePrivacyGuard_blocksRetrofitHttpAndModelDownloadsInStrictOfflineMode() {
        AppPreferences.setOfflineFallbackEnabled(context, true)
        OfflinePrivacyGuard.resetMetricsForTest()

        // 1. Direct OkHttp call via shared RetrofitClient must be rejected by StrictOfflineInterceptor
        val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models").build()
        var caughtException: Throwable? = null
        try {
            RetrofitClient.okHttpClient.newCall(req).execute().close()
        } catch (t: Throwable) {
            caughtException = t
        }
        assertTrue(
            "Expected StrictOfflineBlockedException, got: $caughtException",
            caughtException is StrictOfflineBlockedException
        )
        assertTrue(OfflinePrivacyGuard.blockedRequestCount >= 1)

        // 2. Model download coordinator must also block batch downloads in strict offline mode
        val dlCheck = OfflinePrivacyGuard.checkModelDownloadAllowed(
            context = context,
            modelId = ModelCatalog.WHISPER_TINY_ASR_MANIFEST.modelId
        )
        assertTrue("Expected offline refusal from checkModelDownloadAllowed", dlCheck.isFailure)
        val countBeforeBatch = OfflinePrivacyGuard.blockedRequestCount
        val coordinator = ModelDownloadCoordinator.getInstance(context)
        coordinator.startOrResumeBatch(
            listOf(ModelCatalog.WHISPER_TINY_ASR_MANIFEST.modelId)
        )
        assertTrue(
            "Expected startOrResumeBatch to record a blocked offline download attempt",
            OfflinePrivacyGuard.blockedRequestCount > countBeforeBatch
        )

        // 3. Log redaction must scrub API keys and Bearer tokens
        val redacted = OfflinePrivacyGuard.redactForLog(
            "GET https://api.example.com?key=AIzaSySecretKey12345 Authorization: Bearer sk-1234567890abcdef"
        )
        assertFalse(redacted.contains("AIzaSySecretKey12345"))
        assertFalse(redacted.contains("sk-1234567890abcdef"))
    }

    // =========================================================================
    // 4. OFFLINE TTS RELIABILITY & KOKORO INTEGRITY
    // =========================================================================

    @Test
    fun offlineTts_blocksCloudChatterboxInStrictOfflineModeAndValidatesKokoroIntegrity() {
        AppPreferences.setOfflineFallbackEnabled(context, true)
        val voiceMgr = VoiceEngineManager.getInstance(context)
        voiceMgr.setActiveEngine(VoiceEngine.CHATTERBOX)

        var errorMsg: String? = null
        voiceMgr.onSpeechError = { msg -> errorMsg = msg }
        voiceMgr.speak("Hello offline test")

        // Pump Robolectric main looper so Dispatchers.Main callback executes without deadlocking
        val deadline = System.currentTimeMillis() + 3_000L
        while (errorMsg == null && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        voiceMgr.onSpeechError = null

        assertNotNull("Expected offline block error for Chatterbox cloud TTS", errorMsg)
        assertTrue(
            "Expected offline error message, got: $errorMsg",
            errorMsg!!.contains("Offline", ignoreCase = true) ||
                errorMsg!!.contains("blocked", ignoreCase = true)
        )

        // Kokoro integrity verifier must reject a truncated/corrupt file
        val tempFile = File(context.cacheDir, "corrupt_kokoro_test.onnx")
        tempFile.writeBytes(ByteArray(256) { 0x42 })
        try {
            val verifyRes = KokoroTtsEngine(context).verifyKokoroArtifactIntegrity("CPU_INT8", tempFile)
            assertTrue("Corrupt Kokoro artifact must fail integrity check", verifyRes.isFailure)
        } finally {
            tempFile.delete()
        }
    }

    // =========================================================================
    // 5. SCREENSHOT FRESHNESS, PRIVACY PAUSE & BITMAP LIFECYCLE
    // =========================================================================

    @Test
    fun visionPipeline_enforcesFreshnessAndClearsFramesOnPrivacyPauseAndFlagSecure() {
        val frame = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        frame.eraseColor(Color.CYAN)
        VisionPipeline.onNewFrame(frame, bypassIntervalThrottle = true)
        assertNotNull("Fresh frame should be returned within TTL", VisionPipeline.getFreshFrame(maxAgeMs = 5_000L))

        // Privacy pause must immediately purge cached frames
        ScreenShareManager.setUserPaused(true)
        assertNull("VisionPipeline must purge cached frame on Privacy Pause", VisionPipeline.getLatestFrame())
        assertNull("ScreenShareManager must clear latestFrame on Privacy Pause", ScreenShareManager.latestFrame.value)

        // Re-populate and test FLAG_SECURE purge
        ScreenShareManager.setUserPaused(false)
        val frame2 = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        frame2.eraseColor(Color.MAGENTA)
        VisionPipeline.onNewFrame(frame2, bypassIntervalThrottle = true)
        assertNotNull(VisionPipeline.getLatestFrame())

        ScreenShareManager.updateProtectedSurfaceStatus(true)
        assertNull("VisionPipeline must purge cached frame on FLAG_SECURE", VisionPipeline.getLatestFrame())

        // ScreenshotManager must NOT recycle caller-owned bitmaps
        val callerBmp = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        callerBmp.eraseColor(Color.YELLOW)
        try {
            val (encoded, retained) = ScreenshotManager(context).encodeToOptimizedBase64(callerBmp, retainBitmap = false)
            assertTrue(encoded.isNotBlank())
            assertFalse("Caller-owned bitmap must NOT be recycled by encodeToOptimizedBase64", callerBmp.isRecycled)
            retained?.let { if (!it.isRecycled) it.recycle() }
        } finally {
            if (!callerBmp.isRecycled) callerBmp.recycle()
        }
    }

    // =========================================================================
    // 6. NETWORK CONNECTIVITY MONITOR & ERROR CLASSIFICATION
    // =========================================================================

    @Test
    fun networkMonitor_classifiesNetworkFailuresAndRespectsOverride() {
        assertEquals(
            NetworkFailureKind.STRICT_OFFLINE_BLOCKED,
            NetworkConnectivityMonitor.classifyThrowable(StrictOfflineBlockedException("blocked"))
        )
        assertEquals(
            NetworkFailureKind.TIMEOUT,
            NetworkConnectivityMonitor.classifyThrowable(SocketTimeoutException("read timed out"))
        )
        assertEquals(
            NetworkFailureKind.TLS_ERROR,
            NetworkConnectivityMonitor.classifyThrowable(SSLException("handshake failed"))
        )
        assertEquals(
            NetworkFailureKind.DNS_OR_CONNECT_FAILURE,
            NetworkConnectivityMonitor.classifyThrowable(UnknownHostException("no host"))
        )
        assertEquals(
            NetworkFailureKind.RATE_LIMITED,
            NetworkConnectivityMonitor.classifyThrowable(null, httpCode = 429)
        )
        assertEquals(
            NetworkFailureKind.AUTH_ERROR,
            NetworkConnectivityMonitor.classifyThrowable(null, httpCode = 401)
        )
        assertEquals(
            NetworkFailureKind.SERVER_ERROR,
            NetworkConnectivityMonitor.classifyThrowable(null, httpCode = 503)
        )

        NetworkConnectivityMonitor.testOverrideOnlineState = false
        assertFalse(NetworkConnectivityMonitor.isValidatedOnline(context))
        NetworkConnectivityMonitor.testOverrideOnlineState = true
        assertTrue(NetworkConnectivityMonitor.isValidatedOnline(context))
    }

    // =========================================================================
    // 7. MANIFEST PERMISSION AUDIT & RECOGNITION SERVICE PROTECTION
    // =========================================================================

    @Test
    fun manifest_hardensRecognitionServiceAndRemovesProhibitedPermissions() {
        val pm = context.packageManager
        val pkgInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requestedPermissions = pkgInfo.requestedPermissions?.toSet().orEmpty()

        assertTrue(
            "ACCESS_NETWORK_STATE must be declared for authoritative connectivity checks",
            Manifest.permission.ACCESS_NETWORK_STATE in requestedPermissions
        )
        assertFalse("QUERY_ALL_PACKAGES must be removed", "android.permission.QUERY_ALL_PACKAGES" in requestedPermissions)
        assertFalse("USE_EXACT_ALARM must be removed", "android.permission.USE_EXACT_ALARM" in requestedPermissions)
        assertFalse("BLUETOOTH_CONNECT must be removed", "android.permission.BLUETOOTH_CONNECT" in requestedPermissions)
        assertFalse("WAKE_LOCK must be removed", Manifest.permission.WAKE_LOCK in requestedPermissions)

        val svcInfo = pm.getServiceInfo(
            ComponentName(context, KavyaRecognitionService::class.java),
            0
        )
        assertEquals(
            "Exported KavyaRecognitionService must require RECORD_AUDIO permission",
            Manifest.permission.RECORD_AUDIO,
            svcInfo.permission
        )
    }
}
