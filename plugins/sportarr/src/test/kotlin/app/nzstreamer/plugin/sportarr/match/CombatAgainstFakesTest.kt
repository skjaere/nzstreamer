package app.nzstreamer.plugin.sportarr.match

import app.nzstreamer.plugin.sportarr.family.Matchers
import app.nzstreamer.plugin.sportarr.model.CardType
import app.nzstreamer.plugin.sportarr.model.Family
import app.nzstreamer.plugin.sportarr.model.FamilyFrom
import app.nzstreamer.plugin.sportarr.model.Parts
import app.nzstreamer.plugin.sportarr.model.Sports
import app.nzstreamer.plugin.sportarr.source.ListRead
import app.nzstreamer.plugin.sportarr.source.ListReader
import app.nzstreamer.plugin.sportarr.source.LoopbackHttp
import app.nzstreamer.plugin.sportarr.source.SportarrClient
import app.nzstreamer.plugin.sportarr.source.loopbackSafety
import io.skjaere.usenet.mockseed.FakeCatalog
import io.skjaere.usenet.mockseed.FakeIds
import io.skjaere.usenet.mockseed.FakeIndexerServer
import io.skjaere.usenet.mockseed.FakeRelease
import io.skjaere.usenet.mockseed.FakeSportarrServer
import io.skjaere.usenet.mockseed.SportsCatalog
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Sportarr's fight leagues end to end (L-SF1, L-SF2, L-SF4), over loopback: mock-seed's fake Sportarr answers as 4.1.12
 * does, "Combat" for its fight leagues and their events, "Fighting" for one as a server before it, and a word no family
 * has for a league whose card carries part names; a refresh's read of it makes each of their events a fight card. An
 * undated fight-night name the sports indexer lists is then judged by the fight card's rules for the UFC fight night's
 * parts, through the runner's searches: a possible match for its main card, another part's for its prelims, and for
 * neither another event's, as it was when Combat read as no family.
 */
class CombatAgainstFakesTest {
    private val anchor = LocalDate.of(2026, 11, 22)
    private val sportarr = FakeSportarrServer(SportsCatalog.load(), anchor, port = 0)
    private val indexer = FakeIndexerServer(emptyList(), KEY, port = 0, bind = InetAddress.getLoopbackAddress(), repeatedWords = true)

    @AfterEach
    fun stop() {
        sportarr.close()
        indexer.close()
    }

    /** The fake's library as a refresh reads it a week after the anchor, once the fight night has aired. */
    private fun sports(): Sports {
        val now = anchor.plusDays(7).atTime(12, 0).toInstant(ZoneOffset.UTC)
        val client = SportarrClient(LoopbackHttp(), "http://127.0.0.1:${sportarr.port}", FakeSportarrServer.KEY, ::loopbackSafety)
        return Sports.of(assertIs<ListRead.Read>(runBlocking { ListReader(client, now, ZoneOffset.UTC).read() }).list)
    }

    @Test
    fun `the fake's Combat, Fighting and part-named leagues read as fight cards, and every family is reached`() {
        val sports = sports()
        val fights = sports.events.filter { it.league.sport in setOf("Combat", "Fighting", "Exhibition") }
            .groupBy({ it.league.name }, { it.family to it.familyFrom }).mapValues { (_, read) -> read.toSet() }
        assertEquals(
            mapOf(
                "UFC" to setOf(Family.FightCard to FamilyFrom.LeagueSport),
                "Velmora Wrestling" to setOf(Family.FightCard to FamilyFrom.LeagueSport),
                "Kestrel Boxing" to setOf(Family.FightCard to FamilyFrom.LeagueSport),
                "KCL" to setOf(Family.FightCard to FamilyFrom.LeagueSport),
                "Quillfen Fight League" to setOf(Family.FightCard to FamilyFrom.PartNames),
            ),
            fights,
        )
        assertEquals(mapOf("Combat" to 3, "Fighting" to 1, "Exhibition" to 1), sports.leagues.filter { it.name in fights }.groupingBy { it.sport.orEmpty() }.eachCount())
        assertEquals(Family.entries.toSet(), sports.events.map { it.family }.toSet())
    }

    @Test
    fun `an undated fight-night name in the Combat league is a possible match for the main card, another part's for the prelims`() {
        val sports = sports()
        val night = sports.events.single { it.id == FIGHT_NIGHT }
        assertEquals(Triple(Family.FightCard, CardType.FightNight, "Combat"), Triple(night.family, night.cardType, night.league.sport))
        indexer.serve(
            listOf(
                FakeRelease(
                    guid = GUID, title = NAME, category = FakeCatalog.TV_SPORT, ids = FakeIds(), grabs = 40, fileNames = listOf("$NAME.mkv"),
                    sizeBytes = 6_000_000_000, postedAt = night.start.plus(Duration.ofHours(5)),
                ) to NZB,
            ),
        )
        val matcher = Matchers(sports).of(night)
        fun run(part: String) = IndexerSearch(indexer.port, KEY, INDEXER).let { search ->
            runBlocking { Runner(search).releases(matcher, night, night.parts.single { it.id == part }) } to search.found
        }

        val (main, foundForMain) = run(Parts.MAIN_CARD)
        assertEquals(setOf(GUID), foundForMain, "the fight night's searches reach it")
        assertEquals(emptyList(), main.accepted)
        assertEquals(listOf("$GUID: No date in the name"), main.offered.map { "${it.handle}: ${it.reason}" })
        assertEquals(null, main.leftOut)

        val (prelims, foundForPrelims) = run(Parts.PRELIMS)
        assertEquals(setOf(GUID), foundForPrelims)
        assertEquals(emptyList(), prelims.accepted + prelims.offered.map { it.handle })
        assertEquals(LeftOut.line(mapOf(RejectKind.OtherParts to 1), Nouns.event(night)), prelims.leftOut)
    }

    private companion object {
        const val KEY = "fake-indexer-key"
        const val INDEXER = "Sports indexer"

        /** The catalogue's UFC Fight Night: Brisk vs. Loam, six days after the anchor. */
        const val FIGHT_NIGHT = 1902

        /** An invented fight night's name as some indexers list one: no date, no number, both surnames. */
        const val NAME = "UFC.Fight.Night.Brisk.vs.Loam.1080p.WEB.h264-KESTREL"
        const val GUID = "sp-fight-night-undated"

        /** What the indexer's get would serve: no test grabs a release. */
        const val NZB = """<?xml version="1.0" encoding="UTF-8"?><nzb xmlns="http://www.newzbin.com/DTD/2003/nzb"/>"""
    }
}
