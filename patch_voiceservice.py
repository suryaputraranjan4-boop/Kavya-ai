import re

with open('app/src/main/java/com/example/services/KavyaVoiceService.kt', 'r') as f:
    content = f.read()

# Replace SpeechRecognizer with GeminiLiveClient
content = content.replace('import android.speech.RecognitionListener\nimport android.speech.RecognizerIntent\nimport android.speech.SpeechRecognizer', 'import com.example.ai.GeminiLiveClient')
content = content.replace('class KavyaVoiceService : Service(), RecognitionListener, LifecycleOwner', 'class KavyaVoiceService : Service(), LifecycleOwner')
content = content.replace('private var speechRecognizer: SpeechRecognizer? = null', 'private var geminiLiveClient: GeminiLiveClient? = null')

# In onCreate
init_block = """
        geminiLiveClient = GeminiLiveClient(
            context = this,
            apiSystem = apiSystem,
            androidAgent = androidAgent,
            onStateChange = { state ->
                when (state) {
                    "IDLE" -> voiceState = VoiceState.IDLE
                    "LISTENING" -> voiceState = VoiceState.LISTENING
                    "SPEAKING" -> voiceState = VoiceState.SPEAKING
                    "THINKING" -> voiceState = VoiceState.THINKING
                    "ERROR" -> voiceState = VoiceState.ERROR
                }
            },
            onCaption = { caption ->
                // Handle captions if needed, e.g. broadcast or show in orb
            }
        )
"""
content = re.sub(r'try \{\s*if \(SpeechRecognizer\.isRecognitionAvailable.*?Log\.w.*?\}\s*\}', init_block, content, flags=re.DOTALL)

# In startListening
start_block = """
        if (!isListening) {
            isListening = true
            isCompanionSessionActive = true
            geminiLiveClient?.startSession()
        }
"""
content = re.sub(r'if \(!isListening\) \{.*?voiceEngine\.stop\(\) // stop speaking if user interrupts\s*\}', start_block, content, flags=re.DOTALL)

# In stopListening
stop_block = """
        if (isListening) {
            isListening = false
            isCompanionSessionActive = false
            geminiLiveClient?.stopSession()
            voiceState = VoiceState.IDLE
        }
"""
content = re.sub(r'if \(isListening\) \{.*?speechRecognizer\?\.stopListening\(\)\s*\}', stop_block, content, flags=re.DOTALL)

# Remove SpeechRecognizer overrides
content = re.sub(r'override fun onReadyForSpeech.*?private fun processQuery', 'private fun processQuery', content, flags=re.DOTALL)

# Update onDestroy
content = content.replace('speechRecognizer?.destroy()', 'geminiLiveClient?.stopSession()')

with open('app/src/main/java/com/example/services/KavyaVoiceService.kt', 'w') as f:
    f.write(content)

