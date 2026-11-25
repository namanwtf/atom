package atom.voice.cloud

import android.content.SharedPreferences
import atom.voice.speech.SecretResolver
import atom.voice.speech.SpeechConfig
import atom.voice.speech.SpeechError
import atom.voice.speech.SpeechException
import atom.voice.speech.SpeechSecret
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class OssCredentials(val accessKeyId: String, val accessKeySecret: String)

/** The user's bucket for temporary recordings. Objects are private (no ACL header = bucket default). */
data class OssTarget(
    /** Signing region without the "oss-" prefix, e.g. cn-hangzhou. */
    val region: String,
    /** Endpoint host without bucket, e.g. oss-cn-hangzhou.aliyuncs.com. */
    val endpoint: String,
    val bucket: String,
    val prefix: String,
    val credentials: OssCredentials,
) {
    val host: String get() = "$bucket.$endpoint"

    fun newObjectKey(): String {
        val p = prefix.trim().trimStart('/').let { if (it.isEmpty() || it.endsWith("/")) it else "$it/" }
        return "${p}rec-${UUID.randomUUID()}.wav"
    }

    companion object {
        fun endpointFor(region: String, override: String): String =
            override.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
                .ifEmpty { "oss-${region.trim().removePrefix("oss-")}.aliyuncs.com" }

        suspend fun from(config: SpeechConfig, secrets: SecretResolver): OssTarget {
            if (config.ossBucket.isBlank()) throw SpeechException(SpeechError.MissingStorage, "bucket")
            val id = secrets.require(SpeechSecret.OssAccessKeyId)
            val secret = secrets.require(SpeechSecret.OssAccessKeySecret)
            val region = config.ossRegion.trim().removePrefix("oss-")
            return OssTarget(region, endpointFor(region, config.ossEndpoint), config.ossBucket.trim(), config.ossPrefix, OssCredentials(id, secret))
        }
    }
}

/** OSS Signature V4 (OSS4-HMAC-SHA256). V1 is being withdrawn for new accounts/buckets, so only V4 is used. */
object OssSigner {
    private const val ALGORITHM = "OSS4-HMAC-SHA256"
    const val UNSIGNED = "UNSIGNED-PAYLOAD"

    fun isoDate(nowMs: Long): String = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(nowMs))

    /** RFC 3986 encoding; "/" kept when [keepSlash]. */
    fun encode(value: String, keepSlash: Boolean = false): String {
        val sb = StringBuilder()
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~' || (keepSlash && ch == '/')) {
                sb.append(ch)
            } else {
                sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xF])
            }
        }
        return sb.toString()
    }

    fun canonicalUri(bucket: String, objectKey: String) = "/" + encode("$bucket/$objectKey", keepSlash = true)

    fun canonicalQuery(params: Map<String, String>): String =
        params.entries.map { encode(it.key) to encode(it.value) }.sortedBy { it.first }
            .joinToString("&") { (k, v) -> if (v.isEmpty()) k else "$k=$v" }

    /** [headers] are the signed headers (content-type, content-md5 and x-oss-*), any case. */
    fun canonicalRequest(method: String, uri: String, query: String, headers: Map<String, String>): String {
        val signed = headers.entries
            .map { it.key.lowercase(Locale.US) to it.value.trim() }
            .filter { (k, _) -> k == "content-type" || k == "content-md5" || k.startsWith("x-oss-") }
            .sortedBy { it.first }
        val canonicalHeaders = signed.joinToString("") { (k, v) -> "$k:$v\n" }
        return "$method\n$uri\n$query\n$canonicalHeaders\n\n$UNSIGNED"
    }

    fun scope(date: String, region: String) = "${date.substring(0, 8)}/$region/oss/aliyun_v4_request"

    fun stringToSign(isoDate: String, region: String, canonicalRequest: String): String =
        "$ALGORITHM\n$isoDate\n${scope(isoDate, region)}\n${sha256Hex(canonicalRequest)}"

    fun signature(secret: String, isoDate: String, region: String, stringToSign: String): String {
        val k1 = hmac(("aliyun_v4$secret").toByteArray(Charsets.UTF_8), isoDate.substring(0, 8))
        val k2 = hmac(k1, region)
        val k3 = hmac(k2, "oss")
        val key = hmac(k3, "aliyun_v4_request")
        return hmac(key, stringToSign).toHex()
    }

    /** Headers for a signed request (Authorization, x-oss-date, x-oss-content-sha256, plus [contentType] if any). */
    fun signedHeaders(target: OssTarget, method: String, objectKey: String, contentType: String?, nowMs: Long): Map<String, String> {
        val date = isoDate(nowMs)
        val headers = linkedMapOf("x-oss-date" to date, "x-oss-content-sha256" to UNSIGNED)
        if (contentType != null) headers["content-type"] = contentType
        val canonical = canonicalRequest(method, canonicalUri(target.bucket, objectKey), "", headers)
        val sig = signature(target.credentials.accessKeySecret, date, target.region, stringToSign(date, target.region, canonical))
        val credential = "${target.credentials.accessKeyId}/${scope(date, target.region)}"
        return headers + ("Authorization" to "$ALGORITHM Credential=$credential,Signature=$sig")
    }

    /** A time-limited GET URL so the transcription service can fetch the private object. */
    fun presignGet(target: OssTarget, objectKey: String, expiresSeconds: Int, now: Long): String {
        val date = isoDate(now)
        val params = linkedMapOf(
            "x-oss-signature-version" to ALGORITHM,
            "x-oss-credential" to "${target.credentials.accessKeyId}/${scope(date, target.region)}",
            "x-oss-date" to date,
            "x-oss-expires" to expiresSeconds.coerceIn(1, 604_800).toString(),
        )
        val query = canonicalQuery(params)
        val canonical = canonicalRequest("GET", canonicalUri(target.bucket, objectKey), query, emptyMap())
        val sig = signature(target.credentials.accessKeySecret, date, target.region, stringToSign(date, target.region, canonical))
        return "https://${target.host}/${encode(objectKey, keepSlash = true)}?$query&x-oss-signature=$sig"
    }

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray(Charsets.UTF_8))

    private fun sha256Hex(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

internal object OssClient {
    suspend fun put(http: OkHttpClient, target: OssTarget, objectKey: String, bytes: ByteArray, contentType: String) {
        val headers = OssSigner.signedHeaders(target, "PUT", objectKey, contentType, System.currentTimeMillis())
        val request = Request.Builder()
            .url("https://${target.host}/${OssSigner.encode(objectKey, keepSlash = true)}")
            .put(bytes.toRequestBody(contentType.toMediaType()))
        headers.forEach { (k, v) -> if (!k.equals("content-type", true)) request.header(k, v) }
        http.send(request.build()).close()
    }

    suspend fun delete(http: OkHttpClient, target: OssTarget, objectKey: String) {
        val headers = OssSigner.signedHeaders(target, "DELETE", objectKey, null, System.currentTimeMillis())
        val request = Request.Builder().url("https://${target.host}/${OssSigner.encode(objectKey, keepSlash = true)}").delete()
        headers.forEach { (k, v) -> request.header(k, v) }
        try {
            http.send(request.build()).close()
        } catch (e: SpeechException) {
            // 404 means it is already gone, which is what we want.
            if (e.detail?.startsWith("HTTP 404") != true) throw e
        }
    }
}

/**
 * Makes sure uploaded recordings never linger: each object is remembered before upload, deleted right after
 * transcription (retried a few times), and anything left over is retried before the next upload.
 */
class OssCleanup(private val prefs: () -> SharedPreferences?) {
    private val key = "oss.pending"

    fun remember(target: OssTarget, objectKey: String) = edit { it + entry(target, objectKey) }

    suspend fun deleteNow(http: OkHttpClient, target: OssTarget, objectKey: String) {
        repeat(3) { attempt ->
            val ok = runCatching { OssClient.delete(http, target, objectKey) }.isSuccess
            if (ok) {
                edit { it - entry(target, objectKey) }
                return
            }
            delay(400L * (attempt + 1))
        }
    }

    suspend fun retryPending(http: OkHttpClient, credentials: OssCredentials) {
        val pending = prefs()?.getStringSet(key, emptySet()).orEmpty()
        for (item in pending) {
            val parts = item.split('|')
            if (parts.size != 4) {
                edit { it - item }
                continue
            }
            val target = OssTarget(parts[0], parts[1], parts[2], "", credentials)
            if (runCatching { OssClient.delete(http, target, parts[3]) }.isSuccess) edit { it - item }
        }
    }

    val pendingCount: Int get() = prefs()?.getStringSet(key, emptySet())?.size ?: 0

    private fun entry(t: OssTarget, objectKey: String) = "${t.region}|${t.endpoint}|${t.bucket}|$objectKey"

    @Synchronized
    private fun edit(change: (Set<String>) -> Set<String>) {
        val p = prefs() ?: return
        val next = change(p.getStringSet(key, emptySet()).orEmpty().toSet())
        p.edit().putStringSet(key, next).apply()
    }
}
