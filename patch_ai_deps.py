with open('app/src/main/java/com/example/ai/KavyaAI.kt', 'r') as f:
    content = f.read()

content = content.replace("List<ChatMessage>", "List<com.example.data.MessageEntity>")
content = content.replace("msg.isLoading || msg.isError", "false") # MessageEntity doesn't have isLoading/isError

with open('app/src/main/java/com/example/ai/KavyaAI.kt', 'w') as f:
    f.write(content)
print("AI Deps Patch applied")
