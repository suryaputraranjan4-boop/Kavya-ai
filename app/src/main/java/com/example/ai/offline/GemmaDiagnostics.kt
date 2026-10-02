package com.example.ai.offline

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import java.io.File

data class GemmaDiagnostics(
    val modelPath: String = "None",
    val sizeBytes: Long = 0L,
    val sizeFormatted: String = "0 MB",
    val detectedFormat: GemmaModelFormat = GemmaModelFormat.UNKNOWN,
    val runtimeSelected: String = "None",
    val initializationState: GemmaModelStatus = GemmaModelStatus.NOT_INSTALLED,
    val healthCheckState: String = "PENDING",
    val availableMemoryMB: Long = 0L,
    val initializationTimeMs: Long = 0L,
    val lastError: String? = null,
    val inferenceSucceeded: Boolean = false,
    val networkUsed: Boolean = false
) {
    fun toFormattedReport(): String {
        return """
            === GEMMA 4 E4B OFFLINE DIAGNOSTICS ===
            Model Path: $modelPath
            Size: $sizeFormatted ($sizeBytes bytes)
            Format: ${detectedFormat.name}
            Runtime Engine: $runtimeSelected
            Status State: ${initializationState.name}
            Health Check: $healthCheckState
            Available System RAM: ${availableMemoryMB} MB
            Initialization Time: ${initializationTimeMs}ms
            Inference Succeeded: $inferenceSucceeded
            Network Call Made: ${if (networkUsed) "YES (CRITICAL VIOLATION)" else "NO (100% Offline Guarantee)"}
            Last Error: ${lastError ?: "None"}
            ======================================
        """.trimIndent()
    }

    companion object {
        fun getAvailableSystemMemoryMB(context: Context): Long {
            return try {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val memoryInfo = ActivityManager.MemoryInfo()
                if (activityManager != null) {
                    activityManager.getMemoryInfo(memoryInfo)
                    memoryInfo.availMem / (1024 * 1024)
                } else {
                    Runtime.getRuntime().freeMemory() / (1024 * 1024)
                }
            } catch (_: Exception) {
                0L
            }
        }
    }
}
