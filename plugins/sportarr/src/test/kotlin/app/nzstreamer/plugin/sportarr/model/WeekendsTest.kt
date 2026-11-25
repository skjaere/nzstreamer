package app.nzstreamer.plugin.sportarr.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * L644's weekends: one league's motorsport sessions grouped for display alone, by the same round number where both have
 * one, by the same text where the round holds no number, else by the same title less its trailing session words within
 * four days. The weekend's key, title and sessions' labels; a round's number is its one whole number.
 */
class WeekendsTest {
    @Test
    fun `sessions with the same round number are one weekend, Round 20 and 20 alike`() {
        val events = kestrelWeekend().mapIndexed { i, event -> if (i % 2 == 0) event.copy(round = "Round 20") else event } +
            on(FORMULA_1, 9, at("2026-11-15T15:00"), "Grand Prix of Vellmar - Race", round = "19")
        val sports = sports(FORMULA_1 to events)

        assertEquals(listOf("2:r19", "2:r20"), sports.weekends.map { it.key }.sorted())
        val weekend = sports.weekend("2:r20")!!
        assertEquals("Grand Prix of Kestrel", weekend.title)
        assertEquals(20, weekend.roundNumber)
        assertEquals(listOf(1, 2, 3, 4, 5), weekend.sessions.map { it.id }, "in time order")
        assertEquals(listOf("Practice 1", "Practice 2", "Practice 3", "Qualifying", "Race"), weekend.sessions.map { it.session!!.label })
        weekend.sessions.forEach { assertSame(weekend, sports.weekendOf(it)) }
    }

    @Test
    fun `a round's number is its one whole number, and a round with two or none has no number`() {
        mapOf("20" to 20, "Round 20" to 20, "Week 10" to 10, "Round 07" to 7, " 20 " to 20).forEach { (round, number) ->
            assertEquals(number, Rounds.number(round), round)
        }
        listOf("Round 20 Race 2", "Final", "Quarterfinals", "", null).forEach { assertNull(Rounds.number(it), "$it") }
    }

    @Test
    fun `a round with two numbers has no number, and its sessions group by their title`() {
        val sports = sports(
            FORMULA_1 to listOf(
                on(FORMULA_1, 1, at("2026-11-21T15:00"), "Grand Prix of Kestrel - Qualifying", round = "Round 20 Race 1"),
                on(FORMULA_1, 2, at("2026-11-22T15:00"), "Grand Prix of Kestrel - Race", round = "Round 20 Race 2"),
            ),
        )
        val weekend = sports.weekends.single()
        assertNull(weekend.roundNumber)
        assertEquals("2:sev-000001", weekend.key)
    }

    @Test
    fun `rounds written with no number group by their text, trimmed and case folded`() {
        val sports = sports(
            FORMULA_1 to listOf(
                on(FORMULA_1, 1, at("2026-11-20T12:30"), "Grand Prix of Kestrel - Practice 1", round = "Final"),
                on(FORMULA_1, 2, at("2026-11-22T15:00"), "Grand Prix of Kestrel - Race", round = " final "),
                // Another text is another weekend, at the same place and days.
                on(FORMULA_1, 3, at("2026-11-21T15:00"), "Grand Prix of Kestrel - Qualifying", round = "Opening"),
            ),
        )
        assertEquals(listOf(listOf(1, 2), listOf(3)), sports.weekends.map { weekend -> weekend.sessions.map { it.id } }.sortedBy { it.first() })
        assertEquals("2:sev-000001", sports.weekendOf(sports.events.single { it.id == 2 })!!.key)
    }

    @Test
    fun `with no round, the same title less its session words within four days`() {
        val sports = sports(
            FORMULA_1 to listOf(
                on(FORMULA_1, 1, at("2026-11-20T12:30"), "Grand Prix of Vellmar - Practice 1"),
                on(FORMULA_1, 2, at("2026-11-21T15:00"), "Grand Prix of Vellmar: Qualifying"),
                on(FORMULA_1, 3, at("2026-11-22T15:00"), "Grand Prix of Vellmar"),
                on(FORMULA_1, 4, at("2026-11-24T12:30"), "grand prix of vellmar - Sprint Qualifying"),
                // Five days after the first: another weekend of the same title.
                on(FORMULA_1, 5, at("2026-11-25T12:31"), "Grand Prix of Vellmar - Practice 1"),
                on(FORMULA_1, 6, at("2026-11-22T16:00"), "Grand Prix of Tarnby - Race"),
            ),
        )
        val weekends = sports.weekends.sortedBy { it.sessions.first().start }
        assertEquals(listOf(listOf(1, 2, 3, 4), listOf(6), listOf(5)), weekends.map { weekend -> weekend.sessions.map { it.id } })
        assertEquals("Grand Prix of Vellmar", weekends[0].title)
        assertEquals("2:sev-000001", weekends[0].key)
        assertEquals(listOf("Practice 1", "Qualifying", "Race", "Sprint Qualifying"), weekends[0].sessions.map { it.session!!.label })
        assertEquals("Grand Prix of Tarnby", weekends[1].title)
    }

    @Test
    fun `two series at one place are two weekends`() {
        val sports = sports(FORMULA_1 to kestrelWeekend(), MOTO_GP to kestrelWeekend(MOTO_GP, firstId = 11))
        assertEquals(setOf("2:r20", "3:r20"), sports.weekends.map { it.key }.toSet())
        assertEquals(listOf(11, 12, 13, 14, 15), sports.weekend("3:r20")!!.sessions.map { it.id })
    }

    @Test
    fun `the race needs no session word, and is labelled Race`() {
        val sports = sports(
            FORMULA_1 to listOf(
                on(FORMULA_1, 1, at("2026-11-21T15:00"), "Grand Prix of Oskelund - Qualifying", round = "19"),
                on(FORMULA_1, 2, at("2026-11-22T15:00"), "Grand Prix of Oskelund", round = "19"),
            ),
        )
        val race = sports.events.single { it.id == 2 }.session!!
        assertEquals("Race", race.label)
        assertEquals(SessionKind.Race, race.kind)
        assertEquals("Grand Prix of Oskelund", sports.weekends.single().title)
    }

    @Test
    fun `the weekend key is the league and r with the round's number, else s with the first session's key`() {
        val local = kestrelWeekend().map { it.copy(round = null) }.mapIndexed { i, event -> if (i == 0) event.copy(externalId = null) else event }
        assertEquals("2:slocal:1", sports(FORMULA_1 to local).weekends.single().key)
        assertEquals("2:r20", sports(FORMULA_1 to kestrelWeekend()).weekends.single().key)
    }

    @Test
    fun `only motorsport sessions make weekends, and the list has each weekend once`() {
        val sports = sports(
            FORMULA_1 to kestrelWeekend() + on(FORMULA_1, 6, at("2026-11-21T09:00"), "Practice 1"),
            PREMIER to listOf(on(PREMIER, 7, at("2026-11-21T15:00"), "Lantern Bay FC vs Copper Crown United")),
            UFC to listOf(ufc999()),
        )
        assertEquals(listOf("2:r20"), sports.weekends.map { it.key })
        assertNull(sports.weekendOf(sports.events.single { it.id == 6 }), "a title of session words alone is Generic, listed alone")
        assertNull(sports.weekendOf(sports.events.single { it.id == 7 }))
        assertEquals(
            setOf("2:r20", "ev-000006", "ev-000007", "ev-000031"),
            sports.listed.map { it.key }.toSet(),
        )
        assertEquals(4, sports.listed.size)
    }
}
