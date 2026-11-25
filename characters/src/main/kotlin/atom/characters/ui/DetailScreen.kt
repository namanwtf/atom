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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.pluralStringResource
import atom.characters.data.CharacterNotes
import atom.ui.components.ArrowRow
import atom.ui.components.AtomDialog
import atom.ui.components.AtomRow
import atom.ui.components.AtomTextButton
import atom.ui.components.SwitchRow
import top.yukonga.miuix.kmp.theme.MiuixTheme
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
    var reading by remember { mutableStateOf<String?>(null) }
    var showNotes by remember { mutableStateOf(false) }

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
    val notes = remember(c) { CharacterNotes.compatibility(c) }

    LargeTitleScreen(title = stringResource(R.string.characters_details_title), onBack = nav::back) {
        // ---------------- hero: avatar with "Change avatar", name, "By <creator>", Favorite
        item {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CharacterArt(c, Modifier.size(112.dp), corner = 56.dp)
                AtomTextButton(stringResource(R.string.characters_change_avatar), {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
                Text(c.displayName, fontSize = MiuixTheme.textStyles.title3.fontSize, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
                val meta = listOfNotNull(
                    c.creator.ifBlank { null }?.let { stringResource(R.string.characters_by, it) },
                    c.version.ifBlank { null }?.let { "v$it" },
                    if (c.origin == CharacterOrigin.BuiltIn) stringResource(R.string.characters_builtin) else null,
                ).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                if (c.tags.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        c.tags.take(4).forEach { Pill(it, MaterialTheme.colorScheme.surfaceContainerHigh) }
                    }
                }
                AtomTextButton(
                    stringResource(if (c.favourite) R.string.characters_unfavourite else R.string.characters_favourite),
                    { scope.launch { store.setFavourite(id, !c.favourite) } },
                    primary = !c.favourite,
                )
            }
        }

        // ---------------- greeting chooser
        item { SectionLabel(stringResource(R.string.characters_choose_greeting)) }
        item {
            Group {
                if (greetings.isEmpty()) {
                    AtomRow(title = stringResource(R.string.characters_no_greeting))
                }
                greetings.forEachIndexed { i, text ->
                    val shown = Macros.apply(text, c.displayName, personaName)
                    GreetingRow(
                        title = if (i == 0) stringResource(R.string.characters_greeting_default) else stringResource(R.string.characters_greeting_alt, i),
                        text = shown,
                        selected = greeting == i,
                        onClick = { greeting = i },
                        onReadFull = { reading = shown },
                    )
                }
            }
        }

        // ---------------- start chat
        item {
            PrimaryButton(
                text = stringResource(R.string.characters_start_chat),
                enabled = !starting,
                modifier = Modifier.padding(top = 16.dp),
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

        // ---------------- persona
        item {
            Group(Modifier.padding(top = 16.dp)) {
                SwitchRow(
                    title = stringResource(R.string.characters_use_persona),
                    checked = usePersona && personaReady,
                    onCheckedChange = { if (personaReady) usePersona = it else nav.open(CharacterRoutes.PERSONA) },
                    summary = if (personaReady) stringResource(R.string.characters_use_persona_subtitle, persona.name.ifBlank { PromptAssembler.DEFAULT_USER_NAME }) else stringResource(R.string.characters_use_persona_unset),
                )
                ArrowRow(stringResource(R.string.characters_edit_persona), { nav.open(CharacterRoutes.PERSONA) })
            }
        }

        // ---------------- profile
        val profile = Macros.apply(c.profile, c.displayName, personaName).trim()
        if (profile.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.characters_profile_section)) }
            item { Group { Box(Modifier.padding(16.dp)) { ExpandableText(profile) } } }
        }
        val creatorNotes = c.creatorNotes.trim()
        if (creatorNotes.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.characters_creator_notes)) }
            item { Group { Box(Modifier.padding(16.dp)) { ExpandableText(creatorNotes) } } }
        }

        // ---------------- manage
        item { SectionLabel(stringResource(R.string.characters_manage)) }
        item {
            Group {
                ArrowRow(stringResource(R.string.characters_edit_character), { nav.open(CharacterRoutes.edit(id)) })
                ArrowRow(
                    stringResource(R.string.characters_story_memory),
                    { nav.open(CharacterRoutes.story(id)) },
                    summary = story.trim().lineSequence().lastOrNull { it.isNotBlank() }?.take(80) ?: stringResource(R.string.characters_story_memory_empty),
                )
                val entries = c.worldbook?.entries?.size ?: 0
                if (entries > 0) {
                    ArrowRow(stringResource(R.string.characters_worldbook), { nav.open(CharacterRoutes.worldbook(id)) }, summary = stringResource(R.string.characters_worldbook_entries, entries))
                }
                ArrowRow(stringResource(R.string.characters_duplicate_character), {
                    scope.launch { store.duplicate(id)?.let { nav.open(CharacterRoutes.detail(it.id)) } }
                })
                if (c.image != null) {
                    ArrowRow(stringResource(R.string.characters_remove_image), { scope.launch { store.setImage(id, null) } })
                }
                ArrowRow(stringResource(R.string.characters_export_png_card), { exportPng.launch(fileName(c, "png")) })
                ArrowRow(stringResource(R.string.characters_export_json_card), { exportJson.launch(fileName(c, "json")) })
                ArrowRow(
                    stringResource(R.string.characters_compat_notes),
                    { showNotes = true },
                    summary = if (notes.isEmpty()) stringResource(R.string.characters_compat_none) else pluralStringResource(R.plurals.characters_compat_count, notes.size, notes.size),
                )
                GroupRow(stringResource(R.string.characters_delete_character), titleColor = MiuixTheme.colorScheme.error, onClick = { confirmDelete = true })
            }
        }
    }

    reading?.let { text ->
        AtomDialog(show = true, onDismiss = { reading = null }, title = stringResource(R.string.characters_read_full)) {
            Column {
                Text(
                    text,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                )
                AtomTextButton(stringResource(R.string.characters_got_it), { reading = null }, Modifier.fillMaxWidth().padding(top = 12.dp), primary = true)
            }
        }
    }

    if (showNotes) {
        AtomDialog(
            show = true,
            onDismiss = { showNotes = false },
            title = stringResource(R.string.characters_compat_notes),
            summary = if (notes.isEmpty()) stringResource(R.string.characters_compat_none_body) else null,
        ) {
            Column {
                if (notes.isNotEmpty()) {
                    Text(
                        notes.joinToString("\n\n"),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    )
                }
                AtomTextButton(stringResource(R.string.characters_got_it), { showNotes = false }, Modifier.fillMaxWidth().padding(top = 12.dp), primary = true)
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

/** One greeting: tap to choose it (a check marks the chosen one); "Read full text" opens all of it. */
@Composable
private fun GreetingRow(title: String, text: String, selected: Boolean, onClick: () -> Unit, onReadFull: () -> Unit) {
    val c = MiuixTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = MiuixTheme.textStyles.headline1.fontSize, color = c.onSurface)
            Text(text, style = MiuixTheme.textStyles.body2, color = c.onSurfaceVariantSummary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.characters_read_full),
                style = MiuixTheme.textStyles.body2,
                color = c.primary,
                modifier = Modifier.clickable(onClick = onReadFull).padding(vertical = 2.dp),
            )
        }
        Box(Modifier.padding(start = 12.dp).size(24.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Filled.Check, null, Modifier.size(20.dp), tint = c.primary)
        }
    }
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
