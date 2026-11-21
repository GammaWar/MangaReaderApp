# Keep OkHttp / Jsoup / Coil rules clean for R8 release APK builds
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jsoup.**
-keepattributes Signature,InnerClasses,EnclosingMethod
