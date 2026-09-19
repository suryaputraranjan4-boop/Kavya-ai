with open("app/src/main/java/com/example/services/KavyaVoiceService.kt", "r") as f:
    content = f.read()

target = """            if (cleanResponse.isNotEmpty() && !alreadySpoken) {
                speak(cleanResponse)
                waitForSpeechOrTimeout(cleanResponse.length)
            } else if (cleanResponse.isNotEmpty() && alreadySpoken) {
                waitForSpeechOrTimeout(cleanResponse.length)
            } else {
                if (isCompanionSessionActive) {
                    startListening()
                } else {
                    voiceState = VoiceState.IDLE
                }
            }"""

replacement = """            val isErrorResponse = cleanResponse.contains("high traffic") || cleanResponse.contains("busy right now") || cleanResponse.startsWith("[sad]")
            
            if (cleanResponse.isNotEmpty() && !alreadySpoken) {
                if (isErrorResponse && isCompanionSessionActive) {
                    // Do not repeatedly speak error messages in companion mode
                    // Just back off slightly and restart listening
                    kotlinx.coroutines.delay(2000)
                    startListening()
                } else {
                    speak(cleanResponse)
                    waitForSpeechOrTimeout(cleanResponse.length)
                }
            } else if (cleanResponse.isNotEmpty() && alreadySpoken) {
                waitForSpeechOrTimeout(cleanResponse.length)
            } else {
                if (isCompanionSessionActive) {
                    startListening()
                } else {
                    voiceState = VoiceState.IDLE
                }
            }"""

content = content.replace(target, replacement)
with open("app/src/main/java/com/example/services/KavyaVoiceService.kt", "w") as f:
    f.write(content)
