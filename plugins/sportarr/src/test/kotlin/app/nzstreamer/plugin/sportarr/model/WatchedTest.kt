package app.nzstreamer.plugin.sportarr.model

import app.nzstreamer.plugin.sportarr.ui.Words
import io.skjaere.usenet.plugin.WatchInfo
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L644's watched: a part as the host says; an event by its headline part; a weekend by its race, or by every race where a
 * round has several. Counts: a card's parts, a weekend's sessions that have started. The tab's Up next: parts in progress,
 * the last played first; then the next part of a card or weekend started and not watched; three at most; nothing of a
 * watched card or weekend.
 */
class WatchedTest {
    private val sports = sports(
        FORMULA_1 to kestrelWeekend(),
        UFC to listOf(ufc999()),
        PREMIER to listOf(
            on(PREMIER, 41, at("2026-11-22T14:00"), "Gildenford FC vs Marrowby United"),
            on(PREMIER, 42, at("2026-11-22T16:30"), "Lantern Bay FC vs Copper Crown United"),
        ),
        VELMORA to listOf(on(VELMORA, 51, at("2026-11-28T23:00"), "Velmora Iron Crown: Night 2", parts = listOf("Countdown", "Main Show"))),
    )
    private val weekend = sports.weekend("2:r20")!!
    private val card = sports.event("ev-000031")!!
    private val game = sports.event("ev-000042")!!

    /** The key of the Kestrel weekend's session [n] (1 to 5: three practices, qualifying, the race). */
    private fun session(n: Int) = weekend.sessions[n - 1].headline.key

    private fun cardPart(id: String) = Keys.part(card.key, id)

    @Test
    fun `a card is watched by its main card, a premium event by its main show, a game by its whole`() {
        assertFalse(Watched.event(card, mapOf(cardPart("early-prelims") to watched(), cardPart("prelims") to watched())))
        assertTrue(Watched.event(card, mapOf(cardPart("main-card") to watched())))
        assertFalse(Watched.event(card, mapOf(cardPart("main-card") to played(60_000, SUNDAY))), "in progress isn't watched")

        val premium = sports.event("ev-000051")!!
        assertFalse(Watched.event(premium, mapOf(Keys.part(premium.key, "pre-show") to watched())))
        assertTrue(Watched.event(premium, mapOf(Keys.part(premium.key, "main-show") to watched())))

        assertTrue(Watched.event(game, mapOf("ev-000042:full" to watched())))
        assertFalse(Watched.event(game, emptyMap()))
        assertTrue(Watched.listed(game, mapOf("ev-000042:full" to watched())))
    }

    @Test
    fun `a weekend is watched by its race`() {
        assertFalse(Watched.weekend(weekend, (1..4).associate { session(it) to watched() }), "everything but the race")
        assertTrue(Watched.weekend(weekend, mapOf(session(5) to watched())))
        assertTrue(Watched.listed(weekend, mapOf(session(5) to watched())))
    }

    @Test
    fun `a round with two races is watched once both are`() {
        val two = sports(
            MOTO_GP to listOf(
                on(MOTO_GP, 1, at("2026-11-21T15:00"), "Grand Prix of Tarnby - Race 1", round = "21"),
                on(MOTO_GP, 2, at("2026-11-22T15:00"), "Grand Prix of Tarnby - Race 2", round = "21"),
                on(MOTO_GP, 3, at("2026-11-21T10:00"), "Grand Prix of Tarnby - Qualifying", round = "21"),
            ),
        ).weekends.single()
        assertEquals(listOf(1, 2), two.headline.map { it.id })
        assertFalse(Watched.weekend(two, mapOf("ev-000001:full" to watched())))
        assertTrue(Watched.weekend(two, mapOf("ev-000001:full" to watched(), "ev-000002:full" to watched())))
    }

    @Test
    fun `a weekend followed without its race is watched once every session is`() {
        val noRace = sports(FORMULA_1 to kestrelWeekend().take(4)).weekends.single()
        assertFalse(Watched.weekend(noRace, (1..3).associate { "ev-00000$it:full" to watched() }))
        assertTrue(Watched.weekend(noRace, (1..4).associate { "ev-00000$it:full" to watched() }))
    }

    @Test
    fun `a card counts its parts, a weekend its sessions that have started`() {
        assertEquals(Count(1, 3), Watched.count(card, mapOf(cardPart("early-prelims") to watched()), SATURDAY_EVENING))
        assertEquals(Count(0, 3), Watched.count(card, emptyMap(), at("2026-11-21T12:00")), "a card's parts before it starts")

        // Saturday evening: the two Friday practices watched, qualifying half watched, the race still to come.
        val saturday = mapOf(session(1) to watched(), session(2) to watched(), session(4) to played(1_000_000, SATURDAY_EVENING))
        val count = Watched.count(weekend, saturday, SATURDAY_EVENING)
        assertEquals(Count(2, 4), count)
        assertEquals("2 of 4 watched", Words.watched(count))

        // After the race: qualifying watched too.
        assertEquals("3 of 5 watched", Words.watched(Watched.count(weekend, saturday + (session(4) to watched()), MONDAY)))
        assertEquals("2 of 3 watched", Words.watched(Watched.count(card, mapOf(cardPart("early-prelims") to watched(), cardPart("prelims") to watched()), SUNDAY)))
    }

    @Test
    fun `the strip has what is in progress first, the last played first, then the next part of a card or weekend`() {
        // As the tab opens on Sunday evening: the game and the race half watched, the game played last; UFC 999's prelims
        // watched.
        val watch = mapOf(
            game.headline.key to played(3_000_000, at("2026-11-22T20:30")),
            session(1) to watched(), session(2) to watched(), session(4) to watched(),
            session(5) to played(1_500_000, at("2026-11-22T19:00")),
            cardPart("early-prelims") to watched(at("2026-11-22T09:00")),
            cardPart("prelims") to watched(at("2026-11-22T10:00")),
        )
        val strip = Watched.upNext(sports.listed, watch, SUNDAY)

        assertEquals(listOf("ev-000042:full", session(5), cardPart("main-card")), strip.map { it.item.part.key })
        assertEquals(listOf(game, weekend, card), strip.map { it.listed })
        assertEquals(listOf(true, true, false), strip.map { it.info != null })
        assertEquals("3 of 5 watched", Words.watched(Watched.count(strip[1].listed, watch, SUNDAY)))
        assertEquals("2 of 3 watched", Words.watched(Watched.count(strip[2].listed, watch, SUNDAY)))
    }

    @Test
    fun `the next session goes on from the last one watched, never back to one skipped`() {
        val monday = mapOf(session(1) to watched(), session(2) to watched(), session(4) to watched())
        assertEquals(listOf(session(5)), Watched.upNext(sports.listed, monday, MONDAY).map { it.item.part.key })

        // Saturday evening with qualifying watched: the race hasn't started, so nothing of the weekend comes next yet.
        assertEquals(emptyList(), Watched.upNext(sports.listed, monday, SATURDAY_EVENING).map { it.item.part.key })

        // Only the first practice watched: the second comes next.
        assertEquals(listOf(session(2)), Watched.upNext(sports.listed, mapOf(session(1) to watched()), MONDAY).map { it.item.part.key })
    }

    @Test
    fun `a weekend caught up on by Mark watched comes before a card played days ago`() {
        // Saturday afternoon: two games half watched, the Friday and Saturday practices marked watched from the weekend's
        // page without a play, qualifying under way; a fight night's prelims played three days ago.
        val saturday = sports(
            FORMULA_1 to kestrelWeekend(),
            UFC to listOf(on(UFC, 32, at("2026-11-14T23:00"), "UFC Fight Night: Brisk vs. Loam", parts = listOf("Prelims", "Main Card"))),
            PREMIER to listOf(
                on(PREMIER, 41, at("2026-11-21T13:00"), "Gildenford FC vs Marrowby United"),
                on(PREMIER, 42, at("2026-11-21T13:00"), "Lantern Bay FC vs Copper Crown United"),
            ),
        )
        val sessions = saturday.weekend("2:r20")!!.sessions.map { it.headline.key }
        val watch = mapOf(
            "ev-000041:full" to played(2_000_000, at("2026-11-21T15:30")),
            "ev-000042:full" to played(3_000_000, at("2026-11-21T16:00")),
            sessions[0] to watched(), sessions[1] to watched(), sessions[2] to watched(),
            "ev-000032:prelims" to watched(at("2026-11-18T21:00")),
        )
        assertEquals(
            listOf("ev-000042:full", "ev-000041:full", sessions[3]),
            Watched.upNext(saturday.listed, watch, at("2026-11-21T16:30")).map { it.item.part.key },
        )
        // With nothing more in progress, the card comes after the weekend.
        assertEquals(
            listOf(sessions[3], "ev-000032:main-card"),
            Watched.upNext(saturday.listed, watch - "ev-000041:full" - "ev-000042:full", at("2026-11-21T16:30")).map { it.item.part.key },
        )
    }

    @Test
    fun `a card goes on to the next part after the last one watched`() {
        val prelimsOnly = mapOf(cardPart("prelims") to watched())
        assertEquals(listOf(cardPart("main-card")), Watched.upNext(sports.listed, prelimsOnly, SUNDAY).map { it.item.part.key })
        val early = mapOf(cardPart("early-prelims") to watched())
        assertEquals(listOf(cardPart("prelims")), Watched.upNext(sports.listed, early, SUNDAY).map { it.item.part.key })
    }

    @Test
    fun `a card or weekend with a part in progress shows that part alone`() {
        val watch = mapOf(session(1) to watched(), session(3) to played(600_000, SUNDAY), cardPart("early-prelims") to watched(), cardPart("prelims") to played(2_265_000, SATURDAY_EVENING))
        assertEquals(listOf(session(3), cardPart("prelims")), Watched.upNext(sports.listed, watch, MONDAY).map { it.item.part.key })
    }

    @Test
    fun `nothing of a watched card or weekend, its parts in progress included`() {
        val watch = mapOf(
            session(5) to watched(), session(3) to played(600_000, SUNDAY),
            cardPart("main-card") to watched(), cardPart("early-prelims") to played(600_000, SUNDAY),
        )
        assertEquals(emptyList(), Watched.upNext(sports.listed, watch, MONDAY))
    }

    @Test
    fun `nothing of a card or weekend nobody started, and three rows at most`() {
        assertEquals(emptyList(), Watched.upNext(sports.listed, emptyMap(), MONDAY))

        val many = sports(PREMIER to (1..5).map { on(PREMIER, it, at("2026-11-21T15:00"), "Wrenford Albion vs Brackenmoor FC") })
        val watch = (1..5).associate { "ev-00000$it:full" to played(60_000L * it, at("2026-11-22T1$it:00")) }
        assertEquals(listOf("ev-000005:full", "ev-000004:full", "ev-000003:full"), Watched.upNext(many.listed, watch, MONDAY).map { it.item.part.key })
    }

    @Test
    fun `in progress means a position and not watched`() {
        assertTrue(Watched.inProgress(played(1, SUNDAY)))
        assertFalse(Watched.inProgress(WatchInfo(0, 7_200_000, false, SUNDAY)))
        assertFalse(Watched.inProgress(watched(SUNDAY)))
        assertFalse(Watched.inProgress(null))
    }

    private companion object {
        val SATURDAY_EVENING: Instant = at("2026-11-21T20:41")
        val SUNDAY: Instant = at("2026-11-22T20:41")
        val MONDAY: Instant = at("2026-11-23T09:00")
    }
}
