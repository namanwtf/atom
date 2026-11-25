package atom.characters.session

import android.content.Context
import atom.characters.R
import atom.characters.data.CharacterStore
import atom.characters.model.CandidateSet
import atom.characters.model.Character
import atom.characters.model.ChatBinding
import atom.characters.prompt.CharacterPrompt
import atom.characters.prompt.PromptAssembler
import atom.core.llm.ChatMessage
import atom.core.llm.Role
import atom.core.tool.Tool
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.StateFlow

/** The greeting a new character chat starts with. The chat shows [marker] above it. */
data class Opening(val characterName: String, val text: String, val marker: String)

/** Everything a run in a character chat needs. Built by [CharacterSession.prepareRun]. */
data class CharacterRun(
    val conversationId: String,
    val character: Character,
    val prompt: CharacterPrompt,
    /** "<name> is replying…" */
    val replyingLabel: String,
    /** False for a regeneration: run the model once with an empty tool registry. */
    val toolsAllowed: Boolean,
) {
    /** Tools that must not be offered in this chat (MEMORY.md writes). */
    val blockedTools: Set<String> get() = CharacterSession.MEMORY_WRITE_TOOLS

    fun filterTools(tools: List<Tool>): List<Tool> =
        if (!toolsAllowed) emptyList() else tools.filterNot { it.spec.name in blockedTools }
}

/**
 * The runtime/chat-facing API for character chats. Install once with [install]; every other call is safe from any
 * thread. Nothing here runs in the background.
 */
object CharacterSession {
    const val STORY_MEMORY_TOOL = "story_memory_update"
    const val LIST_TOOL = "list_characters"
    val MEMORY_WRITE_TOOLS: Set<String> = setOf("memory_append", "memory_update", "memory_clear")

    @Volatile
    private var storeRef: CharacterStore? = null

    @Volatile
    private var appContext: Context? = null

    private val active = ConcurrentHashMap<String, String>()

    val store: CharacterStore get() = checkNotNull(storeRef) { "CharacterSession.install was not called" }

    val bindings: StateFlow<Map<String, ChatBinding>> get() = store.bindings

    fun install(context: Context) {
        if (storeRef != null) return
        synchronized(this) {
            if (storeRef != null) return
            appContext = context.applicationContext
            storeRef = CharacterStore(File(context.applicationContext.filesDir, "atom4_characters"))
        }
    }

    internal fun installForTest(store: CharacterStore) {
        storeRef = store
        appContext = null
        active.clear()
    }

    // ------------------------------------------------------------------ Starting a chat

    /**
     * Creates the binding for a new conversation and returns its id; the chat creates the conversation itself when
     * it opens an id it does not know and [isCharacterChat] says yes. Null if the character does not exist.
     */
    suspend fun startChat(characterId: String, greetingIndex: Int, usePersona: Boolean): String? {
        val s = store.also { it.load() }
        val c = s.get(characterId) ?: return null
        val id = UUID.randomUUID().toString()
        s.bind(
            ChatBinding(
                conversationId = id,
                characterId = c.id,
                greetingIndex = greetingIndex.coerceIn(0, (c.greetings.size - 1).coerceAtLeast(0)),
                usePersona = usePersona,
                snapshot = c,
                startedAt = System.currentTimeMillis(),
            ),
        )
        return id
    }

    suspend fun binding(conversationId: String): ChatBinding? {
        store.load()
        return store.bindings.value[conversationId]
    }

    suspend fun isCharacterChat(conversationId: String): Boolean = binding(conversationId) != null

    /** The greeting to store as the conversation's first assistant message, plus the marker text. */
    suspend fun opening(conversationId: String): Opening? {
        val b = binding(conversationId) ?: return null
        val c = store.get(b.characterId) ?: b.snapshot
        val persona = if (b.usePersona) store.persona.value else null
        return Opening(c.displayName, PromptAssembler.opening(c, b.greetingIndex, persona), storyBeginsLabel())
    }

    suspend fun setUsePersona(conversationId: String, use: Boolean) {
        store.updateBinding(conversationId) { it.copy(usePersona = use) }
    }

    /** Call when the conversation is deleted. Story memory is kept: it belongs to the character. */
    suspend fun forget(conversationId: String) {
        active.remove(conversationId)
        store.unbind(conversationId)
    }

    // ------------------------------------------------------------------ Runs

    /**
     * Builds the character block for a run and marks the chat active (for the prompt hint and the story memory
     * tool). Returns null for ordinary conversations. Always pair with [endRun].
     *
     * [history] is what the agent will receive (oldest first); [contextWindow] caps the worldbook to a quarter of it.
     * Pass [regenerate] = true when producing an alternative reply: no tools, one model call.
     */
    suspend fun prepareRun(
        conversationId: String,
        history: List<ChatMessage>,
        contextWindow: Int? = null,
        regenerate: Boolean = false,
    ): CharacterRun? {
        val b = binding(conversationId) ?: return null
        val live = store.get(b.characterId)
        if (live != null && live != b.snapshot) store.updateBinding(conversationId) { it.copy(snapshot = live) }
        val c = live ?: b.snapshot
        val persona = if (b.usePersona) store.persona.value else null
        val recent = history.filter { it.role == Role.User || it.role == Role.Assistant }.map { it.text }.filter { it.isNotBlank() }
        val bookBudget = c.worldbook?.tokenBudget ?: 0
        val budget = contextWindow?.let { w -> (w / 4).let { cap -> if (bookBudget <= 0) cap else minOf(bookBudget, cap) } } ?: bookBudget
        val prompt = PromptAssembler.build(
            c = c,
            persona = persona,
            recent = recent,
            storyMemory = store.storyMemory(c.id),
            worldbookBudget = budget,
            toolsAllowed = !regenerate,
        )
        active[conversationId] = c.id
        return CharacterRun(conversationId, c, prompt, replyingLabel(c.displayName), toolsAllowed = !regenerate)
    }

    fun endRun(conversationId: String) {
        active.remove(conversationId)
    }

    /** Character ids with a run in progress. */
    fun activeCharacterIds(): Set<String> = active.values.toSet()

    fun replyingLabel(name: String): String =
        appContext?.getString(R.string.characters_replying, name) ?: "$name is replying…"

    fun storyBeginsLabel(): String = appContext?.getString(R.string.characters_story_begins) ?: "The story begins here"

    // ------------------------------------------------------------------ Reply candidates

    /** Current candidates for the message the chat identifies by [messageKey] (use the message's createdAt). */
    fun candidates(conversationId: String, messageKey: String): CandidateSet? =
        storeRef?.bindings?.value?.get(conversationId)?.candidates?.get(messageKey)

    /**
     * Records a regenerated reply. The first call also keeps [currentText] as candidate 1. The returned set's
     * [CandidateSet.current] is the text the chat stores for that message.
     */
    suspend fun addCandidate(conversationId: String, messageKey: String, currentText: String, newText: String): CandidateSet? =
        store.updateBinding(conversationId) { b ->
            b.copy(candidates = b.candidates + (messageKey to CharacterStore.addCandidate(b.candidates[messageKey], currentText, newText)))
        }?.candidates?.get(messageKey)

    /** Switches to candidate [index] (clamped); the chat stores the returned set's current text. */
    suspend fun selectCandidate(conversationId: String, messageKey: String, index: Int): CandidateSet? =
        store.updateBinding(conversationId) { b ->
            val set = b.candidates[messageKey] ?: return@updateBinding b
            b.copy(candidates = b.candidates + (messageKey to set.copy(selected = index.coerceIn(0, set.texts.lastIndex))))
        }?.candidates?.get(messageKey)

    /** After the user edits a character message in place, keeps the selected candidate in sync. */
    suspend fun replaceCandidate(conversationId: String, messageKey: String, text: String) {
        store.updateBinding(conversationId) { b ->
            val set = b.candidates[messageKey] ?: return@updateBinding b
            val texts = set.texts.toMutableList().also { it[set.selected] = text }
            b.copy(candidates = b.candidates + (messageKey to set.copy(texts = texts)))
        }
    }
}
