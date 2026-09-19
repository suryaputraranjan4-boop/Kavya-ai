package com.example.agent

import com.example.utils.AppLaunchDiagnostic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AgentLiveState {
    IDLE,
    PLANNING,
    EXECUTING,
    VERIFYING,
    COMPLETED,
    FAILED
}

data class AgentExecutionDiagnostic(
    val id: String,
    val prompt: String,
    val targetApp: String,
    val totalSteps: Int,
    val completedSteps: Int,
    val success: Boolean,
    val stepsSummary: List<String>,
    val foregroundApp: String,
    val verificationStatus: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Developer diagnostic engine.
 * Records multi-step executions, universal app resolutions, screen inspections, and verification logs.
 * Accessible through developer/debug settings.
 */
object DiagnosticEngine {
    private val _liveState = MutableStateFlow(AgentLiveState.IDLE)
    val liveState: StateFlow<AgentLiveState> = _liveState.asStateFlow()

    private val _currentTaskDescription = MutableStateFlow<String?>("Idle")
    val currentTaskDescription: StateFlow<String?> = _currentTaskDescription.asStateFlow()

    private val _currentTargetApp = MutableStateFlow<String?>("None")
    val currentTargetApp: StateFlow<String?> = _currentTargetApp.asStateFlow()

    private val _lastVerificationResult = MutableStateFlow<String?>("None")
    val lastVerificationResult: StateFlow<String?> = _lastVerificationResult.asStateFlow()

    private val _recentDiagnostics = MutableStateFlow<List<AgentExecutionDiagnostic>>(emptyList())
    val recentDiagnostics: StateFlow<List<AgentExecutionDiagnostic>> = _recentDiagnostics.asStateFlow()

    private val _latestAppLaunchDiagnostic = MutableStateFlow<AppLaunchDiagnostic?>(null)
    val latestAppLaunchDiagnostic: StateFlow<AppLaunchDiagnostic?> = _latestAppLaunchDiagnostic.asStateFlow()

    private val _automationDebugLogs = MutableStateFlow<List<AutomationDebugLog>>(emptyList())
    val automationDebugLogs: StateFlow<List<AutomationDebugLog>> = _automationDebugLogs.asStateFlow()

    private val _latestSelfTestReport = MutableStateFlow<SystemSelfTestReport?>(null)
    val latestSelfTestReport: StateFlow<SystemSelfTestReport?> = _latestSelfTestReport.asStateFlow()

    fun updateLiveState(state: AgentLiveState, task: String? = null, target: String? = null) {
        _liveState.value = state
        if (task != null) _currentTaskDescription.value = task
        if (target != null) _currentTargetApp.value = target
    }

    fun recordVerification(result: String) {
        _lastVerificationResult.value = result
    }

    fun recordSelfTestReport(report: SystemSelfTestReport) {
        _latestSelfTestReport.value = report
    }

    fun recordAppLaunch(diagnostic: AppLaunchDiagnostic) {
        _latestAppLaunchDiagnostic.value = diagnostic
    }

    fun recordAgentExecution(diagnostic: AgentExecutionDiagnostic) {
        _recentDiagnostics.value = listOf(diagnostic) + _recentDiagnostics.value.take(25)
    }

    fun recordAutomationDebugLog(log: AutomationDebugLog) {
        _automationDebugLogs.value = listOf(log) + _automationDebugLogs.value.take(50)
    }

    fun clearDiagnostics() {
        _recentDiagnostics.value = emptyList()
        _latestAppLaunchDiagnostic.value = null
        _automationDebugLogs.value = emptyList()
        _liveState.value = AgentLiveState.IDLE
        _currentTaskDescription.value = "Idle"
        _currentTargetApp.value = "None"
        _lastVerificationResult.value = "None"
    }
}


