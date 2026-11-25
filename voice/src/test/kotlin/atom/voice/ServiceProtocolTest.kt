package atom.voice

import atom.voice.audio.Wav
import atom.voice.cloud.DashScopeProtocol
import atom.voice.cloud.DeepSeekProtocol
import atom.voice.cloud.DoubaoProtocol
import atom.voice.cloud.GeminiProtocol
import atom.voice.cloud.MimoProtocol
import atom.voice.cloud.MiniMaxProtocol
import atom.voice.cloud.OssCredentials
import atom.voice.cloud.OssSigner
import atom.voice.cloud.OssTarget
import atom.voice.speech.JsonObjectSplitter
import atom.voice.speech.MimoLanguage
import atom.voice.speech.MiniMaxSite
import atom.voice.speech.QwenRegion
import atom.voice.speech.SpeechConfig
import atom.voice.speech.SpeechError
import atom.voice.speech.SpeechException
import atom.voice.speech.TtsServiceId
import atom.voice.speech.VoiceCatalog
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ServiceProtocolTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun JsonObject.s(key: String) = this[key]!!.jsonPrimitive.content

    // ------------------------------------------------------------------ DashScope / Qwen

    @Test
    fun qwenRegionsAndBaseOverride() {
        val sg = SpeechConfig(qwenRegion = QwenRegion.Singapore)
        assertEquals("wss://dashscope-intl.aliyuncs.com/api-ws/v1/realtime?model=qwen3-asr-flash-realtime", DashScopeProtocol.realtimeUrl(sg))
        assertEquals("https://dashscope.aliyuncs.com", SpeechConfig(qwenRegion = QwenRegion.Beijing).dashScopeBase)
        val custom = SpeechConfig(qwenBaseUrl = "https://ws123.cn-beijing.maas.aliyuncs.com/")
        assertEquals("https://ws123.cn-beijing.maas.aliyuncs.com", custom.dashScopeBase)
        assertEquals("wss://ws123.cn-beijing.maas.aliyuncs.com", custom.dashScopeWsBase)
    }

    @Test
    fun qwenRealtimeSessionIsManualTurnPcm16k() {
        val o = obj(DashScopeProtocol.sessionUpdate("e1", "en"))
        assertEquals("session.update", o.s("type"))
        val session = o["session"]!!.jsonObject
        assertEquals("pcm", session.s("input_audio_format"))
        assertEquals("16000", session.s("sample_rate"))
        assertEquals(JsonNull, session["turn_detection"])
        assertEquals("en", session["input_audio_transcription"]!!.jsonObject.s("language"))
        // Blank language means auto: no transcription block at all.
        assertNull(obj(DashScopeProtocol.sessionUpdate("e2", " "))["session"]!!.jsonObject["input_audio_transcription"])
        val append = obj(DashScopeProtocol.append("e3", byteArrayOf(1, 2, 3)))
        assertEquals("input_audio_buffer.append", append.s("type"))
        assertContentEquals(byteArrayOf(1, 2, 3), Base64.getDecoder().decode(append.s("audio")))
        assertEquals("session.finish", obj(DashScopeProtocol.finish("e4")).s("type"))
        assertEquals("input_audio_buffer.commit", obj(DashScopeProtocol.commit("e5")).s("type"))
    }

    @Test
    fun qwenRealtimeEvents() {
        val partial = DashScopeProtocol.parseRealtime("""{"type":"conversation.item.input_audio_transcription.text","item_id":"i1","text":"hello ","stash":"wor"}""")
        assertEquals(DashScopeProtocol.RealtimeEvent.Partial("i1", "hello wor"), partial)
        val done = DashScopeProtocol.parseRealtime("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"i1","transcript":"hello world"}""")
        assertEquals(DashScopeProtocol.RealtimeEvent.Completed("i1", "hello world"), done)
        assertEquals(DashScopeProtocol.RealtimeEvent.Finished, DashScopeProtocol.parseRealtime("""{"type":"session.finished"}"""))
        assertIs<DashScopeProtocol.RealtimeEvent.Failed>(DashScopeProtocol.parseRealtime("""{"type":"error","error":{"code":"x","message":"bad key"}}"""))
        assertEquals(DashScopeProtocol.RealtimeEvent.Other, DashScopeProtocol.parseRealtime("not json"))
    }

    @Test
    fun qwenShortAudioBody() {
        val wav = Wav.encode(ByteArray(320), 16_000)
        val o = obj(DashScopeProtocol.shortAudioBody(wav, "zh"))
        assertEquals("qwen3-asr-flash", o.s("model"))
        val part = o["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("input_audio", part.s("type"))
        assertTrue(part["input_audio"]!!.jsonObject.s("data").startsWith("data:audio/wav;base64,UklGR"))
        assertEquals("zh", o["asr_options"]!!.jsonObject.s("language"))
        assertEquals("hi there", DashScopeProtocol.parseChatText("""{"choices":[{"message":{"content":"hi there"}}]}"""))
        assertEquals("ab", DashScopeProtocol.parseChatText("""{"choices":[{"message":{"content":[{"type":"text","text":"a"},{"text":"b"}]}}]}"""))
    }

    @Test
    fun qwenFileTranscription() {
        val o = obj(DashScopeProtocol.fileTransBody("https://b.oss/x.wav?sig", ""))
        assertEquals("qwen3-asr-flash-filetrans", o.s("model"))
        assertEquals("https://b.oss/x.wav?sig", o["input"]!!.jsonObject.s("file_url"))
        assertEquals("t-1", DashScopeProtocol.parseTaskId("""{"output":{"task_id":"t-1","task_status":"PENDING"}}"""))
        val ok = DashScopeProtocol.parseTask("""{"output":{"task_status":"SUCCEEDED","result":{"transcription_url":"https://r/1.json"}}}""")
        assertEquals("SUCCEEDED", ok.status)
        assertEquals("https://r/1.json", ok.resultUrl)
        val newer = DashScopeProtocol.parseTask("""{"output":{"task_status":"SUCCEEDED","results":[{"subtask_status":"SUCCEEDED","transcription_url":"https://r/2.json"}]}}""")
        assertEquals("https://r/2.json", newer.resultUrl)
        val failed = DashScopeProtocol.parseTask("""{"output":{"task_status":"FAILED","code":"InvalidFile","message":"cannot download"}}""")
        assertEquals("cannot download", failed.error)
        assertEquals("hello world", DashScopeProtocol.parseTranscription("""{"transcripts":[{"channel_id":0,"text":"hello"},{"channel_id":1,"text":" world "}]}"""))
    }

    @Test
    fun qwenTtsBodyAndEvents() {
        val o = obj(DashScopeProtocol.ttsBody("Hi", "Serena"))
        assertEquals("qwen3-tts-flash", o.s("model"))
        assertEquals("Serena", o["input"]!!.jsonObject.s("voice"))
        assertEquals("Auto", o["input"]!!.jsonObject.s("language_type"))
        val chunk = DashScopeProtocol.parseTtsEvent("""{"output":{"audio":{"data":"AQID","url":""}}}""")
        assertContentEquals(byteArrayOf(1, 2, 3), chunk.pcm)
        assertNull(chunk.url)
        val last = DashScopeProtocol.parseTtsEvent("""{"output":{"audio":{"data":"","url":"https://x/a.wav"},"finish_reason":"stop"}}""")
        assertNull(last.pcm)
        assertEquals("https://x/a.wav", last.url)
        assertEquals("Invalid API-key", DashScopeProtocol.parseTtsEvent("""{"code":"InvalidApiKey","message":"Invalid API-key"}""").error)
    }

    // ------------------------------------------------------------------ Doubao

    @Test
    fun doubaoFramesFollowTheBinaryProtocol() {
        val json = DoubaoProtocol.fullClientRequestJson("u1", "")
        val full = DoubaoProtocol.fullClientRequest(json)
        assertContentEquals(byteArrayOf(0x11, 0x10, 0x10, 0x00), full.copyOfRange(0, 4))
        assertEquals(json.toByteArray().size, ByteBuffer.wrap(full, 4, 4).int)
        val request = obj(json)
        assertEquals("pcm", request["audio"]!!.jsonObject.s("format"))
        assertEquals("16000", request["audio"]!!.jsonObject.s("rate"))
        assertEquals("bigmodel", request["request"]!!.jsonObject.s("model_name"))

        val audio = DoubaoProtocol.audio(byteArrayOf(9, 8), last = false)
        assertContentEquals(byteArrayOf(0x11, 0x20, 0x00, 0x00, 0, 0, 0, 2, 9, 8), audio)
        val last = DoubaoProtocol.audio(ByteArray(0), last = true)
        assertContentEquals(byteArrayOf(0x11, 0x22, 0x00, 0x00, 0, 0, 0, 0), last)
    }

    @Test
    fun doubaoParsesServerResultsAndErrors() {
        fun response(flags: Int, json: String, gzip: Boolean): ByteArray {
            val raw = json.toByteArray()
            val payload = if (gzip) ByteArrayOutputStream().also { GZIPOutputStream(it).use { g -> g.write(raw) } }.toByteArray() else raw
            val hasSeq = flags and 1 != 0
            val buf = ByteBuffer.allocate(4 + (if (hasSeq) 4 else 0) + 4 + payload.size)
            buf.put(0x11).put(((0b1001 shl 4) or flags).toByte()).put(((1 shl 4) or (if (gzip) 1 else 0)).toByte()).put(0)
            if (hasSeq) buf.putInt(if (flags and 2 != 0) -3 else 2)
            buf.putInt(payload.size).put(payload)
            return buf.array()
        }
        val partial = DoubaoProtocol.parse(response(0b0001, """{"result":{"text":"你好"}}""", gzip = true))
        assertEquals(DoubaoProtocol.ServerFrame.Result("你好", last = false), partial)
        val final = DoubaoProtocol.parse(response(0b0011, """{"result":{"text":"你好世界"}}""", gzip = false))
        assertEquals(DoubaoProtocol.ServerFrame.Result("你好世界", last = true), final)

        val msg = "invalid params".toByteArray()
        val err = ByteBuffer.allocate(12 + msg.size).put(0x11).put((0b1111 shl 4).toByte()).put(0x10).put(0)
            .putInt(45_000_001).putInt(msg.size).put(msg).array()
        assertEquals(DoubaoProtocol.ServerFrame.Error(45_000_001, "invalid params"), DoubaoProtocol.parse(err))
        assertEquals(SpeechError.NoSpeech, DoubaoProtocol.errorKind(45_000_002))
    }

    @Test
    fun doubaoHeadersNewKeyOrLegacyPair() {
        val modern = DoubaoProtocol.asrHeaders("key", appId = "", resourceId = "", connectId = "c")
        assertEquals("key", modern["X-Api-Key"])
        assertEquals("volc.seedasr.sauc.duration", modern["X-Api-Resource-Id"])
        assertNull(modern["X-Api-App-Key"])
        val legacy = DoubaoProtocol.asrHeaders("token", appId = "123", resourceId = "volc.bigasr.sauc.duration", connectId = "c")
        assertEquals("123", legacy["X-Api-App-Key"])
        assertEquals("token", legacy["X-Api-Access-Key"])
        assertNull(legacy["X-Api-Key"])
        // TTS uses App-Id (not App-Key) for the legacy console.
        assertEquals("123", DoubaoProtocol.ttsHeaders("token", "123", "")["X-Api-App-Id"])
        assertEquals("seed-tts-2.0", DoubaoProtocol.ttsHeaders("k", "", "")["X-Api-Resource-Id"])
    }

    @Test
    fun doubaoTtsStream() {
        val body = obj(DoubaoProtocol.ttsBody("u", "hello", "zh_female_vv_uranus_bigtts"))
        val params = body["req_params"]!!.jsonObject
        assertEquals("zh_female_vv_uranus_bigtts", params.s("speaker"))
        assertEquals("pcm", params["audio_params"]!!.jsonObject.s("format"))
        val splitter = JsonObjectSplitter()
        val stream = """{"code":0,"message":"","data":"AQI="}{"code":0,"data":null,"sentence":{"text":"a}b"}}""" + "\n" + """{"code":20000000,"message":"ok","data":null}"""
        val objects = stream.chunked(7).flatMap { splitter.feed(it) }
        assertEquals(3, objects.size)
        val first = DoubaoProtocol.parseTtsChunk(objects[0])
        assertContentEquals(byteArrayOf(1, 2), first.pcm)
        assertNull(DoubaoProtocol.parseTtsChunk(objects[1]).pcm)
        assertTrue(DoubaoProtocol.parseTtsChunk(objects[2]).done)
        assertEquals("speaker denied (45000000)", DoubaoProtocol.parseTtsChunk("""{"code":45000000,"message":"speaker denied"}""").error)
    }

    // ------------------------------------------------------------------ OSS V4

    private val target = OssTarget("cn-hangzhou", "oss-cn-hangzhou.aliyuncs.com", "bucket", "atom-voice/", OssCredentials("AKID", "SECRET"))

    @Test
    fun ossCanonicalRequestAndSigning() {
        assertEquals("/bucket/atom-voice/rec%20a.wav", OssSigner.canonicalUri("bucket", "atom-voice/rec a.wav"))
        val canonical = OssSigner.canonicalRequest(
            "PUT", "/bucket/k.wav", "",
            mapOf("X-OSS-Date" to "20251101T010203Z", "x-oss-content-sha256" to "UNSIGNED-PAYLOAD", "Content-Type" to "audio/wav", "Host" to "ignored"),
        )
        assertEquals(
            "PUT\n/bucket/k.wav\n\ncontent-type:audio/wav\nx-oss-content-sha256:UNSIGNED-PAYLOAD\nx-oss-date:20251101T010203Z\n\n\nUNSIGNED-PAYLOAD",
            canonical,
        )
        val sts = OssSigner.stringToSign("20251101T010203Z", "cn-hangzhou", canonical)
        assertTrue(sts.startsWith("OSS4-HMAC-SHA256\n20251101T010203Z\n20251101/cn-hangzhou/oss/aliyun_v4_request\n"))
        val sig = OssSigner.signature("SECRET", "20251101T010203Z", "cn-hangzhou", sts)
        assertEquals(64, sig.length)
        assertEquals(sig, OssSigner.signature("SECRET", "20251101T010203Z", "cn-hangzhou", sts)) // deterministic
        val headers = OssSigner.signedHeaders(target, "PUT", "atom-voice/k.wav", "audio/wav", 1_761_958_923_000)
        val auth = headers.getValue("Authorization")
        assertTrue(auth.startsWith("OSS4-HMAC-SHA256 Credential=AKID/20251101/cn-hangzhou/oss/aliyun_v4_request,Signature="), auth)
        assertEquals("UNSIGNED-PAYLOAD", headers["x-oss-content-sha256"])
    }

    @Test
    fun ossPresignedUrl() {
        val url = OssSigner.presignGet(target, "atom-voice/rec-1.wav", 3_600, 1_761_958_923_000)
        assertTrue(url.startsWith("https://bucket.oss-cn-hangzhou.aliyuncs.com/atom-voice/rec-1.wav?"), url)
        assertTrue("x-oss-signature-version=OSS4-HMAC-SHA256" in url)
        assertTrue("x-oss-credential=AKID%2F20251101%2Fcn-hangzhou%2Foss%2Faliyun_v4_request" in url)
        assertTrue("x-oss-expires=3600" in url)
        assertTrue(Regex("&x-oss-signature=[0-9a-f]{64}$").containsMatchIn(url))
        assertEquals("oss-ap-southeast-1.aliyuncs.com", OssTarget.endpointFor("oss-ap-southeast-1", ""))
        assertEquals("oss-cn-hangzhou-internal.aliyuncs.com", OssTarget.endpointFor("cn-hangzhou", "https://oss-cn-hangzhou-internal.aliyuncs.com/"))
        assertTrue(target.newObjectKey().matches(Regex("atom-voice/rec-[0-9a-f-]{36}\\.wav")))
    }

    // ------------------------------------------------------------------ MiniMax, Gemini, MiMo, DeepSeek

    @Test
    fun miniMaxRequestAndResponse() {
        val config = SpeechConfig(miniMaxModel = "speech-2.8-hd", miniMaxVoice = "male-qn-jingying", miniMaxSpeed = 3f, miniMaxNormalizeNumbers = true, miniMaxSite = MiniMaxSite.China)
        assertEquals("https://api.minimax.cn/v1/t2a_v2", MiniMaxProtocol.url(config))
        val o = obj(MiniMaxProtocol.body("hi", config))
        assertEquals("speech-2.8-hd", o.s("model"))
        assertEquals("male-qn-jingying", o["voice_setting"]!!.jsonObject.s("voice_id"))
        assertEquals("2.0", o["voice_setting"]!!.jsonObject.s("speed")) // clamped
        assertEquals("pcm", o["audio_setting"]!!.jsonObject.s("format"))
        assertEquals("basic", o.s("text_normalization_mode"))
        assertEquals("hex", o.s("output_format"))
        assertNull(obj(MiniMaxProtocol.body("hi", config.copy(miniMaxNormalizeNumbers = false)))["text_normalization_mode"])
        assertContentEquals(byteArrayOf(0x01, 0x7f, 0xff.toByte()), MiniMaxProtocol.parse("""{"data":{"audio":"017fff","status":2},"base_resp":{"status_code":0}}"""))
        val e = assertFailsWith<SpeechException> { MiniMaxProtocol.parse("""{"base_resp":{"status_code":1004,"status_msg":"auth failed"}}""") }
        assertEquals(SpeechError.Auth, e.kind)
    }

    @Test
    fun geminiInteractionsRequestAndAudio() {
        val config = SpeechConfig(geminiVoice = "Puck", geminiStyle = "cheerful")
        val o = obj(GeminiProtocol.body("Hello", config))
        assertEquals("gemini-3.8-flash-tts", o.s("model"))
        val content = o["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("Hello", content.s("text"))
        assertEquals("cheerful", content["annotations"]!!.jsonArray[0].jsonObject.s("style"))
        assertEquals("Puck", o["generation_config"]!!.jsonObject["speech_config"]!!.jsonArray[0].jsonObject.s("voice"))
        assertEquals("audio", o["response_format"]!!.jsonObject.s("type"))
        assertNull(obj(GeminiProtocol.body("x", SpeechConfig()))["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["annotations"])
        val audio = GeminiProtocol.audio("""{"steps":[{"type":"model_output","content":[{"type":"audio","data":"AQI="},{"type":"audio","data":"Aw=="}]}]}""")
        assertContentEquals(byteArrayOf(1, 2, 3), audio)
        assertFailsWith<SpeechException> { GeminiProtocol.audio("""{"error":{"message":"quota"}}""") }
    }

    @Test
    fun mimoBodies() {
        val asr = obj(MimoProtocol.asrBody(ByteArray(4), MimoLanguage.English))
        assertEquals("mimo-v2.5-asr", asr.s("model"))
        assertEquals("en", asr["asr_options"]!!.jsonObject.s("language"))
        val tts = obj(MimoProtocol.ttsBody("speak this", "冰糖", "slow and warm"))
        val messages = tts["messages"] as JsonArray
        assertEquals("user", messages[0].jsonObject.s("role"))
        assertEquals("slow and warm", messages[0].jsonObject.s("content"))
        assertEquals("assistant", messages[1].jsonObject.s("role"))
        assertEquals("speak this", messages[1].jsonObject.s("content"))
        assertEquals("冰糖", tts["audio"]!!.jsonObject.s("voice"))
        assertEquals(1, (obj(MimoProtocol.ttsBody("x", "Mia", " "))["messages"] as JsonArray).size)
        assertContentEquals(byteArrayOf(1, 2), MimoProtocol.ttsAudio("""{"choices":[{"message":{"audio":{"data":"AQI="}}}]}"""))
    }

    @Test
    fun deepSeekBodies() {
        val o = obj(DeepSeekProtocol.ttsBody("hi", "tide"))
        assertEquals("tide", o.s("voice_id"))
        assertEquals("pcm", o.s("format"))
        assertEquals("false", o.s("filter_markdown"))
        assertEquals("hello", DeepSeekProtocol.asrText("""{"request_id":"r","text":"hello","duration_ms":900}"""))
    }

    // ------------------------------------------------------------------ audio + catalog

    @Test
    fun wavRoundTripAndIncrementalDecode() {
        val pcm = ByteArray(1_000) { it.toByte() }
        val wav = Wav.encode(pcm, 24_000)
        val decoder = Wav.StreamDecoder(16_000)
        val out = ByteArrayOutputStream()
        wav.toList().chunked(17).forEach { out.write(decoder.feed(it.toByteArray())) }
        assertEquals(24_000, decoder.sampleRate)
        assertContentEquals(pcm, out.toByteArray())
        val raw = Wav.StreamDecoder(24_000)
        assertContentEquals(pcm, raw.feed(pcm))
        assertTrue(raw.isRaw)
        assertContentEquals(byteArrayOf(0x34, 0x12, 0xff.toByte(), 0xff.toByte()), Wav.shortsToBytes(shortArrayOf(0x1234, -1)))
    }

    @Test
    fun voiceIdsMatchBackToPresets() {
        assertEquals("Vivi", VoiceCatalog.match(TtsServiceId.Doubao, "zh_female_vv_uranus_bigtts")?.name)
        assertEquals("Cherry", VoiceCatalog.match(TtsServiceId.Qwen, "cherry")?.name)
        assertEquals("Rock Candy", VoiceCatalog.match(TtsServiceId.Mimo, "冰糖")?.name)
        assertNull(VoiceCatalog.match(TtsServiceId.Qwen, "my-cloned-voice"))
        assertEquals(16, VoiceCatalog.doubao.size)
        assertEquals(8, VoiceCatalog.gemini.size)
        val config = SpeechConfig().withVoice(TtsServiceId.Gemini, "Leda")
        assertEquals("Leda", config.voiceFor(TtsServiceId.Gemini))
    }
}
