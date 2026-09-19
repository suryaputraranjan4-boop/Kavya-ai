with open('app/src/main/java/com/example/ai/KavyaAI.kt', 'r') as f:
    content = f.read()

# Remove the streamChat block that was appended at the end
start_idx = content.find("    suspend fun streamChat(")
if start_idx != -1:
    content = content[:start_idx]

# Remove the trailing brace from before we appended
last_brace = content.rfind("}")
if last_brace != -1:
    content = content[:last_brace] + "\n"

# Now append streamChat correctly and close the class
stream_code = """
    suspend fun streamChat(prompt: String, history: List<com.example.data.MessageEntity> = emptyList(), screenContext: String? = null, memoryContext: String = ""): kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.flow {
        val contents = mutableListOf<Content>()
        for (msg in history.takeLast(10)) {
            val role = if (msg.isUser) "user" else "model"
            if (msg.text.isNotBlank()) {
                contents.add(Content(role = role, parts = listOf(Part(text = msg.text.trim()))))
            }
        }
        contents.add(Content(role = "user", parts = listOf(Part(text = prompt.trim()))))
        
        val screenInfoBlock = if (!screenContext.isNullOrBlank()) {
            \"\"\"
            CURRENT DEVICE SCREEN CONTEXT:
            $screenContext
            \"\"\".trimIndent()
        } else ""

        val memoryBlock = if (memoryContext.isNotBlank()) {
            \"\"\"
            USER MEMORY & PREFERENCES:
            $memoryContext
            \"\"\".trimIndent()
        } else ""

        val sysText = \"\"\"
            UNIVERSAL APP AUTOMATION & AGENTIC BEHAVIOR:
            - You are a universal Android agent. You can automate ANY app (Instagram, WhatsApp, YouTube, Spotify, Chrome, etc.).
            - When asked to perform an action in an app (e.g. "Scroll reels on Instagram", "Message Rahul on WhatsApp"):
              Step 1: Use <ACTION:OPEN_APP:AppName> to launch the target app.
              Step 2: You will receive the new SCREEN CONTEXT.
              Step 3: Use <ACTION:UI_CLICK:ElementName>, <ACTION:UI_TYPE:Field:Text>, or <ACTION:UI_SCROLL:forward> based on the actual screen elements.
              Step 4: Continue until the goal is achieved.
            - If you are already in the correct app, skip OPEN_APP and directly interact with the UI.
            - NEVER pretend to have done something. ALWAYS rely on actual SCREEN CONTEXT to verify your actions.
            - Do NOT write long paragraphs when automating. Provide a short, sweet confirmation.
            $screenInfoBlock
            $memoryBlock
            CORE PERSONALITY & AESTHETIC:
            - Adorable, cheerful, affectionate, and genuinely empathetic anime female friend.
            - Speak naturally with popular Indian / Hindi slang and colloquial warmth.
            - Valid Actions: <ACTION:OPEN_APP:App>, <ACTION:SEARCH:Query>, <ACTION:UI_CLICK:Target>, <ACTION:UI_TYPE:Target:Text>, <ACTION:UI_SCROLL:Direction>, <ACTION:SAVE_MEMORY:Key|Value>.
        \"\"\".trimIndent()

        val requestObj = GenerateContentRequest(
            contents = contents,
            generationConfig = GenerationConfig(temperature = 0.7f),
            systemInstruction = Content(parts = listOf(Part(text = sysText)))
        )

        val apiKey = if (context != null) com.example.utils.AppPreferences.getEffectiveApiKey(context) else BuildConfig.GEMINI_API_KEY
        val json = kotlinx.serialization.json.Json { encodeDefaults = false; ignoreUnknownKeys = true }
        val requestJson = json.encodeToString(GenerateContentRequest.serializer(), requestObj)
        
        val request = okhttp3.Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse&key=$apiKey")
            .post(okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/json"), requestJson))
            .build()
            
        val okHttpClient = okhttp3.OkHttpClient.Builder()
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        var fullText = ""
        try {
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                emit("Error: ${response.code} ${response.message}")
                return@flow
            }
            response.body?.source()?.let { source ->
                while (!source.exhausted()) {
                    val line = source.readUtf8LineStrict()
                    if (line.startsWith("data: ")) {
                        val dataJson = line.substring(6)
                        if (dataJson.isNotBlank()) {
                            try {
                                val chunk = json.decodeFromString(GenerateContentResponse.serializer(), dataJson)
                                val textChunk = chunk.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: ""
                                if (textChunk.isNotEmpty()) {
                                    fullText += textChunk
                                    emit(textChunk)
                                }
                            } catch (e: Exception) {
                                // parse error on chunk
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            emit("Error: ${e.message}")
        }
    }
}
"""
content += stream_code

# Also fix the deprecated parse issue by using .toMediaType()
content = content.replace('okhttp3.MediaType.parse("application/json")', '"application/json".toMediaType()')

with open('app/src/main/java/com/example/ai/KavyaAI.kt', 'w') as f:
    f.write(content)
print("AI Final Patch applied")
