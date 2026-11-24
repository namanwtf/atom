@file:OptIn(ExperimentalMaterial3Api::class)

package atom.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import atom.app.R
import atom.app.ui.common.AtomCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bundled from the repository at build time (see `bundleLegalFiles` in app/build.gradle.kts). */
private data class LegalDoc(@StringRes val title: Int, @StringRes val subtitle: Int, val asset: String)

private val DOCS = listOf(
    LegalDoc(R.string.legal_notice, R.string.legal_notice_desc, "legal/NOTICE"),
    LegalDoc(R.string.legal_gpl, R.string.legal_gpl_desc, "legal/LICENSE-GPL-3.0"),
    LegalDoc(R.string.legal_apache, R.string.legal_apache_desc, "legal/LICENSE-Apache-2.0"),
    LegalDoc(R.string.legal_mit, R.string.legal_mit_desc, "legal/LICENSE-MIT-llama.cpp"),
)

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_licenses)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(DOCS) { doc -> LegalCard(doc, initiallyOpen = doc === DOCS.first()) }
        }
    }
}

@Composable
private fun LegalCard(doc: LegalDoc, initiallyOpen: Boolean) {
    val context = LocalContext.current
    var open by rememberSaveable(doc.asset) { mutableStateOf(initiallyOpen) }
    var text by remember(doc.asset) { mutableStateOf<String?>(null) }

    // License texts are tens of KB; read only when a card is opened.
    LaunchedEffect(open) {
        if (open && text == null) {
            text = withContext(Dispatchers.IO) {
                runCatching { context.assets.open(doc.asset).bufferedReader().use { it.readText() } }.getOrNull()
            }
        }
    }

    AtomCard(Modifier.animateContentSize()) {
        Column(
            Modifier.fillMaxWidth().clickable { open = !open },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(doc.title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(doc.subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (open) {
            Text(
                text ?: "",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                softWrap = false,
                modifier = Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()),
            )
        }
    }
}
