package atom.llm.cloud

import atom.core.llm.ReasoningEffort
import java.net.Socket
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer

class ModelsDevAndOAuthTest {

    private val catalog = """
        {"acme":{"id":"acme","name":"Acme AI","npm":"@ai-sdk/openai-compatible","api":"https://api.acme.ai/v1/","doc":"https://acme.ai/docs",
           "env":["ACME_KEY"],"models":{
             "acme-think":{"id":"acme-think","name":"Acme Think","reasoning":true,
               "reasoning_options":[{"type":"toggle"},{"type":"effort","values":["low","high","max"]}],
               "tool_call":true,"limit":{"context":262144,"output":8192},"modalities":{"input":["text","image"],"output":["text"]},
               "cost":{"input":1}},
             "acme-mini":{"id":"acme-mini","name":"Acme Mini","reasoning":false,"limit":{"context":32768}}}},
         "claudeish":{"id":"claudeish","name":"Claudeish","npm":"@ai-sdk/anthropic","models":{"c1":{"id":"c1","name":"C1"}}},
         "empty":{"id":"empty","name":"Empty","models":{}},
         "templ":{"id":"templ","name":"Templated","api":"https://${'$'}{REGION}.x.com","models":{"t":{"id":"t"}}}}
    """.trimIndent()

    @Test
    fun parsesCatalogIntoProvidersWithCapabilities() {
        val providers = ModelsDev.parse(catalog)

        assertEquals(listOf("Acme AI", "Claudeish", "Templated"), providers.map { it.name })
        val acme = providers.first()
        assertEquals("https://api.acme.ai/v1", acme.baseUrl)
        assertEquals(Wire.OpenAiChat, acme.wire)
        val think = acme.models.first { it.id == "acme-think" }
        assertEquals(
            listOf(ReasoningEffort.Off, ReasoningEffort.Low, ReasoningEffort.High, ReasoningEffort.Max),
            think.capabilities.reasoning!!.levels,
        )
        assertEquals(262_144, think.capabilities.contextWindow)
        assertEquals(true, think.capabilities.vision)
        assertFalse(acme.models.first { it.id == "acme-mini" }.capabilities.reasoning!!.supported)

        val claude = providers[1]
        assertEquals(Wire.Anthropic, claude.wire)
        assertEquals("https://api.anthropic.com", claude.baseUrl)
        assertNull(providers[2].baseUrl)
    }

    @Test
    fun importedProvidersStartDisabledAndCacheRoundTrips() {
        val providers = ModelsDev.parse(catalog)
        val config = ModelsDev.toConfig(providers.first(), setOf("acme-think"), "acme")

        assertFalse(config.enabled)
        assertNull(config.presetId)
        assertEquals(listOf("acme-think"), config.models.map { it.id })
        assertEquals("acme-think", config.primaryModel)
        assertEquals("acme", config.importedFrom)
        assertTrue(config.models.single().reasoning.supported)
        assertEquals(providers, ModelsDev.decodeCache(ModelsDev.encodeCache(providers)))
        assertNull(ModelsDev.decodeCache("not json"))
    }

    @Test
    fun pkceMatchesRfc7636() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val verifier = Pkce.verifier()
        assertTrue(verifier.length >= 43)
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun chatGptAuthorizeAndTokenRequests() {
        val parsed = ChatGptAuth.authorizeUrl("chal", "st8").toHttpUrl()
        assertEquals("auth.openai.com", parsed.host)
        assertEquals("/oauth/authorize", parsed.encodedPath)
        assertEquals("code", parsed.queryParameter("response_type"))
        assertEquals(ChatGptAuth.CLIENT_ID, parsed.queryParameter("client_id"))
        assertEquals("http://localhost:1455/auth/callback", parsed.queryParameter("redirect_uri"))
        assertEquals("S256", parsed.queryParameter("code_challenge_method"))
        assertEquals("chal", parsed.queryParameter("code_challenge"))
        assertEquals("st8", parsed.queryParameter("state"))
        assertTrue("offline_access" in parsed.queryParameter("scope")!!)

        val token = ChatGptAuth.tokenRequest("the-code", "the-verifier")
        val form = token.body as FormBody
        val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
        assertEquals("authorization_code", fields["grant_type"])
        assertEquals("the-verifier", fields["code_verifier"])
        assertEquals(ChatGptAuth.REDIRECT_URI, fields["redirect_uri"])

        val refresh = Json.parseToJsonElement(Buffer().also { ChatGptAuth.refreshRequest("rt").body!!.writeTo(it) }.readUtf8()).jsonObject
        assertEquals("refresh_token", refresh["grant_type"]!!.jsonPrimitive.content)
        assertEquals("rt", refresh["refresh_token"]!!.jsonPrimitive.content)
    }

    private fun jwt(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("{}".toByteArray()) + "." + enc.encodeToString(payload.toByteArray()) + ".sig"
    }

    @Test
    fun chatGptTokensExposeAccountAndPlan() {
        val id = jwt("""{"email":"a@b.c","https://api.openai.com/auth":{"chatgpt_account_id":"acct_9","chatgpt_plan_type":"plus"}}""")
        val tokens = ChatGptAuth.parseTokens("""{"access_token":"at","refresh_token":"rt","id_token":"$id","expires_in":3600}""", 1_000L)

        assertEquals("acct_9", tokens.accountId)
        assertEquals("plus", tokens.plan)
        assertEquals("a@b.c", tokens.email)
        assertEquals(3_601_000L, tokens.expiresAtMillis)
        assertTrue(tokens.expiresWithin(3_600_000L, 5_000L))
        assertEquals("acct_9", ChatGptAuth.accountHeaders(tokens).first { it.name == "ChatGPT-Account-ID" }.value)

        val refreshed = ChatGptAuth.parseTokens("""{"access_token":"at2","expires_in":10}""", 0L, tokens)
        assertEquals("rt", refreshed.refreshToken)
        assertEquals("acct_9", refreshed.accountId)
    }

    @Test
    fun grokDeviceFlowRequestsAndPollStates() {
        val form = GrokAuth.deviceCodeRequest().body as FormBody
        val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
        assertEquals(GrokAuth.CLIENT_ID, fields["client_id"])
        assertTrue("grok-cli:access" in fields["scope"]!!)
        assertEquals("https://auth.x.ai/oauth2/device/code", GrokAuth.deviceCodeRequest().url.toString())

        val code = GrokAuth.parseDeviceCode("""{"device_code":"dc","user_code":"ABCD-EFGH","verification_uri":"https://accounts.x.ai/device","expires_in":900,"interval":0}""")
        assertEquals("ABCD-EFGH", code.userCode)
        assertEquals(1, code.intervalSeconds)

        val poll = GrokAuth.pollRequest("dc").body as FormBody
        assertEquals(GrokAuth.DEVICE_GRANT, (0 until poll.size).first { poll.name(it) == "grant_type" }.let(poll::value))
        assertEquals(GrokAuth.Poll.Pending, GrokAuth.parsePoll(400, """{"error":"authorization_pending"}""", 0))
        assertEquals(GrokAuth.Poll.SlowDown, GrokAuth.parsePoll(400, """{"error":"slow_down"}""", 0))
        val denied = assertIs<GrokAuth.Poll.Failed>(GrokAuth.parsePoll(400, """{"error":"access_denied"}""", 0))
        assertEquals(OAuthException.Kind.Denied, denied.error.kind)
        val expired = assertIs<GrokAuth.Poll.Failed>(GrokAuth.parsePoll(400, """{"error":"expired_token"}""", 0))
        assertEquals(OAuthException.Kind.Expired, expired.error.kind)
        val done = assertIs<GrokAuth.Poll.Done>(GrokAuth.parsePoll(200, """{"access_token":"x","refresh_token":"r","expires_in":60}""", 0))
        assertEquals(60_000L, done.tokens.expiresAtMillis)

        val revoke = GrokAuth.revokeRequest("r", "refresh_token")
        assertEquals("https://auth.x.ai/oauth2/revoke", revoke.url.toString())
    }

    @Test
    fun loopbackReceivesTheBrowserRedirect() = runBlocking {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val callback = LoopbackCallback(port).bind()
        val result = async { callback.await(5_000) }
        delay(50)
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write("GET /favicon.ico HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            socket.getInputStream().readBytes()
        }
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write("GET /auth/callback?code=abc%2B1&state=s1 HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            assertTrue(String(socket.getInputStream().readBytes()).startsWith("HTTP/1.1 200"))
        }
        val received = result.await()
        assertEquals("abc+1", received.code)
        assertEquals("s1", received.state)
    }
}
