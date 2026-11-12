package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.services.ScreenShareManager
import com.example.visual.VisionPipeline
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class ChromeScreenShareStabilityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ScreenShareManager.resetToIdle()
        ScreenShareManager.clearSnapshotVault()
        VisionPipeline.reset()
    }

    @After
    fun tearDown() {
        ScreenShareManager.resetToIdle()
        ScreenShareManager.clearSnapshotVault()
        VisionPipeline.reset()
    }

    private fun startMockActiveSession(tag: String = "mock.projection.consent"): String {
        val consentIntent = Intent(tag)
        ScreenShareManager.handleConsentResult(context, Activity.RESULT_OK, consentIntent)
        val stateAfterConsent = ScreenShareManager.state.value
        assertTrue(stateAfterConsent is ScreenShareManager.ScreenShareState.StartingService)
        val sessionId = ScreenShareManager.telemetry.value.sessionId
        assertTrue(sessionId.isNotBlank())

        ScreenShareManager.onServiceStarted(sessionId)
        assertTrue(ScreenShareManager.state.value is ScreenShareManager.ScreenShareState.WaitingForFirstFrame)

        val bmp = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.BLUE)
        val accepted = ScreenShareManager.onFrameCaptured(bmp, 360, 640, sessionId)
        assertTrue(accepted)
        assertTrue(ScreenShareManager.state.value is ScreenShareManager.ScreenShareState.Active)
        return sessionId
    }

    @Test
    fun `visibility hidden populates single-app Entire Screen Chrome recovery hint and resumes cleanly`() {
        val created = ScreenShareManager.createConsentIntent(context)
        assertNotNull(created)

        val sessionId = startMockActiveSession("mock.chrome.consent")
        ScreenShareManager.onCapturedContentVisibilityChanged(false, sessionId)
        assertTrue(ScreenShareManager.state.value is ScreenShareManager.ScreenShareState.Paused)
        assertTrue(ScreenShareManager.telemetry.value.isSystemVisibilityPaused)
        assertTrue(ScreenShareManager.telemetry.value.recoveryHint.contains("Entire Screen"))

        ScreenShareManager.onCapturedContentVisibilityChanged(true, sessionId)
        assertTrue(ScreenShareManager.state.value is ScreenShareManager.ScreenShareState.Active)
        assertFalse(ScreenShareManager.telemetry.value.isSystemVisibilityPaused)
    }

    @Test
    fun `privacy pause gates frames updates telemetry and restores active state on resume`() {
        val tokenIntent = Intent("mock.token.single.use")
        ScreenShareManager.handleConsentResult(context, Activity.RESULT_OK, tokenIntent)
        assertTrue(ScreenShareManager.state.value is ScreenShareManager.ScreenShareState.StartingService)
        val sessionId = ScreenShareManager.telemetry.value.sessionId

        ScreenShareManager.onServiceStarted(sessionId)
        val frame1 = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        assertTrue(ScreenShareManager.onFrameCaptured(frame1, 360, 640, sessionId))

        // Enable Privacy Pause -> must transition to Paused and reject incoming frames
        assertTrue(ScreenShareManager.toggleUserPause())
        assertTrue(ScreenShareManager.isUserPaused)
        assertTrue(ScreenShareManager.state.value is ScreenShareManager.ScreenShareState.Paused)
        assertTrue(ScreenShareManager.telemetry.value.recoveryHint.contains("Privacy Pause"))

        val blockedFrame = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        assertFalse(ScreenShareManager.onFrameCaptured(blockedFrame, 360, 640, sessionId))
        blockedFrame.recycle()

        // Resume -> must restore Active
        assertFalse(ScreenShareManager.toggleUserPause())
        assertTrue(ScreenShareManager.state.value is ScreenShareManager.ScreenShareState.Active)

        // Single-use token protection: reusing the exact same Intent instance must be rejected
        ScreenShareManager.handleConsentResult(context, Activity.RESULT_OK, tokenIntent)
        assertTrue(ScreenShareManager.state.value is ScreenShareManager.ScreenShareState.PermissionDenied)
    }

    @Test
    fun `protected surface and foreground chrome url telemetry update cleanly without dropping session`() {
        val sessionId = startMockActiveSession("mock.protected.chrome")
        ScreenShareManager.updateForegroundAppContext("com.android.chrome", "https://kotlinlang.org")
        val telemetryBefore = ScreenShareManager.telemetry.value
        assertEquals("com.android.chrome", telemetryBefore.foregroundPackage)
        assertEquals("https://kotlinlang.org", telemetryBefore.activeBrowserUrl)

        ScreenShareManager.updateProtectedSurfaceStatus(true, sessionId = sessionId)
        val activeProtected = ScreenShareManager.state.value as ScreenShareManager.ScreenShareState.Active
        assertTrue(activeProtected.isProtectedSurface)
        assertTrue(ScreenShareManager.telemetry.value.isProtectedSurface)
        assertTrue(ScreenShareManager.telemetry.value.recoveryHint.contains("FLAG_SECURE"))

        ScreenShareManager.updateProtectedSurfaceStatus(false, sessionId = sessionId)
        val activeNormal = ScreenShareManager.state.value as ScreenShareManager.ScreenShareState.Active
        assertFalse(activeNormal.isProtectedSurface)
        assertFalse(ScreenShareManager.telemetry.value.isProtectedSurface)
    }

    @Test
    fun `snapshot vault caps history at 6 items in reverse chronological order and clears cleanly`() {
        repeat(8) { idx ->
            ScreenShareManager.saveSnapshotToVault(
                title = "Snap $idx",
                summary = "Summary $idx",
                appPackage = "com.android.chrome",
                urlOrContext = "https://example.com/$idx"
            )
        }

        val vault = ScreenShareManager.snapshotVault.value
        assertEquals(6, vault.size)
        assertEquals("Snap 7", vault.first().title)
        assertEquals("Snap 2", vault.last().title)

        ScreenShareManager.clearSnapshotVault()
        assertTrue(ScreenShareManager.snapshotVault.value.isEmpty())
    }

    @Test
    fun `cropNormalizedRegionCompressed clamps inverted and out of range coordinates without recycling source`() {
        val bitmap = Bitmap.createBitmap(240, 480, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.CYAN)

        val normalCrop = VisionPipeline.cropNormalizedRegionCompressed(
            leftNorm = 0.2f,
            topNorm = 0.25f,
            rightNorm = 0.75f,
            bottomNorm = 0.7f,
            sourceBitmap = bitmap
        )
        assertNotNull(normalCrop)
        assertTrue((normalCrop?.size ?: 0) > 0)

        // Inverted & out-of-range bounds must safely clamp and succeed
        val clampedCrop = VisionPipeline.cropNormalizedRegionCompressed(
            leftNorm = 1.4f,
            topNorm = 0.9f,
            rightNorm = -0.2f,
            bottomNorm = 0.3f,
            sourceBitmap = bitmap
        )
        assertNotNull(clampedCrop)
        assertTrue((clampedCrop?.size ?: 0) > 0)
        assertFalse("Source bitmap must remain unrecycled", bitmap.isRecycled)

        bitmap.recycle()
    }

    @Test
    fun `static screen Active state remains Active and preserves isStaticScreen flag`() {
        val staticActive = ScreenShareManager.ScreenShareState.Active(
            width = 720,
            height = 1600,
            fps = 0,
            framesCaptured = 12,
            lastFrameTimestamp = 123456L,
            sessionId = "chrome-static-1",
            isStaticScreen = true,
            isProtectedSurface = false
        )
        assertTrue(staticActive.isStaticScreen)
        assertFalse(staticActive.isProtectedSurface)
        assertEquals(0, staticActive.fps)
        assertEquals("chrome-static-1", staticActive.sessionId)
    }
}
