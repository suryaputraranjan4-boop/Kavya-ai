with open("app/src/main/java/com/example/services/KavyaVoiceService.kt", "r") as f:
    content = f.read()

target = """                val response = aiClient.chat(finalQuery, emptyList(), screenContext, isProactiveMode = isCompanionSessionActive)"""
replacement = """                val response = aiClient.chat(finalQuery, emptyList(), screenContext, isProactiveMode = false)"""

content = content.replace(target, replacement)
with open("app/src/main/java/com/example/services/KavyaVoiceService.kt", "w") as f:
    f.write(content)
