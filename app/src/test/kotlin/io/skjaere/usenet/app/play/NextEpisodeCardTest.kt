package io.skjaere.usenet.app.play

import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    /** L354: a next episode on this device says so, with no search; one that can't play offline says why. */
    @Test
    fun `the third line says when the next episode is downloaded, on its way or not downloaded while offline`() {
        assertEquals("Downloaded · ready", prefetchLine(Prefetch.Downloaded(partsMissing = 0)))
        assertEquals("Downloaded · 1 part missing", prefetchLine(Prefetch.Downloaded(partsMissing = 1)))
        assertEquals("Downloaded · 2 parts missing", prefetchLine(Prefetch.Downloaded(partsMissing = 2)))
        assertEquals("Not downloaded · offline", prefetchLine(Prefetch.NotDownloadedOffline))
        assertEquals("Still downloading · 42%", prefetchLine(Prefetch.StillDownloading(42)))
    }

    /** L354: a downloaded next episode counts down and plays as a release found does; what can't play offline offers Close alone. */
    @Test
    fun `the card offers Play now for what can play, and Close alone for what can't offline`() {
        val downloaded = AutoAdvance.Card(secondsLeft = 8, progress = 0.8f, prefetch = Prefetch.Downloaded(0))
        assertTrue(offersPlayNow(downloaded))
        assertEquals("Stop", stopLabel(downloaded))
        listOf(Prefetch.NotDownloadedOffline, Prefetch.StillDownloading(42)).forEach { status ->
            val card = AutoAdvance.Card(secondsLeft = null, progress = null, prefetch = status)
            assertFalse(offersPlayNow(card), "$status")
            assertEquals("Close", stopLabel(card))
        }
        // As before: nothing found, or nothing held, still offers Play now, which searches.
        assertTrue(offersPlayNow(AutoAdvance.Card(null, null, Prefetch.NothingFound)))
        assertTrue(offersPlayNow(AutoAdvance.Card(null, null, null)))
    }
}
