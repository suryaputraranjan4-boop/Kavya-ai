with open('app/src/main/java/com/example/ai/KavyaAI.kt', 'r') as f:
    content = f.read()

content = content.replace("suspend fun chat(prompt: String, history: List<ChatMessage> = emptyList(), screenContext: String? = null): String {",
"suspend fun chat(prompt: String, history: List<ChatMessage> = emptyList(), screenContext: String? = null, memoryContext: String = \"\"): String {")

instructions = """        val screenInfoBlock = if (!screenContext.isNullOrBlank()) {
            \"\"\"
            CURRENT DEVICE SCREEN CONTEXT (Live UI elements currently visible on user's screen):
            $screenContext
            ------------------------------------
            \"\"\".trimIndent()
        } else {
            ""
        }"""

memory_block = """
        val memoryBlock = if (memoryContext.isNotBlank()) {
            \"\"\"
            USER MEMORY & PREFERENCES (Persistent):
            $memoryContext
            ------------------------------------
            \"\"\".trimIndent()
        } else {
            ""
        }
"""

content = content.replace(instructions, instructions + memory_block)
content = content.replace("$screenInfoBlock\n            CORE PERSONALITY & AESTHETIC:", "$screenInfoBlock\n            $memoryBlock\n            CORE PERSONALITY & AESTHETIC:")

# Also we need to add ACTION format for saving memory
target_actions = """              * <ACTION:OPEN_APP:AppName> (e.g., <ACTION:OPEN_APP:YouTube>)
              * <ACTION:SEARCH:Query> (e.g., <ACTION:SEARCH:Minecraft on YouTube>)"""

new_actions = """              * <ACTION:OPEN_APP:AppName> (e.g., <ACTION:OPEN_APP:YouTube>)
              * <ACTION:SEARCH:Query> (e.g., <ACTION:SEARCH:Minecraft on YouTube>)
              * <ACTION:SAVE_MEMORY:Key|Value> (e.g., <ACTION:SAVE_MEMORY:Language|Hinglish>)"""
content = content.replace(target_actions, new_actions)

with open('app/src/main/java/com/example/ai/KavyaAI.kt', 'w') as f:
    f.write(content)
print("AI Patch applied")
