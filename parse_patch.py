import re

with open("app/src/main/java/com/example/ai/GeminiLiveClient.kt", "r") as f:
    content = f.read()

execute_task_plan_tool = """
                            put(JSONObject().apply {
                                put("name", "executeTaskPlan")
                                put("description", "Execute a multi-step structured action plan on the Android device. Use this for complex commands like 'Open YouTube and search Hi'.")
                                put("parameters", JSONObject().apply {
                                    put("type", "OBJECT")
                                    put("properties", JSONObject().apply {
                                        put("intent", JSONObject().apply { put("type", "STRING") })
                                        put("steps", JSONObject().apply {
                                            put("type", "ARRAY")
                                            put("items", JSONObject().apply {
                                                put("type", "OBJECT")
                                                put("properties", JSONObject().apply {
                                                    put("action", JSONObject().apply { put("type", "STRING") })
                                                    put("target", JSONObject().apply { put("type", "STRING") })
                                                    put("query", JSONObject().apply { put("type", "STRING") })
                                                    put("recipient", JSONObject().apply { put("type", "STRING") })
                                                    put("message", JSONObject().apply { put("type", "STRING") })
                                                })
                                                put("required", JSONArray().put("action").put("target"))
                                            })
                                        })
                                    })
                                    put("required", JSONArray().put("intent").put("steps"))
                                })
                            })
"""

# Insert the new tool declaration
content = content.replace('                            put(JSONObject().apply {\n                                put("name", "performAndroidAction")', execute_task_plan_tool + '                            put(JSONObject().apply {\n                                put("name", "performAndroidAction")')

with open("app/src/main/java/com/example/ai/GeminiLiveClient.kt", "w") as f:
    f.write(content)
