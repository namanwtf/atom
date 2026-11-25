package atom.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedFolderRulesTest {
    private val linux = "/data/user/0/app/files/linux"
    private fun check(name: String, path: String, existing: List<SharedFolder> = emptyList()) =
        SharedFolderRules.validate(SharedFolder(name, path), existing, linux)

    @Test
    fun acceptsNormalFolders() {
        assertNull(check("Download", "/storage/emulated/0/Download"))
        assertNull(check("my.proj-1_x", "/storage/emulated/0/Projects/a"))
    }

    @Test
    fun nameRules() {
        assertEquals(SharedFolderRules.Problem.BadName, check("", "/storage/emulated/0/a"))
        assertEquals(SharedFolderRules.Problem.BadName, check(".hidden", "/storage/emulated/0/a"))
        assertEquals(SharedFolderRules.Problem.BadName, check("has space", "/storage/emulated/0/a"))
        assertEquals(SharedFolderRules.Problem.BadName, check("a/b", "/storage/emulated/0/a"))
        assertEquals(SharedFolderRules.Problem.BadName, check("x".repeat(33), "/storage/emulated/0/a"))
    }

    @Test
    fun duplicatesAndLimit() {
        val existing = listOf(SharedFolder("Docs", "/storage/emulated/0/Documents"))
        assertEquals(SharedFolderRules.Problem.DuplicateName, check("docs", "/storage/emulated/0/Other", existing))
        assertEquals(SharedFolderRules.Problem.DuplicatePath, check("D2", "/storage/emulated/0//Documents/", existing))
        val full = (1..SharedFolderRules.MAX_FOLDERS).map { SharedFolder("f$it", "/storage/emulated/0/f$it") }
        assertEquals(SharedFolderRules.Problem.TooMany, check("one-more", "/storage/emulated/0/x", full))
    }

    @Test
    fun forbiddenAndRelativePaths() {
        assertEquals(SharedFolderRules.Problem.NotAbsolute, check("a", "storage/emulated/0"))
        assertEquals(SharedFolderRules.Problem.NotAbsolute, check("a", "/../.."))
        assertEquals(SharedFolderRules.Problem.Forbidden, check("a", "/"))
        assertEquals(SharedFolderRules.Problem.Forbidden, check("a", "/proc/self"))
        assertEquals(SharedFolderRules.Problem.Forbidden, check("a", "/storage/../data/data/com.bank"))
        assertEquals(SharedFolderRules.Problem.InsideLinux, check("a", "$linux/workspace"))
        assertEquals(SharedFolderRules.Problem.InsideLinux, check("a", "/data/user/0/app/files"))
    }

    @Test
    fun helpers() {
        assertEquals("/storage/emulated/0/Documents/Notes", SharedFolderRules.pathFromTreeUri("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FNotes"))
        assertEquals("/storage/1A2B-3C4D", SharedFolderRules.pathFromTreeUri("content://com.android.externalstorage.documents/tree/1A2B-3C4D%3A"))
        assertEquals("My_Photos", SharedFolderRules.suggestName("/storage/emulated/0/My Photos/"))
    }
}
