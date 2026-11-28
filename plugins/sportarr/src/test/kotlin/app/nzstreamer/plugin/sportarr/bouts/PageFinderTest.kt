package app.nzstreamer.plugin.sportarr.bouts

import app.nzstreamer.plugin.sportarr.Logged
import app.nzstreamer.plugin.sportarr.bouts.Fixtures.article
import app.nzstreamer.plugin.sportarr.bouts.Fixtures.dateCell
import app.nzstreamer.plugin.sportarr.bouts.Fixtures.index
import app.nzstreamer.plugin.sportarr.model.BOXING
import app.nzstreamer.plugin.sportarr.model.SportsEvent
import app.nzstreamer.plugin.sportarr.model.UFC
import app.nzstreamer.plugin.sportarr.model.VELMORA
import app.nzstreamer.plugin.sportarr.model.at
import app.nzstreamer.plugin.sportarr.model.on
import app.nzstreamer.plugin.sportarr.model.sports
import app.nzstreamer.plugin.sportarr.source.KeptLeague
import app.nzstreamer.plugin.sportarr.source.RecordingHttp
import app.nzstreamer.plugin.sportarr.source.answer
import app.nzstreamer.plugin.sportarr.source.noAnswer
import io.skjaere.usenet.plugin.PluginHttpException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * L-B1's finding of an event's article: the candidate titles and their one encoder, the canonical title and the date that
 * accept a page, a redirect named by its canonical link; then, only when every candidate missed, the index's two columns
 * (red links never a match, nothing under /w/ asked) and its one matching row's article, the index kept a day.
 */
class PageFinderTest {
    @TempDir
    lateinit var dir: File

    private var now: Instant = Instant.parse("2026-11-22T12:00:00Z")

    private val ufc999 = event(31, "UFC 999: Quessel vs. Halde", at("2026-11-21T23:00"))
    private val fightNight = event(32, "UFC Fight Night: Brisk vs. Loam", at("2026-11-28T01:00"))
    private val night888 = event(33, "UFC Fight Night 888 Brisk vs Loam", at("2026-11-28T01:00"))

    private fun event(id: Int, title: String, start: Instant, broadcast: LocalDate? = null, league: KeptLeague = UFC): SportsEvent =
        sports(league to listOf(on(league, id, start, title).copy(broadcastDate = broadcast))).events.single()

    /** A wiki of [pages] by path; any other path is a 404. */
    private fun wiki(vararg pages: Pair<String, String>): RecordingHttp {
        val byPath = pages.toMap()
        return RecordingHttp { request -> byPath[request.url.removePrefix(BASE)]?.let { answer(200, it) } ?: answer(404, "<p>No article</p>") }
    }

    private fun finder(
        http: RecordingHttp,
        copy: IndexCopy = IndexFile(dir, EmptyCoroutineContext),
        decode: (ByteArray) -> String = { it.decodeToString() },
    ) = PageFinder(BASE, WikipediaRequests(http, { now }), copy, { now }, EmptyCoroutineContext, decode)

    private fun paths(http: RecordingHttp): List<String> = http.requests.map { it.url.removePrefix(BASE) }

    private fun articleOn(canonical: String, iso: String) = article(canonical = "https://en.wikipedia.org/wiki/$canonical", date = dateCell("A day", iso))

    @Test
    fun `the titles asked are the number, the title as written, then the fight night's number`() {
        assertEquals(listOf("UFC 999", "UFC 999: Quessel vs. Halde"), PageFinder.candidates("UFC 999: Quessel vs. Halde"))
        assertEquals(listOf("UFC Fight Night: Brisk vs. Loam"), PageFinder.candidates("UFC Fight Night: Brisk vs. Loam"))
        assertEquals(listOf("UFC Fight Night 888 Brisk vs Loam", "UFC Fight Night 888"), PageFinder.candidates("UFC Fight Night 888 Brisk vs Loam"))
        assertEquals(listOf("UFC 999"), PageFinder.candidates("  UFC   999 "))
        assertEquals(listOf("UFC 999", "ufc 999 Quessel vs. Halde"), PageFinder.candidates("ufc 999 Quessel vs. Halde"))
        assertEquals(listOf("UFC 9990x"), PageFinder.candidates("UFC 9990x"))
    }

    @Test
    fun `only a UFC card, numbered or a fight night, is read`() {
        listOf(ufc999, fightNight, night888).forEach { assertTrue(PageFinder.reads(it), it.title) }
        val others = listOf(
            event(40, "UFC Countdown: Quessel vs. Halde", at("2026-11-21T20:00")),
            event(41, "Kestrel 999: Quessel vs. Halde", at("2026-11-21T20:00")),
            event(42, "Kestrel Boxing 12: Quessel vs. Halde", at("2026-11-21T20:00"), league = BOXING),
            event(43, "Velmora Wrestling: Night of Kestrels", at("2026-11-21T20:00"), league = VELMORA),
        )
        others.forEach { assertFalse(PageFinder.reads(it), it.title) }
    }

    @Test
    fun `a title is encoded one way, underscores and a few marks kept, everything else as UTF-8, never a query`() {
        assertEquals("$BASE/wiki/UFC_Fight_Night:_Brisk_vs._Loam", PageFinder.address(BASE, "UFC Fight Night: Brisk vs. Loam"))
        assertEquals("$BASE/wiki/Qu%C3%A9ssel_(c),_~x-y", PageFinder.address(BASE, "Quéssel (c), ~x-y"))
        assertEquals("$BASE/wiki/A%2FB%3Fc%3Dd%26e%23f%25%2B%27%22", PageFinder.address(BASE, "A/B?c=d&e#f%+'\""))
        assertEquals("$BASE/wiki/List_of_UFC_events", PageFinder.address(BASE, PageFinder.INDEX))
        assertEquals("https://en.wikipedia.org/wiki/UFC_999", PageFinder.address("https://en.wikipedia.org/", "UFC 999"))
    }

    @Test
    fun `a request and the article's own address come from the one encoder, and the canonical path names the article`() = runTest {
        val title = "UFC Fight Night: Quéssel vs. Halde"
        val http = wiki("/wiki/UFC_Fight_Night:_Qu%C3%A9ssel_vs._Halde" to articleOn("UFC_Fight_Night:_Qu%C3%A9ssel_vs._Halde", "2026-11-28"))
        val found = assertIs<PageFinder.Found.Article>(finder(http).find(event(34, title, at("2026-11-28T01:00"))))
        assertEquals(listOf(PageFinder.address(BASE, title)), http.requests.map { it.url })
        assertEquals(title, found.title)
        assertEquals(http.requests.single().url, PageFinder.address(BASE, found.title))
        assertFalse(http.requests.any { '?' in it.url })
    }

    @Test
    fun `a redirect's article is named by its canonical link, never by the title asked`() = runTest {
        val http = wiki("/wiki/UFC_Fight_Night_888" to articleOn("UFC_Fight_Night:_Brisk_vs._Loam", "2026-11-28"))
        val found = assertIs<PageFinder.Found.Article>(finder(http).find(night888))
        assertEquals("UFC Fight Night: Brisk vs. Loam", found.title)
        assertEquals(listOf("/wiki/UFC_Fight_Night_888_Brisk_vs_Loam", "/wiki/UFC_Fight_Night_888"), paths(http))
    }

    @Test
    fun `a page's date is the event's within a day, in UTC or as broadcast, or the page isn't the event's`() = runTest {
        listOf("2026-11-20", "2026-11-21", "2026-11-22").forEach { iso ->
            assertIs<PageFinder.Found.Article>(finder(wiki("/wiki/UFC_999" to articleOn("UFC_999", iso))).find(ufc999), iso)
        }
        listOf("2026-11-19", "2026-11-23", "2025-11-21").forEach { iso ->
            assertEquals(PageFinder.Found.NoPage, finder(wiki("/wiki/UFC_999" to articleOn("UFC_999", iso))).find(ufc999), iso)
        }
        val broadcast = event(35, "UFC 999: Quessel vs. Halde", at("2026-11-21T23:00"), broadcast = LocalDate.of(2026, 11, 22))
        assertIs<PageFinder.Found.Article>(finder(wiki("/wiki/UFC_999" to articleOn("UFC_999", "2026-11-23"))).find(broadcast))
        // No Date row, or no ISO date in it: never the event's.
        assertEquals(PageFinder.Found.NoPage, finder(wiki("/wiki/UFC_999" to article(date = null))).find(ufc999))
        assertEquals(PageFinder.Found.NoPage, finder(wiki("/wiki/UFC_999" to article(date = "November 21, 2026"))).find(ufc999))
    }

    @Test
    fun `a canonical title not starting UFC, a season's article among them, refuses the page`() = runTest {
        listOf("2026_in_UFC", "Quessel", "Ufc_999", "UFC999").forEach { canonical ->
            assertEquals(PageFinder.Found.NoPage, finder(wiki("/wiki/UFC_999" to articleOn(canonical, "2026-11-21"))).find(ufc999), canonical)
        }
        assertEquals(PageFinder.Found.NoPage, finder(wiki("/wiki/UFC_999" to article(canonical = null))).find(ufc999))
        // Only the path counts: the host is never asked, and a relative link is as good.
        listOf("/wiki/UFC_999", "https://elsewhere.invalid/wiki/UFC_999").forEach { canonical ->
            assertIs<PageFinder.Found.Article>(finder(wiki("/wiki/UFC_999" to article(canonical = canonical))).find(ufc999), canonical)
        }
    }

    @Test
    fun `a candidate whose page throws while it is read is a miss, and an index that throws is no page, nothing escaping`() = runTest {
        // A page holding the mark throws as it is read, its message naming what it read; any other reads as it is.
        val mark = "<!-- unreadable -->"
        val decode: (ByteArray) -> String = { bytes -> bytes.decodeToString().also { check(mark !in it) { "Quessel vs. Halde at /wiki/UFC_999" } } }
        val logged = Logged.mark()
        val http = wiki("/wiki/UFC_999" to article() + mark, "/wiki/UFC_999:_Quessel_vs._Halde" to article())
        assertEquals("UFC 999", assertIs<PageFinder.Found.Article>(finder(http, decode = decode).find(ufc999)).title)
        assertEquals(listOf("/wiki/UFC_999", "/wiki/UFC_999:_Quessel_vs._Halde"), paths(http))

        val index = index(scheduled = emptyList(), past = listOf(IndexEntry("UFC 999: Quessel vs. Halde", "/wiki/UFC_999_(Kestrel)", "Nov 21, 2026")))
        val listed = wiki("/wiki/List_of_UFC_events" to index + mark, "/wiki/UFC_999_(Kestrel)" to articleOn("UFC_999_(Kestrel)", "2026-11-21"))
        assertEquals(PageFinder.Found.NoPage, finder(listed, decode = decode).find(ufc999))
        assertEquals(listOf("/wiki/UFC_999", "/wiki/UFC_999:_Quessel_vs._Halde", "/wiki/List_of_UFC_events"), paths(listed))
        assertEquals(emptyList(), dir.list()!!.toList(), "an index that couldn't be read isn't kept")

        val lines = Logged.pluginLinesSince(logged).map { it.words }
        assertEquals(2, lines.count { "IllegalStateException" in it }, "$lines")
        listOf("Quessel", "Halde", "999", "/wiki").forEach { word -> assertFalse(lines.any { word in it }, "$word in $lines") }
    }

    @Test
    fun `the candidates' pages, the index and its row's article are read off the thread that asked`() {
        val caller = CopyOnWriteArrayList<Thread>()
        val callers = Executors.newSingleThreadExecutor { Thread(it, "finder-caller").also { thread -> caller += thread } }
        val readOn = CopyOnWriteArrayList<Thread>()
        try {
            val index = index(scheduled = emptyList(), past = listOf(IndexEntry("UFC 999: Quessel vs. Halde", "/wiki/UFC_999_(Kestrel)", "Nov 21, 2026")))
            val http = wiki(
                "/wiki/UFC_999" to articleOn("Quessel", "2026-11-21"),
                "/wiki/List_of_UFC_events" to index,
                "/wiki/UFC_999_(Kestrel)" to articleOn("UFC_999_(Kestrel)", "2026-11-21"),
            )
            // The finder's work as a session makes it; the decode seam notes the thread it ran on.
            val finder = PageFinder(BASE, WikipediaRequests(http, { now }), IndexFile(dir, EmptyCoroutineContext), { now }) { bytes ->
                readOn += Thread.currentThread()
                bytes.decodeToString()
            }
            val found = runBlocking(callers.asCoroutineDispatcher()) { finder.find(ufc999) }
            assertEquals("UFC 999 (Kestrel)", assertIs<PageFinder.Found.Article>(found).title)
            assertEquals(1, caller.size, "the caller's one thread asked")
            assertEquals(3, readOn.size, "the refused candidate, the index and its row's article")
            assertTrue(readOn.none { it === caller.single() }, "read on: ${readOn.map { it.name }}")
        } finally {
            callers.shutdownNow()
        }
    }

    @Test
    fun `every candidate missed, the index is asked with 8 MB, every request with no header`() = runTest {
        val http = wiki()
        assertEquals(PageFinder.Found.NoPage, finder(http).find(ufc999))
        assertEquals(listOf("/wiki/UFC_999", "/wiki/UFC_999:_Quessel_vs._Halde", "/wiki/List_of_UFC_events"), paths(http))
        assertEquals(listOf(4, 4, 8).map { it * 1024 * 1024 }, http.requests.map { it.maxBytes })
        assertTrue(http.requests.all { it.headers.isEmpty() })
    }

    @Test
    fun `the index's tables are found by their headers' words, the past one led by its number`() {
        val page = index(
            scheduled = listOf(IndexEntry("UFC Fight Night: Brisk vs. Loam", "/wiki/UFC_Fight_Night:_Brisk_vs._Loam", "Nov 28, 2026")),
            past = listOf(
                IndexEntry("UFC 999: Quessel vs. Halde", "./UFC_999_(Kestrel)", "November 21, 2026"),
                IndexEntry("UFC 998: Ambrel vs. Savrin", "/wiki/UFC_998#Results", "Sept 14, 2026"),
                IndexEntry("UFC 997: Durnay vs. Wexmoor", "/wiki/UFC_997", "Kestrel 7, 2026"),
                IndexEntry("UFC 996: Kjelsen vs. Tollerup", null, "Oct 31, 2026"),
            ),
        )
        assertEquals(
            listOf(
                IndexRow("UFC Fight Night: Brisk vs. Loam", "UFC Fight Night: Brisk vs. Loam", "2026-11-28"),
                IndexRow("UFC 999 (Kestrel)", "UFC 999: Quessel vs. Halde", "2026-11-21"),
                IndexRow("UFC 998", "UFC 998: Ambrel vs. Savrin", "2026-09-14"),
            ),
            PageFinder.indexRows(page),
        )
    }

    @Test
    fun `a Date cell of letters alone is read in time in step with its length`() {
        val page = index(
            scheduled = listOf(
                IndexEntry("UFC 999: Quessel vs. Halde", "/wiki/UFC_999", "Kestrel".repeat(60_000)),
                IndexEntry("UFC Fight Night: Brisk vs. Loam", "/wiki/UFC_Fight_Night:_Brisk_vs._Loam", "Nov 28, 2026"),
            ),
            past = emptyList(),
        )
        assertTimeoutPreemptively(Duration.ofSeconds(10)) {
            assertEquals(listOf(IndexRow("UFC Fight Night: Brisk vs. Loam", "UFC Fight Night: Brisk vs. Loam", "2026-11-28")), PageFinder.indexRows(page))
        }
    }

    @Test
    fun `the one matching row's article is read and checked, asked by its link's title encoded again`() = runTest {
        val page = index(
            scheduled = listOf(IndexEntry("UFC Fight Night: Brisk vs. Loam", "./UFC_Fight_Night%3A_Brisk_vs._Loam_(Kestrel)", "Nov 28, 2026")),
            past = listOf(
                IndexEntry("UFC 999: Quessel vs. Halde", "/wiki/UFC_999_(Kestrel)", "Nov 21, 2026"),
                IndexEntry("UFC Fight Night: Brisk vs. Loam", "/wiki/UFC_Fight_Night_887", "Nov 21, 2025"),
            ),
        )
        val http = wiki(
            "/wiki/List_of_UFC_events" to page,
            "/wiki/UFC_Fight_Night:_Brisk_vs._Loam_(Kestrel)" to articleOn("UFC_Fight_Night:_Brisk_vs._Loam_(Kestrel)", "2026-11-28"),
            "/wiki/UFC_999_(Kestrel)" to articleOn("UFC_999_(Kestrel)", "2026-11-21"),
        )
        val finder = finder(http)
        assertEquals("UFC Fight Night: Brisk vs. Loam (Kestrel)", assertIs<PageFinder.Found.Article>(finder.find(night888)).title)
        assertEquals("/wiki/UFC_Fight_Night:_Brisk_vs._Loam_(Kestrel)", paths(http).last())
        assertEquals("UFC 999 (Kestrel)", assertIs<PageFinder.Found.Article>(finder.find(ufc999)).title)
        assertEquals("/wiki/UFC_999_(Kestrel)", paths(http).last())
        // The matching row's article is checked as a candidate is: a date two days off is no page.
        val off = wiki("/wiki/List_of_UFC_events" to page, "/wiki/UFC_999_(Kestrel)" to articleOn("UFC_999_(Kestrel)", "2026-11-24"))
        assertEquals(PageFinder.Found.NoPage, finder(off, IndexFile(File(dir, "other"), EmptyCoroutineContext)).find(ufc999))
    }

    @Test
    fun `a row naming the event by its number alone, no fighter in it, matches, and a longer number doesn't`() = runTest {
        val page = index(
            scheduled = listOf(
                IndexEntry("UFC Fight Night 888", "/wiki/UFC_Fight_Night_888_(Kestrel)", "Nov 28, 2026"),
                IndexEntry("UFC Fight Night 8880", "/wiki/UFC_Fight_Night_8880", "Nov 28, 2026"),
            ),
            past = listOf(
                IndexEntry("UFC 999 (Kestrel)", "/wiki/UFC_999_(Kestrel)", "Nov 21, 2026"),
                IndexEntry("UFC 9990 (Tarnholm)", "/wiki/UFC_9990_(Tarnholm)", "Nov 21, 2026"),
            ),
        )
        val http = wiki(
            "/wiki/List_of_UFC_events" to page,
            "/wiki/UFC_999_(Kestrel)" to articleOn("UFC_999_(Kestrel)", "2026-11-21"),
            "/wiki/UFC_Fight_Night_888_(Kestrel)" to articleOn("UFC_Fight_Night_888_(Kestrel)", "2026-11-28"),
        )
        val finder = finder(http)
        assertEquals("UFC 999 (Kestrel)", assertIs<PageFinder.Found.Article>(finder.find(ufc999)).title)
        assertEquals("UFC Fight Night 888 (Kestrel)", assertIs<PageFinder.Found.Article>(finder.find(night888)).title)
        assertFalse(paths(http).any { "9990" in it || "8880" in it })
    }

    @Test
    fun `a red link is no match, and nothing under w is ever asked`() = runTest {
        val page = index(
            scheduled = listOf(
                IndexEntry("UFC 999: Quessel vs. Halde", "/w/index.php?title=UFC_999_(Kestrel)&amp;action=edit&amp;redlink=1", "Nov 21, 2026", red = true),
                IndexEntry("UFC Fight Night: Brisk vs. Loam", "/wiki/UFC_Fight_Night_(Kestrel)", "Nov 28, 2026", red = true),
            ),
            past = emptyList(),
        )
        val http = wiki("/wiki/List_of_UFC_events" to page, "/wiki/UFC_Fight_Night_(Kestrel)" to articleOn("UFC_Fight_Night_(Kestrel)", "2026-11-28"))
        val finder = finder(http)
        assertEquals(PageFinder.Found.NoPage, finder.find(ufc999))
        assertEquals(PageFinder.Found.NoPage, finder.find(fightNight))
        assertFalse(http.requests.any { "/w/" in it.url })
        assertEquals(listOf("/wiki/UFC_999", "/wiki/UFC_999:_Quessel_vs._Halde", "/wiki/List_of_UFC_events", "/wiki/UFC_Fight_Night:_Brisk_vs._Loam"), paths(http))
    }

    @Test
    fun `two matching rows are no page, and no article is asked`() = runTest {
        val page = index(
            scheduled = emptyList(),
            past = listOf(
                IndexEntry("UFC 999: Quessel vs. Halde", "/wiki/UFC_999_(Kestrel)", "Nov 22, 2026"),
                IndexEntry("UFC 999: Quessel vs. Halde II", "/wiki/UFC_999_(Tarnholm)", "Nov 21, 2026"),
            ),
        )
        val http = wiki("/wiki/List_of_UFC_events" to page, "/wiki/UFC_999_(Kestrel)" to articleOn("UFC_999_(Kestrel)", "2026-11-21"))
        assertEquals(PageFinder.Found.NoPage, finder(http).find(ufc999))
        assertEquals(3, http.requests.size)
    }

    @Test
    fun `the index is asked once a day at most, its rows kept in the cache folder`() = runTest {
        val page = index(scheduled = emptyList(), past = listOf(IndexEntry("UFC 999: Quessel vs. Halde", "/wiki/UFC_999_(Kestrel)", "Nov 21, 2026")))
        val http = wiki("/wiki/List_of_UFC_events" to page, "/wiki/UFC_999_(Kestrel)" to articleOn("UFC_999_(Kestrel)", "2026-11-21"))
        val finder = finder(http)
        assertIs<PageFinder.Found.Article>(finder.find(ufc999))
        assertEquals(1, paths(http).count { it == "/wiki/List_of_UFC_events" })

        now = now.plus(Duration.ofHours(23))
        assertIs<PageFinder.Found.Article>(finder.find(ufc999))
        // A finder made again (a new session) reads the kept rows.
        assertIs<PageFinder.Found.Article>(finder(http).find(ufc999))
        assertEquals(1, paths(http).count { it == "/wiki/List_of_UFC_events" })
        val kept = Json.parseToJsonElement(File(dir, "ufc-events.json").readText()).jsonObject
        assertEquals(setOf("format", "readAt", "rows"), kept.keys)
        assertEquals(setOf("article", "text", "date"), kept.getValue("rows").jsonArray.single().jsonObject.keys)
        assertEquals(listOf("ufc-events.json"), dir.list()!!.toList())

        now = now.plus(Duration.ofHours(1))
        assertIs<PageFinder.Found.Article>(finder.find(ufc999))
        assertEquals(2, paths(http).count { it == "/wiki/List_of_UFC_events" })
    }

    @Test
    fun `an index too large at 8 MB fails the read, keeps nothing, and is asked again next time`() = runTest {
        val http = RecordingHttp { request ->
            if (request.url.endsWith("/List_of_UFC_events")) noAnswer(PluginHttpException.Kind.TooLarge) else answer(404)
        }
        val finder = finder(http)
        assertEquals(PageFinder.Found.Failed("TooLarge"), finder.find(ufc999))
        assertEquals(8 * 1024 * 1024, http.requests.last().maxBytes)
        assertEquals(emptyList(), dir.list()!!.toList())
        assertEquals(PageFinder.Found.Failed("TooLarge"), finder.find(ufc999))
        assertEquals(2, paths(http).count { it == "/wiki/List_of_UFC_events" })
    }

    @Test
    fun `forgetting the index deletes its copy and asks for it again`() = runTest {
        val page = index(scheduled = emptyList(), past = listOf(IndexEntry("UFC 999: Quessel vs. Halde", "/wiki/UFC_999_(Kestrel)", "Nov 21, 2026")))
        val http = wiki("/wiki/List_of_UFC_events" to page, "/wiki/UFC_999_(Kestrel)" to articleOn("UFC_999_(Kestrel)", "2026-11-21"))
        val finder = finder(http)
        finder.find(ufc999)
        File(dir, "ufc-events.json.tmp").writeText("torn")
        finder.forget()
        assertEquals(emptyList(), dir.list()!!.toList())
        finder.find(ufc999)
        assertEquals(2, paths(http).count { it == "/wiki/List_of_UFC_events" })
    }

    private companion object {
        /** A loopback base the recording HTTP answers for; nothing listens there. */
        const val BASE = "http://127.0.0.1:1868"
    }
}
