# Porcupine
-keep class ai.picovoice.** { *; }

# Retrofit / OkHttp
-keepattributes Signature
-keepattributes Exceptions
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.jarvis.app.llm.models.** { *; }

# Google Play Services
-keep class com.google.android.gms.** { *; }
