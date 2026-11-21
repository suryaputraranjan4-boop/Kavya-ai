package com.example.ui.screens.kucp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.kucp.KucpGateway
import com.example.kucp.model.ConnectorStatus
import com.example.ui.theme.*
import com.example.viewmodel.KavyaViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KucpDashboardScreen(
    navController: NavController,
    viewModel: KavyaViewModel? = null
) {
    val context = LocalContext.current
    val gateway = remember { KucpGateway.getInstance(context) }

    val connectors by gateway.connectors.collectAsStateWithLifecycle()
    val tools by gateway.tools.collectAsStateWithLifecycle()
    val healthMap by gateway.health.collectAsStateWithLifecycle()
    val plugins by gateway.plugins.collectAsStateWithLifecycle()
    val webhooks by gateway.webhooks.collectAsStateWithLifecycle()
    val webhookEvents by gateway.webhookEvents.collectAsStateWithLifecycle()
    val workflows by gateway.workflows.collectAsStateWithLifecycle()
    val workflowRuns by gateway.workflowRuns.collectAsStateWithLifecycle()
    val auditEvents by gateway.auditEvents.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }

    val showFeedback: (String) -> Unit = { msg ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
    }

    val tabs = listOf(
        "Discover" to Icons.Default.Explore,
        "Studio" to Icons.Default.Build,
        "Connect" to Icons.Default.Hub,
        "Tools" to Icons.Default.Terminal,
        "Automate" to Icons.Default.AccountTree,
        "Security" to Icons.Default.Security
    )

    val connectedCount = remember(connectors, healthMap) {
        connectors.count { (healthMap[it.id]?.status ?: it.status) == ConnectorStatus.CONNECTED }
    }

    Scaffold(
        containerColor = Background,
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Surface,
                    titleContentColor = TextPrimary
                ),
                navigationIcon = {
                    IconButton(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("kucp_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "Integrations Hub",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = TextPrimary,
                            modifier = Modifier.testTag("kucp_dashboard_title")
                        )
                        Text(
                            text = "Universal Connectivity Platform (KUCP)",
                            fontSize = 11.sp,
                            color = AccentCyan
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                gateway.reloadFromDatabase()
                                showFeedback("Synced ${connectors.size} connectors & ${tools.size} tools.")
                            }
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh KUCP state", tint = TextPrimary)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(listOf(Background, BackgroundGradientEnd))
                )
                .padding(padding)
        ) {
            // Summary metrics header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricBadge("Connected", "$connectedCount/${connectors.size}", SuccessGreen, Modifier.weight(1f))
                MetricBadge("Active Tools", "${tools.count { it.status == ConnectorStatus.CONNECTED || it.status == ConnectorStatus.CONFIGURED }}", AccentCyan, Modifier.weight(1f))
                MetricBadge("Workflows", "${workflows.size}", Primary, Modifier.weight(1f))
            }

            // Tab Strip
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("kucp_tab_row"),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(tabs) { index, (label, icon) ->
                    val selected = selectedTab == index
                    FilterChip(
                        selected = selected,
                        onClick = { selectedTab = index },
                        leadingIcon = {
                            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = {
                            Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                        },
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("kucp_tab_${label.lowercase()}")
                    )
                }
            }

            HorizontalDivider(color = OutlineVariant, modifier = Modifier.padding(vertical = 4.dp))

            // Active Tab Body
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 820.dp)
                    .align(Alignment.CenterHorizontally)
                    .testTag("kucp_content_list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    when (selectedTab) {
                        0 -> KucpDiscoverTab(
                            gateway = gateway,
                            onFeedback = showFeedback,
                            onInstalledNavigateToConnect = { selectedTab = 2 }
                        )
                        1 -> KucpStudioTab(
                            gateway = gateway,
                            onFeedback = showFeedback,
                            onSavedNavigateToConnect = { selectedTab = 2 }
                        )
                        2 -> KucpConnectTab(
                            gateway = gateway,
                            connectors = connectors,
                            healthMap = healthMap,
                            onFeedback = showFeedback
                        )
                        3 -> KucpToolsTab(
                            gateway = gateway,
                            tools = tools,
                            onFeedback = showFeedback
                        )
                        4 -> KucpAutomateTab(
                            gateway = gateway,
                            connectors = connectors,
                            tools = tools,
                            plugins = plugins,
                            webhooks = webhooks,
                            webhookEvents = webhookEvents,
                            workflows = workflows,
                            workflowRuns = workflowRuns,
                            onFeedback = showFeedback
                        )
                        else -> KucpSecurityTab(
                            gateway = gateway,
                            auditEvents = auditEvents,
                            onFeedback = showFeedback
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricBadge(
    label: String,
    value: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = SurfaceGlass
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, color = accent, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            Text(label, color = TextSecondary, fontSize = 11.sp)
        }
    }
}
