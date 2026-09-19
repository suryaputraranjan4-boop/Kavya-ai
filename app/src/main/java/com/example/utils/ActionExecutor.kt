package com.example.utils

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Standard action executor bridging voice service to deterministic CommandRouter.
 */
object ActionExecutor {
    private const val TAG = "KavyaActionExecutor"

    suspend fun launchApp(context: Context, appNameQuery: String): CommandExecutionResult {
        val router = CommandRouter(context)
        return router.executeDetailed("OPEN_APP", appNameQuery)
    }

    fun openSettings(context: Context, settingType: String): Boolean {
        val intent = Intent().apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            action = when (settingType.lowercase()) {
                "wifi" -> Settings.ACTION_WIFI_SETTINGS
                "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                "display", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
                "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
                "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                else -> Settings.ACTION_SETTINGS
            }
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    fun searchWeb(context: Context, query: String): Boolean {
        val cleanQuery = query.trim()
        val searchUrl = "https://www.google.com/search?q=${android.net.Uri.encode(cleanQuery)}"

        // 1. Try ACTION_WEB_SEARCH only if an activity exists on device to handle it
        try {
            val webSearchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(android.app.SearchManager.QUERY, cleanQuery)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (webSearchIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(webSearchIntent)
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_WEB_SEARCH failed: ${e.message}")
        }

        // 2. Guaranteed browser fallback via ACTION_VIEW
        return try {
            val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(searchUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Browser search fallback failed: ${e.message}")
            false
        }
    }
}
