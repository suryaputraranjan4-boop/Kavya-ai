with open("app/src/main/java/com/example/services/KavyaVoiceService.kt", "r") as f:
    content = f.read()

content = content.replace("memoryEngine = com.example.agent.MemoryEngine(this, aiClient)", "memoryEngine = com.example.agent.MemoryEngine(this)")

with open("app/src/main/java/com/example/services/KavyaVoiceService.kt", "w") as f:
    f.write(content)
