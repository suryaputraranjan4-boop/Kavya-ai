import re

with open("app/src/main/java/com/example/proactive/ProactiveController.kt", "r") as f:
    content = f.read()

# Add lastScreenHash
if "private var lastScreenHash: Int = 0" not in content:
    content = content.replace(
        "    private var nextIntervalTargetMs = 15000L",
        "    private var nextIntervalTargetMs = 15000L\n    private var lastScreenHash: Int = 0"
    )

target = """        // Priority 1: Screen context observation (ONLY if enabled by user)
        if (_isScreenAwareness.value) {
            val screenContext = screenInspector.getScreenContextString()
            if (!screenContext.isNullOrBlank() && !screenContext.contains("Root node is null")) {
                val lowerScreen = screenContext.lowercase()"""
                
replacement = """        // Priority 1: Screen context observation (ONLY if enabled by user)
        if (_isScreenAwareness.value) {
            val screenContext = screenInspector.getScreenContextString()
            if (!screenContext.isNullOrBlank() && !screenContext.contains("Root node is null")) {
                val currentHash = screenContext.hashCode()
                if (currentHash != lastScreenHash) {
                    lastScreenHash = currentHash
                    val lowerScreen = screenContext.lowercase()"""

content = content.replace(target, replacement)

target2 = """                        return ProactiveDecision(
                            shouldSpeak = true,
                            reason = "User appears to be reading or browsing. Make a relevant short comment, question, or offer to summarize.",
                            text = null,
                            triggerType = ProactiveTriggerType.SCREEN_OBSERVATION,
                            priorityScore = 0.85f
                        )
                    }
                    lowerScreen.contains("docs") || lowerScreen.contains("notes") || lowerScreen.contains("code") || lowerScreen.contains("studio") -> {
                        return ProactiveDecision(
                            shouldSpeak = true,
                            reason = "User appears to be working or studying. Make a short encouraging comment, check in, or offer help.",
                            text = null,
                            triggerType = ProactiveTriggerType.CURRENT_ACTIVITY,
                            priorityScore = 0.8f
                        )
                    }
                }
            }
        }"""
        
replacement2 = """                        return ProactiveDecision(
                            shouldSpeak = true,
                            reason = "User appears to be reading or browsing. Make a relevant short comment, question, or offer to summarize.",
                            text = null,
                            triggerType = ProactiveTriggerType.SCREEN_OBSERVATION,
                            priorityScore = 0.85f
                        )
                    }
                    lowerScreen.contains("docs") || lowerScreen.contains("notes") || lowerScreen.contains("code") || lowerScreen.contains("studio") -> {
                        return ProactiveDecision(
                            shouldSpeak = true,
                            reason = "User appears to be working or studying. Make a short encouraging comment, check in, or offer help.",
                            text = null,
                            triggerType = ProactiveTriggerType.CURRENT_ACTIVITY,
                            priorityScore = 0.8f
                        )
                    }
                }
                }
            }
        }"""

content = content.replace(target2, replacement2)
with open("app/src/main/java/com/example/proactive/ProactiveController.kt", "w") as f:
    f.write(content)
