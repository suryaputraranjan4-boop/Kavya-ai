package com.example.kucp.catalog

import com.example.kucp.model.AuthConfig
import com.example.kucp.model.AuthSchemeType
import com.example.kucp.model.ConnectorCategory
import com.example.kucp.model.ConnectorDefinition
import com.example.kucp.model.ConnectorProtocol
import com.example.kucp.model.ConnectorStatus
import com.example.kucp.model.MarketplaceTemplate
import com.example.kucp.model.ParamLocation
import com.example.kucp.model.ToolDescriptor
import com.example.kucp.model.ToolParameterSchema
import com.example.kucp.model.ToolRiskLevel

/**
 * Curated Marketplace catalog spanning major service ecosystems.
 * All templates remain strictly TEMPLATE (not connected) until the user configures real
 * credentials / OAuth client IDs and validates connectivity.
 */
object KucpIntegrationCatalog {

    val templates: List<MarketplaceTemplate> = listOf(
        MarketplaceTemplate(
            id = "tpl_github_rest",
            name = "GitHub REST API",
            provider = "GitHub",
            category = ConnectorCategory.DEVELOPER_DEVOPS,
            protocol = ConnectorProtocol.REST,
            description = "Repositories, issues, pull requests, and workflow dispatches via GitHub REST v3.",
            docsUrl = "https://docs.github.com/rest",
            defaultBaseUrl = "https://api.github.com",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            defaultScopes = listOf("repo", "read:user"),
            defaultTools = listOf(
                ToolDescriptor(
                    toolId = "tpl_github_rest:list_repos",
                    connectorId = "tpl_github_rest",
                    name = "list_user_repos",
                    description = "Lists repositories for the authenticated GitHub user.",
                    protocol = ConnectorProtocol.REST,
                    methodOrOperation = "GET",
                    endpointPath = "/user/repos",
                    inputSchema = listOf(
                        ToolParameterSchema("per_page", "integer", "Results per page", false, "10", location = ParamLocation.QUERY)
                    ),
                    riskLevel = ToolRiskLevel.LOW,
                    isIdempotent = true,
                    tags = listOf("github", "git", "repos", "code", "developer")
                ),
                ToolDescriptor(
                    toolId = "tpl_github_rest:create_issue",
                    connectorId = "tpl_github_rest",
                    name = "create_issue",
                    description = "Creates a new issue in a GitHub repository.",
                    protocol = ConnectorProtocol.REST,
                    methodOrOperation = "POST",
                    endpointPath = "/repos/{owner}/{repo}/issues",
                    inputSchema = listOf(
                        ToolParameterSchema("owner", "string", "Repository owner", true, location = ParamLocation.PATH),
                        ToolParameterSchema("repo", "string", "Repository name", true, location = ParamLocation.PATH),
                        ToolParameterSchema("title", "string", "Issue title", true, location = ParamLocation.BODY),
                        ToolParameterSchema("body", "string", "Issue description", false, location = ParamLocation.BODY)
                    ),
                    riskLevel = ToolRiskLevel.HIGH,
                    requiresConfirmation = true,
                    isIdempotent = false,
                    tags = listOf("github", "issue", "bug", "developer")
                )
            ),
            setupNotes = "Requires a GitHub Personal Access Token (PAT) stored in the Credential Vault."
        ),
        MarketplaceTemplate(
            id = "tpl_google_gmail",
            name = "Google Gmail API",
            provider = "Google Workspace",
            category = ConnectorCategory.GOOGLE_WORKSPACE,
            protocol = ConnectorProtocol.OAUTH2_OIDC,
            description = "Read messages, search threads, and draft/send emails via Gmail REST API.",
            docsUrl = "https://developers.google.com/gmail/api",
            defaultBaseUrl = "https://gmail.googleapis.com/gmail/v1",
            defaultAuthScheme = AuthSchemeType.OAUTH2_PKCE,
            defaultScopes = listOf("https://www.googleapis.com/auth/gmail.readonly"),
            defaultTools = listOf(
                ToolDescriptor(
                    toolId = "tpl_google_gmail:list_messages",
                    connectorId = "tpl_google_gmail",
                    name = "gmail_list_messages",
                    description = "Lists recent Gmail message IDs matching optional search query q.",
                    protocol = ConnectorProtocol.REST,
                    methodOrOperation = "GET",
                    endpointPath = "/users/me/messages",
                    inputSchema = listOf(
                        ToolParameterSchema("q", "string", "Gmail search filter", false, location = ParamLocation.QUERY),
                        ToolParameterSchema("maxResults", "integer", "Max results", false, "10", location = ParamLocation.QUERY)
                    ),
                    riskLevel = ToolRiskLevel.LOW,
                    tags = listOf("gmail", "email", "google", "inbox")
                )
            ),
            setupNotes = "Requires your Google OAuth 2.0 Android/PKCE Client ID."
        ),
        MarketplaceTemplate(
            id = "tpl_google_calendar",
            name = "Google Calendar",
            provider = "Google Workspace",
            category = ConnectorCategory.GOOGLE_WORKSPACE,
            protocol = ConnectorProtocol.OAUTH2_OIDC,
            description = "List calendars and query upcoming events via Google Calendar v3.",
            docsUrl = "https://developers.google.com/calendar/api",
            defaultBaseUrl = "https://www.googleapis.com/calendar/v3",
            defaultAuthScheme = AuthSchemeType.OAUTH2_PKCE,
            defaultScopes = listOf("https://www.googleapis.com/auth/calendar.readonly"),
            defaultTools = listOf(
                ToolDescriptor(
                    toolId = "tpl_google_calendar:list_events",
                    connectorId = "tpl_google_calendar",
                    name = "calendar_list_events",
                    description = "Lists upcoming events from primary Google Calendar.",
                    protocol = ConnectorProtocol.REST,
                    methodOrOperation = "GET",
                    endpointPath = "/calendars/primary/events",
                    inputSchema = listOf(
                        ToolParameterSchema("maxResults", "integer", "Max events", false, "10", location = ParamLocation.QUERY)
                    ),
                    riskLevel = ToolRiskLevel.LOW,
                    tags = listOf("calendar", "schedule", "meeting", "google")
                )
            ),
            setupNotes = "Configure OAuth 2.0 Client ID and authorize via PKCE."
        ),
        MarketplaceTemplate(
            id = "tpl_google_drive",
            name = "Google Drive",
            provider = "Google Workspace",
            category = ConnectorCategory.CLOUD_STORAGE,
            protocol = ConnectorProtocol.OAUTH2_OIDC,
            description = "Search files, folders, and Docs/Sheets metadata in Google Drive.",
            docsUrl = "https://developers.google.com/drive/api",
            defaultBaseUrl = "https://www.googleapis.com/drive/v3",
            defaultAuthScheme = AuthSchemeType.OAUTH2_PKCE,
            defaultScopes = listOf("https://www.googleapis.com/auth/drive.metadata.readonly"),
            defaultTools = listOf(
                ToolDescriptor(
                    toolId = "tpl_google_drive:list_files",
                    connectorId = "tpl_google_drive",
                    name = "drive_search_files",
                    description = "Searches files and folders in Google Drive.",
                    protocol = ConnectorProtocol.REST,
                    methodOrOperation = "GET",
                    endpointPath = "/files",
                    inputSchema = listOf(
                        ToolParameterSchema("q", "string", "Drive query filter", false, location = ParamLocation.QUERY),
                        ToolParameterSchema("pageSize", "integer", "Results per page", false, "10", location = ParamLocation.QUERY)
                    ),
                    riskLevel = ToolRiskLevel.LOW,
                    tags = listOf("drive", "docs", "sheets", "files", "google")
                )
            ),
            setupNotes = "Requires OAuth 2.0 + PKCE."
        ),
        MarketplaceTemplate(
            id = "tpl_microsoft_graph",
            name = "Microsoft Graph (365)",
            provider = "Microsoft",
            category = ConnectorCategory.MICROSOFT_365,
            protocol = ConnectorProtocol.OAUTH2_OIDC,
            description = "Outlook mail, Microsoft Calendar, Teams chats, and OneDrive files.",
            docsUrl = "https://learn.microsoft.com/graph/overview",
            defaultBaseUrl = "https://graph.microsoft.com/v1.0",
            defaultAuthScheme = AuthSchemeType.OAUTH2_PKCE,
            defaultScopes = listOf("User.Read", "Mail.Read", "Calendars.Read"),
            defaultTools = listOf(
                ToolDescriptor(
                    toolId = "tpl_microsoft_graph:me_profile",
                    connectorId = "tpl_microsoft_graph",
                    name = "ms_graph_me",
                    description = "Fetches signed-in Microsoft 365 user profile.",
                    protocol = ConnectorProtocol.REST,
                    methodOrOperation = "GET",
                    endpointPath = "/me",
                    riskLevel = ToolRiskLevel.LOW,
                    tags = listOf("microsoft", "outlook", "teams", "onedrive")
                )
            ),
            setupNotes = "Register an Entra ID Mobile/Native Client ID."
        ),
        MarketplaceTemplate(
            id = "tpl_slack_api",
            name = "Slack Web API",
            provider = "Slack",
            category = ConnectorCategory.COMMUNICATION,
            protocol = ConnectorProtocol.REST,
            description = "List channels and post workspace messages via Slack Web API.",
            docsUrl = "https://api.slack.com/web",
            defaultBaseUrl = "https://slack.com/api",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            defaultScopes = listOf("channels:read", "chat:write"),
            defaultTools = listOf(
                ToolDescriptor(
                    toolId = "tpl_slack_api:list_channels",
                    connectorId = "tpl_slack_api",
                    name = "slack_list_channels",
                    description = "Lists public Slack channels.",
                    protocol = ConnectorProtocol.REST,
                    methodOrOperation = "GET",
                    endpointPath = "/conversations.list",
                    riskLevel = ToolRiskLevel.LOW,
                    tags = listOf("slack", "chat", "channels", "message")
                ),
                ToolDescriptor(
                    toolId = "tpl_slack_api:post_message",
                    connectorId = "tpl_slack_api",
                    name = "slack_post_message",
                    description = "Posts a message to a Slack channel.",
                    protocol = ConnectorProtocol.REST,
                    methodOrOperation = "POST",
                    endpointPath = "/chat.postMessage",
                    inputSchema = listOf(
                        ToolParameterSchema("channel", "string", "Channel ID", true, location = ParamLocation.BODY),
                        ToolParameterSchema("text", "string", "Message text", true, location = ParamLocation.BODY)
                    ),
                    riskLevel = ToolRiskLevel.HIGH,
                    requiresConfirmation = true,
                    isIdempotent = false,
                    tags = listOf("slack", "send", "message")
                )
            ),
            setupNotes = "Requires Slack Bot User OAuth Token (xoxb-...)."
        ),
        MarketplaceTemplate(
            id = "tpl_discord_api",
            name = "Discord Bot / Webhook",
            provider = "Discord",
            category = ConnectorCategory.COMMUNICATION,
            protocol = ConnectorProtocol.REST,
            description = "Interact with Discord channels or send webhook alerts.",
            docsUrl = "https://discord.com/developers/docs/intro",
            defaultBaseUrl = "https://discord.com/api/v10",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            defaultTools = listOf(
                ToolDescriptor(
                    toolId = "tpl_discord_api:send_message",
                    connectorId = "tpl_discord_api",
                    name = "discord_send_channel_message",
                    description = "Sends a message to a Discord channel.",
                    protocol = ConnectorProtocol.REST,
                    methodOrOperation = "POST",
                    endpointPath = "/channels/{channel_id}/messages",
                    inputSchema = listOf(
                        ToolParameterSchema("channel_id", "string", "Discord channel ID", true, location = ParamLocation.PATH),
                        ToolParameterSchema("content", "string", "Message content", true, location = ParamLocation.BODY)
                    ),
                    riskLevel = ToolRiskLevel.HIGH,
                    requiresConfirmation = true,
                    isIdempotent = false,
                    tags = listOf("discord", "chat", "message")
                )
            ),
            setupNotes = "Use Bot token prefix 'Bot ' or switch to Webhook URL."
        ),
        MarketplaceTemplate(
            id = "tpl_notion_api",
            name = "Notion API",
            provider = "Notion",
            category = ConnectorCategory.PRODUCTIVITY,
            protocol = ConnectorProtocol.REST,
            description = "Search Notion pages/databases and append knowledge blocks.",
            docsUrl = "https://developers.notion.com/",
            defaultBaseUrl = "https://api.notion.com/v1",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            defaultTools = listOf(
                ToolDescriptor(
                    toolId = "tpl_notion_api:search",
                    connectorId = "tpl_notion_api",
                    name = "notion_search",
                    description = "Searches Notion pages and databases by title.",
                    protocol = ConnectorProtocol.REST,
                    methodOrOperation = "POST",
                    endpointPath = "/search",
                    inputSchema = listOf(
                        ToolParameterSchema("query", "string", "Search query", true, location = ParamLocation.BODY)
                    ),
                    riskLevel = ToolRiskLevel.LOW,
                    tags = listOf("notion", "notes", "docs", "wiki")
                )
            ),
            setupNotes = "Requires Notion Internal Integration Secret + header 'Notion-Version: 2022-06-28'."
        ),
        MarketplaceTemplate(
            id = "tpl_linear_graphql",
            name = "Linear GraphQL",
            provider = "Linear",
            category = ConnectorCategory.PRODUCTIVITY,
            protocol = ConnectorProtocol.GRAPHQL,
            description = "Query projects, issues, and sprint cycles via Linear GraphQL API.",
            docsUrl = "https://developers.linear.app/docs/graphql/working-with-the-graphql-api",
            defaultBaseUrl = "https://api.linear.app/graphql",
            defaultAuthScheme = AuthSchemeType.API_KEY,
            defaultTools = listOf(
                ToolDescriptor(
                    toolId = "tpl_linear_graphql:issues",
                    connectorId = "tpl_linear_graphql",
                    name = "linear_query",
                    description = "Executes a read-only Linear GraphQL query.",
                    protocol = ConnectorProtocol.GRAPHQL,
                    methodOrOperation = "QUERY",
                    endpointPath = "/graphql",
                    inputSchema = listOf(
                        ToolParameterSchema("query", "string", "GraphQL query body", true, "{ viewer { name } }", location = ParamLocation.BODY)
                    ),
                    riskLevel = ToolRiskLevel.LOW,
                    tags = listOf("linear", "issues", "graphql", "tasks")
                )
            ),
            setupNotes = "Use Authorization header with your Linear Personal API Key."
        ),
        MarketplaceTemplate(
            id = "tpl_supabase_rest",
            name = "Supabase PostgREST",
            provider = "Supabase",
            category = ConnectorCategory.DATABASES,
            protocol = ConnectorProtocol.REST,
            description = "Query Postgres tables and RPC functions via Supabase REST endpoint.",
            docsUrl = "https://supabase.com/docs/guides/api",
            defaultBaseUrl = "https://YOUR_PROJECT.supabase.co/rest/v1",
            defaultAuthScheme = AuthSchemeType.API_KEY,
            defaultTools = listOf(
                ToolDescriptor(
                    toolId = "tpl_supabase_rest:select_rows",
                    connectorId = "tpl_supabase_rest",
                    name = "supabase_select",
                    description = "Fetches rows from a Supabase table with PostgREST filter.",
                    protocol = ConnectorProtocol.REST,
                    methodOrOperation = "GET",
                    endpointPath = "/{table}",
                    inputSchema = listOf(
                        ToolParameterSchema("table", "string", "Table name", true, location = ParamLocation.PATH),
                        ToolParameterSchema("select", "string", "Columns", false, "*", location = ParamLocation.QUERY)
                    ),
                    riskLevel = ToolRiskLevel.LOW,
                    tags = listOf("supabase", "postgres", "database", "sql")
                )
            ),
            setupNotes = "Replace YOUR_PROJECT in Base URL and store Supabase anon/service key in apikey header."
        ),
        MarketplaceTemplate(
            id = "tpl_n8n_webhook",
            name = "n8n / Zapier / Make Webhook",
            provider = "Automation Webhook",
            category = ConnectorCategory.AUTOMATION,
            protocol = ConnectorProtocol.WEBHOOK,
            description = "Trigger external n8n, Zapier, or Make scenarios with HMAC-signed JSON payloads.",
            docsUrl = "https://docs.n8n.io/integrations/builtin/core-nodes/n8n-nodes-base.webhook/",
            defaultBaseUrl = "https://your-instance.example.com/webhook/kavya",
            defaultAuthScheme = AuthSchemeType.HMAC_WEBHOOK,
            defaultTools = listOf(
                ToolDescriptor(
                    toolId = "tpl_n8n_webhook:trigger",
                    connectorId = "tpl_n8n_webhook",
                    name = "trigger_automation_webhook",
                    description = "Dispatches an HMAC-SHA256 signed event payload to your automation webhook.",
                    protocol = ConnectorProtocol.WEBHOOK,
                    methodOrOperation = "POST",
                    endpointPath = "",
                    inputSchema = listOf(
                        ToolParameterSchema("event", "string", "Event name", true, location = ParamLocation.BODY),
                        ToolParameterSchema("payload", "string", "JSON payload or note", true, location = ParamLocation.BODY)
                    ),
                    riskLevel = ToolRiskLevel.MEDIUM,
                    requiresConfirmation = true,
                    isIdempotent = false,
                    tags = listOf("n8n", "zapier", "make", "webhook", "automation")
                )
            ),
            setupNotes = "Enter your exact HTTPS webhook URL and optional HMAC signing secret."
        ),
        MarketplaceTemplate(
            id = "tpl_home_assistant",
            name = "Home Assistant",
            provider = "Home Assistant",
            category = ConnectorCategory.SMART_HOME,
            protocol = ConnectorProtocol.REST,
            description = "Inspect smart home states and invoke Home Assistant services.",
            docsUrl = "https://developers.home-assistant.io/docs/api/rest/",
            defaultBaseUrl = "http://homeassistant.local:8123/api",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            defaultTools = listOf(
                ToolDescriptor(
                    toolId = "tpl_home_assistant:states",
                    connectorId = "tpl_home_assistant",
                    name = "ha_get_states",
                    description = "Reads current sensor and device entity states.",
                    protocol = ConnectorProtocol.REST,
                    methodOrOperation = "GET",
                    endpointPath = "/states",
                    riskLevel = ToolRiskLevel.LOW,
                    tags = listOf("home", "iot", "smart home", "lights", "sensors")
                )
            ),
            setupNotes = "Requires Long-Lived Access Token + enabling Local Dev / LAN access if on private Wi-Fi."
        ),
        MarketplaceTemplate(
            id = "tpl_remote_mcp",
            name = "Remote MCP Server",
            provider = "Model Context Protocol",
            category = ConnectorCategory.AI_ML,
            protocol = ConnectorProtocol.MCP,
            description = "Connect any MCP Streamable HTTP / SSE server and auto-discover its tools, resources, and prompts.",
            docsUrl = "https://modelcontextprotocol.io/specification/2025-03-26",
            defaultBaseUrl = "https://mcp.example.com/mcp",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            defaultTools = emptyList(),
            setupNotes = "Enter MCP endpoint URL and optional token, then tap Connect & Discover Tools."
        ),
        MarketplaceTemplate(
            id = "tpl_a2a_agent",
            name = "A2A Partner Agent",
            provider = "Agent-to-Agent",
            category = ConnectorCategory.AI_ML,
            protocol = ConnectorProtocol.A2A,
            description = "Discover /.well-known/agent.json and delegate specialized tasks via A2A JSON-RPC.",
            docsUrl = "https://google.github.io/A2A/",
            defaultBaseUrl = "https://agent.example.com",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            defaultTools = listOf(
                ToolDescriptor(
                    toolId = "tpl_a2a_agent:tasks_send",
                    connectorId = "tpl_a2a_agent",
                    name = "a2a_send_task",
                    description = "Delegates a natural-language task to the external A2A agent.",
                    protocol = ConnectorProtocol.A2A,
                    methodOrOperation = "tasks/send",
                    endpointPath = "",
                    inputSchema = listOf(
                        ToolParameterSchema("prompt", "string", "Task prompt for external agent", true, location = ParamLocation.BODY)
                    ),
                    riskLevel = ToolRiskLevel.MEDIUM,
                    requiresConfirmation = true,
                    isIdempotent = false,
                    tags = listOf("a2a", "agent", "delegate")
                )
            ),
            setupNotes = "Enter agent base URL to fetch its Agent Card and enable task delegation."
        ),
        MarketplaceTemplate(
            id = "tpl_figma_rest",
            name = "Figma REST API",
            provider = "Figma",
            category = ConnectorCategory.DESIGN_MEDIA,
            protocol = ConnectorProtocol.REST,
            description = "Inspect Figma files, components, and team comments.",
            docsUrl = "https://www.figma.com/developers/api",
            defaultBaseUrl = "https://api.figma.com/v1",
            defaultAuthScheme = AuthSchemeType.API_KEY,
            defaultTools = listOf(
                ToolDescriptor(
                    toolId = "tpl_figma_rest:get_me",
                    connectorId = "tpl_figma_rest",
                    name = "figma_get_me",
                    description = "Retrieves authenticated Figma user info.",
                    protocol = ConnectorProtocol.REST,
                    methodOrOperation = "GET",
                    endpointPath = "/me",
                    riskLevel = ToolRiskLevel.LOW,
                    tags = listOf("figma", "design", "ui")
                )
            ),
            setupNotes = "Use header name 'X-Figma-Token' with Personal Access Token."
        )
    )

    fun instantiateFromTemplate(
        template: MarketplaceTemplate,
        customBaseUrl: String = template.defaultBaseUrl,
        allowLocalDev: Boolean = template.id == "tpl_home_assistant"
    ): Pair<ConnectorDefinition, List<ToolDescriptor>> {
        val instanceId = "${template.id}_${System.currentTimeMillis() % 100_000}"
        val cleanUrl = customBaseUrl.trim().trimEnd('/')
        val hostAllow = Regex("^https?://([^/:]+)").find(cleanUrl)?.groupValues?.get(1)
            ?.let { listOf(it) } ?: emptyList()

        val auth = when (template.id) {
            "tpl_figma_rest" -> AuthConfig(AuthSchemeType.API_KEY, headerName = "X-Figma-Token", prefix = "")
            "tpl_supabase_rest" -> AuthConfig(AuthSchemeType.API_KEY, headerName = "apikey", prefix = "")
            else -> AuthConfig(
                schemeType = template.defaultAuthScheme,
                headerName = "Authorization",
                prefix = if (template.defaultAuthScheme in setOf(AuthSchemeType.BEARER_TOKEN, AuthSchemeType.OAUTH2_PKCE)) "Bearer " else "",
                scopes = template.defaultScopes
            )
        }

        val connector = ConnectorDefinition(
            id = instanceId,
            name = template.name,
            provider = template.provider,
            description = template.description,
            version = "1.0.0",
            protocol = template.protocol,
            category = template.category,
            baseUrl = cleanUrl,
            status = ConnectorStatus.CONFIGURED,
            authConfig = auth,
            capabilities = template.defaultTools.map { it.name },
            permissions = template.defaultScopes,
            domainAllowlist = hostAllow,
            isLocalDevAllowed = allowLocalDev,
            defaultHeaders = if (template.id == "tpl_notion_api") mapOf("Notion-Version" to "2022-06-28") else emptyMap(),
            docsUrl = template.docsUrl
        )

        val tools = template.defaultTools.map { t ->
            t.copy(
                toolId = "${instanceId}:${t.name}",
                connectorId = instanceId,
                status = ConnectorStatus.CONFIGURED
            )
        }
        return connector to tools
    }
}
