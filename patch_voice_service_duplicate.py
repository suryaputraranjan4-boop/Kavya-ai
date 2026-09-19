import re

with open("app/src/main/java/com/example/services/KavyaVoiceService.kt", "r") as f:
    content = f.read()

target1 = """            var cleanResponse = ""
            var apiContextStr = \"\"\""""
replacement1 = """            var cleanResponse = ""
            var apiContextStr = ""
            var alreadySpoken = false"""

if target1 in content:
    content = content.replace(target1, replacement1)
else:
    print("Could not find target1")
    
target2 = """                        onSpeakProgress = { spokenAnnouncement ->
                            speak(spokenAnnouncement)
                        }"""
replacement2 = """                        onSpeakProgress = { spokenAnnouncement ->
                            speak(spokenAnnouncement)
                            alreadySpoken = true
                        }"""
if target2 in content:
    content = content.replace(target2, replacement2)
else:
    print("Could not find target2")

target3 = """            if (cleanResponse.isNotEmpty()) {
                speak(cleanResponse)
                waitForSpeechOrTimeout(cleanResponse.length)
            } else {"""
replacement3 = """            if (cleanResponse.isNotEmpty() && !alreadySpoken) {
                speak(cleanResponse)
                waitForSpeechOrTimeout(cleanResponse.length)
            } else if (cleanResponse.isNotEmpty() && alreadySpoken) {
                waitForSpeechOrTimeout(cleanResponse.length)
            } else {"""
if target3 in content:
    content = content.replace(target3, replacement3)
else:
    print("Could not find target3")

with open("app/src/main/java/com/example/services/KavyaVoiceService.kt", "w") as f:
    f.write(content)
print("Patched KavyaVoiceService duplicate speak issue")
