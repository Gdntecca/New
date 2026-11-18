# Keep the author credit constant in the binary.
-keep class com.superdownloader.app.Credits { *; }
-keepclassmembers class com.superdownloader.app.browser.BlobBridge { @android.webkit.JavascriptInterface <methods>; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
