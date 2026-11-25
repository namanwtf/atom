package atom.characters.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class Placement { BeforeDefinition, AfterDefinition }

/** How secondary keywords combine with a primary match when [WorldEntry.requireSecondary] is on. */
@Serializable
enum class SecondaryLogic { AndAny, AndAll, NotAny, NotAll }

@Serializable
data class WorldEntry(
    val id: String,
    val name: String = "",
    val keys: List<String> = emptyList(),
    val secondaryKeys: List<String> = emptyList(),
    val requireSecondary: Boolean = false,
    val secondaryLogic: SecondaryLogic = SecondaryLogic.AndAny,
    val alwaysActive: Boolean = false,
    val placement: Placement = Placement.BeforeDefinition,
    /** Lower values are inserted earlier in the prompt. */
    val insertionOrder: Int = 100,
    /** Which entries survive when the token budget is tight (higher wins); falls back to [insertionOrder]. */
    val priority: Int? = null,
    val enabled: Boolean = true,
    val caseSensitive: Boolean = false,
    /** Plain keywords only match whole words (a key "cat" does not fire on "category"). */
    val wholeWords: Boolean = true,
    /** Overrides [Worldbook.scanDepth] for this entry. */
    val scanDepth: Int? = null,
    /** Never activated by another entry's content during recursive scanning. */
    val excludeRecursion: Boolean = false,
    /** Its content never activates other entries during recursive scanning. */
    val preventRecursion: Boolean = false,
    val content: String = "",
    /** Set on import when part of the original entry could not be represented. */
    val compatibilityNote: String? = null,
    /** Unknown fields from the source card, written back on export. */
    val extensions: JsonObject? = null,
) {
    val effectivePriority: Int get() = priority ?: insertionOrder
}

@Serializable
data class Worldbook(
    val name: String = "",
    val description: String = "",
    /** How many recent messages are scanned for keywords. */
    val scanDepth: Int = DEFAULT_SCAN_DEPTH,
    /** Tokens all injected entries may use together; 0 or less means no limit. */
    val tokenBudget: Int = DEFAULT_TOKEN_BUDGET,
    /** Activated entries' content is scanned again for more keywords. */
    val recursiveScanning: Boolean = false,
    val entries: List<WorldEntry> = emptyList(),
    val extensions: JsonObject? = null,
) {
    companion object {
        const val DEFAULT_SCAN_DEPTH = 4
        const val DEFAULT_TOKEN_BUDGET = 1024
    }
}

@Serializable
enum class CharacterOrigin { BuiltIn, Created, Imported }

@Serializable
data class Character(
    val id: String,
    val name: String,
    /** The character's description ("profile"); `description` in card files. */
    val profile: String = "",
    val greeting: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val personality: String = "",
    val scenario: String = "",
    val exampleDialogue: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val creatorNotes: String = "",
    val tags: List<String> = emptyList(),
    val creator: String = "",
    val version: String = "",
    val credits: String = "",
    val nickname: String = "",
    val worldbook: Worldbook? = null,
    val origin: CharacterOrigin = CharacterOrigin.Created,
    val favourite: Boolean = false,
    /** File name inside the store's image folder, or null for the monogram card. */
    val image: String? = null,
    /** Bumped whenever the image changes, so cached thumbnails are refreshed. */
    val imageVersion: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** Card extensions this app does not use, preserved for export. */
    val extensions: JsonObject? = null,
) {
    /** Default greeting first, then the alternates; blank ones are dropped. */
    val greetings: List<String> get() = (listOf(greeting) + alternateGreetings).filter { it.isNotBlank() }

    val displayName: String get() = nickname.ifBlank { name }

    /** True when the user made it or may treat it as their own (the built-in companion counts). */
    val isMine: Boolean get() = origin != CharacterOrigin.Imported
}

@Serializable
data class Persona(val name: String = "", val description: String = "") {
    val isEmpty: Boolean get() = name.isBlank() && description.isBlank()
}

/** Alternative character replies for one message; [selected] is the one shown and kept in the conversation. */
@Serializable
data class CandidateSet(val texts: List<String>, val selected: Int) {
    val current: String get() = texts[selected.coerceIn(0, texts.lastIndex)]
    val hasPrevious: Boolean get() = selected > 0
    val hasNext: Boolean get() = selected < texts.lastIndex

    /** "2 / 3" style position for the switcher. */
    val position: String get() = "${selected + 1} / ${texts.size}"
}

/**
 * Ties a conversation to a character. [snapshot] is refreshed at every run while the character exists and keeps
 * the chat working after the character is deleted.
 */
@Serializable
data class ChatBinding(
    val conversationId: String,
    val characterId: String,
    val greetingIndex: Int = 0,
    val usePersona: Boolean = false,
    val snapshot: Character,
    val startedAt: Long = 0,
    val candidates: Map<String, CandidateSet> = emptyMap(),
)
