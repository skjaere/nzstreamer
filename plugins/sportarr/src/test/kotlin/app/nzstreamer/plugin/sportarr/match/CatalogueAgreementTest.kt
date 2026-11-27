package app.nzstreamer.plugin.sportarr.match

import app.nzstreamer.plugin.sportarr.family.Catalogue
import app.nzstreamer.plugin.sportarr.family.CatalogueAt
import app.nzstreamer.plugin.sportarr.family.Matchers
import app.nzstreamer.plugin.sportarr.model.Family
import app.nzstreamer.plugin.sportarr.model.FamilyFrom
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sports catalogue's verdicts (L655, L647): every release row of mock-seed's sports-catalogue.json, read from the
 * tests' classpath so a change to it runs this again, gets the verdict, reason and kind the row writes, from the matcher
 * its event's league gives, through the shared rules as the runner judges. The same rows say what the sports indexer
 * lists and the device classes expect, and the fakes start on any day, so they hold whatever the anchor.
 */
class CatalogueAgreementTest {
    /** Each row that [catalogue] judges otherwise than it writes, worded as the row is; none for a catalogue that agrees. */
    private fun disagreements(catalogue: CatalogueAt): List<String> {
        val matchers = Matchers(catalogue.sports)
        return catalogue.rows.map { (release, row) ->
            // A row for an event the list leaves out would be judged by no family at all.
            if (catalogue.sports.events.none { it.id == row.eventId }) return@map "${release.id}: event ${row.eventId} isn't listed"
            catalogue.disagreement(matchers.of(catalogue.event(row.eventId)), release, row)
        }.filterNotNull()
    }

    @Test
    fun `every release row gets the verdict, reason and kind it writes, from its event's family`() {
        val wrong = disagreements(Catalogue)
        assertEquals(emptyList(), wrong, wrong.joinToString("\n"))
        // Every family's rules hold rows here, and every verdict: no family is left to its own tests alone.
        val judged = Catalogue.rows.map { (_, row) -> Catalogue.event(row.eventId).family to row.verdict }
        assertEquals(Family.entries.toSet(), judged.map { it.first }.toSet())
        Family.entries.filter { it != Family.Generic }.forEach { family ->
            assertEquals(3, judged.filter { it.first == family }.map { it.second }.distinct().size, "$family accepts, offers and leaves out")
        }
        assertTrue(Catalogue.rows.size >= Catalogue.releases.size)
    }

    @Test
    fun `the fight leagues are fight cards by Combat, by the older Fighting and by part names under a word of no family`() {
        // As Sportarr 4.1.12 names them, one league as a server before it does, and one whose sport no family lists.
        val fights = Catalogue.sports.events.filter { it.family == Family.FightCard }
            .groupBy({ it.league.sport.orEmpty() }, { it.familyFrom }).mapValues { (_, from) -> from.toSet() }
        assertEquals(
            mapOf("Combat" to setOf(FamilyFrom.LeagueSport), "Fighting" to setOf(FamilyFrom.LeagueSport), "Exhibition" to setOf(FamilyFrom.PartNames)),
            fights,
        )
        assertEquals(setOf("UFC", "Velmora Wrestling", "Kestrel Boxing"), Catalogue.sports.leagues.filter { it.sport == "Combat" }.map { it.name }.toSet())
    }

    @Test
    fun `the rows hold whatever the anchor - every day of a year and a month, a new year and a 29 February`() {
        val wrong = generateSequence(LocalDate.of(2027, 12, 1)) { it.plusDays(1) }.takeWhile { it.year < 2029 }
            .flatMap { anchor -> disagreements(CatalogueAt(anchor)).map { "at $anchor: $it" } }.toList()
        assertEquals(emptyList(), wrong.take(20), "${wrong.size} rows disagree:\n" + wrong.take(20).joinToString("\n"))
    }
}
