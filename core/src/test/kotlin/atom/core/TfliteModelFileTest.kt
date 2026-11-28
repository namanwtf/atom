package atom.core

import atom.core.ondevice.TfliteModelFile
import atom.core.ondevice.WakeShapes
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The "Hey Atom" model files of the on-device AI pack, as the pack opens them: LiteRT's Java interpreter allocates at
 * the stored input shapes in its constructor, so every stage must be stored at the shape the pipeline feeds once the
 * pack has set it. The melspectrogram model is stored as [1, 1], where its first CONV_2D cannot be prepared (4.0.6:
 * "Node number 2 (CONV_2D) failed to prepare"). Loading and running the stages is checked on the emulator (E2E q) and
 * with LiteRT on the runner (tools/ondevice/wake_probe.py).
 */
class TfliteModelFileTest {
    private val dir = File("../ondevice-pack/src/main/assets/${WakeShapes.ASSET_DIR}")

    private fun load(name: String): ByteBuffer {
        val bytes = File(dir, name).readBytes()
        // As the pack holds them: a direct buffer in the phone's byte order.
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
    }

    private fun bytesOf(b: ByteBuffer): ByteArray = ByteArray(b.capacity()).also { b.duplicate().apply { rewind() }.get(it) }

    @Test
    fun theMelspectrogramInputIsStoredAtOneSampleUntilThePackSetsIt() {
        val mel = load(WakeShapes.MELSPEC_MODEL)
        val before = bytesOf(mel)
        assertContentEquals(intArrayOf(1, 1), TfliteModelFile.storedInputShape(mel))

        val feed = intArrayOf(1, WakeShapes.MEL_INPUT_SAMPLES)
        assertTrue(TfliteModelFile.setStoredInputShape(mel, feed))
        assertContentEquals(feed, TfliteModelFile.storedInputShape(mel))
        // Only the shape's value changed (1 -> 1760: two bytes), never the layout.
        val after = bytesOf(mel)
        assertEquals(before.size, after.size)
        assertEquals(2, before.indices.count { before[it] != after[it] })
        // Setting it again changes nothing.
        assertTrue(TfliteModelFile.setStoredInputShape(mel, feed))
        assertContentEquals(after, bytesOf(mel))
    }

    @Test
    fun theOtherStagesAreAlreadyStoredAtTheShapeTheyAreFed() {
        val embedding = load(WakeShapes.EMBEDDING_MODEL)
        val before = bytesOf(embedding)
        val feed = intArrayOf(1, WakeShapes.MEL_WINDOW_FRAMES, WakeShapes.MEL_BINS, 1)
        assertContentEquals(feed, TfliteModelFile.storedInputShape(embedding))
        assertTrue(TfliteModelFile.setStoredInputShape(embedding, feed))
        assertContentEquals(before, bytesOf(embedding))

        val classifier = load(WakeShapes.CLASSIFIER_MODEL)
        assertContentEquals(intArrayOf(1, 16, WakeShapes.EMBEDDING_DIM), TfliteModelFile.storedInputShape(classifier))
    }

    @Test
    fun aShapeOfAnotherLengthOrABrokenFileIsLeftAlone() {
        val mel = load(WakeShapes.MELSPEC_MODEL)
        val before = bytesOf(mel)
        assertFalse(TfliteModelFile.setStoredInputShape(mel, intArrayOf(1, 1760, 1)))
        assertContentEquals(before, bytesOf(mel))

        val junk = ByteBuffer.wrap(ByteArray(64) { (it * 37).toByte() })
        val junkBefore = bytesOf(junk)
        assertNull(TfliteModelFile.storedInputShape(junk))
        assertFalse(TfliteModelFile.setStoredInputShape(junk, intArrayOf(1, 1760)))
        assertContentEquals(junkBefore, bytesOf(junk))
        assertNull(TfliteModelFile.storedInputShape(ByteBuffer.allocate(0)))
    }
}
