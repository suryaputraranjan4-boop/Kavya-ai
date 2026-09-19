with open('app/src/main/java/com/example/viewmodel/KavyaViewModel.kt', 'r') as f:
    content = f.read()

target_block = """                    val memoryList = memoryDao.getAllMemories().map { "${it.key}: ${it.value}" }.joinToString("\\n")
                    val response = aiClient.chat(prompt, historyEntities, screenContext, memoryList)
                    rawModelResponse = response
                    
                    val actionRegex = "<ACTION:([^:>]+)(?::([^>]*))?>".toRegex()
                    val matches = actionRegex.findAll(response).toList()
                    cleanResponse = voiceManager.cleanText(response)"""

replacement_block = """                    val memoryList = memoryDao.getAllMemories().map { "${it.key}: ${it.value}" }.joinToString("\\n")
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
                        if (currentSentence.contains(".") || currentSentence.contains("!") || currentSentence.contains("?") || currentSentence.contains("\\n")) {
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

content = content.replace(target_block, replacement_block)

# Also we need to remove the old voiceManager.processAndSpeak at the end of the method since we are streaming it now!
target_voice_end = """                if (_autoSpeak.value && !isErrorResponse && cleanResponse.isNotBlank()) {
                    val responseForVoice = if (rawModelResponse.isNotBlank()) rawModelResponse else cleanResponse
                    _pendingCaption.value = cleanResponse
                    voiceManager.processAndSpeak(responseForVoice)
                } else {
                    _latestKavyaCaption.value = cleanResponse
                }"""

replacement_voice_end = """                // Streaming voice is handled during collection.
                if (!_autoSpeak.value || isErrorResponse || cleanResponse.isBlank()) {
                    _latestKavyaCaption.value = cleanResponse
                }"""
content = content.replace(target_voice_end, replacement_voice_end)

with open('app/src/main/java/com/example/viewmodel/KavyaViewModel.kt', 'w') as f:
    f.write(content)
print("VM Stream Patch applied")
