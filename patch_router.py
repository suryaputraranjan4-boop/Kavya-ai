with open("app/src/main/java/com/example/utils/CommandRouter.kt", "r") as f:
    content = f.read()

target = """    private suspend fun executeLaunchAndVerify("""
replacement = """    private suspend fun executeLaunchAndVerify(
        targetPackage: String,
        targetAppName: String,
        isSearch: Boolean = false,
        searchQuery: String = ""
    ): CommandExecutionResult {
        com.example.state.KavyaStateManager.updateAction(tool = if (isSearch) "Search" else "AppLaunch", action = targetAppName)
"""

if target in content:
    # Need to be careful because the signature already exists
    pass

import re
content = re.sub(
    r'(fun executeDetailed.*?\{)',
    r'\1\n        com.example.state.KavyaStateManager.updateAction(tool = action, action = param)',
    content,
    flags=re.DOTALL
)

with open("app/src/main/java/com/example/utils/CommandRouter.kt", "w") as f:
    f.write(content)
