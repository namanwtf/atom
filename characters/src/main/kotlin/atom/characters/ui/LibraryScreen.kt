package atom.characters.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import atom.characters.R
import atom.characters.card.CardException
import atom.characters.data.CharacterStore
import atom.characters.model.Character
import atom.characters.session.CharacterSession
import atom.ui.components.CircleIconButton
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.RowChevron
import atom.ui.components.RowValue
import atom.ui.components.SectionLabel
import atom.ui.components.SegmentedControl
import atom.ui.feature.FeatureNav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class LibraryFilter { All, Favourites, Mine }

@Composable
internal fun LibraryScreen(nav: FeatureNav) {
    val store = CharacterSession.store
    LaunchedEffect(Unit) { store.load() }
    val all by store.characters.collectAsState()
    val persona by store.persona.collectAsState()
    val loaded by store.loaded.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val message = try {
                    val bytes = readBytes(context, uri) ?: throw CardException(context.getString(R.string.characters_image_failed))
                    val c = store.importCard(bytes)
                    nav.open(CharacterRoutes.detail(c.id))
                    context.getString(R.string.characters_imported, c.displayName)
                } catch (e: CardException) {
                    context.getString(R.string.characters_import_failed, e.message.orEmpty())
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val mode = LibraryFilter.entries[filter]
    val base = when (mode) {
        LibraryFilter.All -> all
        LibraryFilter.Favourites -> all.filter { it.favourite }
        LibraryFilter.Mine -> all.filter { it.isMine }
    }
    val results = CharacterStore.search(base, query)

    LargeTitleScreen(
        title = stringResource(R.string.characters_title),
        onBack = nav::back,
        actions = {
            CircleIconButton(CharactersScreens.ImportIcon, stringResource(R.string.characters_import), {
                importer.launch(arrayOf("image/png", "application/json", "text/plain", "application/octet-stream"))
            })
            Spacer(Modifier.size(8.dp))
            CircleIconButton(Icons.Filled.Add, stringResource(R.string.characters_create), { nav.open(CharacterRoutes.edit(CharacterRoutes.NEW)) }, modifier = Modifier.padding(end = 8.dp))
        },
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SearchField(query, { query = it }, stringResource(R.string.characters_search))
                SegmentedControl(
                    options = listOf(
                        stringResource(R.string.characters_filter_all) to null,
                        stringResource(R.string.characters_filter_favourites) to null,
                        stringResource(R.string.characters_filter_mine) to null,
                    ),
                    selected = filter,
                    onSelect = { filter = it },
                )
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Group {
                GroupRow(
                    title = stringResource(R.string.characters_my_persona),
                    icon = Icons.Filled.Person,
                    onClick = { nav.open(CharacterRoutes.PERSONA) },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RowValue(persona.name.ifBlank { stringResource(R.string.characters_my_persona_empty) })
                            RowChevron()
                        }
                    },
                )
                if (loaded && !store.hasDefault) {
                    GroupDivider()
                    GroupRow(
                        title = stringResource(R.string.characters_restore_default),
                        subtitle = stringResource(R.string.characters_restore_default_subtitle),
                        icon = Icons.Filled.Refresh,
                        onClick = {
                            scope.launch {
                                val c = store.restoreDefault()
                                Toast.makeText(context, context.getString(R.string.characters_restored, c.displayName), Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }
        }

        val sections: List<Pair<Int, List<Character>>> = if (query.isBlank() && mode == LibraryFilter.All) {
            listOf(
                R.string.characters_section_favourites to results.filter { it.favourite },
                R.string.characters_section_mine to results.filter { it.isMine },
                R.string.characters_section_imported to results.filter { !it.isMine },
            )
        } else {
            listOf(R.string.characters_section_results to results)
        }
        if (loaded && results.isEmpty()) {
            item {
                Text(
                    stringResource(if (query.isBlank()) R.string.characters_empty else R.string.characters_empty_search),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 18.dp, top = 28.dp),
                )
            }
        }
        sections.filter { it.second.isNotEmpty() }.forEach { (label, list) ->
            cardGrid(label, list, onOpen = { nav.open(CharacterRoutes.detail(it.id)) }, onFavourite = { c ->
                scope.launch { store.setFavourite(c.id, !c.favourite) }
            })
        }
    }
}

private fun LazyListScope.cardGrid(label: Int, list: List<Character>, onOpen: (Character) -> Unit, onFavourite: (Character) -> Unit) {
    item(key = "label-$label") { SectionLabel(androidx.compose.ui.res.stringResource(label)) }
    items(list.chunked(2), key = { row -> "$label-" + row.joinToString { it.id } }) { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { c -> CharacterCard(c, Modifier.weight(1f), { onOpen(c) }, { onFavourite(c) }) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun CharacterCard(c: Character, modifier: Modifier, onClick: () -> Unit, onFavourite: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        Box {
            CharacterArt(c, Modifier.fillMaxWidth().aspectRatio(0.75f))
            Surface(
                onClick = onFavourite,
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(34.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (c.favourite) Icons.Filled.Favorite else CharactersScreens.FavouriteOutline,
                        stringResource(if (c.favourite) R.string.characters_unfavourite else R.string.characters_favourite),
                        Modifier.size(18.dp),
                        tint = Color.White,
                    )
                }
            }
        }
        Text(
            c.displayName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
        val sub = c.tags.take(3).joinToString(" · ")
        if (sub.isNotEmpty()) {
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
    }
}

internal suspend fun readBytes(context: Context, uri: Uri, limit: Int = CharacterStore.MAX_CARD_BYTES): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) throw CardException("file too large")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }.getOrElse { if (it is CardException) throw it else null }
}
