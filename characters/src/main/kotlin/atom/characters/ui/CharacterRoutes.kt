package atom.characters.ui

/** Routes this feature renders. Ids are store ids; "new" opens an empty editor. */
object CharacterRoutes {
    const val FEATURE_ID = "characters"
    const val LIBRARY = "characters/library"
    const val PERSONA = "characters/persona"
    const val NEW = "new"

    fun detail(id: String) = "characters/detail/$id"
    fun edit(id: String) = "characters/edit/$id"
    fun worldbook(id: String) = "characters/worldbook/$id"
    fun entry(id: String, entryId: String) = "characters/entry/$id/$entryId"
    fun story(id: String) = "characters/story/$id"

    sealed interface Parsed {
        data object Library : Parsed
        data object Persona : Parsed
        data class Detail(val id: String) : Parsed
        data class Edit(val id: String) : Parsed
        data class WorldbookList(val id: String) : Parsed
        data class Entry(val id: String, val entryId: String) : Parsed
        data class Story(val id: String) : Parsed
    }

    fun parse(route: String): Parsed? {
        val parts = route.split('/')
        if (parts.firstOrNull() != FEATURE_ID) return null
        return when (parts.getOrNull(1)) {
            "library" -> Parsed.Library
            "persona" -> Parsed.Persona
            "detail" -> parts.getOrNull(2)?.let { Parsed.Detail(it) }
            "edit" -> parts.getOrNull(2)?.let { Parsed.Edit(it) }
            "worldbook" -> parts.getOrNull(2)?.let { Parsed.WorldbookList(it) }
            "entry" -> if (parts.size >= 4) Parsed.Entry(parts[2], parts.subList(3, parts.size).joinToString("/")) else null
            "story" -> parts.getOrNull(2)?.let { Parsed.Story(it) }
            else -> null
        }
    }
}
