# LiteRT-LM's AAR ships no consumer rules, but its native library calls back into these classes
# (callbacks, Message/Content JSON, tool reflection) by name. Keep them intact in minified apps.
-keep class com.google.ai.edge.litertlm.** { *; }
-keep interface com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
