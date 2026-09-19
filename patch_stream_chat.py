import re

with open("app/src/main/java/com/example/ai/KavyaAI.kt", "r") as f:
    content = f.read()

target = """        if (!com.example.api.QuotaManager.instance.acquireQuota(requestId, priority)) {
            emit("I am a bit busy right now. Please try again in a moment.")
            return@flow
        }
        try {
        val contents = mutableListOf<Content>()"""

replacement = """        if (!com.example.api.QuotaManager.instance.acquireQuota(requestId, priority)) {
            emit("I am a bit busy right now. Please try again in a moment.")
            return@flow
        }
        val contents = mutableListOf<Content>()"""

content = content.replace(target, replacement)

with open("app/src/main/java/com/example/ai/KavyaAI.kt", "w") as f:
    f.write(content)
