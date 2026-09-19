with open('app/src/main/java/com/example/viewmodel/KavyaViewModel.kt', 'r') as f:
    content = f.read()

target = """                // Only follow-up if explicitly inspecting screen (READ_SCREEN) and recursion depth < 2
                if (aType == "READ_SCREEN" && !isAmbiguous && recursionDepth < 2) {"""

replacement = """                // Multi-step agent loop for universal app automation
                val needsFollowUp = (aType == "READ_SCREEN" || aType == "OPEN_APP" || aType == "UI_CLICK" || aType == "UI_SCROLL" || aType == "UI_TYPE") && !isAmbiguous
                if (needsFollowUp && recursionDepth < 3) {"""

content = content.replace("if (commandRouter.isDirectDeviceCommand(prompt)) {", "if (false) { // Disabled hardcoded routing, use AI for universal app automation")
content = content.replace(target, replacement)

with open('app/src/main/java/com/example/viewmodel/KavyaViewModel.kt', 'w') as f:
    f.write(content)
print("VM Loop Patch applied")
