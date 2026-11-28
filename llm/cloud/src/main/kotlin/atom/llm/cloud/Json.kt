package atom.llm.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

/** Tool arguments as an object for wires that need structured input; malformed JSON degrades to `{}`. */
internal fun argumentsObject(argumentsJson: String): JsonObject =
    parseJsonOrNull(argumentsJson) as? JsonObject ?: JsonObject(emptyMap())

internal fun normalizeArguments(raw: String): String = raw.trim().ifEmpty { "{}" }

/**
 * Tool arguments as they go back to the provider in the history: a call whose arguments are not a JSON object (one
 * the output limit cut off mid-way) goes as `{}`, because providers refuse the whole request over invalid JSON there
 * (DeepSeek answers HTTP 400). The call's result already tells the model what happened to it.
 */
internal fun wireArguments(raw: String): String {
    val normalized = normalizeArguments(raw)
    return if (parseJsonOrNull(normalized) is JsonObject) normalized else "{}"
}
