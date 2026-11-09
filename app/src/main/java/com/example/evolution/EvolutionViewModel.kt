package com.example.evolution

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EvolutionViewModel(application: Application) : AndroidViewModel(application) {
    private val database = com.example.data.AppDatabase.getDatabase(application)
    private val evolutionDao = database.evolutionDao()
    private val evolutionEngine = EvolutionEngine(application)

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _latestReport = MutableStateFlow<EvolutionReportEntity?>(null)
    val latestReport: StateFlow<EvolutionReportEntity?> = _latestReport.asStateFlow()

    private val _historyList = MutableStateFlow<List<EvolutionHistoryEntity>>(emptyList())
    val historyList: StateFlow<List<EvolutionHistoryEntity>> = _historyList.asStateFlow()

    private val _pendingUpgrades = MutableStateFlow<List<EvolutionUpgradeEntity>>(emptyList())
    val pendingUpgrades: StateFlow<List<EvolutionUpgradeEntity>> = _pendingUpgrades.asStateFlow()

    private val _allUpgrades = MutableStateFlow<List<EvolutionUpgradeEntity>>(emptyList())
    val allUpgrades: StateFlow<List<EvolutionUpgradeEntity>> = _allUpgrades.asStateFlow()

    private val _evolutionSettings = MutableStateFlow(
        EvolutionSettings(
            enabled = true,
            researchTime = "02:00 AM",
            timezone = "User Timezone",
            frequency = "Every Day",
            maxProjectsPerDay = 10,
            automaticResearch = true,
            automaticTesting = true,
            automaticInstallation = false,
            automaticSafeUpgrade = true,
            humanApprovalForMajorChanges = true,
            securityLevel = "Maximum"
        )
    )
    val evolutionSettings: StateFlow<EvolutionSettings> = _evolutionSettings.asStateFlow()

    init {
        viewModelScope.launch {
            evolutionDao.getLatestReport().collectLatest { report ->
                _latestReport.value = report
                if (report == null) {
                    runManualScan()
                }
            }
        }
        viewModelScope.launch {
            evolutionDao.getAllHistory().collectLatest { history ->
                _historyList.value = history
            }
        }
        viewModelScope.launch {
            evolutionDao.getPendingUpgrades().collectLatest { pending ->
                _pendingUpgrades.value = pending
            }
        }
        viewModelScope.launch {
            evolutionDao.getAllUpgrades().collectLatest { upgrades ->
                _allUpgrades.value = upgrades
            }
        }
    }

    fun runManualScan() {
        if (_isScanning.value) return
        _isScanning.value = true
        viewModelScope.launch {
            try {
                evolutionEngine.runDailyScan()
            } catch (e: Exception) {
                android.util.Log.w("EvolutionViewModel", "Evolution scan failed (${e.javaClass.simpleName}).")
            } finally {
                _isScanning.value = false
            }
        }
    }

    private fun currentDate(): String =
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date())

    fun approveUpgrade(upgradeId: String) {
        viewModelScope.launch {
            // Approval is recorded for manual review only. Evolution does not install code.
            evolutionDao.updateUpgradeStatus(upgradeId, "APPROVED_FOR_MANUAL_REVIEW")
            evolutionDao.insertHistory(
                EvolutionHistoryEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    date = currentDate(),
                    projectName = "Upgrade candidate",
                    capability = "Manual review",
                    securityResult = "NOT_SCANNED",
                    action = "Manual review approved; no code applied",
                    status = "Review only",
                    version = "—",
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun rejectUpgrade(upgradeId: String) {
        viewModelScope.launch {
            evolutionDao.updateUpgradeStatus(upgradeId, "REJECTED")
            evolutionDao.insertHistory(
                EvolutionHistoryEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    date = currentDate(),
                    projectName = "Upgrade candidate",
                    capability = "Manual review",
                    securityResult = "NOT_SCANNED",
                    action = "Candidate rejected by user",
                    status = "Rejected",
                    version = "—",
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun rollbackLastUpgrade() {
        viewModelScope.launch {
            // No source changes are installed by this research-only engine, so there is
            // no applied change set to restore. Record the unavailable operation honestly.
            evolutionDao.insertHistory(
                EvolutionHistoryEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    date = currentDate(),
                    projectName = "Evolution rollback",
                    capability = "No installed change set",
                    securityResult = "NOT_APPLICABLE",
                    action = "Rollback unavailable: no code changes were installed",
                    status = "Not performed",
                    version = "—",
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun updateSettings(newSettings: EvolutionSettings) {
        _evolutionSettings.value = newSettings
    }
}
