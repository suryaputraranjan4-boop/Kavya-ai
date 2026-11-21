package com.example.vision

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

@Composable
fun VisionTechInfoAndSetupDialog(
    spec: VisionTechnologySpec,
    diagnostics: VisionRuntimeDiagnostics?,
    accentColor: Color,
    onDismiss: () -> Unit,
    onDiagnosticsUpdated: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tripoUrl by remember { mutableStateOf(VisionPackageManager.getTripoSrEndpoint(context)) }
    var tripoToken by remember { mutableStateOf(VisionPackageManager.getTripoSrToken(context)) }
    var manimUrl by remember { mutableStateOf(VisionPackageManager.getManimEndpoint(context)) }
    var manimToken by remember { mutableStateOf(VisionPackageManager.getManimToken(context)) }
    var cesiumToken by remember { mutableStateOf(VisionPackageManager.getCesiumIonToken(context)) }
    var verificationResultText by remember { mutableStateOf<String?>(null) }
    var isTestingHealth by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF0E1522),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.2.dp, accentColor.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                .testTag("vision_tech_info_dialog")
        ) {
            LazyColumn(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = spec.displayName,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = accentColor.copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = "v${spec.version} • ${spec.licenseName}",
                                color = accentColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(spec.description, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                }

                // Official Source / Installation & Docs links (always opened cleanly in external system browser)
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(spec.officialSourceUrl)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    })
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Code, contentDescription = "Official Source / Installation", tint = accentColor, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Source / Install", fontSize = 11.sp, color = Color.White)
                        }
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(spec.docsUrl)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    })
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.MenuBook, contentDescription = "Official Docs", tint = accentColor, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Docs", fontSize = 11.sp, color = Color.White)
                        }
                    }
                }

                // Cryptographic & Hardware Verification Diagnostics
                item {
                    val statusColor = when (diagnostics?.availabilityStatus) {
                        VisionAvailabilityStatus.INSTALLED_VERIFIED,
                        VisionAvailabilityStatus.BACKEND_READY -> Color(0xFF10B981)
                        VisionAvailabilityStatus.CONFIGURED_UNTESTED,
                        VisionAvailabilityStatus.BACKEND_REQUIRED -> Color(0xFFF59E0B)
                        else -> Color(0xFFFF5252)
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF070B12),
                        modifier = Modifier.fillMaxWidth().border(1.dp, statusColor.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "STATUS: ${diagnostics?.availabilityStatus?.label ?: "Unknown"}",
                                color = statusColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = diagnostics?.statusDetail ?: spec.backendRoleDescription,
                                color = Color.White.copy(alpha = 0.78f),
                                fontSize = 11.sp
                            )
                            Text(
                                text = "WebGL: ${if (diagnostics?.webGlAvailable != false) "Supported" else "Unavailable"} • ARCore: ${if (diagnostics?.arSupported == true) "Detected" else "Not Detected"}",
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 10.5.sp
                            )
                            if (!diagnostics?.expectedSha256.isNullOrBlank()) {
                                Text(
                                    text = "Manifest SHA-256: ${diagnostics?.expectedSha256?.take(24)}…",
                                    color = Color(0xFF7DD3FC),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    scope.launch {
                                        val map = VisionPackageManager.verifyAllAsync(context)
                                        val d = map[spec.id]
                                        VisionTechnologyDownloadManager.getInstance(context).refreshAllStates()
                                        verificationResultText = if (d?.sha256Verified == true) {
                                            "Verified ${spec.displayName} SHA-256 (${d.verifiedBytes / 1024} KB)."
                                        } else {
                                            d?.statusDetail ?: "Verification complete."
                                        }
                                        onDiagnosticsUpdated()
                                    }
                                }) {
                                    Text("Verify All SHA-256", color = accentColor, fontSize = 11.sp)
                                }
                                TextButton(onClick = {
                                    val freed = VisionPackageManager.clearPackageCache(context) + VisualizationStorageRepository.clearAllSavedExports(context)
                                    VisionTechnologyDownloadManager.getInstance(context).refreshAllStates()
                                    verificationResultText = "Cleared ${freed / 1024} KB local exports & cache."
                                    onDiagnosticsUpdated()
                                }) {
                                    Text("Clear Cache", color = Color(0xFFFFB74D), fontSize = 11.sp)
                                }
                            }
                            verificationResultText?.let {
                                Text(it, color = Color(0xFF10B981), fontSize = 10.5.sp)
                            }
                        }
                    }
                }

                // Remote Worker Configuration (shown cleanly for TripoSR & Manim Community) or Cesium Ion token
                if (spec.id == VisionTechId.TRIPO_SR || spec.id == VisionTechId.MANIM || spec.id == VisionTechId.CESIUM_JS) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (spec.id == VisionTechId.TRIPO_SR) {
                                Text("TRIPOSR PYTHON WORKER ENDPOINT", color = accentColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                OutlinedTextField(
                                    value = tripoUrl,
                                    onValueChange = { tripoUrl = it },
                                    label = { Text("Worker URL (e.g. https://... or http://10.0.2.2:8000)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = tripoToken,
                                    onValueChange = { tripoToken = it },
                                    label = { Text("Bearer Token (Optional, stored encrypted)") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else if (spec.id == VisionTechId.MANIM) {
                                Text("MANIM COMMUNITY RENDER WORKER", color = accentColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                OutlinedTextField(
                                    value = manimUrl,
                                    onValueChange = { manimUrl = it },
                                    label = { Text("Worker URL (e.g. https://... or http://10.0.2.2:8090)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = manimToken,
                                    onValueChange = { manimToken = it },
                                    label = { Text("Bearer Token (Optional, stored encrypted)") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else if (spec.id == VisionTechId.CESIUM_JS) {
                                Text("CESIUM ION ACCESS TOKEN (OPTIONAL)", color = accentColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                OutlinedTextField(
                                    value = cesiumToken,
                                    onValueChange = { cesiumToken = it },
                                    label = { Text("Encrypted Ion Token (leave blank for offline NaturalEarth)") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = {
                                        when (spec.id) {
                                            VisionTechId.TRIPO_SR -> VisionPackageManager.setTripoSrEndpoint(context, tripoUrl, tripoToken)
                                            VisionTechId.MANIM -> VisionPackageManager.setManimEndpoint(context, manimUrl, manimToken)
                                            VisionTechId.CESIUM_JS -> VisionPackageManager.setCesiumIonToken(context, cesiumToken)
                                            else -> {}
                                        }
                                        VisionTechnologyDownloadManager.getInstance(context).refreshAllStates()
                                        onDiagnosticsUpdated()
                                        Toast.makeText(context, "Saved securely", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Save Config", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                                }

                                if (spec.runtimeType == VisionRuntimeType.REMOTE_PYTHON_WORKER) {
                                    OutlinedButton(
                                        enabled = !isTestingHealth,
                                        onClick = {
                                            if (spec.id == VisionTechId.TRIPO_SR) VisionPackageManager.setTripoSrEndpoint(context, tripoUrl, tripoToken)
                                            else VisionPackageManager.setManimEndpoint(context, manimUrl, manimToken)
                                            isTestingHealth = true
                                            scope.launch {
                                                val status = TripoSrAndManimWorkers.testWorkerHealth(context, spec.id)
                                                VisionTechnologyDownloadManager.getInstance(context).refreshAllStates()
                                                verificationResultText = status.detailMessage
                                                isTestingHealth = false
                                                onDiagnosticsUpdated()
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(if (isTestingHealth) "Testing..." else "Test Health", color = Color.White, fontSize = 11.5.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                // Capabilities list
                item {
                    Text("CAPABILITIES", color = accentColor, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    spec.capabilities.forEach { cap ->
                        Text("• $cap", color = Color.White.copy(alpha = 0.8f), fontSize = 11.5.sp)
                    }
                }

                item {
                    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
fun VisionTemplatesAndSavedSheet(
    savedArtifacts: List<VisualizationArtifact>,
    accentColor: Color,
    onSelectTemplate: (VisualizationTemplate) -> Unit,
    onOpenArtifact: (VisualizationArtifact) -> Unit,
    onToggleFavorite: (VisualizationArtifact) -> Unit,
    onRenameArtifact: (VisualizationArtifact, String) -> Unit,
    onDuplicateArtifact: (VisualizationArtifact) -> Unit,
    onDeleteArtifact: (VisualizationArtifact) -> Unit,
    onDismiss: () -> Unit
) {
    var innerTab by remember { mutableIntStateOf(0) }
    var renameTarget by remember { mutableStateOf<VisualizationArtifact?>(null) }
    var renameText by remember { mutableStateOf("") }
    val templates = remember { VisionTechRegistry.defaultTemplates() }

    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Visualization") },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    renameTarget?.let { onRenameArtifact(it, renameText) }
                    renameTarget = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TabRow(selectedTabIndex = innerTab, containerColor = Color(0xFF0E1522), contentColor = accentColor) {
            Tab(selected = innerTab == 0, onClick = { innerTab = 0 }, text = { Text("Starter Templates (${templates.size})") })
            Tab(selected = innerTab == 1, onClick = { innerTab = 1 }, text = { Text("Saved Vault (${savedArtifacts.size})") })
        }

        if (innerTab == 0) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 460.dp)
            ) {
                items(templates, key = { it.id }) { tpl ->
                    val spec = VisionTechRegistry.getSpec(tpl.techId)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF101724),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
                            .clickable {
                                onSelectTemplate(tpl)
                                onDismiss()
                            }
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tpl.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                Surface(shape = RoundedCornerShape(6.dp), color = accentColor.copy(alpha = 0.15f)) {
                                    Text(spec.displayName, color = accentColor, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                            Text(tpl.description, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                        }
                    }
                }
            }
        } else {
            if (savedArtifacts.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth().padding(28.dp)
                ) {
                    Text(
                        "No saved visualizations yet. Generate a visual or open a template to save here.",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 12.sp
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 460.dp)
                ) {
                    items(savedArtifacts, key = { it.artifactId }) { item ->
                        val spec = VisionTechRegistry.getSpec(item.techId)
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFF101724),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
                                .clickable {
                                    onOpenArtifact(item)
                                    onDismiss()
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${spec.displayName} • ${item.summaryText}", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { onToggleFavorite(item) }) {
                                    Icon(
                                        imageVector = if (item.isSavedFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                                        contentDescription = "Favorite",
                                        tint = if (item.isSavedFavorite) Color(0xFFF59E0B) else Color.White.copy(alpha = 0.6f)
                                    )
                                }
                                IconButton(onClick = {
                                    renameTarget = item
                                    renameText = item.title
                                }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Rename", tint = Color.White.copy(alpha = 0.75f))
                                }
                                IconButton(onClick = { onDuplicateArtifact(item) }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate", tint = Color.White.copy(alpha = 0.75f))
                                }
                                IconButton(onClick = { onDeleteArtifact(item) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VisionFullscreenDialog(
    artifact: VisualizationArtifact,
    quality: QualityPreset,
    reducedMotion: Boolean,
    cesiumIonToken: String,
    arSupported: Boolean,
    controller: VisionWebViewController,
    onEvent: (VisionJsEvent) -> Unit,
    onClose: () -> Unit
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF070B12))
                .testTag("vision_fullscreen_dialog")
        ) {
            VisionWebViewHost(
                artifact = artifact,
                quality = quality,
                reducedMotion = reducedMotion,
                cesiumIonToken = cesiumIonToken,
                arSupported = arSupported,
                controller = controller,
                onEvent = onEvent,
                modifier = Modifier.fillMaxSize()
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xCC0E1522))
            ) {
                Icon(Icons.Default.CloseFullscreen, contentDescription = "Exit Fullscreen", tint = Color.White)
            }
        }
    }
}
