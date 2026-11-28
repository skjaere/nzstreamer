package app.nzstreamer.plugin.sportarr.bouts

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * L-B4's rules (W6): a name's surname and full keys; every pair in one total order, so either way round reads the same;
 * the main event alone in the title's order, and only where the title names both fighters, one on each side.
 */
class PairsTest {
    /** [pair] ordered from both ways round: the same two strings either way, or the test fails. */
    private fun ordered(a: String, b: String): Pair<String, String> {
        val one = Pairs.ordered(a, b)
        assertEquals(one, Pairs.ordered(b, a), "$a, $b")
        return one
    }

    private fun main(a: String, b: String, title: String): Pair<String, String> {
        val one = Pairs.mainEvent(a, b, title)
        assertEquals(one, Pairs.mainEvent(b, a, title), "$a, $b under $title")
        return one
    }

    @Test
    fun `a name's marks are taken off`() {
        assertEquals("Quessel", Pairs.name("Quessel (c)"))
        assertEquals("Halde", Pairs.name(" Halde (ic) "))
        assertEquals("Brisk", Pairs.name("(C) Brisk"))
        assertEquals("Elsvin Marhoff", Pairs.name("Elsvin  Marhoff"))
    }

    @Test
    fun `a surname key is the last word but a Jr, Sr or numeral, folded`() {
        assertEquals("BRISK", Pairs.surnameKey("Elsvin Brisk Jr."))
        assertEquals("BRISK", Pairs.surnameKey("Elsvin Brisk jr"))
        assertEquals("LOAM", Pairs.surnameKey("Loam III"))
        assertEquals("LOAM", Pairs.surnameKey("Brannoc Loam Sr. II"))
        assertEquals("HALDE", Pairs.surnameKey("Halde IV"))
        assertEquals("QUESSEL", Pairs.surnameKey("Quéssel"))
        assertEquals(Pairs.surnameKey("Quessel"), Pairs.surnameKey("QUÉSSEL"))
        assertEquals("OSTREM", Pairs.surnameKey("Ostrem-"))
        assertEquals("JR", Pairs.surnameKey("Jr."))
        assertEquals("ELSVIN MARHOFF JR", Pairs.fullKey("Elsvin  Marhöff Jr."))
    }

    @Test
    fun `pairs order by surname, then the whole name, then the text, whichever way they come`() {
        assertEquals("Ambrel" to "Savrin", ordered("Savrin", "Ambrel"))
        assertEquals("Halde Brisk" to "Loam Brisk", ordered("Loam Brisk", "Halde Brisk"))
        assertEquals("Brannoc Marhoff" to "Elsvin Marhoff", ordered("Elsvin Marhoff", "Brannoc Marhoff"))
        assertEquals("Quessel" to "Quéssel", ordered("Quéssel", "Quessel"))
        assertEquals("Brisk" to "Brisk", ordered("Brisk", "Brisk"))
        assertEquals("Brisk Jr." to "Loam", ordered("Loam", "Brisk Jr."))
    }

    @Test
    fun `the main event follows a title naming both fighters, against the alphabet`() {
        assertEquals("Quessel" to "Halde", main("Halde", "Quessel", "UFC 999: Quessel vs. Halde"))
        assertEquals("Quessel" to "Halde", main("Halde", "Quessel", "UFC 999: Quéssel vs. Halde"))
        assertEquals("Loam" to "Brisk", main("Brisk", "Loam", "UFC Fight Night: Loam vs. Brisk"))
        assertEquals("Elsvin Brisk Jr." to "Halde", main("Halde", "Elsvin Brisk Jr.", "UFC 999: Brisk vs. Halde"))
    }

    @Test
    fun `a title naming one fighter, or none, leaves the alphabet's order`() {
        assertEquals("Halde" to "Quessel", main("Quessel", "Halde", "UFC 999: Quessel vs. Brisk"))
        assertEquals("Halde" to "Quessel", main("Quessel", "Halde", "UFC 999"))
        assertEquals("Halde" to "Quessel", main("Quessel", "Halde", "UFC 999: Brisk vs. Loam"))
        assertEquals("Halde" to "Quessel", main("Quessel", "Halde", "UFC Fight Night 888"))
    }

    @Test
    fun `a shared surname, or a side naming both, leaves the alphabet's order`() {
        assertEquals("Brannoc Marhoff" to "Elsvin Marhoff", main("Elsvin Marhoff", "Brannoc Marhoff", "UFC 999: Marhoff vs. Marhoff"))
        assertEquals("Brannoc Marhoff" to "Elsvin Marhoff", main("Elsvin Marhoff", "Brannoc Marhoff", "UFC 999: E. Marhoff vs. B. Marhoff"))
        assertEquals("Halde" to "Quessel", main("Quessel", "Halde", "UFC 999: Quessel and Halde vs. Brisk"))
        assertEquals("Halde" to "Quessel", main("Quessel", "Halde", "UFC 999: Quessel vs. Halde and Quessel"))
    }
}
