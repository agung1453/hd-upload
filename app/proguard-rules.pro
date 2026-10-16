# Nimbus Browser — R8 / ProGuard rules
#
# The app is a single WebView shell, so the default optimized Android
# rules cover almost everything. Below are the few extra keeps that the
# AndroidX libraries and our own reflection-free code need.

# --- WebView JS bridges / keep JavaScript-facing API -----------------
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# --- ViewModels / Settings ------------------------------------------
-keep class * extends androidx.lifecycle.ViewModel { <init>(); }
-keep class * extends androidx.preference.Preference { <init>(...); }

# --- SQLite + coroutines --------------------------------------------
-dontwarn kotlinx.coroutines.**

# --- Silence warnings that are irrelevant for a WebView app ----------
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**

# Keep line numbers so crash reports stay readable, hide the source file.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile