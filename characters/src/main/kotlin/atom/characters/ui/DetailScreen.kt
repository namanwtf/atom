package atom.characters.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import atom.characters.R
import atom.characters.model.Character
import atom.characters.model.CharacterOrigin
import atom.characters.prompt.Macros
import atom.characters.prompt.PromptAssembler
import atom.characters.session.CharacterSession
import atom.ui.components.AtomCard
import atom.ui.components.AtomMenu
import atom.ui.components.AtomSwitch
import atom.ui.components.BottomActionBar
import atom.ui.components.CircleIconButton
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.Pill
import atom.ui.components.PrimaryButton
import atom.ui.components.RowChevron
import atom.ui.components.SectionLabel
import atom.ui.feature.FeatureNav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DetailScreen(id: String, nav: FeatureNav) {
    val store = CharacterSession.store
    LaunchedEffect(Unit) { store.load() }
    val all by store.characters.collectAsState()
    val loaded by store.loaded.collectAsState()
    val persona by store.persona.collectAsState()
    val c = all.firstOrNull { it.id == id }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var greeting by rememberSaveable(id) { mutableIntStateOf(0) }
    var usePersona by rememberSaveable(id) { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }

    val story by produceState("", id, c?.updatedAt) { value = store.storyMemory(id) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val png = CharacterImages.normalisedPng(context, uri)
                if (png == null) Toast.makeText(context, R.string.characters_image_failed, Toast.LENGTH_SHORT).show() else store.setImage(id, png)
            }
        }
    }
    val exportPng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) scope.launch { toastExport(context, writeTo(context, uri) { store.exportPng(id) { CharacterImages.placeholderPng(it) } }) }
    }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { toastExport(context, writeTo(context, uri) { store.exportJson(id)?.toByteArray() }) }
    }

    if (c == null) {
        LargeTitleScreen(title = "", onBack = nav::back) {
            if (loaded) {
                item {
                    Text(stringResource(R.string.characters_not_found), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(18.dp))
                }
            }
        }
        return
    }
    val greetings = c.greetings
    val personaName = PromptAssembler.userName(if (usePersona && !persona.isEmpty) persona else null)
    val personaReady = !persona.isEmpty

    LargeTitleScreen(
        title = c.displayName,
        onBack = nav::back,
        actions = {
            CircleIconButton(
                if (c.favourite) Icons.Filled.Favorite else CharactersScreens.FavouriteOutline,
                stringResource(if (c.favourite) R.string.characters_unfavourite else R.string.characters_favourite),
                { scope.launch { store.setFavourite(id, !c.favourite) } },
            )
            Spacer(Modifier.size(8.dp))
            CircleIconButton(Icons.Filled.Edit, stringResource(R.string.characters_edit), { nav.open(CharacterRoutes.edit(id)) })
            Spacer(Modifier.size(8.dp))
            Box(Modifier.padding(end = 8.dp)) {
                CircleIconButton(Icons.Filled.MoreVert, stringResource(R.string.characters_more), { menu = true })
                AtomMenu(expanded = menu, onDismiss = { menu = false }) {
                    MenuItem(stringResource(R.string.characters_duplicate)) {
                        menu = false
                        scope.launch { store.duplicate(id)?.let { nav.open(CharacterRoutes.detail(it.id)) } }
                    }
                    MenuItem(stringResource(R.string.characters_change_image)) {
                        menu = false
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    if (c.image != null) {
                        MenuItem(stringResource(R.string.characters_remove_image)) {
                            menu = false
                            scope.launch { store.setImage(id, null) }
                        }
                    }
                    MenuItem(stringResource(R.string.characters_export_png)) {
                        menu = false
                        exportPng.launch(fileName(c, "png"))
                    }
                    MenuItem(stringResource(R.string.characters_export_json)) {
                        menu = false
                        exportJson.launch(fileName(c, "json"))
                    }
                    MenuItem(stringResource(R.string.characters_delete), destructive = true) {
                        menu = false
                        confirmDelete = true
                    }
                }
            }
        },
        bottomBar = {
            BottomActionBar {
                PrimaryButton(
                    text = stringResource(R.string.characters_start_chat),
                    enabled = !starting,
                    onClick = {
                        starting = true
                        scope.launch {
                            val convo = CharacterSession.startChat(id, greeting, usePersona && personaReady)
                            starting = false
                            if (convo != null) nav.openChat(convo)
                        }
                    },
                )
            }
        },
    ) {
        item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CharacterArt(c, Modifier.widthIn(max = 360.dp).fillMaxWidth(0.72f).aspectRatio(0.75f), corner = 28.dp)
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val meta = listOfNotNull(
                    c.creator.ifBlank { null }?.let { stringResource(R.string.characters_by, it) },
                    c.version.ifBlank { null }?.let { "v$it" },
                    if (c.origin == CharacterOrigin.BuiltIn) stringResource(R.string.characters_builtin) else null,
                ).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (c.tags.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        c.tags.take(4).forEach { Pill(it, MaterialTheme.colorScheme.surfaceContainerHigh) }
                    }
                }
            }
        }

        item { SectionLabel(stringResource(R.string.characters_choose_greeting)) }
        item {
            if (greetings.isEmpty()) {
                AtomCard { Text(stringResource(R.string.characters_no_greeting), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                Group {
                    greetings.forEachIndexed { i, text ->
                        if (i > 0) GroupDivider(inset = 18.dp)
                        GreetingRow(
                            title = if (i == 0) stringResource(R.string.characters_greeting_default) else stringResource(R.string.characters_greeting_alt, i),
                            text = Macros.apply(text, c.displayName, personaName),
                            selected = greeting == i,
                            onClick = { greeting = i },
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(14.dp)) }
        item {
            Group {
                GroupRow(
                    title = stringResource(R.string.characters_use_persona),
                    subtitle = if (personaReady) stringResource(R.string.characters_use_persona_subtitle, persona.name.ifBlank { PromptAssembler.DEFAULT_USER_NAME }) else stringResource(R.string.characters_use_persona_unset),
                    onClick = { if (personaReady) usePersona = !usePersona else nav.open(CharacterRoutes.PERSONA) },
                    trailing = { AtomSwitch(checked = usePersona && personaReady, onCheckedChange = if (personaReady) ({ usePersona = it }) else null, enabled = personaReady) },
                )
                GroupDivider(inset = 18.dp)
                GroupRow(
                    title = stringResource(R.string.characters_story_memory),
                    subtitle = story.trim().lineSequence().lastOrNull { it.isNotBlank() }?.take(80) ?: stringResource(R.string.characters_story_memory_empty),
                    onClick = { nav.open(CharacterRoutes.story(id)) },
                    trailing = { RowChevron() },
                )
            }
        }

        val profile = Macros.apply(c.profile, c.displayName, personaName).trim()
        if (profile.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.characters_about)) }
            item { AtomCard { ExpandableText(profile) } }
        }
        val notes = c.creatorNotes.trim()
        if (notes.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.characters_creator_notes)) }
            item { AtomCard { ExpandableText(notes) } }
        }
        val entries = c.worldbook?.entries?.size ?: 0
        if (entries > 0) {
            item {
                Text(
                    stringResource(R.string.characters_worldbook_entries, entries),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 18.dp, top = 14.dp),
                )
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.characters_delete_title, c.displayName),
            body = stringResource(R.string.characters_delete_body),
            confirm = stringResource(R.string.characters_delete),
            dismiss = stringResource(R.string.characters_cancel),
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    store.delete(id)
                    nav.back()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun GreetingRow(title: String, text: String, selected: Boolean, onClick: () -> Unit) {
    GroupRow(
        title = title,
        subtitle = text,
        onClick = onClick,
        trailing = {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Filled.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
        },
    )
}

@Composable
private fun ExpandableText(text: String) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = if (expanded) Int.MAX_VALUE else 6,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clickable { expanded = !expanded },
    )
}

@Composable
internal fun MenuItem(text: String, destructive: Boolean = false, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (destructive) FontWeight.Medium else null,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        },
        onClick = onClick,
    )
}

private fun fileName(c: Character, ext: String): String =
    c.displayName.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "character" } + ".$ext"

private suspend fun writeTo(context: android.content.Context, uri: android.net.Uri, bytes: suspend () -> ByteArray?): Boolean {
    val data = runCatching { bytes() }.getOrNull() ?: return false
    return withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data) } != null }.getOrDefault(false)
    }
}

private fun toastExport(context: android.content.Context, ok: Boolean) {
    Toast.makeText(context, if (ok) R.string.characters_exported else R.string.characters_export_failed, Toast.LENGTH_SHORT).show()
}
