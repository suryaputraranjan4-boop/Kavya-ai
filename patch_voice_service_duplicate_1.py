with open("app/src/main/java/com/example/services/KavyaVoiceService.kt", "r") as f:
    content = f.read()

target1 = """            var cleanResponse = ""
            var apiContextStr = ""

            val intentMatch = if (!isFastChat) apiSystem.router.routeIntent(query) else null"""
replacement1 = """            var cleanResponse = ""
            var apiContextStr = ""
            var alreadySpoken = false

            val intentMatch = if (!isFastChat) apiSystem.router.routeIntent(query) else null"""

if target1 in content:
    content = content.replace(target1, replacement1)
    with open("app/src/main/java/com/example/services/KavyaVoiceService.kt", "w") as f:
        f.write(content)
    print("Patched target1")
else:
    print("Could not find target1")
