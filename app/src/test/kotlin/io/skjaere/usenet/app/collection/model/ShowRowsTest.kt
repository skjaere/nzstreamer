package io.skjaere.usenet.app.collection.model

import io.skjaere.usenet.app.collection.recentlyAired
import io.skjaere.usenet.app.collection.toEpisodeWatches
import io.skjaere.usenet.data.RefreshFailure
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.metadata.ShowProgress
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ShowRowsTest {
    private val harbor = collected(1, "Harbor Lights")

    /**
     * The row's progress, from the stored facts and the SQL aggregate, is what the Show page computes
     * from TMDB's whole answer and every watch state: random shows and histories around today.
     */
    @Test
    fun `progress from the stored facts and the aggregate equals ShowProgress over the full answer`() {
        val random = Random(12)
        repeat(400) { round ->
            val id = 100 + round
            val counts = List(random.nextInt(1, 5)) { random.nextInt(0, 13) }
            val lastSeason = random.nextInt(1, counts.size + 1)
            val last = (lastSeason to random.nextInt(1, maxOf(2, counts[lastSeason - 1] + 1))).takeIf { random.nextInt(6) > 0 }
            val nextSeason = (last?.first ?: 1) + random.nextInt(0, 2)
            val next = (nextSeason to random.nextInt(1, 6)).takeIf { random.nextBoolean() }
            val details = showDetails(
                id, counts = counts, last = last, lastAired = today.minusDays(random.nextLong(0, 20)),
                next = next, nextAirs = today.plusDays(random.nextLong(-3, 10)).takeIf { random.nextInt(5) > 0 },
                status = listOf("Returning Series", "Ended", "Canceled").random(random), specials = random.nextInt(0, 3),
            )
            // Each played at its own time: on a tie ShowProgress takes the first in the list, which SQL doesn't order (WatchAggregatesTest pins the tie).
            val states = (1..random.nextInt(0, 30)).map { n ->
                val s = random.nextInt(0, counts.size + 2)
                val e = random.nextInt(1, 14)
                val at = random.nextLong(1, 6) * 100 + n
                when (random.nextInt(4)) {
                    0 -> started(id, s, e, positionMs = random.nextLong(0, 1_200_000), at = at)
                    else -> watched(id, s, e, at = at)
                }.let { if (random.nextInt(12) == 0) it.copy(deleted = true) else it }
            }.distinctBy { it.key }
            val live = states.filterNot { it.deleted }
            val expected = ShowProgress.of(details, live.toEpisodeWatches(), today, details.recentlyAired())
            val row = showRow(collected(id), details, states)
            assertEquals(expected, row.progress, "round $round: $details, $states")
            assertEquals(live.maxOfOrNull { it.lastPlayedAt } ?: 0, row.lastWatchedAt)
        }
    }

    /** Glasswater Bay as CollectionItemTest had it: season 1's four aired, TMDB lists the next for the 20th. */
    private val airs = today.plusDays(6)

    private fun glasswater(next: EpisodeSummary, counts: List<Int> = listOf(5, 8)) = ShowDetails(
        1, "Glasswater Bay", null, 2026, null, null, null, "Returning Series", true, 45,
        counts.mapIndexed { i, n -> SeasonSummary(i + 1, null, n, null, null) },
        lastEpisode = EpisodeSummary(1, 4, null, today.minusDays(2)), nextEpisode = next, ids = ExternalIds(),
    )

    private val season1 = (1..4).map { watched(1, 1, it) }

    @Test
    fun `an episode watched before TMDB's date isn't still said to air then`() {
        val e05 = glasswater(EpisodeSummary(1, 5, null, airs))
        assertEquals("S01E05 airs 20 Oct", showRow(harbor, e05, season1).status)
        assertEquals("Up to date", showRow(harbor, e05, season1 + watched(1, 1, 5)).status)
        assertEquals(ShowState.UpToDate, showRow(harbor, e05, season1 + watched(1, 1, 5)).state)
    }

    @Test
    fun `a season premiere says when the season returns, unless it was watched before`() {
        val premiere = glasswater(EpisodeSummary(2, 1, null, airs), counts = listOf(4, 8))
        assertEquals("Season 2 returns 20 Oct", showRow(harbor, premiere, season1).status)
        assertEquals("Up to date", showRow(harbor, premiere, season1 + watched(1, 2, 1)).status)
    }

    @Test
    fun `a first episode still to air is said to air, not to return`() {
        val pilot = showDetails(1, counts = listOf(8), last = null, lastAired = null, next = 1 to 1, nextAirs = today.plusDays(30))
        assertEquals("S01E01 airs 13 Nov", showRow(harbor, pilot).status)
    }

    @Test
    fun `each line a show with facts can have`() {
        val d = showDetails(1)
        assertEquals("Next: S01E01 · not started", showRow(harbor, d).status)
        assertEquals("Next: S01E03 · 18 unwatched", showRow(harbor, d, listOf(watched(1, 1, 1), watched(1, 1, 2))).status)
        assertEquals("Resume S01E03 · 18 unwatched", showRow(harbor, d, listOf(watched(1, 1, 1), watched(1, 1, 2), started(1, 1, 3))).status)
        val nearlyDone = (1..19).map { if (it <= 10) watched(1, 1, it) else watched(1, 2, it - 10) } + started(1, 2, 10)
        assertEquals("Resume S02E10", showRow(harbor, d, nearlyDone).status)
        val all = (1..10).flatMap { listOf(watched(1, 1, it), watched(1, 2, it)) }
        assertEquals("Up to date", showRow(harbor, d, all).status)
        assertEquals("Finished · all 20 watched", showRow(harbor, d.copy(status = "Ended"), all).status)
        assertEquals("Finished · all 20 watched", showRow(harbor, d.copy(status = "Canceled"), all).status)
        assertEquals("Up to date", showRow(harbor, showDetails(1, next = 3 to 1, nextAirs = null), all).status)
    }

    @Test
    fun `each line a show without facts can have`() {
        assertEquals("Checking TMDB…", showRow(harbor, null).status)
        assertEquals("Couldn't load from TMDB", showRow(harbor, null, failure = "Server").status)
        assertEquals("Not on TMDB", showRow(harbor, null, failure = "NotFound").status)
        assertEquals("No episode info yet", showRow(harbor, null, hasKey = false).status)
        assertEquals("No episode info yet", showRow(harbor, null, failure = "Server", hasKey = false).status)
        assertEquals(
            listOf(LoadState.Pending, LoadState.Failed, LoadState.NotFound, LoadState.NoKey, LoadState.Ready),
            listOf(showRow(harbor, null), showRow(harbor, null, failure = "Server"), showRow(harbor, null, failure = "NotFound"), showRow(harbor, null, hasKey = false), showRow(harbor, showDetails(1))).map { it.load },
        )
    }

    /** A TV poster's one line under its title: short enough for one line at 13 sp; the full status is still TalkBack's. */
    @Test
    fun `each card line a show with facts can have`() {
        val d = showDetails(1)
        assertEquals("S01E01", showRow(harbor, d).cardLine)
        assertEquals("S01E03", showRow(harbor, d, listOf(watched(1, 1, 1), watched(1, 1, 2))).cardLine)
        assertEquals("Resume S01E03", showRow(harbor, d, listOf(watched(1, 1, 1), watched(1, 1, 2), started(1, 1, 3))).cardLine)
        val all = (1..10).flatMap { listOf(watched(1, 1, it), watched(1, 2, it)) }
        assertEquals("Up to date", showRow(harbor, d, all).cardLine)
        assertEquals("Finished", showRow(harbor, d.copy(status = "Ended"), all).cardLine)
        // A season premiere comes back; another episode airs. Another year's date gives the month and the year.
        assertEquals("Back 20 Oct", showRow(harbor, glasswater(EpisodeSummary(2, 1, null, airs), counts = listOf(4, 8)), season1).cardLine)
        assertEquals("Back Nov 2027", showRow(harbor, glasswater(EpisodeSummary(2, 1, null, LocalDate.of(2027, 11, 14)), counts = listOf(4, 8)), season1).cardLine)
        assertEquals("Airs 20 Oct", showRow(harbor, glasswater(EpisodeSummary(1, 5, null, airs)), season1).cardLine)
        assertEquals("Airs Jan 2027", showRow(harbor, glasswater(EpisodeSummary(1, 5, null, LocalDate.of(2027, 1, 9))), season1).cardLine)
        val pilot = showDetails(1, counts = listOf(8), last = null, lastAired = null, next = 1 to 1, nextAirs = today.plusDays(30))
        assertEquals("Airs 13 Nov", showRow(harbor, pilot).cardLine)
    }

    @Test
    fun `each card line a show without facts can have`() {
        assertEquals("Checking…", showRow(harbor, null).cardLine)
        assertEquals("Couldn't load", showRow(harbor, null, failure = "Server").cardLine)
        assertEquals("Not on TMDB", showRow(harbor, null, failure = "NotFound").cardLine)
        assertEquals("No episodes yet", showRow(harbor, null, hasKey = false).cardLine)
    }

    /** Under Next to air a poster's line is when the next episode airs, as the phone's date pill says it. */
    @Test
    fun `a poster's line under Next to air is the next episode's air date`() {
        val d = showDetails(1, counts = listOf(10, 6), last = 2 to 6, lastAired = today.minusDays(3), next = 2 to 7, nextAirs = today.plusDays(4))
        val row = showRow(harbor, d, (1..10).map { watched(1, 1, it) } + watched(1, 2, 1))
        assertEquals("Airs 18 Oct", row.airsLine)
        assertEquals("Airs 18 Oct", row.posterLine(nextToAir = true))
        assertEquals("S02E02", row.posterLine(nextToAir = false))
        assertEquals("Airs Nov 2027", showRow(harbor, d.copy(nextEpisode = EpisodeSummary(2, 7, null, LocalDate.of(2027, 11, 2)))).airsLine)
        // Nothing to air: the card's own line.
        val ended = showRow(harbor, showDetails(1))
        assertNull(ended.airsLine)
        assertEquals("S01E01", ended.posterLine(nextToAir = true))
    }

    @Test
    fun `states follow the progress, and shows without facts are not started only when nothing was played`() {
        val d = showDetails(1)
        val all = (1..10).flatMap { listOf(watched(1, 1, it), watched(1, 2, it)) }
        assertEquals(ShowState.NotStarted, showRow(harbor, d).state)
        assertEquals(ShowState.Watching, showRow(harbor, d, listOf(watched(1, 1, 1))).state)
        assertEquals(ShowState.Watching, showRow(harbor, d, all.dropLast(1) + started(1, 2, 10)).state)
        assertEquals(ShowState.UpToDate, showRow(harbor, d, all).state)
        assertEquals(ShowState.Finished, showRow(harbor, d.copy(status = "Ended"), all).state)
        assertEquals(ShowState.NotStarted, showRow(harbor, null).state)
        // Specials and a start under a minute aren't "started".
        assertEquals(ShowState.NotStarted, showRow(harbor, null, listOf(watched(1, 0, 1), started(1, 1, 1, positionMs = 30_000))).state)
        assertEquals(ShowState.Unknown, showRow(harbor, null, listOf(watched(1, 1, 1))).state)
        assertEquals(ShowState.Unknown, showRow(harbor, null, listOf(started(1, 1, 1))).state)
    }

    @Test
    fun `new episodes, the next air date, the last aired date and the share watched`() {
        val d = showDetails(1, counts = listOf(10, 6), last = 2 to 6, lastAired = today.minusDays(3), next = 2 to 7, nextAirs = today.plusDays(4))
        val row = showRow(harbor, d, (1..10).map { watched(1, 1, it) } + watched(1, 2, 1))
        assertEquals(1, row.newEpisodes)
        assertEquals(today.plusDays(4), row.upcoming)
        assertEquals("18 Oct", row.upcomingLabel)
        assertEquals(today.minusDays(3), row.lastAired)
        assertEquals(11f / 16, row.watchedFraction)
        assertNull(showRow(harbor, d).watchedFraction)
        // A next episode TMDB lists for today has aired: no longer upcoming tomorrow.
        val airsToday = d.copy(nextEpisode = EpisodeSummary(2, 7, null, today))
        assertEquals(today, showRow(harbor, airsToday).upcoming)
        assertEquals(today, showRow(harbor, airsToday).lastAired)
        assertNull(showRow(harbor, airsToday, day = today.plusDays(1)).upcoming)
        assertTrue(showRow(harbor, d.copy(status = "Ended")).ended)
    }

    @Test
    fun `TalkBack hears the row with its units spelled out`() {
        val d = showDetails(1, name = "Harbor Lights", counts = listOf(10, 6), last = 2 to 6, lastAired = today.minusDays(3))
        val row = showRow(harbor, d, (1..10).map { watched(1, 1, it) } + (1..4).map { watched(1, 2, it) })
        assertEquals("Harbor Lights. Next: season 2, episode 5. 2 unwatched. New episode.", row.a11y)
        assertEquals("Harbor Lights. Checking TMDB…", showRow(harbor, null).a11y)
    }

    @Test
    fun `names, years and posters come from the collection, the original name from TMDB`() {
        val show = collected(1, "Harbor Lights", year = 2021).copy(posterPath = "/hl.jpg")
        val row = showRow(show, showDetails(1, name = "Hamnljus", originalName = "Hamnljus"))
        assertEquals(TitleText("Harbor Lights", "Hamnljus", 2021), row.text)
        assertEquals("/hl.jpg", row.posterPath)
        assertEquals(TitleKey.show(1), row.key)
    }

    @Test
    fun `the cache gives the same row for the same inputs and builds only what changed`() {
        val cache = RowCache<ShowRow>()
        val shows = (1..3).map { collected(it) }
        val summaries = shows.associate { it.tmdbId to showDetails(it.tmdbId).toSummary() }
        val watches = mapOf(1 to aggregateOf(1, listOf(watched(1, 1, 1)))!!, 2 to aggregateOf(2, listOf(watched(2, 1, 1)))!!)
        val first = ShowRows.build(shows, summaries, watches, emptyMap(), today, true, cache)
        assertEquals(3, cache.built)
        val changed = watches + (2 to aggregateOf(2, listOf(watched(2, 1, 1), watched(2, 1, 2)))!!)
        val second = ShowRows.build(shows, summaries, changed, emptyMap(), today, true, cache)
        assertEquals(4, cache.built)
        assertSame(first[0], second[0])
        assertNotSame(first[1], second[1])
        assertSame(first[2], second[2])
        // A rebuilt row keeps the title text it had, so its search keys aren't worked out again.
        assertSame(first[1].text, second[1].text)
        assertEquals("Next: S01E03 · 18 unwatched", second[1].status)
        // A show gone from the collection is forgotten.
        ShowRows.build(shows.take(1), summaries, changed, emptyMap(), today, true, cache)
        assertSame(first[0], ShowRows.build(shows.take(1), summaries, changed, emptyMap(), today, true, cache)[0])
        assertEquals(4, cache.built)
        assertEquals(1, cache.size)
    }

    @Test
    fun `failures are matched to their titles`() {
        val failures = listOf(RefreshFailure("show", 1, "NotFound"), RefreshFailure("movie", 1, "Server"))
        assertEquals(mapOf(TitleKey.show(1) to "NotFound", TitleKey.movie(1) to "Server"), RowInputs.failures(failures))
    }
}
