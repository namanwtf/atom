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
