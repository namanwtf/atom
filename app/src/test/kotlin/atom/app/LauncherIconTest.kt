package atom.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The launcher icon lives under new resource names (atom_launcher, atom_launcher_round), so a launcher or
 * notification shade that cached the old app's icon by resource name or id misses its cache. With minSdk 30 the
 * adaptive icon in mipmap-anydpi always wins, so there are no density bitmaps of it (they would never be used).
 */
class LauncherIconTest {
    private val res = File("src/main/res")

    @Test
    fun theManifestUsesTheNewNames() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue("android:icon=\"@mipmap/atom_launcher\"" in manifest, "android:icon")
        assertTrue("android:roundIcon=\"@mipmap/atom_launcher_round\"" in manifest, "android:roundIcon")
        val sources = File("src/main").walkTopDown().filter { it.isFile && (it.extension == "xml" || it.extension == "kt") }
        for (f in sources) {
            val text = f.readText()
            assertFalse(Regex("""@mipmap/ic_launcher(_round)?\b(?!_)|R\.mipmap\.ic_launcher(_round)?\b(?!_)""").containsMatchIn(text), "old launcher name in ${f.path}")
        }
        assertFalse(File(res, "mipmap-anydpi/ic_launcher.xml").exists())
        assertFalse(File(res, "mipmap-anydpi/ic_launcher_round.xml").exists())
    }

    @Test
    fun theAdaptiveIconsHaveNoMonochromeLayer() {
        for (name in listOf("atom_launcher", "atom_launcher_round")) {
            val xml = File(res, "mipmap-anydpi/$name.xml").readText()
            assertTrue("<adaptive-icon" in xml && "@mipmap/ic_launcher_fg" in xml, name)
            assertFalse("monochrome" in xml, name)
        }
    }

    @Test
    fun noUnusedDensityBitmaps() {
        val dirs = res.listFiles { f -> f.isDirectory && f.name.startsWith("mipmap") }.orEmpty().map { it.name }
        assertEquals(listOf("mipmap-anydpi"), dirs)
    }
}
