package com.example.agent

import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Production-grade Voice Activity Detector (VAD) for 16 kHz, Mono, PCM 16-bit audio.
 *
 * Key Features:
 * 1. Adaptive Noise Floor Tracking (handles quiet rooms and noisy environments).
 * 2. Pre-roll & Post-roll Buffering (prevents clipping speech onset and trailing consonants).
 * 3. Minimum Speech & Silence Duration Gating (filters out short acoustic pops/clicks).
 * 4. Language-Independent Signal Analysis (ideal for Hindi, English, Hinglish).
 * 5. Zero physical microphone ownership (pure signal processing engine).
 */
class VoiceActivityDetector(
    private val sampleRate: Int = 16000,
    private val speechStartRmsThreshold: Float = 260.0f,
    private val speechEndRmsThreshold: Float = 160.0f,
    private val minSpeechDurationMs: Long = 120L,
    private val silenceDurationMs: Long = 850L,
    private val preRollMs: Long = 300L,
    private val postRollMs: Long = 200L
) {
    companion object {
        private const val TAG = "KavyaVAD"
        private const val MIN_NOISE_FLOOR = 35.0f
        private const val MAX_NOISE_FLOOR = 400.0f
        private const val MAX_UTTERANCE_DURATION_MS = 20000L // 20s hard cap
    }

    var timeProvider: () -> Long = System::currentTimeMillis

    var onSpeechStart: (() -> Unit)? = null
    var onSpeechActiveFrame: ((ByteArray) -> Unit)? = null
    var onSpeechEnd: ((ByteArray) -> Unit)? = null

    @Volatile private var isGated: Boolean = false
    @Volatile private var isSpeechDetected: Boolean = false

    private var noiseFloor: Float = 120.0f
    private var speechStartTimeMs: Long = 0L
    private var lastSpeechTimeMs: Long = 0L
    private var silenceStartTimeMs: Long = 0L

    // Pre-roll circular buffer storing raw PCM frames
    private val preRollBuffer = java.util.ArrayDeque<ByteArray>()
    private val bytesPerMs = (sampleRate * 2) / 1000 // 16kHz 16-bit mono = 32 bytes/ms
    private val maxPreRollBytes = (preRollMs * bytesPerMs).toInt()
    private var currentPreRollBytes = 0

    // Utterance accumulator
    private val utteranceStream = ByteArrayOutputStream()

    /**
     * Set playback gate (e.g., when Kavya is speaking via TTS) to suppress speech detection.
     */
    fun setGated(gated: Boolean) {
        if (this.isGated != gated) {
            this.isGated = gated
            if (gated) {
                resetUtterance()
            }
        }
    }

    fun isSpeechActive(): Boolean = isSpeechDetected

    /**
     * Processes a single 16-bit PCM mono audio frame.
     */
    @Synchronized
    fun processFrame(frame: ByteArray, length: Int) {
        if (isGated || length <= 0) return

        val rms = calculateRms(frame, length)
        val now = timeProvider()

        // Adaptive noise floor update (only when no active speech is detected)
        if (!isSpeechDetected && rms < (noiseFloor * 2.0f)) {
            noiseFloor = (0.96f * noiseFloor + 0.04f * rms).coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)
        }

        val dynamicStartThreshold = maxOf(speechStartRmsThreshold, (noiseFloor * 1.8f).coerceAtMost(700.0f))
        val dynamicEndThreshold = maxOf(speechEndRmsThreshold, (noiseFloor * 1.25f).coerceAtMost(500.0f))

        val frameCopy = frame.copyOf(length)

        if (!isSpeechDetected) {
            // Buffer pre-roll frames
            preRollBuffer.addLast(frameCopy)
            currentPreRollBytes += length
            while (currentPreRollBytes > maxPreRollBytes && preRollBuffer.isNotEmpty()) {
                val removed = preRollBuffer.removeFirst()
                currentPreRollBytes -= removed.size
            }

            if (rms > dynamicStartThreshold) {
                if (speechStartTimeMs == 0L) {
                    speechStartTimeMs = now
                } else if (now - speechStartTimeMs >= minSpeechDurationMs) {
                    // Confirmed speech start
                    isSpeechDetected = true
                    lastSpeechTimeMs = now
                    silenceStartTimeMs = 0L
                    Log.d(TAG, "Speech START detected (RMS: %.1f, NoiseFloor: %.1f)".format(rms, noiseFloor))

                    // Flush pre-roll buffer into utterance stream
                    utteranceStream.reset()
                    for (bufferedFrame in preRollBuffer) {
                        utteranceStream.write(bufferedFrame)
                    }
                    preRollBuffer.clear()
                    currentPreRollBytes = 0

                    onSpeechStart?.invoke()
                }
            } else {
                speechStartTimeMs = 0L
            }
        } else {
            // Active speech state
            utteranceStream.write(frameCopy)
            onSpeechActiveFrame?.invoke(frameCopy)

            if (rms > dynamicEndThreshold) {
                lastSpeechTimeMs = now
                silenceStartTimeMs = 0L
            } else {
                if (silenceStartTimeMs == 0L) {
                    silenceStartTimeMs = now
                }
            }

            val currentUtteranceDurationMs = now - (speechStartTimeMs.takeIf { it > 0 } ?: now)
            val elapsedSilenceMs = if (silenceStartTimeMs > 0L) now - silenceStartTimeMs else 0L

            if (elapsedSilenceMs >= silenceDurationMs || currentUtteranceDurationMs >= MAX_UTTERANCE_DURATION_MS) {
                Log.d(TAG, "Speech END detected (Utterance duration: ${currentUtteranceDurationMs}ms, Silence: ${elapsedSilenceMs}ms)")
                val completedUtterance = utteranceStream.toByteArray()
                resetUtterance()

                if (completedUtterance.size >= (minSpeechDurationMs * bytesPerMs)) {
                    onSpeechEnd?.invoke(completedUtterance)
                }
            }
        }
    }

    @Synchronized
    fun reset() {
        resetUtterance()
        noiseFloor = 120.0f
    }

    private fun resetUtterance() {
        isSpeechDetected = false
        speechStartTimeMs = 0L
        lastSpeechTimeMs = 0L
        silenceStartTimeMs = 0L
        preRollBuffer.clear()
        currentPreRollBytes = 0
        utteranceStream.reset()
    }

    private fun calculateRms(pcmBytes: ByteArray, length: Int): Float {
        if (length < 2) return 0f
        var sumSquares = 0.0
        val sampleCount = length / 2

        val buffer = ByteBuffer.wrap(pcmBytes, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            val sample = buffer.short.toDouble()
            sumSquares += sample * sample
        }

        return sqrt(sumSquares / sampleCount).toFloat()
    }
}
