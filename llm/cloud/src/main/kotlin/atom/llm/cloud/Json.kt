package atom.llm.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

private val EmptyArray = JsonArray(emptyList())
private val JsonMedia = "application/json".toMediaType()

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray = this[key] as? JsonArray ?: EmptyArray

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonElement.asObject(): JsonObject? = this as? JsonObject

internal fun JsonObject.toRequestBody(): RequestBody = toString().toRequestBody(JsonMedia)

internal fun parseJsonOrNull(text: String): JsonElement? =
    try {
        Json.parseToJsonElement(text)
    } catch (e: IllegalArgumentException) {
        null
    }

/**
 * Tool arguments as an object for wires that need structured input; malformed JSON (a call cut off at the output
 * limit) degrades to `{}`, and unquoted values are quoted, so the request body is always valid JSON.
 */
internal fun argumentsObject(argumentsJson: String): JsonObject =
    (parseJsonOrNull(argumentsJson) as? JsonObject)?.let { strictly(it) as JsonObject } ?: JsonObject(emptyMap())

internal fun normalizeArguments(raw: String): String = raw.trim().ifEmpty { "{}" }

private val LenientJson = Json { isLenient = true }

/**
 * Tool arguments as a JSON object string for wires that send them as text (OpenAI Chat Completions, Responses).
 * Valid arguments go out unchanged (the prompt cache stays warm). Anything else would make strict providers refuse
 * every later request of the conversation ("EOF while parsing a string"): a call cut off when the reply hit its
 * output limit, or one from a stored conversation, goes out as `{}` (its tool result says what happened), and
 * lenient JSON a model wrote (`{text: hi}`) goes out as the strict JSON it stands for.
 */
internal fun wireArguments(raw: String): String {
    val text = normalizeArguments(raw)
    // kotlinx accepts unquoted values even when not lenient, so an object that parses can still need quoting.
    val parsed = try {
        LenientJson.parseToJsonElement(text)
    } catch (e: IllegalArgumentException) {
        null
    } as? JsonObject ?: return "{}"
    val strict = strictly(parsed)
    return if (strict == parsed) text else strict.toString()
}

private val JsonNumber = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

/** [element] with its unquoted values (`hi` in `{text: hi}`) as the strings they stand for. */
private fun strictly(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.mapValues { strictly(it.value) })
    is JsonArray -> JsonArray(element.map(::strictly))
    is JsonPrimitive -> when {
        element.isString || element is JsonNull -> element
        element.content == "true" || element.content == "false" || JsonNumber.matches(element.content) -> element
        else -> JsonPrimitive(element.content)
    }
}
