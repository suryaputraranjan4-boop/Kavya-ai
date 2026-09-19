with open('app/src/main/java/com/example/ui/screens/HomeScreen.kt', 'r') as f:
    content = f.read()

import re

# 1. Add latestKavyaCaption observation
target1 = "val isScreenSharing by viewModel.isScreenSharing.collectAsState()"
replacement1 = target1 + "\n    val latestCaption by viewModel.latestKavyaCaption.collectAsState()"
content = content.replace(target1, replacement1)

# 2. Add TypewriterText composable before KavyaVoiceOrb
target2 = """            // LOWER CENTER: Microphone
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {"""
replacement2 = """            // LOWER CENTER: Microphone
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                AnimatedVisibility(visible = !latestCaption.isNullOrBlank()) {
                    TypewriterText(
                        text = latestCaption ?: "",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.W400,
                            letterSpacing = 0.5.sp,
                            color = Color.White.copy(alpha = 0.9f)
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(horizontal = 32.dp, vertical = 16.dp)
                            .fillMaxWidth()
                    )
                }"""
content = content.replace(target2, replacement2)

# 3. Prevent auto-navigation to chat
target3 = """            ChatBar(
                onSend = {
                    viewModel.sendMessage(it)
                    navController.navigate("chat")
                },
                onMicClick = {
                    if (canStart) {
                        try {
                            val intent = Intent(context, KavyaVoiceService::class.java)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(intent)
                            } else {
                                context.startService(intent)
                            }
                            viewModel.setVoiceState(VoiceState.LISTENING)
                        } catch (e: Exception) {
                            navController.navigate("chat")
                        }
                    } else {
                        navController.navigate("permissions")
                    }
                },"""
replacement3 = """            ChatBar(
                onSend = {
                    viewModel.sendMessage(it)
                },
                onMicClick = {
                    if (canStart) {
                        try {
                            val intent = Intent(context, KavyaVoiceService::class.java)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(intent)
                            } else {
                                context.startService(intent)
                            }
                            viewModel.setVoiceState(VoiceState.LISTENING)
                        } catch (e: Exception) {
                            // ignored
                        }
                    } else {
                        navController.navigate("permissions")
                    }
                },"""
content = content.replace(target3, replacement3)

with open('app/src/main/java/com/example/ui/screens/HomeScreen.kt', 'w') as f:
    f.write(content)
print("Success 3")
