# The system binds the accessibility service by the class name in the manifest.
-keep class atom.device.AtomAccessibilityService { <init>(); }

# Shizuku: provider is instantiated by name; newProcess is private in API 13 and reached by reflection.
-keep class rikka.shizuku.ShizukuProvider { <init>(); }
-keepclassmembers class rikka.shizuku.Shizuku {
    private static rikka.shizuku.ShizukuRemoteProcess newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}
-keep class rikka.shizuku.ShizukuRemoteProcess { *; }
-keep class moe.shizuku.server.** { *; }
