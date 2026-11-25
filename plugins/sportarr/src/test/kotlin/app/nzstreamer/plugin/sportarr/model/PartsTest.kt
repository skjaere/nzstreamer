package app.nzstreamer.plugin.sportarr.model

import app.nzstreamer.plugin.sportarr.source.KeptLeague
import app.nzstreamer.plugin.sportarr.source.WireEvent
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals

/**
 * L644's parts: one, `full`, for a game, a session, a matchup, a weekly show and a Generic event; a numbered card's early
 * prelims, prelims and main card, a fight night's prelims and main card, a premium event's pre-show and main show. Sportarr's
 * part names (fighting sports alone) are taken, in its order and words, only where every name that maps fits the card's
 * type and its main part is among them; otherwise the type's own list. Ids never come from a list that doesn't fit.
 */
class PartsTest {
    private val numberedList = listOf("Early Prelims", "Prelims", "Main Card", "Post Show")
    private val fightNightList = listOf("Prelims", "Main Card")

    /** The parts of [title] in [league], as (id, label), with [others] of the league listed beside it. */
    private fun parts(league: KeptLeague, title: String, names: List<String>?, others: List<WireEvent> = emptyList()): List<Pair<String, String>> =
        sports(league to listOf(on(league, 1, at("2026-11-21T23:00"), title, parts = names)) + others)
            .events.single { it.id == 1 }.parts.map { it.id to it.label }

    /** Velmora Monday Brawl the two weeks before, so the one on the 23rd is a weekly show. */
    private val earlierBrawls = listOf(
        on(VELMORA, 2, at("2026-11-21T23:00").minus(Duration.ofDays(7)), "Velmora Monday Brawl"),
        on(VELMORA, 3, at("2026-11-21T23:00").minus(Duration.ofDays(14)), "Velmora Monday Brawl"),
    )

    @Test
    fun `each card type has its own parts and labels where Sportarr names none`() {
        assertEquals(
            listOf("early-prelims" to "Early Prelims", "prelims" to "Prelims", "main-card" to "Main Card"),
            parts(UFC, "UFC 999: Quessel vs. Halde", null),
        )
        assertEquals(listOf("prelims" to "Prelims", "main-card" to "Main Card"), parts(UFC, "UFC Fight Night: Brisk vs. Loam", null))
        assertEquals(listOf("pre-show" to "Pre-Show", "main-show" to "Main Show"), parts(VELMORA, "Velmora Iron Crown", null))
        assertEquals(listOf("full"), parts(VELMORA, "Velmora Monday Brawl", null, earlierBrawls).map { it.first })
        assertEquals(listOf("full"), parts(BOXING, "Quessel vs. Halde", null).map { it.first })
    }

    @Test
    fun `Sportarr's names come in its order and words, a lead card as the prelims and a post show as no part`() {
        assertEquals(
            listOf("early-prelims" to "Early Prelims", "prelims" to "Prelims", "main-card" to "Main Card"),
            parts(UFC, "UFC 999: Quessel vs. Halde", numberedList),
        )
        assertEquals(
            listOf("early-prelims" to "Early Prelims", "prelims" to "Lead Card", "main-card" to "Main Card"),
            parts(UFC, "UFC 999: Quessel vs. Halde", listOf("Early Prelims", "Lead Card", "Main Card", "Post Show")),
        )
        assertEquals(
            listOf("prelims" to "Preliminary Card", "main-card" to "Main Event"),
            parts(UFC, "UFC 999: Quessel vs. Halde", listOf("Preliminary Card", "Main Event")),
        )
        // Sportarr's order, whatever it is, and its words as sent, read whatever their case, hyphens and spaces.
        assertEquals(
            listOf("main-card" to "MAIN CARD", "early-prelims" to "early-prelims", "prelims" to "Prelims"),
            parts(UFC, "UFC 999: Quessel vs. Halde", listOf(" MAIN CARD ", "early-prelims", "Prelims")),
        )
        assertEquals(listOf("prelims" to "Prelims", "main-card" to "Main Card"), parts(UFC, "UFC Fight Night: Brisk vs. Loam", fightNightList))
    }

    @Test
    fun `a matchup and a weekly show are one part, whatever Sportarr sends`() {
        assertEquals(listOf("full"), parts(BOXING, "Quessel vs. Halde", numberedList).map { it.first })
        assertEquals(listOf("full"), parts(BOXING, "Quessel vs. Halde", fightNightList).map { it.first })
        assertEquals(listOf("full"), parts(VELMORA, "Velmora Monday Brawl", numberedList, earlierBrawls).map { it.first })
    }

    @Test
    fun `a premium event's Countdown and Main Show are its pre-show and main show, with those labels`() {
        assertEquals(
            listOf("pre-show" to "Countdown", "main-show" to "Main Show"),
            parts(VELMORA, "Velmora Iron Crown: Night 2", listOf("Countdown", "Main Show")),
        )
        listOf("Pre-Show", "pre show", "Kickoff", "Zero Hour", "Buy-In").forEach { name ->
            assertEquals(listOf("pre-show" to name, "main-show" to "Main Show"), parts(VELMORA, "Velmora Iron Crown", listOf(name, "Main Show")), name)
        }
    }

    @Test
    fun `a list that doesn't fit the card's type gives the type's own`() {
        val premium = listOf("pre-show" to "Pre-Show", "main-show" to "Main Show")
        val fightNight = listOf("prelims" to "Prelims", "main-card" to "Main Card")
        val numbered = listOf("early-prelims" to "Early Prelims", "prelims" to "Prelims", "main-card" to "Main Card")

        assertEquals(premium, parts(VELMORA, "Velmora Iron Crown", numberedList), "a premium event with the numbered card's list")
        assertEquals(premium, parts(VELMORA, "Velmora Iron Crown", fightNightList), "a premium event with the fight night's list")
        assertEquals(fightNight, parts(UFC, "UFC Fight Night: Brisk vs. Loam", numberedList), "a fight night with the numbered card's list")
        assertEquals(numbered, parts(UFC, "UFC 999: Quessel vs. Halde", listOf("Early Prelims", "Prelims")), "no main card")
        assertEquals(numbered, parts(UFC, "UFC 999: Quessel vs. Halde", listOf("Countdown", "Main Card")), "a pre-show on a numbered card")
        assertEquals(numbered, parts(UFC, "UFC 999: Quessel vs. Halde", listOf("Prelims", "Lead Card", "Main Card")), "two names for one part")
        assertEquals(numbered, parts(UFC, "UFC 999: Quessel vs. Halde", listOf("Post Show")), "no name that maps")
        assertEquals(numbered, parts(UFC, "UFC 999: Quessel vs. Halde", listOf("Undercard", "Headliner")), "no name that maps")
        assertEquals(numbered, parts(UFC, "UFC 999: Quessel vs. Halde", emptyList()), "[]")
        assertEquals(numbered, parts(UFC, "UFC 999: Quessel vs. Halde", null), "null")
    }

    @Test
    fun `the headline part is the main card, the main show, else the whole event`() {
        val sports = sports(
            UFC to listOf(ufc999(), on(UFC, 32, at("2026-11-14T23:00"), "UFC Fight Night: Brisk vs. Loam")),
            VELMORA to listOf(on(VELMORA, 33, at("2026-11-28T23:00"), "Velmora Iron Crown: Night 2", parts = listOf("Countdown", "Main Show"))),
            BOXING to listOf(on(BOXING, 34, at("2026-11-21T22:00"), "Quessel vs. Halde")),
            PREMIER to listOf(on(PREMIER, 35, at("2026-11-21T15:00"), "Lantern Bay FC vs Copper Crown United")),
        )
        assertEquals(mapOf(31 to "main-card", 32 to "main-card", 33 to "main-show", 34 to "full", 35 to "full"), sports.events.associate { it.id to it.headline.id })
    }

    @Test
    fun `games, sessions and Generic events are one part, a session labelled by its words`() {
        val sports = sports(
            PREMIER to listOf(on(PREMIER, 1, at("2026-11-21T15:00"), "Lantern Bay FC vs Copper Crown United")),
            FORMULA_1 to kestrelWeekend(firstId = 10),
            SKIING to listOf(on(SKIING, 2, at("2026-11-22T10:30"), "Kestrel · Men's Downhill")),
        )
        assertEquals(listOf("full" to "Lantern Bay FC vs Copper Crown United"), sports.events.single { it.id == 1 }.parts.map { it.id to it.label })
        assertEquals(listOf("full" to "Practice 1"), sports.events.single { it.id == 10 }.parts.map { it.id to it.label })
        assertEquals(listOf("full" to "Race"), sports.events.single { it.id == 14 }.parts.map { it.id to it.label })
        val generic = sports.events.single { it.id == 2 }
        assertEquals(Family.Generic, generic.family)
        assertEquals(listOf("full"), generic.parts.map { it.id })
    }
}
