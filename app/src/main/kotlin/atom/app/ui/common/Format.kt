package atom.app.ui.common

import java.util.Locale
import kotlin.math.roundToInt

fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "?"
    bytes >= 1_000_000_000L -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format(Locale.getDefault(), "%.0f MB", bytes / 1_000_000.0)
    else -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1_000.0)
}

fun formatDuration(ms: Long): String = when {
    ms < 1_000 -> "$ms ms"
    ms < 60_000 -> String.format(Locale.getDefault(), "%.1f s", ms / 1000.0)
    else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
}

fun formatTokens(n: Int): String = when {
    n >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(Locale.getDefault(), "%.1fk", n / 1_000.0)
    else -> n.toString()
}

/** `read_screen` → `Read screen`. Model-facing tool names stay as they are; this is display only. */
fun humanizeTool(name: String): String {
    val words = name.replace('-', '_').split('_').filter { it.isNotEmpty() }
    if (words.isEmpty()) return name
    return words.joinToString(" ").replaceFirstChar { it.titlecase(Locale.getDefault()) }
}

/** Phones report slightly less than their marketed RAM; snap up to the nearest common size. */
fun marketedRamGb(totalBytes: Long): Int {
    val gib = totalBytes / (1024.0 * 1024.0 * 1024.0)
    val sizes = intArrayOf(2, 3, 4, 6, 8, 10, 12, 16, 18, 20, 24, 32)
    return sizes.firstOrNull { it >= gib - 0.05 } ?: gib.roundToInt()
}
