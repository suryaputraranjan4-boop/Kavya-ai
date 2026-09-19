with open('app/src/main/java/com/example/viewmodel/KavyaViewModel.kt', 'r') as f:
    content = f.read()

target = """                            // Execute action using verified CommandRouter
                            val detailedResult = commandRouter.executeDetailed(aType, aParam)
                            executionResultStr = detailedResult.output
                            isAmbiguous = detailedResult.isAmbiguous
                            candidateApps = detailedResult.candidateApps
                            launchDiagnostic = detailedResult.diagnostic"""

replacement = """                            // Execute action using verified CommandRouter
                            if (aType == "SAVE_MEMORY") {
                                val parts = aParam.split("|")
                                if (parts.size >= 2) {
                                    val key = parts[0].trim()
                                    val value = parts[1].trim()
                                    memoryDao.insertMemory(com.example.data.MemoryEntity(key = key, value = value))
                                    executionResultStr = "Memory saved successfully: $key = $value"
                                } else {
                                    executionResultStr = "Failed to save memory: Invalid format."
                                }
                                launchDiagnostic = null
                            } else {
                                val detailedResult = commandRouter.executeDetailed(aType, aParam)
                                executionResultStr = detailedResult.output
                                isAmbiguous = detailedResult.isAmbiguous
                                candidateApps = detailedResult.candidateApps
                                launchDiagnostic = detailedResult.diagnostic
                            }"""

content = content.replace(target, replacement)

with open('app/src/main/java/com/example/viewmodel/KavyaViewModel.kt', 'w') as f:
    f.write(content)
print("VM Memory Action Patch applied")
