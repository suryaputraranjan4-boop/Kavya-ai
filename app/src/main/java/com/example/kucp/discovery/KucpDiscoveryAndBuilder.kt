package com.example.kucp.discovery

import android.content.Context
import com.example.ai.AIModelRouter
import com.example.kucp.model.AuthConfig
import com.example.kucp.model.AuthSchemeType
import com.example.kucp.model.ConnectorCategory
import com.example.kucp.model.ConnectorDefinition
import com.example.kucp.model.ConnectorDraft
import com.example.kucp.model.ConnectorProtocol
import com.example.kucp.model.ConnectorStatus
import com.example.kucp.model.DiscoveryCandidate
import com.example.kucp.model.ParamLocation
import com.example.kucp.model.ToolDescriptor
import com.example.kucp.model.ToolParameterSchema
import com.example.kucp.model.ToolRiskLevel
import com.example.kucp.protocol.OpenApiAndYamlParser
import com.example.kucp.protocol.RestApiRuntime
import com.example.kucp.security.SecretRedactor
import com.example.kucp.security.SsrfPolicyValidator
import com.example.kucp.security.UntrustedContentIsolator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

/**
 * Internet-Wide Integration Discovery Engine.
 * Queries legitimate public registries (GitHub Repositories API, APIs.guru OpenAPI directory,
 * and direct OpenAPI/MCP documentation URLs) while classifying auth, licenses, and deprecation warnings.
 */
class IntegrationDiscoveryEngine(
    private val httpClient: OkHttpClient = RestApiRuntime.defaultHttpClient()
) {
    suspend fun searchPublicRegistries(
        query: String,
        includeGitHubMcpSearch: Boolean = true,
        includeApisGuru: Boolean = true
    ): Result<List<DiscoveryCandidate>> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isBlank()) return@withContext Result.failure(IllegalArgumentException("Discovery search query is empty."))
        val results = mutableListOf<DiscoveryCandidate>()

        if (includeApisGuru) {
            fetchApisGuruMatches(clean, results)
        }
        if (includeGitHubMcpSearch) {
            fetchGitHubMatches(clean, results)
        }
        Result.success(results)
    }

    private fun fetchApisGuruMatches(query: String, out: MutableList<DiscoveryCandidate>) {
        try {
            val url = "https://api.apis.guru/v2/list.json"
            val req = Request.Builder().url(url).header("Accept", "application/json").get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return
                val raw = resp.body?.string().orEmpty()
                val root = JSONObject(raw)
                val qLower = query.lowercase()
                val keys = root.keys()
                var matched = 0
                while (keys.hasNext() && matched < 12) {
                    val providerId = keys.next()
                    val apiObj = root.optJSONObject(providerId) ?: continue
                    val preferred = apiObj.optString("preferred")
                    val verObj = apiObj.optJSONObject("versions")?.optJSONObject(preferred) ?: continue
                    val info = verObj.optJSONObject("info") ?: continue
                    val title = info.optString("title", providerId)
                    val desc = info.optString("description", "")
                    if (providerId.lowercase().contains(qLower) ||
                        title.lowercase().contains(qLower) ||
                        desc.lowercase().contains(qLower)
                    ) {
                        val swaggerUrl = verObj.optString("swaggerUrl")
                        val licenseName = info.optJSONObject("license")?.optString("name").orEmpty().ifBlank { "See docs" }
                        out += DiscoveryCandidate(
                            id = "apisguru_$providerId",
                            name = title,
                            sourceRegistry = "APIs.guru OpenAPI Directory",
                            url = info.optJSONObject("contact")?.optString("url").orEmpty().ifBlank { swaggerUrl },
                            specUrl = swaggerUrl,
                            protocol = ConnectorProtocol.OPENAPI,
                            description = desc.take(180),
                            authRequirement = "Declared in OpenAPI securitySchemes",
                            license = licenseName,
                            capabilities = listOf("OpenAPI Import", "Auto-Generated REST Tools")
                        )
                        matched++
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun fetchGitHubMatches(query: String, out: MutableList<DiscoveryCandidate>) {
        try {
            val encoded = URLEncoder.encode("$query mcp OR openapi OR api", "UTF-8")
            val url = "https://api.github.com/search/repositories?q=$encoded&sort=stars&per_page=8"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Kavya-KUCP-Discovery")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return
                val items = JSONObject(resp.body?.string().orEmpty()).optJSONArray("items") ?: return
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val fullName = item.optString("full_name")
                    val desc = item.optString("description")
                    val htmlUrl = item.optString("html_url")
                    val archived = item.optBoolean("archived", false)
                    val isMcp = fullName.lowercase().contains("mcp") || desc.lowercase().contains("mcp")
                    out += DiscoveryCandidate(
                        id = "gh_${item.optLong("id")}",
                        name = fullName,
                        sourceRegistry = "GitHub Public Registry",
                        url = htmlUrl,
                        specUrl = item.optString("homepage").orEmpty(),
                        protocol = if (isMcp) ConnectorProtocol.MCP else ConnectorProtocol.REST,
                        description = desc.take(180),
                        authRequirement = "Check repository README (Untrusted external repo)",
                        license = item.optJSONObject("license")?.optString("spdx_id").orEmpty().ifBlank { "Unspecified" },
                        deprecationWarning = if (archived) "Repository is archived" else null,
                        capabilities = listOfNotNull(if (isMcp) "MCP Server Candidate" else "API / SDK Candidate")
                    )
                }
            }
        } catch (_: Exception) {}
    }

    suspend fun inspectRemoteSpecUrl(specUrl: String, allowLocalDev: Boolean = false): Result<ConnectorDraft> = withContext(Dispatchers.IO) {
        val check = SsrfPolicyValidator.validateUrl(specUrl, allowLocalDev)
        if (!check.allowed) return@withContext Result.failure(SecurityException(check.reason))
        try {
            httpClient.newCall(Request.Builder().url(specUrl).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Result.failure(IllegalStateException("Spec fetch HTTP ${resp.code}"))
                val body = resp.body?.string().orEmpty()
                if (body.length > 2_000_000) return@withContext Result.failure(IllegalStateException("Spec exceeds 2 MB safety limit"))
                OpenApiAndYamlParser.importSpec(body, sourceUrl = specUrl, allowLocalDev = allowLocalDev)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Custom Connector Builder & Studio:
 * Supports visual manual creation, OpenAPI JSON/YAML import, GraphQL endpoint import, MCP endpoint setup,
 * and explicit user-triggered AI-assisted documentation analysis returning an unprivileged preview draft.
 */
object CustomConnectorBuilder {

    fun buildVisualConnector(
        name: String,
        baseUrl: String,
        protocol: ConnectorProtocol,
        category: ConnectorCategory = ConnectorCategory.CUSTOM,
        authScheme: AuthSchemeType = AuthSchemeType.BEARER_TOKEN,
        headerName: String = "Authorization",
        authPrefix: String = "Bearer ",
        isLocalDevAllowed: Boolean = false,
        initialToolName: String = "execute_endpoint",
        initialMethod: String = "GET",
        initialPath: String = "/",
        initialDescription: String = "Custom connector action"
    ): Result<ConnectorDraft> {
        val cleanName = name.trim()
        val cleanUrl = baseUrl.trim().trimEnd('/')
        if (cleanName.isBlank()) return Result.failure(IllegalArgumentException("Connector name is required."))
        if (cleanUrl.isNotBlank() && !cleanUrl.startsWith("local://")) {
            val urlCheck = SsrfPolicyValidator.validateUrl(
                rawUrl = cleanUrl,
                allowLocalDev = isLocalDevAllowed,
                allowWebSocketSchemes = protocol == ConnectorProtocol.WEBSOCKET
            )
            if (!urlCheck.allowed) return Result.failure(SecurityException(urlCheck.reason))
        }

        val id = "custom_${cleanName.lowercase().replace(Regex("[^a-z0-9]+"), "_").take(20)}_${UUID.randomUUID().toString().take(6)}"
        val hostAllow = Regex("^(?:https?|wss?)://([^/:]+)").find(cleanUrl)?.groupValues?.get(1)
            ?.let { listOf(it) } ?: emptyList()

        val risk = when (initialMethod.uppercase()) {
            "GET", "QUERY" -> ToolRiskLevel.LOW
            "DELETE" -> ToolRiskLevel.HIGH
            else -> ToolRiskLevel.MEDIUM
        }

        val connector = ConnectorDefinition(
            id = id,
            name = cleanName,
            provider = "Custom User Connector",
            description = initialDescription,
            version = "1.0.0",
            protocol = protocol,
            category = category,
            baseUrl = cleanUrl,
            status = ConnectorStatus.CONFIGURED,
            authConfig = AuthConfig(
                schemeType = authScheme,
                headerName = headerName,
                prefix = authPrefix
            ),
            capabilities = listOf(initialToolName),
            domainAllowlist = hostAllow,
            isLocalDevAllowed = isLocalDevAllowed
        )

        val tools = if (protocol == ConnectorProtocol.MCP || initialToolName.isBlank()) {
            emptyList()
        } else {
            listOf(
                ToolDescriptor(
                    toolId = "$id:$initialToolName",
                    connectorId = id,
                    name = initialToolName,
                    description = initialDescription,
                    protocol = protocol,
                    methodOrOperation = initialMethod.uppercase(),
                    endpointPath = initialPath,
                    inputSchema = emptyList(),
                    riskLevel = risk,
                    requiresConfirmation = risk == ToolRiskLevel.HIGH || risk == ToolRiskLevel.CRITICAL,
                    isIdempotent = initialMethod.uppercase() in setOf("GET", "QUERY"),
                    status = ConnectorStatus.CONFIGURED
                )
            )
        }
        return Result.success(ConnectorDraft(connector, tools, emptyList(), requiresUserApproval = true))
    }

    /**
     * AI-Assisted Documentation Analyzer.
     * Parses external docs text strictly as untrusted data and generates an unprivileged ConnectorDraft preview
     * that requires explicit user review and approval before saving. Never grants credentials or auto-executes.
     */
    suspend fun analyzeDocumentationWithAi(
        context: Context,
        rawDocumentationText: String
    ): Result<ConnectorDraft> {
        val cleanDoc = rawDocumentationText.trim()
        if (cleanDoc.isBlank()) return Result.failure(IllegalArgumentException("Documentation text is empty."))
        val safeDoc = UntrustedContentIsolator.isolate("UserProvidedDocs", cleanDoc, maxChars = 6_000)
        val prompt = """
            Analyze the API documentation below and return ONLY valid JSON matching this schema (no markdown, no explanation):
            {
              "name": "Service Name",
              "baseUrl": "https://api.example.com",
              "protocol": "REST",
              "authScheme": "BEARER_TOKEN",
              "description": "Short summary",
              "tools": [
                {
                  "name": "tool_snake_case",
                  "description": "What it does",
                  "method": "GET",
                  "path": "/v1/items",
                  "params": [{"name": "limit", "type": "integer", "location": "QUERY", "required": false}]
                }
              ]
            }

            $safeDoc
        """.trimIndent()

        return try {
            val router = AIModelRouter(context)
            val response = router.chat(
                prompt = prompt,
                history = emptyList(),
                screenContext = "",
                memoryContext = "",
                isProactiveMode = false
            )
            if (response.startsWith("Error:") || response.isBlank()) {
                return Result.failure(IllegalStateException(SecretRedactor.redact(response.ifBlank { "AI provider returned empty response." })))
            }
            val jsonMatch = Regex("\\{[\\s\\S]*\\}").find(response)?.value
                ?: return Result.failure(IllegalStateException("AI analyzer did not return valid JSON draft."))
            val obj = JSONObject(jsonMatch)
            val name = obj.optString("name", "AI Analyzed Connector")
            val baseUrl = obj.optString("baseUrl", "").trimEnd('/')
            val protoStr = obj.optString("protocol", "REST").uppercase()
            val protocol = ConnectorProtocol.entries.firstOrNull { it.name == protoStr } ?: ConnectorProtocol.REST
            val authStr = obj.optString("authScheme", "BEARER_TOKEN").uppercase()
            val authScheme = AuthSchemeType.entries.firstOrNull { it.name == authStr } ?: AuthSchemeType.BEARER_TOKEN

            val cid = "ai_${name.lowercase().replace(Regex("[^a-z0-9]+"), "_").take(18)}_${UUID.randomUUID().toString().take(6)}"
            val hostAllow = Regex("^https?://([^/:]+)").find(baseUrl)?.groupValues?.get(1)?.let { listOf(it) } ?: emptyList()

            val toolsArr = obj.optJSONArray("tools") ?: JSONArray()
            val tools = buildList {
                for (i in 0 until toolsArr.length()) {
                    val t = toolsArr.optJSONObject(i) ?: continue
                    val tName = t.optString("name").ifBlank { continue }
                    val method = t.optString("method", "GET").uppercase()
                    val paramsArr = t.optJSONArray("params") ?: JSONArray()
                    val params = buildList {
                        for (j in 0 until paramsArr.length()) {
                            val p = paramsArr.optJSONObject(j) ?: continue
                            val locStr = p.optString("location", "QUERY").uppercase()
                            val loc = ParamLocation.entries.firstOrNull { it.name == locStr } ?: ParamLocation.QUERY
                            add(
                                ToolParameterSchema(
                                    name = p.optString("name"),
                                    type = p.optString("type", "string"),
                                    description = p.optString("description"),
                                    required = p.optBoolean("required", false),
                                    location = loc
                                )
                            )
                        }
                    }
                    val risk = if (method == "DELETE") ToolRiskLevel.HIGH else if (method == "GET") ToolRiskLevel.LOW else ToolRiskLevel.MEDIUM
                    add(
                        ToolDescriptor(
                            toolId = "$cid:$tName",
                            connectorId = cid,
                            name = tName,
                            description = t.optString("description"),
                            protocol = protocol,
                            methodOrOperation = method,
                            endpointPath = t.optString("path", "/"),
                            inputSchema = params,
                            riskLevel = risk,
                            requiresConfirmation = risk == ToolRiskLevel.HIGH,
                            isIdempotent = method == "GET",
                            status = ConnectorStatus.CONFIGURED
                        )
                    )
                }
            }

            val connector = ConnectorDefinition(
                id = cid,
                name = name,
                provider = "AI-Analyzed Draft",
                description = obj.optString("description"),
                version = "1.0.0",
                protocol = protocol,
                category = ConnectorCategory.CUSTOM,
                baseUrl = baseUrl,
                status = ConnectorStatus.CONFIGURED,
                authConfig = AuthConfig(schemeType = authScheme),
                capabilities = tools.map { it.name },
                domainAllowlist = hostAllow
            )
            Result.success(
                ConnectorDraft(
                    connector = connector,
                    tools = tools,
                    analysisWarnings = listOf("AI-generated draft requires manual review of URL, auth scheme, and permissions before approval."),
                    requiresUserApproval = true
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
