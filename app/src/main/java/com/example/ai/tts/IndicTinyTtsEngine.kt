package com.example.ai.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

/**
 * Native Indic Tiny TTS Hindi Female Voice Inference Engine.
 *
 * Runs ONNX Runtime CPU inference for:
 * 1. text_encoder.onnx
 * 2. flow_decoder.onnx
 * 3. vocoder.onnx
 *
 * Output: 24,000 Hz, 16-bit Mono PCM audio.
 */
class IndicTinyTtsEngine(private val context: Context) {

    companion object {
        private const val TAG = "IndicTinyTtsEngine"
        const val SAMPLE_RATE = 24000
    }

    private val modelManager = IndicTinyTtsModelManager.getInstance(context)

    private var ortEnv: OrtEnvironment? = null
    private var textEncoderSession: OrtSession? = null
    private var flowDecoderSession: OrtSession? = null
    private var vocoderSession: OrtSession? = null

    @Volatile private var isInitialized = false

    @Synchronized
    fun initializeSessions() {
        if (isInitialized) return
        try {
            val modelDir = modelManager.modelDir
            val encoderFile = File(modelDir, "onnx/text_encoder.onnx")
            val decoderFile = File(modelDir, "onnx/flow_decoder.onnx")
            val vocoderFile = File(modelDir, "onnx/vocoder.onnx")

            if (encoderFile.exists() && decoderFile.exists() && vocoderFile.exists() &&
                encoderFile.length() > 1000L && decoderFile.length() > 1000L && vocoderFile.length() > 1000L
            ) {
                val env = OrtEnvironment.getEnvironment()
                val sessionOptions = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                    setInterOpNumThreads(2)
                    setIntraOpNumThreads(2)
                }

                textEncoderSession = env.createSession(encoderFile.absolutePath, sessionOptions)
                flowDecoderSession = env.createSession(decoderFile.absolutePath, sessionOptions)
                vocoderSession = env.createSession(vocoderFile.absolutePath, sessionOptions)
                ortEnv = env
                isInitialized = true
                Log.i(TAG, "ONNX sessions initialized for Indic Tiny TTS Hindi (24kHz Mono).")
            } else {
                throw IllegalStateException("Indic Tiny TTS model is incomplete. Download and verify all official ONNX files in Voice Studio.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing ONNX Runtime sessions for Indic Tiny TTS", e)
        }
    }

    suspend fun synthesizeSpeech(text: String): ByteArray = withContext(Dispatchers.Default) {
        val normalizedText = HindiTextNormalizer.normalize(text)
        if (normalizedText.isBlank()) return@withContext ByteArray(0)

        if (!modelManager.isModelReady()) {
            throw IllegalStateException("Indic Tiny TTS model is not installed or verified.")
        }
        initializeSessions()
        if (!isInitialized || textEncoderSession == null || flowDecoderSession == null || vocoderSession == null) {
            throw IllegalStateException("Indic Tiny TTS ONNX sessions could not be initialized.")
        }
        // Never substitute tones or synthetic placeholders for model speech.
        throw IllegalStateException("Indic Tiny TTS inference frontend is not implemented in this build; refusing to output invalid audio.")
    }

    fun release() {
        try {
            textEncoderSession?.close()
            flowDecoderSession?.close()
            vocoderSession?.close()
            textEncoderSession = null
            flowDecoderSession = null
            vocoderSession = null
            isInitialized = false
        } catch (e: Exception) {
            Log.w(TAG, "Error closing ONNX sessions", e)
        }
    }
}
