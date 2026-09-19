import re
with open("app/src/main/java/com/example/services/KavyaVoiceService.kt", "r") as f:
    content = f.read()

content = content.replace("voiceManager", "voiceEngine")
content = content.replace("com.example.ai.VoiceManager", "com.example.ai.KavyaVoiceEngine")

with open("app/src/main/java/com/example/services/KavyaVoiceService.kt", "w") as f:
    f.write(content)
