package com.example.memory.okf

import org.json.JSONArray
import org.json.JSONObject

/**
 * Standard Tool Interface for OKF Agent Memory.
 * Can be invoked directly by Kavya's AI Brain / Gemini or by the internal TaskPlanner.
 */
class OkfMemoryTools(private val repository: OkfMemoryRepository) {

    /**
     * Searches persistent semantic project memory using BM25 ranking.
     */
    suspend fun memorySearch(query: String, category: String? = null, limit: Int = 5): String {
        if (query.isBlank()) {
            return JSONObject().apply {
                put("status", "error")
                put("message", "Search query cannot be blank")
            }.toString()
        }

        val catEnum = if (!category.isNullOrBlank()) OkfCategory.fromString(category) else null
        val results = repository.searchKnowledge(query, catEnum, topK = limit)

        val array = JSONArray()
        results.forEach { scored ->
            val u = scored.unit
            array.put(JSONObject().apply {
                put("id", u.id)
                put("key", u.key)
                put("content", u.content)
                put("category", u.category.name)
                put("score", "%.2f".format(scored.score))
                put("matchedTerms", JSONArray(scored.matchedTerms))
                put("importance", u.importance)
                put("source", u.provenance.source)
            })
        }

        return JSONObject().apply {
            put("status", "success")
            put("query", query)
            put("count", results.size)
            put("results", array)
        }.toString()
    }

    /**
     * Retrieves specific knowledge unit by id or key.
     */
    fun memoryShow(idOrKey: String): String {
        val unit = repository.getKnowledge(idOrKey)
        return if (unit != null) {
            JSONObject().apply {
                put("status", "success")
                put("unit", unit.toJsonObject())
            }.toString()
        } else {
            JSONObject().apply {
                put("status", "error")
                put("message", "Memory item '$idOrKey' not found")
            }.toString()
        }
    }

    /**
     * Creates new knowledge with Search-Before-Write verification.
     */
    suspend fun memoryCreate(
        key: String,
        content: String,
        category: String = "FACT",
        tags: List<String> = emptyList(),
        importance: Int = 3,
        source: String = "USER_EXPLICIT"
    ): String {
        if (key.isBlank() || content.isBlank()) {
            return JSONObject().apply {
                put("status", "error")
                put("message", "Both key and content must be provided")
            }.toString()
        }

        val catEnum = OkfCategory.fromString(category)
        val provenance = OkfProvenance(source = source, author = "AI Agent")
        val writeResult = repository.createKnowledge(
            key = key,
            content = content,
            category = catEnum,
            tags = tags,
            importance = importance,
            provenance = provenance
        )

        return when (writeResult) {
            is OkfWriteResult.Created -> JSONObject().apply {
                put("status", "success")
                put("action", "CREATED")
                put("id", writeResult.unit.id)
                put("key", writeResult.unit.key)
                put("category", writeResult.unit.category.name)
                put("message", "Saved to persistent OKF memory")
            }.toString()

            is OkfWriteResult.Updated -> JSONObject().apply {
                put("status", "success")
                put("action", "UPDATED")
                put("id", writeResult.unit.id)
                put("key", writeResult.unit.key)
                put("version", writeResult.unit.version)
                put("message", "Updated existing memory (Search-Before-Write detected existing record)")
            }.toString()

            is OkfWriteResult.DuplicateAvoided -> JSONObject().apply {
                put("status", "success")
                put("action", "DUPLICATE_AVOIDED")
                put("id", writeResult.existingUnit.id)
                put("key", writeResult.existingUnit.key)
                put("message", "Information is already preserved in memory; skipped duplicate creation")
            }.toString()

            is OkfWriteResult.ConflictDetected -> JSONObject().apply {
                put("status", "conflict")
                put("action", "CONFLICT_DETECTED")
                put("existingUnitId", writeResult.existingUnit.id)
                put("existingKey", writeResult.existingUnit.key)
                put("existingContent", writeResult.existingUnit.content)
                put("explanation", writeResult.explanation)
                put("message", "Search-Before-Write flagged a conflict with existing verified knowledge")
            }.toString()
        }
    }

    /**
     * Updates an existing knowledge unit.
     */
    suspend fun memoryUpdate(
        idOrKey: String,
        content: String? = null,
        category: String? = null,
        status: String? = null,
        importance: Int? = null
    ): String {
        val catEnum = if (!category.isNullOrBlank()) OkfCategory.fromString(category) else null
        val statEnum = if (!status.isNullOrBlank()) OkfStatus.fromString(status) else null

        val updated = repository.updateKnowledge(
            idOrKey = idOrKey,
            content = content,
            category = catEnum,
            status = statEnum,
            importance = importance
        )

        return if (updated != null) {
            JSONObject().apply {
                put("status", "success")
                put("id", updated.id)
                put("key", updated.key)
                put("version", updated.version)
                put("message", "Successfully updated memory")
            }.toString()
        } else {
            JSONObject().apply {
                put("status", "error")
                put("message", "Memory item '$idOrKey' not found")
            }.toString()
        }
    }

    /**
     * Deletes or archives knowledge.
     */
    suspend fun memoryDelete(idOrKey: String, permanent: Boolean = false): String {
        val success = repository.deleteKnowledge(idOrKey, permanent)
        return if (success) {
            JSONObject().apply {
                put("status", "success")
                put("action", if (permanent) "DELETED" else "ARCHIVED")
                put("message", "Memory record '${idOrKey}' successfully ${if (permanent) "deleted" else "archived"}")
            }.toString()
        } else {
            JSONObject().apply {
                put("status", "error")
                put("message", "Memory item '$idOrKey' not found")
            }.toString()
        }
    }

    /**
     * Lists memory items.
     */
    fun memoryList(category: String? = null, status: String? = "ACTIVE", limit: Int = 30): String {
        val catEnum = if (!category.isNullOrBlank()) OkfCategory.fromString(category) else null
        val statEnum = if (!status.isNullOrBlank()) OkfStatus.fromString(status) else null

        val items = repository.listKnowledge(catEnum, statEnum, limit)
        val array = JSONArray()
        items.forEach { array.put(it.toJsonObject()) }

        return JSONObject().apply {
            put("status", "success")
            put("count", items.size)
            put("items", array)
        }.toString()
    }
}
