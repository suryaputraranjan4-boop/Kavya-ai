# Networking & crypto compatibility warnings
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn okhttp3.**
-dontwarn retrofit2.**

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.**$$serializer { *; }
-keepclassmembers class com.example.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# JNI native bridge methods and DTOs returned from native code
-keep class com.example.ai.offline.LlamaCppEngine { *; }
-keep class com.example.ai.offline.LlamaCppEngine$* { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# WebView JavascriptInterface bridges (including anonymous/inner bridge classes)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
-keep class com.example.vision.VisionWebViewHost$* {
    @android.webkit.JavascriptInterface <methods>;
}

# Room Database entities, DAOs, and generated implementations
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.paging.**

# On-device ML runtimes (ONNX Runtime, LiteRT-LM, MediaPipe, ML Kit)
-keep class ai.onnxruntime.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.mediapipe.** { *; }
-keep class com.google.mlkit.** { *; }

# Preserve source file & line numbers for crash diagnostics
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
