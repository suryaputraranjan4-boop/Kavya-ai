with open("app/src/main/java/com/example/viewmodel/KavyaViewModel.kt", "r") as f:
    content = f.read()

target = """            val response = aiClient.chat(
                prompt = prompt,
                history = historyEntities,
                screenContext = screenContext,
                memoryContext = "",
                isProactiveMode = true
            )
            val clean = response.replace("<ACTION:[^>]+>".toRegex(), "").trim()
            if (clean.isNotBlank()) clean else null"""
            
replacement = """            val response = aiClient.chat(
                prompt = prompt,
                history = historyEntities,
                screenContext = screenContext,
                memoryContext = "",
                isProactiveMode = true
            )
            
            // If proactive speech hits an error (e.g. rate limit), fail silently instead of bothering the user
            if (response.contains("high traffic") || response.contains("busy right now") || response.startsWith("[sad]")) {
                return null
            }
            
            val clean = response.replace("<ACTION:[^>]+>".toRegex(), "").trim()
            if (clean.isNotBlank()) clean else null"""

content = content.replace(target, replacement)
with open("app/src/main/java/com/example/viewmodel/KavyaViewModel.kt", "w") as f:
    f.write(content)
