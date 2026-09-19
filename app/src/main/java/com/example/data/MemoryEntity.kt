package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Real persistent long-term memory model for Kavya.
 * Tracks user preferences, important facts, conversational context, and verified learning.
 */
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val content: String = "",
    val category: String = "PREFERENCE", // PREFERENCE, IMPORTANT, CRITICAL, CONTEXT, TEMPORARY
    val importance: Int = 3,            // 1 (lowest/temporary) to 5 (critical)
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
    val sourceConversation: String = "",
    val confidence: Float = 1.0f,
    val userConfirmed: Boolean = true
)

