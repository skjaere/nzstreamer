package io.skjaere.usenet.app.collection.model

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MovieRowsTest {
    private val copper = collectedMovie(11, "Copper Crown", year = 2019)
    private val out = movieSummary(11)

    @Test
    fun `each line a movie can have`() {
        assertEquals("Not watched", movieRow(copper, out).status)
        // 42 min 1 s left rounds up.
        assertEquals("43 min left", movieRow(copper, out, movieWatch(11, positionMs = 7_080_000 - 2_521_000)).status)
        assertEquals("42 min left", movieRow(copper, out, movieWatch(11, positionMs = 7_080_000 - 2_520_000)).status)
        assertEquals("1 h 12 min left", movieRow(copper, out, movieWatch(11, positionMs = 7_080_000 - 4_320_000)).status)
        assertEquals("In progress", movieRow(copper, out, movieWatch(11, positionMs = 600_000, durationMs = 0)).status)
        assertEquals("✓ Watched", movieRow(copper, out, movieWatch(11, watched = true)).status)
        assertEquals("Out 12 Mar 2027", movieRow(copper, movieSummary(11, release = LocalDate.of(2027, 3, 12), status = "Post Production")).status)
        assertEquals("Out 12 Dec", movieRow(copper, movieSummary(11, release = LocalDate.of(2026, 12, 12), status = "Post Production")).status)
        assertEquals("Release date not announced", movieRow(copper, movieSummary(11, release = null, status = "In Production")).status)
        assertEquals("Not watched", movieRow(copper, movieSummary(11, release = null, status = "Released")).status)
    }

    /** A TV poster's one line under its title: short enough for one line at 13 sp; the full status is still TalkBack's. */
    @Test
    fun `each card line a movie can have`() {
        assertEquals("2019 · 1 h 58", movieRow(copper, out).cardLine)
        assertEquals("2019 · 45 min", movieRow(copper, movieSummary(11, runtime = 45)).cardLine)
        assertEquals("2019 · 2 h", movieRow(copper, movieSummary(11, runtime = 120)).cardLine)
        assertEquals("2019", movieRow(copper, movieSummary(11, runtime = null)).cardLine)
        assertEquals("1 h 58", movieRow(copper.copy(year = null), out).cardLine)
        assertEquals("42 min left", movieRow(copper, out, movieWatch(11, positionMs = 7_080_000 - 2_520_000)).cardLine)
        assertEquals("1 h 12 left", movieRow(copper, out, movieWatch(11, positionMs = 7_080_000 - 4_320_000)).cardLine)
        assertEquals("In progress", movieRow(copper, out, movieWatch(11, positionMs = 600_000, durationMs = 0)).cardLine)
        assertEquals("✓ Watched", movieRow(copper, out, movieWatch(11, watched = true)).cardLine)
        assertEquals("Out 12 Dec", movieRow(copper, movieSummary(11, release = LocalDate.of(2026, 12, 12), status = "Post Production")).cardLine)
        assertEquals("Out Mar 2027", movieRow(copper, movieSummary(11, release = LocalDate.of(2027, 3, 12), status = "Post Production")).cardLine)
        assertEquals("Date not set", movieRow(copper, movieSummary(11, release = null, status = "In Production")).cardLine)
    }

    @Test
    fun `each card line a movie without facts can have`() {
        assertEquals("Checking…", movieRow(copper, null).cardLine)
        assertEquals("Couldn't load", movieRow(copper, null, failure = "BadResponse").cardLine)
        assertEquals("Not on TMDB", movieRow(copper, null, failure = "NotFound").cardLine)
        // Without a key nothing is to come: what a movie not watched says, its year.
        assertEquals("2019", movieRow(copper, null, hasKey = false).cardLine)
        assertEquals("Not watched", movieRow(copper.copy(year = null), null, hasKey = false).cardLine)
        // What was watched is known without facts.
        assertEquals("✓ Watched", movieRow(copper, null, movieWatch(11, watched = true)).cardLine)
        // Under Next to air (a show's sort) a movie's line is its own.
        assertEquals("2019", movieRow(copper, null, hasKey = false).posterLine(nextToAir = true))
    }

    @Test
    fun `in progress comes before watched and upcoming, and a minute in is in progress`() {
        val upcoming = movieSummary(11, release = today.plusDays(30), status = "Post Production")
        assertEquals(MovieState.InProgress, movieRow(copper, upcoming, movieWatch(11, positionMs = 60_000)).state)
        assertEquals(MovieState.Upcoming, movieRow(copper, upcoming, movieWatch(11, positionMs = 59_000)).state)
        assertEquals(MovieState.Watched, movieRow(copper, upcoming, movieWatch(11, watched = true)).state)
        assertEquals(MovieState.Upcoming, movieRow(copper, upcoming).state)
        assertEquals(MovieState.NotWatched, movieRow(copper, movieSummary(11, release = today)).state)
        assertEquals(MovieState.NotWatched, movieRow(copper, out, movieWatch(11, positionMs = 30_000)).state)
    }

    @Test
    fun `without facts, only what was watched is known`() {
        assertEquals(MovieState.Unknown, movieRow(copper, null).state)
        assertEquals(MovieState.InProgress, movieRow(copper, null, movieWatch(11, positionMs = 600_000)).state)
        assertEquals(MovieState.Watched, movieRow(copper, null, movieWatch(11, watched = true)).state)
        assertEquals("Checking TMDB…", movieRow(copper, null).status)
        assertEquals("Couldn't load from TMDB", movieRow(copper, null, failure = "BadResponse").status)
        assertEquals("Not on TMDB", movieRow(copper, null, failure = "NotFound").status)
        assertEquals("Not watched", movieRow(copper, null, hasKey = false).status)
        assertEquals("2019", movieRow(copper, null).meta)
    }

    @Test
    fun `the meta line has the year and the runtime`() {
        assertEquals("2019 · 1 h 58 min", movieRow(copper, out).meta)
        assertEquals("2019 · 45 min", movieRow(copper, movieSummary(11, runtime = 45)).meta)
        assertEquals("2019 · 2 h", movieRow(copper, movieSummary(11, runtime = 120)).meta)
        assertEquals("2019", movieRow(copper, movieSummary(11, runtime = null)).meta)
        assertEquals("1 h 58 min", movieRow(copper.copy(year = null), out).meta)
    }

    @Test
    fun `progress shows for a movie in progress only`() {
        assertEquals(0.5f, movieRow(copper, out, movieWatch(11, positionMs = 3_540_000)).progressFraction)
        assertNull(movieRow(copper, out, movieWatch(11, watched = true)).progressFraction)
        assertNull(movieRow(copper, out, movieWatch(11, positionMs = 600_000, durationMs = 0)).progressFraction)
    }

    @Test
    fun `TalkBack hears the row with its units spelled out`() {
        assertEquals("Copper Crown, 2019, 1 hour 58 minutes, 42 minutes left", movieRow(copper, out, movieWatch(11, positionMs = 7_080_000 - 2_520_000)).a11y)
        assertEquals("Copper Crown, 2019, 1 minute, Watched", movieRow(copper, movieSummary(11, runtime = 1), movieWatch(11, watched = true)).a11y)
        assertEquals("Copper Crown, 2019, 2 hours, Not watched", movieRow(copper, movieSummary(11, runtime = 120)).a11y)
    }

    @Test
    fun `the release date, when it was watched and the original title are kept for the lists`() {
        val row = movieRow(copper, movieSummary(11, original = "Kopparkronan"), movieWatch(11, watched = true, at = 77))
        assertEquals(LocalDate.of(2019, 5, 1), row.releaseDate)
        assertEquals(77, row.lastWatchedAt)
        assertEquals(TitleText("Copper Crown", "Kopparkronan", 2019), row.text)
        assertEquals(118, row.runtimeMinutes)
        assertEquals(LoadState.Ready, row.load)
        assertEquals(TitleKey.movie(11), row.key)
    }
}
