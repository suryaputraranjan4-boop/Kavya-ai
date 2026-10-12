package com.example.skills

import android.content.Context
import com.example.agent.IntentGateDecision
import com.example.agent.IntentCategory

data class SkillResult(
    val success: Boolean,
    val outputMessage: String,
    val actionType: String? = null,
    val diagnosticData: Map<String, String> = emptyMap()
)

interface KavyaSkill {
    val id: String
    val name: String
    val description: String
    fun canHandle(intent: IntentGateDecision): Boolean
    suspend fun execute(intent: IntentGateDecision, context: Context): SkillResult
}

/**
 * Modular Skill, Action, Tool, and Function registry (Requirement 10).
 * Decouples domain capabilities into clean, audited skills.
 */
object SkillsRegistry {
    private val registeredSkills = mutableListOf<KavyaSkill>()

    init {
        registerDefaultSkills()
    }

    private fun registerDefaultSkills() {
        registerSkill(AppControlSkill())
        registerSkill(MessagingSkill())
        registerSkill(MediaSkill())
        registerSkill(PhoneCallSkill())
        registerSkill(SystemControlSkill())
        registerSkill(WebResearchSkill())
        registerSkill(MemorySkill())
        registerSkill(SchedulingSkill())
        registerSkill(TermuxBridgeSkill())
    }

    fun registerSkill(skill: KavyaSkill) {
        synchronized(registeredSkills) {
            if (registeredSkills.none { it.id == skill.id }) {
                registeredSkills.add(skill)
            }
        }
    }

    fun findSkillForIntent(intent: IntentGateDecision): KavyaSkill? {
        synchronized(registeredSkills) {
            return registeredSkills.firstOrNull { it.canHandle(intent) }
        }
    }

    fun getAllSkills(): List<KavyaSkill> {
        synchronized(registeredSkills) {
            return registeredSkills.toList()
        }
    }
}
