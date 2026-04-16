# ProGuard rules for TG WS Proxy

# Keep serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class dev.minios.tgwsproxy.**$$serializer { *; }
-keepclassmembers class dev.minios.tgwsproxy.** {
    *** Companion;
}
-keepclasseswithmembers class dev.minios.tgwsproxy.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
