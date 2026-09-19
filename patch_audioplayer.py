with open('app/src/main/java/com/example/ai/GeminiAudioPlayer.kt', 'r') as f:
    content = f.read()

replacement = """package com.example.ai

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

class GeminiAudioPlayer(
    private val context: Context,
    private val onSpeakingStateChanged: ((Boolean) -> Unit)? = null
) {
    companion object {
        private const val TAG = "GeminiAudioPlayer"
    }

    private var mediaPlayer: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isPlaying = false
    
    private val audioQueue: Queue<ByteArray> = LinkedList()
    private var isProcessingQueue = false
    private var fileCounter = 0

    fun isSpeaking(): Boolean = isPlaying

    @Synchronized
    fun playAudioBytes(audioBytes: ByteArray, enqueue: Boolean = false, onComplete: (() -> Unit)? = null) {
        if (!enqueue) {
            stop()
        }
        audioQueue.offer(audioBytes)
        if (!isProcessingQueue) {
            processNextInQueue(onComplete)
        }
    }

    @Synchronized
    private fun processNextInQueue(onComplete: (() -> Unit)? = null) {
        val audioBytes = audioQueue.poll()
        if (audioBytes == null) {
            isProcessingQueue = false
            cleanupAndNotify(onComplete)
            return
        }
        isProcessingQueue = true
        
        try {
            fileCounter++
            val tempFile = File(context.cacheDir, "gemini_response_audio_${fileCounter}.wav")
            FileOutputStream(tempFile).use { it.write(audioBytes) }

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
                        }
                        mp.start()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start MediaPlayer", e)
                        processNextInQueue(onComplete)
                    }
                }
                setOnCompletionListener {
                    try {
                        it.release()
                    } catch (e: Exception) {}
                    mediaPlayer = null
                    processNextInQueue(onComplete)
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    processNextInQueue(onComplete)
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio setup error: ${e.message}", e)
            processNextInQueue(onComplete)
        }
    }

    @Synchronized
    fun stop() {
        audioQueue.clear()
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
"""

with open('app/src/main/java/com/example/ai/GeminiAudioPlayer.kt', 'w') as f:
    f.write(replacement)
print("AudioPlayer Patch applied")
