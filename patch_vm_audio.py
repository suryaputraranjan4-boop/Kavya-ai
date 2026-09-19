with open('app/src/main/java/com/example/ai/VoiceManager.kt', 'r') as f:
    content = f.read()

content = content.replace("fun processAndSpeak(rawText: String): SpeechAnalysisResult {", "fun processAndSpeak(rawText: String, enqueue: Boolean = false): SpeechAnalysisResult {")
content = content.replace("speak(analysis.cleanSpokenText, analysis.sentiment)", "speak(analysis.cleanSpokenText, analysis.sentiment, enqueue)")
content = content.replace("fun speak(text: String, explicitSentiment: Sentiment? = null) {", "fun speak(text: String, explicitSentiment: Sentiment? = null, enqueue: Boolean = false) {")
content = content.replace("stop()\n        if (geminiPlayer != null && kavyaAI != null) {", "if (!enqueue) stop()\n        if (geminiPlayer != null && kavyaAI != null) {")
content = content.replace("geminiPlayer.playAudioBytes(audioBytes)", "geminiPlayer.playAudioBytes(audioBytes, enqueue)")

with open('app/src/main/java/com/example/ai/VoiceManager.kt', 'w') as f:
    f.write(content)
print("VoiceManager Patch applied")
