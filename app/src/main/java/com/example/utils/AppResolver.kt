package com.example.utils

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.util.Log
import com.example.services.KavyaAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Verified model representing an installed, launchable application on the Android device.
 */
data class InstalledApp(
    val appName: String,
    val packageName: String,
    val launcherActivity: String?,
    val normalizedName: String,
    val aliases: List<String> = emptyList(),
    val isSystemApp: Boolean = false
)

/**
 * Exact resolution confidence level.
 */
enum class MatchConfidence {
    EXACT,       // Exact app name match or verified official alias match
    HIGH,        // Normalized exact match or single unambiguous token match
    AMBIGUOUS,   // Multiple installed apps match (user MUST disambiguate)
    NONE         // No match found (REJECT - Never guess)
}

/**
 * Result of the deterministic app resolution.
 */
data class AppResolutionResult(
    val requestedName: String,
    val matchedApp: InstalledApp?,
    val candidateApps: List<InstalledApp> = emptyList(),
    val confidence: MatchConfidence,
    val reason: String,
    val isWebsiteRequest: Boolean = false
)

/**
 * Structured diagnostic record to audit:
 * "requested app == resolved app == foreground app"
 */
data class AppLaunchDiagnostic(
    val requestedApp: String,
    val resolvedApp: String,
    val packageName: String,
    val launchIntent: String,
    val confidence: String,
    val foregroundBefore: String,
    val foregroundAfter: String,
    val verification: String, // "PASS" or "FAIL - <Reason>"
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Deterministic Android App Launcher Engine for Kavya.
 *
 * Enforces the strict rule pipeline:
 * USER COMMAND
 * → extract exact requested app name
 * → query Android PackageManager for installed apps
 * → match exact app label / official alias
 * → obtain launchable Intent for THAT package
 * → launch
 * → verify foreground package
 * → only then report success.
 *
 * Absolute Constraints:
 * - Never guess an app.
 * - Never launch a similar or random app.
 * - Never invent package names.
 * - If resolution fails, returns NONE confidence so caller outputs "I couldn't find that app".
 * - If multiple match, returns AMBIGUOUS with candidate list so user picks.
 */
class AppResolver(private val context: Context) {

    companion object {
        private const val TAG = "KavyaAppResolver"
        private const val PREFS_ALIASES = "kavya_user_app_aliases"

        /**
         * Deterministic natural language stripper for English, Hindi, and Hinglish.
         */
        fun extractAppNameFromNaturalLanguage(input: String): String {
            var text = input.trim()
                .removeSurrounding("\"", "\"")
                .removeSurrounding("'", "'")
                .removeSurrounding("“", "”")
                .trim()

            val prefixRegex = listOf(
                "^can you please (open|launch|start|run)\\s+".toRegex(RegexOption.IGNORE_CASE),
                "^can you (open|launch|start|run)\\s+".toRegex(RegexOption.IGNORE_CASE),
                "^please (open|launch|start|run)\\s+".toRegex(RegexOption.IGNORE_CASE),
                "^(open|launch|start|run)\\s+the\\s+".toRegex(RegexOption.IGNORE_CASE),
                "^(open|launch|start|run)\\s+".toRegex(RegexOption.IGNORE_CASE),
                "^(khol do|khol|kholo|chalao|chalaye|chalado|start karo|start kar|open karo|open kar)\\s+".toRegex(RegexOption.IGNORE_CASE),
                "^(खोलो|खोलिए|खोल|चलाओ|चालू करो|चालू कर)\\s+".toRegex(RegexOption.IGNORE_CASE)
            )

            for (pattern in prefixRegex) {
                text = text.replace(pattern, "").trim()
            }

            val suffixRegex = listOf(
                "\\s+(khol do|khol|kholo|chalao|chalaye|chalado|start karo|start kar|open karo|open kar)$".toRegex(RegexOption.IGNORE_CASE),
                "\\s+(खोलो|खोलिए|खोल|चलाओ|चालू करो|चालू कर)$".toRegex(RegexOption.IGNORE_CASE),
                "\\s+(application|app|एप|ऐप)$".toRegex(RegexOption.IGNORE_CASE),
                "[.?!]+$".toRegex()
            )

            for (pattern in suffixRegex) {
                text = text.replace(pattern, "").trim()
            }

            return text
        }

        // Canonical mapping of common aliases in English, Hindi (Devanagari), and Hinglish
        // to either target package IDs or standardized app labels.
        private val OFFICIAL_ALIASES = mapOf(
            // Google / Search
            "google" to listOf("com.google.android.googlequicksearchbox", "com.android.chrome", "google", "chrome"),
            "google search" to listOf("com.google.android.googlequicksearchbox", "com.android.chrome", "google"),
            "गूगल" to listOf("com.google.android.googlequicksearchbox", "com.android.chrome", "google"),
            "गूगल सर्च" to listOf("com.google.android.googlequicksearchbox", "com.android.chrome", "google"),

            // YouTube
            "yt" to listOf("com.google.android.youtube", "youtube"),
            "youtube" to listOf("com.google.android.youtube", "youtube"),
            "यूट्यूब" to listOf("com.google.android.youtube", "youtube"),
            "युटुब" to listOf("com.google.android.youtube", "youtube"),

            // YouTube Music
            "yt music" to listOf("com.google.android.apps.youtube.music", "youtube music"),
            "youtube music" to listOf("com.google.android.apps.youtube.music", "youtube music"),
            "वाईटी म्यूजिक" to listOf("com.google.android.apps.youtube.music", "youtube music"),

            // WhatsApp
            "wa" to listOf("com.whatsapp", "com.whatsapp.w4b", "whatsapp"),
            "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b", "whatsapp"),
            "व्हाट्सएप" to listOf("com.whatsapp", "whatsapp"),
            "वाट्सएप" to listOf("com.whatsapp", "whatsapp"),

            // Instagram
            "ig" to listOf("com.instagram.android", "instagram"),
            "insta" to listOf("com.instagram.android", "instagram"),
            "instagram" to listOf("com.instagram.android", "instagram"),
            "इंस्टाग्राम" to listOf("com.instagram.android", "instagram"),
            "इंस्टा" to listOf("com.instagram.android", "instagram"),

            // Facebook
            "fb" to listOf("com.facebook.katana", "com.facebook.lite", "facebook"),
            "facebook" to listOf("com.facebook.katana", "com.facebook.lite", "facebook"),
            "फेसबुक" to listOf("com.facebook.katana", "com.facebook.lite", "facebook"),

            // Chrome & Browser
            "chrome" to listOf("com.android.chrome", "chrome", "google chrome"),
            "google chrome" to listOf("com.android.chrome", "chrome", "google chrome"),
            "browser" to listOf("com.android.chrome", "com.google.android.browser", "chrome", "browser"),
            "क्रोम" to listOf("com.android.chrome", "chrome"),
            "गूगल क्रोम" to listOf("com.android.chrome", "google chrome"),
            "ब्राउज़र" to listOf("com.android.chrome", "chrome", "browser"),

            // Spotify
            "spotify" to listOf("com.spotify.music", "spotify"),
            "स्पॉटिफ़ाई" to listOf("com.spotify.music", "spotify"),
            "स्पॉटीफाई" to listOf("com.spotify.music", "spotify"),

            // Camera
            "camera" to listOf("com.google.android.GoogleCamera", "com.android.camera", "com.android.camera2", "com.sec.android.app.camera", "camera"),
            "कैमरा" to listOf("com.google.android.GoogleCamera", "com.android.camera", "com.android.camera2", "camera"),
            "cam" to listOf("com.google.android.GoogleCamera", "com.android.camera", "camera"),

            // Settings
            "settings" to listOf("com.android.settings", "settings"),
            "setting" to listOf("com.android.settings", "settings"),
            "सेटिंग्स" to listOf("com.android.settings", "settings"),
            "सेटिंग" to listOf("com.android.settings", "settings"),

            // Calculator
            "calculator" to listOf("com.google.android.calculator", "com.android.calculator2", "com.sec.android.app.popupcalculator", "calculator"),
            "calc" to listOf("com.google.android.calculator", "com.android.calculator2", "calculator"),
            "कैलकुलेटर" to listOf("com.google.android.calculator", "calculator"),
            "हिसाब" to listOf("com.google.android.calculator", "calculator"),

            // Phone / Dialer
            "phone" to listOf("com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer", "phone"),
            "dialer" to listOf("com.google.android.dialer", "com.android.dialer", "phone"),
            "call" to listOf("com.google.android.dialer", "phone"),
            "फोन" to listOf("com.google.android.dialer", "phone"),
            "कॉल" to listOf("com.google.android.dialer", "phone"),

            // Messages / SMS
            "messages" to listOf("com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging", "messages"),
            "message" to listOf("com.google.android.apps.messaging", "messages"),
            "sms" to listOf("com.google.android.apps.messaging", "messages"),
            "मैसेज" to listOf("com.google.android.apps.messaging", "messages"),

            // Gmail
            "gmail" to listOf("com.google.android.gm", "gmail"),
            "mail" to listOf("com.google.android.gm", "gmail"),
            "जीमेल" to listOf("com.google.android.gm", "gmail"),
            "ईमेल" to listOf("com.google.android.gm", "gmail"),

            // Google Maps
            "maps" to listOf("com.google.android.apps.maps", "maps", "google maps"),
            "map" to listOf("com.google.android.apps.maps", "maps"),
            "google maps" to listOf("com.google.android.apps.maps", "maps", "google maps"),
            "मैप्स" to listOf("com.google.android.apps.maps", "maps"),
            "गूगल मैप्स" to listOf("com.google.android.apps.maps", "maps"),

            // Photos / Gallery
            "photos" to listOf("com.google.android.apps.photos", "com.android.gallery3d", "com.sec.android.gallery3d", "photos", "gallery"),
            "photo" to listOf("com.google.android.apps.photos", "photos"),
            "gallery" to listOf("com.google.android.apps.photos", "com.android.gallery3d", "com.sec.android.gallery3d", "gallery", "photos"),
            "फोटो" to listOf("com.google.android.apps.photos", "photos"),
            "गैलरी" to listOf("com.google.android.apps.photos", "gallery"),

            // Clock / Alarm
            "clock" to listOf("com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage", "clock"),
            "alarm" to listOf("com.google.android.deskclock", "com.android.deskclock", "clock"),
            "घड़ी" to listOf("com.google.android.deskclock", "clock"),
            "अलार्म" to listOf("com.google.android.deskclock", "clock"),

            // Contacts
            "contacts" to listOf("com.google.android.contacts", "com.android.contacts", "contacts"),
            "contact" to listOf("com.google.android.contacts", "contacts"),
            "कांटेक्ट" to listOf("com.google.android.contacts", "contacts"),

            // Calendar
            "calendar" to listOf("com.google.android.calendar", "com.android.calendar", "com.samsung.android.calendar", "calendar"),
            "कैलेंडर" to listOf("com.google.android.calendar", "com.android.calendar", "calendar"),
            "पंचांग" to listOf("com.google.android.calendar", "calendar"),

            // Free Fire / Games
            "free fire" to listOf("com.dts.freefireth", "com.dts.freefiremax", "free fire", "free fire max"),
            "free fire max" to listOf("com.dts.freefiremax", "com.dts.freefireth", "free fire max", "free fire"),
            "freefire" to listOf("com.dts.freefireth", "com.dts.freefiremax", "free fire"),
            "ff" to listOf("com.dts.freefireth", "com.dts.freefiremax", "free fire"),
            "फ्री फायर" to listOf("com.dts.freefireth", "com.dts.freefiremax", "free fire"),

            // Play Store
            "play store" to listOf("com.android.vending", "play store", "google play store"),
            "playstore" to listOf("com.android.vending", "play store"),
            "प्ले स्टोर" to listOf("com.android.vending", "play store"),

            // Twitter / X
            "twitter" to listOf("com.twitter.android", "twitter"),
            "x" to listOf("com.twitter.android", "twitter", "x"),
            "ट्विटर" to listOf("com.twitter.android", "twitter"),

            // Telegram
            "telegram" to listOf("org.telegram.messenger", "telegram"),
            "टेलीग्राम" to listOf("org.telegram.messenger", "telegram"),

            // Reddit
            "reddit" to listOf("com.reddit.frontpage", "reddit"),
            "रेडिट" to listOf("com.reddit.frontpage", "reddit"),

            // LinkedIn
            "linkedin" to listOf("com.linkedin.android", "linkedin"),
            "लिंक्डइन" to listOf("com.linkedin.android", "linkedin"),

            // Snapchat
            "snapchat" to listOf("com.snapchat.android", "snapchat"),
            "स्नैपचैट" to listOf("com.snapchat.android", "snapchat"),

            // Netflix
            "netflix" to listOf("com.netflix.mediaclient", "netflix"),
            "नेटफ्लिक्स" to listOf("com.netflix.mediaclient", "netflix"),

            // Amazon & Shopping
            "amazon" to listOf("in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping", "amazon"),
            "अमेज़न" to listOf("in.amazon.mShop.android.shopping", "amazon"),
            "flipkart" to listOf("com.flipkart.android", "flipkart"),
            "फ्लिपकार्ट" to listOf("com.flipkart.android", "flipkart"),

            // Payments
            "paytm" to listOf("net.one97.paytm", "paytm"),
            "पेटीएम" to listOf("net.one97.paytm", "paytm"),
            "phonepe" to listOf("com.phonepe.app", "phonepe"),
            "फोनपे" to listOf("com.phonepe.app", "phonepe"),
            "gpay" to listOf("com.google.android.apps.nbu.paisa.user", "gpay", "google pay"),
            "google pay" to listOf("com.google.android.apps.nbu.paisa.user", "google pay"),
            "गूगल पे" to listOf("com.google.android.apps.nbu.paisa.user", "google pay"),

            // ChatGPT
            "chatgpt" to listOf("com.openai.chatgpt", "chatgpt"),
            "चैटजीपीटी" to listOf("com.openai.chatgpt", "chatgpt")
        )
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_ALIASES, Context.MODE_PRIVATE)

    @Volatile
    private var installedAppIndex: List<InstalledApp> = emptyList()
    private var lastIndexTimestamp: Long = 0

    init {
        try {
            refreshIndex()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing app index: ${e.message}")
        }
    }

    fun setInstalledAppsForTesting(apps: List<InstalledApp>) {
        installedAppIndex = apps
        lastIndexTimestamp = System.currentTimeMillis()
    }

    /**
     * Queries the Android PackageManager for all currently installed launchable activities.
     */
    @Synchronized
    fun refreshIndex(): List<InstalledApp> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos: List<ResolveInfo> = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainIntent, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying package manager: ${e.message}")
            emptyList()
        }

        val appList = mutableListOf<InstalledApp>()
        val seenPackages = mutableSetOf<String>()

        for (info in resolveInfos) {
            val packageName = info.activityInfo?.packageName ?: continue
            if (seenPackages.contains(packageName)) continue
            seenPackages.add(packageName)

            val appLabel = try {
                info.loadLabel(pm)?.toString()?.trim() ?: packageName
            } catch (_: Exception) {
                packageName
            }
            val activityName = info.activityInfo.name
            val normalized = normalizeString(appLabel)
            val isSystem = (info.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            appList.add(
                InstalledApp(
                    appName = appLabel,
                    packageName = packageName,
                    launcherActivity = activityName,
                    normalizedName = normalized,
                    aliases = getAliasesForPackage(packageName, appLabel),
                    isSystemApp = isSystem
                )
            )
        }

        // Ensure Settings app is indexed even if launcher intent query varies by OEM/ROM
        if (appList.none { it.packageName == "com.android.settings" }) {
            try {
                val settingsAppInfo = pm.getApplicationInfo("com.android.settings", 0)
                val label = pm.getApplicationLabel(settingsAppInfo).toString().trim()
                appList.add(
                    InstalledApp(
                        appName = if (label.isNotBlank()) label else "Settings",
                        packageName = "com.android.settings",
                        launcherActivity = "com.android.settings.Settings",
                        normalizedName = "settings",
                        aliases = listOf("settings", "सेटिंग्स", "setting"),
                        isSystemApp = true
                    )
                )
            } catch (_: Exception) {}
        }

        if (appList.isNotEmpty()) {
            installedAppIndex = appList
        }
        lastIndexTimestamp = System.currentTimeMillis()
        Log.d(TAG, "Indexed ${installedAppIndex.size} launchable installed applications.")
        return installedAppIndex
    }

    fun getInstalledApps(): List<InstalledApp> {
        if (System.currentTimeMillis() - lastIndexTimestamp > 60_000) {
            CoroutineScope(Dispatchers.IO).launch {
                refreshIndex()
            }
        }
        return installedAppIndex
    }

    /**
     * Deterministic resolution flow:
     * Extract exact app name -> Query installed apps -> Priority match.
     * NEVER guesses an unrelated app.
     */
    fun resolve(rawInput: String): AppResolutionResult {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return AppResolutionResult(
                requestedName = rawInput,
                matchedApp = null,
                confidence = MatchConfidence.NONE,
                reason = "Empty app name provided."
            )
        }

        // Detect explicit website requests
        if (isWebsiteIntent(trimmed)) {
            return AppResolutionResult(
                requestedName = trimmed,
                matchedApp = null,
                confidence = MatchConfidence.NONE,
                reason = "Website request detected.",
                isWebsiteRequest = true
            )
        }

        val cleanedTarget = extractAppNameFromNaturalLanguage(trimmed)
        val normalizedQuery = normalizeString(cleanedTarget)
        val apps = getInstalledApps()

        Log.d(TAG, "Resolving app: raw='$rawInput' -> cleaned='$cleanedTarget' -> normalized='$normalizedQuery'")

        // 1. Exact case-sensitive match on App Label
        val exactMatches = apps.filter { it.appName == cleanedTarget }
        if (exactMatches.size == 1) {
            return AppResolutionResult(
                requestedName = cleanedTarget,
                matchedApp = exactMatches.first(),
                confidence = MatchConfidence.EXACT,
                reason = "Exact case-sensitive label match: '${exactMatches.first().appName}'"
            )
        }

        // 2. Case-insensitive exact match on App Label
        val caseInsensitiveMatches = apps.filter { it.appName.equals(cleanedTarget, ignoreCase = true) }
        if (caseInsensitiveMatches.size == 1) {
            return AppResolutionResult(
                requestedName = cleanedTarget,
                matchedApp = caseInsensitiveMatches.first(),
                confidence = MatchConfidence.EXACT,
                reason = "Case-insensitive label match: '${caseInsensitiveMatches.first().appName}'"
            )
        } else if (caseInsensitiveMatches.size > 1) {
            return AppResolutionResult(
                requestedName = cleanedTarget,
                matchedApp = null,
                candidateApps = caseInsensitiveMatches,
                confidence = MatchConfidence.AMBIGUOUS,
                reason = "Multiple installed applications have the label '$cleanedTarget'."
            )
        }

        // 3. User-defined alias preference (e.g. user selected YouTube Music previously for 'music')
        val userSavedPackage = prefs.getString(normalizedQuery, null)
        if (userSavedPackage != null) {
            val userApp = apps.find { it.packageName == userSavedPackage }
            if (userApp != null) {
                return AppResolutionResult(
                    requestedName = cleanedTarget,
                    matchedApp = userApp,
                    confidence = MatchConfidence.EXACT,
                    reason = "User-saved alias '$normalizedQuery' -> ${userApp.appName}"
                )
            }
        }

        // 4. Official Multilingual / Short Alias Match
        val officialTargets = OFFICIAL_ALIASES[normalizedQuery]
        if (officialTargets != null) {
            val aliasMatches = mutableListOf<InstalledApp>()
            for (target in officialTargets) {
                val matched = apps.find { it.packageName == target || it.normalizedName == target }
                if (matched != null && !aliasMatches.contains(matched)) {
                    aliasMatches.add(matched)
                }
            }

            if (aliasMatches.size == 1) {
                return AppResolutionResult(
                    requestedName = cleanedTarget,
                    matchedApp = aliasMatches.first(),
                    confidence = MatchConfidence.EXACT,
                    reason = "Official alias '$normalizedQuery' matched installed '${aliasMatches.first().appName}'"
                )
            } else if (aliasMatches.size > 1) {
                return AppResolutionResult(
                    requestedName = cleanedTarget,
                    matchedApp = null,
                    candidateApps = aliasMatches,
                    confidence = MatchConfidence.AMBIGUOUS,
                    reason = "Official alias '$normalizedQuery' matches multiple installed apps."
                )
            }
        }

        // 5. Normalized exact match (e.g. "Google Chrome" -> "google chrome")
        val normalizedExact = apps.filter { it.normalizedName == normalizedQuery }
        if (normalizedExact.size == 1) {
            return AppResolutionResult(
                requestedName = cleanedTarget,
                matchedApp = normalizedExact.first(),
                confidence = MatchConfidence.HIGH,
                reason = "Normalized label match '${normalizedExact.first().appName}'"
            )
        }

        // 6. Check for exact full-word matches or prefix matches in installed apps
        // (e.g. If user asks for "YouTube" and only "YouTube" is installed, exact match took care of it.
        // If user asks for "YouTube", and "YouTube" & "YouTube Music" are both installed, distinguish strictly.)
        val wordBoundaryMatches = apps.filter { app ->
            val words = app.normalizedName.split("\\s+".toRegex())
            words.contains(normalizedQuery) || app.normalizedName.startsWith(normalizedQuery)
        }

        if (wordBoundaryMatches.size == 1) {
            return AppResolutionResult(
                requestedName = cleanedTarget,
                matchedApp = wordBoundaryMatches.first(),
                confidence = MatchConfidence.HIGH,
                reason = "Single unambiguous match '${wordBoundaryMatches.first().appName}'"
            )
        } else if (wordBoundaryMatches.size > 1) {
            // Check if one of them is an exact normalized match
            val exactInGroup = wordBoundaryMatches.find { it.normalizedName == normalizedQuery }
            if (exactInGroup != null) {
                return AppResolutionResult(
                    requestedName = cleanedTarget,
                    matchedApp = exactInGroup,
                    confidence = MatchConfidence.EXACT,
                    reason = "Exact match in group: '${exactInGroup.appName}'"
                )
            }

            // Otherwise, it is ambiguous: Ask the user. NEVER guess randomly!
            return AppResolutionResult(
                requestedName = cleanedTarget,
                matchedApp = null,
                candidateApps = wordBoundaryMatches,
                confidence = MatchConfidence.AMBIGUOUS,
                reason = "Multiple installed applications matched '$cleanedTarget': ${wordBoundaryMatches.map { it.appName }}."
            )
        }

        // 7. No match found on this device -> Strict REJECTION. Never guess an unrelated app.
        return AppResolutionResult(
            requestedName = cleanedTarget,
            matchedApp = null,
            confidence = MatchConfidence.NONE,
            reason = "No installed application found for '$cleanedTarget'."
        )
    }

    /**
     * Validates package existence, enabled state, and retrieves the verified launch Intent.
     */
    fun validateAndPrepareLaunch(app: InstalledApp): Intent? {
        val pm = context.packageManager
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(app.packageName, 0)
            }

            if (packageInfo.applicationInfo == null || !packageInfo.applicationInfo!!.enabled) {
                Log.e(TAG, "Application ${app.packageName} is disabled or unavailable.")
                return null
            }

            val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
            if (launchIntent == null) {
                Log.e(TAG, "No launch intent found for ${app.packageName}")
                return null
            }

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launchIntent
        } catch (e: Exception) {
            Log.e(TAG, "Validation failed for ${app.packageName}: ${e.message}")
            null
        }
    }

    fun saveUserAlias(alias: String, packageName: String) {
        val normalized = normalizeString(alias)
        if (normalized.isNotBlank()) {
            prefs.edit().putString(normalized, packageName).apply()
        }
    }

    /**
     * Deterministic natural language stripper for English, Hindi, and Hinglish.
     */
    fun extractAppNameFromNaturalLanguage(input: String): String {
        return Companion.extractAppNameFromNaturalLanguage(input)
    }

    data class CompoundCommand(
        val appName: String,
        val searchQuery: String,
        val actionType: String = "SEARCH" // "SEARCH", "PLAY", "TYPE"
    )

    fun parseCompoundCommand(input: String): CompoundCommand? {
        val trimmed = input.trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        // If the command is a multi-step task (e.g. "open youtube, search hi, and open the second video"),
        // yield to TaskPlanner / AndroidAgent so all steps execute in proper Observe-Decide-Execute sequence.
        if (lower.contains("second video") || lower.contains("2nd video") ||
            lower.contains("third video") || lower.contains("3rd video") ||
            lower.contains("dusra video") || lower.contains("doosra video") ||
            lower.contains("teesra video") || lower.contains("first video") ||
            lower.contains("second website") || lower.contains("2nd website") ||
            lower.contains("third website") || lower.contains("3rd website") ||
            lower.contains("dusri website") || lower.contains("teesri website") ||
            lower.contains("second result") || lower.contains("2nd result") ||
            lower.contains("third result") || lower.contains("3rd result") ||
            lower.contains("second link") || lower.contains("2nd link") ||
            lower.contains(", and open") || lower.contains(", then open") ||
            lower.contains(" aur dusra") || lower.contains(" aur teesra") ||
            lower.contains(" and open the") || lower.contains("2nd or third")
        ) {
            return null
        }

        // Pattern 1: "open youtube and search hey", "launch spotify and play coldplay"
        val openAndSearchRegex = "^(?:open|launch|start)\\s+(.+?)\\s+and\\s+(?:search(?:\\s+for)?|find|look\\s+for)\\s+(.+)$".toRegex(RegexOption.IGNORE_CASE)
        openAndSearchRegex.find(trimmed)?.let { match ->
            val cleanQuery = com.example.agent.StructuredActionParser.sanitizeQuery(match.groupValues[2].trim())
            return CompoundCommand(appName = match.groupValues[1].trim(), searchQuery = cleanQuery, actionType = "SEARCH")
        }

        val openAndPlayRegex = "^(?:open|launch|start)\\s+(.+?)\\s+and\\s+(?:play|listen\\s+to)\\s+(.+)$".toRegex(RegexOption.IGNORE_CASE)
        openAndPlayRegex.find(trimmed)?.let { match ->
            return CompoundCommand(appName = match.groupValues[1].trim(), searchQuery = match.groupValues[2].trim(), actionType = "PLAY")
        }

        val openAndTypeRegex = "^(?:open|launch|start)\\s+(.+?)\\s+and\\s+(?:type|write|enter)\\s+(.+)$".toRegex(RegexOption.IGNORE_CASE)
        openAndTypeRegex.find(trimmed)?.let { match ->
            return CompoundCommand(appName = match.groupValues[1].trim(), searchQuery = match.groupValues[2].trim(), actionType = "TYPE")
        }

        // Pattern 2: "search hey on youtube", "play shape of you on spotify"
        val searchOnRegex = "^(?:search|find|look\\s+up)\\s+(.+?)\\s+on\\s+(.+)$".toRegex(RegexOption.IGNORE_CASE)
        searchOnRegex.find(trimmed)?.let { match ->
            return CompoundCommand(appName = match.groupValues[2].trim(), searchQuery = match.groupValues[1].trim(), actionType = "SEARCH")
        }

        val playOnRegex = "^(?:play|listen\\s+to)\\s+(.+?)\\s+on\\s+(.+)$".toRegex(RegexOption.IGNORE_CASE)
        playOnRegex.find(trimmed)?.let { match ->
            return CompoundCommand(appName = match.groupValues[2].trim(), searchQuery = match.groupValues[1].trim(), actionType = "PLAY")
        }

        // Pattern 3: Hinglish/Hindi: "youtube me hey search karo", "youtube par comedy chalao", "google ke andar wikipedia kholo"
        val hinglishSearchRegex = "^(.+?)\\s+(?:me|par|pe|mein|ke\\s+andar|पर|पे|में|के\\s+अंदर)\\s+(.+?)\\s+(?:search\\s+karo|search\\s+khol|khol\\s+do|chalao|dhoondo|kholo|open\\s+karo|सर्च\\s+करो|चलाओ|ढूंढो|खोलो|search\\s+करो|open\\s+करो|search\\s+kar)$".toRegex(RegexOption.IGNORE_CASE)
        hinglishSearchRegex.find(trimmed)?.let { match ->
            val app = match.groupValues[1].trim()
            val query = match.groupValues[2].trim()
            return CompoundCommand(appName = app, searchQuery = query, actionType = "SEARCH")
        }

        // Pattern 4: "google par search karo xyz", "youtube pe chalao xyz"
        val hinglishPrefixRegex = "^(.+?)\\s+(?:me|par|pe|mein|ke\\s+andar|पर|पे|में|के\\s+अंदर)\\s+(?:search\\s+karo|search\\s+kar|dhoondo|chalao|play\\s+karo|kholo|सर्च\\s+करो|ढूंढो|चलाओ|प्ले\\s+करो|खोलो|search\\s+करो|open\\s+करो)\\s+(.+)$".toRegex(RegexOption.IGNORE_CASE)
        hinglishPrefixRegex.find(trimmed)?.let { match ->
            val app = match.groupValues[1].trim()
            val query = match.groupValues[2].trim()
            return CompoundCommand(appName = app, searchQuery = query, actionType = "SEARCH")
        }

        // Pattern 5: "open website xyz in google/chrome" or "open xyz in google/youtube/instagram"
        val openInAppRegex = "^(?:open|launch|kholo|खोलो)\\s+(?:website\\s+|site\\s+)?(.+?)\\s+(?:in|on|inside|ke\\s+andar|पर|पे|में|के\\s+अंदर)\\s+(.+)$".toRegex(RegexOption.IGNORE_CASE)
        openInAppRegex.find(trimmed)?.let { match ->
            val query = match.groupValues[1].trim()
            val app = match.groupValues[2].trim()
            return CompoundCommand(appName = app, searchQuery = query, actionType = "SEARCH")
        }

        // Pattern 6: Direct search queries without specifying an app: "search for cats", "search weather in Mumbai", "google latest news"
        val directSearchRegex = "^(?:search(?:\\s+for)?|look\\s+up|google|find)\\s+(.+)$".toRegex(RegexOption.IGNORE_CASE)
        directSearchRegex.find(trimmed)?.let { match ->
            val query = match.groupValues[1].trim()
            if (query.isNotBlank() && !query.startsWith("on ") && !query.startsWith("in ")) {
                return CompoundCommand(appName = "Google", searchQuery = query, actionType = "SEARCH")
            }
        }

        // Pattern 7: Hindi/Hinglish direct search: "search karo xyz", "dhoondo xyz", "xyz search karo"
        val hinglishDirectSearchRegex = "^(?:search\\s+karo|dhoondo|khojo|सर्च\\s+करो|ढूंढो|खोजो)\\s+(.+)$".toRegex(RegexOption.IGNORE_CASE)
        hinglishDirectSearchRegex.find(trimmed)?.let { match ->
            val query = match.groupValues[1].trim()
            return CompoundCommand(appName = "Google", searchQuery = query, actionType = "SEARCH")
        }

        val hinglishSuffixSearchRegex = "^(.+?)\\s+(?:search\\s+karo|dhoondo|khojo|सर्च\\s+करो|ढूंढो|खोजो)$".toRegex(RegexOption.IGNORE_CASE)
        hinglishSuffixSearchRegex.find(trimmed)?.let { match ->
            val query = match.groupValues[1].trim()
            return CompoundCommand(appName = "Google", searchQuery = query, actionType = "SEARCH")
        }

        // Pattern 8: "khol kar search karo": "youtube khol kar gana search karo", "youtube khol ke cats dhoondo"
        val hindiKholKarRegex = "^(?:open|kholo|खोलो)?\\s*(.+?)\\s+(?:khol\\s+kar|kholke|khol\\s+ke|open\\s+karke|open\\s+kar)\\s+(?:usme\\s+)?(.+?)\\s+(?:search\\s+karo|search\\s+kar|chalao|play\\s+karo|dhoondo|सर्च\\s+करो|चलाओ|ढूंढो)$".toRegex(RegexOption.IGNORE_CASE)
        hindiKholKarRegex.find(trimmed)?.let { match ->
            val app = match.groupValues[1].trim()
            val query = match.groupValues[2].trim()
            return CompoundCommand(appName = app, searchQuery = query, actionType = "SEARCH")
        }

        // Pattern 9: "kholo aur search karo": "youtube kholo aur billiyan search karo"
        val hindiAurSearchRegex = "^(?:open|launch|kholo|खोलो)?\\s*(.+?)\\s+(?:kholo\\s+aur|open\\s+karo\\s+aur)\\s+(?:search(?:\\s+karo)?|find|dhoondo|play\\s+karo)?\\s*(.+)$".toRegex(RegexOption.IGNORE_CASE)
        hindiAurSearchRegex.find(trimmed)?.let { match ->
            val app = match.groupValues[1].trim()
            val query = match.groupValues[2].trim()
            return CompoundCommand(appName = app, searchQuery = query, actionType = "SEARCH")
        }

        return null
    }

    fun isDirectAppLaunchQuery(input: String): Boolean {
        val trimmed = input.trim().lowercase(Locale.ROOT)
        if (trimmed.isEmpty()) return false

        // If it's an ordinal website / video / result selection, do not treat as app launch
        val isOrdinalSelection = trimmed.contains("website") || trimmed.contains("result") ||
                trimmed.contains("video") || trimmed.contains("link") ||
                trimmed.contains("2nd") || trimmed.contains("3rd") || trimmed.contains("1st") ||
                trimmed.contains("second") || trimmed.contains("third") || trimmed.contains("first") ||
                trimmed.contains("dusra") || trimmed.contains("dusri") || trimmed.contains("teesra") || trimmed.contains("teesri")
        if (isOrdinalSelection && (trimmed.contains("website") || trimmed.contains("result") || trimmed.contains("video") || trimmed.contains("link") || trimmed.contains("or third"))) {
            return false
        }

        // If it's a compound command like "open youtube and search hey", do not treat as plain app launch
        if (parseCompoundCommand(input) != null) return false

        // Check common patterns
        if (trimmed.startsWith("open ") || trimmed.startsWith("launch ") ||
            trimmed.startsWith("start ") || trimmed.startsWith("run ") ||
            trimmed.endsWith(" kholo") || trimmed.endsWith(" khol do") ||
            trimmed.endsWith(" open karo") || trimmed.endsWith(" open kar") ||
            trimmed.endsWith(" chalao") || trimmed.endsWith(" chalaye") ||
            trimmed.endsWith(" खोलो") || trimmed.endsWith(" चालू करो") ||
            trimmed.startsWith("खोलो ") || trimmed.startsWith("चालू करो ")
        ) {
            return true
        }

        // Direct app name match
        val extracted = extractAppNameFromNaturalLanguage(input)
        val normalizedExtracted = normalizeString(extracted)
        return OFFICIAL_ALIASES.containsKey(normalizedExtracted) ||
               installedAppIndex.any { it.normalizedName == normalizedExtracted }
    }

    fun isWebsiteIntent(input: String): Boolean {
        val lower = input.lowercase(Locale.ROOT)
        return lower.contains(" website") ||
               lower.contains(" site") ||
               lower.contains(".com") ||
               lower.contains(".org") ||
               lower.contains(".net") ||
               lower.contains("in browser") ||
               lower.contains("web page") ||
               lower.startsWith("http://") ||
               lower.startsWith("https://") ||
               lower.startsWith("www.")
    }

    fun normalizeString(input: String): String {
        return input.lowercase(Locale.ROOT)
            .replace("[^a-z0-9\\s\u0900-\u097F]".toRegex(), "") // Alpha-numeric + Devanagari Unicode
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun getAliasesForPackage(packageName: String, appName: String): List<String> {
        val list = mutableListOf<String>()
        list.add(appName)
        val normalized = normalizeString(appName)
        OFFICIAL_ALIASES.forEach { (alias, targets) ->
            if (targets.contains(packageName) || targets.contains(normalized)) {
                list.add(alias)
            }
        }
        return list.distinct()
    }
}
