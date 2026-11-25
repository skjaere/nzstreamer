package app.nzstreamer.plugin.sportarr.source

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * L641's "When" and "Threads", in the test's time: the list is read as the tab first shows with no list or one read more
 * than 15 minutes ago, on Refresh and Try again, and after a Save that changed the address or the key; never on a timer
 * or while nothing asks; one read at a time, a second ask joining it; a failed or refused read keeps the old list whole;
 * and the decode runs off the dispatcher that asked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshTest {
    @TempDir
    lateinit var dir: File

    private val sportarr = TestSportarr(
        listOf(league(1, "Premier League"), league(2, "Formula 1", "Motorsport")),
        mapOf(
            1 to listOf(event(1, START.minus(Duration.ofDays(2)), 1), event(2, START.plus(Duration.ofDays(3)), 1)),
            2 to listOf(event(3, START.minus(Duration.ofDays(1)), 2, title = "Grand Prix of Kestrel - Race")),
        ),
    )

    /** Requests one full read makes: the leagues and one page each. */
    private val oneRead = 3

    private fun TestScope.refresh(
        secrets: MapSecrets = MapSecrets(mapOf("url" to "http://127.0.0.1:1867", "key" to KEY)),
        kept: KeptList = KeptList(dir, EmptyCoroutineContext),
    ): Refresh {
        val clock = { START.plusMillis(testScheduler.currentTime) }
        return Refresh(backgroundScope, Connection(secrets), kept, clock) { saved ->
            ListReader(SportarrClient(sportarr.http, saved.address, saved.key, ::loopbackSafety, EmptyCoroutineContext), clock(), ZoneOffset.UTC, work = EmptyCoroutineContext)
                .read()
        }
    }

    @Test
    fun `nothing is read while nothing asks, however long`() = runTest {
        refresh()
        advanceTimeBy(48.hours)
        runCurrent()
        assertEquals(0, sportarr.http.requests.size)
    }

    @Test
    fun `the first show reads, and a later one only once the list is over 15 minutes old`() = runTest {
        val refresh = refresh()
        assertEquals(ListState.Loading, refresh.state.value)

        refresh.shown().join()
        assertEquals(oneRead, sportarr.http.requests.size)
        val first = assertIs<ListState.Ready>(refresh.state.value)
        assertEquals(START, first.readAt)
        assertEquals(setOf(1, 2, 3), first.list.events.map { it.id }.toSet())

        advanceTimeBy(10.minutes)
        refresh.shown().join()
        advanceTimeBy(5.minutes)
        refresh.shown().join()
        assertEquals(oneRead, sportarr.http.requests.size, "15 minutes isn't more than 15 minutes")

        advanceTimeBy(1.seconds)
        refresh.shown().join()
        assertEquals(2 * oneRead, sportarr.http.requests.size)
        assertEquals(START.plus(Duration.ofSeconds(15 * 60 + 1)), assertIs<ListState.Ready>(refresh.state.value).readAt)
    }

    @Test
    fun `Refresh and Try again read at once, whatever the list's age`() = runTest {
        val refresh = refresh()
        refresh.shown().join()
        advanceTimeBy(1.minutes)
        refresh.refresh().join()
        assertEquals(2 * oneRead, sportarr.http.requests.size)
        refresh.refresh().join()
        assertEquals(3 * oneRead, sportarr.http.requests.size)
    }

    @Test
    fun `asks while a read runs join it`() = runTest {
        sportarr.latency = { delay(2_000) }
        val refresh = refresh()

        val first = refresh.refresh()
        runCurrent()
        assertSame(first, refresh.refresh())
        val shown = refresh.shown()
        advanceTimeBy(1_000)
        assertSame(first, refresh.refresh())
        first.join()
        shown.join()

        assertEquals(oneRead, sportarr.http.requests.size)
        assertEquals(1, sportarr.http.mostAtOnce)
        assertIs<ListState.Ready>(refresh.state.value)
    }

    @Test
    fun `a Save that changed the address or the key reads at once, and one that changed nothing doesn't`() = runTest {
        val secrets = MapSecrets()
        val refresh = refresh(secrets)
        refresh.shown().join()
        assertEquals(ListState.NotSetUp, refresh.state.value)
        assertEquals(0, sportarr.http.requests.size)

        val connection = Connection(secrets)
        refresh.saved(connection.save("http://127.0.0.1:1867", KEY)).join()
        assertEquals(oneRead, sportarr.http.requests.size)
        assertIs<ListState.Ready>(refresh.state.value)

        refresh.saved(connection.save("http://127.0.0.1:1867", KEY)).join()
        assertEquals(oneRead, sportarr.http.requests.size, "nothing changed")

        refresh.saved(connection.save("http://127.0.0.1:1867/", "another-key")).join()
        assertEquals(2 * oneRead, sportarr.http.requests.size)
        assertTrue(sportarr.http.requests.takeLast(oneRead).all { it.headers["X-Api-Key"] == "another-key" })
    }

    @Test
    fun `a changed Save while a read runs reads again with what was saved`() = runTest {
        sportarr.latency = { delay(2_000) }
        val secrets = MapSecrets(mapOf("url" to "http://127.0.0.1:1867", "key" to KEY))
        val refresh = refresh(secrets)
        val first = refresh.refresh()
        advanceTimeBy(2_500)

        val again = refresh.saved(Connection(secrets).save("http://127.0.0.1:1867", "another-key"))
        again.join()

        assertTrue(first.isCancelled, "the read with the old key was let go")
        assertEquals(1, sportarr.http.mostAtOnce)
        assertTrue(sportarr.http.requests.takeLast(oneRead).all { it.headers["X-Api-Key"] == "another-key" })
        assertIs<ListState.Ready>(refresh.state.value)
    }

    @Test
    fun `not set up, nothing is read`() = runTest {
        val refresh = refresh(MapSecrets(mapOf("url" to "http://127.0.0.1:1867")))
        refresh.shown().join()
        refresh.refresh().join()
        assertEquals(ListState.NotSetUp, refresh.state.value)
        assertEquals(0, sportarr.http.requests.size)
    }

    @Test
    fun `Loading while the first read runs`() = runTest {
        sportarr.latency = { delay(1_000) }
        val refresh = refresh()
        val shown = refresh.shown()
        runCurrent()
        assertEquals(ListState.Loading, refresh.state.value)
        shown.join()
        assertIs<ListState.Ready>(refresh.state.value)
    }

    @Test
    fun `a 401 on a page keeps the old list whole, Refused`() = runTest {
        val kept = KeptList(dir, EmptyCoroutineContext)
        val refresh = refresh(kept = kept)
        refresh.shown().join()
        val before = assertIs<ListState.Ready>(refresh.state.value).list

        sportarr.pages[2 to 1] = { answer(401) }
        advanceTimeBy(20.minutes)
        refresh.shown().join()

        val refused = assertIs<ListState.Refused>(refresh.state.value)
        assertEquals(before, refused.list)
        assertEquals(START, refused.readAt)
        assertEquals(before, kept.read(), "list.json is the old list")
    }

    @Test
    fun `a failed read keeps the old list whole, Unreachable`() = runTest {
        val kept = KeptList(dir, EmptyCoroutineContext)
        val refresh = refresh(kept = kept)
        refresh.shown().join()
        val before = assertIs<ListState.Ready>(refresh.state.value).list

        sportarr.pages[2 to 1] = { answer(500) }
        refresh.refresh().join()

        assertEquals(ListState.Unreachable(before, Failure.ErrorStatus), refresh.state.value)
        assertEquals(START, (refresh.state.value as ListState.Unreachable).readAt)
        assertEquals(before, kept.read())
    }

    @Test
    fun `with no kept list a failed read is Unreachable with no list`() = runTest {
        sportarr.leaguesAnswer = { noAnswer(io.skjaere.usenet.plugin.PluginHttpException.Kind.Unreachable) }
        val refresh = refresh()
        refresh.shown().join()
        val state = assertIs<ListState.Unreachable>(refresh.state.value)
        assertNull(state.list)
        assertNull(state.readAt)
        assertEquals(Failure.NoAnswer, state.why)
    }

    @Test
    fun `the kept list shows at once from list_json, and an old one is read again behind it`() = runTest {
        val kept = KeptList(dir, EmptyCoroutineContext)
        val old = SportsList(START.minus(Duration.ofHours(3)), listOf(KeptLeague(1, "Premier League", "Soccer")), listOf(event(9, START, 1)), cut = false)
        kept.write(old)
        sportarr.latency = { delay(1_000) }
        val refresh = refresh(kept = kept)

        val shown = refresh.shown()
        runCurrent()
        assertEquals(ListState.Ready(old), refresh.state.value)
        shown.join()
        val read = assertIs<ListState.Ready>(refresh.state.value)
        assertEquals(setOf(1, 2, 3), read.list.events.map { it.id }.toSet())
        assertEquals(read.list, kept.read())
    }

    @Test
    fun `a fresh kept list is shown and not read`() = runTest {
        val kept = KeptList(dir, EmptyCoroutineContext)
        val fresh = SportsList(START.minus(Duration.ofMinutes(5)), listOf(KeptLeague(1, "Premier League", "Soccer")), listOf(event(9, START, 1)), false)
        kept.write(fresh)
        val refresh = refresh(kept = kept)
        refresh.shown().join()
        assertEquals(ListState.Ready(fresh), refresh.state.value)
        assertEquals(0, sportarr.http.requests.size)
    }

    @Test
    fun `nothing switched on in Sportarr is NothingFollowed`() = runTest {
        sportarr.leagues = listOf(league(1, monitored = false))
        val refresh = refresh()
        refresh.shown().join()
        assertEquals(ListState.NothingFollowed, refresh.state.value)
    }

    @Test
    fun `the decode runs off the dispatcher that asked`() {
        val callerThread = CopyOnWriteArrayList<Thread>()
        val callerThreads = Executors.newSingleThreadExecutor { Thread(it, "refresh-caller").also { thread -> callerThread += thread } }
        val scope = CoroutineScope(callerThreads.asCoroutineDispatcher() + SupervisorJob())
        val decodedOn = CopyOnWriteArrayList<Thread>()
        // The decoder seam: Wire's own reading, the thread it ran on noted first.
        val decoder = object : WireDecoder {
            override fun status(body: ByteArray) = Wire.status(body).also { decodedOn += Thread.currentThread() }

            override fun leagues(body: ByteArray) = Wire.leagues(body).also { decodedOn += Thread.currentThread() }

            override fun events(body: ByteArray) = Wire.events(body).also { decodedOn += Thread.currentThread() }
        }
        try {
            val refresh = Refresh(scope, Connection(MapSecrets(mapOf("url" to "http://127.0.0.1:1867", "key" to KEY))), KeptList(dir), { START }) { saved ->
                ListReader(SportarrClient(sportarr.http, saved.address, saved.key, ::loopbackSafety), START, ZoneOffset.UTC, decoder).read()
            }
            runBlocking { refresh.refresh().join() }

            assertEquals(oneRead, decodedOn.size, "the leagues and each page decoded through the seam")
            assertEquals(1, callerThread.size, "the caller's one thread ran the refresh")
            assertTrue(decodedOn.none { it === callerThread.single() }, "decoded on: ${decodedOn.map { it.name }}")
            assertNotNull(KeptList(dir).let { runBlocking { it.read() } })
            assertIs<ListState.Ready>(refresh.state.value)
        } finally {
            scope.cancel()
            callerThreads.shutdownNow()
        }
    }

    private companion object {
        val START: Instant = Instant.parse("2026-11-24T12:00:00Z")
        const val KEY = "kestrel0123456789abcdef0123456789"
    }
}
