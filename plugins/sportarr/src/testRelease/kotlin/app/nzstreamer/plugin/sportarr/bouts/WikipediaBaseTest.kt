package app.nzstreamer.plugin.sportarr.bouts

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** The released plugin reads English Wikipedia itself, never a fake (L-B12). */
class WikipediaBaseTest {
    @Test
    fun `the release build reads English Wikipedia`() {
        assertEquals("https://en.wikipedia.org", WikipediaBase.URL)
        assertEquals("https://en.wikipedia.org/wiki/UFC_999", PageFinder.address(WikipediaBase.URL, "UFC 999"))
    }
}
