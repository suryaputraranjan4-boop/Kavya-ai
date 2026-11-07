package com.example.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * File operations are limited to app-owned storage or an explicit SAF content:// URI.
 * Public shared storage is not accessed through deprecated unrestricted file paths.
 */
class FileAgent(private val context: Context) {

    companion object {
        private const val TAG = "FileAgent"
        private const val MAX_READ_BYTES = 64 * 1024
        private const val FILE_PROVIDER_SUFFIX = ".fileprovider"
    }

    fun openFile(fileName: String, stepId: Int = 0): StepExecutionResult {
        val target = fileName.trim()
        if (target.isBlank()) return failure(stepId, UniversalActionType.OPEN_FILE, "File name or URI is required.")

        return try {
            val uri = resolveUri(target)
                ?: return failure(stepId, UniversalActionType.OPEN_FILE, "File not found or not accessible. Select it through Android's file picker and pass its content URI.")
            val mimeType = context.contentResolver.getType(uri)
                ?: mimeTypeFor(target)
                ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // Package visibility can make resolveActivity return null on Android 11+.
            // Let Android resolve the chooser/handler and report ActivityNotFoundException if none exists.
            context.startActivity(intent)
            StepExecutionResult(stepId, true, UniversalActionType.OPEN_FILE, "Opened '$target' in an installed viewer.")
        } catch (e: Exception) {
            Log.e(TAG, "Unable to open file '$target'", e)
            failure(stepId, UniversalActionType.OPEN_FILE, "Could not open '$target': ${e.message ?: "unknown error"}")
        }
    }

    fun readFile(fileName: String, stepId: Int = 0): StepExecutionResult {
        val target = fileName.trim()
        if (target.isBlank()) return failure(stepId, UniversalActionType.READ_FILE, "File name or URI is required.")

        return try {
            val uri = resolveUri(target)
                ?: return failure(stepId, UniversalActionType.READ_FILE, "File not found or not accessible. Select it through Android's file picker and pass its content URI.")
            val input = context.contentResolver.openInputStream(uri)
                ?: return failure(stepId, UniversalActionType.READ_FILE, "Android could not open the selected file for reading.")
            val bytes = input.use { readBounded(it, MAX_READ_BYTES) }
            if (bytes.isEmpty()) {
                StepExecutionResult(stepId, true, UniversalActionType.READ_FILE, "File '$target' is empty.")
            } else {
                val text = bytes.toString(Charsets.UTF_8)
                val suffix = if (bytes.size >= MAX_READ_BYTES) "\n[Output limited to $MAX_READ_BYTES bytes.]" else ""
                StepExecutionResult(stepId, true, UniversalActionType.READ_FILE, "Contents of '$target':\n$text$suffix")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to read file '$target'", e)
            failure(stepId, UniversalActionType.READ_FILE, "Could not read '$target': ${e.message ?: "unknown error"}")
        }
    }

    fun createFolder(folderName: String, stepId: Int = 0): StepExecutionResult {
        val safeName = validateRelativeName(folderName)
            ?: return failure(stepId, UniversalActionType.CREATE_FOLDER, "Folder name is invalid.")
        return try {
            val parent = downloadsDirectory()
            val folder = File(parent, safeName)
            if (folder.exists()) {
                if (folder.isDirectory) {
                    StepExecutionResult(stepId, true, UniversalActionType.CREATE_FOLDER, "Folder '$safeName' already exists in Kavya's app storage.")
                } else {
                    failure(stepId, UniversalActionType.CREATE_FOLDER, "'$safeName' exists but is not a folder.")
                }
            } else if (folder.mkdirs()) {
                StepExecutionResult(stepId, true, UniversalActionType.CREATE_FOLDER, "Created folder '$safeName' in Kavya's app-specific Downloads folder.")
            } else {
                failure(stepId, UniversalActionType.CREATE_FOLDER, "Android could not create folder '$safeName'.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating folder '$safeName'", e)
            failure(stepId, UniversalActionType.CREATE_FOLDER, "Could not create folder: ${e.message ?: "unknown error"}")
        }
    }

    fun createFile(fileName: String, content: String = "", stepId: Int = 0): StepExecutionResult {
        val safeName = validateRelativeName(fileName)
            ?: return failure(stepId, UniversalActionType.CREATE_FILE, "File name is invalid.")
        return try {
            val parent = downloadsDirectory()
            if (!parent.exists() && !parent.mkdirs()) {
                return failure(stepId, UniversalActionType.CREATE_FILE, "Could not create Kavya's app-specific Downloads folder.")
            }
            val file = File(parent, safeName).canonicalFile
            if (file.parentFile != parent.canonicalFile) {
                return failure(stepId, UniversalActionType.CREATE_FILE, "File path must stay inside Kavya's app-specific Downloads folder.")
            }
            if (file.exists()) {
                return failure(stepId, UniversalActionType.CREATE_FILE, "File '$safeName' already exists. It was not overwritten.")
            }
            FileOutputStream(file, false).use { it.write(content.toByteArray(Charsets.UTF_8)) }
            StepExecutionResult(stepId, true, UniversalActionType.CREATE_FILE, "Created '$safeName' in Kavya's app-specific Downloads folder.")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating file '$safeName'", e)
            failure(stepId, UniversalActionType.CREATE_FILE, "Could not create file: ${e.message ?: "unknown error"}")
        }
    }

    fun executeFileAction(
        action: UniversalActionType,
        param: String,
        target: String,
        content: String = "",
        stepId: Int = 0
    ): StepExecutionResult {
        val name = target.ifBlank { param }
        return when (action) {
            UniversalActionType.OPEN_FILE -> openFile(name, stepId)
            UniversalActionType.READ_FILE -> readFile(name, stepId)
            UniversalActionType.CREATE_FOLDER -> createFolder(name, stepId)
            UniversalActionType.CREATE_FILE -> createFile(name, content, stepId)
            else -> failure(stepId, action, "This file operation is not implemented yet: $action")
        }
    }

    private fun downloadsDirectory(): File =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "Downloads")

    private fun validateRelativeName(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isBlank() || trimmed == "." || trimmed == "..") return null
        if (trimmed.contains('/') || trimmed.contains('\\') || trimmed.contains('\u0000')) return null
        if (trimmed.startsWith("content:", ignoreCase = true) || trimmed.startsWith("file:", ignoreCase = true)) return null
        return trimmed
    }

    private fun resolveUri(value: String): Uri? {
        val parsed = runCatching { Uri.parse(value) }.getOrNull()
        if (parsed?.scheme.equals("content", ignoreCase = true)) {
            return parsed
        }
        val file = resolveLocalFile(value) ?: return null
        return runCatching {
            FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, file)
        }.onFailure { Log.e(TAG, "Unable to create content URI for '${file.name}'", it) }.getOrNull()
    }

    private fun resolveLocalFile(value: String): File? {
        val raw = runCatching {
            val uri = Uri.parse(value)
            if (uri.scheme.equals("file", ignoreCase = true)) uri.path else value
        }.getOrNull() ?: return null
        val requested = File(raw)
        val roots = listOfNotNull(
            context.filesDir,
            context.cacheDir,
            context.getExternalFilesDir(null),
            downloadsDirectory()
        ).map { runCatching { it.canonicalFile }.getOrNull() }.filterNotNull()

        val candidates = if (requested.isAbsolute) {
            listOf(requested)
        } else {
            roots.map { File(it, raw) }
        }
        return candidates.firstNotNullOfOrNull { candidate ->
            val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return@firstNotNullOfOrNull null
            val allowed = roots.any { root ->
                canonical == root || canonical.path.startsWith(root.path + File.separator)
            }
            canonical.takeIf { allowed && it.isFile && it.canRead() }
        }
    }

    private fun mimeTypeFor(fileName: String): String? {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return if (extension.isBlank()) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var remaining = limit
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count <= 0) break
            output.write(buffer, 0, count)
            remaining -= count
        }
        return output.toByteArray()
    }

    private fun failure(stepId: Int, action: UniversalActionType, message: String) =
        StepExecutionResult(stepId, false, action, message)
}
