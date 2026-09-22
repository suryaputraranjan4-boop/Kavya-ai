package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.evolution.EvolutionViewModel
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvolutionScreen(navController: NavController, viewModel: EvolutionViewModel = viewModel()) {
    val isScanning by viewModel.isScanning.collectAsState()
    val latestReport by viewModel.latestReport.collectAsState()
    val historyList by viewModel.historyList.collectAsState()
    val pendingUpgrades by viewModel.pendingUpgrades.collectAsState()
    val allUpgrades by viewModel.allUpgrades.collectAsState()
    val settings by viewModel.evolutionSettings.collectAsState()

    var selectedTab by remember { mutableStateOf(0) } // 0: Dashboard, 1: Pending, 2: History & Diff, 3: Settings
    var selectedUpgradeForDiff by remember { mutableStateOf<com.example.evolution.EvolutionUpgradeEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kavya Evolution Engine", color = TextPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.runManualScan() }) {
                        if (isScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = PrimaryLight, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Run Scan Now", tint = PrimaryLight)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceGlass)
            )
        },
        containerColor = Background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = SurfaceGlass,
                edgePadding = 16.dp
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Dashboard", color = if (selectedTab == 0) PrimaryLight else TextSecondary) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Pending (${pendingUpgrades.size})", color = if (selectedTab == 1) PrimaryLight else TextSecondary) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("History & Diffs", color = if (selectedTab == 2) PrimaryLight else TextSecondary) }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text("Settings", color = if (selectedTab == 3) PrimaryLight else TextSecondary) }
                )
            }

            when (selectedTab) {
                0 -> EvolutionDashboardTab(latestReport, allUpgrades, viewModel)
                1 -> EvolutionPendingTab(pendingUpgrades, viewModel, onShowDiff = { selectedUpgradeForDiff = it })
                2 -> EvolutionHistoryTab(historyList)
                3 -> EvolutionSettingsTab(settings, viewModel)
            }
        }
    }

    if (selectedUpgradeForDiff != null) {
        AlertDialog(
            onDismissRequest = { selectedUpgradeForDiff = null },
            containerColor = SurfaceVariant,
            title = { Text("Change Diff & Provenance", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text("Project: ${selectedUpgradeForDiff!!.projectName}", style = MaterialTheme.typography.titleSmall, color = PrimaryLight)
                        Text("Source: ${selectedUpgradeForDiff!!.projectUrl}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Text("License: ${selectedUpgradeForDiff!!.license} (Compatible: ${selectedUpgradeForDiff!!.licenseCompatible})", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("BEFORE (Old Implementation):", style = MaterialTheme.typography.labelMedium, color = Error)
                        Box(modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(8.dp)).padding(8.dp)) {
                            Text(selectedUpgradeForDiff!!.beforeSnippet, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("AFTER (New Implementation):", style = MaterialTheme.typography.labelMedium, color = SuccessGreen)
                        Box(modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(8.dp)).padding(8.dp)) {
                            Text(selectedUpgradeForDiff!!.afterSnippet, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Security Result: ${selectedUpgradeForDiff!!.securityResult} | Risk: ${selectedUpgradeForDiff!!.riskLevel}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Text("Benchmark: ${selectedUpgradeForDiff!!.performanceBenchmark}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedUpgradeForDiff = null }) {
                    Text("Close", color = PrimaryLight)
                }
            }
        )
    }
}

@Composable
fun EvolutionDashboardTab(report: com.example.evolution.EvolutionReportEntity?, upgrades: List<com.example.evolution.EvolutionUpgradeEntity>, viewModel: EvolutionViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceGlass),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Today's Evolution Scan Summary", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = TextPrimary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatBox("Scanned", "${report?.discovered ?: 0}")
                        StatBox("Passed", "${report?.passed ?: 0}")
                        StatBox("New Caps", "${report?.newCaps ?: 0}")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatBox("Applied", "${report?.applied ?: 0}")
                        StatBox("Pending", "${report?.pending ?: 0}")
                        StatBox("Blocked", "${report?.rejected ?: 0}")
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceGlass),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Capability Progress Matrix", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = TextPrimary)
                    Spacer(modifier = Modifier.height(12.dp))
                    CapabilityBar("Memory & Context", 0.85f)
                    CapabilityBar("Reasoning & Planning", 0.90f)
                    CapabilityBar("Automation & UI", 0.75f)
                    CapabilityBar("GitHub Evolution Research", 0.95f)
                    CapabilityBar("Self-Testing & Sandbox", 0.80f)
                }
            }
        }

        item {
            Button(
                onClick = { viewModel.rollbackLastUpgrade() },
                colors = ButtonDefaults.buttonColors(containerColor = Error.copy(alpha = 0.2f), contentColor = Error),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Emergency Rollback Last Upgrade")
            }
        }
    }
}

@Composable
fun StatBox(label: String, value: String) {
    Column(
        modifier = Modifier
            .background(SurfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp)
            .width(90.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = PrimaryLight)
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

@Composable
fun CapabilityBar(name: String, progress: Float) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium, color = PrimaryLight)
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = PrimaryLight,
            trackColor = SurfaceVariant
        )
    }
}

@Composable
fun EvolutionPendingTab(pendingUpgrades: List<com.example.evolution.EvolutionUpgradeEntity>, viewModel: EvolutionViewModel, onShowDiff: (com.example.evolution.EvolutionUpgradeEntity) -> Unit) {
    if (pendingUpgrades.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No upgrades awaiting approval.", color = TextSecondary)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(pendingUpgrades) { upgrade ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceGlass),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Project: ${upgrade.projectName}", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = TextPrimary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Improves: ${upgrade.capabilityImproved}", style = MaterialTheme.typography.bodyMedium, color = PrimaryLight)
                        Text("Reason: ${upgrade.reason}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Security: ${upgrade.securityResult}", style = MaterialTheme.typography.labelSmall, color = SuccessGreen)
                            Text("Risk: ${upgrade.riskLevel}", style = MaterialTheme.typography.labelSmall, color = AccentPurpleLight)
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onShowDiff(upgrade) }, modifier = Modifier.weight(1f)) {
                                Text("Diff")
                            }
                            Button(onClick = { viewModel.approveUpgrade(upgrade.id) }, colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen), modifier = Modifier.weight(1f)) {
                                Text("Approve")
                            }
                            Button(onClick = { viewModel.rejectUpgrade(upgrade.id) }, colors = ButtonDefaults.buttonColors(containerColor = Error), modifier = Modifier.weight(1f)) {
                                Text("Reject")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EvolutionHistoryTab(historyList: List<com.example.evolution.EvolutionHistoryEntity>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(historyList) { history ->
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceGlass),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(history.projectName, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = TextPrimary)
                        Text(history.date, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Capability: ${history.capability}", style = MaterialTheme.typography.bodySmall, color = PrimaryLight)
                    Text("Action: ${history.action} | Status: ${history.status}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
        }
    }
}

@Composable
fun EvolutionSettingsTab(settings: com.example.evolution.EvolutionSettings, viewModel: EvolutionViewModel) {
    var automaticInstallation by remember { mutableStateOf(settings.automaticInstallation) }
    var humanApproval by remember { mutableStateOf(settings.humanApprovalForMajorChanges) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceGlass),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Evolution Control Center", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = TextPrimary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Automatic Safe Installation", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        Switch(checked = automaticInstallation, onCheckedChange = { automaticInstallation = it })
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Human Approval for Major Changes", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        Switch(checked = humanApproval, onCheckedChange = { humanApproval = it })
                    }
                }
            }
        }
    }
}
