with open("app/src/main/java/com/example/ui/screens/DebugDashboardScreen.kt", "r") as f:
    content = f.read()

target = """        DashboardCard("Gemini API Requests") {"""
replacement = """        val globalState by com.example.state.KavyaStateManager.state.collectAsState()
        
        DashboardCard("Global State Manager (KAVYA_STATE_MANAGER)") {
            StatRow("Conversation", globalState.conversationState)
            StatRow("Voice", globalState.voiceState.name)
            StatRow("CGI", globalState.cgiState)
            StatRow("Active Tool", globalState.activeTool ?: "None")
            StatRow("App Context", globalState.currentApp ?: "None")
        }
        
        DashboardCard("Gemini API Requests") {"""

content = content.replace(target, replacement)
with open("app/src/main/java/com/example/ui/screens/DebugDashboardScreen.kt", "w") as f:
    f.write(content)
