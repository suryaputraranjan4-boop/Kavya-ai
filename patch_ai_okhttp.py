with open('app/src/main/java/com/example/ai/KavyaAI.kt', 'r') as f:
    content = f.read()

content = content.replace('okhttp3.MediaType.parse("application/json")', '"application/json".toMediaType()')
content = content.replace("val requestBody = json.encodeToString", "val requestJson = json.encodeToString")
content = content.replace("post(okhttp3.RequestBody.create(", "post(okhttp3.RequestBody.create(") # Wait, I didn't change the usage.
content = content.replace("post(okhttp3.RequestBody.create(\"application/json\".toMediaType(), requestBody))", "post(okhttp3.RequestBody.create(\"application/json\".toMediaType(), requestJson))")

with open('app/src/main/java/com/example/ai/KavyaAI.kt', 'w') as f:
    f.write(content)
print("AI OkHttp Patch applied")
