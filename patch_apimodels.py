import re

with open('app/src/main/java/com/example/api/ApiModels.kt', 'r') as f:
    content = f.read()

content = content.replace('UTILITIES', 'UTILITIES, UTILITY')
content = content.replace('UNKNOWN\n}', 'UNKNOWN, TRANSCRIPTION, IMAGE_ANALYSIS\n}')

with open('app/src/main/java/com/example/api/ApiModels.kt', 'w') as f:
    f.write(content)
