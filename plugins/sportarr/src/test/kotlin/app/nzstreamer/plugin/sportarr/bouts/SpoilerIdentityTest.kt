package app.nzstreamer.plugin.sportarr.bouts

import app.nzstreamer.plugin.sportarr.bouts.Fixtures.NUMBERED
import app.nzstreamer.plugin.sportarr.bouts.Fixtures.UFC_999_CARD
import app.nzstreamer.plugin.sportarr.bouts.Fixtures.UFC_999_TITLE
import app.nzstreamer.plugin.sportarr.bouts.Fixtures.article
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * W6, as L-B13 splits it, the reader's part: one card as its pre-event table and as its results table (every winner moved
 * left with "def.", methods, rounds, times, a deduction note, a note beginning "For the first time", a draw, a no contest
 * and a pair who share a surname) reads as one and the same card, and so encodes as the same JSON; and so does every way
 * of listing its thirteen pairs, each either way round. Nothing of a result is in any field.
 */
class SpoilerIdentityTest {
    private fun read(page: String): BoutCard = assertIs<BoutTable.Read.Out>(BoutTable.read(page, "UFC 999", UFC_999_TITLE, NUMBERED)).card

    private val json = Json

    @Test
    fun `the pre-event and the results tables read as one card, encoded alike`() {
        val before = read(article(form = Form.Pre))
        val after = read(article(form = Form.Results, heading = Heading.Legacy))
        assertEquals(UFC_999_CARD, before)
        assertEquals(before, after)
        assertEquals(json.encodeToString(BoutCard.serializer(), before), json.encodeToString(BoutCard.serializer(), after))
        assertTrue(before.parts.flatMap { it.bouts }.any { it.first == "Brannoc Marhoff" && it.second == "Elsvin Marhoff" }, "the pair who share a surname")
        assertEquals(13, before.parts.sumOf { it.bouts.size })
    }

    @Test
    fun `every one of the 8,192 ways of listing the thirteen pairs reads as the same card, in both forms`() {
        val masks = 0L until (1L shl 13)
        Form.entries.forEach { form ->
            masks.forEach { swaps -> assertEquals(UFC_999_CARD, read(article(form = form, swaps = swaps)), "$form, $swaps") }
        }
    }

    @Test
    fun `no field and nothing encoded holds a separator, a method, a round, a time or a note`() {
        val card = read(article(form = Form.Results))
        val encoded = json.encodeToString(BoutCard.serializer(), card)
        Fixtures.RESULT_WORDS.forEach { word -> assertFalse(word in encoded, "\"$word\" in $encoded") }
        assertFalse(Regex("""\d:\d\d""").containsMatchIn(encoded), encoded)
        val fields = card.parts.flatMap { it.bouts }.flatMap { listOf(it.weight, it.first, it.second) }
        fields.forEach { field -> assertFalse(field.any(Char::isDigit), "a round's number in \"$field\"") }
        assertEquals(setOf("Lightweight", "Bantamweight"), card.parts.flatMap { it.bouts }.filter { it.titleFight }.map { it.weight }.toSet())
    }
}
