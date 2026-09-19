with open("app/src/main/java/com/example/ai/KavyaVoiceEngine.kt", "r") as f:
    content = f.read()

target = """                while (attempt < maxAttempts && !geminiSuccess) {
                    attempt++
                    try {"""
                    
replacement = """                while (attempt < maxAttempts && !geminiSuccess) {
                    attempt++
                    com.example.api.QuotaManager.instance.totalVoiceRequests++
                    try {"""

content = content.replace(target, replacement)

target2 = """                    } catch (e: Exception) {
                        lastException = e
                        Log.w(TAG, "VOICE_ERROR on attempt $attempt: ${e.message}")"""
                        
replacement2 = """                    } catch (e: Exception) {
                        lastException = e
                        com.example.api.QuotaManager.instance.apply {
                            lastErrorType = e.javaClass.simpleName
                            lastErrorTime = System.currentTimeMillis()
                            lastErrorRequestType = "VOICE"
                        }
                        Log.w(TAG, "VOICE_ERROR on attempt $attempt: ${e.message}")"""

content = content.replace(target2, replacement2)
with open("app/src/main/java/com/example/ai/KavyaVoiceEngine.kt", "w") as f:
    f.write(content)
