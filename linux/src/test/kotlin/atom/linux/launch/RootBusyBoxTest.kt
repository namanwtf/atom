package atom.linux.launch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RootBusyBoxTest {
    @Test
    fun looksInEveryRootManagerInOrder() {
        assertEquals(listOf("/data/adb/magisk", "/data/adb/ksu/bin", "/data/adb/ap/bin"), RootBusyBox.MANAGER_DIRS)
        val p = RootBusyBox.prelude("/data/user/0/app/cache/root-busybox", 10123)
        assertTrue(p.startsWith("for atom_bb_d in /data/adb/magisk /data/adb/ksu/bin /data/adb/ap/bin; do "))
        assertTrue("--install -s '/data/user/0/app/cache/root-busybox'" in p)
        assertTrue("chown -R 10123:10123 '/data/user/0/app/cache/root-busybox'" in p)
        // Appended, so the system's own tools keep priority.
        assertTrue("PATH=\"\$PATH:\$atom_bb_d:\"'/data/user/0/app/cache/root-busybox'" in p)
        assertTrue(p.endsWith("\n"))
    }

    @Test
    fun wrapsCommandsAndShells() {
        val dir = "/c/bb"
        assertTrue(RootBusyBox.wrap("ls /", dir, null).endsWith("\nls /"))
        assertTrue("chown" !in RootBusyBox.wrap("ls /", dir, null))
        assertTrue(RootBusyBox.interactive(dir, 1).endsWith("\nexec /system/bin/sh -i"))
        // Quoted, so a path with spaces or quotes stays one word.
        assertTrue("'/c/it'\\''s'" in RootBusyBox.prelude("/c/it's", null))
    }

    @Test
    fun pathBuilding() {
        assertEquals("/system/bin:/data/adb/magisk", RootBusyBox.pathWith("/system/bin", listOf("/data/adb/magisk")))
        assertEquals("/system/bin:/x", RootBusyBox.pathWith("/system/bin:/x", listOf("/x")))
        assertEquals("/a", RootBusyBox.pathWith("", listOf("/a")))
    }
}
