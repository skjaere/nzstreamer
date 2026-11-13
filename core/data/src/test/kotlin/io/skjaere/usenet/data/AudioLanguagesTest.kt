package io.skjaere.usenet.data

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

// The settings rows are rendered from one list and their edits applied later to the stored one, which
// a tap that lands before recomposition (after a removal, or a second tap) has already changed.
class AudioLanguagesTest {
    private val languages = listOf("de", "en", "fr")

    @Test
    fun `moving up swaps the language with the one above`() {
        assertEquals(listOf("de", "fr", "en"), AudioLanguages.moveUp(languages, "fr"))
    }

    @Test
    fun `moving up a row rendered before a removal above it moves that language`() {
        // Rows [de, en, fr]: ✕ on en, then ↑ on fr (the third row) before the list re-renders.
        assertEquals(listOf("fr", "de"), AudioLanguages.moveUp(listOf("de", "fr"), "fr"))
    }

    @Test
    fun `moving up a language that was removed meanwhile changes nothing`() {
        // Rows [de, en, fr]: ✕ on en, then ↑ on en (the second row) before the list re-renders.
        assertEquals(listOf("de", "fr"), AudioLanguages.moveUp(listOf("de", "fr"), "en"))
    }

    @Test
    fun `moving up the first language changes nothing`() {
        assertEquals(languages, AudioLanguages.moveUp(languages, "de"))
        // Rows [de, en, fr]: ✕ on de, then ↑ on en (the second row), which is now first.
        assertEquals(listOf("en", "fr"), AudioLanguages.moveUp(listOf("en", "fr"), "en"))
    }

    @Test
    fun `each tap moves the language one more place up`() {
        // Rows [de, en, fr]: ↑ on fr twice before the list re-renders.
        assertEquals(listOf("fr", "de", "en"), AudioLanguages.moveUp(AudioLanguages.moveUp(languages, "fr"), "fr"))
    }

    @Test
    fun `removing drops just that language`() {
        assertEquals(listOf("de", "fr"), AudioLanguages.remove(languages, "en"))
        assertEquals(listOf("de", "fr"), AudioLanguages.remove(listOf("de", "fr"), "en"))
    }

    @Test
    fun `removing the only language left changes nothing`() {
        // Rows [de, en]: ✕ on de, then ✕ on en before the list re-renders without its ✕ buttons.
        assertEquals(listOf("en"), AudioLanguages.remove(listOf("en"), "en"))
    }
}
