package com.example.voice

import com.example.voice.KavyaMicrophoneEngine.EngineState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneEngineStateTest {
    @Test
    fun startingIsTreatedAsListening() {
        assertTrue(EngineState.MIC_STARTING.isListening)
    }

    @Test
    fun activeAndSleepingAreTreatedAsListening() {
        assertTrue(EngineState.MIC_ACTIVE.isListening)
        assertTrue(EngineState.MIC_SLEEPING.isListening)
    }

    @Test
    fun offAndErrorAreNotTreatedAsListening() {
        assertFalse(EngineState.MIC_OFF.isListening)
        assertFalse(EngineState.MIC_ERROR.isListening)
    }
}
