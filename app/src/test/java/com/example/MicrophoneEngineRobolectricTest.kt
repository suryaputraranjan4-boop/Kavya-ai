package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.agent.MicrophoneEngine
import com.example.agent.PcmFrameProcessor
import com.example.agent.VoiceActivityDetector
import com.example.ai.GeminiLiveClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MicrophoneEngineRobolectricTest {

    @Test
    fun testVoiceActivityDetector_SpeechStartAndEnd() {
        var speechStarted = false
        var speechEnded = false
        var capturedUtteranceSize = 0
        var currentTime = 1000L

        val vad = VoiceActivityDetector(
            sampleRate = 16000,
            speechStartRmsThreshold = 300.0f,
            speechEndRmsThreshold = 200.0f,
            minSpeechDurationMs = 100L,
            silenceDurationMs = 300L
        )
        vad.timeProvider = { currentTime }

        vad.onSpeechStart = { speechStarted = true }
        vad.onSpeechEnd = { pcm ->
            speechEnded = true
            capturedUtteranceSize = pcm.size
        }

        // Generate silence frame (RMS = 0)
        val silenceFrame = ByteArray(640) // 20ms at 16kHz

        // Generate speech frame with high amplitude (RMS ~ 1000)
        val speechFrame = ByteArray(640)
        val buffer = ByteBuffer.wrap(speechFrame).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 320) {
            buffer.putShort(1000)
        }

        // 1. Send silence
        for (i in 0 until 10) {
            currentTime += 20L
            vad.processFrame(silenceFrame, silenceFrame.size)
        }
        assertFalse("Speech should not start on silence", speechStarted)

        // 2. Send active speech frames (> 100ms)
        for (i in 0 until 10) {
            currentTime += 20L
            vad.processFrame(speechFrame, speechFrame.size)
        }
        assertTrue("Speech start callback should fire on active speech", speechStarted)

        // 3. Send silence frames to trigger speech end (> 300ms)
        for (i in 0 until 20) {
            currentTime += 20L
            vad.processFrame(silenceFrame, silenceFrame.size)
        }
        assertTrue("Speech end callback should fire on trailing silence", speechEnded)
        assertTrue("Utterance buffer should contain captured speech", capturedUtteranceSize > 0)
    }

    @Test
    fun testVoiceActivityDetector_PlaybackGateSuppressesSpeech() {
        var speechStarted = false
        val vad = VoiceActivityDetector(speechStartRmsThreshold = 300.0f)
        vad.onSpeechStart = { speechStarted = true }

        // Enable TTS playback gate
        vad.setGated(true)

        val speechFrame = ByteArray(640)
        val buffer = ByteBuffer.wrap(speechFrame).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 320) {
            buffer.putShort(2000)
        }

        for (i in 0 until 15) {
            vad.processFrame(speechFrame, speechFrame.size)
        }

        assertFalse("Gated VAD must suppress speech detection during TTS playback", speechStarted)
    }

    @Test
    fun testPcmFrameProcessor_SubscriberDistribution() {
        val vad = VoiceActivityDetector()
        val processor = PcmFrameProcessor(vad)

        var frameReceivedCount = 0
        var receivedBytes = 0

        processor.registerSubscriber("test_sub") { frame ->
            frameReceivedCount++
            receivedBytes += frame.size
        }

        assertTrue("Processor should report active subscribers", processor.hasSubscribers())

        val testFrame = ByteArray(320) { 0x1F }
        processor.processRawFrame(testFrame, testFrame.size)

        // Give async coroutine task brief time to deliver
        Thread.sleep(50)

        assertEquals("Frame subscriber should receive frame", 1, frameReceivedCount)
        assertEquals("Frame byte count should match", 320, receivedBytes)

        processor.unregisterSubscriber("test_sub")
        assertFalse("Processor should report no active subscribers after unregister", processor.hasSubscribers())
    }

    @Test
    fun testMicrophoneEngineStateAndSpeakingGate() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = MicrophoneEngine.getInstance(context)

        assertEquals("Initial state should be IDLE", MicrophoneEngine.State.IDLE, engine.engineState.value)

        // Test assistant speaking gate
        engine.setAssistantSpeaking(true)
        assertEquals("State should switch to TTS_PLAYBACK when assistant speaks", MicrophoneEngine.State.TTS_PLAYBACK, engine.engineState.value)

        engine.setAssistantSpeaking(false)
        assertEquals("State should revert to IDLE when assistant finishes speaking", MicrophoneEngine.State.IDLE, engine.engineState.value)
    }

    @Test
    fun testGeminiLiveClientSubscriptionLifecycle() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val client = GeminiLiveClient(context)

        assertEquals("Client should start disconnected", LiveVoiceState.DISCONNECTED, client.state.value)

        client.startLiveSession("test_api_key")
        assertEquals("Client should enter connecting state after start", LiveVoiceState.CONNECTING, client.state.value)

        client.stopLiveSession()
        assertEquals("Client should return to disconnected after stop", LiveVoiceState.DISCONNECTED, client.state.value)
    }
}
