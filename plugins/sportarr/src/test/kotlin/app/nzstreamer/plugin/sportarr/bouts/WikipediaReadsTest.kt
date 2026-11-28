package app.nzstreamer.plugin.sportarr.bouts

import app.nzstreamer.plugin.sportarr.Logged
import app.nzstreamer.plugin.sportarr.bouts.Fixtures.UFC_999_CARD
import app.nzstreamer.plugin.sportarr.bouts.Fixtures.article
import app.nzstreamer.plugin.sportarr.bouts.Fixtures.dateCell
import app.nzstreamer.plugin.sportarr.bouts.Fixtures.index
import app.nzstreamer.plugin.sportarr.model.SportsEvent
import app.nzstreamer.plugin.sportarr.model.UFC
import app.nzstreamer.plugin.sportarr.model.at
import app.nzstreamer.plugin.sportarr.model.on
import app.nzstreamer.plugin.sportarr.model.sports
import app.nzstreamer.plugin.sportarr.source.RecordingHttp
import app.nzstreamer.plugin.sportarr.source.answer
import app.nzstreamer.plugin.sportarr.source.noAnswer
import io.skjaere.usenet.plugin.HttpAnswer
import io.skjaere.usenet.plugin.PluginHttpException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * L-B6's reads, in the test's time: one at a time, five requests at most, no header; each answer's meaning and the holds a
 * 429, a 5xx or a 403 asks for, a held read failing with no request; a request with no answer a failed read; and nothing
 * escaping a read, whatever its page or its files throw: a page that can't be read holds no card, anything else fails
 * the read, and the line logged names the exception's class alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WikipediaReadsTest {
    @TempDir
    lateinit var dir: File

    /** UFC 999, a day before the test's start: after its start. */
    private val ufc999 = event(31, "UFC 999: Quessel vs. Halde", at("2026-11-21T23:00"))

    /** A fight night in six days: before its start. */
    private val fightNight = event(32, "UFC Fight Night: Brisk vs. Loam", at("2026-11-28T01:00"))

    private val night888 = event(33, "UFC Fight Night 888 Brisk vs Loam", at("2026-11-28T01:00"))

    private fun event(id: Int, title: String, start: Instant): SportsEvent = sports(UFC to listOf(on(UFC, id, start, title))).events.single()

    private fun TestScope.now(): Instant = START.plusMillis(testScheduler.currentTime)

    private fun TestScope.reads(
        http: RecordingHttp,
        index: IndexCopy = IndexFile(dir, EmptyCoroutineContext),
        reader: (String, String, String, List<String>) -> BoutTable.Read = BoutTable::read,
    ) = WikipediaReads(http, BASE, { now() }, index, EmptyCoroutineContext, reader)

    /** Every request answered [status] with [headers]. */
    private fun answering(status: Int, headers: Map<String, String> = emptyMap()) =
        RecordingHttp { HttpAnswer(status, headers, "<p>Busy</p>".toByteArray()) }

    /** Every request answered 404: no article anywhere. */
    private fun nowhere() = RecordingHttp { answer(404) }

    /** UFC 999's article under its number; any other path a 404. */
    private fun wiki(vararg pages: Pair<String, String> = arrayOf("/wiki/UFC_999" to article())): RecordingHttp {
        val byPath = pages.toMap()
        return RecordingHttp { request -> byPath[request.url.removePrefix(BASE)]?.let { answer(200, it) } ?: answer(404) }
    }

    @Test
    fun `UFC 999's card is read from its article in one request, with no header`() = runTest {
        val http = wiki()
        assertEquals(CardRead.Found(UFC_999_CARD), reads(http).read(ufc999))
        assertEquals(1, http.requests.size)
        assertEquals(emptyMap(), http.requests.single().headers)
    }

    @Test
    fun `a read makes five requests at most, every candidate missed, the index and its row's article`() = runTest {
        listOf(ufc999, fightNight, night888).forEach { event ->
            val page = index(scheduled = emptyList(), past = listOf(IndexEntry(event.title, "/wiki/Kestrel_card", "Nov 28, 2026"), IndexEntry(event.title, "/wiki/Tarnholm_card", "Nov 21, 2026")))
            val http = wiki("/wiki/List_of_UFC_events" to page)
            reads(http, IndexFile(File(dir, "${event.id}"), EmptyCoroutineContext)).read(event)
            assertEquals(PageFinder.candidates(event.title).size + 2, http.requests.size, event.title)
            assertTrue(http.requests.size <= 5)
            assertTrue(http.requests.all { it.headers.isEmpty() && '?' !in it.url })
        }
    }

    @Test
    fun `a 429 holds every request for its Retry-After in seconds, capped at an hour, or 60 s without one`() = runTest {
        listOf(mapOf("retry-after" to "120") to 120, mapOf("retry-after" to "7200") to 3600, emptyMap<String, String>() to 60, mapOf("retry-after" to "soon") to 60)
            .forEach { (headers, seconds) -> heldFor(answering(429, headers), seconds, "429") }
    }

    @Test
    fun `a 429's Retry-After may be an HTTP date`() = runTest {
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(now().plusSeconds(300).atOffset(ZoneOffset.UTC))
        heldFor(answering(429, mapOf("retry-after" to date)), 300, "429")
    }

    @Test
    fun `a 429's Retry-After may be in either of the older HTTP date forms, which a recipient must still read`() = runTest {
        /** The time [seconds] from now, written as [pattern] writes it. */
        fun ahead(seconds: Long, pattern: String) = DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).format(now().plusSeconds(seconds).atOffset(ZoneOffset.UTC))
        // rfc850-date ("Sunday, 22-Nov-26 12:05:00 GMT") and asctime-date ("Sun Nov 22 12:05:00 2026").
        heldFor(answering(429, mapOf("retry-after" to ahead(300, "EEEE, dd-MMM-yy HH:mm:ss 'GMT'"))), 300, "429")
        heldFor(answering(429, mapOf("retry-after" to ahead(240, "EEE MMM ppd HH:mm:ss yyyy"))), 240, "429")
        // asctime's day of one digit comes after two spaces; a day nine days on is held for the hour at most.
        heldFor(answering(429, mapOf("retry-after" to "Tue Dec  1 12:00:00 2026")), 3600, "429")
        // A two-digit year more than fifty years on is the last such year gone by: a time past, which holds nothing.
        val http = answering(429, mapOf("retry-after" to "Saturday, 01-Jan-94 00:00:00 GMT"))
        val reads = reads(http)
        assertEquals(CardRead.Failed("429"), reads.read(ufc999))
        reads.read(ufc999)
        assertEquals(2, http.requests.size, "a time in 1994 holds nothing")
    }

    @Test
    fun `a 5xx holds for 30 s, or its Retry-After when longer, and a 403 for an hour`() = runTest {
        heldFor(answering(503), 30, "503")
        heldFor(answering(500, mapOf("retry-after" to "90")), 90, "500")
        heldFor(answering(502, mapOf("retry-after" to "10")), 30, "502")
        heldFor(answering(403), 3600, "403")
    }

    /** A read answered by [http] fails with [why] and holds every read for [seconds]: none of them makes a request. */
    private suspend fun TestScope.heldFor(http: RecordingHttp, seconds: Int, why: String) {
        val reads = reads(http)
        assertEquals(CardRead.Failed(why), reads.read(ufc999))
        assertEquals(1, http.requests.size)
        advanceTimeBy(seconds * 1000L - 1)
        runCurrent()
        assertEquals(CardRead.Failed("held"), reads.read(ufc999))
        assertEquals(CardRead.Failed("held"), reads.read(fightNight))
        assertEquals(1, http.requests.size, "held for ${seconds}s")
        advanceTimeBy(1)
        runCurrent()
        reads.read(ufc999)
        assertEquals(2, http.requests.size, "free after ${seconds}s")
    }

    @Test
    fun `a request with no answer, or another status, is a failed read and holds nothing`() = runTest {
        PluginHttpException.Kind.entries.forEach { kind ->
            val http = RecordingHttp { noAnswer(kind) }
            val reads = reads(http)
            assertEquals(CardRead.Failed(kind.name), reads.read(ufc999))
            assertEquals(1, http.requests.size)
            reads.read(ufc999)
            assertEquals(2, http.requests.size, "$kind holds nothing")
        }
        val http = answering(410)
        assertEquals(CardRead.Failed("410"), reads(http).read(ufc999))
    }

    @Test
    fun `two reads asked at once run one after the other`() = runTest {
        val http = RecordingHttp { request ->
            delay(500)
            if (request.url.endsWith("/UFC_999")) answer(200, article()) else answer(404)
        }
        val reads = reads(http)
        val first = backgroundScope.async { reads.read(ufc999) }
        val second = backgroundScope.async { reads.read(fightNight) }
        runCurrent()
        assertEquals(1, http.requests.size)
        advanceTimeBy(501.milliseconds)
        runCurrent()
        assertEquals(CardRead.Found(UFC_999_CARD), first.await())
        assertEquals(CardRead.NotYet, second.await())
        assertEquals(1, http.mostAtOnce)
        assertEquals(listOf("/wiki/UFC_999", "/wiki/UFC_Fight_Night:_Brisk_vs._Loam", "/wiki/List_of_UFC_events"), http.requests.map { it.url.removePrefix(BASE) })
    }

    @Test
    fun `no page, or an accepted page with no card, is not out yet before the start and no card after`() = runTest {
        val nightPage = article(canonical = "https://en.wikipedia.org/wiki/UFC_Fight_Night:_Brisk_vs._Loam", date = dateCell("November 28, 2026", "2026-11-28"), cardTable = null)
        val reads = reads(wiki("/wiki/UFC_999" to article(cardTable = null), "/wiki/UFC_Fight_Night:_Brisk_vs._Loam" to nightPage))
        assertEquals(CardRead.NoCard, reads.read(ufc999))
        assertEquals(CardRead.NotYet, reads.read(fightNight))
        assertEquals(CardRead.NotYet, reads(nowhere()).read(fightNight))
        assertEquals(CardRead.NoCard, reads(nowhere()).read(ufc999))
        // A card not split into parts is not out yet, whenever it is read.
        val unsplit = article(cardTable = Fixtures.rows(Fixtures.header("Fight card"), Fixtures.vs("Lightweight", "Quessel", "Halde")))
        assertEquals(CardRead.NotYet, reads(wiki("/wiki/UFC_999" to unsplit)).read(ufc999))
    }

    @Test
    fun `a truncated page, a table nested deep and a reader that throws hold no card, and nothing escapes`() = runTest {
        val whole = article()
        val truncated = whole.substring(0, whole.indexOf("<h2 id=\"Fight_card\"") + 10)
        val deep = article(cardTable = "<table><tr><td>".repeat(1000) + Fixtures.table(Fixtures.UFC_999, Form.Pre) + "</td></tr></table>".repeat(1000))
        listOf(truncated, deep).forEach { page ->
            assertEquals(CardRead.NoCard, backgroundScope.async { reads(wiki("/wiki/UFC_999" to page)).read(ufc999) }.await())
        }
        val mark = Logged.mark()
        val throwing = reads(wiki()) { _, _, _, _ -> throw IllegalStateException("Quessel vs. Halde at UFC 999") }
        assertEquals(CardRead.NoCard, backgroundScope.async { throwing.read(ufc999) }.await())
        // Before the event's start it is not out yet, as any page with no card is.
        val beforeStart = reads(wiki("/wiki/UFC_Fight_Night:_Brisk_vs._Loam" to fightNightPage())) { _, _, _, _ -> throw IllegalStateException("Brisk vs. Loam") }
        assertEquals(CardRead.NotYet, backgroundScope.async { beforeStart.read(fightNight) }.await())
        assertTrue(backgroundScope.isActive)
        val lines = Logged.pluginLinesSince(mark).map { it.words }
        assertTrue(lines.any { "IllegalStateException" in it }, "$lines")
        listOf("Quessel", "Halde", "Brisk", "Loam", "999", BASE, "wiki").forEach { word -> assertFalse(lines.any { word in it }, "$word in $lines") }
    }

    @Test
    fun `a throw in the file work fails the read, and nothing escapes`() = runTest {
        val failing = object : IndexCopy {
            override suspend fun read(): KeptIndex = throw IllegalStateException("No space at /data/kestrel")

            override suspend fun write(index: KeptIndex) = throw IllegalStateException("No space at /data/kestrel")

            override suspend fun delete() = Unit
        }
        val mark = Logged.mark()
        val http = nowhere()
        assertEquals(CardRead.Failed("an error"), backgroundScope.async { reads(http, failing).read(ufc999) }.await())
        assertTrue(backgroundScope.isActive)
        val lines = Logged.pluginLinesSince(mark).map { it.words }
        assertTrue(lines.any { "IllegalStateException" in it }, "$lines")
        assertFalse(lines.any { "kestrel" in it || "/data" in it }, "$lines")
        // The read after it reads again.
        reads(http, failing).read(ufc999)
        assertEquals(4, http.requests.size)
    }

    @Test
    fun `each read logs its outcome, its counts and its time, and nothing it read`() = runTest {
        val mark = Logged.mark()
        reads(nowhere()).read(ufc999)
        reads(wiki("/wiki/UFC_999" to article())).read(ufc999)
        reads(answering(503)).read(ufc999)
        val lines = Logged.pluginLinesSince(mark).map { it.words.replace(Regex("""\d+ ms"""), "N ms") }
        assertEquals(
            listOf(
                "Fight card read: no card, 3 requests in N ms",
                "Fight card read: found, 13 bouts in 3 parts, 1 requests in N ms",
                "Fight card not read: 503 after 1 requests in N ms",
            ),
            lines,
        )
    }

    private companion object {
        val START: Instant = Instant.parse("2026-11-22T12:00:00Z")

        /** A loopback base the recording HTTP answers for; nothing listens there. */
        const val BASE = "http://127.0.0.1:1868"
    }
}
