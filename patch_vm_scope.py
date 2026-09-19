with open('app/src/main/java/com/example/viewmodel/KavyaViewModel.kt', 'r') as f:
    content = f.read()

target = """                            if (isAmbiguous) {
                                cleanResponse = detailedResult.output
                            } else if (!detailedResult.success && detailedResult.output.isNotBlank()) {
                                cleanResponse = detailedResult.output
                            }"""

replacement = """                            if (isAmbiguous) {
                                cleanResponse = executionResultStr
                            } else if (executionResultStr.isNotBlank() && launchDiagnostic != null && launchDiagnostic.verification.startsWith("FAIL")) {
                                cleanResponse = executionResultStr
                            }"""

content = content.replace(target, replacement)

with open('app/src/main/java/com/example/viewmodel/KavyaViewModel.kt', 'w') as f:
    f.write(content)
print("VM Scope Patch applied")
