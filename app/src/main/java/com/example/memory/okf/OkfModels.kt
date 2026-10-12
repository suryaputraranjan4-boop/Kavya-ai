package com.example.memory.okf

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * OKF (Open Knowledge Framework) Agent Memory categories.
 * Strict semantic separation of durable agent knowledge.
 */
enum class OkfCategory {
    ARCHITECTURE,
    DECISION,
    PREFERENCE,
    CONVENTION,
    CONSTRAINT,
    RUNBOOK,
    FACT,
    TROUBLESHOOTING;

    companion object {
        fun fromString(value: String?): OkfCategory {
            if (value.isNullOrBlank()) return FACT
            val clean = value.trim().uppercase(Locale.ROOT)
            return entries.firstOrNull { it.name == clean } ?: when {
                clean.contains("ARCH") -> ARCHITECTURE
                clean.contains("DECIS") -> DECISION
                clean.contains("PREF") -> PREFERENCE
                clean.contains("CONV") || clean.contains("RULE") -> CONVENTION
                clean.contains("CONST") || clean.contains("LIMIT") -> CONSTRAINT
                clean.contains("RUN") || clean.contains("BOOK") -> RUNBOOK
                clean.contains("TROUBLE") || clean.contains("FIX") || clean.contains("ERROR") -> TROUBLESHOOTING
                else -> FACT
            }
        }
    }
}

/**
 * Knowledge unit lifecycle status.
 */
enum class OkfStatus {
    ACTIVE,
    DRAFT,
    ARCHIVED,
    DEPRECATED;

    companion object {
        fun fromString(value: String?): OkfStatus {
            if (value.isNullOrBlank()) return ACTIVE
            val clean = value.trim().uppercase(Locale.ROOT)
            return entries.firstOrNull { it.name == clean } ?: ACTIVE
        }
    }
}

/**
 * Provenance tracking indicating source and authority of knowledge.
 */
data class OkfProvenance(
    val source: String = "USER_EXPLICIT", // USER_EXPLICIT, CONFIRMED_DECISION, SYSTEM_CONVENTION, MIGRATION, RUNBOOK
    val author: String = "User",
    val timestamp: Long = System.currentTimeMillis(),
    val sessionContext: String = ""
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("source", source)
        put("author", author)
        put("timestamp", timestamp)
        put("sessionContext", sessionContext)
    }

    companion object {
        fun fromJsonObject(obj: JSONObject?): OkfProvenance {
            if (obj == null) return OkfProvenance()
            return OkfProvenance(
                source = obj.optString("source", "USER_EXPLICIT"),
                author = obj.optString("author", "User"),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                sessionContext = obj.optString("sessionContext", "")
            )
        }
    }
}

/**
 * Primary unit of knowledge in OKF Agent Memory.
 * Git-native representation with hash identifier and versioning.
 */
data class OkfKnowledgeUnit(
    val id: String, // 12-char hex git-like commit hash
    val key: String, // Human-readable title or semantic topic
    val content: String, // Markdown / knowledge description
    val category: OkfCategory = OkfCategory.FACT,
    val status: OkfStatus = OkfStatus.ACTIVE,
    val provenance: OkfProvenance = OkfProvenance(),
    val confidence: Float = 1.0f,
    val importance: Int = 3, // 1 (lowest) to 5 (critical)
    val tags: List<String> = emptyList(),
    val version: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * Serializes to Markdown format with YAML frontmatter.
     */
    fun toMarkdownWithFrontmatter(): String {
        val tagsStr = tags.joinToString(", ", prefix = "[", postfix = "]")
        return buildString {
            appendLine("---")
            appendLine("id: $id")
            appendLine("key: $key")
            appendLine("category: ${category.name}")
            appendLine("status: ${status.name}")
            appendLine("source: ${provenance.source}")
            appendLine("author: ${provenance.author}")
            appendLine("confidence: $confidence")
            appendLine("importance: $importance")
            appendLine("version: $version")
            appendLine("tags: $tagsStr")
            appendLine("createdAt: $createdAt")
            appendLine("updatedAt: $updatedAt")
            appendLine("---")
            appendLine()
            append(content.trim())
        }
    }

    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("key", key)
        put("content", content)
        put("category", category.name)
        put("status", status.name)
        put("provenance", provenance.toJsonObject())
        put("confidence", confidence.toDouble())
        put("importance", importance)
        put("version", version)
        put("tags", JSONArray(tags))
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun generateId(key: String, content: String): String {
            val input = "$key:$content:${System.currentTimeMillis()}:${UUID.randomUUID()}"
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(input.toByteArray(Charsets.UTF_8))
            return digest.take(6).joinToString("") { "%02x".format(it) }
        }

        fun fromJsonObject(obj: JSONObject): OkfKnowledgeUnit {
            val tagsList = mutableListOf<String>()
            val tagsArray = obj.optJSONArray("tags")
            if (tagsArray != null) {
                for (i in 0 until tagsArray.length()) {
                    tagsList.add(tagsArray.optString(i))
                }
            }
            return OkfKnowledgeUnit(
                id = obj.optString("id", UUID.randomUUID().toString().take(12)),
                key = obj.optString("key", "Untitled"),
                content = obj.optString("content", ""),
                category = OkfCategory.fromString(obj.optString("category")),
                status = OkfStatus.fromString(obj.optString("status")),
                provenance = OkfProvenance.fromJsonObject(obj.optJSONObject("provenance")),
                confidence = obj.optDouble("confidence", 1.0).toFloat(),
                importance = obj.optInt("importance", 3),
                tags = tagsList,
                version = obj.optInt("version", 1),
                createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
            )
        }
    }
}

/**
 * Result of a Search-Before-Write operation.
 */
sealed class OkfWriteResult {
    data class Created(val unit: OkfKnowledgeUnit) : OkfWriteResult()
    data class Updated(val unit: OkfKnowledgeUnit, val previousVersion: Int) : OkfWriteResult()
    data class DuplicateAvoided(val existingUnit: OkfKnowledgeUnit) : OkfWriteResult()
    data class ConflictDetected(
        val existingUnit: OkfKnowledgeUnit,
        val proposedKey: String,
        val proposedContent: String,
        val explanation: String
    ) : OkfWriteResult()
}

/**
 * Git commit record for knowledge tracking.
 */
data class OkfCommitLog(
    val commitHash: String,
    val action: String, // CREATE, UPDATE, ARCHIVE, DELETE, MIGRATE
    val unitId: String,
    val summary: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("commitHash", commitHash)
        put("action", action)
        put("unitId", unitId)
        put("summary", summary)
        put("timestamp", timestamp)
    }

    companion object {
        fun fromJsonObject(obj: JSONObject): OkfCommitLog {
            return OkfCommitLog(
                commitHash = obj.optString("commitHash", ""),
                action = obj.optString("action", "UPDATE"),
                unitId = obj.optString("unitId", ""),
                summary = obj.optString("summary", ""),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis())
            )
        }
    }
}

/**
 * Layer 1: Working Memory.
 * Small, high-priority rules loaded permanently into prompt without blowing token budget.
 */
data class OkfWorkingMemory(
    val operatingRules: List<String> = listOf(
        "Always execute requested actions immediately and verify completion.",
        "Keep natural conversations warm, concise, and respectful of privacy.",
        "Never fabricate success when device automation fails.",
        "Use selective memory retrieval; never dump raw external data into permanent memory."
    ),
    val projectConventions: List<String> = listOf(
        "Kavya uses Jetpack Compose, Material 3, and Kotlin Coroutines.",
        "Local scraper service operates at http://localhost:8080 without requiring Google Maps API keys.",
        "Memory uses OKF Git-native knowledge storage with BM25 local ranking."
    ),
    val safetyConstraints: List<String> = listOf(
        "Confirm with user before sending messages, making calls, or deleting critical files.",
        "Never expose API keys, passwords, or authentication tokens in UI logs or prompts."
    )
) {
    /**
     * Compact string representation for prompt injection (budget ~150-250 words).
     */
    fun toPromptBlock(): String {
        return buildString {
            appendLine("WORKING MEMORY (Always-Active Agent Rules):")
            appendLine("Operating Rules:")
            operatingRules.forEach { appendLine("- $it") }
            appendLine("Project Conventions:")
            projectConventions.forEach { appendLine("- $it") }
            appendLine("Safety Constraints:")
            safetyConstraints.forEach { appendLine("- $it") }
        }.trim()
    }
}
