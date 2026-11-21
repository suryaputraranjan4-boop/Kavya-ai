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
    private val speechStartRmsThreshold: Float = 460.0f,
    private val speechEndRmsThreshold: Float = 250.0f,
    private val minSpeechDurationMs: Long = 260L,
    private val silenceDurationMs: Long = 850L,
    private val preRollMs: Long = 300L,
    private val postRollMs: Long = 200L
) {
    companion object {
        private const val TAG = "KavyaVAD"
        private const val MIN_NOISE_FLOOR = 50.0f
        private const val MAX_NOISE_FLOOR = 800.0f
        private const val MAX_UTTERANCE_DURATION_MS = 20000L // 20s hard cap
        private const val MIN_VOICED_FRAMES_FOR_UTTERANCE = 3
    }

    var timeProvider: () -> Long = System::currentTimeMillis

    var onSpeechStart: (() -> Unit)? = null
    var onSpeechActiveFrame: ((ByteArray) -> Unit)? = null
    var onSpeechEnd: ((ByteArray) -> Unit)? = null
    var onUserInterruption: (() -> Unit)? = null

    @Volatile private var isGated: Boolean = false
    @Volatile private var isAssistantSpeaking: Boolean = false
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

    data class FrameAcousticMetrics(
        val rms: Float,
        val peak: Float,
        val crestFactor: Float,
        val zeroCrossingRate: Float,
        val zeroMeanRms: Float,
        val speechBandEnergyRatio: Float,
        val isImpulsiveClickOrPop: Boolean,
        val isBroadbandNoise: Boolean,
        val isLowFreqWindOrRumble: Boolean,
        val isSpeechLike: Boolean
    )

    private var consecutiveSpeechLikeFrames: Int = 0
    private var voicedFramesInUtterance: Int = 0
    private var peakUtteranceRms: Float = 0f

    /**
     * Explicit user mute gate.
     */
    fun setGated(gated: Boolean) {
        if (this.isGated != gated) {
            this.isGated = gated
            if (gated) {
                resetUtterance()
            }
        }
    }

    /**
     * Informs VAD whether assistant is currently speaking for full-duplex interruption detection.
     */
    fun setAssistantSpeaking(speaking: Boolean) {
        this.isAssistantSpeaking = speaking
        if (!speaking && isSpeechDetected && silenceStartTimeMs > 0L) {
            // Re-evaluate silence window after assistant finishes speaking
            silenceStartTimeMs = timeProvider()
        }
    }

    fun isSpeechActive(): Boolean = isSpeechDetected

    fun getNoiseFloor(): Float = noiseFloor

    /**
     * Evaluates an assembled 16-bit PCM utterance and returns a real acoustic speech confidence [0f..1f].
     * Pure silence, all-zero buffers, or steady low-energy ambient hum score < 0.25f and are rejected
     * before ever hitting STT transcription.
     */
    fun computeUtteranceConfidence(pcmData: ByteArray): Float {
        if (pcmData.size < (minSpeechDurationMs * bytesPerMs)) return 0f
        val frameBytes = (sampleRate * 2 / 25).coerceAtLeast(640) // 40ms chunks
        var totalFrames = 0
        var speechLikeFrames = 0
        var maxRms = 0f
        var speechRmsSum = 0f
        val effectiveFloor = maxOf(noiseFloor, MIN_NOISE_FLOOR)
        var offset = 0
        while (offset + frameBytes <= pcmData.size) {
            val slice = pcmData.copyOfRange(offset, offset + frameBytes)
            val m = analyzeFrameAcoustics(slice, slice.size)
            totalFrames++
            if (m.rms > maxRms) maxRms = m.rms
            if (m.isSpeechLike && m.rms > maxOf(speechEndRmsThreshold, effectiveFloor * 1.45f)) {
                speechLikeFrames++
                speechRmsSum += m.rms
            }
            offset += frameBytes
        }
        if (totalFrames == 0 || speechLikeFrames < MIN_VOICED_FRAMES_FOR_UTTERANCE) return 0f
        val meanSpeechRms = speechRmsSum / speechLikeFrames
        val snr = meanSpeechRms / effectiveFloor
        val voicedRatio = speechLikeFrames.toFloat() / totalFrames.toFloat()
        if (maxRms < speechStartRmsThreshold * 0.9f || snr < 1.75f) return 0.15f
        val snrScore = ((snr - 1.5f) / 4.5f).coerceIn(0f, 1f)
        val ratioScore = (voicedRatio / 0.35f).coerceIn(0f, 1f)
        return (0.65f * snrScore + 0.35f * ratioScore).coerceIn(0f, 1f)
    }

    /**
     * Processes a single 16-bit PCM mono audio frame.
     */
    @Synchronized
    fun processFrame(frame: ByteArray, length: Int) {
        if (isGated || length <= 0) return

        val metrics = analyzeFrameAcoustics(frame, length)
        val rms = metrics.rms
        val now = timeProvider()

        // Adaptive noise floor update (only when no active speech is detected and assistant is silent)
        // Impulsive clicks/pops must never inflate the steady-state ambient noise floor.
        if (!isSpeechDetected && !isAssistantSpeaking && !metrics.isImpulsiveClickOrPop) {
            if (!metrics.isSpeechLike || rms < (noiseFloor * 1.8f)) {
                noiseFloor = if (rms <= noiseFloor) {
                    (0.95f * noiseFloor + 0.05f * rms).coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)
                } else {
                    val clampedTarget = minOf(rms, noiseFloor * 2.5f)
                    (0.985f * noiseFloor + 0.015f * clampedTarget).coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)
                }
            }
        }

        // When assistant is speaking, raise onset threshold to prevent speaker echo false-starts
        val startMultiplier = if (isAssistantSpeaking) 3.4f else 2.4f
        val dynamicStartThreshold = maxOf(
            speechStartRmsThreshold * (if (isAssistantSpeaking) 1.5f else 1.0f),
            noiseFloor * startMultiplier
        )
        val dynamicEndThreshold = maxOf(speechEndRmsThreshold, noiseFloor * 1.5f)

        val frameCopy = frame.copyOf(length)
        val isValidSpeechFrame = rms > dynamicStartThreshold && metrics.isSpeechLike

        if (!isSpeechDetected) {
            // Buffer pre-roll frames
            preRollBuffer.addLast(frameCopy)
            currentPreRollBytes += length
            while (currentPreRollBytes > maxPreRollBytes && preRollBuffer.isNotEmpty()) {
                val removed = preRollBuffer.removeFirst()
                currentPreRollBytes -= removed.size
            }

            if (isValidSpeechFrame) {
                consecutiveSpeechLikeFrames++
                val effectiveMinSpeechMs = if (isAssistantSpeaking) 160L else minSpeechDurationMs
                if (speechStartTimeMs == 0L) {
                    speechStartTimeMs = now
                } else if (consecutiveSpeechLikeFrames >= 3 && now - speechStartTimeMs >= effectiveMinSpeechMs) {
                    // Confirmed sustained human speech start
                    isSpeechDetected = true
                    lastSpeechTimeMs = now
                    silenceStartTimeMs = 0L
                    voicedFramesInUtterance = consecutiveSpeechLikeFrames
                    peakUtteranceRms = rms
                    Log.d(
                        TAG,
                        "Speech START detected (RMS=%.1f, Floor=%.1f, ZCR=%.3f, Crest=%.2f, AssistantSpeaking=%b)".format(
                            rms,
                            noiseFloor,
                            metrics.zeroCrossingRate,
                            metrics.crestFactor,
                            isAssistantSpeaking
                        )
                    )

                    // Flush pre-roll buffer into utterance stream
                    utteranceStream.reset()
                    for (bufferedFrame in preRollBuffer) {
                        utteranceStream.write(bufferedFrame)
                    }
                    preRollBuffer.clear()
                    currentPreRollBytes = 0

                    if (isAssistantSpeaking) {
                        Log.i(TAG, "User interrupted assistant speaking!")
                        onUserInterruption?.invoke()
                    }
                    onSpeechStart?.invoke()
                }
            } else {
                consecutiveSpeechLikeFrames = 0
                speechStartTimeMs = 0L
            }
        } else {
            // Active speech state
            utteranceStream.write(frameCopy)
            onSpeechActiveFrame?.invoke(frameCopy)
            if (rms > peakUtteranceRms) peakUtteranceRms = rms

            if (rms > dynamicEndThreshold && metrics.isSpeechLike) {
                voicedFramesInUtterance++
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
                val voicedCount = voicedFramesInUtterance
                val peakRms = peakUtteranceRms
                val completedUtterance = utteranceStream.toByteArray()
                resetUtterance()

                val hasSubstantialSpeech = completedUtterance.size >= (minSpeechDurationMs * bytesPerMs) &&
                    voicedCount >= MIN_VOICED_FRAMES_FOR_UTTERANCE &&
                    peakRms >= speechStartRmsThreshold
                if (hasSubstantialSpeech) {
                    Log.d(TAG, "Speech END accepted (duration=${currentUtteranceDurationMs}ms, voicedFrames=$voicedCount, peakRms=$peakRms)")
                    onSpeechEnd?.invoke(completedUtterance)
                } else {
                    Log.d(TAG, "Discarded low-energy/short non-speech segment (voicedFrames=$voicedCount, peakRms=$peakRms)")
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
        consecutiveSpeechLikeFrames = 0
        voicedFramesInUtterance = 0
        peakUtteranceRms = 0f
        speechStartTimeMs = 0L
        lastSpeechTimeMs = 0L
        silenceStartTimeMs = 0L
        preRollBuffer.clear()
        currentPreRollBytes = 0
        utteranceStream.reset()
    }

    /**
     * Computes RMS, Peak, Crest Factor, Zero-Crossing Rate (ZCR), and first-difference speech-band
     * energy ratio to reject keyboard clicks, mic pops, broadband white noise, and low-frequency wind.
     */
    fun analyzeFrameAcoustics(pcmBytes: ByteArray, length: Int = pcmBytes.size): FrameAcousticMetrics {
        val sampleCount = length / 2
        if (sampleCount <= 1) {
            return FrameAcousticMetrics(
                rms = 0f,
                peak = 0f,
                crestFactor = 0f,
                zeroCrossingRate = 0f,
                zeroMeanRms = 0f,
                speechBandEnergyRatio = 0f,
                isImpulsiveClickOrPop = false,
                isBroadbandNoise = false,
                isLowFreqWindOrRumble = false,
                isSpeechLike = false
            )
        }

        val buffer = ByteBuffer.wrap(pcmBytes, 0, sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        var sumSquares = 0.0
        var sumSamples = 0.0
        var peakAbs = 0f
        var zeroCrossings = 0
        var diffSquares = 0.0
        var prevSample = 0f

        val samples = FloatArray(sampleCount)
        for (i in 0 until sampleCount) {
            val s = buffer.short.toFloat()
            samples[i] = s
            sumSamples += s
            sumSquares += (s * s).toDouble()
            val absS = kotlin.math.abs(s)
            if (absS > peakAbs) peakAbs = absS
            if (i > 0) {
                if ((s >= 0f && prevSample < 0f) || (s < 0f && prevSample >= 0f)) {
                    zeroCrossings++
                }
                val diff = s - prevSample
                diffSquares += (diff * diff).toDouble()
            }
            prevSample = s
        }

        val rms = sqrt(sumSquares / sampleCount).toFloat()
        val mean = (sumSamples / sampleCount).toFloat()
        var acSumSquares = 0.0
        for (i in 0 until sampleCount) {
            val centered = samples[i] - mean
            acSumSquares += (centered * centered).toDouble()
        }
        val zeroMeanRms = sqrt(acSumSquares / sampleCount).toFloat()
        val crestFactor = if (rms > 1f) peakAbs / rms else 1f
        val zcr = zeroCrossings.toFloat() / (sampleCount - 1).coerceAtLeast(1)
        val diffRms = sqrt(diffSquares / (sampleCount - 1).coerceAtLeast(1)).toFloat()
        val speechBandEnergyRatio = if (zeroMeanRms > 1f) diffRms / zeroMeanRms else 1f

        // 1. Impulsive transient (keyboard click, tap, mic pop): extreme peak-to-RMS ratio
        val isClickOrPop = crestFactor > 4.2f

        // 2. Broadband unvoiced noise / static / fan hiss: ZCR > 0.45 (>3.6 kHz dominant energy)
        val isBroadbandNoise = zcr > 0.45f

        // 3. Low-frequency wind / breath rumble on mic: AC signal with very low ZCR (<0.018) and sluggish derivative
        val isLowFreqWind = zeroMeanRms > 50f && zcr < 0.018f && speechBandEnergyRatio < 0.12f

        val isSpeechLike = rms > 1f && !isClickOrPop && !isBroadbandNoise && !isLowFreqWind

        return FrameAcousticMetrics(
            rms = rms,
            peak = peakAbs,
            crestFactor = crestFactor,
            zeroCrossingRate = zcr,
            zeroMeanRms = zeroMeanRms,
            speechBandEnergyRatio = speechBandEnergyRatio,
            isImpulsiveClickOrPop = isClickOrPop,
            isBroadbandNoise = isBroadbandNoise,
            isLowFreqWindOrRumble = isLowFreqWind,
            isSpeechLike = isSpeechLike
        )
    }
}
