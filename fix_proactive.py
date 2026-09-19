with open("app/src/main/java/com/example/proactive/ProactiveController.kt", "r") as f:
    content = f.read()

content = content.replace("private var nextIntervalTargetMs = 25000L", "private var nextIntervalTargetMs = 25000L\n    private var lastScreenHash: Int = 0")

with open("app/src/main/java/com/example/proactive/ProactiveController.kt", "w") as f:
    f.write(content)
