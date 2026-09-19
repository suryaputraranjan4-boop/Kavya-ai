import re

with open("app/src/main/java/com/example/ai/GeminiAudioPlayer.kt", "r") as f:
    content = f.read()

target = """data class QueuedAudio(
    val audioBytes: ByteArray,
    val text: String? = null
)"""

replacement = """data class QueuedAudio(
    val audioBytes: ByteArray,
    val text: String? = null,
    val onComplete: (() -> Unit)? = null
)"""

content = content.replace(target, replacement)

target2 = """    @Synchronized
    fun playAudioBytes(
        audioBytes: ByteArray, 
        text: String? = null,
        enqueue: Boolean = false, 
        onComplete: (() -> Unit)? = null
    ) {
        if (!enqueue) {
            stop()
        }
        audioQueue.offer(QueuedAudio(audioBytes, text))
        if (!isProcessingQueue) {
            processNextInQueue(onComplete)
        }
    }"""

replacement2 = """    @Synchronized
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
    }"""

content = content.replace(target2, replacement2)

target3 = """    @Synchronized
    private fun processNextInQueue(onComplete: (() -> Unit)? = null) {"""

replacement3 = """    @Synchronized
    private fun processNextInQueue() {"""

content = content.replace(target3, replacement3)

target4 = """        val queuedItem = audioQueue.poll()
        if (queuedItem == null) {
            isProcessingQueue = false
            cleanupAndNotify(onComplete)
            return
        }"""

replacement4 = """        val queuedItem = audioQueue.poll()
        if (queuedItem == null) {
            isProcessingQueue = false
            cleanupAndNotify(null)
            return
        }"""

content = content.replace(target4, replacement4)

target5 = """                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start MediaPlayer", e)
                        processNextInQueue(onComplete)
                    }"""

replacement5 = """                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start MediaPlayer", e)
                        queuedItem.onComplete?.invoke()
                        processNextInQueue()
                    }"""

content = content.replace(target5, replacement5)

target6 = """                setOnCompletionListener {
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
                }"""

replacement6 = """                setOnCompletionListener {
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
                }"""

content = content.replace(target6, replacement6)

target7 = """        } catch (e: Exception) {
            Log.e(TAG, "Audio setup error: ${e.message}", e)
            processNextInQueue(onComplete)
        }"""

replacement7 = """        } catch (e: Exception) {
            Log.e(TAG, "Audio setup error: ${e.message}", e)
            queuedItem.onComplete?.invoke()
            processNextInQueue()
        }"""

content = content.replace(target7, replacement7)

with open("app/src/main/java/com/example/ai/GeminiAudioPlayer.kt", "w") as f:
    f.write(content)
