# kotlinx.serialization: keep the generated serializers and companions of the app's persistence DTOs
# (conversations, settings selection) so release builds can still read files written by debug builds.
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**

-keep,includedescriptorclasses class atom.app.**$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class atom.app.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class atom.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class atom.app.data.** { *; }
-keepnames class atom.app.data.EngineSelection$*

# The optional on-device AI pack (:ondevice-pack) is loaded at run time and calls these classes by their real
# names: Atom's core interfaces and data types, and the Kotlin and kotlinx libraries they expose. Keep them whole
# and unrenamed, so the pack's code links against them (app/src/main/kotlin/atom/app/ondevice/OnDevicePack.kt).
-keep class atom.core.** { *; }
-keep interface atom.core.** { *; }
-keep class kotlin.** { *; }
-keep interface kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep interface kotlinx.coroutines.** { *; }
-keep class kotlinx.serialization.** { *; }
-keep interface kotlinx.serialization.** { *; }
-keepattributes InnerClasses, Signature, EnclosingMethod, Exceptions
-dontwarn kotlin.**
-dontwarn kotlinx.**
