with open('app/src/main/java/com/example/ai/KavyaAI.kt', 'r') as f:
    content = f.read()

content = content.replace("screenContext: String? = null", "screenContext: String? = null, memoryContext: String = \"\"")

with open('app/src/main/java/com/example/ai/KavyaAI.kt', 'w') as f:
    f.write(content)
print("AI Sig Patch applied")
