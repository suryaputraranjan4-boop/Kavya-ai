import re

with open('app/src/main/java/com/example/services/KavyaVoiceService.kt', 'r') as f:
    content = f.read()

# Replace any occurrence of ") catch (e: Exception) { ... }" with ")"
content = re.sub(r'\) catch \(e: Exception\) \{[^\}]*\}', ')', content)

with open('app/src/main/java/com/example/services/KavyaVoiceService.kt', 'w') as f:
    f.write(content)
