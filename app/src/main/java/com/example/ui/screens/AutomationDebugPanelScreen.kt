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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AutomationDebugPanelScreen(navController: NavController, viewModel: KavyaViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val taskState by TaskStateMachine.state.collectAsState()
    val eventLogs by AutomationEventLogger.logs.collectAsState()

    val liveState by DiagnosticEngine.liveState.collectAsState()
    val currentTask by DiagnosticEngine.currentTaskDescription.collectAsState()
    val currentTarget by DiagnosticEngine.currentTargetApp.collectAsState()
    val lastVerification by DiagnosticEngine.lastVerificationResult.collectAsState()
    val debugLogs by DiagnosticEngine.automationDebugLogs.collectAsState()
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
            // 1. Live Task State Machine (Requirement 33)
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Live Task State Machine",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            StatusBadge(
                                text = taskState.phase.name,
                                isActive = taskState.phase != TaskPhase.IDLE && taskState.phase != TaskPhase.SLEEP,
                                activeColor = when (taskState.phase) {
                                    TaskPhase.COMPLETED -> SuccessGreen
                                    TaskPhase.FAILED, TaskPhase.INTERRUPTED -> Error
                                    TaskPhase.SLEEP -> TextTertiary
                                    else -> PrimaryLight
                                }
                            )
                        }

                        HorizontalDivider(color = OutlineVariant)

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Active App:", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            Text(taskState.currentApp, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = PrimaryLight)
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Foreground Package:", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            Text(currentForegroundPkg, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = TextPrimary)
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Current Step:", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            Text(taskState.currentStep, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = TextPrimary)
                        }

                        if (taskState.detectedTarget != "None") {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Detected Target:", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                Text(taskState.detectedTarget, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = AccentCyan)
                            }
                        }

                        if (taskState.targetBounds != null) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Target Bounds:", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                Text(taskState.targetBounds.toString(), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp), color = TextTertiary)
                            }
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Last Action / Result:", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            Text(
                                if (taskState.actionResult != "None") taskState.actionResult else taskState.lastAction,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = if (taskState.actionResult.contains("verified") || taskState.actionResult.contains("PASS")) SuccessGreen else TextPrimary
                            )
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
                    }
                }
            }

            // 3. Structured Automation Event Log (Requirement 34)
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Automation Event Log",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            if (eventLogs.isNotEmpty()) {
                                TextButton(onClick = { AutomationEventLogger.clear() }) {
                                    Text("Clear", color = TextTertiary, fontSize = 12.sp)
                                }
                            }
                        }

                        if (eventLogs.isEmpty()) {
                            Text(
                                "No automation events logged yet. Speak or type a command to observe live traces.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                eventLogs.take(15).forEach { log ->
                                    val tagColor = when (log.tag) {
                                        EventTag.VOICE -> AccentCyan
                                        EventTag.PARSED -> PrimaryLight
                                        EventTag.APP -> AccentPurpleLight
                                        EventTag.SCREEN -> Color(0xFFFFB74D)
                                        EventTag.ACTION -> Color(0xFF64B5F6)
                                        EventTag.VERIFY -> SuccessGreen
                                        EventTag.TASK -> Color.White
                                        EventTag.ERROR -> Error
                                    }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(SurfaceVariant.copy(alpha = 0.5f))
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                            Text(
                                                "[${log.tag.name}]",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = tagColor
                                                ),
                                                modifier = Modifier.width(68.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                log.message,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    color = TextPrimary
                                                ),
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. System Self-Test
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
                                Text("Health audit of core subsystems", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = TextSecondary)
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

            // 5. Interactive Deterministic App Resolver Tool
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

            // 6. Re-index Button
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
