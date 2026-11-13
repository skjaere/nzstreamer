package io.skjaere.usenet.data

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals

class WatchTitleTest {
    @Test
    fun `episode title is the show name and the season-episode tag`() {
        assertEquals("Harbor Lights S01E02", WatchTitle.episode("Harbor Lights", 1, 2))
        assertEquals("Harbor Lights S12E105", WatchTitle.episode("Harbor Lights", 12, 105))
    }

    // TMDB names are not format strings: '% H' and '% B' are a flag clash, '% W' an unknown conversion
    // and '%s' takes the season's argument.
    @ParameterizedTest
    @ValueSource(strings = ["100% Hotter", "100% Wolf: Legend of the Moonstone", "A % B", "The %s Show", "50%"])
    fun `a percent sign in the show name is kept literally`(name: String) {
        assertEquals("$name S01E02", WatchTitle.episode(name, 1, 2))
    }
}
