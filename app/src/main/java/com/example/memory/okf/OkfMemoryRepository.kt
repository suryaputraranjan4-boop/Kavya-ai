package com.example.memory.okf

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * OKF Agent Memory Persistent Repository.
 * Features:
 * - Git-native directory structure on local filesystem.
 * - Search-Before-Write principle (prevents duplication and surfaces conflicts).
 * - Pure local BM25 ranking retrieval without external vector DB or embeddings.
 * - Layer 1 Working Memory & Layer 2 Semantic Project Memory.
 */
class OkfMemoryRepository private constructor(private val context: Context) {

    companion object {
        private const val TAG = "OkfMemoryRepo"
        private const val MEMORY_DIR_NAME = "okf_memory"
        private const val KNOWLEDGE_SUBDIR = "knowledge"
        private const val INDEX_FILE_NAME = "index.json"
        private const val GIT_LOG_FILE_NAME = "git_commits.json"
        private const val WORKING_MEMORY_FILE_NAME = "working_memory.json"

        @Volatile
        private var INSTANCE: OkfMemoryRepository? = null

        fun getInstance(context: Context): OkfMemoryRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OkfMemoryRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val baseDir: File = File(context.filesDir, MEMORY_DIR_NAME)
    private val knowledgeDir: File = File(baseDir, KNOWLEDGE_SUBDIR)
    private val indexFile: File = File(baseDir, INDEX_FILE_NAME)
    private val gitLogFile: File = File(baseDir, GIT_LOG_FILE_NAME)
    private val workingMemoryFile: File = File(baseDir, WORKING_MEMORY_FILE_NAME)

    private val bm25Index = OkfBm25Index()

    // Thread-safe in-memory cache of units mapped by id
    private val memoryMap = mutableMapOf<String, OkfKnowledgeUnit>()
    private val _memoriesFlow = MutableStateFlow<List<OkfKnowledgeUnit>>(emptyList())
    val memoriesFlow: StateFlow<List<OkfKnowledgeUnit>> = _memoriesFlow.asStateFlow()
    val unitsFlow: StateFlow<List<OkfKnowledgeUnit>> get() = memoriesFlow

    private val _workingMemoryFlow = MutableStateFlow(OkfWorkingMemory())
    val workingMemoryFlow: StateFlow<OkfWorkingMemory> = _workingMemoryFlow.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    init {
        initializeStorage()
    }

    private fun initializeStorage() {
        try {
            if (!baseDir.exists()) baseDir.mkdirs()
            if (!knowledgeDir.exists()) knowledgeDir.mkdirs()

            loadWorkingMemory()
            loadIndexAndFiles()
            _isInitialized.value = true
            Log.d(TAG, "OKF Agent Memory initialized with ${memoryMap.size} units")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing OKF Memory storage: ${e.message}", e)
        }
    }

    private fun loadWorkingMemory() {
        if (workingMemoryFile.exists()) {
            try {
                val jsonStr = workingMemoryFile.readText(Charsets.UTF_8)
                val obj = JSONObject(jsonStr)
                val opRules = parseStringList(obj.optJSONArray("operatingRules"))
                val convs = parseStringList(obj.optJSONArray("projectConventions"))
                val safety = parseStringList(obj.optJSONArray("safetyConstraints"))
                _workingMemoryFlow.value = OkfWorkingMemory(
                    operatingRules = if (opRules.isNotEmpty()) opRules else _workingMemoryFlow.value.operatingRules,
                    projectConventions = if (convs.isNotEmpty()) convs else _workingMemoryFlow.value.projectConventions,
                    safetyConstraints = if (safety.isNotEmpty()) safety else _workingMemoryFlow.value.safetyConstraints
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading working memory file: ${e.message}")
            }
        } else {
            saveWorkingMemory(_workingMemoryFlow.value)
        }
    }

    fun saveWorkingMemory(memory: OkfWorkingMemory) {
        try {
            val obj = JSONObject().apply {
                put("operatingRules", JSONArray(memory.operatingRules))
                put("projectConventions", JSONArray(memory.projectConventions))
                put("safetyConstraints", JSONArray(memory.safetyConstraints))
            }
            workingMemoryFile.writeText(obj.toString(2), Charsets.UTF_8)
            _workingMemoryFlow.value = memory
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving working memory: ${e.message}", e)
        }
    }

    fun updateWorkingMemory(ruleKey: String, ruleValue: String) {
        val current = _workingMemoryFlow.value
        val newEntry = "$ruleKey: $ruleValue"
        val updatedRules = (current.operatingRules.filterNot { it.startsWith("$ruleKey:") } + newEntry)
        saveWorkingMemory(current.copy(operatingRules = updatedRules))
    }

    private fun parseStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            list.add(arr.optString(i))
        }
        return list
    }

    private fun loadIndexAndFiles() {
        memoryMap.clear()
        if (indexFile.exists()) {
            try {
                val jsonStr = indexFile.readText(Charsets.UTF_8)
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i)
                    if (obj != null) {
                        val unit = OkfKnowledgeUnit.fromJsonObject(obj)
                        memoryMap[unit.id] = unit
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Index file corrupt or unreadable, falling back to reading markdown files: ${e.message}")
            }
        }

        // If index was empty or missing, rebuild from markdown directory
        if (memoryMap.isEmpty() && knowledgeDir.exists()) {
            knowledgeDir.listFiles { file -> file.extension == "md" }?.forEach { file ->
                val unit = parseMarkdownFile(file)
                if (unit != null) {
                    memoryMap[unit.id] = unit
                }
            }
            persistIndex()
        }

        updateFlow()
    }

    private fun updateFlow() {
        _memoriesFlow.value = memoryMap.values
            .sortedWith(compareByDescending<OkfKnowledgeUnit> { it.importance }.thenByDescending { it.updatedAt })
    }

    private fun persistIndex() {
        try {
            val array = JSONArray()
            memoryMap.values.forEach { array.put(it.toJsonObject()) }
            indexFile.writeText(array.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed persisting index: ${e.message}", e)
        }
    }

    private fun appendGitLog(action: String, unitId: String, summary: String) {
        try {
            val commitHash = UUID.randomUUID().toString().replace("-", "").take(8)
            val log = OkfCommitLog(commitHash, action, unitId, summary)
            val logs = getGitLog().toMutableList()
            logs.add(0, log)
            val array = JSONArray()
            logs.take(100).forEach { array.put(it.toJsonObject()) }
            gitLogFile.writeText(array.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Failed appending git log: ${e.message}")
        }
    }

    fun getGitLog(): List<OkfCommitLog> {
        if (!gitLogFile.exists()) return emptyList()
        return try {
            val jsonStr = gitLogFile.readText(Charsets.UTF_8)
            val array = JSONArray(jsonStr)
            val list = mutableListOf<OkfCommitLog>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i)
                if (obj != null) list.add(OkfCommitLog.fromJsonObject(obj))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * SEARCH-BEFORE-WRITE PRINCIPLE:
     * 1. Searches existing memory for duplicate or conflicting information.
     * 2. If equivalent, avoids duplicate or updates.
     * 3. If conflicting, surfaces conflict.
     * 4. Otherwise writes new knowledge unit.
     */
    suspend fun createKnowledge(
        key: String,
        content: String,
        category: OkfCategory = OkfCategory.FACT,
        tags: List<String> = emptyList(),
        importance: Int = 3,
        provenance: OkfProvenance = OkfProvenance()
    ): OkfWriteResult = withContext(Dispatchers.IO) {
        val cleanKey = key.trim()
        val cleanContent = content.trim()

        // 1. Search existing memory for potential duplicates or conflicts
        val existingUnits = memoryMap.values.filter { it.status == OkfStatus.ACTIVE }
        
        // Check for exact or near-identical key
        val exactKeyMatch = existingUnits.firstOrNull { 
            it.key.equals(cleanKey, ignoreCase = true) 
        }

        if (exactKeyMatch != null) {
            val similarity = bm25Index.computeSimilarity(exactKeyMatch.content, cleanContent)
            if (similarity > 0.85 || exactKeyMatch.content.equals(cleanContent, ignoreCase = true)) {
                // Equivalent information already preserved
                return@withContext OkfWriteResult.DuplicateAvoided(exactKeyMatch)
            } else {
                // Detect possible direct contradiction
                val contradicts = isContradictory(exactKeyMatch.content, cleanContent)
                if (contradicts) {
                    return@withContext OkfWriteResult.ConflictDetected(
                        existingUnit = exactKeyMatch,
                        proposedKey = cleanKey,
                        proposedContent = cleanContent,
                        explanation = "Conflicts with existing active memory '${exactKeyMatch.key}': \"${exactKeyMatch.content}\""
                    )
                }
                // If not contradictory, update existing unit with increased version
                val updated = exactKeyMatch.copy(
                    content = cleanContent,
                    category = category,
                    importance = importance.coerceIn(1, 5),
                    tags = (exactKeyMatch.tags + tags).distinct(),
                    version = exactKeyMatch.version + 1,
                    updatedAt = System.currentTimeMillis()
                )
                saveUnitInternal(updated)
                appendGitLog("UPDATE", updated.id, "Updated knowledge '${updated.key}' (v${updated.version})")
                return@withContext OkfWriteResult.Updated(updated, exactKeyMatch.version)
            }
        }

        // Check BM25 search similarity to avoid semantic duplicates with different titles
        val scoredMatches = bm25Index.search(
            query = "$cleanKey $cleanContent",
            corpus = existingUnits,
            topK = 3
        )
        val topMatch = scoredMatches.firstOrNull()
        if (topMatch != null && topMatch.score > 8.0) {
            val sim = bm25Index.computeSimilarity(topMatch.unit.content, cleanContent)
            if (sim > 0.75) {
                return@withContext OkfWriteResult.DuplicateAvoided(topMatch.unit)
            }
        }

        // 2. Create new unit
        val newId = OkfKnowledgeUnit.generateId(cleanKey, cleanContent)
        val newUnit = OkfKnowledgeUnit(
            id = newId,
            key = cleanKey,
            content = cleanContent,
            category = category,
            status = OkfStatus.ACTIVE,
            provenance = provenance,
            confidence = 1.0f,
            importance = importance.coerceIn(1, 5),
            tags = tags,
            version = 1,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        saveUnitInternal(newUnit)
        appendGitLog("CREATE", newUnit.id, "Created knowledge '${newUnit.key}' [${newUnit.category}]")
        return@withContext OkfWriteResult.Created(newUnit)
    }

    private fun isContradictory(contentA: String, contentB: String): Boolean {
        val a = contentA.lowercase(Locale.ROOT)
        val b = contentB.lowercase(Locale.ROOT)
        
        // Direct boolean opposites
        val pairs = listOf(
            Pair("dark mode", "light mode"),
            Pair("enable", "disable"),
            Pair("true", "false"),
            Pair("hindi", "english only"),
            Pair("concise", "verbose"),
            Pair("compact", "expanded")
        )
        for ((term1, term2) in pairs) {
            if ((a.contains(term1) && b.contains(term2)) || (a.contains(term2) && b.contains(term1))) {
                return true
            }
        }
        return false
    }

    /**
     * Updates an existing knowledge unit.
     */
    suspend fun updateKnowledge(
        idOrKey: String,
        content: String? = null,
        category: OkfCategory? = null,
        tags: List<String>? = null,
        status: OkfStatus? = null,
        importance: Int? = null
    ): OkfKnowledgeUnit? = withContext(Dispatchers.IO) {
        val existing = findUnit(idOrKey) ?: return@withContext null
        val updated = existing.copy(
            content = content?.trim() ?: existing.content,
            category = category ?: existing.category,
            tags = tags ?: existing.tags,
            status = status ?: existing.status,
            importance = importance?.coerceIn(1, 5) ?: existing.importance,
            version = existing.version + 1,
            updatedAt = System.currentTimeMillis()
        )
        saveUnitInternal(updated)
        appendGitLog("UPDATE", updated.id, "Updated '${updated.key}' (v${updated.version})")
        return@withContext updated
    }

    /**
     * Deletes or archives knowledge.
     */
    suspend fun deleteKnowledge(idOrKey: String, permanent: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val unit = findUnit(idOrKey) ?: return@withContext false
        if (permanent) {
            memoryMap.remove(unit.id)
            val file = File(knowledgeDir, "${unit.id}.md")
            if (file.exists()) file.delete()
            persistIndex()
            updateFlow()
            appendGitLog("DELETE", unit.id, "Permanently deleted '${unit.key}'")
        } else {
            val archived = unit.copy(status = OkfStatus.ARCHIVED, updatedAt = System.currentTimeMillis())
            saveUnitInternal(archived)
            appendGitLog("ARCHIVE", unit.id, "Archived '${unit.key}'")
        }
        return@withContext true
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        memoryMap.clear()
        knowledgeDir.listFiles()?.forEach { it.delete() }
        persistIndex()
        updateFlow()
        appendGitLog("CLEAR", "all", "Cleared all project memory")
    }

    /**
     * Retrieves knowledge matching query using local BM25 ranking.
     * Selectively returns only the top relevant entries without blowing context size.
     */
    suspend fun searchKnowledge(
        query: String,
        category: OkfCategory? = null,
        topK: Int = 4
    ): List<ScoredKnowledgeUnit> = withContext(Dispatchers.IO) {
        val activeUnits = memoryMap.values.filter { it.status == OkfStatus.ACTIVE }
        return@withContext bm25Index.search(query, activeUnits, category, topK)
    }

    /**
     * Retrieves a single unit by ID or key.
     */
    fun getKnowledge(idOrKey: String): OkfKnowledgeUnit? {
        return findUnit(idOrKey)
    }

    fun listKnowledge(
        category: OkfCategory? = null,
        status: OkfStatus? = OkfStatus.ACTIVE,
        limit: Int = 50
    ): List<OkfKnowledgeUnit> {
        return memoryMap.values
            .filter { (category == null || it.category == category) && (status == null || it.status == status) }
            .sortedWith(compareByDescending<OkfKnowledgeUnit> { it.importance }.thenByDescending { it.updatedAt })
            .take(limit)
    }

    private fun findUnit(idOrKey: String): OkfKnowledgeUnit? {
        val clean = idOrKey.trim()
        return memoryMap[clean] ?: memoryMap.values.firstOrNull { 
            it.key.equals(clean, ignoreCase = true) || it.id.equals(clean, ignoreCase = true) 
        }
    }

    private fun saveUnitInternal(unit: OkfKnowledgeUnit) {
        memoryMap[unit.id] = unit
        val file = File(knowledgeDir, "${unit.id}.md")
        file.writeText(unit.toMarkdownWithFrontmatter(), Charsets.UTF_8)
        persistIndex()
        updateFlow()
    }

    private fun parseMarkdownFile(file: File): OkfKnowledgeUnit? {
        return try {
            val text = file.readText(Charsets.UTF_8)
            if (!text.startsWith("---")) return null
            val parts = text.split("---", limit = 3)
            if (parts.size < 3) return null
            val frontmatterLines = parts[1].lines()
            val body = parts[2].trim()

            var id = file.nameWithoutExtension
            var key = "Untitled"
            var category = OkfCategory.FACT
            var status = OkfStatus.ACTIVE
            var source = "USER_EXPLICIT"
            var author = "User"
            var confidence = 1.0f
            var importance = 3
            var version = 1
            var createdAt = file.lastModified()
            var updatedAt = file.lastModified()
            val tags = mutableListOf<String>()

            for (line in frontmatterLines) {
                val kv = line.split(":", limit = 2)
                if (kv.size == 2) {
                    val k = kv[0].trim()
                    val v = kv[1].trim()
                    when (k) {
                        "id" -> id = v
                        "key" -> key = v
                        "category" -> category = OkfCategory.fromString(v)
                        "status" -> status = OkfStatus.fromString(v)
                        "source" -> source = v
                        "author" -> author = v
                        "confidence" -> confidence = v.toFloatOrNull() ?: 1.0f
                        "importance" -> importance = v.toIntOrNull() ?: 3
                        "version" -> version = v.toIntOrNull() ?: 1
                        "createdAt" -> createdAt = v.toLongOrNull() ?: createdAt
                        "updatedAt" -> updatedAt = v.toLongOrNull() ?: updatedAt
                        "tags" -> {
                            val cleanTags = v.removePrefix("[").removeSuffix("]")
                            tags.addAll(cleanTags.split(",").map { it.trim() }.filter { it.isNotBlank() })
                        }
                    }
                }
            }

            OkfKnowledgeUnit(
                id = id,
                key = key,
                content = body,
                category = category,
                status = status,
                provenance = OkfProvenance(source, author, createdAt),
                confidence = confidence,
                importance = importance,
                tags = tags,
                version = version,
                createdAt = createdAt,
                updatedAt = updatedAt
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing markdown file ${file.name}: ${e.message}")
            null
        }
    }

    /**
     * Builds a selective, concise memory context string for AI prompts.
     * Combines Layer 1 Working Memory with top relevant Semantic Project Memories.
     */
    suspend fun buildPromptMemoryContext(userQuery: String, activeAppOrTopic: String? = null): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()

        // Layer 1: Working Memory (always compact, essential rules)
        val workingMem = _workingMemoryFlow.value
        sb.append(workingMem.toPromptBlock())
        sb.append("\n\n")

        // Layer 2: Selective Project Memory (BM25 retrieved relevant facts only)
        val searchQuery = if (!activeAppOrTopic.isNullOrBlank()) "$userQuery $activeAppOrTopic" else userQuery
        val relevant = searchKnowledge(searchQuery, topK = 4)

        if (relevant.isNotEmpty()) {
            sb.append("RELEVANT PROJECT MEMORY (BM25 Retrieved Context):\n")
            relevant.forEach { scored ->
                val u = scored.unit
                sb.append("• [${u.category.name}] ${u.key}: ${u.content} (Source: ${u.provenance.source})\n")
            }
        }

        return@withContext sb.toString().trim()
    }

    fun exportJson(): String {
        val array = JSONArray()
        memoryMap.values.forEach { array.put(it.toJsonObject()) }
        return array.toString(2)
    }

    fun exportMarkdownBundle(): String {
        return buildString {
            appendLine("# Kavya OKF Agent Memory Export")
            appendLine("Generated: ${System.currentTimeMillis()}")
            appendLine("Total Knowledge Units: ${memoryMap.size}")
            appendLine()
            memoryMap.values.forEach { unit ->
                appendLine(unit.toMarkdownWithFrontmatter())
                appendLine("\n========================================\n")
            }
        }
    }
}
