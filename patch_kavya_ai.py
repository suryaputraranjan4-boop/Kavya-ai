import re

with open("app/src/main/java/com/example/ai/KavyaAI.kt", "r") as f:
    content = f.read()

target = """        val maxRetries = 3
        
        for (model in TEXT_MODEL_TIERS) {"""
replacement = """        val maxRetries = 3
        var lastError: Exception? = null
        
        for (model in TEXT_MODEL_TIERS) {"""

content = content.replace(target, replacement)

target2 = """        val maxRetries = 3
        
        for (model in AUDIO_MODEL_TIERS) {"""
replacement2 = """        val maxRetries = 3
        var lastError: Exception? = null
        
        for (model in AUDIO_MODEL_TIERS) {"""

content = content.replace(target2, replacement2)

with open("app/src/main/java/com/example/ai/KavyaAI.kt", "w") as f:
    f.write(content)
