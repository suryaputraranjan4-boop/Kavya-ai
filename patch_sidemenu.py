with open("app/src/main/java/com/example/ui/components/SideMenu.kt", "r") as f:
    content = f.read()

target = """        DrawerItem(Icons.AutoMirrored.Filled.Help, "Help & Support") { 
            // no-op for now, could add help screen later or map to advanced
            scope.launch { drawerState.close() } 
        }"""
        
replacement = """        DrawerItem(Icons.AutoMirrored.Filled.Help, "Help & Support") { 
            // no-op for now, could add help screen later or map to advanced
            scope.launch { drawerState.close() } 
        }
        DrawerItem(Icons.Default.BugReport, "Debug Dashboard") {
            navController.navigate("debug_dashboard") { launchSingleTop = true }
            scope.launch { drawerState.close() }
        }"""

content = content.replace(target, replacement)
with open("app/src/main/java/com/example/ui/components/SideMenu.kt", "w") as f:
    f.write(content)
