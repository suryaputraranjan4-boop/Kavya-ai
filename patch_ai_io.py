with open('app/src/main/java/com/example/ai/KavyaAI.kt', 'r') as f:
    content = f.read()

target = """        } catch (e: Exception) {
            emit("Error: ${e.message}")
        }
    }"""

replacement = """        } catch (e: Exception) {
            emit("Error: ${e.message}")
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)"""

if target in content:
    content = content.replace(target, replacement)
    with open('app/src/main/java/com/example/ai/KavyaAI.kt', 'w') as f:
        f.write(content)
    print("AI IO Patch applied")
else:
    print("Target not found")
