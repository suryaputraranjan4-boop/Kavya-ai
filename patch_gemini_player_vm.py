with open("app/src/main/java/com/example/viewmodel/KavyaViewModel.kt", "r") as f:
    content = f.read()

target = """            if (isSpeaking) {
                _voiceState.value = VoiceState.SPEAKING
            } else {
                if (_voiceState.value == VoiceState.SPEAKING) {
                    _voiceState.value = VoiceState.IDLE
                }
            }"""
            
replacement = """            if (isSpeaking) {
                _voiceState.value = VoiceState.SPEAKING
                com.example.state.KavyaStateManager.updateVoiceState(VoiceState.SPEAKING)
            } else {
                if (_voiceState.value == VoiceState.SPEAKING) {
                    _voiceState.value = VoiceState.IDLE
                    com.example.state.KavyaStateManager.updateVoiceState(VoiceState.IDLE)
                }
            }"""

content = content.replace(target, replacement)
with open("app/src/main/java/com/example/viewmodel/KavyaViewModel.kt", "w") as f:
    f.write(content)
