package app.nzstreamer.plugin.sportarr.bouts

import app.nzstreamer.plugin.sportarr.bouts.Fixtures.UFC_999_CARD
import app.nzstreamer.plugin.sportarr.bouts.Fixtures.article
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L-B5's cards.json: format 1, one entry per event with when it was read and what it said, the card where one was found;
 * written whole through cards.json.tmp; read tolerantly; at most a hundred entries, the oldest read dropped first; a failed
 * read never kept. And W6, as L-B13 splits it, the kept cards' part: a card read before the event and after it, its results
 * in, is kept as the same bytes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeptCardsTest {
    @TempDir
    lateinit var dir: File

    private val kept get() = KeptCards(dir, EmptyCoroutineContext)
    private val at = Instant.parse("2026-11-22T12:00:00Z")

    private val cards = mapOf(
        "ev-000031" to KeptCard(at, CardRead.Found(UFC_999_CARD)),
        "ev-000032" to KeptCard(at.plusSeconds(60), CardRead.NotYet),
        "local:7" to KeptCard(at.minusSeconds(3600), CardRead.NoCard),
    )

    @Test
    fun `cards written read back the same`() = runBlocking<Unit> {
        assertTrue(kept.write(cards))
        assertEquals(cards, kept.read())
        assertEquals(listOf(KeptCards.NAME), dir.list()!!.toList(), "written whole, then renamed")
        assertTrue(kept.write(emptyMap()))
        assertEquals(emptyMap(), kept.read())
    }

    @Test
    fun `a missing, torn, unknown or unreadable file is none, and an entry of an unknown kind is left out`() = runBlocking<Unit> {
        assertEquals(emptyMap(), kept.read())
        val file = File(dir, KeptCards.NAME)
        listOf("""{"format":1,"cards":[{"event":"ev-1"""", "[]", """{"format":2,"cards":[]}""", "").forEach { written ->
            file.writeText(written)
            assertEquals(emptyMap(), kept.read(), written)
        }
        file.writeText(
            """{"format":1,"cards":[{"event":"ev-1","readAt":"2026-11-22T12:00:00Z","state":"result","card":null},""" +
                """{"event":"ev-2","readAt":"2026-11-22T12:00:00Z","state":"found"},""" +
                """{"event":"ev-3","readAt":"2026-11-22T12:00:00Z","state":"no-card","extra":1}]}""",
        )
        assertEquals(mapOf("ev-3" to KeptCard(at, CardRead.NoCard)), kept.read())
        file.delete()
        file.mkdirs()
        assertEquals(emptyMap(), kept.read())
    }

    @Test
    fun `a hundred cards are kept at most, the oldest read dropped first`() = runBlocking<Unit> {
        val many = (1..101).associate { "ev-$it" to KeptCard(at.plusSeconds(it.toLong()), CardRead.NotYet) }
        kept.write(many)
        val read = kept.read()
        assertEquals(100, read.size)
        assertFalse("ev-1" in read)
        assertTrue("ev-2" in read && "ev-101" in read)
    }

    @Test
    fun `a failed read is never kept`() = runBlocking<Unit> {
        kept.write(cards + ("ev-9" to KeptCard(at, CardRead.Failed("503"))))
        assertEquals(cards, kept.read())
        assertFalse("503" in File(dir, KeptCards.NAME).readText())
    }

    @Test
    fun `deleting takes the file and a temporary one a write left`() = runBlocking<Unit> {
        kept.write(cards)
        File(dir, "${KeptCards.NAME}.tmp").writeText("torn")
        kept.delete()
        assertEquals(emptyList(), dir.list()!!.toList())
    }

    @Test
    fun `a card read before the event and one read with its results keep the same bytes`() = runTest {
        val files = Form.entries.map { form ->
            val folder = File(dir, form.name)
            val wiki = TestWiki()
            wiki.pages["/wiki/UFC_999"] = article(form = form, heading = if (form == Form.Pre) Heading.Parsoid else Heading.Legacy)
            val clock = { WIKI_START.plusMillis(testScheduler.currentTime) }
            val reads = WikipediaReads(wiki.http, WIKI_BASE, clock, IndexFile(File(folder, "cache"), EmptyCoroutineContext), EmptyCoroutineContext)
            val cards = Cards(this, reads, KeptCards(folder, EmptyCoroutineContext), CardsOption(folder, EmptyCoroutineContext), clock)
            cards.opened(UFC_999_EVENT)
            advanceUntilIdle()
            assertEquals(listOf("/wiki/UFC_999"), wiki.asked, "$form")
            File(folder, KeptCards.NAME).readBytes()
        }
        assertTrue(files[0].contentEquals(files[1]), "${files[0].decodeToString()}\n${files[1].decodeToString()}")
        // The card's words, the time it was read left out (its own colons and zeros are no result).
        val text = files[0].decodeToString().replace(Regex(""""readAt":"[^"]*""""), "")
        Fixtures.RESULT_WORDS.forEach { word -> assertFalse(word in text, "\"$word\" kept") }
    }
}
