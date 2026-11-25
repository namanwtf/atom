package atom.general.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import atom.general.R
import atom.general.language.AppLanguage
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.feature.FeatureNav
import java.util.Locale

@Composable
internal fun LanguageScreen(nav: FeatureNav) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(AppLanguage.current(context)) }

    LargeTitleScreen(title = stringResource(R.string.language_title), onBack = nav::back) {
        item(key = "g-lang") {
            Group {
                GroupRow(
                    title = stringResource(R.string.language_follow_system),
                    subtitle = stringResource(R.string.language_follow_system_sub),
                    onClick = {
                        AppLanguage.set(context, null)
                        selected = null
                    },
                    trailing = { if (selected == null) CheckMark() },
                )
                AppLanguage.available.forEach { tag ->
                    GroupDivider(inset = 18.dp)
                    val locale = Locale.forLanguageTag(tag)
                    GroupRow(
                        title = locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) },
                        subtitle = locale.getDisplayName(Locale.getDefault()),
                        onClick = {
                            AppLanguage.set(context, tag)
                            selected = tag
                        },
                        trailing = { if (selected == tag) CheckMark() },
                    )
                }
            }
            FootNote(stringResource(R.string.language_note))
        }
    }
}

@Composable
private fun CheckMark() {
    Icon(Icons.Filled.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
}
