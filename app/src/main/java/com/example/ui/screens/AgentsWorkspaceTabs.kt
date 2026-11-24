package com.example.ui.screens

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agent.*
import com.example.ai.OfflinePrivacyGuard
import com.example.data.ScheduledTaskEntity
import com.example.kucp.model.AuditEvent
import com.example.kucp.model.WorkflowDefinition
import com.example.memory.okf.OkfCategory
import com.example.memory.okf.OkfKnowledgeUnit
import com.example.memory.okf.OkfMemoryRepository
import com.example.scheduler.SchedulerEngine
import com.example.ui.theme.*
import com.example.utils.NetworkConnectivityMonitor
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun AgentsGlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = SurfaceGlass,
        border = BorderStroke(1.dp, GlassBorder)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            content = content
        )
    }
}

@Composable
internal fun AgentsStatusPill(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f))
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
internal fun MetricTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .heightIn(min = 68.dp)
            .clickable(onClick = onClick)
            .testTag("agents_metric_${label.lowercase(Locale.ROOT).replace(" ", "_")}"),
        shape = RoundedCornerShape(16.dp),
        color = SurfaceGlass,
        border = BorderStroke(1.dp, GlassBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(label, fontSize = 11.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun SchedulesWorkspaceTab(
    scheduler: SchedulerEngine,
    schedules: List<ScheduledTaskEntity>,
    workflows: List<WorkflowDefinition>,
    onSnackbar: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf("DAILY") }
    val scheduleTypes = listOf("DAILY", "WEEKLY", "MONTHLY", "INTERVAL_60M", "MONITOR_POLL_60M")

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            AgentsGlassCard {
                Text("Create Recurring Agent Schedule / Monitor", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth().testTag("schedule_title_input"),
                    placeholder = { Text("Schedule title (e.g., AI Releases Digest)", fontSize = 12.sp) },
                    singleLine = true
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth().testTag("schedule_prompt_input"),
                    placeholder = { Text("Agent objective / monitoring prompt...", fontSize = 12.sp) },
                    maxLines = 2
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    scheduleTypes.take(3).forEach { st ->
                        FilterChip(
                            selected = selectedType == st,
                            onClick = { selectedType = st },
                            label = { Text(st, fontSize = 11.sp) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    scheduleTypes.drop(3).forEach { st ->
                        FilterChip(
                            selected = selectedType == st,
                            onClick = { selectedType = st },
                            label = { Text(st, fontSize = 11.sp) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (title.isNotBlank()) {
                            val t = title.trim()
                            val p = prompt.trim().ifBlank { t }
                            title = ""
                            prompt = ""
                            scope.launch {
                                scheduler.createAdvancedSchedule(t, p, selectedType)
                                onSnackbar("Scheduled '$t' ($selectedType)")
                            }
                        }
                    },
                    enabled = title.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("create_schedule_button")
                ) { Text("Save Schedule") }
            }
        }

        item {
            Text("Scheduled Jobs (${schedules.size}) • Trigger Workflows (${workflows.size})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextSecondary)
        }

        items(schedules, key = { it.id }) { item ->
            AgentsGlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
                        Text(
                            "${item.scheduleType} • Next: ${if (item.nextRunTimestamp > 0) fmt.format(Date(item.nextRunTimestamp)) else "None"}",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = item.isActive,
                        onCheckedChange = { checked ->
                            scope.launch { scheduler.toggleActive(item.id, checked) }
                        },
                        modifier = Modifier.testTag("toggle_schedule_${item.id}")
                    )
                }
                if (item.promptOrAction.isNotBlank()) {
                    Text(item.promptOrAction, fontSize = 12.sp, color = TextTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                val run = scheduler.runScheduleNow(item.id)
                                onSnackbar(if (run != null) "Ran '${item.title}' -> ${run.status.name}" else "Schedule not found")
                            }
                        },
                        modifier = Modifier.testTag("run_schedule_${item.id}")
                    ) { Text("Run Now", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                scheduler.deleteSchedule(item.id)
                                onSnackbar("Deleted '${item.title}'")
                            }
                        },
                        modifier = Modifier.testTag("delete_schedule_${item.id}")
                    ) { Text("Delete", fontSize = 12.sp, color = Error) }
                }
            }
        }
    }
}

@Composable
internal fun BrowserSessionsTab(
    sessions: List<BrowserResearchSession>,
    onCancelSession: (String) -> Unit,
    onClearFinished: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Shared Research & Browser Sessions (${sessions.size})", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
                if (sessions.any { it.status != BrowserSessionStatus.ACTIVE }) {
                    TextButton(
                        onClick = onClearFinished,
                        modifier = Modifier.testTag("clear_browser_sessions_button")
                    ) { Text("Clear Finished", fontSize = 12.sp) }
                }
            }
        }
        if (sessions.isEmpty()) {
            item {
                AgentsGlassCard {
                    Text("No Web Research Sessions Recorded", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = TextPrimary)
                    Text("Task-scoped Wikipedia, arXiv, DuckDuckGo, and HTTPS page fetches appear here with visited URLs and citation counts.", fontSize = 12.sp, color = TextSecondary)
                }
            }
        } else {
            items(sessions, key = { it.sessionId }) { session ->
                AgentsGlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(session.agentId, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = PrimaryLight, modifier = Modifier.weight(1f))
                        AgentsStatusPill(
                            session.status.label,
                            when (session.status) {
                                BrowserSessionStatus.ACTIVE -> AccentCyan
                                BrowserSessionStatus.COMPLETED -> SuccessGreen
                                BrowserSessionStatus.BLOCKED_OFFLINE -> AccentYellow
                                else -> Error
                            }
                        )
                    }
                    Text(session.objective, fontSize = 13.sp, color = TextPrimary)
                    Text("URLs visited: ${session.urlsVisited.size} • Citations collected: ${session.evidenceCount}", fontSize = 11.sp, color = TextSecondary)
                    if (session.lastNote.isNotBlank()) {
                        Text(session.lastNote, fontSize = 11.sp, color = TextTertiary)
                    }
                    if (session.status == BrowserSessionStatus.ACTIVE) {
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = { onCancelSession(session.sessionId) }) {
                            Text("Cancel Session", fontSize = 12.sp, color = Error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MemoryWorkspaceTab(
    okfMemory: OkfMemoryRepository,
    memoryUnits: List<OkfKnowledgeUnit>,
    onSnackbar: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<OkfCategory?>(null) }

    val filtered = remember(memoryUnits, query, selectedCategory) {
        memoryUnits.filter { unit ->
            (selectedCategory == null || unit.category == selectedCategory) &&
                (query.isBlank() || unit.key.contains(query, ignoreCase = true) || unit.content.contains(query, ignoreCase = true))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            AgentsGlassCard {
                Text("Long-Term Agent Memory (OKF + BM25)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().testTag("agents_memory_search_input"),
                    placeholder = { Text("Search stored knowledge, preferences, research...", fontSize = 12.sp) },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = selectedCategory == null, onClick = { selectedCategory = null }, label = { Text("All (${memoryUnits.size})", fontSize = 11.sp) })
                    FilterChip(selected = selectedCategory == OkfCategory.PREFERENCE, onClick = { selectedCategory = OkfCategory.PREFERENCE }, label = { Text("Preferences", fontSize = 11.sp) })
                    FilterChip(selected = selectedCategory == OkfCategory.FACT, onClick = { selectedCategory = OkfCategory.FACT }, label = { Text("Research/Facts", fontSize = 11.sp) })
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                AgentsGlassCard {
                    Text("No matching memories stored.", fontSize = 12.sp, color = TextSecondary)
                }
            }
        } else {
            items(filtered.take(40), key = { it.id }) { unit ->
                AgentsGlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(unit.key, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                        AgentsStatusPill(unit.category.name, PrimaryLight)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(unit.content.take(260), fontSize = 12.sp, color = TextSecondary)
                    Text("Provenance: ${unit.provenance.source} • Author: ${unit.provenance.author}", fontSize = 10.sp, color = TextTertiary)
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = {
                            scope.launch {
                                okfMemory.deleteKnowledge(unit.id, permanent = true)
                                onSnackbar("Deleted memory '${unit.key}'")
                            }
                        },
                        modifier = Modifier.testTag("delete_memory_${unit.id}")
                    ) { Text("Delete", fontSize = 12.sp, color = Error) }
                }
            }
        }
    }
}

@Composable
internal fun ExecutionHistoryTab(
    history: List<ToolExecutionHistoryEntry>,
    auditEvents: List<AuditEvent>
) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Universal Tool Executions (${history.size})", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
        }
        if (history.isEmpty()) {
            item {
                AgentsGlassCard {
                    Text("No tool invocations logged in this session yet.", fontSize = 12.sp, color = TextSecondary)
                }
            }
        } else {
            items(history.take(40), key = { it.id }) { entry ->
                AgentsGlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${entry.toolId} (${entry.providerId})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                        AgentsStatusPill(
                            entry.status.label,
                            if (entry.status == GatewayOperationStatus.COMPLETED) SuccessGreen else AccentYellow
                        )
                    }
                    Text("${entry.agentId} • ${entry.durationMs}ms • ${fmt.format(Date(entry.timestampMs))}", fontSize = 11.sp, color = TextSecondary)
                    if (entry.outputExcerpt.isNotBlank()) {
                        Text(entry.outputExcerpt, fontSize = 11.sp, color = TextTertiary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    if (!entry.errorMessage.isNullOrBlank()) {
                        Text(entry.errorMessage, fontSize = 11.sp, color = Error)
                    }
                }
            }
        }

        if (auditEvents.isNotEmpty()) {
            item {
                Text("Connector & Security Audit Log (${auditEvents.size})", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
            }
            items(auditEvents.take(20), key = { it.id }) { ev ->
                AgentsGlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ev.action, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                        AgentsStatusPill(ev.outcome, if (ev.outcome == "SUCCESS") SuccessGreen else AccentYellow)
                    }
                    Text("${ev.connectorId} • ${ev.detailRedacted}", fontSize = 11.sp, color = TextSecondary)
                }
            }
        }
    }
}

@Composable
internal fun ResourceMonitorTab(
    liveAgentCount: Int,
    maxConcurrency: Int,
    totalExternalCalls: Int,
    activeBrowserSessions: Int
) {
    val context = LocalContext.current
    val runtime = Runtime.getRuntime()
    val heapUsedMb = ((runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)).coerceAtLeast(1L)
    val heapMaxMb = (runtime.maxMemory() / (1024L * 1024L)).coerceAtLeast(1L)
    val cpuCores = runtime.availableProcessors()

    val memInfo = remember(context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
    }
    val sysAvailMb = memInfo.availMem / (1024L * 1024L)
    val sysTotalMb = memInfo.totalMem / (1024L * 1024L)
    val strictOffline = OfflinePrivacyGuard.isStrictOfflineMode(context)
    val onlineValidated = remember(context) { NetworkConnectivityMonitor.isValidatedOnline(context) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            AgentsGlassCard {
                Text("Live Device & Agent Resource Telemetry", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPrimary)
                Spacer(Modifier.height(6.dp))
                Text("All readings below come directly from Android Runtime, ActivityManager, and active orchestrator state.", fontSize = 11.sp, color = TextTertiary)
                Spacer(Modifier.height(10.dp))
                ResourceRow("JVM Heap Usage", "$heapUsedMb MB / $heapMaxMb MB")
                ResourceRow("Device RAM Available", "$sysAvailMb MB / $sysTotalMb MB (lowMemory=${memInfo.lowMemory})")
                ResourceRow("CPU Cores Detected", "$cpuCores cores")
                ResourceRow("Parallel Agent Slots", "$liveAgentCount active / $maxConcurrency bounded max")
                ResourceRow("Active Browser Research Sessions", "$activeBrowserSessions")
                ResourceRow("External Gateway Calls (Session)", "$totalExternalCalls")
                ResourceRow("Strict Offline Mode", if (strictOffline) "ENABLED (Cloud blocked)" else "DISABLED")
                ResourceRow("Network Connectivity", if (onlineValidated) "Validated Online" else "Offline / Unvalidated")
            }
        }
    }
}

@Composable
private fun ResourceRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, color = TextSecondary)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
    }
}
