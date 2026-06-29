# -------------------------------------------------------------------------
# InstaGet ProGuard / R8 rules
# -------------------------------------------------------------------------

# Strip debug/verbose logs from release builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Keep stack trace line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# -------------------------------------------------------------------------
# Kotlin
# -------------------------------------------------------------------------
-keepclassmembers class **$WhenMappings { <fields>; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# -------------------------------------------------------------------------
# Room (database entities, DAOs, and database class)
# -------------------------------------------------------------------------
-keep class com.instaget.downloader.data.db.** { *; }
-keepclassmembers @androidx.room.Entity class * { *; }

# -------------------------------------------------------------------------
# Gson + Retrofit (network models with @SerializedName)
# -------------------------------------------------------------------------
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.instaget.downloader.network.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn com.google.gson.**

# Retrofit
-keep class retrofit2.** { *; }
-keepattributes Exceptions
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

# -------------------------------------------------------------------------
# Glide
# -------------------------------------------------------------------------
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-dontwarn com.bumptech.glide.**

# -------------------------------------------------------------------------
# Google Mobile Ads (AdMob)
# -------------------------------------------------------------------------
-keep class com.google.android.gms.ads.** { *; }
-keep class com.google.ads.** { *; }
-dontwarn com.google.android.gms.ads.**

# -------------------------------------------------------------------------
# Google Play Billing
# -------------------------------------------------------------------------
-keep class com.android.billingclient.** { *; }
-dontwarn com.android.billingclient.**

# -------------------------------------------------------------------------
# WorkManager
# -------------------------------------------------------------------------
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keepclassmembers class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-dontwarn androidx.work.**

# -------------------------------------------------------------------------
# Navigation Component
# -------------------------------------------------------------------------
-keep class androidx.navigation.** { *; }
-dontwarn androidx.navigation.**

# -------------------------------------------------------------------------
# ViewBinding
# -------------------------------------------------------------------------
-keep class * extends androidx.viewbinding.ViewBinding { *; }

# -------------------------------------------------------------------------
# WebView (Terms/Privacy WebViewActivity)
# -------------------------------------------------------------------------
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public boolean *(android.webkit.WebView, java.lang.String);
}

# -------------------------------------------------------------------------
# App Activities
# -------------------------------------------------------------------------
-keep class com.instaget.downloader.MainActivity { *; }
-keep class com.instaget.downloader.SplashActivity { *; }
-keep class com.instaget.downloader.WelcomeActivity { *; }
-keep class com.instaget.downloader.FullScreenViewerActivity { *; }

# -------------------------------------------------------------------------
# Coroutines
# -------------------------------------------------------------------------
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-dontwarn kotlinx.coroutines.**
