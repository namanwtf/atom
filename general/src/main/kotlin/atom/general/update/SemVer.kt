package atom.general.update

/**
 * Semantic version (semver.org 2.0 precedence). Parsing is lenient for release tags: a leading "v", missing
 * minor/patch ("1.4") and build metadata ("+abc") are accepted; build metadata is ignored for ordering.
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String> = emptyList(),
) : Comparable<SemVer> {

    val isPreRelease: Boolean get() = preRelease.isNotEmpty()

    override fun compareTo(other: SemVer): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        // A release ranks above any of its pre-releases.
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            return compareValues(other.preRelease.size.coerceAtMost(1), preRelease.size.coerceAtMost(1))
        }
        for (i in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val c = compareIdentifier(preRelease[i], other.preRelease[i])
            if (c != 0) return c
        }
        return compareValues(preRelease.size, other.preRelease.size)
    }

    override fun toString(): String =
        "$major.$minor.$patch" + if (preRelease.isEmpty()) "" else "-" + preRelease.joinToString(".")

    companion object {
        private val NUMERIC = Regex("0|[1-9][0-9]*")
        private val IDENT = Regex("[0-9A-Za-z-]+")

        fun parse(text: String): SemVer? {
            var s = text.trim()
            if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1)
            s = s.substringBefore('+')
            val core = s.substringBefore('-')
            val pre = if (s.contains('-')) s.substringAfter('-') else null
            val parts = core.split('.')
            if (parts.isEmpty() || parts.size > 3) return null
            val nums = parts.map { p -> if (p.isNotEmpty() && p.all(Char::isDigit) && p.length <= 9) p.toInt() else return null }
            val ids = when (pre) {
                null -> emptyList()
                else -> pre.split('.').also { list ->
                    if (list.any { it.isEmpty() || !IDENT.matches(it) }) return null
                    if (list.any { it.all(Char::isDigit) && !NUMERIC.matches(it) }) return null
                }
            }
            return SemVer(nums[0], nums.getOrElse(1) { 0 }, nums.getOrElse(2) { 0 }, ids)
        }

        private fun compareIdentifier(a: String, b: String): Int {
            val an = a.all(Char::isDigit)
            val bn = b.all(Char::isDigit)
            return when {
                an && bn -> compareValues(a.toBigInteger(), b.toBigInteger())
                an -> -1 // numeric identifiers rank below alphanumeric ones
                bn -> 1
                else -> a.compareTo(b)
            }
        }
    }
}
