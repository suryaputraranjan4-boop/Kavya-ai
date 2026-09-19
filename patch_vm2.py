import re
with open("app/src/main/java/com/example/ai/KavyaVoiceEngine.kt", "r") as f:
    content = f.read()

target = "    fun stop() {"
replacement = """    fun setBaseVoiceProfile(pitch: Float, rate: Float) {
        // Now handled via AppPreferences directly in speak()
    }
    
    fun stop() {"""
    
content = content.replace(target, replacement)

with open("app/src/main/java/com/example/ai/KavyaVoiceEngine.kt", "w") as f:
    f.write(content)
