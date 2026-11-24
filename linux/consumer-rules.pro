# JNI entry points of libatom_pty.so are looked up by name.
-keepclasseswithmembernames class atom.linux.pty.Pty {
    native <methods>;
}
