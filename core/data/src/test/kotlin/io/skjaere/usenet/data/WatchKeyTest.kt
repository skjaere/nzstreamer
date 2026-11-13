package io.skjaere.usenet.data

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class WatchKeyTest {
    /** A movie in the collection finds its progress by key, so history from before it was added shows. */
    @Test
    fun `a collected movie's watch key is movie colon its id`() {
        assertEquals("movie:" + CollectedMovie.idFor(900011), WatchKey.movie(900011))
        assertEquals("tmdb:900011", CollectedMovie.idFor(900011))
    }
}
