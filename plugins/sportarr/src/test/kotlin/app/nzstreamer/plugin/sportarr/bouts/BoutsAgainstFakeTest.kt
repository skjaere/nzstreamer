package app.nzstreamer.plugin.sportarr.bouts

import app.nzstreamer.plugin.sportarr.bouts.WikiFakes.Asked
import app.nzstreamer.plugin.sportarr.bouts.WikiFakes.Companion.FIGHT_NIGHT
import app.nzstreamer.plugin.sportarr.bouts.WikiFakes.Companion.UFC_999
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UFC events' fight cards against mock-seed's fake Wikipedia over loopback (L-B12, L-B13), each read by a session's
 * [Cards] over a folder of its own, through a PluginHttp with the host's rules (it sends its own User-Agent: the host's is
 * checked on a device). The catalogue's two UFC events as a refresh reads them from the fake Sportarr: UFC 999 found at its
 * title in one request, and the fight night, titled as Sportarr titles it, at its number's redirect in two; an article
 * under another title found through List of UFC events, which a second event's read then doesn't ask again; each of the
 * fake's forms and failures as the page says it, a hold lasting until it has passed; and the card read before the event
 * and after it, its winners moved left with "def.", the same card kept in the same bytes.
 */
class BoutsAgainstFakeTest {
    @TempDir
    lateinit var dir: File

    private val fakes = WikiFakes(LocalDate.of(2026, 11, 22))
    private val noon = fakes.noon

    @AfterEach
    fun stop() = fakes.close()

    private fun control(path: String) = fakes.control(path)

    private fun asked(): List<Asked> = fakes.asked()

    /** A session's cards over a folder of its own, [name], the time [clock]'s. */
    private fun CoroutineScope.cards(name: String, clock: () -> Instant = { noon }): Cards = fakes.cards(this, File(dir, name), clock)

    /** What the page of the event [id] says once it has opened and its read has ended. */
    private suspend fun Cards.open(id: Int): CardState? = open(fakes.events.getValue(id))

    /** Try again on the page of the event [id], and what it says once that read has ended. */
    private suspend fun Cards.again(id: Int): CardState? = again(fakes.events.getValue(id))

    @Test
    fun `UFC 999 is found at its title in one request, and the fight night at its number's redirect in two`() = runBlocking<Unit> {
        val cards = cards("both")
        assertEquals(CardState.Bouts(UFC_999_FAKE), cards.open(UFC_999))
        assertEquals(listOf(Asked("/wiki/UFC_999", null, "results", 200)), asked())
        control("reset")
        assertEquals(CardState.Bouts(FIGHT_NIGHT_FAKE), cards.open(FIGHT_NIGHT))
        assertEquals(
            listOf(Asked("/wiki/UFC_Fight_Night_888_Brisk_vs_Loam", null, null, 404), Asked("/wiki/UFC_Fight_Night_888", null, "pre", 200)),
            asked(),
            "Sportarr's title as written, then its number's page, served as Wikipedia serves a redirect",
        )
        // Each kept: the page again asks nothing.
        control("reset")
        assertEquals(CardState.Bouts(UFC_999_FAKE), cards.open(UFC_999))
        assertEquals(CardState.Bouts(FIGHT_NIGHT_FAKE), cards.open(FIGHT_NIGHT))
        assertEquals(emptyList(), asked())
    }

    @Test
    fun `an article under another title is found through List of UFC events, asked once for both events`() = runBlocking<Unit> {
        // Both moved as the index is first read, so the copy kept of its rows links both.
        control("wiki-form?event=$UFC_999&form=moved")
        control("wiki-form?event=$FIGHT_NIGHT&form=moved")
        val cards = cards("moved")
        assertEquals(CardState.Bouts(UFC_999_FAKE.copy(article = "UFC 999 (Tarnholm)")), cards.open(UFC_999))
        assertEquals(
            listOf(
                Asked("/wiki/UFC_999", null, "moved", 404),
                Asked("/wiki/UFC_999:_Quessel_vs._Halde", null, null, 404),
                Asked("/wiki/List_of_UFC_events", null, null, 200),
                Asked("/wiki/UFC_999_(Tarnholm)", null, "moved", 200),
            ),
            asked(),
        )
        control("reset")
        control("wiki-form?event=$FIGHT_NIGHT&form=moved")
        assertEquals(CardState.Bouts(FIGHT_NIGHT_FAKE.copy(article = "UFC on Kestrel: Brisk vs. Loam")), cards.open(FIGHT_NIGHT))
        assertEquals(
            listOf("/wiki/UFC_Fight_Night_888_Brisk_vs_Loam", "/wiki/UFC_Fight_Night_888", "/wiki/UFC_on_Kestrel:_Brisk_vs._Loam"),
            asked().map { it.path },
            "the index's rows kept from the read before, under a day old",
        )
    }

    @Test
    fun `each of the fake's forms reads as the event's page says it, within the budget`() = runBlocking<Unit> {
        val expected = listOf(
            Triple(UFC_999, "results", CardState.Bouts(UFC_999_FAKE) to 1),
            Triple(UFC_999, "pre", CardState.Bouts(UFC_999_FAKE) to 1),
            Triple(UFC_999, "unsplit", CardState.NotYet to 1),
            Triple(UFC_999, "bare", CardState.NoCard to 1),
            Triple(UFC_999, "missing", CardState.NoCard to 3),
            Triple(UFC_999, "moved", CardState.Bouts(UFC_999_FAKE.copy(article = "UFC 999 (Tarnholm)")) to 4),
            Triple(FIGHT_NIGHT, "results", CardState.Bouts(FIGHT_NIGHT_FAKE) to 2),
            Triple(FIGHT_NIGHT, "pre", CardState.Bouts(FIGHT_NIGHT_FAKE) to 2),
            Triple(FIGHT_NIGHT, "unsplit", CardState.NotYet to 2),
            Triple(FIGHT_NIGHT, "bare", CardState.NotYet to 2),
            Triple(FIGHT_NIGHT, "missing", CardState.NotYet to 3),
            Triple(FIGHT_NIGHT, "moved", CardState.Bouts(FIGHT_NIGHT_FAKE.copy(article = "UFC on Kestrel: Brisk vs. Loam")) to 4),
        )
        expected.forEach { (id, form, said) ->
            val (state, requests) = said
            control("reset")
            control("wiki-form?event=$id&form=$form")
            assertEquals(state, cards("$form-$id").open(id), "$form, event $id")
            val asked = asked()
            assertEquals(requests, asked.size, "$form, event $id: $asked")
            assertTrue(asked.all { it.path.startsWith("/wiki/") && it.query == null }, "$form, event $id: $asked")
        }
    }

    @Test
    fun `each of the fake's failures fails the read, and its hold lasts until it has passed`() = runBlocking<Unit> {
        val holds = mapOf(503 to Duration.ofSeconds(30), 429 to Duration.ofSeconds(60), 403 to Duration.ofHours(1))
        holds.forEach { (status, hold) ->
            control("reset")
            control("wiki-fail?status=$status")
            var now = noon
            val cards = cards("failed-$status") { now }
            assertEquals(CardState.CantReach, cards.open(UFC_999), "$status")
            assertEquals(listOf(status), asked().map { it.status })
            assertFalse(File(dir, "failed-$status/data/${KeptCards.NAME}").exists(), "$status: a failed read is never kept")
            control("wiki-fail?on=false")
            // Try again while the hold lasts: nothing asked.
            now = noon.plus(hold).minusMillis(1)
            assertEquals(CardState.CantReach, cards.again(UFC_999), "$status, held")
            assertEquals(1, asked().size, "$status, held")
            now = noon.plus(hold)
            assertEquals(CardState.Bouts(UFC_999_FAKE), cards.again(UFC_999), "$status, once the hold has passed")
            assertEquals(listOf(status, 200), asked().map { it.status })
        }
    }

    @Test
    fun `while the fake holds its answer the page is Reading, then it shows the bouts`() = runBlocking<Unit> {
        control("wiki-hold?on=true")
        val cards = cards("held")
        val event = fakes.events.getValue(UFC_999)
        val read = cards.opened(event)
        // Off this thread, so the read runs meanwhile, until its request waits at the fake.
        assertTrue(withContext(Dispatchers.IO) { fakes.holding.tryAcquire(WAIT_S, TimeUnit.SECONDS) }, "the request reached the fake")
        assertEquals(CardState.Reading, cards.state(event).first())
        control("wiki-hold?on=false")
        read.join()
        assertEquals(CardState.Bouts(UFC_999_FAKE), cards.state(event).first())
        assertEquals(listOf(Asked("/wiki/UFC_999", null, "results", 200)), asked())
    }

    @Test
    fun `a card read before the event and one read with its results, winners on the left, are the same card in the same bytes`() = runBlocking<Unit> {
        listOf(UFC_999 to UFC_999_FAKE, FIGHT_NIGHT to FIGHT_NIGHT_FAKE).forEach { (id, card) ->
            val kept = listOf("pre", "results").map { form ->
                control("reset")
                control("wiki-form?event=$id&form=$form")
                val state = cards("$form-$id").open(id)
                assertEquals(Asked(ARTICLES.getValue(id), null, form, 200), asked().last(), "the article answered in the $form form")
                state to File(dir, "$form-$id/data/${KeptCards.NAME}").readBytes()
            }
            assertEquals(CardState.Bouts(card), kept[0].first)
            assertEquals(kept[0].first, kept[1].first, "event $id")
            assertContentEquals(kept[0].second, kept[1].second, "event $id")
            // The card's words, the time it was read left out (its own colons and zeros are no result).
            val text = kept[1].second.decodeToString().replace(Regex(""""readAt":"[^"]*""""), "")
            Fixtures.RESULT_WORDS.forEach { word -> assertFalse(word in text, "\"$word\" kept for event $id") }
        }
    }

    private companion object {
        /** Where each event's article is answered: at UFC 999's title, and at the redirect of the fight night's number. */
        val ARTICLES = mapOf(UFC_999 to "/wiki/UFC_999", FIGHT_NIGHT to "/wiki/UFC_Fight_Night_888")

        /** How long a request may take to reach the fake's hold. */
        const val WAIT_S = 10L

        /**
         * UFC 999 as the fake Wikipedia's card reads (wikipedia.json): each pair in L-B4's order, the main event as the title
         * names it, every part in the order fought.
         */
        val UFC_999_FAKE = BoutCard(
            "UFC 999",
            listOf(
                BoutPart(
                    "early-prelims",
                    listOf(
                        Bout("Flyweight", "Ambrel", "Savrin", titleFight = false, mainEvent = false),
                        Bout("Women's Strawweight", "Durnay", "Wexmoor", titleFight = false, mainEvent = false),
                        Bout("Welterweight", "Kjelsen", "Tollerup", titleFight = false, mainEvent = false),
                    ),
                ),
                BoutPart(
                    "prelims",
                    listOf(
                        Bout("Bantamweight", "Ferlund", "Tarnoy", titleFight = false, mainEvent = false),
                        Bout("Light Heavyweight", "Grisholm", "Pellicot", titleFight = false, mainEvent = false),
                        Bout("Featherweight", "Kestvik", "Velmar", titleFight = false, mainEvent = false),
                        Bout("Middleweight", "Brenneck", "Marhoff", titleFight = false, mainEvent = false),
                    ),
                ),
                BoutPart(
                    "main-card",
                    listOf(
                        Bout("Heavyweight", "Lindqvar", "Zelinek", titleFight = false, mainEvent = false),
                        Bout("Women's Flyweight", "Ostrem", "Tessmar", titleFight = false, mainEvent = false),
                        Bout("Welterweight", "Rendahl", "Varnholt", titleFight = false, mainEvent = false),
                        Bout("Bantamweight", "Brisk", "Loam", titleFight = true, mainEvent = false),
                        Bout("Lightweight", "Quessel", "Halde", titleFight = true, mainEvent = true),
                    ),
                ),
            ),
        )

        /** The fight night's: its one early prelims bout under Prelims, first, as Sportarr gives it no such part. */
        val FIGHT_NIGHT_FAKE = BoutCard(
            "UFC Fight Night: Brisk vs. Loam",
            listOf(
                BoutPart(
                    "prelims",
                    listOf(
                        Bout("Women's Strawweight", "Ostrem", "Wexmoor", titleFight = false, mainEvent = false),
                        Bout("Flyweight", "Kestvik", "Savrin", titleFight = false, mainEvent = false),
                        Bout("Bantamweight", "Ferlund", "Velmar", titleFight = false, mainEvent = false),
                        Bout("Women's Flyweight", "Durnay", "Tessmar", titleFight = false, mainEvent = false),
                        Bout("Welterweight", "Kjelsen", "Rendahl", titleFight = false, mainEvent = false),
                    ),
                ),
                BoutPart(
                    "main-card",
                    listOf(
                        Bout("Light Heavyweight", "Brenneck", "Pellicot", titleFight = false, mainEvent = false),
                        Bout("Heavyweight", "Lindqvar", "Zelinek", titleFight = false, mainEvent = false),
                        Bout("Middleweight", "Grisholm", "Marhoff", titleFight = false, mainEvent = false),
                        Bout("Featherweight", "Brisk", "Loam", titleFight = false, mainEvent = true),
                    ),
                ),
            ),
        )
    }
}
