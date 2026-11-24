package atom.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelUrlsTest {

    private fun ok(s: String) = ModelUrls.parse(s, emptyList()).getOrThrow()
    private fun bad(s: String): String {
        val r = ModelUrls.parse(s, emptyList())
        assertTrue(r.isFailure, "expected failure for '$s' but got ${r.getOrNull()}")
        return r.exceptionOrNull()!!.message!!
    }

    @Test
    fun hfShorthand() {
        val m = ok("hf:Qwen/Qwen3-4B-GGUF/Qwen3-4B-Q4_K_M.gguf")
        assertEquals("https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf", m.url)
        assertEquals(ModelFormat.Gguf, m.format)
        assertEquals(0L, m.sizeBytes)
        assertNull(m.sha256)
        assertEquals("Qwen3-4B-Q4_K_M", m.name)
        assertTrue(m.id.startsWith("hf-qwen-qwen3-4b-gguf-qwen3-4b-q4-k-m"), m.id)
    }

    @Test
    fun hfShorthandWithSubfolderAndLiteRt() {
        val m = ok("hf:litert-community/Some-Repo/sub/dir/model.litertlm")
        assertEquals("https://huggingface.co/litert-community/Some-Repo/resolve/main/sub/dir/model.litertlm", m.url)
        assertEquals(ModelFormat.LiteRtLm, m.format)
    }

    @Test
    fun hfBlobUrlBecomesResolve() {
        val m = ok("https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/blob/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf")
        assertEquals(
            "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            m.url,
        )
    }

    @Test
    fun hfResolveUrlWithQueryAndRevision() {
        val m = ok("https://huggingface.co/org/repo/resolve/abc123/file.GGUF?download=true")
        assertEquals("https://huggingface.co/org/repo/resolve/abc123/file.GGUF", m.url)
        assertEquals(ModelFormat.Gguf, m.format)
        // Non-main revisions get a distinct id so they don't collide with main.
        val main = ok("https://huggingface.co/org/repo/resolve/main/file.GGUF")
        assertFalse(m.id == main.id)
    }

    @Test
    fun hfShortHostAndEncodedName() {
        val m = ok("https://hf.co/org/repo/blob/main/my%20model.gguf")
        assertEquals("https://huggingface.co/org/repo/resolve/main/my%20model.gguf", m.url)
        assertEquals("my model", m.name)
    }

    @Test
    fun directUrl() {
        val m = ok("https://example.com/files/tiny-model.litertlm?sig=abc#frag")
        assertEquals("https://example.com/files/tiny-model.litertlm?sig=abc", m.url)
        assertEquals(ModelFormat.LiteRtLm, m.format)
        assertTrue(m.id.startsWith("url-tiny-model-"), m.id)
        // Same link -> same id (stable across calls), different link -> different id.
        assertEquals(m.id, ok("https://example.com/files/tiny-model.litertlm?sig=abc").id)
        assertFalse(m.id == ok("https://mirror.example.org/tiny-model.litertlm").id)
    }

    @Test
    fun curatedLinkKeepsVerifiedMetadata() {
        val curated = ModelCatalog.curated.first { it.format == ModelFormat.Gguf && it.sha256 != null }
        val blob = curated.url.replace("/resolve/", "/blob/")
        val m = ModelUrls.parse(blob).getOrThrow()
        assertEquals(curated, m)
    }

    @Test
    fun rejectsBadInput() {
        bad("")
        bad("   ")
        bad("not a url")
        bad("ftp://example.com/model.gguf")
        bad("http://example.com/model.gguf") // https only
        bad("https://example.com/model.bin")
        bad("https://example.com/")
        bad("https://huggingface.co/Qwen/Qwen3-4B-GGUF") // repo page, not a file
        bad("https://huggingface.co/Qwen/Qwen3-4B-GGUF/tree/main")
        bad("hf:Qwen/Qwen3-4B-GGUF") // no file
        bad("hf:Qwen//x.gguf")
        bad("hf:org/repo/../../etc/x.gguf")
        bad("https://user:pw@example.com/model.gguf")
        bad("javascript:alert(1)")
        val split = bad("hf:org/repo/big-Q4_K_M-00001-of-00003.gguf")
        assertTrue(split.contains("Split"), split)
        assertTrue(bad("https://example.com/model.safetensors").contains(".gguf"))
    }

    @Test
    fun tokenHostGuard() {
        assertTrue(ModelUrls.isHuggingFaceHost("huggingface.co"))
        assertTrue(ModelUrls.isHuggingFaceHost("HuggingFace.co"))
        assertTrue(ModelUrls.isHuggingFaceHost("cdn-lfs.huggingface.co"))
        assertTrue(ModelUrls.isHuggingFaceHost("hf.co"))
        assertFalse(ModelUrls.isHuggingFaceHost("huggingface.co.evil.com"))
        assertFalse(ModelUrls.isHuggingFaceHost("evilhuggingface.co"))
        assertFalse(ModelUrls.isHuggingFaceHost("cas-bridge.xethub.hf.co"))
        assertFalse(ModelUrls.isHuggingFaceHost("example.com"))
        assertFalse(ModelUrls.isHuggingFaceHost(null))
    }

    @Test
    fun catalogIsSane() {
        val c = ModelCatalog.curated
        assertTrue(c.size in 6..10)
        assertEquals(c.size, c.map { it.id }.toSet().size)
        assertTrue(c.any { it.format == ModelFormat.LiteRtLm } && c.any { it.format == ModelFormat.Gguf })
        for (m in c) {
            assertTrue(m.url.startsWith("https://huggingface.co/") && "/resolve/" in m.url, m.url)
            assertEquals(m.format, ModelFormat.fromFileName(m.url))
            assertTrue(m.sizeBytes > 0)
            m.sha256?.let { assertTrue(Regex("^[0-9a-f]{64}$").matches(it), it) }
            if (!m.gated) assertTrue(m.sha256 != null, "${m.id} should carry a hash")
            assertEquals(ModelUrls.slug(m.id, 120), m.id, "ids must be file-name safe")
        }
    }
}
