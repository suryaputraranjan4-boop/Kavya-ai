import re

with open('app/src/main/java/com/example/services/KavyaVoiceService.kt', 'r') as f:
    content = f.read()

content = content.replace(') catch (e: Exception) {\n                    }', ')')

with open('app/src/main/java/com/example/services/KavyaVoiceService.kt', 'w') as f:
    f.write(content)
