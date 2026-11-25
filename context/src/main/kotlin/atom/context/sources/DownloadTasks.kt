package atom.context.sources

/** One system download task (DownloadManager), whatever its state. */
internal data class DownloadTask(
    val id: Long,
    val title: String,
    val status: String,
    val currentBytes: Long,
    val totalBytes: Long,
    val modifiedMillis: Long,
    val app: String?,
    val path: String?,
) {
    val inProgress: Boolean get() = status in IN_PROGRESS

    companion object {
        val IN_PROGRESS = setOf("pending", "running", "paused", "waiting for network", "waiting for Wi-Fi", "waiting to retry")
    }
}

/**
 * Turns DownloadManager rows into [DownloadTask]s. Atom's own tasks come from the public DownloadManager columns;
 * with root, every app's tasks come from `content query` on the downloads provider, which uses its raw columns and
 * HTTP-like status codes. Pure, so it is unit-tested.
 */
internal object DownloadTasks {
    /** Projection for the provider's `all_downloads` table (raw column names). */
    val PROVIDER_COLUMNS = listOf("_id", "title", "status", "current_bytes", "total_bytes", "lastmod", "notificationpackage", "_data", "uri")

    const val PROVIDER_URI = "content://downloads/all_downloads"

    fun queryCommand(): String = "content query --uri $PROVIDER_URI --projection " + PROVIDER_COLUMNS.joinToString(":") +
        " --sort \"lastmod DESC\""

    /** DownloadManager.STATUS_* (public API) with its reason code. */
    fun publicStatus(status: Int, reason: Int): String = when (status) {
        1 -> "pending"
        2 -> "running"
        4 -> when (reason) {
            2 -> "waiting for network"
            3 -> "waiting for Wi-Fi"
            1 -> "waiting to retry"
            else -> "paused"
        }
        8 -> "completed"
        16 -> "failed" + failReason(reason)?.let { " ($it)" }.orEmpty()
        else -> "unknown ($status)"
    }

    /** The provider's raw status codes (190-199 in progress, 200 done, 4xx/5xx failed, 490 cancelled). */
    fun providerStatus(code: Int): String = when (code) {
        190 -> "pending"
        192 -> "running"
        193 -> "paused"
        194 -> "waiting to retry"
        195 -> "waiting for network"
        196 -> "waiting for Wi-Fi"
        198 -> "failed (not enough space)"
        199 -> "failed (storage not found)"
        200 -> "completed"
        490 -> "cancelled"
        488 -> "failed (file already exists)"
        492 -> "failed (file error)"
        495 -> "failed (connection error)"
        497 -> "failed (too many retries)"
        in 400..599 -> "failed (HTTP $code)"
        else -> "unknown ($code)"
    }

    private fun failReason(reason: Int): String? = when (reason) {
        1001 -> "file error"
        1002 -> "unhandled HTTP code"
        1004 -> "connection error"
        1005 -> "too many redirects"
        1006 -> "not enough space"
        1007 -> "storage not found"
        1008 -> "cannot resume"
        1009 -> "file already exists"
        in 400..599 -> "HTTP $reason"
        else -> null
    }

    /**
     * Parses `content query` output: one "Row: N key=value, key=value" line per task. Values can contain ", ", so a
     * value runs until the next known column name.
     */
    fun parseProviderRows(output: String): List<DownloadTask> {
        val keys = PROVIDER_COLUMNS.joinToString("|") { Regex.escape(it) }
        val field = Regex("""(?:^|, )($keys)=""")
        return output.lineSequence().filter { it.startsWith("Row:") }.mapNotNull { line ->
            val body = line.substringAfter(' ').substringAfter(' ')
            val matches = field.findAll(body).toList()
            if (matches.isEmpty()) return@mapNotNull null
            val values = HashMap<String, String>()
            matches.forEachIndexed { i, m ->
                val end = matches.getOrNull(i + 1)?.range?.first ?: body.length
                values[m.groupValues[1]] = body.substring(m.range.last + 1, end)
            }
            fun v(k: String) = values[k]?.takeIf { it != "NULL" && it.isNotEmpty() }
            val id = v("_id")?.toLongOrNull() ?: return@mapNotNull null
            DownloadTask(
                id = id,
                title = v("title") ?: v("uri")?.substringAfterLast('/')?.substringBefore('?') ?: "download $id",
                status = providerStatus(v("status")?.toIntOrNull() ?: -1),
                currentBytes = v("current_bytes")?.toLongOrNull() ?: 0,
                totalBytes = v("total_bytes")?.toLongOrNull() ?: -1,
                modifiedMillis = v("lastmod")?.toLongOrNull() ?: 0,
                app = v("notificationpackage"),
                path = v("_data"),
            )
        }.toList()
    }

    /** "45% of 12.0 MB" while running, the size when done. */
    fun progress(t: DownloadTask, human: (Long) -> String): String = when {
        t.totalBytes > 0 && t.inProgress -> "${(t.currentBytes * 100 / t.totalBytes).coerceIn(0, 100)}% of ${human(t.totalBytes)}"
        t.totalBytes > 0 -> human(t.totalBytes)
        t.currentBytes > 0 -> human(t.currentBytes) + " so far"
        else -> "size unknown"
    }
}
