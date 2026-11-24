package atom.llm.cloud

import atom.core.llm.LlmException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer

class ListModelsTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() = server.close()

    private fun list(providerId: String, path: String) =
        runBlocking { CloudEngines.listModels(Providers.byId(providerId)!!, "key-123456", server.url(path).toString()) }

    @Test
    fun openAiCompatibleReturnsSortedIds() {
        server.enqueueJson(200, """{"object":"list","data":[{"id":"zeta"},{"id":"alpha"},{"id":"mid"}]}""")

        assertEquals(listOf("alpha", "mid", "zeta"), list("openai", "/v1"))
        val recorded = server.takeRequest()
        assertEquals("/v1/models", recorded.url.encodedPath)
        assertEquals("Bearer key-123456", recorded.headers["Authorization"])
    }

    @Test
    fun anthropicFollowsPagination() {
        server.enqueueJson(200, """{"data":[{"id":"claude-b"}],"has_more":true,"last_id":"claude-b"}""")
        server.enqueueJson(200, """{"data":[{"id":"claude-a"}],"has_more":false,"last_id":"claude-a"}""")

        assertEquals(listOf("claude-a", "claude-b"), list("anthropic", "/v1"))
        assertEquals("key-123456", server.takeRequest().headers["x-api-key"])
        assertEquals("claude-b", server.takeRequest().url.queryParameter("after_id"))
    }

    @Test
    fun geminiKeepsGenerateContentModels() {
        server.enqueueJson(
            200,
            """{"models":[
                {"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent","countTokens"]},
                {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]}]}""",
        )

        assertEquals(listOf("gemini-3.8-flash"), list("gemini", "/v1beta"))
        assertEquals("/v1beta/models", server.takeRequest().url.encodedPath)
    }

    @Test
    fun providersWithoutListingReturnEmpty() {
        assertEquals(emptyList(), list("minimax", "/v1"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun failuresThrowLlmException() {
        server.enqueueJson(401, """{"error":{"message":"bad key"}}""")

        val error = assertFailsWith<LlmException> { list("openai", "/v1") }
        assertFalse(error.retryable)
        assertTrue("bad key" in error.message!!)
    }

    @Test
    fun everyProviderIsWellFormed() {
        assertEquals(Providers.all.size, Providers.all.map { it.id }.toSet().size)
        assertNotNull(Providers.byId(Providers.CUSTOM_ID))
        Providers.all.forEach { provider ->
            assertTrue(provider.baseUrl.startsWith("http"), provider.id)
            assertFalse(provider.baseUrl.endsWith("/"), provider.id)
        }
    }
}
