package com.example.services

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import com.example.MainActivity
import com.example.agent.MicrophoneEngine
import com.example.orchestrator.ScreenObservationCoordinator
import com.example.voice.KavyaMicrophoneEngine

/**
 * Standard Android [VoiceInteractionService] allowing Kavya AI to be selected
 * as the system Default Digital Assistant while preserving foreground service mode
 * on all devices.
 *
 * Note on Android OS Wake-Word Constraints:
 * Android restricts hardware DSP `AlwaysOnHotwordDetector` to pre-installed OEM/system
 * assistants (`MANAGE_VOICE_KEYPHRASES` is a signature|privileged permission).
 * For third-party apps, the officially supported Google Assistant-like architecture is:
 * 1. Register as Default Digital Assistant (`RoleManager.ROLE_ASSISTANT` / VoiceInteractionService)
 * 2. Capture OS Assist screenshot & context on trigger (`onHandleScreenshot`)
 * 3. Launch visible Voice Popup + optional explicit foreground listening ("Hey Kavya") with
 *    a clear microphone notification and instant Stop control (never hidden background recording).
 */
class KavyaAssistantInteractionService : VoiceInteractionService() {
    companion object {
        private const val TAG = "KavyaAssistService"

        fun isDefaultDigitalAssistant(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rm = runCatching { context.getSystemService(RoleManager::class.java) }.getOrNull()
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                    return rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
                }
            }
            return runCatching {
                isActiveService(context, android.content.ComponentName(context, KavyaAssistantInteractionService::class.java))
            }.getOrDefault(false)
        }

        fun createDefaultAssistantSetupIntent(context: Context): Intent {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rm = runCatching { context.getSystemService(RoleManager::class.java) }.getOrNull()
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && !rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
                    return rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
                }
            }
            return Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    override fun onReady() {
        super.onReady()
        Log.i(TAG, "KavyaAssistantInteractionService ready.")
        KavyaMicrophoneEngine.init(applicationContext)
    }
}

class KavyaVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return KavyaVoiceInteractionSession(this)
    }
}

class KavyaVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        if (screenshot != null && !screenshot.isRecycled) {
            ScreenObservationCoordinator.registerAssistScreenshot(screenshot)
            Log.i("KavyaAssistSession", "Captured official OS Assist screenshot (${screenshot.width}x${screenshot.height})")
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Wake Kavya and trigger the Google Assistant-style activation popup
        KavyaMicrophoneEngine.init(context.applicationContext)
        MicrophoneEngine.getInstance(context.applicationContext).activateAssistantPopup()
        KavyaVoiceService.startService(context.applicationContext)

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_ASSIST
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { context.startActivity(launchIntent) }
        hide()
    }
}
