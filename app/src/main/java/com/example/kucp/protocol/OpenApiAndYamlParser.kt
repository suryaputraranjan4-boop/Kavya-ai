package com.example.kucp.protocol

import com.example.kucp.model.AuthConfig
import com.example.kucp.model.AuthSchemeType
import com.example.kucp.model.ConnectorCategory
import com.example.kucp.model.ConnectorDefinition
import com.example.kucp.model.ConnectorDraft
import com.example.kucp.model.ConnectorProtocol
import com.example.kucp.model.ConnectorStatus
import com.example.kucp.model.ParamLocation
import com.example.kucp.model.ToolDescriptor
import com.example.kucp.model.ToolParameterSchema
import com.example.kucp.model.ToolRiskLevel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Pure-Kotlin OpenAPI 3.x / Swagger 2.0 Importer & Exporter supporting both JSON and YAML specs.
 * Includes a self-contained indentation-aware YAML -> JSONObject converter so no external parser
 * crashes or unverified dependencies are introduced.
 */
object OpenApiAndYamlParser {

    private val prettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun importSpec(
        rawSpec: String,
        sourceUrl: String = "",
        allowLocalDev: Boolean = false
    ): Result<ConnectorDraft> {
        return try {
            val trimmed = rawSpec.trim()
            if (trimmed.isBlank()) {
                return Result.failure(IllegalArgumentException("OpenAPI specification input is empty."))
            }

            val rootJson: JSONObject = if (trimmed.startsWith("{")) {
                JSONObject(trimmed)
            } else {
                yamlToJsonObject(trimmed)
            }

            val isSwagger2 = rootJson.has("swagger") && rootJson.optString("swagger").startsWith("2.")
            val isOpenApi3 = rootJson.has("openapi")
            if (!isSwagger2 && !isOpenApi3 && !rootJson.has("paths")) {
                return Result.failure(IllegalArgumentException("Spec is missing 'openapi'/'swagger' header and 'paths' map."))
            }

            val info = rootJson.optJSONObject("info") ?: JSONObject()
            val title = info.optString("title").ifBlank { "Imported API Connector" }
            val version = info.optString("version").ifBlank { "1.0.0" }
            val description = info.optString("description").ifBlank { "Imported from ${if (isSwagger2) "Swagger 2.0" else "OpenAPI 3"} spec" }

            var baseUrl = if (isSwagger2) {
                val host = rootJson.optString("host").trim()
                val schemes = rootJson.optJSONArray("schemes")
                val scheme = schemes?.optString(0)?.takeIf { it.isNotBlank() } ?: "https"
                val basePath = rootJson.optString("basePath").trim()
                if (host.isNotBlank()) "$scheme://$host$basePath" else ""
            } else {
                val servers = rootJson.optJSONArray("servers")
                servers?.optJSONObject(0)?.optString("url").orEmpty().trim()
            }
            if (baseUrl.startsWith("/") && sourceUrl.startsWith("http")) {
                val origin = sourceUrl.substringBefore("/", sourceUrl)
                val protoHost = Regex("^(https?://[^/]+)").find(sourceUrl)?.groupValues?.get(1).orEmpty()
                if (protoHost.isNotBlank()) baseUrl = protoHost + baseUrl
            }
            baseUrl = baseUrl.trimEnd('/')

            val warnings = mutableListOf<String>()
            if (baseUrl.isBlank()) {
                warnings += "No server base URL detected in spec; set the base URL manually before connecting."
            }

            val authConfig = extractAuthConfig(rootJson, isSwagger2)
            val connectorId = "openapi_" + title.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24) +
                "_" + UUID.randomUUID().toString().take(6)

            val hostAllowlist = Regex("^https?://([^/:]+)").find(baseUrl)?.groupValues?.get(1)
                ?.let { listOf(it) } ?: emptyList()

            val tools = extractTools(rootJson, connectorId, version)
            if (tools.isEmpty()) {
                warnings += "No callable HTTP operations were found under 'paths'."
            }

            val connector = ConnectorDefinition(
                id = connectorId,
                name = title,
                provider = title,
                description = description.take(300),
                version = version,
                protocol = ConnectorProtocol.OPENAPI,
                category = ConnectorCategory.CUSTOM,
                baseUrl = baseUrl,
                status = ConnectorStatus.CONFIGURED,
                authConfig = authConfig,
                capabilities = tools.map { it.name }.take(20),
                permissions = authConfig.scopes,
                domainAllowlist = hostAllowlist,
                isLocalDevAllowed = allowLocalDev,
                docsUrl = sourceUrl
            )

            Result.success(
                ConnectorDraft(
                    connector = connector,
                    tools = tools,
                    analysisWarnings = warnings,
                    requiresUserApproval = true
                )
            )
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("Failed to parse OpenAPI/YAML spec: ${e.message}", e))
        }
    }

    private fun extractAuthConfig(root: JSONObject, isSwagger2: Boolean): AuthConfig {
        val schemesObj = if (isSwagger2) {
            root.optJSONObject("securityDefinitions")
        } else {
            root.optJSONObject("components")?.optJSONObject("securitySchemes")
        } ?: return AuthConfig(schemeType = AuthSchemeType.NONE)

        val keys = schemesObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val scheme = schemesObj.optJSONObject(key) ?: continue
            val type = scheme.optString("type").lowercase()
            when (type) {
                "oauth2" -> {
                    val flows = scheme.optJSONObject("flows")
                    val authCode = flows?.optJSONObject("authorizationCode") ?: flows?.optJSONObject("implicit")
                    val authUrl = authCode?.optString("authorizationUrl").orEmpty().ifBlank { scheme.optString("authorizationUrl") }
                    val tokenUrl = authCode?.optString("tokenUrl").orEmpty().ifBlank { scheme.optString("tokenUrl") }
                    val scopesObj = authCode?.optJSONObject("scopes") ?: scheme.optJSONObject("scopes")
                    val scopes = buildList {
                        scopesObj?.keys()?.forEach { add(it) }
                    }
                    return AuthConfig(
                        schemeType = AuthSchemeType.OAUTH2_PKCE,
                        oauthAuthorizeUrl = authUrl,
                        oauthTokenUrl = tokenUrl,
                        scopes = scopes,
                        pkceRequired = true
                    )
                }
                "openidconnect" -> {
                    return AuthConfig(
                        schemeType = AuthSchemeType.OIDC,
                        oidcDiscoveryUrl = scheme.optString("openIdConnectUrl"),
                        pkceRequired = true
                    )
                }
                "http" -> {
                    val httpScheme = scheme.optString("scheme").lowercase()
                    return if (httpScheme == "basic") {
                        AuthConfig(schemeType = AuthSchemeType.BASIC_AUTH, headerName = "Authorization", prefix = "Basic ")
                    } else {
                        AuthConfig(schemeType = AuthSchemeType.BEARER_TOKEN, headerName = "Authorization", prefix = "Bearer ")
                    }
                }
                "apikey" -> {
                    val loc = scheme.optString("in").lowercase()
                    val paramName = scheme.optString("name").ifBlank { "X-API-Key" }
                    return if (loc == "query") {
                        AuthConfig(schemeType = AuthSchemeType.API_KEY, headerName = "", queryParamName = paramName, prefix = "")
                    } else {
                        AuthConfig(schemeType = AuthSchemeType.API_KEY, headerName = paramName, prefix = "")
                    }
                }
            }
        }
        return AuthConfig(schemeType = AuthSchemeType.NONE)
    }

    private fun extractTools(root: JSONObject, connectorId: String, version: String): List<ToolDescriptor> {
        val paths = root.optJSONObject("paths") ?: return emptyList()
        val tools = mutableListOf<ToolDescriptor>()
        val httpMethods = listOf("get", "post", "put", "patch", "delete")

        val pathKeys = paths.keys()
        while (pathKeys.hasNext()) {
            val path = pathKeys.next()
            val pathObj = paths.optJSONObject(path) ?: continue
            val pathLevelParams = pathObj.optJSONArray("parameters")

            for (method in httpMethods) {
                val op = pathObj.optJSONObject(method) ?: continue
                val opId = op.optString("operationId").ifBlank {
                    val cleanPath = path.replace(Regex("[{}/]+"), "_").trim('_').ifBlank { "root" }
                    "${method}_$cleanPath"
                }.replace(Regex("[^A-Za-z0-9_.-]"), "_")

                val summary = op.optString("summary").ifBlank { op.optString("description") }
                    .ifBlank { "${method.uppercase()} $path" }

                val params = mutableListOf<ToolParameterSchema>()
                collectParameters(root, pathLevelParams, params)
                collectParameters(root, op.optJSONArray("parameters"), params)
                collectRequestBodyParameters(root, op.optJSONObject("requestBody"), params)

                val upperMethod = method.uppercase()
                val risk = when (upperMethod) {
                    "GET" -> ToolRiskLevel.LOW
                    "DELETE" -> ToolRiskLevel.HIGH
                    "POST", "PUT", "PATCH" -> {
                        val lowerName = opId.lowercase()
                        if (lowerName.contains("delete") || lowerName.contains("transfer") ||
                            lowerName.contains("payment") || lowerName.contains("send") ||
                            lowerName.contains("publish")
                        ) ToolRiskLevel.HIGH else ToolRiskLevel.MEDIUM
                    }
                    else -> ToolRiskLevel.LOW
                }

                val tags = buildList {
                    val arr = op.optJSONArray("tags")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val t = arr.optString(i)
                            if (t.isNotBlank()) add(t)
                        }
                    }
                }

                tools += ToolDescriptor(
                    toolId = "${connectorId}:$opId",
                    connectorId = connectorId,
                    name = opId,
                    description = summary.take(240),
                    protocol = ConnectorProtocol.REST,
                    methodOrOperation = upperMethod,
                    endpointPath = path,
                    inputSchema = params.distinctBy { "${it.location}:${it.name}" },
                    riskLevel = risk,
                    requiresConfirmation = risk == ToolRiskLevel.HIGH || risk == ToolRiskLevel.CRITICAL,
                    isIdempotent = upperMethod in setOf("GET", "PUT", "DELETE"),
                    tags = tags,
                    version = version,
                    status = ConnectorStatus.CONFIGURED
                )
            }
        }
        return tools
    }

    private fun resolveRef(root: JSONObject, obj: JSONObject?): JSONObject? {
        if (obj == null) return null
        val ref = obj.optString("\$ref")
        if (!ref.startsWith("#/")) return obj
        val parts = ref.removePrefix("#/").split("/")
        var current: Any? = root
        for (part in parts) {
            current = (current as? JSONObject)?.opt(part)
        }
        return current as? JSONObject ?: obj
    }

    private fun collectParameters(root: JSONObject, arr: JSONArray?, out: MutableList<ToolParameterSchema>) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val raw = arr.optJSONObject(i) ?: continue
            val p = resolveRef(root, raw) ?: continue
            val name = p.optString("name")
            if (name.isBlank()) continue
            val loc = when (p.optString("in").lowercase()) {
                "path" -> ParamLocation.PATH
                "query" -> ParamLocation.QUERY
                "header" -> ParamLocation.HEADER
                else -> ParamLocation.BODY
            }
            val schema = resolveRef(root, p.optJSONObject("schema"))
            val type = schema?.optString("type")?.ifBlank { p.optString("type") }?.ifBlank { "string" } ?: "string"
            val enumArr = schema?.optJSONArray("enum")
            val enums = buildList {
                if (enumArr != null) {
                    for (j in 0 until enumArr.length()) add(enumArr.optString(j))
                }
            }
            out += ToolParameterSchema(
                name = name,
                type = type,
                description = p.optString("description").take(140),
                required = p.optBoolean("required", loc == ParamLocation.PATH),
                defaultValue = schema?.opt("default")?.toString(),
                enumValues = enums,
                location = loc
            )
        }
    }

    private fun collectRequestBodyParameters(
        root: JSONObject,
        rawReqBody: JSONObject?,
        out: MutableList<ToolParameterSchema>
    ) {
        val reqBody = resolveRef(root, rawReqBody) ?: return
        val content = reqBody.optJSONObject("content") ?: return
        val media = content.optJSONObject("application/json")
            ?: content.optJSONObject("*/*")
            ?: run {
                val k = content.keys()
                if (k.hasNext()) content.optJSONObject(k.next()) else null
            } ?: return
        val schema = resolveRef(root, media.optJSONObject("schema")) ?: return
        val requiredSet = HashSet<String>().apply {
            val reqArr = schema.optJSONArray("required")
            if (reqArr != null) {
                for (i in 0 until reqArr.length()) add(reqArr.optString(i))
            }
        }
        val props = schema.optJSONObject("properties") ?: return
        val keys = props.keys()
        while (keys.hasNext()) {
            val propName = keys.next()
            val propSchema = resolveRef(root, props.optJSONObject(propName)) ?: continue
            val enumArr = propSchema.optJSONArray("enum")
            val enums = buildList {
                if (enumArr != null) {
                    for (j in 0 until enumArr.length()) add(enumArr.optString(j))
                }
            }
            out += ToolParameterSchema(
                name = propName,
                type = propSchema.optString("type").ifBlank { "string" },
                description = propSchema.optString("description").take(140),
                required = propName in requiredSet,
                defaultValue = propSchema.opt("default")?.toString(),
                enumValues = enums,
                location = ParamLocation.BODY
            )
        }
    }

    fun exportConnectorBundleJson(connector: ConnectorDefinition, tools: List<ToolDescriptor>): String {
        val sanitized = connector.copy(rollbackSnapshotJson = "")
        return prettyJson.encodeToString(
            ConnectorDraft(
                connector = sanitized,
                tools = tools,
                analysisWarnings = emptyList(),
                requiresUserApproval = false
            )
        )
    }

    fun importConnectorBundleJson(rawJson: String): Result<ConnectorDraft> {
        return try {
            val draft = prettyJson.decodeFromString<ConnectorDraft>(rawJson.trim())
            Result.success(draft.copy(requiresUserApproval = true))
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("Invalid KUCP Connector JSON bundle: ${e.message}", e))
        }
    }

    fun exportToOpenApiJson(connector: ConnectorDefinition, tools: List<ToolDescriptor>): String {
        val root = JSONObject()
        root.put("openapi", "3.0.3")
        root.put("info", JSONObject().apply {
            put("title", connector.name)
            put("version", connector.version)
            put("description", connector.description)
        })
        if (connector.baseUrl.isNotBlank()) {
            root.put("servers", JSONArray().put(JSONObject().put("url", connector.baseUrl)))
        }
        val paths = JSONObject()
        for (tool in tools) {
            val path = tool.endpointPath.ifBlank { "/${tool.name}" }
            val pathObj = paths.optJSONObject(path) ?: JSONObject().also { paths.put(path, it) }
            val method = tool.methodOrOperation.lowercase().let {
                if (it in setOf("get", "post", "put", "patch", "delete")) it else "post"
            }
            val opObj = JSONObject().apply {
                put("operationId", tool.name)
                put("summary", tool.description)
                val nonBody = tool.inputSchema.filter { it.location != ParamLocation.BODY }
                if (nonBody.isNotEmpty()) {
                    put("parameters", JSONArray().apply {
                        nonBody.forEach { p ->
                            put(JSONObject().apply {
                                put("name", p.name)
                                put("in", p.location.name.lowercase())
                                put("required", p.required)
                                put("schema", JSONObject().put("type", p.type))
                            })
                        }
                    })
                }
                val bodyParams = tool.inputSchema.filter { it.location == ParamLocation.BODY }
                if (bodyParams.isNotEmpty() && method != "get") {
                    val props = JSONObject()
                    val reqs = JSONArray()
                    bodyParams.forEach { p ->
                        props.put(p.name, JSONObject().put("type", p.type).put("description", p.description))
                        if (p.required) reqs.put(p.name)
                    }
                    put("requestBody", JSONObject().put("content", JSONObject().put("application/json",
                        JSONObject().put("schema", JSONObject().apply {
                            put("type", "object")
                            put("properties", props)
                            if (reqs.length() > 0) put("required", reqs)
                        })
                    )))
                }
            }
            pathObj.put(method, opObj)
        }
        root.put("paths", paths)
        return root.toString(2)
    }

    // =========================================================================
    // CLEAN INDENTATION-AWARE YAML -> JSONObject CONVERTER
    // =========================================================================

    private data class YamlLine(val indent: Int, val text: String)

    fun yamlToJsonObject(yaml: String): JSONObject {
        val lines = preprocessYamlLines(yaml)
        if (lines.isEmpty()) return JSONObject()
        val (parsed, _) = parseYamlNode(lines, 0, lines[0].indent)
        return parsed as? JSONObject ?: JSONObject()
    }

    private fun preprocessYamlLines(yaml: String): List<YamlLine> {
        val out = mutableListOf<YamlLine>()
        val rawLines = yaml.lines()
        for (raw in rawLines) {
            val noComment = stripYamlComment(raw)
            if (noComment.isBlank()) continue
            val trimmedStart = noComment.trimStart()
            if (trimmedStart == "---" || trimmedStart == "...") continue
            val indent = noComment.length - trimmedStart.length
            out += YamlLine(indent, noComment.trimEnd())
        }
        return out
    }

    private fun stripYamlComment(line: String): String {
        var inSingle = false
        var inDouble = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '\'' && !inDouble) inSingle = !inSingle
            else if (c == '"' && !inSingle && (i == 0 || line[i - 1] != '\\')) inDouble = !inDouble
            else if (c == '#' && !inSingle && !inDouble && (i == 0 || line[i - 1].isWhitespace())) {
                return line.substring(0, i)
            }
        }
        return line
    }

    private fun parseYamlNode(lines: List<YamlLine>, startIdx: Int, expectedIndent: Int): Pair<Any?, Int> {
        if (startIdx >= lines.size) return Pair(null, startIdx)
        val first = lines[startIdx]
        val content = first.text.trimStart()
        return if (content.startsWith("- ") || content == "-") {
            parseYamlSequence(lines, startIdx, first.indent)
        } else {
            parseYamlMap(lines, startIdx, expectedIndent)
        }
    }

    private fun parseYamlMap(lines: List<YamlLine>, startIdx: Int, mapIndent: Int): Pair<JSONObject, Int> {
        val obj = JSONObject()
        var idx = startIdx
        while (idx < lines.size) {
            val line = lines[idx]
            if (line.indent < mapIndent) break
            val content = line.text.trim()
            if (content.startsWith("- ") || content == "-") break

            val colonIdx = findKeyValueColon(content)
            if (colonIdx == -1) {
                idx++
                continue
            }
            val key = unquoteScalar(content.substring(0, colonIdx).trim())
            val rest = content.substring(colonIdx + 1).trim()
            idx++

            if (rest.isEmpty() || rest == "|" || rest == ">" || rest == "|-" || rest == ">-") {
                if (rest == "|" || rest == ">" || rest == "|-" || rest == ">-") {
                    val sb = StringBuilder()
                    val blockIndent = if (idx < lines.size) lines[idx].indent else line.indent + 2
                    while (idx < lines.size && lines[idx].indent >= blockIndent) {
                        if (sb.isNotEmpty()) sb.append(if (rest.startsWith(">")) " " else "\n")
                        sb.append(lines[idx].text.trim())
                        idx++
                    }
                    obj.put(key, sb.toString())
                } else if (idx < lines.size && (lines[idx].indent > line.indent ||
                            (lines[idx].indent == line.indent && lines[idx].text.trimStart().startsWith("-")))
                ) {
                    val (child, nextIdx) = parseYamlNode(lines, idx, lines[idx].indent)
                    obj.put(key, child ?: JSONObject.NULL)
                    idx = nextIdx
                } else {
                    obj.put(key, JSONObject())
                }
            } else {
                obj.put(key, parseInlineValue(rest))
            }
        }
        return Pair(obj, idx)
    }

    private fun parseYamlSequence(lines: List<YamlLine>, startIdx: Int, seqIndent: Int): Pair<JSONArray, Int> {
        val arr = JSONArray()
        var idx = startIdx
        while (idx < lines.size) {
            val line = lines[idx]
            if (line.indent < seqIndent) break
            val content = line.text.trimStart()
            if (!(content.startsWith("- ") || content == "-")) break

            val afterDash = if (content == "-") "" else content.substring(2).trim()
            idx++

            if (afterDash.isEmpty()) {
                if (idx < lines.size && lines[idx].indent > line.indent) {
                    val (child, nextIdx) = parseYamlNode(lines, idx, lines[idx].indent)
                    arr.put(child ?: JSONObject.NULL)
                    idx = nextIdx
                } else {
                    arr.put(JSONObject.NULL)
                }
            } else {
                val colonIdx = findKeyValueColon(afterDash)
                if (colonIdx != -1) {
                    // Inline map item starting on the `- key: val` line
                    val itemMapIndent = line.indent + 2
                    val syntheticFirst = YamlLine(itemMapIndent, " ".repeat(itemMapIndent) + afterDash)
                    val combined = buildList {
                        add(syntheticFirst)
                        var j = idx
                        while (j < lines.size && lines[j].indent >= itemMapIndent) {
                            val t = lines[j].text.trimStart()
                            if (lines[j].indent == seqIndent && (t.startsWith("- ") || t == "-")) break
                            add(lines[j])
                            j++
                        }
                    }
                    val (mapObj, consumed) = parseYamlMap(combined, 0, itemMapIndent)
                    arr.put(mapObj)
                    idx += (consumed - 1).coerceAtLeast(0)
                } else {
                    arr.put(parseInlineValue(afterDash))
                }
            }
        }
        return Pair(arr, idx)
    }

    private fun findKeyValueColon(text: String): Int {
        var inSingle = false
        var inDouble = false
        var bracketDepth = 0
        for (i in text.indices) {
            val c = text[i]
            if (c == '\'' && !inDouble) inSingle = !inSingle
            else if (c == '"' && !inSingle && (i == 0 || text[i - 1] != '\\')) inDouble = !inDouble
            else if (!inSingle && !inDouble) {
                when (c) {
                    '{', '[' -> bracketDepth++
                    '}', ']' -> if (bracketDepth > 0) bracketDepth--
                    ':' -> if (bracketDepth == 0 && (i == text.lastIndex || text[i + 1].isWhitespace())) return i
                }
            }
        }
        return -1
    }

    private fun parseInlineValue(raw: String): Any {
        val v = raw.trim()
        if (v.startsWith("[") && v.endsWith("]")) {
            return try {
                JSONArray(v)
            } catch (_: Exception) {
                val inner = v.substring(1, v.length - 1).trim()
                val arr = JSONArray()
                if (inner.isNotBlank()) inner.split(",").forEach { arr.put(parseInlineValue(it)) }
                arr
            }
        }
        if (v.startsWith("{") && v.endsWith("}")) {
            return try {
                JSONObject(v)
            } catch (_: Exception) {
                unquoteScalar(v)
            }
        }
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            return unquoteScalar(v)
        }
        return when {
            v == "true" || v == "True" -> true
            v == "false" || v == "False" -> false
            v == "null" || v == "~" -> JSONObject.NULL
            v.toLongOrNull() != null -> v.toLong()
            v.toDoubleOrNull() != null -> v.toDouble()
            else -> unquoteScalar(v)
        }
    }

    private fun unquoteScalar(s: String): String {
        val t = s.trim()
        return if (t.length >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))) {
            t.substring(1, t.length - 1)
        } else t
    }
}
