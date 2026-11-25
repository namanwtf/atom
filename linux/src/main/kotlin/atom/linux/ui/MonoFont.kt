package atom.linux.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import atom.linux.R

/**
 * JetBrains Mono (SIL Open Font License 1.1, licenses/OFL-1.1.txt), Regular and Bold, bundled as font resources
 * of this module. Used by the terminal; public so other monospace text (code blocks) can share it.
 */
object MonoFont {
    val JetBrainsMono: FontFamily = FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
    )
}
