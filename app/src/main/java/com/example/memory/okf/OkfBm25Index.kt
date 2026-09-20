package com.example.memory.okf

import java.util.Locale
import kotlin.math.ln

data class ScoredKnowledgeUnit(
    val unit: OkfKnowledgeUnit,
    val score: Double,
    val matchedTerms: List<String>
)

/**
 * Pure local BM25 (Best Matching 25) Information Retrieval Engine.
 * Does NOT require external embedding APIs, vector databases, or cloud endpoints.
 */
class OkfBm25Index(
    private val k1: Double = 1.5,
    private val b: Double = 0.75
) {
    companion object {
        private val STOP_WORDS = setOf(
            "the", "is", "at", "which", "on", "a", "an", "and", "or", "in", "for", "to", "of",
            "this", "that", "it", "with", "as", "by", "from", "be", "are", "was", "were", "will",
            "hai", "ki", "ka", "ke", "ko", "me", "mein", "se", "aur", "ye", "yeh", "karo", "karna"
        )
    }

    /**
     * Tokenizes text into normalized stems/words, removing punctuation and common stopwords.
     */
    fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return text.lowercase(Locale.ROOT)
            .split(Regex("[\\s.,;:!?()\\[\\]{}\"'\n\r\t/\\\\_-]+"))
            .filter { it.length > 2 && !STOP_WORDS.contains(it) }
    }

    /**
     * Scores a collection of knowledge units against a user query using BM25.
     * Incorporates title boost, importance factor, and category priorities.
     */
    fun search(
        query: String,
        corpus: List<OkfKnowledgeUnit>,
        targetCategory: OkfCategory? = null,
        topK: Int = 5
    ): List<ScoredKnowledgeUnit> {
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty() || corpus.isEmpty()) return emptyList()

        // Filter corpus by category if specified
        val candidateUnits = if (targetCategory != null) {
            corpus.filter { it.category == targetCategory && it.status == OkfStatus.ACTIVE }
        } else {
            corpus.filter { it.status == OkfStatus.ACTIVE }
        }
        if (candidateUnits.isEmpty()) return emptyList()

        val totalDocs = candidateUnits.size.toDouble()

        // 1. Compute document token frequencies and lengths
        // Key/title tokens are boosted by 2.5x
        val docTokensList = candidateUnits.map { unit ->
            val keyTokens = tokenize(unit.key)
            val contentTokens = tokenize(unit.content)
            val tagTokens = unit.tags.flatMap { tokenize(it) }
            
            // Build weighted token frequency map for this document
            val tfMap = mutableMapOf<String, Double>()
            keyTokens.forEach { t -> tfMap[t] = (tfMap[t] ?: 0.0) + 2.5 }
            contentTokens.forEach { t -> tfMap[t] = (tfMap[t] ?: 0.0) + 1.0 }
            tagTokens.forEach { t -> tfMap[t] = (tfMap[t] ?: 0.0) + 1.8 }

            val docLength = keyTokens.size * 2.5 + contentTokens.size + tagTokens.size * 1.8
            Triple(unit, tfMap, docLength)
        }

        val avgDocLength = docTokensList.map { it.third }.average().coerceAtLeast(1.0)

        // 2. Compute Document Frequency (df) for each query token
        val dfMap = mutableMapOf<String, Int>()
        for (qTerm in queryTokens) {
            var count = 0
            for ((_, tfMap, _) in docTokensList) {
                if (tfMap.containsKey(qTerm)) count++
            }
            dfMap[qTerm] = count
        }

        // 3. Score each document with BM25 formula
        val scoredList = mutableListOf<ScoredKnowledgeUnit>()

        for ((unit, tfMap, docLength) in docTokensList) {
            var bm25Score = 0.0
            val matchedTerms = mutableListOf<String>()

            for (qTerm in queryTokens) {
                val tf = tfMap[qTerm] ?: 0.0
                if (tf > 0) {
                    matchedTerms.add(qTerm)
                    val df = dfMap[qTerm] ?: 0
                    // Standard Robertson-Spärck Jones IDF
                    val idf = ln(1.0 + (totalDocs - df + 0.5) / (df + 0.5)).coerceAtLeast(0.1)
                    val tfNorm = (tf * (k1 + 1.0)) / (tf + k1 * (1.0 - b + b * (docLength / avgDocLength)))
                    bm25Score += idf * tfNorm
                }
            }

            if (bm25Score > 0.0) {
                // Apply importance boost: scale from 1.0 (importance 1) to 1.4 (importance 5)
                val importanceMultiplier = 1.0 + (unit.importance - 1) * 0.1
                
                // Category priority boost for user-approved preferences and decisions
                val categoryMultiplier = when (unit.category) {
                    OkfCategory.PREFERENCE -> 1.3
                    OkfCategory.DECISION -> 1.25
                    OkfCategory.CONSTRAINT -> 1.2
                    OkfCategory.ARCHITECTURE -> 1.15
                    else -> 1.0
                }

                val finalScore = bm25Score * importanceMultiplier * categoryMultiplier
                scoredList.add(ScoredKnowledgeUnit(unit, finalScore, matchedTerms))
            }
        }

        return scoredList
            .sortedByDescending { it.score }
            .take(topK)
    }

    /**
     * Computes similarity score between a proposed topic/content and existing unit
     * for Search-Before-Write conflict and duplicate detection.
     */
    fun computeSimilarity(text1: String, text2: String): Double {
        val t1 = tokenize(text1).toSet()
        val t2 = tokenize(text2).toSet()
        if (t1.isEmpty() || t2.isEmpty()) return 0.0
        val intersection = t1.intersect(t2).size.toDouble()
        val union = t1.union(t2).size.toDouble()
        return if (union > 0) intersection / union else 0.0
    }
}
