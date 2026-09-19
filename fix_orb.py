import re

with open('app/src/main/java/com/example/ui/components/KavyaVoiceOrb.kt', 'r') as f:
    content = f.read()

# Fix duplicate branches inside when statements
content = re.sub(r'VoiceState\.UNDERSTANDING -> [^\n]*\n\s*VoiceState\.UNDERSTANDING -> [^\n]*\n', 'VoiceState.UNDERSTANDING -> 1.02f\n', content)
content = re.sub(r'VoiceState\.EXECUTING -> [^\n]*\n\s*VoiceState\.EXECUTING -> [^\n]*\n', 'VoiceState.EXECUTING -> 1.06f\n', content)

with open('app/src/main/java/com/example/ui/components/KavyaVoiceOrb.kt', 'w') as f:
    f.write(content)
