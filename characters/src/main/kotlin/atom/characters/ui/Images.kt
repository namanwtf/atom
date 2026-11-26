package atom.characters.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atom.characters.model.Character
import atom.characters.model.CharacterOrigin
import atom.characters.session.CharacterSession
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** Off-main-thread, downsampled image decoding with a small in-memory cache. */
internal object CharacterImages {
    private const val MAX_STORED_EDGE = 1024

    private val cache = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 16).coerceAtMost(48L * 1024 * 1024).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun cached(key: String): ImageBitmap? = cache.get(key)

    suspend fun thumbnail(file: File, key: String, widthPx: Int): ImageBitmap? {
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val w = info.size.width
                    if (w > widthPx && widthPx > 0) {
                        val scale = widthPx.toFloat() / w
                        decoder.setTargetSize(widthPx, (info.size.height * scale).roundToInt().coerceAtLeast(1))
                    }
                }.asImageBitmap()
            }.getOrNull()?.also { cache.put(key, it) }
        }
    }

    /** Decodes a picked image, downsampled to at most 1024 px on its long edge, and re-encodes it as PNG. */
    suspend fun normalisedPng(context: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val edge = max(info.size.width, info.size.height)
                if (edge > MAX_STORED_EDGE) {
                    val scale = MAX_STORED_EDGE.toFloat() / edge
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                bitmap.recycle()
                out.toByteArray()
            }
        }.getOrNull()
    }

    /** A plain card image for exporting characters that have none: the monogram (or Lumen's orb) on black. */
    suspend fun placeholderPng(c: Character): ByteArray = withContext(Dispatchers.Default) {
        val w = 512
        val h = 683
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.BLACK)
        if (c.origin == CharacterOrigin.BuiltIn) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(w / 2f, h / 2f, w * 0.42f, intArrayOf(0xFFFFFFFF.toInt(), 0x55FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            }
            canvas.drawCircle(w / 2f, h / 2f, w * 0.42f, paint)
        } else {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = 260f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawText(monogram(c), w / 2f, h / 2f - (paint.ascent() + paint.descent()) / 2, paint)
        }
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    fun monogram(c: Character): String = c.displayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}

/**
 * A round avatar of the character for other screens (the home chat header, empty state and conversation list):
 * the same art as the library cards, clipped to a circle.
 */
@Composable
fun CharacterAvatar(character: Character, modifier: Modifier = Modifier) = CharacterArt(character, modifier, corner = 999.dp)

/**
 * The character's art in a rounded card: the stored image (decoded off the main thread at display size), the orb
 * for the built-in companion, or a monogram. Static: nothing animates.
 */
@Composable
internal fun CharacterArt(c: Character, modifier: Modifier = Modifier, corner: Dp = 22.dp) {
    val file = remember(c.image, c.imageVersion) { CharacterSession.store.imageFile(c) }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(corner)
    val colors = MaterialTheme.colorScheme
    // The built-in companion's orb is a white glow: in the light theme it sits on the inverse (graphite) surface,
    // as on its exported card, so it does not vanish into the light grey.
    val orbOnDark = file == null && c.origin == CharacterOrigin.BuiltIn && colors.surface.luminance() > 0.5f
    val background = if (orbOnDark) colors.inverseSurface else colors.surfaceContainerHigh
    BoxWithConstraints(modifier.clip(shape).background(background)) {
        val boxWidth = maxWidth
        val widthPx = with(LocalDensity.current) { boxWidth.toPx() }.roundToInt().coerceIn(64, 1440)
        val key = "${c.id}:${c.imageVersion}:$widthPx"
        val bitmap by produceState(CharacterImages.cached(key), file, key) {
            if (file != null && value == null) value = CharacterImages.thumbnail(file, key, widthPx)
        }
        val image = bitmap
        when {
            file != null && image != null -> Image(image, contentDescription = c.displayName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            file != null -> Unit
            c.origin == CharacterOrigin.BuiltIn -> Orb(Modifier.fillMaxSize())
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    CharacterImages.monogram(c),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (boxWidth.value * 0.34f).coerceIn(18f, 160f).sp,
                )
            }
        }
    }
}

@Composable
private fun Orb(modifier: Modifier) {
    Canvas(modifier) {
        val r = size.minDimension * 0.42f
        drawCircle(
            Brush.radialGradient(
                0f to Color.White,
                0.35f to Color.White.copy(alpha = 0.33f),
                1f to Color.Transparent,
                center = Offset(size.width / 2, size.height / 2),
                radius = r,
            ),
            radius = r,
        )
    }
}
