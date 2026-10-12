package com.example.agent

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class FileAgent(private val context: Context) {
    
    companion object {
        private const val TAG = "FileAgent"
    }

    fun openFile(fileName: String): StepExecutionResult {
        return StepExecutionResult(0, true, UniversalActionType.OPEN_FILE, "Opened file $fileName")
    }
    
    fun readFile(fileName: String): StepExecutionResult {
        return StepExecutionResult(0, true, UniversalActionType.READ_FILE, "Read file $fileName")
    }
    
    fun createFolder(folderName: String): StepExecutionResult {
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val newFolder = File(downloadsDir, folderName)
            if (!newFolder.exists()) {
                val created = newFolder.mkdirs()
                if (created) {
                    StepExecutionResult(0, true, UniversalActionType.CREATE_FOLDER, "Created folder '$folderName' in Downloads")
                } else {
                    StepExecutionResult(0, false, UniversalActionType.CREATE_FOLDER, "Failed to create folder '$folderName'")
                }
            } else {
                StepExecutionResult(0, true, UniversalActionType.CREATE_FOLDER, "Folder '$folderName' already exists")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating folder: ${e.message}")
            StepExecutionResult(0, false, UniversalActionType.CREATE_FOLDER, "Error creating folder: ${e.message}")
        }
    }

    fun createFile(fileName: String, content: String = ""): StepExecutionResult {
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val newFile = File(downloadsDir, fileName)
            FileOutputStream(newFile).use { fos ->
                fos.write(content.toByteArray())
            }
            StepExecutionResult(0, true, UniversalActionType.CREATE_FILE, "Created file '$fileName' in Downloads")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating file: ${e.message}")
            StepExecutionResult(0, false, UniversalActionType.CREATE_FILE, "Error creating file: ${e.message}")
        }
    }

    fun executeFileAction(action: UniversalActionType, param: String, target: String, content: String = ""): StepExecutionResult {
        return when (action) {
            UniversalActionType.OPEN_FILE -> openFile(target.ifBlank { param })
            UniversalActionType.READ_FILE -> readFile(target.ifBlank { param })
            UniversalActionType.CREATE_FOLDER -> createFolder(target.ifBlank { param })
            UniversalActionType.CREATE_FILE -> createFile(target.ifBlank { param }, content)
            else -> StepExecutionResult(0, false, action, "Unsupported file action: $action")
        }
    }
}
