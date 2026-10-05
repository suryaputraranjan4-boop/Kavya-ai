package com.example.skillopt

import android.content.Context
import android.util.Base64
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SkillOptStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "skillopt")
    private val botsRoot = File(root, "bots")
    private val stagingRoot = File(root, "staging")

    init { botsRoot.mkdirs(); stagingRoot.mkdirs() }

    fun readSkill(botId: String): String? {
        val file = File(botsRoot, botId + "/SKILL.md")
        return file.takeIf { it.exists() }?.readText()
    }

    fun writeInitialSkill(botId: String, content: String) {
        val dir = File(botsRoot, botId).apply { mkdirs() }
        val live = File(dir, "SKILL.md")
        if (!live.exists()) live.writeText(content)
    }

    fun stageCandidate(botId: String, candidate: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val dir = File(stagingRoot, botId + "/" + stamp).apply { mkdirs() }
        return File(dir, "SKILL.md").also { it.writeText(candidate) }
    }

    fun adoptCandidate(botId: String, stagedFile: File): Boolean {
        if (!stagedFile.exists()) return false
        val dir = File(botsRoot, botId).apply { mkdirs() }
        val live = File(dir, "SKILL.md")
        if (live.exists()) {
            File(dir, "SKILL.backup." + System.currentTimeMillis() + ".md")
                .writeText(live.readText())
        }
        stagedFile.copyTo(live, overwrite = true)
        return true
    }

    fun appendTrajectory(botId: String, task: String, success: Boolean, output: String) {
        val dir = File(botsRoot, botId).apply { mkdirs() }
        val values = listOf(task, success.toString(), output).map {
            Base64.encodeToString(it.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
        File(dir, "trajectories.log").appendText(values.joinToString("\t") + "\n")
    }

    fun recentTrajectories(botId: String, maxItems: Int): List<String> {
        val file = File(botsRoot, botId + "/trajectories.log")
        if (!file.exists()) return emptyList()
        return file.readLines().takeLast(maxItems).mapNotNull { line ->
            val parts = line.split("\t")
            if (parts.size != 3) return@mapNotNull null
            try {
                val task = String(Base64.decode(parts[0], Base64.DEFAULT), Charsets.UTF_8)
                val success = String(Base64.decode(parts[1], Base64.DEFAULT), Charsets.UTF_8)
                val output = String(Base64.decode(parts[2], Base64.DEFAULT), Charsets.UTF_8)
                "success=" + success + " | task=" + task + " | output=" + output
            } catch (_: Exception) { null }
        }
    }
}
