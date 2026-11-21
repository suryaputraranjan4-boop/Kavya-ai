package com.example.ui.screens.kucp

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kucp.KucpGateway
import com.example.kucp.discovery.CustomConnectorBuilder
import com.example.kucp.model.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun KucpDiscoverTab(
    gateway: KucpGateway,
    onFeedback: (String) -> Unit,
    onInstalledNavigateToConnect: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val templates = remember { gateway.getMarketplaceTemplates() }
    var selectedCategory by remember { mutableStateOf<ConnectorCategory?>(null) }
    var registryQuery by remember { mutableStateOf("") }
    var specUrlInput by remember { mutableStateOf("") }
    var searchingRegistry by remember { mutableStateOf(false) }
    var registryResults by remember { mutableStateOf<List<DiscoveryCandidate>>(emptyList()) }
    var inspectedDraft by remember { mutableStateOf<ConnectorDraft?>(null) }

    val filteredTemplates = remember(templates, selectedCategory) {
        if (selectedCategory == null) templates else templates.filter { it.category == selectedCategory }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        KucpGlassCard(borderColor = AccentCyan.copy(alpha = 0.45f)) {
            Text("Internet Integration Discovery", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                "Search public APIs.guru OpenAPI specs & GitHub MCP/API repositories, or inspect any OpenAPI / Swagger spec URL.",
                color = TextSecondary,
                fontSize = 12.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = registryQuery,
                    onValueChange = { registryQuery = it },
                    label = { Text("Search services (e.g. stripe, github, mcp)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("kucp_discovery_search_input")
                )
                Button(
                    onClick = {
                        if (registryQuery.isBlank()) return@Button
                        searchingRegistry = true
                        scope.launch {
                            val res = gateway.searchInternetRegistries(registryQuery)
                            searchingRegistry = false
                            res.fold(
                                onSuccess = { list ->
                                    registryResults = list
                                    onFeedback("Found ${list.size} integration candidates.")
                                },
                                onFailure = { err -> onFeedback(err.message ?: "Registry search failed.") }
                            )
                        }
                    },
                    enabled = !searchingRegistry,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("kucp_discovery_search_btn")
                ) {
                    if (searchingRegistry) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Search, contentDescription = "Search registries")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = specUrlInput,
                    onValueChange = { specUrlInput = it },
                    label = { Text("Direct OpenAPI / Swagger URL") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = {
                        if (specUrlInput.isBlank()) return@OutlinedButton
                        scope.launch {
                            val res = gateway.discoveryEngine.inspectRemoteSpecUrl(specUrlInput.trim())
                            res.fold(
                                onSuccess = { draft ->
                                    inspectedDraft = draft
                                    onFeedback("Parsed '${draft.connector.name}' with ${draft.tools.size} tools.")
                                },
                                onFailure = { err -> onFeedback("Spec inspect failed: ${err.message}") }
                            )
                        }
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text("Inspect")
                }
            }

            inspectedDraft?.let { draft ->
                DraftPreviewCard(
                    draft = draft,
                    onApproveSave = {
                        scope.launch {
                            gateway.saveConnectorAndTools(draft.connector, draft.tools)
                            inspectedDraft = null
                            onFeedback("Installed '${draft.connector.name}' (${draft.tools.size} tools).")
                            onInstalledNavigateToConnect()
                        }
                    },
                    onDismiss = { inspectedDraft = null }
                )
            }

            registryResults.take(6).forEach { cand ->
                KucpGlassCard(borderColor = Outline) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(cand.name, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("${cand.sourceRegistry} • ${cand.protocol.displayName}", color = AccentCyan, fontSize = 11.sp)
                        }
                        KucpStatusChip(cand.license, TextSecondary)
                    }
                    if (cand.description.isNotBlank()) {
                        Text(cand.description, color = TextSecondary, fontSize = 12.sp)
                    }
                    cand.deprecationWarning?.let { warn ->
                        Text("Warning: $warn", color = AccentYellow, fontSize = 11.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (cand.specUrl.isNotBlank() && cand.protocol == ConnectorProtocol.OPENAPI) {
                            Button(
                                onClick = {
                                    specUrlInput = cand.specUrl
                                    scope.launch {
                                        val res = gateway.discoveryEngine.inspectRemoteSpecUrl(cand.specUrl)
                                        res.fold(
                                            onSuccess = { inspectedDraft = it },
                                            onFailure = { onFeedback("Could not inspect spec: ${it.message}") }
                                        )
                                    }
                                },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                            ) {
                                Text("Preview Spec")
                            }
                        }
                        if (cand.url.startsWith("https://")) {
                            OutlinedButton(
                                onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(cand.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                            ) {
                                Text("View Docs")
                            }
                        }
                    }
                }
            }
        }

        Text("Ecosystem Marketplace (${filteredTemplates.size})", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = selectedCategory == null,
                onClick = { selectedCategory = null },
                label = { Text("All", fontSize = 11.sp) }
            )
            listOf(
                ConnectorCategory.DEVELOPER_DEVOPS,
                ConnectorCategory.GOOGLE_WORKSPACE,
                ConnectorCategory.COMMUNICATION,
                ConnectorCategory.PRODUCTIVITY,
                ConnectorCategory.AI_ML
            ).forEach { cat ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { selectedCategory = if (selectedCategory == cat) null else cat },
                    label = { Text(cat.label.substringBefore(" "), fontSize = 11.sp) }
                )
            }
        }

        filteredTemplates.forEach { tpl ->
            KucpGlassCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tpl.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("${tpl.provider} • ${tpl.protocol.displayName} • ${tpl.defaultAuthScheme.displayName}", color = AccentCyan, fontSize = 11.sp)
                    }
                    KucpStatusChip(tpl.category.label, Primary)
                }
                Text(tpl.description, color = TextSecondary, fontSize = 12.sp)
                if (tpl.setupNotes.isNotBlank()) {
                    Text(tpl.setupNotes, color = TextTertiary, fontSize = 11.sp)
                }
                Button(
                    onClick = {
                        scope.launch {
                            val installed = gateway.installMarketplaceTemplate(tpl)
                            onFeedback("Added '${installed.name}'. Configure credentials & click Connect.")
                            onInstalledNavigateToConnect()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("kucp_install_template_${tpl.id}")
                ) {
                    Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Configure ${tpl.name} (${tpl.defaultTools.size} tools)")
                }
            }
        }
    }
}

@Composable
fun KucpStudioTab(
    gateway: KucpGateway,
    onFeedback: (String) -> Unit,
    onSavedNavigateToConnect: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Visual Builder inputs
    var connName by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var selectedProtocol by remember { mutableStateOf(ConnectorProtocol.REST) }
    var selectedAuth by remember { mutableStateOf(AuthSchemeType.BEARER_TOKEN) }
    var toolName by remember { mutableStateOf("fetch_items") }
    var toolMethod by remember { mutableStateOf("GET") }
    var toolPath by remember { mutableStateOf("/v1/items") }
    var allowLocalDev by remember { mutableStateOf(false) }

    // OpenAPI / Swagger / AI Docs Import
    var specText by remember { mutableStateOf("") }
    var docsText by remember { mutableStateOf("") }
    var aiLoading by remember { mutableStateOf(false) }
    var previewDraft by remember { mutableStateOf<ConnectorDraft?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        previewDraft?.let { draft ->
            DraftPreviewCard(
                draft = draft,
                onApproveSave = {
                    scope.launch {
                        gateway.saveConnectorAndTools(draft.connector, draft.tools)
                        previewDraft = null
                        onFeedback("Saved custom connector '${draft.connector.name}' (${draft.tools.size} tools).")
                        onSavedNavigateToConnect()
                    }
                },
                onDismiss = { previewDraft = null }
            )
        }

        KucpGlassCard {
            Text("Visual Custom Connector Builder", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            OutlinedTextField(
                value = connName,
                onValueChange = { connName = it },
                label = { Text("Connector Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("kucp_studio_name_input")
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base / Endpoint URL (https://... or wss://...)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("kucp_studio_url_input")
            )
            Text("Protocol", color = TextSecondary, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    ConnectorProtocol.REST,
                    ConnectorProtocol.MCP,
                    ConnectorProtocol.GRAPHQL,
                    ConnectorProtocol.A2A,
                    ConnectorProtocol.GRPC
                ).forEach { p ->
                    FilterChip(
                        selected = selectedProtocol == p,
                        onClick = { selectedProtocol = p },
                        label = { Text(p.name, fontSize = 11.sp) }
                    )
                }
            }
            Text("Auth Scheme", color = TextSecondary, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    AuthSchemeType.NONE,
                    AuthSchemeType.BEARER_TOKEN,
                    AuthSchemeType.API_KEY,
                    AuthSchemeType.OAUTH2_PKCE
                ).forEach { a ->
                    FilterChip(
                        selected = selectedAuth == a,
                        onClick = { selectedAuth = a },
                        label = { Text(a.name.replace("_", " "), fontSize = 10.sp) }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = toolMethod,
                    onValueChange = { toolMethod = it },
                    label = { Text("Method") },
                    singleLine = true,
                    modifier = Modifier.weight(0.4f)
                )
                OutlinedTextField(
                    value = toolPath,
                    onValueChange = { toolPath = it },
                    label = { Text("Path / Op") },
                    singleLine = true,
                    modifier = Modifier.weight(0.6f)
                )
            }
            OutlinedTextField(
                value = toolName,
                onValueChange = { toolName = it },
                label = { Text("Initial Tool Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = allowLocalDev, onCheckedChange = { allowLocalDev = it })
                Text("Allow local/LAN development server (SSRF exemption)", color = TextSecondary, fontSize = 12.sp)
            }
            Button(
                onClick = {
                    val draftRes = CustomConnectorBuilder.buildVisualConnector(
                        name = connName,
                        baseUrl = baseUrl,
                        protocol = selectedProtocol,
                        authScheme = selectedAuth,
                        isLocalDevAllowed = allowLocalDev,
                        initialToolName = toolName,
                        initialMethod = toolMethod,
                        initialPath = toolPath
                    )
                    draftRes.fold(
                        onSuccess = { draft ->
                            previewDraft = draft
                            onFeedback("Draft built. Review & approve above.")
                        },
                        onFailure = { err -> onFeedback("Builder validation: ${err.message}") }
                    )
                },
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag("kucp_studio_build_btn")
            ) {
                Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Preview Custom Connector Draft")
            }
        }

        KucpGlassCard {
            Text("Import OpenAPI 3.x / Swagger 2.0 (JSON or YAML)", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            OutlinedTextField(
                value = specText,
                onValueChange = { specText = it },
                label = { Text("Paste OpenAPI / Swagger JSON or YAML") },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth().testTag("kucp_openapi_input")
            )
            Button(
                onClick = {
                    val res = gateway.previewOpenApiImport(specText, allowLocalDev = allowLocalDev)
                    res.fold(
                        onSuccess = { draft ->
                            previewDraft = draft
                            onFeedback("Parsed '${draft.connector.name}' (${draft.tools.size} tools).")
                        },
                        onFailure = { err -> onFeedback("Import failed: ${err.message}") }
                    )
                },
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag("kucp_openapi_import_btn")
            ) {
                Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Parse Spec into Draft")
            }
        }

        KucpGlassCard {
            Text("AI-Assisted Documentation Analyzer", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                "Generates an unprivileged ConnectorDraft from pasted API docs for your explicit manual approval.",
                color = TextSecondary,
                fontSize = 12.sp
            )
            OutlinedTextField(
                value = docsText,
                onValueChange = { docsText = it },
                label = { Text("Paste API documentation snippet") },
                minLines = 2,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = {
                    if (docsText.isBlank()) return@OutlinedButton
                    aiLoading = true
                    scope.launch {
                        val res = CustomConnectorBuilder.analyzeDocumentationWithAi(context, docsText)
                        aiLoading = false
                        res.fold(
                            onSuccess = { draft ->
                                previewDraft = draft
                                onFeedback("AI generated reviewable draft '${draft.connector.name}'.")
                            },
                            onFailure = { err -> onFeedback("AI analysis unavailable: ${err.message}") }
                        )
                    }
                },
                enabled = !aiLoading,
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
            ) {
                if (aiLoading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Analyze Docs into Draft")
            }
        }
    }
}

@Composable
private fun DraftPreviewCard(
    draft: ConnectorDraft,
    onApproveSave: () -> Unit,
    onDismiss: () -> Unit
) {
    KucpGlassCard(borderColor = SuccessGreen.copy(alpha = 0.7f)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Draft Preview: ${draft.connector.name}", color = SuccessGreenGlow, fontWeight = FontWeight.Bold)
            KucpStatusChip(draft.connector.protocol.name, AccentCyan)
        }
        Text("Base URL: ${draft.connector.baseUrl.ifBlank { "(none)" }}", color = TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text("Discovered Tools (${draft.tools.size}): ${draft.tools.joinToString { it.name }.ifBlank { "Dynamic/MCP" }}", color = TextPrimary, fontSize = 12.sp)
        draft.analysisWarnings.forEach { warn ->
            Text("Review: $warn", color = AccentYellow, fontSize = 11.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onApproveSave,
                modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("kucp_approve_draft_btn")
            ) {
                Text("Approve & Save")
            }
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Text("Discard")
            }
        }
    }
}
