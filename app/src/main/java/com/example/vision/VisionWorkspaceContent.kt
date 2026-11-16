package com.example.vision

import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
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
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisionWorkspaceContent(
    accentColor: Color,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val diagnosticsMap by VisionPackageManager.diagnostics.collectAsState()
    val savedArtifacts by VisualizationStorageRepository.artifacts.collectAsState()
    val activeArtifactState by VisualizationOrchestrator.activeArtifact.collectAsState()
    val activeJob by VisualizationOrchestrator.activeJobState.collectAsState()
    val qualityPreset by VisualizationOrchestrator.qualityPreset.collectAsState()
    val isFullScreenOpen by VisualizationOrchestrator.isFullScreenPreviewOpen.collectAsState()

    var selectedCategory by remember { mutableStateOf<VisionCategory?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedTechId by remember(activeArtifactState?.techIdWire) {
        mutableStateOf(activeArtifactState?.techId ?: VisionTechId.THREE_JS)
    }
    var promptInput by remember { mutableStateOf("") }
    var telemetryText by remember { mutableStateOf<String?>(null) }
    var lastExportedFile by remember { mutableStateOf<Pair<File, VisualizationExportFormat>?>(null) }

    var showInfoDialogFor by remember { mutableStateOf<VisionTechnologySpec?>(null) }
    var showTemplatesSheet by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }

    val webController = remember { VisionWebViewController() }

    LaunchedEffect(context) {
        VisionPackageManager.refreshAllDiagnostics(context)
        val loaded = VisualizationStorageRepository.loadAll(context)
        if (VisualizationOrchestrator.activeArtifact.value == null) {
            val firstTemplate = VisionTechRegistry.defaultTemplates().firstOrNull { it.techId == selectedTechId }
                ?: VisionTechRegistry.defaultTemplates().first()
            if (loaded.isNotEmpty()) {
                VisualizationOrchestrator.selectArtifact(loaded.first())
            } else {
                VisualizationOrchestrator.openTemplate(context, firstTemplate, VisualizationEntrySource.HAMBURGER_VISION)
            }
        }
    }

    val activeArtifact = activeArtifactState ?: remember(selectedTechId) {
        val tpl = VisionTechRegistry.defaultTemplates().first { it.techId == selectedTechId }
        VisualizationArtifact(
            techIdWire = tpl.techId.wireId,
            title = tpl.title,
            userPrompt = tpl.promptHint,
            summaryText = tpl.description,
            scenePayloadJson = tpl.payloadJson,
            sourceWire = VisualizationEntrySource.TEMPLATE.name
        )
    }

    val activeSpec = remember(activeArtifact.techId) { VisionTechRegistry.getSpec(activeArtifact.techId) }
    val cesiumToken = remember(context, showInfoDialogFor) { VisionPackageManager.getCesiumIonToken(context) }
    val arSupported = remember(diagnosticsMap) { diagnosticsMap[VisionTechId.MODEL_VIEWER]?.arSupported ?: false }

    // Safe Document Picker for .glb / .splat / .ply / .excalidraw / .mmd / .csv / .py / .json
    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                var fileName = "imported_file"
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) fileName = c.getString(idx)
                    }
                }
                val lower = fileName.lowercase()
                val isBinary = lower.endsWith(".glb") || lower.endsWith(".gltf") || lower.endsWith(".splat") || lower.endsWith(".ply")
                if (isBinary) {
                    val outDir = File(context.filesDir, "visualizations").apply { mkdirs() }
                    val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val dst = File(outDir, safeName)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(dst).use { out -> input.copyTo(out) }
                    }
                    val imported = VisualizationOrchestrator.importExternalFileContent(context, fileName, null, dst)
                    selectedTechId = imported.techId
                    telemetryText = "Imported binary asset: $fileName (${dst.length() / 1024} KB)"
                } else {
                    val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    val imported = VisualizationOrchestrator.importExternalFileContent(context, fileName, text, null)
                    selectedTechId = imported.techId
                    telemetryText = "Imported dataset/source: $fileName"
                }
            }.onFailure { err ->
                Toast.makeText(context, "Import failed: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val filteredTechs = remember(selectedCategory, searchQuery) {
        val base = VisionTechRegistry.byCategory(selectedCategory)
        if (searchQuery.isBlank()) base
        else base.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
                it.shortTagline.contains(searchQuery, ignoreCase = true) ||
                it.category.displayName.contains(searchQuery, ignoreCase = true)
        }
    }

    val handleJsEvent: (VisionJsEvent) -> Unit = remember(activeArtifact.artifactId) {
        { event ->
            when (event) {
                is VisionJsEvent.EngineReady -> telemetryText = "Engine Ready: ${event.rendererInfo}"
                is VisionJsEvent.RenderComplete -> telemetryText = "Rendered (${event.metricsJson.take(64)})"
                is VisionJsEvent.RenderError -> telemetryText = "Render Error: ${event.message}"
                is VisionJsEvent.StateMutated -> {
                    VisualizationOrchestrator.applyFollowUpMutationFromUi(context, event.payloadJson, event.label)
                    telemetryText = "Saved change: ${event.label}"
                }
                is VisionJsEvent.ExportReady -> {
                    val file = VisualizationStorageRepository.saveExportFile(
                        context = context,
                        artifactId = activeArtifact.artifactId,
                        format = event.format,
                        content = event.contentBase64OrText,
                        isBase64 = event.isBase64
                    )
                    lastExportedFile = file to event.format
                    telemetryText = "Exported ${event.format.label} (${file.length() / 1024} KB)"
                }
                else -> {}
            }
        }
    }

    if (showInfoDialogFor != null) {
        VisionTechInfoAndSetupDialog(
            spec = showInfoDialogFor!!,
            diagnostics = diagnosticsMap[showInfoDialogFor!!.id],
            accentColor = accentColor,
            onDismiss = { showInfoDialogFor = null },
            onDiagnosticsUpdated = { VisionPackageManager.refreshAllDiagnostics(context) }
        )
    }

    if (showTemplatesSheet) {
        ModalBottomSheet(
            onDismissRequest = { showTemplatesSheet = false },
            containerColor = Color(0xFF0B1120)
        ) {
            VisionTemplatesAndSavedSheet(
                savedArtifacts = savedArtifacts,
                accentColor = accentColor,
                onSelectTemplate = { tpl ->
                    val art = VisualizationOrchestrator.openTemplate(context, tpl)
                    selectedTechId = art.techId
                },
                onOpenArtifact = { art ->
                    VisualizationOrchestrator.selectArtifact(art)
                    selectedTechId = art.techId
                },
                onToggleFavorite = { VisualizationStorageRepository.toggleFavorite(context, it.artifactId) },
                onRenameArtifact = { art, title -> VisualizationStorageRepository.renameArtifact(context, art.artifactId, title) },
                onDuplicateArtifact = { art ->
                    VisualizationStorageRepository.duplicateArtifact(context, art.artifactId)?.let {
                        VisualizationOrchestrator.selectArtifact(it)
                    }
                },
                onDeleteArtifact = { VisualizationStorageRepository.deleteArtifact(context, it.artifactId) },
                onDismiss = { showTemplatesSheet = false }
            )
        }
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Export & Share (${activeSpec.displayName})") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Choose an export format supported by ${activeSpec.displayName}. Exports are saved locally and shareable via Android Share Sheet.",
                        fontSize = 12.sp
                    )
                    activeSpec.supportedExportFormats.forEach { fmt ->
                        OutlinedButton(
                            onClick = {
                                when {
                                    fmt == VisualizationExportFormat.JSON -> {
                                        val file = VisualizationStorageRepository.saveExportFile(
                                            context, activeArtifact.artifactId, fmt, activeArtifact.scenePayloadJson, false
                                        )
                                        lastExportedFile = file to fmt
                                    }
                                    fmt == VisualizationExportFormat.PYTHON_PY -> {
                                        val py = runCatching { org.json.JSONObject(activeArtifact.scenePayloadJson).optString("script") }.getOrDefault("")
                                        val file = VisualizationStorageRepository.saveExportFile(
                                            context, activeArtifact.artifactId, fmt, py, false
                                        )
                                        lastExportedFile = file to fmt
                                    }
                                    fmt == VisualizationExportFormat.MP4 -> {
                                        val mp4Path = activeArtifact.mediaFilePath
                                        if (mp4Path != null && File(mp4Path).isFile) {
                                            lastExportedFile = File(mp4Path) to VisualizationExportFormat.MP4
                                        } else {
                                            Toast.makeText(context, "Render video via Manim Worker first", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    else -> {
                                        webController.requestExport(fmt)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = fmt.label, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Export ${fmt.label} (.${fmt.extension})")
                        }
                    }

                    lastExportedFile?.let { (file, fmt) ->
                        HorizontalDivider()
                        Text("Ready: ${file.name} (${file.length() / 1024} KB)", color = Color(0xFF10B981), fontSize = 11.5.sp)
                        Button(
                            onClick = {
                                val intent = VisualizationStorageRepository.buildShareIntent(context, file, fmt.mimeType, activeArtifact.title)
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share File", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Share via Android Sheet")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("Done") }
            }
        )
    }

    if (isFullScreenOpen && activeArtifact.techId !in listOf(VisionTechId.MANIM, VisionTechId.TRIPO_SR)) {
        VisionFullscreenDialog(
            artifact = activeArtifact,
            quality = qualityPreset,
            reducedMotion = reducedMotion,
            cesiumIonToken = cesiumToken,
            arSupported = arSupported,
            controller = webController,
            onEvent = handleJsEvent,
            onClose = { VisualizationOrchestrator.setFullScreenPreviewOpen(false) }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
            .testTag("vision_studio_root"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Search + Category Filters + Vault Button
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search 16 engines (Three.js, Mermaid, Manim...)", fontSize = 11.5.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("vision_engine_search"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                FilledTonalIconButton(
                    onClick = { showTemplatesSheet = true },
                    modifier = Modifier.size(48.dp).testTag("vision_templates_vault_button")
                ) {
                    Icon(Icons.Default.Bookmarks, contentDescription = "Templates and Saved Vault", tint = accentColor)
                }
            }

            Spacer(Modifier.height(8.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null },
                        label = { Text("All (16)", fontSize = 11.sp) }
                    )
                }
                items(VisionCategory.entries) { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = if (selectedCategory == cat) null else cat },
                        label = { Text(cat.displayName, fontSize = 11.sp) }
                    )
                }
            }
        }

        // 2. 16 Technologies Selector Strip
        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag("vision_tech_strip")
            ) {
                items(filteredTechs, key = { it.id.wireId }) { spec ->
                    val isSelected = activeArtifact.techId == spec.id
                    val diag = diagnosticsMap[spec.id]
                    val statusColor = when (diag?.availabilityStatus) {
                        VisionAvailabilityStatus.INSTALLED_VERIFIED,
                        VisionAvailabilityStatus.BACKEND_READY -> Color(0xFF10B981)
                        VisionAvailabilityStatus.BACKEND_REQUIRED -> Color(0xFFF59E0B)
                        else -> Color(0xFF64748B)
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) accentColor.copy(alpha = 0.18f) else Color(0xFF101724),
                        modifier = Modifier
                            .widthIn(min = 132.dp)
                            .border(
                                width = if (isSelected) 1.5.dp else 0.8.dp,
                                color = if (isSelected) accentColor else Color.White.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                selectedTechId = spec.id
                                val existing = savedArtifacts.firstOrNull { it.techId == spec.id }
                                if (existing != null) {
                                    VisualizationOrchestrator.selectArtifact(existing)
                                } else {
                                    val tpl = VisionTechRegistry.defaultTemplates().first { it.techId == spec.id }
                                    VisualizationOrchestrator.openTemplate(context, tpl, VisualizationEntrySource.HAMBURGER_VISION)
                                }
                            }
                            .testTag("vision_tech_card_${spec.id.wireId}")
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(7.dp).clip(CircleShape).background(statusColor))
                                Text(
                                    text = spec.displayName,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                            Text(
                                text = "${spec.category.badgeLabel} • ${diag?.availabilityStatus?.label ?: spec.licenseName}",
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 9.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // 3. Prompt / AI Generation Bar + File Import
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF101724)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = promptInput,
                            onValueChange = { promptInput = it },
                            placeholder = { Text("Ask ${activeSpec.displayName} to draw, plot, animate, or edit...", fontSize = 11.5.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("vision_prompt_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = accentColor,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                        Button(
                            onClick = {
                                val q = promptInput.ifBlank { activeArtifact.userPrompt.ifBlank { activeSpec.shortTagline } }
                                VisualizationOrchestrator.generateVisualizationAsync(
                                    context = context,
                                    prompt = q,
                                    preferredTechId = activeArtifact.techId,
                                    source = VisualizationEntrySource.HAMBURGER_VISION
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                            modifier = Modifier.heightIn(min = 48.dp).testTag("vision_generate_button")
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "Generate", tint = Color.Black, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Render", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = { importFileLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.heightIn(min = 40.dp)
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = "Import File", tint = accentColor, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Import", color = Color.White, fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = { showInfoDialogFor = activeSpec },
                            modifier = Modifier.heightIn(min = 40.dp).testTag("vision_tech_info_button")
                        ) {
                            Icon(Icons.Default.Info, contentDescription = "Tech Info & Worker Setup", tint = accentColor, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Docs & Setup", color = Color.White, fontSize = 11.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        if (activeSpec.requiresWebGl) {
                            FilterChip(
                                selected = false,
                                onClick = {
                                    val next = QualityPreset.entries[(qualityPreset.ordinal + 1) % QualityPreset.entries.size]
                                    VisualizationOrchestrator.setQualityPreset(next)
                                },
                                label = { Text("Q: ${qualityPreset.name}", fontSize = 10.sp) }
                            )
                        }
                    }

                    val jobStatus = activeJob
                    if (jobStatus != null && jobStatus.status != VisualizationJobStatus.READY && jobStatus.status != VisualizationJobStatus.CANCELLED) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LinearProgressIndicator(
                                progress = { jobStatus.progress },
                                color = accentColor,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { VisualizationOrchestrator.cancelActiveJob() }) {
                                Text("Cancel", color = Color(0xFFFF5252), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        // 4. Live Interactive Canvas Stage + Toolbar
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0F1A)),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.2.dp, accentColor.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Header bar with Title + Undo/Redo + Fullscreen + Export/Share
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0E1522))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activeArtifact.title,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = telemetryText ?: activeArtifact.summaryText,
                                color = Color(0xFF7DD3FC),
                                fontSize = 10.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            enabled = activeArtifact.canUndo,
                            onClick = { VisualizationOrchestrator.undoActive(context) }
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Undo,
                                contentDescription = "Undo",
                                tint = if (activeArtifact.canUndo) Color.White else Color.White.copy(alpha = 0.25f)
                            )
                        }
                        IconButton(
                            enabled = activeArtifact.canRedo,
                            onClick = { VisualizationOrchestrator.redoActive(context) }
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Redo,
                                contentDescription = "Redo",
                                tint = if (activeArtifact.canRedo) Color.White else Color.White.copy(alpha = 0.25f)
                            )
                        }
                        if (activeSpec.id !in listOf(VisionTechId.MANIM, VisionTechId.TRIPO_SR)) {
                            IconButton(onClick = { VisualizationOrchestrator.setFullScreenPreviewOpen(true) }) {
                                Icon(Icons.Default.Fullscreen, contentDescription = "Fullscreen Preview", tint = accentColor)
                            }
                        }
                        IconButton(onClick = { showExportDialog = true }) {
                            Icon(Icons.Default.IosShare, contentDescription = "Export and Share", tint = accentColor)
                        }
                    }

                    // Active Engine Stage
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(380.dp)
                    ) {
                        when (activeArtifact.techId) {
                            VisionTechId.MANIM -> ManimWorkerStage(
                                artifact = activeArtifact,
                                accentColor = accentColor,
                                onOpenWorkerSettings = { showInfoDialogFor = activeSpec },
                                onArtifactUpdated = { VisualizationOrchestrator.selectArtifact(it) }
                            )
                            VisionTechId.TRIPO_SR -> TripoSrWorkerStage(
                                artifact = activeArtifact,
                                accentColor = accentColor,
                                onOpenWorkerSettings = { showInfoDialogFor = activeSpec },
                                onPreviewGeneratedGlb = { glbFile, name ->
                                    val imported = VisualizationOrchestrator.importExternalFileContent(
                                        context = context,
                                        fileName = glbFile.name,
                                        rawText = null,
                                        savedBinaryFile = glbFile
                                    )
                                    selectedTechId = imported.techId
                                    telemetryText = "Loaded reconstructed GLB for $name"
                                }
                            )
                            else -> VisionWebViewHost(
                                artifact = activeArtifact,
                                quality = qualityPreset,
                                reducedMotion = reducedMotion,
                                cesiumIonToken = cesiumToken,
                                arSupported = arSupported,
                                controller = webController,
                                onEvent = handleJsEvent,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
