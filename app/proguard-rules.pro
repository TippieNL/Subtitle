# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class nl.tippie.subtitle.** {
    *** Companion;
}
-keepclasseswithmembers class nl.tippie.subtitle.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
# Room
-keep class * extends androidx.room.RoomDatabase { <init>(); }
