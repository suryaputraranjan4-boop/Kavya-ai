package com.example.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.LinkedList
import java.util.Queue
import java.util.concurrent.CopyOnWriteArrayList

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
    @Volatile private var isPlaying = false
    private val speakingStateListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val captionListeners = CopyOnWriteArrayList<(String) -> Unit>()
    
    private val audioQueue: Queue<QueuedAudio> = LinkedList()
    private var isProcessingQueue = false
    @Volatile private var playbackGeneration = 0L
    private var fileCounter = 0
    private val instanceId = java.util.UUID.randomUUID().toString().take(8)

    fun isSpeaking(): Boolean = isPlaying

    fun addSpeakingStateListener(listener: (Boolean) -> Unit) {
        speakingStateListeners.addIfAbsent(listener)
    }

    fun removeSpeakingStateListener(listener: (Boolean) -> Unit) {
        speakingStateListeners.remove(listener)
    }

    fun addCaptionListener(listener: (String) -> Unit) {
        captionListeners.addIfAbsent(listener)
    }

    fun removeCaptionListener(listener: (String) -> Unit) {
        captionListeners.remove(listener)
    }

    private fun dispatchSpeakingState(speaking: Boolean) {
        onSpeakingStateChanged?.invoke(speaking)
        speakingStateListeners.forEach { listener ->
            try { listener(speaking) } catch (e: Exception) {
                Log.w(TAG, "Speaking-state listener failed: " + e.message)
            }
        }
    }

    private fun dispatchCaption(caption: String) {
        if (caption.isBlank()) return
        onAudioChunkStarted?.invoke(caption)
        captionListeners.forEach { listener ->
            try { listener(caption) } catch (e: Exception) {
                Log.w(TAG, "Caption listener failed: " + e.message)
            }
        }
    }

    fun notifySpeaking(speaking: Boolean) {
        mainHandler.post {
            this.isPlaying = speaking
            dispatchSpeakingState(speaking)
        }
    }

    fun notifyCaption(caption: String) {
        mainHandler.post {
            dispatchCaption(caption)
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

    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
    private var activeFocusRequest: android.media.AudioFocusRequest? = null
    private val focusChangeListener = android.media.AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            android.media.AudioManager.AUDIOFOCUS_LOSS,
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> stop()
        }
    }

    private fun requestSpeechFocus(): Boolean {
        val am = audioManager ?: return true
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val req = activeFocusRequest ?: android.media.AudioFocusRequest.Builder(
                    android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                ).setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .build()
                ).setOnAudioFocusChangeListener(focusChangeListener).build().also { activeFocusRequest = it }
                am.requestAudioFocus(req) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    focusChangeListener,
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                ) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (_: Throwable) {
            true
        }
    }

    private fun abandonSpeechFocus() {
        val am = audioManager ?: return
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                activeFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(focusChangeListener)
            }
        } catch (_: Throwable) {}
    }

    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var audioTrackSampleRate: Int = 0
    private val pendingPcmChunks = java.util.concurrent.atomic.AtomicInteger(0)
    private val pcmExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun hasContainerMagic(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        val b3 = bytes[3].toInt() and 0xFF
        val isRiff = b0 == 'R'.code && b1 == 'I'.code && b2 == 'F'.code && b3 == 'F'.code
        val isOgg = b0 == 'O'.code && b1 == 'g'.code && b2 == 'g'.code && b3 == 'S'.code
        val isFlac = b0 == 'f'.code && b1 == 'L'.code && b2 == 'a'.code && b3 == 'C'.code
        val isId3 = b0 == 'I'.code && b1 == 'D'.code && b2 == '3'.code
        val isMp3Sync = b0 == 0xFF && (b1 and 0xE0) == 0xE0
        return isRiff || isOgg || isFlac || isId3 || isMp3Sync
    }

    fun playWav(wavBytes: ByteArray, text: String? = null, enqueue: Boolean = false, onComplete: (() -> Unit)? = null) {
        if (wavBytes.isEmpty()) {
            onComplete?.invoke()
            return
        }
        val playable = if (hasContainerMagic(wavBytes)) {
            wavBytes
        } else {
            val header = createWavHeader(wavBytes.size, sampleRate = 24000)
            ByteArray(header.size + wavBytes.size).also { out ->
                System.arraycopy(header, 0, out, 0, header.size)
                System.arraycopy(wavBytes, 0, out, header.size, wavBytes.size)
            }
        }
        playAudioBytes(playable, text, enqueue, onComplete)
    }

    fun playPcm(pcmBytes: ByteArray, sampleRate: Int = 24000, text: String? = null, enqueue: Boolean = false, onComplete: (() -> Unit)? = null) {
        if (pcmBytes.isEmpty()) {
            onComplete?.invoke()
            return
        }
        playPcmStreaming(pcmBytes, sampleRate, text, enqueue, onComplete)
    }

    @Synchronized
    private fun getOrCreateAudioTrack(sampleRate: Int, minChunkSize: Int): AudioTrack {
        val existing = audioTrack
        if (existing != null && existing.state == AudioTrack.STATE_INITIALIZED && audioTrackSampleRate == sampleRate) {
            if (existing.playState != AudioTrack.PLAYSTATE_PLAYING) {
                existing.play()
            }
            return existing
        }
        try {
            existing?.release()
        } catch (_: Exception) {}

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val bufSize = maxOf(minBufSize * 2, minChunkSize.coerceAtLeast(8192))

        val track = AudioTrack(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            bufSize,
            AudioTrack.MODE_STREAM,
            android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        track.play()
        audioTrack = track
        audioTrackSampleRate = sampleRate
        return track
    }

    @Synchronized
    private fun playPcmStreaming(
        pcmBytes: ByteArray,
        sampleRate: Int,
        text: String?,
        enqueue: Boolean,
        onComplete: (() -> Unit)?
    ) {
        if (!enqueue) {
            stop()
        }
        requestSpeechFocus()
        pendingPcmChunks.incrementAndGet()

        val currentGen = playbackGeneration
        pcmExecutor.execute {
            if (currentGen != playbackGeneration) return@execute
            try {
                text?.let { notifyCaption(it) }
                notifySpeaking(true)
                val track = getOrCreateAudioTrack(sampleRate, pcmBytes.size)
                track.write(pcmBytes, 0, pcmBytes.size, AudioTrack.WRITE_BLOCKING)
                val durationMs = ((pcmBytes.size.toLong() * 1000L) / (sampleRate * 2)).coerceAtLeast(40L)
                // Sleep cleanly on single-thread executor until buffer finishes playing unless cancelled
                if (currentGen == playbackGeneration && pendingPcmChunks.get() == 1) {
                    try { Thread.sleep(durationMs.coerceAtMost(8_000L)) } catch (_: InterruptedException) {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack streaming error", e)
            } finally {
                if (currentGen == playbackGeneration) {
                    val remaining = pendingPcmChunks.updateAndGet { prev -> (prev - 1).coerceAtLeast(0) }
                    if (remaining == 0 && audioQueue.isEmpty() && !isProcessingQueue) {
                        abandonSpeechFocus()
                        notifySpeaking(false)
                    }
                    onComplete?.invoke()
                }
            }
        }
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
        val generation = playbackGeneration

        var tempFile: File? = null
        try {
            fileCounter++
            tempFile = File(context.cacheDir, "gemini_audio_${instanceId}_${fileCounter}.wav")
            currentTempFile = tempFile
            FileOutputStream(tempFile).use { it.write(queuedItem.audioBytes) }

            requestSpeechFocus()
            val fileToDelete = tempFile
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .build()
                )
                setDataSource(fileToDelete.absolutePath)
                setOnPreparedListener { mp ->
                    mainHandler.post {
                        if (generation != playbackGeneration) {
                            try { mp.release() } catch (_: Exception) {}
                            try { fileToDelete.delete() } catch (_: Exception) {}
                            if (currentTempFile == fileToDelete) currentTempFile = null
                            return@post
                        }
                        try {
                            if (!this@GeminiAudioPlayer.isPlaying) {
                                this@GeminiAudioPlayer.isPlaying = true
                                dispatchSpeakingState(true)
                            }
                            queuedItem.text?.let { chunkText ->
                                if (chunkText.isNotBlank()) dispatchCaption(chunkText)
                            }
                            mp.start()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start MediaPlayer", e)
                            try { mp.release() } catch (_: Exception) {}
                            if (mediaPlayer === mp) mediaPlayer = null
                            try { fileToDelete.delete() } catch (_: Exception) {}
                            if (currentTempFile == fileToDelete) currentTempFile = null
                            if (generation == playbackGeneration) {
                                queuedItem.onComplete?.invoke()
                                processNextInQueue()
                            }
                        }
                    }
                }
                setOnCompletionListener { mp ->
                    try { mp.release() } catch (_: Exception) {}
                    if (mediaPlayer === mp) mediaPlayer = null
                    try { fileToDelete.delete() } catch (_: Exception) {}
                    if (currentTempFile == fileToDelete) currentTempFile = null
                    if (generation == playbackGeneration) {
                        queuedItem.onComplete?.invoke()
                        processNextInQueue()
                    }
                }
                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    try { mp.reset(); mp.release() } catch (_: Exception) {}
                    if (mediaPlayer === mp) mediaPlayer = null
                    try { fileToDelete.delete() } catch (_: Exception) {}
                    if (currentTempFile == fileToDelete) currentTempFile = null
                    if (generation == playbackGeneration) {
                        queuedItem.onComplete?.invoke()
                        processNextInQueue()
                    }
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
        playbackGeneration++
        pendingPcmChunks.set(0)
        audioQueue.clear()
        isProcessingQueue = false
        abandonSpeechFocus()
        try {
            audioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                }
                track.release()
            }
            audioTrack = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioTrack: ${e.message}")
        }
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
            audioTrack?.release()
            audioTrack = null
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing player: ${e.message}")
        } finally {
            if (pendingPcmChunks.get() == 0 && audioQueue.isEmpty() && !isProcessingQueue) {
                abandonSpeechFocus()
            }
            if (isPlaying && audioQueue.isEmpty()) {
                isPlaying = false
                mainHandler.post { 
                    dispatchSpeakingState(false) 
                    onComplete?.invoke()
                }
            } else {
                mainHandler.post { onComplete?.invoke() }
            }
        }
    }
}
