package atom.general

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import atom.general.appearance.AppearanceBackup
import atom.general.ui.AboutScreen
import atom.general.ui.AppearanceScreen
import atom.general.ui.BackupScreen
import atom.general.ui.GeneralIcons
import atom.general.ui.LanguageScreen
import atom.ui.feature.AtomFeature
import atom.ui.feature.Backups
import atom.ui.feature.FeatureEntry
import atom.ui.feature.FeatureNav
import atom.ui.feature.FeatureSection

/** Settings > General: Appearance & theme, Data backup, Language, About. No tools, no background work. */
object GeneralFeature : AtomFeature {
    override val id: String = "general"

    const val APPEARANCE = "general/appearance"
    const val BACKUP = "general/backup"
    const val LANGUAGE = "general/language"
    const val ABOUT = "general/about"

    override fun onAppCreate(context: Context) {
        Backups.register(AppearanceBackup(context))
    }

    override val entries: List<FeatureEntry>
        get() = listOf(
            FeatureEntry(FeatureSection.General, R.string.general_entry_appearance, R.string.general_entry_appearance_sub, GeneralIcons.Palette, APPEARANCE),
            FeatureEntry(FeatureSection.General, R.string.general_entry_language, R.string.general_entry_language_sub, GeneralIcons.Language, LANGUAGE),
            FeatureEntry(FeatureSection.General, R.string.general_entry_backup, R.string.general_entry_backup_sub, GeneralIcons.Backup, BACKUP),
            FeatureEntry(FeatureSection.General, R.string.general_entry_about, R.string.general_entry_about_sub, GeneralIcons.Info, ABOUT),
        )

    @Composable
    override fun Screen(route: String, nav: FeatureNav) {
        when (route) {
            APPEARANCE -> AppearanceScreen(nav)
            BACKUP -> BackupScreen(nav)
            LANGUAGE -> LanguageScreen(nav)
            ABOUT -> AboutScreen(nav)
            else -> LaunchedEffect(route) { nav.back() }
        }
    }
}
