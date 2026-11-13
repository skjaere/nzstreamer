package io.skjaere.usenet.app.collection.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a TV's poster of a title shows besides its picture: the grid's and TV Home's alike. */
class PostersTest {
    private val harbor = collected(1, "Harbor Lights")
    private val copper = collectedMovie(11, "Copper Crown")

    /** The bar says how far through a title is: none before it is begun, none once it is done (TV-4's L8). */
    @Test
    fun `a poster's bar shows only while a title is begun and not done`() {
        val d = showDetails(1)
        assertEquals(2f / 20, showRow(harbor, d, listOf(watched(1, 1, 1), watched(1, 1, 2))).posterProgress)
        assertNull(showRow(harbor, d).posterProgress, "not started")
        // Begun, nothing watched to the end yet: a bar at nothing says nothing.
        assertEquals(0f, showRow(harbor, d, listOf(started(1, 1, 1))).watchedFraction)
        assertNull(showRow(harbor, d, listOf(started(1, 1, 1))).posterProgress)
        val all = (1..10).flatMap { listOf(watched(1, 1, it), watched(1, 2, it)) }
        assertEquals(1f, showRow(harbor, d, all).watchedFraction)
        assertNull(showRow(harbor, d, all).posterProgress, "up to date")
        assertNull(showRow(harbor, d.copy(status = "Ended"), all).posterProgress, "finished")
        assertEquals(0.5f, movieRow(copper, movieSummary(11), movieWatch(11, positionMs = 3_540_000)).posterProgress)
        assertNull(movieRow(copper, movieSummary(11), movieWatch(11, watched = true)).posterProgress)
        assertNull(movieRow(copper, movieSummary(11)).posterProgress)
    }

    /** A poster's menu marks a title watched until all of it is, then unwatched; a show TMDB hasn't answered for gets no mark (TV-4's L13). */
    @Test
    fun `a poster's menu marks watched until it all is, and offers no mark for a show without facts`() {
        val d = showDetails(1)
        assertEquals(true, showRow(harbor, d).menuMark, "not started")
        assertEquals(true, showRow(harbor, d, listOf(watched(1, 1, 1))).menuMark, "watching")
        val all = (1..10).flatMap { listOf(watched(1, 1, it), watched(1, 2, it)) }
        assertEquals(false, showRow(harbor, d, all).menuMark, "up to date")
        assertEquals(false, showRow(harbor, d.copy(status = "Ended"), all).menuMark, "finished")
        // Its aired episodes aren't known, so Watched can't be marked; nor, then, is it offered the other way.
        assertNull(showRow(harbor, null).menuMark, "no facts")
        assertNull(showRow(harbor, null, listOf(watched(1, 1, 1))).menuMark, "no facts, begun")
        assertEquals(true, movieRow(copper, movieSummary(11)).menuMark, "not watched")
        assertEquals(true, movieRow(copper, movieSummary(11), movieWatch(11, positionMs = 3_540_000)).menuMark, "in progress")
        assertEquals(false, movieRow(copper, movieSummary(11), movieWatch(11, watched = true)).menuMark, "watched")
        // A movie needs no facts to be marked.
        assertEquals(true, movieRow(copper, null).menuMark, "no facts")
        assertEquals(true, movieRow(copper, movieSummary(11, release = today.plusDays(30))).menuMark, "not out yet")
    }
}
