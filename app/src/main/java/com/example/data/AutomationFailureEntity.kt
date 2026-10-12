package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Records automation interaction failures and learned successful alternatives.
 * Enables behavioral learning without uncontrolled code rewriting.
 */
@Entity(tableName = "automation_failures")
data class AutomationFailureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val targetApp: String,
    val targetAction: String,
    val failureType: String,
    val screenSummary: String,
    val successfulAlternative: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
