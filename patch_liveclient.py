import re

with open('app/src/main/java/com/example/ai/GeminiLiveClient.kt', 'r') as f:
    content = f.read()

setup_block = """
                    put("generationConfig", JSONObject().apply {
                        put("responseModalities", JSONArray().put("AUDIO"))
                    })
                    put("tools", JSONArray().put(JSONObject().apply {
                        put("functionDeclarations", JSONArray().apply {
                            put(JSONObject().apply {
                                put("name", "performAndroidAction")
                                put("description", "Perform a UI action on the Android device. Supported actions: OPEN_APP, TAP, TYPE, SCROLL, BACK, HOME, CLEAR_TEXT")
                                put("parameters", JSONObject().apply {
                                    put("type", "OBJECT")
                                    put("properties", JSONObject().apply {
                                        put("action", JSONObject().apply {
                                            put("type", "STRING")
                                        })
                                        put("param", JSONObject().apply {
                                            put("type", "STRING")
                                        })
                                    })
                                    put("required", JSONArray().put("action").put("param"))
                                })
                            })
                            put(JSONObject().apply {
                                put("name", "callApi")
                                put("description", "Call an external API or Hugging Face model.")
                                put("parameters", JSONObject().apply {
                                    put("type", "OBJECT")
                                    put("properties", JSONObject().apply {
                                        put("apiId", JSONObject().apply {
                                            put("type", "STRING")
                                        })
                                        put("params", JSONObject().apply {
                                            put("type", "STRING")
                                        })
                                    })
                                    put("required", JSONArray().put("apiId").put("params"))
                                })
                            })
                        })
                    }))
"""
content = re.sub(r'put\("generationConfig", JSONObject\(\)\.apply \{.*?\}\)', setup_block.strip(), content, flags=re.DOTALL)


tool_call_handler = """
            } else if (root.has("toolCall")) {
                val toolCall = root.getJSONObject("toolCall")
                val functionCalls = toolCall.getJSONArray("functionCalls")
                val functionResponses = JSONArray()
                
                for (i in 0 until functionCalls.length()) {
                    val fc = functionCalls.getJSONObject(i)
                    val id = fc.getString("id")
                    val name = fc.getString("name")
                    val args = fc.getJSONObject("args")
                    
                    var resultStr = ""
                    if (name == "performAndroidAction") {
                        val action = args.getString("action")
                        val param = args.getString("param")
                        Log.d(TAG, "Tool call: performAndroidAction(\$action, \$param)")
                        // Launch a coroutine to execute
                        scope.launch {
                            val step = com.example.agent.TaskStep(
                                id = 1,
                                actionType = when (action) {
                                    "OPEN_APP" -> com.example.agent.UniversalActionType.OPEN_APP
                                    "TAP" -> com.example.agent.UniversalActionType.TAP
                                    "TYPE" -> com.example.agent.UniversalActionType.TYPE
                                    "SCROLL" -> com.example.agent.UniversalActionType.SCROLL
                                    "BACK" -> com.example.agent.UniversalActionType.BACK
                                    "HOME" -> com.example.agent.UniversalActionType.HOME
                                    "CLEAR_TEXT" -> com.example.agent.UniversalActionType.CLEAR_TEXT
                                    else -> com.example.agent.UniversalActionType.SYSTEM_CONTROL
                                },
                                targetAppOrUrl = param,
                                param = param
                            )
                            val res = androidAgent.executeAtomicStep(step)
                            val responseObj = JSONObject().apply {
                                put("success", res.success)
                                put("result", res.output)
                            }
                            sendToolResponse(id, name, responseObj)
                        }
                    } else if (name == "callApi") {
                         val apiId = args.getString("apiId")
                         scope.launch {
                             // mock success for now
                             val responseObj = JSONObject().apply {
                                 put("success", true)
                                 put("result", "Called API \$apiId")
                             }
                             sendToolResponse(id, name, responseObj)
                         }
                    }
                }
            }
"""
content = re.sub(r'\} else if \(root\.has\("toolCall"\)\) \{\s*// Handle function calling\s*\}', tool_call_handler.strip(), content, flags=re.DOTALL)

content = content.replace('class GeminiLiveClient(', """class GeminiLiveClient(
""")

send_tool = """
    private fun sendToolResponse(id: String, name: String, responseObj: JSONObject) {
        try {
            val msg = JSONObject().apply {
                put("toolResponse", JSONObject().apply {
                    put("functionResponses", JSONArray().put(JSONObject().apply {
                        put("id", id)
                        put("name", name)
                        put("response", responseObj)
                    }))
                })
            }
            webSocket?.send(msg.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send tool response", e)
        }
    }
"""

content = content.replace('private fun startAudioIO()', send_tool + '\n    private fun startAudioIO()')

with open('app/src/main/java/com/example/ai/GeminiLiveClient.kt', 'w') as f:
    f.write(content)

