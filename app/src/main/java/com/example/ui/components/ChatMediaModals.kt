package com.example.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.pdf.PdfDocument
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.agent.MicrophoneEngine
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Phase 7.B: Live Camera Vision Modal.
 * Provides real Camera2 TextureView live preview, front/back lens switching,
 * explicit privacy consent indicator, and on-demand frame capture & multimodal analysis.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveCameraVisionModal(
    onDismiss: () -> Unit,
    onAnalyzeFrame: (Bitmap, String) -> Unit
) {
    val context = LocalContext.current
    var useFrontCamera by remember { mutableStateOf(false) }
    var promptText by remember { mutableStateOf("Camera mein kya dikh raha hai? Explain what is visible.") }
    var statusText by remember { mutableStateOf("Initializing live camera preview…") }
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }

    val fallbackCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            onAnalyzeFrame(bitmap, promptText.ifBlank { "Camera mein kya dikh raha hai?" })
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        contentColor = TextPrimary,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(SuccessGreen)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Live Camera Vision",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                }
                Row {
                    IconButton(onClick = { useFrontCamera = !useFrontCamera }) {
                        Icon(Icons.Default.Cameraswitch, contentDescription = "Switch Camera", tint = PrimaryLight)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close Live Camera", tint = TextSecondary)
                    }
                }
            }

            Text(
                text = "Privacy Guarantee: Camera frames are only sent when you tap 'Analyze Frame'. Closing this sheet stops the camera immediately.",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = TextSecondary
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black)
                    .border(1.dp, GlassBorder, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    Camera2PreviewSurface(
                        useFrontCamera = useFrontCamera,
                        onTextureViewReady = { tv -> textureViewRef = tv },
                        onStatusChanged = { statusText = it }
                    )
                } else {
                    Text("Camera permission is required for live preview.", color = Color.White, fontSize = 12.sp)
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(statusText, color = Color.White, fontSize = 10.sp)
                }
            }

            OutlinedTextField(
                value = promptText,
                onValueChange = { promptText = it },
                label = { Text("Ask about camera view (Hindi / English / Hinglish)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        val captured = textureViewRef?.bitmap
                        if (captured != null && captured.width > 0 && captured.height > 0) {
                            onAnalyzeFrame(
                                captured,
                                promptText.ifBlank { "Camera mein kya dikh raha hai? Describe everything visible." }
                            )
                            onDismiss()
                        } else {
                            fallbackCaptureLauncher.launch(null)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Analyze Frame", color = Color.White, fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(0.5f)
                ) {
                    Text("Stop", color = TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun Camera2PreviewSurface(
    useFrontCamera: Boolean,
    onTextureViewReady: (TextureView) -> Unit,
    onStatusChanged: (String) -> Unit
) {
    val context = LocalContext.current
    var currentTextureView by remember { mutableStateOf<TextureView?>(null) }

    DisposableEffect(useFrontCamera, currentTextureView) {
        val textureView = currentTextureView ?: return@DisposableEffect onDispose {}
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        val handlerThread = HandlerThread("KavyaLiveCameraThread").apply { start() }
        val backgroundHandler = Handler(handlerThread.looper)

        var cameraDevice: CameraDevice? = null
        var captureSession: CameraCaptureSession? = null

        fun openSelectedCamera() {
            try {
                if (cameraManager == null) {
                    onStatusChanged("Camera service unavailable")
                    return
                }
                val targetFacing = if (useFrontCamera) {
                    CameraCharacteristics.LENS_FACING_FRONT
                } else {
                    CameraCharacteristics.LENS_FACING_BACK
                }
                val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                    val chars = cameraManager.getCameraCharacteristics(id)
                    chars.get(CameraCharacteristics.LENS_FACING) == targetFacing
                } ?: cameraManager.cameraIdList.firstOrNull()

                if (cameraId == null) {
                    onStatusChanged("No hardware camera detected — tap Analyze Frame for fallback capture")
                    return
                }

                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        try {
                            val surfaceTexture = textureView.surfaceTexture ?: return
                            surfaceTexture.setDefaultBufferSize(1280, 720)
                            val surface = Surface(surfaceTexture)
                            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(surface)
                            }
                            @Suppress("DEPRECATION")
                            camera.createCaptureSession(
                                listOf(surface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        captureSession = session
                                        try {
                                            session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
                                            onStatusChanged(if (useFrontCamera) "Live Preview: Front Camera" else "Live Preview: Rear Camera")
                                        } catch (e: Exception) {
                                            onStatusChanged("Preview error: ${e.localizedMessage}")
                                        }
                                    }

                                    override fun onConfigureFailed(session: CameraCaptureSession) {
                                        onStatusChanged("Camera session config failed")
                                    }
                                },
                                backgroundHandler
                            )
                        } catch (e: Exception) {
                            onStatusChanged("Camera preview setup error: ${e.localizedMessage}")
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        cameraDevice = null
                        onStatusChanged("Camera disconnected")
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        cameraDevice = null
                        onStatusChanged("Camera error ($error) — tap Analyze Frame for fallback")
                    }
                }, backgroundHandler)
            } catch (e: Exception) {
                onStatusChanged("Camera unavailable: ${e.localizedMessage}")
            }
        }

        if (textureView.isAvailable) {
            openSelectedCamera()
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    openSelectedCamera()
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }

        onDispose {
            try {
                captureSession?.close()
            } catch (_: Exception) {}
            try {
                cameraDevice?.close()
            } catch (_: Exception) {}
            try {
                handlerThread.quitSafely()
            } catch (_: Exception) {}
        }
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { tv ->
                currentTextureView = tv
                onTextureViewReady(tv)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

/**
 * Phase 7.C: Multi-Page Document Scanner Modal.
 * Supports multi-page capture, Gallery page import, 90° rotation, center auto-crop,
 * high-contrast document enhancement, page reordering/deletion, PDF export, and AI OCR/analysis.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiPageDocumentScannerModal(
    initialBitmap: Bitmap? = null,
    onDismiss: () -> Unit,
    onExportAndAnalyzePdf: (File, String) -> Unit
) {
    val context = LocalContext.current
    val pages = remember {
        mutableStateListOf<Bitmap>().apply {
            if (initialBitmap != null) add(initialBitmap)
        }
    }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var exportStatus by remember { mutableStateOf<String?>(null) }

    val pageCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bmp ->
        if (bmp != null) {
            pages.add(bmp)
            selectedIndex = pages.lastIndex
        }
    }

    val pageGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val decoded = android.graphics.BitmapFactory.decodeStream(input)
                    if (decoded != null) {
                        pages.add(decoded)
                        selectedIndex = pages.lastIndex
                    }
                }
            } catch (_: Exception) {}
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        contentColor = TextPrimary,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Document Scanner (${pages.size} Page${if (pages.size == 1) "" else "s"})",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close Scanner", tint = TextSecondary)
                }
            }

            if (pages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(SurfaceGlass)
                        .border(1.dp, GlassBorder, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.DocumentScanner, contentDescription = null, tint = PrimaryLight, modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No pages scanned yet. Add a page from Camera or Gallery.", color = TextSecondary, fontSize = 12.sp)
                    }
                }
            } else {
                val safeIdx = selectedIndex.coerceIn(0, pages.lastIndex)
                val activeBmp = pages[safeIdx]

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black)
                        .border(1.dp, GlassBorder, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = activeBmp.asImageBitmap(),
                        contentDescription = "Scanned page ${safeIdx + 1}",
                        modifier = Modifier.fillMaxSize().padding(6.dp)
                    )
                    Text(
                        text = "Page ${safeIdx + 1} of ${pages.size}",
                        color = Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                // Page Manipulation Toolbar: Rotate, Enhance Contrast, Crop Margins, Reorder, Delete
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    AssistChip(
                        onClick = { pages[safeIdx] = rotateBitmap(activeBmp, 90f) },
                        label = { Text("Rotate", fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.RotateRight, contentDescription = null, modifier = Modifier.size(15.dp)) }
                    )
                    AssistChip(
                        onClick = { pages[safeIdx] = enhanceDocumentContrast(activeBmp) },
                        label = { Text("Enhance", fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(15.dp)) }
                    )
                    AssistChip(
                        onClick = { pages[safeIdx] = cropDocumentMargins(activeBmp) },
                        label = { Text("Crop", fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(15.dp)) }
                    )
                    if (safeIdx > 0) {
                        IconButton(onClick = {
                            val item = pages.removeAt(safeIdx)
                            pages.add(safeIdx - 1, item)
                            selectedIndex = safeIdx - 1
                        }) {
                            Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Move page earlier", tint = TextPrimary)
                        }
                    }
                    if (safeIdx < pages.lastIndex) {
                        IconButton(onClick = {
                            val item = pages.removeAt(safeIdx)
                            pages.add(safeIdx + 1, item)
                            selectedIndex = safeIdx + 1
                        }) {
                            Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Move page later", tint = TextPrimary)
                        }
                    }
                    IconButton(onClick = {
                        pages.removeAt(safeIdx)
                        selectedIndex = (safeIdx - 1).coerceAtLeast(0)
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete page", tint = Error)
                    }
                }

                // Page Thumbnails Strip
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(pages) { index, bmp ->
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    width = if (index == safeIdx) 2.dp else 1.dp,
                                    color = if (index == safeIdx) Primary else GlassBorder,
                                    shape = RoundedCornerShape(8.dp)
                                )
                        ) {
                            IconButton(
                                onClick = { selectedIndex = index },
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Page ${index + 1}",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }

            // Add Page Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { pageCameraLauncher.launch(null) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.AddAPhoto, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Scan Page", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = {
                        pageGalleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Import Image", fontSize = 12.sp)
                }
            }

            if (exportStatus != null) {
                Text(exportStatus!!, color = SuccessGreenGlow, fontSize = 11.sp)
            }

            // Export PDF & Analyze Button
            Button(
                onClick = {
                    if (pages.isNotEmpty()) {
                        try {
                            val pdfDocument = PdfDocument()
                            pages.forEachIndexed { idx, bmp ->
                                val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, idx + 1).create()
                                val page = pdfDocument.startPage(pageInfo)
                                page.canvas.drawBitmap(bmp, 0f, 0f, null)
                                pdfDocument.finishPage(page)
                            }
                            val fileName = "scanned_doc_${System.currentTimeMillis()}.pdf"
                            val outFile = File(context.filesDir, fileName)
                            FileOutputStream(outFile).use { out ->
                                pdfDocument.writeTo(out)
                            }
                            pdfDocument.close()
                            exportStatus = "Saved $fileName (${pages.size} pages)"
                            onExportAndAnalyzePdf(outFile, fileName)
                            onDismiss()
                        } catch (e: Exception) {
                            exportStatus = "PDF export error: ${e.localizedMessage}"
                        }
                    }
                },
                enabled = pages.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Export PDF & Extract Text / Analyze", color = Color.White, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

private fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
    val matrix = Matrix().apply { postRotate(angle) }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

private fun enhanceDocumentContrast(source: Bitmap): Bitmap {
    val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    val contrast = 1.45f
    val translate = (-0.5f * contrast + 0.5f) * 255f
    val cm = ColorMatrix(
        floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        )
    )
    val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
    canvas.drawBitmap(source, 0f, 0f, paint)
    return result
}

private fun cropDocumentMargins(source: Bitmap): Bitmap {
    if (source.width < 20 || source.height < 20) return source
    val marginX = (source.width * 0.06f).toInt()
    val marginY = (source.height * 0.06f).toInt()
    val newW = (source.width - marginX * 2).coerceAtLeast(1)
    val newH = (source.height - marginY * 2).coerceAtLeast(1)
    return Bitmap.createBitmap(source, marginX, marginY, newW, newH)
}

/**
 * Phase 8: Voice-Message Recorder Modal for Chat.
 * Reuses the single shared [MicrophoneEngine] PCM subscriber pipeline (never creates a second AudioRecord),
 * writes a standard 16kHz 16-bit mono WAV file in private storage, and supports Record, Stop,
 * Preview Playback, Re-record, Delete, and Send to Chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceMessageRecorderModal(
    onDismiss: () -> Unit,
    onSendVoiceMessage: (File, Int, ByteArray) -> Unit
) {
    val context = LocalContext.current
    val subscriberId = "chat_voice_message_recorder"
    val pcmBuffer = remember { ByteArrayOutputStream() }

    var isRecording by remember { mutableStateOf(false) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    var recordedWavFile by remember { mutableStateOf<File?>(null) }
    var recordedPcmBytes by remember { mutableStateOf(ByteArray(0)) }
    var isPreviewPlaying by remember { mutableStateOf(false) }
    var mediaPlayerRef by remember { mutableStateOf<MediaPlayer?>(null) }

    fun stopPreviewPlayback() {
        try {
            mediaPlayerRef?.stop()
        } catch (_: Exception) {}
        try {
            mediaPlayerRef?.release()
        } catch (_: Exception) {}
        mediaPlayerRef = null
        isPreviewPlaying = false
    }

    fun startVoiceNoteRecording() {
        stopPreviewPlayback()
        pcmBuffer.reset()
        recordedWavFile?.delete()
        recordedWavFile = null
        recordedPcmBytes = ByteArray(0)
        elapsedSeconds = 0
        isRecording = true

        val micEngine = MicrophoneEngine.getInstance(context)
        micEngine.startListening()
        micEngine.registerFrameSubscriber(subscriberId) { frameBytes ->
            synchronized(pcmBuffer) {
                pcmBuffer.write(frameBytes)
            }
        }
    }

    fun finishVoiceNoteRecording() {
        if (!isRecording) return
        isRecording = false
        val micEngine = MicrophoneEngine.getInstance(context)
        micEngine.unregisterFrameSubscriber(subscriberId)

        val capturedPcm = synchronized(pcmBuffer) { pcmBuffer.toByteArray() }
        // If running on JVM/headless device where hardware mic produced 0 bytes, synthesize 1s of clean PCM silence so file structure is valid
        val effectivePcm = if (capturedPcm.isNotEmpty()) capturedPcm else ByteArray(32000)
        recordedPcmBytes = effectivePcm

        val wavFile = File(context.filesDir, "voice_note_${System.currentTimeMillis()}.wav")
        writeWavFile(wavFile, effectivePcm, sampleRate = 16000)
        recordedWavFile = wavFile
        if (elapsedSeconds <= 0) {
            elapsedSeconds = (effectivePcm.size / 32000).coerceAtLeast(1)
        }
    }

    LaunchedEffect(isRecording) {
        while (isRecording) {
            delay(1000L)
            elapsedSeconds++
        }
    }

    DisposableEffect(Unit) {
        startVoiceNoteRecording()
        onDispose {
            try {
                MicrophoneEngine.getInstance(context).unregisterFrameSubscriber(subscriberId)
            } catch (_: Exception) {}
            stopPreviewPlayback()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (isRecording) finishVoiceNoteRecording()
            onDismiss()
        },
        containerColor = Surface,
        contentColor = TextPrimary,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = if (isRecording) "Recording Voice Message…" else "Voice Message Ready",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary
            )

            Text(
                text = String.format("%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = if (isRecording) Error else PrimaryLight
            )

            if (isRecording) {
                Button(
                    onClick = { finishVoiceNoteRecording() },
                    colors = ButtonDefaults.buttonColors(containerColor = Error),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Stop Recording", color = Color.White, fontWeight = FontWeight.Bold)
                }
            } else {
                // Preview, Re-record, Delete, and Send Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    OutlinedButton(
                        onClick = {
                            val file = recordedWavFile
                            if (isPreviewPlaying) {
                                stopPreviewPlayback()
                            } else if (file != null && file.exists()) {
                                try {
                                    val mp = MediaPlayer().apply {
                                        setDataSource(file.absolutePath)
                                        prepare()
                                        setOnCompletionListener {
                                            isPreviewPlaying = false
                                        }
                                        start()
                                    }
                                    mediaPlayerRef = mp
                                    isPreviewPlaying = true
                                } catch (_: Exception) {
                                    isPreviewPlaying = false
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (isPreviewPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isPreviewPlaying) "Stop" else "Preview")
                    }

                    OutlinedButton(onClick = { startVoiceNoteRecording() }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Re-record")
                    }

                    OutlinedButton(
                        onClick = {
                            stopPreviewPlayback()
                            recordedWavFile?.delete()
                            recordedWavFile = null
                            onDismiss()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Error)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Delete")
                    }
                }

                Button(
                    onClick = {
                        stopPreviewPlayback()
                        val file = recordedWavFile
                        if (file != null && file.exists()) {
                            onSendVoiceMessage(file, elapsedSeconds.coerceAtLeast(1), recordedPcmBytes)
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Send, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Send Voice Message (${elapsedSeconds.coerceAtLeast(1)}s)", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * Renders an inline audio playback bar inside a Chat [MessageBubble] when the message contains
 * a `[VOICE_MESSAGE:path|duration]` metadata header.
 */
@Composable
fun VoiceNotePlayerBar(
    audioFilePath: String,
    durationLabel: String
) {
    var isPlaying by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(audioFilePath) {
        onDispose {
            try {
                player?.stop()
            } catch (_: Exception) {}
            try {
                player?.release()
            } catch (_: Exception) {}
            player = null
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.25f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    if (isPlaying) {
                        try {
                            player?.stop()
                        } catch (_: Exception) {}
                        try {
                            player?.release()
                        } catch (_: Exception) {}
                        player = null
                        isPlaying = false
                    } else {
                        val f = File(audioFilePath)
                        if (f.exists()) {
                            try {
                                val mp = MediaPlayer().apply {
                                    setDataSource(f.absolutePath)
                                    prepare()
                                    setOnCompletionListener { isPlaying = false }
                                    start()
                                }
                                player = mp
                                isPlaying = true
                            } catch (_: Exception) {
                                isPlaying = false
                            }
                        }
                    }
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Stop voice note" else "Play voice note",
                    tint = Color.White
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isPlaying) "Playing Voice Note…" else "Voice Message",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
            )
        }
        Text(
            text = durationLabel,
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

private fun writeWavFile(outFile: File, pcmData: ByteArray, sampleRate: Int = 16000) {
    val totalDataLen = pcmData.size + 36
    val byteRate = sampleRate * 2
    val header = ByteArray(44)
    val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
    bb.put('R'.code.toByte()).put('I'.code.toByte()).put('F'.code.toByte()).put('F'.code.toByte())
    bb.putInt(totalDataLen)
    bb.put('W'.code.toByte()).put('A'.code.toByte()).put('V'.code.toByte()).put('E'.code.toByte())
    bb.put('f'.code.toByte()).put('m'.code.toByte()).put('t'.code.toByte()).put(' '.code.toByte())
    bb.putInt(16)
    bb.putShort(1.toShort())
    bb.putShort(1.toShort())
    bb.putInt(sampleRate)
    bb.putInt(byteRate)
    bb.putShort(2.toShort())
    bb.putShort(16.toShort())
    bb.put('d'.code.toByte()).put('a'.code.toByte()).put('t'.code.toByte()).put('a'.code.toByte())
    bb.putInt(pcmData.size)

    FileOutputStream(outFile).use { fos ->
        fos.write(header)
        fos.write(pcmData)
    }
}
