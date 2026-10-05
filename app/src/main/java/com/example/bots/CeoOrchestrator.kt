package com.example.bots

import android.content.Context
import com.example.agent.AndroidAgent
import com.example.agent.ContextEngine
import com.example.agent.IntentGateDecision
import com.example.agent.TaskPlanner
import com.example.ai.AIModelRouter
import com.example.skillopt.SkillOptEngine
import com.example.skills.SkillsRegistry
import java.util.UUID

/**
 * Single CEO/orchestrator layer. It coordinates specialist bots but never
 * creates a second Android automation engine.
 */
class CeoOrchestrator(
    context: Context,
    private val modelRouter: AIModelRouter,
    private val androidAgent: AndroidAgent,
    private val taskPlanner: TaskPlanner
) {
    private val skillOpt = SkillOptEngine(context, modelRouter).also { it.initialize() }

    fun route(intent: IntentGateDecision): KavyaBotSpec =
        KavyaBotTeam.forIntent(intent.category)

    fun createTask(intent: IntentGateDecision): BotTask {
        val bot = route(intent)
        return BotTask(UUID.randomUUID().toString(), bot.id, intent.rawPrompt)
    }

    suspend fun execute(
        task: BotTask,
        intent: IntentGateDecision,
        context: Context
    ): BotResult {
        val bot = KavyaBotTeam.byId(task.botId)
            ?: return BotResult(task.botId, false, "Unknown bot")

        val skill = SkillsRegistry.findSkillForIntent(intent)
        if (skill != null) {
            val result = skill.execute(intent, context)
            skillOpt.recordExecution(
                botId = bot.id,
                task = task.goal,
                success = result.success,
                output = result.outputMessage
            )
            return BotResult(bot.id, result.success, result.outputMessage)
        }

        if (bot.role == BotRole.ANDROID_CONTROL) {
            val plan = taskPlanner.createPlan(task.goal, ContextEngine())
            if (plan != null && plan.steps.isNotEmpty()) {
                val outcome = androidAgent.executeTaskPlan(plan)
                return BotResult(bot.id, outcome.success, outcome.finalSpokenMessage)
            }
        }

        val answer = modelRouter.chat(
            prompt = task.goal,
            history = emptyList(),
            screenContext = null,
            memoryContext = "",
            isProactiveMode = false
        )
        return BotResult(bot.id, true, answer)
    }

    suspend fun learn(botId: String): SkillOptProposalView? {
        val proposal = skillOpt.proposeImprovement(botId) ?: return null
        return SkillOptProposalView(
            proposal.botId, proposal.accepted, proposal.reason, proposal.stagedPath
        )
    }

    fun adoptLearnedSkill(botId: String, stagedPath: String): Boolean =
        skillOpt.adopt(botId, stagedPath)
}

data class SkillOptProposalView(
    val botId: String,
    val staged: Boolean,
    val reason: String,
    val stagedPath: String?
)
