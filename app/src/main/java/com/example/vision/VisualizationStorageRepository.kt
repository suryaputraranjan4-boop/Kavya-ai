package com.example.vision

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent local storage, undo/redo history, file import, and Share/FileProvider export
 * repository for generated visualizations, scenes, whiteboards, datasets, GLBs, and videos.
 */
object VisualizationStorageRepository {

    private const val TAG = "VisualizationRepo"
    private const val INDEX_FILE = "index.json"
    private const val MAX_UNDO_DEPTH = 25

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val _artifacts = MutableStateFlow<List<VisualizationArtifact>>(emptyList())
    val artifacts: StateFlow<List<VisualizationArtifact>> = _artifacts.asStateFlow()

    private val undoStacks = ConcurrentHashMap<String, ArrayDeque<VisualizationUndoSnapshot>>()
    private val redoStacks = ConcurrentHashMap<String, ArrayDeque<VisualizationUndoSnapshot>>()

    private fun rootDir(context: Context): File =
        File(context.filesDir, "visualizations").apply { mkdirs() }

    private fun scenesDir(context: Context): File =
        File(rootDir(context), "scenes").apply { mkdirs() }

    private fun exportsDir(context: Context, artifactId: String): File =
        File(rootDir(context), "exports/$artifactId").apply { mkdirs() }

    fun loadAll(context: Context): List<VisualizationArtifact> {
        val indexFile = File(rootDir(context), INDEX_FILE)
        if (!indexFile.isFile) {
            _artifacts.value = emptyList()
            return emptyList()
        }
        return try {
            val loaded = json.decodeFromString<List<VisualizationArtifact>>(indexFile.readText(Charsets.UTF_8))
                .sortedByDescending { it.updatedAtMs }
            _artifacts.value = loaded
            loaded
        } catch (e: Exception) {
            Log.w(TAG, "Failed decoding visualizations index: ${e.message}")
            _artifacts.value = emptyList()
            emptyList()
        }
    }

    private fun persistIndex(context: Context, list: List<VisualizationArtifact>) {
        val ordered = list.sortedByDescending { it.updatedAtMs }
        File(rootDir(context), INDEX_FILE).writeText(json.encodeToString(ordered), Charsets.UTF_8)
        _artifacts.value = ordered
    }

    @Synchronized
    fun saveOrUpdate(
        context: Context,
        artifact: VisualizationArtifact,
        pushUndoState: Boolean = false,
        undoLabel: String = "Edit scene"
    ): VisualizationArtifact {
        val currentList = if (_artifacts.value.isEmpty() && File(rootDir(context), INDEX_FILE).isFile) {
            loadAll(context)
        } else {
            _artifacts.value
        }
        val existing = currentList.firstOrNull { it.artifactId == artifact.artifactId }

        if (pushUndoState && existing != null && existing.scenePayloadJson != artifact.scenePayloadJson) {
            val stack = undoStacks.getOrPut(artifact.artifactId) { ArrayDeque() }
            if (stack.size >= MAX_UNDO_DEPTH) stack.removeFirst()
            stack.addLast(VisualizationUndoSnapshot(label = undoLabel, payloadJson = existing.scenePayloadJson))
            redoStacks[artifact.artifactId]?.clear()
        }

        val now = System.currentTimeMillis()
        val withFlags = artifact.copy(
            updatedAtMs = now,
            canUndo = (undoStacks[artifact.artifactId]?.isNotEmpty() == true),
            canRedo = (redoStacks[artifact.artifactId]?.isNotEmpty() == true)
        )

        File(scenesDir(context), "${withFlags.artifactId}.json").writeText(withFlags.scenePayloadJson, Charsets.UTF_8)
        val updatedList = listOf(withFlags) + currentList.filterNot { it.artifactId == withFlags.artifactId }
        persistIndex(context, updatedList)
        return withFlags
    }

    @Synchronized
    fun undo(context: Context, artifactId: String): VisualizationArtifact? {
        val undoStack = undoStacks[artifactId] ?: return null
        if (undoStack.isEmpty()) return null
        val current = _artifacts.value.firstOrNull { it.artifactId == artifactId } ?: return null
        val prev = undoStack.removeLast()
        val redoStack = redoStacks.getOrPut(artifactId) { ArrayDeque() }
        redoStack.addLast(VisualizationUndoSnapshot(label = "Redo", payloadJson = current.scenePayloadJson))
        val updated = current.copy(
            scenePayloadJson = prev.payloadJson,
            updatedAtMs = System.currentTimeMillis(),
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty()
        )
        return saveOrUpdate(context, updated, pushUndoState = false)
    }

    @Synchronized
    fun redo(context: Context, artifactId: String): VisualizationArtifact? {
        val redoStack = redoStacks[artifactId] ?: return null
        if (redoStack.isEmpty()) return null
        val current = _artifacts.value.firstOrNull { it.artifactId == artifactId } ?: return null
        val next = redoStack.removeLast()
        val undoStack = undoStacks.getOrPut(artifactId) { ArrayDeque() }
        undoStack.addLast(VisualizationUndoSnapshot(label = "Edit", payloadJson = current.scenePayloadJson))
        val updated = current.copy(
            scenePayloadJson = next.payloadJson,
            updatedAtMs = System.currentTimeMillis(),
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty()
        )
        return saveOrUpdate(context, updated, pushUndoState = false)
    }

    fun toggleFavorite(context: Context, artifactId: String): VisualizationArtifact? {
        val cur = _artifacts.value.firstOrNull { it.artifactId == artifactId } ?: return null
        return saveOrUpdate(context, cur.copy(isSavedFavorite = !cur.isSavedFavorite))
    }

    fun renameArtifact(context: Context, artifactId: String, newTitle: String): VisualizationArtifact? {
        val clean = newTitle.trim().ifBlank { return null }
        val cur = _artifacts.value.firstOrNull { it.artifactId == artifactId } ?: return null
        return saveOrUpdate(context, cur.copy(title = clean))
    }

    fun duplicateArtifact(context: Context, artifactId: String): VisualizationArtifact? {
        val cur = _artifacts.value.firstOrNull { it.artifactId == artifactId } ?: return null
        val copy = cur.copy(
            artifactId = UUID.randomUUID().toString(),
            title = "${cur.title} (Copy)",
            createdAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
            canUndo = false,
            canRedo = false,
            exportedPaths = emptyMap()
        )
        return saveOrUpdate(context, copy)
    }

    fun deleteArtifact(context: Context, artifactId: String) {
        val current = _artifacts.value.filterNot { it.artifactId == artifactId }
        File(scenesDir(context), "$artifactId.json").delete()
        exportsDir(context, artifactId).deleteRecursively()
        undoStacks.remove(artifactId)
        redoStacks.remove(artifactId)
        persistIndex(context, current)
    }

    fun saveExportFile(
        context: Context,
        artifactId: String,
        format: VisualizationExportFormat,
        content: String,
        isBase64: Boolean
    ): File {
        val safeTitle = (_artifacts.value.firstOrNull { it.artifactId == artifactId }?.title ?: "visual")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "visual" }
        val outFile = File(exportsDir(context, artifactId), "${safeTitle}.${format.extension}")
        outFile.parentFile?.mkdirs()

        val bytes = if (isBase64) {
            val cleaned = content.substringAfter("base64,", content)
            Base64.decode(cleaned, Base64.DEFAULT)
        } else {
            content.toByteArray(Charsets.UTF_8)
        }
        outFile.writeBytes(bytes)

        val current = _artifacts.value.firstOrNull { it.artifactId == artifactId }
        if (current != null) {
            val updatedExports = current.exportedPaths + (format.name to outFile.absolutePath)
            val updatedThumb = if (format == VisualizationExportFormat.PNG) outFile.absolutePath else current.thumbnailPath
            saveOrUpdate(
                context,
                current.copy(
                    exportedPaths = updatedExports,
                    thumbnailPath = updatedThumb
                )
            )
        }
        return outFile
    }

    fun buildShareIntent(context: Context, file: File, mimeType: String, title: String): Intent {
        val authority = "${context.packageName}.fileprovider"
        val uri: Uri = FileProvider.getUriForFile(context, authority, file)
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.let { Intent.createChooser(it, "Share $title") }
    }

    fun clearAllSavedExports(context: Context): Long {
        var bytes = 0L
        File(rootDir(context), "exports").let { dir ->
            if (dir.exists()) {
                bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                dir.deleteRecursively()
            }
        }
        return bytes
    }
}
