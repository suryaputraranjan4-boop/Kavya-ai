import re

with open('app/src/main/java/com/example/api/ApiSystem.kt', 'r') as f:
    content = f.read()

execute_directly = """
    suspend fun executeApiDirectly(apiId: String, paramsJson: String): String {
        val api = registry.getApi(apiId) ?: return "API not found"
        val paramsMap = mutableMapOf<String, String>()
        try {
            val json = org.json.JSONObject(paramsJson)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                paramsMap[key] = json.getString(key)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing params JSON", e)
        }
        return executor.execute(api, paramsMap) ?: "API Execution failed"
    }
"""

content = content.replace('    suspend fun processRequest(', execute_directly + '\n    suspend fun processRequest(')

with open('app/src/main/java/com/example/api/ApiSystem.kt', 'w') as f:
    f.write(content)

