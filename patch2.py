with open('app/src/main/java/com/example/viewmodel/KavyaViewModel.kt', 'r') as f:
    content = f.read()

target = """                if (_autoSpeak.value && !isErrorResponse && cleanResponse.isNotBlank()) {
                    val responseForVoice = if (rawModelResponse.isNotBlank()) rawModelResponse else cleanResponse
                    voiceManager.processAndSpeak(responseForVoice)
                }"""

replacement = """                if (_autoSpeak.value && !isErrorResponse && cleanResponse.isNotBlank()) {
                    val responseForVoice = if (rawModelResponse.isNotBlank()) rawModelResponse else cleanResponse
                    _pendingCaption.value = cleanResponse
                    voiceManager.processAndSpeak(responseForVoice)
                } else {
                    _latestKavyaCaption.value = cleanResponse
                }"""

if target in content:
    with open('app/src/main/java/com/example/viewmodel/KavyaViewModel.kt', 'w') as f:
        f.write(content.replace(target, replacement))
    print("Success 2")
else:
    print("Failed 2")
