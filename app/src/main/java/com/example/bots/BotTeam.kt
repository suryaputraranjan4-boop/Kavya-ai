package com.example.bots

import com.example.agent.IntentCategory

enum class BotRole { CEO, ANDROID_CONTROL, RESEARCH, MEMORY, SCHEDULER, GENERAL }

data class KavyaBotSpec(
    val id: String,
    val name: String,
    val role: BotRole,
    val description: String,
    val intentCategories: Set<IntentCategory> = emptySet(),
    val skillIds: Set<String> = emptySet()
)

object KavyaBotTeam {
    val all: List<KavyaBotSpec> = listOf(
        KavyaBotSpec("ceo", "Kavya CEO", BotRole.CEO,
            "Top-level coordinator that decomposes the user's goal and delegates work."),
        KavyaBotSpec("android_control", "Android Control Bot", BotRole.ANDROID_CONTROL,
            "Executes Android/device actions through existing audited skills and AndroidAgent.",
            setOf(IntentCategory.ACTION, IntentCategory.MULTI_STEP_TASK),
            setOf("app_control", "system_control", "messaging", "phone_call", "media")),
        KavyaBotSpec("research", "Research Bot", BotRole.RESEARCH,
            "Handles explicit research and information gathering.",
            setOf(IntentCategory.RESEARCH), setOf("web_research")),
        KavyaBotSpec("memory", "Memory Bot", BotRole.MEMORY,
            "Stores and recalls durable user-approved memories.",
            setOf(IntentCategory.MEMORY_SAVE, IntentCategory.MEMORY_RECALL), setOf("memory")),
        KavyaBotSpec("scheduler", "Scheduler Bot", BotRole.SCHEDULER,
            "Handles reminders and scheduled work.",
            setOf(IntentCategory.SCHEDULE), setOf("scheduling")),
        KavyaBotSpec("general", "General Bot", BotRole.GENERAL,
            "Conversational fallback using the currently selected Kavya AI provider.",
            setOf(IntentCategory.CHAT, IntentCategory.QUESTION, IntentCategory.EXPLANATION,
                IntentCategory.UNKNOWN_OR_AMBIGUOUS))
    )

    fun byId(id: String): KavyaBotSpec? = all.firstOrNull { it.id == id }

    fun forIntent(category: IntentCategory): KavyaBotSpec =
        all.firstOrNull { category in it.intentCategories } ?: byId("general")!!
}

data class BotTask(
    val id: String,
    val botId: String,
    val goal: String,
    val parentTaskId: String? = null,
    val priority: Int = 0
)

data class BotResult(
    val botId: String,
    val success: Boolean,
    val summary: String,
    val delegatedTasks: List<BotTask> = emptyList()
)
