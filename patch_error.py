with open("app/src/main/java/com/example/ai/KavyaAI.kt", "r") as f:
    content = f.read()

target = """            lastError is HttpException && ((lastError as HttpException).code() == 400 || (lastError as HttpException).code() == 404) -> {
                "[sad] There seems to be a model compatibility issue. Please check your Gemini configuration."
            }"""

replacement = """            lastError is HttpException && ((lastError as HttpException).code() == 400 || (lastError as HttpException).code() == 404) -> {
                val errBody = (lastError as HttpException).response()?.errorBody()?.string() ?: ""
                "[sad] There seems to be a model compatibility issue. Please check your Gemini configuration. Details: " + errBody
            }"""

content = content.replace(target, replacement)
with open("app/src/main/java/com/example/ai/KavyaAI.kt", "w") as f:
    f.write(content)
