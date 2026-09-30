package com.example.agent

import android.content.Context
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.MemoryDao
import com.example.data.MemoryEntity
import com.example.data.MessageEntity
import com.example.memory.okf.OkfCategory
import com.example.memory.okf.OkfMemoryRepository
import com.example.memory.okf.OkfMemoryTools
import com.example.memory.okf.OkfProvenance
import com.example.memory.okf.OkfStatus
import com.example.utils.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Real Persistent Long-Term Memory Engine for Kavya.
 * Powered by OKF Agent Memory (Git-native storage & local BM25 ranking)
 * with Room Database dual-persistence for existing view compatibility.
 */
class MemoryEngine(private val context: Context) {

    companion object {
        private const val TAG = "KavyaMemoryEngine"
    }

    private val memoryDao: MemoryDao = AppDatabase.getDatabase(context).memoryDao()
    val okfRepository: OkfMemoryRepository = OkfMemoryRepository.getInstance(context)
    val okfTools: OkfMemoryTools = OkfMemoryTools(okfRepository)

    init {
        // Asynchronously migrate existing Room records to OKF Git-native format if needed
        CoroutineScope(Dispatchers.IO).launch {
            try {
                migrateRoomRecordsToOkf()
            } catch (e: Exception) {
                Log.w(TAG, "Background Room -> OKF migration notice: ${e.message}")
            }
        }
    }

    private suspend fun migrateRoomRecordsToOkf() {
        val roomMemories = memoryDao.getAllMemories()
        if (roomMemories.isEmpty()) return

        for (m in roomMemories) {
            val existing = okfRepository.getKnowledge(m.key)
            if (existing == null) {
                okfRepository.createKnowledge(
                    key = m.key,
                    content = m.content,
                    category = OkfCategory.fromString(m.category),
                    importance = m.importance,
                    provenance = OkfProvenance(
                        source = "MIGRATION",
                        author = "Kavya Room Migration",
                        timestamp = m.createdAt
                    )
                )
            }
        }
    }

    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>> = memoryDao.getAllMemoriesFlow()

    fun getMemoriesByCategoryFlow(category: String): Flow<List<MemoryEntity>> {
        return if (category == "ALL" || category.isBlank()) {
            memoryDao.getAllMemoriesFlow()
        } else {
            memoryDao.getMemoriesByCategoryFlow(category.uppercase())
        }
    }

    /**
     * Inspects incoming user input for explicit or implicit long-term memory statements.
     * Extracts, validates, classifies, persists to Room DB, and verifies storage.
     */
    suspend fun processAndExtractMemory(
        userInput: String,
        sourceConversation: String = ""
    ): MemoryExtractionResult = withContext(Dispatchers.IO) {
        val trimmed = userInput.trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        // 1. Check for explicit Forget / Delete memory commands
        if (isForgetCommand(lower)) {
            val keyToForget = extractKeyToForget(lower, trimmed)
            if (keyToForget.isNotBlank()) {
                val matches = memoryDao.searchMemories(keyToForget)
                var deletedCount = 0
                for (match in matches) {
                    memoryDao.deleteMemory(match)
                    deletedCount++
                }
                if (deletedCount > 0) {
                    return@withContext MemoryExtractionResult(
                        detected = true,
                        isForgetAction = true,
                        confirmationText = "Theek hai, main yeh bhool gayi.",
                        affectedKey = keyToForget
                    )
                }
            }
        }

        // 2. Check for explicit Correction commands ("No, I meant X", "Actually my favorite is Y")
        val correction = extractCorrection(lower, trimmed)
        if (correction != null) {
            val (topicKey, newContent) = correction
            val existing = memoryDao.searchMemories(topicKey)
            if (existing.isNotEmpty()) {
                val toUpdate = existing.first().copy(
                    content = newContent,
                    lastUsedAt = System.currentTimeMillis(),
                    confidence = 1.0f,
                    userConfirmed = true
                )
                memoryDao.updateMemory(toUpdate)
            } else {
                val newMemory = MemoryEntity(
                    key = topicKey,
                    content = newContent,
                    category = "PREFERENCE",
                    importance = 4,
                    sourceConversation = sourceConversation,
                    confidence = 1.0f,
                    userConfirmed = true
                )
                memoryDao.insertMemory(newMemory)
            }
            return@withContext MemoryExtractionResult(
                detected = true,
                saved = true,
                confirmationText = "Update kar diya: $topicKey = $newContent",
                affectedKey = topicKey,
                persistedMemory = existing.firstOrNull()
            )
        }

        // 3. Check for explicit Remember commands
        val explicitMemory = extractExplicitMemory(lower, trimmed)
        if (explicitMemory != null) {
            val (key, content, category, importance) = explicitMemory
            
            // Check if already stored with similar key
            val existing = memoryDao.searchMemories(key)
            val memoryEntity = if (existing.isNotEmpty()) {
                existing.first().copy(
                    content = content,
                    category = category,
                    importance = importance,
                    lastUsedAt = System.currentTimeMillis(),
                    sourceConversation = sourceConversation,
                    confidence = 1.0f,
                    userConfirmed = true
                )
            } else {
                MemoryEntity(
                    key = key,
                    content = content,
                    category = category,
                    importance = importance,
                    sourceConversation = sourceConversation,
                    confidence = 1.0f,
                    userConfirmed = true
                )
            }

            val insertedId = memoryDao.insertMemory(memoryEntity)
            val verify = memoryDao.getMemoryById(if (memoryEntity.id > 0) memoryEntity.id else insertedId)
            
            val isVerified = verify != null
            if (isVerified) {
                try {
                    okfRepository.createKnowledge(
                        key = key,
                        content = content,
                        category = if (category == "TASK_WORKFLOW") OkfCategory.RUNBOOK else OkfCategory.PREFERENCE,
                        importance = importance,
                        provenance = OkfProvenance(source = "EXPLICIT_USER_COMMAND", author = "User")
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "OKF sync error on remember: ${e.message}")
                }
            }

            val confirm = if (isVerified) {
                if (category == "TASK_WORKFLOW" || category == "WORKFLOW_PREFERENCE") {
                    "Theek hai, maine yaad rakh liya: $content. Aage se is task mein isi workflow ka dhyan rakhungi."
                } else {
                    "Theek hai, maine yaad rakh liya: $content."
                }
            } else {
                "I couldn't save that to memory."
            }

            return@withContext MemoryExtractionResult(
                detected = true,
                saved = isVerified,
                confirmationText = confirm,
                affectedKey = key,
                persistedMemory = verify
            )
        }

        // Requirement 1 Mandate: Do not save every conversation automatically.
        // Only save information when explicitly requested or when it is part of the defined active-task memory system.
        return@withContext MemoryExtractionResult(detected = false)
    }

    /**
     * Semantic and relevance-based retrieval for AI context injection.
     * Selects only top relevant memories (max 6) matching query keywords, categories, and importance.
     */
    suspend fun retrieveRelevantMemories(
        userQuery: String,
        activeContext: String? = null
    ): List<MemoryEntity> = withContext(Dispatchers.IO) {
        val all = memoryDao.getAllMemories()
        if (all.isEmpty()) return@withContext emptyList()

        // If OKF Memory is enabled, leverage local BM25 ranking
        if (AppPreferences.isOkfMemoryEnabled(context)) {
            val bm25Results = okfRepository.searchKnowledge(userQuery, topK = 6)
            if (bm25Results.isNotEmpty()) {
                val matchedEntities = mutableListOf<MemoryEntity>()
                val now = System.currentTimeMillis()
                for (scored in bm25Results) {
                    val entity = all.firstOrNull { it.key.equals(scored.unit.key, ignoreCase = true) }
                        ?: MemoryEntity(
                            key = scored.unit.key,
                            content = scored.unit.content,
                            category = scored.unit.category.name,
                            importance = scored.unit.importance,
                            lastUsedAt = now
                        )
                    matchedEntities.add(entity)
                    if (entity.id > 0) {
                        memoryDao.updateLastUsed(entity.id, now)
                    }
                }
                return@withContext matchedEntities
            }
        }

        val lowerQuery = userQuery.lowercase(Locale.ROOT)
        val tokens = lowerQuery.split(" ", ",", ".", "?", "!", "\n").filter { it.length > 2 }

        // Score each memory by relevance
        val scoredMemories = all.map { memory ->
            var score = 0
            val lowerKey = memory.key.lowercase(Locale.ROOT)
            val lowerContent = memory.content.lowercase(Locale.ROOT)

            // Category weighting
            when (memory.category.uppercase(Locale.ROOT)) {
                "CRITICAL" -> score += 10
                "PREFERENCE" -> score += 6
                "IMPORTANT" -> score += 5
                "CONTEXT" -> score += 3
                else -> score += 1
            }

            score += (memory.importance * 2)

            // Keyword token overlap
            for (token in tokens) {
                if (lowerKey.contains(token)) score += 8
                if (lowerContent.contains(token)) score += 5
            }

            // Language / Style matches
            if (lowerQuery.contains("hinglish") || lowerQuery.contains("hindi") || lowerQuery.contains("english")) {
                if (lowerKey.contains("language") || lowerContent.contains("hinglish") || lowerContent.contains("hindi")) {
                    score += 15
                }
            }

            // Active context matching
            if (!activeContext.isNullOrBlank()) {
                val lowerActive = activeContext.lowercase(Locale.ROOT)
                if (lowerKey.contains(lowerActive) || lowerContent.contains(lowerActive)) {
                    score += 10
                }
            }

            // Recency boost (within last 24h)
            val hoursSinceLastUsed = (System.currentTimeMillis() - memory.lastUsedAt) / (1000 * 60 * 60)
            if (hoursSinceLastUsed < 24) {
                score += 3
            }

            Pair(memory, score)
        }

        // Return top items with score threshold
        val selected = scoredMemories
            .filter { it.second > 4 }
            .sortedByDescending { it.second }
            .take(6)
            .map { it.first }

        // Update lastUsed timestamp for selected
        val now = System.currentTimeMillis()
        for (m in selected) {
            memoryDao.updateLastUsed(m.id, now)
        }

        return@withContext selected
    }

    /**
     * Handles explicit conversational recall questions such as:
     * "What did I tell you last time?", "What are my preferences?", "Mujhe kya pasand hai?", "Kya tumhe yaad hai?"
     */
    suspend fun handleConversationalRecall(
        query: String,
        recentMessages: List<MessageEntity> = emptyList()
    ): String? = withContext(Dispatchers.IO) {
        val lower = query.lowercase(Locale.ROOT).trim()

        val isRecallQuestion = lower.contains("what did i tell you") ||
                lower.contains("last time") ||
                lower.contains("what do you remember") ||
                lower.contains("kya yaad hai") ||
                lower.contains("क्या याद है") ||
                lower.contains("pichli baar") ||
                lower.contains("meri preferences") ||
                lower.contains("what are my preferences") ||
                lower.contains("what do you know about me") ||
                lower.contains("yaad hai") ||
                lower.contains("do you remember")

        if (!isRecallQuestion) return@withContext null

        val memories = memoryDao.getAllMemories()

        // Check if user is asking about a SPECIFIC topic, person, or app (Requirement 9: No Fake Memory)
        val specificKeywords = listOf(
            "didi", "whatsapp", "spotify", "youtube", "chrome", "rohan",
            "mummy", "call", "message", "song", "preference", "workflow"
        )
        val matchingKeywords = specificKeywords.filter { lower.contains(it) }

        if (matchingKeywords.isNotEmpty()) {
            val matched = memories.filter { m ->
                val lowerKey = m.key.lowercase(Locale.ROOT)
                val lowerContent = m.content.lowercase(Locale.ROOT)
                matchingKeywords.any { kw -> lowerKey.contains(kw) || lowerContent.contains(kw) }
            }

            if (matched.isNotEmpty()) {
                val sb = StringBuilder()
                sb.append("Haan, mujhe yaad hai:\n\n")
                matched.forEach { m ->
                    sb.append("• ${m.content}\n")
                }
                return@withContext sb.toString().trim()
            } else {
                // Strictly return actual status instead of making up a fake memory
                return@withContext "Mujhe is baare mein koi memory saved nahi mili."
            }
        }

        if (memories.isEmpty() && recentMessages.isEmpty()) {
            return@withContext "Mujhe abhi aapke bare mein koi saved memory nahi mili. Agar aap kuch yaad rakhwana chahte hain, toh bas bol dijiye 'Ye yaad rakhna' ya 'Remember this'!"
        }

        val sb = StringBuilder()
        sb.append("Aapke bare mein mujhe yeh baatein yaad hain:\n\n")

        val validMemories = memories.filter { it.category != "TEMPORARY" }
        if (validMemories.isNotEmpty()) {
            validMemories.take(6).forEach { m ->
                val badge = when (m.category) {
                    "TASK_WORKFLOW", "WORKFLOW_PREFERENCE" -> "⚙️ Workflow"
                    "PREFERENCE" -> "⭐ Preference"
                    "CRITICAL" -> "🔴 Critical"
                    "IMPORTANT" -> "📌 Important"
                    else -> "📝 Note"
                }
                sb.append("• $badge (${m.key}): ${m.content}\n")
            }
        } else {
            sb.append("Abhi koi explicit preferences saved nahi hain, par pichle conversations ka context mere paas hai.")
        }

        return@withContext sb.toString().trim()
    }

    /**
     * Requirement 2 & 5: Finds remembered workflow preferences matching target app, target entity, or action.
     */
    suspend fun findWorkflowMemory(
        targetApp: String?,
        targetEntity: String? = null,
        action: String? = null,
        query: String = ""
    ): MemoryEntity? = withContext(Dispatchers.IO) {
        val all = memoryDao.getAllMemories()
        if (all.isEmpty()) return@withContext null

        val app = targetApp?.lowercase(Locale.ROOT)?.trim()
        val entity = targetEntity?.lowercase(Locale.ROOT)?.trim()
        val q = query.lowercase(Locale.ROOT).trim()

        // 1. Exact Key match (e.g. "Workflow:WhatsApp:Didi")
        if (!app.isNullOrBlank() && !entity.isNullOrBlank()) {
            val exactKeyMatch = all.firstOrNull {
                it.key.lowercase(Locale.ROOT).contains(app) && it.key.lowercase(Locale.ROOT).contains(entity)
            }
            if (exactKeyMatch != null) return@withContext exactKeyMatch

            // Or content containing both
            val exactContentMatch = all.firstOrNull {
                val c = it.content.lowercase(Locale.ROOT)
                c.contains(app) && c.contains(entity)
            }
            if (exactContentMatch != null) return@withContext exactContentMatch
        }

        // 2. App + Action match (e.g. "Workflow:WhatsApp:Preference")
        if (!app.isNullOrBlank()) {
            val appMatch = all.firstOrNull {
                it.key.lowercase(Locale.ROOT).contains(app) && (it.category == "TASK_WORKFLOW" || it.category == "WORKFLOW_PREFERENCE" || it.category == "PREFERENCE")
            }
            if (appMatch != null) return@withContext appMatch
        }

        // 3. Search query match
        if (q.isNotBlank()) {
            val searchResults = memoryDao.searchMemories(q)
            val workflowMatch = searchResults.firstOrNull { it.category == "TASK_WORKFLOW" || it.category == "WORKFLOW_PREFERENCE" || it.importance >= 4 }
            if (workflowMatch != null) return@withContext workflowMatch
        }

        null
    }

    /**
     * Retrieves all memories relevant to planning a specific task.
     */
    suspend fun findRelevantTaskMemories(
        targetApp: String?,
        targetEntity: String?,
        action: String?,
        prompt: String
    ): List<MemoryEntity> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MemoryEntity>()
        val workflowMem = findWorkflowMemory(targetApp, targetEntity, action, prompt)
        if (workflowMem != null) {
            results.add(workflowMem)
        }

        // Also query general memories for the app or prompt
        if (!targetApp.isNullOrBlank()) {
            val appMems = memoryDao.searchMemories(targetApp)
            for (m in appMems) {
                if (results.none { it.id == m.id }) {
                    results.add(m)
                }
            }
        }

        results
    }

    /**
     * Requirement 6: Learns from a successful task execution by reinforcing the memory and updating timestamp/usage.
     */
    suspend fun recordWorkflowSuccess(
        memory: MemoryEntity,
        learnedDetails: String? = null
    ) = withContext(Dispatchers.IO) {
        val updated = memory.copy(
            lastUsedAt = System.currentTimeMillis(),
            confidence = 1.0f,
            content = if (learnedDetails.isNullOrBlank()) memory.content else "${memory.content.substringBefore(" [Verified Path: ")} [Verified Path: $learnedDetails]"
        )
        memoryDao.updateMemory(updated)
        try {
            okfRepository.updateKnowledge(
                idOrKey = updated.key,
                content = updated.content,
                importance = updated.importance
            )
        } catch (e: Exception) {
            Log.w(TAG, "OKF sync error on success: ${e.message}")
        }
    }

    /**
     * Requirement 6 & 7: Updates an existing workflow memory without creating unnecessary duplicates.
     */
    suspend fun updateWorkflowMemory(
        key: String,
        updatedContent: String,
        targetApp: String? = null,
        targetEntity: String? = null
    ) = withContext(Dispatchers.IO) {
        val existing = memoryDao.searchMemories(key).firstOrNull()
        if (existing != null) {
            val updated = existing.copy(
                content = updatedContent,
                lastUsedAt = System.currentTimeMillis(),
                confidence = 1.0f
            )
            memoryDao.updateMemory(updated)
            try {
                okfRepository.updateKnowledge(
                    idOrKey = updated.key,
                    content = updated.content
                )
            } catch (e: Exception) {
                Log.w(TAG, "OKF sync error on update: ${e.message}")
            }
        } else {
            val newEntity = MemoryEntity(
                key = key,
                content = updatedContent,
                category = "TASK_WORKFLOW",
                importance = 5,
                confidence = 1.0f,
                userConfirmed = true
            )
            val id = memoryDao.insertMemory(newEntity)
            try {
                okfRepository.createKnowledge(
                    key = key,
                    content = updatedContent,
                    category = OkfCategory.RUNBOOK,
                    importance = 5,
                    provenance = OkfProvenance(source = "TASK_LEARNING", author = "Kavya Memory Engine")
                )
            } catch (e: Exception) {
                Log.w(TAG, "OKF sync error on create: ${e.message}")
            }
        }
    }

    // --- Private Extraction Helpers ---

    private fun isForgetCommand(lower: String): Boolean {
        return lower.startsWith("forget ") || lower.contains("bhool jao") ||
                lower.contains("delete memory") || lower.contains("don't remember") ||
                lower.contains("remove memory")
    }

    private fun extractKeyToForget(lower: String, original: String): String {
        return when {
            lower.contains("forget that") -> original.substringAfter("forget that", "").trim()
            lower.contains("forget") -> original.substringAfter("forget", "").trim()
            lower.contains("bhool jao") -> original.substringBefore("bhool jao", "").trim()
            lower.contains("remove") -> original.substringAfter("remove", "").trim()
            else -> ""
        }
    }

    private fun extractCorrection(lower: String, original: String): Pair<String, String>? {
        if (lower.startsWith("no, i meant ") || lower.startsWith("actually i meant ") || lower.startsWith("nahin, mera matlab ")) {
            val correctedText = when {
                lower.startsWith("no, i meant ") -> original.substringAfter("no, i meant ", "").trim()
                lower.startsWith("actually i meant ") -> original.substringAfter("actually i meant ", "").trim()
                else -> original.substringAfter("nahin, mera matlab ", "").trim()
            }
            if (correctedText.isNotBlank()) {
                val topic = if (correctedText.contains("chrome", true) || correctedText.contains("youtube", true) || correctedText.contains("browser", true)) {
                    "Preferred App"
                } else if (correctedText.contains("hinglish", true) || correctedText.contains("hindi", true) || correctedText.contains("english", true)) {
                    "Language Preference"
                } else {
                    "Recent Clarification"
                }
                return Pair(topic, correctedText)
            }
        }
        return null
    }

    private fun extractExplicitMemory(lower: String, original: String): MemoryTuple? {
        val rememberPrefixTriggers = listOf(
            "aage se aise karna: ",
            "aage se aise karna ",
            "aage se aisa karna: ",
            "aage se aisa karna ",
            "aage se aise hi karna ",
            "aage se dhyan rakhna ki ",
            "aage se dhyan rakhna ",
            "aage se ",
            "remember this: ",
            "remember this ",
            "remember that: ",
            "remember that ",
            "remember to ",
            "remember ",
            "yaad rakhna ki ",
            "yaad rakhna: ",
            "yaad rakhna ",
            "yaad rakho ki ",
            "yaad rakho ",
            "isko remember karo ki ",
            "isko remember karo ",
            "ise remember karo ",
            "isko yaad rakho ki ",
            "isko yaad rakho ",
            "isko yaad rakhna ki ",
            "isko yaad rakhna ",
            "note down that ",
            "always remember "
        )

        for (trigger in rememberPrefixTriggers) {
            if (lower.startsWith(trigger)) {
                val rawFact = original.substring(trigger.length).trim(' ', ',', '.', ':', ';', '!', '\"', '\'')
                if (rawFact.isNotBlank()) {
                    return classifyFact(rawFact)
                }
            }
        }

        // Check for suffix triggers like "Ye yaad rakhna", "Isko remember karo", "Aage se aise karna"
        val suffixPatterns = listOf(
            "(?i)\\s*[,.]?\\s*(?:ye\\s+|yeh\\s+|isko\\s+|ise\\s+)?(?:yaad\\s+rakhna|yaad\\s+rakho|remember\\s+karo|remember\\s+rakhna|remember\\s+this|remember\\s+that|aage\\s+se\\s+aise\\s+karna|aage\\s+se\\s+aisa\\s+karna|aage\\s+se\\s+aise\\s+hi\\s+karna|याद\\s*रखना|याद\\s*रखो)[.!]?$",
            "(?i)\\s*[,.]?\\s*(?:next\\s+time\\s+do\\s+it\\s+like\\s+this|from\\s+now\\s+on\\s+do\\s+this)[.!]?$"
        )

        for (pat in suffixPatterns) {
            val regex = pat.toRegex()
            if (regex.containsMatchIn(original)) {
                val cleaned = original.replace(regex, "").trim(' ', ',', '.', ':', ';', '!', '\"', '\'')
                if (cleaned.isNotBlank()) {
                    return classifyFact(cleaned)
                }
            }
        }

        // Direct statements: "My project is X", "My favorite music is Y", "I prefer Hinglish"
        if (lower.startsWith("my project is ") || lower.startsWith("my ongoing project is ")) {
            val fact = original.substringAfter("is ").trim()
            return MemoryTuple("Ongoing Project", fact, "IMPORTANT", 4)
        }
        if (lower.startsWith("i prefer ") || lower.startsWith("my preference is ")) {
            val fact = original.substringAfter("prefer ", "").ifEmpty { original.substringAfter("is ") }.trim()
            return MemoryTuple("User Preference", fact, "PREFERENCE", 4)
        }
        if (lower.startsWith("mera naam ") || lower.startsWith("my name is ")) {
            val name = original.substringAfter("is ").substringAfter("naam ").trim()
            return MemoryTuple("User Name", name, "CRITICAL", 5)
        }

        return null
    }

    private fun classifyFact(fact: String): MemoryTuple {
        val lower = fact.lowercase(Locale.ROOT)

        // Check for App Workflow Instructions (Requirement 1, 2, 8)
        val isWorkflowInstruction = lower.contains("whatsapp") || lower.contains("spotify") ||
                lower.contains("youtube") || lower.contains("chrome") ||
                lower.contains("message") || lower.contains("chat") ||
                lower.contains("type") || lower.contains("call") ||
                lower.contains("play") || lower.contains("open hone ke baad") ||
                lower.contains("composer") || lower.contains("aage se")

        if (isWorkflowInstruction) {
            var app = when {
                lower.contains("whatsapp") || lower.contains("वाट्सएप") || lower.contains("व्हाट्सएप") -> "WhatsApp"
                lower.contains("spotify") || lower.contains("स्पॉटिफाई") -> "Spotify"
                lower.contains("youtube") || lower.contains("यूट्यूब") -> "YouTube"
                lower.contains("chrome") || lower.contains("browser") -> "Google Chrome"
                lower.contains("phone") || lower.contains("call") -> "Phone"
                else -> null
            }

            var entity: String? = null
            val candidates = listOf(
                "didi", "rohan", "mummy", "mom", "papa", "dad", "bhai", "sister",
                "brother", "rahul", "priya", "amit", "neha", "arijit singh", "boss"
            )
            for (candidate in candidates) {
                if (lower.contains(candidate)) {
                    entity = candidate.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                    break
                }
            }

            val key = when {
                app != null && entity != null -> "Workflow:$app:$entity"
                app != null -> "Workflow:$app:Preference"
                entity != null -> "Workflow:Contact:$entity"
                else -> "Workflow:${fact.take(20).trim().replace(" ", "_")}"
            }
            return MemoryTuple(key, fact, "TASK_WORKFLOW", 5)
        }

        return when {
            lower.contains("friendly") || lower.contains("tone") || lower.contains("personality") || lower.contains("polite") || lower.contains("formal") || lower.contains("casual") || lower.contains("behavior") || lower.contains("dostana") || lower.contains("pyar se") -> {
                MemoryTuple("Personality & Tone", fact, "PERSONALITY", 5)
            }
            lower.contains("hinglish") || lower.contains("hindi") || lower.contains("english") || lower.contains("language") -> {
                MemoryTuple("Language Preference", fact, "PREFERENCE", 4)
            }
            lower.contains("project") || lower.contains("task") || lower.contains("deadline") || lower.contains("meeting") -> {
                MemoryTuple("Project & Plans", fact, "IMPORTANT", 4)
            }
            lower.contains("favorite") || lower.contains("like") || lower.contains("pasand") || lower.contains("prefer") -> {
                MemoryTuple("Personal Preferences", fact, "PREFERENCE", 3)
            }
            lower.contains("name") || lower.contains("birthday") || lower.contains("emergency") -> {
                MemoryTuple("Key Identity & Life Facts", fact, "CRITICAL", 5)
            }
            else -> {
                val key = fact.take(24).trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                MemoryTuple(key, fact, "IMPORTANT", 3)
            }
        }
    }

    private fun extractAutomaticPreference(lower: String, original: String): MemoryTuple? {
        // Automatic personality & preference candidate identification
        if (lower.contains("friendly") || lower.contains("dostana") || lower.contains("talk politely") || lower.contains("be casual") || lower.contains("tone") || lower.contains("personality") || lower.contains("aise bolo")) {
            return MemoryTuple("Personality & Tone", original, "PERSONALITY", 5)
        }
        if (lower.contains("always reply in hinglish") || (lower.contains("hinglish mein bolo") && !lower.contains("remember"))) {
            return MemoryTuple("Language Preference", "User frequently prefers Hinglish responses.", "PREFERENCE", 3)
        }
        if (lower.contains("chhota bolo") || lower.contains("be concise") || lower.contains("keep it brief")) {
            return MemoryTuple("Response Style", "User prefers concise, to-the-point responses.", "PREFERENCE", 3)
        }
        return null
    }

    suspend fun deleteMemoryById(id: Long) = withContext(Dispatchers.IO) {
        val existing = memoryDao.getAllMemories().firstOrNull { it.id == id }
        if (existing != null) {
            okfRepository.deleteKnowledge(existing.key, permanent = false)
        }
        memoryDao.deleteById(id)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        okfRepository.clearAll()
        memoryDao.clearAll()
    }

    suspend fun insertCustomMemory(key: String, content: String, category: String, importance: Int) = withContext(Dispatchers.IO) {
        val cleanKey = key.trim()
        val cleanContent = content.trim()
        val cleanCategory = category.trim().uppercase(Locale.ROOT)
        val cleanImportance = importance.coerceIn(1, 5)

        // Search-Before-Write via OKF repository
        okfRepository.createKnowledge(
            key = cleanKey,
            content = cleanContent,
            category = OkfCategory.fromString(cleanCategory),
            importance = cleanImportance,
            provenance = OkfProvenance(source = "USER_EXPLICIT", author = "User")
        )

        val entity = MemoryEntity(
            key = cleanKey,
            content = cleanContent,
            category = cleanCategory,
            importance = cleanImportance,
            createdAt = System.currentTimeMillis(),
            lastUsedAt = System.currentTimeMillis(),
            confidence = 1.0f,
            userConfirmed = true
        )
        memoryDao.insertMemory(entity)
    }

    suspend fun updateMemory(memory: MemoryEntity) = withContext(Dispatchers.IO) {
        okfRepository.updateKnowledge(
            idOrKey = memory.key,
            content = memory.content,
            category = OkfCategory.fromString(memory.category),
            importance = memory.importance
        )
        memoryDao.updateMemory(memory)
    }

    suspend fun autoLearnTaskProgress(userPrompt: String, aiResponse: String) = withContext(Dispatchers.IO) {
        val lower = userPrompt.lowercase(Locale.ROOT)
        if (lower.contains("create") || lower.contains("add") || lower.contains("implement") || lower.contains("build") || lower.contains("fix") || lower.contains("feature") || lower.contains("bana") || lower.contains("joda")) {
            val taskKey = "project_task_" + System.currentTimeMillis()
            val summary = "Task: $userPrompt | Outcome: Completed/Processed successfully."
            val existing = memoryDao.getAllMemories().filter { it.content.contains(userPrompt, ignoreCase = true) }
            if (existing.isEmpty()) {
                val entity = MemoryEntity(
                    key = taskKey,
                    content = summary,
                    category = "PROJECT_PROGRESS",
                    importance = 4,
                    sourceConversation = userPrompt,
                    confidence = 0.9f,
                    userConfirmed = false
                )
                memoryDao.insertMemory(entity)
                okfRepository.createKnowledge(
                    key = taskKey,
                    content = summary,
                    category = OkfCategory.fromString("PROJECT_PROGRESS"),
                    importance = 4,
                    provenance = OkfProvenance(source = "AUTO_LEARN", author = "Kavya AGI Engine")
                )
            }
        }
    }
}

data class MemoryTuple(
    val key: String,
    val content: String,
    val category: String,
    val importance: Int
)

data class MemoryExtractionResult(
    val detected: Boolean,
    val isForgetAction: Boolean = false,
    val saved: Boolean = false,
    val confirmationText: String = "",
    val affectedKey: String? = null,
    val persistedMemory: MemoryEntity? = null
)
