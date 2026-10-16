# Readle ProGuard / R8 rules.
# Room, Compose and AndroidX ship their own consumer rules.

# The EPUB reader exposes this class to WebView JavaScript by name.
-keepclassmembers class com.readle.app.reader.reflow.ReaderJsBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# ---- size / optimisation ------------------------------------------------------------
-allowaccessmodification
-repackageclasses ''
-optimizationpasses 5

# Strip debug/verbose logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# Kotlin null-check helpers are pure overhead once the app is compiled.
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNull(...);
    public static void checkNotNullParameter(...);
    public static void checkNotNullExpressionValue(...);
    public static void checkParameterIsNotNull(...);
    public static void checkExpressionValueIsNotNull(...);
    public static void checkReturnedValueIsNotNull(...);
    public static void checkFieldIsNotNull(...);
}

# Keep readable stack traces without shipping source names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Libraries referenced only by annotation processors / optional integrations.
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
