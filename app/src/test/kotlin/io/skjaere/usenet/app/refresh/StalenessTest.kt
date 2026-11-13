package io.skjaere.usenet.app.refresh

import io.skjaere.usenet.data.EpisodeMark
import io.skjaere.usenet.data.MovieSummary
import io.skjaere.usenet.data.ShowSummary
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals

class StalenessTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val la = ZoneId.of("America/Los_Angeles")
    private val now = Instant.parse("2026-10-14T18:00:00Z").toEpochMilli()
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun show(status: String = "Returning Series", next: LocalDate? = null) =
        ShowSummary(1, status = status, seasons = "1:10", next = next?.let { EpisodeMark(1, 5, null, it.toEpochDay()) })

    private fun movie(release: LocalDate?, status: String? = "Released") = MovieSummary(1, releaseDay = release?.toEpochDay(), status = status)

    @Test
    fun `shows are due again by what TMDB said of them`() {
        assertEquals(now + 7 * day, Staleness.staleAt(show("Ended"), now, now, paris))
        assertEquals(now + 7 * day, Staleness.staleAt(show("Canceled"), now, now, paris))
        assertEquals(now + day, Staleness.staleAt(show(), now, now, paris))
        // The next episode airs today (in Paris): hourly.
        assertEquals(now + hour, Staleness.staleAt(show(next = LocalDate.of(2026, 10, 14)), now, now, paris))
        assertEquals(now + hour, Staleness.staleAt(show(next = LocalDate.of(2026, 10, 10)), now, now, paris))
    }

    @Test
    fun `a show whose next episode airs tomorrow is due at local midnight`() {
        val tomorrow = LocalDate.of(2026, 10, 15)
        assertEquals(Instant.parse("2026-10-14T22:00:00Z").toEpochMilli(), Staleness.staleAt(show(next = tomorrow), now, now, paris))
        // In Los Angeles it is 11:00 on the 14th: its midnight is 13 hours away, before the daily refresh.
        assertEquals(Instant.parse("2026-10-15T07:00:00Z").toEpochMilli(), Staleness.staleAt(show(next = tomorrow), now, now, la))
        // 25 October has 25 hours in Paris (summer time ends): its start is still 00:00 local, 22:00 UTC.
        val eve = Instant.parse("2026-10-24T20:00:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-10-24T22:00:00Z").toEpochMilli(), Staleness.staleAt(show(next = LocalDate.of(2026, 10, 25)), eve, eve, paris))
    }

    @Test
    fun `movies are due by when they come out`() {
        // Out in a month: daily.
        assertEquals(now + day, Staleness.staleAt(movie(LocalDate.of(2026, 11, 14), "Post Production"), now, now, paris))
        // Out tomorrow: at local midnight.
        assertEquals(Instant.parse("2026-10-14T22:00:00Z").toEpochMilli(), Staleness.staleAt(movie(LocalDate.of(2026, 10, 15), "Post Production"), now, now, paris))
        assertEquals(now + day, Staleness.staleAt(movie(null, "Planned"), now, now, paris))
        assertEquals(now + 7 * day, Staleness.staleAt(movie(LocalDate.of(2026, 9, 1)), now, now, paris))
        assertEquals(now + 30 * day, Staleness.staleAt(movie(LocalDate.of(2019, 5, 1)), now, now, paris))
        assertEquals(now + 7 * day, Staleness.staleAt(movie(null, "Released"), now, now, paris))
    }

    @Test
    fun `facts already as old as their policy are not due again for five minutes`() {
        assertEquals(now + 5 * 60_000, Staleness.staleAt(show(), now - day, now, paris))
        assertEquals(now + 5 * 60_000, Staleness.staleAt(show(), now - 3 * day, now, paris))
        assertEquals(now + day - 2 * hour, Staleness.staleAt(show(), now - 2 * hour, now, paris))
    }

    @Test
    fun `the policy an answer is judged by`() {
        assertEquals(Duration.ofDays(1), Staleness.policy(show(), now, paris))
        assertEquals(Duration.ofDays(30), Staleness.policy(movie(LocalDate.of(2019, 5, 1)), now, paris))
    }

    @Test
    fun `failures back off, and pauses double up to a limit`() {
        assertEquals(listOf(15L, 60, 360, 1_440, 1_440), (1..5).map { Staleness.retryAfter(it).toMinutes() })
        assertEquals(Duration.ofDays(7), Staleness.NOT_FOUND_RETRY)
        assertEquals(listOf(10L, 20, 40, 80, 120, 120), (1..6).map { Staleness.pause(RefreshStatus.Reason.RateLimited, it).seconds })
        assertEquals(listOf(30L, 60, 120, 240, 480, 900, 900), (1..7).map { Staleness.pause(RefreshStatus.Reason.Offline, it).seconds })
    }
}
