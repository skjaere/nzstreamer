package io.skjaere.usenet.serve

/** What a request's Range asks of a file (L53). */
internal sealed interface RangeAnswer {
    /** The whole file, a 200: no Range, or one the server ignores. */
    data object Whole : RangeAnswer

    /** Bytes [first] to [last] of it, a 206. */
    data class Part(val first: Long, val last: Long) : RangeAnswer

    /** A 416: a start at or past the end, or a suffix of nothing. */
    data object Unsatisfiable : RangeAnswer
}

/**
 * What [ranges] (every Range field of the request) ask of a file of [size] bytes, [ifRange] when an If-Range came:
 * `bytes=N-`, `N-M` (M clamped to the last byte) and `-N` (the whole file, as a 206, for N at least the size) are served
 * from the byte asked; `-0` and a start at or past the end are a 416. Several ranges, a malformed one, more than one Range
 * field or an If-Range (the server has no validators) leave the Range ignored, as RFC 9110 allows: the whole file. A Cast
 * receiver only ever sent `bytes=N-` (item 6(a)); the rest is cheap.
 */
internal fun rangeAnswer(ranges: List<String>, ifRange: Boolean, size: Long): RangeAnswer {
    if (ranges.size != 1 || ifRange) return RangeAnswer.Whole
    val range = ranges.single()
    val equals = range.indexOf('=')
    if (equals < 0 || !range.substring(0, equals).equals("bytes", ignoreCase = true)) return RangeAnswer.Whole
    val spec = range.substring(equals + 1)
    if (',' in spec) return RangeAnswer.Whole
    val dash = spec.indexOf('-')
    if (dash < 0) return RangeAnswer.Whole
    val start = spec.substring(0, dash)
    val end = spec.substring(dash + 1)
    if (start.isEmpty()) {
        val suffix = number(end) ?: return RangeAnswer.Whole
        if (suffix == 0L || size == 0L) return RangeAnswer.Unsatisfiable
        return RangeAnswer.Part(maxOf(0, size - suffix), size - 1)
    }
    val first = number(start) ?: return RangeAnswer.Whole
    val last = if (end.isEmpty()) null else number(end) ?: return RangeAnswer.Whole
    if (last != null && last < first) return RangeAnswer.Whole
    if (first >= size) return RangeAnswer.Unsatisfiable
    return RangeAnswer.Part(first, minOf(last ?: (size - 1), size - 1))
}

/** Digits alone, as a byte position; null for anything else or one too big for a file. */
private fun number(text: String): Long? = if (text.isNotEmpty() && text.length <= 18 && text.all { it in '0'..'9' }) text.toLong() else null
