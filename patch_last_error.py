import re

with open("app/src/main/java/com/example/ai/KavyaAI.kt", "r") as f:
    content = f.read()

target = """        try {
            val apiKey = if (context != null) {"""
            
replacement = """        var lastError: Exception? = null
        try {
            val apiKey = if (context != null) {"""

content = content.replace(target, replacement)

target2 = """        // Try supported model tiers with exponential backoff for 429 rate limit errors
        var lastError: Exception? = null"""
        
replacement2 = """        // Try supported model tiers with exponential backoff for 429 rate limit errors"""

content = content.replace(target2, replacement2)

with open("app/src/main/java/com/example/ai/KavyaAI.kt", "w") as f:
    f.write(content)
