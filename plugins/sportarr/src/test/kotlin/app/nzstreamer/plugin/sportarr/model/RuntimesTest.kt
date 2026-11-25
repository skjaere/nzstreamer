package app.nzstreamer.plugin.sportarr.model

import app.nzstreamer.plugin.sportarr.source.Families
import app.nzstreamer.plugin.sportarr.source.KeptLeague
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * L644's one runtime table, in minutes, which feeds the host's ranking and the size floor: team games by Sportarr's sport
 * words, folded; motorsport by the session; fight cards by the part and the card's type; Generic, testing and an endurance
 * race unknown.
 */
class RuntimesTest {
    @Test
    fun `every team game's row, by the sport's folded words`() {
        val rows = mapOf(
            "soccer" to 110, "association football" to 110, "futbol" to 110,
            "football" to 190, "american football" to 190, "gridiron football" to 190,
            "australian rules football" to 150, "australian football" to 150, "aussie rules" to 150,
            "rugby union" to 110, "rugby" to 110,
            // Short games: their own figure, not their sport's.
            "rugby sevens" to 30, "rugby 7s" to 30, "3x3 basketball" to 30,
            "rugby league" to 100,
            "basketball" to 150,
            "ice hockey" to 150, "hockey" to 150,
            "baseball" to 180,
            "cricket" to 210,
        )
        rows.forEach { (sport, minutes) -> assertEquals(minutes, Runtimes.teamGame(sport), sport) }
        // Each row's words are a team game's as the families name it: the two lists name a sport alike.
        rows.keys.forEach { assertEquals(Family.TeamGame, Families.ofSport(it), it) }
        // Any other team game.
        listOf("volleyball", "handball", "netball", "field hockey", "lacrosse", "gaelic football", "hurling")
            .forEach { assertEquals(110, Runtimes.teamGame(it), it) }
    }

    @Test
    fun `Football, Australian Football and Ice Hockey as Sportarr writes them`() {
        assertEquals(190, Runtimes.teamGame("Football"))
        assertEquals(150, Runtimes.teamGame("Australian Football"))
        assertEquals(150, Runtimes.teamGame("Ice Hockey"))
        assertEquals(150, Runtimes.teamGame("Ice-Hockey"))
        assertEquals(100, Runtimes.teamGame(" Rugby  League "))

        // Through the model, by the league's sport, or the event's where the league has none.
        fun game(sport: String?, eventSport: String? = null): Int? {
            val league = KeptLeague(1, "Kestrel Premier Division", sport)
            val game = on(league, 1, at("2026-11-21T15:00"), "Lantern Bay FC vs Copper Crown United").copy(sport = eventSport)
            return sports(league to listOf(game)).events.single().parts.single().runtimeMinutes
        }
        assertEquals(190, game("Football"))
        assertEquals(150, game("Australian Football"))
        assertEquals(150, game("Ice Hockey"))
        assertEquals(110, game("Soccer"))
        assertEquals(180, game(null, eventSport = "Baseball"))
        assertEquals(150, sports(NBA to listOf(on(NBA, 1, at("2026-11-22T01:30"), "Saltmere Gulls vs Marrowby Lynx"))).events.single().headline.runtimeMinutes)
    }

    @Test
    fun `motorsport sessions by their words`() {
        val rows = mapOf(
            "Practice 1" to 65, "Free Practice 2" to 65, "FP3" to 65,
            "Qualifying" to 70,
            "Sprint Qualifying" to 45, "Sprint Shootout" to 45,
            "Sprint" to 45, "Sprint Race" to 45,
            "Race" to 120, "Race 2" to 120,
            "Warm-up" to 30, "Warm Up" to 30,
            // The titles' session words are the release names': one list.
            "Q2" to 70, "Superpole" to 70, "Superpole Race" to 45, "Feature Race" to 120, "Zeittraining" to 70,
        )
        rows.forEach { (session, minutes) -> assertEquals(minutes, runtime("Grand Prix of Kestrel - $session"), session) }
        assertEquals(120, runtime("Grand Prix of Kestrel"), "a title with no session word is the race")
        listOf("Testing", "Test Day 2", "Shakedown").forEach { assertNull(runtime("Grand Prix of Kestrel - $it"), it) }
    }

    @Test
    fun `an endurance race is unknown, and its practice isn't`() {
        assertNull(runtime("6 Hours of Oskelund", ENDURANCE))
        assertNull(runtime("6 Hours of Oskelund - Race", ENDURANCE))
        assertNull(runtime("Kestrel Endurance Series - Race", ENDURANCE))
        assertEquals(65, runtime("6 Hours of Oskelund - Practice 1", ENDURANCE))
        assertEquals(70, runtime("6 Hours of Oskelund - Qualifying", ENDURANCE))
    }

    @Test
    fun `fight cards by the part and the card's type`() {
        val brawls = (0L..2L).map { week ->
            on(VELMORA, 10 + week.toInt(), at("2026-11-23T01:00").minus(Duration.ofDays(7 * week)), "Velmora Monday Brawl")
        }
        val sports = sports(
            UFC to listOf(ufc999(), on(UFC, 32, at("2026-11-14T23:00"), "UFC Fight Night: Brisk vs. Loam", parts = listOf("Lead Card", "Main Card"))),
            BOXING to listOf(on(BOXING, 33, at("2026-11-21T22:00"), "Quessel vs. Halde")),
            VELMORA to brawls + on(VELMORA, 34, at("2026-11-28T23:00"), "Velmora Iron Crown: Night 2", parts = listOf("Countdown", "Main Show")),
        )
        fun minutes(id: Int) = sports.events.single { it.id == id }.parts.map { it.id to it.runtimeMinutes }

        assertEquals(listOf("early-prelims" to 90, "prelims" to 120, "main-card" to 180), minutes(31))
        assertEquals(listOf("prelims" to 120, "main-card" to 180), minutes(32), "a lead card is the prelims")
        assertEquals(listOf("full" to 240), minutes(33), "a matchup")
        assertEquals(listOf("full" to 150), minutes(10), "a weekly show")
        assertEquals(listOf("pre-show" to 60, "main-show" to 240), minutes(34))
    }

    @Test
    fun `Generic is unknown, a game or card stepped down to it too`() {
        val sports = sports(
            SKIING to listOf(on(SKIING, 1, at("2026-11-22T10:30"), "Kestrel · Men's Downhill")),
            PREMIER to listOf(on(PREMIER, 2, at("2026-11-21T15:00"), "Premier League Matchday 12")),
            UFC to listOf(on(UFC, 3, at("2026-11-21T23:00"), "2026-11-21")),
            FORMULA_1 to listOf(on(FORMULA_1, 4, at("2026-11-21T15:00"), "Qualifying")),
        )
        sports.events.forEach { event ->
            assertEquals(Family.Generic, event.family, event.title)
            assertNull(event.parts.single().runtimeMinutes, event.title)
        }
    }

    private fun runtime(title: String, league: KeptLeague = FORMULA_1): Int? =
        sports(league to listOf(on(league, 1, at("2026-11-22T15:00"), title))).events.single().parts.single().runtimeMinutes
}
