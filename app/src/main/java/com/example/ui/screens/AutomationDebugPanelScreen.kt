package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.agent.*
import com.example.services.KavyaAccessibilityService
import com.example.ui.components.GlassCard
import com.example.ui.components.StatusBadge
import com.example.ui.theme.*
import com.example.utils.AppResolver
import com.example.viewmodel.KavyaViewModel
import kotlinx.coroutines.launch

@Composable
fun AutomationDebugPanelScreen(navController: NavController, viewModel: KavyaViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val liveState by DiagnosticEngine.liveState.collectAsState()
    val currentTask by DiagnosticEngine.currentTaskDescription.collectAsState()
    val currentTarget by DiagnosticEngine.currentTargetApp.collectAsState()
    val lastVerification by DiagnosticEngine.lastVerificationResult.collectAsState()
    val debugLogs by DiagnosticEngine.automationDebugLogs.collectAsState()
    val agentDiagnostics by DiagnosticEngine.recentDiagnostics.collectAsState()
    val selfTestReport by DiagnosticEngine.latestSelfTestReport.collectAsState()
    val isSelfTestRunning by SelfTestEngine.isRunning.collectAsState()

    var testQuery by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<String?>(null) }
    val isAccessibilityConnected = KavyaAccessibilityService.instance != null
    val currentForegroundPkg = viewModel.screenInspector.getCurrentForegroundPackage()

    SimpleScreen("Automation Diagnostics", navController) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Live System Status Overview
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Live Agent State",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            val stateColor = when (liveState) {
                                AgentLiveState.IDLE -> TextTertiary
                                AgentLiveState.PLANNING -> AccentCyan
                                AgentLiveState.EXECUTING -> PrimaryLight
                                AgentLiveState.VERIFYING -> AccentPurpleLight
                                AgentLiveState.COMPLETED -> SuccessGreen
                                AgentLiveState.FAILED -> Error
                            }
                            StatusBadge(text = liveState.name, isActive = liveState != AgentLiveState.IDLE, activeColor = stateColor)
                        }

                        HorizontalDivider(color = OutlineVariant)

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Current Task:", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            Text(currentTask ?: "None", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = TextPrimary)
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Target App:", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            Text(currentTarget ?: "None", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = PrimaryLight)
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Accessibility Service:", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            StatusBadge(
                                text = if (isAccessibilityConnected) "BOUND" else "DISCONNECTED",
                                isActive = isAccessibilityConnected,
                                activeColor = SuccessGreen,
                                inactiveColor = Error
                            )
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Foreground Package:", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            Text(currentForegroundPkg, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = TextPrimary)
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Last Verification:", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            Text(lastVerification ?: "None", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = if (lastVerification?.startsWith("PASS") == true) SuccessGreen else TextTertiary)
                        }
                    }
                }
            }

            // 2. Self-Test System Trigger & Results
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("System Self-Test", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = TextPrimary)
                                Text("Automated health audit of all 7 core subsystems", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = TextSecondary)
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        SelfTestEngine.runAllTests(
                                            context = context,
                                            kavyaAI = com.example.ai.KavyaAI(context),
                                            appResolver = viewModel.commandRouter.appResolver,
                                            memoryEngine = viewModel.memoryEngine,
                                            screenInspector = viewModel.screenInspector
                                        )
                                    }
                                },
                                enabled = !isSelfTestRunning,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                if (isSelfTestRunning) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Testing...", fontSize = 12.sp, color = Color.White)
                                } else {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Run Audit", fontSize = 12.sp, color = Color.White)
                                }
                            }
                        }

                        if (selfTestReport != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            val report = selfTestReport!!
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                report.components.forEach { comp ->
                                    val (badgeText, badgeColor) = when (comp.status) {
                                        HealthStatus.PASS -> "PASS" to SuccessGreen
                                        HealthStatus.WARN -> "WARN" to Color(0xFFFFB74D)
                                        HealthStatus.FAIL -> "FAIL" to Error
                                        HealthStatus.RUNNING -> "RUNNING" to AccentCyan
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(SurfaceVariant.copy(alpha = 0.5f))
                                            .padding(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(comp.name, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = TextPrimary)
                                                Text(comp.details, style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp), color = TextSecondary)
                                            }
                                            StatusBadge(text = badgeText, isActive = comp.status == HealthStatus.PASS, activeColor = badgeColor)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Step-by-Step Automation Trace Logs
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Recent Automation Logs", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = TextPrimary)
                            if (debugLogs.isNotEmpty()) {
                                TextButton(onClick = { DiagnosticEngine.clearDiagnostics() }) {
                                    Text("Clear", color = TextTertiary, fontSize = 12.sp)
                                }
                            }
                        }

                        if (debugLogs.isEmpty()) {
                            Text("No automation actions recorded yet.", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                debugLogs.take(10).forEach { log ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Surface.copy(alpha = 0.6f))
                                            .padding(10.dp)
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(log.action.ifBlank { log.command }, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = TextPrimary)
                                                Text(log.verification.ifBlank { log.apiResponseStatus }, style = MaterialTheme.typography.labelSmall.copy(color = if (log.verification.contains("PASS") || log.apiResponseStatus.contains("200")) SuccessGreen else Error))
                                            }
                                            Text("Prompt: ${log.command.take(60)}", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = TextSecondary)
                                            Text("Type: ${log.classifiedTaskType} | Provider: ${log.selectedProvider} (${log.selectedModel}) | Latency: ${log.requestLatencyMs}ms", style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp), color = AccentCyan)
                                            if (log.target.isNotBlank()) {
                                                Text("Target: ${log.target} | App: ${log.resolvedApp}", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = TextSecondary)
                                            }
                                            Text("Result: ${log.result}", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = TextSecondary)
                                            if (log.error != null) {
                                                Text("Error: ${log.error}", style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp), color = Error)
                                            }
                                            if (log.detectedElements.isNotEmpty()) {
                                                Text("Visible Nodes: ${log.detectedElements.take(3).joinToString(", ")}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp), color = TextTertiary)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. Interactive Deterministic App Resolver Tool
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Deterministic App Resolver Tester", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = TextPrimary)
                        OutlinedTextField(
                            value = testQuery,
                            onValueChange = {
                                testQuery = it
                                if (it.isNotBlank()) {
                                    val res = viewModel.commandRouter.appResolver.resolve(it)
                                    testResult = "• Extracted Name: ${viewModel.commandRouter.appResolver.extractAppNameFromNaturalLanguage(it)}\n• Matched App: ${res.matchedApp?.appName ?: "None"}\n• Package: ${res.matchedApp?.packageName ?: "None"}\n• Confidence: ${res.confidence}\n• Reason: ${res.reason}"
                                } else {
                                    testResult = null
                                }
                            },
                            label = { Text("Test Command (e.g. YT kholo, Open YouTube)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (testResult != null) {
                            Text(testResult!!, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp), color = TextSecondary)
                        }
                    }
                }
            }

            // 5. Re-index Button
            item {
                val count = viewModel.commandRouter.appResolver.getInstalledApps().size
                Button(
                    onClick = { viewModel.commandRouter.appResolver.refreshIndex() },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Re-index Installed Apps ($count apps)", color = Color.White)
                }
            }
        }
    }
}

