package atom.web.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A small JSONPath subset for custom endpoints: `$.data.items[*].title`, `results`, `a.b[0].c`, `items[-1]`.
 * A leading `$` or `$.` is optional; `[*]` (or a bare `*` segment) fans out over an array or object.
 */
object JsonPath {
    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): JsonElement = lenient.parseToJsonElement(text)

    internal sealed interface Step {
        data class Key(val name: String) : Step
        data class Index(val index: Int) : Step
        data object All : Step
    }

    internal fun steps(path: String): List<Step> {
        var p = path.trim()
        if (p.startsWith("$")) p = p.removePrefix("$")
        val out = ArrayList<Step>()
        var i = 0
        val name = StringBuilder()
        fun flush() {
            if (name.isNotEmpty()) {
                val n = name.toString()
                out += if (n == "*") Step.All else Step.Key(n)
                name.clear()
            }
        }
        while (i < p.length) {
            val c = p[i]
            when (c) {
                '.' -> { flush(); i++ }
                '[' -> {
                    flush()
                    val end = p.indexOf(']', i)
                    require(end > i) { "Unclosed '[' in path '$path'" }
                    val inner = p.substring(i + 1, end).trim()
                    out += when {
                        inner == "*" -> Step.All
                        inner.startsWith("'") || inner.startsWith("\"") -> Step.Key(inner.substring(1, inner.length - 1))
                        else -> Step.Index(inner.toIntOrNull() ?: throw IllegalArgumentException("Bad index '$inner' in path '$path'"))
                    }
                    i = end + 1
                }
                else -> { name.append(c); i++ }
            }
        }
        flush()
        return out
    }

    fun select(root: JsonElement, path: String): List<JsonElement> {
        var current = listOf(root)
        for (step in steps(path)) {
            current = current.flatMap { el ->
                when (step) {
                    is Step.Key -> listOfNotNull((el as? JsonObject)?.get(step.name))
                    is Step.Index -> (el as? JsonArray)?.let { arr ->
                        val idx = if (step.index < 0) arr.size + step.index else step.index
                        listOfNotNull(arr.getOrNull(idx))
                    } ?: emptyList()
                    Step.All -> when (el) {
                        is JsonArray -> el.toList()
                        is JsonObject -> el.values.toList()
                        else -> emptyList()
                    }
                }
            }
            if (current.isEmpty()) break
        }
        return current.filter { it !is JsonNull }
    }

    fun first(root: JsonElement, path: String): JsonElement? = select(root, path).firstOrNull()

    /** The array at [path] as items; a fan-out path returns the matched elements. Blank path means the root. */
    fun items(root: JsonElement, path: String): List<JsonElement> {
        if (path.isBlank() || path.trim() == "$") return (root as? JsonArray)?.toList() ?: listOf(root)
        val found = select(root, path)
        return if (found.size == 1 && found[0] is JsonArray) (found[0] as JsonArray).toList() else found
    }

    fun string(root: JsonElement, path: String): String? {
        if (path.isBlank()) return null
        val el = first(root, path) ?: return null
        return when (el) {
            is JsonPrimitive -> el.content
            else -> el.toString()
        }.takeIf { it.isNotBlank() }
    }
}
