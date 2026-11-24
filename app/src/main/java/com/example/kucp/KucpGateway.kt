package com.example.kucp

import android.content.Context
import android.net.Uri
import com.example.data.AppDatabase
import com.example.kucp.android.AndroidCapabilityPermissionStatus
import com.example.kucp.android.AndroidNativeIntegrationProvider
import com.example.kucp.auth.KucpOAuthManager
import com.example.kucp.auth.OAuthTokenResult
import com.example.kucp.auth.PkceSession
import com.example.kucp.catalog.KucpIntegrationCatalog
import com.example.kucp.discovery.CustomConnectorBuilder
import com.example.kucp.discovery.IntegrationDiscoveryEngine
import com.example.kucp.model.AuditEvent
import com.example.kucp.model.CatalogRefreshReport
import com.example.kucp.model.ConnectorDefinition
import com.example.kucp.model.ConnectorDraft
import com.example.kucp.model.ConnectorHealth
import com.example.kucp.model.ConnectorProtocol
import com.example.kucp.model.ConnectorStatus
import com.example.kucp.model.DiscoveryCandidate
import com.example.kucp.model.MarketplaceTemplate
import com.example.kucp.model.PluginManifest
import com.example.kucp.model.ToolDescriptor
import com.example.kucp.model.ToolInvocationRequest
import com.example.kucp.model.ToolInvocationResult
import com.example.kucp.model.ToolRiskLevel
import com.example.kucp.model.WebhookEventRecord
import com.example.kucp.model.WebhookSubscription
import com.example.kucp.model.WorkflowDefinition
import com.example.kucp.model.WorkflowExecutionRecord
import com.example.kucp.plugin.SandboxedPluginRuntime
import com.example.kucp.protocol.GraphqlRuntime
import com.example.kucp.protocol.LocalMcpBridgeHandler
import com.example.kucp.protocol.McpClientRuntime
import com.example.kucp.protocol.OpenApiAndYamlParser
import com.example.kucp.protocol.RestApiRuntime
import com.example.kucp.protocol.WebhookWebSocketA2aGrpcRuntime
import com.example.kucp.registry.DynamicToolRegistry
import com.example.kucp.security.KucpSecurityGateway
import com.example.kucp.security.SecretRedactor
import com.example.kucp.storage.KucpAuditEventEntity
import com.example.kucp.storage.KucpConnectorEntity
import com.example.kucp.storage.KucpCredentialVault
import com.example.kucp.storage.KucpPluginEntity
import com.example.kucp.storage.KucpToolEntity
import com.example.kucp.storage.KucpWebhookEntity
import com.example.kucp.storage.KucpWorkflowEntity
import com.example.kucp.storage.KucpWorkflowRunEntity
import com.example.kucp.workflow.KucpWorkflowEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.UUID

/**
 * Central Universal Integration Gateway for Kavya AI Universal Connectivity Platform (KUCP).
 * Coordinates persistence, credential vaulting, security policy, protocol runtimes,
 * discovery, plugin sandboxing, dynamic tool registry, Android native capabilities,
 * webhooks, and DAG workflows without coupling connectors to the core LLM SystemPrompt.
 */
class KucpGateway private constructor(
    private val appContext: Context,
    httpClient: OkHttpClient = RestApiRuntime.defaultHttpClient()
) {
    companion object {
        @Volatile
        private var instance: KucpGateway? = null

        fun getInstance(context: Context): KucpGateway =
            instance ?: synchronized(this) {
                instance ?: KucpGateway(context.applicationContext).also { instance = it }
            }

        /** Dedicated factory for unit/Robolectric tests using MockWebServer OkHttpClient. */
        internal fun createForTesting(context: Context, httpClient: OkHttpClient): KucpGateway =
            KucpGateway(context.applicationContext, httpClient)
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val registrySyncMutex = Mutex()
    private val db by lazy { AppDatabase.getDatabase(appContext) }
    private val dao by lazy { db.kucpDao() }

    val securityGateway = KucpSecurityGateway()
    val restRuntime = RestApiRuntime(httpClient)
    val graphqlRuntime = GraphqlRuntime(httpClient)
    val mcpRuntime = McpClientRuntime(httpClient)
    val realtimeRuntime = WebhookWebSocketA2aGrpcRuntime(httpClient)
    val oauthManager = KucpOAuthManager(httpClient)
    val discoveryEngine = IntegrationDiscoveryEngine(httpClient)
    val pluginRuntime = SandboxedPluginRuntime(httpClient)
    val toolRegistry = DynamicToolRegistry()

    val workflowEngine = KucpWorkflowEngine(
        executeStepTool = { connectorId, toolId, args, confirmed, idempKey ->
            executeTool(
                ToolInvocationRequest(
                    requestId = UUID.randomUUID().toString(),
                    connectorId = connectorId,
                    toolId = toolId,
                    arguments = args,
                    userConfirmed = confirmed,
                    idempotencyKey = idempKey
                )
            )
        }
    )

    private val _plugins = MutableStateFlow<List<KucpPluginEntity>>(emptyList())
    val plugins: StateFlow<List<KucpPluginEntity>> = _plugins.asStateFlow()

    private val _webhooks = MutableStateFlow<List<WebhookSubscription>>(emptyList())
    val webhooks: StateFlow<List<WebhookSubscription>> = _webhooks.asStateFlow()

    private val _webhookEvents = MutableStateFlow<List<WebhookEventRecord>>(emptyList())
    val webhookEvents: StateFlow<List<WebhookEventRecord>> = _webhookEvents.asStateFlow()

    private val _workflows = MutableStateFlow<List<WorkflowDefinition>>(emptyList())
    val workflows: StateFlow<List<WorkflowDefinition>> = _workflows.asStateFlow()

    private val _workflowRuns = MutableStateFlow<List<WorkflowExecutionRecord>>(emptyList())
    val workflowRuns: StateFlow<List<WorkflowExecutionRecord>> = _workflowRuns.asStateFlow()

    private val _auditEvents = MutableStateFlow<List<AuditEvent>>(emptyList())
    val auditEvents: StateFlow<List<AuditEvent>> = _auditEvents.asStateFlow()

    private val _catalogReport = MutableStateFlow(KucpIntegrationCatalog.computeCatalogStats())
    val catalogReport: StateFlow<CatalogRefreshReport> = _catalogReport.asStateFlow()

    val connectors: StateFlow<List<ConnectorDefinition>> = toolRegistry.connectors
    val tools: StateFlow<List<ToolDescriptor>> = toolRegistry.tools
    val health: StateFlow<Map<String, ConnectorHealth>> = toolRegistry.health

    init {
        registerAndroidNativeConnector()
        scope.launch { reloadFromDatabase() }
    }

    private fun registerAndroidNativeConnector() {
        toolRegistry.registerConnectorAndTools(
            AndroidNativeIntegrationProvider.connectorDefinition(),
            AndroidNativeIntegrationProvider.defaultTools()
        )
    }

    suspend fun reloadFromDatabase() = registrySyncMutex.withLock {
        registerAndroidNativeConnector()
        val connEntities = dao.getAllConnectors()
        val allToolEntities = dao.getAllTools().groupBy { it.connectorId }
        for (ce in connEntities) {
            try {
                val def = json.decodeFromString<ConnectorDefinition>(ce.definitionJson)
                val tools = allToolEntities[ce.id].orEmpty().mapNotNull { te ->
                    try { json.decodeFromString<ToolDescriptor>(te.descriptorJson) } catch (_: Exception) { null }
                }
                toolRegistry.registerConnectorAndTools(def, tools)
            } catch (_: Exception) {}
        }

        val pluginEntities = dao.getAllPlugins()
        _plugins.value = pluginEntities
        for (pe in pluginEntities) {
            try {
                val manifest = json.decodeFromString<PluginManifest>(pe.manifestJson)
                toolRegistry.registerConnectorAndTools(
                    pluginRuntime.syntheticConnectorFor(manifest, pe.enabled),
                    pluginRuntime.pluginToolsToDescriptors(manifest, pe.enabled)
                )
            } catch (_: Exception) {}
        }

        _webhooks.value = dao.getAllWebhooks().mapNotNull { we ->
            try { json.decodeFromString<WebhookSubscription>(we.subscriptionJson) } catch (_: Exception) { null }
        }
        _workflows.value = dao.getAllWorkflows().mapNotNull { wf ->
            try { json.decodeFromString<WorkflowDefinition>(wf.definitionJson) } catch (_: Exception) { null }
        }
        _workflowRuns.value = dao.getRecentWorkflowRuns().mapNotNull { wr ->
            try { json.decodeFromString<WorkflowExecutionRecord>(wr.recordJson) } catch (_: Exception) { null }
        }
        _auditEvents.value = dao.getRecentAuditEvents().map { ae ->
            AuditEvent(
                id = ae.id,
                timestampMs = ae.timestampMs,
                connectorId = ae.connectorId,
                toolId = ae.toolId,
                action = ae.action,
                outcome = ae.outcome,
                riskLevel = ToolRiskLevel.entries.firstOrNull { it.name == ae.riskLevel } ?: ToolRiskLevel.LOW,
                detailRedacted = ae.detailRedacted
            )
        }
    }

    // =========================================================================
    // CONNECTOR LIFECYCLE, TEMPLATE INSTANTIATION & ROLLBACK
    // =========================================================================

    fun getMarketplaceTemplates(): List<MarketplaceTemplate> = KucpIntegrationCatalog.templates

    /**
     * Safely refreshes catalog metadata against installed connectors without deleting
     * user-connected integrations, wiping credentials, or resetting Connected status.
     */
    suspend fun refreshCatalogMetadata(): CatalogRefreshReport {
        val installed = toolRegistry.connectors.value
        val updatedIds = mutableListOf<String>()
        var preservedConnected = 0

        for (conn in installed) {
            if (conn.status == ConnectorStatus.CONNECTED || conn.status == ConnectorStatus.PARTIALLY_AUTHORIZED) {
                preservedConnected++
            }
            val tpl = KucpIntegrationCatalog.findTemplateByCanonicalId(conn.canonicalServiceId)
                ?: KucpIntegrationCatalog.templates.firstOrNull { conn.id.contains(it.id.removePrefix("tpl_"), ignoreCase = true) }
                ?: continue

            val mergedAuth = conn.authConfig.copy(
                oauthAuthorizeUrl = conn.authConfig.oauthAuthorizeUrl.ifBlank { tpl.oauthAuthorizeUrl },
                oauthTokenUrl = conn.authConfig.oauthTokenUrl.ifBlank { tpl.oauthTokenUrl },
                oauthRevokeUrl = conn.authConfig.oauthRevokeUrl.ifBlank { tpl.oauthRevokeUrl }
            )
            val refreshed = conn.copy(
                canonicalServiceId = conn.canonicalServiceId.ifBlank { tpl.canonicalServiceId },
                ecosystemSources = tpl.ecosystemSources.ifEmpty { conn.ecosystemSources },
                docsUrl = tpl.docsUrl.ifBlank { conn.docsUrl },
                restrictions = tpl.restrictions.ifBlank { conn.restrictions },
                webhookSupported = tpl.webhookSupported || conn.webhookSupported,
                implementationState = tpl.implementationState,
                authConfig = mergedAuth
            )
            if (refreshed != conn) {
                saveConnectorAndTools(refreshed, toolRegistry.getToolsForConnector(conn.id))
                updatedIds += conn.id
            }
        }

        val baseStats = KucpIntegrationCatalog.computeCatalogStats().copy(
            updatedIds = updatedIds,
            preservedConnectedCount = preservedConnected
        )
        _catalogReport.value = baseStats
        recordAudit(
            "catalog",
            "",
            "CATALOG_METADATA_REFRESH",
            "SUCCESS",
            ToolRiskLevel.LOW,
            "Catalog synced: ${baseStats.uniqueCanonicalCount} canonical services, ${updatedIds.size} updated, $preservedConnected active connections preserved"
        )
        return baseStats
    }

    suspend fun installMarketplaceTemplate(
        template: MarketplaceTemplate,
        customBaseUrl: String = template.defaultBaseUrl,
        allowLocalDev: Boolean = template.id == "tpl_home_assistant"
    ): ConnectorDefinition {
        val (conn, tools) = KucpIntegrationCatalog.instantiateFromTemplate(template, customBaseUrl, allowLocalDev)
        saveConnectorAndTools(conn, tools)
        recordAudit(conn.id, "", "CONNECTOR_INSTALL_TEMPLATE", "SUCCESS", ToolRiskLevel.LOW, "Added template ${template.name}")
        return conn
    }

    suspend fun saveConnectorAndTools(
        connector: ConnectorDefinition,
        tools: List<ToolDescriptor>
    ) = registrySyncMutex.withLock {
        val prev = toolRegistry.getConnector(connector.id)
        val rollbackJson = if (prev != null) {
            json.encodeToString(prev.copy(rollbackSnapshotJson = ""))
        } else connector.rollbackSnapshotJson

        val updatedConn = connector.copy(
            capabilities = tools.map { it.name },
            rollbackSnapshotJson = rollbackJson,
            updatedAtMs = System.currentTimeMillis()
        )
        dao.upsertConnector(
            KucpConnectorEntity(
                id = updatedConn.id,
                name = updatedConn.name,
                provider = updatedConn.provider,
                protocol = updatedConn.protocol.name,
                category = updatedConn.category.name,
                status = updatedConn.status.name,
                version = updatedConn.version,
                enabled = updatedConn.enabled,
                definitionJson = json.encodeToString(updatedConn),
                updatedAtMs = updatedConn.updatedAtMs
            )
        )
        dao.deleteToolsForConnector(updatedConn.id)
        dao.upsertTools(
            tools.map { t ->
                KucpToolEntity(
                    toolId = t.toolId,
                    connectorId = updatedConn.id,
                    name = t.name,
                    protocol = t.protocol.name,
                    riskLevel = t.riskLevel.name,
                    descriptorJson = json.encodeToString(t),
                    updatedAtMs = updatedConn.updatedAtMs
                )
            }
        )
        toolRegistry.registerConnectorAndTools(updatedConn, tools)
    }

    suspend fun rollbackConnectorVersion(connectorId: String): Result<ConnectorDefinition> {
        val current = toolRegistry.getConnector(connectorId)
            ?: return Result.failure(IllegalArgumentException("Connector not found."))
        if (current.rollbackSnapshotJson.isBlank()) {
            return Result.failure(IllegalStateException("No previous version snapshot available for ${current.name}."))
        }
        return try {
            val restored = json.decodeFromString<ConnectorDefinition>(current.rollbackSnapshotJson)
                .copy(updatedAtMs = System.currentTimeMillis())
            val tools = toolRegistry.getToolsForConnector(connectorId)
            saveConnectorAndTools(restored, tools)
            recordAudit(connectorId, "", "CONNECTOR_ROLLBACK", "SUCCESS", ToolRiskLevel.MEDIUM, "Rolled back ${current.name} to v${restored.version}")
            Result.success(restored)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setConnectorEnabled(connectorId: String, enabled: Boolean) {
        val current = toolRegistry.getConnector(connectorId) ?: return
        val updated = current.copy(
            enabled = enabled,
            status = if (enabled) current.status else ConnectorStatus.DISCONNECTED
        )
        saveConnectorAndTools(updated, toolRegistry.getToolsForConnector(connectorId))
        recordAudit(connectorId, "", if (enabled) "CONNECTOR_ENABLE" else "CONNECTOR_DISABLE", "SUCCESS", ToolRiskLevel.LOW, current.name)
    }

    suspend fun deleteConnector(connectorId: String) {
        if (connectorId == AndroidNativeIntegrationProvider.CONNECTOR_ID) return
        registrySyncMutex.withLock {
            KucpCredentialVault.revokeAllForConnector(appContext, connectorId)
            toolRegistry.removeConnector(connectorId)
            dao.deleteToolsForConnector(connectorId)
            dao.deleteConnector(connectorId)
        }
        recordAudit(connectorId, "", "CONNECTOR_DELETE", "SUCCESS", ToolRiskLevel.MEDIUM, "Removed connector & wiped vault secrets")
    }

    // =========================================================================
    // CREDENTIAL VAULT & OAUTH2 / PKCE
    // =========================================================================

    suspend fun saveConnectorCredential(connectorId: String, slot: String, secretValue: String): Boolean {
        val saved = KucpCredentialVault.saveCredential(appContext, connectorId, slot, secretValue)
        if (saved) {
            toolRegistry.getConnector(connectorId)?.let { c ->
                val updated = c.copy(authConfig = c.authConfig.copy(lastRotatedAtMs = System.currentTimeMillis()))
                saveConnectorAndTools(updated, toolRegistry.getToolsForConnector(connectorId))
            }
            recordAudit(connectorId, "", "CREDENTIAL_SAVED", "SUCCESS", ToolRiskLevel.MEDIUM, "Updated slot '$slot' (encrypted)")
        }
        return saved
    }

    suspend fun revokeConnectorCredential(connectorId: String, slot: String) {
        KucpCredentialVault.revokeCredential(appContext, connectorId, slot)
        toolRegistry.getConnector(connectorId)?.let { c ->
            saveConnectorAndTools(c.copy(status = ConnectorStatus.REVOKED), toolRegistry.getToolsForConnector(connectorId))
        }
        recordAudit(connectorId, "", "CREDENTIAL_REVOKED", "SUCCESS", ToolRiskLevel.HIGH, "Revoked credential slot '$slot'")
    }

    fun getMaskedCredential(connectorId: String, slot: String): String =
        KucpCredentialVault.getMaskedCredential(appContext, connectorId, slot)

    fun listVaultEntriesMasked(): List<Triple<String, String, String>> =
        KucpCredentialVault.listAllVaultEntriesMasked(appContext)

    fun startOAuthPkceFlow(connectorId: String): Result<Pair<PkceSession, Uri>> {
        val conn = toolRegistry.getConnector(connectorId)
            ?: return Result.failure(IllegalArgumentException("Connector not found."))
        val session = oauthManager.createPkceSession(conn)
        return oauthManager.buildAuthorizationUri(conn, session).map { uri ->
            scope.launch {
                saveConnectorAndTools(
                    conn.copy(status = ConnectorStatus.AWAITING_AUTHORIZATION),
                    toolRegistry.getToolsForConnector(connectorId)
                )
            }
            session to uri
        }
    }

    suspend fun completeOAuthCallback(callbackUri: Uri): OAuthTokenResult {
        val res = oauthManager.handleCallbackAndExchangeCode(appContext, callbackUri) { cid -> toolRegistry.getConnector(cid) }
        val resolvedConnId = res.connectorId.ifBlank { callbackUri.getQueryParameter("state").orEmpty() }
        if (res.success && res.connectorId.isNotBlank()) {
            toolRegistry.getConnector(res.connectorId)?.let { conn ->
                val now = System.currentTimeMillis()
                val requestedScopes = conn.authConfig.scopes
                val partial = requestedScopes.isNotEmpty() &&
                    res.grantedScopes.isNotEmpty() &&
                    !res.grantedScopes.containsAll(requestedScopes)
                val newStatus = if (partial) ConnectorStatus.PARTIALLY_AUTHORIZED else ConnectorStatus.CONNECTED
                val updated = conn.copy(
                    status = newStatus,
                    grantedScopes = res.grantedScopes.ifEmpty { conn.grantedScopes },
                    connectedAccountLabel = res.accountLabel.ifBlank { conn.connectedAccountLabel },
                    lastSyncedAtMs = now,
                    authConfig = conn.authConfig.copy(
                        tokenExpiresAtMs = if (res.expiresInSec > 0L) now + res.expiresInSec * 1000L else conn.authConfig.tokenExpiresAtMs,
                        hasRefreshToken = res.hasRefreshToken || conn.authConfig.hasRefreshToken,
                        lastRotatedAtMs = now
                    )
                )
                saveConnectorAndTools(updated, toolRegistry.getToolsForConnector(conn.id))
                toolRegistry.updateHealth(conn.id, true, 1L, newStatus)
            }
        }
        recordAudit(
            resolvedConnId.ifBlank { "oauth" },
            "",
            "OAUTH_PKCE_EXCHANGE",
            if (res.success) "SUCCESS" else "FAILED",
            ToolRiskLevel.MEDIUM,
            res.error ?: "OAuth PKCE token stored in vault (${res.grantedScopes.size} scopes)"
        )
        return res
    }

    /**
     * Revokes OAuth tokens at the provider (when oauthRevokeUrl is supported), wipes all
     * encrypted credentials for the connector, clears account identity, and marks REVOKED.
     */
    suspend fun disconnectAndRevokeAccount(connectorId: String): Result<Unit> {
        val conn = toolRegistry.getConnector(connectorId)
            ?: return Result.failure(IllegalArgumentException("Connector '$connectorId' not found."))
        val revokeRes = oauthManager.revokeProviderToken(appContext, conn)
        KucpCredentialVault.revokeAllForConnector(appContext, connectorId)
        val updated = conn.copy(
            status = ConnectorStatus.REVOKED,
            connectedAccountLabel = "",
            grantedScopes = emptyList(),
            authConfig = conn.authConfig.copy(
                tokenExpiresAtMs = 0L,
                hasRefreshToken = false
            )
        )
        saveConnectorAndTools(updated, toolRegistry.getToolsForConnector(connectorId))
        toolRegistry.updateHealth(connectorId, false, 0L, ConnectorStatus.REVOKED, "Account disconnected & credentials revoked")
        recordAudit(
            connectorId,
            "",
            "ACCOUNT_DISCONNECT_REVOKE",
            if (revokeRes.isSuccess) "SUCCESS" else "LOCAL_WIPED_REMOTE_WARN",
            ToolRiskLevel.HIGH,
            revokeRes.exceptionOrNull()?.message ?: "Revoked provider token and cleared local credential vault"
        )
        return revokeRes
    }

    // =========================================================================
    // PROTOCOL DISCOVERY, CONNECTION VALIDATION & TOOL EXECUTION
    // =========================================================================

    fun registerLocalMcpBridge(bridgeId: String, handler: LocalMcpBridgeHandler) {
        mcpRuntime.registerLocalBridge(bridgeId, handler)
    }

    suspend fun connectAndValidateConnector(connectorId: String): Result<ConnectorHealth> {
        val conn = toolRegistry.getConnector(connectorId)
            ?: return Result.failure(IllegalArgumentException("Connector '$connectorId' not found."))

        return when (conn.protocol) {
            ConnectorProtocol.ANDROID_NATIVE -> {
                toolRegistry.updateHealth(connectorId, true, 1L, ConnectorStatus.CONNECTED)
                Result.success(toolRegistry.health.value[connectorId] ?: ConnectorHealth(connectorId, ConnectorStatus.CONNECTED))
            }
            ConnectorProtocol.MCP -> {
                val initRes = mcpRuntime.initializeSession(appContext, conn)
                if (initRes.isFailure) {
                    val err = SecretRedactor.redact(initRes.exceptionOrNull()?.message)
                    toolRegistry.updateHealth(connectorId, false, 0L, ConnectorStatus.ERROR, err)
                    recordAudit(connectorId, "", "MCP_CONNECT", "FAILED", ToolRiskLevel.LOW, err)
                    return Result.failure(initRes.exceptionOrNull()!!)
                }
                val started = System.currentTimeMillis()
                val toolsRes = mcpRuntime.listTools(appContext, conn)
                val latency = (System.currentTimeMillis() - started).coerceAtLeast(1L)
                if (toolsRes.isSuccess) {
                    val discoveredTools = toolsRes.getOrThrow()
                    saveConnectorAndTools(conn.copy(status = ConnectorStatus.CONNECTED), discoveredTools)
                    toolRegistry.updateHealth(connectorId, true, latency, ConnectorStatus.CONNECTED)
                    recordAudit(connectorId, "", "MCP_DISCOVER_TOOLS", "SUCCESS", ToolRiskLevel.LOW, "Synced ${discoveredTools.size} MCP tools")
                    Result.success(toolRegistry.health.value[connectorId]!!)
                } else {
                    val err = SecretRedactor.redact(toolsRes.exceptionOrNull()?.message)
                    toolRegistry.updateHealth(connectorId, false, latency, ConnectorStatus.ERROR, err)
                    Result.failure(toolsRes.exceptionOrNull()!!)
                }
            }
            ConnectorProtocol.GRAPHQL -> {
                val started = System.currentTimeMillis()
                val intro = graphqlRuntime.introspectSchema(appContext, conn)
                val latency = (System.currentTimeMillis() - started).coerceAtLeast(1L)
                if (intro.isSuccess && intro.getOrThrow().isNotEmpty()) {
                    val discovered = intro.getOrThrow()
                    saveConnectorAndTools(conn.copy(status = ConnectorStatus.CONNECTED), discovered)
                    toolRegistry.updateHealth(connectorId, true, latency, ConnectorStatus.CONNECTED)
                    recordAudit(connectorId, "", "GRAPHQL_INTROSPECT", "SUCCESS", ToolRiskLevel.LOW, "Discovered ${discovered.size} GraphQL ops")
                    Result.success(toolRegistry.health.value[connectorId]!!)
                } else {
                    val ping = graphqlRuntime.healthCheck(appContext, conn)
                    if (ping.isSuccess) {
                        saveConnectorAndTools(conn.copy(status = ConnectorStatus.CONNECTED), toolRegistry.getToolsForConnector(connectorId))
                        toolRegistry.updateHealth(connectorId, true, ping.getOrThrow(), ConnectorStatus.CONNECTED)
                        Result.success(toolRegistry.health.value[connectorId]!!)
                    } else {
                        val err = SecretRedactor.redact(ping.exceptionOrNull()?.message)
                        toolRegistry.updateHealth(connectorId, false, latency, ConnectorStatus.ERROR, err)
                        Result.failure(ping.exceptionOrNull()!!)
                    }
                }
            }
            ConnectorProtocol.A2A -> {
                val started = System.currentTimeMillis()
                val cardRes = realtimeRuntime.discoverA2aAgentCard(conn)
                val latency = (System.currentTimeMillis() - started).coerceAtLeast(1L)
                cardRes.fold(
                    onSuccess = { card ->
                        val updatedConn = conn.copy(
                            name = card.optString("name").ifBlank { conn.name },
                            description = card.optString("description").ifBlank { conn.description },
                            status = ConnectorStatus.CONNECTED
                        )
                        saveConnectorAndTools(updatedConn, toolRegistry.getToolsForConnector(connectorId))
                        toolRegistry.updateHealth(connectorId, true, latency, ConnectorStatus.CONNECTED)
                        recordAudit(connectorId, "", "A2A_DISCOVER", "SUCCESS", ToolRiskLevel.LOW, "Verified A2A Agent Card")
                        Result.success(toolRegistry.health.value[connectorId]!!)
                    },
                    onFailure = { err ->
                        val msg = SecretRedactor.redact(err.message)
                        toolRegistry.updateHealth(connectorId, false, latency, ConnectorStatus.ERROR, msg)
                        Result.failure(err)
                    }
                )
            }
            else -> {
                val ping = restRuntime.healthCheck(appContext, conn)
                ping.fold(
                    onSuccess = { lat ->
                        saveConnectorAndTools(conn.copy(status = ConnectorStatus.CONNECTED), toolRegistry.getToolsForConnector(connectorId))
                        toolRegistry.updateHealth(connectorId, true, lat, ConnectorStatus.CONNECTED)
                        recordAudit(connectorId, "", "CONNECTOR_PING", "SUCCESS", ToolRiskLevel.LOW, "HTTP check OK (${lat}ms)")
                        Result.success(toolRegistry.health.value[connectorId]!!)
                    },
                    onFailure = { err ->
                        val msg = SecretRedactor.redact(err.message)
                        toolRegistry.updateHealth(connectorId, false, 0L, ConnectorStatus.ERROR, msg)
                        recordAudit(connectorId, "", "CONNECTOR_PING", "FAILED", ToolRiskLevel.LOW, msg)
                        Result.failure(err)
                    }
                )
            }
        }
    }

    suspend fun executeTool(request: ToolInvocationRequest): ToolInvocationResult {
        val tool = toolRegistry.getTool(request.toolId)
            ?: return ToolInvocationResult(
                requestId = request.requestId,
                connectorId = request.connectorId,
                toolId = request.toolId,
                success = false,
                errorCode = "TOOL_NOT_FOUND",
                errorMessage = "Tool '${request.toolId}' is not registered."
            )
        val connector = toolRegistry.getConnector(tool.connectorId)
            ?: return ToolInvocationResult(
                requestId = request.requestId,
                connectorId = tool.connectorId,
                toolId = tool.toolId,
                success = false,
                errorCode = "CONNECTOR_NOT_FOUND",
                errorMessage = "Connector '${tool.connectorId}' is not registered."
            )

        // Argument schema validation
        val issues = toolRegistry.validateArguments(tool, request.arguments)
        if (issues.isNotEmpty()) {
            val msg = issues.joinToString("; ") { it.message }
            recordAudit(connector.id, tool.toolId, "TOOL_VALIDATION_FAILED", "BLOCKED", tool.riskLevel, msg)
            return ToolInvocationResult(
                requestId = request.requestId,
                connectorId = connector.id,
                toolId = tool.toolId,
                success = false,
                errorCode = "VALIDATION_ERROR",
                errorMessage = msg
            )
        }

        // Security & policy gate (rate limit, circuit breaker, SSRF, allowlist, confirmation)
        val isLocalBridge = connector.protocol == ConnectorProtocol.MCP && connector.baseUrl.startsWith("local://")
        val targetUrl = if (connector.protocol == ConnectorProtocol.ANDROID_NATIVE || isLocalBridge) null else connector.baseUrl
        val policy = securityGateway.checkExecutionPolicy(connector, tool, targetUrl, request.userConfirmed)
        if (!policy.allowed) {
            val needsConfirm = policy.reason.startsWith("CONFIRMATION_REQUIRED")
            recordAudit(connector.id, tool.toolId, "TOOL_POLICY_GATE", if (needsConfirm) "CONFIRM_NEEDED" else "BLOCKED", tool.riskLevel, policy.reason)
            return ToolInvocationResult(
                requestId = request.requestId,
                connectorId = connector.id,
                toolId = tool.toolId,
                success = false,
                errorCode = if (needsConfirm) "CONFIRMATION_REQUIRED" else "POLICY_BLOCKED",
                errorMessage = policy.reason,
                requiresUserConfirmation = needsConfirm,
                confirmationPrompt = if (needsConfirm) "Allow '${connector.name}' -> '${tool.name}' (${tool.riskLevel.label})?" else null
            )
        }

        if (connector.status == ConnectorStatus.UNSUPPORTED) {
            return ToolInvocationResult(
                requestId = request.requestId,
                connectorId = connector.id,
                toolId = tool.toolId,
                success = false,
                errorCode = "UNSUPPORTED_PROVIDER",
                errorMessage = connector.restrictions.ifBlank { "${connector.name} has no official public API for third-party apps." }
            )
        }

        suspend fun dispatchProtocolCall(activeConnector: ConnectorDefinition): ToolInvocationResult {
            return when (activeConnector.protocol) {
                ConnectorProtocol.ANDROID_NATIVE -> AndroidNativeIntegrationProvider.execute(appContext, tool, request)
                ConnectorProtocol.MCP -> mcpRuntime.execute(appContext, activeConnector, tool, request)
                ConnectorProtocol.GRAPHQL -> graphqlRuntime.execute(appContext, activeConnector, tool, request)
                ConnectorProtocol.PLUGIN -> {
                    val pEntity = _plugins.value.firstOrNull { "plugin_${it.pluginId}" == activeConnector.id }
                    if (pEntity == null) {
                        ToolInvocationResult(request.requestId, activeConnector.id, tool.toolId, false, errorCode = "PLUGIN_MISSING", errorMessage = "Plugin not found.")
                    } else {
                        val manifest = json.decodeFromString<PluginManifest>(pEntity.manifestJson)
                        pluginRuntime.executePluginTool(manifest, tool, request)
                    }
                }
                ConnectorProtocol.WEBHOOK, ConnectorProtocol.WEBSOCKET, ConnectorProtocol.A2A, ConnectorProtocol.GRPC, ConnectorProtocol.CUSTOM_SDK ->
                    realtimeRuntime.execute(appContext, activeConnector, tool, request)
                ConnectorProtocol.REST, ConnectorProtocol.OPENAPI, ConnectorProtocol.OAUTH2_OIDC ->
                    restRuntime.execute(appContext, activeConnector, tool, request)
            }
        }

        var result = dispatchProtocolCall(connector)

        // Automatic OAuth refresh retry on HTTP 401 when a refresh token is available
        if (!result.success && result.statusCode == 401 && connector.authConfig.hasRefreshToken) {
            val refreshRes = oauthManager.refreshTokenIfPossible(appContext, connector)
            if (refreshRes.success) {
                val now = System.currentTimeMillis()
                val refreshedConn = connector.copy(
                    status = ConnectorStatus.CONNECTED,
                    lastSyncedAtMs = now,
                    authConfig = connector.authConfig.copy(
                        tokenExpiresAtMs = now + refreshRes.expiresInSec * 1000L,
                        lastRotatedAtMs = now
                    )
                )
                saveConnectorAndTools(refreshedConn, toolRegistry.getToolsForConnector(connector.id))
                result = dispatchProtocolCall(refreshedConn)
            } else {
                saveConnectorAndTools(
                    connector.copy(status = ConnectorStatus.NEEDS_RECONNECTION),
                    toolRegistry.getToolsForConnector(connector.id)
                )
                toolRegistry.updateHealth(connector.id, false, result.durationMs, ConnectorStatus.NEEDS_RECONNECTION, refreshRes.error)
            }
        }

        securityGateway.recordOutcome(connector.id, result.success)
        val statusOverride = when {
            result.statusCode == 429 -> ConnectorStatus.RATE_LIMITED
            result.statusCode == 401 -> ConnectorStatus.NEEDS_RECONNECTION
            else -> null
        }
        toolRegistry.updateHealth(
            connectorId = connector.id,
            success = result.success,
            latencyMs = result.durationMs,
            statusOverride = statusOverride,
            error = result.errorMessage
        )
        recordAudit(
            connectorId = connector.id,
            toolId = tool.toolId,
            action = "TOOL_EXEC:${tool.name}",
            outcome = if (result.success) "SUCCESS" else (result.errorCode ?: "FAILED"),
            risk = tool.riskLevel,
            detail = SecretRedactor.redact(if (result.success) "HTTP ${result.statusCode} in ${result.durationMs}ms" else result.errorMessage.orEmpty())
        )
        return result
    }

    // =========================================================================
    // CUSTOM CONNECTOR IMPORT / EXPORT, PLUGINS, WEBHOOKS & WORKFLOWS
    // =========================================================================

    fun previewOpenApiImport(rawSpec: String, sourceUrl: String = "", allowLocalDev: Boolean = false): Result<ConnectorDraft> =
        OpenApiAndYamlParser.importSpec(rawSpec, sourceUrl, allowLocalDev)

    fun exportConnectorBundle(connectorId: String): String? {
        val c = toolRegistry.getConnector(connectorId) ?: return null
        return OpenApiAndYamlParser.exportConnectorBundleJson(c, toolRegistry.getToolsForConnector(connectorId))
    }

    fun exportConnectorAsOpenApi(connectorId: String): String? {
        val c = toolRegistry.getConnector(connectorId) ?: return null
        return OpenApiAndYamlParser.exportToOpenApiJson(c, toolRegistry.getToolsForConnector(connectorId))
    }

    suspend fun installPlugin(rawManifestJson: String, expectedSha256: String? = null): Result<PluginManifest> {
        val parsed = pluginRuntime.parseAndValidateManifest(rawManifestJson, expectedSha256)
        if (parsed.isFailure) {
            recordAudit("plugin", "", "PLUGIN_INSTALL", "REJECTED", ToolRiskLevel.HIGH, parsed.exceptionOrNull()?.message.orEmpty())
            return parsed
        }
        val manifest = parsed.getOrThrow()
        registrySyncMutex.withLock {
            val prev = _plugins.value.firstOrNull { it.pluginId == manifest.pluginId }
            val entity = KucpPluginEntity(
                pluginId = manifest.pluginId,
                name = manifest.name,
                version = manifest.version,
                sha256 = manifest.sha256,
                enabled = true,
                manifestJson = json.encodeToString(manifest),
                previousManifestJson = prev?.manifestJson.orEmpty(),
                installedAtMs = System.currentTimeMillis()
            )
            dao.upsertPlugin(entity)
            toolRegistry.registerConnectorAndTools(
                pluginRuntime.syntheticConnectorFor(manifest, true),
                pluginRuntime.pluginToolsToDescriptors(manifest, true)
            )
            _plugins.value = dao.getAllPlugins()
        }
        recordAudit("plugin_${manifest.pluginId}", "", "PLUGIN_INSTALL", "SUCCESS", ToolRiskLevel.MEDIUM, "Installed ${manifest.name} v${manifest.version}")
        return Result.success(manifest)
    }

    suspend fun rollbackPlugin(pluginId: String): Result<PluginManifest> {
        val existing = dao.getAllPlugins().firstOrNull { it.pluginId == pluginId }
            ?: return Result.failure(IllegalArgumentException("Plugin '$pluginId' not installed."))
        if (existing.previousManifestJson.isBlank()) {
            return Result.failure(IllegalStateException("No previous plugin version to roll back to."))
        }
        return installPlugin(existing.previousManifestJson)
    }

    suspend fun togglePluginEnabled(pluginId: String, enabled: Boolean) = registrySyncMutex.withLock {
        val existing = dao.getAllPlugins().firstOrNull { it.pluginId == pluginId } ?: return@withLock
        val updated = existing.copy(enabled = enabled)
        dao.upsertPlugin(updated)
        val manifest = json.decodeFromString<PluginManifest>(updated.manifestJson)
        toolRegistry.registerConnectorAndTools(
            pluginRuntime.syntheticConnectorFor(manifest, enabled),
            pluginRuntime.pluginToolsToDescriptors(manifest, enabled)
        )
        _plugins.value = dao.getAllPlugins()
    }

    suspend fun saveWebhookSubscription(sub: WebhookSubscription) {
        dao.upsertWebhook(KucpWebhookEntity(sub.id, sub.connectorId, sub.name, sub.active, json.encodeToString(sub), System.currentTimeMillis()))
        _webhooks.value = dao.getAllWebhooks().mapNotNull { try { json.decodeFromString<WebhookSubscription>(it.subscriptionJson) } catch (_: Exception) { null } }
    }

    suspend fun processIncomingWebhookEvent(
        subscriptionId: String,
        eventType: String,
        payload: String,
        signatureHeader: String
    ): Boolean {
        val sub = _webhooks.value.firstOrNull { it.id == subscriptionId } ?: return false
        val secret = KucpCredentialVault.getCredential(appContext, sub.connectorId, "webhook_secret")
        val sigOk = if (secret.isBlank()) true else realtimeRuntime.verifyWebhookSignature(secret, payload, signatureHeader)
        val rec = WebhookEventRecord(
            id = UUID.randomUUID().toString(),
            subscriptionId = subscriptionId,
            eventType = eventType,
            signatureValid = sigOk,
            payloadExcerpt = SecretRedactor.redact(payload.take(280))
        )
        _webhookEvents.value = (listOf(rec) + _webhookEvents.value).take(50)
        recordAudit(sub.connectorId, subscriptionId, "WEBHOOK_EVENT:$eventType", if (sigOk) "VERIFIED" else "SIG_INVALID", ToolRiskLevel.MEDIUM, rec.payloadExcerpt)
        if (sigOk && sub.active) {
            saveWebhookSubscription(sub.copy(lastTriggeredAtMs = System.currentTimeMillis(), deliveryCount = sub.deliveryCount + 1))
            _workflows.value.filter { it.enabled && it.triggerType == "WEBHOOK" && it.webhookTriggerId == subscriptionId }
                .forEach { wf -> runWorkflow(wf.id, mapOf("eventType" to eventType, "payload" to payload)) }
        }
        return sigOk
    }

    suspend fun saveWorkflow(workflow: WorkflowDefinition) {
        val updated = workflow.copy(updatedAtMs = System.currentTimeMillis())
        dao.upsertWorkflow(KucpWorkflowEntity(updated.id, updated.name, updated.enabled, json.encodeToString(updated), updated.updatedAtMs))
        _workflows.value = dao.getAllWorkflows().mapNotNull { try { json.decodeFromString<WorkflowDefinition>(it.definitionJson) } catch (_: Exception) { null } }
        recordAudit("workflow", updated.id, "WORKFLOW_SAVED", "SUCCESS", ToolRiskLevel.LOW, updated.name)
    }

    suspend fun deleteWorkflow(workflowId: String) {
        dao.deleteWorkflow(workflowId)
        _workflows.value = dao.getAllWorkflows().mapNotNull { try { json.decodeFromString<WorkflowDefinition>(it.definitionJson) } catch (_: Exception) { null } }
    }

    suspend fun runWorkflow(workflowId: String, initialInputs: Map<String, String> = emptyMap()): WorkflowExecutionRecord {
        val wf = _workflows.value.firstOrNull { it.id == workflowId }
            ?: return WorkflowExecutionRecord(UUID.randomUUID().toString(), workflowId, workflowId, "FAILED", errorSummary = "Workflow not found.")
        val rec = workflowEngine.executeWorkflow(wf, initialInputs)
        dao.upsertWorkflowRun(
            KucpWorkflowRunEntity(rec.runId, rec.workflowId, rec.status, json.encodeToString(rec), rec.startedAtMs, rec.finishedAtMs)
        )
        _workflowRuns.value = dao.getRecentWorkflowRuns().mapNotNull { try { json.decodeFromString<WorkflowExecutionRecord>(it.recordJson) } catch (_: Exception) { null } }
        recordAudit("workflow", wf.id, "WORKFLOW_RUN", rec.status, ToolRiskLevel.MEDIUM, rec.errorSummary ?: "${rec.stepResults.size} steps finished")
        return rec
    }

    fun inspectAndroidPermissions(): List<AndroidCapabilityPermissionStatus> =
        AndroidNativeIntegrationProvider.inspectPermissionsMatrix(appContext)

    suspend fun searchInternetRegistries(query: String): Result<List<DiscoveryCandidate>> =
        discoveryEngine.searchPublicRegistries(query)

    suspend fun recordAudit(
        connectorId: String,
        toolId: String,
        action: String,
        outcome: String,
        risk: ToolRiskLevel,
        detail: String
    ) {
        val ev = AuditEvent(
            id = UUID.randomUUID().toString(),
            timestampMs = System.currentTimeMillis(),
            connectorId = connectorId,
            toolId = toolId,
            action = action,
            outcome = outcome,
            riskLevel = risk,
            detailRedacted = SecretRedactor.redact(detail).take(320)
        )
        try {
            dao.insertAuditEvent(
                KucpAuditEventEntity(
                    id = ev.id,
                    connectorId = ev.connectorId,
                    toolId = ev.toolId,
                    action = ev.action,
                    outcome = ev.outcome,
                    riskLevel = ev.riskLevel.name,
                    detailRedacted = ev.detailRedacted,
                    timestampMs = ev.timestampMs
                )
            )
            _auditEvents.value = (listOf(ev) + _auditEvents.value).take(200)
        } catch (_: Exception) {}
    }

    suspend fun clearAuditLog() {
        dao.clearAuditEvents()
        _auditEvents.value = emptyList()
    }
}
