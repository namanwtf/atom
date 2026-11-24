package atom.core.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Small builder for the JSON Schema objects tools declare. */
class SchemaBuilder {
    private val properties = linkedMapOf<String, JsonElement>()
    private val required = mutableListOf<String>()

    fun string(name: String, description: String, required: Boolean = false, enum: List<String>? = null) =
        prop(name, required, buildMap {
            put("type", JsonPrimitive("string"))
            put("description", JsonPrimitive(description))
            if (enum != null) put("enum", JsonArray(enum.map(::JsonPrimitive)))
        })

    fun integer(name: String, description: String, required: Boolean = false) =
        prop(name, required, mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive(description)))

    fun number(name: String, description: String, required: Boolean = false) =
        prop(name, required, mapOf("type" to JsonPrimitive("number"), "description" to JsonPrimitive(description)))

    fun boolean(name: String, description: String, required: Boolean = false) =
        prop(name, required, mapOf("type" to JsonPrimitive("boolean"), "description" to JsonPrimitive(description)))

    private fun prop(name: String, isRequired: Boolean, body: Map<String, JsonElement>) {
        properties[name] = JsonObject(body)
        if (isRequired) required += name
    }

    fun build() = JsonObject(buildMap {
        put("type", JsonPrimitive("object"))
        put("properties", JsonObject(properties))
        if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
    })
}

fun schema(block: SchemaBuilder.() -> Unit = {}): JsonObject = SchemaBuilder().apply(block).build()

class ToolArgumentException(message: String) : IllegalArgumentException(message)

fun JsonObject.string(name: String): String =
    optString(name) ?: throw ToolArgumentException("Missing required argument '$name'")

fun JsonObject.optString(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content

fun JsonObject.int(name: String): Int =
    optInt(name) ?: throw ToolArgumentException("Missing required integer '$name'")

fun JsonObject.optInt(name: String): Int? =
    (this[name] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() ?: it.content.toIntOrNull() }

fun JsonObject.optDouble(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull

fun JsonObject.optBoolean(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.content.toBooleanStrictOrNull() }
