package com.example.bots

/**
 * Specialized orchestration identities over Kavya's existing skills.
 * Bots do not create a second execution engine.
 */
data class KavyaBot(
    val id: String,
    val name: String,
    val description: String,
    val skillIds: List<String>
)

object KavyaBotRegistry {
    private val bots = listOf(
        KavyaBot(
            "android_control_bot",
            "Android Control Bot",
            "Handles app launch, system navigation, messaging, calls, and media actions.",
            listOf("skill_app_control", "skill_system_control", "skill_messaging", "skill_phone_call", "skill_media")
        ),
        KavyaBot(
            "research_bot",
            "Research Bot",
            "Handles explicit web and GitHub research requests.",
            listOf("skill_web_research")
        ),
        KavyaBot(
            "memory_bot",
            "Memory Bot",
            "Handles persistent memory save and recall workflows.",
            listOf("skill_memory")
        ),
        KavyaBot(
            "scheduler_bot",
            "Scheduler Bot",
            "Handles reminders and recurring scheduled tasks.",
            listOf("skill_scheduling")
        ),
        KavyaBot(
            "local_tools_bot",
            "Local Tools Bot",
            "Handles optional local Termux/CLI capability discovery.",
            listOf("skill_termux_bridge")
        )
    )

    fun all(): List<KavyaBot> = bots

    fun forSkill(skillId: String): KavyaBot? =
        bots.firstOrNull { skillId in it.skillIds }
}
