package atom.extend.skills

import atom.extend.Frontmatter

data class SkillManifest(val name: String, val description: String, val body: String) {
    /** First sentence or line of the description, short enough for the system prompt. */
    val oneLine: String get() {
        val flat = description.replace(Regex("\\s+"), " ").trim()
        val sentence = flat.indexOf(". ").takeIf { it in 20..MAX_ONE_LINE }?.let { flat.take(it + 1) } ?: flat
        return if (sentence.length <= MAX_ONE_LINE) sentence else sentence.take(MAX_ONE_LINE - 1).trimEnd() + "…"
    }

    fun render(): String = "---\nname: $name\ndescription: ${Frontmatter.quote(description)}\n---\n\n${body.trim()}\n"

    companion object {
        const val FILE = "SKILL.md"
        const val MAX_NAME = 64
        const val MAX_DESCRIPTION = 1024
        private const val MAX_ONE_LINE = 160

        fun parse(text: String): Result<SkillManifest> {
            val doc = Frontmatter.parse(text)
                ?: return Result.failure(SkillException("SKILL.md must start with a --- frontmatter block holding name and description."))
            val name = doc.fields["name"]?.trim().orEmpty()
            val description = doc.fields["description"]?.trim().orEmpty()
            validateName(name)?.let { return Result.failure(SkillException(it)) }
            if (description.isEmpty()) return Result.failure(SkillException("SKILL.md frontmatter needs a description."))
            if (description.length > MAX_DESCRIPTION) {
                return Result.failure(SkillException("The description is longer than $MAX_DESCRIPTION characters."))
            }
            return Result.success(SkillManifest(name, description, doc.body))
        }

        fun validateName(name: String): String? = when {
            name.isEmpty() -> "SKILL.md frontmatter needs a name."
            name.length > MAX_NAME -> "The skill name is longer than $MAX_NAME characters."
            !Frontmatter.KEBAB.matches(name) -> "The skill name \"$name\" must be lowercase letters, digits and hyphens (kebab-case)."
            else -> null
        }
    }
}

class SkillException(message: String) : Exception(message)

enum class SkillOrigin { BuiltIn, User }

data class SkillInfo(
    val manifest: SkillManifest,
    val origin: SkillOrigin,
    val enabled: Boolean,
    val sizeBytes: Long = 0,
    val fileCount: Int = 1,
) {
    val name: String get() = manifest.name
}
