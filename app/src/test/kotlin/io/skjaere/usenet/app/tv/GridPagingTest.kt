package io.skjaere.usenet.app.tv

import io.skjaere.usenet.app.tv.GridPaging.Target
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Where a TV grid's CH and PAGE keys take the focus (TV-4's L12): seven posters to a line. */
class GridPagingTest {
    @Test
    fun `outside A–Z a page is three lines, to the first poster of the line`() {
        // 40 posters: lines 0 to 5, the last (35 to 39) not full.
        assertEquals(Target.Poster(21), GridPaging.lines(from = 0, count = 40, forward = true))
        assertEquals(Target.Poster(21), GridPaging.lines(from = 4, count = 40, forward = true))
        assertEquals(Target.Poster(35), GridPaging.lines(from = 22, count = 40, forward = true), "short of three lines: the last line")
        assertEquals(Target.Stay, GridPaging.lines(from = 37, count = 40, forward = true), "on the last line already")
        assertEquals(Target.Poster(14), GridPaging.lines(from = 39, count = 40, forward = false))
        assertEquals(Target.Poster(0), GridPaging.lines(from = 16, count = 40, forward = false), "short of three lines: the first line")
        assertEquals(Target.Header, GridPaging.lines(from = 3, count = 40, forward = false), "the first line: up to the header")
    }

    @Test
    fun `from the header a page goes to the first poster, and nowhere further up`() {
        assertEquals(Target.Poster(0), GridPaging.lines(from = null, count = 40, forward = true))
        assertEquals(Target.Stay, GridPaging.lines(from = null, count = 40, forward = false))
        assertEquals(Target.Stay, GridPaging.lines(from = null, count = 0, forward = true), "no posters")
        assertEquals(Target.Poster(0), GridPaging.letters(from = null, starts = listOf(0, 5), forward = true))
        assertEquals(Target.Stay, GridPaging.letters(from = null, starts = listOf(0, 5), forward = false))
    }

    @Test
    fun `in A–Z toward the end is the next letter with titles`() {
        val starts = listOf(0, 3, 30, 31)
        assertEquals(Target.Poster(3), GridPaging.letters(from = 0, starts = starts, forward = true))
        assertEquals(Target.Poster(30), GridPaging.letters(from = 3, starts = starts, forward = true))
        assertEquals(Target.Poster(30), GridPaging.letters(from = 17, starts = starts, forward = true))
        assertEquals(Target.Stay, GridPaging.letters(from = 31, starts = starts, forward = true), "the last letter")
        assertEquals(Target.Stay, GridPaging.letters(from = 33, starts = starts, forward = true))
    }

    @Test
    fun `in A–Z toward the top is the start of the letter, else the letter before`() {
        val starts = listOf(0, 3, 30, 31)
        assertEquals(Target.Poster(3), GridPaging.letters(from = 17, starts = starts, forward = false), "past its letter's first: that first")
        assertEquals(Target.Poster(0), GridPaging.letters(from = 3, starts = starts, forward = false), "on its letter's first: the letter before")
        assertEquals(Target.Poster(30), GridPaging.letters(from = 31, starts = starts, forward = false))
        assertEquals(Target.Poster(0), GridPaging.letters(from = 2, starts = starts, forward = false))
        assertEquals(Target.Header, GridPaging.letters(from = 0, starts = starts, forward = false), "the first letter's first: up to the header")
    }

    @Test
    fun `a poster's item in the grid comes after the header's`() {
        assertEquals(3, GridPaging.itemOf(row = 0, header = 3))
        assertEquals(24, GridPaging.itemOf(row = 21, header = 3))
    }

    @Test
    fun `near the end is within three lines of the last`() {
        // 20 posters, lines 0 to 2: the first screen's two lines are within three of the last.
        assertEquals(true, GridPaging.nearEnd(lastShown = 13, count = 20))
        // 60 posters, lines 0 to 8.
        assertEquals(false, GridPaging.nearEnd(lastShown = 13, count = 60))
        assertEquals(false, GridPaging.nearEnd(lastShown = 34, count = 60), "line 4: four lines from the last")
        assertEquals(true, GridPaging.nearEnd(lastShown = 35, count = 60), "line 5")
        assertEquals(true, GridPaging.nearEnd(lastShown = 59, count = 60))
        assertEquals(false, GridPaging.nearEnd(lastShown = 13, count = 60, lines = 1))
        assertEquals(false, GridPaging.nearEnd(lastShown = null, count = 60), "nothing shown yet")
        assertEquals(false, GridPaging.nearEnd(lastShown = null, count = 0), "no posters")
    }
}
