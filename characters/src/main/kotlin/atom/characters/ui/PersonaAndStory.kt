package atom.characters.ui

import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import atom.characters.R
import atom.characters.data.CharacterStore
import atom.characters.model.Persona
import atom.characters.session.CharacterSession
import atom.ui.components.CompactButton
import atom.ui.components.LargeTitleScreen
import atom.ui.feature.FeatureNav
import kotlinx.coroutines.launch

@Composable
internal fun PersonaScreen(nav: FeatureNav) {
    val store = CharacterSession.store
    val loaded by store.loaded.collectAsState()
    LaunchedEffect(Unit) { store.load() }
    var draft by remember { mutableStateOf<Persona?>(null) }
    LaunchedEffect(loaded) { if (loaded && draft == null) draft = store.persona.value }
    val latest by rememberUpdatedState(draft)
    DisposableEffect(Unit) {
        onDispose {
            val p = latest ?: return@onDispose
            if (p != store.persona.value) SaveScope.scope.launch { store.setPersona(p) }
        }
    }
    LargeTitleScreen(title = stringResource(R.string.characters_persona_title), onBack = nav::back) {
        val p = draft ?: return@LargeTitleScreen
        item {
            LabeledField(
                stringResource(R.string.characters_persona_name),
                p.name,
                { draft = p.copy(name = it) },
                singleLine = true,
                keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            )
        }
        item {
            LabeledField(
                stringResource(R.string.characters_persona_description),
                p.description,
                { draft = p.copy(description = it) },
                hint = stringResource(R.string.characters_persona_description_hint),
                minLines = 5,
            )
        }
        item {
            Text(
                stringResource(R.string.characters_persona_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, top = 12.dp),
            )
        }
    }
}

@Composable
internal fun StoryMemoryScreen(id: String, nav: FeatureNav) {
    val store = CharacterSession.store
    LaunchedEffect(Unit) { store.load() }
    val all by store.characters.collectAsState()
    val bindings by store.bindings.collectAsState()
    val name = all.firstOrNull { it.id == id }?.displayName ?: bindings.values.firstOrNull { it.characterId == id }?.snapshot?.displayName.orEmpty()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id) {
        val current = store.storyMemory(id)
        saved = current
        if (text == null) text = current
    }
    val tooLong = (text?.toByteArray()?.size ?: 0) > CharacterStore.STORY_LIMIT
    val latest by rememberUpdatedState(text)
    val latestSaved by rememberUpdatedState(saved)
    DisposableEffect(id) {
        onDispose {
            val t = latest ?: return@onDispose
            if (t != latestSaved && t.toByteArray().size <= CharacterStore.STORY_LIMIT) SaveScope.scope.launch { store.setStoryMemory(id, t) }
        }
    }
    LargeTitleScreen(
        title = stringResource(R.string.characters_story_memory),
        onBack = nav::back,
        actions = {
            CompactButton(
                text = stringResource(R.string.characters_save),
                enabled = text != null && text != saved && !tooLong,
                onClick = {
                    val t = text ?: return@CompactButton
                    scope.launch {
                        if (store.setStoryMemory(id, t)) {
                            saved = t
                            Toast.makeText(context, R.string.characters_saved, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.padding(end = 12.dp),
            )
        },
    ) {
        val t = text ?: return@LargeTitleScreen
        item {
            Text(
                stringResource(R.string.characters_story_memory_subtitle, name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
            )
        }
        item {
            LabeledField(
                label = "",
                value = t,
                onValueChange = { text = it },
                hint = stringResource(R.string.characters_story_hint),
                minLines = 10,
                error = if (tooLong) stringResource(R.string.characters_story_too_long) else null,
            )
        }
        if (t.isNotEmpty()) {
            item {
                CompactButton(stringResource(R.string.characters_clear), { text = "" }, primary = false, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}
