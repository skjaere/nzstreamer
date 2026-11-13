package io.skjaere.usenet.app.tv

/**
 * Where a TV grid's paging keys take the focus (the remote's CH and a keyboard's PAGE, toward the end or the top): seven
 * posters to a line, three lines a page, or in A–Z a letter at a time. Pure, so the targets are tested on the JVM. A
 * poster is its [Target.Poster.row] among the posters; [itemOf] turns that into the grid's item, which counts the
 * header's lines before it.
 */
object GridPaging {
    /** Posters to a line. */
    const val COLUMNS = 7

    /** Lines a page moves outside A–Z. */
    const val LINES = 3

    /** Where a paging key takes the focus. */
    sealed interface Target {
        /** The poster at [row]. */
        data class Poster(val row: Int) : Target

        /** Above the posters: the header's lines (the collection's switch). */
        data object Header : Target

        /** There is nothing further that way: the focus stays. */
        data object Stay : Target
    }

    /**
     * From the poster at [from] of [count] (null: from the header above them), [LINES] lines toward the end ([forward]) or
     * the top, to the first poster of that line, but no further than the first or the last line; toward the top from
     * the first line, the header.
     */
    fun lines(from: Int?, count: Int, forward: Boolean): Target {
        if (count == 0) return Target.Stay
        if (from == null) return if (forward) Target.Poster(0) else Target.Stay
        val line = from / COLUMNS
        val last = (count - 1) / COLUMNS
        return when {
            forward -> if (line >= last) Target.Stay else Target.Poster(minOf(line + LINES, last) * COLUMNS)
            line == 0 -> Target.Header
            else -> Target.Poster(maxOf(line - LINES, 0) * COLUMNS)
        }
    }

    /**
     * A–Z, by [starts] (the first poster of each letter with titles, in order): toward the end the next letter's first;
     * toward the top the first of [from]'s own letter when [from] is past it, else the letter before's; from the first
     * letter's first, the header. From the header (null) toward the end, the first poster.
     */
    fun letters(from: Int?, starts: List<Int>, forward: Boolean): Target {
        if (starts.isEmpty()) return Target.Stay
        if (from == null) return if (forward) Target.Poster(starts.first()) else Target.Stay
        return if (forward) {
            starts.firstOrNull { it > from }?.let(Target::Poster) ?: Target.Stay
        } else {
            starts.lastOrNull { it < from }?.let(Target::Poster) ?: Target.Header
        }
    }

    /** The grid's item of the poster at [row], after the [header]'s lines. */
    fun itemOf(row: Int, header: Int): Int = header + row

    /** Lines before the last that count as near the end ([nearEnd]): a page of 20 posters is about three. */
    const val AHEAD = 3

    /**
     * Whether the posters shown come within [lines] lines of the last of [count] ([lastShown] the last poster shown, null
     * before one is): what a grid that grows as it is scrolled asks for more by, before the user reaches the end.
     */
    fun nearEnd(lastShown: Int?, count: Int, lines: Int = AHEAD): Boolean =
        lastShown != null && count > 0 && lastShown / COLUMNS >= (count - 1) / COLUMNS - lines
}
