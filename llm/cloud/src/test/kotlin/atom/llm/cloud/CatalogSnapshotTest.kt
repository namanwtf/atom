package atom.llm.cloud

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

class CatalogSnapshotTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() = server.close()

    @Test
    fun bundledSnapshotParsesWithItsDate() {
        val snapshot = assertNotNull(ModelsDev.bundled())
        assertTrue(snapshot.providers.size > 50, "snapshot has ${snapshot.providers.size} providers")
        val deepseek = snapshot.providers.first { it.id == "deepseek" }
        assertEquals("https://api.deepseek.com", deepseek.baseUrl)
        assertTrue(deepseek.models.isNotEmpty())
        assertTrue(snapshot.providers.all { p -> p.models.isNotEmpty() })
        assertNotNull(snapshot.fetchedAtMillis)
    }

    @Test
    fun snapshotTimeReadsTheFetchedLine() {
        assertEquals(0L, ModelsDev.snapshotTime("fetched=1970-01-01T00:00:00+00:00\nsource=x"))
        assertNull(ModelsDev.snapshotTime("source=x"))
        assertNull(ModelsDev.snapshotTime("fetched=yesterday"))
    }

    @Test
    fun failuresAreTyped() {
        assertEquals(CatalogError.Dns, ModelsDev.classify(UnknownHostException("models.dev")))
        assertEquals(CatalogError.Timeout, ModelsDev.classify(SocketTimeoutException("read timed out")))
        assertEquals(CatalogError.Ssl, ModelsDev.classify(SSLHandshakeException("bad cert")))
        assertEquals(CatalogError.Format, ModelsDev.classify(SerializationException("bad json")))
        assertEquals(CatalogError.Http(503), ModelsDev.classify(CatalogException(CatalogError.Http(503))))
        assertEquals(CatalogError.Dns, ModelsDev.classify(IOException("wrapped", UnknownHostException("x"))))
        assertIs<CatalogError.Network>(ModelsDev.classify(IOException("reset")))
    }

    @Test
    fun httpErrorsAndOversizedBodiesAreTyped() = runBlocking {
        val http = OkHttpClient()
        server.enqueueJson(502, "{}")
        val e = assertFailsWith<CatalogException> { ModelsDev.fetch(http, server.url("/api.json").toString()) }
        assertEquals(CatalogError.Http(502), e.error)

        server.enqueueJson(200, "not json at all")
        val f = assertFailsWith<Exception> { ModelsDev.fetch(http, server.url("/api.json").toString()) }
        assertEquals(CatalogError.Format, ModelsDev.classify(f))
    }

    @Test
    fun limitedStreamStopsAtTheCap() {
        val input = ModelsDev.limited(ByteArrayInputStream(ByteArray(100)), 64)
        val e = assertFailsWith<CatalogException> { input.readBytes() }
        assertEquals(CatalogError.TooLarge, e.error)
        assertEquals(10, ModelsDev.limited(ByteArrayInputStream(ByteArray(10)), 64).readBytes().size)
    }
}
