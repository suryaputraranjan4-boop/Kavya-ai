with open('app/src/main/java/com/example/viewmodel/KavyaViewModel.kt', 'r') as f:
    content = f.read()

target = """    val geminiPlayer = com.example.ai.GeminiAudioPlayer(application) { isSpeaking ->
        if (isSpeaking) {
            _voiceState.value = VoiceState.SPEAKING
        } else {
            if (_voiceState.value == VoiceState.SPEAKING) {
                _voiceState.value = VoiceState.IDLE
            }
        }
    }"""

replacement = """    private val _pendingCaption = MutableStateFlow<String?>(null)
    private val _latestKavyaCaption = MutableStateFlow<String?>(null)
    val latestKavyaCaption: StateFlow<String?> = _latestKavyaCaption.asStateFlow()

    val geminiPlayer = com.example.ai.GeminiAudioPlayer(application) { isSpeaking ->
        if (isSpeaking) {
            _voiceState.value = VoiceState.SPEAKING
            if (_pendingCaption.value != null) {
                _latestKavyaCaption.value = _pendingCaption.value
                _pendingCaption.value = null
            }
        } else {
            if (_voiceState.value == VoiceState.SPEAKING) {
                _voiceState.value = VoiceState.IDLE
            }
        }
    }"""

if target in content:
    with open('app/src/main/java/com/example/viewmodel/KavyaViewModel.kt', 'w') as f:
        f.write(content.replace(target, replacement))
    print("Success 1")
else:
    print("Failed 1")
