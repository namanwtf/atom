package atom.characters.data

import atom.characters.card.CardCodec
import atom.characters.card.CardException
import atom.characters.model.CandidateSet
import atom.characters.model.Character
import atom.characters.model.CharacterOrigin
import atom.characters.model.ChatBinding
import atom.characters.model.Persona
import atom.characters.png.Png
import atom.core.io.deleteTreeNoFollow
import java.io.File
import java.util.Base64
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * All character data in one folder: the library, images, per-character story memory, the user's persona and the
 * conversation bindings. Every write is atomic and serialised; flows reflect what is on disk.
 */
class CharacterStore(
    private val root: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
    private val mutex = Mutex()
    private val libraryFile = File(root, "characters.json")
    private val personaFile = File(root, "persona.json")
    private val chatsFile = File(root, "chats.json")
    private val imageDir = File(root, "images")
    private val storyDir = File(root, "story")

    private val _characters = MutableStateFlow<List<Character>>(emptyList())
    private val _persona = MutableStateFlow(Persona())
    private val _bindings = MutableStateFlow<Map<String, ChatBinding>>(emptyMap())
    private val _loaded = MutableStateFlow(false)

    val characters: StateFlow<List<Character>> = _characters.asStateFlow()
    val persona: StateFlow<Persona> = _persona.asStateFlow()
    val bindings: StateFlow<Map<String, ChatBinding>> = _bindings.asStateFlow()
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    val hasDefault: Boolean get() = _characters.value.any { it.origin == CharacterOrigin.BuiltIn }

    fun get(id: String): Character? = _characters.value.firstOrNull { it.id == id }

    fun imageFile(c: Character): File? = c.image?.let { File(imageDir, it) }?.takeIf { it.isFile }

    /** Reads everything once; later calls return immediately. Seeds the default character on a fresh install. */
    suspend fun load() {
        if (_loaded.value) return
        mutex.withLock {
            if (_loaded.value) return
            withContext(io) {
                root.mkdirs()
                val list = if (libraryFile.isFile) {
                    runCatching { json.decodeFromString(ListSerializer(Character.serializer()), libraryFile.readText()) }.getOrDefault(emptyList())
                } else {
                    listOf(DefaultCharacter.create(clock())).also { writeLibrary(it) }
                }
                _characters.value = sorted(list)
                _persona.value = runCatching { json.decodeFromString(Persona.serializer(), personaFile.readText()) }.getOrDefault(Persona())
                _bindings.value = runCatching { json.decodeFromString(ListSerializer(ChatBinding.serializer()), chatsFile.readText()) }
                    .getOrDefault(emptyList()).associateBy { it.conversationId }
            }
            _loaded.value = true
        }
    }

    // ------------------------------------------------------------------ Library

    /** Inserts or replaces [c] (matched by id) and returns what was stored. */
    suspend fun save(c: Character): Character = edit { list ->
        val stored = normalised(c)
        val next = if (list.any { it.id == c.id }) list.map { if (it.id == c.id) stored else it } else list + stored
        next to stored
    }

    /** Atomically applies [change] to the latest stored version; null when the character no longer exists. */
    suspend fun update(id: String, change: (Character) -> Character): Character? = edit { list ->
        val current = list.firstOrNull { it.id == id } ?: return@edit list to null
        val next = normalised(change(current).copy(id = id))
        list.map { if (it.id == id) next else it } to next
    }

    fun newCharacter(): Character = Character(id = UUID.randomUUID().toString(), name = "", origin = CharacterOrigin.Created)

    /** Removes the character and its image. Chats keep their snapshot and story memory stays for them. */
    suspend fun delete(id: String) {
        val image = edit { list -> list.filterNot { it.id == id } to list.firstOrNull { it.id == id }?.image }
        if (image != null) withContext(io) { File(imageDir, image).delete() }
    }

    suspend fun duplicate(id: String): Character? {
        val source = get(id) ?: return null
        val copyId = UUID.randomUUID().toString()
        val image = imageFile(source)?.let { src ->
            withContext(io) {
                imageDir.mkdirs()
                val name = "$copyId.png"
                src.copyTo(File(imageDir, name), overwrite = true)
                name
            }
        }
        return save(
            source.copy(
                id = copyId,
                name = "${source.name} (copy)",
                origin = CharacterOrigin.Created,
                favourite = false,
                image = image,
                imageVersion = clock(),
                createdAt = 0,
            ),
        )
    }

    suspend fun setFavourite(id: String, favourite: Boolean) {
        get(id)?.let { save(it.copy(favourite = favourite)) }
    }

    /** Stores [png] as the character's image; null goes back to the monogram card. */
    suspend fun setImage(id: String, png: ByteArray?): Character? {
        val c = get(id) ?: return null
        val name = withContext(io) {
            c.image?.let { File(imageDir, it).delete() }
            if (png == null) {
                null
            } else {
                imageDir.mkdirs()
                "$id.png".also { writeAtomic(File(imageDir, it), png) }
            }
        }
        return save(c.copy(image = name, imageVersion = clock()))
    }

    suspend fun restoreDefault(): Character {
        val existing = get(DefaultCharacter.ID)
        val fresh = DefaultCharacter.create(clock())
        return save(if (existing != null) fresh.copy(favourite = existing.favourite, image = existing.image) else fresh)
    }

    // ------------------------------------------------------------------ Cards

    /** Imports a PNG or JSON card as a new character. Throws [CardException] with a user-readable reason. */
    suspend fun importCard(bytes: ByteArray): Character {
        if (bytes.size > MAX_CARD_BYTES) throw CardException("This file is larger than ${MAX_CARD_BYTES / (1024 * 1024)} MB")
        val parsed = withContext(Dispatchers.Default) { CardCodec.read(bytes, clock()) }
        val image = if (Png.isPng(bytes)) {
            withContext(Dispatchers.Default) { runCatching { CardCodec.stripCard(bytes) }.getOrNull() }
        } else {
            null
        }
        val saved = save(parsed.character)
        return if (image != null) setImage(saved.id, image) ?: saved else saved
    }

    suspend fun exportJson(id: String): String? = get(id)?.let { CardCodec.exportJson(it) }

    /** [placeholder] renders an image for characters without one. */
    suspend fun exportPng(id: String, placeholder: suspend (Character) -> ByteArray): ByteArray? {
        val c = get(id) ?: return null
        val image = imageFile(c)?.let { withContext(io) { it.readBytes() } } ?: placeholder(c)
        return withContext(Dispatchers.Default) { CardCodec.exportPng(c, image) }
    }

    // ------------------------------------------------------------------ Story memory

    suspend fun storyMemory(characterId: String): String = withContext(io) {
        storyFile(characterId)?.takeIf { it.isFile }?.readText().orEmpty()
    }

    /** Returns false when the text is over [STORY_LIMIT]. */
    suspend fun setStoryMemory(characterId: String, text: String): Boolean = mutex.withLock {
        if (text.toByteArray().size > STORY_LIMIT) return false
        val file = storyFile(characterId) ?: return false
        withContext(io) {
            if (text.isBlank()) file.delete() else {
                storyDir.mkdirs()
                writeAtomic(file, text.toByteArray())
            }
        }
        true
    }

    suspend fun appendStoryMemory(characterId: String, text: String): Boolean {
        val current = storyMemory(characterId)
        val next = if (current.isBlank()) text.trim() else current.trimEnd() + "\n" + text.trim()
        return setStoryMemory(characterId, next)
    }

    // ------------------------------------------------------------------ Persona

    suspend fun setPersona(p: Persona) = mutex.withLock {
        val clean = Persona(p.name.trim(), p.description.trim())
        withContext(io) { writeAtomic(personaFile, json.encodeToString(Persona.serializer(), clean).toByteArray()) }
        _persona.value = clean
    }

    // ------------------------------------------------------------------ Chat bindings

    suspend fun bind(binding: ChatBinding) = editBindings { it + (binding.conversationId to binding) }

    suspend fun updateBinding(conversationId: String, change: (ChatBinding) -> ChatBinding): ChatBinding? {
        var result: ChatBinding? = null
        editBindings { map ->
            val b = map[conversationId] ?: return@editBindings map
            val next = change(b)
            result = next
            map + (conversationId to next)
        }
        return result
    }

    suspend fun unbind(conversationId: String) = editBindings { it - conversationId }

    // ------------------------------------------------------------------ Backup

    /** Everything this module stores, for the app's single-file backup. Images are base64. */
    suspend fun exportBackup(): JsonObject {
        load()
        val list = _characters.value
        val stories = list.map { it.id }.plus(_bindings.value.values.map { it.characterId }).distinct()
            .associateWith { storyMemory(it) }.filterValues { it.isNotBlank() }
        val images = withContext(io) {
            list.mapNotNull { c -> imageFile(c)?.let { c.id to Base64.getEncoder().encodeToString(it.readBytes()) } }.toMap()
        }
        return buildJsonObject {
            put("version", BACKUP_VERSION)
            put("characters", json.encodeToJsonElement(ListSerializer(Character.serializer()), list))
            put("persona", json.encodeToJsonElement(Persona.serializer(), _persona.value))
            put("chats", json.encodeToJsonElement(ListSerializer(ChatBinding.serializer()), _bindings.value.values.toList()))
            put("story", JsonObject(stories.mapValues { JsonPrimitive(it.value) }))
            put("images", JsonObject(images.mapValues { JsonPrimitive(it.value) }))
        }
    }

    /** Validates [backup] fully, then replaces all character data. Throws [IllegalArgumentException] if invalid. */
    suspend fun importBackup(backup: JsonObject) {
        val version = backup["version"]?.jsonPrimitive?.content?.toIntOrNull()
        require(version == BACKUP_VERSION) { "Unsupported character backup version" }
        val list = json.decodeFromJsonElement(ListSerializer(Character.serializer()), backup.getValue("characters"))
        val persona = backup["persona"]?.let { json.decodeFromJsonElement<Persona>(it) } ?: Persona()
        val chats = backup["chats"]?.let { json.decodeFromJsonElement(ListSerializer(ChatBinding.serializer()), it) }.orEmpty()
        val stories = backup["story"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        val images = backup["images"]?.jsonObject?.mapValues { Base64.getDecoder().decode(it.value.jsonPrimitive.content) }.orEmpty()
        require((list.map { it.id } + stories.keys + images.keys).all(::isSafeId)) { "Invalid id in character backup" }
        mutex.withLock {
            withContext(io) {
                val staging = File(root.parentFile, root.name + ".import")
                staging.deleteTreeNoFollow()
                File(staging, "images").mkdirs()
                File(staging, "story").mkdirs()
                val restored = list.map { c -> if (images.containsKey(c.id)) c.copy(image = "${c.id}.png") else c.copy(image = null) }
                images.forEach { (id, bytes) -> File(staging, "images/$id.png").writeBytes(bytes) }
                stories.forEach { (id, text) -> File(staging, "story/$id.md").writeText(text) }
                File(staging, "characters.json").writeText(json.encodeToString(ListSerializer(Character.serializer()), restored))
                File(staging, "persona.json").writeText(json.encodeToString(Persona.serializer(), persona))
                File(staging, "chats.json").writeText(json.encodeToString(ListSerializer(ChatBinding.serializer()), chats))
                val old = File(root.parentFile, root.name + ".old")
                old.deleteTreeNoFollow()
                if (root.exists() && !root.renameTo(old)) error("Could not replace character data")
                if (!staging.renameTo(root)) {
                    old.renameTo(root)
                    error("Could not replace character data")
                }
                old.deleteTreeNoFollow()
                _characters.value = sorted(restored)
                _persona.value = persona
                _bindings.value = chats.associateBy { it.conversationId }
            }
        }
    }

    // ------------------------------------------------------------------ Internals

    private suspend fun <T> edit(change: (List<Character>) -> Pair<List<Character>, T>): T = mutex.withLock {
        val (next, result) = change(_characters.value)
        withContext(io) { writeLibrary(next) }
        _characters.value = sorted(next)
        // Open chats and the conversation list show the edited name, avatar and profile at once, not at the next run.
        val bindings = withLiveSnapshots(_bindings.value, next)
        if (bindings !== _bindings.value) {
            withContext(io) {
                root.mkdirs()
                writeAtomic(chatsFile, json.encodeToString(ListSerializer(ChatBinding.serializer()), bindings.values.toList()).toByteArray())
            }
            _bindings.value = bindings
        }
        result
    }

    private suspend fun editBindings(change: (Map<String, ChatBinding>) -> Map<String, ChatBinding>) = mutex.withLock {
        val next = change(_bindings.value)
        if (next === _bindings.value) return@withLock
        withContext(io) {
            root.mkdirs()
            writeAtomic(chatsFile, json.encodeToString(ListSerializer(ChatBinding.serializer()), next.values.toList()).toByteArray())
        }
        _bindings.value = next
    }

    private fun normalised(c: Character): Character {
        val now = clock()
        return c.copy(
            name = c.name.trim().ifBlank { "Unnamed" },
            tags = CardCodec.normaliseTags(c.tags),
            alternateGreetings = c.alternateGreetings.filter { it.isNotBlank() },
            createdAt = if (c.createdAt == 0L) now else c.createdAt,
            updatedAt = now,
        )
    }

    private fun writeLibrary(list: List<Character>) {
        root.mkdirs()
        writeAtomic(libraryFile, json.encodeToString(ListSerializer(Character.serializer()), list).toByteArray())
    }

    private fun storyFile(characterId: String): File? = if (isSafeId(characterId)) File(storyDir, "$characterId.md") else null

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) error("Could not write ${target.name}")
        }
    }

    private fun sorted(list: List<Character>) = list.sortedWith(
        compareBy<Character> { it.origin != CharacterOrigin.BuiltIn }.thenBy { it.displayName.lowercase(Locale.ROOT) }.thenBy { it.id },
    )

    companion object {
        const val STORY_LIMIT = 64 * 1024
        const val MAX_CARD_BYTES = 24 * 1024 * 1024
        const val MAX_CANDIDATES = 10
        private const val BACKUP_VERSION = 1

        /**
         * [bindings] with each chat's snapshot replaced by the stored character when that one differs. A deleted
         * character keeps its snapshot (the chat goes on working). Returns [bindings] itself when nothing changed.
         */
        fun withLiveSnapshots(bindings: Map<String, ChatBinding>, characters: List<Character>): Map<String, ChatBinding> {
            val byId = characters.associateBy { it.id }
            var changed = false
            val next = bindings.mapValues { (_, b) ->
                val live = byId[b.characterId]
                if (live != null && live != b.snapshot) {
                    changed = true
                    b.copy(snapshot = live)
                } else {
                    b
                }
            }
            return if (changed) next else bindings
        }

        /** Characters whose shown name changed between [before] and [after]: id to (old name, new name). */
        fun renamed(before: List<Character>, after: List<Character>): Map<String, Pair<String, String>> {
            val old = before.associate { it.id to it.displayName }
            return after.mapNotNull { c -> old[c.id]?.takeIf { it != c.displayName }?.let { c.id to (it to c.displayName) } }.toMap()
        }

        fun isSafeId(id: String): Boolean = id.isNotEmpty() && id.length <= 80 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

        /** Name, nickname or tag contains every word of [query]; "#word" only matches tags. */
        fun search(list: List<Character>, query: String): List<Character> {
            val words = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return list
            return list.filter { c ->
                val tags = c.tags.map { it.lowercase(Locale.ROOT) }
                val names = listOf(c.name, c.nickname).map { it.lowercase(Locale.ROOT) }
                words.all { w ->
                    if (w.startsWith("#") && w.length > 1) {
                        tags.any { it.contains(w.drop(1)) }
                    } else {
                        names.any { it.contains(w) } || tags.any { it.contains(w) }
                    }
                }
            }
        }

        /** Adds [newText] as a new candidate after the current ones and selects it. */
        fun addCandidate(set: CandidateSet?, original: String, newText: String): CandidateSet {
            val base = set?.texts ?: listOf(original)
            val texts = (base + newText).let { if (it.size > MAX_CANDIDATES) it.drop(it.size - MAX_CANDIDATES) else it }
            return CandidateSet(texts, texts.lastIndex)
        }
    }
}
