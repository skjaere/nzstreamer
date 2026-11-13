package io.skjaere.usenet.app.play

import androidx.media3.common.C
import io.skjaere.usenet.data.LibraryRepository
import io.skjaere.usenet.data.WatchKind
import org.junit.jupiter.api.Test
import kotlin.math.ceil
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoAdvanceTest {
    private var clock = 1_000_000L
    private val duration = 45 * 60_000L
    private val ready = Prefetch.Ready("Harbor.Lights.S01E02.1080p.WEB-DL.AAC2.0.H.264-GOOD", 2_200_000_000)

    private fun machine(autoPlayNext: Boolean = true) = AutoAdvance(autoPlayNext, now = { clock })

    private fun at(
        positionMs: Long,
        durationMs: Long = duration,
        paused: Boolean = false,
        ended: Boolean = false,
        creditsMs: Long? = null,
        stalled: Boolean = false,
        hidden: Boolean = false,
    ) = AutoAdvance.Playback(positionMs, durationMs, paused, ended, creditsMs, stalled, hidden)

    /**
     * Plays (or sits paused) from [fromMs] for [forMs] of wall-clock time, updating every 100 ms as
     * the player does; the file ends at [duration]. Returns every step, the first at the current time.
     */
    private fun AutoAdvance.play(fromMs: Long, forMs: Long, paused: Boolean = false, prefetch: Prefetch? = ready, creditsMs: Long? = null) =
        play(fromMs, forMs, paused, creditsMs, status = { prefetch }, onStart = {})

    /**
     * As [play], with the player reporting [search] and starting it when told to. [stalled] keeps
     * the position where it is without a pause, as a player stopped on an error does.
     */
    private fun AutoAdvance.play(fromMs: Long, forMs: Long, search: Search, paused: Boolean = false, creditsMs: Long? = null, stalled: Boolean = false) =
        play(fromMs, forMs, paused, creditsMs, search::status, stalled) { search.starts += clock }

    private fun AutoAdvance.play(
        fromMs: Long,
        forMs: Long,
        paused: Boolean,
        creditsMs: Long?,
        status: () -> Prefetch?,
        stalled: Boolean = false,
        onStart: () -> Unit,
    ): List<AutoAdvance.Step> {
        val start = clock
        return (0..forMs step 100).map { dt ->
            clock = start + dt
            val position = if (paused || stalled) fromMs else (fromMs + dt).coerceAtMost(duration)
            update(at(position, paused = paused, ended = position >= duration, creditsMs = creditsMs, stalled = stalled), status()).also { if (it.startPrefetch) onStart() }
        }
    }

    /**
     * The next episode's search as the player reports it, run as the coordinator does: it finds
     * [ready] five seconds after it starts and hands it on for ten minutes, and the player reports
     * no search once what it found would lapse before a countdown could end.
     */
    private inner class Search {
        /** When each search started. */
        val starts = mutableListOf<Long>()

        fun status(): Prefetch? {
            val age = clock - (starts.lastOrNull() ?: return null)
            return when {
                age < 5_000 -> Prefetch.Searching
                age < 5_000 + 10 * 60_000L - AutoAdvance.COUNTDOWN_MS -> ready
                else -> null
            }
        }
    }

    @Test
    fun `the card shows once ten seconds are left, not before`() {
        val a = machine()
        assertNull(a.update(at(duration - 10_001), ready).card)
        assertEquals(AutoAdvance.Card(secondsLeft = 10, progress = 1f, prefetch = ready), a.update(at(duration - 10_000), ready).card)
    }

    /** The device tests' shorter timings ([io.skjaere.usenet.app.PlayerActivity.timings]): the card and its countdown follow them. */
    @Test
    fun `shorter timings, as a device test gives, show the card and count down from them`() {
        val a = AutoAdvance(autoPlayNext = true, now = { clock }, cardBeforeEndMs = 2_000, countdownMs = 1_000)
        val steps = a.play(duration - 3_000, 3_000)
        // Shown a second in, with two seconds left, its one-second countdown full; moving on a second later.
        assertEquals(10, steps.indexOfFirst { it.card != null })
        assertEquals(AutoAdvance.Card(secondsLeft = 1, progress = 1f, prefetch = ready), steps[10].card)
        assertEquals(0.5f, steps[15].card?.progress!!, 0.001f)
        assertEquals(listOf(20), steps.indices.filter { steps[it].advance })
    }

    @Test
    fun `the card shows when playback ends, also after a seek straight past the last ticks`() {
        // A seek to the end can end playback before any tick saw the last ten seconds, and some
        // files never report a duration.
        val unknown = machine()
        assertNull(unknown.update(at(20 * 60_000L, durationMs = C.TIME_UNSET), ready).card)
        assertNotNull(unknown.update(at(20 * 60_000L, durationMs = C.TIME_UNSET, ended = true), ready).card)

        val seeked = machine()
        assertNull(seeked.update(at(20 * 60_000L), ready).card)
        assertEquals(10, seeked.update(at(duration, ended = true), ready).card?.secondsLeft)
    }

    @Test
    fun `the countdown holds while the user has paused`() {
        val a = machine()
        val counting = a.play(duration - 10_000, 3_000)
        assertEquals(7, counting.last().card?.secondsLeft)
        assertEquals(0.7f, counting.last().card?.progress!!, 0.001f)

        val held = a.play(duration - 7_000, 20_000, paused = true)
        assertTrue(held.none { it.advance })
        assertEquals(7, held.last().card?.secondsLeft)

        val resumed = a.play(duration - 7_000, 7_000)
        assertEquals(listOf(resumed.lastIndex), resumed.indices.filter { resumed[it].advance })
    }

    /** Under the audio and subtitles sheet: held while playback goes on to the end and past it, running on once the card shows again. */
    @Test
    fun `the countdown holds while the card is out of sight, also once the file has ended`() {
        val a = machine()
        a.play(duration - 10_000, 3_000)
        val hidden = (100..20_000L step 100).map { dt ->
            clock += 100
            val position = (duration - 7_000 + dt).coerceAtMost(duration)
            a.update(at(position, ended = position >= duration, hidden = true), ready)
        }
        assertTrue(hidden.none { it.advance })
        assertEquals(7, hidden.last().card?.secondsLeft)
        val shown = a.play(duration, 7_000)
        assertEquals(listOf(shown.lastIndex), shown.indices.filter { shown[it].advance })
    }

    /**
     * W1 as the machine takes it: an episode ending out of sight, as in the picture-in-picture window, waits for the card to
     * be seen, its countdown whole. The machine still says what the card would show meanwhile (M3), so the player keeps the
     * card's view hidden itself (PipScene). That the player tells it the card is hidden in the window is
     * PictureInPictureDeviceTest's anEpisodeEndingInTheWindowWaitsThereUntilExpanded.
     */
    @Test
    fun `an episode ending out of sight holds its whole countdown, which runs once the card can be seen`() {
        val a = machine()
        val unseen = (100..25_000L step 100).map { dt ->
            clock += 100
            val position = (duration - 15_000 + dt).coerceAtMost(duration)
            a.update(at(position, ended = position >= duration, hidden = true), ready)
        }
        assertTrue(unseen.none { it.advance })
        assertEquals(10, unseen.last().card?.secondsLeft, "the card the player keeps hidden, its countdown whole")
        val shown = a.play(duration, 10_000)
        assertEquals(10, shown.first().card?.secondsLeft)
        assertEquals(listOf(shown.lastIndex), shown.indices.filter { shown[it].advance })
    }

    @Test
    fun `once the file has ended the countdown runs, paused or not`() {
        val a = machine()
        val steps = a.play(duration, 10_000, paused = true)
        assertEquals(10, steps.first().card?.secondsLeft)
        assertTrue(steps.last().advance)
    }

    @Test
    fun `reaching zero advances exactly once`() {
        val a = machine()
        val steps = a.play(duration - 10_000, 20_000)
        assertEquals(listOf(100), steps.indices.filter { steps[it].advance })
        assertEquals(1, steps[99].card?.secondsLeft)
        // Leaving now: nothing more to show or do.
        assertTrue(steps.drop(101).all { it == AutoAdvance.Step(null) })
        assertFalse(a.playNow())
    }

    @Test
    fun `play now advances at once, and only once`() {
        val a = machine()
        a.play(duration - 10_000, 2_000)
        assertTrue(a.playNow())
        assertFalse(a.playNow())
        assertTrue(a.play(duration - 8_000, 20_000).none { it.advance })
    }

    @Test
    fun `stop hides the card and nothing advances for this episode`() {
        val a = machine()
        a.play(duration - 10_000, 2_000)
        a.stop()
        assertTrue(a.play(duration - 8_000, 30_000).all { it.card == null && !it.advance })
        assertFalse(a.playNow())
        // Nor after seeking back and reaching the end again.
        assertTrue(a.play(duration - 60_000, 70_000).all { it.card == null && !it.advance })
    }

    @Test
    fun `without auto-play the card has no countdown and only play now moves on`() {
        val a = machine(autoPlayNext = false)
        val steps = a.play(duration - 10_000, 30_000)
        assertEquals(AutoAdvance.Card(secondsLeft = null, progress = null, prefetch = ready), steps.first().card)
        assertTrue(steps.all { it.card?.secondsLeft == null && it.card?.progress == null && !it.advance })
        assertTrue(a.playNow())
    }

    @Test
    fun `when the search found nothing there is no countdown and no advance, but play now still works`() {
        val a = machine()
        a.play(duration - 10_000, 3_000, prefetch = Prefetch.Searching)
        val steps = a.play(duration - 7_000, 20_000, prefetch = Prefetch.NothingFound)
        assertTrue(steps.all { it.card == AutoAdvance.Card(secondsLeft = null, progress = null, prefetch = Prefetch.NothingFound) })
        assertTrue(steps.none { it.advance })
        assertTrue(a.playNow())
    }

    @Test
    fun `the countdown runs while the search is still going`() {
        val a = machine()
        val before = a.play(duration - 10_000, 0, prefetch = null)
        // Not started yet reads as searching: the card starts the search as it shows.
        assertEquals(Prefetch.Searching, before.single().card?.prefetch)
        val steps = a.play(duration - 10_000, 10_000, prefetch = Prefetch.Searching)
        assertTrue(steps.last().advance)
    }

    @Test
    fun `seeking back hides the card, and it counts down from the start when it shows again`() {
        val a = machine()
        a.play(duration - 10_000, 4_000)
        assertNull(a.update(at(duration - 60_000), ready).card)
        assertFalse(a.playNow())
        val again = a.play(duration - 10_000, 9_900)
        assertEquals(10, again.first().card?.secondsLeft)
        assertTrue(again.none { it.advance })
    }

    @Test
    fun `the release search starts at ninety percent, once`() {
        val a = machine()
        // Where LibraryRepository marks the episode watched.
        val threshold = ceil(duration * LibraryRepository.WATCHED_FRACTION).toLong()
        assertFalse(a.update(at(threshold - 1), null).startPrefetch)
        assertTrue(a.update(at(threshold), null).startPrefetch)
        assertFalse(a.update(at(threshold + 100), Prefetch.Searching).startPrefetch)
        assertTrue(a.play(duration - 20_000, 30_000, prefetch = Prefetch.Searching).none { it.startPrefetch })
    }

    @Test
    fun `the release search starts when the card shows, if that comes first`() {
        // In a one-minute file the last ten seconds start before ninety percent.
        val short = 60_000L
        val a = machine()
        assertFalse(a.update(at(49_999, durationMs = short), null).startPrefetch)
        val shown = a.update(at(50_000, durationMs = short), null)
        assertNotNull(shown.card)
        assertTrue(shown.startPrefetch)
        assertFalse(a.update(at(54_000, durationMs = short), Prefetch.Searching).startPrefetch)
    }

    @Test
    fun `in a long episode the release search starts five minutes before the end`() {
        // Ninety percent of an hour is six minutes before the end. The coordinator hands a result
        // on for ten minutes, so the search has to start late enough for it to last until the end.
        val hour = 60 * 60_000L
        val a = machine()
        assertFalse(a.update(at(54 * 60_000L, durationMs = hour), null).startPrefetch)
        assertFalse(a.update(at(55 * 60_000L - 1, durationMs = hour), null).startPrefetch)
        assertTrue(a.update(at(55 * 60_000L, durationMs = hour), null).startPrefetch)
    }

    @Test
    fun `a release no longer held when the card shows is searched for again, once`() {
        val a = machine()
        assertTrue(a.update(at(duration - 60_000), null).startPrefetch)
        assertTrue(a.play(duration - 60_000, 1_000).none { it.startPrefetch })
        // The user paused for a while: what was found is too old to be handed on, and the player
        // reports no search.
        val shown = a.update(at(duration - 10_000), null)
        assertTrue(shown.startPrefetch)
        assertEquals(Prefetch.Searching, shown.card?.prefetch)
        assertFalse(a.update(at(duration - 9_900), null).startPrefetch)
        assertTrue(a.play(duration - 9_800, 2_000, prefetch = Prefetch.Searching).none { it.startPrefetch })
    }

    @Test
    fun `a release still held when the card shows is not searched for again`() {
        val a = machine()
        assertTrue(a.update(at(duration - 60_000), null).startPrefetch)
        assertTrue(a.play(duration - 10_000, 9_000).none { it.startPrefetch })
    }

    @Test
    fun `a stopped card starts no search`() {
        val a = machine()
        a.stop()
        assertTrue(a.play(duration - 10_000, 12_000).none { it.startPrefetch || it.card != null || it.advance })
    }

    /** Credits known for the last three minutes. */
    private val credits = duration - 3 * 60_000L

    @Test
    fun `with known credits the card shows from their start, offering Stop, but counts down only in the last ten seconds`() {
        val a = machine()
        assertNull(a.update(at(credits - 1, creditsMs = credits), ready).card)
        val steps = a.play(credits, 3 * 60_000L - 10_100, creditsMs = credits)
        assertTrue(steps.all { it.card == AutoAdvance.Card(secondsLeft = null, progress = null, prefetch = ready, autoPlays = true) && !it.advance })
        assertEquals(AutoAdvance.Card(secondsLeft = 10, progress = 1f, prefetch = ready), a.update(at(duration - 10_000, creditsMs = credits), ready).card)
    }

    @Test
    fun `a card shown at the credits moves on when the file ends, not before`() {
        val a = machine()
        val steps = a.play(credits, 3 * 60_000L + 1_000, creditsMs = credits)
        // The countdown starts 170 s in, ten seconds before the end, and runs out as the file ends.
        assertEquals(listOf(1_800), steps.indices.filter { steps[it].advance })
        assertEquals(10, steps[1_700].card?.secondsLeft)
        assertNull(steps[1_699].card?.secondsLeft)
    }

    @Test
    fun `paused in the credits the card stays and nothing counts`() {
        val a = machine()
        a.play(credits, 5_000, creditsMs = credits)
        val held = a.play(credits + 5_000, 60_000, paused = true, creditsMs = credits)
        assertTrue(held.all { it.card?.autoPlays == true && it.card?.secondsLeft == null && !it.advance })
    }

    @Test
    fun `play now and stop work from the credits on`() {
        val playing = machine()
        playing.play(credits, 2_000, creditsMs = credits)
        assertTrue(playing.playNow())

        val stopped = machine()
        stopped.play(credits, 2_000, creditsMs = credits)
        stopped.stop()
        assertTrue(stopped.play(credits + 2_000, 3 * 60_000L, creditsMs = credits).all { it == AutoAdvance.Step(null) })
        assertFalse(stopped.playNow())
    }

    @Test
    fun `seeking back before the credits hides the card`() {
        val a = machine()
        a.play(credits, 2_000, creditsMs = credits)
        assertNull(a.update(at(credits - 60_000, creditsMs = credits), ready).card)
        assertFalse(a.playNow())
        assertNotNull(a.update(at(credits + 1_000, creditsMs = credits), ready).card)
    }

    @Test
    fun `without auto-play, or with nothing found, the credits card offers Close`() {
        val manual = machine(autoPlayNext = false)
        assertEquals(AutoAdvance.Card(null, null, ready, autoPlays = false), manual.update(at(credits, creditsMs = credits), ready).card)
        val nothing = machine()
        assertEquals(AutoAdvance.Card(null, null, Prefetch.NothingFound, autoPlays = false), nothing.update(at(credits, creditsMs = credits), Prefetch.NothingFound).card)
    }

    @Test
    fun `credits before ninety percent start the release search as the card shows`() {
        // Credits that start 15 minutes before the end of a 45-minute episode.
        val early = duration - 15 * 60_000L
        val a = machine()
        assertFalse(a.update(at(early - 100, creditsMs = early), null).startPrefetch)
        val shown = a.update(at(early, creditsMs = early), null)
        assertNotNull(shown.card)
        assertTrue(shown.startPrefetch)
        assertTrue(a.play(early + 100, 60_000, prefetch = Prefetch.Searching, creditsMs = early).none { it.startPrefetch })
    }

    @Test
    fun `credits after ninety percent leave the release search where it starts without them`() {
        val a = machine()
        val threshold = ceil(duration * LibraryRepository.WATCHED_FRACTION).toLong()
        assertFalse(a.update(at(threshold - 1, creditsMs = credits), null).startPrefetch)
        assertTrue(a.update(at(threshold, creditsMs = credits), null).startPrefetch)
        val shown = a.update(at(credits, creditsMs = credits), Prefetch.Searching)
        assertNotNull(shown.card)
        assertFalse(shown.startPrefetch)
    }

    @Test
    fun `a release that stops being held while the credits card shows is searched for again at once`() {
        val a = machine()
        // The search starts at ninety percent, before the credits, and finds a release.
        assertTrue(a.update(at(ceil(duration * LibraryRepository.WATCHED_FRACTION).toLong(), creditsMs = credits), null).startPrefetch)
        assertTrue(a.play(credits, 30_000, creditsMs = credits).none { it.startPrefetch })
        // Paused in the credits for over ten minutes: what was found is too old to be handed on,
        // and the player reports no search. The card doesn't claim one runs without starting it.
        val lapsed = a.play(credits + 30_000, 11 * 60_000L, paused = true, prefetch = null, creditsMs = credits)
        assertEquals(listOf(0), lapsed.indices.filter { lapsed[it].startPrefetch })
        assertEquals(Prefetch.Searching, lapsed.first().card?.prefetch)
        // Played on to the end with the search running: nothing more starts, also as the countdown begins.
        val rest = a.play(credits + 30_000, 3 * 60_000L, prefetch = Prefetch.Searching, creditsMs = credits)
        assertTrue(rest.none { it.startPrefetch })
        assertEquals(1, rest.count { it.advance })
    }

    @Test
    fun `a long pause with the card up searches once at most, and once more as playback goes on if what it found has lapsed`() {
        val a = machine()
        val search = Search()
        // The search starts at ninety percent and finds a release; the user pauses in the credits.
        val watched = ceil(duration * LibraryRepository.WATCHED_FRACTION).toLong()
        a.play(watched, credits + 30_000 - watched, search, creditsMs = credits)
        assertEquals(1, search.starts.size)
        // An hour paused, the screen kept on: each release found lapses ten minutes on, but the
        // indexers are asked once, not every ten minutes.
        val paused = a.play(credits + 30_000, 60 * 60_000L, search, paused = true, creditsMs = credits)
        assertEquals(1, paused.count { it.startPrefetch })
        // Meanwhile the card says the play searches again, not that it is looking.
        assertNull(paused.last().card?.prefetch)
        // Played on: what was found has lapsed, so it searches once more, and only once to the end.
        val resumed = a.play(credits + 30_000, 3 * 60_000L, search, creditsMs = credits)
        assertEquals(listOf(0), resumed.indices.filter { resumed[it].startPrefetch })
        assertEquals(Prefetch.Searching, resumed.first().card?.prefetch)
        assertEquals(ready, resumed.last { it.card != null }.card?.prefetch)
        assertEquals(1, resumed.count { it.advance })
    }

    @Test
    fun `a player stopped on an error with the card up searches once at most, and once more as playback goes on`() {
        val a = machine()
        val search = Search()
        val watched = ceil(duration * LibraryRepository.WATCHED_FRACTION).toLong()
        a.play(watched, credits + 30_000 - watched, search, creditsMs = credits)
        assertEquals(1, search.starts.size)
        // The stream fails in the credits and nobody is watching: not paused, but nothing moves on.
        // Each release found lapses ten minutes on; the indexers are asked once in the hour.
        val failed = a.play(credits + 30_000, 60 * 60_000L, search, creditsMs = credits, stalled = true)
        assertEquals(1, failed.count { it.startPrefetch })
        assertNull(failed.last().card?.prefetch)
        // Played on: what was found has lapsed, so it searches once more.
        val resumed = a.play(credits + 30_000, 3 * 60_000L, search, creditsMs = credits)
        assertEquals(listOf(0), resumed.indices.filter { resumed[it].startPrefetch })
        assertEquals(1, resumed.count { it.advance })
    }

    @Test
    fun `a pause that ends while what was found is still held searches nothing more`() {
        val a = machine()
        val search = Search()
        val watched = ceil(duration * LibraryRepository.WATCHED_FRACTION).toLong()
        a.play(watched, credits + 30_000 - watched, search, creditsMs = credits)
        // Paused for four minutes, then played to the end: held all the while.
        assertTrue(a.play(credits + 30_000, 4 * 60_000L, search, paused = true, creditsMs = credits).none { it.startPrefetch })
        val rest = a.play(credits + 30_000, 3 * 60_000L, search, creditsMs = credits)
        assertTrue(rest.none { it.startPrefetch })
        assertEquals(1, search.starts.size)
    }

    @Test
    fun `at the end with no countdown, a long wait searches once at most`() {
        // Auto-play is off: the card waits at the end for Play now, the screen kept on.
        val a = machine(autoPlayNext = false)
        val search = Search()
        a.play(duration - 60_000, 60_000, search)
        assertEquals(1, search.starts.size)
        val waiting = a.play(duration, 60 * 60_000L, search)
        assertEquals(1, waiting.count { it.startPrefetch })
        assertNull(waiting.last().card?.prefetch)
        assertTrue(a.playNow())
    }

    @Test
    fun `a search whose result is never held is not run again and again while the credits card shows`() {
        // An indexer failed: the coordinator keeps nothing to hand on, so the player reports no
        // search as soon as one ends. Searching again each time would hammer the indexers.
        val a = machine()
        assertTrue(a.update(at(credits, creditsMs = credits), null).startPrefetch)
        repeat(3) { i ->
            val from = credits + i * 20_000L
            assertTrue(a.play(from, 5_000, prefetch = Prefetch.Searching, creditsMs = credits).none { it.startPrefetch })
            // Nothing searches meanwhile, and the card doesn't say anything does: the play searches again.
            assertTrue(a.play(from + 5_000, 15_000, prefetch = null, creditsMs = credits).all { !it.startPrefetch && it.card != null && it.card?.prefetch == null })
        }
    }

    @Test
    fun `credits starting in the last ten seconds, or outside the file, change nothing`() {
        val late = machine()
        assertNull(late.update(at(duration - 10_001, creditsMs = duration - 5_000), ready).card)
        assertEquals(10, late.update(at(duration - 10_000, creditsMs = duration - 5_000), ready).card?.secondsLeft)
        val outside = machine()
        assertNull(outside.update(at(duration - 60_000, creditsMs = duration + 1_000), ready).card)
        assertNull(outside.update(at(20 * 60_000L, durationMs = C.TIME_UNSET, creditsMs = 10 * 60_000L), ready).card)
    }

    @Test
    fun `only TMDB episodes lead on to a next one`() {
        fun launch(watch: WatchTarget) = PlayerLaunch(
            uri = "usenet://x/0", title = "Harbor Lights", subtitle = null, watchKey = watch.key("x", 0), watch = watch,
            release = SavedRelease("fake", "guid", "Harbor.Lights.S01E01", null, 0, "x"),
        )
        val episode = WatchTarget(WatchKind.Episode, "Harbor Lights S01E01", showTmdbId = 900002, season = 1, episode = 1)
        assertEquals(AutoAdvance.Episode(900002, 1, 1), AutoAdvance.episodeOf(launch(episode)))
        // An episode picked without TMDB numbers is recorded against the release.
        assertNull(AutoAdvance.episodeOf(launch(episode.copy(season = null))))
        assertNull(AutoAdvance.episodeOf(launch(WatchTarget(WatchKind.Movie, "Copper Crown", movieTmdbId = 12))))
        assertNull(AutoAdvance.episodeOf(launch(WatchTarget(WatchKind.Release, "Harbor.Lights.S01E01"))))
        // The bare-URI path (an opened NZB file) has no launch at all.
        assertNull(AutoAdvance.episodeOf(null))
    }
}
