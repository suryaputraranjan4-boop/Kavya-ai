package com.example.agent

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * Audio Frame Processor for continuous PCM 16-bit mono streams.
 *
 * Responsibilities:
 * 1. Validates frame integrity and sample length.
 * 2. Calculates real-time audio amplitude normalized for UI visualizers.
 * 3. Feeds Voice Activity Detector (VAD) without blocking the hardware capture thread.
 * 4. Distributes raw PCM frames to registered subscribers (e.g. Gemini Live).
 * 5. Delivers completed speech utterances asynchronously to processing callbacks.
 */
class PcmFrameProcessor(
    private val vad: VoiceActivityDetector
) {
    companion object {
        private const val TAG = "KavyaPcmProcessor"
        private const val NORMALIZATION_MAX_AMPLITUDE = 8000.0f
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Subscribers for raw PCM audio frames (e.g., Gemini Live client)
    private val frameSubscribers = ConcurrentHashMap<String, (ByteArray) -> Unit>()

    // Shared flow for amplitude updates (0.0f .. 1.0f)
    private val _amplitudeFlow = MutableSharedFlow<Float>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val amplitudeFlow: SharedFlow<Float> = _amplitudeFlow.asSharedFlow()

    init {
        // Wire VAD callbacks
        vad.onSpeechEnd = { utterancePcm ->
            onUtteranceCompleted?.invoke(utterancePcm)
        }
    }

    var onUtteranceCompleted: ((ByteArray) -> Unit)? = null

    /**
     * Registers a consumer for live PCM audio frames.
     */
    fun registerSubscriber(id: String, subscriber: (ByteArray) -> Unit) {
        frameSubscribers[id] = subscriber
        Log.d(TAG, "Registered frame subscriber: $id (total subscribers: ${frameSubscribers.size})")
    }

    /**
     * Unregisters a consumer from live PCM audio frames.
     */
    fun unregisterSubscriber(id: String) {
        frameSubscribers.remove(id)
        Log.d(TAG, "Unregistered frame subscriber: $id (remaining subscribers: ${frameSubscribers.size})")
    }

    fun hasSubscribers(): Boolean = frameSubscribers.isNotEmpty()

    /**
     * Process a raw PCM audio frame captured directly from AudioRecord.
     * Called on the high-priority recording thread. Must execute efficiently.
     */
    fun processRawFrame(frame: ByteArray, length: Int) {
        if (length <= 0) return

        val pcmData = frame.copyOf(length)

        // 1. Calculate normalized amplitude for UI visualizer
        val normalizedAmplitude = calculateNormalizedAmplitude(pcmData, length)
        _amplitudeFlow.tryEmit(normalizedAmplitude)

        // 2. Feed VAD
        vad.processFrame(pcmData, length)

        // 3. Deliver raw frame to subscribers asynchronously if any exist
        if (frameSubscribers.isNotEmpty()) {
            val subscribersCopy = frameSubscribers.values.toList()
            scope.launch {
                for (subscriber in subscribersCopy) {
                    try {
                        subscriber(pcmData)
                    } catch (e: Exception) {
                        Log.e(TAG, "Subscriber delivery exception", e)
                    }
                }
            }
        }
    }

    fun setPlaybackGated(isGated: Boolean) {
        vad.setGated(isGated)
    }

    fun setAssistantSpeaking(isSpeaking: Boolean) {
        vad.setAssistantSpeaking(isSpeaking)
    }

    fun reset() {
        vad.reset()
    }

    private fun calculateNormalizedAmplitude(pcmBytes: ByteArray, length: Int): Float {
        if (length < 2) return 0.0f
        var sumSquares = 0.0
        val sampleCount = length / 2

        val buffer = ByteBuffer.wrap(pcmBytes, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            val sample = buffer.short.toDouble()
            sumSquares += sample * sample
        }

        val rms = sqrt(sumSquares / sampleCount).toFloat()
        return (rms / NORMALIZATION_MAX_AMPLITUDE).coerceIn(0.0f, 1.0f)
    }
}
