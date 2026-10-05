package com.example.skillopt

import android.content.Context
import android.util.Log
import com.example.ai.AIModelRouter
import com.example.bots.KavyaBotSpec
import com.example.bots.KavyaBotTeam
import java.io.File
import java.util.Locale

/**
 * Android-native SkillOpt-style layer:
 * trajectory -> bounded reflection/edit -> validation -> staged skill -> adoption.
 *
 * The model weights are never changed. The live SKILL.md is never replaced
 * automatically by an unvalidated model response.
 */
class SkillOptEngine(context: Context, private val modelRouter: AIModelRouter) {
    companion object {
        private const val TAG = "KavyaSkillOpt"
        private const val MAX_SKILL_CHARS = 12000
        private const val MAX_TRAJECTORIES = 12
    }

    private val store = SkillOptStore(context.applicationContext)

    fun initialize() {
        KavyaBotTeam.all.filter { it.role.name != "CEO" }.forEach {
            store.writeInitialSkill(it.id, initialSkill(it))
        }
    }

    fun recordExecution(botId: String, task: String, success: Boolean, output: String) {
        if (KavyaBotTeam.byId(botId) == null || botId == "ceo") return
        store.appendTrajectory(botId, task.take(1200), success, output.take(2000))
    }

    fun skillForBot(botId: String): String =
        store.readSkill(botId) ?: KavyaBotTeam.byId(botId)?.let(::initialSkill).orEmpty()

    suspend fun proposeImprovement(botId: String): SkillOptProposal? {
        val bot = KavyaBotTeam.byId(botId) ?: return null
        val current = store.readSkill(botId) ?: initialSkill(bot)
        val trajectories = store.recentTrajectories(botId, MAX_TRAJECTORIES)
        if (trajectories.isEmpty()) return null

        val raw = modelRouter.chat(
            prompt = optimizerPrompt(bot, current, trajectories),
            history = emptyList(),
            screenContext = null,
            memoryContext = "",
            isProactiveMode = false
        )
        val candidate = extract(raw) ?: return null
        val validation = validate(candidate, trajectories)
        if (!validation.accepted) {
            Log.w(TAG, "Rejected " + botId + ": " + validation.reason)
            return SkillOptProposal(botId, false, validation.reason, null)
        }
        val staged = store.stageCandidate(botId, candidate)
        return SkillOptProposal(botId, true, validation.reason, staged.absolutePath)
    }

    fun adopt(botId: String, stagedPath: String): Boolean =
        store.adoptCandidate(botId, File(stagedPath))

    private fun optimizerPrompt(
        bot: KavyaBotSpec,
        current: String,
        trajectories: List<String>
    ): String = "You are Kavya's SkillOpt optimizer.\n" +
        "Improve one specialist procedure without changing model weights.\n\n" +
        "BOT: " + bot.id + "\n" + bot.description + "\n\n" +
        "CURRENT SKILL:\n" + current + "\n\n" +
        "TRAJECTORIES:\n" + trajectories.joinToString("\n") + "\n\n" +
        "Rules:\n" +
        "- bounded ADD, DELETE, REPLACE, or REORDER only\n" +
        "- preserve useful instructions\n" +
        "- never invent tools or permissions\n" +
        "- never include secrets or private data\n" +
        "- return only complete SKILL.md inside a markdown fence\n" +
        "- maximum " + MAX_SKILL_CHARS + " characters"

    private fun extract(raw: String): String? {
        val fence = 96.toChar().toString().repeat(3)
        val match = Regex(
            Regex.escape(fence) + "(?:markdown|md)?\\s*([\\s\\S]*?)" + Regex.escape(fence),
            RegexOption.IGNORE_CASE
        ).find(raw) ?: return null
        return match.groupValues[1].trim().takeIf {
            it.isNotBlank() && it.length <= MAX_SKILL_CHARS
        }
    }

    private fun validate(candidate: String, trajectories: List<String>): Validation {
        val lower = candidate.lowercase(Locale.ROOT)
        if (!lower.contains("# ") || !lower.contains("##"))
            return Validation(false, "Invalid skill structure.")
        if (candidate.length > MAX_SKILL_CHARS)
            return Validation(false, "Candidate too large.")
        if (listOf("api_key=", "authorization: bearer", "private_key", "password=")
                .any { lower.contains(it) })
            return Validation(false, "Credential-like material detected.")

        val hasFailures = trajectories.any { it.contains("success=false") }
        if (hasFailures && listOf("verify", "recover", "failure", "check").none { lower.contains(it) })
            return Validation(false, "Candidate does not address recent failures.")

        return Validation(true, "Candidate passed local structure and safety gates.")
    }

    private fun initialSkill(bot: KavyaBotSpec): String =
        "# " + bot.name + "\n\n" +
        "## Purpose\n" + bot.description + "\n\n" +
        "## Procedure\n" +
        "- Understand the goal before acting.\n" +
        "- Use only assigned existing Kavya skills/tools.\n" +
        "- Verify state after actions.\n" +
        "- Recover or re-plan after failures.\n\n" +
        "## Safety\n" +
        "- Do not invent capabilities or permissions.\n" +
        "- Do not expose secrets or private data.\n\n" +
        "## Learning\n" +
        "- Improve only through bounded, validated proposals."
}

data class SkillOptProposal(
    val botId: String,
    val accepted: Boolean,
    val reason: String,
    val stagedPath: String?
)

private data class Validation(
    val accepted: Boolean,
    val reason: String
)
