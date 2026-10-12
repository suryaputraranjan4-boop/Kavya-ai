package com.example.skills

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.agent.IntentCategory
import com.example.agent.IntentGateDecision
import com.example.agent.UniversalActionType
import com.example.data.AppDatabase
import com.example.data.MemoryEntity
import com.example.data.ScheduledTaskEntity
import com.example.services.KavyaAccessibilityService
import com.example.utils.AppResolver
import java.util.UUID

class AppControlSkill : KavyaSkill {
    override val id: String = "skill_app_control"
    override val name: String = "App Control Skill"
    override val description: String = "Launches verified installed Android applications using package manager."

    override fun canHandle(intent: IntentGateDecision): Boolean {
        return intent.category == IntentCategory.ACTION &&
                intent.steps.any { it.actionType == UniversalActionType.OPEN_APP }
    }

    override suspend fun execute(intent: IntentGateDecision, context: Context): SkillResult {
        val appResolver = AppResolver(context)
        val appName = intent.targetApp ?: return SkillResult(false, "No app name specified.")
        val resolution = appResolver.resolve(appName)

        if (resolution.matchedApp == null) {
            return SkillResult(false, "App '$appName' is not installed on this device.")
        }

        val router = com.example.utils.CommandRouter(context)
        val launchResult = router.launchSpecificApp(resolution.matchedApp!!)
        return SkillResult(
            success = launchResult.success,
            outputMessage = launchResult.output,
            actionType = "OPEN_APP",
            diagnosticData = mapOf(
                "appName" to resolution.matchedApp!!.appName,
                "packageName" to resolution.matchedApp!!.packageName,
                "verification" to (launchResult.diagnostic?.verification ?: "UNKNOWN")
            )
        )
    }
}

class MessagingSkill : KavyaSkill {
    override val id: String = "skill_messaging"
    override val name: String = "Messaging Skill"
    override val description: String = "Sends messages via WhatsApp or SMS."

    override fun canHandle(intent: IntentGateDecision): Boolean {
        return intent.steps.any {
            it.actionType in listOf(
                UniversalActionType.SEND_MESSAGE,
                UniversalActionType.SEND_WHATSAPP_MESSAGE,
                UniversalActionType.SEND_SMS
            )
        }
    }

    override suspend fun execute(intent: IntentGateDecision, context: Context): SkillResult {
        val step = intent.steps.firstOrNull {
            it.actionType in listOf(
                UniversalActionType.SEND_MESSAGE,
                UniversalActionType.SEND_WHATSAPP_MESSAGE,
                UniversalActionType.SEND_SMS
            )
        } ?: return SkillResult(false, "No messaging step found.")

        val recipient = step.recipient ?: "Friend"
        val message = step.messageText ?: step.param?.split("||")?.getOrNull(1) ?: "Hello"
        val isWhatsApp = intent.targetApp?.contains("WhatsApp", ignoreCase = true) == true ||
                step.actionType == UniversalActionType.SEND_WHATSAPP_MESSAGE

        return if (isWhatsApp) {
            try {
                val waIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://api.whatsapp.com/send?text=${Uri.encode(message)}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(waIntent)
                SkillResult(true, "WhatsApp message prepared for $recipient: \"$message\"", "SEND_WHATSAPP_MESSAGE")
            } catch (e: Exception) {
                SkillResult(false, "Could not open WhatsApp: ${e.message}", "SEND_WHATSAPP_MESSAGE")
            }
        } else {
            try {
                val smsIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("sms:")
                    putExtra("sms_body", message)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(smsIntent)
                SkillResult(true, "SMS drafted for $recipient: \"$message\"", "SEND_SMS")
            } catch (e: Exception) {
                SkillResult(false, "Could not draft SMS: ${e.message}", "SEND_SMS")
            }
        }
    }
}

class MediaSkill : KavyaSkill {
    override val id: String = "skill_media"
    override val name: String = "Media Playback Skill"
    override val description: String = "Plays media on YouTube or Spotify."

    override fun canHandle(intent: IntentGateDecision): Boolean {
        return intent.steps.any { it.actionType == UniversalActionType.PLAY }
    }

    override suspend fun execute(intent: IntentGateDecision, context: Context): SkillResult {
        val query = intent.query ?: intent.param ?: ""
        val isSpotify = intent.targetApp?.contains("Spotify", ignoreCase = true) == true
        return try {
            if (isSpotify) {
                val intentUri = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${Uri.encode(query)}")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intentUri)
                SkillResult(true, "Playing '$query' on Spotify.", "PLAY_MEDIA")
            } else {
                val intentUri = Intent(Intent.ACTION_SEARCH).apply {
                    setPackage("com.google.android.youtube")
                    putExtra("query", query)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intentUri)
                SkillResult(true, "Playing '$query' on YouTube.", "PLAY_MEDIA")
            }
        } catch (e: Exception) {
            SkillResult(false, "Failed to launch media player: ${e.message}", "PLAY_MEDIA")
        }
    }
}

class PhoneCallSkill : KavyaSkill {
    override val id: String = "skill_phone_call"
    override val name: String = "Phone Call Skill"
    override val description: String = "Initiates phone or WhatsApp calls."

    override fun canHandle(intent: IntentGateDecision): Boolean {
        return intent.steps.any {
            it.actionType in listOf(
                UniversalActionType.CALL,
                UniversalActionType.MAKE_PHONE_CALL,
                UniversalActionType.MAKE_WHATSAPP_CALL
            )
        }
    }

    override suspend fun execute(intent: IntentGateDecision, context: Context): SkillResult {
        val step = intent.steps.firstOrNull {
            it.actionType in listOf(
                UniversalActionType.CALL,
                UniversalActionType.MAKE_PHONE_CALL,
                UniversalActionType.MAKE_WHATSAPP_CALL
            )
        } ?: return SkillResult(false, "No call step found.")

        val target = step.recipient ?: step.param ?: ""
        return try {
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:${Uri.encode(target)}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(dialIntent)
            SkillResult(true, "Dialer opened for $target.", "MAKE_PHONE_CALL")
        } catch (e: Exception) {
            SkillResult(false, "Could not open dialer: ${e.message}", "MAKE_PHONE_CALL")
        }
    }
}

class SystemControlSkill : KavyaSkill {
    override val id: String = "skill_system_control"
    override val name: String = "System Navigation Skill"
    override val description: String = "Executes native OS navigation like Home, Back, and Recents."

    override fun canHandle(intent: IntentGateDecision): Boolean {
        return intent.category == IntentCategory.ACTION &&
                intent.steps.any { it.actionType in listOf(UniversalActionType.HOME, UniversalActionType.BACK, UniversalActionType.SWITCH_APP) }
    }

    override suspend fun execute(intent: IntentGateDecision, context: Context): SkillResult {
        val service = KavyaAccessibilityService.instance
            ?: return SkillResult(false, "Accessibility Service is not active for system navigation.")

        val step = intent.steps.firstOrNull() ?: return SkillResult(false, "No action step found.")
        val success = when (step.actionType) {
            UniversalActionType.HOME -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            UniversalActionType.BACK -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            UniversalActionType.SWITCH_APP -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
            else -> false
        }
        return SkillResult(success, if (success) "Action ${step.actionType.name} executed." else "Failed to dispatch action.")
    }
}

class WebResearchSkill : KavyaSkill {
    override val id: String = "skill_web_research"
    override val name: String = "Web & GitHub Research Skill"
    override val description: String = "Performs explicit web or GitHub research."

    override fun canHandle(intent: IntentGateDecision): Boolean {
        return intent.category == IntentCategory.RESEARCH
    }

    override suspend fun execute(intent: IntentGateDecision, context: Context): SkillResult {
        val query = intent.query ?: intent.rawPrompt
        return SkillResult(
            success = true,
            outputMessage = "Initiating research for: '$query'.",
            actionType = "RESEARCH",
            diagnosticData = mapOf("query" to query)
        )
    }
}

class MemorySkill : KavyaSkill {
    override val id: String = "skill_memory"
    override val name: String = "Persistent Memory Skill"
    override val description: String = "Saves and retrieves persistent user memory."

    override fun canHandle(intent: IntentGateDecision): Boolean {
        return intent.category in listOf(IntentCategory.MEMORY_SAVE, IntentCategory.MEMORY_RECALL)
    }

    override suspend fun execute(intent: IntentGateDecision, context: Context): SkillResult {
        val db = AppDatabase.getDatabase(context)
        val memoryDao = db.memoryDao()
        if (intent.category == IntentCategory.MEMORY_SAVE) {
            val content = intent.param ?: intent.rawPrompt
            val memory = MemoryEntity(
                key = "UserPref_${System.currentTimeMillis()}",
                content = content,
                category = "USER_PREFERENCE",
                importance = 5
            )
            return try {
                memoryDao.insertMemory(memory)
                SkillResult(true, "I will remember that: \"$content\".", "SAVE_MEMORY")
            } catch (e: Exception) {
                SkillResult(false, "Failed to save memory: ${e.message}", "SAVE_MEMORY")
            }
        }
        return SkillResult(true, "Retrieving memory for query.", "RECALL_MEMORY")
    }
}

class SchedulingSkill : KavyaSkill {
    override val id: String = "skill_scheduling"
    override val name: String = "Natural Language Scheduling Skill"
    override val description: String = "Creates persistent schedules and reminders."

    override fun canHandle(intent: IntentGateDecision): Boolean {
        return intent.category == IntentCategory.SCHEDULE
    }

    override suspend fun execute(intent: IntentGateDecision, context: Context): SkillResult {
        val prompt = intent.query ?: intent.rawPrompt
        val lower = prompt.lowercase(java.util.Locale.ROOT)
        val isDaily = lower.contains("every day") || lower.contains("har din") || lower.contains("har roz") || lower.contains("every morning")
        val isTomorrow = lower.contains("tomorrow") || lower.contains("kal")

        // Parse hour if mentioned, e.g., "9 am", "7 pm", "9:00"
        var hour = 9
        var minute = 0
        val timeRegex = Regex("(?i)(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?")
        timeRegex.find(lower)?.let { match ->
            val h = match.groupValues[1].toIntOrNull() ?: 9
            val m = match.groupValues[2].toIntOrNull() ?: 0
            val ampm = match.groupValues[3].lowercase(java.util.Locale.ROOT)
            hour = if (ampm == "pm" && h < 12) h + 12 else if (ampm == "am" && h == 12) 0 else h
            minute = m
        }

        val type = if (isDaily) "DAILY" else if (isTomorrow) "ONE_TIME" else "ONE_TIME"
        val schedule = ScheduledTaskEntity(
            id = UUID.randomUUID().toString(),
            title = prompt.take(30),
            promptOrAction = prompt,
            scheduleType = type,
            hour = hour,
            minute = minute,
            isActive = true
        )

        val db = AppDatabase.getDatabase(context)
        db.taskDao().insertScheduledTask(schedule)

        val timeStr = String.format(java.util.Locale.ROOT, "%02d:%02d", hour, minute)
        val confirmation = if (isDaily) {
            "Scheduled daily task at $timeStr: \"${prompt.take(40)}\"."
        } else {
            "Reminder set for $timeStr: \"${prompt.take(40)}\"."
        }

        return SkillResult(true, confirmation, "SCHEDULE_TASK", mapOf("scheduleId" to schedule.id))
    }
}

/**
 * Optional local shell execution bridge (Requirement 30).
 * Dynamic capability detection. Fails gracefully if Termux is not installed.
 */
class TermuxBridgeSkill : KavyaSkill {
    override val id: String = "skill_termux_bridge"
    override val name: String = "Termux CLI Bridge"
    override val description: String = "Optional command-line bridge to local Termux or shell execution."

    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.termux", 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun canHandle(intent: IntentGateDecision): Boolean {
        val lower = intent.rawPrompt.lowercase(java.util.Locale.ROOT)
        return lower.contains("termux") || lower.startsWith("run shell ") || lower.startsWith("cli:")
    }

    override suspend fun execute(intent: IntentGateDecision, context: Context): SkillResult {
        if (!isTermuxInstalled(context)) {
            return SkillResult(
                success = false,
                outputMessage = "Termux is not installed on this device. Kavya continues operating in native Android mode.",
                actionType = "TERMUX_CLI",
                diagnosticData = mapOf("installed" to "false")
            )
        }

        return SkillResult(
            success = true,
            outputMessage = "Termux is available for authorized task execution.",
            actionType = "TERMUX_CLI",
            diagnosticData = mapOf("installed" to "true")
        )
    }
}
