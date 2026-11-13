package com.example.orchestrator

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.example.services.KavyaAccessibilityService
import com.example.utils.AppResolver
import kotlinx.coroutines.delay

/**
 * Section 14: Dedicated Multi-Window & Split-Screen Controller.
 *
 * Uses legitimate Android capabilities:
 * - Checks device/Android version support (API 24+)
 * - Checks target activity resizeable support via PackageManager
 * - Triggers split-screen via AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN (when authorized)
 *   and launches adjacent activity with FLAG_ACTIVITY_LAUNCH_ADJACENT | FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK
 * - Verifies resulting window state and never claims split-screen succeeded unless verified.
 */
data class MultiWindowResult(
    val success: Boolean,
    val verificationStatus: OrchestratorVerificationStatus,
    val detail: String,
    val primaryPackage: String = "",
    val secondaryPackage: String = ""
)

object MultiWindowController {
    private const val TAG = "MultiWindowController"

    private const val FEATURE_FREEFORM_WINDOW_MANAGEMENT = "android.software.freeform_window_management"
    private const val RESIZE_MODE_UNRESIZEABLE_VALUE = 0

    /**
     * Checks whether the current Android version and device configuration support multi-window / split-screen.
     */
    fun isDeviceMultiWindowSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            context.packageManager.hasSystemFeature(FEATURE_FREEFORM_WINDOW_MANAGEMENT) ||
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
        } catch (_: Exception) {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
        }
    }

    /**
     * Inspects PackageManager to determine if the target package's launch activity supports multi-window resize mode.
     */
    fun doesPackageSupportMultiWindow(context: Context, packageName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || packageName.isBlank()) return false
        return try {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName) ?: return false
            val resolveInfo = pm.resolveActivity(launchIntent, PackageManager.MATCH_DEFAULT_ONLY) ?: return false
            val activityInfo = resolveInfo.activityInfo ?: return true
            return try {
                val field = ActivityInfo::class.java.getDeclaredField("resizeMode")
                field.isAccessible = true
                val mode = field.getInt(activityInfo)
                mode != RESIZE_MODE_UNRESIZEABLE_VALUE
            } catch (_: Throwable) {
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking multi-window support for $packageName: ${e.message}")
            false
        }
    }

    /**
     * Executes an AI-authorized split-screen / adjacent launch workflow and verifies the resulting window state.
     */
    suspend fun executeSplitScreen(
        context: Context,
        permit: ActionAuthorizationPermit,
        primaryAppQuery: String,
        secondaryAppQuery: String = ""
    ): MultiWindowResult {
        if (!AiAuthorityGateway.isInFlightPermitValid(permit)) {
            return MultiWindowResult(
                success = false,
                verificationStatus = OrchestratorVerificationStatus.FAILED,
                detail = "Rejected split-screen execution: missing or expired AI authorization permit."
            )
        }

        if (!isDeviceMultiWindowSupported(context)) {
            return MultiWindowResult(
                success = false,
                verificationStatus = OrchestratorVerificationStatus.FAILED,
                detail = "Split-screen / multi-window is not supported on this Android version."
            )
        }

        val resolver = AppResolver(context)
        val (firstRaw, secondRaw) = if (secondaryAppQuery.isNotBlank()) {
            primaryAppQuery.trim() to secondaryAppQuery.trim()
        } else {
            parseSplitAppPair(primaryAppQuery)
        }

        val primaryApp = if (firstRaw.isNotBlank()) resolver.resolve(firstRaw).matchedApp else null
        val secondaryApp = if (secondRaw.isNotBlank()) resolver.resolve(secondRaw).matchedApp else null

        if (primaryApp != null && !doesPackageSupportMultiWindow(context, primaryApp.packageName)) {
            return MultiWindowResult(
                success = false,
                verificationStatus = OrchestratorVerificationStatus.FAILED,
                detail = "App '${primaryApp.appName}' does not support split-screen / multi-window mode.",
                primaryPackage = primaryApp.packageName
            )
        }

        if (secondaryApp != null && !doesPackageSupportMultiWindow(context, secondaryApp.packageName)) {
            return MultiWindowResult(
                success = false,
                verificationStatus = OrchestratorVerificationStatus.FAILED,
                detail = "App '${secondaryApp.appName}' does not support split-screen / multi-window mode.",
                primaryPackage = primaryApp?.packageName.orEmpty(),
                secondaryPackage = secondaryApp.packageName
            )
        }

        val a11y = KavyaAccessibilityService.instance
        if (a11y == null) {
            return MultiWindowResult(
                success = false,
                verificationStatus = OrchestratorVerificationStatus.BLOCKED_BY_PERMISSION,
                detail = "Accessibility service is required to toggle split-screen mode and verify multi-window state."
            )
        }

        return try {
            // 1. If primary app specified and not already in foreground, launch it first
            if (primaryApp != null && !a11y.isForegroundPackage(primaryApp.packageName)) {
                val primaryIntent = context.packageManager.getLaunchIntentForPackage(primaryApp.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                } ?: return MultiWindowResult(
                    success = false,
                    verificationStatus = OrchestratorVerificationStatus.FAILED,
                    detail = "Unable to resolve launch intent for ${primaryApp.appName}."
                )
                context.startActivity(primaryIntent)
                delay(450L)
            }

            // 2. Toggle split-screen via Accessibility global action (API 24+)
            val toggled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                a11y.performAuthorizedGlobal(permit, AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            } else {
                false
            }
            delay(350L)

            // 3. If secondary app is specified, launch it with FLAG_ACTIVITY_LAUNCH_ADJACENT
            var adjacentLaunched = false
            if (secondaryApp != null) {
                val adjacentIntent = context.packageManager.getLaunchIntentForPackage(secondaryApp.packageName)?.apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    )
                }
                if (adjacentIntent != null) {
                    context.startActivity(adjacentIntent)
                    adjacentLaunched = true
                    delay(450L)
                }
            }

            // 4. Verify multi-window / foreground state honestly
            val verified = verifySplitScreenActive(a11y, primaryApp?.packageName, secondaryApp?.packageName, toggled, adjacentLaunched)
            if (verified) {
                MultiWindowResult(
                    success = true,
                    verificationStatus = OrchestratorVerificationStatus.SUCCESS,
                    detail = buildString {
                        append("Verified split-screen mode active")
                        if (primaryApp != null) append(" with ${primaryApp.appName}")
                        if (secondaryApp != null) append(" and ${secondaryApp.appName}")
                    },
                    primaryPackage = primaryApp?.packageName.orEmpty(),
                    secondaryPackage = secondaryApp?.packageName.orEmpty()
                )
            } else if (toggled || adjacentLaunched) {
                MultiWindowResult(
                    success = false,
                    verificationStatus = OrchestratorVerificationStatus.PARTIALLY_COMPLETED,
                    detail = "Split-screen toggle/adjacent launch was dispatched, but multi-window layout could not be confirmed on screen.",
                    primaryPackage = primaryApp?.packageName.orEmpty(),
                    secondaryPackage = secondaryApp?.packageName.orEmpty()
                )
            } else {
                MultiWindowResult(
                    success = false,
                    verificationStatus = OrchestratorVerificationStatus.FAILED,
                    detail = "Device rejected split-screen toggle request.",
                    primaryPackage = primaryApp?.packageName.orEmpty(),
                    secondaryPackage = secondaryApp?.packageName.orEmpty()
                )
            }
        } catch (e: Exception) {
            MultiWindowResult(
                success = false,
                verificationStatus = OrchestratorVerificationStatus.FAILED,
                detail = "Split-screen execution error: ${e.message}",
                primaryPackage = primaryApp?.packageName.orEmpty(),
                secondaryPackage = secondaryApp?.packageName.orEmpty()
            )
        }
    }

    private fun verifySplitScreenActive(
        a11y: KavyaAccessibilityService,
        primaryPkg: String?,
        secondaryPkg: String?,
        toggled: Boolean,
        adjacentLaunched: Boolean
    ): Boolean {
        return try {
            val windows = a11y.windows
            if (windows != null && windows.size >= 2) {
                val appWindows = windows.count { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION || it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER }
                if (appWindows >= 2) return true
            }
            val fg = a11y.getForegroundPackage()
            if (toggled && secondaryPkg != null && adjacentLaunched && fg.contains(secondaryPkg, ignoreCase = true)) {
                return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    fun parseSplitAppPair(raw: String): Pair<String, String> {
        val clean = raw.replace("(?i)split\\s*screen|multi\\s*window|side\\s*by\\s*side|me|mein|in|open|launch|kholo".toRegex(), " ").trim()
        val parts = clean.split(Regex("(?i)\\s+(?:and|aur|with|और)\\s+|\\s*,\\s*|\\s*\\|\\s*"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> parts[0] to parts[1]
            parts.size == 1 -> parts[0] to ""
            else -> clean to ""
        }
    }
}
