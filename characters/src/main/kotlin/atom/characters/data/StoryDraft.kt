package atom.characters.data

/** Compares the story memory the editor started from with what is stored now and the user's draft. */
object StoryDraft {
    enum class Result { Same, AdoptRemote, Conflict }

    /**
     * [base] is what the editor loaded, [current] what is stored now, [draft] the editor text. A change made by a
     * conversation is adopted when there is no draft, and is a conflict (the draft is kept) when there is one.
     */
    fun check(base: String, current: String, draft: String): Result = when {
        current == base -> Result.Same
        draft == base || draft == current -> Result.AdoptRemote
        else -> Result.Conflict
    }
}
