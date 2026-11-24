package com.example.kucp.model

import kotlinx.serialization.Serializable

@Serializable
enum class ConnectorProtocol(val displayName: String) {
    MCP("MCP (Model Context Protocol)"),
    REST("REST API"),
    GRAPHQL("GraphQL"),
    OPENAPI("OpenAPI / Swagger"),
    OAUTH2_OIDC("OAuth 2.0 / OIDC"),
    WEBHOOK("Webhooks"),
    WEBSOCKET("WebSockets"),
    GRPC("gRPC"),
    A2A("A2A Agent Protocol"),
    PLUGIN("Sandboxed Plugin"),
    CUSTOM_SDK("Custom SDK Adapter"),
    ANDROID_NATIVE("Android Native Service")
}

@Serializable
enum class AuthSchemeType(val displayName: String) {
    NONE("No Authentication"),
    API_KEY("API Key"),
    BEARER_TOKEN("Bearer Token"),
    BASIC_AUTH("Basic Auth"),
    OAUTH2_PKCE("OAuth 2.0 + PKCE"),
    OIDC("OpenID Connect"),
    HMAC_WEBHOOK("HMAC-SHA256 Webhook Secret"),
    MTLS_HEADER_CERT("Certificate / Custom Header")
}

@Serializable
enum class EcosystemSource(val label: String) {
    CLAUDE_DIRECTORY("Claude Directory"),
    CHATGPT_CONNECTED_APPS("ChatGPT Connected Apps"),
    GEMINI_CONNECTED_APPS("Gemini Connected Apps"),
    OFFICIAL_MCP("Official Remote MCP"),
    OFFICIAL_API("Official Public API"),
    ANDROID_NATIVE("Android Native API")
}

@Serializable
enum class ConnectorImplementationState(val label: String) {
    IMPLEMENTED("Implemented"),
    SETUP_REQUIRED("Setup / Credentials Required"),
    ANDROID_INTENT_ONLY("Android Intent / OS API Only"),
    BLOCKED_NO_PUBLIC_API("Blocked (No Public API / Restricted)")
}

@Serializable
enum class ConnectorStatus(val label: String) {
    TEMPLATE("Template (Not Connected)"),
    AVAILABLE("Available"),
    SETUP_REQUIRED("Setup Required"),
    AWAITING_AUTHORIZATION("Awaiting Authorization"),
    CONNECTING("Connecting"),
    CONFIGURED("Configured (Untested)"),
    CONNECTED("Connected"),
    PARTIALLY_AUTHORIZED("Partially Authorized"),
    NEEDS_RECONNECTION("Needs Reconnection"),
    TEMPORARILY_UNAVAILABLE("Temporarily Unavailable"),
    DEGRADED("Degraded"),
    RATE_LIMITED("Rate Limited"),
    DISCONNECTED("Disconnected"),
    UNSUPPORTED("Unsupported"),
    REVOKED("Revoked"),
    ERROR("Error")
}

@Serializable
enum class ToolRiskLevel(val label: String) {
    LOW("Low (Read-Only)"),
    MEDIUM("Medium (Safe State)"),
    HIGH("High (External Write / Sensitive)"),
    CRITICAL("Critical (Destructive / System)")
}

@Serializable
enum class ConnectorCategory(val label: String) {
    GOOGLE_WORKSPACE("Google Workspace"),
    MICROSOFT_365("Microsoft 365"),
    COMMUNICATION("Communication"),
    PRODUCTIVITY("Productivity"),
    DEVELOPER_DEVOPS("Developer & DevOps"),
    CLOUD_STORAGE("Cloud & Storage"),
    AI_ML("AI & Agents"),
    AUTOMATION("Automation"),
    DESIGN_MEDIA("Design & Media"),
    BUSINESS_CRM_FINANCE("Business, CRM & Finance"),
    RESEARCH_KNOWLEDGE("Research & Knowledge"),
    DATABASES("Databases"),
    SMART_HOME("Smart Home & IoT"),
    ANDROID_SYSTEM("Android Native"),
    CUSTOM("Custom Connectors")
}

@Serializable
enum class ParamLocation {
    BODY, QUERY, PATH, HEADER
}

@Serializable
data class AuthConfig(
    val schemeType: AuthSchemeType = AuthSchemeType.NONE,
    val headerName: String = "Authorization",
    val queryParamName: String = "",
    val prefix: String = "Bearer ",
    val credentialSlot: String = "primary",
    val oauthAuthorizeUrl: String = "",
    val oauthTokenUrl: String = "",
    val oauthRevokeUrl: String = "",
    val oauthRedirectUri: String = "kavya://oauth/callback",
    val oauthClientId: String = "",
    val scopes: List<String> = emptyList(),
    val pkceRequired: Boolean = true,
    val oidcDiscoveryUrl: String = "",
    val tokenExpiresAtMs: Long = 0L,
    val hasRefreshToken: Boolean = false,
    val lastRotatedAtMs: Long = 0L
)

@Serializable
data class ToolParameterSchema(
    val name: String,
    val type: String = "string",
    val description: String = "",
    val required: Boolean = false,
    val defaultValue: String? = null,
    val enumValues: List<String> = emptyList(),
    val location: ParamLocation = ParamLocation.BODY
)

@Serializable
data class ToolDescriptor(
    val toolId: String,
    val connectorId: String,
    val name: String,
    val description: String,
    val protocol: ConnectorProtocol = ConnectorProtocol.REST,
    val methodOrOperation: String = "GET",
    val endpointPath: String = "",
    val inputSchema: List<ToolParameterSchema> = emptyList(),
    val outputSchemaDescription: String = "JSON object or text payload",
    val riskLevel: ToolRiskLevel = ToolRiskLevel.LOW,
    val requiresConfirmation: Boolean = false,
    val isIdempotent: Boolean = true,
    val requiredScopes: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val version: String = "1.0.0",
    val status: ConnectorStatus = ConnectorStatus.CONNECTED,
    val paginationCursorParam: String = ""
)

@Serializable
data class ConnectorDefinition(
    val id: String,
    val name: String,
    val provider: String,
    val description: String = "",
    val version: String = "1.0.0",
    val protocol: ConnectorProtocol = ConnectorProtocol.REST,
    val category: ConnectorCategory = ConnectorCategory.CUSTOM,
    val baseUrl: String = "",
    val status: ConnectorStatus = ConnectorStatus.CONFIGURED,
    val authConfig: AuthConfig = AuthConfig(),
    val capabilities: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val domainAllowlist: List<String> = emptyList(),
    val rateLimitPerMinute: Int = 60,
    val timeoutMs: Long = 15_000L,
    val isLocalDevAllowed: Boolean = false,
    val defaultHeaders: Map<String, String> = emptyMap(),
    val customProtocolAdapterId: String = "",
    val docsUrl: String = "",
    val enabled: Boolean = true,
    val rollbackSnapshotJson: String = "",
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val canonicalServiceId: String = "",
    val ecosystemSources: List<EcosystemSource> = emptyList(),
    val connectedAccountLabel: String = "",
    val grantedScopes: List<String> = emptyList(),
    val webhookSupported: Boolean = false,
    val pollingIntervalMinutes: Int = 0,
    val restrictions: String = "",
    val implementationState: ConnectorImplementationState = ConnectorImplementationState.SETUP_REQUIRED,
    val lastSyncedAtMs: Long = 0L
)

@Serializable
data class ToolInvocationRequest(
    val requestId: String,
    val connectorId: String,
    val toolId: String,
    val arguments: Map<String, String> = emptyMap(),
    val userConfirmed: Boolean = false,
    val idempotencyKey: String = ""
)

@Serializable
data class ToolInvocationResult(
    val requestId: String,
    val connectorId: String,
    val toolId: String,
    val success: Boolean,
    val statusCode: Int = 0,
    val outputData: String = "",
    val isolatedDataForLlm: String = "",
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val durationMs: Long = 0L,
    val nextCursor: String? = null,
    val requiresUserConfirmation: Boolean = false,
    val confirmationPrompt: String? = null
)

@Serializable
data class ConnectorHealth(
    val connectorId: String,
    val status: ConnectorStatus = ConnectorStatus.CONFIGURED,
    val latencyMs: Long = 0L,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val consecutiveFailures: Int = 0,
    val circuitOpenUntilMs: Long = 0L,
    val lastCheckedAtMs: Long = 0L,
    val lastError: String? = null
)

@Serializable
data class WorkflowCondition(
    val sourceStepId: String,
    val operator: String = "NOT_EMPTY", // EQUALS, CONTAINS, NOT_EMPTY
    val expectedValue: String = ""
)

@Serializable
data class WorkflowStep(
    val stepId: String,
    val title: String,
    val connectorId: String,
    val toolId: String,
    val inputMapping: Map<String, String> = emptyMap(),
    val dependsOn: List<String> = emptyList(),
    val condition: WorkflowCondition? = null,
    val loopArrayJsonPath: String = "",
    val maxLoopIterations: Int = 10,
    val retryIdempotentReads: Boolean = true,
    val requireConfirmation: Boolean = false
)

@Serializable
data class WorkflowDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    val triggerType: String = "MANUAL", // MANUAL, WEBHOOK, SCHEDULED
    val webhookTriggerId: String = "",
    val scheduleCronOrMinutes: String = "",
    val steps: List<WorkflowStep> = emptyList(),
    val enabled: Boolean = true,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Serializable
data class WorkflowStepResult(
    val stepId: String,
    val title: String,
    val status: String, // COMPLETED, SKIPPED, FAILED, CANCELLED, CONFIRMATION_REQUIRED
    val output: String = "",
    val error: String? = null,
    val durationMs: Long = 0L
)

@Serializable
data class WorkflowExecutionRecord(
    val runId: String,
    val workflowId: String,
    val workflowName: String,
    val status: String, // RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED, CONFIRMATION_REQUIRED
    val stepResults: List<WorkflowStepResult> = emptyList(),
    val startedAtMs: Long = System.currentTimeMillis(),
    val finishedAtMs: Long = 0L,
    val errorSummary: String? = null
)

@Serializable
data class PluginToolSpec(
    val name: String,
    val description: String,
    val httpMethod: String = "GET",
    val urlTemplate: String,
    val headers: Map<String, String> = emptyMap(),
    val bodyTemplateJson: String = "",
    val parameters: List<ToolParameterSchema> = emptyList(),
    val riskLevel: ToolRiskLevel = ToolRiskLevel.LOW
)

@Serializable
data class PluginManifest(
    val pluginId: String,
    val name: String,
    val version: String,
    val author: String = "Community",
    val description: String = "",
    val sha256: String = "",
    val declaredPermissions: List<String> = emptyList(),
    val allowedDomains: List<String> = emptyList(),
    val tools: List<PluginToolSpec> = emptyList()
)

@Serializable
data class WebhookSubscription(
    val id: String,
    val connectorId: String,
    val name: String,
    val targetUrl: String = "",
    val eventTypes: List<String> = emptyList(),
    val active: Boolean = true,
    val lastTriggeredAtMs: Long = 0L,
    val deliveryCount: Int = 0
)

@Serializable
data class WebhookEventRecord(
    val id: String,
    val subscriptionId: String,
    val eventType: String,
    val signatureValid: Boolean,
    val payloadExcerpt: String,
    val receivedAtMs: Long = System.currentTimeMillis()
)

@Serializable
data class AuditEvent(
    val id: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val connectorId: String,
    val toolId: String = "",
    val actor: String = "user",
    val action: String,
    val outcome: String,
    val riskLevel: ToolRiskLevel = ToolRiskLevel.LOW,
    val detailRedacted: String = ""
)

@Serializable
data class MarketplaceTemplate(
    val id: String,
    val name: String,
    val provider: String,
    val category: ConnectorCategory,
    val protocol: ConnectorProtocol,
    val description: String,
    val docsUrl: String,
    val defaultBaseUrl: String,
    val defaultAuthScheme: AuthSchemeType,
    val defaultScopes: List<String> = emptyList(),
    val defaultTools: List<ToolDescriptor> = emptyList(),
    val setupNotes: String = "",
    val canonicalServiceId: String = id,
    val ecosystemSources: List<EcosystemSource> = emptyList(),
    val readOperations: List<String> = emptyList(),
    val writeOperations: List<String> = emptyList(),
    val webhookSupported: Boolean = false,
    val pollingSupported: Boolean = true,
    val restrictions: String = "",
    val implementationState: ConnectorImplementationState = ConnectorImplementationState.SETUP_REQUIRED,
    val oauthAuthorizeUrl: String = "",
    val oauthTokenUrl: String = "",
    val oauthRevokeUrl: String = "",
    val defaultHeaders: Map<String, String> = emptyMap()
)

@Serializable
data class CatalogRefreshReport(
    val refreshedAtMs: Long = System.currentTimeMillis(),
    val claudeSourceCount: Int = 0,
    val chatGptSourceCount: Int = 0,
    val geminiSourceCount: Int = 0,
    val totalRawListings: Int = 0,
    val uniqueCanonicalCount: Int = 0,
    val implementedCount: Int = 0,
    val setupRequiredCount: Int = 0,
    val blockedCount: Int = 0,
    val addedIds: List<String> = emptyList(),
    val removedIds: List<String> = emptyList(),
    val updatedIds: List<String> = emptyList(),
    val preservedConnectedCount: Int = 0
)

@Serializable
data class DiscoveryCandidate(
    val id: String,
    val name: String,
    val sourceRegistry: String,
    val url: String,
    val specUrl: String = "",
    val protocol: ConnectorProtocol = ConnectorProtocol.REST,
    val description: String = "",
    val authRequirement: String = "Unknown",
    val license: String = "Unknown",
    val deprecationWarning: String? = null,
    val capabilities: List<String> = emptyList()
)

@Serializable
data class ConnectorDraft(
    val connector: ConnectorDefinition,
    val tools: List<ToolDescriptor>,
    val analysisWarnings: List<String> = emptyList(),
    val requiresUserApproval: Boolean = true
)
