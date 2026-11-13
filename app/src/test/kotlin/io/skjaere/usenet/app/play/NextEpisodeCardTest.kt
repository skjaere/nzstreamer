package io.skjaere.usenet.app.play

import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals

class NextEpisodeCardTest {
    @Test
    fun `the third line says how the search for the next episode's release is going`() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertEquals("Looking for a release…", prefetchLine(Prefetch.Searching))
            assertEquals("Release found · 2.2 GB · ready", prefetchLine(Prefetch.Ready("Harbor.Lights.S02E06.1080p.WEB-DL", 2_200_000_000)))
            // Some indexers report no size.
            assertEquals("Release found · ready", prefetchLine(Prefetch.Ready("Harbor.Lights.S02E06.1080p.WEB-DL", 0)))
            // In the season pack playing, which names no file for it: the play asks which it is, and it may not be there.
            assertEquals("May be in this release · you choose its file", prefetchLine(Prefetch.Ready("Harbor.Lights.S02.720p.WEB.h264-GRP", 0, choose = true)))
            assertEquals("No release found yet", prefetchLine(Prefetch.NothingFound))
            // Nothing searches now, and what was found is no longer held (or an indexer failed).
            assertEquals("Searches again on play", prefetchLine(null))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `the card offers Stop while the next episode will start by itself, Close otherwise`() {
        val ready = Prefetch.Ready("Harbor.Lights.S02E06.1080p.WEB-DL", 2_200_000_000)
        assertEquals("Stop", stopLabel(AutoAdvance.Card(secondsLeft = 8, progress = 0.8f, prefetch = ready)))
        // From the start of the credits, before the countdown.
        assertEquals("Stop", stopLabel(AutoAdvance.Card(secondsLeft = null, progress = null, prefetch = ready, autoPlays = true)))
        assertEquals("Close", stopLabel(AutoAdvance.Card(secondsLeft = null, progress = null, prefetch = ready)))
    }
}
