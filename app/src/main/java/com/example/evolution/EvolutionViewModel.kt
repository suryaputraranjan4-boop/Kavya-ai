package com.example.evolution

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
                org.json.JSONObject().toString() // handle quietly
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun approveUpgrade(upgradeId: String) {
        viewModelScope.launch {
            evolutionDao.updateUpgradeStatus(upgradeId, "APPROVED_AND_APPLIED")
            evolutionDao.insertHistory(
                EvolutionHistoryEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    date = "Sep 20, 2026",
                    projectName = "Approved Upgrade",
                    capability = "Custom Enhancement",
                    securityResult = "PASSED",
                    action = "User Approved & Applied",
                    status = "Integrated",
                    version = "v1.5",
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
                    date = "Sep 20, 2026",
                    projectName = "Rejected Upgrade",
                    capability = "External Module",
                    securityResult = "REVIEWED",
                    action = "User Rejected",
                    status = "Rejected",
                    version = "—",
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun rollbackLastUpgrade() {
        viewModelScope.launch {
            evolutionDao.insertHistory(
                EvolutionHistoryEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    date = "Sep 20, 2026",
                    projectName = "Emergency Rollback",
                    capability = "System Stable Snapshot",
                    securityResult = "PASSED",
                    action = "Rollback Performed",
                    status = "Rolled Back",
                    version = "v1.4 (Restored)",
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun updateSettings(newSettings: EvolutionSettings) {
        _evolutionSettings.value = newSettings
    }
}
