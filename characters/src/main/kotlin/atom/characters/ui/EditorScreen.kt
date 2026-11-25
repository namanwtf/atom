package atom.characters.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import atom.characters.R
import atom.characters.model.Character
import atom.characters.session.CharacterSession
import atom.ui.components.CompactButton
import atom.ui.components.Group
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.RowChevron
import atom.ui.components.RowValue
import atom.ui.components.ScreenIcons
import atom.ui.components.SecondaryButton
import atom.ui.components.SectionLabel
import atom.ui.feature.FeatureNav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface PendingImage {
    data object Unchanged : PendingImage
    data object Removed : PendingImage
    class Replaced(val png: ByteArray) : PendingImage
}

@Composable
internal fun EditorScreen(routeId: String, nav: FeatureNav) {
    val store = CharacterSession.store
    val loaded by store.loaded.collectAsState()
    val live by store.characters.collectAsState()
    LaunchedEffect(Unit) { store.load() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isNew = routeId == CharacterRoutes.NEW
    val fresh = remember { store.newCharacter() }

    var original by remember { mutableStateOf<Character?>(null) }
    var draft by remember { mutableStateOf<Character?>(null) }
    var tagsText by remember { mutableStateOf("") }
    var image by remember { mutableStateOf<PendingImage>(PendingImage.Unchanged) }
    var advanced by remember { mutableStateOf(false) }
    var showErrors by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(loaded) {
        if (loaded && draft == null) {
            val start = if (isNew) fresh else store.get(routeId)
            original = start
            draft = start
            tagsText = joinList(start?.tags.orEmpty())
            advanced = start != null && listOf(start.personality, start.scenario, start.exampleDialogue, start.systemPrompt, start.postHistoryInstructions).any { it.isNotBlank() }
        }
    }

    val d = draft
    val dirty = d != null && (d != original || tagsText != joinList(original?.tags.orEmpty()) || image != PendingImage.Unchanged)

    fun leave() {
        if (dirty) confirmDiscard = true else nav.back()
    }
    BackHandler(enabled = dirty) { confirmDiscard = true }

    suspend fun persist(): Character? {
        val current = draft ?: return null
        if (current.name.isBlank()) {
            showErrors = true
            return null
        }
        saving = true
        val edited = current.copy(tags = splitList(tagsText))
        var saved = store.update(edited.id) { latest ->
            edited.copy(worldbook = latest.worldbook, image = latest.image, imageVersion = latest.imageVersion, favourite = latest.favourite)
        } ?: store.save(edited)
        when (val img = image) {
            is PendingImage.Replaced -> saved = store.setImage(saved.id, img.png) ?: saved
            PendingImage.Removed -> saved = store.setImage(saved.id, null) ?: saved
            PendingImage.Unchanged -> Unit
        }
        original = saved
        draft = saved
        tagsText = joinList(saved.tags)
        image = PendingImage.Unchanged
        saving = false
        return saved
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val png = CharacterImages.normalisedPng(context, uri)
                if (png == null) Toast.makeText(context, R.string.characters_image_failed, Toast.LENGTH_SHORT).show() else image = PendingImage.Replaced(png)
            }
        }
    }

    LargeTitleScreen(
        title = if (isNew) stringResource(R.string.characters_new) else d?.displayName?.ifBlank { null } ?: stringResource(R.string.characters_edit),
        onBack = ::leave,
        actions = {
            CompactButton(
                text = stringResource(R.string.characters_save),
                enabled = d != null && !saving,
                onClick = {
                    scope.launch {
                        val saved = persist() ?: return@launch
                        nav.back()
                        if (isNew) nav.open(CharacterRoutes.detail(saved.id))
                    }
                },
                modifier = Modifier.padding(end = 12.dp),
            )
        },
    ) {
        if (d == null) return@LargeTitleScreen
        fun update(change: Character.() -> Character) {
            draft = draft?.change()
        }

        item { SectionLabel(stringResource(R.string.characters_image)) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.width(112.dp).aspectRatio(0.75f)) {
                    when (val img = image) {
                        is PendingImage.Replaced -> PendingPreview(img.png)
                        PendingImage.Removed -> CharacterArt(d.copy(image = null), Modifier.fillMaxSize(), corner = 18.dp)
                        PendingImage.Unchanged -> CharacterArt(d, Modifier.fillMaxSize(), corner = 18.dp)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(stringResource(R.string.characters_choose_image), {
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                    val hasImage = image is PendingImage.Replaced || (image == PendingImage.Unchanged && d.image != null)
                    if (hasImage) SecondaryButton(stringResource(R.string.characters_remove_image), { image = PendingImage.Removed })
                }
            }
        }

        item {
            LabeledField(
                label = stringResource(R.string.characters_name),
                value = d.name,
                onValueChange = { v -> update { copy(name = v) } },
                singleLine = true,
                error = if (showErrors && d.name.isBlank()) stringResource(R.string.characters_name_required) else null,
            )
        }
        item {
            LabeledField(stringResource(R.string.characters_profile), d.profile, { v -> update { copy(profile = v) } }, hint = stringResource(R.string.characters_profile_hint), minLines = 4)
        }

        item { SectionLabel(stringResource(R.string.characters_greetings)) }
        item {
            LabeledField(stringResource(R.string.characters_greeting_default), d.greeting, { v -> update { copy(greeting = v) } }, hint = stringResource(R.string.characters_greeting_default_hint), minLines = 3)
        }
        d.alternateGreetings.forEachIndexed { i, text ->
            item(key = "alt-$i") {
                Column {
                    LabeledField(stringResource(R.string.characters_greeting_alt, i + 1), text, { v ->
                        update { copy(alternateGreetings = alternateGreetings.toMutableList().also { it[i] = v }) }
                    }, minLines = 3)
                    Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.CenterEnd) {
                        CompactButton(stringResource(R.string.characters_remove), {
                            update { copy(alternateGreetings = alternateGreetings.filterIndexed { j, _ -> j != i }) }
                        }, primary = false)
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(10.dp))
            SecondaryButton(stringResource(R.string.characters_add_greeting), { update { copy(alternateGreetings = alternateGreetings + "") } })
        }

        item { Spacer(Modifier.height(18.dp)) }
        item {
            Group {
                GroupRow(
                    title = stringResource(R.string.characters_worldbook),
                    icon = ScreenIcons.Document,
                    onClick = {
                        scope.launch {
                            val saved = if (dirty || isNew && original?.createdAt == 0L) persist() else d
                            if (saved != null) nav.open(CharacterRoutes.worldbook(saved.id))
                        }
                    },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val count = live.firstOrNull { it.id == d.id }?.worldbook?.entries?.size ?: 0
                            RowValue(if (count == 0) stringResource(R.string.characters_worldbook_subtitle_none) else count.toString())
                            RowChevron()
                        }
                    },
                )
            }
        }
        item {
            Spacer(Modifier.height(10.dp))
            Group {
                GroupRow(
                    title = stringResource(R.string.characters_advanced),
                    icon = ScreenIcons.Tune,
                    onClick = { advanced = !advanced },
                    trailing = { RowChevron() },
                )
            }
        }
        if (advanced) {
            item { LabeledField(stringResource(R.string.characters_nickname), d.nickname, { v -> update { copy(nickname = v) } }, singleLine = true) }
            item { LabeledField(stringResource(R.string.characters_personality), d.personality, { v -> update { copy(personality = v) } }, minLines = 2) }
            item { LabeledField(stringResource(R.string.characters_scenario), d.scenario, { v -> update { copy(scenario = v) } }, minLines = 2) }
            item {
                LabeledField(stringResource(R.string.characters_example_dialogue), d.exampleDialogue, { v -> update { copy(exampleDialogue = v) } }, hint = stringResource(R.string.characters_example_dialogue_hint), minLines = 4)
            }
            item { LabeledField(stringResource(R.string.characters_system_prompt), d.systemPrompt, { v -> update { copy(systemPrompt = v) } }, minLines = 3) }
            item {
                LabeledField(stringResource(R.string.characters_post_history), d.postHistoryInstructions, { v -> update { copy(postHistoryInstructions = v) } }, hint = stringResource(R.string.characters_post_history_hint), minLines = 2)
            }
            item { LabeledField(stringResource(R.string.characters_creator_notes), d.creatorNotes, { v -> update { copy(creatorNotes = v) } }, minLines = 2) }
            item { LabeledField(stringResource(R.string.characters_tags), tagsText, { tagsText = it }, hint = stringResource(R.string.characters_tags_hint), singleLine = true) }
            item { LabeledField(stringResource(R.string.characters_creator), d.creator, { v -> update { copy(creator = v) } }, singleLine = true) }
            item { LabeledField(stringResource(R.string.characters_version), d.version, { v -> update { copy(version = v) } }, singleLine = true) }
            item { LabeledField(stringResource(R.string.characters_credits), d.credits, { v -> update { copy(credits = v) } }, minLines = 2) }
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.characters_discard_title),
            body = null,
            confirm = stringResource(R.string.characters_discard),
            dismiss = stringResource(R.string.characters_keep_editing),
            onConfirm = {
                confirmDiscard = false
                nav.back()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

@Composable
private fun PendingPreview(png: ByteArray) {
    val bitmap by produceState<ImageBitmap?>(null, png) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size, opts)?.asImageBitmap()
            }.getOrNull()
        }
    }
    bitmap?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp))) }
}
