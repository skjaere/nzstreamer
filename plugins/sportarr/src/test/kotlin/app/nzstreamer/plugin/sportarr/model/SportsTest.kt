package app.nzstreamer.plugin.sportarr.model

import app.nzstreamer.plugin.sportarr.source.KeptLeague
import app.nzstreamer.plugin.sportarr.source.SportsList
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The model built from a read's list (L643, L644): each event under the league whose route listed it, with its family,
 * its card's type, its parts and its session; the season's years and the round's number as L644 reads them; the list's
 * rows, each weekend once.
 */
class SportsTest {
    @Test
    fun `each event is built under its league, with its family and its card's type`() {
        val sports = sports(
            PREMIER to listOf(on(PREMIER, 1, at("2026-11-21T15:00"), "Lantern Bay FC vs Copper Crown United")),
            FORMULA_1 to kestrelWeekend(firstId = 10),
            UFC to listOf(ufc999(), on(UFC, 32, at("2026-11-14T23:00"), "UFC Fight Night: Brisk vs. Loam")),
            BOXING to listOf(on(BOXING, 33, at("2026-11-21T22:00"), "Quessel vs. Halde")),
            SKIING to listOf(on(SKIING, 34, at("2026-11-22T10:30"), "Kestrel · Men's Downhill")),
        )

        assertEquals(listOf(1, 2, 4, 6, 7), sports.leagues.map { it.id }, "Sportarr's order")
        fun event(id: Int) = sports.events.single { it.id == id }
        assertEquals(PREMIER.name, event(1).league.name)
        assertEquals(Family.TeamGame, event(1).family)
        assertEquals(Family.Motorsport, event(10).family)
        assertEquals(SessionKind.Practice, event(10).session!!.kind)
        assertEquals(CardType.Numbered, event(31).cardType)
        assertEquals(CardType.FightNight, event(32).cardType)
        assertEquals(CardType.Matchup, event(33).cardType)
        assertEquals(Family.Generic, event(34).family)
        assertNull(event(1).cardType)
        assertNull(event(1).session)
        assertEquals(at("2026-11-21T15:00"), event(1).start)
        assertEquals("Lantern Bay FC vs Copper Crown United", event(1).title)
    }

    @Test
    fun `a Combat league's card has the parts and runtimes it has under Fighting, where it was one Generic part`() {
        val combat = KeptLeague(UFC.id, UFC.name, "Combat")
        fun parts(league: KeptLeague) = sports(league to listOf(ufc999().copy(sport = league.sport))).events.single().let { event ->
            Triple(event.family, event.cardType, event.parts.map { Triple(it.key, it.label, it.runtimeMinutes) })
        }
        assertEquals(
            Triple(
                Family.FightCard,
                CardType.Numbered,
                listOf(
                    Triple("ev-000031:early-prelims", "Early Prelims", 90),
                    Triple("ev-000031:prelims", "Prelims", 120),
                    Triple("ev-000031:main-card", "Main Card", 180),
                ),
            ),
            parts(combat),
        )
        assertEquals(parts(UFC), parts(combat), "as under Fighting")
        // Read from the league's Combat: the card's part names alone would make it a fight card without the word.
        assertEquals(FamilyFrom.LeagueSport, sports(combat to listOf(ufc999().copy(sport = "Combat"))).events.single().familyFrom)
    }

    @Test
    fun `an event's own sport names its family and its runtime where its league's names none`() {
        val multi = KeptLeague(21, "Kestrel Games", "Exhibition")
        val sports = sports(
            multi to listOf(
                on(multi, 1, at("2026-11-21T18:00"), "Northfield Rovers vs Easton Athletic").copy(sport = "American Football"),
                on(multi, 2, at("2026-11-21T22:00"), "Kestrel Fight Night: Brisk vs. Loam", parts = listOf("Prelims", "Main Card")),
                on(multi, 3, at("2026-11-21T10:00"), "Kestrel Open Final").copy(sport = "Darts"),
            ),
        )
        fun event(id: Int) = sports.events.single { it.id == id }
        assertEquals(Family.TeamGame, event(1).family)
        assertEquals("American Football", event(1).sport)
        assertEquals(listOf(190), event(1).parts.map { it.runtimeMinutes }, "American football's, not another team game's")
        assertEquals(Family.FightCard to CardType.FightNight, event(2).family to event(2).cardType)
        assertEquals(listOf("prelims" to 120, "main-card" to 180), event(2).parts.map { it.id to it.runtimeMinutes })
        assertEquals(Family.Generic, event(3).family)
        assertEquals("Exhibition", event(3).sport, "the league's word, where no sport named a family")
    }

    @Test
    fun `an event is kept under the league whose route listed it, and nowhere else`() {
        val stray = on(PREMIER, 2, at("2026-11-21T15:00"), "Wrenford Albion vs Brackenmoor FC").copy(leagueId = 99)
        val list = SportsList(
            at("2026-11-22T20:41"), listOf(PREMIER),
            listOf(on(PREMIER, 1, at("2026-11-21T15:00"), "Lantern Bay FC vs Copper Crown United"), stray), cut = false,
        )
        assertEquals(listOf(1), Sports.of(list).events.map { it.id })
    }

    @Test
    fun `an event's key is kept once, the first of the list`() {
        val first = on(PREMIER, 1, at("2026-11-21T15:00"), "Lantern Bay FC vs Copper Crown United").copy(externalId = "ev-000731")
        val again = on(PREMIER, 2, at("2026-11-14T15:00"), "Wrenford Albion vs Brackenmoor FC").copy(externalId = "ev-000731")
        val sports = sports(PREMIER to listOf(first, again))
        assertEquals(listOf(1), sports.events.map { it.id })
        assertEquals(1, sports.event("ev-000731")!!.id)
    }

    @Test
    fun `a fight card keeps the type its list kept for it, and one seen first is typed against its league`() {
        val nights = listOf(
            on(VELMORA, 51, at("2026-11-23T01:00"), "Velmora Monday Brawl"),
            on(VELMORA, 52, at("2026-11-16T01:00"), "Velmora Monday Brawl"),
        )
        val list = SportsList(at("2026-11-23T20:41"), listOf(VELMORA), nights, cut = false)
        assertEquals(listOf(CardType.WeeklyShow, CardType.WeeklyShow), Sports.of(list).events.map { it.cardType })

        // The night before was read alone once, and kept as a named event: it stays one, with its two parts.
        val kept = Sports.of(list.copy(cardTypes = mapOf("ev-000052" to CardType.Premium, "ev-000099" to CardType.Numbered)))
        assertEquals(listOf("ev-000052:pre-show", "ev-000052:main-show"), kept.event("ev-000052")!!.parts.map { it.key })
        assertEquals(CardType.WeeklyShow, kept.event("ev-000051")!!.cardType, "seen for the first time")
        assertEquals(mapOf("ev-000051" to CardType.WeeklyShow, "ev-000052" to CardType.Premium), kept.cardTypes, "what the list keeps next")

        // A type kept for an event that isn't a fight card now is never used, nor kept.
        val game = SportsList(at("2026-11-23T20:41"), listOf(PREMIER), listOf(on(PREMIER, 1, at("2026-11-21T15:00"), "Lantern Bay FC vs Copper Crown United")), false)
        val built = Sports.of(game.copy(cardTypes = mapOf("ev-000001" to CardType.Premium)))
        assertEquals(listOf("ev-000001:full"), built.event("ev-000001")!!.parts.map { it.key })
        assertEquals(emptyMap(), built.cardTypes)
    }

    @Test
    fun `the season's years are its four-digit years, a split season's two, else the start's UTC year`() {
        fun years(season: String?, start: Instant = at("2026-11-21T15:00")): List<Int> =
            sports(PREMIER to listOf(on(PREMIER, 1, start, "Lantern Bay FC vs Copper Crown United").copy(season = season))).events.single().seasonYears

        assertEquals(listOf(2026), years("2026"))
        assertEquals(listOf(2025, 2026), years("2025-2026"))
        assertEquals(listOf(2024, 2025), years("2024-25"))
        assertEquals(listOf(2024, 2025), years("2024/25"))
        assertEquals(listOf(2026), years(null))
        assertEquals(listOf(2026), years("Week 10"))
        assertEquals(listOf(2027), years(null, at("2027-01-01T00:30")), "the UTC year, wherever the device is")
        assertEquals(listOf(2026), years(null, at("2026-12-31T23:30")))
    }

    @Test
    fun `the list's rows are each weekend once and every other event alone, newest first`() {
        val sports = sports(
            FORMULA_1 to kestrelWeekend(),
            PREMIER to listOf(on(PREMIER, 41, at("2026-11-22T16:30"), "Lantern Bay FC vs Copper Crown United")),
            UFC to listOf(ufc999()),
        )
        assertEquals(listOf("ev-000041", "2:r20", "ev-000031"), sports.listed.map { it.key })
        assertEquals(sports.readAt, at("2026-11-22T20:41"))
        assertEquals(false, sports.cut)
    }
}
