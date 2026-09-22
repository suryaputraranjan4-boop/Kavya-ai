package com.example.evolution

data class EvolutionProject(
    val id: String,
    val name: String,
    val owner: String,
    val url: String,
    val license: String,
    val language: String,
    val framework: String,
    val stars: Int,
    val forks: Int,
    val lastUpdate: String,
    val dependencies: List<String>,
    val documentationQuality: String,
    val architecture: String,
    val requiredPermissions: List<String>,
    val potentialUsefulness: String,
    val maintenanceStatus: String
)

data class SecurityScanResult(
    val repoId: String,
    val isClean: Boolean,
    val secretsFound: List<String>,
    val vulnerabilities: List<String>,
    val suspiciousBehaviors: List<String>,
    val securityLevel: String, // SAFE, WARNING, DANGEROUS
    val quarantined: Boolean,
    val rejectionReason: String?
)

data class SandboxTestResult(
    val repoId: String,
    val success: Boolean,
    val latencyDeltaMs: Long,
    val memoryDeltaMb: Int,
    val toolExecutionSuccess: Boolean,
    val errorMessage: String?
)

data class CapabilityScore(
    val capabilityName: String,
    val score: Float // 0.0 to 1.0
)

data class UpgradePlan(
    val id: String,
    val projectName: String,
    val projectUrl: String,
    val commitSha: String,
    val license: String,
    val licenseCompatible: Boolean,
    val capabilityImproved: String,
    val reason: String,
    val securityResult: String, // PASSED, WARNING, FAILED
    val riskLevel: String, // LOW, MEDIUM, HIGH
    val upgradeLevel: String, // SAFE, REVIEW, BLOCKED
    val status: String, // PENDING, APPROVED, REJECTED, APPLIED, ROLLED_BACK
    val beforeSnippet: String,
    val afterSnippet: String,
    val filesAffected: List<String>,
    val testResultsSummary: String,
    val performanceBenchmark: String,
    val date: String,
    val version: String
)

data class EvolutionReport(
    val id: String,
    val date: String,
    val scanTime: String,
    val repositoriesDiscovered: Int,
    val repositoriesAnalyzed: Int,
    val securityPassed: Int,
    val quarantined: Int,
    val rejected: Int,
    val newCapabilitiesFound: Int,
    val upgradeCandidates: Int,
    val changesApplied: Int,
    val pendingApproval: Int,
    val rollbackEvents: Int
)

data class EvolutionSettings(
    val enabled: Boolean,
    val researchTime: String,
    val timezone: String,
    val frequency: String,
    val maxProjectsPerDay: Int,
    val automaticResearch: Boolean,
    val automaticTesting: Boolean,
    val automaticInstallation: Boolean,
    val automaticSafeUpgrade: Boolean,
    val humanApprovalForMajorChanges: Boolean,
    val securityLevel: String
)
