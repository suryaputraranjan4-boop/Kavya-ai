package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AutomationFailureDao {
    @Query("SELECT * FROM automation_failures ORDER BY timestamp DESC")
    suspend fun getAllFailures(): List<AutomationFailureEntity>

    @Query("SELECT * FROM automation_failures WHERE targetApp = :targetApp ORDER BY timestamp DESC")
    suspend fun getFailuresForApp(targetApp: String): List<AutomationFailureEntity>

    @Query("SELECT successfulAlternative FROM automation_failures WHERE targetApp = :targetApp AND targetAction = :targetAction AND successfulAlternative IS NOT NULL ORDER BY timestamp DESC LIMIT 1")
    suspend fun getAlternative(targetApp: String, targetAction: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFailure(failure: AutomationFailureEntity): Long

    @Query("UPDATE automation_failures SET successfulAlternative = :alternative WHERE targetApp = :targetApp AND targetAction = :targetAction")
    suspend fun updateAlternative(targetApp: String, targetAction: String, alternative: String)
}
