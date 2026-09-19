with open("app/src/main/java/com/example/ui/navigation/NavGraph.kt", "r") as f:
    content = f.read()

target = """        composable("advanced") { AdvancedScreen(navController) }
        composable("proactive_settings") { ProactiveSettingsScreen(navController, viewModel) }
    }
}"""
replacement = """        composable("advanced") { AdvancedScreen(navController) }
        composable("proactive_settings") { ProactiveSettingsScreen(navController, viewModel) }
        composable("debug_dashboard") { DebugDashboardScreen() }
    }
}"""

content = content.replace(target, replacement)
with open("app/src/main/java/com/example/ui/navigation/NavGraph.kt", "w") as f:
    f.write(content)
