import re

with open("app/src/main/java/com/example/services/KavyaVoiceService.kt", "r") as f:
    content = f.read()

target = """            val screenContext = if (!isFastChat) KavyaAccessibilityService.instance?.getScreenContext() else null
            val response = aiClient.chat(query, emptyList(), screenContext, isProactiveMode = isCompanionSessionActive)
            
            // Parse actions
            val actionRegex = "<ACTION:([^:>]+)(?::([^>]*))?>".toRegex()
            val matchResults = actionRegex.findAll(response).toList()
            var actionOutput = ""
            for (match in matchResults) {
                val action = match.groupValues[1].trim()
                val param = match.groupValues.getOrNull(2)?.trim() ?: ""
                
                Log.d(TAG, "Executing Action: $action with param: $param")
                val result = router.executeDetailed(action, param)
                actionOutput = result.output
            }
            
            // Remove tags from spoken response
            var displayResponse = response.replace(actionRegex, "").trim()
            if (actionOutput.isNotBlank() && (displayResponse.isBlank() || actionOutput.contains("not installed") || actionOutput.contains("Failed"))) {
                displayResponse = actionOutput
            }
            
            if (displayResponse.isNotEmpty()) {
                speak(displayResponse)
                waitForSpeechOrTimeout(displayResponse.length)
            } else {
                if (isCompanionSessionActive) {
                    startListening()
                } else {
                    voiceState = VoiceState.IDLE
                }
            }"""

replacement = """            var cleanResponse = ""
            var apiContextStr = ""

            val intentMatch = if (!isFastChat) apiSystem.router.routeIntent(query) else null

            if (!isFastChat && intentMatch == null) {
                // 1. Check for explicit Memory Extraction / Forget commands
                val memoryExtraction = memoryEngine.processAndExtractMemory(query, sourceConversation = query)
                if (memoryExtraction.detected && memoryExtraction.confirmationText.isNotBlank()) {
                    cleanResponse = memoryExtraction.confirmationText
                }
                
                // 2. Check for explicit Conversational Recall questions
                if (cleanResponse.isBlank()) {
                    val recallAnswer = memoryEngine.handleConversationalRecall(query)
                    if (recallAnswer != null) {
                        cleanResponse = recallAnswer
                    }
                }
            }

            // 3. Intelligent Public API Orchestration
            if (!isFastChat && cleanResponse.isBlank()) {
                val apiResult = apiSystem.processRequest(query)
                if (apiResult is com.example.api.ApiExecutionResult.Success) {
                    apiContextStr = "API_RESULT: " + apiResult.data
                } else if (apiResult is com.example.api.ApiExecutionResult.ToolRedirect) {
                    // Handled by Task Planner below
                }
            }

            // 4. Check for Universal Task Plan
            if (!isFastChat && cleanResponse.isBlank() && apiContextStr.isBlank()) {
                val taskPlan = taskPlanner.createPlan(query, contextEngine)
                if (taskPlan != null) {
                    val outcome = androidAgent.executeTaskPlan(
                        taskPlan,
                        onSpeakProgress = { spokenAnnouncement ->
                            speak(spokenAnnouncement)
                        }
                    )
                    cleanResponse = outcome.finalSpokenMessage
                }
            }

            // 5. Fallback to Gemini if no local engine handled it
            if (cleanResponse.isBlank()) {
                val screenContext = if (!isFastChat) KavyaAccessibilityService.instance?.getScreenContext() else null
                
                var finalQuery = query
                if (apiContextStr.isNotBlank()) {
                    finalQuery = "User said: $query\\nSystem Context: $apiContextStr\\n(Incorporate the API result naturally into your response)"
                }

                val response = aiClient.chat(finalQuery, emptyList(), screenContext, isProactiveMode = isCompanionSessionActive)
                
                val actionRegex = "<ACTION:([^:>]+)(?::([^>]*))?>".toRegex()
                val matchResults = actionRegex.findAll(response).toList()
                var actionOutput = ""
                for (match in matchResults) {
                    val action = match.groupValues[1].trim()
                    val param = match.groupValues.getOrNull(2)?.trim() ?: ""
                    Log.d(TAG, "Executing Action: $action with param: $param")
                    val result = router.executeDetailed(action, param)
                    actionOutput = result.output
                }
                
                cleanResponse = response.replace(actionRegex, "").trim()
                if (actionOutput.isNotBlank() && (cleanResponse.isBlank() || actionOutput.contains("not installed") || actionOutput.contains("Failed"))) {
                    cleanResponse = actionOutput
                }
            }
            
            if (cleanResponse.isNotEmpty()) {
                speak(cleanResponse)
                waitForSpeechOrTimeout(cleanResponse.length)
            } else {
                if (isCompanionSessionActive) {
                    startListening()
                } else {
                    voiceState = VoiceState.IDLE
                }
            }"""

if target in content:
    with open("app/src/main/java/com/example/services/KavyaVoiceService.kt", "w") as f:
        f.write(content.replace(target, replacement))
    print("Replaced successfully")
else:
    print("Target not found")
