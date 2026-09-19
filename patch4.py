with open('app/src/main/java/com/example/ui/screens/HomeScreen.kt', 'r') as f:
    content = f.read()

target = """                                } catch (e: Exception) {
                                    navController.navigate("chat")
                                }"""
replacement = """                                } catch (e: Exception) {
                                    // ignored
                                }"""
content = content.replace(target, replacement)

with open('app/src/main/java/com/example/ui/screens/HomeScreen.kt', 'w') as f:
    f.write(content)
print("Success 4")
