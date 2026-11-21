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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kucp.KucpGateway
import com.example.kucp.android.AndroidNativeIntegrationProvider
import com.example.kucp.model.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun KucpConnectTab(
    gateway: KucpGateway,
    connectors: List<ConnectorDefinition>,
    healthMap: Map<String, ConnectorHealth>,
    onFeedback: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var credentialModalConnector by remember { mutableStateOf<ConnectorDefinition?>(null) }
    var secretSlot by remember { mutableStateOf("primary") }
    var secretValue by remember { mutableStateOf("") }
    var exportedJsonModal by remember { mutableStateOf<Pair<String, String>?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (connectors.isEmpty()) {
            KucpGlassCard {
                Text("No connectors registered yet. Install one from Discover or build one in Studio.", color = TextSecondary)
            }
        } else {
            connectors.forEach { conn ->
                val h = healthMap[conn.id]
                val effectiveStatus = h?.status ?: conn.status
                KucpGlassCard(modifier = Modifier.testTag("kucp_connector_card_${conn.id}")) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(conn.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("${conn.provider} • v${conn.version} • ${conn.protocol.displayName}", color = AccentCyan, fontSize = 11.sp)
                        }
                        Switch(
                            checked = conn.enabled,
                            onCheckedChange = { checked ->
                                scope.launch { gateway.setConnectorEnabled(conn.id, checked) }
                            }
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        KucpStatusChip(effectiveStatus.label, statusColor(effectiveStatus))
                        if (h != null && h.latencyMs > 0L) {
                            KucpStatusChip("${h.latencyMs} ms", AccentCyan)
                        }
                        KucpStatusChip("${conn.capabilities.size} tools", Primary)
                    }

                    if (conn.baseUrl.isNotBlank()) {
                        Text(conn.baseUrl, color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    h?.lastError?.takeIf { it.isNotBlank() }?.let { err ->
                        Text("Last error: $err", color = Error, fontSize = 11.sp)
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val res = gateway.connectAndValidateConnector(conn.id)
                                    onFeedback(
                                        res.fold(
                                            onSuccess = { "${conn.name} verified (${it.latencyMs}ms)" },
                                            onFailure = { "Connection failed: ${it.message}" }
                                        )
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp)
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Connect", fontSize = 12.sp)
                        }

                        if (conn.authConfig.schemeType == AuthSchemeType.OAUTH2_PKCE || conn.authConfig.schemeType == AuthSchemeType.OIDC) {
                            FilledTonalButton(
                                onClick = {
                                    val startRes = gateway.startOAuthPkceFlow(conn.id)
                                    startRes.fold(
                                        onSuccess = { (_, uri) ->
                                            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                            onFeedback("Opened OAuth 2.0 PKCE consent in browser.")
                                        },
                                        onFailure = { err -> onFeedback("OAuth config needed: ${err.message}") }
                                    )
                                },
                                modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp)
                            ) {
                                Text("OAuth PKCE", fontSize = 12.sp)
                            }
                        } else if (conn.authConfig.schemeType != AuthSchemeType.NONE) {
                            FilledTonalButton(
                                onClick = {
                                    credentialModalConnector = conn
                                    secretSlot = conn.authConfig.credentialSlot.ifBlank { "primary" }
                                    secretValue = ""
                                },
                                modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp)
                            ) {
                                Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Credentials", fontSize = 12.sp)
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                val bundle = gateway.exportConnectorBundle(conn.id).orEmpty()
                                exportedJsonModal = "${conn.name} JSON Bundle" to bundle
                            },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) {
                            Text("Export JSON", fontSize = 12.sp)
                        }
                        if (conn.rollbackSnapshotJson.isNotBlank()) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        val r = gateway.rollbackConnectorVersion(conn.id)
                                        onFeedback(r.fold(onSuccess = { "Rolled back ${it.name}" }, onFailure = { it.message ?: "Failed" }))
                                    }
                                },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                            ) {
                                Text("Rollback", fontSize = 12.sp)
                            }
                        }
                        if (conn.id != AndroidNativeIntegrationProvider.CONNECTOR_ID) {
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        gateway.deleteConnector(conn.id)
                                        onFeedback("Removed '${conn.name}' and wiped its vault secrets.")
                                    }
                                },
                                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete connector", tint = Error)
                            }
                        }
                    }
                }
            }
        }
    }

    credentialModalConnector?.let { targetConn ->
        val currentMasked = remember(targetConn.id, secretSlot) {
            gateway.getMaskedCredential(targetConn.id, secretSlot)
        }
        AlertDialog(
            onDismissRequest = { credentialModalConnector = null },
            title = { Text("Vault Secret: ${targetConn.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Current masked value: $currentMasked", color = AccentCyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    OutlinedTextField(
                        value = secretSlot,
                        onValueChange = { secretSlot = it },
                        label = { Text("Slot (e.g. primary, access_token, webhook_secret)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = secretValue,
                        onValueChange = { secretValue = it },
                        label = { Text("Secret Token / API Key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        val ok = gateway.saveConnectorCredential(targetConn.id, secretSlot.trim().ifBlank { "primary" }, secretValue.trim())
                        credentialModalConnector = null
                        onFeedback(if (ok) "Encrypted secret saved to Vault." else "Failed to store empty secret.")
                    }
                }) {
                    Text("Save Encrypted")
                }
            },
            dismissButton = {
                TextButton(onClick = { credentialModalConnector = null }) { Text("Cancel") }
            }
        )
    }

    exportedJsonModal?.let { (title, jsonStr) ->
        AlertDialog(
            onDismissRequest = { exportedJsonModal = null },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = jsonStr,
                    onValueChange = {},
                    readOnly = true,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = { exportedJsonModal = null }) { Text("Close") }
            }
        )
    }
}

@Composable
fun KucpToolsTab(
    gateway: KucpGateway,
    tools: List<ToolDescriptor>,
    onFeedback: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val matchedTools = remember(query, tools) {
        if (query.isBlank()) tools else gateway.toolRegistry.retrieveRelevantTools(query, maxResults = 25)
    }
    var selectedTool by remember(tools) { mutableStateOf<ToolDescriptor?>(tools.firstOrNull()) }
    var argValues by remember(selectedTool?.toolId) {
        mutableStateOf(
            selectedTool?.inputSchema?.associate { it.name to (it.defaultValue ?: "") }.orEmpty()
        )
    }
    var lastResult by remember { mutableStateOf<ToolInvocationResult?>(null) }
    var pendingConfirmTool by remember { mutableStateOf<Pair<ToolDescriptor, ToolInvocationRequest>?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("BM25 Contextual Tool Filter (e.g. clipboard, apps, github, email)") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("kucp_tools_search_input")
        )

        selectedTool?.let { activeTool ->
            KucpGlassCard(borderColor = AccentCyan.copy(alpha = 0.55f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Test Console: ${activeTool.name}", color = TextPrimary, fontWeight = FontWeight.Bold)
                    KucpStatusChip(activeTool.riskLevel.name, if (activeTool.riskLevel == ToolRiskLevel.LOW) SuccessGreen else AccentYellow)
                }
                Text(activeTool.description, color = TextSecondary, fontSize = 12.sp)
                activeTool.inputSchema.forEach { param ->
                    OutlinedTextField(
                        value = argValues[param.name].orEmpty(),
                        onValueChange = { newVal -> argValues = argValues + (param.name to newVal) },
                        label = { Text("${param.name}${if (param.required) "*" else ""} (${param.type})") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Button(
                    onClick = {
                        scope.launch {
                            val req = ToolInvocationRequest(
                                requestId = UUID.randomUUID().toString(),
                                connectorId = activeTool.connectorId,
                                toolId = activeTool.toolId,
                                arguments = argValues.filterValues { it.isNotBlank() },
                                userConfirmed = false,
                                idempotencyKey = UUID.randomUUID().toString()
                            )
                            val res = gateway.executeTool(req)
                            if (res.requiresUserConfirmation) {
                                pendingConfirmTool = activeTool to req
                            } else {
                                lastResult = res
                                onFeedback("${activeTool.name}: ${if (res.success) "OK" else res.errorCode}")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag("kucp_tool_execute_btn")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Execute ${activeTool.name}")
                }

                lastResult?.let { r ->
                    KucpGlassCard(borderColor = if (r.success) SuccessGreen else Error) {
                        Text(
                            if (r.success) "Success • HTTP ${r.statusCode} • ${r.durationMs}ms" else "Failed (${r.errorCode}): ${r.errorMessage}",
                            color = if (r.success) SuccessGreenGlow else Error,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                        if (r.outputData.isNotBlank()) {
                            Text(r.outputData.take(700), color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        matchedTools.forEach { t ->
            KucpGlassCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(t.name, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        Text("${t.protocol.name} • ${t.methodOrOperation} ${t.endpointPath}", color = AccentCyan, fontSize = 11.sp)
                        Text(t.description, color = TextSecondary, fontSize = 12.sp)
                    }
                    FilledTonalButton(
                        onClick = {
                            selectedTool = t
                            argValues = t.inputSchema.associate { it.name to (it.defaultValue ?: "") }
                        },
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text("Inspect")
                    }
                }
            }
        }
    }

    pendingConfirmTool?.let { (tool, req) ->
        AlertDialog(
            onDismissRequest = { pendingConfirmTool = null },
            title = { Text("Confirm ${tool.riskLevel.label} Tool") },
            text = { Text("Allow execution of '${tool.name}' on connector '${tool.connectorId}'?") },
            confirmButton = {
                Button(onClick = {
                    pendingConfirmTool = null
                    scope.launch {
                        val res = gateway.executeTool(req.copy(userConfirmed = true))
                        lastResult = res
                        onFeedback("${tool.name}: ${if (res.success) "Confirmed & Executed" else res.errorCode}")
                    }
                }) { Text("Approve") }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmTool = null }) { Text("Cancel") }
            }
        )
    }
}
