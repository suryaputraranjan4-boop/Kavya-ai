package com.example.skillopt

import android.content.Context
import android.util.Log
import com.example.ai.AIModelRouter
import com.example.bots.KavyaBot
import com.example.bots.KavyaBotRegistry
import java.io.File
import java.util.Locale

/**
 * SkillOpt-style optimization layer for Kavya.
 *
 * Microsoft SkillOpt is a Python research/deployment engine. Kavya is Android/Kotlin,
 * so this layer keeps the same important contract without embedding Python:
 *
 * trajectory -> reflection/edit proposal -> bounded candidate -> gate
 * -> staged artifact -> explicit adoption.
 */
class SkillOptEngine(
    context: Context,
    private val modelRouter: AIModelRouter
) {
    companion object {
        private const val TAG = "KavyaSkillOpt"
        private const val MAX_SKILL_CHARS = 12_000
        private const val MAX_TRAJECTORIES = 12
    }

    private val store = SkillOptStore(context.applicationContext)

    fun initialize() {
        KavyaBotRegistry.all().forEach { bot ->
            store.writeInitialSkill(bot.id, initialSkill(bot))
        }
    }

    fun skillForBot(botId: String): String {
        val bot = KavyaBotRegistry.all().firstOrNull { it.id == botId }
        if (bot == null) return ""
        return store.readSkill(botId) ?: initialSkill(bot)
    }

    fun recordExecution(skillId: String, task: String, success: Boolean, output: String) {
        val bot = KavyaBotRegistry.forSkill(skillId)
        if (bot == null) return
        store.appendTrajectory(bot.id, task.take(1200), success, output.take(2000))
    }

    suspend fun proposeImprovement(botId: String): SkillOptProposal? {
        val bot = KavyaBotRegistry.all().firstOrNull { it.id == botId }
        if (bot == null) return null

        val current = store.readSkill(botId) ?: initialSkill(bot)
        val trajectories = store.recentTrajectories(botId, MAX_TRAJECTORIES)
        if (trajectories.isEmpty()) return null

        val raw = modelRouter.chat(
            prompt = buildOptimizerPrompt(bot, current, trajectories),
            history = emptyList(),
            screenContext = null,
            memoryContext = "",
            isProactiveMode = false
        )
        val candidate = extractSkillDocument(raw) ?: return null

        val validation = validateCandidate(candidate, trajectories)
        if (!validation.accepted) {
            Log.w(TAG, "Rejected candidate for " + bot.id + ": " + validation.reason)
            return SkillOptProposal(bot.id, false, validation.reason, null)
        }

        val staged = store.stageCandidate(bot.id, candidate)
        return SkillOptProposal(bot.id, true, validation.reason, staged.absolutePath)
    }

    fun adopt(botId: String, stagedPath: String): Boolean =
        store.adoptCandidate(botId, File(stagedPath))

    private fun buildOptimizerPrompt(
        bot: KavyaBot,
        current: String,
        trajectories: List<String>
    ): String {
        return "You are Kavya's SkillOpt optimizer.\n" +
            "Optimize ONE specialized bot's natural-language procedure without changing model weights.\n\n" +
            "BOT:\n" + bot.id + "\n" + bot.description + "\n\n" +
            "CURRENT SKILL:\n" + current + "\n\n" +
            "RECENT TRAJECTORIES:\n" + trajectories.joinToString("\n") + "\n\n" +
            "Rules:\n" +
            "1. Make only bounded ADD, DELETE, REPLACE, or REORDER changes.\n" +
            "2. Preserve useful existing instructions.\n" +
            "3. Do not invent tools, permissions, APIs, or capabilities.\n" +
            "4. Do not include secrets, credentials, or private data.\n" +
            "5. Return ONLY the complete replacement SKILL.md inside one markdown code fence.\n" +
            "6. Keep the result below " + MAX_SKILL_CHARS + " characters."
    }

    private fun extractSkillDocument(raw: String): String? {
        val fence = 96.toChar().toString().repeat(3)
        val pattern = Regex(
            Regex.escape(fence) + "(?:markdown|md)?\\s*([\\s\\S]*?)" + Regex.escape(fence),
            RegexOption.IGNORE_CASE
        )
        val match = pattern.find(raw) ?: return null
        val candidate = match.groupValues[1].trim()
        return candidate.takeIf { it.isNotBlank() && it.length <= MAX_SKILL_CHARS }
    }

    private fun validateCandidate(candidate: String, trajectories: List<String>): ValidationResult {
        val lower = candidate.lowercase(Locale.ROOT)

        if (!lower.contains("# ") || !lower.contains("##")) {
            return ValidationResult(false, "Invalid skill structure.")
        }
        if (candidate.length > MAX_SKILL_CHARS) {
            return ValidationResult(false, "Candidate exceeds the bounded skill size.")
        }

        val forbidden = listOf("api_key=", "authorization: bearer", "private_key", "password=")
        if (forbidden.any { lower.contains(it) }) {
            return ValidationResult(false, "Credential-like material detected.")
        }

        val failures = trajectories.count { it.contains("success=false") }
        val addressesFailures = failures == 0 ||
            lower.contains("failure") ||
            lower.contains("verify") ||
            lower.contains("recover") ||
            lower.contains("check")

        if (!addressesFailures) {
            return ValidationResult(false, "Candidate does not address recent failure/recovery evidence.")
        }

        return ValidationResult(
            true,
            "Local safety/structure gate passed; candidate staged for explicit adoption."
        )
    }

    private fun initialSkill(bot: KavyaBot): String {
        return "# " + bot.name + "\n\n" +
            "## Purpose\n" + bot.description + "\n\n" +
            "## Procedure\n" +
            "- Understand the requested task before acting.\n" +
            "- Use only the existing Kavya skills and tools assigned to this bot.\n" +
            "- Prefer deterministic Android capabilities when sufficient.\n" +
            "- Verify the resulting state after an action before reporting success.\n" +
            "- If an action fails, recover or re-plan instead of repeating the same failed step.\n\n" +
            "## Safety\n" +
            "- Do not invent capabilities or permissions.\n" +
            "- Do not expose secrets or private data.\n" +
            "- Stop immediately when the master controller cancels the task.\n\n" +
            "## Learning\n" +
            "- Improve this procedure only through bounded, validated proposals."
    }
}

data class SkillOptProposal(
    val botId: String,
    val accepted: Boolean,
    val reason: String,
    val stagedPath: String?
)

private data class ValidationResult(
    val accepted: Boolean,
    val reason: String
)
