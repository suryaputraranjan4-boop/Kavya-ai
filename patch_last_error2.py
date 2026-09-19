with open("app/src/main/java/com/example/ai/KavyaAI.kt", "r") as f:
    content = f.read()

target = """                } catch (e: HttpException) {
                    lastError = e
                    val code = e.code()"""
                    
replacement = """                } catch (e: HttpException) {
                    lastError = e
                    val code = e.code()
                    com.example.api.QuotaManager.instance.apply {
                        lastErrorType = "HttpException"
                        lastErrorTime = System.currentTimeMillis()
                        lastErrorRequestType = "TEXT"
                        if (code == 429) rateLimitErrorsCount++
                    }"""

content = content.replace(target, replacement)

target2 = """                } catch (e: IOException) {
                    lastError = e"""
                    
replacement2 = """                } catch (e: IOException) {
                    lastError = e
                    com.example.api.QuotaManager.instance.apply {
                        lastErrorType = "IOException"
                        lastErrorTime = System.currentTimeMillis()
                        lastErrorRequestType = "TEXT"
                    }"""

content = content.replace(target2, replacement2)

target3 = """                } catch (e: Exception) {
                    lastError = e"""
                    
replacement3 = """                } catch (e: Exception) {
                    lastError = e
                    com.example.api.QuotaManager.instance.apply {
                        lastErrorType = "Exception"
                        lastErrorTime = System.currentTimeMillis()
                        lastErrorRequestType = "TEXT"
                    }"""

content = content.replace(target3, replacement3)

with open("app/src/main/java/com/example/ai/KavyaAI.kt", "w") as f:
    f.write(content)
