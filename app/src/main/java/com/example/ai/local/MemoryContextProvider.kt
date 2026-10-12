package com.example.ai.local

import android.content.Context
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.MemoryEntity
import com.example.memory.okf.OkfMemoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Memory Context Provider for Kavya AI.
 * Bridges local Qwen3-4B inference with Kavya's existing Room / OKF persistent memory database.
 * Retrieves top relevant memories using token score matching to keep prompt size optimized.
 */
class MemoryContextProvider private constructor(private val context: Context) {

    companion object {
        private const val TAG = "KavyaMemoryContextProvider"

        @Volatile
        private var instance: MemoryContextProvider? = null

        fun getInstance(context: Context): MemoryContextProvider {
            return instance ?: synchronized(this) {
                instance ?: MemoryContextProvider(context.applicationContext).also { instance = it }
            }
        }
    }

    private val db = AppDatabase.getDatabase(context)
    private val memoryDao = db.memoryDao()
    private val okfRepo = OkfMemoryRepository.getInstance(context)

    /**
     * Retrieves up to 5 relevant persistent memories based on keywords in the prompt.
     */
    suspend fun retrieveRelevantMemoryContext(prompt: String): String = withContext(Dispatchers.IO) {
        try {
            val allMemories = memoryDao.getAllMemories()
            if (allMemories.isEmpty()) return@withContext ""

            val lowerPrompt = prompt.lowercase(Locale.ROOT)
            val keywords = lowerPrompt.split("\\s+".toRegex()).filter { it.length > 3 }

            val scoredMemories = allMemories.map { memory ->
                val textToMatch = "${memory.key} ${memory.content} ${memory.category}".lowercase(Locale.ROOT)
                var score = 0
                for (kw in keywords) {
                    if (textToMatch.contains(kw)) {
                        score += 2
                    }
                }
                if (memory.importance >= 4) {
                    score += 1
                }
                Pair(memory, score)
            }

            val topMemories = scoredMemories
                .filter { it.second > 0 || it.first.importance >= 5 }
                .sortedByDescending { it.second }
                .take(5)
                .map { it.first }

            val memoriesToUse = if (topMemories.isNotEmpty()) topMemories else allMemories.take(3)

            val sb = StringBuilder()
            sb.append("\n[KAVYA PERSISTENT USER MEMORY & PREFERENCES]:\n")
            for (m in memoriesToUse) {
                sb.append("• ").append(m.key).append(": ").append(m.content).append("\n")
            }
            return@withContext sb.toString().trim()
        } catch (e: Exception) {
            Log.w(TAG, "Error retrieving memory context: ${e.message}")
            return@withContext ""
        }
    }

    /**
     * Saves a explicit memory entry into Kavya's permanent Room and OKF memory storage.
     */
    suspend fun saveUserMemoryExplicit(key: String, content: String, category: String = "USER_PREFERENCE"): Boolean = withContext(Dispatchers.IO) {
        try {
            val entity = MemoryEntity(
                key = key,
                content = content,
                category = category,
                importance = 4,
                sourceConversation = content,
                confidence = 1.0f,
                userConfirmed = true
            )
            memoryDao.insertMemory(entity)
            okfRepo.createKnowledge(
                key = key,
                content = content,
                importance = 4,
                provenance = com.example.memory.okf.OkfProvenance(source = "OFFLINE_GEMMA", author = "User")
            )
            Log.i(TAG, "Explicit memory saved locally: $key = $content")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save explicit memory", e)
            false
        }
    }
}
