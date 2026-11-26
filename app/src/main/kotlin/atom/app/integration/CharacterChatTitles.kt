package atom.app.integration

import atom.app.AppGraph
import atom.app.data.TitleSource
import atom.characters.data.CharacterStore
import atom.characters.session.CharacterSession
import kotlinx.coroutines.launch

/**
 * A renamed character renames its chats that are still called by the character's name (the name a character chat
 * starts with, until a title is generated for it). Titles the user typed or that were generated stay.
 * Avatar and profile need nothing here: the store refreshes each chat's snapshot when the character is saved.
 */
internal object CharacterChatTitles {
    fun start(graph: AppGraph) {
        graph.appScope.launch {
            val store = runCatching { CharacterSession.store }.getOrNull() ?: return@launch
            runCatching { store.load() }
            var before = store.characters.value
            store.characters.collect { list ->
                val renames = CharacterStore.renamed(before, list)
                before = list
                if (renames.isEmpty()) return@collect
                for (b in store.bindings.value.values) {
                    val (old, new) = renames[b.characterId] ?: continue
                    runCatching {
                        graph.tasks.editConversation(b.conversationId) { c ->
                            if (c.title == old && c.titleSource != TitleSource.Generated) c.copy(title = new) else c
                        }
                    }
                }
            }
        }
    }
}
