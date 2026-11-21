package com.example.kucp.plugin

import android.content.Context
import com.example.kucp.model.AuthSchemeType
import com.example.kucp.model.ConnectorDefinition
import com.example.kucp.model.ConnectorProtocol
import com.example.kucp.model.ConnectorStatus
import com.example.kucp.model.PluginManifest
import com.example.kucp.model.ToolDescriptor
import com.example.kucp.model.ToolInvocationRequest
import com.example.kucp.model.ToolInvocationResult
import com.example.kucp.protocol.RestApiRuntime
import com.example.kucp.security.SecretRedactor
import com.example.kucp.security.SsrfPolicyValidator
import com.example.kucp.security.UntrustedContentIsolator
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sandboxed Declarative Plugin Runtime.
 * Strictly Google Play Policy compliant: executes declarative JSON manifest HTTP/transform plugins inside
 * explicit domain allowlists, permission checks, and SHA-256 verification without dynamic executable code (.dex/.jar/.so).
 */
class SandboxedPluginRuntime(
    private val httpClient: OkHttpClient = RestApiRuntime.defaultHttpClient()
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    companion object {
        private val forbiddenNativeExtensions = listOf(".dex", ".jar", ".so", ".apk", ".elf")
        private val allowedPluginPermissions = setOf("INTERNET", "READ_ONLY", "WRITE_ALLOWED")
    }

    fun parseAndValidateManifest(rawManifestJson: String, expectedSha256: String? = null): Result<PluginManifest> {
        val trimmed = rawManifestJson.trim()
        if (trimmed.isBlank()) return Result.failure(IllegalArgumentException("Plugin manifest JSON is empty."))

        val lower = trimmed.lowercase()
        if (forbiddenNativeExtensions.any { lower.contains(it) }) {
            return Result.failure(SecurityException("Play Policy Guard: Plugins cannot declare or load executable binaries (.dex/.jar/.so/.apk)."))
        }

        val manifest = try {
            json.decodeFromString<PluginManifest>(trimmed)
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("Invalid PluginManifest JSON: ${e.message}", e))
        }

        if (manifest.pluginId.isBlank() || manifest.name.isBlank() || manifest.version.isBlank()) {
            return Result.failure(IllegalArgumentException("Plugin manifest must specify pluginId, name, and version."))
        }
        if (manifest.allowedDomains.isEmpty()) {
            return Result.failure(SecurityException("Sandboxed plugin must declare explicit non-empty allowedDomains."))
        }
        val unauthorizedPerms = manifest.declaredPermissions.filter { it.uppercase() !in allowedPluginPermissions }
        if (unauthorizedPerms.isNotEmpty()) {
            return Result.failure(SecurityException("Plugin requested disallowed sandbox permissions: $unauthorizedPerms"))
        }

        val computedSha = sha256Hex(trimmed.toByteArray(Charsets.UTF_8))
        val targetSha = expectedSha256?.takeIf { it.isNotBlank() } ?: manifest.sha256
        if (targetSha.isNotBlank() && !targetSha.equals(computedSha, ignoreCase = true) && expectedSha256 != null) {
            return Result.failure(SecurityException("Plugin SHA-256 verification failed."))
        }

        return Result.success(manifest.copy(sha256 = computedSha))
    }

    fun pluginToolsToDescriptors(manifest: PluginManifest, enabled: Boolean): List<ToolDescriptor> {
        val status = if (enabled) ConnectorStatus.CONNECTED else ConnectorStatus.DISCONNECTED
        return manifest.tools.map { spec ->
            ToolDescriptor(
                toolId = "plugin_${manifest.pluginId}:${spec.name}",
                connectorId = "plugin_${manifest.pluginId}",
                name = spec.name,
                description = spec.description,
                protocol = ConnectorProtocol.PLUGIN,
                methodOrOperation = spec.httpMethod.uppercase(),
                endpointPath = spec.urlTemplate,
                inputSchema = spec.parameters,
                riskLevel = spec.riskLevel,
                requiresConfirmation = spec.riskLevel == com.example.kucp.model.ToolRiskLevel.HIGH ||
                    spec.riskLevel == com.example.kucp.model.ToolRiskLevel.CRITICAL,
                isIdempotent = spec.httpMethod.equals("GET", ignoreCase = true),
                version = manifest.version,
                status = status,
                tags = listOf("plugin", manifest.name.lowercase())
            )
        }
    }

    suspend fun executePluginTool(
        manifest: PluginManifest,
        tool: ToolDescriptor,
        request: ToolInvocationRequest
    ): ToolInvocationResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val spec = manifest.tools.firstOrNull { it.name == tool.name }
            ?: return@withContext ToolInvocationResult(request.requestId, tool.connectorId, tool.toolId, false, errorCode = "NOT_FOUND", errorMessage = "Tool '${tool.name}' missing in plugin '${manifest.pluginId}'.")

        val method = spec.httpMethod.uppercase()
        if (method !in setOf("GET", "HEAD") && "WRITE_ALLOWED" !in manifest.declaredPermissions.map { it.uppercase() }) {
            return@withContext ToolInvocationResult(
                requestId = request.requestId,
                connectorId = tool.connectorId,
                toolId = tool.toolId,
                success = false,
                errorCode = "SANDBOX_PERMISSION_DENIED",
                errorMessage = "Plugin '${manifest.name}' lacks WRITE_ALLOWED permission for $method requests."
            )
        }

        var resolvedUrl = spec.urlTemplate
        request.arguments.forEach { (k, v) ->
            resolvedUrl = resolvedUrl.replace("{$k}", URLEncoder.encode(v, "UTF-8"))
        }

        val urlCheck = SsrfPolicyValidator.validateUrl(
            rawUrl = resolvedUrl,
            allowLocalDev = false,
            domainAllowlist = manifest.allowedDomains
        )
        if (!urlCheck.allowed) {
            return@withContext ToolInvocationResult(request.requestId, tool.connectorId, tool.toolId, false, errorCode = "SANDBOX_DOMAIN_BLOCKED", errorMessage = urlCheck.reason)
        }

        try {
            val rb = Request.Builder().url(resolvedUrl)
            spec.headers.forEach { (k, v) -> rb.header(k, v) }
            if (method in setOf("GET", "HEAD")) {
                rb.method(method, null)
            } else {
                var bodyStr = spec.bodyTemplateJson.ifBlank {
                    JSONObject().apply { request.arguments.forEach { (k, v) -> put(k, v) } }.toString()
                }
                request.arguments.forEach { (k, v) ->
                    bodyStr = bodyStr.replace("{{$k}}", v.replace("\"", "\\\""))
                }
                rb.method(method, bodyStr.toRequestBody("application/json; charset=utf-8".toMediaType()))
            }

            httpClient.newCall(rb.build()).execute().use { resp ->
                val raw = SecretRedactor.redact(resp.body?.string().orEmpty())
                ToolInvocationResult(
                    requestId = request.requestId,
                    connectorId = tool.connectorId,
                    toolId = tool.toolId,
                    success = resp.isSuccessful,
                    statusCode = resp.code,
                    outputData = raw,
                    isolatedDataForLlm = UntrustedContentIsolator.isolate("Plugin:${manifest.name}", raw),
                    errorCode = if (resp.isSuccessful) null else "PLUGIN_HTTP_${resp.code}",
                    durationMs = System.currentTimeMillis() - started
                )
            }
        } catch (e: Exception) {
            ToolInvocationResult(
                requestId = request.requestId,
                connectorId = tool.connectorId,
                toolId = tool.toolId,
                success = false,
                errorCode = "PLUGIN_EXCEPTION",
                errorMessage = SecretRedactor.redact(e.message),
                durationMs = System.currentTimeMillis() - started
            )
        }
    }

    fun syntheticConnectorFor(manifest: PluginManifest, enabled: Boolean): ConnectorDefinition =
        ConnectorDefinition(
            id = "plugin_${manifest.pluginId}",
            name = manifest.name,
            provider = manifest.author,
            description = manifest.description,
            version = manifest.version,
            protocol = ConnectorProtocol.PLUGIN,
            status = if (enabled) ConnectorStatus.CONNECTED else ConnectorStatus.DISCONNECTED,
            authConfig = com.example.kucp.model.AuthConfig(AuthSchemeType.NONE),
            capabilities = manifest.tools.map { it.name },
            permissions = manifest.declaredPermissions,
            domainAllowlist = manifest.allowedDomains,
            enabled = enabled
        )

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
