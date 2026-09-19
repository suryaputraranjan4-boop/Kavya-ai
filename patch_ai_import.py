with open('app/src/main/java/com/example/ai/KavyaAI.kt', 'r') as f:
    content = f.read()

import_statement = "import kotlinx.coroutines.flow.flowOn\n"
if import_statement not in content:
    content = content.replace("import kotlinx.coroutines.flow.Flow", "import kotlinx.coroutines.flow.Flow\n" + import_statement)
    if "import kotlinx.coroutines.flow.Flow" not in content:
        # Just prepend it after package
        content = content.replace("package com.example.ai\n", "package com.example.ai\n\n" + import_statement)
    with open('app/src/main/java/com/example/ai/KavyaAI.kt', 'w') as f:
        f.write(content)
    print("Import patch applied")
