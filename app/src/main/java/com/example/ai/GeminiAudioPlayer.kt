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

    @Synchronized
    private fun processNextInQueue() {
        val queuedItem = audioQueue.poll()
        if (queuedItem == null) {
            isProcessingQueue = false
            cleanupAndNotify(null)
            return
        }
        isProcessingQueue = true
        
        try {
            fileCounter++
            val tempFile = File(context.cacheDir, "gemini_audio_${instanceId}_${fileCounter}.wav")
            FileOutputStream(tempFile).use { it.write(queuedItem.audioBytes) }

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(tempFile.absolutePath)
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
                        queuedItem.onComplete?.invoke()
                        processNextInQueue()
                    }
                }
                setOnCompletionListener {
                    try {
                        it.release()
                    } catch (e: Exception) {}
                    mediaPlayer = null
                    queuedItem.onComplete?.invoke()
                    processNextInQueue()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    queuedItem.onComplete?.invoke()
                    processNextInQueue()
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio setup error: ${e.message}", e)
            queuedItem.onComplete?.invoke()
            processNextInQueue()
        }
    }

    @Synchronized
    fun stop() {
        audioQueue.clear()
        isProcessingQueue = false
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
