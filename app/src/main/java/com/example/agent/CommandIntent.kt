package com.example.agent

/**
 * Structured semantic user intents representing verified user goals.
 * Raw speech-to-text is strictly converted to CommandIntent before any action is executed.
 */
sealed class CommandIntent {

    /**
     * Intent to call a contact or phone number.
     * Must NOT be converted to numeric dial pad typing!
     */
    data class CallContact(
        val contactName: String,
        val phoneNumber: String? = null,
        val isWhatsApp: Boolean = false
    ) : CommandIntent()

    /**
     * Intent to send a message to a contact in WhatsApp or SMS.
     * Follows the 10-step verified WhatsApp/SMS state machine.
     */
    data class SendMessage(
        val app: String, // "WhatsApp" or "SMS"
        val recipient: String,
        val messageText: String
    ) : CommandIntent()

    /**
     * Intent to open a contact's chat without typing or sending any message.
     */
    data class OpenChat(
        val app: String, // "WhatsApp"
        val contactName: String
    ) : CommandIntent()

    /**
     * Intent to search, select, and play media in Spotify or YouTube.
     * Must NOT stop after search; proceeds to select and verify playback.
     */
    data class PlayMedia(
        val app: String, // "Spotify" or "YouTube"
        val mediaQuery: String,
        val targetType: String = "TRACK" // TRACK, ARTIST, ALBUM, PLAYLIST
    ) : CommandIntent()

    /**
     * Intent to launch an installed application with verified foreground state.
     */
    data class OpenApp(
        val appName: String,
        val packageName: String? = null
    ) : CommandIntent()

    /**
     * Intent to open a specific website in Chrome or browser.
     */
    data class OpenWebsite(
        val app: String = "Google Chrome",
        val destinationUrl: String,
        val destinationName: String
    ) : CommandIntent()

    /**
     * Intent to perform a web search on Google/Chrome with ONLY the query extracted.
     */
    data class WebSearch(
        val query: String,
        val engineOrApp: String = "Google"
    ) : CommandIntent()

    /**
     * Intent for hardware controls (Torch, Volume, Wi-Fi, Bluetooth, Screenshot).
     */
    data class SystemControl(
        val controlType: String,
        val param: String = ""
    ) : CommandIntent()

    /**
     * In-app or on-screen interactions (Scroll, Back, Home, Tap, Ordinal click).
     */
    data class InteractInApp(
        val action: String,
        val param: String = ""
    ) : CommandIntent()

    /**
     * General conversational or informational dialogue with Kavya AI.
     * NEVER triggers app launches or blind search fallbacks!
     */
    data class Conversation(
        val text: String
    ) : CommandIntent()

    data class Stop(val reason: String = "User requested stop") : CommandIntent()
    data class Sleep(val reason: String = "User requested sleep") : CommandIntent()
    data class Wake(val reason: String = "User requested wake") : CommandIntent()

    /**
     * Explicit memory instruction intent ("Ye yaad rakhna", "Remember this", "Aage se aise karna").
     */
    data class RememberInstruction(
        val memoryKey: String,
        val memoryContent: String,
        val category: String = "TASK_WORKFLOW",
        val targetApp: String? = null,
        val targetEntity: String? = null,
        val rawCommand: String = ""
    ) : CommandIntent()
}

/**
 * Lightweight Context Memory model that survives across the entire action chain.
 */
data class TaskContext(
    val originalCommand: String,
    val normalizedIntent: CommandCategory,
    val targetApp: String = "",
    val targetEntity: String = "",
    val currentPackage: String = "",
    val currentScreen: String = "",
    val currentStep: String = "",
    val expectedState: String = "",
    val lastAction: String = "",
    val lastObservedState: String = "",
    val retryCount: Int = 0,
    val confidence: Float = 1.0f,
    val failureReason: String? = null,
    val activeMemory: com.example.data.MemoryEntity? = null,
    val rememberedWorkflowSteps: List<String> = emptyList(),
    val rememberedPreferenceApplied: Boolean = false,
    val adaptedWorkflow: Boolean = false
)
