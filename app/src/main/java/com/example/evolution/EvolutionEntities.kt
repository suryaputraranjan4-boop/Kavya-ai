package com.example.evolution

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "evolution_history")
data class EvolutionHistoryEntity(
    @PrimaryKey val id: String,
    val date: String,
    val projectName: String,
    val capability: String,
    val securityResult: String,
    val action: String,
    val status: String,
    val version: String,
    val timestamp: Long
)

@Entity(tableName = "evolution_reports")
data class EvolutionReportEntity(
    @PrimaryKey val id: String,
    val date: String,
    val scanTime: String,
    val discovered: Int,
    val analyzed: Int,
    val passed: Int,
    val quarantined: Int,
    val rejected: Int,
    val newCaps: Int,
    val candidates: Int,
    val applied: Int,
    val pending: Int,
    val rollbacks: Int,
    val timestamp: Long
)

@Entity(tableName = "evolution_upgrades")
data class EvolutionUpgradeEntity(
    @PrimaryKey val id: String,
    val projectName: String,
    val projectUrl: String,
    val commitSha: String,
    val license: String,
    val licenseCompatible: Boolean,
    val capabilityImproved: String,
    val reason: String,
    val securityResult: String,
    val riskLevel: String,
    val upgradeLevel: String,
    val status: String,
    val beforeSnippet: String,
    val afterSnippet: String,
    val filesAffected: String,
    val testResultsSummary: String,
    val performanceBenchmark: String,
    val date: String,
    val version: String,
    val timestamp: Long
)
