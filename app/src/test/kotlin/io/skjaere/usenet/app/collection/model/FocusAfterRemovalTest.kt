package io.skjaere.usenet.app.collection.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FocusAfterRemovalTest {
    private val k = (1..6).map { TitleKey.movie(it) }

    @Test
    fun `focus goes to the row after the last one removed`() {
        assertEquals(k[3], focusAfterRemoval(k, setOf(k[1], k[2])))
    }

    @Test
    fun `at the end, to the row before`() {
        assertEquals(k[3], focusAfterRemoval(k, setOf(k[4], k[5])))
    }

    @Test
    fun `rows removed here and there, after the last of them`() {
        assertEquals(k[5], focusAfterRemoval(k, setOf(k[0], k[2], k[4])))
        assertEquals(k[3], focusAfterRemoval(k, setOf(k[0], k[4], k[5], k[2])))
    }

    @Test
    fun `all removed, nowhere`() {
        assertNull(focusAfterRemoval(k, k.toSet()))
        assertNull(focusAfterRemoval(emptyList(), emptySet()))
    }
}
