with open("app/src/main/java/com/example/viewmodel/KavyaViewModel.kt", "r") as f:
    content = f.read()

target = """    fun setVoiceState(state: VoiceState) {
        _voiceState.value = state"""
        
replacement = """    fun setVoiceState(state: VoiceState) {
        _voiceState.value = state
        com.example.state.KavyaStateManager.updateVoiceState(state)"""

content = content.replace(target, replacement)

target2 = """        _voiceState.value = VoiceState.THINKING"""
replacement2 = """        _voiceState.value = VoiceState.THINKING
        com.example.state.KavyaStateManager.updateVoiceState(VoiceState.THINKING)"""

content = content.replace(target2, replacement2)

with open("app/src/main/java/com/example/viewmodel/KavyaViewModel.kt", "w") as f:
    f.write(content)
