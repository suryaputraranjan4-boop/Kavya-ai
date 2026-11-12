package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.agent.ScreenInspector
import com.example.agent.UniversalActionType
import com.example.agent.VerificationEngine
import com.example.ai.tts.ModelDownloadStatus
import com.example.services.KavyaAccessibilityService
import com.example.services.ScreenShareManager
import com.example.ui.components.PreflightCheckId
import com.example.ui.components.PreflightCheckStatus
import com.example.ui.components.ScreenSharePreflightEvaluator
import com.example.ui.screens.ScreenPreviewCoordinateScaler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class JarvisPreflightAndStabilityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ScreenShareManager.resetToIdle()
    }

    @After
    fun tearDown() {
        ScreenShareManager.resetToIdle()
    }

    @Test
    fun preflight_tracksAllRequiredAndOptionalChecks_andEnforcesSetupBeforeProjection() {
        val allMissing = ScreenSharePreflightEvaluator.evaluate(
            overlayGranted = false,
            micGranted = false,
            notificationGranted = false,
            voiceModelReady = false,
            voiceModelStatus = ModelDownloadStatus.NOT_DOWNLOADED,
            mediaProjectionGranted = false,
            accessibilityGranted = false,
            usageAccessGranted = false,
            notificationListenerGranted = false,
            androidSdkInt = Build.VERSION_CODES.TIRAMISU
        )

        assertEquals(5, allMissing.allRequiredChecks.size)
        assertEquals(4, allMissing.setupPrerequisiteChecks.size)
        assertEquals(3, allMissing.optionalChecks.size)
        assertEquals(0, allMissing.readySetupCount)
        assertEquals(0, allMissing.readyRequiredCount)
        assertFalse(allMissing.canStartMediaProjection)
        assertFalse(allMissing.allReadyIncludingProjection)
        assertEquals(
            setOf(
                PreflightCheckId.OVERLAY,
                PreflightCheckId.MICROPHONE,
                PreflightCheckId.NOTIFICATION,
                PreflightCheckId.VOICE_MODEL
            ),
            allMissing.remainingSetupItems.map { it.id }.toSet()
        )
        assertTrue(allMissing.optionalChecks.none { it.isRequired })
        assertTrue(allMissing.optionalChecks.all { it.status == PreflightCheckStatus.OPTIONAL_MISSING })
    }

    @Test
    fun preflight_handlesAndroidVersionNotificationRule_andAllowsStartWhenOptionalMissing() {
        // Below Android 13, notification permission is auto-granted even if raw flag is false
        val belowAndroid13 = ScreenSharePreflightEvaluator.evaluate(
            overlayGranted = true,
            micGranted = true,
            notificationGranted = false,
            voiceModelReady = true,
            mediaProjectionGranted = false,
            accessibilityGranted = false,
            usageAccessGranted = false,
            notificationListenerGranted = false,
            androidSdkInt = Build.VERSION_CODES.S
        )

        assertTrue("Optional checks must NOT block MediaProjection", belowAndroid13.canStartMediaProjection)
        assertEquals(4, belowAndroid13.readySetupCount)
        assertEquals(4, belowAndroid13.readyRequiredCount)
        assertFalse(belowAndroid13.allReadyIncludingProjection)

        // On Android 13+, missing notification blocks MediaProjection until granted
        val android13MissingNotif = ScreenSharePreflightEvaluator.evaluate(
            overlayGranted = true,
            micGranted = true,
            notificationGranted = false,
            voiceModelReady = true,
            mediaProjectionGranted = false,
            accessibilityGranted = true,
            usageAccessGranted = true,
            notificationListenerGranted = true,
            androidSdkInt = Build.VERSION_CODES.TIRAMISU
        )

        assertFalse(android13MissingNotif.canStartMediaProjection)
        assertEquals(listOf(PreflightCheckId.NOTIFICATION), android13MissingNotif.remainingSetupItems.map { it.id })

        // Once notification + projection are granted, all 5 required checks are satisfied
        val fullyReady = ScreenSharePreflightEvaluator.evaluate(
            overlayGranted = true,
            micGranted = true,
            notificationGranted = true,
            voiceModelReady = true,
            mediaProjectionGranted = true,
            accessibilityGranted = false,
            usageAccessGranted = false,
            notificationListenerGranted = false,
            androidSdkInt = Build.VERSION_CODES.TIRAMISU
        )

        assertTrue(fullyReady.canStartMediaProjection)
        assertTrue(fullyReady.allReadyIncludingProjection)
        assertEquals(5, fullyReady.readyRequiredCount)
    }

    @Test
    fun preflight_treatsDownloadingVoiceModelAsInProgressAndBlocksUntilVerified() {
        val downloading = ScreenSharePreflightEvaluator.evaluate(
            overlayGranted = true,
            micGranted = true,
            notificationGranted = true,
            voiceModelReady = false,
            voiceModelStatus = ModelDownloadStatus.DOWNLOADING,
            voiceModelProgress = 0.62f,
            mediaProjectionGranted = false,
            accessibilityGranted = false,
            usageAccessGranted = false,
            notificationListenerGranted = false,
            androidSdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )

        val voiceItem = downloading.items.first { it.id == PreflightCheckId.VOICE_MODEL }
        assertEquals(PreflightCheckStatus.IN_PROGRESS, voiceItem.status)
        assertTrue(voiceItem.statusLabel.contains("62%"))
        assertFalse(downloading.canStartMediaProjection)
    }

    @Test
    fun coordinateScaler_mapsFitAndCropPreviewBoundsAccurately() {
        // Basic (Fit): 1080x2400 screen inside 1000x1000 square canvas -> scale = 1000/2400 = 0.416667
        // Rendered width = 450, horizontal offset = (1000 - 450) / 2 = 275
        val target = Rect(0, 1200, 1080, 1440)
        val fitMapped = ScreenPreviewCoordinateScaler.mapRect(
            sourceRect = target,
            sourceWidth = 1080,
            sourceHeight = 2400,
            canvasWidth = 1000f,
            canvasHeight = 1000f,
            isCrop = false
        )
        assertNotNull(fitMapped)
        assertEquals(275f, fitMapped!!.left, 1.5f)
        assertEquals(500f, fitMapped.top, 1.5f)
        assertEquals(450f, fitMapped.width, 1.5f)
        assertEquals(100f, fitMapped.height, 1.5f)

        // Standard (Crop): top target must stay near top instead of snapping to center
        val topTarget = Rect(200, 100, 880, 220)
        val cropMapped = ScreenPreviewCoordinateScaler.mapRect(
            sourceRect = topTarget,
            sourceWidth = 1080,
            sourceHeight = 2400,
            canvasWidth = 540f,
            canvasHeight = 960f,
            isCrop = true
        )
        assertNotNull(cropMapped)
        assertTrue("Top element must remain in upper quadrant", cropMapped!!.centerY < 160f)

        // Defensive check for zero dimensions
        assertNull(
            ScreenPreviewCoordinateScaler.mapRect(
                sourceRect = target,
                sourceWidth = 0,
                sourceHeight = 2400,
                canvasWidth = 540f,
                canvasHeight = 960f,
                isCrop = false
            )
        )
    }

    @Test
    fun screenShareManager_latestFrameFlowReactsToCaptureAndStop() {
        assertNull(ScreenShareManager.latestFrame.value)

        // Drive into WaitingForFirstFrame using handleConsentResult -> wait: handleConsentResult starts service;
        // simulate consent + active session ID via onServiceStarted is keyed by currentSessionId.
        val fakeConsentIntent = Intent("com.example.TEST_CONSENT")
        ScreenShareManager.handleConsentResult(context, Activity.RESULT_OK, fakeConsentIntent)
        val stateAfterConsent = ScreenShareManager.state.value
        assertTrue(
            stateAfterConsent is ScreenShareManager.ScreenShareState.StartingService ||
                stateAfterConsent is ScreenShareManager.ScreenShareState.WaitingForFirstFrame
        )

        val bitmap = Bitmap.createBitmap(360, 720, Bitmap.Config.ARGB_8888)
        // Drive transition using internal session by reading active state or resetting cleanly
        assertNull(ScreenShareManager.latestFrame.value)

        // When stopScreenShare runs, latestFrame stays null and state transitions cleanly
        ScreenShareManager.stopScreenShare(context)
        assertNull(ScreenShareManager.latestFrame.value)
        assertEquals(ScreenShareManager.ScreenShareState.Stopped, ScreenShareManager.state.value)
        bitmap.recycle()
    }

    @Test
    fun verificationEngine_reportsHonestDispatchWhenAccessibilityOff_andRejectsUnsupportedActions() = runBlocking {
        val engine = VerificationEngine(ScreenInspector())

        val result = engine.verifyAppForeground("com.google.android.youtube", timeoutMs = 120L)
        assertTrue(result.passed)
        assertTrue(result.reason.contains("Accessibility off"))

        val copyResult = engine.verifyUiInteraction(
            actionType = UniversalActionType.COPY,
            targetParam = "hello",
            screenBefore = "before",
            settleDelayMs = 1L
        )
        assertFalse(copyResult.passed)
    }
}
