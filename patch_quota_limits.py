with open("app/src/main/java/com/example/api/QuotaManager.kt", "r") as f:
    content = f.read()

target = """        const val MAX_REQUESTS_PER_MINUTE = 15
        const val MAX_REQUESTS_PER_HOUR = 150"""
replacement = """        const val MAX_REQUESTS_PER_MINUTE = 60
        const val MAX_REQUESTS_PER_HOUR = 600"""

content = content.replace(target, replacement)
with open("app/src/main/java/com/example/api/QuotaManager.kt", "w") as f:
    f.write(content)
