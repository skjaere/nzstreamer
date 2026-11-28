package app.nzstreamer.plugin.sportarr.bouts

import app.nzstreamer.plugin.sportarr.Logged
import app.nzstreamer.plugin.sportarr.bouts.Fixtures.UFC_999_CARD
import app.nzstreamer.plugin.sportarr.bouts.Fixtures.article
import app.nzstreamer.plugin.sportarr.model.BOXING
import app.nzstreamer.plugin.sportarr.model.SportsEvent
import app.nzstreamer.plugin.sportarr.model.at
import app.nzstreamer.plugin.sportarr.model.on
import app.nzstreamer.plugin.sportarr.model.sports
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * L-B5's and L-B8's kept cards and switch, in the test's time: a read only as a page opens or on Try again; a kept card
 * shown at once and read again only when it may have changed; one request for a page opened again while its read runs or
 * waits; the switch read before any read, failing closed, and turned off by cancelling the read and deleting every file;
 * nothing read again until a page opens.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardsTest {
    @TempDir
    lateinit var dir: File

    private val data get() = File(dir, "data")
    private val cache get() = File(dir, "cache")
    private val wiki = TestWiki()

    private val ufc999 = UFC_999_EVENT
    private val fightNight = FIGHT_NIGHT_EVENT

    /**
     * A session's cards over [wiki] and the test's folders, the files read and written on [keptIo] and [optionIo]. In the
     * test's own scope, so advancing the test's time runs what they launch, and a throw from them fails the test.
     */
    private fun TestScope.cards(keptIo: CoroutineContext = EmptyCoroutineContext, optionIo: CoroutineContext = EmptyCoroutineContext): Cards {
        val clock = { WIKI_START.plusMillis(testScheduler.currentTime) }
        val reads = WikipediaReads(wiki.http, WIKI_BASE, clock, IndexFile(cache, EmptyCoroutineContext), EmptyCoroutineContext)
        return Cards(this, reads, KeptCards(data, keptIo), CardsOption(data, optionIo), clock)
    }

    private suspend fun Cards.now(event: SportsEvent): CardState? = state(event).first()

    /** Opens [event]'s page and lets its read end. */
    private suspend fun TestScope.open(cards: Cards, event: SportsEvent): CardState? {
        cards.opened(event)
        advanceUntilIdle()
        return cards.now(event)
    }

    @Test
    fun `nothing is read while no page opens, and a page that reads no card never reads`() = runTest {
        val cards = cards()
        advanceTimeBy(48.hours)
        val boxing = sports(BOXING to listOf(on(BOXING, 50, at("2026-11-21T22:00"), "Kestrel Boxing 12: Quessel vs. Halde"))).events.single()
        val premium = ufcEvent(51, "UFC Countdown: Quessel vs. Halde", at("2026-11-21T20:00"))
        listOf(boxing, premium).forEach { event ->
            assertNull(open(cards, event))
        }
        assertEquals(0, wiki.http.requests.size)
        assertFalse(File(data, KeptCards.NAME).exists())
    }

    @Test
    fun `a card found before the start is read again once over an hour old`() = runTest {
        val cards = cards()
        assertIs<CardState.Bouts>(open(cards, fightNight))
        assertEquals(1, wiki.http.requests.size)
        advanceTimeBy(1.hours)
        open(cards, fightNight)
        assertEquals(1, wiki.http.requests.size, "an hour isn't over an hour")
        advanceTimeBy(1.milliseconds)
        open(cards, fightNight)
        assertEquals(2, wiki.http.requests.size)
    }

    @Test
    fun `a card found after the start is read again once over three days old`() = runTest {
        val cards = cards()
        assertEquals(CardState.Bouts(UFC_999_CARD), open(cards, ufc999))
        advanceTimeBy(3.days)
        open(cards, ufc999)
        assertEquals(1, wiki.http.requests.size)
        advanceTimeBy(1.milliseconds)
        open(cards, ufc999)
        assertEquals(2, wiki.http.requests.size)
    }

    @Test
    fun `a card read before the start is read again once the event has started`() = runTest {
        val soon = ufcEvent(34, "UFC 999: Quessel vs. Halde", WIKI_START.plus(Duration.ofMinutes(30)))
        val cards = cards()
        assertIs<CardState.Bouts>(open(cards, soon))
        advanceTimeBy(31.minutes)
        open(cards, soon)
        assertEquals(2, wiki.http.requests.size, "read before the start")
        advanceTimeBy(2.days)
        open(cards, soon)
        assertEquals(2, wiki.http.requests.size, "read after the start, two days ago")
    }

    @Test
    fun `no page is not out yet before the start and no card after, each kept and read again once over an hour old`() = runTest {
        wiki.pages.clear()
        val cards = cards()
        assertEquals(CardState.NotYet, open(cards, fightNight))
        assertEquals(CardState.NoCard, open(cards, ufc999))
        val asked = wiki.http.requests.size
        advanceTimeBy(1.hours)
        open(cards, fightNight)
        open(cards, ufc999)
        assertEquals(asked, wiki.http.requests.size)
        advanceTimeBy(1.milliseconds)
        open(cards, ufc999)
        // Two candidates and the index, which a 404 left uncopied.
        assertEquals(asked + 3, wiki.http.requests.size)
        val kept = File(data, KeptCards.NAME).readText()
        assertTrue("\"not-yet\"" in kept && "\"no-card\"" in kept, kept)
    }

    @Test
    fun `an accepted page with no card is not out yet before the start and no card after`() = runTest {
        wiki.pages["/wiki/UFC_999"] = article(cardTable = null)
        wiki.pages["/wiki/UFC_Fight_Night:_Brisk_vs._Loam"] = fightNightPage().replace(Fixtures.table(Fixtures.UFC_999, Form.Pre), "")
        val cards = cards()
        assertEquals(CardState.NoCard, open(cards, ufc999))
        assertEquals(CardState.NotYet, open(cards, fightNight))
        assertEquals(2, wiki.http.requests.size)
    }

    @Test
    fun `a kept card shows at once, while it is read again and after that read failed`() = runTest {
        open(cards(), ufc999)
        val before = File(data, KeptCards.NAME).readBytes()
        advanceTimeBy(4.days)

        // A session made again: the kept card at once, no Reading, while its read runs and after it fails.
        wiki.latency = 1_000
        wiki.status = 503
        val again = cards()
        again.opened(ufc999)
        runCurrent()
        assertEquals(CardState.Bouts(UFC_999_CARD), again.now(ufc999))
        assertEquals(2, wiki.http.requests.size)
        advanceUntilIdle()
        assertEquals(CardState.Bouts(UFC_999_CARD), again.now(ufc999))
        assertTrue(before.contentEquals(File(data, KeptCards.NAME).readBytes()), "a failed read keeps nothing")
    }

    @Test
    fun `with nothing kept a read shows Reading, then Can't reach where it fails, and Try again reads once, holds allowing`() = runTest {
        wiki.latency = 1_000
        wiki.status = 503
        val cards = cards()
        cards.opened(ufc999)
        runCurrent()
        assertEquals(CardState.Reading, cards.now(ufc999))
        advanceUntilIdle()
        assertEquals(CardState.CantReach, cards.now(ufc999))
        assertFalse(File(data, KeptCards.NAME).exists())

        // Held: Try again fails at once, with no request.
        wiki.status = null
        cards.tryAgain(ufc999)
        advanceTimeBy(10.seconds)
        assertEquals(CardState.CantReach, cards.now(ufc999))
        assertEquals(1, wiki.http.requests.size)

        advanceTimeBy(21.seconds)
        cards.tryAgain(ufc999)
        runCurrent()
        assertEquals(CardState.Reading, cards.now(ufc999))
        advanceUntilIdle()
        assertEquals(CardState.Bouts(UFC_999_CARD), cards.now(ufc999))
        assertEquals(2, wiki.http.requests.size)
    }

    @Test
    fun `a throw outside the reads' own catch fails the read, the kept card staying or Can't reach, and nothing escapes`() = runTest {
        // The cards' own clock throws once armed (the reads keep a clock of their own): when a kept card's age is taken,
        // and when a read's time is.
        var armed = false
        val clock = { WIKI_START.plusMillis(testScheduler.currentTime) }
        val reads = WikipediaReads(wiki.http, WIKI_BASE, clock, IndexFile(cache, EmptyCoroutineContext), EmptyCoroutineContext)
        val cardsClock = { if (armed) throw IllegalStateException("No clock at /data/kestrel") else clock() }
        val cards = Cards(this, reads, KeptCards(data, EmptyCoroutineContext), CardsOption(data, EmptyCoroutineContext), cardsClock)
        assertIs<CardState.Bouts>(open(cards, fightNight))
        val kept = File(data, KeptCards.NAME).readBytes()

        armed = true
        val mark = Logged.mark()
        advanceTimeBy(2.hours)
        assertIs<CardState.Bouts>(open(cards, fightNight), "the kept card, as it was")
        assertEquals(1, wiki.http.requests.size)
        assertEquals(CardState.CantReach, open(cards, ufc999), "nothing kept")
        assertEquals(2, wiki.http.requests.size)
        assertTrue(kept.contentEquals(File(data, KeptCards.NAME).readBytes()), "nothing kept of the failed read")
        assertTrue(isActive)
        val lines = Logged.pluginLinesSince(mark).map { it.words }
        assertEquals(2, lines.count { "IllegalStateException" in it }, "$lines")
        assertFalse(lines.any { "kestrel" in it || "/data" in it }, "$lines")
    }

    @Test
    fun `a page opened again while its read waits for another's makes one request`() = runTest {
        wiki.latency = 1_000
        val cards = cards()
        cards.opened(ufc999)
        runCurrent()
        val waiting = cards.opened(fightNight)
        assertSame(waiting, cards.opened(fightNight))
        assertSame(waiting, cards.tryAgain(fightNight))
        assertSame(waiting, cards.opened(fightNight))
        runCurrent()
        assertEquals(CardState.Reading, cards.now(fightNight))
        advanceUntilIdle()
        assertEquals(listOf("/wiki/UFC_999", "/wiki/UFC_Fight_Night:_Brisk_vs._Loam"), wiki.asked)
        assertEquals(1, wiki.http.mostAtOnce)
        open(cards, fightNight)
        open(cards, ufc999)
        assertEquals(2, wiki.http.requests.size)
    }

    @Test
    fun `switching off cancels the read in flight, deletes every file it kept, and says Off with no request`() = runTest {
        val cards = cards()
        assertIs<CardState.Bouts>(open(cards, fightNight))
        File(data, "${KeptCards.NAME}.tmp").writeText("torn")
        cache.mkdirs()
        File(cache, "ufc-events.json").writeText("{}")
        File(cache, "ufc-events.json.tmp").writeText("torn")

        // A read of several requests (no article: both candidates, then the index), switched off while its first is out.
        wiki.pages.clear()
        wiki.latency = 1_000
        val mark = Logged.mark()
        cards.opened(ufc999)
        runCurrent()
        assertEquals(2, wiki.http.requests.size)
        cards.setOn(false)
        advanceUntilIdle()
        assertEquals(listOf("/wiki/UFC_Fight_Night:_Brisk_vs._Loam", "/wiki/UFC_999"), wiki.asked, "the read's other requests never sent")
        assertEquals(emptyList(), Logged.pluginLinesSince(mark).map { it.words }.filter { it.startsWith("Fight card") }, "its answer dropped")
        assertEquals(listOf(CardsOption.NAME), data.list()!!.toList())
        assertEquals(emptyList(), cache.list()!!.toList())
        assertEquals(CardState.Off, cards.now(ufc999))
        assertEquals(CardState.Off, cards.now(fightNight))
        assertEquals("""{"format":1,"fightCards":false}""", File(data, CardsOption.NAME).readText())

        assertEquals(CardState.Off, open(cards, ufc999))
        assertEquals(CardState.Off, open(cards, fightNight))
        assertEquals(2, wiki.http.requests.size)
        assertEquals(false, cards.switch.value)
    }

    @Test
    fun `the holds stay through the switch turned off and on again, however often`() = runTest {
        wiki.status = 403
        val cards = cards()
        assertEquals(CardState.CantReach, open(cards, ufc999))
        assertEquals(1, wiki.http.requests.size)
        wiki.status = null
        // Off and on again, as a remote's repeated presses could: the 403's hour still holds every read.
        repeat(3) {
            cards.setOn(false)
            advanceUntilIdle()
            cards.setOn(true)
            advanceUntilIdle()
        }
        advanceTimeBy(1.hours - 1.milliseconds)
        assertEquals(CardState.CantReach, open(cards, ufc999), "held")
        assertEquals(1, wiki.http.requests.size)
        advanceTimeBy(1.milliseconds)
        assertEquals(CardState.Bouts(UFC_999_CARD), open(cards, ufc999))
        assertEquals(2, wiki.http.requests.size)
    }

    @Test
    fun `switching off while a card is being written leaves no file`() = runTest {
        val io = ManualDispatcher()
        val cards = cards(keptIo = io)
        cards.opened(ufc999)
        repeat(3) {
            runCurrent()
            if (wiki.http.requests.isEmpty()) io.runAll()
        }
        assertEquals(1, wiki.http.requests.size)
        assertEquals(1, io.queue.size, "the card's write waits to run")

        cards.setOn(false)
        repeat(5) {
            runCurrent()
            io.runAll()
        }
        advanceUntilIdle()
        assertFalse(File(data, KeptCards.NAME).exists())
        assertFalse(File(data, "${KeptCards.NAME}.tmp").exists())
        assertEquals(CardState.Off, cards.now(ufc999))
    }

    @Test
    fun `switched on again, nothing is read until a page opens, and that read finds nothing kept`() = runTest {
        val cards = cards()
        open(cards, ufc999)
        cards.setOn(false)
        advanceUntilIdle()
        cards.setOn(true)
        advanceTimeBy(1.hours)
        assertEquals(1, wiki.http.requests.size)
        assertNull(cards.now(ufc999), "nothing shown until the page opens")
        assertEquals("""{"format":1,"fightCards":true}""", File(data, CardsOption.NAME).readText())

        cards.opened(ufc999)
        runCurrent()
        assertEquals(CardState.Bouts(UFC_999_CARD), cards.now(ufc999))
        assertEquals(2, wiki.http.requests.size)
    }

    @Test
    fun `a page opened while the switch loads shows none and reads nothing until it is read, and nothing when it is off`() = runTest {
        val io = ManualDispatcher()
        val cards = cards(optionIo = io)
        cards.opened(ufc999)
        runCurrent()
        assertNull(cards.now(ufc999))
        assertNull(cards.switch.value)
        assertEquals(0, wiki.http.requests.size)
        io.runAll()
        advanceUntilIdle()
        assertEquals(CardState.Bouts(UFC_999_CARD), cards.now(ufc999))
        assertEquals(1, wiki.http.requests.size)

        data.mkdirs()
        File(data, CardsOption.NAME).writeText("""{"format":1,"fightCards":false}""")
        val off = cards(optionIo = io)
        off.opened(fightNight)
        runCurrent()
        assertNull(off.now(fightNight))
        io.runAll()
        advanceUntilIdle()
        assertEquals(CardState.Off, off.now(fightNight))
        assertEquals(1, wiki.http.requests.size)
    }

    @Test
    fun `an unreadable switch is off, and switching it on writes it anew`() = runTest {
        data.mkdirs()
        File(data, CardsOption.NAME).writeText("""{"format":1,"fightCards":tr""")
        val cards = cards()
        assertEquals(CardState.Off, open(cards, ufc999))
        assertEquals(0, wiki.http.requests.size)
        cards.setOn(true)
        advanceUntilIdle()
        assertEquals("""{"format":1,"fightCards":true}""", File(data, CardsOption.NAME).readText())
        assertIs<CardState.Bouts>(open(cards, ufc999))
    }

    @Test
    fun `the settings page reads the switch without opening a page`() = runTest {
        val cards = cards()
        assertNull(cards.switch.value)
        cards.readSwitch()
        advanceUntilIdle()
        assertEquals(true, cards.switch.value)
        assertEquals(0, wiki.http.requests.size)
        assertFalse(File(data, CardsOption.NAME).exists(), "reading the switch writes nothing")
    }
}
