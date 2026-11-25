# LSPosed instantiates the entry class named in META-INF/xposed/java_init.list and calls its lifecycle methods,
# which override classes that only exist inside the framework (compileOnly). Hookers override
# XposedInterface.Hooker#intercept. Keep the whole (small) package unrenamed so none of that is stripped.
-keep class atom.xposed.** { *; }
-adaptresourcefilecontents META-INF/xposed/java_init.list
-dontwarn io.github.libxposed.api.**
-dontwarn io.github.libxposed.annotation.**
