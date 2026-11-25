package atom.voice.speech

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal val SpeechJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun parseObject(raw: String): JsonObject? =
    runCatching { SpeechJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()

/** Walks `a.b[0].c` style paths. */
internal fun JsonElement?.at(path: String): JsonElement? {
    var cur: JsonElement? = this
    for (part in path.split('.')) {
        if (cur == null) return null
        val bracket = part.indexOf('[')
        val name = if (bracket >= 0) part.substring(0, bracket) else part
        if (name.isNotEmpty()) cur = (cur as? JsonObject)?.get(name)
        if (bracket >= 0) {
            val index = part.substring(bracket + 1, part.indexOf(']')).toIntOrNull() ?: return null
            cur = (cur as? JsonArray)?.getOrNull(index)
        }
    }
    return cur
}

internal fun JsonElement?.str(path: String): String? = (at(path) as? JsonPrimitive)?.contentOrNull

internal fun JsonElement?.int(path: String): Int? = (at(path) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

/** A chat-completions `content` that is either a string or an array of `{type:text,text}` parts. */
internal fun JsonElement?.contentText(): String? = when (this) {
    is JsonPrimitive -> contentOrNull
    is JsonArray -> mapNotNull { it.str("text") }.joinToString("").takeIf { it.isNotEmpty() }
    else -> null
}

/**
 * Splits a stream of concatenated JSON objects (optionally separated by whitespace, newlines or nothing) into
 * complete objects, string- and escape-aware. Feed bytes as they arrive; complete objects come back in order.
 */
class JsonObjectSplitter {
    private val buf = StringBuilder()
    private var depth = 0
    private var inString = false
    private var escaped = false
    private var start = -1
    private var scanned = 0

    fun feed(chunk: String): List<String> {
        buf.append(chunk)
        val out = mutableListOf<String>()
        var i = scanned
        while (i < buf.length) {
            val c = buf[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> if (depth > 0) inString = true
                    '{' -> {
                        if (depth == 0) start = i
                        depth++
                    }
                    '}' -> if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            out += buf.substring(start, i + 1)
                            buf.delete(0, i + 1)
                            i = -1
                            start = -1
                        }
                    }
                }
            }
            i++
        }
        scanned = buf.length
        if (depth == 0 && start < 0) {
            buf.setLength(0)
            scanned = 0
        }
        return out
    }
}
