# JNI: libatom_llama.so binds to these names.
-keepclasseswithmembernames,includedescriptorclasses class atom.engine.llamacpp.LlamaNative {
    native <methods>;
}
-keep class atom.engine.llamacpp.LlamaNative { *; }
# The native token loop calls TokenSink.onToken([B)Z by name on whatever implements it.
-keep interface atom.engine.llamacpp.TokenSink { *; }
-keepclassmembers class * implements atom.engine.llamacpp.TokenSink {
    boolean onToken(byte[]);
}
