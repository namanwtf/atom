package atom.device.apps

import java.text.Normalizer

internal data class AppEntry(val label: String, val packageName: String)

internal data class AppMatch(val app: AppEntry, val score: Int)

/**
 * Resolves what a model or user calls an app ("whatsapp", "Google maps", "settings", "com.android.chrome")
 * to installed launcher entries. Scores are coarse tiers so ties are meaningful.
 */
internal object AppMatcher {
    fun rank(query: String, apps: List<AppEntry>): List<AppMatch> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val qTokens = tokens(query)
        return apps.mapNotNull { app ->
            val s = score(query.trim(), q, qTokens, app)
            if (s > 0) AppMatch(app, s) else null
        }.sortedWith(compareByDescending<AppMatch> { it.score }.thenBy { it.app.label.length }.thenBy { it.app.label })
    }

    /** Best match, or null when nothing plausible matches. */
    fun best(query: String, apps: List<AppEntry>): AppMatch? = rank(query, apps).firstOrNull()

    private fun score(raw: String, q: String, qTokens: List<String>, app: AppEntry): Int {
        if (raw.equals(app.packageName, ignoreCase = true)) return 120
        val label = normalize(app.label)
        val pkg = app.packageName.lowercase()
        val labelTokens = tokens(app.label)
        return when {
            label == q -> 100
            label.startsWith(q) -> 90
            qTokens.isNotEmpty() && qTokens.all { t -> labelTokens.any { it.startsWith(t) } } -> 80
            label.contains(q) -> 70
            q.length >= 3 && pkg.split('.').any { it == q } -> 65
            q.length >= 4 && normalize(pkg).contains(q) -> 60
            q.length >= 4 && withinTypos(q, label) -> 50
            q.length >= 3 && isSubsequence(q, label) && q.length * 2 >= label.length -> 40
            else -> 0
        }
    }

    private fun withinTypos(q: String, label: String): Boolean {
        val allowed = if (q.length >= 8) 2 else 1
        if (kotlin.math.abs(q.length - label.length) > allowed) return false
        return levenshtein(q, label) <= allowed
    }

    private val marks = Regex("\\p{M}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "").lowercase()

    internal fun normalize(s: String): String = fold(s).filter { it.isLetterOrDigit() }

    private fun tokens(s: String): List<String> = fold(s).split(separators).filter { it.isNotEmpty() }

    private fun isSubsequence(q: String, s: String): Boolean {
        var i = 0
        for (c in s) if (i < q.length && q[i] == c) i++
        return i == q.length
    }

    /** Edit distance where swapping two adjacent letters counts once (optimal string alignment). */
    internal fun levenshtein(a: String, b: String): Int {
        val d = Array(a.length + 1) { i -> IntArray(b.length + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
                }
            }
        }
        return d[a.length][b.length]
    }
}
