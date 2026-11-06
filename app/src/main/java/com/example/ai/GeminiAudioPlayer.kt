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
    /** Secondary observer used by KavyaVoiceEngine to track queue-drained state. */
    @Volatile
    var onPlaybackStateChanged: ((Boolean) -> Unit)? = null
    companion object {
        private const val TAG = "GeminiAudioPlayer"
    }

    private var mediaPlayer: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var isPlaying = false
    
    private val audioQueue: Queue<QueuedAudio> = LinkedList()
    private var isProcessingQueue = false
    @Volatile private var playbackGeneration = 0L
    private var activeTempFile: File? = null
    private var fileCounter = 0
    private val instanceId = java.util.UUID.randomUUID().toString().take(8)

    fun isSpeaking(): Boolean = isPlaying

    /** True while the current item is playing or any queued item is being prepared. */
    @Synchronized
    fun hasPendingPlayback(): Boolean = isProcessingQueue || audioQueue.isNotEmpty() || isPlaying

    fun notifySpeaking(speaking: Boolean) {
        mainHandler.post {
            this.isPlaying = speaking
            onSpeakingStateChanged?.invoke(speaking)
            onPlaybackStateChanged?.invoke(speaking)
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
            // Replacing one response with another must not publish a false
            // speaking-state edge; that briefly reopens the mic and can cause
            // Android's audible recognizer-start cue on every assistant turn.
            stop(notifySpeakingState = false)
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
        val generation = playbackGeneration

        var createdPlayer: MediaPlayer? = null
        try {
            fileCounter++
            val tempFile = File(context.cacheDir, "gemini_audio_${instanceId}_${fileCounter}.wav")
            activeTempFile = tempFile
            FileOutputStream(tempFile).use { it.write(queuedItem.audioBytes) }

            val player = MediaPlayer()
            createdPlayer = player
            mediaPlayer = player
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            player.setDataSource(tempFile.absolutePath)
            player.setOnPreparedListener { mp ->
                try {
                    // Serialize start against stop(): a late prepare callback must
                    // never restart audio after the user has interrupted playback.
                    synchronized(this@GeminiAudioPlayer) {
                        if (generation != playbackGeneration || mediaPlayer !== mp) {
                            try { mp.release() } catch (_: Exception) {}
                            tempFile.delete()
                            return@setOnPreparedListener
                        }
                        mp.start()
                    }
                    mainHandler.post {
                        if (generation == playbackGeneration) {
                            if (!this@GeminiAudioPlayer.isPlaying) {
                                this@GeminiAudioPlayer.isPlaying = true
                                onSpeakingStateChanged?.invoke(true)
                                onPlaybackStateChanged?.invoke(true)
                            }
                            queuedItem.text?.takeIf { it.isNotBlank() }?.let { onAudioChunkStarted?.invoke(it) }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start MediaPlayer", e)
                    finishCurrent(mp, tempFile, queuedItem, generation)
                }
            }
            player.setOnCompletionListener { mp ->
                finishCurrent(mp, tempFile, queuedItem, generation)
            }
            player.setOnErrorListener { mp, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                finishCurrent(mp, tempFile, queuedItem, generation)
                true
            }
            player.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "Audio setup error: ${e.message}", e)
            try { createdPlayer?.release() } catch (_: Exception) {}
            activeTempFile?.delete()
            activeTempFile = null
            mediaPlayer = null
            runCatching { queuedItem.onComplete?.invoke() }
            processNextInQueue()
        }
    }

    @Synchronized
    fun stop(notifySpeakingState: Boolean = true) {
        // Invalidate callbacks from the previous player before releasing it.
        playbackGeneration++
        audioQueue.clear()
        isProcessingQueue = false

        val player = mediaPlayer
        mediaPlayer = null
        activeTempFile?.delete()
        activeTempFile = null

        try {
            if (player?.isPlaying == true) player.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping player: ${e.message}")
        } finally {
            try { player?.release() } catch (_: Exception) {}
            cleanupAndNotify(null, notifySpeakingState)
        }
    }

    @Synchronized
    private fun finishCurrent(
        player: MediaPlayer,
        tempFile: File,
        item: QueuedAudio,
        generation: Long
    ) {
        if (generation != playbackGeneration || mediaPlayer !== player) {
            try { player.release() } catch (_: Exception) {}
            tempFile.delete()
            return
        }

        try { player.release() } catch (_: Exception) {}
        mediaPlayer = null
        if (activeTempFile === tempFile) activeTempFile = null
        tempFile.delete()
        runCatching { item.onComplete?.invoke() }
        processNextInQueue()
    }

    private fun cleanupAndNotify(
        onComplete: (() -> Unit)?,
        notifySpeakingState: Boolean = true
    ) {
        try {
            mediaPlayer?.release()
            mediaPlayer = null
            activeTempFile?.delete()
            activeTempFile = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing player: ${e.message}")
        } finally {
            if (audioQueue.isEmpty()) {
                // Also publish IDLE after setup failures that occurred before
                // MediaPlayer reached its prepared/playing callback.
                isPlaying = false
                mainHandler.post {
                    if (notifySpeakingState) {
                        onSpeakingStateChanged?.invoke(false)
                        onPlaybackStateChanged?.invoke(false)
                    }
                    onComplete?.invoke()
                }
            } else {
                mainHandler.post { onComplete?.invoke() }
            }
        }
    }
}
