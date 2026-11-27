package atom.app

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The launcher icon lives under new resource names (atom_launcher, atom_launcher_round), so a launcher or
 * notification shade that cached the old app's icon by resource name or id misses its cache. Legacy bitmaps sit next
 * to the adaptive icon for shades that read those.
 */
class LauncherIconTest {
    private val res = File("src/main/res")
    private val densities = mapOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192)

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
    fun legacyBitmapsExistAtEveryDensity() {
        for ((d, px) in densities) {
            for (name in listOf("atom_launcher", "atom_launcher_round")) {
                val f = File(res, "mipmap-$d/$name.png")
                assertTrue(f.isFile, f.path)
                val img = ImageIO.read(f)
                assertEquals(px, img.width, f.path)
                assertEquals(px, img.height, f.path)
                if (name == "atom_launcher") {
                    // Square on black, edge to edge.
                    assertEquals(0xFF000000.toInt(), img.getRGB(0, 0), f.path)
                } else {
                    // Clipped to a circle: the corners are transparent, the middle is not.
                    assertEquals(0, img.getRGB(0, 0) ushr 24, f.path)
                    assertEquals(0xFF, img.getRGB(px / 2, px / 2) ushr 24, f.path)
                }
            }
        }
    }
}
