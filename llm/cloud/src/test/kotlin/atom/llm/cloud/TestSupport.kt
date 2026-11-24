package atom.llm.cloud

import atom.core.llm.LlmEngine
import atom.core.llm.LlmEvent
import atom.core.llm.LlmException
import atom.core.llm.LlmRequest
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

internal fun sseBody(vararg data: String): String = data.joinToString("") { "data: $it\n\n" }

internal fun namedSseBody(vararg events: Pair<String, String>): String =
    events.joinToString("") { (type, data) -> "event: $type\ndata: $data\n\n" }

internal fun MockWebServer.enqueueSse(body: String) =
    enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(body).build())

internal fun MockWebServer.enqueueJson(code: Int, body: String) =
    enqueue(MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build())

internal fun LlmEngine.collect(request: LlmRequest): List<LlmEvent> = runBlocking { stream(request).toList() }

internal fun LlmEngine.failure(request: LlmRequest): LlmException =
    assertFailsWith<LlmException> { collect(request) }

internal fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(body!!.utf8()).jsonObject

internal fun List<LlmEvent>.text(): String = filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text }

internal fun List<LlmEvent>.calls() = filterIsInstance<LlmEvent.ToolCallReady>().map { it.call }

internal fun List<LlmEvent>.finished() = filterIsInstance<LlmEvent.Finished>()

internal val weatherSchema: JsonObject = Json.parseToJsonElement(
    """{"type":"object","properties":{"city":{"type":"string","description":"City name"},
       "unit":{"type":["string","null"],"enum":["c","f"]}},"required":["city","missing"],
       "additionalProperties":false,"${'$'}schema":"http://json-schema.org/draft-07/schema#"}""",
).jsonObject
