package atom.voice.audio

/** Fixed-capacity PCM16 ring buffer. Keeps the newest [capacity] samples; never allocates after construction. */
class ShortRingBuffer(val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be > 0" }
    }

    private val data = ShortArray(capacity)
    private var writePos = 0

    var size: Int = 0
        private set

    fun clear() {
        writePos = 0
        size = 0
    }

    fun write(src: ShortArray, offset: Int = 0, length: Int = src.size - offset) {
        var off = offset
        var len = length
        if (len >= capacity) { // only the newest `capacity` samples survive
            off += len - capacity
            len = capacity
        }
        val first = minOf(len, capacity - writePos)
        System.arraycopy(src, off, data, writePos, first)
        if (len > first) System.arraycopy(src, off + first, data, 0, len - first)
        writePos = (writePos + len) % capacity
        size = minOf(capacity, size + len)
    }

    /** Copies the contents oldest-first into [dst] (must hold [size] samples); returns the sample count. */
    fun copyTo(dst: ShortArray): Int {
        require(dst.size >= size) { "dst too small" }
        val start = (writePos - size + capacity) % capacity
        val first = minOf(size, capacity - start)
        System.arraycopy(data, start, dst, 0, first)
        if (size > first) System.arraycopy(data, 0, dst, first, size - first)
        return size
    }
}
