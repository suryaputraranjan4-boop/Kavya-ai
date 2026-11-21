package com.example.vision

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * In-App Visualization Technology Library & Real Download Manager replacing the legacy 12-model catalog.
 * Displays all 12 exact technologies with official docs/source links, real registry downloads,
 * pause/resume/cancel/retry, cryptographic verification, storage checks, safe removal, and direct Open/Use integration.
 */
@Composable
fun VisualizationTechnologyLibrarySection(
    accentColor: Color,
    onOpenTechnology: (VisionTechId) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloadManager = remember { VisionTechnologyDownloadManager.getInstance(context) }
    val techStates by downloadManager.states.collectAsState()
    val storageSummary by downloadManager.storageSummary.collectAsState()
    val pkgDiagnostics by VisionPackageManager.diagnostics.collectAsState()

    var selectedFilter by remember { mutableStateOf("ALL") }
    var searchQuery by remember { mutableStateOf("") }
    var setupDialogTech by remember { mutableStateOf<VisionTechnologySpec?>(null) }
    var isVerifyingAll by remember { mutableStateOf(false) }

    LaunchedEffect(context) {
        downloadManager.refreshAllStates()
    }

    val all12 = VisionTechRegistry.core12Technologies
    val filteredTechs = remember(selectedFilter, searchQuery) {
        val byCat = when (selectedFilter) {
            "3D" -> all12.filter { it.category == VisionCategory.THREE_D_AND_SPATIAL }
            "DIAGRAMS" -> all12.filter { it.category == VisionCategory.DIAGRAMS_AND_FLOWCHARTS }
            "CHARTS" -> all12.filter { it.category == VisionCategory.CHARTS_AND_DATA }
            "ANIMATION" -> all12.filter { it.category == VisionCategory.ANIMATION_AND_EXPLAINER }
            "PYTHON" -> all12.filter { it.runtimeType == VisionRuntimeType.REMOTE_PYTHON_WORKER }
            else -> all12
        }
        if (searchQuery.isBlank()) byCat
        else byCat.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true) ||
                it.visualizationRole.contains(searchQuery, ignoreCase = true) ||
                it.description.contains(searchQuery, ignoreCase = true)
        }
    }

    if (setupDialogTech != null) {
        VisionTechInfoAndSetupDialog(
            spec = setupDialogTech!!,
            diagnostics = pkgDiagnostics[setupDialogTech!!.id],
            accentColor = accentColor,
            onDismiss = { setupDialogTech = null },
            onDiagnosticsUpdated = {
                VisionPackageManager.refreshAllDiagnostics(context)
                downloadManager.refreshAllStates()
            }
        )
    }

    com.example.ui.components.GlassCard(
        shape = RoundedCornerShape(22.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("visualization_technology_library_section")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Header + Overall Status Pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = "Visualization Technology Library",
                        style = MaterialTheme.typography.titleMedium,
                        color = com.example.ui.theme.TextPrimary
                    )
                    Text(
                        text = "12 official 3D, diagram, charting, GIS & animation technologies",
                        style = MaterialTheme.typography.bodySmall,
                        color = com.example.ui.theme.TextSecondary
                    )
                }
                com.example.ui.components.StatusBadge(
                    text = "${storageSummary.functionalCount}/12 Functional",
                    isActive = storageSummary.functionalCount > 0,
                    activeColor = com.example.ui.theme.SuccessGreen,
                    inactiveColor = com.example.ui.theme.PrimaryLight
                )
            }

            // Centralized Storage, Batch Download & Verification Control Well
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xB2101520),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, com.example.ui.theme.OutlineVariant, RoundedCornerShape(16.dp))
                    .testTag("tech_library_storage_summary_card")
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "In-App Download Manager",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = com.example.ui.theme.TextPrimary
                        )
                        Text(
                            text = "${storageSummary.installedCount}/12 Installed • ${downloadManager.formatSize(storageSummary.availableDeviceBytes)} free",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (storageSummary.hasSufficientHeadroom) com.example.ui.theme.AccentCyan else com.example.ui.theme.Error
                        )
                    }

                    Text(
                        text = "Downloaded Library: ${downloadManager.formatSize(storageSummary.downloadedLibraryBytes)} • Active Transfers: ${storageSummary.activeDownloadsCount} • 10 JS engines run locally in WebView; TripoSR & Manim Community use separate Python workers.",
                        color = com.example.ui.theme.TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val queued = downloadManager.downloadAllSupported()
                                val msg = if (queued > 0) {
                                    "Queued $queued official npm packages for download & SHA-512 verification."
                                } else {
                                    "All 10 supported WebView packages are already installed or downloading."
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            },
                            enabled = storageSummary.hasSufficientHeadroom,
                            colors = ButtonDefaults.buttonColors(containerColor = com.example.ui.theme.Primary),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1.35f)
                                .heightIn(min = 48.dp)
                                .testTag("tech_library_download_all_btn")
                        ) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = "Download All Supported",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Download All (10 Web)",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.5.sp,
                                maxLines = 1
                            )
                        }

                        OutlinedButton(
                            enabled = !isVerifyingAll,
                            onClick = {
                                isVerifyingAll = true
                                scope.launch {
                                    for (spec in all12) {
                                        downloadManager.verifyTechnologyIntegration(spec.id)
                                    }
                                    isVerifyingAll = false
                                    Toast.makeText(context, "Verified all 12 technology runtimes & endpoints.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, com.example.ui.theme.GlassBorder),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(0.95f)
                                .heightIn(min = 48.dp)
                                .testTag("tech_library_verify_all_btn")
                        ) {
                            Icon(
                                Icons.Default.Verified,
                                contentDescription = "Verify All Technologies",
                                tint = accentColor,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (isVerifyingAll) "Verifying..." else "Verify All",
                                color = com.example.ui.theme.TextPrimary,
                                fontSize = 11.5.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // Search Box
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search 12 technologies (TripoSR, Three.js, Manim...)", fontSize = 11.5.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search Technology Library") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("tech_library_search_input"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accentColor,
                    unfocusedBorderColor = com.example.ui.theme.OutlineVariant,
                    focusedTextColor = com.example.ui.theme.TextPrimary,
                    unfocusedTextColor = com.example.ui.theme.TextPrimary
                )
            )

            // Category Filter Pills
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val filters = listOf(
                    "ALL" to "All (12)",
                    "3D" to "3D & GIS (5)",
                    "DIAGRAMS" to "Diagrams (3)",
                    "CHARTS" to "Charts (2)",
                    "ANIMATION" to "Animation (2)",
                    "PYTHON" to "Python Workers (2)"
                )
                items(filters) { (key, label) ->
                    val active = selectedFilter == key
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (active) com.example.ui.theme.Primary.copy(alpha = 0.24f) else Color(0x88181D29),
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .border(
                                width = 1.dp,
                                color = if (active) com.example.ui.theme.PrimaryLight.copy(alpha = 0.7f) else com.example.ui.theme.OutlineVariant,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable { selectedFilter = key }
                            .testTag("tech_library_filter_$key")
                    ) {
                        Text(
                            text = label,
                            color = if (active) com.example.ui.theme.TextPrimary else com.example.ui.theme.TextSecondary,
                            fontSize = 11.5.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // 12 Technology Cards
            filteredTechs.forEach { spec ->
                val state = techStates[spec.id] ?: TechnologyDownloadState(techId = spec.id)
                TechnologyLibraryCard(
                    spec = spec,
                    state = state,
                    accentColor = accentColor,
                    formatSize = downloadManager::formatSize,
                    onOpenUrl = { url ->
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        }.onFailure {
                            Toast.makeText(context, "Unable to open URL: $url", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onDownloadOrResume = { downloadManager.startOrResumeDownload(spec.id) },
                    onPause = { downloadManager.pauseDownload(spec.id) },
                    onCancel = { downloadManager.cancelDownload(spec.id) },
                    onRetry = { downloadManager.retryDownload(spec.id) },
                    onVerify = {
                        scope.launch {
                            val res = downloadManager.verifyTechnologyIntegration(spec.id)
                            Toast.makeText(context, "${spec.displayName}: ${res.stage.label}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onRemove = {
                        val freed = downloadManager.removeDownloadedAssets(spec.id)
                        Toast.makeText(
                            context,
                            "Removed ${spec.displayName} downloaded assets (${downloadManager.formatSize(freed)} freed).",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onConfigure = { setupDialogTech = spec },
                    onOpenAndUse = { onOpenTechnology(spec.id) }
                )
            }
        }
    }
}

@Composable
private fun TechnologyLibraryCard(
    spec: VisionTechnologySpec,
    state: TechnologyDownloadState,
    accentColor: Color,
    formatSize: (Long) -> String,
    onOpenUrl: (String) -> Unit,
    onDownloadOrResume: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onVerify: () -> Unit,
    onRemove: () -> Unit,
    onConfigure: () -> Unit,
    onOpenAndUse: () -> Unit
) {
    val stageColor = when (state.stage) {
        TechnologyInstallStage.FUNCTIONAL -> com.example.ui.theme.SuccessGreen
        TechnologyInstallStage.INSTALLED,
        TechnologyInstallStage.DOWNLOADED -> com.example.ui.theme.AccentCyan
        TechnologyInstallStage.CONFIGURED,
        TechnologyInstallStage.PAUSED -> Color(0xFFF59E0B)
        TechnologyInstallStage.DOWNLOADING,
        TechnologyInstallStage.RESOLVING_REGISTRY,
        TechnologyInstallStage.VERIFYING,
        TechnologyInstallStage.QUEUED -> com.example.ui.theme.PrimaryLight
        TechnologyInstallStage.ERROR -> com.example.ui.theme.Error
        TechnologyInstallStage.NOT_DOWNLOADED -> com.example.ui.theme.TextSecondary
    }

    val displaySizeBytes = when {
        state.installedSizeBytes > 0L -> state.installedSizeBytes
        state.totalBytes > 0L -> state.totalBytes
        state.packageSizeBytes > 0L -> state.packageSizeBytes
        else -> spec.estimatedPackageSizeBytes
    }
    val isPythonWorker = spec.runtimeType == VisionRuntimeType.REMOTE_PYTHON_WORKER
    val canOpen = state.stage == TechnologyInstallStage.FUNCTIONAL ||
        state.stage == TechnologyInstallStage.INSTALLED ||
        state.stage == TechnologyInstallStage.CONFIGURED ||
        state.stage == TechnologyInstallStage.DOWNLOADED ||
        state.bundledRuntimeAvailable ||
        isPythonWorker

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xCC0D1320),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (state.stage == TechnologyInstallStage.FUNCTIONAL) {
                    com.example.ui.theme.SuccessGreen.copy(alpha = 0.45f)
                } else {
                    com.example.ui.theme.OutlineVariant
                },
                shape = RoundedCornerShape(18.dp)
            )
            .testTag("tech_card_${spec.id.wireId}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Row 1: Name + Status Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(stageColor)
                    )
                    Column {
                        Text(
                            text = spec.displayName,
                            color = com.example.ui.theme.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${spec.visualizationRole} • ${spec.category.badgeLabel}",
                            color = accentColor,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = stageColor.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, stageColor.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = if (state.stage == TechnologyInstallStage.DOWNLOADING) {
                            "${state.stage.label} ${state.progressPercent}%"
                        } else {
                            state.stage.label
                        },
                        color = stageColor,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }
            }

            // Row 2: Description
            Text(
                text = spec.description,
                color = com.example.ui.theme.TextSecondary,
                fontSize = 11.5.sp,
                lineHeight = 16.sp
            )

            // Row 3: Package Name, Runtime Kind, Size & License metadata
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.Black.copy(alpha = 0.28f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "Package: ${spec.packageName} • v${state.resolvedVersion.ifBlank { spec.version }} • ${formatSize(displaySizeBytes)}",
                        color = Color(0xFFBAE6FD),
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Runtime: ${spec.downloadKind.label} • License: ${state.resolvedLicense.ifBlank { spec.licenseName }}" +
                            if (state.integrityVerified) " • Verified (${state.integrityAlgorithm})" else "",
                        color = com.example.ui.theme.TextSecondary,
                        fontSize = 10.sp
                    )
                }
            }

            // Row 4: Official Documentation & Official Source/Installation Links
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onOpenUrl(spec.docsUrl) },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, com.example.ui.theme.GlassBorder),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("tech_docs_link_${spec.id.wireId}")
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = "Official Documentation for ${spec.displayName}",
                        tint = accentColor,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("Official Docs", color = com.example.ui.theme.TextPrimary, fontSize = 11.sp, maxLines = 1)
                }
                OutlinedButton(
                    onClick = { onOpenUrl(spec.officialSourceUrl) },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, com.example.ui.theme.GlassBorder),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("tech_source_link_${spec.id.wireId}")
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "Official Source for ${spec.displayName}",
                        tint = accentColor,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("Source / Install", color = com.example.ui.theme.TextPrimary, fontSize = 11.sp, maxLines = 1)
                }
            }

            // Row 5: Live Progress Bar during active or paused transfer
            if (state.stage.isActiveTransfer || state.stage == TechnologyInstallStage.PAUSED) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (state.downloadedBytes > 0L) {
                                "${formatSize(state.downloadedBytes)} / ${formatSize(maxOf(state.totalBytes, state.downloadedBytes))}"
                            } else {
                                state.stage.label
                            },
                            color = com.example.ui.theme.TextPrimary,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "${state.progressPercent}%",
                            color = stageColor,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (state.stage == TechnologyInstallStage.RESOLVING_REGISTRY || state.stage == TechnologyInstallStage.VERIFYING) {
                        LinearProgressIndicator(
                            color = stageColor,
                            trackColor = Color.White.copy(alpha = 0.12f),
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { state.progressFraction },
                            color = stageColor,
                            trackColor = Color.White.copy(alpha = 0.12f),
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                        )
                    }
                }
            }

            // Row 6: Honest Status / Platform Limitation / Error Message
            if (state.statusMessage.isNotBlank()) {
                Text(
                    text = state.statusMessage,
                    color = if (state.stage == TechnologyInstallStage.ERROR) com.example.ui.theme.Error else com.example.ui.theme.TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
            if (isPythonWorker) {
                Text(
                    text = "Android Note: ${spec.androidLimitationNote}",
                    color = Color(0xFFFBBF24),
                    fontSize = 10.5.sp,
                    lineHeight = 14.5.sp
                )
            }

            // Row 7: Primary Download / Pause / Resume / Retry / Cancel / Open / Remove Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    state.stage == TechnologyInstallStage.DOWNLOADING ||
                        state.stage == TechnologyInstallStage.RESOLVING_REGISTRY ||
                        state.stage == TechnologyInstallStage.QUEUED -> {
                        OutlinedButton(
                            onClick = onPause,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFFF59E0B)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("tech_pause_btn_${spec.id.wireId}")
                        ) {
                            Icon(Icons.Default.Pause, contentDescription = "Pause Download", tint = Color(0xFFF59E0B), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Pause", color = Color(0xFFF59E0B), fontSize = 11.5.sp)
                        }
                        OutlinedButton(
                            onClick = onCancel,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, com.example.ui.theme.Error),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("tech_cancel_btn_${spec.id.wireId}")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel Download", tint = com.example.ui.theme.Error, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Cancel", color = com.example.ui.theme.Error, fontSize = 11.5.sp)
                        }
                    }

                    state.stage == TechnologyInstallStage.PAUSED -> {
                        Button(
                            onClick = onDownloadOrResume,
                            colors = ButtonDefaults.buttonColors(containerColor = com.example.ui.theme.Primary),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("tech_resume_btn_${spec.id.wireId}")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Resume Download", tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Resume", color = Color.White, fontSize = 11.5.sp)
                        }
                        OutlinedButton(
                            onClick = onCancel,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, com.example.ui.theme.Error),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(0.9f)
                                .heightIn(min = 48.dp)
                                .testTag("tech_cancel_btn_${spec.id.wireId}")
                        ) {
                            Text("Cancel", color = com.example.ui.theme.Error, fontSize = 11.5.sp)
                        }
                    }

                    state.stage == TechnologyInstallStage.ERROR -> {
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(containerColor = com.example.ui.theme.Error),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("tech_retry_btn_${spec.id.wireId}")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Retry Download", tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Retry", color = Color.White, fontSize = 11.5.sp)
                        }
                    }

                    !state.integrityVerified -> {
                        Button(
                            onClick = onDownloadOrResume,
                            colors = ButtonDefaults.buttonColors(containerColor = com.example.ui.theme.Primary),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1.1f)
                                .heightIn(min = 48.dp)
                                .testTag("tech_download_btn_${spec.id.wireId}")
                        ) {
                            Icon(Icons.Default.Download, contentDescription = "Download ${spec.displayName}", tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = when (spec.downloadKind) {
                                    VisionDownloadKind.PYTHON_MODEL_WEIGHTS_AND_WORKER -> "Download Weights"
                                    VisionDownloadKind.PYTHON_RENDER_PACKAGE_AND_WORKER -> "Download Wheel"
                                    VisionDownloadKind.NPM_WEBVIEW_PACKAGE -> "Download / Install"
                                },
                                color = Color.White,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                    }

                    else -> {
                        OutlinedButton(
                            onClick = onVerify,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.65f)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(0.9f)
                                .heightIn(min = 48.dp)
                                .testTag("tech_verify_btn_${spec.id.wireId}")
                        ) {
                            Icon(Icons.Default.Verified, contentDescription = "Verify ${spec.displayName}", tint = accentColor, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Verify", color = com.example.ui.theme.TextPrimary, fontSize = 11.sp)
                        }
                    }
                }

                // Configure Worker button for TripoSR / Manim Community / Cesium Ion
                if (isPythonWorker || spec.id == VisionTechId.CESIUM_JS) {
                    OutlinedButton(
                        onClick = onConfigure,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, com.example.ui.theme.GlassBorder),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("tech_setup_btn_${spec.id.wireId}")
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = "Configure ${spec.displayName}", tint = accentColor, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (isPythonWorker) "Worker" else "Token", color = com.example.ui.theme.TextPrimary, fontSize = 11.sp)
                    }
                }

                // Open / Use Button (integrated directly into Studio / Worker stage)
                Button(
                    onClick = onOpenAndUse,
                    enabled = canOpen,
                    colors = ButtonDefaults.buttonColors(containerColor = com.example.ui.theme.SuccessGreen),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(0.95f)
                        .heightIn(min = 48.dp)
                        .testTag("tech_open_btn_${spec.id.wireId}")
                ) {
                    Icon(Icons.Default.PlayCircle, contentDescription = "Open and Use ${spec.displayName}", tint = Color.Black, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Open / Use", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
                }

                // Safe Removal icon button when downloaded assets exist on disk
                if (state.hasDownloadedAssets) {
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("tech_remove_btn_${spec.id.wireId}")
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Remove downloaded assets for ${spec.displayName}",
                            tint = com.example.ui.theme.Error
                        )
                    }
                }
            }
        }
    }
}
