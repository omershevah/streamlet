package dev.omershevach.streamlet.engine

/** A resolved, inclusive byte range of a file of a known size. */
internal data class ByteRange(val start: Long, val end: Long, val partial: Boolean) {
    val length get() = end - start + 1

    companion object {
        private val SPEC = Regex("""bytes\s*=\s*(\d*)\s*-\s*(\d*)""")

        /** Parses a Range header. No header means the whole file; returns null if unsatisfiable. */
        fun parse(header: String?, size: Long): ByteRange? {
            val m = header?.let { SPEC.find(it) } ?: return if (size > 0) ByteRange(0, size - 1, false) else null
            val (a, b) = m.destructured
            val range = when {
                a.isEmpty() && b.isEmpty() -> return null
                a.isEmpty() -> ByteRange((size - b.toLong()).coerceAtLeast(0), size - 1, true)
                else -> ByteRange(a.toLong(), if (b.isEmpty()) size - 1 else minOf(b.toLong(), size - 1), true)
            }
            return range.takeIf { it.start < size && it.start <= it.end }
        }
    }
}
