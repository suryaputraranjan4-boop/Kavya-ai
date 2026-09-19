import re

with open('app/src/main/java/com/example/api/ApiRegistry.kt', 'r') as f:
    content = f.read()

content = content.replace('ApiPriority.P1_CORE', 'ApiPriority.P1_FREQUENT')

with open('app/src/main/java/com/example/api/ApiRegistry.kt', 'w') as f:
    f.write(content)

