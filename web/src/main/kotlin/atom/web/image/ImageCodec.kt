package atom.web.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import android.media.ExifInterface
import java.io.ByteArrayOutputStream

/** Decodes, orients and downscales images for the model (long side ≤ [MAX_SIDE]). */
object ImageCodec {
    const val MAX_SIDE = 1568

    data class Encoded(val mime: String, val base64: String, val width: Int, val height: Int, val bytes: Int)

    fun bounds(bytes: ByteArray): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    fun decodeScaled(bytes: ByteArray, maxSide: Int = MAX_SIDE): Bitmap? {
        val (w, h) = bounds(bytes) ?: return null
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val oriented = orient(bmp, bytes)
        val long = maxOf(oriented.width, oriented.height)
        if (long <= maxSide) return oriented
        val scale = maxSide.toFloat() / long
        val out = Bitmap.createScaledBitmap(oriented, (oriented.width * scale).toInt().coerceAtLeast(1), (oriented.height * scale).toInt().coerceAtLeast(1), true)
        if (out !== oriented) oriented.recycle()
        return out
    }

    private fun orient(bmp: Bitmap, bytes: ByteArray): Bitmap {
        val rotation = runCatching {
            when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (rotation == 0f) return bmp
        val m = Matrix().apply { postRotate(rotation) }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    fun encode(bmp: Bitmap, quality: Int = 85, png: Boolean = false): Encoded {
        val out = ByteArrayOutputStream()
        bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, quality, out)
        val b = out.toByteArray()
        return Encoded(if (png) "image/png" else "image/jpeg", Base64.encodeToString(b, Base64.NO_WRAP), bmp.width, bmp.height, b.size)
    }

    /** For the model: JPEG, long side ≤ [maxSide]. */
    fun forModel(bytes: ByteArray, maxSide: Int = MAX_SIDE): Encoded? {
        val bmp = decodeScaled(bytes, maxSide) ?: return null
        return try { encode(bmp) } finally { bmp.recycle() }
    }

    fun mimeOf(bytes: ByteArray): String = when {
        bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
        bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size > 11 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        bytes.size > 3 && String(bytes, 0, 3, Charsets.US_ASCII) == "GIF" -> "image/gif"
        else -> "image/png"
    }
}
