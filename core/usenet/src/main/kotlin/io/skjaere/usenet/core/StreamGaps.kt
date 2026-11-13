package io.skjaere.usenet.core

/**
 * The stretches of a file that its streams fill with zeros, as no provider can give their articles: file offsets, merged
 * where they touch or overlap. The engine's streams zero-fill such articles rather than end there
 * ([UsenetEngine.defaultStreamer]), and record each stretch as they find it ([NzbStreamOpener]), the read-ahead's too, on
 * their download threads, before its zeros reach the channel: a reader that has read a byte of one can tell it is one.
 * Safe from any thread. [listen]ers hear of each stretch as it is added, those added before they listened first.
 */
class StreamGaps {
    // Apart from each other (none touch), in file order: few in a file, so lists will do.
    private val ranges = ArrayList<LongRange>()
    private val listeners = ArrayList<(LongRange) -> Unit>()

    /** Adds [range], merged with those it touches or overlaps; its listeners hear of it as it came. */
    @Synchronized
    fun add(range: LongRange) {
        if (range.isEmpty()) return
        var from = range.first
        var to = range.last
        // The first that ends at or after the byte before it; it and those after it that start by the byte after it go into it.
        val at = ranges.indexOfFirst { it.last + 1 >= from }.let { if (it < 0) ranges.size else it }
        var end = at
        while (end < ranges.size && ranges[end].first <= to + 1) {
            from = minOf(from, ranges[end].first)
            to = maxOf(to, ranges[end].last)
            end++
        }
        ranges.subList(at, end).clear()
        ranges.add(at, from..to)
        listeners.forEach { it(range) }
    }

    /**
     * The stretch, merged, that ends at or after [position]: the one holding it, else the next after it; null when none
     * does. A reader asks on every read, millions of them as a container's index is read a few bytes at a time (#224), so
     * the list is gone through by its indices: an iterator would be an object a read.
     */
    @Synchronized
    fun next(position: Long): LongRange? {
        var i = 0
        while (i < ranges.size) {
            val range = ranges[i++]
            if (range.last >= position) return range
        }
        return null
    }

    /** Every stretch, merged, in file order. */
    @Synchronized
    fun all(): List<LongRange> = ranges.toList()

    /** Calls [listener] for each stretch: at once for those so far (merged), then for each as it is added. */
    @Synchronized
    fun listen(listener: (LongRange) -> Unit) {
        ranges.forEach(listener)
        listeners += listener
    }
}
