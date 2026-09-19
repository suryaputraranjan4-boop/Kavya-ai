with open("app/src/main/java/com/example/ai/KavyaAI.kt", "r") as f:
    content = f.read()

target = """        } catch (e: Exception) {
            emit("Error: ${e.message}")
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)"""
replacement = """        } catch (e: Exception) {
            emit("Error: ${e.message}")
        } finally {
            com.example.api.QuotaManager.instance.releaseQuota(requestId)
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)"""
if target in content:
    content = content.replace(target, replacement)
    with open("app/src/main/java/com/example/ai/KavyaAI.kt", "w") as f:
        f.write(content)
    print("Patched streamChat function end")
else:
    print("Could not find target for streamChat end")
