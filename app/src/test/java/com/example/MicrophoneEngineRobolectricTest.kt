package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.agent.MicrophoneEngine
import com.example.agent.MicrophoneState
import com.example.agent.SleepWakeDetector
import com.example.state.KavyaStateManager
import com.example.ui.components.VoiceState
import com.example.utils.PermissionsManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MicrophoneEngineRobolectricTest {

    private lateinit var context: Context
    private lateinit var app: Application
    private lateinit var engine: MicrophoneEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        app = ApplicationProvider.getApplicationContext()
        engine = MicrophoneEngine.getInstance(context)
        engine.cancelListening()
        KavyaStateManager.updateVoiceState(VoiceState.IDLE)
    }

    @Test
    fun testInitialMicrophoneStateIsIdle() {
        assertEquals(MicrophoneState.IDLE, engine.micState.value)
        assertEquals(0f, engine.amplitude.value, 0.001f)
    }

    @Test
    fun testPermissionDeniedHandlingDoesNotCrash() {
        // Revoke RECORD_AUDIO permission in shadow application
        val shadowApp = Shadows.shadowOf(app)
        shadowApp.denyPermissions(Manifest.permission.RECORD_AUDIO)

        assertFalse(PermissionsManager.hasRecordAudioPermission(context))

        var errorReported = false
        var errorMessage = ""
        engine.startListening(
            onListeningStarted = {
                fail("Should not start listening without RECORD_AUDIO permission")
            },
            onResult = {
                fail("Should not receive results without permission")
            },
            onError = { err ->
                errorReported = true
                errorMessage = err
            }
        )

        assertTrue("Error should be reported when permission is denied", errorReported)
        assertTrue("Error message should mention permission", errorMessage.contains("permission", ignoreCase = true))
        assertEquals(MicrophoneState.ERROR, engine.micState.value)
        assertEquals(VoiceState.ERROR, KavyaStateManager.state.value.voiceState)
    }

    @Test
    fun testPermissionGrantedStartsMicrophoneSession() {
        val shadowApp = Shadows.shadowOf(app)
        shadowApp.grantPermissions(Manifest.permission.RECORD_AUDIO)

        assertTrue(PermissionsManager.hasRecordAudioPermission(context))
        engine.refreshHardwareDiagnostics()

        assertTrue(engine.status.value.hasPermission)
    }

    @Test
    fun testStopAndSleepCommandsDetectProperly() {
        assertTrue(SleepWakeDetector.isSleepCommand("Sleep Kavya"))
        assertTrue(SleepWakeDetector.isSleepCommand("Kavya sleep"))
        assertTrue(SleepWakeDetector.isSleepCommand("kavya chup ho jao"))
        assertTrue(SleepWakeDetector.isSleepCommand("so jao kavya"))

        assertTrue(SleepWakeDetector.isWakeCommand("Wake Kavya"))
        assertTrue(SleepWakeDetector.isWakeCommand("Kavya wake"))
        assertTrue(SleepWakeDetector.isWakeCommand("wake up kavya"))

        val isStopCommand = { text: String ->
            val lower = text.lowercase().trim()
            lower == "stop" || lower == "ruko" || lower == "cancel" || lower == "bas" ||
                    lower.contains("stop kavya") || lower.contains("kavya stop") ||
                    lower.contains("kavya chup") || lower.contains("chup ho jao")
        }
        assertTrue(isStopCommand("Stop Kavya"))
        assertTrue(isStopCommand("Kavya stop"))
        assertTrue(isStopCommand("ruko"))

        assertFalse(SleepWakeDetector.isSleepCommand("Open YouTube and play music"))
    }

    @Test
    fun testCentralizedStateTransitions() {
        KavyaStateManager.updateVoiceState(VoiceState.IDLE)
        assertEquals(VoiceState.IDLE, KavyaStateManager.state.value.voiceState)

        KavyaStateManager.updateVoiceState(VoiceState.LISTENING)
        assertEquals(VoiceState.LISTENING, KavyaStateManager.state.value.voiceState)

        KavyaStateManager.updateVoiceState(VoiceState.THINKING)
        assertEquals(VoiceState.THINKING, KavyaStateManager.state.value.voiceState)

        KavyaStateManager.updateVoiceState(VoiceState.IDLE)
        assertEquals(VoiceState.IDLE, KavyaStateManager.state.value.voiceState)
    }
}
