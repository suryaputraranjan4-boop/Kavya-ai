package com.example.context

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.OpenableColumns

data class DeviceSummary(
    val androidVersion: String,
    val apiLevel: Int,
    val manufacturer: String,
    val model: String,
    val appVersion: String,
    val availableStorageMB: Long,
    val totalStorageMB: Long,
    val installedAppsCount: Int
)

data class SelectedFileInfo(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String?
)

/**
 * Minimal, safe real device context layer for Kavya AI.
 * Exposes basic system specifications and storage awareness without requiring broad permissions.
 */
class DeviceContextManager(private val context: Context) {

    fun getSafeDeviceSummary(): DeviceSummary {
        val appVersion = try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }

        val stat = try {
            val path = Environment.getDataDirectory()
            val statFs = StatFs(path.path)
            Pair(
                (statFs.availableBlocksLong * statFs.blockSizeLong) / (1024 * 1024),
                (statFs.blockCountLong * statFs.blockSizeLong) / (1024 * 1024)
            )
        } catch (_: Exception) {
            Pair(0L, 0L)
        }

        val appsCount = try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            context.packageManager.queryIntentActivities(mainIntent, 0).size
        } catch (_: Exception) {
            0
        }

        return DeviceSummary(
            androidVersion = Build.VERSION.RELEASE ?: "Unknown",
            apiLevel = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: "Android",
            model = Build.MODEL ?: "Device",
            appVersion = appVersion,
            availableStorageMB = stat.first,
            totalStorageMB = stat.second,
            installedAppsCount = appsCount
        )
    }

    fun isDeviceQuery(query: String): Boolean {
        val q = query.lowercase().trim()
        return q.contains("device info") || q.contains("device information") ||
               q.contains("phone info") || q.contains("android version") ||
               q.contains("storage info") || q.contains("device specs") ||
               q.contains("phone specs") || q.contains("system info") ||
               q == "device" || q == "specs"
    }

    fun getDeviceFormattedString(): String {
        val s = getSafeDeviceSummary()
        return buildString {
            appendLine("Device Information:")
            appendLine("• Device: ${s.manufacturer} ${s.model}")
            appendLine("• OS: Android ${s.androidVersion} (API level ${s.apiLevel})")
            appendLine("• Application: Kavya AI v${s.appVersion}")
            if (s.totalStorageMB > 0) {
                val freeGb = String.format(java.util.Locale.US, "%.1f", s.availableStorageMB / 1024.0)
                val totalGb = String.format(java.util.Locale.US, "%.1f", s.totalStorageMB / 1024.0)
                appendLine("• Storage: $freeGb GB free of $totalGb GB")
            }
            if (s.installedAppsCount > 0) {
                appendLine("• Launchable Apps: ${s.installedAppsCount} apps installed")
            }
        }.trimEnd()
    }

    fun getFileInfo(uri: Uri): SelectedFileInfo? {
        return try {
            var name = "Unknown file"
            var size = 0L
            val mimeType = context.contentResolver.getType(uri)

            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                }
            }
            SelectedFileInfo(uri, name, size, mimeType)
        } catch (_: Exception) {
            null
        }
    }
}
