package atom.context.sources

/**
 * Pulls only the one-time code out of a message. A code is a 4-8 digit token (or "123-456") in a message that talks
 * about a code; amounts, dates, phone and account numbers are skipped, and the candidate nearest a keyword wins.
 */
object Otp {
    private val keywords = listOf(
        "otp", "one-time", "one time", "verification", "verify", "code", "passcode", "password", "pin", "2fa",
        "authentication", "login", "log in", "sign in", "sign-in", "security", "token",
        "验证码", "校验码", "动态码", "確認碼", "認証", "コード", "인증", "कोड", "ओटीपी", "código", "codigo", "kode",
    )

    private val candidate = Regex("(?<![\\w.,₹$€£¥/:#*-])(\\d{4,8}|\\d{3}[- ]\\d{3})(?![\\w,/:]|\\.\\d)")
    private val prefixed = Regex("\\b[A-Z]{1,3}-(\\d{4,8})\\b")
    private val unitAfter = Regex("^\\s?(%|rs\\b|inr\\b|usd\\b|mb\\b|gb\\b|kb\\b|am\\b|pm\\b|hrs?\\b|mins?\\b)", RegexOption.IGNORE_CASE)
    private val amountBefore = Regex("(rs\\.?|inr|usd|eur|gbp|aed|sgd|\\$|₹|€|£|¥|amt|amount|balance|bal|debited|credited|paid)\\s*$", RegexOption.IGNORE_CASE)
    private val accountBefore = Regex("(a/c|acct|account|card|ending|ending with|x{2,}|\\*{2,}|no\\.?|number|tel|phone|call|mobile|ref|order|id)\\s*[:#]?\\s*$", RegexOption.IGNORE_CASE)
    private val dateAround = Regex("[/.]|\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)", RegexOption.IGNORE_CASE)

    fun containsKeyword(text: String): Boolean {
        val t = text.lowercase()
        return keywords.any { t.contains(it) }
    }

    /** The code, or null if [text] does not look like a verification message. */
    fun extract(text: String): String? {
        if (!containsKeyword(text)) return null
        prefixed.find(text)?.let { return it.groupValues[1] }
        val lower = text.lowercase()
        val keywordAt = keywords.flatMap { k -> Regex(Regex.escape(k)).findAll(lower).map { it.range.first }.toList() }
        val found = candidate.findAll(text).mapNotNull { m ->
            val start = m.range.first
            val end = m.range.last + 1
            val before = text.substring(maxOf(0, start - 14), start)
            val after = text.substring(end, minOf(text.length, end + 6))
            if (amountBefore.containsMatchIn(before) || accountBefore.containsMatchIn(before) || unitAfter.containsMatchIn(after)) {
                return@mapNotNull null
            }
            val digits = m.groupValues[1].filter(Char::isDigit)
            if (digits.length == 4 && digits.toInt() in 1900..2099) {
                val around = text.substring(maxOf(0, start - 6), start) + text.substring(end, minOf(text.length, end + 6))
                if (dateAround.containsMatchIn(around)) return@mapNotNull null
            }
            val distance = keywordAt.minOfOrNull { k -> if (k <= start) start - k else (k - end) * 2 } ?: Int.MAX_VALUE
            digits to distance
        }.toList()
        return found.minByOrNull { it.second }?.first
    }
}
