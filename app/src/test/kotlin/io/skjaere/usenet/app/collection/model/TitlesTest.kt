package io.skjaere.usenet.app.collection.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TitlesTest {
    @Test
    fun `a show's and a movie's keys read back, and say which they are`() {
        val show = TitleKey.show(123)
        val movie = TitleKey.movie(456)
        assertEquals("show:123", show.value)
        assertEquals("movie:456", movie.value)
        assertEquals(show, TitleKey.parse("show:123"))
        assertEquals(movie, TitleKey.parse("movie:456"))
        assertTrue(show.isShow)
        assertFalse(movie.isShow)
        assertEquals(123 to 456, show.tmdbId to movie.tmdbId)
    }

    @Test
    fun `anything else is no key`() {
        listOf("", "show:", "movie:x", "episode:tmdb:1:s1:e1", "tmdb:5", "show 5", "Show:5").forEach { assertNull(TitleKey.parse(it), it) }
    }

    @Test
    fun `the side's name reads back, and one it doesn't know is none`() {
        assertEquals(Side.Movies, Side.byName("Movies"))
        assertNull(Side.byName("Archive"))
    }
}
