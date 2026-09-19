with open("app/src/main/java/com/example/ai/KavyaAI.kt", "r") as f:
    content = f.read()

target = """    ): kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.flow {
        val contents = mutableListOf<Content>()"""
replacement = """    ): kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.flow {
        val requestId = java.util.UUID.randomUUID().toString()
        val priority = if (isProactiveMode) com.example.api.RequestPriority.P3_PROACTIVE_CONVERSATION else com.example.api.RequestPriority.P0_ACTIVE_USER_REQUEST
        
        if (!com.example.api.QuotaManager.instance.acquireQuota(requestId, priority)) {
            emit("I am a bit busy right now. Please try again in a moment.")
            return@flow
        }
        try {
        val contents = mutableListOf<Content>()"""

if target in content:
    content = content.replace(target, replacement)
    
    # Now find the end of the flow to add finally
    # We will search for the end of the loop and the method end
    
    target2 = """            }
        } catch (e: Exception) {
            Log.e(TAG, "Stream error", e)
            emit("Error: ${e.message}")
        }
    }"""
    replacement2 = """            }
        } catch (e: Exception) {
            Log.e(TAG, "Stream error", e)
            emit("Error: ${e.message}")
        } finally {
            com.example.api.QuotaManager.instance.releaseQuota(requestId)
        }
    }"""
    if target2 in content:
        content = content.replace(target2, replacement2)
    else:
        print("Target2 not found")
        
    with open("app/src/main/java/com/example/ai/KavyaAI.kt", "w") as f:
        f.write(content)
    print("Patched streamChat function")
else:
    print("Could not find target for streamChat function")
