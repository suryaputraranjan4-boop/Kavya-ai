with open("app/src/main/java/com/example/viewmodel/KavyaViewModel.kt", "r") as f:
    content = f.read()

target = """                            isProactiveMode = proactiveController.isProactiveMode.value"""
replacement = """                            isProactiveMode = false"""

content = content.replace(target, replacement)
with open("app/src/main/java/com/example/viewmodel/KavyaViewModel.kt", "w") as f:
    f.write(content)
