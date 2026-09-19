import re

with open('app/src/main/java/com/example/ai/GeminiLiveClient.kt', 'r') as f:
    content = f.read()

content = content.replace('                    }\n                }\n                }\n            }\n        } catch (e: Exception) {', '                    }\n                }\n            }\n        } catch (e: Exception) {')

with open('app/src/main/java/com/example/ai/GeminiLiveClient.kt', 'w') as f:
    f.write(content)
