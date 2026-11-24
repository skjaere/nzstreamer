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

    /** A plugin's item is kept under the plugin's id and its own key, so two plugins' keys never meet. */
    @Test
    fun `a plugin item's watch key is item, the plugin's id and its key`() {
        assertEquals("item:skeleton:ev-000731:main-card", WatchKey.item("skeleton", "ev-000731:main-card"))
        assertEquals("item:kestrel:local:12:full", WatchKey.item("kestrel", "local:12:full"))
    }
}
