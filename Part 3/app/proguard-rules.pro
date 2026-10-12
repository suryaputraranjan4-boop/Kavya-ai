# Proguard Rules for JARVIS Assistant

# Keep Gson model classes
-keep class com.jarvis.assistant.data.model.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Keep JavascriptInterfaces for WebViews (HUD, Settings, & Floating Orbs)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Vosk offline wake word and JNA native libraries
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keep class net.java.dev.jna.** { *; }

# Keep Room database entities & DAOs
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep class com.jarvis.assistant.data.memory.** { *; }

# ── Tech GPTx Security Module — keep rules ───────────────────────────────────
# Keep SecurityManager and DeveloperInfo visible at runtime (needed for checks)
-keep class com.jarvis.assistant.security.SecurityManager { *; }
-keep class com.jarvis.assistant.security.DeveloperInfo { *; }

# Obfuscate everything else aggressively
-optimizationpasses 5
-allowaccessmodification
-repackageclasses 'tgptx'

# Remove Log calls in release builds (security: don't leak internals)
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
