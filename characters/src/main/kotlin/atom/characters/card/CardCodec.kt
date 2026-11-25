package atom.characters.card

import atom.characters.model.Character
import atom.characters.model.CharacterOrigin
import atom.characters.model.Placement
import atom.characters.model.SecondaryLogic
import atom.characters.model.WorldEntry
import atom.characters.model.Worldbook
import atom.characters.png.Png
import atom.characters.png.PngException
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class CardException(message: String) : Exception(message)

enum class CardSpec { V1, V2, V3 }

/** A parsed card: the normalised character plus where it came from. */
data class ParsedCard(val character: Character, val spec: CardSpec)

/**
 * Reads Character Card V1/V2/V3 (JSON or PNG with "chara"/"ccv3" text chunks) into [Character] and writes them back.
 * Reading is lenient (nulls, numbers as strings, comma-separated keys, SillyTavern world-info shapes); writing
 * produces V3 with V1 fields mirrored at the top level, and PNGs carry both a V2 "chara" and a V3 "ccv3" chunk.
 */
object CardCodec {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }
    private const val ATOM_EXT = "atom"
    private val CARD_KEYWORDS = setOf("chara", "ccv3")

    // ------------------------------------------------------------------ Reading

    /** Detects PNG or JSON from the bytes themselves. */
    fun read(bytes: ByteArray, now: Long = System.currentTimeMillis(), newId: () -> String = ::randomId): ParsedCard =
        if (Png.isPng(bytes)) readPng(bytes, now, newId) else readJson(String(bytes, Charsets.UTF_8).removePrefix("﻿"), now, newId)

    fun readPng(bytes: ByteArray, now: Long = System.currentTimeMillis(), newId: () -> String = ::randomId): ParsedCard {
        val chunks = try {
            Png.readChunks(bytes, verifyCrc = true)
        } catch (e: PngException) {
            if (e.message?.startsWith("CRC") == true) Png.readChunks(bytes, verifyCrc = false) else throw CardException("This PNG is damaged: ${e.message}")
        }
        val texts = Png.texts(chunks).mapKeys { it.key.lowercase() }
        val sources = listOfNotNull(texts["ccv3"], texts["chara"])
        if (sources.isEmpty()) throw CardException("This PNG has no character card inside")
        var failure: CardException? = null
        for (encoded in sources) {
            val decoded = decodeBase64(encoded)
            if (decoded == null) {
                failure = failure ?: CardException("The card data in this PNG is not valid base64")
                continue
            }
            try {
                return readJson(decoded, now, newId)
            } catch (e: CardException) {
                failure = failure ?: e
            }
        }
        throw failure!!
    }

    fun readJson(text: String, now: Long = System.currentTimeMillis(), newId: () -> String = ::randomId): ParsedCard {
        val parsed = try {
            json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            throw CardException("This file is not valid JSON")
        }
        val root = parsed as? JsonObject ?: throw CardException("This JSON is not a character card")
        val spec = root.str("spec")
        val data = root["data"] as? JsonObject
        val (fields, kind) = when {
            spec == "chara_card_v3" && data != null -> data to CardSpec.V3
            spec == "chara_card_v2" && data != null -> data to CardSpec.V2
            data != null && data.str("name") != null -> data to (if (data["assets"] != null || data["nickname"] != null) CardSpec.V3 else CardSpec.V2)
            else -> root to CardSpec.V1
        }
        val name = (fields.str("name") ?: fields.str("char_name"))?.clean()?.takeIf { it.isNotBlank() }
            ?: throw CardException("This card has no character name")
        val extensions = fields["extensions"] as? JsonObject
        val atom = extensions?.get(ATOM_EXT) as? JsonObject
        val book = (fields["character_book"] as? JsonObject)?.let(::readBook)
        val notes = fields.str("creator_notes")?.clean().orEmpty().ifBlank {
            ((fields["creator_notes_multilingual"] as? JsonObject)?.let { m -> m.str("en") ?: m.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNullSafe() } })
                ?.clean().orEmpty()
        }
        val character = Character(
            id = newId(),
            name = name,
            profile = (fields.str("description") ?: fields.str("char_persona")).clean(),
            greeting = (fields.str("first_mes") ?: fields.str("char_greeting")).clean(),
            alternateGreetings = fields.strings("alternate_greetings").map { it.clean() }.filter { it.isNotBlank() },
            personality = fields.str("personality").clean(),
            scenario = (fields.str("scenario") ?: fields.str("world_scenario")).clean(),
            exampleDialogue = (fields.str("mes_example") ?: fields.str("example_dialogue")).clean(),
            systemPrompt = fields.str("system_prompt").clean(),
            postHistoryInstructions = fields.str("post_history_instructions").clean(),
            creatorNotes = notes,
            tags = normaliseTags(fields.strings("tags")),
            creator = fields.str("creator").clean(),
            version = fields.str("character_version").clean(),
            credits = atom?.str("credits").clean(),
            nickname = fields.str("nickname").clean(),
            worldbook = book,
            origin = CharacterOrigin.Imported,
            createdAt = now,
            updatedAt = now,
            extensions = extensions?.let { JsonObject(it - ATOM_EXT) }?.takeIf { it.isNotEmpty() },
        )
        return ParsedCard(character, kind)
    }

    /** Accepts a V2 `character_book` or a SillyTavern world-info file (entries keyed by uid). */
    fun readBook(o: JsonObject): Worldbook {
        val rawEntries: List<JsonObject> = when (val e = o["entries"]) {
            is JsonArray -> e.filterIsInstance<JsonObject>()
            is JsonObject -> e.values.filterIsInstance<JsonObject>()
            else -> emptyList()
        }
        val ids = HashSet<String>()
        val entries = rawEntries.mapIndexed { i, raw ->
            val entry = readEntry(raw, i)
            val id = if (ids.add(entry.id)) entry.id else "${entry.id}-$i".also { ids.add(it) }
            entry.copy(id = id)
        }
        val ext = o["extensions"] as? JsonObject
        return Worldbook(
            name = o.str("name").clean(),
            description = o.str("description").clean(),
            scanDepth = (o.int("scan_depth") ?: ext?.int("scan_depth"))?.coerceIn(0, 1000) ?: Worldbook.DEFAULT_SCAN_DEPTH,
            tokenBudget = o.int("token_budget") ?: Worldbook.DEFAULT_TOKEN_BUDGET,
            recursiveScanning = o.bool("recursive_scanning") ?: false,
            entries = entries,
            extensions = ext?.takeIf { it.isNotEmpty() },
        )
    }

    private fun readEntry(o: JsonObject, index: Int): WorldEntry {
        val ext = o["extensions"] as? JsonObject
        val notes = ArrayList<String>()
        val keys = (o.keyList("keys") + o.keyList("key")).distinct()
        val secondary = (o.keyList("secondary_keys") + o.keyList("keysecondary")).distinct()
        val useRegex = o.bool("use_regex") == true
        val finalKeys = if (useRegex) keys.map { if (isRegexKey(it)) it else "/$it/" } else keys
        val finalSecondary = if (useRegex) secondary.map { if (isRegexKey(it)) it else "/$it/" } else secondary
        (finalKeys + finalSecondary).filter { isRegexKey(it) && compileKey(it) == null }.forEach {
            notes += "Keyword $it is not a valid pattern and is matched as plain text."
        }

        val stPosition = ext?.int("position") ?: (o["position"] as? JsonPrimitive)?.intOrNull
        val placement = when {
            o.str("position") == "after_char" -> Placement.AfterDefinition
            o.str("position") == "before_char" -> Placement.BeforeDefinition
            stPosition == 1 -> Placement.AfterDefinition
            stPosition == null || stPosition == 0 -> Placement.BeforeDefinition
            else -> {
                notes += "Originally placed ${stPositionName(stPosition, ext?.int("depth") ?: o.int("depth"))}; Atom inserts it after the character definition instead."
                Placement.AfterDefinition
            }
        }
        val logic = when (ext?.int("selectiveLogic") ?: o.int("selectiveLogic")) {
            1 -> SecondaryLogic.NotAll
            2 -> SecondaryLogic.NotAny
            3 -> SecondaryLogic.AndAll
            else -> SecondaryLogic.AndAny
        }
        val probability = ext?.int("probability") ?: o.int("probability")
        val useProbability = ext?.bool("useProbability") ?: o.bool("useProbability") ?: (probability != null)
        if (useProbability && probability != null && probability < 100) {
            notes += "Trigger chance of $probability% is ignored; the entry always fires when its keywords match."
        }
        val group = ext?.str("group") ?: o.str("group")
        if (!group.isNullOrBlank()) notes += "Inclusion group \"$group\" is ignored; every matching entry in the group can fire."
        if ((ext?.int("delay_until_recursion") ?: 0) > 0 || ext?.bool("delay_until_recursion") == true) {
            notes += "\"Delay until recursion\" is ignored."
        }

        val enabled = o.bool("enabled") ?: (o.bool("disable")?.not()) ?: true
        val constant = o.bool("constant") ?: false
        val content = o.str("content").clean()
        val name = (o.str("name") ?: o.str("comment")).clean()
        val order = o.int("insertion_order") ?: o.int("order") ?: 100
        val id = (o["id"] as? JsonPrimitive)?.contentOrNullSafe() ?: (o["uid"] as? JsonPrimitive)?.contentOrNullSafe() ?: "e$index"
        val known = setOf(
            "position", "selectiveLogic", "probability", "useProbability", "group", "depth", "scan_depth",
            "case_sensitive", "match_whole_words", "exclude_recursion", "prevent_recursion",
        )
        return WorldEntry(
            id = id,
            name = name,
            keys = finalKeys,
            secondaryKeys = finalSecondary,
            requireSecondary = (o.bool("selective") ?: false) && finalSecondary.isNotEmpty(),
            secondaryLogic = logic,
            alwaysActive = constant,
            placement = placement,
            insertionOrder = order,
            priority = o.int("priority"),
            enabled = enabled,
            caseSensitive = o.bool("case_sensitive") ?: ext?.bool("case_sensitive") ?: o.bool("caseSensitive") ?: false,
            wholeWords = ext?.bool("match_whole_words") ?: o.bool("matchWholeWords") ?: true,
            scanDepth = (ext?.int("scan_depth") ?: o.int("scanDepth"))?.coerceIn(0, 1000),
            excludeRecursion = ext?.bool("exclude_recursion") ?: o.bool("excludeRecursion") ?: false,
            preventRecursion = ext?.bool("prevent_recursion") ?: o.bool("preventRecursion") ?: false,
            content = content,
            compatibilityNote = notes.joinToString(" ").ifBlank { null },
            extensions = ext?.let { JsonObject(it.filterKeys { k -> k !in known }) }?.takeIf { it.isNotEmpty() },
        )
    }

    private fun stPositionName(position: Int, depth: Int?): String = when (position) {
        2 -> "at the top of the author's note"
        3 -> "at the bottom of the author's note"
        4 -> "inside the chat at depth ${depth ?: 4}"
        5 -> "before the example messages"
        6 -> "after the example messages"
        else -> "at position $position"
    }

    // ------------------------------------------------------------------ Writing

    fun toV3(c: Character): JsonObject = buildJsonObject {
        put("spec", "chara_card_v3")
        put("spec_version", "3.0")
        put("data", data(c, v3 = true))
        put("name", c.name)
        put("description", c.profile)
        put("personality", c.personality)
        put("scenario", c.scenario)
        put("first_mes", c.greeting)
        put("mes_example", c.exampleDialogue)
    }

    fun toV2(c: Character): JsonObject = buildJsonObject {
        put("spec", "chara_card_v2")
        put("spec_version", "2.0")
        put("data", data(c, v3 = false))
    }

    fun exportJson(c: Character): String = pretty.encodeToString(JsonObject.serializer(), toV3(c))

    /** Embeds the card into [image] (any PNG), replacing card chunks already there. */
    fun exportPng(c: Character, image: ByteArray): ByteArray {
        val v2 = base64(json.encodeToString(JsonObject.serializer(), toV2(c)))
        val v3 = base64(json.encodeToString(JsonObject.serializer(), toV3(c)))
        return Png.replaceTexts(image, CARD_KEYWORDS, listOf("chara" to v2, "ccv3" to v3))
    }

    /** The image part of a card PNG, without its card chunks. */
    fun stripCard(png: ByteArray): ByteArray = Png.stripTexts(png, CARD_KEYWORDS)

    private fun data(c: Character, v3: Boolean): JsonObject = buildJsonObject {
        put("name", c.name)
        put("description", c.profile)
        put("personality", c.personality)
        put("scenario", c.scenario)
        put("first_mes", c.greeting)
        put("mes_example", c.exampleDialogue)
        put("creator_notes", c.creatorNotes)
        put("system_prompt", c.systemPrompt)
        put("post_history_instructions", c.postHistoryInstructions)
        put("alternate_greetings", JsonArray(c.alternateGreetings.map(::JsonPrimitive)))
        put("tags", JsonArray(c.tags.map(::JsonPrimitive)))
        put("creator", c.creator)
        put("character_version", c.version)
        val ext = (c.extensions ?: JsonObject(emptyMap())).toMutableMap()
        if (c.credits.isNotBlank()) ext[ATOM_EXT] = buildJsonObject { put("credits", c.credits) }
        put("extensions", JsonObject(ext))
        c.worldbook?.let { put("character_book", writeBook(it)) }
        if (v3) {
            put("nickname", c.nickname)
            put("group_only_greetings", JsonArray(emptyList()))
            put("creation_date", c.createdAt / 1000)
            put("modification_date", c.updatedAt / 1000)
            put(
                "assets",
                buildJsonArray {
                    add(buildJsonObject { put("type", "icon"); put("uri", "ccdefault:"); put("name", "main"); put("ext", "png") })
                },
            )
        }
    }

    fun writeBook(b: Worldbook): JsonObject = buildJsonObject {
        put("name", b.name)
        put("description", b.description)
        put("scan_depth", b.scanDepth)
        put("token_budget", b.tokenBudget)
        put("recursive_scanning", b.recursiveScanning)
        put("extensions", b.extensions ?: JsonObject(emptyMap()))
        put(
            "entries",
            JsonArray(
                b.entries.mapIndexed { i, e ->
                    buildJsonObject {
                        put("keys", JsonArray(e.keys.map(::JsonPrimitive)))
                        put("content", e.content)
                        put("extensions", entryExtensions(e))
                        put("enabled", e.enabled)
                        put("insertion_order", e.insertionOrder)
                        put("case_sensitive", e.caseSensitive)
                        put("use_regex", false)
                        put("constant", e.alwaysActive)
                        put("name", e.name)
                        put("comment", e.name)
                        e.priority?.let { put("priority", it) }
                        put("id", e.id.toIntOrNull() ?: i)
                        put("selective", e.requireSecondary)
                        put("secondary_keys", JsonArray(e.secondaryKeys.map(::JsonPrimitive)))
                        put("position", if (e.placement == Placement.AfterDefinition) "after_char" else "before_char")
                    }
                },
            ),
        )
    }

    private fun entryExtensions(e: WorldEntry): JsonObject {
        val m = (e.extensions ?: JsonObject(emptyMap())).toMutableMap()
        m["position"] = JsonPrimitive(if (e.placement == Placement.AfterDefinition) 1 else 0)
        m["selectiveLogic"] = JsonPrimitive(
            when (e.secondaryLogic) {
                SecondaryLogic.AndAny -> 0
                SecondaryLogic.NotAll -> 1
                SecondaryLogic.NotAny -> 2
                SecondaryLogic.AndAll -> 3
            },
        )
        m["match_whole_words"] = JsonPrimitive(e.wholeWords)
        m["case_sensitive"] = JsonPrimitive(e.caseSensitive)
        m["exclude_recursion"] = JsonPrimitive(e.excludeRecursion)
        m["prevent_recursion"] = JsonPrimitive(e.preventRecursion)
        m["scan_depth"] = e.scanDepth?.let(::JsonPrimitive) ?: JsonNull
        return JsonObject(m)
    }

    // ------------------------------------------------------------------ Helpers

    internal fun isRegexKey(key: String): Boolean = REGEX_KEY.matches(key.trim())

    /** Compiles a "/pattern/flags" key, or null when it is not one or does not compile. */
    internal fun compileKey(key: String): Regex? {
        val m = REGEX_KEY.matchEntire(key.trim()) ?: return null
        val options = buildSet {
            if ('i' in m.groupValues[2]) add(RegexOption.IGNORE_CASE)
            if ('m' in m.groupValues[2]) add(RegexOption.MULTILINE)
            if ('s' in m.groupValues[2]) add(RegexOption.DOT_MATCHES_ALL)
        }
        return runCatching { Regex(m.groupValues[1], options) }.getOrNull()
    }

    private val REGEX_KEY = Regex("^/(.+)/([gimsuy]*)$", RegexOption.DOT_MATCHES_ALL)

    fun normaliseTags(tags: List<String>): List<String> {
        val seen = HashSet<String>()
        return tags.map { it.trim() }.filter { it.isNotEmpty() && seen.add(it.lowercase()) }
    }

    private fun decodeBase64(text: String): String? {
        val compact = text.filterNot { it.isWhitespace() }
        return runCatching { String(Base64.getDecoder().decode(compact), Charsets.UTF_8) }
            .recoverCatching { String(Base64.getUrlDecoder().decode(compact), Charsets.UTF_8) }
            .getOrNull()
    }

    private fun base64(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun String?.clean(): String = this.orEmpty().replace("\r\n", "\n").replace('\r', '\n').trim()

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else content

    private fun JsonObject.str(name: String): String? = when (val v = this[name]) {
        is JsonPrimitive -> v.contentOrNullSafe()
        else -> null
    }

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.let {
        if (it is JsonNull) null else it.intOrNull ?: it.doubleOrNull?.toInt() ?: it.longOrNull?.toInt() ?: it.content.trim().toIntOrNull()
    }

    private fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.let {
        if (it is JsonNull) null else it.booleanOrNull ?: when (it.content.trim().lowercase()) {
            "1", "yes" -> true
            "0", "no" -> false
            else -> null
        }
    }

    private fun JsonObject.strings(name: String): List<String> = when (val v = this[name]) {
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe() }
        is JsonPrimitive -> v.contentOrNullSafe()?.split(',').orEmpty()
        else -> emptyList()
    }

    /** Keywords may be an array or one comma-separated string; regex keys are kept whole. */
    private fun JsonObject.keyList(name: String): List<String> = when (val v = this[name]) {
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe() }
        is JsonPrimitive -> v.contentOrNullSafe()?.let { s -> if (isRegexKey(s)) listOf(s) else s.split(',') }.orEmpty()
        else -> emptyList()
    }.map { it.trim() }.filter { it.isNotEmpty() }

    private fun randomId(): String = UUID.randomUUID().toString()

    private operator fun JsonObject.minus(key: String): Map<String, JsonElement> = filterKeys { it != key }
}
