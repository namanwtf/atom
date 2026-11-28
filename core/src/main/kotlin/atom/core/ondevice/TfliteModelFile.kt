package atom.core.ondevice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads and sets the stored shape of a `.tflite` model's first input, in the model's bytes, before an interpreter is
 * created from them.
 *
 * LiteRT's Java `Interpreter` allocates the tensors in its constructor, at the shapes stored in the file. A model
 * whose input is dynamic is stored with placeholder sizes: the "Hey Atom" melspectrogram model's input `[-1, -1]` is
 * stored as `[1, 1]`, and at one sample its first CONV_2D (a 512-sample STFT window) has no valid output, so the
 * constructor fails with "Node number 2 (CONV_2D) failed to prepare" before `resizeInput` could be called. Writing
 * the size the app feeds (`[1, 1760]`) into the stored shape makes that first allocation valid; the computation is
 * unchanged (the shape signature still says dynamic).
 *
 * A minimal reader of the TFLite FlatBuffer (`tensorflow/lite/schema/schema.fbs`): Model.subgraphs[0] ->
 * SubGraph.inputs[0] -> SubGraph.tensors[i].shape. Only the shape's values change, never the file's layout; a shape of
 * another length, a shape vector shared with another tensor, or anything malformed leaves the bytes alone.
 */
object TfliteModelFile {
    // Field indexes in schema.fbs.
    private const val MODEL_SUBGRAPHS = 2
    private const val SUBGRAPH_TENSORS = 0
    private const val SUBGRAPH_INPUTS = 1
    private const val TENSOR_SHAPE = 0
    private const val TENSOR_SHAPE_SIGNATURE = 7
    private const val MAX_RANK = 16

    /** The stored shape of the first input of the first subgraph, or null when the bytes are not a readable model. */
    fun storedInputShape(model: ByteBuffer): IntArray? = runCatching {
        val b = model.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val shape = inputShapeVector(b) ?: return null
        val rank = b.getInt(shape)
        if (rank !in 0..MAX_RANK || checked(b, shape + 4 * rank) == null) return null
        IntArray(rank) { b.getInt(shape + 4 + 4 * it) }
    }.getOrNull()

    /**
     * Sets the stored shape of the first input to [dims] in [model] (a writable buffer of the whole file; position
     * and limit are ignored). True when the stored shape now is [dims]; false when it could not be set, and then
     * nothing was changed.
     */
    fun setStoredInputShape(model: ByteBuffer, dims: IntArray): Boolean = runCatching {
        val b = model.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val shape = inputShapeVector(b) ?: return false
        if (b.getInt(shape) != dims.size || checked(b, shape + 4 * dims.size) == null) return false
        val current = IntArray(dims.size) { b.getInt(shape + 4 + 4 * it) }
        if (current.contentEquals(dims)) return true
        if (shapeShared(b, shape)) return false
        if (b.isReadOnly) return false
        dims.forEachIndexed { i, d -> b.putInt(shape + 4 + 4 * i, d) }
        true
    }.getOrDefault(false)

    /** Position of the input tensor's shape vector (its length word), or null. */
    private fun inputShapeVector(b: ByteBuffer): Int? {
        val tensors = tensorsVector(b) ?: return null
        val inputs = ref(b, subgraph(b) ?: return null, SUBGRAPH_INPUTS) ?: return null
        if (b.getInt(inputs) < 1) return null
        val index = b.getInt(inputs + 4)
        val tensor = element(b, tensors, index) ?: return null
        return ref(b, tensor, TENSOR_SHAPE)
    }

    /** True when another tensor's shape or shape signature points at the same vector. */
    private fun shapeShared(b: ByteBuffer, shape: Int): Boolean {
        val tensors = tensorsVector(b) ?: return true
        val count = b.getInt(tensors)
        if (count < 0 || count > b.capacity() / 4) return true
        var owners = 0
        for (i in 0 until count) {
            val t = element(b, tensors, i) ?: return true
            if (ref(b, t, TENSOR_SHAPE) == shape) owners++
            if (ref(b, t, TENSOR_SHAPE_SIGNATURE) == shape) owners++
        }
        return owners != 1
    }

    private fun subgraph(b: ByteBuffer): Int? {
        val root = b.getInt(0)
        val subgraphs = ref(b, root, MODEL_SUBGRAPHS) ?: return null
        return element(b, subgraphs, 0)
    }

    private fun tensorsVector(b: ByteBuffer): Int? = ref(b, subgraph(b) ?: return null, SUBGRAPH_TENSORS)

    /** The table at [index] of a vector of tables at [vector], or null when out of range. */
    private fun element(b: ByteBuffer, vector: Int, index: Int): Int? {
        if (index < 0 || index >= b.getInt(vector)) return null
        val at = vector + 4 + 4 * index
        return checked(b, at + b.getInt(at))
    }

    /** Where the offset field [field] of the table at [table] points, or null when the field is absent. */
    private fun ref(b: ByteBuffer, table: Int, field: Int): Int? {
        val vtable = checked(b, table - b.getInt(table)) ?: return null
        val vtableSize = b.getShort(vtable).toInt() and 0xFFFF
        val slot = 4 + 2 * field
        if (slot + 2 > vtableSize) return null
        val offset = b.getShort(vtable + slot).toInt() and 0xFFFF
        if (offset == 0) return null
        val at = table + offset
        return checked(b, at + b.getInt(at))
    }

    private fun checked(b: ByteBuffer, position: Int): Int? = position.takeIf { it >= 0 && it + 4 <= b.capacity() }
}
