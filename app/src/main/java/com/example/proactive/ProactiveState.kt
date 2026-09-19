package com.example.proactive

enum class ProactiveFrequency(val displayName: String, val minIntervalMs: Long, val maxIntervalMs: Long) {
    QUIET("Relaxed & Quiet", 40000L, 85000L),
    BALANCED("Balanced (Recommended)", 18000L, 38000L),
    CHATTY("Lively & Conversational", 8000L, 20000L);

    companion object {
        fun fromString(value: String): ProactiveFrequency {
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: BALANCED
        }
    }
}

enum class ProactiveTriggerType {
    FOLLOW_UP_CONVERSATION,
    SCREEN_OBSERVATION,
    MEMORY_RECALL,
    CURRENT_ACTIVITY,
    SPONTANEOUS_ENGAGEMENT
}

data class ProactiveDecision(
    val shouldSpeak: Boolean,
    val reason: String,
    val text: String? = null,
    val triggerType: ProactiveTriggerType? = null,
    val priorityScore: Float = 0f
)
