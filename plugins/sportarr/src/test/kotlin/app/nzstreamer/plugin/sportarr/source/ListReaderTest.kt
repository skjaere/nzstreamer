package app.nzstreamer.plugin.sportarr.source

import io.skjaere.usenet.plugin.PluginHttpException.Kind
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * L641 against a small Sportarr that pages as Sportarr does: the leagues, then each league switched on, a page at a time
 * and a league at a time; a newest-first walk stops at the first page that reaches before the window, a page out of order
 * reads on to the last page or the budget of six, a plain array is the whole league; the window is the device's days; the
 * newest 5,000 are kept; a cut by the budget or the cap is said; and the read commits all or nothing, a league removed
 * between the two reads left out.
 */
class ListReaderTest {
    private fun read(sportarr: TestSportarr, now: Instant = NOW, zone: ZoneId = ZoneOffset.UTC): ListRead = kotlinx.coroutines.runBlocking {
        reader(sportarr, now, zone).read()
    }

    private fun reader(sportarr: TestSportarr, now: Instant = NOW, zone: ZoneId = ZoneOffset.UTC) =
        ListReader(SportarrClient(sportarr.http, BASE, KEY, ::loopbackSafety, EmptyCoroutineContext), now, zone, work = EmptyCoroutineContext)

    private fun list(read: ListRead): SportsList = assertIs<ListRead.Read>(read).list

    @Test
    fun `a newest-first walk stops at the first page that reaches before the window`() {
        // 4,000 events three hours apart from 100 days ahead: page 2 reaches past the window's start, 60 days back.
        val events = series(4000, NOW.plus(Duration.ofDays(100)), Duration.ofHours(3))
        val sportarr = TestSportarr(listOf(league(1)), mapOf(1 to events))

        val list = list(read(sportarr))

        assertEquals(listOf(1 to 1, 1 to 2), sportarr.pagesAsked)
        assertEquals(events.filter { it.eventDate >= WINDOW_START && it.eventDate < WINDOW_END }.map { it.id }.toSet(), list.events.map { it.id }.toSet())
        assertFalse(list.cut)
        assertEquals(NOW, list.readAt)
    }

    @Test
    fun `pages out of order read on to the last page, and keep what falls in the window`() {
        val events = series(4000, NOW.plus(Duration.ofDays(100)), Duration.ofHours(3))
        val sportarr = TestSportarr(listOf(league(1)), mapOf(1 to events)).apply { ascending = true }

        val list = list(read(sportarr))

        assertEquals((1..4).map { 1 to it }, sportarr.pagesAsked)
        assertEquals(events.count { it.eventDate >= WINDOW_START && it.eventDate < WINDOW_END }, list.events.size)
        assertFalse(list.cut)
    }

    @Test
    fun `pages out of order read on to the budget, and the list says it was cut`() {
        // Eight pages, the window's events on pages 1 and 2, as a newest-first walk would find them, but no page in order.
        val events = series(8000, NOW.plus(Duration.ofDays(31)), Duration.ofMinutes(30))
        val sportarr = TestSportarr(listOf(league(1)), mapOf(1 to events)).apply { ascending = true }

        val list = list(read(sportarr))

        assertEquals((1..6).map { 1 to it }, sportarr.pagesAsked)
        assertEquals(events.take(6000).count { it.eventDate >= WINDOW_START && it.eventDate < WINDOW_END }, list.events.size)
        assertTrue(list.cut)
    }

    @Test
    fun `a page that starts after the page before ended turns the order check off`() {
        // Each page newest first, but page 2 starts after page 1's oldest: a walk that trusted the order would stop there.
        val first = series(1000, NOW.plus(Duration.ofDays(10)), Duration.ofMinutes(10), firstId = 1)
        val second = series(1000, NOW.plus(Duration.ofDays(20)), Duration.ofDays(1), firstId = 2001)
        val third = series(5, NOW.minus(Duration.ofDays(3)), Duration.ofDays(1), firstId = 4001)
        val sportarr = TestSportarr(listOf(league(1)), mapOf(1 to emptyList())).apply {
            pages[1 to 1] = { envelope(1, 3, first) }
            pages[1 to 2] = { envelope(2, 3, second) }
            pages[1 to 3] = { envelope(3, 3, third) }
        }

        val list = list(read(sportarr))

        assertEquals((1..3).map { 1 to it }, sportarr.pagesAsked)
        assertTrue(third.all { event -> list.events.any { it.id == event.id } }, "the last page's events, in the window, are kept")
    }

    @Test
    fun `a plain array is the whole league at once`() {
        val events = series(3000, NOW.plus(Duration.ofDays(100)), Duration.ofHours(3))
        val sportarr = TestSportarr(listOf(league(1)), mapOf(1 to events)).apply { paging = false }

        val list = list(read(sportarr))

        assertEquals(listOf(1 to 1), sportarr.pagesAsked)
        assertEquals(events.count { it.eventDate >= WINDOW_START && it.eventDate < WINDOW_END }, list.events.size)
        assertFalse(list.cut)
    }

    @Test
    fun `six pages a league at most, and the list says it was cut`() {
        // A big league listing the far future first: six pages of it never reach the window.
        val far = series(6500, NOW.plus(Duration.ofDays(400)), Duration.ofMinutes(80), firstId = 1)
        val near = series(20, NOW, Duration.ofDays(1), firstId = 9001)
        val sportarr = TestSportarr(listOf(league(1), league(2)), mapOf(1 to far, 2 to near))

        val list = list(read(sportarr))

        assertEquals((1..6).map { 1 to it } + (2 to 1), sportarr.pagesAsked)
        assertEquals(near.map { it.id }.toSet(), list.events.map { it.id }.toSet())
        assertTrue(list.cut)
        assertEquals(1 + 7, sportarr.http.requests.size)
    }

    @Test
    fun `the newest 5,000 are kept, newest first, and the list says it was cut`() {
        val first = series(3000, NOW.plus(Duration.ofDays(30)), Duration.ofMinutes(40), firstId = 1, leagueId = 1)
        val second = series(3000, NOW.plus(Duration.ofDays(30)).minusSeconds(60), Duration.ofMinutes(40), firstId = 10_001, leagueId = 2)
        val sportarr = TestSportarr(listOf(league(1), league(2, "Kestrel Hockey League", "Ice Hockey")), mapOf(1 to first, 2 to second))

        val list = list(read(sportarr))

        val all = (first + second).filter { it.eventDate >= WINDOW_START && it.eventDate < WINDOW_END }
        assertTrue(all.size > 5000, "the fixture fills the window past the cap: ${all.size}")
        val newest = all.sortedWith(compareByDescending<WireEvent> { it.eventDate }.thenByDescending { it.id }).take(5000)
        assertEquals(newest.map { it.id }, list.events.map { it.id })
        assertTrue(list.cut)
    }

    @Test
    fun `nothing cut says so`() {
        val sportarr = TestSportarr(listOf(league(1)), mapOf(1 to series(30, NOW, Duration.ofDays(1))))
        assertFalse(list(read(sportarr)).cut)
    }

    @Test
    fun `any failure fails the whole read, with nothing of it kept`() {
        val failures = listOf(
            { s: TestSportarr -> s.pages[2 to 2] = { answer(500) } } to Failure.ErrorStatus,
            { s: TestSportarr -> s.pages[2 to 2] = { answer(302) } } to Failure.ErrorStatus,
            { s: TestSportarr -> s.pages[2 to 2] = { noAnswer(Kind.Timeout) } } to Failure.NoAnswer,
            { s: TestSportarr -> s.pages[2 to 2] = { noAnswer(Kind.Unreachable) } } to Failure.NoAnswer,
            { s: TestSportarr -> s.pages[2 to 2] = { noAnswer(Kind.TooLarge) } } to Failure.TooLarge,
            { s: TestSportarr -> s.pages[2 to 2] = { answer(200, "<html>Sportarr is starting</html>") } } to Failure.Unreadable,
            { s: TestSportarr -> s.pages[2 to 2] = { answer(200, """{"page":2,"records":""") } } to Failure.Unreadable,
            { s: TestSportarr -> s.leaguesAnswer = { answer(500) } } to Failure.ErrorStatus,
            { s: TestSportarr -> s.leaguesAnswer = { answer(200, "{}") } } to Failure.Unreadable,
            { s: TestSportarr -> s.leaguesAnswer = { noAnswer(Kind.Unreachable) } } to Failure.NoAnswer,
            { s: TestSportarr -> s.leaguesAnswer = { answer(404) } } to Failure.ErrorStatus,
        )
        failures.forEachIndexed { index, (fail, why) ->
            val sportarr = threeLeagues().apply(fail)
            assertEquals(ListRead.Failed(why), read(sportarr), "case $index")
        }
    }

    @Test
    fun `a 401 or 403 on any request is Refused`() {
        listOf<(TestSportarr) -> Unit>(
            { it.leaguesAnswer = { answer(401) } },
            { it.leaguesAnswer = { answer(403) } },
            { it.pages[1 to 1] = { answer(401) } },
            { it.pages[2 to 2] = { answer(403) } },
        ).forEachIndexed { index, refuse ->
            assertEquals(ListRead.Refused, read(threeLeagues().apply(refuse)), "case $index")
        }
    }

    @Test
    fun `a league that answers 404 was removed meanwhile, and the rest commits`() {
        val removed = threeLeagues().apply { events = events - 2 }
        val list = list(read(removed))
        assertEquals(listOf(1, 3), list.leagues.map { it.id })
        assertEquals(setOf(1, 3), list.events.map { it.leagueId }.toSet())

        // Removed after its first page was read: none of its events stay.
        val halfway = threeLeagues().apply { pages[2 to 2] = { answer(404, """{"error":"League not found"}""") } }
        val after = list(read(halfway))
        assertEquals(listOf(1, 3), after.leagues.map { it.id })
        assertFalse(after.events.any { it.leagueId == 2 })
    }

    @Test
    fun `one request for the leagues and one a page, a league at a time, in Sportarr's order`() = runTest {
        val sportarr = threeLeagues().apply { latency = { delay(250) } }

        list(reader(sportarr).read())

        val pages = listOf(1 to 1, 2 to 1, 2 to 2, 3 to 1)
        assertEquals(1 + pages.size, sportarr.http.requests.size)
        assertEquals("$BASE/api/leagues", sportarr.http.requests.first().url)
        assertEquals(pages, sportarr.pagesAsked)
        assertEquals(1, sportarr.http.mostAtOnce)
    }

    @Test
    fun `only leagues switched on in Sportarr are read, with what counts of each`() {
        val sportarr = TestSportarr(
            listOf(
                league(1, "Premier League"),
                league(2, "Kestrel Hockey League", "Ice Hockey", monitored = false),
                league(3, "Kestrel Premier Division", keepAllEvents = true),
            ),
            mapOf(
                1 to listOf(event(1, NOW, 1, monitored = false), event(2, NOW, 1)),
                2 to listOf(event(3, NOW, 2)),
                3 to listOf(event(4, NOW, 3), event(5, NOW, 3, monitored = false, hasFile = true), event(6, NOW, 3, monitored = false)),
            ),
        )

        val list = list(read(sportarr))

        assertEquals(listOf(1 to 1, 3 to 1), sportarr.pagesAsked)
        assertEquals(listOf(1, 3), list.leagues.map { it.id })
        assertEquals(setOf(1, 2, 4, 5), list.events.map { it.id }.toSet())
        assertEquals(KeptLeague(1, "Premier League", "Soccer", null), list.leagues.first())
    }

    @Test
    fun `each event is kept under the league whose route listed it`() {
        val sportarr = TestSportarr(
            listOf(league(1, "Premier League"), league(3, "UFC", "Fighting")),
            mapOf(
                1 to listOf(event(1, NOW, 1), event(2, NOW, leagueId = 99)),
                3 to listOf(event(3, NOW, 3).copy(leagueId = null)),
            ),
        )
        val list = list(read(sportarr))
        assertEquals(mapOf(1 to 1, 2 to 1, 3 to 3), list.events.associate { it.id to it.leagueId })
    }

    @Test
    fun `nothing switched on reads the leagues alone`() {
        val sportarr = TestSportarr(listOf(league(1, monitored = false)), mapOf(1 to listOf(event(1, NOW))))
        val list = list(read(sportarr))
        assertEquals(emptyList(), list.leagues)
        assertEquals(emptyList(), list.events)
        assertEquals(1, sportarr.http.requests.size)
    }

    @Test
    fun `the window runs from the start of the local day 60 days back to the end of the local day 31 days ahead`() {
        val now = Instant.parse("2026-11-24T02:00:00Z")
        val events = listOf(
            event(1, Instant.parse("2026-09-24T12:00:00Z")),
            event(2, Instant.parse("2026-09-24T15:00:00Z")),
            event(3, Instant.parse("2026-09-25T00:00:00Z")),
            event(4, Instant.parse("2026-12-25T12:00:00Z")),
            event(5, Instant.parse("2026-12-25T20:00:00Z")),
            event(6, Instant.parse("2026-12-26T00:00:00Z")),
        )
        fun kept(zone: ZoneId) = list(read(TestSportarr(listOf(league(1)), mapOf(1 to events)), now, zone)).events.map { it.id }.toSet()

        // UTC: 24 Nov, so 25 Sep 00:00 to 26 Dec 00:00.
        assertEquals(setOf(3, 4, 5), kept(ZoneOffset.UTC))
        // Ten hours ahead it is 24 Nov at noon: 24 Sep 14:00 to 25 Dec 14:00 in UTC.
        assertEquals(setOf(2, 3, 4), kept(ZoneOffset.ofHours(10)))
        // Ten hours behind it is still 23 Nov: 24 Sep 10:00 to 25 Dec 10:00 in UTC.
        assertEquals(setOf(1, 2, 3), kept(ZoneOffset.ofHours(-10)))
    }

    @Test
    fun `an empty page is the last`() {
        val sportarr = TestSportarr(listOf(league(1)), mapOf(1 to emptyList())).apply {
            pages[1 to 1] = { envelope(1, 9, series(1000, NOW.plus(Duration.ofDays(20)), Duration.ofMinutes(20))) }
            pages[1 to 2] = { envelope(2, 9, emptyList()) }
        }
        list(read(sportarr))
        assertEquals(listOf(1 to 1, 1 to 2), sportarr.pagesAsked)
    }

    /** Three small leagues: one page, two pages, one page. */
    private fun threeLeagues() = TestSportarr(
        listOf(league(1, "Premier League"), league(2, "MotoGP", "Motorsport"), league(3, "UFC", "Fighting")),
        mapOf(
            1 to series(20, NOW, Duration.ofDays(1), firstId = 1, leagueId = 1),
            2 to series(1500, NOW.plus(Duration.ofDays(20)), Duration.ofHours(1), firstId = 101, leagueId = 2),
            3 to series(4, NOW, Duration.ofDays(7), firstId = 5001, leagueId = 3),
        ),
    )

    private companion object {
        const val BASE = "http://127.0.0.1:1867"
        const val KEY = "kestrel0123456789abcdef0123456789"
        val NOW: Instant = Instant.parse("2026-11-24T12:00:00Z")
        val WINDOW_START: Instant = Instant.parse("2026-09-25T00:00:00Z")
        val WINDOW_END: Instant = Instant.parse("2026-12-26T00:00:00Z")

        /** [count] events [step] apart, the newest at [newest]. */
        fun series(count: Int, newest: Instant, step: Duration, firstId: Int = 1, leagueId: Int = 1): List<WireEvent> =
            (0 until count).map { event(firstId + it, newest.minus(step.multipliedBy(it.toLong())), leagueId) }

        fun envelope(page: Int, totalPages: Int, records: List<WireEvent>) = answer(
            200,
            """{"page":$page,"pageSize":1000,"totalRecords":${totalPages * 1000},"totalPages":$totalPages,"records":${TestSportarr.eventsJson(records)}}""",
        )
    }
}
