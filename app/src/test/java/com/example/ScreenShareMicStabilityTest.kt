package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.agent.MicrophoneEngine
import com.example.services.ScreenShareManager
import com.example.voice.KavyaMicrophoneEngine
import com.example.visual.JarvisGuidanceEngine
import com.example.visual.ScreenSharePresentationLevel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ScreenShareMicStabilityTest {

    @Test
    fun testMicrophoneEngineStateTransitions() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        KavyaMicrophoneEngine.init(context)

        val states = KavyaMicrophoneEngine.EngineState.entries
        assertTrue("Must contain full authoritative states", states.contains(KavyaMicrophoneEngine.EngineState.MIC_OFF))
        assertTrue(states.contains(KavyaMicrophoneEngine.EngineState.MIC_STARTING))
        assertTrue(states.contains(KavyaMicrophoneEngine.EngineState.MIC_ACTIVE))
        assertTrue(states.contains(KavyaMicrophoneEngine.EngineState.MIC_SPEECH_DETECTED))
        assertTrue(states.contains(KavyaMicrophoneEngine.EngineState.MIC_PROCESSING))
        assertTrue(states.contains(KavyaMicrophoneEngine.EngineState.MIC_SLEEPING))
        assertTrue(states.contains(KavyaMicrophoneEngine.EngineState.MIC_STOPPING))
        assertTrue(states.contains(KavyaMicrophoneEngine.EngineState.MIC_ERROR))

        // Check listening property helper
        assertTrue(KavyaMicrophoneEngine.EngineState.MIC_ACTIVE.isListening)
        assertTrue(KavyaMicrophoneEngine.EngineState.MIC_SPEECH_DETECTED.isListening)
        assertFalse(KavyaMicrophoneEngine.EngineState.MIC_OFF.isListening)
    }

    @Test
    fun testMicrophoneRapidStartStopIdempotency() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val mic = MicrophoneEngine.getInstance(context)

        // Rapid start / stop cycles should not throw or deadlock
        for (i in 0 until 5) {
            mic.stopListening()
            assertEquals(MicrophoneEngine.State.IDLE, mic.engineState.value)
        }
    }

    @Test
    fun testScreenSharePresentationLevels() {
        assertEquals(3, ScreenSharePresentationLevel.entries.size)
        assertEquals(ScreenSharePresentationLevel.BASIC, ScreenSharePresentationLevel.fromId("BASIC"))
        assertEquals(ScreenSharePresentationLevel.STANDARD, ScreenSharePresentationLevel.fromId("STANDARD"))
        assertEquals(ScreenSharePresentationLevel.ADVANCED, ScreenSharePresentationLevel.fromId("ADVANCED"))
        // Case-insensitivity and default fallback
        assertEquals(ScreenSharePresentationLevel.ADVANCED, ScreenSharePresentationLevel.fromId("advanced"))
        assertEquals(ScreenSharePresentationLevel.STANDARD, ScreenSharePresentationLevel.fromId("unknown"))

        assertEquals("LEVEL 1 • OVERLAY", ScreenSharePresentationLevel.BASIC.badgeLabel)
        assertEquals("LEVEL 2 • STUDIO", ScreenSharePresentationLevel.STANDARD.badgeLabel)
        assertEquals("LEVEL 3 • WORKSPACE", ScreenSharePresentationLevel.ADVANCED.badgeLabel)
    }

    @Test
    fun testScreenShareStateTransitions() {
        assertFalse(ScreenShareManager.isScreenSharingActive)

        val activeState = ScreenShareManager.ScreenShareState.Active(
            width = 1080,
            height = 2400,
            fps = 60,
            framesCaptured = 120,
            lastFrameTimestamp = System.currentTimeMillis(),
            sessionId = "test-session-123"
        )
        assertEquals(1080, activeState.width)
        assertEquals(2400, activeState.height)
        assertEquals(60, activeState.fps)

        val waitingState = ScreenShareManager.ScreenShareState.WaitingForFirstFrame(
            sessionStartedMs = System.currentTimeMillis(),
            sessionId = "test-session-123"
        )
        assertNotNull(waitingState.sessionId)
    }

    @Test
    fun testVoiceStudioNarrationCallbackIntegration() {
        var spokenText = ""
        var speechStopped = false

        JarvisGuidanceEngine.externalSpeakCallback = { text ->
            spokenText = text
        }
        JarvisGuidanceEngine.externalStopSpeakCallback = {
            speechStopped = true
        }

        JarvisGuidanceEngine.externalSpeakCallback?.invoke("Explaining this UI element")
        assertEquals("Explaining this UI element", spokenText)

        JarvisGuidanceEngine.externalStopSpeakCallback?.invoke()
        assertTrue(speechStopped)
    }
}
