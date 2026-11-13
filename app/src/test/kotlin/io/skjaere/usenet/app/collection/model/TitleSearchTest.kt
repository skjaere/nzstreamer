package io.skjaere.usenet.app.collection.model

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TitleSearchTest {
    private fun finds(text: TitleText, query: String) = text.matches(TitleSearch.tokens(query))

    private val orbit = TitleText("The Quiet Orbit", null, 2024)

    @Test
    fun `the title, the original title and the year are searched`() {
        assertTrue(finds(orbit, "quiet"))
        assertTrue(finds(orbit, "2024"))
        assertTrue(finds(TitleText("Copper Crown", "Kopparkronan", 2019), "koppar"))
        assertTrue(finds(orbit, ""))
    }

    @Test
    fun `several words must each start a word of the title, in any order`() {
        assertTrue(finds(orbit, "orb 2024"))
        assertTrue(finds(orbit, "Orbit  the"))
        assertFalse(finds(orbit, "orb 2023"))
        assertFalse(finds(orbit, "rbit"))
        assertFalse(finds(orbit, "uiet"))
    }

    @Test
    fun `case, accents and punctuation don't matter`() {
        val ebrin = TitleText("Ébrin's Hollow: Part Two", null, null)
        assertTrue(finds(ebrin, "ebrins"))
        assertTrue(finds(ebrin, "ÉBRIN"))
        assertTrue(finds(ebrin, "hollow part"))
        assertTrue(finds(TitleText("Salt & Signal", null, null), "salt signal"))
        assertTrue(finds(TitleText("Salt & Signal", null, null), "signal!"))
    }

    @Test
    fun `folding ASCII on the fast path gives what the full path gives`() {
        val random = Random(5)
        val ascii = (32..126).map { it.toChar() }
        repeat(2_000) {
            val text = String(CharArray(random.nextInt(0, 30)) { ascii.random(random) })
            assertEquals(TitleSearch.foldSlowly(text), TitleSearch.fold(text), "for \"$text\"")
        }
        assertEquals("ebrin hollow", TitleSearch.fold("  Ébrin—Hollow!  "))
    }
}
