package com.example.utils

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import com.example.services.KavyaAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

data class CommandExecutionResult(
    val output: String,
    val success: Boolean,
    val isAmbiguous: Boolean = false,
    val candidateApps: List<InstalledApp> = emptyList(),
    val diagnostic: AppLaunchDiagnostic? = null
)

/**
 * Executes requested actions following strict execution and verification flow:
 * USER COMMAND
 * → extract exact requested app name or device action
 * → query Android PackageManager & Accessibility Services
 * → execute action (app launch, search, UI click, UI type, screenshot, panel toggle)
 * → verify result & return conversational confirmation.
 */
class CommandRouter(private val context: Context) {

    companion object {
        private const val TAG = "KavyaCommandRouter"
        @Volatile
        var lastDiagnostic: AppLaunchDiagnostic? = null
            private set
        @Volatile
        var isTorchOn: Boolean = false
    }

    val appResolver = AppResolver(context)

    data class SmsRequest(val recipient: String, val message: String)

    fun parseCallCommand(input: String): String? {
        val trimmed = input.trim()
        val regex1 = "^(?:call|phone|dial)\\s+(.+)$".toRegex(RegexOption.IGNORE_CASE)
        regex1.find(trimmed)?.let {
            val contact = it.groupValues[1].replace(Regex("(?i)\\s+(ko|pe|par|karo|lagao)$"), "").trim()
            if (contact.isNotBlank()) return contact
        }

        val regex2 = "^(.+?)\\s+(?:ko|pe|par)?\\s*(?:call\\s+karo|phone\\s+karo|phone\\s+lagao|phone\\s+milao|कॉल\\s+करो|फ़ोन\\s+करो|फोन\\s+लगाओ)$".toRegex(RegexOption.IGNORE_CASE)
        regex2.find(trimmed)?.let {
            val contact = it.groupValues[1].trim()
            if (contact.isNotBlank()) return contact
        }
        return null
    }

    fun parseSmsCommand(input: String): SmsRequest? {
        val trimmed = input.trim()
        val regex1 = "^(?:send\\s+)?(?:message|sms)\\s+(?:to\\s+)?(.+?)\\s+(?:that|ki|bhejo|saying)?\\s*(.+)$".toRegex(RegexOption.IGNORE_CASE)
        regex1.find(trimmed)?.let {
            val recip = it.groupValues[1].replace(Regex("(?i)\\s+(ko|pe|par)$"), "").trim()
            val msg = it.groupValues[2].trim()
            if (recip.isNotBlank()) return SmsRequest(recip, msg)
        }

        val regex2 = "^(.+?)\\s+ko\\s+(?:message|sms)\\s+(?:karo|bhejo|kar\\s+do)\\s*(.*)$".toRegex(RegexOption.IGNORE_CASE)
        regex2.find(trimmed)?.let {
            val recip = it.groupValues[1].trim()
            val msg = it.groupValues[2].trim()
            if (recip.isNotBlank()) return SmsRequest(recip, msg.ifBlank { "Hello" })
        }
        return null
    }

    fun isDirectDeviceCommand(input: String): Boolean {
        val trimmed = input.trim().lowercase(java.util.Locale.ROOT)
        if (trimmed.isEmpty()) return false

        // Conversational greetings ("hi", "hello", "kaise ho") must NEVER trigger device commands or app launches!
        val interpreted = com.example.agent.CommandInterpreter.interpret(input)
        if (interpreted is com.example.agent.CommandIntent.Conversation) {
            return false
        }

        // Call & SMS
        if (parseCallCommand(input) != null) return true
        if (parseSmsCommand(input) != null) return true

        // Ordinal website / video / search result selection (e.g. "open 2nd website", "3rd result", "dusri website kholo")
        if (parseOrdinalSelectionCommand(input) != null) return true

        // Compound commands like "Open youtube and search hey"
        if (appResolver.parseCompoundCommand(input) != null) return true

        // Direct app launch commands
        if (appResolver.isDirectAppLaunchQuery(input)) return true

        // Quick settings / Panel / System controls
        return trimmed.contains("screenshot") ||
               trimmed.contains("flashlight") || trimmed.contains("torch") ||
               trimmed.contains("dnd") || trimmed.contains("do not disturb") ||
               trimmed.contains("silent mode") || trimmed.contains("vibrate mode") ||
               trimmed.contains("auto rotate") || trimmed.contains("autorotate") || trimmed.contains("rotation") ||
               trimmed.contains("quick settings") || trimmed.contains("notification panel") ||
               trimmed.contains("wifi") || trimmed.contains("wi-fi") ||
               trimmed.contains("bluetooth") ||
               trimmed.startsWith("volume ") || trimmed.endsWith(" volume") || trimmed.contains("volume up") || trimmed.contains("volume down") || trimmed.contains("mute") ||
               trimmed == "settings" || trimmed.startsWith("open settings") ||
               trimmed == "camera" || trimmed.startsWith("open camera") ||
               trimmed.startsWith("search ") || trimmed.startsWith("google ") ||
               trimmed.startsWith("tap ") || trimmed.startsWith("click ") || trimmed.startsWith("press ") ||
               trimmed.startsWith("write ") || trimmed.startsWith("type ") ||
               trimmed.startsWith("scroll ")
    }

    suspend fun executeDirectUserCommand(
        input: String,
        onBeforeExecute: (suspend (spokenAnnouncement: String) -> Unit)? = null
    ): CommandExecutionResult {
        val trimmed = input.trim()
        val lower = trimmed.lowercase(java.util.Locale.ROOT)

        // 0. Call action
        val callTarget = parseCallCommand(trimmed)
        if (callTarget != null) {
            return handleCall(callTarget)
        }

        // 0.1 SMS / Message action
        val sms = parseSmsCommand(trimmed)
        if (sms != null) {
            return handleSms(sms.recipient, sms.message)
        }

        // 0.2 Ordinal selection (website, video, result, link)
        val ordinalCmd = parseOrdinalSelectionCommand(trimmed)
        if (ordinalCmd != null) {
            val (ordinalIndex, announcement) = ordinalCmd
            return handleSelectOrdinal(ordinalIndex, announcement)
        }

        // 1. Compound Search / Play / Type command
        val compound = appResolver.parseCompoundCommand(trimmed)
        if (compound != null) {
            return handleCompoundCommand(compound)
        }

        // 2. Screenshot
        if (lower.contains("screenshot") || lower.contains("screen shot")) {
            return handleTakeScreenshot()
        }

        // 3. Torch / Flashlight
        if (lower.contains("flashlight") || lower.contains("torch")) {
            val turnOn = !lower.contains("off") && !lower.contains("band")
            return handleTorch(turnOn)
        }

        // 4. Do Not Disturb / Silent
        if (lower.contains("dnd") || lower.contains("do not disturb") || lower.contains("silent")) {
            val enable = !lower.contains("off") && !lower.contains("disable") && !lower.contains("hatao")
            return handleDnd(enable)
        }

        // 5. Auto-Rotate
        if (lower.contains("auto rotate") || lower.contains("autorotate") || lower.contains("rotation")) {
            val enable = if (lower.contains("off") || lower.contains("disable")) false else if (lower.contains("on") || lower.contains("enable")) true else null
            return handleAutoRotate(enable)
        }

        // 6. Quick Settings & Notifications panel
        if (lower.contains("quick settings") || lower.contains("control center")) {
            return handlePanel("QUICK_SETTINGS")
        }
        if (lower.contains("notification panel") || lower.contains("notifications shade") || lower.contains("show notifications")) {
            return handlePanel("NOTIFICATIONS")
        }

        // 7. Connectivity & Settings
        if (lower.contains("wifi") || lower.contains("wi-fi")) {
            val announcement = "Main Wi-Fi settings open kar rahi hoon..."
            onBeforeExecute?.invoke(announcement)
            return handleOpenSettings(Settings.ACTION_WIFI_SETTINGS, "Wi-Fi Settings")
        }
        if (lower.contains("bluetooth")) {
            val announcement = "Main Bluetooth settings open kar rahi hoon..."
            onBeforeExecute?.invoke(announcement)
            return handleOpenSettings(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth Settings")
        }
        if (lower.startsWith("volume ") || lower.endsWith(" volume") || lower.contains("volume up") || lower.contains("volume down") || lower.contains("mute")) {
            return handleSetVolume(trimmed)
        }
        if (lower == "settings" || lower == "open settings" || lower == "settings kholo") {
            val announcement = "Main settings open kar rahi hoon..."
            onBeforeExecute?.invoke(announcement)
            return handleOpenSettings(Settings.ACTION_SETTINGS, "Settings")
        }

        // 8. Hands-free UI Click / Tap
        if (lower.startsWith("tap ") || lower.startsWith("click ") || lower.startsWith("press ")) {
            val target = trimmed.substringAfter(" ").trim()
            return handleUiClick(target)
        }

        // 9. Hands-free UI Type / Write
        if (lower.startsWith("write ") || lower.startsWith("type ") || lower.startsWith("enter ")) {
            val textToType = trimmed.substringAfter(" ").trim()
            return handleUiType("", textToType)
        }

        // 10. Scroll
        if (lower.startsWith("scroll ")) {
            val dir = trimmed.substringAfter(" ").trim()
            return handleUiScroll(dir)
        }

        // Default direct path: App Resolution
        val extractedAppName = appResolver.extractAppNameFromNaturalLanguage(trimmed)
        val targetApp = if (extractedAppName.isNotBlank()) extractedAppName else trimmed
        return handleOpenApp(targetApp)
    }

    fun handleCall(contactOrNumber: String): CommandExecutionResult {
        val clean = contactOrNumber.trim()
        val isDigits = clean.all { it.isDigit() || it == '+' || it == ' ' || it == '-' }
        val uri = if (isDigits && clean.length >= 3) {
            Uri.parse("tel:${clean.replace(" ", "")}")
        } else {
            Uri.parse("tel:")
        }
        val intent = Intent(Intent.ACTION_DIAL, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            CommandExecutionResult("Main $clean ko phone mila rahi hoon...", true)
        } catch (e: Exception) {
            CommandExecutionResult("Call error: ${e.message}", false)
        }
    }

    fun handleSms(recipient: String, message: String): CommandExecutionResult {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:")
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            CommandExecutionResult("Main $recipient ko message bhej rahi hoon...", true)
        } catch (e: Exception) {
            CommandExecutionResult("SMS error: ${e.message}", false)
        }
    }

    suspend fun executeDetailed(
        actionType: String,
        actionParam: String,
        onBeforeExecute: (suspend (spokenAnnouncement: String) -> Unit)? = null
    ): CommandExecutionResult {
        com.example.state.KavyaStateManager.updateAction(tool = actionType, action = actionParam)
        return try {
            when (actionType) {
                "OPEN_APP" -> {
                    onBeforeExecute?.invoke("Main $actionParam open kar rahi hoon...")
                    handleOpenApp(actionParam)
                }
                "OPEN_AND_SEARCH" -> {
                    val parts = actionParam.split(":", limit = 2)
                    if (parts.size == 2) {
                        onBeforeExecute?.invoke("Main ${parts[0]} open kar rahi hoon aur '${parts[1]}' search kar rahi hoon...")
                        handleCompoundCommand(AppResolver.CompoundCommand(parts[0], parts[1], "SEARCH"))
                    } else {
                        onBeforeExecute?.invoke("Main $actionParam open kar rahi hoon...")
                        handleOpenApp(actionParam)
                    }
                }
                "SEARCH_WEB" -> {
                    handleSearchWeb(actionParam)
                }
                "CALL", "PHONE" -> {
                    handleCall(actionParam)
                }
                "SMS", "MESSAGE" -> {
                    val parts = actionParam.split(":", limit = 2)
                    val recip = parts.getOrNull(0) ?: actionParam
                    val msg = parts.getOrNull(1) ?: "Hello"
                    handleSms(recip, msg)
                }
                "SCREENSHOT" -> handleTakeScreenshot()
                "TORCH", "FLASHLIGHT" -> handleTorch(actionParam.equals("ON", true))
                "DND" -> handleDnd(actionParam.equals("ON", true))
                "ROTATION", "AUTO_ROTATE" -> handleAutoRotate(if (actionParam.equals("ON", true)) true else if (actionParam.equals("OFF", true)) false else null)
                "PANEL" -> handlePanel(actionParam)
                "WIFI" -> handleOpenSettings(Settings.ACTION_WIFI_SETTINGS, "Wi-Fi Settings")
                "BLUETOOTH" -> handleOpenSettings(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth Settings")
                "OPEN_SETTINGS" -> handleOpenSettings(Settings.ACTION_SETTINGS, "Settings")
                "VOLUME" -> handleSetVolume(actionParam)
                "CAMERA" -> handleOpenApp("Camera")
                "UI_CLICK" -> handleUiClick(actionParam)
                "UI_TYPE" -> {
                    val parts = actionParam.split(":", limit = 2)
                    if (parts.size == 2) {
                        handleUiType(parts[0], parts[1])
                    } else {
                        handleUiType("", actionParam)
                    }
                }
                "UI_SCROLL" -> handleUiScroll(actionParam)
                "READ_SCREEN", "SCREEN_SUMMARY" -> {
                    val screen = KavyaAccessibilityService.instance?.getScreenContext() ?: "Screen reading unavailable (Accessibility disabled)"
                    CommandExecutionResult(screen, true)
                }
                "GLOBAL_ACTION" -> {
                    val action = when(actionParam.uppercase()) {
                        "BACK" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                        "HOME" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                        "RECENTS" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                        "NOTIFICATIONS" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                        "QUICK_SETTINGS" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                        "POWER_DIALOG" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
                        "LOCK_SCREEN" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else -1
                        "TAKE_SCREENSHOT", "SCREENSHOT" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else -1
                        else -> -1
                    }
                    if (action != -1) {
                        val success = KavyaAccessibilityService.instance?.performGlobal(action) == true
                        CommandExecutionResult(
                            output = if (success) "Executed $actionParam" else "Accessibility service not ready for $actionParam",
                            success = success
                        )
                    } else {
                        CommandExecutionResult("Action $actionParam not supported on this Android version", false)
                    }
                }
                "VERIFY_FOREGROUND" -> {
                    val current = KavyaAccessibilityService.instance?.getForegroundPackage() ?: "Unknown"
                    val expected = actionParam.trim()
                    val match = current.contains(expected, ignoreCase = true) || expected.contains(current, ignoreCase = true)
                    CommandExecutionResult(
                        output = if (match) "Foreground verified: $current" else "Foreground MISMATCH: expected '$expected' but active is '$current'",
                        success = match
                    )
                }
                else -> CommandExecutionResult("Unknown Action Type: $actionType", false)
            }
        } catch (e: Exception) {
            CommandExecutionResult("Execution Error: ${e.message}", false)
        }
    }

    suspend fun handleCompoundCommand(compound: AppResolver.CompoundCommand): CommandExecutionResult {
        val appName = compound.appName.trim()
        val query = compound.searchQuery.trim()
        val lowerApp = appName.lowercase(Locale.ROOT)

        // If user says "open any app and search things" or "search things", handle as web search
        if (lowerApp.contains("any app") || lowerApp == "app" || lowerApp.isBlank()) {
            return handleSearchWeb(query)
        }

        // 1. YouTube specialized deep search
        if (lowerApp.contains("youtube") || lowerApp == "yt") {
            val ytUri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
            val isPlayRequest = compound.actionType.equals("PLAY", ignoreCase = true) ||
                    isPlayOrOpenVideoIntent(query) ||
                    isPlayOrOpenVideoIntent(compound.appName)
            try {
                val ytIntent = Intent(Intent.ACTION_VIEW, ytUri).apply {
                    setPackage("com.google.android.youtube")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (context.packageManager.resolveActivity(ytIntent, 0) != null) {
                    context.startActivity(ytIntent)
                    if (isPlayRequest) {
                        triggerAutoPlayFirstVideo()
                        return CommandExecutionResult("Main YouTube open karke video play kar rahi hoon...", true)
                    }
                    return CommandExecutionResult("Main YouTube open kar rahi hoon aur '$query' search kar rahi hoon...", true)
                } else {
                    // Fallback to browser YouTube search
                    val browserYt = Intent(Intent.ACTION_VIEW, ytUri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(browserYt)
                    if (isPlayRequest) {
                        triggerAutoPlayFirstVideo()
                        return CommandExecutionResult("Main YouTube open karke video play kar rahi hoon...", true)
                    }
                    return CommandExecutionResult("Main YouTube open kar rahi hoon aur '$query' search kar rahi hoon...", true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Direct YouTube intent failed, falling back to browser: ${e.message}")
                try {
                    val browserYt = Intent(Intent.ACTION_VIEW, ytUri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(browserYt)
                    if (isPlayRequest) {
                        triggerAutoPlayFirstVideo()
                        return CommandExecutionResult("Main YouTube open karke video play kar rahi hoon...", true)
                    }
                    return CommandExecutionResult("Main YouTube open kar rahi hoon aur '$query' search kar rahi hoon...", true)
                } catch (e2: Exception) {
                    Log.e(TAG, "YouTube browser fallback failed: ${e2.message}")
                }
            }
        }

        // 2. Play Store
        if (lowerApp.contains("play store") || lowerApp.contains("playstore") || lowerApp == "store") {
            try {
                val marketUri = Uri.parse("market://search?q=${Uri.encode(query)}&c=apps")
                val marketIntent = Intent(Intent.ACTION_VIEW, marketUri).apply {
                    setPackage("com.android.vending")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (context.packageManager.resolveActivity(marketIntent, 0) != null) {
                    context.startActivity(marketIntent)
                    return CommandExecutionResult("Main Play Store open kar rahi hoon aur '$query' search kar rahi hoon...", true)
                } else {
                    val webStore = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=${Uri.encode(query)}&c=apps")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(webStore)
                    return CommandExecutionResult("Main Play Store open kar rahi hoon aur '$query' search kar rahi hoon...", true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Play Store search failed: ${e.message}")
            }
        }

        // 3. Google Maps
        if (lowerApp.contains("maps") || lowerApp == "map" || lowerApp.contains("google maps")) {
            try {
                val mapUri = Uri.parse("geo:0,0?q=${Uri.encode(query)}")
                val mapIntent = Intent(Intent.ACTION_VIEW, mapUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (context.packageManager.resolveActivity(mapIntent, 0) != null) {
                    context.startActivity(mapIntent)
                    return CommandExecutionResult("Main Google Maps open kar rahi hoon aur '$query' search kar rahi hoon...", true)
                } else {
                    val webMap = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(query)}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(webMap)
                    return CommandExecutionResult("Main Google Maps open kar rahi hoon aur '$query' search kar rahi hoon...", true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Maps search failed: ${e.message}")
            }
        }

        // 4. Spotify / Music
        if (lowerApp.contains("spotify") || lowerApp.contains("music") || lowerApp.contains("gana") || lowerApp.contains("gaana")) {
            try {
                val spotifyUri = Uri.parse("spotify:search:${Uri.encode(query)}")
                val spotifyIntent = Intent(Intent.ACTION_VIEW, spotifyUri).apply {
                    setPackage("com.spotify.music")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (context.packageManager.resolveActivity(spotifyIntent, 0) != null) {
                    context.startActivity(spotifyIntent)
                    return CommandExecutionResult("Main Spotify open kar rahi hoon aur '$query' search kar rahi hoon...", true)
                }

                val mediaIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(android.app.SearchManager.QUERY, query)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (context.packageManager.resolveActivity(mediaIntent, 0) != null) {
                    context.startActivity(mediaIntent)
                    return CommandExecutionResult("Main Music open kar rahi hoon aur '$query' play kar rahi hoon...", true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Media intent failed: ${e.message}")
            }
        }

        // 5. Web browser / Chrome / Google
        if (lowerApp.contains("chrome") || lowerApp.contains("google") || lowerApp.contains("browser") || lowerApp.contains("web") || lowerApp.contains("internet")) {
            return handleSearchWeb(query)
        }

        // 6. General App Launch + Automated Accessibility UI Search
        val launchResult = handleOpenApp(appName)
        if (!launchResult.success) {
            // Graceful fallback: If app isn't installed, search for it on web
            val webFallback = handleSearchWeb("$appName $query")
            if (webFallback.success) {
                return CommandExecutionResult("Main Google par '$appName $query' search kar rahi hoon...", true)
            }
            return launchResult
        }

        // Allow app window to transition to foreground
        delay(500)

        val accessibility = KavyaAccessibilityService.instance
        if (accessibility != null) {
            val searched = accessibility.performAppSearch(query)
            if (searched) {
                return CommandExecutionResult("Main ${launchResult.diagnostic?.resolvedApp ?: appName} open kar rahi hoon aur '$query' search kar rahi hoon...", true)
            }
        }

        return CommandExecutionResult("Main ${launchResult.diagnostic?.resolvedApp ?: appName} open kar rahi hoon...", true)
    }

    private fun handleTakeScreenshot(): CommandExecutionResult {
        val accessibility = KavyaAccessibilityService.instance
        if (accessibility != null) {
            val success = accessibility.takeScreenshot()
            return if (success) {
                CommandExecutionResult("Screenshot taken successfully.", true)
            } else {
                CommandExecutionResult("Screenshot command sent via Accessibility.", true)
            }
        }
        return CommandExecutionResult("Please enable Kavya Accessibility Service to take screenshots hands-free.", false)
    }

    private fun handleTorch(turnOn: Boolean): CommandExecutionResult {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            val cameraId = cameraManager?.cameraIdList?.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: "0"

            cameraManager?.setTorchMode(cameraId, turnOn)
            isTorchOn = turnOn
            CommandExecutionResult("Flashlight turned ${if (turnOn) "ON" else "OFF"}", true)
        } catch (e: Exception) {
            CommandExecutionResult("Unable to toggle flashlight: ${e.message}", false)
        }
    }

    private fun handleDnd(enable: Boolean): CommandExecutionResult {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return CommandExecutionResult("Audio service unavailable", false)
        return try {
            if (enable) {
                audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                CommandExecutionResult("Do Not Disturb / Silent mode activated", true)
            } else {
                audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                CommandExecutionResult("Do Not Disturb turned off. Normal ringtone restored", true)
            }
        } catch (e: Exception) {
            // If system requires notification policy permission, open settings
            val intent = Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                CommandExecutionResult("Opened Do Not Disturb Settings", true)
            } catch (e2: Exception) {
                CommandExecutionResult("Could not adjust DND mode: ${e.message}", false)
            }
        }
    }

    private fun handleAutoRotate(enable: Boolean?): CommandExecutionResult {
        return try {
            val current = Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
            val target = if (enable != null) (if (enable) 1 else 0) else (if (current == 1) 0 else 1)
            val success = Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, target)
            val stateStr = if (target == 1) "enabled" else "disabled"
            if (success) {
                CommandExecutionResult("Auto-rotate is now $stateStr", true)
            } else {
                handleOpenSettings(Settings.ACTION_DISPLAY_SETTINGS, "Display Settings")
            }
        } catch (e: Exception) {
            handleOpenSettings(Settings.ACTION_DISPLAY_SETTINGS, "Display Settings for Auto-Rotate")
        }
    }

    private fun handlePanel(panelType: String): CommandExecutionResult {
        val accessibility = KavyaAccessibilityService.instance
        if (accessibility != null) {
            val action = if (panelType.contains("QUICK", ignoreCase = true)) {
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            } else {
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            }
            val ok = accessibility.performGlobal(action)
            return CommandExecutionResult(if (ok) "Opened ${panelType.lowercase()} panel" else "Failed to open panel", ok)
        }
        return CommandExecutionResult("Accessibility service is needed to open panels hands-free.", false)
    }

    private fun handleUiClick(targetText: String): CommandExecutionResult {
        val accessibility = KavyaAccessibilityService.instance
            ?: return CommandExecutionResult("Accessibility Service is not enabled. Please enable it in Settings.", false)

        val success = accessibility.clickNodeByText(targetText)
        return if (success) {
            CommandExecutionResult("Tapped on '$targetText'", true)
        } else {
            CommandExecutionResult("Could not find '$targetText' on current screen.", false)
        }
    }

    private fun handleUiType(targetField: String, textToType: String): CommandExecutionResult {
        val accessibility = KavyaAccessibilityService.instance
            ?: return CommandExecutionResult("Accessibility Service is not enabled. Please enable it in Settings.", false)

        val success = accessibility.typeInNodeByText(targetField, textToType)
        return if (success) {
            accessibility.clickSearchOrSubmitButton()
            CommandExecutionResult("Typed '$textToType' into ${if (targetField.isNotBlank()) "'$targetField'" else "input"}", true)
        } else {
            CommandExecutionResult("Could not find an editable input field to type into.", false)
        }
    }

    private fun handleUiScroll(direction: String): CommandExecutionResult {
        val accessibility = KavyaAccessibilityService.instance
            ?: return CommandExecutionResult("Accessibility Service is not enabled.", false)

        val success = accessibility.scroll(direction)
        return if (success) {
            CommandExecutionResult("Scrolled $direction", true)
        } else {
            CommandExecutionResult("No scrollable view found on screen.", false)
        }
    }

    private suspend fun handleOpenApp(requestedName: String): CommandExecutionResult {
        val resolution = appResolver.resolve(requestedName)

        // If user explicitly asked for website (e.g. "YouTube website")
        if (resolution.isWebsiteRequest) {
            val query = appResolver.extractAppNameFromNaturalLanguage(requestedName)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(intent)
                CommandExecutionResult("Opened web browser for: $query", true)
            } catch (e: Exception) {
                CommandExecutionResult("Failed to open browser: ${e.message}", false)
            }
        }

        // None confidence -> Strict rejection. Never guess or open random fallback.
        if (resolution.confidence == MatchConfidence.NONE) {
            val diag = AppLaunchDiagnostic(
                requestedApp = requestedName,
                resolvedApp = "None",
                packageName = "None",
                launchIntent = "None",
                confidence = resolution.confidence.name,
                foregroundBefore = KavyaAccessibilityService.instance?.getForegroundPackage() ?: "com.example",
                foregroundAfter = "Unchanged",
                verification = "FAIL - I couldn't find that app"
            )
            lastDiagnostic = diag
            return CommandExecutionResult("I couldn't find that app: '$requestedName'.", false, diagnostic = diag)
        }

        // Ambiguous match: multiple apps match (e.g. YouTube vs YouTube Music) -> Prompt user
        if (resolution.confidence == MatchConfidence.AMBIGUOUS) {
            val names = resolution.candidateApps.joinToString(", ") { it.appName }
            val diag = AppLaunchDiagnostic(
                requestedApp = requestedName,
                resolvedApp = "Ambiguous (${resolution.candidateApps.size} apps)",
                packageName = resolution.candidateApps.joinToString(", ") { it.packageName },
                launchIntent = "Prompt User",
                confidence = resolution.confidence.name,
                foregroundBefore = KavyaAccessibilityService.instance?.getForegroundPackage() ?: "com.example",
                foregroundAfter = "Unchanged",
                verification = "AMBIGUOUS - Disambiguation Required"
            )
            lastDiagnostic = diag
            return CommandExecutionResult(
                output = "Which app would you like to open? Found: $names",
                success = false,
                isAmbiguous = true,
                candidateApps = resolution.candidateApps,
                diagnostic = diag
            )
        }

        val app = resolution.matchedApp
            ?: return CommandExecutionResult("I couldn't find that app: '$requestedName'.", false)

        return executeLaunchAndVerify(requestedName, app, resolution.confidence.name)
    }

    suspend fun launchSpecificApp(app: InstalledApp): CommandExecutionResult {
        return executeLaunchAndVerify(app.appName, app, "EXACT")
    }

    private suspend fun executeLaunchAndVerify(
        requestedName: String,
        app: InstalledApp,
        confidenceName: String
    ): CommandExecutionResult {
        // Obtain launchable Intent for THAT package
        val launchIntent = appResolver.validateAndPrepareLaunch(app)
        val fgBefore = KavyaAccessibilityService.instance?.getForegroundPackage() ?: "com.example"

        if (launchIntent == null) {
            val diag = AppLaunchDiagnostic(
                requestedApp = requestedName,
                resolvedApp = app.appName,
                packageName = app.packageName,
                launchIntent = "NULL",
                confidence = confidenceName,
                foregroundBefore = fgBefore,
                foregroundAfter = "Unchanged",
                verification = "FAIL - No launchable intent found"
            )
            lastDiagnostic = diag
            return CommandExecutionResult("Could not launch '${app.appName}'. Application is disabled or has no launcher activity.", false, diagnostic = diag)
        }

        return try {
            // Launch the exact package
            context.startActivity(launchIntent)

            // Dynamic polling with stabilization for Android WindowManager transition
            val accessibilityService = KavyaAccessibilityService.instance
            var fgAfter = accessibilityService?.getForegroundPackage() ?: app.packageName
            var isForegroundVerified = accessibilityService == null

            if (accessibilityService != null) {
                val timeoutMs = 1500L
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < timeoutMs) {
                    fgAfter = accessibilityService.getForegroundPackage()
                    val match = fgAfter.equals(app.packageName, ignoreCase = true) ||
                            fgAfter.contains(app.packageName, ignoreCase = true) ||
                            app.packageName.contains(fgAfter, ignoreCase = true) ||
                            (app.packageName == "com.whatsapp" && fgAfter.contains("whatsapp")) ||
                            (app.packageName == "com.google.android.youtube" && fgAfter.contains("youtube")) ||
                            (app.packageName == "com.spotify.music" && fgAfter.contains("spotify")) ||
                            (app.packageName == "com.instagram.android" && fgAfter.contains("instagram"))

                    if (match) {
                        isForegroundVerified = true
                        break
                    }
                    delay(80)
                }
                // If still not verified by package name, verify via active root window node package
                if (!isForegroundVerified) {
                    val root = accessibilityService.rootInActiveWindow
                    val rootPkg = root?.packageName?.toString() ?: ""
                    if (rootPkg.contains(app.packageName, ignoreCase = true) || app.packageName.contains(rootPkg, ignoreCase = true)) {
                        fgAfter = rootPkg
                        isForegroundVerified = true
                    }
                }
            }

            if (isForegroundVerified) {
                val diag = AppLaunchDiagnostic(
                    requestedApp = requestedName,
                    resolvedApp = app.appName,
                    packageName = app.packageName,
                    launchIntent = app.launcherActivity ?: "MainActivity",
                    confidence = confidenceName,
                    foregroundBefore = fgBefore,
                    foregroundAfter = fgAfter,
                    verification = "PASS"
                )
                lastDiagnostic = diag
                Log.d(TAG, "Successfully launched and verified ${app.appName} ($fgAfter)")
                val isHindiOrHinglish = requestedName.any { it in '\u0900'..'\u097F' } ||
                    requestedName.lowercase(Locale.ROOT).let { it.contains("kholo") || it.contains("chalao") || it.contains("jao") }
                val successMsg = if (isHindiOrHinglish) "${app.appName} खुल गया।" else "Opened ${app.appName}."
                CommandExecutionResult(successMsg, true, diagnostic = diag)
            } else {
                val diag = AppLaunchDiagnostic(
                    requestedApp = requestedName,
                    resolvedApp = app.appName,
                    packageName = app.packageName,
                    launchIntent = app.launcherActivity ?: "MainActivity",
                    confidence = confidenceName,
                    foregroundBefore = fgBefore,
                    foregroundAfter = fgAfter,
                    verification = "FAIL - Foreground verification failed (active: $fgAfter, expected: ${app.packageName})"
                )
                lastDiagnostic = diag
                Log.e(TAG, "Foreground verification mismatch: expected ${app.packageName}, got $fgAfter")
                val isHindiOrHinglish = requestedName.any { it in '\u0900'..'\u097F' } ||
                    requestedName.lowercase(Locale.ROOT).let { it.contains("kholo") || it.contains("chalao") || it.contains("jao") }
                val failMsg = if (isHindiOrHinglish) "${app.appName} open नहीं हुआ।" else "Failed to open ${app.appName}."
                CommandExecutionResult(failMsg, false, diagnostic = diag)
            }
        } catch (e: Exception) {
            val diag = AppLaunchDiagnostic(
                requestedApp = requestedName,
                resolvedApp = app.appName,
                packageName = app.packageName,
                launchIntent = app.launcherActivity ?: "Error",
                confidence = confidenceName,
                foregroundBefore = fgBefore,
                foregroundAfter = "Failed",
                verification = "FAIL - Exception: ${e.message}"
            )
            lastDiagnostic = diag
            CommandExecutionResult("Failed to open ${app.appName}: ${e.message}", false, diagnostic = diag)
        }
    }

    private fun handleOpenSettings(settingsAction: String, label: String): CommandExecutionResult {
        return try {
            val intent = Intent(settingsAction).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            CommandExecutionResult("Opened $label", true)
        } catch (e: Exception) {
            CommandExecutionResult("Failed to open $label: ${e.message}", false)
        }
    }

    private fun handleSetVolume(param: String): CommandExecutionResult {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return CommandExecutionResult("Audio manager unavailable", false)
        return try {
            when {
                param.contains("UP", ignoreCase = true) -> {
                    audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    CommandExecutionResult("Volume increased", true)
                }
                param.contains("DOWN", ignoreCase = true) -> {
                    audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    CommandExecutionResult("Volume decreased", true)
                }
                param.contains("MUTE", ignoreCase = true) -> {
                    audioManager.adjustVolume(AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    CommandExecutionResult("Volume muted", true)
                }
                param.contains("UNMUTE", ignoreCase = true) -> {
                    audioManager.adjustVolume(AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                    CommandExecutionResult("Volume unmuted", true)
                }
                param.contains("MAX", ignoreCase = true) -> {
                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
                    CommandExecutionResult("Volume set to Maximum", true)
                }
                param.contains("%") -> {
                    val percent = param.replace("%", "").trim().toIntOrNull() ?: 50
                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val target = (max * (percent / 100f)).toInt()
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
                    CommandExecutionResult("Volume set to $percent%", true)
                }
                else -> {
                    audioManager.adjustVolume(AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
                    CommandExecutionResult("Volume UI displayed", true)
                }
            }
        } catch (e: Exception) {
            CommandExecutionResult("Failed to adjust volume: ${e.message}", false)
        }
    }

    fun handleSearchWeb(query: String): CommandExecutionResult {
        val cleanQuery = query.trim()
        val encodedQuery = Uri.encode(cleanQuery)
        val searchUrl = "https://www.google.com/search?q=$encodedQuery"

        // 1. Try ACTION_WEB_SEARCH only if an activity exists on device to handle it
        try {
            val webSearchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(android.app.SearchManager.QUERY, cleanQuery)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (webSearchIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(webSearchIntent)
                return CommandExecutionResult("Main Google par '$cleanQuery' search kar rahi hoon...", true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_WEB_SEARCH failed: ${e.message}")
        }

        // 2. Try Chrome directly if installed
        val pm = context.packageManager
        try {
            val chromeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                setPackage("com.android.chrome")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (chromeIntent.resolveActivity(pm) != null) {
                context.startActivity(chromeIntent)
                return CommandExecutionResult("Main Google par '$cleanQuery' search kar rahi hoon...", true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Chrome direct launch failed: ${e.message}")
        }

        // 3. Fallback to standard web browser
        return try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
            CommandExecutionResult("Main Google par '$cleanQuery' search kar rahi hoon...", true)
        } catch (e: Exception) {
            Log.e(TAG, "Search fallback failed: ${e.message}")
            CommandExecutionResult("Could not launch browser: ${e.message}", false)
        }
    }

    private fun triggerAutoPlayFirstVideo() {
        CoroutineScope(Dispatchers.Default).launch {
            delay(1300)
            val service = KavyaAccessibilityService.instance
            if (service != null) {
                for (attempt in 0..12) {
                    val root = service.rootInActiveWindow
                    if (root != null) {
                        val firstVideo = service.findOrdinalContentNode(root, 0)
                        if (firstVideo != null) {
                            service.clickNode(firstVideo)
                            break
                        }
                    }
                    delay(300)
                }
            }
        }
    }

    private fun isPlayOrOpenVideoIntent(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return lower.contains("play") || lower.contains("chalao") || lower.contains("bajao") ||
                lower.contains("sunao") || lower.contains("video open") || lower.contains("open video") ||
                lower.contains("music") || lower.contains("gana") || lower.contains("gaana") ||
                lower.contains("video") || lower.contains("khol kar") || lower.contains("open karke do") ||
                lower.contains("open karo") || lower.contains("chala do") || lower.contains("play karo")
    }

    fun parseOrdinalSelectionCommand(input: String): Pair<Int, String>? {
        val lower = input.trim().lowercase(Locale.ROOT)

        val hasOrdinalKeyword = lower.contains("website") || lower.contains("result") ||
                lower.contains("link") || lower.contains("video") || lower.contains("page") ||
                lower.contains("kholo") || lower.contains("chalao") || lower.contains("open") ||
                lower.contains("wali") || lower.contains("wala")

        if (!hasOrdinalKeyword) return null

        // 1st result
        if (lower.contains("1st website") || lower.contains("first website") ||
            lower.contains("1st result") || lower.contains("first result") ||
            lower.contains("1st video") || lower.contains("first video") ||
            lower.contains("1st link") || lower.contains("first link") ||
            lower.contains("pehli website") || lower.contains("pehla video") ||
            lower.contains("pahli website") || lower.contains("pehla result") ||
            lower.contains("pehli wali") || lower.contains("pehla wala") ||
            (lower.contains("website") && (lower.contains("first") || lower.contains("1st") || lower.contains("pehli")))
        ) {
            val isVideo = lower.contains("video") || lower.contains("play") || lower.contains("chalao")
            val msg = if (isVideo) "Pehla video play kar rahi hoon..." else "Pehli website open kar rahi hoon..."
            return Pair(0, msg)
        }

        // 2nd result
        if (lower.contains("2nd website") || lower.contains("second website") ||
            lower.contains("2nd result") || lower.contains("second result") ||
            lower.contains("2nd video") || lower.contains("second video") ||
            lower.contains("2nd link") || lower.contains("second link") ||
            lower.contains("dusri website") || lower.contains("doosri website") ||
            lower.contains("dusra video") || lower.contains("doosra video") ||
            lower.contains("dusra result") || lower.contains("doosra result") ||
            lower.contains("dusri wali") || lower.contains("dusra wala") ||
            lower.contains("2nd or third website") ||
            (lower.contains("website") && (lower.contains("second") || lower.contains("2nd") || lower.contains("dusri") || lower.contains("doosri")))
        ) {
            val isVideo = lower.contains("video") || lower.contains("play") || lower.contains("chalao")
            val msg = if (isVideo) "Doosra video play kar rahi hoon..." else "Doosri website open kar rahi hoon..."
            return Pair(1, msg)
        }

        // 3rd result
        if (lower.contains("3rd website") || lower.contains("third website") ||
            lower.contains("3rd result") || lower.contains("third result") ||
            lower.contains("3rd video") || lower.contains("third video") ||
            lower.contains("3rd link") || lower.contains("third link") ||
            lower.contains("teesri website") || lower.contains("teesra video") ||
            lower.contains("teesra result") || lower.contains("teesri wali") || lower.contains("teesra wala") ||
            (lower.contains("website") && (lower.contains("third") || lower.contains("3rd") || lower.contains("teesri")))
        ) {
            val isVideo = lower.contains("video") || lower.contains("play") || lower.contains("chalao")
            val msg = if (isVideo) "Teesra video play kar rahi hoon..." else "Teesri website open kar rahi hoon..."
            return Pair(2, msg)
        }

        // 4th result
        if (lower.contains("4th website") || lower.contains("fourth website") ||
            lower.contains("4th result") || lower.contains("fourth result") ||
            lower.contains("4th video") || lower.contains("fourth video") ||
            lower.contains("chauthi website") || lower.contains("chautha video")
        ) {
            val isVideo = lower.contains("video") || lower.contains("play") || lower.contains("chalao")
            val msg = if (isVideo) "Chautha video play kar rahi hoon..." else "Chauthi website open kar rahi hoon..."
            return Pair(3, msg)
        }

        // Last result
        if (lower.contains("last website") || lower.contains("aakhri website") ||
            lower.contains("last result") || lower.contains("aakhri result") ||
            lower.contains("last video") || lower.contains("aakhri video") ||
            lower.contains("aakhri wali") || lower.contains("aakhri wala")
        ) {
            val isVideo = lower.contains("video") || lower.contains("play") || lower.contains("chalao")
            val msg = if (isVideo) "Aakhri video play kar rahi hoon..." else "Aakhri result open kar rahi hoon..."
            return Pair(-1, msg)
        }

        return null
    }

    private suspend fun handleSelectOrdinal(ordinalIndex: Int, announcement: String): CommandExecutionResult {
        val service = KavyaAccessibilityService.instance
            ?: return CommandExecutionResult("Accessibility Service enable nahi hai.", false)

        for (attempt in 0..11) {
            val root = service.rootInActiveWindow
            if (root != null) {
                val node = service.findOrdinalContentNode(root, ordinalIndex)
                if (node != null) {
                    val clicked = service.clickNode(node)
                    if (clicked) {
                        return CommandExecutionResult(announcement, true)
                    }
                }
            }
            delay(250)
        }
        val friendly = when (ordinalIndex) {
            0 -> "pehla"
            1 -> "doosra"
            2 -> "teesra"
            3 -> "chautha"
            -1 -> "aakhri"
            else -> "${ordinalIndex + 1}"
        }
        return CommandExecutionResult("Screen par $friendly result nahi mila.", false)
    }
}
