package com.example.vision

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Isolated Python Worker Panels for:
 * 1. Manim Community Edition (script editor + validation + async worker render + ExoPlayer MP4 playback)
 * 2. TripoSR (zero-permission Photo Picker + upload + GLB download + Model Viewer handoff)
 */
@Composable
fun ManimWorkerStage(
    artifact: VisualizationArtifact,
    accentColor: Color,
    onOpenWorkerSettings: () -> Unit,
    onArtifactUpdated: (VisualizationArtifact) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val parsed = remember(artifact.scenePayloadJson) {
        runCatching { JSONObject(artifact.scenePayloadJson) }.getOrElse { JSONObject() }
    }
    var scriptText by remember(artifact.artifactId, artifact.scenePayloadJson) {
        mutableStateOf(parsed.optString("script"))
    }
    var qualityFlag by remember(artifact.artifactId) {
        mutableStateOf(parsed.optString("quality", "m").ifBlank { "m" })
    }
    val validation = remember(scriptText) { TripoSrAndManimWorkers.validateManimScript(scriptText) }

    var renderJob by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var errorLogs by remember { mutableStateOf<String?>(null) }
    var renderedMp4Path by remember(artifact.artifactId, artifact.mediaFilePath) {
        mutableStateOf(artifact.mediaFilePath?.takeIf { File(it).isFile && File(it).length() > 64L })
    }

    val endpointConfigured = VisionPackageManager.getManimEndpoint(context).isNotBlank()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070B12))
            .padding(12.dp)
            .testTag("manim_worker_stage"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Rendered Video Player (when worker returned an MP4)
        if (renderedMp4Path != null) {
            ManimVideoPlayerView(
                videoFile = File(renderedMp4Path!!),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        } else {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF0E1522),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, (if (endpointConfigured) Color(0xFF10B981) else Color(0xFFF59E0B)).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(12.dp)
                ) {
                    Icon(
                        imageVector = if (endpointConfigured) Icons.Default.CloudDone else Icons.Default.CloudOff,
                        contentDescription = "Worker Status",
                        tint = if (endpointConfigured) Color(0xFF10B981) else Color(0xFFF59E0B)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (endpointConfigured) "Manim Python Worker Configured" else "Python Render Worker Required",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (endpointConfigured) {
                                "Tap Render MP4 to compile this validated scene on your worker."
                            } else {
                                "Android devices cannot bundle LaTeX/FFmpeg/Cairo inside the APK. Configure your Manim worker URL to render MP4."
                            },
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 11.sp
                        )
                    }
                    TextButton(onClick = onOpenWorkerSettings) {
                        Text("Configure", color = accentColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Script Editor + Validation Badge
        OutlinedTextField(
            value = scriptText,
            onValueChange = { scriptText = it },
            label = {
                Text(
                    text = if (validation.isValid) {
                        "Manim Scene Script ✓ (${validation.sceneClassName ?: "Scene"})"
                    } else {
                        "Manim Script Error: ${validation.issues.firstOrNull()}"
                    },
                    fontSize = 11.sp,
                    color = if (validation.isValid) Color(0xFF10B981) else Color(0xFFFF5252)
                )
            },
            textStyle = LocalTextStyle.current.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color(0xFFE2E8F0)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accentColor,
                unfocusedBorderColor = Color.White.copy(alpha = 0.18f)
            )
        )

        if (renderJob?.isActive == true) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                color = accentColor,
                modifier = Modifier.fillMaxWidth()
            )
        }
        statusMessage?.let { msg ->
            Text(text = msg, color = Color(0xFF7DD3FC), fontSize = 11.sp)
        }
        errorLogs?.let { logs ->
            Text(
                text = logs,
                color = Color(0xFFFF8A80),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 4
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            listOf("l" to "480p", "m" to "720p", "h" to "1080p").forEach { (flag, label) ->
                FilterChip(
                    selected = qualityFlag == flag,
                    onClick = { qualityFlag = flag },
                    label = { Text(label, fontSize = 10.5.sp) }
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            if (renderJob?.isActive == true) {
                OutlinedButton(onClick = {
                    renderJob?.cancel()
                    renderJob = null
                    statusMessage = "Manim render cancelled."
                }) {
                    Text("Cancel", color = Color(0xFFFF5252), fontSize = 11.sp)
                }
            } else {
                Button(
                    enabled = validation.isValid,
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    onClick = {
                        val updatedObj = runCatching { JSONObject(artifact.scenePayloadJson) }.getOrElse { JSONObject() }.apply {
                            put("script", scriptText)
                            put("sceneClass", validation.sceneClassName ?: "MainScene")
                            put("quality", qualityFlag)
                        }
                        val savedDraft = VisualizationStorageRepository.saveOrUpdate(
                            context = context,
                            artifact = artifact.copy(scenePayloadJson = updatedObj.toString()),
                            pushUndoState = true,
                            undoLabel = "Edit Manim Script"
                        )
                        onArtifactUpdated(savedDraft)

                        if (!endpointConfigured) {
                            onOpenWorkerSettings()
                            return@Button
                        }

                        errorLogs = null
                        progress = 0.08f
                        renderJob = scope.launch {
                            val outMp4 = File(context.filesDir, "visualizations/manim_${artifact.artifactId}.mp4")
                            val res = TripoSrAndManimWorkers.runManimRenderJob(
                                context = context,
                                script = scriptText,
                                sceneClass = validation.sceneClassName ?: "MainScene",
                                qualityFlag = qualityFlag,
                                outputMp4File = outMp4,
                                onProgress = { p, msg ->
                                    progress = p
                                    statusMessage = msg
                                }
                            )
                            statusMessage = res.detailMessage
                            if (res.serverLogs.isNotBlank() && !res.success) {
                                errorLogs = res.serverLogs
                            }
                            if (res.success && res.outputFile != null) {
                                renderedMp4Path = res.outputFile.absolutePath
                                val finalArtifact = VisualizationStorageRepository.saveOrUpdate(
                                    context = context,
                                    artifact = savedDraft.copy(
                                        mediaFilePath = res.outputFile.absolutePath,
                                        exportedPaths = savedDraft.exportedPaths + (VisualizationExportFormat.MP4.name to res.outputFile.absolutePath)
                                    )
                                )
                                onArtifactUpdated(finalArtifact)
                            }
                        }
                    }
                ) {
                    Icon(Icons.Default.Movie, contentDescription = "Render MP4", tint = Color.Black, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Render MP4", color = Color.Black, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun ManimVideoPlayerView(
    videoFile: File,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val player = remember(videoFile.absolutePath) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
            }
        },
        modifier = modifier
    )
}

@Composable
fun TripoSrWorkerStage(
    artifact: VisualizationArtifact,
    accentColor: Color,
    onOpenWorkerSettings: () -> Unit,
    onPreviewGeneratedGlb: (File, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedImageFile by remember { mutableStateOf<File?>(null) }
    var selectedImageName by remember { mutableStateOf<String?>(null) }
    var resolution by remember { mutableIntStateOf(256) }
    var removeBg by remember { mutableStateOf(true) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var statusText by remember { mutableStateOf<String?>(null) }

    val endpointConfigured = VisionPackageManager.getTripoSrEndpoint(context).isNotBlank()

    // Google Play Policy Compliant: Android Photo Picker (zero storage permission needed)
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            var name = "object_photo.jpg"
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = c.getString(idx)
                }
            }
            val dst = File(context.cacheDir, "triposr_input_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dst).use { out -> input.copyTo(out) }
            }
            if (dst.isFile && dst.length() > 0L) {
                selectedImageFile = dst
                selectedImageName = name
                statusText = "Ready: $name (${dst.length() / 1024} KB)"
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070B12))
            .padding(16.dp)
            .testTag("triposr_worker_stage"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF0E1522),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, (if (endpointConfigured) Color(0xFF10B981) else Color(0xFFF59E0B)).copy(alpha = 0.55f), RoundedCornerShape(14.dp))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = if (endpointConfigured) Icons.Default.CloudDone else Icons.Default.CloudOff,
                        contentDescription = "TripoSR Worker",
                        tint = if (endpointConfigured) Color(0xFF10B981) else Color(0xFFF59E0B)
                    )
                    Text(
                        text = if (endpointConfigured) "TripoSR GPU Worker Connected" else "TripoSR GPU Worker Required",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onOpenWorkerSettings) {
                        Text("Setup Worker", color = accentColor, fontSize = 11.5.sp)
                    }
                }
                Text(
                    text = "Select a single object photo using Android Photo Picker. Kavya uploads it to your configured TripoSR Python worker, downloads the .glb mesh, and opens it directly in Model Viewer / Three.js.",
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 11.5.sp
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            ) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = "Pick Photo", tint = accentColor)
                Spacer(Modifier.width(8.dp))
                Text(selectedImageName ?: "Pick Object Photo", color = Color.White, fontSize = 12.sp)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Mesh Res:", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
            listOf(128, 256).forEach { r ->
                FilterChip(selected = resolution == r, onClick = { resolution = r }, label = { Text("${r}px") })
            }
            Spacer(Modifier.weight(1f))
            FilterChip(selected = removeBg, onClick = { removeBg = !removeBg }, label = { Text("Remove BG") })
        }

        if (activeJob?.isActive == true) {
            LinearProgressIndicator(progress = { progress }, color = accentColor, modifier = Modifier.fillMaxWidth())
        }
        statusText?.let { Text(it, color = Color(0xFF7DD3FC), fontSize = 11.5.sp) }

        Spacer(modifier = Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            if (activeJob?.isActive == true) {
                OutlinedButton(
                    onClick = {
                        activeJob?.cancel()
                        activeJob = null
                        statusText = "TripoSR upload cancelled."
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text("Cancel Reconstruction", color = Color(0xFFFF5252))
                }
            } else {
                Button(
                    enabled = selectedImageFile != null,
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    onClick = {
                        val img = selectedImageFile ?: return@Button
                        if (!endpointConfigured) {
                            onOpenWorkerSettings()
                            return@Button
                        }
                        progress = 0.1f
                        activeJob = scope.launch {
                            val glbFile = File(context.filesDir, "visualizations/triposr_${System.currentTimeMillis()}.glb")
                            val res = TripoSrAndManimWorkers.runTripoSrReconstruction(
                                context = context,
                                imageFile = img,
                                outputGlbFile = glbFile,
                                mcResolution = resolution,
                                removeBackground = removeBg,
                                onProgress = { p, msg ->
                                    progress = p
                                    statusText = msg
                                }
                            )
                            statusText = res.detailMessage
                            if (res.success && res.outputFile != null) {
                                onPreviewGeneratedGlb(res.outputFile, selectedImageName ?: "TripoSR Mesh")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.ViewInAr, contentDescription = "Reconstruct 3D GLB", tint = Color.Black)
                    Spacer(Modifier.width(8.dp))
                    Text("Reconstruct 3D Mesh (.GLB)", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
