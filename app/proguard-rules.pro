# Proguard rules for Bkörkortsteori App

# Keep WebView JavaScript interface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Firebase Messaging Service
-keep class se.bkorkortsteori.app.BKFirebaseMessagingService { *; }

# Keep Kotlin metadata
-keep class kotlin.Metadata { *; }

# Don't warn about missing Firebase classes if google-services.json not yet added
-dontwarn com.google.firebase.**

# Keep WebView client callbacks
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String);
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public boolean *(android.webkit.WebView, java.lang.String);
}

-keepclassmembers class * extends android.webkit.WebChromeClient {
    public void *(android.webkit.WebView, java.lang.String, java.lang.String);
    public boolean *(android.webkit.WebView, java.lang.String);
}
