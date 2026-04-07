# AI Pass SDK ProGuard Rules

# Keep AiPassSDK public API and inner sealed classes
-keep class one.aipass.AiPassSDK { *; }
-keep class one.aipass.AiPassSDK$* { *; }

# Keep OAuth2 domain models
-keep class one.aipass.domain.** { *; }

# Keep OAuth2 result classes (sealed classes)
-keep class one.aipass.domain.OAuth2Result { *; }
-keep class one.aipass.domain.OAuth2Result$* { *; }
-keep class one.aipass.domain.OAuth2ValidationResult { *; }
-keep class one.aipass.domain.OAuth2ValidationResult$* { *; }
-keep class one.aipass.domain.OAuth2RevocationResult { *; }
-keep class one.aipass.domain.OAuth2RevocationResult$* { *; }

# Keep callback activity
-keep class one.aipass.ui.OAuth2CallbackActivity { *; }

# Keep ALL API service interfaces (Retrofit)
-keep interface one.aipass.data.OAuth2ApiService { *; }
-keep interface one.aipass.data.AiPassCompletionApiService { *; }
-keep interface one.aipass.data.AiPassAudioApiService { *; }

# Keep ALL API data models (request/response classes)
-keep class one.aipass.data.** { *; }
-keepclassmembers class one.aipass.data.** {
    <fields>;
    <init>(...);
    public <methods>;
}

# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# OkHttp
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**

# Gson - Critical for API serialization
-keep class com.google.gson.** { *; }
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.stream.** { *; }

# CRITICAL: Keep @SerializedName fields for all data classes
-keepclassmembers class one.aipass.data.** {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep TypeToken and reflection for complex types
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Kotlin
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**

# Android Security Crypto
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**
