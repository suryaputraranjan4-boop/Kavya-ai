with open("app/src/main/java/com/example/viewmodel/KavyaViewModel.kt", "r") as f:
    content = f.read()

target = """                            if (_autoSpeak.value) {
                                voiceEngine.processAndSpeak(cleanResponse, enqueue = true)
                            }"""
replacement = """                            if (_autoSpeak.value) {
                                voiceEngine.processAndSpeak(cleanResponse, enqueue = true)
                                alreadySpoken = true
                            }"""
if target in content:
    content = content.replace(target, replacement)
    with open("app/src/main/java/com/example/viewmodel/KavyaViewModel.kt", "w") as f:
        f.write(content)
    print("Patched target")
else:
    print("Could not find target")
