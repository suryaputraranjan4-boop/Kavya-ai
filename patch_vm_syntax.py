with open('app/src/main/java/com/example/viewmodel/KavyaViewModel.kt', 'r') as f:
    content = f.read()

import re

# Clean up the bad newline and set up the stream block
# Look for the exact block from `val memoryList =` to `cleanResponse = voiceManager.cleanText(response)`

start_str = "                    val memoryList = "
end_str = "cleanResponse = voiceManager.cleanText(response)"

start_idx = content.find(start_str)
end_idx = content.find(end_str) + len(end_str)

if start_idx != -1 and end_idx != -1:
    target = content[start_idx:end_idx]
    
    replacement = """                    val memoryList = memoryDao.getAllMemories().map { "${it.key}: ${it.value}" }.joinToString("\\n")
                    var fullResponse = ""
                    var currentSentence = ""
                    var cleanResponseBuilder = StringBuilder()
                    
                    aiClient.streamChat(prompt, historyEntities, screenContext, memoryList).collect { chunk ->
                        fullResponse += chunk
                        currentSentence += chunk
                        
                        val cleanChunk = voiceManager.cleanText(chunk)
                        if (cleanChunk.isNotBlank()) {
                            cleanResponseBuilder.append(cleanChunk)
                            _pendingCaption.value = cleanResponseBuilder.toString()
                            _latestKavyaCaption.value = cleanResponseBuilder.toString()
                        }

                        // Split on sentence boundaries to start voice early
                        if (currentSentence.contains(".") || currentSentence.contains("!") || currentSentence.contains("?") || currentSentence.contains("\\n") || currentSentence.contains("।")) {
                            val sentenceToSpeak = voiceManager.cleanText(currentSentence)
                            if (sentenceToSpeak.isNotBlank() && !sentenceToSpeak.contains("<ACTION")) {
                                voiceManager.processAndSpeak(sentenceToSpeak, enqueue = true)
                            }
                            currentSentence = ""
                        }
                    }
                    
                    // Flush remaining
                    val remainingSentence = voiceManager.cleanText(currentSentence)
                    if (remainingSentence.isNotBlank() && !remainingSentence.contains("<ACTION")) {
                        voiceManager.processAndSpeak(remainingSentence, enqueue = true)
                    }

                    rawModelResponse = fullResponse
                    
                    val actionRegex = "<ACTION:([^:>]+)(?::([^>]*))?>".toRegex()
                    val matches = actionRegex.findAll(fullResponse).toList()
                    cleanResponse = cleanResponseBuilder.toString()"""
                    
    content = content.replace(target, replacement)
    
with open('app/src/main/java/com/example/viewmodel/KavyaViewModel.kt', 'w') as f:
    f.write(content)
print("VM Syntax Patch applied")
