package io.skjaere.usenet.app.collection

import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.metadata.TmdbError
import io.skjaere.usenet.metadata.TmdbException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DetailsLoaderTest {
    private val monday = show(1, last = EpisodeSummary(2, 4, null, LocalDate.of(2026, 10, 8)), next = EpisodeSummary(2, 5, null, LocalDate.of(2026, 10, 15)))
    private val wednesday = show(1, last = EpisodeSummary(2, 5, null, LocalDate.of(2026, 10, 15)), next = EpisodeSummary(2, 6, null, LocalDate.of(2026, 10, 22)))

    private fun show(id: Int, last: EpisodeSummary? = null, next: EpisodeSummary? = null) =
        ShowDetails(id, "Show $id", null, 2024, null, null, null, "Returning Series", true, 45, emptyList(), last, next, ExternalIds())

    /** Wall time that moves with the test's virtual time. */
    private class SchedulerClock(private val scheduler: TestCoroutineScheduler) : Clock() {
        override fun instant(): Instant = Instant.parse("2026-10-13T08:00:00Z").plusMillis(scheduler.currentTime)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    /** [ids] that call [onPassEnd] each time a pass over them has handed out every id. */
    private class HookedIds(private val ids: List<Int>, private val onPassEnd: () -> Unit) : AbstractList<Int>() {
        override val size get() = ids.size
        override fun get(index: Int) = ids[index]
        override fun iterator(): Iterator<Int> = object : Iterator<Int> {
            private val inner = ids.iterator()
            override fun hasNext() = inner.hasNext().also { if (!it) onPassEnd() }
            override fun next() = inner.next()
        }
    }

    private fun TestScope.loader(fetch: suspend (Int) -> ShowDetails) =
        DetailsLoader<Int, ShowDetails>(fetch, SchedulerClock(testScheduler), dispatcher = StandardTestDispatcher(testScheduler))

    /** Collects [ids] until the returned job is cancelled; everything emitted lands in [into]. */
    private fun TestScope.subscribe(loader: DetailsLoader<Int, ShowDetails>, ids: List<Int>, into: MutableList<Map<Int, ShowDetails?>>) =
        backgroundScope.launch { loader.load(ids).collect { into += it } }

    @Test
    fun `details an hour old are fetched again on the next load instead of kept for good`() = runTest {
        var answer = monday
        var calls = 0
        val loader = loader { calls++; answer }
        val emitted = mutableListOf<Map<Int, ShowDetails?>>()
        subscribe(loader, listOf(1), emitted).also { runCurrent() }.cancel()
        assertEquals(monday, emitted.last()[1])

        answer = wednesday
        advanceTimeBy(Duration.ofHours(2).toMillis())
        subscribe(loader, listOf(1), emitted)
        runCurrent()
        assertEquals(wednesday, emitted.last()[1])
        assertEquals(2, calls)
    }

    @Test
    fun `details younger than an hour are not fetched again`() = runTest {
        var calls = 0
        val loader = loader { calls++; monday }
        val emitted = mutableListOf<Map<Int, ShowDetails?>>()
        subscribe(loader, listOf(1), emitted).also { runCurrent() }.cancel()
        advanceTimeBy(Duration.ofMinutes(30).toMillis())
        subscribe(loader, listOf(1), emitted)
        runCurrent()
        assertEquals(monday, emitted.last()[1])
        assertEquals(1, calls)
    }

    @Test
    fun `a collection left open fetches its details again every hour`() = runTest {
        var answer = monday
        val loader = loader { answer }
        val emitted = mutableListOf<Map<Int, ShowDetails?>>()
        subscribe(loader, listOf(1), emitted)
        runCurrent()
        assertEquals(monday, emitted.last()[1])

        answer = wednesday
        advanceTimeBy(Duration.ofMinutes(61).toMillis())
        runCurrent()
        assertEquals(wednesday, emitted.last()[1])
    }

    @Test
    fun `a failed refetch keeps the details it had`() = runTest {
        var fail = false
        val loader = loader { if (fail) throw TmdbException(TmdbError.RateLimited()) else monday }
        val emitted = mutableListOf<Map<Int, ShowDetails?>>()
        subscribe(loader, listOf(1), emitted).also { runCurrent() }.cancel()

        fail = true
        advanceTimeBy(Duration.ofHours(2).toMillis())
        subscribe(loader, listOf(1), emitted)
        runCurrent()
        assertEquals(monday, emitted.last()[1])
    }

    @Test
    fun `a show that couldn't be loaded is tried again on the next load`() = runTest {
        var calls = 0
        val loader = loader { if (calls++ == 0) throw TmdbException(TmdbError.RateLimited()) else monday }
        val emitted = mutableListOf<Map<Int, ShowDetails?>>()
        subscribe(loader, listOf(1), emitted).also { runCurrent() }.cancel()
        assertEquals(mapOf(1 to null), emitted.last())

        subscribe(loader, listOf(1), emitted)
        runCurrent()
        assertEquals(monday, emitted.last()[1])
    }

    @Test
    fun `details are fetched four at a time`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var inFlight = 0
        var most = 0
        val loader = loader { id ->
            inFlight++
            most = maxOf(most, inFlight)
            gate.await()
            inFlight--
            show(id)
        }
        val emitted = mutableListOf<Map<Int, ShowDetails?>>()
        subscribe(loader, (1..10).toList(), emitted)
        runCurrent()
        assertEquals(4, inFlight)

        gate.complete(Unit)
        runCurrent()
        assertEquals((1..10).associateWith { show(it) }, emitted.last())
        assertEquals(4, most)
    }

    @Test
    fun `the collection comes out before any details and fills in as they arrive`() = runTest {
        val answers = (1..3).associateWith { CompletableDeferred<ShowDetails>() }
        val loader = loader { id -> answers.getValue(id).await() }
        val emitted = mutableListOf<Map<Int, ShowDetails?>>()
        subscribe(loader, listOf(1, 2, 3), emitted)
        runCurrent()
        assertEquals(listOf(emptyMap()), emitted)

        answers.getValue(2).complete(show(2))
        runCurrent()
        assertEquals(mapOf(2 to show(2)), emitted.last())

        answers.getValue(1).completeExceptionally(TmdbException(TmdbError.NotFound("Not found")))
        runCurrent()
        assertEquals(mapOf(2 to show(2), 1 to null), emitted.last())
    }

    @Test
    fun `answers stored at the same time on two threads end the round with both shown`() = runBlocking<Unit> {
        // Show 1's answer reads what is loaded (2 is missing) and, before it sends that, show 2's
        // answer is stored and sent: if 1's older read goes out last, show 2 stays "loading" for
        // an hour although its details are in.
        val answer2 = CompletableDeferred<Unit>()
        val answered1 = AtomicBoolean(false)
        val hooked = AtomicBoolean(false)
        val sent2 = CountDownLatch(1)
        val ids = HookedIds(listOf(1, 2)) {
            if (answered1.get() && hooked.compareAndSet(false, true)) {
                answer2.complete(Unit)
                sent2.await(2, TimeUnit.SECONDS)
            }
        }
        Executors.newFixedThreadPool(4).asCoroutineDispatcher().use { pool ->
            val loader = DetailsLoader<Int, ShowDetails>({ id ->
                if (id == 2) answer2.await() else answered1.set(true)
                show(id)
            }, dispatcher = pool)
            val emitted = withTimeout(10_000) {
                loader.load(ids).onEach { if (2 in it) sent2.countDown() }.take(3).toList()
            }
            assertEquals(mapOf(1 to show(1), 2 to show(2)), emitted.last())
        }
    }
}
