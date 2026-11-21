package com.example.ui.screens.kucp

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kucp.KucpGateway
import com.example.kucp.model.*
import com.example.kucp.storage.KucpPluginEntity
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
internal fun KucpGlassCard(
    modifier: Modifier = Modifier,
    borderColor: Color = GlassBorder,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = SurfaceGlass,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
internal fun KucpStatusChip(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f))
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

internal fun statusColor(status: ConnectorStatus): Color = when (status) {
    ConnectorStatus.CONNECTED -> SuccessGreen
    ConnectorStatus.TEMPLATE -> TextSecondary
    ConnectorStatus.CONFIGURED -> AccentCyan
    ConnectorStatus.DEGRADED, ConnectorStatus.RATE_LIMITED -> AccentYellow
    ConnectorStatus.ERROR, ConnectorStatus.REVOKED -> Error
    ConnectorStatus.DISCONNECTED -> TextTertiary
}

@Composable
fun KucpAutomateTab(
    gateway: KucpGateway,
    connectors: List<ConnectorDefinition>,
    tools: List<ToolDescriptor>,
    plugins: List<KucpPluginEntity>,
    webhooks: List<WebhookSubscription>,
    webhookEvents: List<WebhookEventRecord>,
    workflows: List<WorkflowDefinition>,
    workflowRuns: List<WorkflowExecutionRecord>,
    onFeedback: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var subSection by remember { mutableIntStateOf(0) } // 0=Workflows, 1=Webhooks, 2=Plugins

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Workflows (${workflows.size})", "Webhooks (${webhooks.size})", "Plugins (${plugins.size})").forEachIndexed { idx, label ->
                FilterChip(
                    selected = subSection == idx,
                    onClick = { subSection = idx },
                    label = { Text(label, fontSize = 12.sp) },
                    modifier = Modifier.defaultMinSize(minHeight = 40.dp)
                )
            }
        }

        when (subSection) {
            0 -> WorkflowsSection(gateway, tools, workflows, workflowRuns, onFeedback, scope)
            1 -> WebhooksSection(gateway, connectors, webhooks, webhookEvents, onFeedback, scope)
            else -> PluginsSection(gateway, plugins, onFeedback, scope)
        }
    }
}

@Composable
private fun WorkflowsSection(
    gateway: KucpGateway,
    tools: List<ToolDescriptor>,
    workflows: List<WorkflowDefinition>,
    workflowRuns: List<WorkflowExecutionRecord>,
    onFeedback: (String) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope
) {
    var wfName by remember { mutableStateOf("") }
    var selectedToolId by remember(tools) { mutableStateOf(tools.firstOrNull()?.toolId.orEmpty()) }
    var argKey by remember { mutableStateOf("query") }
    var argVal by remember { mutableStateOf("") }

    KucpGlassCard {
        Text("Create DAG Workflow", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        OutlinedTextField(
            value = wfName,
            onValueChange = { wfName = it },
            label = { Text("Workflow Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("kucp_wf_name_input")
        )
        Text(
            text = "Step 1 Tool: ${tools.firstOrNull { it.toolId == selectedToolId }?.name ?: "Select a tool"}",
            color = AccentCyan,
            fontSize = 12.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tools.take(4).forEach { t ->
                FilterChip(
                    selected = t.toolId == selectedToolId,
                    onClick = { selectedToolId = t.toolId },
                    label = { Text(t.name.take(18), fontSize = 11.sp) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = argKey,
                onValueChange = { argKey = it },
                label = { Text("Param") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = argVal,
                onValueChange = { argVal = it },
                label = { Text("Value") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Button(
            onClick = {
                val chosen = tools.firstOrNull { it.toolId == selectedToolId }
                if (wfName.isBlank() || chosen == null) {
                    onFeedback("Enter a workflow name and select a tool.")
                    return@Button
                }
                scope.launch {
                    val wf = WorkflowDefinition(
                        id = "wf_${UUID.randomUUID().toString().take(8)}",
                        name = wfName.trim(),
                        description = "Triggers ${chosen.name}",
                        steps = listOf(
                            WorkflowStep(
                                stepId = "step_1",
                                title = chosen.name,
                                connectorId = chosen.connectorId,
                                toolId = chosen.toolId,
                                inputMapping = if (argKey.isNotBlank() && argVal.isNotBlank()) mapOf(argKey.trim() to argVal.trim()) else emptyMap()
                            )
                        )
                    )
                    gateway.saveWorkflow(wf)
                    wfName = ""
                    onFeedback("Saved workflow '${wf.name}'.")
                }
            },
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag("kucp_wf_save_btn")
        ) {
            Icon(Icons.Default.AddTask, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Save Workflow")
        }
    }

    if (workflows.isEmpty()) {
        KucpGlassCard {
            Text("No workflows saved yet. Create a DAG workflow above or link a webhook trigger.", color = TextSecondary, fontSize = 13.sp)
        }
    } else {
        workflows.forEach { wf ->
            KucpGlassCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(wf.name, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        Text("${wf.steps.size} steps • Trigger: ${wf.triggerType}", color = TextSecondary, fontSize = 12.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    val rec = gateway.runWorkflow(wf.id)
                                    onFeedback("Workflow '${wf.name}': ${rec.status}")
                                }
                            },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Run workflow")
                            Spacer(Modifier.width(4.dp))
                            Text("Run")
                        }
                        IconButton(
                            onClick = { scope.launch { gateway.deleteWorkflow(wf.id) } },
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete workflow", tint = Error)
                        }
                    }
                }
            }
        }
    }

    if (workflowRuns.isNotEmpty()) {
        KucpGlassCard {
            Text("Recent Workflow Runs", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            workflowRuns.take(5).forEach { run ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(run.workflowName, color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    KucpStatusChip(
                        text = run.status,
                        color = if (run.status == "COMPLETED") SuccessGreen else if (run.status == "FAILED") Error else AccentYellow
                    )
                }
            }
        }
    }
}

@Composable
private fun WebhooksSection(
    gateway: KucpGateway,
    connectors: List<ConnectorDefinition>,
    webhooks: List<WebhookSubscription>,
    webhookEvents: List<WebhookEventRecord>,
    onFeedback: (String) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope
) {
    var hookName by remember { mutableStateOf("") }
    var targetUrl by remember { mutableStateOf("") }
    var selectedConnId by remember(connectors) { mutableStateOf(connectors.firstOrNull()?.id.orEmpty()) }

    KucpGlassCard {
        Text("Register Webhook Subscription", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        OutlinedTextField(
            value = hookName,
            onValueChange = { hookName = it },
            label = { Text("Subscription Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = targetUrl,
            onValueChange = { targetUrl = it },
            label = { Text("Webhook Endpoint URL (optional for inbound)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                if (hookName.isBlank()) {
                    onFeedback("Enter a webhook subscription name.")
                    return@Button
                }
                scope.launch {
                    val sub = WebhookSubscription(
                        id = "wh_${UUID.randomUUID().toString().take(8)}",
                        connectorId = selectedConnId.ifBlank { "custom" },
                        name = hookName.trim(),
                        targetUrl = targetUrl.trim(),
                        eventTypes = listOf("event.created", "event.updated")
                    )
                    gateway.saveWebhookSubscription(sub)
                    hookName = ""
                    targetUrl = ""
                    onFeedback("Saved webhook '${sub.name}'.")
                }
            },
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
        ) {
            Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Save Webhook Subscription")
        }
    }

    webhooks.forEach { wh ->
        KucpGlassCard {
            Text(wh.name, color = TextPrimary, fontWeight = FontWeight.SemiBold)
            Text("Connector: ${wh.connectorId} • Deliveries: ${wh.deliveryCount}", color = TextSecondary, fontSize = 12.sp)
        }
    }

    if (webhookEvents.isNotEmpty()) {
        KucpGlassCard {
            Text("Recent Webhook Events", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            webhookEvents.take(5).forEach { ev ->
                Text(
                    "${ev.eventType} (${if (ev.signatureValid) "HMAC Verified" else "Invalid Sig"}): ${ev.payloadExcerpt.take(80)}",
                    color = if (ev.signatureValid) SuccessGreen else Error,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun PluginsSection(
    gateway: KucpGateway,
    plugins: List<KucpPluginEntity>,
    onFeedback: (String) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope
) {
    var manifestInput by remember { mutableStateOf("") }

    KucpGlassCard {
        Text("Install Sandboxed Declarative Plugin", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(
            "Google Play compliant: accepts declarative JSON plugin manifests verified by SHA-256 & domain allowlist (binary .dex/.jar/.so blocked).",
            color = TextSecondary,
            fontSize = 12.sp
        )
        OutlinedTextField(
            value = manifestInput,
            onValueChange = { manifestInput = it },
            label = { Text("Plugin Manifest JSON") },
            minLines = 3,
            maxLines = 7,
            modifier = Modifier.fillMaxWidth().testTag("kucp_plugin_manifest_input")
        )
        Button(
            onClick = {
                scope.launch {
                    val res = gateway.installPlugin(manifestInput)
                    res.fold(
                        onSuccess = { p ->
                            manifestInput = ""
                            onFeedback("Installed sandboxed plugin '${p.name}' v${p.version}.")
                        },
                        onFailure = { err ->
                            onFeedback("Plugin rejected: ${err.message}")
                        }
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag("kucp_plugin_install_btn")
        ) {
            Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Validate & Install Plugin")
        }
    }

    plugins.forEach { pe ->
        KucpGlassCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("${pe.name} v${pe.version}", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text("SHA-256: ${pe.sha256.take(16)}…", color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Switch(
                    checked = pe.enabled,
                    onCheckedChange = { checked ->
                        scope.launch { gateway.togglePluginEnabled(pe.pluginId, checked) }
                    }
                )
            }
            if (pe.previousManifestJson.isNotBlank()) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val r = gateway.rollbackPlugin(pe.pluginId)
                            onFeedback(r.fold(onSuccess = { "Rolled back ${it.name}" }, onFailure = { it.message ?: "Rollback failed" }))
                        }
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text("Rollback Previous Version")
                }
            }
        }
    }
}

@Composable
fun KucpSecurityTab(
    gateway: KucpGateway,
    auditEvents: List<AuditEvent>,
    onFeedback: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permissionsMatrix = remember(auditEvents.size) { gateway.inspectAndroidPermissions() }
    val vaultEntries = remember(auditEvents.size) { gateway.listVaultEntriesMasked() }
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        KucpGlassCard {
            Text("Android Native Capabilities & Permissions", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            permissionsMatrix.forEach { perm ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(perm.title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(perm.remediationHint, color = TextSecondary, fontSize = 11.sp)
                    }
                    KucpStatusChip(
                        text = if (perm.granted) "Granted" else "Needs Grant",
                        color = if (perm.granted) SuccessGreen else AccentYellow
                    )
                }
            }
            OutlinedButton(
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open App Permission Settings")
            }
        }

        KucpGlassCard {
            Text("AES-256-GCM Encrypted Credential Vault", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (vaultEntries.isEmpty()) {
                Text("Vault is currently empty. Add API keys, bearer tokens, or OAuth sessions in the Connect tab.", color = TextSecondary, fontSize = 12.sp)
            } else {
                vaultEntries.forEach { (connId, slot, masked) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("$connId [$slot]", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(masked, color = AccentCyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    gateway.revokeConnectorCredential(connId, slot)
                                    onFeedback("Revoked '$slot' for $connId")
                                }
                            },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) {
                            Text("Revoke", color = Error)
                        }
                    }
                }
            }
        }

        KucpGlassCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Security Audit Log (${auditEvents.size})", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                TextButton(
                    onClick = {
                        scope.launch {
                            gateway.clearAuditLog()
                            onFeedback("Audit log cleared.")
                        }
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text("Clear", color = TextSecondary)
                }
            }
            if (auditEvents.isEmpty()) {
                Text("No audit events recorded yet.", color = TextSecondary, fontSize = 12.sp)
            } else {
                auditEvents.take(15).forEach { ev ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                            .padding(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(ev.action, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text("${formatter.format(Date(ev.timestampMs))} • ${ev.outcome}", color = AccentCyan, fontSize = 11.sp)
                        }
                        if (ev.detailRedacted.isNotBlank()) {
                            Text(ev.detailRedacted, color = TextSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
