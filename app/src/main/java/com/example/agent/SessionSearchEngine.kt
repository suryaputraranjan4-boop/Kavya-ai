package com.example.agent

import com.example.data.ChatDao
import com.example.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SessionSearchResult(
    val snippet: String,
    val timestamp: Long,
    val formattedDate: String,
    val relevanceScore: Float
)

/**
 * Searches historical user conversations locally from SQLite Room without sending
 * the entire chat history to Gemini (Requirement 9).
 */
class SessionSearchEngine(private val chatDao: ChatDao) {

    private val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())

    /**
     * Searches previous chat sessions for a specific topic, question, or keyword.
     * Extracts only high-relevance snippets within a compact context window.
     */
    suspend fun searchPastConversations(rawQuery: String): List<SessionSearchResult> = withContext(Dispatchers.IO) {
        val cleanTerms = rawQuery.lowercase(Locale.ROOT)
            .replace("[.,!?;:]".toRegex(), " ")
            .split("\\s+".toRegex())
            .filter { it.length > 2 && it !in listOf("what", "did", "talk", "about", "last", "week", "kya", "baat", "humne", "kavya", "with", "this", "that") }

        if (cleanTerms.isEmpty()) {
            val directMatches = chatDao.searchMessages(rawQuery.take(20))
            return@withContext formatResults(directMatches, listOf(rawQuery))
        }

        val allCandidates = mutableListOf<MessageEntity>()
        for (term in cleanTerms.take(4)) {
            val matches = chatDao.searchMessages(term)
            allCandidates.addAll(matches)
        }

        val distinctMatches = allCandidates.distinctBy { it.id }
        return@withContext formatResults(distinctMatches, cleanTerms)
    }

    private fun formatResults(messages: List<MessageEntity>, terms: List<String>): List<SessionSearchResult> {
        return messages.map { msg ->
            val lowerText = msg.text.lowercase(Locale.ROOT)
            var score = 0f
            for (term in terms) {
                if (lowerText.contains(term)) score += 1.0f
            }
            val snippetPrefix = if (msg.isUser) "User: " else "Kavya: "
            val compactText = if (msg.text.length > 200) msg.text.take(197) + "..." else msg.text
            SessionSearchResult(
                snippet = "$snippetPrefix$compactText",
                timestamp = msg.timestamp,
                formattedDate = dateFormat.format(Date(msg.timestamp)),
                relevanceScore = score
            )
        }.sortedByDescending { it.relevanceScore }
            .take(6)
    }
}
