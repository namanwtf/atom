package atom.ui.theme

import android.content.Context
import android.os.Build

/** True when the device can supply wallpaper (Monet) colours. Minimum SDK is 30, so this is Android 12+. */
val supportsDynamicColor: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * The wallpaper's primary key colour (system accent 1, tone 500) as ARGB, or null below Android 12 or when the
 * system does not expose it. Seeds non-Tonal-Spot palette styles for the "System" accent.
 */
fun systemSeedColor(context: Context): Long? {
    if (!supportsDynamicColor) return null
    return runCatching { context.getColor(android.R.color.system_accent1_500).toLong() and 0xFFFFFFFFL }.getOrNull()
}
