package atom.app.ui.settings

import atom.ui.components.ScreenIcons

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import atom.app.R
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.theme.extras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bundled from the repository at build time (see `bundleLegalFiles` in app/build.gradle.kts). */
private data class LegalDoc(@StringRes val title: Int, @StringRes val subtitle: Int, val asset: String)

private val DOCS = listOf(
    LegalDoc(R.string.legal_notice, R.string.legal_notice_desc, "legal/NOTICE"),
    LegalDoc(R.string.legal_gpl, R.string.legal_gpl_desc, "legal/LICENSE-GPL-3.0"),
    LegalDoc(R.string.legal_apache, R.string.legal_apache_desc, "legal/LICENSE-Apache-2.0"),
    LegalDoc(R.string.legal_mit, R.string.legal_mit_desc, "legal/LICENSE-MIT-llama.cpp"),
    LegalDoc(R.string.legal_gpl2, R.string.legal_gpl2_desc, "legal/LICENSE-GPL-2.0"),
    LegalDoc(R.string.legal_lgpl3, R.string.legal_lgpl3_desc, "legal/LICENSE-LGPL-3.0"),
)

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    LargeTitleScreen(title = stringResource(R.string.screen_licenses), onBack = onBack) {
        item(key = "docs") {
            Group(Modifier.padding(top = 8.dp)) {
                DOCS.forEachIndexed { i, doc ->
                    if (i > 0) GroupDivider()
                    LegalRow(doc, initiallyOpen = i == 0)
                }
            }
        }
    }
}

@Composable
private fun LegalRow(doc: LegalDoc, initiallyOpen: Boolean) {
    val context = LocalContext.current
    var open by rememberSaveable(doc.asset) { mutableStateOf(initiallyOpen) }
    var text by remember(doc.asset) { mutableStateOf<String?>(null) }
    val turn by animateFloatAsState(if (open) 90f else 0f, tween(200), label = "chevron")

    // License texts are tens of KB; read only when a row is opened.
    LaunchedEffect(open) {
        if (open && text == null) {
            text = withContext(Dispatchers.IO) {
                runCatching { context.assets.open(doc.asset).bufferedReader().use { it.readText() } }.getOrNull()
            } ?: ""
        }
    }

    GroupRow(
        title = stringResource(doc.title),
        subtitle = stringResource(doc.subtitle),
        icon = ScreenIcons.Document,
        onClick = { open = !open },
        trailing = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp).rotate(turn),
            )
        },
    )
    AnimatedVisibility(
        visible = open,
        enter = fadeIn(tween(200)) + expandVertically(tween(220)),
        exit = fadeOut(tween(150)) + shrinkVertically(tween(200)),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .heightIn(min = 64.dp),
        ) {
            val body = text
            if (body == null) {
                CircularProgressIndicator(
                    Modifier.size(18.dp).align(Alignment.Center),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    body,
                    style = MaterialTheme.extras.mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    softWrap = false,
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(14.dp),
                )
            }
        }
    }
}
