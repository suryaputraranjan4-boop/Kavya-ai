package com.example.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.LinkedList
import java.util.Queue

data class QueuedAudio(
    val audioBytes: ByteArray,
    val text: String? = null,
    val onComplete: (() -> Unit)? = null
)

class GeminiAudioPlayer(
    private val context: Context,
    private val onSpeakingStateChanged: ((Boolean) -> Unit)? = null,
    private val onAudioChunkStarted: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "GeminiAudioPlayer"
    }

    private var mediaPlayer: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isPlaying = false
    
    private val audioQueue: Queue<QueuedAudio> = LinkedList()
    private var isProcessingQueue = false
    private var fileCounter = 0
    private val instanceId = java.util.UUID.randomUUID().toString().take(8)

    fun isSpeaking(): Boolean = isPlaying

    fun notifySpeaking(speaking: Boolean) {
        mainHandler.post {
            this.isPlaying = speaking
            onSpeakingStateChanged?.invoke(speaking)
        }
    }

    fun notifyCaption(caption: String) {
        mainHandler.post {
            if (caption.isNotBlank()) {
                onAudioChunkStarted?.invoke(caption)
            }
        }
    }

    @Synchronized
    fun playAudioBytes(
        audioBytes: ByteArray, 
        text: String? = null,
        enqueue: Boolean = false, 
        onComplete: (() -> Unit)? = null
    ) {
        if (!enqueue) {
            stop()
        }
        audioQueue.offer(QueuedAudio(audioBytes, text, onComplete))
        if (!isProcessingQueue) {
            processNextInQueue()
        }
    }

    fun playWav(wavBytes: ByteArray, text: String? = null, enqueue: Boolean = false, onComplete: (() -> Unit)? = null) {
        playAudioBytes(wavBytes, text, enqueue, onComplete)
    }

    fun playPcm(pcmBytes: ByteArray, sampleRate: Int = 24000, text: String? = null, enqueue: Boolean = false, onComplete: (() -> Unit)? = null) {
        if (pcmBytes.isEmpty()) {
            onComplete?.invoke()
            return
        }
        val header = createWavHeader(pcmBytes.size, sampleRate)
        val wavBytes = ByteArray(header.size + pcmBytes.size)
        System.arraycopy(header, 0, wavBytes, 0, header.size)
        System.arraycopy(pcmBytes, 0, wavBytes, header.size, pcmBytes.size)
        playAudioBytes(wavBytes, text, enqueue, onComplete)
    }

    private fun createWavHeader(pcmLength: Int, sampleRate: Int): ByteArray {
        val totalDataLen = pcmLength + 36
        val numChannels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = numChannels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = blockAlign.toByte()
        header[33] = 0
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (pcmLength and 0xff).toByte()
        header[41] = ((pcmLength shr 8) and 0xff).toByte()
        header[42] = ((pcmLength shr 16) and 0xff).toByte()
        header[43] = ((pcmLength shr 24) and 0xff).toByte()
        return header
    }

    private var currentTempFile: File? = null

    init {
        cleanupOldTempFiles()
    }

    private fun cleanupOldTempFiles() {
        try {
            val cacheFiles = context.cacheDir.listFiles { _, name -> name.startsWith("gemini_audio_") && name.endsWith(".wav") }
            cacheFiles?.forEach { file ->
                // Clean up files created in previous sessions or older than 10 minutes
                if (System.currentTimeMillis() - file.lastModified() > 10 * 60 * 1000 || !file.name.contains(instanceId)) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed cleaning cache files: ${e.message}")
        }
    }

    @Synchronized
    private fun processNextInQueue() {
        val queuedItem = audioQueue.poll()
        if (queuedItem == null) {
            isProcessingQueue = false
            cleanupAndNotify(null)
            return
        }
        isProcessingQueue = true
        
        var tempFile: File? = null
        try {
            fileCounter++
            tempFile = File(context.cacheDir, "gemini_audio_${instanceId}_${fileCounter}.wav")
            currentTempFile = tempFile
            FileOutputStream(tempFile).use { it.write(queuedItem.audioBytes) }

            val fileToDelete = tempFile
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(fileToDelete.absolutePath)
                setOnPreparedListener { mp ->
                    try {
                        mainHandler.post {
                            if (!this@GeminiAudioPlayer.isPlaying) {
                                this@GeminiAudioPlayer.isPlaying = true
                                onSpeakingStateChanged?.invoke(true)
                            }
                            queuedItem.text?.let { chunkText ->
                                if (chunkText.isNotBlank()) {
                                    onAudioChunkStarted?.invoke(chunkText)
                                }
                            }
                        }
                        mp.start()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start MediaPlayer", e)
                        try { mp.release() } catch (_: Exception) {}
                        if (mediaPlayer === mp) mediaPlayer = null
                        try { fileToDelete.delete() } catch (_: Exception) {}
                        if (currentTempFile == fileToDelete) currentTempFile = null
                        queuedItem.onComplete?.invoke()
                        processNextInQueue()
                    }
                }
                setOnCompletionListener { mp ->
                    try { mp.release() } catch (_: Exception) {}
                    if (mediaPlayer === mp) mediaPlayer = null
                    try { fileToDelete.delete() } catch (_: Exception) {}
                    if (currentTempFile == fileToDelete) currentTempFile = null
                    queuedItem.onComplete?.invoke()
                    processNextInQueue()
                }
                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    try { mp.reset(); mp.release() } catch (_: Exception) {}
                    if (mediaPlayer === mp) mediaPlayer = null
                    try { fileToDelete.delete() } catch (_: Exception) {}
                    if (currentTempFile == fileToDelete) currentTempFile = null
                    queuedItem.onComplete?.invoke()
                    processNextInQueue()
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio setup error: ${e.message}", e)
            try { tempFile?.delete() } catch (_: Exception) {}
            if (currentTempFile == tempFile) currentTempFile = null
            queuedItem.onComplete?.invoke()
            processNextInQueue()
        }
    }

    @Synchronized
    fun stop() {
        audioQueue.clear()
        isProcessingQueue = false
        try {
            currentTempFile?.delete()
            currentTempFile = null
        } catch (_: Exception) {}
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer?.isPlaying == true) {
                    mediaPlayer?.stop()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping player: ${e.message}")
            } finally {
                cleanupAndNotify(null)
            }
        } else {
            cleanupAndNotify(null)
        }
    }

    private fun cleanupAndNotify(onComplete: (() -> Unit)?) {
        try {
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing player: ${e.message}")
        } finally {
            if (isPlaying && audioQueue.isEmpty()) {
                isPlaying = false
                mainHandler.post { 
                    onSpeakingStateChanged?.invoke(false) 
                    onComplete?.invoke()
                }
            } else {
                mainHandler.post { onComplete?.invoke() }
            }
        }
    }
}
