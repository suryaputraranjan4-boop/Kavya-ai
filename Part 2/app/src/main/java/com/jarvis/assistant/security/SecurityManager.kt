package com.jarvis.assistant.security

import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * ╔══════════════════════════════════════════════════════════════╗
 * ║           JARVIS ASSISTANT — SECURITY MODULE                 ║
 * ║   Original Developer  : Tech GPTx                           ║
 * ║   Copyright © 2026    : Tech GPTx. All Rights Reserved.     ║
 * ║   GitHub              : https://github.com/techgptx/Playlist ║
 * ║   Content Creators    : Must give 100% shoutout to Tech GPTx ║
 * ╚══════════════════════════════════════════════════════════════╝
 *
 * Anti-Clone System:
 *  - Verifies the app's package name is the original one
 *  - Verifies the APK signing certificate at runtime
 *  - Blocks tampered / cloned APKs from running
 *
 * TAMCL v1.0 License: Anyone who uses this code to create content
 * MUST give 100% shoutout / credit to "Tech GPTx" in their video.
 */
object SecurityManager {

    // ── Developer Identity ────────────────────────────────────────
    const val DEVELOPER_NAME    = "Tech GPTx"
    const val COPYRIGHT_NOTICE  = "Copyright © 2026 Tech GPTx. All Rights Reserved."
    const val GITHUB_REPO       = "https://github.com/techgptx/Playlist"
    const val LICENSE_NAME      = "TAMCL v1.0 — Content creators MUST credit Tech GPTx"

    // ── Anti-Clone: expected package name ────────────────────────
    private const val EXPECTED_PACKAGE = "com.jarvis.assistant"

    /**
     * Call this once inside Application.onCreate() or MainActivity.onCreate()
     * before any other initialisation.
     */
    fun verify(context: Context) {
        checkPackageName(context)
        // Note: To enable APK Signature Lock (Feature 2), generate a .jks key,
        // sign your APK, compute its SHA-256 fingerprint, paste it below and
        // uncomment checkSignature(). See README for full guide.
        // checkSignature(context)
    }

    // ── Package-name anti-clone check ────────────────────────────
    private fun checkPackageName(context: Context) {
        if (context.packageName != EXPECTED_PACKAGE) {
            showTamperAlert(
                context,
                "Clone Detected",
                "This is an unauthorised clone of the JARVIS app by $DEVELOPER_NAME.\n\n" +
                "Original: $EXPECTED_PACKAGE\n" +
                "Found   : ${context.packageName}\n\n" +
                "The app will now close. Please download the official version from:\n$GITHUB_REPO"
            )
        }
    }

    // ── APK Signature Lock (optional — enable after signing) ─────
    /**
     * To enable:
     *  1. Generate your keystore:  keytool -genkey -v -keystore techgptx.jks ...
     *  2. Sign your APK with that keystore.
     *  3. Get the SHA-256 of your signing cert and paste it in EXPECTED_SHA256.
     *  4. Uncomment the call to checkSignature() in verify() above.
     */
    @Suppress("unused")
    private fun checkSignature(context: Context) {
        val EXPECTED_SHA256 = "PASTE_YOUR_APK_SHA256_FINGERPRINT_HERE"

        val actual = getApkSignatureSha256(context) ?: run {
            showTamperAlert(context, "Verification Failed", "Unable to verify app authenticity. Closing.")
            return
        }
        if (!actual.equals(EXPECTED_SHA256, ignoreCase = true)) {
            showTamperAlert(
                context,
                "Tampered APK Detected",
                "This APK has been re-signed or modified.\n\n" +
                "$DEVELOPER_NAME only distributes APKs signed with the original certificate.\n\n" +
                "Download the official APK from:\n$GITHUB_REPO"
            )
        }
    }

    private fun getApkSignatureSha256(context: Context): String? {
        return try {
            val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = context.packageManager.getPackageInfo(
                    context.packageName, PackageManager.GET_SIGNING_CERTIFICATES
                )
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageInfo(
                    context.packageName, PackageManager.GET_SIGNATURES
                )
                info.signatures
            }
            val firstSig = sigs?.firstOrNull() ?: return null
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(firstSig.toByteArray())
            digest.joinToString(":") { "%02X".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    // ── Alert + force-quit ───────────────────────────────────────
    private fun showTamperAlert(context: Context, title: String, message: String) {
        try {
            AlertDialog.Builder(context)
                .setTitle("⚠️ $title")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("OK") { _, _ -> exitProcess(1) }
                .show()
        } catch (_: Exception) {
            exitProcess(1)
        }
    }
}
