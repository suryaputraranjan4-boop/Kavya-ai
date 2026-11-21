package com.example.kucp.protocol

import android.content.Context
import android.util.Base64
import com.example.kucp.model.AuthSchemeType
import com.example.kucp.model.ConnectorDefinition
import com.example.kucp.model.ConnectorProtocol
import com.example.kucp.model.ConnectorStatus
import com.example.kucp.model.ParamLocation
import com.example.kucp.model.ToolDescriptor
import com.example.kucp.model.ToolInvocationRequest
import com.example.kucp.model.ToolInvocationResult
import com.example.kucp.model.ToolParameterSchema
import com.example.kucp.model.ToolRiskLevel
import com.example.kucp.security.SecretRedactor
import com.example.kucp.security.SsrfPolicyValidator
import com.example.kucp.security.UntrustedContentIsolator
import com.example.kucp.storage.KucpCredentialVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Extensible Protocol Adapter SPI for KUCP.
 * Any external SDK, proprietary protocol, MCP bridge, REST, GraphQL, gRPC, or A2A agent implements this interface.
 */
interface KucpProtocolAdapter {
    val adapterId: String
    val supportedProtocols: Set<ConnectorProtocol>
    suspend fun execute(
        context: Context,
        connector: ConnectorDefinition,
        tool: ToolDescriptor,
        request: ToolInvocationRequest
    ): ToolInvocationResult

    suspend fun healthCheck(context: Context, connector: ConnectorDefinition): Result<Long>
}

object ProtocolAdapterRegistry {
    private val customAdapters = ConcurrentHashMap<String, KucpProtocolAdapter>()

    fun registerCustomAdapter(adapter: KucpProtocolAdapter) {
        customAdapters[adapter.adapterId] = adapter
    }

    fun getCustomAdapter(adapterId: String): KucpProtocolAdapter? = customAdapters[adapterId]

    fun listCustomAdapterIds(): List<String> = customAdapters.keys().toList().sorted()
}

/**
 * Injects authenticated headers or query params from encrypted KucpCredentialVault.
 */
internal object AuthHeaderInjector {
    fun applyAuth(
        context: Context,
        connector: ConnectorDefinition,
        urlBuilder: StringBuilder,
        requestBuilder: Request.Builder
    ) {
        connector.defaultHeaders.forEach { (k, v) -> requestBuilder.header(k, v) }
        val auth = connector.authConfig
        when (auth.schemeType) {
            AuthSchemeType.NONE -> Unit
            AuthSchemeType.API_KEY -> {
                val key = KucpCredentialVault.getCredential(context, connector.id, auth.credentialSlot)
                if (key.isNotBlank()) {
                    if (auth.queryParamName.isNotBlank()) {
                        val sep = if (urlBuilder.contains("?")) "&" else "?"
                        urlBuilder.append(sep).append(URLEncoder.encode(auth.queryParamName, "UTF-8"))
                            .append("=").append(URLEncoder.encode(key, "UTF-8"))
                    } else {
                        val h = auth.headerName.ifBlank { "X-API-Key" }
                        requestBuilder.header(h, "${auth.prefix}$key")
                    }
                }
            }
            AuthSchemeType.BEARER_TOKEN, AuthSchemeType.OAUTH2_PKCE, AuthSchemeType.OIDC -> {
                val tokenSlot = if (auth.schemeType == AuthSchemeType.BEARER_TOKEN) auth.credentialSlot else "oauth_access"
                val token = KucpCredentialVault.getCredential(context, connector.id, tokenSlot)
                if (token.isNotBlank()) {
                    val h = auth.headerName.ifBlank { "Authorization" }
                    val prefix = auth.prefix.ifBlank { "Bearer " }
                    requestBuilder.header(h, "$prefix$token")
                }
            }
            AuthSchemeType.BASIC_AUTH -> {
                val raw = KucpCredentialVault.getCredential(context, connector.id, auth.credentialSlot)
                if (raw.isNotBlank()) {
                    val encoded = if (raw.contains(":")) {
                        Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    } else raw
                    requestBuilder.header("Authorization", "Basic $encoded")
                }
            }
            AuthSchemeType.HMAC_WEBHOOK, AuthSchemeType.MTLS_HEADER_CERT -> {
                val certOrSecret = KucpCredentialVault.getCredential(context, connector.id, auth.credentialSlot)
                if (certOrSecret.isNotBlank() && auth.headerName.isNotBlank()) {
                    requestBuilder.header(auth.headerName, "${auth.prefix}$certOrSecret")
                }
            }
        }
    }
}

/**
 * HTTP REST Runtime supporting GET/POST/PUT/PATCH/DELETE, path interpolation, query params,
 * JSON body construction, pagination extraction, idempotent-only Retry-After backoff, and isolation.
 */
class RestApiRuntime(
    private val baseClient: OkHttpClient = defaultHttpClient()
) : KucpProtocolAdapter {

    override val adapterId: String = "builtin_rest_http"
    override val supportedProtocols: Set<ConnectorProtocol> = setOf(ConnectorProtocol.REST, ConnectorProtocol.OPENAPI)

    override suspend fun execute(
        context: Context,
        connector: ConnectorDefinition,
        tool: ToolDescriptor,
        request: ToolInvocationRequest
    ): ToolInvocationResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val client = baseClient.newBuilder()
            .callTimeout(connector.timeoutMs.coerceIn(2_000L, 60_000L), TimeUnit.MILLISECONDS)
            .build()

        try {
            val built = buildRestUrlAndBody(connector, tool, request.arguments)
            val urlCheck = SsrfPolicyValidator.validateUrl(
                rawUrl = built.url,
                allowLocalDev = connector.isLocalDevAllowed,
                domainAllowlist = connector.domainAllowlist
            )
            if (!urlCheck.allowed) {
                return@withContext ToolInvocationResult(
                    requestId = request.requestId,
                    connectorId = connector.id,
                    toolId = tool.toolId,
                    success = false,
                    errorCode = "SECURITY_BLOCKED",
                    errorMessage = urlCheck.reason,
                    durationMs = System.currentTimeMillis() - started
                )
            }

            val urlSb = StringBuilder(built.url)
            val reqBuilder = Request.Builder().url(built.url)
            AuthHeaderInjector.applyAuth(context, connector, urlSb, reqBuilder)
            if (urlSb.toString() != built.url) {
                reqBuilder.url(urlSb.toString())
            }
            built.headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            if (request.idempotencyKey.isNotBlank()) {
                reqBuilder.header("Idempotency-Key", request.idempotencyKey)
            }

            val method = tool.methodOrOperation.uppercase().ifBlank { "GET" }
            val jsonMedia = "application/json; charset=utf-8".toMediaType()
            when (method) {
                "GET", "HEAD" -> reqBuilder.method(method, null)
                "DELETE" -> reqBuilder.delete(built.bodyJson?.toRequestBody(jsonMedia))
                else -> reqBuilder.method(method, (built.bodyJson ?: "{}").toRequestBody(jsonMedia))
            }

            val httpReq = reqBuilder.build()
            val isIdempotentRead = method in setOf("GET", "HEAD", "OPTIONS")
            var attempt = 0
            var response: Response
            while (true) {
                attempt++
                response = client.newCall(httpReq).execute()
                if (isIdempotentRead && (response.code == 429 || response.code == 503) && attempt <= 2) {
                    val retryAfterSec = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1L, 5L) ?: 1L
                    response.close()
                    delay(retryAfterSec * 1000L)
                } else {
                    break
                }
            }

            response.use { resp ->
                val rawBody = resp.body?.string().orEmpty()
                val nextCursor = extractNextCursor(resp, rawBody)
                val redactedBody = SecretRedactor.redact(rawBody)
                val isolated = UntrustedContentIsolator.isolate("${connector.name}:${tool.name}", redactedBody)
                val elapsed = System.currentTimeMillis() - started
                ToolInvocationResult(
                    requestId = request.requestId,
                    connectorId = connector.id,
                    toolId = tool.toolId,
                    success = resp.isSuccessful,
                    statusCode = resp.code,
                    outputData = redactedBody,
                    isolatedDataForLlm = isolated,
                    errorCode = if (resp.isSuccessful) null else "HTTP_${resp.code}",
                    errorMessage = if (resp.isSuccessful) null else "HTTP ${resp.code}: ${redactedBody.take(240)}",
                    durationMs = elapsed,
                    nextCursor = nextCursor
                )
            }
        } catch (e: Exception) {
            ToolInvocationResult(
                requestId = request.requestId,
                connectorId = connector.id,
                toolId = tool.toolId,
                success = false,
                errorCode = "REST_EXCEPTION",
                errorMessage = SecretRedactor.redact(e.message ?: "Request failed"),
                durationMs = System.currentTimeMillis() - started
            )
        }
    }

    override suspend fun healthCheck(context: Context, connector: ConnectorDefinition): Result<Long> = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val urlCheck = SsrfPolicyValidator.validateUrl(
            rawUrl = connector.baseUrl,
            allowLocalDev = connector.isLocalDevAllowed,
            domainAllowlist = connector.domainAllowlist
        )
        if (!urlCheck.allowed) return@withContext Result.failure(SecurityException(urlCheck.reason))

        try {
            val sb = StringBuilder(connector.baseUrl)
            val rb = Request.Builder().url(connector.baseUrl).get()
            AuthHeaderInjector.applyAuth(context, connector, sb, rb)
            if (sb.toString() != connector.baseUrl) rb.url(sb.toString())
            baseClient.newCall(rb.build()).execute().use { resp ->
                val latency = System.currentTimeMillis() - started
                if (resp.code in 200..499 && resp.code != 401 && resp.code != 403) {
                    Result.success(latency)
                } else {
                    Result.failure(IllegalStateException("Endpoint returned HTTP ${resp.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private data class BuiltRestCall(val url: String, val headers: Map<String, String>, val bodyJson: String?)

    private fun buildRestUrlAndBody(
        connector: ConnectorDefinition,
        tool: ToolDescriptor,
        args: Map<String, String>
    ): BuiltRestCall {
        val base = connector.baseUrl.trimEnd('/')
        var path = tool.endpointPath
        val headers = LinkedHashMap<String, String>()
        val bodyObj = JSONObject()
        val queryPairs = mutableListOf<Pair<String, String>>()

        val schemaByName = tool.inputSchema.associateBy { it.name }
        val handledKeys = HashSet<String>()

        for (param in tool.inputSchema) {
            val rawVal = args[param.name] ?: param.defaultValue ?: continue
            handledKeys.add(param.name)
            when (param.location) {
                ParamLocation.PATH -> {
                    path = path.replace("{${param.name}}", URLEncoder.encode(rawVal, "UTF-8"))
                }
                ParamLocation.QUERY -> {
                    queryPairs += param.name to rawVal
                }
                ParamLocation.HEADER -> {
                    headers[param.name] = rawVal
                }
                ParamLocation.BODY -> {
                    bodyObj.put(param.name, parseJsonScalarOrObj(rawVal, param.type))
                }
            }
        }

        // Handle any extra arguments not explicitly listed in schema
        args.forEach { (k, v) ->
            if (k !in handledKeys && schemaByName[k] == null) {
                if (path.contains("{$k}")) {
                    path = path.replace("{$k}", URLEncoder.encode(v, "UTF-8"))
                } else if (tool.methodOrOperation.uppercase() == "GET") {
                    queryPairs += k to v
                } else {
                    bodyObj.put(k, parseJsonScalarOrObj(v, "string"))
                }
            }
        }

        val fullPath = if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            val normalizedPath = if (path.isBlank() || path.startsWith("/")) path else "/$path"
            "$base$normalizedPath"
        }

        val finalUrl = fullPath.toHttpUrlOrNull()?.newBuilder()?.apply {
            queryPairs.forEach { (k, v) -> addQueryParameter(k, v) }
        }?.build()?.toString() ?: fullPath

        val bodyStr = if (tool.methodOrOperation.uppercase() in setOf("GET", "HEAD") && bodyObj.length() == 0) {
            null
        } else if (bodyObj.length() > 0) {
            bodyObj.toString()
        } else null

        return BuiltRestCall(finalUrl, headers, bodyStr)
    }

    private fun parseJsonScalarOrObj(value: String, typeHint: String): Any {
        val t = value.trim()
        return when {
            typeHint == "integer" && t.toLongOrNull() != null -> t.toLong()
            typeHint == "number" && t.toDoubleOrNull() != null -> t.toDouble()
            typeHint == "boolean" && (t == "true" || t == "false") -> t.toBoolean()
            t.startsWith("{") -> try { JSONObject(t) } catch (_: Exception) { value }
            t.startsWith("[") -> try { JSONArray(t) } catch (_: Exception) { value }
            else -> value
        }
    }

    private fun extractNextCursor(resp: Response, rawBody: String): String? {
        val link = resp.header("Link")
        if (!link.isNullOrBlank()) {
            val match = Regex("<([^>]+)>;\\s*rel=\"next\"").find(link)
            if (match != null) return match.groupValues[1]
        }
        return try {
            val obj = JSONObject(rawBody.trim())
            obj.optString("next_cursor").ifBlank { obj.optString("nextPageToken") }.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient = com.example.ai.OfflinePrivacyGuard.registerCloudClient(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .addInterceptor(com.example.ai.StrictOfflineInterceptor(operationTag = "KucpProtocolRuntime"))
                .followRedirects(false) // Strict SSRF guard against redirect bypasses
                .build()
        )
    }
}

/**
 * GraphQL Runtime supporting Query/Mutation execution and __schema introspection -> dynamic ToolDescriptors.
 */
class GraphqlRuntime(
    private val httpClient: OkHttpClient = RestApiRuntime.defaultHttpClient()
) : KucpProtocolAdapter {
    override val adapterId: String = "builtin_graphql"
    override val supportedProtocols: Set<ConnectorProtocol> = setOf(ConnectorProtocol.GRAPHQL)

    override suspend fun execute(
        context: Context,
        connector: ConnectorDefinition,
        tool: ToolDescriptor,
        request: ToolInvocationRequest
    ): ToolInvocationResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val urlCheck = SsrfPolicyValidator.validateUrl(connector.baseUrl, connector.isLocalDevAllowed, connector.domainAllowlist)
        if (!urlCheck.allowed) {
            return@withContext ToolInvocationResult(request.requestId, connector.id, tool.toolId, false, errorCode = "SECURITY_BLOCKED", errorMessage = urlCheck.reason)
        }
        try {
            val queryStr = request.arguments["query"] ?: buildDefaultGraphqlOp(tool, request.arguments)
            val varsObj = JSONObject()
            val varsArg = request.arguments["variables"]
            if (!varsArg.isNullOrBlank()) {
                val parsed = JSONObject(varsArg)
                parsed.keys().forEach { varsObj.put(it, parsed.get(it)) }
            } else {
                request.arguments.forEach { (k, v) -> if (k != "query") varsObj.put(k, v) }
            }

            val payload = JSONObject().apply {
                put("query", queryStr)
                put("variables", varsObj)
            }.toString()

            val sb = StringBuilder(connector.baseUrl)
            val rb = Request.Builder()
                .url(connector.baseUrl)
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            AuthHeaderInjector.applyAuth(context, connector, sb, rb)
            if (sb.toString() != connector.baseUrl) rb.url(sb.toString())

            httpClient.newCall(rb.build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val redacted = SecretRedactor.redact(raw)
                val hasGqlErrors = try {
                    JSONObject(raw).optJSONArray("errors")?.length()?.let { it > 0 } == true
                } catch (_: Exception) { false }

                ToolInvocationResult(
                    requestId = request.requestId,
                    connectorId = connector.id,
                    toolId = tool.toolId,
                    success = resp.isSuccessful && !hasGqlErrors,
                    statusCode = resp.code,
                    outputData = redacted,
                    isolatedDataForLlm = UntrustedContentIsolator.isolate("${connector.name}:${tool.name}", redacted),
                    errorCode = when {
                        !resp.isSuccessful -> "HTTP_${resp.code}"
                        hasGqlErrors -> "GRAPHQL_ERROR"
                        else -> null
                    },
                    errorMessage = if (resp.isSuccessful && !hasGqlErrors) null else redacted.take(300),
                    durationMs = System.currentTimeMillis() - started
                )
            }
        } catch (e: Exception) {
            ToolInvocationResult(
                requestId = request.requestId,
                connectorId = connector.id,
                toolId = tool.toolId,
                success = false,
                errorCode = "GRAPHQL_EXCEPTION",
                errorMessage = SecretRedactor.redact(e.message),
                durationMs = System.currentTimeMillis() - started
            )
        }
    }

    suspend fun introspectSchema(context: Context, connector: ConnectorDefinition): Result<List<ToolDescriptor>> = withContext(Dispatchers.IO) {
        val introspectQuery = """
            query KucpIntrospection {
              __schema {
                queryType { name fields { name description args { name description type { name kind ofType { name kind } } } } }
                mutationType { name fields { name description args { name description type { name kind ofType { name kind } } } } }
              }
            }
        """.trimIndent()
        val probeTool = ToolDescriptor(
            toolId = "${connector.id}:__introspect",
            connectorId = connector.id,
            name = "__introspect",
            description = "GraphQL Schema Introspection",
            protocol = ConnectorProtocol.GRAPHQL,
            methodOrOperation = "QUERY"
        )
        val res = execute(
            context,
            connector,
            probeTool,
            ToolInvocationRequest(UUID.randomUUID().toString(), connector.id, probeTool.toolId, mapOf("query" to introspectQuery))
        )
        if (!res.success) return@withContext Result.failure(IllegalStateException(res.errorMessage ?: "Introspection failed"))

        return@withContext try {
            val schema = JSONObject(res.outputData).optJSONObject("data")?.optJSONObject("__schema")
                ?: return@withContext Result.failure(IllegalStateException("Introspection response missing data.__schema"))

            val out = mutableListOf<ToolDescriptor>()
            listOf("queryType" to false, "mutationType" to true).forEach { (fieldKey, isMutation) ->
                val fields = schema.optJSONObject(fieldKey)?.optJSONArray("fields") ?: return@forEach
                for (i in 0 until fields.length()) {
                    val f = fields.optJSONObject(i) ?: continue
                    val fname = f.optString("name")
                    if (fname.isBlank() || fname.startsWith("__")) continue
                    val argsArr = f.optJSONArray("args")
                    val params = buildList {
                        if (argsArr != null) {
                            for (j in 0 until argsArr.length()) {
                                val a = argsArr.optJSONObject(j) ?: continue
                                val typeObj = a.optJSONObject("type")
                                val kind = typeObj?.optString("kind")
                                val isReq = kind == "NON_NULL"
                                val tName = typeObj?.optString("name")?.takeIf { it.isNotBlank() && it != "null" }
                                    ?: typeObj?.optJSONObject("ofType")?.optString("name")
                                    ?: "string"
                                add(
                                    ToolParameterSchema(
                                        name = a.optString("name"),
                                        type = tName.lowercase(),
                                        description = a.optString("description"),
                                        required = isReq,
                                        location = ParamLocation.BODY
                                    )
                                )
                            }
                        }
                    }
                    out += ToolDescriptor(
                        toolId = "${connector.id}:$fname",
                        connectorId = connector.id,
                        name = fname,
                        description = f.optString("description").ifBlank { "${if (isMutation) "Mutation" else "Query"} $fname" },
                        protocol = ConnectorProtocol.GRAPHQL,
                        methodOrOperation = if (isMutation) "MUTATION" else "QUERY",
                        endpointPath = connector.baseUrl,
                        inputSchema = params,
                        riskLevel = if (isMutation) ToolRiskLevel.MEDIUM else ToolRiskLevel.LOW,
                        requiresConfirmation = isMutation,
                        isIdempotent = !isMutation
                    )
                }
            }
            Result.success(out)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildDefaultGraphqlOp(tool: ToolDescriptor, args: Map<String, String>): String {
        val opWord = if (tool.methodOrOperation.equals("MUTATION", ignoreCase = true)) "mutation" else "query"
        val cleanArgs = args.filterKeys { it != "query" && it != "variables" }
        val argClause = if (cleanArgs.isEmpty()) "" else cleanArgs.entries.joinToString(", ", prefix = "(", postfix = ")") { (k, v) ->
            val escaped = v.replace("\"", "\\\"")
            "$k: \"$escaped\""
        }
        return "$opWord { ${tool.name}$argClause }"
    }

    override suspend fun healthCheck(context: Context, connector: ConnectorDefinition): Result<Long> {
        val started = System.currentTimeMillis()
        val probe = ToolDescriptor("${connector.id}:ping", connector.id, "__typename", "Health ping", ConnectorProtocol.GRAPHQL, "QUERY")
        val res = execute(context, connector, probe, ToolInvocationRequest("ping", connector.id, probe.toolId, mapOf("query" to "{ __typename }")))
        return if (res.success) Result.success(System.currentTimeMillis() - started)
        else Result.failure(IllegalStateException(res.errorMessage ?: "GraphQL ping failed"))
    }
}

/**
 * MCP (Model Context Protocol) JSON-RPC 2.0 Client Runtime supporting Streamable HTTP + SSE frames
 * and registered local in-app/loopback bridges. Handles initialize, notifications/initialized,
 * tools/list, tools/call, resources/list, prompts/list, and Mcp-Session-Id state.
 */
interface LocalMcpBridgeHandler {
    suspend fun handleJsonRpc(method: String, paramsJson: String): JSONObject
}

class McpClientRuntime(
    private val httpClient: OkHttpClient = RestApiRuntime.defaultHttpClient()
) : KucpProtocolAdapter {

    override val adapterId: String = "builtin_mcp_jsonrpc"
    override val supportedProtocols: Set<ConnectorProtocol> = setOf(ConnectorProtocol.MCP)

    private val sessionIds = ConcurrentHashMap<String, String>()
    private val serverCapabilities = ConcurrentHashMap<String, String>()
    private val localBridges = ConcurrentHashMap<String, LocalMcpBridgeHandler>()

    fun registerLocalBridge(bridgeId: String, handler: LocalMcpBridgeHandler) {
        localBridges[bridgeId] = handler
    }

    fun getSessionId(connectorId: String): String? = sessionIds[connectorId]
    fun getServerCapabilitiesSummary(connectorId: String): String = serverCapabilities[connectorId].orEmpty()

    suspend fun initializeSession(context: Context, connector: ConnectorDefinition): Result<JSONObject> = withContext(Dispatchers.IO) {
        val initParams = JSONObject().apply {
            put("protocolVersion", "2024-11-05")
            put("capabilities", JSONObject().put("roots", JSONObject().put("listChanged", false)))
            put("clientInfo", JSONObject().put("name", "Kavya-KUCP").put("version", "1.0.0"))
        }
        val res = sendRpc(context, connector, "initialize", initParams)
        res.onSuccess { resultObj ->
            serverCapabilities[connector.id] = resultObj.optJSONObject("capabilities")?.toString().orEmpty()
            // Notify server initialization complete (best-effort notification)
            sendRpc(context, connector, "notifications/initialized", JSONObject(), isNotification = true)
        }
        res
    }

    suspend fun listTools(context: Context, connector: ConnectorDefinition): Result<List<ToolDescriptor>> = withContext(Dispatchers.IO) {
        if (sessionIds[connector.id].isNullOrBlank() && !connector.baseUrl.startsWith("local://")) {
            initializeSession(context, connector)
        }
        val res = sendRpc(context, connector, "tools/list", JSONObject())
        res.mapCatching { obj ->
            val arr = obj.optJSONArray("tools") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i) ?: continue
                    val tName = t.optString("name")
                    if (tName.isBlank()) continue
                    val schemaObj = t.optJSONObject("inputSchema") ?: JSONObject()
                    val props = schemaObj.optJSONObject("properties")
                    val reqSet = HashSet<String>().apply {
                        val reqArr = schemaObj.optJSONArray("required")
                        if (reqArr != null) {
                            for (j in 0 until reqArr.length()) add(reqArr.optString(j))
                        }
                    }
                    val params = buildList {
                        props?.keys()?.forEach { pName ->
                            val p = props.optJSONObject(pName)
                            add(
                                ToolParameterSchema(
                                    name = pName,
                                    type = p?.optString("type")?.ifBlank { "string" } ?: "string",
                                    description = p?.optString("description").orEmpty(),
                                    required = pName in reqSet,
                                    location = ParamLocation.BODY
                                )
                            )
                        }
                    }
                    val lower = tName.lowercase()
                    val highRisk = lower.contains("delete") || lower.contains("write") || lower.contains("send") || lower.contains("exec")
                    add(
                        ToolDescriptor(
                            toolId = "${connector.id}:$tName",
                            connectorId = connector.id,
                            name = tName,
                            description = t.optString("description").ifBlank { "MCP Tool $tName" },
                            protocol = ConnectorProtocol.MCP,
                            methodOrOperation = "tools/call",
                            endpointPath = connector.baseUrl,
                            inputSchema = params,
                            riskLevel = if (highRisk) ToolRiskLevel.HIGH else ToolRiskLevel.MEDIUM,
                            requiresConfirmation = highRisk,
                            isIdempotent = !highRisk,
                            version = connector.version,
                            status = ConnectorStatus.CONNECTED
                        )
                    )
                }
            }
        }
    }

    suspend fun listResources(context: Context, connector: ConnectorDefinition): Result<JSONArray> =
        sendRpc(context, connector, "resources/list", JSONObject()).map { it.optJSONArray("resources") ?: JSONArray() }

    suspend fun listPrompts(context: Context, connector: ConnectorDefinition): Result<JSONArray> =
        sendRpc(context, connector, "prompts/list", JSONObject()).map { it.optJSONArray("prompts") ?: JSONArray() }

    override suspend fun execute(
        context: Context,
        connector: ConnectorDefinition,
        tool: ToolDescriptor,
        request: ToolInvocationRequest
    ): ToolInvocationResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        if (sessionIds[connector.id].isNullOrBlank() && !connector.baseUrl.startsWith("local://")) {
            initializeSession(context, connector)
        }
        val argsJson = JSONObject()
        request.arguments.forEach { (k, v) -> argsJson.put(k, v) }
        val params = JSONObject().apply {
            put("name", tool.name)
            put("arguments", argsJson)
        }
        val res = sendRpc(context, connector, "tools/call", params)
        res.fold(
            onSuccess = { obj ->
                val isErr = obj.optBoolean("isError", false)
                val textOut = extractMcpContentText(obj)
                val redacted = SecretRedactor.redact(textOut)
                ToolInvocationResult(
                    requestId = request.requestId,
                    connectorId = connector.id,
                    toolId = tool.toolId,
                    success = !isErr,
                    statusCode = 200,
                    outputData = redacted,
                    isolatedDataForLlm = UntrustedContentIsolator.isolate("${connector.name}:${tool.name}", redacted),
                    errorCode = if (isErr) "MCP_TOOL_ERROR" else null,
                    errorMessage = if (isErr) redacted.take(300) else null,
                    durationMs = System.currentTimeMillis() - started
                )
            },
            onFailure = { err ->
                ToolInvocationResult(
                    requestId = request.requestId,
                    connectorId = connector.id,
                    toolId = tool.toolId,
                    success = false,
                    errorCode = "MCP_RPC_ERROR",
                    errorMessage = SecretRedactor.redact(err.message),
                    durationMs = System.currentTimeMillis() - started
                )
            }
        )
    }

    override suspend fun healthCheck(context: Context, connector: ConnectorDefinition): Result<Long> {
        val started = System.currentTimeMillis()
        return initializeSession(context, connector).map { System.currentTimeMillis() - started }
    }

    private suspend fun sendRpc(
        context: Context,
        connector: ConnectorDefinition,
        method: String,
        params: JSONObject,
        isNotification: Boolean = false
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        if (connector.baseUrl.startsWith("local://")) {
            val bridgeKey = connector.baseUrl.removePrefix("local://").trim('/')
            val handler = localBridges[bridgeKey]
                ?: return@withContext Result.failure(IllegalStateException("Local MCP bridge '$bridgeKey' is not registered."))
            return@withContext try {
                Result.success(handler.handleJsonRpc(method, params.toString()))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        val urlCheck = SsrfPolicyValidator.validateUrl(connector.baseUrl, connector.isLocalDevAllowed, connector.domainAllowlist)
        if (!urlCheck.allowed) return@withContext Result.failure(SecurityException(urlCheck.reason))

        try {
            val rpc = JSONObject().apply {
                put("jsonrpc", "2.0")
                if (!isNotification) put("id", UUID.randomUUID().toString())
                put("method", method)
                put("params", params)
            }
            val sb = StringBuilder(connector.baseUrl)
            val rb = Request.Builder()
                .url(connector.baseUrl)
                .header("Accept", "application/json, text/event-stream")
                .post(rpc.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            sessionIds[connector.id]?.let { rb.header("Mcp-Session-Id", it) }
            AuthHeaderInjector.applyAuth(context, connector, sb, rb)
            if (sb.toString() != connector.baseUrl) rb.url(sb.toString())

            httpClient.newCall(rb.build()).execute().use { resp ->
                resp.header("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionIds[connector.id] = it }
                if (isNotification && (resp.code == 202 || resp.code == 204 || resp.isSuccessful)) {
                    return@withContext Result.success(JSONObject())
                }
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("MCP HTTP ${resp.code}: ${SecretRedactor.redact(raw.take(240))}"))
                }
                val jsonPayload = parseJsonOrSseFrame(raw)
                val errObj = jsonPayload.optJSONObject("error")
                if (errObj != null) {
                    return@withContext Result.failure(IllegalStateException("MCP Error ${errObj.optInt("code")}: ${errObj.optString("message")}"))
                }
                Result.success(jsonPayload.optJSONObject("result") ?: jsonPayload)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseJsonOrSseFrame(raw: String): JSONObject {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) return JSONObject(trimmed)
        // Parse SSE `data: {...}` lines
        val dataLines = trimmed.lines().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }
        val lastJson = dataLines.lastOrNull { it.startsWith("{") }
        return if (lastJson != null) JSONObject(lastJson) else JSONObject().put("raw", trimmed)
    }

    private fun extractMcpContentText(resultObj: JSONObject): String {
        val contentArr = resultObj.optJSONArray("content") ?: return resultObj.toString()
        val parts = mutableListOf<String>()
        for (i in 0 until contentArr.length()) {
            val item = contentArr.optJSONObject(i) ?: continue
            val text = item.optString("text").ifBlank { item.optJSONObject("resource")?.optString("text").orEmpty() }
            if (text.isNotBlank()) parts += text
        }
        return if (parts.isNotEmpty()) parts.joinToString("\n") else resultObj.toString()
    }
}

/**
 * Webhook HMAC-SHA256 signer/verifier, WebSocket live session manager, A2A Agent Client, and gRPC-JSON transcode adapter.
 */
class WebhookWebSocketA2aGrpcRuntime(
    private val httpClient: OkHttpClient = RestApiRuntime.defaultHttpClient()
) : KucpProtocolAdapter {

    override val adapterId: String = "builtin_webhook_ws_a2a_grpc"
    override val supportedProtocols: Set<ConnectorProtocol> = setOf(
        ConnectorProtocol.WEBHOOK,
        ConnectorProtocol.WEBSOCKET,
        ConnectorProtocol.A2A,
        ConnectorProtocol.GRPC,
        ConnectorProtocol.CUSTOM_SDK
    )

    private val activeSockets = ConcurrentHashMap<String, WebSocket>()
    private val socketInbox = ConcurrentHashMap<String, MutableList<String>>()

    fun computeHmacSha256Hex(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun verifyWebhookSignature(secret: String, payload: String, providedSignatureHeader: String): Boolean {
        if (secret.isBlank() || providedSignatureHeader.isBlank()) return false
        val expected = computeHmacSha256Hex(secret, payload)
        val normalized = providedSignatureHeader.removePrefix("sha256=").trim().lowercase()
        if (expected.length != normalized.length) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].code xor normalized[i].code)
        return diff == 0
    }

    suspend fun discoverA2aAgentCard(connector: ConnectorDefinition): Result<JSONObject> = withContext(Dispatchers.IO) {
        val base = connector.baseUrl.trimEnd('/')
        val cardUrl = if (base.endsWith("agent.json")) base else "$base/.well-known/agent.json"
        val check = SsrfPolicyValidator.validateUrl(cardUrl, connector.isLocalDevAllowed, connector.domainAllowlist)
        if (!check.allowed) return@withContext Result.failure(SecurityException(check.reason))
        try {
            httpClient.newCall(Request.Builder().url(cardUrl).get().build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) Result.failure(IllegalStateException("A2A card HTTP ${resp.code}"))
                else Result.success(JSONObject(body))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun execute(
        context: Context,
        connector: ConnectorDefinition,
        tool: ToolDescriptor,
        request: ToolInvocationRequest
    ): ToolInvocationResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        if (connector.protocol == ConnectorProtocol.CUSTOM_SDK && connector.customProtocolAdapterId.isNotBlank()) {
            val custom = ProtocolAdapterRegistry.getCustomAdapter(connector.customProtocolAdapterId)
            if (custom != null) return@withContext custom.execute(context, connector, tool, request)
            return@withContext ToolInvocationResult(
                requestId = request.requestId,
                connectorId = connector.id,
                toolId = tool.toolId,
                success = false,
                errorCode = "ADAPTER_NOT_FOUND",
                errorMessage = "Custom SDK adapter '${connector.customProtocolAdapterId}' is not registered."
            )
        }

        val targetUrl = tool.endpointPath.let { p ->
            when {
                p.startsWith("http://") || p.startsWith("https://") || p.startsWith("ws://") || p.startsWith("wss://") -> p
                p.isBlank() -> connector.baseUrl
                else -> "${connector.baseUrl.trimEnd('/')}/${p.trimStart('/')}"
            }
        }

        val check = SsrfPolicyValidator.validateUrl(
            rawUrl = targetUrl,
            allowLocalDev = connector.isLocalDevAllowed,
            domainAllowlist = connector.domainAllowlist,
            allowWebSocketSchemes = connector.protocol == ConnectorProtocol.WEBSOCKET
        )
        if (!check.allowed) {
            return@withContext ToolInvocationResult(request.requestId, connector.id, tool.toolId, false, errorCode = "SECURITY_BLOCKED", errorMessage = check.reason)
        }

        try {
            when (connector.protocol) {
                ConnectorProtocol.WEBHOOK -> {
                    val payloadObj = JSONObject().apply {
                        put("event", tool.name)
                        put("timestampMs", System.currentTimeMillis())
                        put("data", JSONObject().apply { request.arguments.forEach { (k, v) -> put(k, v) } })
                    }.toString()
                    val secret = KucpCredentialVault.getCredential(context, connector.id, connector.authConfig.credentialSlot)
                    val rb = Request.Builder().url(targetUrl)
                        .post(payloadObj.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    if (secret.isNotBlank()) {
                        rb.header("X-Signature-256", "sha256=${computeHmacSha256Hex(secret, payloadObj)}")
                    }
                    httpClient.newCall(rb.build()).execute().use { resp ->
                        val body = SecretRedactor.redact(resp.body?.string().orEmpty())
                        ToolInvocationResult(
                            requestId = request.requestId,
                            connectorId = connector.id,
                            toolId = tool.toolId,
                            success = resp.isSuccessful,
                            statusCode = resp.code,
                            outputData = body.ifBlank { "Webhook delivered (HTTP ${resp.code})" },
                            isolatedDataForLlm = UntrustedContentIsolator.isolate(connector.name, body),
                            errorCode = if (resp.isSuccessful) null else "HTTP_${resp.code}",
                            durationMs = System.currentTimeMillis() - started
                        )
                    }
                }
                ConnectorProtocol.WEBSOCKET -> {
                    val ws = activeSockets[connector.id] ?: openWebSocket(context, connector, targetUrl)
                    val msg = request.arguments["message"] ?: JSONObject().apply {
                        request.arguments.forEach { (k, v) -> put(k, v) }
                    }.toString()
                    val sent = ws.send(msg)
                    val inboxSnapshot = socketInbox[connector.id]?.takeLast(5)?.joinToString("\n").orEmpty()
                    ToolInvocationResult(
                        requestId = request.requestId,
                        connectorId = connector.id,
                        toolId = tool.toolId,
                        success = sent,
                        outputData = if (sent) "WebSocket frame sent.\nRecent frames:\n$inboxSnapshot" else "Failed to enqueue WebSocket frame",
                        isolatedDataForLlm = UntrustedContentIsolator.isolate(connector.name, inboxSnapshot),
                        durationMs = System.currentTimeMillis() - started
                    )
                }
                ConnectorProtocol.A2A -> {
                    val rpc = JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("id", UUID.randomUUID().toString())
                        put("method", "tasks/send")
                        put("params", JSONObject().apply {
                            put("id", request.idempotencyKey.ifBlank { UUID.randomUUID().toString() })
                            put("message", JSONObject().apply {
                                put("role", "user")
                                put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", request.arguments["prompt"] ?: request.arguments.toString())))
                            })
                        })
                    }.toString()
                    val sb = StringBuilder(targetUrl)
                    val rb = Request.Builder().url(targetUrl).post(rpc.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    AuthHeaderInjector.applyAuth(context, connector, sb, rb)
                    if (sb.toString() != targetUrl) rb.url(sb.toString())
                    httpClient.newCall(rb.build()).execute().use { resp ->
                        val body = SecretRedactor.redact(resp.body?.string().orEmpty())
                        ToolInvocationResult(
                            requestId = request.requestId,
                            connectorId = connector.id,
                            toolId = tool.toolId,
                            success = resp.isSuccessful,
                            statusCode = resp.code,
                            outputData = body,
                            isolatedDataForLlm = UntrustedContentIsolator.isolate("A2A:${connector.name}", body),
                            errorCode = if (resp.isSuccessful) null else "A2A_HTTP_${resp.code}",
                            durationMs = System.currentTimeMillis() - started
                        )
                    }
                }
                ConnectorProtocol.GRPC -> {
                    // Structured gRPC-JSON transcoding transport (e.g., Envoy gRPC-JSON transcoder or grpc-gateway)
                    val grpcBody = JSONObject().apply {
                        request.arguments.forEach { (k, v) -> put(k, v) }
                    }.toString()
                    val sb = StringBuilder(targetUrl)
                    val rb = Request.Builder().url(targetUrl)
                        .header("Content-Type", "application/json")
                        .header("X-KUCP-Transport", "grpc-json-transcode")
                        .post(grpcBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    AuthHeaderInjector.applyAuth(context, connector, sb, rb)
                    if (sb.toString() != targetUrl) rb.url(sb.toString())
                    httpClient.newCall(rb.build()).execute().use { resp ->
                        val body = SecretRedactor.redact(resp.body?.string().orEmpty())
                        ToolInvocationResult(
                            requestId = request.requestId,
                            connectorId = connector.id,
                            toolId = tool.toolId,
                            success = resp.isSuccessful,
                            statusCode = resp.code,
                            outputData = body,
                            isolatedDataForLlm = UntrustedContentIsolator.isolate("gRPC:${connector.name}", body),
                            errorCode = if (resp.isSuccessful) null else "GRPC_STATUS_${resp.code}",
                            errorMessage = if (resp.isSuccessful) null else "gRPC gateway returned HTTP ${resp.code}: ${body.take(200)}",
                            durationMs = System.currentTimeMillis() - started
                        )
                    }
                }
                else -> ToolInvocationResult(request.requestId, connector.id, tool.toolId, false, errorCode = "UNSUPPORTED", errorMessage = "Unsupported protocol ${connector.protocol}")
            }
        } catch (e: Exception) {
            ToolInvocationResult(
                requestId = request.requestId,
                connectorId = connector.id,
                toolId = tool.toolId,
                success = false,
                errorCode = "TRANSPORT_EXCEPTION",
                errorMessage = SecretRedactor.redact(e.message),
                durationMs = System.currentTimeMillis() - started
            )
        }
    }

    private fun openWebSocket(context: Context, connector: ConnectorDefinition, wsUrl: String): WebSocket {
        com.example.ai.OfflinePrivacyGuard.checkOutboundAllowed(
            context = context,
            operation = "kucp_websocket:${connector.id}",
            host = runCatching { java.net.URI(wsUrl).host.orEmpty() }.getOrDefault("")
        )
        val sb = StringBuilder(wsUrl)
        val rb = Request.Builder().url(wsUrl)
        AuthHeaderInjector.applyAuth(context, connector, sb, rb)
        if (sb.toString() != wsUrl) rb.url(sb.toString())
        val inbox = socketInbox.getOrPut(connector.id) { java.util.Collections.synchronizedList(mutableListOf()) }
        val ws = httpClient.newWebSocket(rb.build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                inbox.add(SecretRedactor.redact(text.take(1000)))
                while (inbox.size > 40) inbox.removeAt(0)
            }
        })
        activeSockets[connector.id] = ws
        com.example.ai.OfflinePrivacyGuard.registerActiveWebSocket(ws)
        return ws
    }

    fun closeWebSocket(connectorId: String) {
        val removed = activeSockets.remove(connectorId)
        com.example.ai.OfflinePrivacyGuard.unregisterWebSocket(removed)
        removed?.close(1000, "Closed by KUCP")
    }

    override suspend fun healthCheck(context: Context, connector: ConnectorDefinition): Result<Long> {
        val started = System.currentTimeMillis()
        if (connector.protocol == ConnectorProtocol.A2A) {
            return discoverA2aAgentCard(connector).map { System.currentTimeMillis() - started }
        }
        val check = SsrfPolicyValidator.validateUrl(
            connector.baseUrl,
            connector.isLocalDevAllowed,
            connector.domainAllowlist,
            allowWebSocketSchemes = connector.protocol == ConnectorProtocol.WEBSOCKET
        )
        return if (check.allowed) Result.success(System.currentTimeMillis() - started)
        else Result.failure(SecurityException(check.reason))
    }
}
