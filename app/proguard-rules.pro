# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** { *** Companion; *; }
-keepclasseswithmembers class ** { kotlinx.serialization.KSerializer serializer(...); }
# wireguard-go JNI
-keep class com.wireguard.android.backend.** { *; }
-dontwarn org.slf4j.**
