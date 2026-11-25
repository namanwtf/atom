package atom.context

import atom.context.sources.MemInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class MemInfoTest {
    @Test
    fun parsesPssSection() {
        val out = """
            Applications Memory Usage (in Kilobytes):
            Total PSS by process:
                412,345K: com.android.systemui (pid 1234 / activities)
                 98,001K: com.whatsapp (pid 5555)
                512,000K: system (pid 900)
            Total PSS by OOM adjustment:
                600,000K: Native
        """.trimIndent()
        val p = MemInfo.parse(out)
        assertEquals(listOf("system", "com.android.systemui", "com.whatsapp"), p.map { it.name })
        assertEquals(412_345L, p[1].kb)
        assertEquals(1234, p[1].pid)
    }
}
