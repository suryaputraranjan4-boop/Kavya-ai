package com.example.memory.obsidian

import kotlinx.coroutines.CancellationException

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.data.MemoryEntity
import com.example.utils.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Obsidian Memory Integration for Kavya AI.
 *
 * Implements genuine two-way file synchronization with an authorized Obsidian Vault
 * folder using Android's Storage Access Framework (SAF).
 *
 * Folder Structure:
 *   [Vault Root] /
 *     KavyaVault /
 *       Memories /       (Markdown files for stored memories with YAML frontmatter)
 *       Conversations /  (Markdown summaries of chats)
 *       Preferences /    (User preferences and directives)
 */
class ObsidianVaultManager(private val context: Context) {

    companion object {
        private const val TAG = "ObsidianVaultManager"
        private const val DIR_KAVYA = "KavyaVault"
        private const val DIR_MEMORIES = "Memories"
        private const val DIR_CONVERSATIONS = "Conversations"
        private const val DIR_PREFERENCES = "Preferences"
    }

    sealed class SyncState {
        object Disconnected : SyncState()
        object Idle : SyncState()
        object Syncing : SyncState()
        data class Success(val message: String, val timestamp: Long) : SyncState()
        data class Error(val error: String) : SyncState()
    }

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Disconnected)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    init {
        checkConnectionStatus()
    }

    fun checkConnectionStatus(): Boolean {
        val uriStr = AppPreferences.getObsidianVaultUri(context)
        if (uriStr.isNullOrBlank()) {
            _syncState.value = SyncState.Disconnected
            return false
        }

        val uri = Uri.parse(uriStr)
        val hasPermission = hasPersistedPermission(uri)
        if (!hasPermission) {
            _syncState.value = SyncState.Disconnected
            AppPreferences.setObsidianVaultUri(context, null)
            return false
        }

        val lastSync = AppPreferences.getObsidianLastSyncTs(context)
        _syncState.value = if (lastSync > 0) {
            SyncState.Success("Vault connected", lastSync)
        } else {
            SyncState.Idle
        }
        return true
    }

    fun isVaultConnected(): Boolean {
        return checkConnectionStatus()
    }

    fun getVaultName(): String? {
        val uriStr = AppPreferences.getObsidianVaultUri(context) ?: return null
        val uri = Uri.parse(uriStr)
        val tree = DocumentFile.fromTreeUri(context, uri)
        return tree?.name ?: "Obsidian Vault"
    }

    /**
     * Connects an authorized Obsidian Vault root selected by the user via SAF.
     */
    fun connectVault(treeUri: Uri): Boolean {
        return try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)

            AppPreferences.setObsidianVaultUri(context, treeUri.toString())
            _syncState.value = SyncState.Idle
            Log.i(TAG, "Successfully connected to Obsidian Vault at $treeUri")
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to Obsidian Vault", e)
            _syncState.value = SyncState.Error("Failed to grant folder permission: ${e.localizedMessage}")
            false
        }
    }

    /**
     * Disconnects the Obsidian Vault and clears stored credentials.
     */
    fun disconnectVault() {
        val uriStr = AppPreferences.getObsidianVaultUri(context)
        if (!uriStr.isNullOrBlank()) {
            try {
                val uri = Uri.parse(uriStr)
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.releasePersistableUriPermission(uri, flags)
            } catch (ignored: Exception) {
            }
        }
        AppPreferences.setObsidianVaultUri(context, null)
        _syncState.value = SyncState.Disconnected
    }

    /**
     * Syncs Room database memories to Obsidian Markdown files.
     */
    suspend fun syncMemoriesToVault(memories: List<MemoryEntity>): Boolean = withContext(Dispatchers.IO) {
        val uriStr = AppPreferences.getObsidianVaultUri(context)
        if (uriStr.isNullOrBlank()) {
            _syncState.value = SyncState.Disconnected
            return@withContext false
        }

        _syncState.value = SyncState.Syncing

        try {
            val treeUri = Uri.parse(uriStr)
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw IllegalStateException("Cannot access Obsidian vault root directory")

            val kavyaDir = getOrCreateSubdirectory(rootDoc, DIR_KAVYA)
            val memoriesDir = getOrCreateSubdirectory(kavyaDir, DIR_MEMORIES)

            var writtenCount = 0
            for (mem in memories) {
                val fileName = sanitizeFileName(mem.key) + ".md"
                val mdContent = buildMarkdownContent(mem)

                var fileDoc = memoriesDir.findFile(fileName)
                if (fileDoc == null) {
                    fileDoc = memoriesDir.createFile("text/markdown", fileName)
                }

                if (fileDoc != null) {
                    context.contentResolver.openOutputStream(fileDoc.uri, "wt")?.use { out ->
                        out.write(mdContent.toByteArray(Charsets.UTF_8))
                        writtenCount++
                    }
                }
            }

            if (writtenCount != memories.size) {
                val message = "Vault export incomplete: wrote $writtenCount of ${memories.size} memory files."
                _syncState.value = SyncState.Error(message)
                Log.w(TAG, message)
                return@withContext false
            }

            val now = System.currentTimeMillis()
            AppPreferences.setObsidianLastSyncTs(context, now)
            _syncState.value = SyncState.Success("Synced $writtenCount memories to Vault", now)
            Log.i(TAG, "Successfully synced $writtenCount memories to Obsidian Vault")
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Sync to Obsidian Vault failed", e)
            _syncState.value = SyncState.Error("Sync failed: ${e.localizedMessage}")
            false
        }
    }

    /**
     * Reads Markdown files from the Obsidian Vault to import into Room.
     */
    suspend fun importMemoriesFromVault(): List<MemoryEntity> = withContext(Dispatchers.IO) {
        val uriStr = AppPreferences.getObsidianVaultUri(context) ?: return@withContext emptyList()
        val treeUri = Uri.parse(uriStr)
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()

        val kavyaDir = rootDoc.findFile(DIR_KAVYA) ?: return@withContext emptyList()
        val memoriesDir = kavyaDir.findFile(DIR_MEMORIES) ?: return@withContext emptyList()

        val imported = mutableListOf<MemoryEntity>()
        val files = memoriesDir.listFiles()

        for (file in files) {
            if (file.isFile && file.name?.endsWith(".md", ignoreCase = true) == true) {
                try {
                    val content = context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() }
                    if (!content.isNullOrBlank()) {
                        val parsed = parseMarkdownMemory(file.name?.removeSuffix(".md") ?: "Imported", content)
                        imported.add(parsed)
                    }
                } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
                    Log.w(TAG, "Failed to read memory file ${file.name}", e)
                }
            }
        }

        imported
    }

    private fun getOrCreateSubdirectory(parent: DocumentFile, name: String): DocumentFile {
        val existing = parent.findFile(name)
        if (existing != null && existing.isDirectory) {
            return existing
        }
        return parent.createDirectory(name)
            ?: throw IllegalStateException("Could not create directory '$name' in vault")
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "memory" }
    }

    private fun buildMarkdownContent(mem: MemoryEntity): String {
        return buildString {
            appendLine("---")
            appendLine("id: ${mem.id}")
            appendLine("key: \"${mem.key.replace("\"", "\\\"")}\"")
            appendLine("category: \"${mem.category}\"")
            appendLine("importance: ${mem.importance}")
            appendLine("confidence: ${mem.confidence}")
            appendLine("created_at: ${mem.createdAt}")
            appendLine("last_used_at: ${mem.lastUsedAt}")
            appendLine("source: \"${mem.sourceConversation.replace("\"", "\\\"")}\"")
            appendLine("---")
            appendLine()
            appendLine("# ${mem.key}")
            appendLine()
            appendLine(mem.content)
        }
    }

    private fun parseMarkdownMemory(defaultKey: String, markdown: String): MemoryEntity {
        var key = defaultKey
        var category = "PREFERENCE"
        var importance = 3
        var confidence = 1.0f
        var createdAt = System.currentTimeMillis()
        var lastUsedAt = System.currentTimeMillis()
        var source = "Obsidian Vault"
        var body = markdown

        if (markdown.startsWith("---")) {
            val endIdx = markdown.indexOf("---", 3)
            if (endIdx != -1) {
                val header = markdown.substring(3, endIdx)
                body = markdown.substring(endIdx + 3).trim()

                for (line in header.lines()) {
                    val parts = line.split(":", limit = 2)
                    if (parts.size == 2) {
                        val k = parts[0].trim()
                        val v = parts[1].trim().removeSurrounding("\"")
                        when (k) {
                            "key" -> if (v.isNotBlank()) key = v
                            "category" -> if (v.isNotBlank()) category = v
                            "importance" -> importance = v.toIntOrNull() ?: 3
                            "confidence" -> confidence = v.toFloatOrNull() ?: 1.0f
                            "created_at" -> createdAt = v.toLongOrNull() ?: createdAt
                            "last_used_at" -> lastUsedAt = v.toLongOrNull() ?: lastUsedAt
                            "source" -> if (v.isNotBlank()) source = v
                        }
                    }
                }
            }
        }

        // If body starts with header line "# <key>", strip it for clean content
        if (body.startsWith("# ")) {
            body = body.substringAfter("\n").trim()
        }

        return MemoryEntity(
            id = 0, // Auto-generate ID in Room
            key = key,
            content = body,
            category = category,
            importance = importance,
            createdAt = createdAt,
            lastUsedAt = lastUsedAt,
            sourceConversation = source,
            confidence = confidence,
            userConfirmed = true
        )
    }

    private fun hasPersistedPermission(uri: Uri): Boolean {
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
    }
}
