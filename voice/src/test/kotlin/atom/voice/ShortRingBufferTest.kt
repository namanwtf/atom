package atom.voice

import atom.voice.audio.ShortRingBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ShortRingBufferTest {
    private fun seq(from: Int, count: Int) = ShortArray(count) { (from + it).toShort() }

    private fun contents(rb: ShortRingBuffer): ShortArray {
        val out = ShortArray(rb.capacity)
        val n = rb.copyTo(out)
        return out.copyOf(n)
    }

    @Test
    fun partialFillKeepsOrder() {
        val rb = ShortRingBuffer(10)
        rb.write(seq(0, 4))
        rb.write(seq(4, 3))
        assertEquals(7, rb.size)
        assertContentEquals(seq(0, 7), contents(rb))
    }

    @Test
    fun wrapAroundKeepsNewestOldestFirst() {
        val rb = ShortRingBuffer(10)
        rb.write(seq(0, 7))
        rb.write(seq(7, 7)) // 14 written, newest 10 = 4..13
        assertEquals(10, rb.size)
        assertContentEquals(seq(4, 10), contents(rb))
        rb.write(seq(14, 3))
        assertContentEquals(seq(7, 10), contents(rb))
    }

    @Test
    fun writeLargerThanCapacityKeepsTail() {
        val rb = ShortRingBuffer(8)
        rb.write(seq(0, 3))
        rb.write(seq(100, 21))
        assertContentEquals(seq(113, 8), contents(rb))
    }

    @Test
    fun offsetWritesAndClear() {
        val rb = ShortRingBuffer(5)
        rb.write(seq(0, 10), offset = 2, length = 3)
        assertContentEquals(seq(2, 3), contents(rb))
        rb.clear()
        assertEquals(0, rb.size)
        rb.write(seq(50, 2))
        assertContentEquals(seq(50, 2), contents(rb))
    }

    @Test
    fun frameSizedPrerollHoldsLastNineteenFrames() {
        val frame = 1280
        val rb = ShortRingBuffer(19 * frame)
        for (f in 0 until 40) rb.write(ShortArray(frame) { f.toShort() })
        val out = contents(rb)
        assertEquals(19 * frame, out.size)
        for (i in 0 until 19) assertEquals((21 + i).toShort(), out[i * frame])
    }
}
