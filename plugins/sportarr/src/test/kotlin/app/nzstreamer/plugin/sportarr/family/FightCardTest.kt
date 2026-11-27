package app.nzstreamer.plugin.sportarr.family

import app.nzstreamer.plugin.sportarr.match.Nouns
import app.nzstreamer.plugin.sportarr.match.PartPicker
import app.nzstreamer.plugin.sportarr.match.RejectKind
import app.nzstreamer.plugin.sportarr.match.SharedRules
import app.nzstreamer.plugin.sportarr.match.Verdict
import app.nzstreamer.plugin.sportarr.model.BOXING
import app.nzstreamer.plugin.sportarr.model.CardType
import app.nzstreamer.plugin.sportarr.model.Family
import app.nzstreamer.plugin.sportarr.model.Parts
import app.nzstreamer.plugin.sportarr.model.Sports
import app.nzstreamer.plugin.sportarr.model.SportsEvent
import app.nzstreamer.plugin.sportarr.model.UFC
import app.nzstreamer.plugin.sportarr.model.VELMORA
import app.nzstreamer.plugin.sportarr.model.at
import app.nzstreamer.plugin.sportarr.model.on
import app.nzstreamer.plugin.sportarr.model.sports
import app.nzstreamer.plugin.sportarr.model.ufc999
import app.nzstreamer.plugin.sportarr.source.KeptLeague
import app.nzstreamer.plugin.sportarr.source.SportsList
import app.nzstreamer.plugin.sportarr.source.WireEvent
import io.skjaere.usenet.plugin.FileInfo
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * L650's fight cards, each judged by the type its list gave it (L643): a numbered card by its organisation and number, a
 * fight night by its organisation, Fight Night or both surnames and its date, or by its own number (L-SF6), either by its
 * organisation and both surnames with no date unless a rematch is listed (L-SF7), a boxing matchup by both surnames and
 * its date, a weekly show by its title's words on its own date, a premium event by its title's words with its year or
 * edition and its night. Then a card's parts by their words: an unlabelled name or PPV is the main part, never a prelim,
 * and a full event is every part's. What names a card less surely is a possible match with its reason; another card's
 * number, night, year or names leave a name out. Every name goes through the shared rules first, as the runner judges it.
 */
class FightCardTest {
    /** A Monday night show, at one in the morning UTC on Tuesday; [broadcast] the date Sportarr sends, the UTC date where it has none. */
    private fun brawl(id: Int, start: String, broadcast: String): WireEvent =
        on(VELMORA, id, at(start), "Velmora Monday Brawl").copy(broadcastDate = LocalDate.parse(broadcast))

    /** Three weeks of the show: the first two with their local dates, the third with Sportarr's fallback, its UTC date. */
    private val brawls = listOf(
        brawl(51, "2026-11-07T01:00", broadcast = "2026-11-06"),
        brawl(52, "2026-11-14T01:00", broadcast = "2026-11-13"),
        brawl(53, "2026-11-21T01:00", broadcast = "2026-11-21"),
    )

    private val list = sports(
        UFC to listOf(ufc999(), on(UFC, 32, at("2026-11-28T19:00"), "UFC Fight Night: Brisk vs. Loam", parts = listOf("Prelims", "Main Card"))),
        VELMORA to brawls + listOf(
            on(VELMORA, 54, at("2026-11-11T23:00"), "Velmora Iron Crown"),
            on(VELMORA, 55, at("2026-11-12T23:00"), "Velmora Iron Crown: Night 2", parts = listOf("Countdown", "Main Show")),
        ),
        BOXING to listOf(on(BOXING, 61, at("2026-11-18T20:00"), "Halde vs. Brisk")),
    )
    private val cards = FightCard(list)

    private fun Sports.byId(id: Int) = events.single { it.id == id }

    private val card = list.byId(31)
    private val fightNight = list.byId(32)
    private val crown = list.byId(54)
    private val nightTwo = list.byId(55)
    private val bout = list.byId(61)

    private val accepted = Verdict.Accept()
    private val otherParts = Verdict.Reject(RejectKind.OtherParts)
    private val shows = Verdict.Reject(RejectKind.Shows)

    /** Another card's, fight's or show's, as its event's noun words it. */
    private val other = Verdict.Reject(RejectKind.OtherEvents)

    private fun offered(reason: String) = Verdict.Offer(reason)

    private fun judge(event: SportsEvent, part: String, name: String, posted: Instant? = null, matcher: FightCard = cards): Verdict =
        SharedRules.judge(matcher, event, event.parts.single { it.id == part }, name, posted, null)

    /** Each row's name judged for each part it names a verdict for; those that disagree, with what they got. */
    private fun wrong(event: SportsEvent, rows: List<Pair<String, Map<String, Verdict>>>, matcher: FightCard = cards): String =
        rows.flatMap { (name, verdicts) ->
            verdicts.mapNotNull { (part, expected) ->
                judge(event, part, name, matcher = matcher).takeIf { it != expected }?.let { "$name for $part: $it, not $expected" }
            }
        }.joinToString("\n")

    private val numberedParts = listOf(Parts.EARLY_PRELIMS, Parts.PRELIMS, Parts.MAIN_CARD)

    /** A numbered card's verdicts: [verdict] for [id], another part for the rest. */
    private fun only(id: String, verdict: Verdict = accepted): Map<String, Verdict> = numberedParts.associateWith { if (it == id) verdict else otherParts }

    private fun every(verdict: Verdict, parts: List<String> = numberedParts): Map<String, Verdict> = parts.associateWith { verdict }

    private fun full(verdict: Verdict) = mapOf(Parts.FULL to verdict)

    private fun show(main: Verdict, pre: Verdict) = mapOf(Parts.MAIN_SHOW to main, Parts.PRE_SHOW to pre)

    @Test
    fun `the cards are typed as their list gives them`() {
        assertEquals(CardType.Numbered, card.cardType)
        assertEquals(CardType.FightNight, fightNight.cardType)
        assertEquals(listOf(CardType.WeeklyShow), (51..53).map { list.byId(it).cardType }.distinct())
        assertEquals(listOf(CardType.Premium, CardType.Premium), listOf(crown.cardType, nightTwo.cardType))
        assertEquals(CardType.Matchup, bout.cardType)
        assertEquals(listOf("Countdown", "Main Show"), nightTwo.parts.map { it.label })
    }

    @Test
    fun `a numbered card's parts are named by their words, each its own part's and never another's`() {
        val rows = listOf(
            "UFC.999.Early.Prelims.1080p.WEB.h264-KESTREL" to only(Parts.EARLY_PRELIMS),
            "UFC999.Early.Prelim.720p.WEB.h264-WREN" to only(Parts.EARLY_PRELIMS),
            "UFC.999.Prelims.1080p.WEB.h264-KESTREL" to only(Parts.PRELIMS),
            "UFC.999.Preliminary.Card.720p.WEB.h264-ORCHID" to only(Parts.PRELIMS),
            "UFC.999.Preliminaries.720p.WEB.h264-ORCHID" to only(Parts.PRELIMS),
            "UFC.999.Undercard.720p.WEB.h264-ORCHID" to only(Parts.PRELIMS),
            "UFC.999.Main.Card.1080p.WEB.h264-KESTREL" to only(Parts.MAIN_CARD),
            "UFC.999.Maincard.1080p.WEB.h264-WREN" to only(Parts.MAIN_CARD),
        )
        assertEquals("", wrong(card, rows))
    }

    @Test
    fun `999 is never 9990, 99 or 1000, and a fight night is another card, even on the card's date`() {
        val rows = listOf(
            "UFC.9990.Prelims.1080p.WEB.h264-WREN",
            "UFC9990.Quessel.vs.Halde.1080p.WEB.h264-WREN",
            "UFC.1000.Main.Card.720p.WEB.h264-ORCHID",
            "UFC.Fight.Night.999.1080p.WEB.h264-DRIFTWOOD",
            "UFC.Fight.Night.999.Prelims.1080p.WEB.h264-DRIFTWOOD",
            // On the card's own date, with the league's word, still another card's.
            "UFC.1000.2026.11.21.Main.Card.1080p.WEB.h264-WREN",
            "UFC.Fight.Night.2026.11.21.1080p.WEB.h264-WREN",
        ).map { it to every(other) }
        assertEquals("", wrong(card, rows))
        assertEquals("other cards", RejectKind.OtherEvents.words(Nouns.event(card)))

        // A number inside the card's: 99 of 999, on the invented league's card, where no such card was ever held.
        val kcl = KeptLeague(20, "KCL", "Fighting")
        val kclList = sports(kcl to listOf(on(kcl, 74, at("2026-11-21T20:00"), "KCL 999: Quessel vs. Halde", parts = listOf("Early Prelims", "Prelims", "Main Card"))))
        val kclCard = kclList.byId(74)
        val kclRows = listOf("KCL.99.Main.Card.1080p.WEB.h264-WREN" to every(other), "KCL.999.Main.Card.1080p.WEB.h264-WREN" to only(Parts.MAIN_CARD))
        assertEquals("", wrong(kclCard, kclRows, FightCard(kclList)))
    }

    @Test
    fun `an unlabelled name or PPV is the main card and never a prelim, and the prelims are never the main card`() {
        val rows = listOf(
            "UFC.999.Quessel.vs.Halde.1080p.WEB.h264-KESTREL" to only(Parts.MAIN_CARD),
            "UFC.999.PPV.Quessel.vs.Halde.720p.WEB.h264-WREN" to only(Parts.MAIN_CARD),
            "UFC.999.1080p.WEB.h264-ORCHID" to only(Parts.MAIN_CARD),
            "UFC.999.PPV.1080p.HDTV.x264-ORCHID" to only(Parts.MAIN_CARD),
            // The PPV broadcast in full is still the main card alone, never the whole card.
            "UFC.999.Full.PPV.1080p.WEB.h264-ORCHID" to only(Parts.MAIN_CARD),
            "UFC.999.2026.11.21.Quessel.vs.Halde.1080p.WEB.h264-KESTREL" to only(Parts.MAIN_CARD),
            "UFC.999.Prelims.Quessel.vs.Halde.720p.WEB.h264-WREN" to only(Parts.PRELIMS),
            // PPV beside a part's own word is that part's pay-per-view: the word decides.
            "UFC.999.PPV.Prelims.1080p.WEB.h264-KESTREL" to only(Parts.PRELIMS),
            "UFC.999.PPV.Early.Prelims.1080p.WEB.h264-KESTREL" to only(Parts.EARLY_PRELIMS),
        )
        assertEquals("", wrong(card, rows))
    }

    @Test
    fun `a full event is every part's, never taken whole, so the picker chooses the part's file`() {
        val wholeCard = "UFC.999.Full.Event.1080p.WEB.h264-ORCHID"
        val rows = listOf(
            wholeCard to every(Verdict.Accept(whole = false)),
            "UFC.999.Full.Card.720p.WEB.h264-WREN" to every(Verdict.Accept(whole = false)),
            "UFC.999.Complete.Event.720p.WEB.h264-WREN" to every(Verdict.Accept(whole = false)),
            // A release of two parts is each of them, and neither whole.
            "UFC.999.Prelims.and.Main.Card.1080p.WEB.h264-WREN" to
                mapOf(Parts.EARLY_PRELIMS to otherParts, Parts.PRELIMS to Verdict.Accept(whole = false), Parts.MAIN_CARD to Verdict.Accept(whole = false)),
        )
        assertEquals("", wrong(card, rows))

        val mainCard = PartPicker.of(cards, card, card.parts.single { it.id == Parts.MAIN_CARD })
        assertFalse(mainCard.takesWhole(wholeCard))
        assertTrue(mainCard.takesWhole("UFC.999.Quessel.vs.Halde.1080p.WEB.h264-KESTREL"))
        assertFalse(PartPicker.of(cards, card, card.parts.single { it.id == Parts.PRELIMS }).takesWhole("UFC.999.Quessel.vs.Halde.1080p.WEB.h264-KESTREL"))
    }

    @Test
    fun `a press conference, a weigh-in and a result word leave a name out as the shared rules name it`() {
        val rows = listOf(
            "UFC.999.Press.Conference.720p.WEB.h264-WREN" to "previews and shows",
            "UFC.999.Weigh-Ins.720p.WEB.h264-WREN" to "previews and shows",
            "UFC.999.Face.Offs.720p.WEB.h264-WREN" to "previews and shows",
            "UFC.999.Behind.The.Scenes.Episode.2.720p.WEB.h264-WREN" to "previews and shows",
            "UFC.999.Quessel.vs.Halde.Results.720p.WEB.h264-WREN" to "a name with a result",
            "UFC.999.Main.Card.Quessel.Wins.1080p.WEB.h264-WREN" to "a name with a result",
        )
        val wrong = rows.flatMap { (name, kind) ->
            numberedParts.mapNotNull { part ->
                val said = (judge(card, part, name) as? Verdict.Reject)?.kind?.words(Nouns.event(card))
                "$name for $part: $said".takeIf { said != kind }
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun `a release posted long before the card is an older release`() {
        val name = "UFC.Fight.Night.999.1080p.WEB.h264-DRIFTWOOD"
        assertEquals(Verdict.Reject(RejectKind.Older), judge(card, Parts.MAIN_CARD, name, posted = card.start.minus(Duration.ofDays(2_000))))
        assertEquals(other, judge(card, Parts.MAIN_CARD, name, posted = card.start.plus(Duration.ofHours(5))))
        val mainCard = "UFC.999.Main.Card.1080p.WEB.h264-KESTREL"
        assertEquals(Verdict.Reject(RejectKind.Older), judge(card, Parts.MAIN_CARD, mainCard, posted = card.start.minus(Duration.ofHours(25))))
        assertEquals(accepted, judge(card, Parts.MAIN_CARD, mainCard, posted = card.start.minus(Duration.ofHours(23))))
    }

    @Test
    fun `a fight night is its organisation, Fight Night or both surnames, and its date`() {
        val parts = listOf(Parts.PRELIMS, Parts.MAIN_CARD)
        fun mainCard(verdict: Verdict) = mapOf(Parts.PRELIMS to otherParts, Parts.MAIN_CARD to verdict)
        val rows = listOf(
            "UFC.Fight.Night.2026.11.28.Brisk.vs.Loam.1080p.WEB.h264-KESTREL" to mainCard(accepted),
            "UFC.Fight.Night.2026.11.28.720p.WEB.h264-ORCHID" to mainCard(accepted),
            "UFC.2026.11.28.Brisk.vs.Loam.Prelims.720p.WEB.h264-WREN" to mapOf(Parts.PRELIMS to accepted, Parts.MAIN_CARD to otherParts),
            // Both fighters with the organisation's word, and no date (L-SF7).
            "UFC.Fight.Night.Brisk.vs.Loam.Main.Card.1080p.WEB.h264-WREN" to mainCard(accepted),
            // The organisation and the date alone: the league's word and the date, a possible match.
            "UFC.2026.11.28.1080p.WEB.h264-WREN" to mainCard(offered("Picked by league and date")),
            "UFC.Fight.Night.2026.11.21.Brisk.vs.Loam.1080p.WEB.h264-WREN" to every(other, parts),
            "Fight.Night.2026.11.28.Brisk.vs.Loam.1080p.WEB.h264-WREN" to every(other, parts),
            "UFC.999.2026.11.28.1080p.WEB.h264-WREN" to every(other, parts),
            "UFC.Fight.Night.1080p.WEB.h264-WREN" to every(other, parts),
        )
        assertEquals("", wrong(fightNight, rows))

        // A fight night's number in its title must be the name's.
        val kcl = KeptLeague(20, "KCL", "Fighting")
        val numbered = sports(kcl to listOf(on(kcl, 33, at("2026-12-05T19:00"), "KCL Fight Night 40: Brisk vs. Loam", parts = listOf("Prelims", "Main Card"))))
        val numberedRows = listOf(
            "KCL.Fight.Night.40.2026.12.05.1080p.WEB.h264-KESTREL" to mainCard(accepted),
            "KCL.Fight.Night.40.720p.WEB.h264-WREN" to mainCard(accepted),
            "KCL.Fight.Night.41.2026.12.05.1080p.WEB.h264-WREN" to every(other, parts),
        )
        assertEquals("", wrong(numbered.byId(33), numberedRows, FightCard(numbered)))
    }

    /**
     * A fight night Sportarr titles "UFC Fight Night 888" on its own [id], or with its fighters after the number and no
     * colon, as it titles some (L-SF9), on Saturday 28 November.
     */
    private fun night(title: String, id: Int = 41) =
        sports(UFC to listOf(on(UFC, id, at("2026-11-28T19:00"), title, parts = listOf("Prelims", "Main Card")))).let { it to it.byId(id) }

    @Test
    fun `a fight night titled with its number and its fighters and no colon reads as its number and both surnames`() {
        val (titled, event) = night("UFC Fight Night 888 Quessel vs Halde")
        val matcher = FightCard(titled)
        assertEquals(CardType.FightNight, event.cardType)
        assertEquals(listOf("UFC"), matcher.context(event).organisations)
        assertEquals(listOf("UFC Fight Night 888", "UFC Quessel Halde"), matcher.queries(event, event.headline).first, "its number and its surnames")
        // Full names on a side: the last word of each is the surname.
        val (named, full) = night("UFC Fight Night 888: Ana Quessel vs. Mira Halde")
        assertEquals(listOf("UFC Fight Night 888", "UFC Quessel Halde"), FightCard(named).queries(full, full.headline).first)
    }

    @Test
    fun `a fight night's undated name is accepted by its own night number, and never with another number, card, year, date or part`() {
        val parts = listOf(Parts.PRELIMS, Parts.MAIN_CARD)
        fun prelims(verdict: Verdict) = mapOf(Parts.PRELIMS to verdict, Parts.MAIN_CARD to otherParts)
        fun mainCard(verdict: Verdict) = mapOf(Parts.PRELIMS to otherParts, Parts.MAIN_CARD to verdict)
        val rows = listOf(
            "UFC.Fight.Night.888.Prelims.1080p.WEB.h264-KESTREL" to prelims(accepted),
            "UFC.Fight.Night.888.1080p.WEB.h264-KESTREL" to mainCard(accepted),
            "UFC.Fight.Night.888.Main.Card.720p.WEB.h264-WREN" to mainCard(accepted),
            "UFC.Fight.Night.888.PPV.720p.WEB.h264-WREN" to mainCard(accepted),
            // Its own year beside the number names no other night.
            "UFC.Fight.Night.888.2026.Prelims.1080p.WEB.h264-ORCHID" to prelims(accepted),
            // A dated name that fits, as before.
            "UFC.Fight.Night.888.2026.11.28.Prelims.1080p.WEB.h264-KESTREL" to prelims(accepted),
            "UFC.Fight.Night.2026.11.28.1080p.WEB.h264-KESTREL" to mainCard(accepted),
            // Another night's number, a numbered card's, another year or another date: another card's.
            "UFC.Fight.Night.887.1080p.WEB.h264-KESTREL" to every(other, parts),
            "UFC.Fight.Night.887.Prelims.1080p.WEB.h264-KESTREL" to every(other, parts),
            "UFC.999.1080p.WEB.h264-KESTREL" to every(other, parts),
            "UFC.999.Prelims.1080p.WEB.h264-KESTREL" to every(other, parts),
            "UFC.Fight.Night.888.2025.1080p.WEB.h264-KESTREL" to every(other, parts),
            "UFC.Fight.Night.888.2025.Prelims.1080p.WEB.h264-KESTREL" to every(other, parts),
            "UFC.Fight.Night.888.2026.11.21.1080p.WEB.h264-KESTREL" to every(other, parts),
            // The number with no organisation, or with no "Fight Night", names no card.
            "Fight.Night.888.1080p.WEB.h264-KESTREL" to every(other, parts),
            "UFC.Night.888.1080p.WEB.h264-KESTREL" to every(other, parts),
        )
        listOf("UFC Fight Night 888", "UFC Fight Night 888 Quessel vs Halde").forEach { title ->
            val (titled, event) = night(title)
            assertEquals("", wrong(event, rows, FightCard(titled)), title)
        }
        val (titled, event) = night("UFC Fight Night 888 Quessel vs Halde")
        val fighters = listOf(
            "UFC.Fight.Night.888.Quessel.vs.Halde.Prelims.1080p.WEB.h264-KESTREL" to prelims(accepted),
            "UFC.Fight.Night.888.Quessel.vs.Halde.1080p.WEB.h264-KESTREL" to mainCard(accepted),
            "UFC.Fight.Night.887.Quessel.vs.Halde.1080p.WEB.h264-KESTREL" to every(other, parts),
        )
        assertEquals("", wrong(event, fighters, FightCard(titled)))
    }

    @Test
    fun `a card's undated name is accepted by its organisation and both fighters, and never with another year, number, date or part`() {
        // UFC 999 of the list, whose two fighters no other UFC card of it has: the matchup of Halde and Brisk is boxing's.
        val rows = listOf(
            "UFC.Halde.vs.Quessel.1080p.WEB.h264-KESTREL" to only(Parts.MAIN_CARD),
            "UFC.Quessel.vs.Halde.Prelims.720p.WEB.h264-WREN" to only(Parts.PRELIMS),
            "UFC.Quessel.vs.Halde.Early.Prelims.720p.WEB.h264-WREN" to only(Parts.EARLY_PRELIMS),
            "UFC.Quessel.vs.Halde.2026.1080p.WEB.h264-ORCHID" to only(Parts.MAIN_CARD),
            // Another year, another card's number, a fight night or another date: another card's.
            "UFC.Halde.vs.Quessel.2025.1080p.WEB.h264-KESTREL" to every(other),
            "UFC.998.Quessel.vs.Halde.1080p.WEB.h264-KESTREL" to every(other),
            "UFC.Fight.Night.888.Quessel.vs.Halde.1080p.WEB.h264-KESTREL" to every(other),
            "UFC.2026.11.28.Quessel.vs.Halde.1080p.WEB.h264-WREN" to every(other),
            // Without the organisation's word, or with one fighter alone, no card's, as before.
            "Quessel.vs.Halde.1080p.WEB.h264-WREN" to every(other),
            "UFC.Quessel.1080p.WEB.h264-WREN" to every(other),
        )
        assertEquals("", wrong(card, rows))

        val parts = listOf(Parts.PRELIMS, Parts.MAIN_CARD)
        fun prelims(verdict: Verdict) = mapOf(Parts.PRELIMS to verdict, Parts.MAIN_CARD to otherParts)
        fun mainCard(verdict: Verdict) = mapOf(Parts.PRELIMS to otherParts, Parts.MAIN_CARD to verdict)
        val (titled, night) = night("UFC Fight Night 888 Quessel vs Halde")
        val nightRows = listOf(
            "UFC.Fight.Night.888.Quessel.vs.Halde.Prelims.1080p.WEB.h264-KESTREL" to prelims(accepted),
            "UFC.Fight.Night.Quessel.vs.Halde.Prelims.1080p.WEB.h264-KESTREL" to prelims(accepted),
            "UFC.Halde.vs.Quessel.1080p.WEB.h264-WREN" to mainCard(accepted),
            "UFC.Fight.Night.Quessel.vs.Halde.2025.1080p.WEB.h264-WREN" to every(other, parts),
            "UFC.Fight.Night.887.Quessel.vs.Halde.1080p.WEB.h264-WREN" to every(other, parts),
            "UFC.999.Quessel.vs.Halde.1080p.WEB.h264-WREN" to every(other, parts),
            "UFC.Fight.Night.2026.12.05.Quessel.vs.Halde.1080p.WEB.h264-WREN" to every(other, parts),
            "Quessel.vs.Halde.Prelims.1080p.WEB.h264-WREN" to every(other, parts),
        )
        assertEquals("", wrong(night, nightRows, FightCard(titled)))
    }

    @Test
    fun `a rematch listed in the league leaves an undated name of the two fighters a possible match, unless it has the card's number`() {
        val rematch = sports(
            UFC to listOf(ufc999(), on(UFC, 42, at("2026-12-05T19:00"), "UFC Fight Night 888 Quessel vs Halde", parts = listOf("Prelims", "Main Card"))),
        )
        val matcher = FightCard(rematch)
        val noDate = offered("No date in the name")
        val cardRows = listOf(
            "UFC.Quessel.vs.Halde.1080p.WEB.h264-KESTREL" to only(Parts.MAIN_CARD, noDate),
            "UFC.Halde.vs.Quessel.Prelims.720p.WEB.h264-WREN" to only(Parts.PRELIMS, noDate),
            "UFC.999.Quessel.vs.Halde.1080p.WEB.h264-KESTREL" to only(Parts.MAIN_CARD),
            // The card's own date tells the two apart; the rematch's date is the rematch's.
            "UFC.2026.11.21.Quessel.vs.Halde.1080p.WEB.h264-KESTREL" to only(Parts.MAIN_CARD),
            "UFC.2026.12.05.Quessel.vs.Halde.1080p.WEB.h264-KESTREL" to every(other),
        )
        assertEquals("", wrong(rematch.byId(31), cardRows, matcher))
        val parts = listOf(Parts.PRELIMS, Parts.MAIN_CARD)
        val nightRows = listOf(
            "UFC.Quessel.vs.Halde.Prelims.1080p.WEB.h264-KESTREL" to mapOf(Parts.PRELIMS to noDate, Parts.MAIN_CARD to otherParts),
            "UFC.Fight.Night.Halde.vs.Quessel.1080p.WEB.h264-KESTREL" to mapOf(Parts.PRELIMS to otherParts, Parts.MAIN_CARD to noDate),
            "UFC.Fight.Night.888.Quessel.vs.Halde.Prelims.1080p.WEB.h264-KESTREL" to mapOf(Parts.PRELIMS to accepted, Parts.MAIN_CARD to otherParts),
            "UFC.Fight.Night.2026.12.05.Quessel.vs.Halde.1080p.WEB.h264-KESTREL" to mapOf(Parts.PRELIMS to otherParts, Parts.MAIN_CARD to accepted),
            "UFC.999.Quessel.vs.Halde.1080p.WEB.h264-KESTREL" to every(other, parts),
        )
        assertEquals("", wrong(rematch.byId(42), nightRows, matcher))
    }

    @Test
    fun `a card whose title names no sides is read by Sportarr's home and away names where it sends both`() {
        fun untitled(title: String, home: String?, away: String?, parts: List<String> = listOf("Prelims", "Main Card")) =
            sports(UFC to listOf(on(UFC, 43, at("2026-11-28T19:00"), title, parts = parts).copy(homeTeamName = home, awayTeamName = away)))
                .let { it to it.byId(43) }
        val parts = listOf(Parts.PRELIMS, Parts.MAIN_CARD)
        val (sent, night) = untitled("UFC Fight Night 888", "Brisk", "Loam")
        val rows = listOf(
            "UFC.Brisk.vs.Loam.1080p.WEB.h264-KESTREL" to mapOf(Parts.PRELIMS to otherParts, Parts.MAIN_CARD to accepted),
            "UFC.Loam.vs.Brisk.Prelims.1080p.WEB.h264-WREN" to mapOf(Parts.PRELIMS to accepted, Parts.MAIN_CARD to otherParts),
            "UFC.Brisk.vs.Loam.2025.1080p.WEB.h264-WREN" to every(other, parts),
        )
        assertEquals("", wrong(night, rows, FightCard(sent)))
        assertEquals(listOf("Brisk", "Loam"), FightCard(sent).context(night).sides)

        // A numbered card's too, and full names give their last words.
        val (numbered, card) = untitled("UFC 997", "Ana Quessel", "Mira Halde", listOf("Early Prelims", "Prelims", "Main Card"))
        assertEquals("", wrong(card, listOf("UFC.Quessel.vs.Halde.1080p.WEB.h264-KESTREL" to only(Parts.MAIN_CARD)), FightCard(numbered)))

        // Without them, with one alone, or with a side of one letter or a number alone, which is no surname: as before.
        listOf(null to null, "Brisk" to null, "Brisk" to "L", "Brisk" to "7").forEach { (home, away) ->
            val (list, event) = untitled("UFC Fight Night 888", home, away)
            val name = "UFC.Brisk.vs.${away ?: "Loam"}.1080p.WEB.h264-KESTREL"
            assertEquals("", wrong(event, listOf(name to every(other, parts)), FightCard(list)), "$home and $away")
        }
    }

    @Test
    fun `a boxing matchup is both surnames and the date, and without a date or as the main event alone a possible match`() {
        val rows = listOf(
            "Boxing.2026.11.18.Halde.vs.Brisk.1080p.WEB.h264-KESTREL" to full(accepted),
            "Boxing.2026.11.18.Brisk.vs.Halde.720p.WEB.h264-WREN" to full(accepted),
            "Boxing.2026.11.18.Halde.vs.Brisk.Full.Card.1080p.WEB.h264-ORCHID" to full(accepted),
            "Boxing.2026.11.18.Halde.vs.Brisk.Complete.Event.1080p.WEB.h264-ORCHID" to full(accepted),
            "Halde.vs.Brisk.1080p.WEB.h264-WREN" to full(offered("No date in the name")),
            "Boxing.2026.11.18.Halde.vs.Brisk.Main.Event.Only.720p.WEB.h264-ORCHID" to full(offered("Main event only")),
            "Kestrel.Boxing.2026.11.18.1080p.WEB.h264-WREN" to full(offered("Picked by league and date")),
            "Boxing.2026.11.18.Halde.vs.Brisk.Prelims.720p.WEB.h264-WREN" to full(otherParts),
            // Boxing's word for the fights before the main event.
            "Boxing.2026.11.18.Halde.vs.Brisk.Undercard.1080p.WEB.h264-WREN" to full(otherParts),
            "Boxing.2026.11.18.Halde.vs.Loam.1080p.WEB.h264-WREN" to full(other),
            "Boxing.2026.11.25.Halde.vs.Brisk.1080p.WEB.h264-WREN" to full(other),
        )
        assertEquals("", wrong(bout, rows))
        assertEquals("other fights", RejectKind.OtherEvents.words(Nouns.event(bout)))
    }

    @Test
    fun `a weekly show is its title's words on its own date, and a name with no date is another week's`() {
        val firstWeek = list.byId(51)
        val firstRows = listOf(
            "Velmora.Monday.Brawl.2026.11.06.1080p.WEB.h264-KESTREL" to full(accepted),
            "Velmora.Monday.Brawl.2026.11.07.720p.WEB.h264-WREN" to full(accepted),
            "Velmora.Monday.Brawl.2026.11.05.720p.WEB.h264-WREN" to full(other),
            "Velmora.Monday.Brawl.2026.11.13.720p.WEB.h264-WREN" to full(other),
            "Velmora.Monday.Brawl.1080p.WEB.h264-DRIFTWOOD" to full(other),
        )
        assertEquals("", wrong(firstWeek, firstRows))
        assertEquals("other shows", RejectKind.OtherEvents.words(Nouns.event(firstWeek)))
    }

    @Test
    fun `a week whose broadcast date is Sportarr's fallback takes the local date the day before, never a week before`() {
        val thirdWeek = list.byId(53)
        val rows = listOf(
            "Velmora.Monday.Brawl.2026.11.20.1080p.WEB.h264-ORCHID" to full(accepted),
            "Velmora.Monday.Brawl.20261120.720p.WEB.h264-WREN" to full(accepted),
            "Velmora.Monday.Brawl.2026.11.21.1080p.WEB.h264-KESTREL" to full(accepted),
            "Velmora.Monday.Brawl.2026.11.13.1080p.WEB.h264-WREN" to full(other),
            "Velmora.Monday.Brawl.2026.11.14.1080p.WEB.h264-WREN" to full(other),
            "Velmora.Monday.Brawl.1080p.WEB.h264-DRIFTWOOD" to full(other),
            "Velmora.Monday.Brawl.2026.11.20.Pre-Show.720p.WEB.h264-WREN" to full(otherParts),
            "Velmora.Wrestling.2026.11.20.1080p.WEB.h264-WREN" to full(offered("Picked by league and date")),
        )
        assertEquals("", wrong(thirdWeek, rows))
    }

    @Test
    fun `a premium event's pre-show and main show are told by their words, its night by its number`() {
        val crownRows = listOf(
            "Velmora.Iron.Crown.2026.1080p.WEB.h264-KESTREL" to show(accepted, otherParts),
            "Velmora.Iron.Crown.2026.PPV.1080p.WEB.h264-WREN" to show(accepted, otherParts),
            "Velmora.Iron.Crown.2026.Main.Show.720p.WEB.h264-ORCHID" to show(accepted, otherParts),
            "Velmora.Iron.Crown.2026.11.11.1080p.WEB.h264-KESTREL" to show(accepted, otherParts),
            "Velmora.Iron.Crown.Night.1.2026.1080p.WEB.h264-WREN" to show(accepted, otherParts),
            "Velmora.Iron.Crown.2026.Preshow.720p.WEB.h264-WREN" to show(otherParts, accepted),
            "Velmora.Iron.Crown.2026.Kickoff.Show.720p.WEB.h264-WREN" to show(otherParts, accepted),
            "Velmora.Iron.Crown.2026.Pre-Show.720p.WEB.h264-WREN" to show(otherParts, accepted),
            "Velmora.Iron.Crown.2026.Full.Event.1080p.WEB.h264-ORCHID" to show(Verdict.Accept(whole = false), Verdict.Accept(whole = false)),
            "Velmora.Iron.Crown.2026.Night.2.1080p.WEB.h264-WREN" to show(other, other),
            // The second night's own date.
            "Velmora.Iron.Crown.2026.11.12.1080p.WEB.h264-WREN" to show(other, other),
            "Velmora.Iron.Crown.2025.1080p.WEB.h264-WREN" to show(other, other),
            "Velmora.Iron.Crown.1080p.WEB.h264-WREN" to show(other, other),
            "Iron.Crown.2026.1080p.WEB.h264-WREN" to show(other, other),
        )
        assertEquals("", wrong(crown, crownRows))

        val nightTwoRows = listOf(
            "Velmora.Iron.Crown.2026.Night.2.1080p.WEB.h264-KESTREL" to show(accepted, otherParts),
            "Velmora.Iron.Crown.Night2.2026.1080p.WEB.h264-WREN" to show(accepted, otherParts),
            "Velmora.Iron.Crown.Night.2.2026.Kickoff.Show.720p.WEB.h264-WREN" to show(otherParts, accepted),
            "Velmora.Iron.Crown.2026.1080p.WEB.h264-KESTREL" to show(other, other),
            "Velmora.Iron.Crown.2026.Night.3.1080p.WEB.h264-WREN" to show(other, other),
            // Its date and the league's word, with no night: a possible match.
            "Velmora.Iron.Crown.2026.11.12.1080p.WEB.h264-WREN" to show(offered("Picked by league and date"), otherParts),
        )
        assertEquals("", wrong(nightTwo, nightTwoRows))

        // An edition number in the title stands for the year.
        val edition = sports(VELMORA to listOf(on(VELMORA, 56, at("2026-11-11T23:00"), "Velmora Iron Crown 38")))
        val editionRows = listOf(
            "Velmora.Iron.Crown.38.1080p.WEB.h264-KESTREL" to show(accepted, otherParts),
            "Velmora.Iron.Crown.2026.1080p.WEB.h264-KESTREL" to show(accepted, otherParts),
            "Velmora.Iron.Crown.37.1080p.WEB.h264-WREN" to show(other, other),
        )
        assertEquals("", wrong(edition.byId(56), editionRows, FightCard(edition)))
    }

    @Test
    fun `a part named in a release by the label Sportarr gives it is that part, before the programme and part words`() {
        // Night 2's pre-show is labelled "Countdown": its Countdown release is the pre-show, never a programme.
        val nightTwoRows = listOf(
            "Velmora.Iron.Crown.Night.2.2026.Countdown.720p.WEB.h264-WREN" to show(otherParts, accepted),
            "Velmora.Iron.Crown.2026.Night.2.Countdown.1080p.WEB.h264-KESTREL" to show(otherParts, accepted),
            "Velmora.Iron.Crown.Night.2.2026.1080p.WEB.h264-KESTREL" to show(accepted, otherParts),
        )
        assertEquals("", wrong(nightTwo, nightTwoRows))

        // A show whose pre-show is labelled "Kickoff", and a fight night whose prelims are labelled "Lead Card".
        val labelled = sports(
            VELMORA to listOf(on(VELMORA, 56, at("2026-11-11T23:00"), "Velmora Iron Crown 38", parts = listOf("Kickoff", "Main Show"))),
            UFC to listOf(on(UFC, 33, at("2026-11-28T19:00"), "UFC Fight Night: Brisk vs. Loam", parts = listOf("Lead Card", "Main Card"))),
        )
        val matcher = FightCard(labelled)
        val kickoffRows = listOf(
            "Velmora.Iron.Crown.38.Kickoff.720p.WEB.h264-WREN" to show(otherParts, accepted),
            "Velmora.Iron.Crown.38.1080p.WEB.h264-KESTREL" to show(accepted, otherParts),
        )
        assertEquals("", wrong(labelled.byId(56), kickoffRows, matcher))
        val leadCardRows = listOf(
            "UFC.Fight.Night.2026.11.28.Lead.Card.720p.WEB.h264-WREN" to mapOf(Parts.PRELIMS to accepted, Parts.MAIN_CARD to otherParts),
            "UFC.Fight.Night.2026.11.28.Brisk.vs.Loam.1080p.WEB.h264-KESTREL" to mapOf(Parts.PRELIMS to otherParts, Parts.MAIN_CARD to accepted),
        )
        assertEquals("", wrong(labelled.byId(33), leadCardRows, matcher))

        // A show whose parts are the plugin's own reads as before: "Countdown" a programme, "Kickoff" alone no part word.
        val ownRows = listOf(
            "Velmora.Iron.Crown.2026.Countdown.720p.WEB.h264-WREN" to show(shows, shows),
            "Velmora.Iron.Crown.2026.Kickoff.720p.WEB.h264-WREN" to show(accepted, otherParts),
        )
        assertEquals("", wrong(crown, ownRows))

        // In a full event's release, the label names the part's file.
        val folder = "Velmora.Iron.Crown.Night.2.2026.Full.Event.1080p.WEB.h264-ORCHID"
        val files = listOf(
            FileInfo("$folder/Velmora.Iron.Crown.Night.2.2026.Countdown.1080p.WEB.h264-ORCHID.mkv", 2_000_000_000, isVideo = true),
            FileInfo("$folder/Velmora.Iron.Crown.Night.2.2026.Main.Show.1080p.WEB.h264-ORCHID.mkv", 6_000_000_000, isVideo = true),
        )
        assertEquals(0, PartPicker.of(cards, nightTwo, nightTwo.parts.single { it.id == Parts.PRE_SHOW }).pick(files))
        assertEquals(1, PartPicker.of(cards, nightTwo, nightTwo.parts.single { it.id == Parts.MAIN_SHOW }).pick(files))
    }

    @Test
    fun `two editions of one title in a year are told apart by their number or their date, never by the year alone`() {
        val editions = sports(
            VELMORA to listOf(on(VELMORA, 58, at("2026-10-10T23:00"), "Velmora Iron Crown 37"), on(VELMORA, 59, at("2026-11-14T23:00"), "Velmora Iron Crown 38")),
        )
        val matcher = FightCard(editions)
        val thirtySeven = editions.byId(58)
        val thirtyEight = editions.byId(59)
        assertEquals(listOf(CardType.Premium, CardType.Premium), listOf(thirtySeven.cardType, thirtyEight.cardType))
        val rows = listOf(
            Triple("Velmora.Iron.Crown.38.2026.PPV.1080p.WEB.h264-KESTREL", other, accepted),
            Triple("Velmora.Iron.Crown.37.2026.1080p.WEB.h264-WREN", accepted, other),
            Triple("Velmora.Iron.Crown.2026.10.10.1080p.WEB.h264-WREN", accepted, other),
            Triple("Velmora.Iron.Crown.2026.11.14.1080p.WEB.h264-WREN", other, accepted),
            Triple("Velmora.Iron.Crown.2026.PPV.1080p.WEB.h264-WREN", other, other),
        )
        val posted = thirtyEight.start.plus(Duration.ofHours(4))
        val wrong = rows.flatMap { (name, forFirst, forSecond) ->
            listOfNotNull(
                judge(thirtySeven, Parts.MAIN_SHOW, name, posted, matcher).takeIf { it != forFirst }?.let { "$name for 37: $it" },
                judge(thirtyEight, Parts.MAIN_SHOW, name, posted, matcher).takeIf { it != forSecond }?.let { "$name for 38: $it" },
            )
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun `a premium event whose title carries its year takes its dated and its undated names`() {
        val titled = sports(VELMORA to listOf(on(VELMORA, 57, at("2026-11-21T23:00"), "Velmora Iron Crown 2026")))
        val rows = listOf(
            "Velmora.Iron.Crown.2026.11.21.1080p.WEB.h264-KESTREL" to show(accepted, otherParts),
            "Velmora.Iron.Crown.2026.PPV.1080p.WEB.h264-WREN" to show(accepted, otherParts),
            "Velmora.Iron.Crown.2026.11.21.Pre-Show.720p.WEB.h264-WREN" to show(otherParts, accepted),
            "Velmora.Iron.Crown.2026.11.14.1080p.WEB.h264-WREN" to show(other, other),
        )
        assertEquals("", wrong(titled.byId(57), rows, FightCard(titled)))
    }

    @Test
    fun `a weekly night first listed alone stays a premium event while it is listed, held to its own date as its weeks are`() {
        // The first week was read alone once, and kept as a named event; the two weeks after it are weekly.
        val read = SportsList(at("2026-11-22T20:41"), listOf(VELMORA), brawls.sortedByDescending { it.eventDate }, cut = false)
        val kept = Sports.of(read.copy(cardTypes = mapOf("ev-000051" to CardType.Premium)))
        val matcher = FightCard(kept)
        val firstWeek = kept.byId(51)
        assertEquals(CardType.Premium, firstWeek.cardType)
        val rows = listOf(
            // A year and no date: any week's, as its weekly nights would say, so no week's.
            "Velmora.Monday.Brawl.2026.1080p.WEB.h264-DRIFTWOOD" to show(other, other),
            "Velmora.Monday.Brawl.S2026E47.1080p.WEB.h264-WREN" to show(other, other),
            "Velmora.Monday.Brawl.2026.Pre-Show.720p.WEB.h264-WREN" to show(other, other),
            "Velmora.Monday.Brawl.2026.11.06.1080p.WEB.h264-KESTREL" to show(accepted, otherParts),
            "Velmora.Monday.Brawl.2026.11.06.Pre-Show.720p.WEB.h264-WREN" to show(otherParts, accepted),
            "Velmora.Wrestling.2026.11.06.1080p.WEB.h264-WREN" to show(offered("Picked by league and date"), otherParts),
            // Another week's date, its own or its local one, is that week's.
            "Velmora.Monday.Brawl.2026.11.13.1080p.WEB.h264-WREN" to show(other, other),
            "Velmora.Monday.Brawl.2026.11.14.1080p.WEB.h264-WREN" to show(other, other),
            "Velmora.Monday.Brawl.1080p.WEB.h264-DRIFTWOOD" to show(other, other),
        )
        assertEquals("", wrong(firstWeek, rows, matcher))
        // The weekly weeks after it still leave the undated name out.
        assertEquals(other, judge(kept.byId(52), Parts.FULL, "Velmora.Monday.Brawl.2026.1080p.WEB.h264-DRIFTWOOD", matcher = matcher))
    }

    @Test
    fun `another listed card's names, and the league's word with the date, are told apart from the card's own`() {
        val rows = listOf(
            // The fight night's two surnames on the numbered card's number.
            "UFC.999.Brisk.vs.Loam.1080p.WEB.h264-WREN" to every(other),
            "UFC.2026.11.21.Quessel.vs.Halde.1080p.WEB.h264-WREN" to only(Parts.MAIN_CARD),
            "UFC.2026.11.21.Prelims.720p.WEB.h264-WREN" to only(Parts.PRELIMS, offered("Picked by league and date")),
            "UFC.Quessel.vs.Halde.1080p.WEB.h264-WREN" to only(Parts.MAIN_CARD),
            "KCL.2026.11.21.1080p.WEB.h264-WREN" to every(other),
        )
        assertEquals("", wrong(card, rows))
        // The weekly show's words on the premium event's date.
        assertEquals(other, judge(crown, Parts.MAIN_SHOW, "Velmora.Monday.Brawl.2026.11.11.1080p.WEB.h264-WREN"))
    }

    @Test
    fun `each type is searched by its own words, the same for every part`() {
        fun queries(event: SportsEvent, matcher: FightCard = cards) = matcher.queries(event, event.headline).let { it.first to it.then }
        assertEquals(listOf("UFC 999", "UFC 2026 11 21") to "UFC Quessel Halde", queries(card))
        assertEquals(queries(card), cards.queries(card, card.parts.first()).let { it.first to it.then }, "the prelims' words are the main card's")
        assertEquals(listOf("UFC Brisk Loam", "UFC 2026 11 28") to null, queries(fightNight))
        assertEquals(listOf("Halde Brisk") to null, queries(bout))
        assertEquals(listOf("Velmora Monday Brawl 2026 11 06") to "Velmora Monday Brawl", queries(list.byId(51)))
        assertEquals(listOf("Velmora Monday Brawl 2026 11 21") to "Velmora Monday Brawl", queries(list.byId(53)))
        assertEquals(listOf("Velmora Iron Crown") to null, queries(crown))
        assertEquals(listOf("Velmora Iron Crown") to null, queries(nightTwo))

        val kcl = KeptLeague(20, "KCL", "Fighting")
        val untitled = sports(
            kcl to listOf(
                on(kcl, 71, at("2026-11-01T20:00"), "KCL 41"),
                on(kcl, 72, at("2026-11-08T20:00"), "KCL Fight Night 40"),
                on(kcl, 73, at("2026-11-15T20:00"), "The KCL Lantern Bay Copper Crown Kestrel Vale Gala"),
            ),
        )
        val matcher = FightCard(untitled)
        assertEquals(listOf("KCL 41", "KCL 2026 11 01") to null, queries(untitled.byId(71), matcher))
        assertEquals(listOf("KCL Fight Night 40", "KCL 2026 11 08") to null, queries(untitled.byId(72), matcher))
        assertEquals(listOf("KCL Lantern Bay Copper Crown") to null, queries(untitled.byId(73), matcher), "five words at most, no article")
    }

    @Test
    fun `a fight night with its number and its fighters is searched by both first, then by its date, the same for every part`() {
        val (titled, night) = night("UFC Fight Night 888 Quessel vs Halde")
        val matcher = FightCard(titled)
        val searched = night.parts.map { part -> matcher.queries(night, part).let { it.first to it.then } }
        assertEquals(listOf(listOf("UFC Fight Night 888", "UFC Quessel Halde") to "UFC 2026 11 28"), searched.distinct(), "every part's words are the card's")
        // No part's word: one card's answers serve every part from the host's cache.
        val words = searched.flatMap { (first, then) -> first + listOfNotNull(then) }.flatMap { it.lowercase().split(' ') }
        assertEquals(emptyList(), words.filter { it in setOf("prelims", "main", "card", "early", "ppv") })

        // Its fighters as Sportarr's home and away names, where its title names none.
        val sent = sports(
            UFC to listOf(on(UFC, 43, at("2026-11-28T19:00"), "UFC Fight Night 888", parts = listOf("Prelims", "Main Card")).copy(homeTeamName = "Brisk", awayTeamName = "Loam")),
        )
        val untitled = sent.byId(43)
        assertEquals(listOf("UFC Fight Night 888", "UFC Brisk Loam") to "UFC 2026 11 28", FightCard(sent).queries(untitled, untitled.headline).let { it.first to it.then })
        // With its number alone, or its fighters alone, as before.
        val (numbered, alone) = night("UFC Fight Night 888")
        assertEquals(listOf("UFC Fight Night 888", "UFC 2026 11 28") to null, FightCard(numbered).queries(alone, alone.headline).let { it.first to it.then })
        assertEquals(listOf("UFC Brisk Loam", "UFC 2026 11 28") to null, cards.queries(fightNight, fightNight.headline).let { it.first to it.then })
    }

    @Test
    fun `a card's names are read with its organisation, its sides and, for a premium event, its shows`() {
        val numbered = cards.context(card)
        assertEquals(listOf("UFC"), numbered.organisations)
        assertEquals(listOf("Quessel", "Halde"), numbered.sides)
        assertFalse(numbered.show)
        assertEquals(listOf("Brisk", "Loam"), cards.context(fightNight).sides)
        assertEquals(listOf("UFC"), cards.context(fightNight).organisations)
        assertTrue(cards.context(crown).show)
        assertFalse(cards.context(list.byId(51)).show)
        assertTrue(cards.context(bout).organisations.isEmpty())
    }

    @Test
    fun `every fight card row of the catalogue gets the verdict it says`() {
        val matcher = FightCard(Catalogue.sports)
        val rows = Catalogue.verdicts(Family.FightCard)
        val wrong = rows.mapNotNull { (release, verdict) -> Catalogue.disagreement(matcher, release, verdict) }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
        assertEquals(
            36,
            rows.size,
            "UFC 999's eight names for each of its parts, the fight night's two for each of its, the matchup's two and the weekly show's six verdicts",
        )
        val night = rows.filter { (_, verdict) -> verdict.eventId == 1902 }.groupBy({ it.second.part }, { it.first.id to it.second.verdict.name })
        assertEquals(
            mapOf(
                Parts.PRELIMS to listOf("ufc-fight-night-main-card" to "Reject", "ufc-fight-night-prelims" to "Accept"),
                Parts.MAIN_CARD to listOf("ufc-fight-night-main-card" to "Accept", "ufc-fight-night-prelims" to "Reject"),
            ),
            night.mapValues { (_, verdicts) -> verdicts.sortedBy { it.first } },
        )
        val mainCard = rows.filter { (_, verdict) -> verdict.eventId == 1901 && verdict.part == Parts.MAIN_CARD }
        assertEquals(
            setOf("ufc-999-main-card", "ufc-999-ppv", "ufc-999-full-event"),
            mainCard.filter { it.second.verdict.name == "Accept" }.map { it.first.id }.toSet(),
        )
        assertEquals(CardType.WeeklyShow, Catalogue.event(2003).cardType)
        assertEquals(CardType.Matchup, Catalogue.event(2101).cardType)
    }

    @Test
    fun `a fight card's matcher is FightCard`() {
        assertIs<FightCard>(Matchers(list).of(card))
        assertIs<FightCard>(Matchers(list).of(crown))
        assertIs<FightCard>(Matchers(Catalogue.sports).of(Catalogue.event(2101)))
    }
}
