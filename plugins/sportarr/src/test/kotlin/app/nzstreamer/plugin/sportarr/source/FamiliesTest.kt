package app.nzstreamer.plugin.sportarr.source

import app.nzstreamer.plugin.sportarr.model.CardType
import app.nzstreamer.plugin.sportarr.model.Family
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * L643: the family comes from the league's sport (the event's when the league has none), folded, in the plugin's own
 * groups; an event steps down to Generic, never up, when its title can't carry its family's matching; and a fight card's
 * type comes from its title and its league, in L643's order.
 */
class FamiliesTest {
    @Test
    fun `each group's words name its family`() {
        val teams = listOf(
            "soccer", "association football", "futbol", "football", "american football", "gridiron football", "canadian football",
            "australian rules football", "australian football", "aussie rules", "gaelic football", "hurling", "camogie", "rugby union", "rugby",
            "rugby sevens", "rugby 7s", "rugby league", "basketball", "3x3 basketball", "ice hockey", "hockey", "field hockey", "roller hockey",
            "inline hockey", "rink hockey", "bandy", "floorball", "baseball", "softball", "cricket", "volleyball", "beach volleyball", "handball",
            "team handball", "water polo", "waterpolo", "futsal", "beach soccer", "lacrosse", "netball", "korfball", "kabaddi", "sepak takraw",
            "polo", "shinty", "curling",
        )
        val motorsport = listOf(
            "motorsport", "motorsports", "motor sport", "motor racing", "auto racing", "car racing", "open wheel racing", "single seater racing",
            "formula racing", "touring car racing", "touring cars", "sports car racing", "sportscar racing", "endurance racing", "stock car racing",
            "oval racing", "drag racing", "rallying", "rally", "rally racing", "rallycross", "karting", "kart racing", "go kart racing",
            "truck racing", "drifting", "motorcycle racing", "motorbike racing", "motorcycling", "motorcycle road racing", "superbike racing",
            "motocross", "supercross", "motorcycle trials", "speedway", "motorcycle speedway", "powerboat racing", "powerboating",
        )
        val fights = listOf(
            "boxing", "bare knuckle boxing", "bareknuckle boxing", "mixed martial arts", "mma", "kickboxing", "kick boxing", "muay thai",
            "thai boxing", "lethwei", "submission grappling", "grappling", "brazilian jiu jitsu", "jiu jitsu", "combat sports", "combat sport",
            "fighting", "wrestling", "professional wrestling", "pro wrestling",
        )
        fights.forEach { assertEquals(Family.FightCard, Families.ofSport(it), it) }
        motorsport.forEach { assertEquals(Family.Motorsport, Families.ofSport(it), it) }
        teams.forEach { assertEquals(Family.TeamGame, Families.ofSport(it), it) }
    }

    @Test
    fun `Sportarr's words are folded, Football a team game, Combat Sports a fight card and Car Racing motorsport`() {
        assertEquals(Family.TeamGame, Families.ofSport("Football"))
        assertEquals(Family.TeamGame, Families.ofSport("American Football"))
        assertEquals(Family.TeamGame, Families.ofSport("Ice Hockey"))
        assertEquals(Family.TeamGame, Families.ofSport("ice-hockey"))
        assertEquals(Family.TeamGame, Families.ofSport("  Australian   Football "))
        assertEquals(Family.FightCard, Families.ofSport("Combat Sports"))
        assertEquals(Family.FightCard, Families.ofSport("Mixed-Martial-Arts"))
        assertEquals(Family.FightCard, Families.ofSport("Muay Thai"))
        assertEquals(Family.Motorsport, Families.ofSport("Car-Racing"))
        assertEquals(Family.Motorsport, Families.ofSport("Motorsport"))
        assertEquals(Family.Motorsport, Families.ofSport("AUTO RACING"))
    }

    @Test
    fun `every other sport, an unknown one and none are Generic`() {
        listOf("Tennis", "Golf", "Darts", "Snooker", "Cycling", "Athletics", "Skiing", "Olympics", "Esports", "Judo", "", "   ", null)
            .forEach { assertEquals(Family.Generic, Families.ofSport(it), "$it") }
    }

    @Test
    fun `the league's sport names the family, the event's when the league has none`() {
        val race = event(1, AT, title = "Grand Prix of Kestrel - Race", sport = "Soccer")
        assertEquals(Family.Motorsport, Families.of("Motorsport", race))
        assertEquals(Family.TeamGame, Families.of(null, event(2, AT, title = "Lantern Bay FC vs Copper Crown United", sport = "Soccer")))
        assertEquals(Family.TeamGame, Families.of(" ", event(2, AT, title = "Lantern Bay FC vs Copper Crown United", sport = "Soccer")))
        assertEquals(Family.Generic, Families.of(null, event(3, AT, title = "Kestrel · Men's Downhill", sport = null)))
    }

    @Test
    fun `a team game needs two sides, from Sportarr's team names or its title`() {
        fun family(title: String, home: String? = null, away: String? = null) = Families.of("Soccer", event(1, AT, title = title, home = home, away = away))

        assertEquals(Family.TeamGame, family("Matchday 12", "Lantern Bay FC", "Copper Crown United"))
        listOf(
            "Lantern Bay FC vs. Copper Crown United", "Lantern Bay FC vs Copper Crown United", "Lantern Bay FC v Copper Crown United",
            "Easton Comets @ Marrowby Lynx", "Easton Comets at Marrowby Lynx", "Lantern Bay FC VS Copper Crown United",
        ).forEach { assertEquals(Family.TeamGame, family(it), it) }

        // One team named, or none: Generic.
        assertEquals(Family.Generic, family("Matchday 12", "Lantern Bay FC", null))
        assertEquals(Family.Generic, family("Matchday 12", "Lantern Bay FC", " "))
        assertEquals(Family.Generic, family("Kestrel Cup Final"))
        assertEquals(Family.Generic, family("Lantern Bay FC vs "))
        assertEquals(Family.Generic, family("vs. Copper Crown United"))
    }

    @Test
    fun `the first separator found splits a title into its sides`() {
        assertEquals("Lantern Bay FC" to "Copper Crown United", Families.sides("Lantern Bay FC vs. Copper Crown United"))
        assertEquals("Kestrel Club 01" to "Kestrel Club 02", Families.sides("Kestrel Club 01 vs Kestrel Club 02"))
        assertEquals("Wrenford Albion" to "Gildenford FC", Families.sides("Wrenford Albion v Gildenford FC"))
        assertEquals("Easton Comets" to "Lantern Bay Herons", Families.sides("Easton Comets @ Lantern Bay Herons"))
        assertEquals("Saltmere Gulls" to "Marrowby Lynx", Families.sides("Saltmere Gulls at Marrowby Lynx"))
        // " vs " comes before " at " in the order, whatever stands first in the title.
        assertEquals("Lowmarsh Rovers at Home" to "Saltmere City", Families.sides("Lowmarsh Rovers at Home vs Saltmere City"))
        assertNull(Families.sides("Kestrel Cup Final"))
        assertNull(Families.sides("Lantern Bay FC vs"))
    }

    @Test
    fun `a motorsport event whose title holds nothing but session words is Generic`() {
        listOf("Race", "Practice 1", "Free Practice 2", "FP3", "Qualifying", "Sprint Qualifying", "Sprint Shootout", "Sprint", "Warm-Up", "Grand Prix",
            "Race 2", "Test Day 3", "Testing", "Rennen", "Essais Libres", "Vrije Training", "", " - ")
            .forEach { assertEquals(Family.Generic, Families.of("Motorsport", event(1, AT, title = it)), it) }
        listOf("Grand Prix of Kestrel - Race", "Grand Prix of Vellmar", "Kestrel Grand Prix - Practice 1", "6 Hours of Oskelund", "Pre-Race Show")
            .forEach { assertEquals(Family.Motorsport, Families.of("Motorsport", event(1, AT, title = it)), it) }
    }

    @Test
    fun `a fight card whose title holds no word but dates and numbers is Generic`() {
        listOf("999", "2026-11-21", "21 November 2026", "21.11.2026", "Nov 21", "")
            .forEach { assertEquals(Family.Generic, Families.of("Fighting", event(1, AT, title = it)), it) }
        listOf("UFC 999", "UFC 999: Quessel vs. Halde", "Quessel vs. Halde", "Velmora Monday Brawl")
            .forEach { assertEquals(Family.FightCard, Families.of("Fighting", event(1, AT, title = it)), it) }
    }

    @Test
    fun `the five card types from titles`() {
        val ufc = listOf(
            event(1, AT, title = "UFC 999: Quessel vs. Halde"),
            event(2, AT.minus(WEEK), title = "UFC Fight Night: Brisk vs. Loam"),
        )
        assertEquals(CardType.Numbered, Families.cardType("UFC", ufc[0], ufc))
        assertEquals(CardType.FightNight, Families.cardType("UFC", ufc[1], ufc))

        // A weekly show three weeks running, its titles told apart by their dates.
        val velmora = listOf(
            event(11, AT, title = "Velmora Monday Brawl 2026-11-23"),
            event(12, AT.minus(WEEK), title = "Velmora Monday Brawl 2026-11-16"),
            event(13, AT.minus(WEEK).minus(WEEK).plus(Duration.ofHours(20)), title = "Velmora Monday Brawl 2026-11-09"),
            event(14, AT.plus(Duration.ofDays(12)), title = "Velmora Iron Crown"),
            event(15, AT.plus(Duration.ofDays(13)), title = "Velmora Iron Crown: Night 2"),
        )
        velmora.take(3).forEach { assertEquals(CardType.WeeklyShow, Families.cardType("Velmora Wrestling", it, velmora), it.title) }
        assertEquals(CardType.Premium, Families.cardType("Velmora Wrestling", velmora[3], velmora))
        assertEquals(CardType.Premium, Families.cardType("Velmora Wrestling", velmora[4], velmora))

        val boxing = listOf(event(21, AT, title = "Quessel vs. Halde"))
        assertEquals(CardType.Matchup, Families.cardType("Kestrel Boxing", boxing[0], boxing))
    }

    @Test
    fun `a numbered card is the league's or the title's first word, then a number`() {
        fun type(league: String, title: String) = event(1, AT, title = title).let { Families.cardType(league, it, listOf(it)) }

        assertEquals(CardType.Numbered, type("KCL", "KCL 12: Brisk vs. Loam"))
        assertEquals(CardType.Numbered, type("Kestrel Boxing", "Kestrel 12"))
        assertEquals(CardType.Numbered, type("KCL", "The KCL 12"))
        assertEquals(CardType.FightNight, type("KCL", "KCL Fight Night 40: Brisk vs. Loam"))
        assertEquals(CardType.Premium, type("KCL", "Velmora Iron Crown 12"))
        assertEquals(CardType.Premium, type("Velmora Wrestling", "Velmora Iron Crown: Night 2"))
        // A bare "A vs. B" is a matchup in any fighting league; one with a name before it isn't bare.
        assertEquals(CardType.Matchup, type("UFC", "Brisk vs Loam"))
        assertEquals(CardType.Premium, type("Kestrel Boxing", "Kestrel Boxing: Quessel vs. Halde"))
        assertEquals(CardType.Premium, type("Kestrel Boxing", "Kestrel Boxing - Quessel vs. Halde"))
    }

    @Test
    fun `a show is weekly only with another of its titles six to eight days away`() {
        fun types(vararg days: Long): List<CardType> {
            val shows = days.mapIndexed { i, day -> event(100 + i, AT.plus(Duration.ofDays(day)), title = "Velmora Monday Brawl") }
            return shows.map { Families.cardType("Velmora Wrestling", it, shows) }
        }
        assertEquals(listOf(CardType.Premium), types(0))
        assertEquals(listOf(CardType.WeeklyShow, CardType.WeeklyShow), types(0, 7))
        assertEquals(listOf(CardType.WeeklyShow, CardType.WeeklyShow), types(0, 6))
        assertEquals(listOf(CardType.WeeklyShow, CardType.WeeklyShow), types(0, 8))
        assertEquals(listOf(CardType.Premium, CardType.Premium), types(0, 14))
        assertEquals(listOf(CardType.Premium, CardType.Premium), types(0, 1))
        assertEquals(listOf(CardType.WeeklyShow, CardType.Premium, CardType.WeeklyShow), types(0, 3, 7))
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-11-23T01:00:00Z")
        val WEEK: Duration = Duration.ofDays(7)
    }
}
