with open('app/src/main/java/com/example/viewmodel/KavyaViewModel.kt', 'r') as f:
    content = f.read()

content = content.replace("cleanResponse = voiceManager.cleanText(response)", "cleanResponse = voiceManager.cleanText(fullResponse)")

with open('app/src/main/java/com/example/viewmodel/KavyaViewModel.kt', 'w') as f:
    f.write(content)
print("VM Response Patch applied")
