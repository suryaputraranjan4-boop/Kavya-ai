import re

with open("app/src/main/java/com/example/ai/GeminiLiveClient.kt", "r") as f:
    content = f.read()

handler_code = """
                    if (name == "executeTaskPlan") {
                        val intent = args.getString("intent")
                        val stepsArray = args.getJSONArray("steps")
                        Log.d(TAG, "Tool call: executeTaskPlan($intent)")
                        scope.launch {
                            val taskSteps = mutableListOf<com.example.agent.TaskStep>()
                            var targetApp = ""
                            for (j in 0 until stepsArray.length()) {
                                val stepObj = stepsArray.getJSONObject(j)
                                val actionStr = stepObj.getString("action").uppercase()
                                val targetStr = stepObj.optString("target", "")
                                val queryStr = stepObj.optString("query", "")
                                val msgStr = stepObj.optString("message", "")
                                
                                if (j == 0 && targetStr.isNotBlank()) targetApp = targetStr
                                
                                val paramStr = if (queryStr.isNotBlank()) queryStr else if (msgStr.isNotBlank()) msgStr else targetStr
                                
                                val mappedAction = when (actionStr) {
                                    "OPEN_APP", "OPEN" -> com.example.agent.UniversalActionType.OPEN_APP
                                    "SEARCH" -> com.example.agent.UniversalActionType.SEARCH
                                    "TAP" -> com.example.agent.UniversalActionType.TAP
                                    "TYPE" -> com.example.agent.UniversalActionType.TYPE
                                    "SEND MESSAGE", "MESSAGE" -> com.example.agent.UniversalActionType.SEND_MESSAGE
                                    else -> com.example.agent.UniversalActionType.SYSTEM_CONTROL
                                }
                                
                                taskSteps.add(
                                    com.example.agent.TaskStep(
                                        id = j + 1,
                                        actionType = mappedAction,
                                        targetAppOrUrl = targetStr,
                                        param = paramStr
                                    )
                                )
                            }
                            
                            val taskPlan = com.example.agent.TaskPlan(
                                originalPrompt = "Parsed Intent: $intent",
                                targetAppName = targetApp,
                                steps = taskSteps,
                                isMultiStep = taskSteps.size > 1
                            )
                            
                            val res = androidAgent.executeTaskPlan(taskPlan)
                            val responseObj = JSONObject().apply {
                                put("success", res.success)
                                put("result", res.finalSpokenMessage)
                            }
                            sendToolResponse(id, name, responseObj)
                        }
                    } else if (name == "performAndroidAction") {
"""

content = content.replace('                    if (name == "performAndroidAction") {', handler_code)

with open("app/src/main/java/com/example/ai/GeminiLiveClient.kt", "w") as f:
    f.write(content)
