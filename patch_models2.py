with open("app/src/main/java/com/example/ai/KavyaAI.kt", "r") as f:
    content = f.read()

target = """        // Ordered list of official supported Gemini models per platform guidelines
        private val MODEL_TIERS = listOf(
            "gemini-3.6-flash",
            "gemini-3.5-flash",
            "gemini-3.1-pro-preview",
            "gemini-2.5-flash",
            "gemini-2.0-flash-exp",
            "gemini-1.5-flash",
            "gemini-1.5-pro",
            "gemini-1.5-flash-8b"
        )"""

replacement = """        // Ordered list of official supported Gemini models per platform guidelines
        private val MODEL_TIERS = listOf(
            "gemini-3.6-flash",
            "gemini-3.5-flash",
            "gemini-3.1-pro-preview",
            "gemini-2.5-flash"
        )"""

content = content.replace(target, replacement)

target2 = """        // Official Gemini models supporting audio modality generation
        private val AUDIO_MODEL_TIERS = listOf(
            "gemini-3.6-flash",
            "gemini-3.5-flash",
            "gemini-3.1-pro-preview",
            "gemini-2.5-flash",
            "gemini-2.0-flash-exp",
            "gemini-1.5-flash",
            "gemini-1.5-pro",
            "gemini-1.5-flash-8b"
        )"""

replacement2 = """        // Official Gemini models supporting audio modality generation
        private val AUDIO_MODEL_TIERS = listOf(
            "gemini-3.6-flash",
            "gemini-3.5-flash",
            "gemini-3.1-pro-preview",
            "gemini-2.5-flash"
        )"""

content = content.replace(target2, replacement2)

with open("app/src/main/java/com/example/ai/KavyaAI.kt", "w") as f:
    f.write(content)
