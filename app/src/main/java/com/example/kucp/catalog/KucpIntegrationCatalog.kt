package com.example.kucp.catalog

import com.example.kucp.model.AuthConfig
import com.example.kucp.model.AuthSchemeType
import com.example.kucp.model.CatalogRefreshReport
import com.example.kucp.model.ConnectorCategory
import com.example.kucp.model.ConnectorDefinition
import com.example.kucp.model.ConnectorImplementationState
import com.example.kucp.model.ConnectorProtocol
import com.example.kucp.model.ConnectorStatus
import com.example.kucp.model.EcosystemSource
import com.example.kucp.model.MarketplaceTemplate
import com.example.kucp.model.ParamLocation
import com.example.kucp.model.ToolDescriptor
import com.example.kucp.model.ToolParameterSchema
import com.example.kucp.model.ToolRiskLevel

/**
 * Authoritative, deduplicated Canonical Connector Catalog unifying publicly documented
 * external service connectors across:
 *  - Claude Connectors & Official Remote MCP Directory
 *  - ChatGPT Connected Apps Directory
 *  - Gemini Connected Apps (Google Workspace & Partner Extensions)
 *
 * Rules strictly enforced:
 *  - One canonical service ID per real external service; multi-platform presence is stored in
 *    [MarketplaceTemplate.ecosystemSources] rather than duplicating adapters.
 *  - Never reports a template as CONNECTED until real authentication and a connection handshake succeed.
 *  - Explicitly marks services with no public API or closed vendor-only integrations as
 *    [ConnectorImplementationState.BLOCKED_NO_PUBLIC_API] with exact reasons.
 */
object KucpIntegrationCatalog {

    private fun readTool(
        tplId: String,
        name: String,
        desc: String,
        path: String,
        protocol: ConnectorProtocol = ConnectorProtocol.REST,
        method: String = "GET",
        params: List<ToolParameterSchema> = emptyList(),
        tags: List<String> = emptyList()
    ) = ToolDescriptor(
        toolId = "$tplId:$name",
        connectorId = tplId,
        name = name,
        description = desc,
        protocol = protocol,
        methodOrOperation = method,
        endpointPath = path,
        inputSchema = params,
        riskLevel = ToolRiskLevel.LOW,
        isIdempotent = true,
        tags = tags
    )

    private fun writeTool(
        tplId: String,
        name: String,
        desc: String,
        path: String,
        protocol: ConnectorProtocol = ConnectorProtocol.REST,
        method: String = "POST",
        params: List<ToolParameterSchema> = emptyList(),
        risk: ToolRiskLevel = ToolRiskLevel.HIGH,
        tags: List<String> = emptyList()
    ) = ToolDescriptor(
        toolId = "$tplId:$name",
        connectorId = tplId,
        name = name,
        description = desc,
        protocol = protocol,
        methodOrOperation = method,
        endpointPath = path,
        inputSchema = params,
        riskLevel = risk,
        requiresConfirmation = true,
        isIdempotent = false,
        tags = tags
    )

    private fun q(name: String, desc: String, required: Boolean = false, default: String? = null, type: String = "string") =
        ToolParameterSchema(name, type, desc, required, default, location = ParamLocation.QUERY)

    private fun p(name: String, desc: String) =
        ToolParameterSchema(name, "string", desc, true, location = ParamLocation.PATH)

    private fun b(name: String, desc: String, required: Boolean = true, default: String? = null, type: String = "string") =
        ToolParameterSchema(name, type, desc, required, default, location = ParamLocation.BODY)

    private const val GOOGLE_AUTH = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val GOOGLE_TOKEN = "https://oauth2.googleapis.com/token"
    private const val GOOGLE_REVOKE = "https://oauth2.googleapis.com/revoke"
    private const val MS_AUTH = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"
    private const val MS_TOKEN = "https://login.microsoftonline.com/common/oauth2/v2.0/token"

    val templates: List<MarketplaceTemplate> = listOf(
        // =====================================================================
        // 1. GOOGLE ECOSYSTEM
        // =====================================================================
        MarketplaceTemplate(
            id = "tpl_google_gmail",
            canonicalServiceId = "google_gmail",
            name = "Gmail",
            provider = "Google",
            category = ConnectorCategory.GOOGLE_WORKSPACE,
            protocol = ConnectorProtocol.OAUTH2_OIDC,
            description = "Search threads, read email metadata/snippets, and create drafts via official Gmail REST v1.",
            docsUrl = "https://developers.google.com/gmail/api",
            defaultBaseUrl = "https://gmail.googleapis.com/gmail/v1",
            defaultAuthScheme = AuthSchemeType.OAUTH2_PKCE,
            defaultScopes = listOf("https://www.googleapis.com/auth/gmail.readonly", "https://www.googleapis.com/auth/gmail.compose"),
            oauthAuthorizeUrl = GOOGLE_AUTH,
            oauthTokenUrl = GOOGLE_TOKEN,
            oauthRevokeUrl = GOOGLE_REVOKE,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.CHATGPT_CONNECTED_APPS, EcosystemSource.GEMINI_CONNECTED_APPS, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("gmail_list_messages", "gmail_get_message"),
            writeOperations = listOf("gmail_create_draft"),
            webhookSupported = true,
            restrictions = "Requires user Google OAuth 2.0 Android/PKCE Client ID; Pub/Sub webhook needs GCP topic.",
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_google_gmail", "gmail_list_messages", "Lists recent Gmail message IDs matching optional search query q.", "/users/me/messages", params = listOf(q("q", "Gmail search query"), q("maxResults", "Max messages", false, "10", "integer")), tags = listOf("gmail", "email", "google", "inbox")),
                readTool("tpl_google_gmail", "gmail_get_message", "Fetches a Gmail message by ID.", "/users/me/messages/{id}", params = listOf(p("id", "Message ID"), q("format", "metadata or full", false, "metadata")), tags = listOf("gmail", "email", "read")),
                writeTool("tpl_google_gmail", "gmail_create_draft", "Creates a Gmail draft (requires user approval).", "/users/me/drafts", params = listOf(b("message", "RFC-2822 raw base64url draft object")), tags = listOf("gmail", "draft", "email"))
            ),
            setupNotes = "Configure your Google OAuth 2.0 Android/PKCE Client ID and authorize narrow Gmail scopes."
        ),
        MarketplaceTemplate(
            id = "tpl_google_drive",
            canonicalServiceId = "google_drive",
            name = "Google Drive",
            provider = "Google",
            category = ConnectorCategory.CLOUD_STORAGE,
            protocol = ConnectorProtocol.OAUTH2_OIDC,
            description = "Search files, folders, and Docs/Sheets/Slides metadata in Google Drive v3.",
            docsUrl = "https://developers.google.com/drive/api",
            defaultBaseUrl = "https://www.googleapis.com/drive/v3",
            defaultAuthScheme = AuthSchemeType.OAUTH2_PKCE,
            defaultScopes = listOf("https://www.googleapis.com/auth/drive.readonly"),
            oauthAuthorizeUrl = GOOGLE_AUTH,
            oauthTokenUrl = GOOGLE_TOKEN,
            oauthRevokeUrl = GOOGLE_REVOKE,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.CHATGPT_CONNECTED_APPS, EcosystemSource.GEMINI_CONNECTED_APPS, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("drive_search_files", "drive_get_file_metadata"),
            webhookSupported = true,
            restrictions = "Requires Google OAuth 2.0 PKCE Client ID.",
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_google_drive", "drive_search_files", "Searches files and folders in Google Drive.", "/files", params = listOf(q("q", "Drive query filter"), q("pageSize", "Results per page", false, "10", "integer")), tags = listOf("drive", "docs", "sheets", "files", "google")),
                readTool("tpl_google_drive", "drive_get_file_metadata", "Retrieves file metadata by Drive fileId.", "/files/{fileId}", params = listOf(p("fileId", "Google Drive file ID"), q("fields", "Fields selector", false, "id,name,mimeType,modifiedTime,webViewLink")), tags = listOf("drive", "file", "metadata"))
            ),
            setupNotes = "Configure Google OAuth 2.0 PKCE Client ID."
        ),
        MarketplaceTemplate(
            id = "tpl_google_calendar",
            canonicalServiceId = "google_calendar",
            name = "Google Calendar",
            provider = "Google",
            category = ConnectorCategory.GOOGLE_WORKSPACE,
            protocol = ConnectorProtocol.OAUTH2_OIDC,
            description = "Query upcoming events and create approved calendar entries via Google Calendar v3.",
            docsUrl = "https://developers.google.com/calendar/api",
            defaultBaseUrl = "https://www.googleapis.com/calendar/v3",
            defaultAuthScheme = AuthSchemeType.OAUTH2_PKCE,
            defaultScopes = listOf("https://www.googleapis.com/auth/calendar.readonly", "https://www.googleapis.com/auth/calendar.events"),
            oauthAuthorizeUrl = GOOGLE_AUTH,
            oauthTokenUrl = GOOGLE_TOKEN,
            oauthRevokeUrl = GOOGLE_REVOKE,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.CHATGPT_CONNECTED_APPS, EcosystemSource.GEMINI_CONNECTED_APPS, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("calendar_list_events"),
            writeOperations = listOf("calendar_create_event"),
            webhookSupported = true,
            restrictions = "Requires Google OAuth 2.0 PKCE Client ID.",
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_google_calendar", "calendar_list_events", "Lists upcoming events from primary Google Calendar.", "/calendars/primary/events", params = listOf(q("maxResults", "Max events", false, "10", "integer"), q("timeMin", "RFC3339 start time")), tags = listOf("calendar", "schedule", "meeting", "google")),
                writeTool("tpl_google_calendar", "calendar_create_event", "Creates a Google Calendar event (requires approval).", "/calendars/primary/events", params = listOf(b("summary", "Event title"), b("start", "Start dateTime ISO object"), b("end", "End dateTime ISO object")), tags = listOf("calendar", "create", "event"))
            ),
            setupNotes = "Configure Google OAuth 2.0 PKCE Client ID."
        ),
        MarketplaceTemplate(
            id = "tpl_google_docs",
            canonicalServiceId = "google_docs",
            name = "Google Docs",
            provider = "Google",
            category = ConnectorCategory.GOOGLE_WORKSPACE,
            protocol = ConnectorProtocol.OAUTH2_OIDC,
            description = "Read and create Google Docs documents via official Docs API v1.",
            docsUrl = "https://developers.google.com/docs/api",
            defaultBaseUrl = "https://docs.googleapis.com/v1",
            defaultAuthScheme = AuthSchemeType.OAUTH2_PKCE,
            defaultScopes = listOf("https://www.googleapis.com/auth/documents.readonly"),
            oauthAuthorizeUrl = GOOGLE_AUTH,
            oauthTokenUrl = GOOGLE_TOKEN,
            oauthRevokeUrl = GOOGLE_REVOKE,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.CHATGPT_CONNECTED_APPS, EcosystemSource.GEMINI_CONNECTED_APPS, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("gdocs_get_document"),
            writeOperations = listOf("gdocs_create_document"),
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_google_docs", "gdocs_get_document", "Reads structure and text of a Google Doc by documentId.", "/documents/{documentId}", params = listOf(p("documentId", "Google Doc ID")), tags = listOf("docs", "document", "google")),
                writeTool("tpl_google_docs", "gdocs_create_document", "Creates a new Google Doc with a title.", "/documents", params = listOf(b("title", "Document title")), tags = listOf("docs", "create", "report"))
            ),
            setupNotes = "Requires Google OAuth 2.0 PKCE Client ID with Docs API enabled."
        ),
        MarketplaceTemplate(
            id = "tpl_google_sheets",
            canonicalServiceId = "google_sheets",
            name = "Google Sheets",
            provider = "Google",
            category = ConnectorCategory.GOOGLE_WORKSPACE,
            protocol = ConnectorProtocol.OAUTH2_OIDC,
            description = "Read cell ranges and append structured rows via Google Sheets API v4.",
            docsUrl = "https://developers.google.com/sheets/api",
            defaultBaseUrl = "https://sheets.googleapis.com/v4",
            defaultAuthScheme = AuthSchemeType.OAUTH2_PKCE,
            defaultScopes = listOf("https://www.googleapis.com/auth/spreadsheets.readonly"),
            oauthAuthorizeUrl = GOOGLE_AUTH,
            oauthTokenUrl = GOOGLE_TOKEN,
            oauthRevokeUrl = GOOGLE_REVOKE,
            ecosystemSources = listOf(EcosystemSource.CHATGPT_CONNECTED_APPS, EcosystemSource.GEMINI_CONNECTED_APPS, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("gsheets_get_values"),
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_google_sheets", "gsheets_get_values", "Reads values from a Google Sheet range.", "/spreadsheets/{spreadsheetId}/values/{range}", params = listOf(p("spreadsheetId", "Spreadsheet ID"), p("range", "A1 range e.g. Sheet1!A1:D20")), tags = listOf("sheets", "spreadsheet", "google", "table"))
            ),
            setupNotes = "Requires Google OAuth 2.0 PKCE Client ID with Sheets API enabled."
        ),
        MarketplaceTemplate(
            id = "tpl_google_slides",
            canonicalServiceId = "google_slides",
            name = "Google Slides",
            provider = "Google",
            category = ConnectorCategory.GOOGLE_WORKSPACE,
            protocol = ConnectorProtocol.OAUTH2_OIDC,
            description = "Inspect slide decks and extract slide text via Google Slides API v1.",
            docsUrl = "https://developers.google.com/slides/api",
            defaultBaseUrl = "https://slides.googleapis.com/v1",
            defaultAuthScheme = AuthSchemeType.OAUTH2_PKCE,
            defaultScopes = listOf("https://www.googleapis.com/auth/presentations.readonly"),
            oauthAuthorizeUrl = GOOGLE_AUTH,
            oauthTokenUrl = GOOGLE_TOKEN,
            oauthRevokeUrl = GOOGLE_REVOKE,
            ecosystemSources = listOf(EcosystemSource.CHATGPT_CONNECTED_APPS, EcosystemSource.GEMINI_CONNECTED_APPS, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("gslides_get_presentation"),
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_google_slides", "gslides_get_presentation", "Fetches a Google Slides presentation by ID.", "/presentations/{presentationId}", params = listOf(p("presentationId", "Presentation ID")), tags = listOf("slides", "presentation", "google"))
            ),
            setupNotes = "Requires Google OAuth 2.0 PKCE Client ID with Slides API enabled."
        ),
        MarketplaceTemplate(
            id = "tpl_google_contacts",
            canonicalServiceId = "google_contacts",
            name = "Google Contacts (People API)",
            provider = "Google",
            category = ConnectorCategory.GOOGLE_WORKSPACE,
            protocol = ConnectorProtocol.OAUTH2_OIDC,
            description = "Search Google Contacts directory via official People API v1.",
            docsUrl = "https://developers.google.com/people",
            defaultBaseUrl = "https://people.googleapis.com/v1",
            defaultAuthScheme = AuthSchemeType.OAUTH2_PKCE,
            defaultScopes = listOf("https://www.googleapis.com/auth/contacts.readonly"),
            oauthAuthorizeUrl = GOOGLE_AUTH,
            oauthTokenUrl = GOOGLE_TOKEN,
            oauthRevokeUrl = GOOGLE_REVOKE,
            ecosystemSources = listOf(EcosystemSource.CHATGPT_CONNECTED_APPS, EcosystemSource.GEMINI_CONNECTED_APPS, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("gcontacts_search"),
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_google_contacts", "gcontacts_search", "Searches contacts by name or email.", "/people:searchContacts", params = listOf(q("query", "Search query", true), q("readMask", "Fields", false, "names,emailAddresses,phoneNumbers")), tags = listOf("contacts", "people", "google"))
            ),
            setupNotes = "Requires Google OAuth 2.0 PKCE Client ID with People API enabled."
        ),
        MarketplaceTemplate(
            id = "tpl_google_tasks",
            canonicalServiceId = "google_tasks",
            name = "Google Tasks",
            provider = "Google",
            category = ConnectorCategory.GOOGLE_WORKSPACE,
            protocol = ConnectorProtocol.OAUTH2_OIDC,
            description = "List task lists and create tasks via official Google Tasks API v1.",
            docsUrl = "https://developers.google.com/tasks",
            defaultBaseUrl = "https://tasks.googleapis.com/tasks/v1",
            defaultAuthScheme = AuthSchemeType.OAUTH2_PKCE,
            defaultScopes = listOf("https://www.googleapis.com/auth/tasks"),
            oauthAuthorizeUrl = GOOGLE_AUTH,
            oauthTokenUrl = GOOGLE_TOKEN,
            oauthRevokeUrl = GOOGLE_REVOKE,
            ecosystemSources = listOf(EcosystemSource.GEMINI_CONNECTED_APPS, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("gtasks_list_tasklists"),
            writeOperations = listOf("gtasks_create_task"),
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_google_tasks", "gtasks_list_tasklists", "Lists user's Google Task lists.", "/users/@me/lists", tags = listOf("tasks", "todo", "google")),
                writeTool("tpl_google_tasks", "gtasks_create_task", "Creates a task in a Google Task list.", "/lists/{tasklist}/tasks", params = listOf(p("tasklist", "Task list ID (e.g. @default)"), b("title", "Task title")), risk = ToolRiskLevel.MEDIUM, tags = listOf("tasks", "create", "todo"))
            ),
            setupNotes = "Requires Google OAuth 2.0 PKCE Client ID with Tasks API enabled."
        ),
        MarketplaceTemplate(
            id = "tpl_youtube_data",
            canonicalServiceId = "youtube",
            name = "YouTube Data API",
            provider = "Google",
            category = ConnectorCategory.DESIGN_MEDIA,
            protocol = ConnectorProtocol.REST,
            description = "Search videos, channels, and playlists via official YouTube Data API v3.",
            docsUrl = "https://developers.google.com/youtube/v3",
            defaultBaseUrl = "https://www.googleapis.com/youtube/v3",
            defaultAuthScheme = AuthSchemeType.API_KEY,
            ecosystemSources = listOf(EcosystemSource.GEMINI_CONNECTED_APPS, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("youtube_search"),
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_youtube_data", "youtube_search", "Searches YouTube videos by query.", "/search", params = listOf(q("q", "Search terms", true), q("part", "Resource part", false, "snippet"), q("maxResults", "Max results", false, "5", "integer")), tags = listOf("youtube", "video", "media"))
            ),
            setupNotes = "Store YouTube Data API v3 key in Credential Vault (query param 'key')."
        ),
        MarketplaceTemplate(
            id = "tpl_google_maps_places",
            canonicalServiceId = "google_maps",
            name = "Google Maps Places API",
            provider = "Google",
            category = ConnectorCategory.RESEARCH_KNOWLEDGE,
            protocol = ConnectorProtocol.REST,
            description = "Search places, ratings, and addresses via official Google Maps Places API (New).",
            docsUrl = "https://developers.google.com/maps/documentation/places/web-service/overview",
            defaultBaseUrl = "https://places.googleapis.com/v1",
            defaultAuthScheme = AuthSchemeType.API_KEY,
            ecosystemSources = listOf(EcosystemSource.GEMINI_CONNECTED_APPS, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("maps_search_text"),
            defaultHeaders = mapOf("X-Goog-FieldMask" to "places.displayName,places.formattedAddress,places.rating"),
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_google_maps_places", "maps_search_text", "Searches places using Places API Text Search.", "/places:searchText", method = "POST", params = listOf(b("textQuery", "Place query e.g. cafes in Bengaluru")), tags = listOf("maps", "places", "location", "google"))
            ),
            setupNotes = "Requires Google Maps Places API key in header 'X-Goog-Api-Key'."
        ),
        MarketplaceTemplate(
            id = "tpl_google_keep_unsupported",
            canonicalServiceId = "google_keep",
            name = "Google Keep",
            provider = "Google",
            category = ConnectorCategory.GOOGLE_WORKSPACE,
            protocol = ConnectorProtocol.REST,
            description = "Listed in Gemini Connected Apps; Google Keep API is restricted to Workspace enterprise service accounts and unavailable for personal OAuth apps.",
            docsUrl = "https://developers.google.com/keep/api",
            defaultBaseUrl = "https://keep.googleapis.com/v1",
            defaultAuthScheme = AuthSchemeType.OAUTH2_PKCE,
            ecosystemSources = listOf(EcosystemSource.GEMINI_CONNECTED_APPS),
            restrictions = "Blocked for personal accounts: Google Keep API only permits Google Workspace enterprise domain-wide delegation.",
            implementationState = ConnectorImplementationState.BLOCKED_NO_PUBLIC_API,
            setupNotes = "Unsupported for personal accounts due to Google Keep API enterprise-only restriction."
        ),
        MarketplaceTemplate(
            id = "tpl_google_flights_hotels_unsupported",
            canonicalServiceId = "google_flights_hotels",
            name = "Google Flights & Hotels",
            provider = "Google",
            category = ConnectorCategory.RESEARCH_KNOWLEDGE,
            protocol = ConnectorProtocol.REST,
            description = "Listed in Gemini Connected Apps; Google does not expose a public Flights or Hotels REST/MCP API for third-party apps.",
            docsUrl = "https://support.google.com/gemini/answer/13695044",
            defaultBaseUrl = "",
            defaultAuthScheme = AuthSchemeType.NONE,
            ecosystemSources = listOf(EcosystemSource.GEMINI_CONNECTED_APPS),
            restrictions = "Blocked: First-party Gemini internal extension only; no public third-party API exists.",
            implementationState = ConnectorImplementationState.BLOCKED_NO_PUBLIC_API,
            setupNotes = "No public API available from Google; use Web Research or an authorized travel API instead."
        ),

        // =====================================================================
        // 2. MICROSOFT 365 ECOSYSTEM (Unified via Microsoft Graph v1.0)
        // =====================================================================
        MarketplaceTemplate(
            id = "tpl_microsoft_graph",
            canonicalServiceId = "microsoft_365_outlook",
            name = "Outlook Mail & Calendar (Microsoft 365)",
            provider = "Microsoft",
            category = ConnectorCategory.MICROSOFT_365,
            protocol = ConnectorProtocol.OAUTH2_OIDC,
            description = "Read Outlook mail, calendar events, and Microsoft 365 profile via Microsoft Graph v1.0.",
            docsUrl = "https://learn.microsoft.com/graph/overview",
            defaultBaseUrl = "https://graph.microsoft.com/v1.0",
            defaultAuthScheme = AuthSchemeType.OAUTH2_PKCE,
            defaultScopes = listOf("User.Read", "Mail.Read", "Calendars.Read"),
            oauthAuthorizeUrl = MS_AUTH,
            oauthTokenUrl = MS_TOKEN,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.CHATGPT_CONNECTED_APPS, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("ms_graph_me", "outlook_list_messages", "outlook_list_events"),
            webhookSupported = true,
            restrictions = "Requires Entra ID Mobile/Native App Client ID; enterprise tenants may require admin consent.",
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_microsoft_graph", "ms_graph_me", "Fetches signed-in Microsoft 365 user profile.", "/me", tags = listOf("microsoft", "outlook", "profile")),
                readTool("tpl_microsoft_graph", "outlook_list_messages", "Lists recent Outlook inbox messages.", "/me/messages", params = listOf(q("\$top", "Max messages", false, "10", "integer")), tags = listOf("outlook", "email", "microsoft")),
                readTool("tpl_microsoft_graph", "outlook_list_events", "Lists upcoming Outlook calendar events.", "/me/events", params = listOf(q("\$top", "Max events", false, "10", "integer")), tags = listOf("outlook", "calendar", "meeting"))
            ),
            setupNotes = "Register an Entra ID Mobile/Native Client ID with redirect kavya://oauth/callback."
        ),
        MarketplaceTemplate(
            id = "tpl_microsoft_onedrive_sharepoint",
            canonicalServiceId = "microsoft_onedrive_sharepoint",
            name = "OneDrive & SharePoint",
            provider = "Microsoft",
            category = ConnectorCategory.MICROSOFT_365,
            protocol = ConnectorProtocol.OAUTH2_OIDC,
            description = "Search OneDrive files, Word/Excel/PowerPoint documents, and SharePoint sites via Microsoft Graph.",
            docsUrl = "https://learn.microsoft.com/graph/api/resources/onedrive",
            defaultBaseUrl = "https://graph.microsoft.com/v1.0",
            defaultAuthScheme = AuthSchemeType.OAUTH2_PKCE,
            defaultScopes = listOf("Files.Read", "Sites.Read.All"),
            oauthAuthorizeUrl = MS_AUTH,
            oauthTokenUrl = MS_TOKEN,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.CHATGPT_CONNECTED_APPS, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("onedrive_list_root", "sharepoint_search_sites"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_microsoft_onedrive_sharepoint", "onedrive_list_root", "Lists files in OneDrive root folder.", "/me/drive/root/children", tags = listOf("onedrive", "excel", "word", "powerpoint", "files")),
                readTool("tpl_microsoft_onedrive_sharepoint", "sharepoint_search_sites", "Searches SharePoint sites.", "/sites", params = listOf(q("search", "Site search query", true)), tags = listOf("sharepoint", "microsoft", "wiki"))
            ),
            setupNotes = "Requires Entra ID Client ID with Files.Read / Sites.Read.All."
        ),
        MarketplaceTemplate(
            id = "tpl_microsoft_teams",
            canonicalServiceId = "microsoft_teams",
            name = "Microsoft Teams",
            provider = "Microsoft",
            category = ConnectorCategory.MICROSOFT_365,
            protocol = ConnectorProtocol.OAUTH2_OIDC,
            description = "List joined Teams and channels via Microsoft Graph Teams API.",
            docsUrl = "https://learn.microsoft.com/graph/api/resources/teams-api-overview",
            defaultBaseUrl = "https://graph.microsoft.com/v1.0",
            defaultAuthScheme = AuthSchemeType.OAUTH2_PKCE,
            defaultScopes = listOf("Team.ReadBasic.All", "Channel.ReadBasic.All"),
            oauthAuthorizeUrl = MS_AUTH,
            oauthTokenUrl = MS_TOKEN,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.CHATGPT_CONNECTED_APPS, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("teams_list_joined"),
            webhookSupported = true,
            restrictions = "Microsoft 365 Work/School tenant required for Teams Graph endpoints.",
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_microsoft_teams", "teams_list_joined", "Lists Microsoft Teams the user has joined.", "/me/joinedTeams", tags = listOf("teams", "microsoft", "chat"))
            ),
            setupNotes = "Requires Entra ID Client ID on a Microsoft 365 work/school account."
        ),
        MarketplaceTemplate(
            id = "tpl_microsoft_planner_onenote",
            canonicalServiceId = "microsoft_planner_onenote",
            name = "Microsoft Planner & OneNote",
            provider = "Microsoft",
            category = ConnectorCategory.MICROSOFT_365,
            protocol = ConnectorProtocol.OAUTH2_OIDC,
            description = "Read Planner tasks and OneNote notebooks via Microsoft Graph v1.0.",
            docsUrl = "https://learn.microsoft.com/graph/api/resources/planner-overview",
            defaultBaseUrl = "https://graph.microsoft.com/v1.0",
            defaultAuthScheme = AuthSchemeType.OAUTH2_PKCE,
            defaultScopes = listOf("Tasks.Read", "Notes.Read"),
            oauthAuthorizeUrl = MS_AUTH,
            oauthTokenUrl = MS_TOKEN,
            ecosystemSources = listOf(EcosystemSource.OFFICIAL_API),
            readOperations = listOf("planner_my_tasks", "onenote_list_notebooks"),
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_microsoft_planner_onenote", "planner_my_tasks", "Lists Planner tasks assigned to current user.", "/me/planner/tasks", tags = listOf("planner", "tasks", "microsoft")),
                readTool("tpl_microsoft_planner_onenote", "onenote_list_notebooks", "Lists OneNote notebooks.", "/me/onenote/notebooks", tags = listOf("onenote", "notes", "microsoft"))
            ),
            setupNotes = "Requires Entra ID Client ID with Tasks.Read / Notes.Read."
        ),

        // =====================================================================
        // 3. COMMUNICATION & COLLABORATION
        // =====================================================================
        MarketplaceTemplate(
            id = "tpl_slack_api",
            canonicalServiceId = "slack",
            name = "Slack",
            provider = "Slack",
            category = ConnectorCategory.COMMUNICATION,
            protocol = ConnectorProtocol.REST,
            description = "List channels, read channel history, and post approved messages via Slack Web API.",
            docsUrl = "https://api.slack.com/web",
            defaultBaseUrl = "https://slack.com/api",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            defaultScopes = listOf("channels:read", "channels:history", "chat:write"),
            oauthAuthorizeUrl = "https://slack.com/oauth/v2/authorize",
            oauthTokenUrl = "https://slack.com/api/oauth.v2.access",
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.CHATGPT_CONNECTED_APPS, EcosystemSource.OFFICIAL_MCP, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("slack_list_channels", "slack_channel_history"),
            writeOperations = listOf("slack_post_message"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_slack_api", "slack_list_channels", "Lists public Slack channels.", "/conversations.list", tags = listOf("slack", "chat", "channels", "message")),
                readTool("tpl_slack_api", "slack_channel_history", "Reads recent messages in a Slack channel.", "/conversations.history", params = listOf(q("channel", "Channel ID", true), q("limit", "Max messages", false, "15", "integer")), tags = listOf("slack", "history", "updates")),
                writeTool("tpl_slack_api", "slack_post_message", "Posts a message to a Slack channel (requires approval).", "/chat.postMessage", params = listOf(b("channel", "Channel ID"), b("text", "Message text")), tags = listOf("slack", "send", "message"))
            ),
            setupNotes = "Store Slack Bot/User OAuth token (xoxb-/xoxp-) or configure Slack OAuth app."
        ),
        MarketplaceTemplate(
            id = "tpl_discord_api",
            canonicalServiceId = "discord",
            name = "Discord",
            provider = "Discord",
            category = ConnectorCategory.COMMUNICATION,
            protocol = ConnectorProtocol.REST,
            description = "Read channel messages and send approved alerts via Discord REST API v10.",
            docsUrl = "https://discord.com/developers/docs/intro",
            defaultBaseUrl = "https://discord.com/api/v10",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            ecosystemSources = listOf(EcosystemSource.OFFICIAL_API),
            readOperations = listOf("discord_get_messages"),
            writeOperations = listOf("discord_send_channel_message"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_discord_api", "discord_get_messages", "Fetches recent messages from a Discord channel.", "/channels/{channel_id}/messages", params = listOf(p("channel_id", "Discord channel ID"), q("limit", "Max messages", false, "10", "integer")), tags = listOf("discord", "chat", "messages")),
                writeTool("tpl_discord_api", "discord_send_channel_message", "Sends a message to a Discord channel (requires approval).", "/channels/{channel_id}/messages", params = listOf(p("channel_id", "Discord channel ID"), b("content", "Message content")), tags = listOf("discord", "send", "message"))
            ),
            setupNotes = "Configure Bot token or Discord Webhook URL."
        ),
        MarketplaceTemplate(
            id = "tpl_telegram_bot",
            canonicalServiceId = "telegram",
            name = "Telegram Bot API",
            provider = "Telegram",
            category = ConnectorCategory.COMMUNICATION,
            protocol = ConnectorProtocol.REST,
            description = "Fetch updates and send approved messages via official Telegram Bot API.",
            docsUrl = "https://core.telegram.org/bots/api",
            defaultBaseUrl = "https://api.telegram.org/botYOUR_BOT_TOKEN",
            defaultAuthScheme = AuthSchemeType.NONE,
            ecosystemSources = listOf(EcosystemSource.OFFICIAL_API),
            readOperations = listOf("telegram_get_updates"),
            writeOperations = listOf("telegram_send_message"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_telegram_bot", "telegram_get_updates", "Gets recent updates received by your Telegram bot.", "/getUpdates", tags = listOf("telegram", "messages", "bot")),
                writeTool("tpl_telegram_bot", "telegram_send_message", "Sends a message to a Telegram chat ID (requires approval).", "/sendMessage", params = listOf(b("chat_id", "Target chat ID"), b("text", "Message text")), tags = listOf("telegram", "send"))
            ),
            setupNotes = "Replace YOUR_BOT_TOKEN in baseUrl with a BotFather token."
        ),
        MarketplaceTemplate(
            id = "tpl_whatsapp_business",
            canonicalServiceId = "whatsapp",
            name = "WhatsApp (Android / Cloud Business API)",
            provider = "Meta",
            category = ConnectorCategory.COMMUNICATION,
            protocol = ConnectorProtocol.REST,
            description = "Listed in Gemini Connected Apps. Personal WhatsApp has no public REST API (Android intent/accessibility only); Meta WhatsApp Cloud API requires a verified Business Account.",
            docsUrl = "https://developers.facebook.com/docs/whatsapp/cloud-api",
            defaultBaseUrl = "https://graph.facebook.com/v20.0",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            ecosystemSources = listOf(EcosystemSource.GEMINI_CONNECTED_APPS, EcosystemSource.OFFICIAL_API, EcosystemSource.ANDROID_NATIVE),
            writeOperations = listOf("whatsapp_cloud_send_text"),
            webhookSupported = true,
            restrictions = "Personal WhatsApp accounts cannot use Cloud API; requires Meta WhatsApp Business phone_number_id and permanent token.",
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                writeTool("tpl_whatsapp_business", "whatsapp_cloud_send_text", "Sends a WhatsApp Business Cloud API text message.", "/{phone_number_id}/messages", params = listOf(p("phone_number_id", "Meta Phone Number ID"), b("messaging_product", "whatsapp", true, "whatsapp"), b("to", "Recipient E.164 phone"), b("text", "JSON body object")), tags = listOf("whatsapp", "message"))
            ),
            setupNotes = "For personal accounts use native Android WhatsApp actions; for Business API supply Meta Cloud token."
        ),
        MarketplaceTemplate(
            id = "tpl_zoom_api",
            canonicalServiceId = "zoom",
            name = "Zoom",
            provider = "Zoom",
            category = ConnectorCategory.COMMUNICATION,
            protocol = ConnectorProtocol.OAUTH2_OIDC,
            description = "List upcoming meetings and cloud recordings via Zoom REST API v2.",
            docsUrl = "https://developers.zoom.us/docs/api/",
            defaultBaseUrl = "https://api.zoom.us/v2",
            defaultAuthScheme = AuthSchemeType.OAUTH2_PKCE,
            oauthAuthorizeUrl = "https://zoom.us/oauth/authorize",
            oauthTokenUrl = "https://zoom.us/oauth/token",
            defaultScopes = listOf("meeting:read", "recording:read"),
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.CHATGPT_CONNECTED_APPS, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("zoom_list_meetings"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_zoom_api", "zoom_list_meetings", "Lists upcoming Zoom meetings for the authenticated user.", "/users/me/meetings", params = listOf(q("type", "upcoming or scheduled", false, "upcoming")), tags = listOf("zoom", "meeting", "video"))
            ),
            setupNotes = "Register a Zoom OAuth PKCE app and enter Client ID."
        ),

        // =====================================================================
        // 4. PRODUCTIVITY, KNOWLEDGE & PROJECT MANAGEMENT
        // =====================================================================
        MarketplaceTemplate(
            id = "tpl_notion_api",
            canonicalServiceId = "notion",
            name = "Notion",
            provider = "Notion",
            category = ConnectorCategory.PRODUCTIVITY,
            protocol = ConnectorProtocol.REST,
            description = "Search Notion pages/databases and create pages via official Notion REST v1 (also supports Notion Remote MCP).",
            docsUrl = "https://developers.notion.com/",
            defaultBaseUrl = "https://api.notion.com/v1",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            oauthAuthorizeUrl = "https://api.notion.com/v1/oauth/authorize",
            oauthTokenUrl = "https://api.notion.com/v1/oauth/token",
            defaultHeaders = mapOf("Notion-Version" to "2022-06-28"),
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.CHATGPT_CONNECTED_APPS, EcosystemSource.OFFICIAL_MCP, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("notion_search", "notion_get_page"),
            writeOperations = listOf("notion_create_page"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_notion_api", "notion_search", "Searches Notion pages and databases by title.", "/search", method = "POST", params = listOf(b("query", "Search query")), tags = listOf("notion", "notes", "docs", "wiki")),
                readTool("tpl_notion_api", "notion_get_page", "Retrieves a Notion page by page_id.", "/pages/{page_id}", params = listOf(p("page_id", "Notion page UUID")), tags = listOf("notion", "page")),
                writeTool("tpl_notion_api", "notion_create_page", "Creates a Notion page (requires approval).", "/pages", params = listOf(b("parent", "Parent JSON object"), b("properties", "Page properties JSON")), risk = ToolRiskLevel.MEDIUM, tags = listOf("notion", "create"))
            ),
            setupNotes = "Store Notion Internal Integration Token (ntn_...) or connect Notion Remote MCP (https://mcp.notion.com/mcp)."
        ),
        MarketplaceTemplate(
            id = "tpl_asana_api",
            canonicalServiceId = "asana",
            name = "Asana",
            provider = "Asana",
            category = ConnectorCategory.PRODUCTIVITY,
            protocol = ConnectorProtocol.REST,
            description = "List Asana workspaces, projects, and tasks, and create approved tasks via Asana API v1.",
            docsUrl = "https://developers.asana.com/docs",
            defaultBaseUrl = "https://app.asana.com/api/1.0",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            oauthAuthorizeUrl = "https://app.asana.com/-/oauth_authorize",
            oauthTokenUrl = "https://app.asana.com/-/oauth_token",
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.CHATGPT_CONNECTED_APPS, EcosystemSource.OFFICIAL_MCP, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("asana_list_workspaces", "asana_list_tasks"),
            writeOperations = listOf("asana_create_task"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_asana_api", "asana_list_workspaces", "Lists Asana workspaces.", "/workspaces", tags = listOf("asana", "projects", "tasks")),
                readTool("tpl_asana_api", "asana_list_tasks", "Lists tasks in an Asana project.", "/projects/{project_gid}/tasks", params = listOf(p("project_gid", "Project GID")), tags = listOf("asana", "tasks")),
                writeTool("tpl_asana_api", "asana_create_task", "Creates a task in Asana (requires approval).", "/tasks", params = listOf(b("data", "Task JSON payload with name and workspace/projects")), risk = ToolRiskLevel.MEDIUM, tags = listOf("asana", "create", "task"))
            ),
            setupNotes = "Store Asana Personal Access Token or connect official Asana MCP."
        ),
        MarketplaceTemplate(
            id = "tpl_trello_api",
            canonicalServiceId = "trello",
            name = "Trello",
            provider = "Atlassian",
            category = ConnectorCategory.PRODUCTIVITY,
            protocol = ConnectorProtocol.REST,
            description = "Inspect Trello boards and create cards via Trello REST API v1.",
            docsUrl = "https://developer.atlassian.com/cloud/trello/rest/",
            defaultBaseUrl = "https://api.trello.com/1",
            defaultAuthScheme = AuthSchemeType.API_KEY,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("trello_list_boards"),
            writeOperations = listOf("trello_create_card"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_trello_api", "trello_list_boards", "Lists Trello boards for the authenticated member.", "/members/me/boards", params = listOf(q("token", "Trello user token", true)), tags = listOf("trello", "boards", "kanban")),
                writeTool("tpl_trello_api", "trello_create_card", "Creates a Trello card in a list.", "/cards", params = listOf(q("idList", "Target list ID", true), q("name", "Card name", true), q("token", "Trello user token", true)), risk = ToolRiskLevel.MEDIUM, tags = listOf("trello", "card"))
            ),
            setupNotes = "Requires Trello API key and member token."
        ),
        MarketplaceTemplate(
            id = "tpl_linear_graphql",
            canonicalServiceId = "linear",
            name = "Linear",
            provider = "Linear",
            category = ConnectorCategory.PRODUCTIVITY,
            protocol = ConnectorProtocol.GRAPHQL,
            description = "Query projects, issues, and sprint cycles via Linear GraphQL API or official Linear Remote MCP.",
            docsUrl = "https://developers.linear.app/docs/graphql/working-with-the-graphql-api",
            defaultBaseUrl = "https://api.linear.app/graphql",
            defaultAuthScheme = AuthSchemeType.API_KEY,
            oauthAuthorizeUrl = "https://linear.app/oauth/authorize",
            oauthTokenUrl = "https://api.linear.app/oauth/token",
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.CHATGPT_CONNECTED_APPS, EcosystemSource.OFFICIAL_MCP, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("linear_query"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_linear_graphql", "linear_query", "Executes a Linear GraphQL query.", "/graphql", protocol = ConnectorProtocol.GRAPHQL, method = "QUERY", params = listOf(b("query", "GraphQL query body", true, "{ viewer { name } issues(first: 10) { nodes { identifier title state { name } } } }")), tags = listOf("linear", "issues", "graphql", "tasks"))
            ),
            setupNotes = "Store Linear Personal API Key in Authorization header, or connect https://mcp.linear.app/sse."
        ),
        MarketplaceTemplate(
            id = "tpl_monday_graphql",
            canonicalServiceId = "monday_com",
            name = "monday.com",
            provider = "monday.com",
            category = ConnectorCategory.PRODUCTIVITY,
            protocol = ConnectorProtocol.GRAPHQL,
            description = "Query boards and items via monday.com GraphQL API v2.",
            docsUrl = "https://developer.monday.com/api-reference/docs",
            defaultBaseUrl = "https://api.monday.com/v2",
            defaultAuthScheme = AuthSchemeType.API_KEY,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("monday_query"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_monday_graphql", "monday_query", "Executes a monday.com GraphQL query.", "/v2", protocol = ConnectorProtocol.GRAPHQL, method = "QUERY", params = listOf(b("query", "GraphQL query", true, "{ boards(limit: 5) { id name } }")), tags = listOf("monday", "boards", "project"))
            ),
            setupNotes = "Store monday.com API v2 token in Authorization header."
        ),
        MarketplaceTemplate(
            id = "tpl_airtable_api",
            canonicalServiceId = "airtable",
            name = "Airtable",
            provider = "Airtable",
            category = ConnectorCategory.PRODUCTIVITY,
            protocol = ConnectorProtocol.REST,
            description = "List bases and query table records via official Airtable Web API.",
            docsUrl = "https://airtable.com/developers/web/api/introduction",
            defaultBaseUrl = "https://api.airtable.com/v0",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("airtable_list_bases", "airtable_list_records"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_airtable_api", "airtable_list_bases", "Lists accessible Airtable bases.", "/meta/bases", tags = listOf("airtable", "database", "spreadsheet")),
                readTool("tpl_airtable_api", "airtable_list_records", "Lists records from an Airtable table.", "/{baseId}/{tableIdOrName}", params = listOf(p("baseId", "Airtable base ID"), p("tableIdOrName", "Table ID or name")), tags = listOf("airtable", "records"))
            ),
            setupNotes = "Store Airtable Personal Access Token."
        ),
        MarketplaceTemplate(
            id = "tpl_clickup_api",
            canonicalServiceId = "clickup",
            name = "ClickUp",
            provider = "ClickUp",
            category = ConnectorCategory.PRODUCTIVITY,
            protocol = ConnectorProtocol.REST,
            description = "Inspect workspaces and tasks via ClickUp API v2.",
            docsUrl = "https://clickup.com/api/",
            defaultBaseUrl = "https://api.clickup.com/api/v2",
            defaultAuthScheme = AuthSchemeType.API_KEY,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("clickup_list_teams", "clickup_list_tasks"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_clickup_api", "clickup_list_teams", "Lists ClickUp workspaces/teams.", "/team", tags = listOf("clickup", "tasks", "project")),
                readTool("tpl_clickup_api", "clickup_list_tasks", "Lists tasks in a ClickUp list.", "/list/{list_id}/task", params = listOf(p("list_id", "ClickUp list ID")), tags = listOf("clickup", "tasks"))
            ),
            setupNotes = "Store ClickUp Personal API token in Authorization header."
        ),
        MarketplaceTemplate(
            id = "tpl_todoist_api",
            canonicalServiceId = "todoist",
            name = "Todoist",
            provider = "Doist",
            category = ConnectorCategory.PRODUCTIVITY,
            protocol = ConnectorProtocol.REST,
            description = "Read and create personal/project tasks via Todoist REST API v2.",
            docsUrl = "https://developer.todoist.com/rest/v2/",
            defaultBaseUrl = "https://api.todoist.com/rest/v2",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("todoist_list_tasks"),
            writeOperations = listOf("todoist_create_task"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_todoist_api", "todoist_list_tasks", "Lists active Todoist tasks.", "/tasks", tags = listOf("todoist", "tasks", "todo")),
                writeTool("tpl_todoist_api", "todoist_create_task", "Creates a new Todoist task.", "/tasks", params = listOf(b("content", "Task text"), b("due_string", "Optional due date e.g. tomorrow", false)), risk = ToolRiskLevel.MEDIUM, tags = listOf("todoist", "create"))
            ),
            setupNotes = "Store Todoist API token from Integrations > Developer settings."
        ),
        MarketplaceTemplate(
            id = "tpl_atlassian_jira",
            canonicalServiceId = "atlassian_jira",
            name = "Jira (Atlassian)",
            provider = "Atlassian",
            category = ConnectorCategory.PRODUCTIVITY,
            protocol = ConnectorProtocol.REST,
            description = "Search Jira issues with JQL and create approved tickets via Atlassian Cloud REST v3 or Atlassian Remote MCP.",
            docsUrl = "https://developer.atlassian.com/cloud/jira/platform/rest/v3/intro/",
            defaultBaseUrl = "https://YOUR_SITE.atlassian.net/rest/api/3",
            defaultAuthScheme = AuthSchemeType.BASIC_AUTH,
            oauthAuthorizeUrl = "https://auth.atlassian.com/authorize",
            oauthTokenUrl = "https://auth.atlassian.com/oauth/token",
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.CHATGPT_CONNECTED_APPS, EcosystemSource.OFFICIAL_MCP, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("jira_search_issues"),
            writeOperations = listOf("jira_create_issue"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_atlassian_jira", "jira_search_issues", "Searches Jira issues using JQL.", "/search/jql", params = listOf(q("jql", "JQL query", false, "ORDER BY updated DESC"), q("maxResults", "Max issues", false, "10", "integer")), tags = listOf("jira", "atlassian", "issues", "tickets")),
                writeTool("tpl_atlassian_jira", "jira_create_issue", "Creates a Jira issue (requires approval).", "/issue", params = listOf(b("fields", "Jira fields JSON object")), tags = listOf("jira", "create"))
            ),
            setupNotes = "Set your Atlassian site URL and API token, or connect Atlassian Remote MCP (https://mcp.atlassian.com/v1/sse)."
        ),
        MarketplaceTemplate(
            id = "tpl_atlassian_confluence",
            canonicalServiceId = "atlassian_confluence",
            name = "Confluence (Atlassian)",
            provider = "Atlassian",
            category = ConnectorCategory.PRODUCTIVITY,
            protocol = ConnectorProtocol.REST,
            description = "Search Confluence spaces and knowledge pages via Confluence Cloud REST v2.",
            docsUrl = "https://developer.atlassian.com/cloud/confluence/rest/v2/intro/",
            defaultBaseUrl = "https://YOUR_SITE.atlassian.net/wiki/api/v2",
            defaultAuthScheme = AuthSchemeType.BASIC_AUTH,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.CHATGPT_CONNECTED_APPS, EcosystemSource.OFFICIAL_MCP, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("confluence_list_pages"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_atlassian_confluence", "confluence_list_pages", "Lists Confluence pages.", "/pages", params = listOf(q("limit", "Max pages", false, "10", "integer")), tags = listOf("confluence", "wiki", "docs", "atlassian"))
            ),
            setupNotes = "Set your Atlassian site URL and API token."
        ),
        MarketplaceTemplate(
            id = "tpl_dropbox_api",
            canonicalServiceId = "dropbox",
            name = "Dropbox",
            provider = "Dropbox",
            category = ConnectorCategory.CLOUD_STORAGE,
            protocol = ConnectorProtocol.OAUTH2_OIDC,
            description = "Search and list files in Dropbox via Dropbox HTTP API v2.",
            docsUrl = "https://www.dropbox.com/developers/documentation/http/documentation",
            defaultBaseUrl = "https://api.dropboxapi.com/2",
            defaultAuthScheme = AuthSchemeType.OAUTH2_PKCE,
            oauthAuthorizeUrl = "https://www.dropbox.com/oauth2/authorize",
            oauthTokenUrl = "https://api.dropboxapi.com/oauth2/token",
            ecosystemSources = listOf(EcosystemSource.CHATGPT_CONNECTED_APPS, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("dropbox_list_folder"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_dropbox_api", "dropbox_list_folder", "Lists files in a Dropbox folder.", "/files/list_folder", method = "POST", params = listOf(b("path", "Folder path (empty for root)", true, "")), tags = listOf("dropbox", "files", "storage"))
            ),
            setupNotes = "Configure Dropbox OAuth PKCE Client ID or generated access token."
        ),
        MarketplaceTemplate(
            id = "tpl_box_api",
            canonicalServiceId = "box",
            name = "Box",
            provider = "Box",
            category = ConnectorCategory.CLOUD_STORAGE,
            protocol = ConnectorProtocol.OAUTH2_OIDC,
            description = "Search enterprise files and folders via Box Content API v2.0.",
            docsUrl = "https://developer.box.com/reference/",
            defaultBaseUrl = "https://api.box.com/2.0",
            defaultAuthScheme = AuthSchemeType.OAUTH2_PKCE,
            oauthAuthorizeUrl = "https://account.box.com/api/oauth2/authorize",
            oauthTokenUrl = "https://api.box.com/oauth2/token",
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.CHATGPT_CONNECTED_APPS, EcosystemSource.OFFICIAL_MCP, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("box_search_content"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_box_api", "box_search_content", "Searches files and folders in Box.", "/search", params = listOf(q("query", "Search query", true), q("limit", "Max items", false, "10", "integer")), tags = listOf("box", "files", "storage"))
            ),
            setupNotes = "Configure Box OAuth 2.0 Client ID or developer token."
        ),

        // =====================================================================
        // 5. DEVELOPMENT & INFRASTRUCTURE
        // =====================================================================
        MarketplaceTemplate(
            id = "tpl_github_rest",
            canonicalServiceId = "github",
            name = "GitHub",
            provider = "GitHub",
            category = ConnectorCategory.DEVELOPER_DEVOPS,
            protocol = ConnectorProtocol.REST,
            description = "Repositories, issues, pull requests, and code search via GitHub REST v3 (also supports official GitHub Remote MCP).",
            docsUrl = "https://docs.github.com/rest",
            defaultBaseUrl = "https://api.github.com",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            defaultScopes = listOf("repo", "read:user"),
            oauthAuthorizeUrl = "https://github.com/login/oauth/authorize",
            oauthTokenUrl = "https://github.com/login/oauth/access_token",
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.CHATGPT_CONNECTED_APPS, EcosystemSource.GEMINI_CONNECTED_APPS, EcosystemSource.OFFICIAL_MCP, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("list_user_repos", "github_list_issues"),
            writeOperations = listOf("create_issue"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_github_rest", "list_user_repos", "Lists repositories for the authenticated GitHub user.", "/user/repos", params = listOf(q("per_page", "Results per page", false, "10", "integer")), tags = listOf("github", "git", "repos", "code", "developer")),
                readTool("tpl_github_rest", "github_list_issues", "Lists open issues in a repository.", "/repos/{owner}/{repo}/issues", params = listOf(p("owner", "Owner"), p("repo", "Repository"), q("state", "open/closed/all", false, "open")), tags = listOf("github", "issues", "bugs")),
                writeTool("tpl_github_rest", "create_issue", "Creates a new issue in a GitHub repository.", "/repos/{owner}/{repo}/issues", params = listOf(p("owner", "Repository owner"), p("repo", "Repository name"), b("title", "Issue title"), b("body", "Issue description", false)), tags = listOf("github", "issue", "bug", "developer"))
            ),
            setupNotes = "Store GitHub PAT in Credential Vault, or connect GitHub Remote MCP (https://api.githubcopilot.com/mcp/)."
        ),
        MarketplaceTemplate(
            id = "tpl_gitlab_api",
            canonicalServiceId = "gitlab",
            name = "GitLab",
            provider = "GitLab",
            category = ConnectorCategory.DEVELOPER_DEVOPS,
            protocol = ConnectorProtocol.REST,
            description = "Inspect GitLab projects, merge requests, and issues via GitLab REST v4.",
            docsUrl = "https://docs.gitlab.com/ee/api/rest/",
            defaultBaseUrl = "https://gitlab.com/api/v4",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("gitlab_list_projects"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_gitlab_api", "gitlab_list_projects", "Lists GitLab projects accessible to the token.", "/projects", params = listOf(q("membership", "true", false, "true"), q("per_page", "Max", false, "10", "integer")), tags = listOf("gitlab", "git", "code"))
            ),
            setupNotes = "Store GitLab Personal Access Token."
        ),
        MarketplaceTemplate(
            id = "tpl_bitbucket_api",
            canonicalServiceId = "bitbucket",
            name = "Bitbucket",
            provider = "Atlassian",
            category = ConnectorCategory.DEVELOPER_DEVOPS,
            protocol = ConnectorProtocol.REST,
            description = "Query Bitbucket repositories and pull requests via Bitbucket Cloud API 2.0.",
            docsUrl = "https://developer.atlassian.com/cloud/bitbucket/rest/",
            defaultBaseUrl = "https://api.bitbucket.org/2.0",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            ecosystemSources = listOf(EcosystemSource.OFFICIAL_API),
            readOperations = listOf("bitbucket_list_workspaces"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_bitbucket_api", "bitbucket_list_workspaces", "Lists Bitbucket workspaces.", "/workspaces", tags = listOf("bitbucket", "git", "code"))
            ),
            setupNotes = "Store Bitbucket OAuth token or app password."
        ),
        MarketplaceTemplate(
            id = "tpl_supabase_rest",
            canonicalServiceId = "supabase",
            name = "Supabase",
            provider = "Supabase",
            category = ConnectorCategory.DATABASES,
            protocol = ConnectorProtocol.REST,
            description = "Query Postgres tables and RPC functions via Supabase PostgREST or Supabase Remote MCP.",
            docsUrl = "https://supabase.com/docs/guides/api",
            defaultBaseUrl = "https://YOUR_PROJECT.supabase.co/rest/v1",
            defaultAuthScheme = AuthSchemeType.API_KEY,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.OFFICIAL_MCP, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("supabase_select"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_supabase_rest", "supabase_select", "Fetches rows from a Supabase table with PostgREST filter.", "/{table}", params = listOf(p("table", "Table name"), q("select", "Columns", false, "*")), tags = listOf("supabase", "postgres", "database", "sql"))
            ),
            setupNotes = "Replace YOUR_PROJECT in Base URL and store Supabase API key in 'apikey' header."
        ),
        MarketplaceTemplate(
            id = "tpl_vercel_api",
            canonicalServiceId = "vercel",
            name = "Vercel",
            provider = "Vercel",
            category = ConnectorCategory.DEVELOPER_DEVOPS,
            protocol = ConnectorProtocol.REST,
            description = "Inspect Vercel projects and deployment statuses via Vercel REST API or Vercel MCP.",
            docsUrl = "https://vercel.com/docs/rest-api",
            defaultBaseUrl = "https://api.vercel.com",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.OFFICIAL_MCP, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("vercel_list_projects", "vercel_list_deployments"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_vercel_api", "vercel_list_projects", "Lists Vercel projects.", "/v9/projects", tags = listOf("vercel", "deploy", "hosting")),
                readTool("tpl_vercel_api", "vercel_list_deployments", "Lists recent Vercel deployments.", "/v6/deployments", params = listOf(q("limit", "Max deployments", false, "10", "integer")), tags = listOf("vercel", "deployments"))
            ),
            setupNotes = "Store Vercel Access Token in Credential Vault."
        ),
        MarketplaceTemplate(
            id = "tpl_cloudflare_api",
            canonicalServiceId = "cloudflare",
            name = "Cloudflare",
            provider = "Cloudflare",
            category = ConnectorCategory.DEVELOPER_DEVOPS,
            protocol = ConnectorProtocol.REST,
            description = "Inspect zones, DNS, and Workers via Cloudflare API v4 or official Cloudflare Remote MCP.",
            docsUrl = "https://developers.cloudflare.com/api/",
            defaultBaseUrl = "https://api.cloudflare.com/client/v4",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.OFFICIAL_MCP, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("cloudflare_list_zones"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_cloudflare_api", "cloudflare_list_zones", "Lists Cloudflare DNS zones.", "/zones", tags = listOf("cloudflare", "dns", "workers"))
            ),
            setupNotes = "Store scoped Cloudflare API Token."
        ),
        MarketplaceTemplate(
            id = "tpl_sentry_api",
            canonicalServiceId = "sentry",
            name = "Sentry",
            provider = "Sentry",
            category = ConnectorCategory.DEVELOPER_DEVOPS,
            protocol = ConnectorProtocol.REST,
            description = "Inspect production error issues and stack traces via Sentry API or official Sentry Remote MCP.",
            docsUrl = "https://docs.sentry.io/api/",
            defaultBaseUrl = "https://sentry.io/api/0",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.OFFICIAL_MCP, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("sentry_list_projects"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_sentry_api", "sentry_list_projects", "Lists Sentry projects.", "/projects/", tags = listOf("sentry", "errors", "monitoring"))
            ),
            setupNotes = "Store Sentry Auth Token or connect https://mcp.sentry.dev/mcp."
        ),
        MarketplaceTemplate(
            id = "tpl_datadog_api",
            canonicalServiceId = "datadog",
            name = "Datadog",
            provider = "Datadog",
            category = ConnectorCategory.DEVELOPER_DEVOPS,
            protocol = ConnectorProtocol.REST,
            description = "Query monitors and service health via Datadog REST API.",
            docsUrl = "https://docs.datadoghq.com/api/latest/",
            defaultBaseUrl = "https://api.datadoghq.com/api/v1",
            defaultAuthScheme = AuthSchemeType.API_KEY,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("datadog_list_monitors"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_datadog_api", "datadog_list_monitors", "Lists Datadog monitors.", "/monitor", tags = listOf("datadog", "monitoring", "alerts"))
            ),
            setupNotes = "Configure DD-API-KEY and DD-APPLICATION-KEY headers."
        ),
        MarketplaceTemplate(
            id = "tpl_posthog_api",
            canonicalServiceId = "posthog",
            name = "PostHog",
            provider = "PostHog",
            category = ConnectorCategory.BUSINESS_CRM_FINANCE,
            protocol = ConnectorProtocol.REST,
            description = "Query analytics projects and insights via PostHog API.",
            docsUrl = "https://posthog.com/docs/api",
            defaultBaseUrl = "https://us.posthog.com/api",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("posthog_list_projects"),
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_posthog_api", "posthog_list_projects", "Lists PostHog analytics projects.", "/projects/", tags = listOf("posthog", "analytics", "metrics"))
            ),
            setupNotes = "Store PostHog Personal API Key."
        ),

        // =====================================================================
        // 6. DESIGN & CREATIVE SERVICES
        // =====================================================================
        MarketplaceTemplate(
            id = "tpl_figma_rest",
            canonicalServiceId = "figma",
            name = "Figma",
            provider = "Figma",
            category = ConnectorCategory.DESIGN_MEDIA,
            protocol = ConnectorProtocol.REST,
            description = "Inspect Figma files, components, and comments via Figma REST v1 or official Figma MCP.",
            docsUrl = "https://www.figma.com/developers/api",
            defaultBaseUrl = "https://api.figma.com/v1",
            defaultAuthScheme = AuthSchemeType.API_KEY,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.CHATGPT_CONNECTED_APPS, EcosystemSource.OFFICIAL_MCP, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("figma_get_me", "figma_get_file"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_figma_rest", "figma_get_me", "Retrieves authenticated Figma user info.", "/me", tags = listOf("figma", "design", "ui")),
                readTool("tpl_figma_rest", "figma_get_file", "Inspects a Figma file JSON tree.", "/files/{key}", params = listOf(p("key", "Figma file key"), q("depth", "Tree depth", false, "2", "integer")), tags = listOf("figma", "design", "file"))
            ),
            setupNotes = "Store Figma Personal Access Token in header 'X-Figma-Token'."
        ),
        MarketplaceTemplate(
            id = "tpl_canva_connect",
            canonicalServiceId = "canva",
            name = "Canva",
            provider = "Canva",
            category = ConnectorCategory.DESIGN_MEDIA,
            protocol = ConnectorProtocol.OAUTH2_OIDC,
            description = "List designs and folders via official Canva Connect API v1 or Canva Remote MCP.",
            docsUrl = "https://www.canva.dev/docs/connect/",
            defaultBaseUrl = "https://api.canva.com/rest/v1",
            defaultAuthScheme = AuthSchemeType.OAUTH2_PKCE,
            oauthAuthorizeUrl = "https://www.canva.com/api/oauth/authorize",
            oauthTokenUrl = "https://api.canva.com/rest/v1/oauth/token",
            defaultScopes = listOf("design:meta:read"),
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.CHATGPT_CONNECTED_APPS, EcosystemSource.OFFICIAL_MCP, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("canva_list_designs"),
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_canva_connect", "canva_list_designs", "Lists user's Canva designs.", "/designs", params = listOf(q("query", "Search query")), tags = listOf("canva", "design", "graphics"))
            ),
            setupNotes = "Requires Canva Connect OAuth 2.0 PKCE Client ID."
        ),
        MarketplaceTemplate(
            id = "tpl_adobe_cc_restricted",
            canonicalServiceId = "adobe_creative_cloud",
            name = "Adobe Creative Cloud",
            provider = "Adobe",
            category = ConnectorCategory.DESIGN_MEDIA,
            protocol = ConnectorProtocol.REST,
            description = "Photoshop/Firefly/Acrobat APIs require paid Adobe Developer Enterprise credentials and server-side IMS OAuth.",
            docsUrl = "https://developer.adobe.com/apis",
            defaultBaseUrl = "https://image.adobe.io",
            defaultAuthScheme = AuthSchemeType.OAUTH2_PKCE,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY),
            restrictions = "Blocked without paid Adobe Developer / Enterprise IMS OAuth credentials.",
            implementationState = ConnectorImplementationState.BLOCKED_NO_PUBLIC_API,
            setupNotes = "Requires commercial Adobe Developer Enterprise API access."
        ),

        // =====================================================================
        // 7. BUSINESS, CRM, FINANCE, E-COMMERCE & SUPPORT
        // =====================================================================
        MarketplaceTemplate(
            id = "tpl_hubspot_api",
            canonicalServiceId = "hubspot",
            name = "HubSpot",
            provider = "HubSpot",
            category = ConnectorCategory.BUSINESS_CRM_FINANCE,
            protocol = ConnectorProtocol.REST,
            description = "Query CRM contacts, companies, and deals via HubSpot CRM v3 or HubSpot Remote MCP.",
            docsUrl = "https://developers.hubspot.com/docs/api/overview",
            defaultBaseUrl = "https://api.hubapi.com",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            oauthAuthorizeUrl = "https://app.hubspot.com/oauth/authorize",
            oauthTokenUrl = "https://api.hubapi.com/oauth/v1/token",
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.CHATGPT_CONNECTED_APPS, EcosystemSource.OFFICIAL_MCP, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("hubspot_list_contacts", "hubspot_list_deals"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_hubspot_api", "hubspot_list_contacts", "Lists CRM contacts from HubSpot.", "/crm/v3/objects/contacts", params = listOf(q("limit", "Max contacts", false, "10", "integer")), tags = listOf("hubspot", "crm", "contacts")),
                readTool("tpl_hubspot_api", "hubspot_list_deals", "Lists CRM deals in HubSpot.", "/crm/v3/objects/deals", params = listOf(q("limit", "Max deals", false, "10", "integer")), tags = listOf("hubspot", "crm", "deals", "sales"))
            ),
            setupNotes = "Store HubSpot Private App token or configure HubSpot OAuth."
        ),
        MarketplaceTemplate(
            id = "tpl_salesforce_api",
            canonicalServiceId = "salesforce",
            name = "Salesforce",
            provider = "Salesforce",
            category = ConnectorCategory.BUSINESS_CRM_FINANCE,
            protocol = ConnectorProtocol.OAUTH2_OIDC,
            description = "Execute SOQL queries against Salesforce CRM objects via Salesforce REST API.",
            docsUrl = "https://developer.salesforce.com/docs/atlas.en-us.api_rest.meta/api_rest/",
            defaultBaseUrl = "https://YOUR_INSTANCE.my.salesforce.com/services/data/v60.0",
            defaultAuthScheme = AuthSchemeType.OAUTH2_PKCE,
            oauthAuthorizeUrl = "https://login.salesforce.com/services/oauth2/authorize",
            oauthTokenUrl = "https://login.salesforce.com/services/oauth2/token",
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.CHATGPT_CONNECTED_APPS, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("salesforce_soql_query"),
            webhookSupported = true,
            restrictions = "Requires Salesforce Connected App OAuth Client ID and instance domain.",
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_salesforce_api", "salesforce_soql_query", "Runs a read-only SOQL query.", "/query", params = listOf(q("q", "SOQL query string", true)), tags = listOf("salesforce", "crm", "soql"))
            ),
            setupNotes = "Set your Salesforce instance URL and Connected App OAuth Client ID."
        ),
        MarketplaceTemplate(
            id = "tpl_stripe_api",
            canonicalServiceId = "stripe",
            name = "Stripe",
            provider = "Stripe",
            category = ConnectorCategory.BUSINESS_CRM_FINANCE,
            protocol = ConnectorProtocol.REST,
            description = "Inspect customers, invoices, and balance via Stripe API v1 or official Stripe Remote MCP.",
            docsUrl = "https://docs.stripe.com/api",
            defaultBaseUrl = "https://api.stripe.com/v1",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.OFFICIAL_MCP, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("stripe_get_balance", "stripe_list_customers"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_stripe_api", "stripe_get_balance", "Retrieves current Stripe account balance.", "/balance", tags = listOf("stripe", "finance", "payments")),
                readTool("tpl_stripe_api", "stripe_list_customers", "Lists recent Stripe customers.", "/customers", params = listOf(q("limit", "Max customers", false, "10", "integer")), tags = listOf("stripe", "customers"))
            ),
            setupNotes = "Use a read-only Restricted Stripe API Key (rk_...)."
        ),
        MarketplaceTemplate(
            id = "tpl_paypal_api",
            canonicalServiceId = "paypal",
            name = "PayPal",
            provider = "PayPal",
            category = ConnectorCategory.BUSINESS_CRM_FINANCE,
            protocol = ConnectorProtocol.REST,
            description = "Inspect invoices and transactions via PayPal REST API or PayPal Remote MCP.",
            docsUrl = "https://developer.paypal.com/api/rest/",
            defaultBaseUrl = "https://api-m.paypal.com/v2",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.OFFICIAL_MCP, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("paypal_list_invoices"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_paypal_api", "paypal_list_invoices", "Lists PayPal invoices.", "/invoicing/invoices", params = listOf(q("page_size", "Max invoices", false, "10", "integer")), tags = listOf("paypal", "invoices", "finance"))
            ),
            setupNotes = "Requires PayPal OAuth access token or PayPal Remote MCP."
        ),
        MarketplaceTemplate(
            id = "tpl_square_api",
            canonicalServiceId = "square",
            name = "Square",
            provider = "Square",
            category = ConnectorCategory.BUSINESS_CRM_FINANCE,
            protocol = ConnectorProtocol.REST,
            description = "Inspect merchant locations and catalog via Square API v2 or official Square MCP.",
            docsUrl = "https://developer.squareup.com/reference/square",
            defaultBaseUrl = "https://connect.squareup.com/v2",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.OFFICIAL_MCP, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("square_list_locations"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_square_api", "square_list_locations", "Lists Square merchant locations.", "/locations", tags = listOf("square", "pos", "commerce"))
            ),
            setupNotes = "Store Square Access Token."
        ),
        MarketplaceTemplate(
            id = "tpl_intercom_api",
            canonicalServiceId = "intercom",
            name = "Intercom",
            provider = "Intercom",
            category = ConnectorCategory.BUSINESS_CRM_FINANCE,
            protocol = ConnectorProtocol.REST,
            description = "Inspect customer support conversations via Intercom API or Intercom Remote MCP.",
            docsUrl = "https://developers.intercom.com/docs",
            defaultBaseUrl = "https://api.intercom.io",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.OFFICIAL_MCP, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("intercom_list_conversations"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_intercom_api", "intercom_list_conversations", "Lists recent Intercom support conversations.", "/conversations", params = listOf(q("per_page", "Max", false, "10", "integer")), tags = listOf("intercom", "support", "tickets"))
            ),
            setupNotes = "Store Intercom Access Token."
        ),
        MarketplaceTemplate(
            id = "tpl_zendesk_api",
            canonicalServiceId = "zendesk",
            name = "Zendesk",
            provider = "Zendesk",
            category = ConnectorCategory.BUSINESS_CRM_FINANCE,
            protocol = ConnectorProtocol.REST,
            description = "Query customer support tickets via Zendesk REST API v2.",
            docsUrl = "https://developer.zendesk.com/api-reference/",
            defaultBaseUrl = "https://YOUR_SUBDOMAIN.zendesk.com/api/v2",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.CHATGPT_CONNECTED_APPS, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("zendesk_list_tickets"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_zendesk_api", "zendesk_list_tickets", "Lists recent Zendesk tickets.", "/tickets.json", params = listOf(q("per_page", "Max", false, "10", "integer")), tags = listOf("zendesk", "support", "tickets"))
            ),
            setupNotes = "Set your Zendesk subdomain and OAuth/API token."
        ),
        MarketplaceTemplate(
            id = "tpl_shopify_api",
            canonicalServiceId = "shopify",
            name = "Shopify",
            provider = "Shopify",
            category = ConnectorCategory.BUSINESS_CRM_FINANCE,
            protocol = ConnectorProtocol.REST,
            description = "Inspect products and order counts via Shopify Admin REST API.",
            docsUrl = "https://shopify.dev/docs/api/admin-rest",
            defaultBaseUrl = "https://YOUR_STORE.myshopify.com/admin/api/2024-10",
            defaultAuthScheme = AuthSchemeType.API_KEY,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("shopify_list_products"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_shopify_api", "shopify_list_products", "Lists products in the Shopify store.", "/products.json", params = listOf(q("limit", "Max", false, "10", "integer")), tags = listOf("shopify", "ecommerce", "products"))
            ),
            setupNotes = "Store Admin API access token in header 'X-Shopify-Access-Token'."
        ),
        MarketplaceTemplate(
            id = "tpl_mailchimp_api",
            canonicalServiceId = "mailchimp",
            name = "Mailchimp",
            provider = "Intuit Mailchimp",
            category = ConnectorCategory.BUSINESS_CRM_FINANCE,
            protocol = ConnectorProtocol.REST,
            description = "Inspect audiences and campaigns via Mailchimp Marketing API 3.0.",
            docsUrl = "https://mailchimp.com/developer/marketing/api/",
            defaultBaseUrl = "https://us1.api.mailchimp.com/3.0",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            ecosystemSources = listOf(EcosystemSource.OFFICIAL_API),
            readOperations = listOf("mailchimp_list_campaigns"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_mailchimp_api", "mailchimp_list_campaigns", "Lists Mailchimp email campaigns.", "/campaigns", params = listOf(q("count", "Max", false, "10", "integer")), tags = listOf("mailchimp", "marketing", "email"))
            ),
            setupNotes = "Set your datacenter prefix (e.g. us19) and Mailchimp API key."
        ),

        // =====================================================================
        // 8. RESEARCH, KNOWLEDGE, MEDIA, AUTOMATION & OPEN PROTOCOLS
        // =====================================================================
        MarketplaceTemplate(
            id = "tpl_wikipedia_rest",
            canonicalServiceId = "wikipedia",
            name = "Wikipedia",
            provider = "Wikimedia Foundation",
            category = ConnectorCategory.RESEARCH_KNOWLEDGE,
            protocol = ConnectorProtocol.REST,
            description = "Public encyclopedia search and article summaries via official Wikimedia REST API (no API key required).",
            docsUrl = "https://en.wikipedia.org/api/rest_v1/",
            defaultBaseUrl = "https://en.wikipedia.org/api/rest_v1",
            defaultAuthScheme = AuthSchemeType.NONE,
            ecosystemSources = listOf(EcosystemSource.OFFICIAL_API),
            readOperations = listOf("wikipedia_summary"),
            implementationState = ConnectorImplementationState.IMPLEMENTED,
            defaultTools = listOf(
                readTool("tpl_wikipedia_rest", "wikipedia_summary", "Fetches a structured Wikipedia article summary by title.", "/page/summary/{title}", params = listOf(p("title", "Article title")), tags = listOf("wikipedia", "research", "encyclopedia", "knowledge"))
            ),
            setupNotes = "Ready to connect immediately — public Wikimedia API requires no credentials."
        ),
        MarketplaceTemplate(
            id = "tpl_arxiv_api",
            canonicalServiceId = "arxiv",
            name = "arXiv Research Papers",
            provider = "Cornell University / arXiv",
            category = ConnectorCategory.RESEARCH_KNOWLEDGE,
            protocol = ConnectorProtocol.REST,
            description = "Search peer-reviewed preprints and academic abstracts via official arXiv API (no API key required).",
            docsUrl = "https://info.arxiv.org/help/api/index.html",
            defaultBaseUrl = "https://export.arxiv.org/api",
            defaultAuthScheme = AuthSchemeType.NONE,
            ecosystemSources = listOf(EcosystemSource.OFFICIAL_API),
            readOperations = listOf("arxiv_search"),
            implementationState = ConnectorImplementationState.IMPLEMENTED,
            defaultTools = listOf(
                readTool("tpl_arxiv_api", "arxiv_search", "Searches arXiv academic papers.", "/query", params = listOf(q("search_query", "arXiv query e.g. all:transformers", true), q("max_results", "Max papers", false, "5", "integer")), tags = listOf("arxiv", "academic", "papers", "research"))
            ),
            setupNotes = "Ready to connect immediately — public academic API requires no credentials."
        ),
        MarketplaceTemplate(
            id = "tpl_openstax_catalog",
            canonicalServiceId = "openstax",
            name = "OpenStax Textbooks",
            provider = "Rice University / OpenStax",
            category = ConnectorCategory.RESEARCH_KNOWLEDGE,
            protocol = ConnectorProtocol.REST,
            description = "Listed in Gemini Connected Apps; inspect open peer-reviewed textbook metadata via OpenStax public CMS API.",
            docsUrl = "https://openstax.org/",
            defaultBaseUrl = "https://openstax.org/apps/cms/api/v2",
            defaultAuthScheme = AuthSchemeType.NONE,
            ecosystemSources = listOf(EcosystemSource.GEMINI_CONNECTED_APPS, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("openstax_search_books"),
            implementationState = ConnectorImplementationState.IMPLEMENTED,
            defaultTools = listOf(
                readTool("tpl_openstax_catalog", "openstax_search_books", "Searches OpenStax peer-reviewed textbooks.", "/pages/", params = listOf(q("type", "books.Book", false, "books.Book"), q("search", "Subject or title")), tags = listOf("openstax", "textbooks", "education", "research"))
            ),
            setupNotes = "Public OpenStax catalog endpoint; no credentials required."
        ),
        MarketplaceTemplate(
            id = "tpl_tavily_search",
            canonicalServiceId = "tavily_search",
            name = "Tavily Web Search",
            provider = "Tavily",
            category = ConnectorCategory.RESEARCH_KNOWLEDGE,
            protocol = ConnectorProtocol.REST,
            description = "Structured web search with citations and page snippets via official Tavily Search API.",
            docsUrl = "https://docs.tavily.com/",
            defaultBaseUrl = "https://api.tavily.com",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.OFFICIAL_MCP, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("tavily_search"),
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_tavily_search", "tavily_search", "Runs a structured web search returning source URLs and excerpts.", "/search", method = "POST", params = listOf(b("query", "Search query"), b("max_results", "Max sources", false, "5", "integer")), tags = listOf("search", "web", "research", "tavily"))
            ),
            setupNotes = "Store Tavily API Key in Credential Vault."
        ),
        MarketplaceTemplate(
            id = "tpl_brave_search",
            canonicalServiceId = "brave_search",
            name = "Brave Web Search",
            provider = "Brave",
            category = ConnectorCategory.RESEARCH_KNOWLEDGE,
            protocol = ConnectorProtocol.REST,
            description = "Independent web and news search via official Brave Search API.",
            docsUrl = "https://brave.com/search/api/",
            defaultBaseUrl = "https://api.search.brave.com/res/v1",
            defaultAuthScheme = AuthSchemeType.API_KEY,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("brave_web_search"),
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_brave_search", "brave_web_search", "Searches the web via Brave Search API.", "/web/search", params = listOf(q("q", "Search query", true), q("count", "Results count", false, "5", "integer")), tags = listOf("brave", "search", "web", "news"))
            ),
            setupNotes = "Store Brave Subscription Token in header 'X-Subscription-Token'."
        ),
        MarketplaceTemplate(
            id = "tpl_spotify_api",
            canonicalServiceId = "spotify",
            name = "Spotify",
            provider = "Spotify",
            category = ConnectorCategory.DESIGN_MEDIA,
            protocol = ConnectorProtocol.OAUTH2_OIDC,
            description = "Listed in Gemini Connected Apps; search tracks/podcasts and inspect playlists via official Spotify Web API.",
            docsUrl = "https://developer.spotify.com/documentation/web-api",
            defaultBaseUrl = "https://api.spotify.com/v1",
            defaultAuthScheme = AuthSchemeType.OAUTH2_PKCE,
            oauthAuthorizeUrl = "https://accounts.spotify.com/authorize",
            oauthTokenUrl = "https://accounts.spotify.com/api/token",
            defaultScopes = listOf("user-read-playback-state", "playlist-read-private"),
            ecosystemSources = listOf(EcosystemSource.GEMINI_CONNECTED_APPS, EcosystemSource.OFFICIAL_API),
            readOperations = listOf("spotify_search"),
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_spotify_api", "spotify_search", "Searches Spotify tracks, albums, or podcasts.", "/search", params = listOf(q("q", "Search query", true), q("type", "track,album,show", false, "track"), q("limit", "Max results", false, "5", "integer")), tags = listOf("spotify", "music", "podcast"))
            ),
            setupNotes = "Configure Spotify OAuth 2.0 PKCE Client ID."
        ),
        MarketplaceTemplate(
            id = "tpl_n8n_webhook",
            canonicalServiceId = "automation_webhook",
            name = "Zapier / Make / n8n Webhook",
            provider = "Automation Webhook",
            category = ConnectorCategory.AUTOMATION,
            protocol = ConnectorProtocol.WEBHOOK,
            description = "Trigger Zapier, Make, or n8n external automation scenarios with HMAC-SHA256 signed JSON payloads.",
            docsUrl = "https://docs.n8n.io/integrations/builtin/core-nodes/n8n-nodes-base.webhook/",
            defaultBaseUrl = "https://your-instance.example.com/webhook/kavya",
            defaultAuthScheme = AuthSchemeType.HMAC_WEBHOOK,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.OFFICIAL_MCP, EcosystemSource.OFFICIAL_API),
            writeOperations = listOf("trigger_automation_webhook"),
            webhookSupported = true,
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                writeTool("tpl_n8n_webhook", "trigger_automation_webhook", "Dispatches an HMAC-SHA256 signed event payload to your automation webhook.", "", protocol = ConnectorProtocol.WEBHOOK, params = listOf(b("event", "Event name"), b("payload", "JSON payload or note")), risk = ToolRiskLevel.MEDIUM, tags = listOf("n8n", "zapier", "make", "webhook", "automation"))
            ),
            setupNotes = "Enter your exact HTTPS webhook URL and optional HMAC signing secret."
        ),
        MarketplaceTemplate(
            id = "tpl_home_assistant",
            canonicalServiceId = "home_assistant",
            name = "Home Assistant",
            provider = "Home Assistant",
            category = ConnectorCategory.SMART_HOME,
            protocol = ConnectorProtocol.REST,
            description = "Inspect smart home states and invoke Home Assistant services.",
            docsUrl = "https://developers.home-assistant.io/docs/api/rest/",
            defaultBaseUrl = "http://homeassistant.local:8123/api",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            ecosystemSources = listOf(EcosystemSource.OFFICIAL_API),
            readOperations = listOf("ha_get_states"),
            implementationState = ConnectorImplementationState.SETUP_REQUIRED,
            defaultTools = listOf(
                readTool("tpl_home_assistant", "ha_get_states", "Reads current sensor and device entity states.", "/states", tags = listOf("home", "iot", "smart home", "lights", "sensors"))
            ),
            setupNotes = "Requires Long-Lived Access Token + Local Dev / LAN access if on private Wi-Fi."
        ),
        MarketplaceTemplate(
            id = "tpl_remote_mcp",
            canonicalServiceId = "remote_mcp_server",
            name = "Universal Remote MCP Server",
            provider = "Model Context Protocol",
            category = ConnectorCategory.AI_ML,
            protocol = ConnectorProtocol.MCP,
            description = "Connect any vendor-hosted MCP Streamable HTTP / SSE server (Anthropic, Cloudflare, Notion, Linear, Sentry, Zapier, etc.) and auto-discover its tools.",
            docsUrl = "https://modelcontextprotocol.io/specification/2025-03-26",
            defaultBaseUrl = "https://mcp.example.com/mcp",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            ecosystemSources = listOf(EcosystemSource.CLAUDE_DIRECTORY, EcosystemSource.CHATGPT_CONNECTED_APPS, EcosystemSource.OFFICIAL_MCP),
            implementationState = ConnectorImplementationState.IMPLEMENTED,
            defaultTools = emptyList(),
            setupNotes = "Enter any remote HTTPS MCP server URL and token, then tap Connect & Discover Tools. Desktop stdio processes are not supported natively on Android."
        ),
        MarketplaceTemplate(
            id = "tpl_a2a_agent",
            canonicalServiceId = "a2a_partner_agent",
            name = "A2A Partner Agent",
            provider = "Agent-to-Agent",
            category = ConnectorCategory.AI_ML,
            protocol = ConnectorProtocol.A2A,
            description = "Discover /.well-known/agent.json and delegate specialized tasks via A2A JSON-RPC.",
            docsUrl = "https://google.github.io/A2A/",
            defaultBaseUrl = "https://agent.example.com",
            defaultAuthScheme = AuthSchemeType.BEARER_TOKEN,
            ecosystemSources = listOf(EcosystemSource.OFFICIAL_API),
            writeOperations = listOf("a2a_send_task"),
            implementationState = ConnectorImplementationState.IMPLEMENTED,
            defaultTools = listOf(
                writeTool("tpl_a2a_agent", "a2a_send_task", "Delegates a natural-language task to the external A2A agent.", "", protocol = ConnectorProtocol.A2A, method = "tasks/send", params = listOf(b("prompt", "Task prompt for external agent")), risk = ToolRiskLevel.MEDIUM, tags = listOf("a2a", "agent", "delegate"))
            ),
            setupNotes = "Enter agent base URL to fetch its Agent Card and enable task delegation."
        )
    )

    fun findTemplateByCanonicalId(canonicalId: String): MarketplaceTemplate? =
        templates.firstOrNull { it.canonicalServiceId.equals(canonicalId, ignoreCase = true) || it.id.equals(canonicalId, ignoreCase = true) }

    /**
     * Computes truthful, non-inflated discovery statistics across Claude, ChatGPT, and Gemini
     * alongside the exact deduplicated canonical service count.
     */
    fun computeCatalogStats(existingConnectors: List<ConnectorDefinition> = emptyList()): CatalogRefreshReport {
        val claudeCount = templates.count { EcosystemSource.CLAUDE_DIRECTORY in it.ecosystemSources }
        val chatGptCount = templates.count { EcosystemSource.CHATGPT_CONNECTED_APPS in it.ecosystemSources }
        val geminiCount = templates.count { EcosystemSource.GEMINI_CONNECTED_APPS in it.ecosystemSources }
        val rawListings = claudeCount + chatGptCount + geminiCount
        val uniqueCanonical = templates.map { it.canonicalServiceId }.toSet().size
        val implemented = templates.count { it.implementationState == ConnectorImplementationState.IMPLEMENTED }
        val setupRequired = templates.count { it.implementationState == ConnectorImplementationState.SETUP_REQUIRED }
        val blocked = templates.count { it.implementationState == ConnectorImplementationState.BLOCKED_NO_PUBLIC_API }
        val preservedConnected = existingConnectors.count { it.status == ConnectorStatus.CONNECTED }

        return CatalogRefreshReport(
            refreshedAtMs = System.currentTimeMillis(),
            claudeSourceCount = claudeCount,
            chatGptSourceCount = chatGptCount,
            geminiSourceCount = geminiCount,
            totalRawListings = rawListings,
            uniqueCanonicalCount = uniqueCanonical,
            implementedCount = implemented,
            setupRequiredCount = setupRequired,
            blockedCount = blocked,
            preservedConnectedCount = preservedConnected
        )
    }

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
            "tpl_google_maps_places" -> AuthConfig(AuthSchemeType.API_KEY, headerName = "X-Goog-Api-Key", prefix = "")
            "tpl_brave_search" -> AuthConfig(AuthSchemeType.API_KEY, headerName = "X-Subscription-Token", prefix = "")
            "tpl_shopify_api" -> AuthConfig(AuthSchemeType.API_KEY, headerName = "X-Shopify-Access-Token", prefix = "")
            "tpl_youtube_data", "tpl_trello_api" -> AuthConfig(AuthSchemeType.API_KEY, queryParamName = "key", headerName = "", prefix = "")
            else -> AuthConfig(
                schemeType = template.defaultAuthScheme,
                headerName = "Authorization",
                prefix = if (template.defaultAuthScheme in setOf(AuthSchemeType.BEARER_TOKEN, AuthSchemeType.OAUTH2_PKCE)) "Bearer " else "",
                oauthAuthorizeUrl = template.oauthAuthorizeUrl,
                oauthTokenUrl = template.oauthTokenUrl,
                oauthRevokeUrl = template.oauthRevokeUrl,
                scopes = template.defaultScopes
            )
        }

        val initialStatus = when (template.implementationState) {
            ConnectorImplementationState.BLOCKED_NO_PUBLIC_API -> ConnectorStatus.UNSUPPORTED
            ConnectorImplementationState.SETUP_REQUIRED -> ConnectorStatus.SETUP_REQUIRED
            else -> ConnectorStatus.CONFIGURED
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
            status = initialStatus,
            authConfig = auth,
            capabilities = template.defaultTools.map { it.name },
            permissions = template.defaultScopes,
            domainAllowlist = hostAllow,
            isLocalDevAllowed = allowLocalDev,
            defaultHeaders = template.defaultHeaders,
            docsUrl = template.docsUrl,
            canonicalServiceId = template.canonicalServiceId,
            ecosystemSources = template.ecosystemSources,
            webhookSupported = template.webhookSupported,
            restrictions = template.restrictions,
            implementationState = template.implementationState
        )

        val tools = template.defaultTools.map { t ->
            t.copy(
                toolId = "${instanceId}:${t.name}",
                connectorId = instanceId,
                status = initialStatus
            )
        }
        return connector to tools
    }
}
