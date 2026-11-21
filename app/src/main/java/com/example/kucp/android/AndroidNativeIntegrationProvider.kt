package com.example.kucp.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.example.kucp.model.AuthConfig
import com.example.kucp.model.AuthSchemeType
import com.example.kucp.model.ConnectorCategory
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
import com.example.services.KavyaAccessibilityService
import com.example.utils.AppResolver
import com.example.utils.PermissionsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class AndroidCapabilityPermissionStatus(
    val capabilityId: String,
    val title: String,
    val permissionName: String,
    val granted: Boolean,
    val remediationHint: String
)

/**
 * Android Native Integration Layer for KUCP.
 * Exposes legitimate, Play-Policy compliant Android device capabilities (Contacts search, Dialer intent,
 * Clipboard, Share Sheet, Installed App lookup & launching, Notification/Accessibility/Usage status)
 * into the exact same DynamicToolRegistry contract, checking runtime permissions cleanly.
 */
object AndroidNativeIntegrationProvider {

    const val CONNECTOR_ID = "android_native_core"

    fun connectorDefinition(): ConnectorDefinition = ConnectorDefinition(
        id = CONNECTOR_ID,
        name = "Android Device Services",
        provider = "Android OS",
        description = "Permission-gated on-device integrations: Contacts search, Dialer, Clipboard, Installed Apps, Share sheet, and Accessibility/Notification status.",
        version = "1.0.0",
        protocol = ConnectorProtocol.ANDROID_NATIVE,
        category = ConnectorCategory.ANDROID_SYSTEM,
        baseUrl = "android://system",
        status = ConnectorStatus.CONNECTED,
        authConfig = AuthConfig(schemeType = AuthSchemeType.NONE),
        capabilities = defaultTools().map { it.name },
        permissions = listOf("READ_CONTACTS", "CALL_PHONE_OPTIONAL", "ACCESSIBILITY_OPTIONAL", "NOTIFICATION_LISTENER_OPTIONAL"),
        enabled = true
    )

    fun defaultTools(): List<ToolDescriptor> = listOf(
        ToolDescriptor(
            toolId = "$CONNECTOR_ID:android_contacts_search",
            connectorId = CONNECTOR_ID,
            name = "android_contacts_search",
            description = "Searches device contacts by name or phone number (requires READ_CONTACTS permission).",
            protocol = ConnectorProtocol.ANDROID_NATIVE,
            methodOrOperation = "QUERY",
            inputSchema = listOf(
                ToolParameterSchema("query", "string", "Contact name substring", true, location = ParamLocation.BODY),
                ToolParameterSchema("limit", "integer", "Max contacts", false, "10", location = ParamLocation.BODY)
            ),
            riskLevel = ToolRiskLevel.LOW,
            requiredScopes = listOf(Manifest.permission.READ_CONTACTS),
            tags = listOf("android", "contacts", "phone", "addressbook")
        ),
        ToolDescriptor(
            toolId = "$CONNECTOR_ID:android_dial_number",
            connectorId = CONNECTOR_ID,
            name = "android_dial_number",
            description = "Opens the system dialer prefilled with a phone number (uses zero-risk ACTION_DIAL by default).",
            protocol = ConnectorProtocol.ANDROID_NATIVE,
            methodOrOperation = "INTENT",
            inputSchema = listOf(
                ToolParameterSchema("phone_number", "string", "Target phone number", true, location = ParamLocation.BODY)
            ),
            riskLevel = ToolRiskLevel.MEDIUM,
            requiresConfirmation = true,
            isIdempotent = false,
            tags = listOf("android", "dial", "call", "phone")
        ),
        ToolDescriptor(
            toolId = "$CONNECTOR_ID:android_installed_apps",
            connectorId = CONNECTOR_ID,
            name = "android_installed_apps",
            description = "Lists or searches installed launchable Android applications.",
            protocol = ConnectorProtocol.ANDROID_NATIVE,
            methodOrOperation = "QUERY",
            inputSchema = listOf(
                ToolParameterSchema("query", "string", "Optional filter query", false, location = ParamLocation.BODY),
                ToolParameterSchema("limit", "integer", "Max apps", false, "20", location = ParamLocation.BODY)
            ),
            riskLevel = ToolRiskLevel.LOW,
            tags = listOf("android", "apps", "packages", "launcher")
        ),
        ToolDescriptor(
            toolId = "$CONNECTOR_ID:android_launch_app",
            connectorId = CONNECTOR_ID,
            name = "android_launch_app",
            description = "Launches an installed Android application by name or package.",
            protocol = ConnectorProtocol.ANDROID_NATIVE,
            methodOrOperation = "INTENT",
            inputSchema = listOf(
                ToolParameterSchema("app_name", "string", "Application name or package", true, location = ParamLocation.BODY)
            ),
            riskLevel = ToolRiskLevel.LOW,
            isIdempotent = true,
            tags = listOf("android", "open", "launch", "app")
        ),
        ToolDescriptor(
            toolId = "$CONNECTOR_ID:android_clipboard",
            connectorId = CONNECTOR_ID,
            name = "android_clipboard",
            description = "Reads or copies text to the Android system clipboard.",
            protocol = ConnectorProtocol.ANDROID_NATIVE,
            methodOrOperation = "LOCAL",
            inputSchema = listOf(
                ToolParameterSchema("mode", "string", "read or write", true, "read", enumValues = listOf("read", "write")),
                ToolParameterSchema("text", "string", "Text to copy when mode=write", false)
            ),
            riskLevel = ToolRiskLevel.MEDIUM,
            tags = listOf("android", "clipboard", "copy", "paste")
        ),
        ToolDescriptor(
            toolId = "$CONNECTOR_ID:android_share_text",
            connectorId = CONNECTOR_ID,
            name = "android_share_text",
            description = "Opens the Android share sheet with text content.",
            protocol = ConnectorProtocol.ANDROID_NATIVE,
            methodOrOperation = "INTENT",
            inputSchema = listOf(
                ToolParameterSchema("text", "string", "Text to share", true),
                ToolParameterSchema("subject", "string", "Optional subject", false)
            ),
            riskLevel = ToolRiskLevel.MEDIUM,
            requiresConfirmation = true,
            isIdempotent = false,
            tags = listOf("android", "share", "export")
        ),
        ToolDescriptor(
            toolId = "$CONNECTOR_ID:android_system_capabilities",
            connectorId = CONNECTOR_ID,
            name = "android_system_capabilities",
            description = "Checks permission status for Contacts, Microphone, Camera, Accessibility, Notifications, Usage Stats, and Overlay.",
            protocol = ConnectorProtocol.ANDROID_NATIVE,
            methodOrOperation = "QUERY",
            riskLevel = ToolRiskLevel.LOW,
            tags = listOf("android", "permissions", "accessibility", "notifications", "status")
        )
    )

    fun inspectPermissionsMatrix(context: Context): List<AndroidCapabilityPermissionStatus> {
        val hasContacts = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasAudio = PermissionsManager.hasRecordAudioPermission(context)
        val hasCall = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val hasA11y = PermissionsManager.isAccessibilityServiceEnabled(context, KavyaAccessibilityService::class.java)
        val hasNotif = PermissionsManager.hasNotificationListenerPermission(context)
        val hasOverlay = PermissionsManager.canDrawOverlays(context)
        val hasUsage = PermissionsManager.hasUsageStatsPermission(context)

        return listOf(
            AndroidCapabilityPermissionStatus("contacts", "Contacts Access", Manifest.permission.READ_CONTACTS, hasContacts, "Grant Contacts permission in Android App Settings or Permissions screen."),
            AndroidCapabilityPermissionStatus("camera", "Camera & Vision", Manifest.permission.CAMERA, hasCamera, "Grant Camera permission for live visual capture."),
            AndroidCapabilityPermissionStatus("mic", "Microphone Audio", Manifest.permission.RECORD_AUDIO, hasAudio, "Grant Microphone permission for voice interaction."),
            AndroidCapabilityPermissionStatus("call", "Direct Call (Optional)", Manifest.permission.CALL_PHONE, hasCall, "Action Dialer works without CALL_PHONE; direct calling requires Phone permission."),
            AndroidCapabilityPermissionStatus("a11y", "UI Accessibility Control", "KavyaAccessibilityService", hasA11y, "Enable Kavya in Android Accessibility Settings."),
            AndroidCapabilityPermissionStatus("notif", "Notification Listener", "NotificationListenerService", hasNotif, "Enable Kavya under Notification Access."),
            AndroidCapabilityPermissionStatus("overlay", "Floating Overlay / HUD", "SYSTEM_ALERT_WINDOW", hasOverlay, "Allow 'Display over other apps' in System Settings."),
            AndroidCapabilityPermissionStatus("usage", "App Usage Access", "PACKAGE_USAGE_STATS", hasUsage, "Enable Usage Data Access if context telemetry is desired.")
        )
    }

    suspend fun execute(
        context: Context,
        tool: ToolDescriptor,
        request: ToolInvocationRequest
    ): ToolInvocationResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        try {
            when (tool.name) {
                "android_system_capabilities" -> {
                    val matrix = inspectPermissionsMatrix(context)
                    val arr = JSONArray()
                    matrix.forEach { item ->
                        arr.put(JSONObject().apply {
                            put("id", item.capabilityId)
                            put("title", item.title)
                            put("granted", item.granted)
                            put("hint", item.remediationHint)
                        })
                    }
                    ToolInvocationResult(request.requestId, CONNECTOR_ID, tool.toolId, true, 200, arr.toString(), arr.toString(), durationMs = System.currentTimeMillis() - started)
                }

                "android_contacts_search" -> {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                        return@withContext ToolInvocationResult(
                            requestId = request.requestId,
                            connectorId = CONNECTOR_ID,
                            toolId = tool.toolId,
                            success = false,
                            errorCode = "PERMISSION_DENIED",
                            errorMessage = "READ_CONTACTS permission is not granted. Open Permissions Settings to authorize contact search.",
                            durationMs = System.currentTimeMillis() - started
                        )
                    }
                    val query = request.arguments["query"].orEmpty().trim()
                    val limit = request.arguments["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 10
                    val contacts = JSONArray()
                    val cursor = context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                        ),
                        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                        arrayOf("%$query%"),
                        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
                    )
                    cursor?.use { c ->
                        val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        while (c.moveToNext() && contacts.length() < limit) {
                            contacts.put(JSONObject().apply {
                                put("name", if (nameIdx >= 0) c.getString(nameIdx) else "")
                                put("phone", if (numIdx >= 0) c.getString(numIdx) else "")
                            })
                        }
                    }
                    val out = contacts.toString()
                    ToolInvocationResult(request.requestId, CONNECTOR_ID, tool.toolId, true, 200, out, out, durationMs = System.currentTimeMillis() - started)
                }

                "android_dial_number" -> {
                    val number = request.arguments["phone_number"].orEmpty().trim()
                    if (number.isBlank()) {
                        return@withContext ToolInvocationResult(request.requestId, CONNECTOR_ID, tool.toolId, false, errorCode = "INVALID_ARG", errorMessage = "phone_number is required.")
                    }
                    val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(dialIntent)
                    ToolInvocationResult(request.requestId, CONNECTOR_ID, tool.toolId, true, 200, "Opened dialer for $number", "Opened dialer for $number", durationMs = System.currentTimeMillis() - started)
                }

                "android_installed_apps" -> {
                    val q = request.arguments["query"].orEmpty().trim().lowercase()
                    val limit = request.arguments["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20
                    val pm = context.packageManager
                    val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                        .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                        .map {
                            val label = pm.getApplicationLabel(it).toString()
                            label to it.packageName
                        }
                        .filter { (label, pkg) -> q.isBlank() || label.lowercase().contains(q) || pkg.lowercase().contains(q) }
                        .sortedBy { it.first.lowercase() }
                        .take(limit)
                    val arr = JSONArray()
                    apps.forEach { (label, pkg) ->
                        arr.put(JSONObject().put("name", label).put("package", pkg))
                    }
                    ToolInvocationResult(request.requestId, CONNECTOR_ID, tool.toolId, true, 200, arr.toString(), arr.toString(), durationMs = System.currentTimeMillis() - started)
                }

                "android_launch_app" -> {
                    val appName = request.arguments["app_name"].orEmpty().trim()
                    if (appName.isBlank()) {
                        return@withContext ToolInvocationResult(request.requestId, CONNECTOR_ID, tool.toolId, false, errorCode = "INVALID_ARG", errorMessage = "app_name is required.")
                    }
                    val pm = context.packageManager
                    val directIntent: Intent? = pm.getLaunchIntentForPackage(appName)
                    val launchIntent: Intent? = directIntent ?: run {
                        val resolver = AppResolver(context)
                        val match = resolver.resolve(appName)
                        match.matchedApp?.let { pm.getLaunchIntentForPackage(it.packageName) }
                    }
                    if (launchIntent == null) {
                        return@withContext ToolInvocationResult(request.requestId, CONNECTOR_ID, tool.toolId, false, errorCode = "APP_NOT_FOUND", errorMessage = "No installed launchable app matched '$appName'.")
                    }
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    ToolInvocationResult(request.requestId, CONNECTOR_ID, tool.toolId, true, 200, "Launched $appName", "Launched $appName", durationMs = System.currentTimeMillis() - started)
                }

                "android_clipboard" -> {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        ?: return@withContext ToolInvocationResult(request.requestId, CONNECTOR_ID, tool.toolId, false, errorCode = "UNAVAILABLE", errorMessage = "ClipboardManager unavailable.")
                    val mode = request.arguments["mode"].orEmpty().lowercase().ifBlank { "read" }
                    if (mode == "write") {
                        val text = request.arguments["text"].orEmpty()
                        cm.setPrimaryClip(ClipData.newPlainText("Kavya KUCP", text))
                        ToolInvocationResult(request.requestId, CONNECTOR_ID, tool.toolId, true, 200, "Copied ${text.length} chars to clipboard.", "Copied to clipboard.", durationMs = System.currentTimeMillis() - started)
                    } else {
                        val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        val redacted = SecretRedactor.redact(clip)
                        ToolInvocationResult(request.requestId, CONNECTOR_ID, tool.toolId, true, 200, redacted, redacted, durationMs = System.currentTimeMillis() - started)
                    }
                }

                "android_share_text" -> {
                    val text = request.arguments["text"].orEmpty()
                    if (text.isBlank()) {
                        return@withContext ToolInvocationResult(request.requestId, CONNECTOR_ID, tool.toolId, false, errorCode = "INVALID_ARG", errorMessage = "text is required.")
                    }
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                        request.arguments["subject"]?.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                    }
                    val chooser = Intent.createChooser(shareIntent, "Share via").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(chooser)
                    ToolInvocationResult(request.requestId, CONNECTOR_ID, tool.toolId, true, 200, "Opened Android share chooser.", "Opened share chooser.", durationMs = System.currentTimeMillis() - started)
                }

                else -> ToolInvocationResult(request.requestId, CONNECTOR_ID, tool.toolId, false, errorCode = "UNKNOWN_TOOL", errorMessage = "Unknown Android native tool '${tool.name}'.")
            }
        } catch (e: Exception) {
            ToolInvocationResult(request.requestId, CONNECTOR_ID, tool.toolId, false, errorCode = "ANDROID_ERR", errorMessage = SecretRedactor.redact(e.message), durationMs = System.currentTimeMillis() - started)
        }
    }
}
