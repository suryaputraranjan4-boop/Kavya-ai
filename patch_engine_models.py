import re
with open("app/src/main/java/com/example/ai/KavyaVoiceEngine.kt", "r") as f:
    content = f.read()

models = """
enum class Sentiment(val label: String, val pitchMultiplier: Float, val speedMultiplier: Float) {
    HAPPY("Sweet and happy", 1.05f, 1.05f),
    SAD("Soft and sad", 0.95f, 0.90f),
    WARM("Warm and comforting", 0.98f, 0.95f),
    EMPATHETIC("Empathetic and gentle", 0.95f, 0.92f),
    EXCITED("Excited and lively", 1.1f, 1.1f),
    CALM("Calm and relaxed", 0.95f, 0.90f),
    WITTY("Witty and playful", 1.02f, 1.05f),
    CONFIDENT("Confident and clear", 1.0f, 1.0f),
    THOUGHTFUL("Thoughtful and pondering", 0.98f, 0.90f),
    PLAYFUL("Playful and adorable", 1.08f, 1.05f),
    FOCUSED("Focused and serious", 0.95f, 0.98f),
    NEUTRAL("Natural and conversational", 1.0f, 1.0f)
}

data class SpeechAnalysisResult(
    val originalText: String,
    val cleanSpokenText: String,
    val sentiment: Sentiment,
    val effectivePitch: Float,
    val effectiveSpeed: Float
)
"""

# Make cleanText public
content = content.replace("private fun cleanText", "fun cleanText")

# Append models
content = content + models

with open("app/src/main/java/com/example/ai/KavyaVoiceEngine.kt", "w") as f:
    f.write(content)
