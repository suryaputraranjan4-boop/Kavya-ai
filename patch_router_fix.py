with open("app/src/main/java/com/example/utils/CommandRouter.kt", "r") as f:
    content = f.read()

target = """    suspend fun executeDetailed(actionType: String, actionParam: String): CommandExecutionResult {
        com.example.state.KavyaStateManager.updateAction(tool = action, action = param)"""
replacement = """    suspend fun executeDetailed(actionType: String, actionParam: String): CommandExecutionResult {
        com.example.state.KavyaStateManager.updateAction(tool = actionType, action = actionParam)"""

content = content.replace(target, replacement)
with open("app/src/main/java/com/example/utils/CommandRouter.kt", "w") as f:
    f.write(content)
