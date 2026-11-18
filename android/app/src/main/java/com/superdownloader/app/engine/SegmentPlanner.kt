package com.superdownloader.app.engine

/** Pure piece arithmetic used by the engine (unit-tested). Ranges are inclusive byte offsets. */
object SegmentPlanner {
    data class Range(val start: Long, val end: Long)

    /** Splits [0, total) into at most [parts] pieces, none smaller than [minPart] (except a tiny file). */
    fun initialSplit(total: Long, parts: Int, minPart: Long): List<Range> {
        if (total <= 0) return emptyList()
        val n = minOf(parts.toLong().coerceAtLeast(1), maxOf(1L, total / minPart.coerceAtLeast(1))).toInt()
        val size = total / n
        return (0 until n).map { i ->
            val st = i * size
            Range(st, if (i == n - 1) total - 1 else st + size - 1)
        }
    }

    /** Where a piece resumes after [downloaded] bytes. */
    fun resumeFrom(start: Long, downloaded: Long): Long = start + downloaded

    /** Bytes left in a piece; Long.MAX_VALUE when its end is unknown. */
    fun remaining(start: Long, downloaded: Long, end: Long): Long =
        if (end < 0) Long.MAX_VALUE else end - resumeFrom(start, downloaded) + 1

    /**
     * Work stealing: the new piece starts halfway through what is left of the victim.
     * Returns the new piece's start, or null when the victim is too small to split.
     */
    fun stealPoint(start: Long, downloaded: Long, end: Long, minSplit: Long): Long? {
        val rem = remaining(start, downloaded, end)
        if (end < 0 || rem < minSplit) return null
        return resumeFrom(start, downloaded) + rem / 2
    }

    /** The HTTP Range header value for a piece. */
    fun rangeHeader(start: Long, downloaded: Long, end: Long): String {
        val from = resumeFrom(start, downloaded)
        return if (end >= 0) "bytes=$from-$end" else "bytes=$from-"
    }
}
