with open("app/src/main/java/com/example/ai/KavyaAI.kt", "r") as f:
    content = f.read()

target1 = """    suspend fun chat(
        prompt: String,
        history: List<MessageEntity> = emptyList(),
        screenContext: String? = null, 
        memoryContext: String = "",
        isProactiveMode: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        val requestId = java.util.UUID.randomUUID().toString()"""

replacement1 = """    suspend fun chat(
        prompt: String,
        history: List<MessageEntity> = emptyList(),
        screenContext: String? = null, 
        memoryContext: String = "",
        isProactiveMode: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        val requestId = (prompt.hashCode() * 31 + history.size).toString()"""

content = content.replace(target1, replacement1)

target2 = """    suspend fun streamChat(
        prompt: String, 
        history: List<com.example.data.MessageEntity> = emptyList(), 
        screenContext: String? = null, 
        memoryContext: String = "",
        isProactiveMode: Boolean = true
    ): kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.flow {
        val requestId = java.util.UUID.randomUUID().toString()"""
        
replacement2 = """    suspend fun streamChat(
        prompt: String, 
        history: List<com.example.data.MessageEntity> = emptyList(), 
        screenContext: String? = null, 
        memoryContext: String = "",
        isProactiveMode: Boolean = true
    ): kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.flow {
        val requestId = (prompt.hashCode() * 31 + history.size).toString()"""

content = content.replace(target2, replacement2)

target3 = """    suspend fun generateSpeechAudio(
        text: String,
        voiceName: String = "Aoede",
        sentiment: String = "Neutral",
        pitchMultiplier: Float = 1.0f,
        speedMultiplier: Float = 1.0f
    ): ByteArray? = withContext(Dispatchers.IO) {
        val requestId = java.util.UUID.randomUUID().toString()"""

replacement3 = """    suspend fun generateSpeechAudio(
        text: String,
        voiceName: String = "Aoede",
        sentiment: String = "Neutral",
        pitchMultiplier: Float = 1.0f,
        speedMultiplier: Float = 1.0f
    ): ByteArray? = withContext(Dispatchers.IO) {
        val requestId = "audio_" + (text.hashCode() * 31 + voiceName.hashCode()).toString()"""
        
content = content.replace(target3, replacement3)

with open("app/src/main/java/com/example/ai/KavyaAI.kt", "w") as f:
    f.write(content)
