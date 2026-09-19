with open("app/src/main/java/com/example/ai/KavyaAI.kt", "r") as f:
    content = f.read()

target = """        // Graceful error message without confusing raw HTTP codes
        return@withContext when {"""
replacement = """        } finally {
            com.example.api.QuotaManager.instance.releaseQuota(requestId)
        }
        // Graceful error message without confusing raw HTTP codes
        return@withContext when {"""

target2 = """            }
            else -> {
                "[sad] I'm having a brief connection hitch. Please try asking again in a moment."
            }
        }
    }"""
replacement2 = """            }
            else -> {
                "[sad] I'm having a brief connection hitch. Please try asking again in a moment."
            }
        }
    }"""

if target in content:
    content = content.replace(target, replacement)
    with open("app/src/main/java/com/example/ai/KavyaAI.kt", "w") as f:
        f.write(content)
    print("Patched chat function")
else:
    print("Could not find target for chat function")
