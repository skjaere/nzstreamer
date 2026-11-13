package io.skjaere.usenet.app.refresh

import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.app.collection.model.toSummary
import io.skjaere.usenet.data.SummaryRefresh
import io.skjaere.usenet.metadata.Aged
import io.skjaere.usenet.metadata.TmdbError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SummaryRefresherTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val start = SchedulerClock.START.toEpochMilli()

    private fun TestScope.store(shows: Iterable<Int> = emptyList(), movies: Iterable<Int> = emptyList()) =
        FakeSummaryStore(shows.toList(), movies.toList()) { testScheduler.currentTime }.also { s -> (shows.map(TitleKey::show) + movies.map(TitleKey::movie)).forEach { s.added[it] = it.tmdbId.toLong() } }

    private fun TestScope.refresher(store: FakeSummaryStore, source: FakeTitleSource, workers: Int = 4) = SummaryRefresher(
        store, source, SchedulerClock(testScheduler), backgroundScope, RateGate(50) { testScheduler.currentTime },
        workers = workers, dispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun now(scope: TestScope) = start + scope.testScheduler.currentTime

    @Test
    fun `at most four titles are asked for at once, each under the rate gate`() = runTest {
        val source = FakeTitleSource(testScheduler, latencyMs = 1_000)
        val store = store(shows = 1..20)
        refresher(store, source).start(flowOf(true))
        advanceTimeBy(5_500)
        assertEquals(20, source.calls.size)
        assertEquals(4, source.maxInFlight)
        assertTrue(source.gated.all { it })
    }

    @Test
    fun `requests to TMDB are spaced, answers from its cache are not`() = runTest {
        val source = FakeTitleSource(testScheduler)
        source.network += (1..8).map(TitleKey::show)
        refresher(store(shows = 1..8, movies = 101..140), source).start(flowOf(true))
        advanceTimeBy(1_000)
        assertEquals(48, source.calls.size)
        // The eight from TMDB went 50 ms apart; the forty from its cache weren't held at the gate.
        assertEquals(listOf(0L, 50, 100, 150, 200, 250, 300, 350), source.passes)
        assertEquals(40, source.callTimes.count { (key, at) -> !key.isShow && at == 0L })
    }

    @Test
    fun `a slow fill is written about once a second, never more than 25 titles at a time`() = runTest {
        val source = FakeTitleSource(testScheduler, latencyMs = 300)
        val store = store(shows = 1..40)
        refresher(store, source).start(flowOf(true))
        advanceTimeBy(4_000)
        assertEquals(40, store.flushes.sumOf { it.titles })
        assertTrue(store.flushes.all { it.titles <= 25 })
        assertTrue(store.flushes.zipWithNext().all { (a, b) -> b.at - a.at >= 1_000 }, "${store.flushes}")
    }

    @Test
    fun `a fast fill is written 25 titles at a time`() = runTest {
        val store = store(shows = 1..100)
        refresher(store, FakeTitleSource(testScheduler)).start(flowOf(true))
        advanceTimeBy(100)
        assertEquals(listOf(25, 25, 25, 25), store.flushes.map { it.titles })
    }

    @Test
    fun `an answer is stored with when TMDB gave it and when it is due again`() = runTest {
        val source = FakeTitleSource(testScheduler)
        source.answers[TitleKey.show(1)] = Aged(fakeShow(1, status = "Ended"), Duration.ofHours(3))
        val store = store(shows = listOf(1), movies = listOf(2))
        refresher(store, source).start(flowOf(true))
        advanceTimeBy(1_100)
        val at = start
        assertEquals(SummaryRefresh(SummaryRefresh.SHOW, 1, fetchedAt = at - 3 * hour, staleAt = at - 3 * hour + 7 * day), store.refresh[TitleKey.show(1)])
        assertEquals(SummaryRefresh(SummaryRefresh.MOVIE, 2, fetchedAt = at - hour, staleAt = at - hour + 7 * day), store.refresh[TitleKey.movie(2)])
        assertEquals(fakeShow(1, status = "Ended").toSummary(), store.showFacts[1])
        assertEquals(fakeMovie(2).toSummary(), store.movieFacts[2])
        // Not asked again while fresh; asked again once due, the bookkeeping written again.
        advanceTimeBy(6 * day)
        assertEquals(2, source.calls.size)
        advanceTimeBy(day)
        assertEquals(4, source.calls.size)
        assertEquals(at - 2 * hour + 7 * day, store.refresh.getValue(TitleKey.movie(2)).fetchedAt)
    }

    @Test
    fun `an answer TMDB couldn't be asked for is kept, retried later, and pauses the rest`() = runTest {
        val source = FakeTitleSource(testScheduler)
        source.answers[TitleKey.show(1)] = Aged(fakeShow(1), Duration.ofDays(3), offline = true)
        val store = store(shows = listOf(1, 2, 3))
        store.added[TitleKey.show(1)] = 100
        val refresher = refresher(store, source, workers = 1)
        refresher.start(flowOf(true))
        advanceTimeBy(1_100)
        val row = store.refresh.getValue(TitleKey.show(1))
        assertEquals(start - 3 * day, row.fetchedAt)
        assertEquals(start + 30_000, row.retryAt)
        assertEquals(fakeShow(1).toSummary(), store.showFacts[1])
        assertEquals(RefreshStatus.Paused(RefreshStatus.Reason.Offline, start + 30_000), refresher.status.value)
        advanceTimeBy(27_900)
        assertEquals(listOf(TitleKey.show(1)), source.calls)
        // TMDB is back.
        source.answers.remove(TitleKey.show(1))
        advanceTimeBy(2_100)
        // The two never asked come first, then the one answered offline.
        assertEquals(listOf(1, 3, 2, 1), source.calls.map { it.tmdbId })
        assertEquals(0L, store.refresh.getValue(TitleKey.show(1)).retryAt)
        advanceTimeBy(1_100)
        assertEquals(RefreshStatus.Idle, refresher.status.value)
    }

    @Test
    fun `a cached answer older than its policy counts as offline`() = runTest {
        val source = FakeTitleSource(testScheduler)
        source.answers[TitleKey.show(1)] = Aged(fakeShow(1), Duration.ofDays(2))
        val store = store(shows = listOf(1))
        val refresher = refresher(store, source)
        refresher.start(flowOf(true))
        advanceTimeBy(1_100)
        assertEquals(start + 30_000, store.refresh.getValue(TitleKey.show(1)).retryAt)
        assertEquals(RefreshStatus.Reason.Offline, (refresher.status.value as RefreshStatus.Paused).reason)
    }

    @Test
    fun `an answer straight from TMDB ends a pause for no network at once, one from its cache doesn't`() = runTest {
        val source = FakeTitleSource(testScheduler)
        source.errors[TitleKey.show(1)] = TmdbError.Network("down")
        val store = store(shows = listOf(1))
        val refresher = refresher(store, source, workers = 2)
        refresher.start(flowOf(true))
        runCurrent()
        assertEquals(RefreshStatus.Paused(RefreshStatus.Reason.Offline, start + 30_000), refresher.status.value)
        source.errors.clear()
        // From TMDB's cache: it says nothing of the network.
        refresher.refreshNow(listOf(TitleKey.show(8)))
        assertEquals(RefreshStatus.Paused(RefreshStatus.Reason.Offline, start + 30_000), refresher.status.value)
        // From TMDB itself (Search's "+ Collection", a page): reachable again, the rest goes on now.
        source.network += TitleKey.show(9)
        refresher.refreshNow(listOf(TitleKey.show(9)))
        runCurrent()
        assertEquals(2, source.calls.count { it == TitleKey.show(1) })
        advanceTimeBy(1_100)
        assertTrue(TitleKey.show(1) in store.refresh)
        assertEquals(RefreshStatus.Idle, refresher.status.value)
        assertTrue(testScheduler.currentTime < 30_000)
    }

    @Test
    fun `coming back to the front asks again at once, not after the pause`() = runTest {
        val source = FakeTitleSource(testScheduler)
        source.errors[TitleKey.show(1)] = TmdbError.Network("down")
        val store = store(shows = listOf(1))
        val active = MutableStateFlow(true)
        val refresher = refresher(store, source, workers = 1)
        refresher.start(active)
        runCurrent()
        advanceTimeBy(30_100)
        // Asked twice, the second pause twice as long.
        assertEquals(2, source.calls.size)
        assertEquals(RefreshStatus.Paused(RefreshStatus.Reason.Offline, start + 90_000), refresher.status.value)
        active.value = false
        runCurrent()
        source.errors.clear()
        active.value = true
        runCurrent()
        assertEquals(3, source.calls.size)
        advanceTimeBy(1_100)
        assertTrue(TitleKey.show(1) in store.refresh)
        assertEquals(RefreshStatus.Idle, refresher.status.value)
    }

    @Test
    fun `a page's answer older than its policy is due again soon and holds nothing back`() = runTest {
        val source = FakeTitleSource(testScheduler)
        val store = store(shows = listOf(1))
        val refresher = refresher(store, source)
        refresher.start(flowOf(true))
        advanceTimeBy(1_100)
        assertEquals(1, source.calls.size)
        // The page has been open for two days on a returning show (a day's policy): TMDB isn't unreachable.
        refresher.offer(fakeShow(1), Duration.ofDays(2))
        runCurrent()
        assertEquals(RefreshStatus.Idle, refresher.status.value)
        // A title added meanwhile is asked for at once.
        store.added[TitleKey.show(2)] = 2
        store.collectedShows.value = setOf(1, 2)
        runCurrent()
        assertEquals(listOf(1, 2), source.calls.map { it.tmdbId })
        advanceTimeBy(1_100)
        val at = start + 1_100
        assertEquals(SummaryRefresh(SummaryRefresh.SHOW, 1, fetchedAt = at - 2 * day, staleAt = at + 5 * 60_000), store.refresh[TitleKey.show(1)])
    }

    @Test
    fun `no network or a rate limit pauses everything without charging the title`() = runTest {
        val source = FakeTitleSource(testScheduler)
        source.errors[TitleKey.show(1)] = TmdbError.Network("down")
        val store = store(shows = listOf(1, 2))
        store.added[TitleKey.show(1)] = 100
        val refresher = refresher(store, source, workers = 1)
        refresher.start(flowOf(true))
        runCurrent()
        assertEquals(listOf(TitleKey.show(1)), source.calls)
        assertEquals(null, store.refresh[TitleKey.show(1)])
        assertEquals(RefreshStatus.Paused(RefreshStatus.Reason.Offline, start + 30_000), refresher.status.value)
        advanceTimeBy(29_900)
        assertEquals(1, source.calls.size)
        // Still down: the pause doubles.
        advanceTimeBy(200)
        assertEquals(listOf(TitleKey.show(1), TitleKey.show(1)), source.calls)
        assertEquals(RefreshStatus.Paused(RefreshStatus.Reason.Offline, now(this) - 100 + 60_000), refresher.status.value)
        source.errors[TitleKey.show(1)] = TmdbError.RateLimited()
        advanceTimeBy(60_000)
        assertEquals(3, source.calls.size)
        assertEquals(RefreshStatus.Reason.RateLimited, (refresher.status.value as RefreshStatus.Paused).reason)
        source.errors.clear()
        advanceTimeBy(60_000)
        assertEquals(setOf(TitleKey.show(1), TitleKey.show(2)), source.calls.drop(3).toSet())
        assertEquals(0, store.refresh.getValue(TitleKey.show(1)).failures)
    }

    @Test
    fun `a title TMDB doesn't know is asked again in a week, a failing one backs off`() = runTest {
        val source = FakeTitleSource(testScheduler)
        source.errors[TitleKey.show(1)] = TmdbError.NotFound("gone")
        source.errors[TitleKey.show(2)] = TmdbError.Server("HTTP 500")
        val store = store(shows = listOf(1, 2))
        refresher(store, source).start(flowOf(true))
        advanceTimeBy(1_100)
        // Show 2 (added later) went through the gate first, show 1 50 ms after.
        assertEquals(SummaryRefresh(SummaryRefresh.SHOW, 1, retryAt = start + 50 + 7 * day, failures = 1, error = "NotFound"), store.refresh[TitleKey.show(1)])
        assertEquals(SummaryRefresh(SummaryRefresh.SHOW, 2, retryAt = start + 15 * 60_000, failures = 1, error = "Server"), store.refresh[TitleKey.show(2)])
        advanceTimeBy(15 * 60_000)
        assertEquals(SummaryRefresh(SummaryRefresh.SHOW, 2, retryAt = start + 15 * 60_000 + hour, failures = 2, error = "Server"), store.refresh[TitleKey.show(2)])
        assertEquals(1, source.calls.count { it == TitleKey.show(1) })
    }

    @Test
    fun `a title whose request TMDB refuses backs off as a failing one does`() = runTest {
        val source = FakeTitleSource(testScheduler)
        source.errors[TitleKey.show(1)] = TmdbError.BadRequest("Invalid parameters")
        val store = store(shows = listOf(1))
        refresher(store, source).start(flowOf(true))
        advanceTimeBy(1_100)
        assertEquals(SummaryRefresh(SummaryRefresh.SHOW, 1, retryAt = start + 15 * 60_000, failures = 1, error = "BadRequest"), store.refresh[TitleKey.show(1)])
        advanceTimeBy(15 * 60_000)
        assertEquals(SummaryRefresh(SummaryRefresh.SHOW, 1, retryAt = start + 15 * 60_000 + hour, failures = 2, error = "BadRequest"), store.refresh[TitleKey.show(1)])
    }

    @Test
    fun `answers that couldn't be stored are asked for again a minute later, not left as answered`() = runTest {
        val source = FakeTitleSource(testScheduler)
        val store = store(shows = 1..3)
        store.full = true
        val refresher = refresher(store, source)
        refresher.start(flowOf(true))
        advanceTimeBy(1_100)
        assertEquals(3, source.calls.size)
        assertEquals(emptyMap(), store.refresh)
        // Not asked again at once (a disk still full would be asked to take them in a loop).
        advanceTimeBy(50_000)
        assertEquals(3, source.calls.size)
        // Room again: asked again, and stored.
        store.full = false
        advanceTimeBy(10_000)
        assertEquals(6, source.calls.size)
        advanceTimeBy(1_100)
        assertEquals(setOf(1, 2, 3), store.refresh.keys.map { it.tmdbId }.toSet())
        assertEquals(setOf(1, 2, 3), store.showFacts.keys)
    }

    @Test
    fun `going inactive cancels what is in flight and writes what was answered`() = runTest {
        val source = FakeTitleSource(testScheduler, latencyMs = 300)
        val store = store(shows = 1..8)
        val active = MutableStateFlow(true)
        val refresher = refresher(store, source)
        refresher.start(active)
        advanceTimeBy(400)
        assertEquals(8, source.calls.size)
        assertEquals(emptyList(), store.flushes)
        active.value = false
        runCurrent()
        assertEquals(4, source.cancelled)
        assertEquals(listOf(4), store.flushes.map { it.titles })
        advanceTimeBy(10_000)
        assertEquals(8, source.calls.size)
        assertEquals(RefreshStatus.Idle, refresher.status.value)
        // Active again: the four cancelled are asked for again.
        active.value = true
        advanceTimeBy(2_000)
        assertEquals(12, source.calls.size)
        assertEquals(8, store.refresh.size)
    }

    @Test
    fun `leaving the front as freed workers are handed titles loses neither the workers nor the titles`() = runTest {
        val source = FakeTitleSource(testScheduler, latencyMs = 300)
        val store = store(shows = 1..8)
        val active = MutableStateFlow(true)
        val refresher = refresher(store, source)
        refresher.start(active)
        runCurrent()
        assertEquals(4, source.calls.size)
        // Queued behind the first four answers: the loop hands their workers the other four, and the
        // app leaves the front before those start.
        launch {
            delay(300)
            active.value = false
        }
        advanceTimeBy(300)
        runCurrent()
        assertEquals(4, source.calls.size)
        assertEquals(4, store.refresh.size)
        // Active again: the other four are asked for at once, by all four workers.
        active.value = true
        advanceTimeBy(2_000)
        assertEquals(List(4) { 300L }, source.callTimes.drop(4).map { it.second })
        assertEquals(8, store.refresh.size)
    }

    @Test
    fun `a title asked for and given up while it waits for a worker is left to the loop`() = runTest {
        val source = FakeTitleSource(testScheduler, latencyMs = 1_000)
        val store = store(shows = listOf(1, 2))
        val refresher = refresher(store, source, workers = 1)
        refresher.start(flowOf(true))
        // The loop asks about show 2 (added later) with the only worker.
        runCurrent()
        val asked = launch { refresher.refreshNow(listOf(TitleKey.show(1))) }
        runCurrent()
        asked.cancel()
        advanceTimeBy(4_000)
        assertEquals(listOf(2, 1), source.calls.map { it.tmdbId })
        assertEquals(setOf(TitleKey.show(1), TitleKey.show(2)), store.refresh.keys)
    }

    @Test
    fun `refreshNow shares the fetch the loop is making`() = runTest {
        val source = FakeTitleSource(testScheduler, latencyMs = 1_000)
        val store = store(shows = listOf(1))
        val refresher = refresher(store, source)
        refresher.start(flowOf(true))
        runCurrent()
        var doneAt = -1L
        launch {
            refresher.refreshNow(listOf(TitleKey.show(1)))
            doneAt = testScheduler.currentTime
        }
        advanceTimeBy(3_000)
        assertEquals(1, source.calls.size)
        assertEquals(1_000, doneAt)
        assertTrue(TitleKey.show(1) in store.refresh)
    }

    @Test
    fun `refreshNow works while inactive and writes at once`() = runTest {
        val source = FakeTitleSource(testScheduler, latencyMs = 200)
        val store = store(shows = listOf(1))
        val refresher = refresher(store, source)
        refresher.start(flowOf(false))
        refresher.refreshNow(listOf(TitleKey.show(1), TitleKey.movie(9)))
        assertEquals(200, testScheduler.currentTime)
        assertEquals(setOf(TitleKey.show(1), TitleKey.movie(9)), store.refresh.keys)
        assertTrue(source.gated.all { it })
        // Without a key it asks, learns there is none, and returns.
        source.errors[TitleKey.show(2)] = TmdbError.NoKey()
        refresher.refreshNow(listOf(TitleKey.show(2)))
        assertEquals(null, store.refresh[TitleKey.show(2)])
    }

    @Test
    fun `what the Show page fetched is stored for collected titles without asking again`() = runTest {
        val source = FakeTitleSource(testScheduler)
        val store = store(shows = listOf(1))
        val refresher = refresher(store, source)
        refresher.offer(fakeShow(1, status = "Ended"), Duration.ZERO)
        refresher.offer(fakeShow(2), Duration.ZERO)
        refresher.offer(fakeMovie(3), Duration.ZERO)
        advanceTimeBy(1_100)
        assertEquals(emptyList(), source.calls)
        assertEquals(setOf(TitleKey.show(1)), store.refresh.keys)
        assertEquals(start + 7 * day, store.refresh.getValue(TitleKey.show(1)).staleAt)
        assertEquals(setOf(1), store.showFacts.keys)
    }

    @Test
    fun `invalidating makes every title due at once`() = runTest {
        val source = FakeTitleSource(testScheduler)
        val store = store(shows = 1..3)
        val refresher = refresher(store, source)
        refresher.start(flowOf(true))
        advanceTimeBy(2_000)
        assertEquals(3, source.calls.size)
        refresher.invalidateAll()
        advanceTimeBy(100)
        assertEquals(1, store.invalidations)
        assertEquals(6, source.calls.size)
    }

    /**
     * The TMDB language changed while titles were answered and asked about: what was asked for in the
     * old one (queued to be written, or still on its way) is due again once written, not taken as fresh.
     */
    @Test
    fun `answers asked for in the old language are asked for again, those queued and those in flight`() = runTest {
        val source = FakeTitleSource(testScheduler, latencyMs = 300)
        val store = store(shows = 1..6)
        val refresher = refresher(store, source)
        refresher.start(flowOf(true))
        // Four answered and queued (written at 1.3 s), two on their way.
        advanceTimeBy(400)
        assertEquals(6, source.calls.size)
        assertEquals(emptyList(), store.flushes)
        val changed = testScheduler.currentTime
        refresher.invalidateAll()
        advanceTimeBy(10_000)
        // Each asked for once again, in the new language, and stored fresh from that.
        assertEquals((1..6).map(TitleKey::show).toSet(), source.callTimes.filter { it.second >= changed }.map { it.first }.toSet())
        assertEquals(12, source.calls.size)
        assertTrue(store.refresh.values.all { it.staleAt > now(this) }, "${store.refresh}")
    }

    /**
     * One that changes while answers are being written: they are written first, then made due with the
     * rest, so the one worker gets to each of them (not only to those it picks before the write lands).
     */
    @Test
    fun `answers being written as the language changes are asked for again too`() = runTest {
        val source = FakeTitleSource(testScheduler, latencyMs = 300)
        val store = store(shows = 1..3)
        store.hold = CompletableDeferred()
        val refresher = refresher(store, source, workers = 1)
        refresher.start(flowOf(true))
        // Answered by 900 ms; their write at 1.3 s is held.
        advanceTimeBy(1_400)
        assertEquals(3, source.calls.size)
        val invalidated = launch { refresher.invalidateAll() }
        runCurrent()
        store.hold!!.complete(Unit)
        advanceTimeBy(5_000)
        assertTrue(invalidated.isCompleted)
        assertEquals(6, source.calls.size)
    }

    @Test
    fun `nothing is asked without a key, and a key TMDB rejects stops the loop`() = runTest {
        val source = FakeTitleSource(testScheduler)
        val store = store(shows = 1..3)
        refresher(store, source).start(flowOf(false))
        advanceTimeBy(10_000)
        assertEquals(emptyList(), source.calls)
        val rejected = refresher(store, source, workers = 1)
        (1..3).forEach { source.errors[TitleKey.show(it)] = TmdbError.InvalidKey("Invalid API key") }
        rejected.start(flowOf(true))
        advanceTimeBy(day)
        assertEquals(1, source.calls.size)
        assertEquals(RefreshStatus.Paused(RefreshStatus.Reason.InvalidKey, null), rejected.status.value)
    }

    @Test
    fun `recently watched first, then the rows on screen, and old bookkeeping is pruned at start`() = runTest {
        val source = FakeTitleSource(testScheduler, latencyMs = 100)
        val store = store(shows = 1..6)
        store.lastWatched[TitleKey.show(5)] = start - day
        store.lastWatched[TitleKey.show(6)] = start - 40 * day
        val refresher = refresher(store, source, workers = 1)
        refresher.prioritize(listOf(TitleKey.show(3), TitleKey.show(2)))
        refresher.start(flowOf(true))
        advanceTimeBy(1_000)
        assertEquals(listOf(5, 3, 2, 6, 4, 1), source.calls.map { it.tmdbId })
        assertEquals(listOf(start - 30 * day), store.prunes)
    }

    @Test
    fun `a title removed while it is fetched still gets its facts, and isn't asked for again`() = runTest {
        val source = FakeTitleSource(testScheduler, latencyMs = 500)
        val store = store(shows = listOf(1))
        refresher(store, source).start(flowOf(true))
        runCurrent()
        store.collectedShows.value = emptySet()
        advanceTimeBy(9 * day)
        assertEquals(1, source.calls.size)
        assertTrue(1 in store.showFacts)
    }
}
