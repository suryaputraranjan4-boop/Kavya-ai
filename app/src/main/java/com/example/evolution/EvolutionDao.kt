package com.example.evolution

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EvolutionDao {
    @Query("SELECT * FROM evolution_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<EvolutionHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: EvolutionHistoryEntity)

    @Query("SELECT * FROM evolution_reports ORDER BY timestamp DESC LIMIT 1")
    fun getLatestReport(): Flow<EvolutionReportEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReport(report: EvolutionReportEntity)

    @Query("SELECT * FROM evolution_upgrades ORDER BY timestamp DESC")
    fun getAllUpgrades(): Flow<List<EvolutionUpgradeEntity>>

    @Query("SELECT * FROM evolution_upgrades WHERE status = 'PENDING'")
    fun getPendingUpgrades(): Flow<List<EvolutionUpgradeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUpgrade(upgrade: EvolutionUpgradeEntity)

    @Query("UPDATE evolution_upgrades SET status = :newStatus WHERE id = :id")
    suspend fun updateUpgradeStatus(id: String, newStatus: String)

    @Query("DELETE FROM evolution_history")
    suspend fun clearHistory()
}
