package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.agent.*
import com.example.data.AppDatabase
import com.example.data.DurableTaskEntity
import com.example.kucp.KucpGateway
import com.example.kucp.android.AndroidNativeIntegrationProvider
import com.example.kucp.model.ConnectorImplementationState
import com.example.kucp.model.ConnectorStatus
import com.example.memory.okf.OkfMemoryRepository
import com.example.scheduler.SchedulerEngine
import com.example.ui.theme.*
import com.example.viewmodel.KavyaViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val AGENTS_TABS = listOf(
    "Overview",
    "Live Agents",
    "Tasks & Plans",
    "Integrations",
    "Schedules",
    "Browser Sessions",
    "Memory",
    "Approvals",
    "History",
    "Resources"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsWorkspaceScreen(
    navController: NavController,
    viewModel: KavyaViewModel? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val orchestrator = remember { UnifiedAgentOrchestrator.getInstance(context) }
    val gateway = remember { KucpGateway.getInstance(context) }
    val toolGateway = remember { UniversalToolGateway.getInstance(context) }
    val browserManager = remember { AgentResearchAndBrowserManager.getInstance(context) }
    val okfMemory = remember { OkfMemoryRepository.getInstance(context) }
    val scheduler = remember { SchedulerEngine(context) }
    val taskDao = remember { AppDatabase.getDatabase(context).taskDao() }

    val liveAgents by orchestrator.liveTempAgents.collectAsStateWithLifecycle()
    val activeRuns by orchestrator.activeRuns.collectAsStateWithLifecycle()
    val recentRuns by orchestrator.recentRuns.collectAsStateWithLifecycle()
    val durableTasks by taskDao.getAllTasksFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    val schedules by scheduler.allSchedulesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val connectors by gateway.connectors.collectAsStateWithLifecycle()
    val catalogReport by gateway.catalogReport.collectAsStateWithLifecycle()
    val workflows by gateway.workflows.collectAsStateWithLifecycle()
    val auditEvents by gateway.auditEvents.collectAsStateWithLifecycle()
    val pendingApprovals by toolGateway.pendingApprovals.collectAsStateWithLifecycle()
    val executionHistory by toolGateway.executionHistory.collectAsStateWithLifecycle()
    val totalExternalCalls by toolGateway.totalExternalCalls.collectAsStateWithLifecycle()
    val browserSessions by browserManager.sessions.collectAsStateWithLifecycle()
    val memoryUnits by okfMemory.memoriesFlow.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }
    var promptInput by remember { mutableStateOf("") }
    var isLaunchingTask by remember { mutableStateOf(false) }

    val connectedExternalCount = remember(connectors) {
        connectors.count {
            it.enabled &&
                it.id != AndroidNativeIntegrationProvider.CONNECTOR_ID &&
                (it.status == ConnectorStatus.CONNECTED || it.status == ConnectorStatus.PARTIALLY_AUTHORIZED)
        }
    }

    Scaffold(
        containerColor = Background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Agents Workspace", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = TextPrimary)
                        Text(
                            text = "${liveAgents.size} live agents • $connectedExternalCount connected • ${pendingApprovals.size} approvals",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier.testTag("agents_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { navController.navigate("kucp_dashboard") },
                        modifier = Modifier.testTag("agents_open_integrations_hub_button")
                    ) {
                        Icon(Icons.Default.Hub, contentDescription = "Open Integrations Hub", tint = PrimaryLight)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Background, BackgroundGradientEnd)))
                .padding(padding)
        ) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(AGENTS_TABS) { index, label ->
                    val badgeCount = when (index) {
                        1 -> liveAgents.size
                        7 -> pendingApprovals.size
                        else -> 0
                    }
                    val displayLabel = if (badgeCount > 0) "$label ($badgeCount)" else label
                    FilterChip(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        label = { Text(displayLabel, fontSize = 12.sp) },
                        modifier = Modifier.testTag("agents_tab_${label.lowercase(Locale.ROOT).replace(" ", "_").replace("&", "and")}"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PrimaryContainer,
                            selectedLabelColor = PrimaryLight,
                            containerColor = SurfaceGlass,
                            labelColor = TextSecondary
                        )
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 720.dp)
                    .align(Alignment.CenterHorizontally)
            ) {
                when (selectedTab) {
                    0 -> OverviewTab(
                        liveAgentsCount = liveAgents.size,
                        activeRunsCount = activeRuns.size,
                        durableTasksCount = durableTasks.size,
                        connectedExternalCount = connectedExternalCount,
                        canonicalCatalogCount = catalogReport.uniqueCanonicalCount,
                        pendingApprovalsCount = pendingApprovals.size,
                        schedulesCount = schedules.count { it.isActive },
                        memoryCount = memoryUnits.size,
                        promptInput = promptInput,
                        onPromptChange = { promptInput = it },
                        isLaunchingTask = isLaunchingTask,
                        recentRuns = recentRuns,
                        onLaunchTask = {
                            val clean = promptInput.trim()
                            if (clean.isNotEmpty() && !isLaunchingTask) {
                                isLaunchingTask = true
                                promptInput = ""
                                scope.launch {
                                    try {
                                        val run = orchestrator.orchestrateFromChat(prompt = clean)
                                        snackbarHostState.showSnackbar("Finished (${run.route.label}): ${run.status.name}")
                                    } finally {
                                        isLaunchingTask = false
                                    }
                                }
                            }
                        },
                        onJumpToTab = { selectedTab = it }
                    )
                    1 -> LiveAgentsTab(
                        liveAgents = liveAgents,
                        activeRuns = activeRuns,
                        onCancelTask = { taskId ->
                            orchestrator.cancelTask(taskId)
                            scope.launch { snackbarHostState.showSnackbar("Cancelled task $taskId") }
                        }
                    )
                    2 -> TasksAndPlansTab(
                        durableTasks = durableTasks,
                        recentRuns = recentRuns,
                        onResumeTask = { taskId ->
                            scope.launch {
                                val res = orchestrator.resumeTask(taskId)
                                snackbarHostState.showSnackbar("Resumed $taskId -> ${res.status.name}")
                            }
                        },
                        onCancelTask = { taskId ->
                            orchestrator.cancelTask(taskId)
                            scope.launch { snackbarHostState.showSnackbar("Cancelled $taskId") }
                        }
                    )
                    3 -> IntegrationsOverviewTab(
                        gateway = gateway,
                        connectors = connectors,
                        catalogReport = catalogReport,
                        onRefreshCatalog = {
                            scope.launch {
                                val rep = gateway.refreshCatalogMetadata()
                                snackbarHostState.showSnackbar("Catalog refreshed: ${rep.uniqueCanonicalCount} services, ${rep.preservedConnectedCount} connected preserved")
                            }
                        },
                        onOpenFullHub = { navController.navigate("kucp_dashboard") },
                        onSnackbar = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                    )
                    4 -> SchedulesWorkspaceTab(
                        scheduler = scheduler,
                        schedules = schedules,
                        workflows = workflows,
                        onSnackbar = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                    )
                    5 -> BrowserSessionsTab(
                        sessions = browserSessions,
                        onCancelSession = { browserManager.cancelSession(it) },
                        onClearFinished = { browserManager.clearFinishedSessions() }
                    )
                    6 -> MemoryWorkspaceTab(
                        okfMemory = okfMemory,
                        memoryUnits = memoryUnits,
                        onSnackbar = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                    )
                    7 -> ApprovalsTab(
                        pendingApprovals = pendingApprovals,
                        onApprove = { id ->
                            toolGateway.approve(id)
                            scope.launch { snackbarHostState.showSnackbar("Approved $id") }
                        },
                        onReject = { id ->
                            toolGateway.reject(id)
                            scope.launch { snackbarHostState.showSnackbar("Rejected $id") }
                        }
                    )
                    8 -> ExecutionHistoryTab(
                        history = executionHistory,
                        auditEvents = auditEvents
                    )
                    9 -> ResourceMonitorTab(
                        liveAgentCount = liveAgents.size,
                        maxConcurrency = orchestrator.maxConcurrencySlots,
                        totalExternalCalls = totalExternalCalls,
                        activeBrowserSessions = browserSessions.count { it.status == BrowserSessionStatus.ACTIVE }
                    )
                }
            }
        }
    }
}

@Composable
private fun OverviewTab(
    liveAgentsCount: Int,
    activeRunsCount: Int,
    durableTasksCount: Int,
    connectedExternalCount: Int,
    canonicalCatalogCount: Int,
    pendingApprovalsCount: Int,
    schedulesCount: Int,
    memoryCount: Int,
    promptInput: String,
    onPromptChange: (String) -> Unit,
    isLaunchingTask: Boolean,
    recentRuns: List<OrchestrationRunSummary>,
    onLaunchTask: () -> Unit,
    onJumpToTab: (Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            AgentsGlassCard {
                Text("Run Multi-Agent Task", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Launch parallel web research, connected-service workflows, or recurring monitors directly through Gemini orchestration.",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = promptInput,
                    onValueChange = onPromptChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("agents_run_task_input"),
                    placeholder = { Text("e.g., Compare Kotlin Multiplatform vs Flutter with citations", fontSize = 12.sp) },
                    singleLine = false,
                    maxLines = 3
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onLaunchTask,
                    enabled = promptInput.isNotBlank() && !isLaunchingTask,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("agents_run_task_button")
                ) {
                    if (isLaunchingTask) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = OnPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("Orchestrating...")
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Launch task", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Run via Unified Orchestrator")
                    }
                }
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("Live Agents", "$liveAgentsCount", PrimaryLight, Modifier.weight(1f)) { onJumpToTab(1) }
                MetricTile("Active Runs", "$activeRunsCount", AccentCyan, Modifier.weight(1f)) { onJumpToTab(2) }
                MetricTile("Approvals", "$pendingApprovalsCount", if (pendingApprovalsCount > 0) AccentYellow else SuccessGreen, Modifier.weight(1f)) { onJumpToTab(7) }
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("Connected Apps", "$connectedExternalCount / $canonicalCatalogCount", PrimaryLight, Modifier.weight(1f)) { onJumpToTab(3) }
                MetricTile("Schedules", "$schedulesCount", SuccessGreen, Modifier.weight(1f)) { onJumpToTab(4) }
                MetricTile("Memories", "$memoryCount", AccentPurpleLight, Modifier.weight(1f)) { onJumpToTab(6) }
            }
        }

        item {
            AgentsGlassCard {
                Text("Recent Orchestrations (${recentRuns.size}) • Persisted Tasks ($durableTasksCount)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                if (recentRuns.isEmpty()) {
                    Text(
                        "No orchestrated runs yet in this session. Complex prompts from Normal Chat or above will appear here with live progress and citations.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                } else {
                    recentRuns.take(5).forEach { run ->
                        HorizontalDivider(color = OutlineVariant, modifier = Modifier.padding(vertical = 6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(run.objective, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(6.dp))
                            AgentsStatusPill(run.status.name, if (run.status == GoalStatus.COMPLETED) SuccessGreen else AccentYellow)
                        }
                        Text(
                            "${run.route.label} • ${run.agentCount} temp agents • ${run.completedSteps}/${run.totalSteps} steps • ${run.sourcesCount} sources",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveAgentsTab(
    liveAgents: List<TemporaryAgentRecord>,
    activeRuns: List<OrchestrationRunSummary>,
    onCancelTask: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (liveAgents.isEmpty() && activeRuns.isEmpty()) {
            item {
                AgentsGlassCard {
                    Text("Zero Active Temporary Agents", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Kavya spawns temporary task-scoped agents dynamically when needed and releases them immediately after synthesis.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }
        } else {
            items(liveAgents, key = { "${it.taskId}_${it.agentId}" }) { agent ->
                AgentsGlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(agent.agentId, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = PrimaryLight, modifier = Modifier.weight(1f))
                        AgentsStatusPill(agent.status.name, if (agent.status == AgentStatus.RUNNING) AccentCyan else SuccessGreen)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(agent.roleObjective, fontSize = 13.sp, color = TextPrimary)
                    Text("Tools: ${agent.assignedTools.joinToString(", ")}", fontSize = 11.sp, color = TextSecondary)
                    if (agent.progressNote.isNotBlank()) {
                        Text(agent.progressNote, fontSize = 11.sp, color = TextTertiary)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onCancelTask(agent.taskId) },
                        modifier = Modifier.testTag("cancel_agent_${agent.agentId}")
                    ) {
                        Text("Cancel Task", fontSize = 12.sp, color = Error)
                    }
                }
            }
        }
    }
}

@Composable
private fun TasksAndPlansTab(
    durableTasks: List<DurableTaskEntity>,
    recentRuns: List<OrchestrationRunSummary>,
    onResumeTask: (String) -> Unit,
    onCancelTask: (String) -> Unit
) {
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (durableTasks.isEmpty()) {
            item {
                AgentsGlassCard {
                    Text("No Durable Tasks Persisted Yet", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
                    Text("Multi-step agent plans and checkpoints stored in Room appear here for recovery.", fontSize = 12.sp, color = TextSecondary)
                }
            }
        } else {
            items(durableTasks, key = { it.id }) { task ->
                val isCompleted = task.status == "COMPLETED"
                val isResumable = task.status in listOf("PENDING", "EXECUTING", "RUNNING", "PAUSED", "FAILED", "WAITING_APPROVAL", "CHECKPOINTED")
                AgentsGlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(task.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        AgentsStatusPill(task.status, if (isCompleted) SuccessGreen else AccentYellow)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${task.intentCategory} • Step ${task.currentStepIndex}/${task.totalSteps} • Updated ${fmt.format(Date(task.updatedAt))}",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                    if (!task.resultSummary.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(task.resultSummary.take(220), fontSize = 11.sp, color = TextTertiary)
                    }
                    if (isResumable) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onResumeTask(task.id) },
                                modifier = Modifier.testTag("resume_task_${task.id}")
                            ) { Text("Resume", fontSize = 12.sp) }
                            OutlinedButton(
                                onClick = { onCancelTask(task.id) },
                                modifier = Modifier.testTag("cancel_task_${task.id}")
                            ) { Text("Cancel", fontSize = 12.sp, color = Error) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IntegrationsOverviewTab(
    gateway: KucpGateway,
    connectors: List<com.example.kucp.model.ConnectorDefinition>,
    catalogReport: com.example.kucp.model.CatalogRefreshReport,
    onRefreshCatalog: () -> Unit,
    onOpenFullHub: () -> Unit,
    onSnackbar: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val templates = remember { gateway.getMarketplaceTemplates() }
    var search by remember { mutableStateOf("") }
    val installedByCanonical = remember(connectors) {
        connectors.associateBy { it.canonicalServiceId.ifBlank { it.id } }
    }
    val filtered = remember(search, templates) {
        if (search.isBlank()) templates
        else templates.filter {
            it.name.contains(search, ignoreCase = true) ||
                it.provider.contains(search, ignoreCase = true) ||
                it.category.label.contains(search, ignoreCase = true)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            AgentsGlassCard {
                Text("Unified Connector Catalog (Claude • ChatGPT • Gemini)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${catalogReport.uniqueCanonicalCount} unique services • Claude: ${catalogReport.claudeSourceCount} • ChatGPT: ${catalogReport.chatGptSourceCount} • Gemini: ${catalogReport.geminiSourceCount} • Blocked/No Public API: ${catalogReport.blockedCount}",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onRefreshCatalog,
                        modifier = Modifier.testTag("agents_refresh_catalog_button")
                    ) { Text("Refresh Catalog", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = onOpenFullHub,
                        modifier = Modifier.testTag("agents_open_kucp_studio_button")
                    ) { Text("Open Auth & OAuth Hub", fontSize = 12.sp) }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth().testTag("agents_integrations_search"),
                    placeholder = { Text("Search 48 canonical services...", fontSize = 12.sp) },
                    singleLine = true
                )
            }
        }

        items(filtered, key = { it.id }) { tpl ->
            val installed = installedByCanonical[tpl.canonicalServiceId]
            val statusText = installed?.status?.label ?: when (tpl.implementationState) {
                ConnectorImplementationState.IMPLEMENTED -> "Available (Ready)"
                ConnectorImplementationState.BLOCKED_NO_PUBLIC_API -> "Unsupported (No Public API)"
                else -> "Setup Required"
            }
            val statusColor = when {
                installed?.status == ConnectorStatus.CONNECTED -> SuccessGreen
                tpl.implementationState == ConnectorImplementationState.BLOCKED_NO_PUBLIC_API -> Error
                tpl.implementationState == ConnectorImplementationState.IMPLEMENTED -> PrimaryLight
                else -> AccentYellow
            }
            AgentsGlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tpl.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
                        Text("${tpl.category.label} • ${tpl.protocol.name} • ${tpl.defaultAuthScheme.displayName}", fontSize = 11.sp, color = TextSecondary)
                    }
                    AgentsStatusPill(statusText, statusColor)
                }
                Spacer(Modifier.height(4.dp))
                Text(tpl.description, fontSize = 12.sp, color = TextSecondary)
                if (tpl.ecosystemSources.isNotEmpty()) {
                    Text(
                        "Ecosystems: ${tpl.ecosystemSources.joinToString(" • ") { it.label }}",
                        fontSize = 10.sp,
                        color = PrimaryLight
                    )
                }
                if (tpl.restrictions.isNotBlank()) {
                    Text("Restriction: ${tpl.restrictions}", fontSize = 11.sp, color = Error)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (installed == null && tpl.implementationState != ConnectorImplementationState.BLOCKED_NO_PUBLIC_API) {
                        Button(
                            onClick = {
                                scope.launch {
                                    gateway.installMarketplaceTemplate(tpl)
                                    onSnackbar("Installed ${tpl.name} (${statusText})")
                                }
                            },
                            modifier = Modifier.testTag("install_connector_${tpl.canonicalServiceId}")
                        ) { Text("Install", fontSize = 12.sp) }
                    } else if (installed != null) {
                        if (installed.status == ConnectorStatus.CONNECTED || installed.status == ConnectorStatus.PARTIALLY_AUTHORIZED) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        gateway.disconnectAndRevokeAccount(installed.id)
                                        onSnackbar("Disconnected & revoked ${installed.name}")
                                    }
                                },
                                modifier = Modifier.testTag("revoke_connector_${tpl.canonicalServiceId}")
                            ) { Text("Disconnect & Revoke", fontSize = 12.sp, color = Error) }
                        } else {
                            Button(
                                onClick = onOpenFullHub,
                                modifier = Modifier.testTag("configure_connector_${tpl.canonicalServiceId}")
                            ) { Text("Configure Auth", fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalsTab(
    pendingApprovals: List<PendingToolApproval>,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (pendingApprovals.isEmpty()) {
            item {
                AgentsGlassCard {
                    Text("No Pending Tool Approvals", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
                    Text(
                        "External writes (emails, messages, calendar edits, deletes, purchases) require explicit confirmation here before executing.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }
        } else {
            items(pendingApprovals, key = { it.approvalId }) { item ->
                AgentsGlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.operationSummary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                        AgentsStatusPill(item.riskLabel, AccentYellow)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Agent: ${item.agentId} • Task: ${item.taskId}", fontSize = 11.sp, color = TextSecondary)
                    if (item.argumentsSummary.isNotBlank()) {
                        Text("Args: ${item.argumentsSummary}", fontSize = 11.sp, color = TextTertiary)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onApprove(item.approvalId) },
                            modifier = Modifier.testTag("approve_tool_${item.approvalId}")
                        ) { Text("Approve") }
                        OutlinedButton(
                            onClick = { onReject(item.approvalId) },
                            modifier = Modifier.testTag("reject_tool_${item.approvalId}")
                        ) { Text("Reject", color = Error) }
                    }
                }
            }
        }
    }
}
