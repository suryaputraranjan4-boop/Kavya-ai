import re

with open('app/src/main/java/com/example/ai/GeminiLiveClient.kt', 'r') as f:
    content = f.read()

api_impl = """
                    } else if (name == "callApi") {
                         val apiId = args.getString("apiId")
                         val params = args.getString("params")
                         Log.d(TAG, "Tool call: callApi($apiId, $params)")
                         scope.launch {
                             val res = apiSystem.executeApiDirectly(apiId, params)
                             val responseObj = JSONObject().apply {
                                 put("success", true)
                                 put("result", res)
                             }
                             sendToolResponse(id, name, responseObj)
                         }
                    }
"""

content = re.sub(r'\} else if \(name == "callApi"\) \{.*?\}\s*\}', api_impl.strip() + '\n                }', content, flags=re.DOTALL)

with open('app/src/main/java/com/example/ai/GeminiLiveClient.kt', 'w') as f:
    f.write(content)
