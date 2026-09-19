import re

with open("app/src/main/java/com/example/viewmodel/KavyaViewModel.kt", "r") as f:
    content = f.read()

# Introduce alreadySpoken
target1 = """                var isAmbiguous = false
                var candidateApps: List<InstalledApp> = emptyList()"""
replacement1 = """                var isAmbiguous = false
                var alreadySpoken = false
                var candidateApps: List<InstalledApp> = emptyList()"""

if target1 in content:
    content = content.replace(target1, replacement1)
else:
    print("Could not find target1")

# Mark alreadySpoken in memory Extraction
target2 = """                        if (_autoSpeak.value) {
                            voiceEngine.processAndSpeak(cleanResponse, enqueue = true)
                        }"""
replacement2 = """                        if (_autoSpeak.value) {
                            voiceEngine.processAndSpeak(cleanResponse, enqueue = true)
                            alreadySpoken = true
                        }"""
if target2 in content:
    content = content.replace(target2, replacement2)
else:
    print("Could not find target2")

# At the very end of processAgentLoop
target3 = """                    // Auto-speak the AI response
                    if (_autoSpeak.value && cleanResponse.isNotBlank()) {
                        voiceEngine.processAndSpeak(cleanResponse, enqueue = false)
                    }"""
replacement3 = """                    // Auto-speak the AI response
                    if (_autoSpeak.value && cleanResponse.isNotBlank() && !alreadySpoken) {
                        voiceEngine.processAndSpeak(cleanResponse, enqueue = false)
                        alreadySpoken = true
                    }"""
if target3 in content:
    content = content.replace(target3, replacement3)
else:
    print("Could not find target3")

with open("app/src/main/java/com/example/viewmodel/KavyaViewModel.kt", "w") as f:
    f.write(content)
print("Patched KavyaViewModel duplicate speak issue")
