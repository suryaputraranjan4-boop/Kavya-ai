with open("app/src/main/java/com/example/viewmodel/KavyaViewModel.kt", "r") as f:
    content = f.read()

target = """        _isProcessing.value = true
        _voiceState.value = VoiceState.THINKING
        com.example.state.KavyaStateManager.updateVoiceState(VoiceState.THINKING)
        _latestKavyaCaption.value = null"""
        
replacement = """        _isProcessing.value = true
        _voiceState.value = VoiceState.THINKING
        com.example.state.KavyaStateManager.updateVoiceState(VoiceState.THINKING)
        com.example.state.KavyaStateManager.setGeminiState("PROCESSING")
        _latestKavyaCaption.value = null"""

content = content.replace(target, replacement)

target2 = """                val isErrorResponse = cleanResponse.startsWith("Unable to connect") || cleanResponse.startsWith("Gemini API Key is not configured")"""

replacement2 = """                val isErrorResponse = cleanResponse.startsWith("Unable to connect") || cleanResponse.startsWith("Gemini API Key is not configured")
                com.example.state.KavyaStateManager.setGeminiState(if (isErrorResponse) "ERROR" else "SUCCESS")"""

content = content.replace(target2, replacement2)

with open("app/src/main/java/com/example/viewmodel/KavyaViewModel.kt", "w") as f:
    f.write(content)
